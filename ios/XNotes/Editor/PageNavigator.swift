import PDFKit
import SwiftUI
import UniformTypeIdentifiers

struct PageNavigator: View {
    let pages: [NotePage]
    let current: UUID
    let editable: Bool
    let pdf: PDFDocument?
    let pagePDFs: [String: PDFDocument]
    let documentTitle: String
    let bookmarkEditing: Bool
    let editBookmark: (BookmarkEdit, UUID) -> Bool
    let exportImages: (Set<UUID>) throws -> [ExportFile]
    let exportPages: (Set<UUID>) throws -> Data
    let open: (UUID) -> Void
    let openPDFMatch: (UUID, String) -> Void
    let add: () -> Void
    let copyPages: (Set<UUID>) -> Bool
    let pasteCount: Int
    let pastePages: () -> Void
    let duplicate: (Set<UUID>) -> Bool
    let delete: (Set<UUID>) -> Bool
    let editPaper: (PagePaperEdit, Set<UUID>) -> Bool
    let move: (IndexSet, Int) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var deleting: Set<UUID> = []
    @State private var selecting = false
    @State private var export: [ExportFile] = []
    @State private var exportType: UTType = .pdf
    @State private var showingExporter = false
    @State private var exportIssue: String?
    @State private var paperRequest: PaperRequest?

    private struct PaperRequest: Identifiable {
        enum Kind { case size, pattern }
        let id = UUID()
        let kind: Kind
        let ids: Set<UUID>
        let firstSize: NotebookPaperSize
        let commonPattern: PaperPattern?
    }
    @State private var selected: Set<UUID> = []
    @State private var editMode: EditMode = .inactive
    @State private var pdfMatches: [UUID: String] = [:]
    @State private var searchedQuery = ""
    @State private var searching = false
    @State private var outline: [PDFOutlineEntry] = []

    private var sources: PDFPageSources { PDFPageSources(original: pdf, inserted: pagePDFs) }

    private var term: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var currentPDFMatches: [UUID: String] { searchedQuery == term ? pdfMatches : [:] }

    var body: some View {
        NavigationStack {
            List {
                if term.isEmpty {
                    ForEach(Array(pages.enumerated()), id: \.element.id) { index, page in row(page, index: index) }
                        .onMove(perform: editable && !selecting ? move : nil)
                } else {
                    let snippets = currentPDFMatches
                    let matches = pages.enumerated().filter { _, page in
                        page.text.localizedStandardContains(term) || snippets[page.id] != nil
                    }
                    if sources.hasTextSource && (searching || searchedQuery != term) {
                        ProgressView("Searching PDF")
                    } else if matches.isEmpty {
                        ContentUnavailableView.search(text: term)
                    }
                    ForEach(matches, id: \.element.id) { index, page in
                        row(page, index: index, pdfSnippet: snippets[page.id])
                    }
                }
            }
            .environment(\.editMode, $editMode)
            .searchable(text: $query)
            .task {
                if let pdf { outline = PDFOutlineEntry.entries(in: pdf) }
            }
            .task(id: term) { await searchPDF(for: term) }
            .navigationTitle("Pages")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(selecting ? "Cancel selection" : "Done") {
                        if selecting { endSelection() } else { dismiss() }
                    }
                }
                if !selecting {
                    ToolbarItem(placement: .primaryAction) {
                        NavigationLink {
                            PageBookmarksView(pages: pages, current: current, editable: bookmarkEditing,
                                              edit: editBookmark) { page in
                                open(page)
                                dismiss()
                            }
                        } label: {
                            Label("Bookmarks", systemImage: "bookmark")
                        }
                    }
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
                ToolbarItemGroup(placement: .primaryAction) {
                    if selecting {
                        Button("Select all pages") { selected = Set(pages.map(\.id)) }
                            .disabled(selected.count == pages.count)
                    } else {
                        Button("Select pages") {
                            editMode = .inactive
                            selecting = true
                        }
                        if editable && term.isEmpty {
                            Button(editMode.isEditing ? "Done reordering" : "Reorder pages") {
                                editMode = editMode.isEditing ? .inactive : .active
                            }
                            Menu("Add page", systemImage: "plus") {
                                Button("Add page", action: add)
                                if pasteCount > 0 {
                                    Button("Paste \(pasteCount) pages", action: pastePages)
                                }
                            }
                        }
                    }
                }
                if selecting {
                    ToolbarItemGroup(placement: .bottomBar) {
                        Text("\(selected.count) selected")
                        Spacer()
                        Menu("Export selected pages", systemImage: "square.and.arrow.up") {
                            Button("Export selected pages as PDF") { prepareExport(asImages: false) }
                            Button("Export selected pages as PNG") { prepareExport(asImages: true) }
                        }
                        .disabled(selected.isEmpty)
                        Button("Copy selected pages", systemImage: "doc.on.doc") {
                            if copyPages(selected) { endSelection() }
                        }
                        .disabled(selected.isEmpty)
                        if editable {
                            Menu("Edit selected paper", systemImage: "doc") {
                                Button("Paper size") { requestPaper(.size) }
                                Button("Paper template") { requestPaper(.pattern) }
                            }
                            .disabled(selected.isEmpty)
                            Button("Duplicate pages", systemImage: "plus.square.on.square") {
                                if duplicate(selected) { endSelection() }
                            }
                            .disabled(selected.isEmpty)
                            Button("Delete pages", systemImage: "trash", role: .destructive) {
                                deleting = selected
                            }
                            .disabled(selected.isEmpty || selected.count >= pages.count)
                        }
                    }
                }
            }
            .fileExporter(isPresented: $showingExporter, documents: export, contentType: exportType) { result in
                export = []
                if case .failure(let error) = result { exportIssue = error.localizedDescription }
            }
            .alert("Page export failed", isPresented: Binding(
                get: { exportIssue != nil },
                set: { if !$0 { exportIssue = nil } }
            )) {
                Button("OK", role: .cancel) { exportIssue = nil }
            } message: {
                Text(exportIssue ?? "")
            }
            .sheet(item: $paperRequest) { request in
                switch request.kind {
                case .size:
                    PaperSizePicker(size: request.firstSize) { size in
                        applyPaper(.size(size), to: request.ids)
                    }
                case .pattern:
                    PaperPicker(selection: request.commonPattern) { pattern in
                        applyPaper(.pattern(pattern), to: request.ids)
                    }
                }
            }
            .confirmationDialog(deleting.count == 1 ? "Delete this page?" : "Delete selected pages?", isPresented: Binding(
                get: { !deleting.isEmpty },
                set: { if !$0 { deleting = [] } }
            ), titleVisibility: .visible) {
                Button(deleting.count == 1 ? "Delete page" : "Delete pages", role: .destructive) {
                    if delete(deleting) { endSelection() }
                    deleting = []
                }
            }
            .onChange(of: editable) {
                if !editable {
                    endSelection()
                    paperRequest = nil
                    deleting = []
                    editMode = .inactive
                }
            }
        }
    }

    private func prepareExport(asImages: Bool) {
        do {
            if asImages {
                export = try exportImages(selected)
                exportType = .png
            } else {
                export = [ExportFile(data: try exportPages(selected), filename: documentTitle + ".pdf")]
                exportType = .pdf
            }
            showingExporter = true
        } catch {
            exportIssue = error.localizedDescription
        }
    }

    private func requestPaper(_ kind: PaperRequest.Kind) {
        let targets = pages.filter { selected.contains($0.id) }
        guard let first = targets.first else { return }
        let patterns = Set(targets.map { $0.paperPattern ?? .blank })
        paperRequest = PaperRequest(kind: kind, ids: selected, firstSize: first.paperSize ?? .letter,
                                    commonPattern: patterns.count == 1 ? patterns.first : nil)
    }

    private func applyPaper(_ edit: PagePaperEdit, to ids: Set<UUID>) -> Bool {
        guard editable, editPaper(edit, ids) else { return false }
        endSelection()
        return true
    }

    private func endSelection() {
        selecting = false
        selected = []
    }

    @MainActor
    private func searchPDF(for term: String) async {
        let sources = sources
        guard sources.hasTextSource, !term.isEmpty else {
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
                if let text = sources.page(for: page, at: index)?.string,
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

    private func row(_ page: NotePage, index: Int, pdfSnippet: String? = nil) -> some View {
        Button {
            if selecting {
                if selected.remove(page.id) == nil { selected.insert(page.id) }
            } else {
                if pdfSnippet != nil {
                    openPDFMatch(page.id, term)
                } else {
                    open(page.id)
                }
                dismiss()
            }
        } label: {
            HStack(spacing: 12) {
                PageThumbnail(page: page, index: page.pdfBackground?.index ?? index,
                              pdf: sources.document(for: page))
                    .accessibilityHidden(true)
                VStack(alignment: .leading) {
                    Text("Page \(index + 1)")
                    if let pdfSnippet {
                        Text(pdfSnippet).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                    if !page.text.isEmpty {
                        Text(page.text).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                }
                Spacer()
                if page.bookmarks?.isEmpty == false {
                    Image(systemName: "bookmark.fill")
                        .accessibilityLabel(Text("Bookmarks"))
                }
                if selecting {
                    Image(systemName: selected.contains(page.id) ? "checkmark.circle.fill" : "circle")
                } else if page.id == current {
                    Image(systemName: "checkmark")
                }
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .accessibilityAddTraits(selecting && selected.contains(page.id) ? .isSelected : [])
        .contextMenu {
            if !selecting {
                Button("Copy page", systemImage: "doc.on.doc") { _ = copyPages([page.id]) }
            }
            if editable && !selecting {
                Button("Duplicate page", systemImage: "plus.square.on.square") { _ = duplicate([page.id]) }
                Button("Delete page", systemImage: "trash", role: .destructive) { deleting = [page.id] }
                    .disabled(pages.count == 1)
            }
        }
    }
}
