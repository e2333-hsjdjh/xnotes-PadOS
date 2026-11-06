import Foundation

enum NotebookKind: String, Codable, CaseIterable {
    case notebook, canvas, pdf

    var symbol: String {
        switch self {
        case .notebook: "book.closed"
        case .canvas: "square.dashed"
        case .pdf: "doc.richtext"
        }
    }
}

struct DrawingLayer: Codable, Identifiable {
    var id = UUID()
    var name: String
    var visible = true
    var locked = false
    var ink = Data()
}

struct NotePage: Codable, Identifiable {
    var id = UUID()
    var text = ""
    var layers = [DrawingLayer(name: String(localized: "Layer 1"))]
}

struct Notebook: Codable, Identifiable {
    var id = UUID()
    var title: String
    var kind: NotebookKind
    var modified = Date()
    var trashedAt: Date?
    var pages = [NotePage()]
    var pdfDigest: String?
}

struct NotebookArchive: Codable {
    let version: Int
    var notebook: Notebook
    var originalPDF: Data?
}
