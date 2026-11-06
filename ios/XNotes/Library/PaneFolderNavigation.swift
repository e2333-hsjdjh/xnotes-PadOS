import Foundation

struct PaneFolderNavigation {
    let folders: [LibraryFolder]
    let selectedID: UUID?

    var current: LibraryFolder? { folders.first { $0.id == selectedID } }

    var children: [LibraryFolder] {
        folders.filter { $0.parentID == current?.id }.sorted {
            $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }

    func visibleDocuments(_ documents: [Notebook], query: String) -> [Notebook] {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if !term.isEmpty {
            return documents.filter { $0.title.localizedStandardContains(term) }
        }
        let knownFolders = Set(folders.map(\.id))
        let parent = current?.id
        return documents.filter {
            $0.folderID.flatMap { knownFolders.contains($0) ? $0 : nil } == parent
        }
    }
}
