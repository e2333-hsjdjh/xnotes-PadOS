import PDFKit
import UIKit

enum NotebookPDFExport {
    static func data(for notebook: Notebook, source: PDFDocument?) throws -> Data {
        guard notebook.kind != .canvas,
              notebook.kind != .pdf || source?.pageCount == notebook.pages.count else {
            throw CocoaError(.fileReadCorruptFile)
        }

        let pages = try notebook.pages.enumerated().map { index, page in
            let original = source?.page(at: index)
            let size = try original.map(NotebookPageDrawing.displaySize) ?? (page.paperSize ?? .letter).dimensions
            return (page, original, CGRect(origin: .zero, size: size))
        }
        var renderError: Error?
        let renderer = UIGraphicsPDFRenderer(bounds: .zero)
        let data = renderer.pdfData { context in
            do {
                for (page, original, bounds) in pages {
                    context.beginPage(withBounds: bounds, pageInfo: [:])
                    let graphics = context.cgContext
                    try NotebookPageDrawing.draw(page, original: original, in: bounds,
                                                 with: graphics, inkScale: 2)
                }
            } catch {
                renderError = error
            }
        }
        if let renderError { throw renderError }
        return data
    }
}
