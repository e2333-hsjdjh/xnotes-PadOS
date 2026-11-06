import PDFKit
import SwiftUI

struct PageNavigator: View {
    let pages: [NotePage]
    let current: UUID
    let editable: Bool
    let pdf: PDFDocument?
    let open: (UUID) -> Void
    let add: () -> Void
    let duplicate: (UUID) -> Void
    let delete: (UUID) -> Void
    let move: (IndexSet, Int) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var deleting: UUID?
    @State private var pdfMatches: [UUID: String] = [:]
    @State private var searchedQuery = ""
    @State private var searching = false
    @State private var outline: [PDFOutlineEntry] = []

    private var term: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var currentPDFMatches: [UUID: String] { searchedQuery == term ? pdfMatches : [:] }

    var body: some View {
        NavigationStack {
            List {
                if term.isEmpty {
                    ForEach(pages) { page in row(page) }
                        .onMove(perform: editable ? move : nil)
                } else {
                    let snippets = currentPDFMatches
                    let matches = pages.filter { $0.text.localizedStandardContains(term) || snippets[$0.id] != nil }
                    if pdf != nil && (searching || searchedQuery != term) {
                        ProgressView("Searching PDF")
                    } else if matches.isEmpty {
                        ContentUnavailableView.search(text: term)
                    }
                    ForEach(matches) { page in row(page, pdfSnippet: snippets[page.id]) }
                }
            }
            .searchable(text: $query)
            .task {
                if let pdf { outline = PDFOutlineEntry.entries(in: pdf) }
            }
            .task(id: term) { await searchPDF(for: term) }
            .navigationTitle("Pages")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                if !outline.isEmpty {
                    ToolbarItem(placement: .primaryAction) {
                        NavigationLink {
                            PDFOutlineView(entries: outline) { index in
                                guard pages.indices.contains(index) else { return }
                                open(pages[index].id)
                                dismiss()
                            }
                        } label: {
                            Label("PDF outline", systemImage: "list.bullet.indent")
                        }
                    }
                }
                if editable && term.isEmpty {
                    ToolbarItemGroup(placement: .primaryAction) {
                        EditButton()
                        Button("Add page", systemImage: "plus", action: add)
                    }
                }
            }
            .confirmationDialog("Delete this page?", isPresented: Binding(
                get: { deleting != nil },
                set: { if !$0 { deleting = nil } }
            ), titleVisibility: .visible) {
                Button("Delete page", role: .destructive) {
                    if let deleting { delete(deleting) }
                }
            }
        }
    }

    @MainActor
    private func searchPDF(for term: String) async {
        guard let pdf, !term.isEmpty else {
            pdfMatches = [:]
            searchedQuery = ""
            searching = false
            return
        }
        searching = true
        searchedQuery = term
        pdfMatches = [:]
        do {
            try await Task.sleep(for: .milliseconds(180))
            var matches: [UUID: String] = [:]
            for (index, page) in pages.enumerated() {
                try Task.checkCancellation()
                if let text = pdf.page(at: index)?.string,
                   let snippet = PDFPageSearch.snippet(in: text, matching: term) {
                    matches[page.id] = snippet
                }
                if index % 8 == 7 {
                    pdfMatches = matches
                    await Task.yield()
                }
            }
            try Task.checkCancellation()
            pdfMatches = matches
            searching = false
        } catch {
            // The task for the next query owns the search state.
        }
    }

    private func row(_ page: NotePage, pdfSnippet: String? = nil) -> some View {
        let number = (pages.firstIndex { $0.id == page.id } ?? 0) + 1
        return Button {
            open(page.id)
            dismiss()
        } label: {
            HStack {
                VStack(alignment: .leading) {
                    Text("Page \(number)")
                    if let pdfSnippet {
                        Text(pdfSnippet).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                    if !page.text.isEmpty {
                        Text(page.text).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                }
                Spacer()
                if page.id == current { Image(systemName: "checkmark") }
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .contextMenu {
            if editable {
                Button("Duplicate page", systemImage: "plus.square.on.square") { duplicate(page.id) }
                Button("Delete page", systemImage: "trash", role: .destructive) { deleting = page.id }
                    .disabled(pages.count == 1)
            }
        }
    }
}
