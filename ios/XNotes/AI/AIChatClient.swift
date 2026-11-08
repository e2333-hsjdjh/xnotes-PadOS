import Foundation

enum AIStreamEvent: Equatable {
    case text(String), thinking, thinkingFallback
}

enum AIStreamFinish { case completed, limited, filtered }

struct AIChatMessage {
    let role: String
    let text: String
    var images: [AIImageAttachment] = []
}

struct AICompletionRequest {
    let provider: AIProvider
    let model: String
    let effort: AIThinkingEffort
    let maxTokens: Int
    let instructions: String
    let messages: [AIChatMessage]

    func urlRequest(includeThinking: Bool = true) throws -> URLRequest {
        try provider.validate()
        guard provider.models.contains(model) else { throw AIChatError.chooseModel }
        guard !messages.isEmpty, messages.count <= 40 else { throw AIChatError.contextLimit }
        guard (256...131072).contains(maxTokens), instructions.utf16.count <= 4000 else { throw AISettingsError.invalidAnswerSettings }
        var request = URLRequest(url: try AIEndpoints.completion(provider.endpoint))
        request.httpMethod = "POST"
        request.timeoutInterval = 60
        request.setValue("Bearer " + provider.key, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        var context: [[String: Any]] = try messages.map { message in
            guard message.images.count <= 6 else { throw AIImageError.limit }
            for image in message.images { try image.validate() }
            if message.images.isEmpty { return ["role": message.role, "content": message.text] }
            let parts: [[String: Any]] = [["type": "text", "text": message.text]] + message.images.map {
                ["type": "image_url", "image_url": ["url": "data:image/png;base64," + $0.png.base64EncodedString()]]
            }
            return ["role": message.role, "content": parts]
        }
        if !instructions.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            context.insert(["role": "system", "content": instructions], at: 0)
        }
        let tokenField = request.url?.host?.lowercased() == "api.openai.com" ? "max_completion_tokens" : "max_tokens"
        var body: [String: Any] = ["model": model, "stream": true, tokenField: maxTokens, "messages": context]
        if includeThinking && effort != .providerDefault {
            let enabled = effort != .off
            let format = AIThinkingPolicy.format(for: provider)
            switch format {
            case .deepSeek: body["thinking"] = ["type": enabled ? "enabled" : "disabled"]
            case .qwen: body["enable_thinking"] = enabled
            case .openRouter: body["reasoning"] = enabled ? ["effort": effort.rawValue] : ["enabled": false]
            default: body["reasoning_effort"] = enabled ? effort.rawValue : "none"
            }
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return request
    }
}

/// Incremental SSE framing keeps truncated streams distinct from completed answers.
struct AIStreamDecoder {
    private var line = Data()
    private var payload: [String] = []
    private var rawBytes = 0
    private var skipLF = false
    private var firstLine = true
    private var answerUnits = 0
    private(set) var finish: AIStreamFinish?
    private(set) var done = false
    let maxTokens: Int

    mutating func append(_ byte: UInt8) throws -> [AIStreamEvent] {
        guard !done else { return [] }
        rawBytes += 1
        guard rawBytes <= max(4 * 1024 * 1024, maxTokens * 512) else { throw AIChatError.responseLimit }
        if skipLF {
            skipLF = false
            if byte == 10 { return [] }
        }
        if byte == 10 || byte == 13 {
            skipLF = byte == 13
            let bytes = line
            line.removeAll(keepingCapacity: true)
            guard var value = String(data: bytes, encoding: .utf8) else { throw AIChatError.invalidStream }
            if firstLine {
                firstLine = false
                if value.first == "\u{FEFF}" { value.removeFirst() }
            }
            if value.isEmpty { return try dispatch() }
            if value.hasPrefix("data:") {
                let content = value.dropFirst(5)
                payload.append(String(content.first == " " ? content.dropFirst() : content))
            }
        } else {
            guard line.count < max(1024 * 1024, maxTokens * 32) else { throw AIChatError.responseLimit }
            line.append(byte)
        }
        return []
    }

    mutating func end() throws -> [AIStreamEvent] {
        var events: [AIStreamEvent] = []
        if !line.isEmpty { events += try append(10) }
        events += try dispatch()
        return events
    }

    private mutating func dispatch() throws -> [AIStreamEvent] {
        guard !payload.isEmpty, !done else { return [] }
        let text = payload.joined(separator: "\n")
        payload.removeAll(keepingCapacity: true)
        if text == "[DONE]" { done = true; finish = finish ?? .completed; return [] }
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              object["error"] == nil, let choices = object["choices"] as? [[String: Any]] else { throw AIChatError.invalidStream }
        guard let first = choices.first else { return [] }
        let delta = first["delta"] as? [String: Any] ?? [:]
        if delta["tool_calls"] != nil { throw AIChatError.toolsUnavailable }
        var events: [AIStreamEvent] = []
        if let reasoning = delta["reasoning_content"] as? String, !reasoning.isEmpty { events.append(.thinking) }
        if let content = delta["content"] as? String, !content.isEmpty {
            guard finish == nil else { throw AIChatError.invalidStream }
            answerUnits += content.utf16.count
            guard answerUnits <= maxTokens * 8 else { throw AIChatError.responseLimit }
            events.append(.text(content))
        }
        if let reason = first["finish_reason"] as? String {
            switch reason {
            case "stop": finish = .completed
            case "length": finish = .limited
            case "content_filter": finish = .filtered
            default: throw AIChatError.toolsUnavailable
            }
        }
        return events
    }
}

enum AIChatClient {
    static func stream(_ input: AICompletionRequest, onEvent: @escaping @Sendable (AIStreamEvent) async throws -> Void,
                       configuration: URLSessionConfiguration = .ephemeral,
                       refusals: AIThinkingRefusals = .shared) async throws -> AIStreamFinish {
        try Task.checkCancellation()
        if await refusals.contains(input) {
            try await onEvent(.thinkingFallback)
            try Task.checkCancellation()
            return try await send(input, includeThinking: false, onEvent: onEvent, configuration: configuration)
        }
        do {
            return try await send(input, includeThinking: true, onEvent: onEvent, configuration: configuration)
        } catch let error as AIChatError where AIThinkingPolicy.isRefusal(error, input: input) {
            try Task.checkCancellation()
            await refusals.remember(input)
            try await onEvent(.thinkingFallback)
            try Task.checkCancellation()
            return try await send(input, includeThinking: false, onEvent: onEvent, configuration: configuration)
        }
    }

    private static func send(_ input: AICompletionRequest, includeThinking: Bool,
                             onEvent: @escaping @Sendable (AIStreamEvent) async throws -> Void,
                             configuration: URLSessionConfiguration) async throws -> AIStreamFinish {
        let request = try input.urlRequest(includeThinking: includeThinking)
        configuration.urlCache = nil
        configuration.urlCredentialStorage = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForResource = 600
        let session = URLSession(configuration: configuration, delegate: AINoRedirects(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        do {
            let (bytes, response) = try await session.bytes(for: request)
            guard let response = response as? HTTPURLResponse else { throw AIChatError.invalidStream }
            guard (200...299).contains(response.statusCode) else {
                var data = Data()
                do {
                    for try await byte in bytes {
                        try Task.checkCancellation()
                        guard data.count < AIProviderFailure.byteLimit else { break }
                        data.append(byte)
                    }
                } catch {
                    if Task.isCancelled || error is CancellationError { throw CancellationError() }
                    // Retain the known HTTP status if reading the error body fails.
                }
                try Task.checkCancellation()
                let detail = AIProviderFailure.detail(data, key: input.provider.key)
                if !detail.isEmpty { throw AIChatError.httpDetail(response.statusCode, detail) }
                throw AIChatError.http(response.statusCode)
            }
            var decoder = AIStreamDecoder(maxTokens: input.maxTokens)
            for try await byte in bytes {
                try Task.checkCancellation()
                for event in try decoder.append(byte) { try await onEvent(event) }
                if decoder.done { break }
            }
            for event in try decoder.end() { try await onEvent(event) }
            guard let finish = decoder.finish else { throw AIChatError.incompleteStream }
            return finish
        } catch is CancellationError { throw CancellationError() }
        catch let error as AIChatError { throw error }
        catch {
            if Task.isCancelled { throw CancellationError() }
            throw AIChatError.connection
        }
    }
}
