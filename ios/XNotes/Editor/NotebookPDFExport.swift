import PDFKit
import UIKit

enum NotebookPDFExport {
    struct Input {
        let notebook: Notebook
        let source: PDFDocument?
        var darkCanvas = false
        var pagePDFs: [String: PDFDocument] = [:]
        var selectedPages: Set<UUID>? = nil
    }

    private struct Page {
        let note: NotePage
        let original: PDFPage?
        let bounds: CGRect
        var crop: CGRect? = nil
        var dark = false
        var scope = 0
    }

    static func data(for notebook: Notebook, source: PDFDocument?, pagePDFs: [String: PDFDocument] = [:], selecting ids: Set<UUID>? = nil) throws -> Data {
        try data(for: [Input(notebook: notebook, source: source, pagePDFs: pagePDFs, selectedPages: ids)])
    }

    static func data(for inputs: [Input]) throws -> Data {
        guard !inputs.isEmpty else { throw CocoaError(.fileReadNoSuchFile) }
        let pages = try inputs.enumerated().flatMap { scope, input in
            try exportPages(input).map { page in
                var grouped = page
                grouped.scope = scope
                return grouped
            }
        }
        let links = PDFExportLinks.plans(for: pages.map { PDFExportLinks.Page(original: $0.original, bounds: $0.bounds, scope: $0.scope) })
        var renderError: Error?
        let renderer = UIGraphicsPDFRenderer(bounds: .zero)
        let data = renderer.pdfData { context in
            do {
                for (index, page) in pages.enumerated() {
                    context.beginPage(withBounds: CGRect(origin: .zero, size: page.crop?.size ?? page.bounds.size), pageInfo: [:])
                    try NotebookPageDrawing.draw(page.note, original: page.original, in: page.bounds,
                                                 with: context.cgContext, inkScale: 2, crop: page.crop, dark: page.dark)
                    links[index].draw(in: context.cgContext)
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
        guard notebook.kind != .pdf || input.source?.pageCount == notebook.pages.count else {
            throw CocoaError(.fileReadCorruptFile)
        }

        let selected = notebook.pages.enumerated().filter { ids?.contains($0.element.id) ?? true }
        guard !selected.isEmpty, ids == nil || selected.count == ids?.count else {
            throw CocoaError(.fileReadNoSuchFile)
        }
        let sources = PDFPageSources(original: notebook.kind == .pdf ? input.source : nil, inserted: input.pagePDFs)
        return try selected.map { index, page in
            if notebook.kind == .canvas {
                return Page(note: page, original: nil,
                            bounds: CGRect(origin: .zero, size: NotebookPageDrawing.canvasSize),
                            crop: try NotebookPageDrawing.canvasCrop(page), dark: input.darkCanvas)
            }
            let original = sources.page(for: page, at: index)
            if (notebook.kind == .pdf || page.pdfBackground != nil) && original == nil { throw CocoaError(.fileReadCorruptFile) }
            let size = try original.map(PDFPageGeometry.displaySize) ?? (page.paperSize ?? .letter).dimensions
            return Page(note: page, original: original, bounds: CGRect(origin: .zero, size: size))
        }
    }
}
