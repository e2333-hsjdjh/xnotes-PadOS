import SwiftUI
import UniformTypeIdentifiers

struct LibraryView: View {
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("trashRetentionDays") private var trashRetention = TrashRetention.untilEmptied
    @State private var pendingRetention: TrashRetention?
    @Environment(\.colorScheme) private var scheme
    @Bindable var library: LibraryStore
    @AppStorage("appearance") private var appearance = "system"
    @AppStorage("librarySort") private var sort = LibrarySort.modified
    @AppStorage("libraryDescending") private var descending = true
    @AppStorage("libraryLayout") private var layout = LibraryLayout.list
    @State private var query = ""
    @State private var folderID: UUID?
    @State private var folderDraft: LibraryFolder?
    @State private var folderName = ""
    @State private var folderRequest: FolderRequest?
    @State private var selectingDocuments = false
    @State private var selectedDocuments: Set<UUID> = []
    @State private var showTrash = false
    @State private var importing = false
    @State private var exporting = false
    @State private var export: [ExportFile] = []
    @State private var exportType: UTType = .xnotesArchive
    @State private var panes: [DocumentPane] = []
    @AppStorage("lastSplitWorkspace") private var lastSplitWorkspace = Data()
    @State private var renaming: Notebook?
    @State private var deleting: Set<UUID> = []
    @State private var deleteWithoutTrash = false
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

    private var selectedForExport: [Notebook] {
        library.notebooks.filter { selectedDocuments.contains($0.id) && $0.trashedAt == nil }
            .sorted { sort.precedes($0, $1, descending: descending) }
    }

    private var restorableSplit: SplitWorkspace? {
        guard let saved = try? JSONDecoder().decode(SplitWorkspace.self, from: lastSplitWorkspace),
              saved.canRestore(from: library.notebooks) else { return nil }
        return saved
    }

    private var availablePanes: [DocumentPane] {
        DocumentPane.available(panes) { openNotebook($0) != nil }
    }

    var body: some View {
        Group {
            let activePanes = availablePanes
            if !activePanes.isEmpty {
                HStack(spacing: 0) {
                    ForEach(activePanes) { pane in
                        if pane.id != activePanes.first?.id { Divider() }
                        if let id = pane.documentID, let notebook = openNotebook(id) {
                            editor(notebook, in: pane)
                        } else {
                            PaneLibraryView(documents: paneDocuments(for: pane), folders: library.folders,
                                            open: { replace(pane, with: $0) },
                                            close: { removePane(pane) })
                        }
                    }
                }
            } else {
                explorer
            }
        }
        .onChange(of: availablePanes) { _, active in
            if panes != active { panes = active }
            rememberSplit(active)
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.pdf, .xnotesArchive]) { result in
            perform {
                open(try library.importFile(result.get(), into: currentFolder?.id))
            }
        }
        .fileExporter(isPresented: $exporting, documents: export, contentType: exportType) {
            export = []
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
        .sheet(item: $folderRequest) { request in
            FolderPicker(folders: folderDestinations(for: request)) { destination in
                switch request.action {
                case .browse:
                    folderID = destination
                    query = ""
                case .moveDocuments(let ids):
                    applyBatch(.move(destination), to: ids)
                case .moveFolder(let id):
                    perform { try library.moveFolder(id, to: destination) }
                }
            }
        }
        .onAppear { maintainStorage() }
        .onChange(of: scenePhase) { if scenePhase == .active { maintainStorage() } }
        .onChange(of: trashRetention) { purgeExpiredTrash() }
        .alert("Change trash retention?", isPresented: Binding(
            get: { pendingRetention != nil },
            set: { if !$0 { pendingRetention = nil } }
        ), presenting: pendingRetention) { option in
            Button("Apply") { trashRetention = option }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Documents already past the new retention limit will be permanently deleted.")
        }
        .confirmationDialog(
            Text("Permanently delete \(deleting.count) documents?"),
            isPresented: Binding(
                get: { !deleting.isEmpty },
                set: { if !$0 { deleting = [] } }
            ),
            titleVisibility: .visible,
            presenting: deleting
        ) { ids in
            Button("Delete permanently", role: .destructive) {
                applyBatch(deleteWithoutTrash ? .deleteWithoutTrash : .deletePermanently, to: ids)
                collectPDFs()
                deleting = []
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
            .onChange(of: folderID) { endDocumentSelection() }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu("Library options", systemImage: "line.3.horizontal") {
                        Button(showTrash ? "Library" : "Trash", systemImage: showTrash ? "books.vertical" : "trash") {
                            endDocumentSelection()
                            showTrash.toggle()
                            if showTrash { purgeExpiredTrash() }
                            query = ""
                        }
                        if !showTrash {
                            if let restorableSplit {
                                Button("Restore last split view", systemImage: "rectangle.split.2x1") {
                                    restoreSplit(restorableSplit)
                                }
                            }
                            Button("Folders", systemImage: "folder") { folderRequest = FolderRequest(action: .browse) }
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
                        Menu("Trash retention") {
                            ForEach(TrashRetention.allCases, id: \.self) { option in
                                Button {
                                    if option.rawValue > 0 && option != trashRetention {
                                        pendingRetention = option
                                    } else {
                                        trashRetention = option
                                    }
                                } label: {
                                    if option == trashRetention {
                                        Label(option.title, systemImage: "checkmark")
                                    } else {
                                        Text(option.title)
                                    }
                                }
                            }
                        }
                        Picker("Appearance", selection: $appearance) {
                            Text("System").tag("system")
                            Text("Light").tag("light")
                            Text("Dark").tag("dark")
                        }
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if selectingDocuments {
                        Button("Cancel selection") { endDocumentSelection() }
                        Button("Select all documents") { selectedDocuments.formUnion(documents.map(\.id)) }
                            .disabled(documents.allSatisfy { selectedDocuments.contains($0.id) })
                    } else {
                        Button("Select documents") { selectingDocuments = true }
                            .disabled(documents.isEmpty)
                    }
                    Menu("Add document", systemImage: "plus") {
                        Button("New folder", systemImage: "folder.badge.plus") {
                            editFolder(LibraryFolder(name: "", parentID: currentFolder?.id))
                        }
                        Button("New notebook", systemImage: "book.closed") { create(.notebook) }
                        Button("New canvas", systemImage: "square.dashed") { create(.canvas) }
                        Button("Import PDF or backup", systemImage: "square.and.arrow.down") { importing = true }
                    }
                    .disabled(showTrash || selectingDocuments)
                }
                if selectingDocuments {
                    ToolbarItemGroup(placement: .bottomBar) {
                        Text("\(selectedDocuments.count) documents selected")
                        Spacer()
                        if showTrash {
                            Button("Restore selected documents", systemImage: "arrow.uturn.backward") {
                                applyBatch(.trash(false), to: selectedDocuments)
                            }
                            .disabled(selectedDocuments.isEmpty)
                            Button("Delete selected permanently", systemImage: "trash", role: .destructive) {
                                requestDeletion(selectedDocuments, bypassTrash: false)
                            }
                            .disabled(selectedDocuments.isEmpty)
                        } else {
                            Button("Move selected documents", systemImage: "folder") {
                                folderRequest = FolderRequest(action: .moveDocuments(selectedDocuments))
                            }
                            .disabled(selectedDocuments.isEmpty)
                            Menu("Export selected documents", systemImage: "square.and.arrow.up") {
                                Button("Export selected documents as PDF") { exportPDFs(selectedForExport) }
                                Button("Export backups") { exportNotebooks(selectedForExport) }
                            }
                            .disabled(selectedDocuments.isEmpty ||
                                      selectedForExport.count != selectedDocuments.count)
                            Button(trashRetention == .off ? "Delete selected permanently" : "Trash selected documents",
                                   systemImage: "trash", role: .destructive) {
                                removeFromLibrary(selectedDocuments)
                            }
                            .disabled(selectedDocuments.isEmpty)
                        }
                    }
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
                Button("Move folder", systemImage: "folder") {
                    folderRequest = FolderRequest(action: .moveFolder(folder.id))
                }
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
                if selectingDocuments {
                    if selectedDocuments.remove(notebook.id) == nil { selectedDocuments.insert(notebook.id) }
                } else if !showTrash {
                    open(notebook.id)
                }
            } label: {
                entryLabel(Text(verbatim: notebook.title), symbol: documentSymbol(notebook))
            }
            .accessibilityAddTraits(selectingDocuments && selectedDocuments.contains(notebook.id) ? .isSelected : [])
            .contextMenu {
                if !selectingDocuments {
                    documentActions(notebook)
                }
            }
        }
    }

    @ViewBuilder private func documentActions(_ notebook: Notebook) -> some View {
        if showTrash {
            Button("Restore", systemImage: "arrow.uturn.backward") { applyBatch(.trash(false), to: [notebook.id]) }
            Button("Delete permanently", systemImage: "trash", role: .destructive) { requestDeletion([notebook.id], bypassTrash: false) }
        } else {
            Button("Rename", systemImage: "pencil") {
                title = notebook.title
                renaming = notebook
            }
            Button("Move to folder", systemImage: "folder") {
                folderRequest = FolderRequest(action: .moveDocuments([notebook.id]))
            }
            Button("Export backup", systemImage: "square.and.arrow.up") { exportNotebooks([notebook]) }
            Button("Export PDF", systemImage: "doc.richtext") { exportPDF(notebook) }
            if notebook.kind == .pdf && notebook.pages.contains(where: { $0.pdfTextMarkups?.isEmpty == false }) {
                Button("Export PDF with editable text markup", systemImage: "highlighter") {
                    perform {
                        let data = try PDFMarkupExport.data(for: notebook, source: library.pdf(for: notebook))
                        export = [ExportFile(data: data, filename: pdfFilename(notebook.title))]
                        exportType = .pdf
                        exporting = true
                    }
                }
            }
            Button(trashRetention == .off ? "Delete permanently" : "Move to Trash",
                   systemImage: "trash", role: .destructive) { removeFromLibrary([notebook.id]) }
        }
    }

    private func documentSymbol(_ notebook: Notebook) -> String {
        if !selectingDocuments { return notebook.kind.symbol }
        return selectedDocuments.contains(notebook.id) ? "checkmark.circle.fill" : "circle"
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

    private func paneDocuments(for pane: DocumentPane) -> [Notebook] {
        let neighbor = panes.first(where: { $0.id != pane.id })?.documentID
        return library.notebooks.filter { $0.trashedAt == nil && ($0.kind != .canvas || $0.id != neighbor) }
            .sorted { sort.precedes($0, $1, descending: descending) }
    }

    private func replace(_ pane: DocumentPane, with document: UUID?) {
        guard let index = panes.firstIndex(where: { $0.id == pane.id }) else { return }
        if let document, openNotebook(document) == nil { return }
        panes[index].documentID = document
    }

    private func removePane(_ pane: DocumentPane) {
        panes.removeAll { $0.id == pane.id }
    }

    private func editor(_ notebook: Notebook, in pane: DocumentPane) -> some View {
        let shared = panes.first(where: { $0.documentID == notebook.id })?.id != pane.id
        return NotebookEditor(notebook: notebook, library: library, readOnly: shared, close: {
            panes = DocumentPane.closingDocument(in: pane.id, panes: panes)
        }, openRecoveredCopy: { documentID in
            replace(pane, with: documentID)
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

    private func rememberSplit(_ active: [DocumentPane]) {
        guard active.count == 2, let leading = active[0].documentID,
              let trailing = active[1].documentID else { return }
        let workspace = SplitWorkspace(leading: leading, trailing: trailing)
        guard workspace.canRestore(from: library.notebooks) else { return }
        if let encoded = try? JSONEncoder().encode(workspace) { lastSplitWorkspace = encoded }
    }

    private func restoreSplit(_ saved: SplitWorkspace) {
        guard saved.canRestore(from: library.notebooks) else { return }
        panes = [DocumentPane(documentID: saved.leading), DocumentPane(documentID: saved.trailing)]
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

    private func folderDestinations(for request: FolderRequest) -> [LibraryFolder] {
        guard case .moveFolder(let id) = request.action else { return library.folders }
        let excluded = LibraryFolder.subtree(root: id, in: library.folders)
        return library.folders.filter { !excluded.contains($0.id) }
    }

    private func editFolder(_ folder: LibraryFolder) {
        folderName = folder.name
        folderDraft = folder
    }

    private func endDocumentSelection() {
        selectingDocuments = false
        selectedDocuments = []
    }

    private func applyBatch(_ change: LibraryStore.DocumentChange, to ids: Set<UUID>) {
        let result = library.changeDocuments(ids, applying: change)
        selectedDocuments.subtract(result.saved)
        if selectingDocuments && selectedDocuments.isEmpty { endDocumentSelection() }
        if let detail = result.failures.values.sorted().first {
            library.issue = String(localized: "\(result.failures.count) documents could not be updated. \(detail)")
        }
    }

    private func removeFromLibrary(_ ids: Set<UUID>) {
        if trashRetention == .off {
            requestDeletion(ids, bypassTrash: true)
        } else {
            applyBatch(.trash(true), to: ids)
        }
    }

    private func requestDeletion(_ ids: Set<UUID>, bypassTrash: Bool) {
        deleteWithoutTrash = bypassTrash
        deleting = ids
    }

    private func maintainStorage() {
        purgeExpiredTrash()
        collectPDFs()
    }

    private func collectPDFs() {
        do {
            try library.sweepUnreferencedPDFs()
        } catch {
            if library.issue == nil { library.issue = error.localizedDescription }
        }
    }

    private func purgeExpiredTrash() {
        let result = library.purgeExpiredTrash(days: trashRetention.rawValue)
        selectedDocuments.subtract(result.saved)
        if let detail = result.failures.values.sorted().first {
            library.issue = String(localized: "\(result.failures.count) documents could not be updated. \(detail)")
        }
    }

    private func exportNotebooks(_ notebooks: [Notebook]) {
        perform {
            export = try BackupExport.files(for: notebooks, encode: library.archive(for:))
            exportType = .xnotesArchive
            exporting = true
        }
    }

    private func exportPDF(_ notebook: Notebook) {
        exportPDFs([notebook])
    }

    private func exportPDFs(_ notebooks: [Notebook]) {
        perform {
            let inputs = notebooks.map { notebook in
                NotebookPDFExport.Input(notebook: notebook, source: library.pdf(for: notebook), darkCanvas: scheme == .dark)
            }
            let title = notebooks.count == 1 ? notebooks[0].title : String(localized: "Selected documents")
            export = [ExportFile(data: try NotebookPDFExport.data(for: inputs), filename: pdfFilename(title))]
            exportType = .pdf
            exporting = true
        }
    }

    private func pdfFilename(_ title: String) -> String {
        title.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_") + ".pdf"
    }

    private func perform(_ operation: () throws -> Void) {
        do { try operation() } catch { library.issue = error.localizedDescription }
    }
}

private struct FolderRequest: Identifiable {
    enum Action {
        case browse
        case moveDocuments(Set<UUID>)
        case moveFolder(UUID)
    }

    let id = UUID()
    let action: Action
}

private enum TrashRetention: Int, CaseIterable {
    case off = 0
    case week = 7
    case month = 30
    case untilEmptied = -1

    var title: LocalizedStringKey {
        switch self {
        case .off: "Trash off"
        case .week: "7 days"
        case .month: "30 days"
        case .untilEmptied: "Until manually emptied"
        }
    }
}
