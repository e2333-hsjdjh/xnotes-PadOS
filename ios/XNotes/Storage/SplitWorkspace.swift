import Foundation

struct SplitWorkspace: Codable, Equatable {
    let leading: UUID
    let trailing: UUID

    func documentsExist(in notebooks: [Notebook]) -> Bool {
        [leading, trailing].allSatisfy { id in notebooks.contains { $0.id == id && $0.trashedAt == nil } }
    }

    func canRestore(from notebooks: [Notebook]) -> Bool {
        (try? restoredPanes(from: notebooks)) != nil
    }

    func restoredPanes(from notebooks: [Notebook]) throws -> [DocumentPane] {
        guard documentsExist(in: notebooks) else { throw WorkspaceError.documentUnavailable }
        let documents = notebooks.filter { $0.id == leading || $0.id == trailing }
        guard documents.allSatisfy({ $0.kind != .canvas }) else { throw WorkspaceError.canvasInSplit }
        return [DocumentPane(documentID: leading), DocumentPane(documentID: trailing)]
    }
}

enum WorkspaceError: LocalizedError {
    case documentUnavailable, canvasInSplit
    var errorDescription: String? {
        switch self {
        case .documentUnavailable: String(localized: "The document is no longer available.")
        case .canvasInSplit: String(localized: "Canvases are for drawing and sketches. Open them on their own, outside split view.")
        }
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

    static func replacingDocument(in id: UUID, with documentID: UUID?, panes: [Self], notebooks: [Notebook]) throws -> [Self] {
        guard let index = panes.firstIndex(where: { $0.id == id }) else { return panes }
        if let documentID {
            guard let document = notebooks.first(where: { $0.id == documentID && $0.trashedAt == nil }) else {
                throw WorkspaceError.documentUnavailable
            }
            guard panes.count == 1 || document.kind != .canvas else { throw WorkspaceError.canvasInSplit }
        }
        var updated = panes
        updated[index].documentID = documentID
        return updated
    }

    static func openingBeside(_ id: UUID, documentID: UUID, panes: [Self], notebooks: [Notebook]) throws -> [Self] {
        guard let source = panes.first(where: { $0.id == id }) else { return panes }
        guard let target = notebooks.first(where: { $0.id == documentID && $0.trashedAt == nil }) else {
            throw WorkspaceError.documentUnavailable
        }
        let sourceIsCanvas = notebooks.contains { $0.id == source.documentID && $0.kind == .canvas }
        guard !sourceIsCanvas, target.kind != .canvas else { throw WorkspaceError.canvasInSplit }
        var updated = panes
        if let index = updated.firstIndex(where: { $0.id != id }) { updated[index].documentID = documentID }
        else { updated.append(DocumentPane(documentID: documentID)) }
        return updated
    }

    static func closingDocument(in id: UUID, panes: [Self]) -> [Self] {
        guard let index = panes.firstIndex(where: { $0.id == id }) else { return panes }
        guard panes.count > 1 else { return [] }
        var updated = panes
        updated[index].documentID = nil
        return updated
    }
}
