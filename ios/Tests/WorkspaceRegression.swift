import Foundation

@MainActor enum WorkspaceRegression {
    static func run() throws {
        let note = Notebook(title: "Note", kind: .notebook)
        let pdf = Notebook(title: "PDF", kind: .pdf)
        let canvas = Notebook(title: "Sketch", kind: .canvas)
        var trash = Notebook(title: "Deleted", kind: .notebook)
        trash.trashedAt = Date()
        let documents = [note, pdf, canvas, trash]
        let single = [DocumentPane(documentID: note.id)]
        let split = try DocumentPane.openingBeside(single[0].id, documentID: pdf.id, panes: single, notebooks: documents)
        precondition(split.count == 2 && split[0] == single[0] && split[1].documentID == pdf.id)
        let home = DocumentPane.closingDocument(in: split[1].id, panes: split)
        precondition(home.count == 2 && home[1].documentID == nil && home[1].id == split[1].id)
        func refuses(_ operation: () throws -> [DocumentPane]) {
            do { _ = try operation(); preconditionFailure("Canvas entered split") }
            catch WorkspaceError.canvasInSplit {}
            catch { preconditionFailure("Unexpected error: \(error)") }
        }
        refuses { try DocumentPane.openingBeside(single[0].id, documentID: canvas.id, panes: single, notebooks: documents) }
        refuses { try DocumentPane.replacingDocument(in: home[1].id, with: canvas.id, panes: home, notebooks: documents) }
        let canvasPane = [DocumentPane(documentID: canvas.id)]
        refuses { try DocumentPane.openingBeside(canvasPane[0].id, documentID: note.id, panes: canvasPane, notebooks: documents) }
        let bothHomes = DocumentPane.closingDocument(in: home[0].id, panes: home)
        refuses { try DocumentPane.replacingDocument(in: bothHomes[0].id, with: canvas.id, panes: bothHomes, notebooks: documents) }
        // A remaining single pane home may open a sketch normally.
        let singleHome = [DocumentPane(documentID: nil)]
        let solo = try DocumentPane.replacingDocument(in: singleHome[0].id, with: canvas.id, panes: singleHome, notebooks: documents)
        precondition(solo.count == 1 && solo[0].documentID == canvas.id)
        let saved = SplitWorkspace(leading: note.id, trailing: pdf.id)
        let restored = try saved.restoredPanes(from: documents)
        precondition(restored.map(\.documentID) == [note.id, pdf.id])
        precondition(SplitWorkspace(leading: note.id, trailing: note.id).canRestore(from: documents))
        for pair in [(note.id, canvas.id), (canvas.id, note.id), (canvas.id, canvas.id)] {
            let old = SplitWorkspace(leading: pair.0, trailing: pair.1)
            let bytes = try JSONEncoder().encode(old)
            let decoded = try JSONDecoder().decode(SplitWorkspace.self, from: bytes)
            precondition(decoded.documentsExist(in: documents) && !decoded.canRestore(from: documents))
            refuses { try decoded.restoredPanes(from: documents) }
            precondition(decoded == old)
        }
        let shared = try DocumentPane.openingBeside(single[0].id, documentID: note.id, panes: single, notebooks: documents)
        precondition(shared.count == 2 && shared.allSatisfy { $0.documentID == note.id })
        do {
            _ = try DocumentPane.replacingDocument(in: home[1].id, with: trash.id, panes: home, notebooks: documents)
            preconditionFailure("Opened trashed file")
        } catch WorkspaceError.documentUnavailable {}
        precondition(single.count == 1 && home[1].documentID == nil && split[1].documentID == pdf.id)
        print("PASS workspace canvas solo, all split entry guards, old restore rejection, pane homes and shared paged documents")
    }
}
