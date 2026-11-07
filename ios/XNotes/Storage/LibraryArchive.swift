import Foundation

/// Portable library subtree. PDF bytes are embedded once per content fingerprint.
struct LibraryArchive: Codable {
    var version = 1
    var name: String
    var folders: [LibraryFolder]
    var notebooks: [Notebook]
    var pdfs: [String: Data]

    func validateHierarchy() throws {
        let ids = Set(folders.map(\.id))
        guard version == 1, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              ids.count == folders.count,
              Set(notebooks.map(\.id)).count == notebooks.count,
              notebooks.allSatisfy({ $0.trashedAt == nil && ($0.folderID == nil || ids.contains($0.folderID!)) }) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let groups = Dictionary(grouping: folders, by: \.parentID)
        var pending = groups[nil] ?? []
        var visited: Set<UUID> = []
        while let folder = pending.popLast() {
            guard visited.insert(folder.id).inserted else { throw CocoaError(.fileReadCorruptFile) }
            pending.append(contentsOf: groups[folder.id] ?? [])
        }
        guard visited == ids else { throw CocoaError(.fileReadCorruptFile) }
    }
}
