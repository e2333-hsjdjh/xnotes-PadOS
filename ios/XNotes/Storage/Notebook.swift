import CoreGraphics
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

struct PageImage: Codable, Identifiable {
    var id = UUID()
    let data: Data
    private var x: CGFloat
    private var y: CGFloat
    private var width: CGFloat
    private var height: CGFloat

    var frame: CGRect {
        get { CGRect(x: x, y: y, width: width, height: height) }
        set {
            x = newValue.origin.x
            y = newValue.origin.y
            width = newValue.width
            height = newValue.height
        }
    }

    init(data: Data, frame: CGRect) {
        self.data = data
        x = frame.origin.x
        y = frame.origin.y
        width = frame.width
        height = frame.height
    }
}

struct NotePage: Codable, Identifiable {
    var id = UUID()
    var text = ""
    var images: [PageImage]?
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
