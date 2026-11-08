import Foundation

private actor StreamCapture {
    var events: [AIStreamEvent] = []
    func append(_ event: AIStreamEvent) { events.append(event) }
}

private final class ChatHTTPStub: URLProtocol, @unchecked Sendable {
    static var body = Data()
    static var status = 200
    static var received: URLRequest?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.received = request
        let response = HTTPURLResponse(url: request.url!, statusCode: Self.status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "text/event-stream"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        for byte in Self.body { client?.urlProtocol(self, didLoad: Data([byte])) }
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

enum AIChatRegression {
    @MainActor static func run() async throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1]).appending(path: "chat-tests-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let provider = AIProvider(name: "Test", endpoint: "https://provider.example/v1", key: "fake-chat-secret", models: ["chat"])
        var config = AIConfiguration(providers: [provider])
        config.reconcileDefault()
        let choice = config.defaultModel!
        let chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\\nworld\"},\"finish_reason\":null}]}\r\n\r\n"
        let ignored = "data: {\"choices\":[{\"delta\":{\"content\":null}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":4}}]}\n\n"
        let stop = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        var decoder = AIStreamDecoder(maxTokens: 4096)
        var output: [AIStreamEvent] = []
        for byte in (ignored + chunk + stop).utf8 { output += try decoder.append(byte) }
        output += try decoder.end()
        precondition(output == [.text("你好\nworld")] && decoder.done && decoder.finish == .completed)
        var truncated = AIStreamDecoder(maxTokens: 4096)
        for byte in chunk.utf8 { _ = try truncated.append(byte) }
        _ = try truncated.end()
        precondition(truncated.finish == nil)
        var length = AIStreamDecoder(maxTokens: 4096)
        for byte in "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n".utf8 { _ = try length.append(byte) }
        _ = try length.end()
        precondition(length.finish == .limited)
        let input = AICompletionRequest(provider: provider, model: "chat", effort: .high, maxTokens: 4096, instructions: "custom", messages: [AIChatMessage(role: "user", text: "hello")])
        let request = try input.urlRequest()
        let body = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
        precondition(body["reasoning_effort"] as? String == "high")
        precondition((body["messages"] as! [[String: String]]).first == ["role": "system", "content": "custom"])
        for (format, field) in [(AIThinkingFormat.deepSeek, "thinking"), (.qwen, "enable_thinking"), (.openRouter, "reasoning")] {
            var route = provider; route.thinkingFormat = format
            let value = AICompletionRequest(provider: route, model: "chat", effort: .off, maxTokens: 4096, instructions: "", messages: input.messages)
            let json = try JSONSerialization.jsonObject(with: value.urlRequest().httpBody!) as! [String: Any]
            precondition(json[field] != nil && json["reasoning_effort"] == nil)
        }
        let sessionConfig = URLSessionConfiguration.ephemeral
        sessionConfig.protocolClasses = [ChatHTTPStub.self]
        ChatHTTPStub.body = Data((chunk + stop).utf8)
        let capture = StreamCapture()
        let finish = try await AIChatClient.stream(input, onEvent: { await capture.append($0) }, configuration: sessionConfig)
        let received = await capture.events
        precondition(finish == .completed && received == [.text("你好\nworld")])
        precondition(ChatHTTPStub.received?.httpMethod == "POST" && ChatHTTPStub.received?.url?.path == "/v1/chat/completions")
        ChatHTTPStub.body = Data(chunk.trimmingCharacters(in: .newlines).utf8)
        let partialCapture = StreamCapture()
        do {
            _ = try await AIChatClient.stream(input, onEvent: { await partialCapture.append($0) }, configuration: sessionConfig)
            fatalError("Accepted HTTP EOF without completion")
        } catch AIChatError.incompleteStream {}
        let eofText = await partialCapture.events
        precondition(eofText == [.text("你好\nworld")])
        ChatHTTPStub.status = 401
        do { _ = try await AIChatClient.stream(input, onEvent: { _ in }, configuration: sessionConfig); fatalError("Accepted HTTP failure") }
        catch AIChatError.http(let code) { precondition(code == 401) }
        var crOnly = AIStreamDecoder(maxTokens: 4096)
        var crEvents: [AIStreamEvent] = []
        for byte in ("\u{FEFF}" + chunk.replacingOccurrences(of: "\r\n", with: "\r") + stop.replacingOccurrences(of: "\n", with: "\r")).utf8 {
            crEvents += try crOnly.append(byte)
        }
        crEvents += try crOnly.end()
        precondition(crEvents == [.text("你好\nworld")] && crOnly.done)
        let history = AIHistoryStore(directory: root.appending(path: "History"))
        let sessions = AISessions(history: history, transport: { _, event in
            try await event(.text("Saved answer"))
            return .completed
        })
        let id = sessions.newConversation()
        sessions.updateDraft(id, text: "Saved question")
        try sessions.send(id, configuration: config)
        try await waitUntil { sessions.conversation(id)?.isRunning == false }
        precondition(sessions.conversation(id)?.turns.last?.status == .completed)
        let reopened = AISessions(history: history)
        precondition(reopened.conversation(id)?.turns.last?.answer == "Saved answer")
        let bytes = try Data(contentsOf: history.directory.appending(path: id.uuidString + ".json"))
        precondition(bytes.range(of: Data(provider.key.utf8)) == nil)
        let empty = sessions.newConversation()
        sessions.updateDraft(empty, text: "Unsent private draft")
        sessions.releaseEmpty(empty)
        precondition(sessions.conversation(empty) == nil)
        // No view owns the running task. Creating another conversation must not cancel it.
        let running = AISessions(history: AIHistoryStore(directory: root.appending(path: "Running")), transport: { _, event in
            try await event(.text("Partial"))
            try await Task.sleep(for: .milliseconds(150))
            try await event(.text(" final"))
            return .completed
        })
        let background = running.newConversation()
        running.updateDraft(background, text: "Continue in background")
        try running.send(background, configuration: config)
        running.updateDraft(background, text: "Next draft while generating")
        let another = running.newConversation()
        running.updateDraft(another, text: "Other draft")
        try await waitUntil { running.conversation(background)?.isRunning == false }
        precondition(running.conversation(background)?.turns.last?.answer == "Partial final")
        precondition(running.conversation(another)?.draft == "Other draft")
        precondition(running.conversation(background)?.draft == "Next draft while generating")
        running.updateDraft(background, text: "Stop this")
        try running.send(background, configuration: config)
        running.stop(background)
        try await Task.sleep(for: .milliseconds(200))
        precondition(running.conversation(background)?.turns.last?.status == .stopped)
        running.updateDraft(background, text: "Keep next draft")
        precondition(running.conversation(background)?.draft == "Keep next draft")
        var removed = config; removed.providers = []; removed.defaultModel = nil
        do { try running.send(background, configuration: removed); fatalError("Fell back after provider deletion") } catch AIChatError.chooseModel {}
        // A deliberately late callback after deletion must not resurrect the conversation.
        let late = AISessions(history: AIHistoryStore(directory: root.appending(path: "Late")), transport: { _, event in
            try? await Task.sleep(for: .milliseconds(100))
            try? await event(.text("Late answer"))
            return .completed
        })
        let deleted = late.newConversation()
        late.updateDraft(deleted, text: "Delete me")
        try late.send(deleted, configuration: config)
        try late.trash(deleted, retentionDays: 0)
        try await Task.sleep(for: .milliseconds(150))
        precondition(late.conversation(deleted) == nil)
        try sessions.trash(id, retentionDays: 7)
        precondition(AISessions(history: history).conversation(id)?.trashedAt != nil)
        try sessions.restore(id)
        try sessions.trash(id, retentionDays: 7, now: Date(timeIntervalSince1970: 1))
        sessions.purgeExpiredTrash(days: 7, now: Date(timeIntervalSince1970: 864000))
        precondition(sessions.conversation(id) == nil)
        var interrupted = AIConversation()
        interrupted.turns = [AITurn(question: "Interrupted", model: choice, effort: .off)]
        interrupted.turns[0].answer = "Preserved partial answer"
        try history.save(interrupted)
        let recovered = AISessions(history: history)
        precondition(recovered.conversation(interrupted.id)?.turns[0].status == .interrupted)
        precondition(recovered.conversation(interrupted.id)?.turns[0].answer == "Preserved partial answer")
        let broken = history.directory.appending(path: UUID().uuidString + ".json")
        try Data("broken".utf8).write(to: broken)
        precondition(AISessions(history: history).damagedCount == 1 && FileManager.default.fileExists(atPath: broken.path))
        let partialHistory = AIHistoryStore(directory: root.appending(path: "Partial"))
        let partial = AISessions(history: partialHistory, transport: { _, event in
            try await event(.text("Kept partial"))
            throw AIChatError.incompleteStream
        })
        let partialID = partial.newConversation()
        partial.updateDraft(partialID, text: "Fail partway")
        try partial.send(partialID, configuration: config)
        try await waitUntil { partial.conversation(partialID)?.isRunning == false }
        precondition(partial.conversation(partialID)?.turns.last?.status == .failed)
        precondition(AISessions(history: partialHistory).conversation(partialID)?.turns.last?.answer == "Kept partial")
        var long = AIConversation()
        long.model = choice
        long.draft = "Keep over-limit question"
        long.turns = (0..<20).map { _ in
            var turn = AITurn(question: "Previous", model: choice, effort: .off)
            turn.answer = "Previous answer"; turn.status = .completed
            return turn
        }
        try history.save(long)
        let full = AISessions(history: history)
        do { try full.send(long.id, configuration: config); fatalError("Silently trimmed context") } catch AIChatError.contextLimit {}
        precondition(full.conversation(long.id)?.draft == long.draft)
        let failing = AISessions(history: AIHistoryStore(directory: root.appending(path: "Fail"), persist: { _, _ in throw CocoaError(.fileWriteOutOfSpace) }), transport: { _, _ in fatalError("Sent before saving question") })
        let failID = failing.newConversation()
        failing.updateDraft(failID, text: "Keep this draft")
        do { try failing.send(failID, configuration: config); fatalError("Ignored save failure") } catch {}
        precondition(failing.conversation(failID)?.draft == "Keep this draft" && failing.conversation(failID)?.turns.isEmpty == true)
        print("PASS AI chat SSE types, truncation, HTTP, thinking formats, saved questions, background lifetime, stop, drafts, deleted providers, trash+late callbacks, restart recovery and corrupt files")
    }

    @MainActor private static func waitUntil(_ predicate: () -> Bool) async throws {
        for _ in 0..<100 {
            if predicate() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        fatalError("Timed out waiting for the specific conversation task")
    }
}
