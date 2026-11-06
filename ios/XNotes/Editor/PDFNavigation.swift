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
    static func snippet(in text: String, matching query: String) -> String? {
        guard !query.isEmpty, let range = text.range(of: query,
            options: [.caseInsensitive, .diacriticInsensitive], locale: .current) else { return nil }
        let start = text.index(range.lowerBound, offsetBy: -36, limitedBy: text.startIndex) ?? text.startIndex
        let end = text.index(range.upperBound, offsetBy: 54, limitedBy: text.endIndex) ?? text.endIndex
        let excerpt = text[start..<end].split(whereSeparator: \.isWhitespace).joined(separator: " ")
        return (start == text.startIndex ? "" : "…") + excerpt + (end == text.endIndex ? "" : "…")
    }
}
