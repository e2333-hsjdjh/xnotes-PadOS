import Foundation

@MainActor enum AIModelKindsRegression {
    static func run() throws {
        for name in ["gpt-image-1", "DALL-E-3", "flux-pro", "stable-diffusion", "sdxl", "seedream-v4", "imagen-4"] {
            precondition(AIModelKind.guess(name) == .image)
        }
        for name in ["text-embedding-3", "whisper-1", "tts-1", "realtime", "moderation", "rerank-1"] {
            precondition(AIModelKind.guess(name) == .other)
        }
        for name in ["astra", "deepseek-chat", "gpt-5", "qwen-3"] { precondition(AIModelKind.guess(name) == .chat) }
        let artist = AIProvider(name: "Images only", endpoint: "https://images.example/v1", key: "fake-image-key", imageModels: ["gpt-image-1"])
        let chat = AIProvider(name: "Chat", endpoint: "https://chat.example/v1", key: "fake-chat-key", models: ["astra"])
        let imageChoice = AIModelChoice(providerID: artist.id, model: "gpt-image-1")
        var configuration = AIConfiguration(providers: [artist], defaultImageModel: imageChoice)
        configuration.reconcileDefault()
        try configuration.validate()
        precondition(configuration.defaultModel == nil && configuration.chatProviders.isEmpty)
        precondition(configuration.provider(for: imageChoice) == nil && configuration.imageProvider(for: imageChoice)?.id == artist.id)
        configuration.providers.append(chat)
        configuration.reconcileDefault()
        try configuration.validate()
        precondition(configuration.defaultModel?.providerID == chat.id)
        precondition(configuration.defaultImageModel == imageChoice)
        let bytes = try JSONEncoder().encode(configuration)
        let restored = try JSONDecoder().decode(AIConfiguration.self, from: bytes)
        precondition(restored == configuration)
        // Decode the previous encrypted payload's schema, without changing IDs or defaults.
        var oldJSON = try JSONSerialization.jsonObject(with: JSONEncoder().encode(AIConfiguration(providers: [chat], defaultModel: .init(providerID: chat.id, model: "astra")))) as! [String: Any]
        var oldProviders = oldJSON["providers"] as! [[String: Any]]
        oldProviders[0].removeValue(forKey: "imageModels")
        oldJSON["providers"] = oldProviders
        oldJSON.removeValue(forKey: "defaultImageModel")
        let legacy = try JSONDecoder().decode(AIConfiguration.self, from: JSONSerialization.data(withJSONObject: oldJSON))
        try legacy.validate()
        precondition(legacy.providers[0] == chat && legacy.defaultModel?.providerID == chat.id && legacy.defaultImageModel == nil)
        var renamed = configuration
        renamed.providers[0].name = "Renamed image provider"
        renamed.providers[0].endpoint = "https://renamed.example/v1"
        precondition(renamed.imageProvider(for: imageChoice)?.id == artist.id)
        // Removing a chosen image model clears that default without selecting another image supplier.
        var replacement = artist
        replacement.id = UUID().uuidString
        renamed.providers.append(replacement)
        renamed.providers.removeAll { $0.id == artist.id }
        renamed.reconcileDefault()
        precondition(renamed.defaultImageModel == nil && renamed.imageProvider(for: imageChoice) == nil)
        precondition(renamed.defaultModel == configuration.defaultModel)
        var removed = configuration
        removed.providers[0].imageModels = ["another-image"]
        removed.reconcileDefault()
        precondition(removed.defaultImageModel == nil)
        var invalid = configuration
        invalid.defaultImageModel = .init(providerID: chat.id, model: "astra")
        do { try invalid.validate(); preconditionFailure("Accepted chat model as default image route") } catch AISettingsError.invalidDefault {}
        var many = artist
        many.imageModels = (0...200).map { "image-\($0)" }
        do { try many.validate(); preconditionFailure("Truncated image list") } catch AISettingsError.tooManyModels {}
        precondition(many.imageModels.count == 201)
        var manual = artist
        manual.models = ["unclassified-model"]
        manual.imageModels = ["another-unclassified-model"]
        try manual.validate()
        // Exercise encrypted persistence with both route types, then preserve the file on rejected edits.
        let root = URL(fileURLWithPath: CommandLine.arguments[1]).appending(path: "image-models-\(UUID())")
        defer { try? FileManager.default.removeItem(at: root) }
        let store = AIConfigurationStore(directory: root, keys: TestKey())
        try store.save(configuration)
        let saved = try Data(contentsOf: root.appending(path: "Providers.encrypted"))
        precondition(saved.range(of: Data(artist.key.utf8)) == nil)
        do { try store.save(invalid); preconditionFailure("Saved invalid image route") } catch AISettingsError.invalidDefault {}
        let retained = try Data(contentsOf: root.appending(path: "Providers.encrypted"))
        precondition(saved == retained && store.configuration == configuration)
        let reread = AIConfigurationStore(directory: root, keys: TestKey())
        precondition(reread.loadIssue == nil && reread.configuration == configuration)
        print("PASS AI chat and image model groups, legacy decoding, route identity, deletion, per-group limits and encrypted persistence")
    }
    private struct TestKey: AIEncryptionKeyStore {
        func key(createIfMissing: Bool) throws -> Data { Data(repeating: 37, count: 32) }
    }
}
