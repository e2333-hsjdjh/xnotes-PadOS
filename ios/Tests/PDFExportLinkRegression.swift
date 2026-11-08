import PDFKit
import UIKit

@MainActor enum PDFExportLinkRegression {
    static func run() throws {
        let original = fixture()
        let source = PDFDocument(data: original)!
        var notebook = Notebook(title: "Links", kind: .pdf)
        notebook.pages = [NotePage(), NotePage()]
        func output(_ data: Data) -> PDFDocument { PDFDocument(data: data)! }
        let full = output(try NotebookPDFExport.data(for: notebook, source: source))
        let annotations = full.page(at: 0)!.annotations
        precondition(annotations.count == 3)
        precondition((annotations[0].action as? PDFActionURL)?.url?.absoluteString == "https://example.org/notes?q=test")
        let target = (annotations[1].action as! PDFActionGoTo).destination
        precondition(target.page === full.page(at: 1))
        precondition(abs(target.point.x - 100) < 1 && abs(target.point.y - 600) < 1)
        precondition((annotations[2].action as! PDFActionGoTo).destination.page === full.page(at: 0))
        let subset = output(try NotebookPDFExport.data(for: notebook, source: source, selecting: [notebook.pages[0].id]))
        precondition(subset.pageCount == 1 && subset.page(at: 0)!.annotations.count == 2)
        let merged = output(try NotebookPDFExport.data(for: [.init(notebook: notebook, source: source), .init(notebook: notebook, source: source)]))
        let mergedTarget = (merged.page(at: 2)!.annotations[1].action as! PDFActionGoTo).destination.page
        precondition(mergedTarget === merged.page(at: 3))
        // Repeated PDF pages in a notebook must not make an ambiguous jump target.
        let hash = String(repeating: "a", count: 64)
        var inserted = Notebook(title: "Inserted", kind: .notebook)
        inserted.pages = [NotePage(pdfBackground: .init(digest: hash, index: 0)), NotePage(pdfBackground: .init(digest: hash, index: 1)), NotePage(pdfBackground: .init(digest: hash, index: 1))]
        let ambiguous = output(try NotebookPDFExport.data(for: inserted, source: nil, pagePDFs: [hash: source]))
        precondition(ambiguous.page(at: 0)!.annotations.count == 2)
        for rotation in [90, 180, 270] {
            let baked = PDFDocument(data: fixture(rotation: rotation))!
            let exported = output(try NotebookPDFExport.data(for: notebook, source: baked))
            let page = exported.page(at: 0)!
            precondition(page.annotations.count == 3)
            for annotation in page.annotations { precondition(page.bounds(for: .mediaBox).contains(annotation.bounds)) }
        }
        precondition(source.page(at: 0)!.annotations.count == 3 && source.page(at: 1)!.annotations.isEmpty)
        precondition(source.page(at: 0)!.bounds(for: .cropBox) == CGRect(x: 0, y: 0, width: 612, height: 792))
        print("PASS PDF exported URL, internal destinations, subset omission, merged document isolation, duplicate targets and rotated link bounds")
    }
    private static func fixture(rotation: Int = 0) -> Data {
        let geometry = rotation == 0 ? "" : "/Rotate \(rotation) /CropBox [20 20 580 760]"
        let content1 = "BT /F1 16 Tf 50 700 Td (Page 1) Tj ET"
        let content2 = "BT /F1 16 Tf 50 700 Td (Page 2) Tj ET"
        let objects = [
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] \(geometry) /Resources << /Font << /F1 7 0 R >> >> /Contents 4 0 R /Annots [8 0 R 9 0 R 10 0 R] >>",
            "<< /Length \(content1.utf8.count) >>\nstream\n\(content1)\nendstream",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>",
            "<< /Length \(content2.utf8.count) >>\nstream\n\(content2)\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Type /Annot /Subtype /Link /Rect [50 600 150 630] /Border [0 0 0] /A << /S /URI /URI (https://example.org/notes?q=test) >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [50 500 150 530] /Border [0 0 0] /A << /S /GoTo /D [5 0 R /XYZ 100 600 null] >> >>",
            "<< /Type /Annot /Subtype /Link /Rect [50 400 150 430] /Border [0 0 0] /Dest [3 0 R /XYZ null 700 null] >>"
        ]
        var bytes = Data("%PDF-1.7\n".utf8), offsets = [0]
        for (index, object) in objects.enumerated() {
            offsets.append(bytes.count)
            bytes.append(Data("\(index + 1) 0 obj\n\(object)\nendobj\n".utf8))
        }
        let xref = bytes.count
        bytes.append(Data("xref\n0 \(objects.count + 1)\n0000000000 65535 f \n".utf8))
        for offset in offsets.dropFirst() { bytes.append(Data(String(format: "%010d 00000 n \n", offset).utf8)) }
        bytes.append(Data("trailer\n<< /Size \(objects.count + 1) /Root 1 0 R >>\nstartxref\n\(xref)\n%%EOF\n".utf8))
        return bytes
    }

}
