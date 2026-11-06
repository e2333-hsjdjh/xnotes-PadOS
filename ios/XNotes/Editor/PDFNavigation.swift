import PDFKit
import SwiftUI

struct PDFOutlineEntry: Identifiable {
    let id: [Int]
    let label: String
    let pageIndex: Int?
    let children: [Self]?

    static func entries(in document: PDFDocument) -> [Self] {
        guard let root = document.outlineRoot else { return [] }
        return children(of: root, path: [], in: document)
    }

    private static func children(of parent: PDFOutline, path: [Int], in document: PDFDocument) -> [Self] {
        (0..<parent.numberOfChildren).compactMap { index in
            guard let item = parent.child(at: index) else { return nil }
            let id = path + [index]
            let target = (item.destination ?? (item.action as? PDFActionGoTo)?.destination)?.page
            let pageIndex = target.map { document.index(for: $0) }
                .flatMap { (0..<document.pageCount).contains($0) ? $0 : nil }
            let nested = children(of: item, path: id, in: document)
            return Self(id: id, label: item.label ?? "", pageIndex: pageIndex,
                        children: nested.isEmpty ? nil : nested)
        }
    }
}

struct PDFOutlineView: View {
    let entries: [PDFOutlineEntry]
    let open: (Int) -> Void

    var body: some View {
        List {
            OutlineGroup(entries, children: \.children) { entry in
                if let index = entry.pageIndex {
                    Button { open(index) } label: {
                        title(for: entry)
                            .frame(minHeight: 48)
                    }
                } else {
                    title(for: entry)
                }
            }
        }
        .navigationTitle("PDF outline")
    }

    private func title(for entry: PDFOutlineEntry) -> Text {
        entry.label.isEmpty ? Text("Untitled section") : Text(entry.label)
    }
}

enum PDFPageSearch {
    private static let options: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]

    static func matchRects(on page: PDFPage, query: String, displaySize: CGSize) -> [CGRect] {
        guard !query.isEmpty, let text = page.string, let reference = page.pageRef else { return [] }
        let display = CGRect(origin: .zero, size: displaySize)
        let transform = reference.getDrawingTransform(.cropBox, rect: display, rotate: 0, preserveAspectRatio: true)
        var cursor = text.startIndex
        var rectangles: [CGRect] = []
        for _ in 0..<100 {
            guard cursor < text.endIndex,
                  let range = text.range(of: query, options: options, range: cursor..<text.endIndex,
                                         locale: .current), !range.isEmpty else { break }
            cursor = range.upperBound
            guard let selection = page.selection(for: NSRange(range, in: text)) else { continue }
            for line in selection.selectionsByLine() {
                let bottomUp = line.bounds(for: page).applying(transform)
                let topDown = CGRect(x: bottomUp.minX, y: display.height - bottomUp.maxY,
                                     width: bottomUp.width, height: bottomUp.height)
                let clipped = topDown.intersection(display)
                if !clipped.isEmpty && !clipped.isInfinite { rectangles.append(clipped) }
            }
        }
        return rectangles
    }

    static func snippet(in text: String, matching query: String) -> String? {
        guard !query.isEmpty, let range = text.range(of: query,
            options: options, locale: .current) else { return nil }
        let start = text.index(range.lowerBound, offsetBy: -36, limitedBy: text.startIndex) ?? text.startIndex
        let end = text.index(range.upperBound, offsetBy: 54, limitedBy: text.endIndex) ?? text.endIndex
        let excerpt = text[start..<end].split(whereSeparator: \.isWhitespace).joined(separator: " ")
        return (start == text.startIndex ? "" : "…") + excerpt + (end == text.endIndex ? "" : "…")
    }
}
