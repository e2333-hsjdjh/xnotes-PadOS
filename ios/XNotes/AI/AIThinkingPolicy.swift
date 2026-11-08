import Foundation

enum AIThinkingPolicy {
    static func format(for provider: AIProvider) -> AIThinkingFormat {
        guard provider.thinkingFormat == .automatic else { return provider.thinkingFormat }
        let host = URL(string: provider.endpoint)?.host?.lowercased() ?? ""
        if host == "deepseek.com" || host.hasSuffix(".deepseek.com") { return .deepSeek }
        if host == "openrouter.ai" || host.hasSuffix(".openrouter.ai") { return .openRouter }
        if host.contains("dashscope") { return .qwen }
        return .openAI
    }
    static func field(for provider: AIProvider) -> String {
        switch format(for: provider) {
        case .deepSeek: "thinking"
        case .qwen: "enable_thinking"
        case .openRouter: "reasoning"
        default: "reasoning_effort"
        }
    }
    static func isRefusal(_ error: AIChatError, input: AICompletionRequest) -> Bool {
        guard input.effort != .providerDefault,
              case .httpDetail(let code, let detail) = error, code == 400 || code == 422 else { return false }
        return detail.range(of: field(for: input.provider), options: .caseInsensitive) != nil
    }
}

/// In-memory capability hints only; never persist keys, prompts or conversation contents.
actor AIThinkingRefusals {
    static let shared = AIThinkingRefusals()
    private struct Route: Hashable {
        let provider: String
        let endpoint: String
        let model: String
        let effort: AIThinkingEffort
        let format: AIThinkingFormat
        init(_ input: AICompletionRequest) {
            provider = input.provider.id
            endpoint = (try? AIEndpoints.completion(input.provider.endpoint).absoluteString) ?? input.provider.endpoint
            model = input.model
            effort = input.effort
            format = AIThinkingPolicy.format(for: input.provider)
        }
    }
    private var routes: Set<Route> = []
    func contains(_ input: AICompletionRequest) -> Bool {
        input.effort != .providerDefault && routes.contains(Route(input))
    }
    func remember(_ input: AICompletionRequest) {
        guard input.effort != .providerDefault else { return }
        if routes.count >= 512 { routes.removeAll() }
        routes.insert(Route(input))
    }
}
