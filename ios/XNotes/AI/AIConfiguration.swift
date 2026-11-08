import Foundation

struct AIModelChoice: Codable, Hashable {
    var providerID: String
    var model: String
}

enum AIThinkingFormat: String, Codable, CaseIterable {
    case automatic, openAI, openRouter, deepSeek, qwen
}

enum AIThinkingEffort: String, Codable, CaseIterable {
    case providerDefault, off, low, medium, high
}

struct AIProvider: Codable, Identifiable, Equatable {
    var id = UUID().uuidString
    var name = ""
    var endpoint = ""
    var key = ""
    var models: [String] = []
    var imageModels: [String] = []
    var thinkingFormat = AIThinkingFormat.automatic

    var label: String { name.isEmpty ? (URL(string: endpoint)?.host ?? endpoint) : name }

    func cleaned() -> Self {
        var result = self
        result.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        result.endpoint = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        result.key = key.trimmingCharacters(in: .whitespacesAndNewlines)
        result.models = Self.uniqueModels(models)
        result.imageModels = Self.uniqueModels(imageModels)
        return result
    }

    static func uniqueModels(_ models: [String]) -> [String] {
        var seen: Set<String> = []
        return models.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    func validate() throws {
        _ = try AIEndpoints.models(endpoint)
        try AIEndpoints.validateKey(key)
        guard !id.isEmpty, !(models.isEmpty && imageModels.isEmpty),
              [models, imageModels].allSatisfy({ list in
                  list == Self.uniqueModels(list) && list.allSatisfy { $0.utf16.count <= 200 }
              }) else { throw AISettingsError.invalidModels }
        guard models.count <= 200, imageModels.count <= 200 else { throw AISettingsError.tooManyModels }
    }
}

struct AIConfiguration: Codable, Equatable {
    var version = 1
    var providers: [AIProvider] = []
    var defaultModel: AIModelChoice?
    var defaultImageModel: AIModelChoice?
    var effort = AIThinkingEffort.providerDefault
    var maxTokens = 4096
    var instructions = AIDefaults.instructions

    /// Explicit choices never fall back to another provider, even after deletion.
    func provider(for choice: AIModelChoice) -> AIProvider? {
        providers.first { $0.id == choice.providerID && $0.models.contains(choice.model) }
    }

    var chatProviders: [AIProvider] { providers.filter { !$0.models.isEmpty } }
    var imageProviders: [AIProvider] { providers.filter { !$0.imageModels.isEmpty } }

    func imageProvider(for choice: AIModelChoice) -> AIProvider? {
        providers.first { $0.id == choice.providerID && $0.imageModels.contains(choice.model) }
    }

    mutating func reconcileDefault() {
        if let defaultImageModel, imageProvider(for: defaultImageModel) == nil { self.defaultImageModel = nil }
        if let defaultModel, provider(for: defaultModel) != nil { return }
        defaultModel = chatProviders.first.flatMap { provider in
            provider.models.first.map { AIModelChoice(providerID: provider.id, model: $0) }
        }
    }

    func validate() throws {
        guard version == 1, providers.count <= 20,
              Set(providers.map(\.id)).count == providers.count else { throw AISettingsError.invalidProviders }
        try providers.forEach { try $0.validate() }
        guard (chatProviders.isEmpty && defaultModel == nil) || defaultModel.map({ provider(for: $0) != nil }) == true else {
            throw AISettingsError.invalidDefault
        }
        if let defaultImageModel, imageProvider(for: defaultImageModel) == nil { throw AISettingsError.invalidDefault }
        guard (256...131072).contains(maxTokens), instructions.utf16.count <= 4000 else {
            throw AISettingsError.invalidAnswerSettings
        }
    }
}

enum AISettingsError: LocalizedError {
    case invalidEndpoint, invalidKey, invalidModels, tooManyModels, invalidProviders, invalidDefault
    case invalidAnswerSettings, keyUnavailable, corruptSettings, unreadableSettings, invalidResponse
    case responseTooLarge, connection, http(Int)

    var errorDescription: String? {
        switch self {
        case .invalidEndpoint: String(localized: "Enter an HTTPS base URL without credentials, query or fragment.")
        case .invalidKey: String(localized: "Enter an API key without line breaks or control characters.")
        case .invalidModels: String(localized: "Add at least one chat or image model ID, up to 200 characters each.")
        case .tooManyModels: String(localized: "Each model group can contain at most 200 models. Nothing was truncated.")
        case .invalidProviders: String(localized: "Provider identities must be unique. At most 20 providers are supported.")
        case .invalidDefault: String(localized: "Choose a default model from an existing provider.")
        case .invalidAnswerSettings: String(localized: "Use 256–131072 output tokens and at most 4000 prompt characters.")
        case .keyUnavailable: String(localized: "The protected AI settings key is unavailable. Saved settings were kept.")
        case .corruptSettings: String(localized: "AI settings could not be decrypted or validated. The saved file was kept.")
        case .unreadableSettings: String(localized: "AI settings are unreadable. Clear them explicitly before replacing them.")
        case .invalidResponse: String(localized: "The provider returned an invalid model list.")
        case .responseTooLarge: String(localized: "The model list is too large. No models were added.")
        case .connection: String(localized: "The model request failed. Check the provider address and connection.")
        case .http(let code): String(format: String(localized: "Model request failed (HTTP %d)."), code)
        }
    }
}

// Older encrypted settings contain only chat models. Keep identities and routes unchanged.
extension AIProvider {
    private enum CodingKeys: String, CodingKey { case id, name, endpoint, key, models, imageModels, thinkingFormat }
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(id: try container.decode(String.self, forKey: .id),
                  name: try container.decode(String.self, forKey: .name),
                  endpoint: try container.decode(String.self, forKey: .endpoint),
                  key: try container.decode(String.self, forKey: .key),
                  models: try container.decode([String].self, forKey: .models),
                  imageModels: try container.decodeIfPresent([String].self, forKey: .imageModels) ?? [],
                  thinkingFormat: try container.decode(AIThinkingFormat.self, forKey: .thinkingFormat))
    }
}
