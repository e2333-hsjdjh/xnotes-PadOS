import Foundation

struct LibraryFolder: Codable, Identifiable {
    var id = UUID()
    var name: String
    var parentID: UUID?

    static func subtree(root: UUID, in folders: [Self]) -> Set<UUID> {
        let groups = Dictionary(grouping: folders, by: \.parentID)
        var pending = [root]
        var visited: Set<UUID> = []
        while let id = pending.popLast() {
            guard visited.insert(id).inserted else { continue }
            pending.append(contentsOf: (groups[id] ?? []).map(\.id))
        }
        return visited
    }
}

struct FolderBranch: Identifiable {
    let folder: LibraryFolder
    let children: [FolderBranch]?

    var id: UUID { folder.id }

    static func tree(from folders: [LibraryFolder]) -> [Self] {
        let groups = Dictionary(grouping: folders, by: \.parentID)
        func branches(under parent: UUID?) -> [Self] {
            (groups[parent] ?? []).sorted {
                $0.name.localizedStandardCompare($1.name) == .orderedAscending
            }.map { folder in
                let children = branches(under: folder.id)
                return Self(folder: folder, children: children.isEmpty ? nil : children)
            }
        }
        return branches(under: nil)
    }
}
