import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @Bindable var library: LibraryStore
    @AppStorage("appearance") private var appearance = "system"
    @State private var query = ""
    @State private var showTrash = false
    @State private var importing = false
    @State private var exporting = false
    @State private var export = ArchiveFile()
    @State private var exportTitle = ""
    @State private var primaryID: UUID?
    @State private var secondaryID: UUID?
    @State private var renaming: Notebook?
    @State private var deleting: Notebook?
    @State private var title = ""

    private var documents: [Notebook] {
        library.notebooks.filter { notebook in
            (notebook.trashedAt != nil) == showTrash &&
            (query.isEmpty || notebook.title.localizedStandardContains(query) ||
                notebook.pages.contains { $0.text.localizedStandardContains(query) })
        }.sorted { $0.modified > $1.modified }
    }

    var body: some View {
        Group {
            if let primaryID, let notebook = openNotebook(primaryID) {
                HStack(spacing: 0) {
                    editor(notebook) {
                        self.primaryID = secondaryID
                        secondaryID = nil
                    }
                    if let secondaryID, let other = openNotebook(secondaryID) {
                        Divider()
                        editor(other) { self.secondaryID = nil }
                    }
                }
            } else {
                explorer
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.pdf, .xnotesArchive]) { result in
            perform {
                primaryID = try library.importFile(result.get())
                secondaryID = nil
            }
        }
        .fileExporter(isPresented: $exporting, document: export, contentType: .xnotesArchive, defaultFilename: exportTitle) {
            if case .failure(let error) = $0 { library.issue = error.localizedDescription }
        }
        .alert("Storage error", isPresented: Binding(
            get: { library.issue != nil },
            set: { if !$0 { library.issue = nil } }
        )) {
            Button("OK", role: .cancel) { library.issue = nil }
        } message: {
            Text(library.issue ?? "")
        }
        .alert("Rename", isPresented: Binding(
            get: { renaming != nil },
            set: { if !$0 { renaming = nil } }
        )) {
            TextField("Title", text: $title)
            Button("Save") {
                guard var notebook = renaming else { return }
                let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { return }
                notebook.title = trimmed
                perform { try library.save(notebook) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Permanently delete this document?", isPresented: Binding(
            get: { deleting != nil },
            set: { if !$0 { deleting = nil } }
        ), titleVisibility: .visible) {
            Button("Delete permanently", role: .destructive) {
                if let deleting { perform { try library.delete(deleting) } }
            }
        }
    }

    private var explorer: some View {
        NavigationStack {
            List {
                if library.unreadableCount > 0 {
                    Text("Some documents could not be read. Their files were kept.")
                }
                if documents.isEmpty {
                    ContentUnavailableView(showTrash ? "Trash is empty" : "No documents", systemImage: "tray")
                }
                ForEach(documents) { notebook in
                    Button {
                        if !showTrash { primaryID = notebook.id }
                    } label: {
                        Label(notebook.title, systemImage: notebook.kind.symbol)
                    }
                    .contextMenu {
                        if showTrash {
                            Button("Restore", systemImage: "arrow.uturn.backward") { setTrashed(notebook, false) }
                            Button("Delete permanently", systemImage: "trash", role: .destructive) { deleting = notebook }
                        } else {
                            Button("Rename", systemImage: "pencil") {
                                title = notebook.title
                                renaming = notebook
                            }
                            Button("Export backup", systemImage: "square.and.arrow.up") { exportNotebook(notebook) }
                            Button("Move to Trash", systemImage: "trash", role: .destructive) { setTrashed(notebook, true) }
                        }
                    }
                }
            }
            .navigationTitle(showTrash ? "Trash" : "XNotes")
            .searchable(text: $query)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu("Library options", systemImage: "line.3.horizontal") {
                        Button(showTrash ? "Library" : "Trash", systemImage: showTrash ? "books.vertical" : "trash") {
                            showTrash.toggle()
                            query = ""
                        }
                        Picker("Appearance", selection: $appearance) {
                            Text("System").tag("system")
                            Text("Light").tag("light")
                            Text("Dark").tag("dark")
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu("Add document", systemImage: "plus") {
                        Button("New notebook", systemImage: "book.closed") { create(.notebook) }
                        Button("New canvas", systemImage: "square.dashed") { create(.canvas) }
                        Button("Import PDF or backup", systemImage: "square.and.arrow.down") { importing = true }
                    }
                }
            }
        }
    }

    private func editor(_ notebook: Notebook, close: @escaping () -> Void) -> some View {
        NotebookEditor(notebook: notebook, library: library, close: close) {
            ForEach(library.notebooks.filter { $0.trashedAt == nil && $0.id != primaryID }) { other in
                Button(other.title) { secondaryID = other.id }
            }
        }
        .id(notebook.id)
    }

    private func openNotebook(_ id: UUID) -> Notebook? {
        library.notebooks.first { $0.id == id && $0.trashedAt == nil }
    }

    private func create(_ kind: NotebookKind) {
        let notebook = Notebook(title: kind == .canvas ? String(localized: "Untitled canvas") : String(localized: "Untitled notebook"), kind: kind)
        perform {
            try library.save(notebook)
            primaryID = notebook.id
        }
    }

    private func setTrashed(_ notebook: Notebook, _ trashed: Bool) {
        var updated = notebook
        updated.trashedAt = trashed ? .now : nil
        perform { try library.save(updated) }
    }

    private func exportNotebook(_ notebook: Notebook) {
        perform {
            export = ArchiveFile(data: try library.archive(for: notebook))
            exportTitle = notebook.title
            exporting = true
        }
    }

    private func perform(_ operation: () throws -> Void) {
        do { try operation() } catch { library.issue = error.localizedDescription }
    }
}

private struct ArchiveFile: FileDocument {
    static let readableContentTypes: [UTType] = [.xnotesArchive]
    var data = Data()

    init(data: Data = Data()) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
