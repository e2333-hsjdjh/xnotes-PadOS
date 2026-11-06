import Foundation

struct SplitWorkspace: Codable, Equatable {
    let leading: UUID
    let trailing: UUID

    func canRestore(from notebooks: [Notebook]) -> Bool {
        let active = notebooks.filter { $0.trashedAt == nil }
        guard let left = active.first(where: { $0.id == leading }),
              active.contains(where: { $0.id == trailing }) else { return false }
        return leading != trailing || left.kind != .canvas
    }
}

struct DocumentPane: Identifiable, Equatable {
    let id = UUID()
    var documentID: UUID?
    static func available(_ panes: [Self], exists: (UUID) -> Bool) -> [Self] {
        panes.compactMap { pane in
            guard let document = pane.documentID, !exists(document) else { return pane }
            guard panes.count > 1 else { return nil }
            var home = pane
            home.documentID = nil
            return home
        }
    }

    static func closingDocument(in id: UUID, panes: [Self]) -> [Self] {
        guard let index = panes.firstIndex(where: { $0.id == id }) else { return panes }
        guard panes.count > 1 else { return [] }
        var updated = panes
        updated[index].documentID = nil
        return updated
    }
}
