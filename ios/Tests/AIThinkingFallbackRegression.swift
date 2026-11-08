import Foundation

private actor FallbackCapture {
    var events: [AIStreamEvent] = []
    func append(_ event: AIStreamEvent) { events.append(event) }
}

private final class ThinkingHTTPStub: URLProtocol, @unchecked Sendable {
    struct Reply { let status: Int; let body: Data }
    static let lock = NSLock()
    static var replies: [Reply] = []
    static var requests: [URLRequest] = []
    static func configure(_ replies: [Reply]) {
        lock.lock(); defer { lock.unlock() }
        self.replies = replies; requests = []
    }
    static func captured() -> [URLRequest] { lock.lock(); defer { lock.unlock() }; return requests }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var captured = request
        if captured.httpBody == nil, let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var body = Data(), buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                precondition(count >= 0 && body.count <= 1024 * 1024)
                if count == 0 { break }
                body.append(contentsOf: buffer.prefix(count))
            }
            captured.httpBody = body
        }
        Self.lock.lock()
        let index = Self.requests.count
        Self.requests.append(captured)
        let reply = index < Self.replies.count ? Self.replies[index] : Reply(status: 500, body: Data())
        Self.lock.unlock()
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: reply.body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor enum AIThinkingFallbackRegression {
    static func run() async throws {
        let success = ThinkingHTTPStub.Reply(status: 200, body: Data("data: {\"choices\":[{\"delta\":{\"content\":\"Answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".utf8))
        func refusal(_ field: String, code: Int = 400) -> ThinkingHTTPStub.Reply {
            .init(status: code, body: try! JSONSerialization.data(withJSONObject: ["error": ["message": "Unsupported parameter: " + field]]))
        }
        func input(_ format: AIThinkingFormat, effort: AIThinkingEffort = .high, endpoint: String = "https://provider.example/v1") -> AICompletionRequest {
            .init(provider: AIProvider(endpoint: endpoint, key: "fake-thinking-key", models: ["chat"], thinkingFormat: format), model: "chat", effort: effort, maxTokens: 4096, instructions: "Custom prompt", messages: [.init(role: "user", text: "Keep this question")])
        }
        func json(_ request: URLRequest) throws -> [String: Any] { try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any] }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ThinkingHTTPStub.self]
        for (format, field) in [(AIThinkingFormat.openAI, "reasoning_effort"), (.deepSeek, "thinking"), (.openRouter, "reasoning"), (.qwen, "enable_thinking")] {
            let request = input(format)
            let cache = AIThinkingRefusals()
            let capture = FallbackCapture()
            ThinkingHTTPStub.configure([refusal(field.uppercased(), code: 422), success])
            let finish = try await AIChatClient.stream(request, onEvent: { await capture.append($0) }, configuration: configuration, refusals: cache)
            let events = await capture.events
            precondition(finish == .completed && events == [.thinkingFallback, .text("Answer")])
            let sent = ThinkingHTTPStub.captured()
            precondition(sent.count == 2 && sent.allSatisfy { $0.url == sent[0].url && $0.value(forHTTPHeaderField: "Authorization") == "Bearer fake-thinking-key" })
            var first = try json(sent[0]); let second = try json(sent[1])
            precondition(first[field] != nil && second[field] == nil)
            first.removeValue(forKey: field)
            precondition(NSDictionary(dictionary: first).isEqual(to: second))
            // Repeated requests in this app session skip the rejected setting, with the same notice.
            ThinkingHTTPStub.configure([success])
            let repeated = FallbackCapture()
            _ = try await AIChatClient.stream(request, onEvent: { await repeated.append($0) }, configuration: configuration, refusals: cache)
            let repeatedEvents = await repeated.events
            precondition(repeatedEvents == [.thinkingFallback, .text("Answer")])
            precondition(ThinkingHTTPStub.captured().count == 1)
            let cachedBody = try json(ThinkingHTTPStub.captured()[0])
            precondition(cachedBody[field] == nil)
            let other = AICompletionRequest(provider: request.provider, model: request.model, effort: .low, maxTokens: request.maxTokens, instructions: request.instructions, messages: request.messages)
            let isolated = await cache.contains(other)
            precondition(!isolated)
            var movedProvider = request.provider
            movedProvider.endpoint = "https://another.example/v1"
            let moved = AICompletionRequest(provider: movedProvider, model: request.model, effort: request.effort, maxTokens: request.maxTokens, instructions: request.instructions, messages: request.messages)
            let movedIsCached = await cache.contains(moved)
            precondition(!movedIsCached)
        }
        for (endpoint, expected) in [("https://api.deepseek.com/v1", "thinking"), ("https://openrouter.ai/api/v1", "reasoning"), ("https://dashscope.aliyuncs.com/compatible-mode/v1", "enable_thinking"), ("https://api.openai.com/v1", "reasoning_effort")] {
            precondition(AIThinkingPolicy.field(for: input(.automatic, endpoint: endpoint).provider) == expected)
        }
        // Never retry an auth/rate error, an unrelated parameter, or a request using provider defaults.
        for (request, reply) in [(input(.openAI), refusal("reasoning_effort", code: 401)), (input(.openAI), refusal("reasoning_effort", code: 429)), (input(.openAI), refusal("max_tokens")), (input(.openAI, effort: .providerDefault), refusal("reasoning_effort"))] {
            ThinkingHTTPStub.configure([reply, success])
            do { _ = try await AIChatClient.stream(request, onEvent: { _ in }, configuration: configuration, refusals: AIThinkingRefusals()); preconditionFailure("Ignored HTTP error") }
            catch is AIChatError {}
            precondition(ThinkingHTTPStub.captured().count == 1)
        }
        // Retry once only, and expose the second error if the provider still refuses.
        ThinkingHTTPStub.configure([refusal("reasoning_effort"), refusal("reasoning_effort"), success])
        do { _ = try await AIChatClient.stream(input(.openAI), onEvent: { _ in }, configuration: configuration, refusals: AIThinkingRefusals()); preconditionFailure("Retried indefinitely") }
        catch AIChatError.httpDetail(let code, _) { precondition(code == 400) }
        precondition(ThinkingHTTPStub.captured().count == 2)
        // Cancellation on the fallback transition must prevent the second network request.
        ThinkingHTTPStub.configure([refusal("reasoning_effort"), success])
        do {
            _ = try await AIChatClient.stream(input(.openAI), onEvent: { event in if event == .thinkingFallback { throw CancellationError() } }, configuration: configuration, refusals: AIThinkingRefusals())
            preconditionFailure("Ignored cancellation")
        } catch is CancellationError {}
        precondition(ThinkingHTTPStub.captured().count == 1)
        let partial = ThinkingHTTPStub.Reply(status: 200, body: Data("data: {\"choices\":[{\"delta\":{\"content\":\"Partial reasoning_effort\"}}]}\n\n".utf8))
        ThinkingHTTPStub.configure([partial, success])
        do { _ = try await AIChatClient.stream(input(.openAI), onEvent: { _ in }, configuration: configuration, refusals: AIThinkingRefusals()); preconditionFailure("Accepted truncated stream") }
        catch AIChatError.incompleteStream {}
        precondition(ThinkingHTTPStub.captured().count == 1)
        // The notice belongs to the originating turn and survives reopening, without rewriting defaults.
        let directory = URL(fileURLWithPath: CommandLine.arguments[1]).appending(path: "thinking-history-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let history = AIHistoryStore(directory: directory)
        let sessions = AISessions(history: history, transport: { _, event in
            try await event(.thinkingFallback)
            try await event(.text("Saved answer"))
            return .completed
        })
        let route = input(.openAI)
        var settings = AIConfiguration(providers: [route.provider], effort: .high)
        settings.reconcileDefault()
        let id = sessions.newConversation()
        sessions.updateDraft(id, text: "Question")
        try sessions.send(id, configuration: settings)
        for _ in 0..<200 where sessions.conversation(id)?.isRunning == true { try await Task.sleep(for: .milliseconds(10)) }
        let turn = sessions.conversation(id)!.turns.last!
        precondition(turn.thinkingDropped == true && turn.effort == .high && turn.status == .completed && settings.effort == .high)
        let reopened = AISessions(history: history).conversation(id)!.turns.last!
        precondition(reopened == turn)
        var legacy = try JSONSerialization.jsonObject(with: JSONEncoder().encode(turn)) as! [String: Any]
        legacy.removeValue(forKey: "thinkingDropped")
        let old = try JSONDecoder().decode(AITurn.self, from: JSONSerialization.data(withJSONObject: legacy))
        precondition(old.thinkingDropped == nil)
        print("PASS AI thinking refusal retry once, all formats, route cache, status guards, cancellation, partial streams and saved per-turn notice")
    }
}
