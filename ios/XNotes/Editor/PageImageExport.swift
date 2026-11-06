import PDFKit

enum PageImageExport {
    @MainActor
    static func files(for notebook: Notebook, source: PDFDocument?, pagePDFs: [String: PDFDocument] = [:], selecting ids: Set<UUID>) throws -> [ExportFile] {
        let pages = notebook.pages.enumerated().filter { ids.contains($0.element.id) }
        guard !pages.isEmpty, pages.count == ids.count else { throw CocoaError(.fileReadNoSuchFile) }
        let title = notebook.title.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
        return try pages.map { index, page in
            let original = page.pdfBackground.flatMap { pagePDFs[$0.digest]?.page(at: $0.index) }
                ?? (notebook.kind == .pdf ? source?.page(at: index) : nil)
            if (notebook.kind == .pdf || page.pdfBackground != nil) && original == nil { throw CocoaError(.fileReadCorruptFile) }
            let image = try NotebookPageDrawing.render(page, original: original, longestEdge: 2048)
            guard let bytes = image.pngData() else { throw CocoaError(.fileWriteUnknown) }
            return ExportFile(data: bytes, filename: "\(title) - \(index + 1).png")
        }
    }
}
