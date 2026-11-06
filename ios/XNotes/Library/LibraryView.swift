import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @Bindable var library: LibraryStore
    @AppStorage("appearance") private var appearance = "system"
    @AppStorage("librarySort") private var sort = LibrarySort.modified
    @AppStorage("libraryDescending") private var descending = true
    @AppStorage("libraryLayout") private var layout = LibraryLayout.list
    @State private var query = ""
    @State private var folderID: UUID?
    @State private var folderDraft: LibraryFolder?
    @State private var folderName = ""
    @State private var showFolders = false
    @State private var moveDocumentID: UUID?
    @State private var showTrash = false
    @State private var importing = false
    @State private var exporting = false
    @State private var export = ExportFile()
    @State private var exportType: UTType = .xnotesArchive
    @State private var exportTitle = ""
    @State private var panes: [DocumentPane] = []
    @State private var renaming: Notebook?
    @State private var deleting: Notebook?
    @State private var title = ""

    private var currentFolder: LibraryFolder? { library.folders.first { $0.id == folderID } }

    private var childFolders: [LibraryFolder] {
        guard !showTrash else { return [] }
        return library.folders.filter {
            $0.parentID == currentFolder?.id && (query.isEmpty || $0.name.localizedStandardContains(query))
        }.sorted {
            $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }

    private var documents: [Notebook] {
        let folderIDs = Set(library.folders.map(\.id))
        return library.notebooks.filter { notebook in
            (notebook.trashedAt != nil) == showTrash &&
            (showTrash || (notebook.folderID.flatMap { folderIDs.contains($0) ? $0 : nil }) == currentFolder?.id) &&
            (query.isEmpty || notebook.title.localizedStandardContains(query) ||
                notebook.pages.contains { $0.text.localizedStandardContains(query) })
        }.sorted { sort.precedes($0, $1, descending: descending) }
    }

    var body: some View {
        Group {
            if !panes.isEmpty {
                HStack(spacing: 0) {
                    ForEach(panes) { pane in
                        if let notebook = openNotebook(pane.documentID) {
                            if pane.id != panes.first?.id { Divider() }
                            editor(notebook, in: pane)
                        }
                    }
                }
            } else {
                explorer
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.pdf, .xnotesArchive]) { result in
            perform {
                open(try library.importFile(result.get(), into: currentFolder?.id))
            }
        }
        .fileExporter(isPresented: $exporting, document: export, contentType: exportType, defaultFilename: exportTitle) {
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
        .alert("Folder name", isPresented: Binding(
            get: { folderDraft != nil },
            set: { if !$0 { folderDraft = nil } }
        )) {
            TextField("Name", text: $folderName)
            Button("Save") {
                guard var folder = folderDraft else { return }
                let name = folderName.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !name.isEmpty else { return }
                folder.name = name
                perform { try library.saveFolder(folder) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(isPresented: $showFolders, onDismiss: { moveDocumentID = nil }) {
            FolderPicker(folders: library.folders) { destination in
                if let moveDocumentID {
                    perform { try library.moveDocument(moveDocumentID, to: destination) }
                } else {
                    folderID = destination
                    query = ""
                }
            }
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
            Group {
                if layout == .grid {
                    ScrollView {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 132), spacing: 12)], spacing: 12) {
                            libraryEntries
                        }
                        .padding()
                    }
                } else {
                    List { libraryEntries }
                }
            }
            .navigationTitle(showTrash ? Text("Trash") : Text(currentFolder?.name ?? "XNotes"))
            .searchable(text: $query)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu("Library options", systemImage: "line.3.horizontal") {
                        Button(showTrash ? "Library" : "Trash", systemImage: showTrash ? "books.vertical" : "trash") {
                            showTrash.toggle()
                            query = ""
                        }
                        if !showTrash {
                            Button("Folders", systemImage: "folder") { showFolders = true }
                        }
                        Picker("Layout", selection: $layout) {
                            Text("List").tag(LibraryLayout.list)
                            Text("Grid").tag(LibraryLayout.grid)
                        }
                        Picker("Sort by", selection: $sort) {
                            Text("Name").tag(LibrarySort.name)
                            Text("Date modified").tag(LibrarySort.modified)
                            Text("Date created").tag(LibrarySort.created)
                        }
                        Toggle("Descending order", isOn: $descending)
                        Picker("Appearance", selection: $appearance) {
                            Text("System").tag("system")
                            Text("Light").tag("light")
                            Text("Dark").tag("dark")
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu("Add document", systemImage: "plus") {
                        Button("New folder", systemImage: "folder.badge.plus") {
                            editFolder(LibraryFolder(name: "", parentID: currentFolder?.id))
                        }
                        Button("New notebook", systemImage: "book.closed") { create(.notebook) }
                        Button("New canvas", systemImage: "square.dashed") { create(.canvas) }
                        Button("Import PDF or backup", systemImage: "square.and.arrow.down") { importing = true }
                    }
                    .disabled(showTrash)
                }
            }
        }
    }

    @ViewBuilder private var libraryEntries: some View {
        if library.unreadableCount > 0 {
            Text("Some documents could not be read. Their files were kept.")
        }
        if !showTrash, let currentFolder {
            Button {
                folderID = currentFolder.parentID
                query = ""
            } label: {
                entryLabel(Text("Parent folder"), symbol: "arrow.up")
            }
        }
        ForEach(childFolders) { folder in
            Button {
                folderID = folder.id
                query = ""
            } label: {
                entryLabel(Text(verbatim: folder.name), symbol: "folder")
            }
            .contextMenu {
                Button("Rename", systemImage: "pencil") { editFolder(folder) }
                Button("Delete empty folder", systemImage: "trash", role: .destructive) {
                    perform { try library.deleteFolder(folder) }
                }
                .disabled(library.unreadableCount > 0 || library.folders.contains { $0.parentID == folder.id } ||
                          library.notebooks.contains { $0.folderID == folder.id })
            }
        }
        if documents.isEmpty && childFolders.isEmpty {
            if query.isEmpty {
                ContentUnavailableView(showTrash ? "Trash is empty" : "No documents", systemImage: "tray")
            } else {
                ContentUnavailableView.search(text: query)
            }
        }
        ForEach(documents) { notebook in
            Button {
                if !showTrash { open(notebook.id) }
            } label: {
                entryLabel(Text(verbatim: notebook.title), symbol: notebook.kind.symbol)
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
                    Button("Move to folder", systemImage: "folder") {
                        moveDocumentID = notebook.id
                        showFolders = true
                    }
                    Button("Export backup", systemImage: "square.and.arrow.up") { exportNotebook(notebook) }
                    if notebook.kind != .canvas {
                        Button("Export PDF", systemImage: "doc.richtext") { exportPDF(notebook) }
                    }
                    Button("Move to Trash", systemImage: "trash", role: .destructive) { setTrashed(notebook, true) }
                }
            }
        }
    }

    @ViewBuilder private func entryLabel(_ title: Text, symbol: String) -> some View {
        if layout == .grid {
            VStack(spacing: 8) {
                Image(systemName: symbol).font(.title2)
                title.font(.subheadline).lineLimit(2)
            }
            .frame(maxWidth: .infinity, minHeight: 92)
            .padding(8)
            .background(.quaternary, in: RoundedRectangle(cornerRadius: 10))
            .contentShape(Rectangle())
        } else {
            Label { title } icon: { Image(systemName: symbol) }
        }
    }

    private func editor(_ notebook: Notebook, in pane: DocumentPane) -> some View {
        let shared = panes.first(where: { $0.documentID == notebook.id })?.id != pane.id
        return NotebookEditor(notebook: notebook, library: library, readOnly: shared, close: {
            panes.removeAll { $0.id == pane.id }
        }) {
            if notebook.kind != .canvas {
                Button("Open another view", systemImage: "rectangle.on.rectangle") {
                    openBeside(pane, documentID: notebook.id)
                }
            }
            ForEach(library.notebooks.filter { $0.trashedAt == nil && $0.id != notebook.id }) { other in
                Button(other.title) { openBeside(pane, documentID: other.id) }
            }
        }
        .id(notebook.id)
    }

    private func open(_ documentID: UUID) {
        panes = [DocumentPane(documentID: documentID)]
    }

    private func openBeside(_ pane: DocumentPane, documentID: UUID) {
        guard panes.contains(where: { $0.id == pane.id }) else { return }
        if let index = panes.firstIndex(where: { $0.id != pane.id }) {
            panes[index].documentID = documentID
        } else {
            panes.append(DocumentPane(documentID: documentID))
        }
    }

    private func openNotebook(_ id: UUID) -> Notebook? {
        library.notebooks.first { $0.id == id && $0.trashedAt == nil }
    }

    private func create(_ kind: NotebookKind) {
        let notebook = Notebook(title: kind == .canvas ? String(localized: "Untitled canvas") : String(localized: "Untitled notebook"), kind: kind, folderID: currentFolder?.id)
        perform {
            try library.save(notebook)
            open(notebook.id)
        }
    }

    private func editFolder(_ folder: LibraryFolder) {
        folderName = folder.name
        folderDraft = folder
    }

    private func setTrashed(_ notebook: Notebook, _ trashed: Bool) {
        var updated = notebook
        updated.trashedAt = trashed ? .now : nil
        perform { try library.save(updated) }
    }

    private func exportNotebook(_ notebook: Notebook) {
        perform {
            export = ExportFile(data: try library.archive(for: notebook))
            exportType = .xnotesArchive
            exportTitle = notebook.title
            exporting = true
        }
    }

    private func exportPDF(_ notebook: Notebook) {
        perform {
            export = ExportFile(data: try NotebookPDFExport.data(for: notebook, source: library.pdf(for: notebook)))
            exportType = .pdf
            exportTitle = notebook.title
            exporting = true
        }
    }

    private func perform(_ operation: () throws -> Void) {
        do { try operation() } catch { library.issue = error.localizedDescription }
    }
}

private struct DocumentPane: Identifiable {
    let id = UUID()
    var documentID: UUID
}

private struct ExportFile: FileDocument {
    static let readableContentTypes: [UTType] = [.xnotesArchive, .pdf]
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
