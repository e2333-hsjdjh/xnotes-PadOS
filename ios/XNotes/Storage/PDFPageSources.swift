import PDFKit

struct PDFPageSources {
    let original: PDFDocument?
    let inserted: [String: PDFDocument]

    var hasTextSource: Bool { original != nil || !inserted.isEmpty }

    func document(for page: NotePage) -> PDFDocument? {
        if let reference = page.pdfBackground {
            return inserted[reference.digest]
        }
        return original
    }

    func page(for note: NotePage, at index: Int) -> PDFPage? {
        document(for: note)?.page(at: note.pdfBackground?.index ?? index)
    }
}
