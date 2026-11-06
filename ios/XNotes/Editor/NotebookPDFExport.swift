import PDFKit
import UIKit

enum NotebookPDFExport {
    struct Input {
        let notebook: Notebook
        let source: PDFDocument?
        var selectedPages: Set<UUID>? = nil
    }

    private struct Page {
        let note: NotePage
        let original: PDFPage?
        let bounds: CGRect
    }

    static func data(for notebook: Notebook, source: PDFDocument?, selecting ids: Set<UUID>? = nil) throws -> Data {
        try data(for: [Input(notebook: notebook, source: source, selectedPages: ids)])
    }

    static func data(for inputs: [Input]) throws -> Data {
        guard !inputs.isEmpty else { throw CocoaError(.fileReadNoSuchFile) }
        let pages = try inputs.flatMap(exportPages)
        var renderError: Error?
        let renderer = UIGraphicsPDFRenderer(bounds: .zero)
        let data = renderer.pdfData { context in
            do {
                for page in pages {
                    context.beginPage(withBounds: page.bounds, pageInfo: [:])
                    try NotebookPageDrawing.draw(page.note, original: page.original, in: page.bounds,
                                                 with: context.cgContext, inkScale: 2)
                }
            } catch {
                renderError = error
            }
        }
        if let renderError { throw renderError }
        return data
    }

    private static func exportPages(_ input: Input) throws -> [Page] {
        let notebook = input.notebook
        let ids = input.selectedPages
        guard notebook.kind != .canvas,
              notebook.kind != .pdf || input.source?.pageCount == notebook.pages.count else {
            throw CocoaError(.fileReadCorruptFile)
        }

        let selected = notebook.pages.enumerated().filter { ids?.contains($0.element.id) ?? true }
        guard !selected.isEmpty, ids == nil || selected.count == ids?.count else {
            throw CocoaError(.fileReadNoSuchFile)
        }
        return try selected.map { index, page in
            let original = notebook.kind == .pdf ? input.source?.page(at: index) : nil
            if notebook.kind == .pdf && original == nil { throw CocoaError(.fileReadCorruptFile) }
            let size = try original.map(PDFPageGeometry.displaySize) ?? (page.paperSize ?? .letter).dimensions
            return Page(note: page, original: original, bounds: CGRect(origin: .zero, size: size))
        }
    }
}
