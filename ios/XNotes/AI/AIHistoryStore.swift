import Foundation

struct AIHistoryStore {
    let directory: URL
    var persist: (Data, URL) throws -> Void = { data, url in
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    static let byteLimit = 64 * 1024 * 1024

    func readAll() throws -> (conversations: [AIConversation], damaged: Int) {
        guard FileManager.default.fileExists(atPath: directory.path) else { return ([], 0) }
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey])
        var conversations: [AIConversation] = []
        var damaged = 0
        for file in files where file.pathExtension == "json" {
            do {
                guard (try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? Int.max) <= Self.byteLimit else {
                    throw AIChatError.corruptHistory
                }
                let conversation = try JSONDecoder().decode(AIConversation.self, from: Data(contentsOf: file))
                guard file.lastPathComponent == conversation.id.uuidString + ".json" else { throw AIChatError.corruptHistory }
                try conversation.validate()
                conversations.append(conversation)
            } catch { damaged += 1 }
        }
        return (conversations, damaged)
    }

    func save(_ conversation: AIConversation) throws {
        try conversation.validate()
        let data = try JSONEncoder().encode(conversation)
        guard data.count <= Self.byteLimit else { throw AIChatError.responseLimit }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var excluded = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
        try persist(data, file(conversation.id))
    }

    func delete(_ id: UUID) throws {
        let url = file(id)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }

    private func file(_ id: UUID) -> URL { directory.appending(path: id.uuidString + ".json") }
}
