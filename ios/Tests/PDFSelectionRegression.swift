import PDFKit
import UIKit

@MainActor enum PDFSelectionRegression {
    static func fixture() -> Data {
        UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { context in
            context.beginPage()
            ("Select these words\nSecond line for annotation" as NSString).draw(in: CGRect(x: 60, y: 80, width: 460, height: 140),
                withAttributes: [.font: UIFont.systemFont(ofSize: 24), .foregroundColor: UIColor.black])
        }
    }
    static func run() throws {
        let bytes = fixture()
        let original = PDFDocument(data: bytes)!
        let page = original.page(at: 0)!
        for rotation in [0, 90, 180, 270] {
            let copy = page.copy() as! PDFPage
            let document = PDFDocument(); document.insert(copy, at: 0)
            copy.rotation = rotation
            copy.setBounds(CGRect(x: 20, y: 30, width: 560, height: 720), for: .cropBox)
            let selection = copy.selection(for: NSRange(location: 0, length: (copy.string! as NSString).length))!
            let size = try PDFPageGeometry.displaySize(copy)
            let rects = PDFSelectionGeometry.rectangles(selection, on: copy, displaySize: size)
            precondition(rects.count >= 2)
            for kind in PDFTextMarkup.Kind.allCases {
                let markup = PDFTextMarkup(kind: kind, query: selection.string!, rectangles: rects, paper: size)
                precondition(markup.isValid)
                let decoded = try JSONDecoder().decode(PDFTextMarkup.self, from: JSONEncoder().encode(markup))
                precondition(decoded == markup)
            }
            precondition(PDFSelectionGeometry.rectangles(selection, on: page, displaySize: size).isEmpty)
            precondition(PDFSelectionGeometry.rectangles(selection, on: copy, displaySize: .zero).isEmpty)
        }
        let storeRoot = URL(fileURLWithPath: CommandLine.arguments[1]).appending(path: "selection-\(UUID())")
        let store = LibraryStore(directory: storeRoot)
        defer { try? FileManager.default.removeItem(at: storeRoot) }
        let input = storeRoot.appending(path: "source.pdf")
        try bytes.write(to: input)
        let id = try store.importFile(input)
        var note = store.notebooks.first { $0.id == id }!
        let selection = page.selection(for: NSRange(location: 0, length: 17))!
        let size = try PDFPageGeometry.displaySize(page)
        let mark = PDFTextMarkup(kind: .underline, query: selection.string!, rectangles: PDFSelectionGeometry.rectangles(selection, on: page, displaySize: size), paper: size)
        let before = note.pages
        note.pages[0].pdfTextMarkups = [mark]
        try store.save(note)
        let history = DocumentHistory()
        history.record(before: before, after: note.pages)
        let persist: ([NotePage]) -> Bool = { pages in
            do {
                var document = store.notebooks.first { $0.id == id }!
                document.pages = pages
                try store.save(document)
                return true
            } catch { return false }
        }
        history.undo(current: note.pages, persist: persist)
        precondition(store.notebooks.first { $0.id == id }!.pages[0].pdfTextMarkups == nil)
        history.redo(current: before, persist: persist)
        precondition(store.notebooks.first { $0.id == id }!.pages[0].pdfTextMarkups == [mark])
        let reopened = LibraryStore(directory: storeRoot)
        precondition(reopened.notebooks.first { $0.id == id }?.pages[0].pdfTextMarkups == [mark])
        let unchanged = try Data(contentsOf: input)
        precondition(unchanged == bytes && page.annotations.isEmpty)
        print("PASS PDF free selection multiline, rotated crop boxes, unrelated page rejection, three markup kinds, persistence and unchanged original")
    }
}
