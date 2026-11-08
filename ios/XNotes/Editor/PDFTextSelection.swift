import PDFKit
import SwiftUI

struct PDFTextSelectionRequest: Identifiable {
    let id = UUID()
    let pageID: UUID
    let background: PDFPageReference?
    let digest: String?
    let original: PDFPage
    let originalDocument: PDFDocument?

    init(pageID: UUID, background: PDFPageReference?, digest: String?, original: PDFPage) {
        self.pageID = pageID; self.background = background; self.digest = digest; self.original = original
        originalDocument = original.document
    }
}

struct PDFTextSelectionSheet: View {
    let request: PDFTextSelectionRequest
    let save: (PDFTextMarkup) -> Bool
    @Environment(\.dismiss) private var dismiss
    @State private var selection: PDFTextMarkup?
    @State private var kind = PDFTextMarkup.Kind.highlight
    @State private var failed = false

    var body: some View {
        NavigationStack {
            VStack {
                if request.originalDocument?.allowsCopying == false {
                    ContentUnavailableView("PDF text selection unavailable", systemImage: "lock")
                } else if request.original.string?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty != false {
                    ContentUnavailableView("No selectable PDF text", systemImage: "text.viewfinder")
                } else {
                    PDFTextSelectionReader(page: request.original) { selection = $0 }
                    Text("Touch and hold text, then drag the selection handles.").foregroundStyle(UITokens.secondaryText)
                }
                Picker("PDF text markup", selection: $kind) {
                    ForEach(PDFTextMarkup.Kind.allCases, id: \.self) { kind in Text(kind.label).tag(kind) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .frame(minHeight: UITokens.minTouchTarget)
                if failed { Text("The selected page changed or the markup could not be saved.") }
            }
            .navigationTitle("Select PDF text")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        guard let selection else { return }
                        let mark = PDFTextMarkup(kind: kind, query: selection.query, rectangles: selection.regions, paper: CGSize(width: 1, height: 1))
                        if save(mark) { dismiss() } else { failed = true }
                    }.disabled(selection?.isValid != true)
                }
            }
        }.tint(.primary)
    }
}

struct PDFTextSelectionReader: UIViewRepresentable {
    let page: PDFPage
    let changed: (PDFTextMarkup?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(changed: changed) }
    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        let document = PDFDocument()
        if page.document?.allowsCopying != false, let copy = page.copy() as? PDFPage { document.insert(copy, at: 0) }
        view.document = document
        view.autoScales = true
        view.displayMode = .singlePage
        view.displayBox = .cropBox
        view.displaysPageBreaks = false
        view.pageShadowsEnabled = false
        view.backgroundColor = .white
        view.tintColor = .black
        context.coordinator.view = view
        NotificationCenter.default.addObserver(context.coordinator, selector: #selector(Coordinator.selectionChanged), name: .PDFViewSelectionChanged, object: view)
        return view
    }
    func updateUIView(_ view: PDFView, context: Context) {}
    static func dismantleUIView(_ view: PDFView, coordinator: Coordinator) {
        NotificationCenter.default.removeObserver(coordinator)
        view.highlightedSelections = nil
        view.document = nil
    }

    final class Coordinator: NSObject {
        weak var view: PDFView?
        let changed: (PDFTextMarkup?) -> Void
        init(changed: @escaping (PDFTextMarkup?) -> Void) { self.changed = changed }
        @objc func selectionChanged() {
            guard let selection = view?.currentSelection, let page = view?.document?.page(at: 0),
                  let text = selection.string, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  let size = try? PDFPageGeometry.displaySize(page) else { view?.highlightedSelections = nil; changed(nil); return }
            selection.color = UIColor(white: 0.5, alpha: 0.35)
            view?.highlightedSelections = [selection]
            view?.setNeedsDisplay()
            let rectangles = PDFSelectionGeometry.rectangles(selection, on: page, displaySize: size)
            let mark = PDFTextMarkup(kind: .highlight, query: text, rectangles: rectangles, paper: size)
            changed(mark.isValid ? mark : nil)
        }
    }
}
