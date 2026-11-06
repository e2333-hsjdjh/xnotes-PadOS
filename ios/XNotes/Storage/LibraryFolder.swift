import Foundation

struct LibraryFolder: Codable, Identifiable {
    var id = UUID()
    var name: String
    var parentID: UUID?
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
