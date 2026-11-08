import Foundation

private final class ErrorHTTPStub: URLProtocol, @unchecked Sendable {
    static let lock = NSLock()
    static var bytes = Data()
    static var code = 400
    static var count = 0
    static func configure(_ code: Int, _ body: Data) {
        lock.lock(); defer { lock.unlock() }
        self.code = code; bytes = body; count = 0
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        let body = Self.bytes, code = Self.code
        Self.count += 1
        Self.lock.unlock()
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: "HTTP/1.1", headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor enum AIHTTPCompatibilityRegression {
    static func run() async throws {
        let key = "fake-compat-secret"
        func body(_ value: [String: Any]) -> Data { try! JSONSerialization.data(withJSONObject: value) }
        precondition(AIProviderFailure.detail(body(["error": ["message": " Bad \nkey \(key)\t invalid "]]), key: key) == "Bad key *** invalid")
        precondition(AIProviderFailure.detail(body(["error": "short-key x"]), key: "x") == "short-key ***")
        precondition(AIProviderFailure.detail(body(["message": "Top level"]), key: key) == "Top level")
        precondition(AIProviderFailure.detail(Data("<html>upstream unavailable</html>".utf8), key: key).isEmpty)
        precondition(AIProviderFailure.detail(body(["error": ["message": 42]]), key: key).isEmpty)
        let long = AIProviderFailure.detail(body(["error": String(repeating: "长", count: 400)]), key: key)
        precondition(long.count == 300 && long.hasSuffix("…"))
        let oversized = body(["error": String(repeating: "a", count: AIProviderFailure.byteLimit)])
        precondition(AIProviderFailure.detail(oversized, key: key).isEmpty)
        for endpoint in ["https://api.openai.com/v1", "https://API.OPENAI.COM/v1/chat/completions", "https://proxy.example/v1", "https://api.openai.com.evil.example/v1", "https://sub.api.openai.com/v1"] {
            let provider = AIProvider(endpoint: endpoint, key: key, models: ["chat"])
            let input = AICompletionRequest(provider: provider, model: "chat", effort: .providerDefault, maxTokens: 4096, instructions: "", messages: [.init(role: "user", text: "Hi")])
            let json = try JSONSerialization.jsonObject(with: input.urlRequest().httpBody!) as! [String: Any]
            let official = URL(string: endpoint)!.host!.lowercased() == "api.openai.com"
            precondition(json[official ? "max_completion_tokens" : "max_tokens"] as? Int == 4096)
            precondition(json[official ? "max_tokens" : "max_completion_tokens"] == nil)
        }
        let provider = AIProvider(endpoint: "https://provider.example/v1", key: key, models: ["chat"], imageModels: ["image"])
        let input = AICompletionRequest(provider: provider, model: "chat", effort: .high, maxTokens: 4096, instructions: "", messages: [.init(role: "user", text: "Hi")])
        let settings = URLSessionConfiguration.ephemeral
        settings.protocolClasses = [ErrorHTTPStub.self]
        ErrorHTTPStub.configure(422, body(["error": ["message": "Unsupported parameter: max_tokens. Key \(key)"]]))
        do {
            _ = try await AIChatClient.stream(input, onEvent: { _ in preconditionFailure("Error body emitted answer") }, configuration: settings)
            preconditionFailure("Ignored HTTP error")
        } catch AIChatError.httpDetail(let code, let detail) {
            precondition(code == 422 && detail.contains("Unsupported parameter") && !detail.contains(key))
            precondition(!AIChatError.httpDetail(code, detail).localizedDescription.contains(key))
        }
        precondition(ErrorHTTPStub.count == 1)
        for response in [Data("<html>502</html>".utf8), oversized, body(["error": ["message": NSNull()]])] {
            ErrorHTTPStub.configure(502, response)
            do { _ = try await AIChatClient.stream(input, onEvent: { _ in }, configuration: settings); preconditionFailure("Ignored HTTP status") }
            catch AIChatError.http(let code) { precondition(code == 502) }
        }
        let imageInput = AICompletionRequest(provider: provider, model: "image", effort: .providerDefault, maxTokens: 4096, instructions: "", messages: input.messages)
        do { _ = try imageInput.urlRequest(); preconditionFailure("Sent image-only model to chat") } catch AIChatError.chooseModel {}
        print("PASS AI OpenAI token field, bounded provider errors, secret redaction, HTTP transport and chat route validation")
    }
}
