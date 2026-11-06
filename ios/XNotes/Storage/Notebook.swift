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

struct DrawingLayer: Codable, Identifiable, Equatable {
    var id = UUID()
    var name: String
    var visible = true
    var locked = false
    var ink = Data()
}

struct PageImage: Codable, Identifiable, Equatable {
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

enum PaperPattern: String, Codable, CaseIterable, Identifiable {
    case blank, ruled, grid, dots

    var id: Self { self }
}

struct PageBookmark: Codable, Identifiable, Equatable {
    var id = UUID()
    var label: String
}

struct PDFPageReference: Codable, Equatable {
    let digest: String
    let index: Int
}

struct NotePage: Codable, Identifiable, Equatable {
    var id = UUID()
    var text = ""
    var images: [PageImage]?
    var bookmarks: [PageBookmark]?
    var pdfBackground: PDFPageReference?
    var pdfTextMarkups: [PDFTextMarkup]?
    var isCover: Bool?
    var coverArtwork: NotebookCover?
    var coverParameters: NotebookCoverParameters?
    var paperPattern: PaperPattern?
    var paperParameters: PaperPatternParameters?
    var paperSize: NotebookPaperSize?
    var layers = [DrawingLayer(name: String(localized: "Layer 1"))]
}

struct Notebook: Codable, Identifiable {
    var id = UUID()
    var title: String
    var kind: NotebookKind
    var modified = Date()
    var created: Date? = .now
    var trashedAt: Date?
    var folderID: UUID?
    var pages = [NotePage()]
    var pdfDigest: String?

    var pdfDigests: Set<String> {
        Set(pages.compactMap { $0.pdfBackground?.digest }).union(pdfDigest.map { [$0] } ?? [])
    }
}

struct NotebookArchive: Codable {
    let version: Int
    var notebook: Notebook
    var originalPDF: Data?
    var pagePDFs: [String: Data]? = nil
}
