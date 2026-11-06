import PDFKit
import PencilKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct NotebookEditor<SplitChoices: View>: View {
    let notebook: Notebook
    let library: LibraryStore
    let readOnly: Bool
    let close: () -> Void
    let openRecoveredCopy: (UUID) -> Void
    @ViewBuilder let splitChoices: () -> SplitChoices

    @Environment(\.colorScheme) private var scheme
    @State private var pdfMatchQuery = ""
    @State private var pdfMatchPage: UUID?
    @State private var pdfMatchRects: [CGRect] = []
    @State private var pdfScrollTarget: PaperScrollTarget?
    @State private var selectedPageID: UUID?
    @State private var showPages = false
    @State private var zoom = PaperZoom()
    @State private var restoredViewState = false
    @State private var showPaper = false
    @State private var showCoverPicker = false
    @State private var pendingCoverPicker = false
    @State private var showPaperSize = false
    @State private var selectedLayer: UUID?
    @State private var tool: InkTool = .pen
    @AppStorage("penWidth") private var penWidth = 3.0
    @AppStorage("markerWidth") private var markerWidth = 9.0
    @AppStorage("penColor") private var penColor = InkColor.black
    @AppStorage("markerColor") private var markerColor = InkColor.yellow
    @AppStorage("penCustomColor") private var penCustomColor = ""
    @AppStorage("markerCustomColor") private var markerCustomColor = ""
    @State private var inkSettingsTool: InkTool?
    @AppStorage("eraserMode") private var eraserMode = InkEraserMode.stroke
    @AppStorage("pencilOnly") private var pencilOnly = false
    @State private var showLayers = false
    @State private var layerEditMode: EditMode = .inactive
    @AppStorage("layerPreviewMode") private var layerPreviewMode = LayerPreviewMode.content
    @State private var imageSelection: PhotosPickerItem?
    @State private var imageImport: Task<Void, Never>?
    private struct CropRequest: Identifiable {
        let id = UUID()
        let pageID: UUID
        let image: PageImage
    }

    @State private var cropRequest: CropRequest?
    @State private var editingImages = false
    @State private var editingText = false
    @State private var text = ""
    @State private var layerToRename: UUID?
    @State private var layerName = ""
    private enum FileImport {
        case originalPDF
        case pagePDF(UUID)
        case insertPDF(after: UUID)
        case image(pageID: UUID, size: CGSize)

        var contentTypes: [UTType] {
            switch self {
            case .originalPDF, .pagePDF: [.pdf, .xnotesArchive]
            case .insertPDF: [.pdf]
            case .image: [.image]
            }
        }
    }

    @State private var fileImport: FileImport = .originalPDF
    private struct PDFInsertRequest: Identifiable {
        let id = UUID()
        let targetID: UUID
        let source: LibraryStore.PDFInsertion
    }

    @State private var pdfInsertRequest: PDFInsertRequest?
    @State private var showingFileImporter = false
    @State private var showingPhotoPicker = false
    @State private var pdf: PDFDocument?
    @State private var pagePDFs: [String: PDFDocument] = [:]
    @State private var pdfImage: UIImage?
    @State private var history = DocumentHistory()

    private var pageIndex: Int {
        notebook.pages.firstIndex { $0.id == selectedPageID } ?? 0
    }

    private var latestNotebook: Notebook? { library.notebooks.first { $0.id == notebook.id } }

    private var page: NotePage { notebook.pages[pageIndex] }
    private var activeLayer: UUID {
        page.layers.first { $0.id == selectedLayer }?.id ?? page.layers[page.layers.count - 1].id
    }
    private var viewStateSlot: DocumentViewStateStore.Slot { readOnly ? .secondary : .primary }
    private var pdfSources: PDFPageSources { PDFPageSources(original: pdf, inserted: pagePDFs) }
    private var currentPDFPage: PDFPage? { pdfSources.page(for: page, at: pageIndex) }
    private var missingPagePDF: Bool { page.pdfBackground != nil && currentPDFPage == nil }
    private var canEdit: Bool { !readOnly && !missingPagePDF && (notebook.kind != .pdf || pdf != nil) }
    private var darkPaper: Bool {
        page.coverArtwork?.usesLightInk ?? (scheme == .dark && notebook.kind != .pdf && page.pdfBackground == nil)
    }
    private var paperSize: CGSize {
        if let pdfImage { return pdfImage.size }
        if notebook.kind == .canvas { return NotebookPageDrawing.canvasSize }
        return (page.paperSize ?? .letter).dimensions
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            if notebook.kind == .pdf && pdf == nil {
                HStack {
                    Text("Original PDF unavailable").font(.subheadline)
                    Spacer()
                    Menu("Recover PDF", systemImage: "link") {
                        Button("Relink original PDF", systemImage: "link") { showFileImporter(for: .originalPDF) }
                        Button("Save annotation-only copy", systemImage: "doc.on.doc") {
                            do {
                                let id = try library.createAnnotationCopy(of: notebook.id)
                                openRecoveredCopy(id)
                            } catch {
                                library.issue = error.localizedDescription
                            }
                        }
                    }
                    .frame(minHeight: 48)
                }
                .padding(.horizontal, 8)
                Divider()
            }
            if missingPagePDF {
                HStack {
                    Text("Original PDF unavailable").font(.subheadline)
                    Spacer()
                    Button("Relink original PDF", systemImage: "link") {
                        showFileImporter(for: .pagePDF(page.id))
                    }
                    .frame(minHeight: 48)
                }
                .padding(.horizontal, 8)
                Divider()
            }
            if canEdit {
                tools
                Divider()
            }
            HStack(spacing: 0) {
                if showLayers && canEdit {
                    layerPanel
                    Divider()
                }
                PaperViewport(paperSize: paperSize, scrollTarget: pdfScrollTarget, zoom: $zoom) { paper }
            }
            if notebook.kind != .canvas {
                Divider()
                pageBar
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear {
            if !restoredViewState {
                if let state = library.viewStates.load(notebook.id, slot: viewStateSlot) {
                    selectedPageID = state.page(in: notebook.pages)
                    zoom = state.zoom
                }
                restoredViewState = true
            }
            retainPagePDFs()
            reloadPDF()
        }
        .onChange(of: notebook.pdfDigests) {
            retainPagePDFs()
            loadBackground()
        }
        .onChange(of: zoom) { saveViewState() }
        .onChange(of: library.pdfRestorationRevision) {
            if notebook.kind == .pdf && pdf == nil { reloadPDF() }
            if missingPagePDF, let reference = page.pdfBackground {
                pagePDFs[reference.digest] = library.pagePDF(for: reference.digest)
            }
            retainPagePDFs()
            loadBackground()
        }
        .fileImporter(isPresented: $showingFileImporter, allowedContentTypes: fileImport.contentTypes,
                      onCompletion: importFile)
        .photosPicker(isPresented: $showingPhotoPicker, selection: $imageSelection, matching: .images)
        .onDisappear { imageImport?.cancel() }
        .onChange(of: readOnly) {
            saveViewState()
            guard readOnly else { return }
            history.clear()
            imageImport?.cancel()
            imageSelection = nil
            editingImages = false
            editingText = false
            showPaper = false
            showCoverPicker = false
            showPaperSize = false
            layerToRename = nil
            inkSettingsTool = nil
        }
        .onChange(of: imageSelection) {
            if let imageSelection { importImage(imageSelection) }
        }
        .onChange(of: page.id) {
            selectedLayer = nil
            layerEditMode = .inactive
            editingImages = false
            loadBackground()
            if pdfMatchPage != page.id { clearPDFMatches() }
            saveViewState()
        }
        .sheet(isPresented: $showPages, onDismiss: pageNavigatorDismissed) {
            pageNavigator
        }
        .sheet(item: $pdfInsertRequest) { request in
            PDFInsertPicker(document: request.source.document) { indices in
                insertPDF(request, indices: indices)
            }
        }
        .sheet(item: $cropRequest) { request in
            PageImageCropView(image: request.image) { region in
                do {
                    let cropped = try PageImageCrop.apply(region, to: request.image)
                    return updatePage(on: request.pageID) { page in
                        guard let index = page.images?.firstIndex(of: request.image) else { return }
                        page.images?[index] = cropped
                    }
                } catch {
                    library.issue = error.localizedDescription
                    return false
                }
            }
        }
        .sheet(item: $inkSettingsTool) { tool in
            if tool == .marker {
                InkSettingsView(tool: tool, preset: $markerColor, custom: $markerCustomColor, width: $markerWidth)
            } else {
                InkSettingsView(tool: tool, preset: $penColor, custom: $penCustomColor, width: $penWidth)
            }
        }
        .sheet(isPresented: $showCoverPicker) {
            let cover = notebook.pages.first.flatMap { $0.isCover == true ? $0 : nil }
            CoverPicker(selection: cover?.coverArtwork, parameters: cover?.coverParameters) { pattern, artwork, parameters in
                setCover(pattern, artwork: artwork, parameters: parameters)
            }
        }
        .sheet(isPresented: $showPaper) {
            let targetID = page.id
            PaperPicker(selection: page.paperPattern ?? .blank, parameters: page.paperParameters) { pattern, parameters in
                editPaper(.pattern(pattern, parameters), ids: [targetID])
            }
        }
        .sheet(isPresented: $showPaperSize) {
            let targetID = page.id
            PaperSizePicker(size: page.paperSize ?? .letter) { size in
                editPaper(.size(size), ids: [targetID])
            }
        }
        .sheet(isPresented: $editingText) {
            NavigationStack {
                TextEditor(text: $text)
                    .padding()
                    .navigationTitle("Page text")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") { editingText = false }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Save") {
                                updatePage { $0.text = text }
                                editingText = false
                            }
                        }
                    }
            }
        }
        .alert("Rename layer", isPresented: Binding(
            get: { layerToRename != nil },
            set: { if !$0 { layerToRename = nil } }
        )) {
            TextField("Name", text: $layerName)
            Button("Save") {
                if let layerToRename, !layerName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    updateLayer(layerToRename) { $0.name = layerName }
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private var pageNavigator: PageNavigator {
        PageNavigator(
            pages: notebook.pages,
            current: page.id,
            editable: !readOnly && notebook.kind == .notebook,
            pdf: pdf,
            pagePDFs: pagePDFs,
            documentTitle: notebook.title,
            bookmarkEditing: canEdit,
            editBookmark: editBookmark,
            exportImages: exportPageImages,
            exportPages: { ids in
                guard let latestNotebook else { throw CocoaError(.fileReadNoSuchFile) }
                return try NotebookPDFExport.data(for: latestNotebook, source: pdf, pagePDFs: pagePDFs, selecting: ids)
            },
            open: { id in
                clearPDFMatches()
                selectedPageID = id
            },
            openPDFMatch: showPDFMatches,
            add: addPage,
            editCover: { showCoverPickerFromNavigator() },
            copyPages: copyPages,
            cutPages: cutPages,
            pasteCount: canEdit ? (library.pageClipboard?.pages.count ?? 0) : 0,
            pastePages: pastePages,
            duplicate: duplicatePages,
            delete: deletePages,
            editPaper: editPaper,
            move: movePages
        )
    }

    private var header: some View {
        HStack {
            icon("Close document", "chevron.backward", action: close)
            VStack(alignment: .leading) {
                Text(notebook.title).font(.headline).lineLimit(1)
                if readOnly { Text("Read-only view").font(.caption).foregroundStyle(.secondary) }
            }
            Spacer()
            Menu {
                Button("Fit width") { zoom.mode = .fitWidth }
                    .disabled(zoom.locked)
                Button("Actual size") { zoom.mode = .manual(1) }
                    .disabled(zoom.locked)
                Toggle("Lock zoom", isOn: $zoom.locked)
            } label: {
                Image(systemName: zoom.locked ? "lock" : "magnifyingglass")
                    .frame(minWidth: 48, minHeight: 48)
            }
            .accessibilityLabel(Text("Zoom"))
            Menu("Split view", systemImage: "rectangle.split.2x1") {
                splitChoices()
            }
            .frame(minWidth: 48, minHeight: 48)
            .accessibilityIdentifier("editor.split")
        }
        .padding(.horizontal, 8)
    }

    private var tools: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 0) {
                ForEach(InkTool.allCases, id: \.self) { item in
                    icon(item.label, item.rawValue, selected: tool == item) { tool = item }
                }
                icon("Undo", "arrow.uturn.backward") { replay(redo: false) }
                    .disabled(!history.canUndo)
                icon("Redo", "arrow.uturn.forward") { replay(redo: true) }
                    .disabled(!history.canRedo)
                icon("Page text", "textformat") {
                    text = page.text
                    editingText = true
                }
                Menu("Insert image", systemImage: "photo.badge.plus") {
                    Button("From photo library", systemImage: "photo.on.rectangle") { showingPhotoPicker = true }
                    Button("From files", systemImage: "folder") {
                        showFileImporter(for: .image(pageID: page.id, size: paperSize))
                    }
                }
                .frame(minWidth: 48, minHeight: 48)
                icon("Arrange images", "move.3d", selected: editingImages) { editingImages.toggle() }
                    .disabled(!editingImages && page.images?.isEmpty != false)
                if notebook.kind == .notebook {
                    icon("Paper template", "rectangle.grid.2x2") { showPaper = true }
                        .disabled(page.pdfBackground != nil)
                    icon("Insert PDF pages", "doc.badge.plus") {
                        showFileImporter(for: .insertPDF(after: page.id))
                    }
                    icon("Paper size", "arrow.up.left.and.arrow.down.right") { showPaperSize = true }
                        .disabled(page.pdfBackground != nil)
                }
                if currentPDFPage != nil {
                    Menu("PDF text markup", systemImage: "character.textbox") {
                        ForEach(PDFTextMarkup.Kind.allCases, id: \.self) { kind in
                            Button(kind.label) { addPDFMarkup(kind) }
                                .disabled(pdfMatchPage != page.id || pdfMatchRects.isEmpty)
                        }
                        if let marks = page.pdfTextMarkups, !marks.isEmpty {
                            Menu("Remove PDF markup") {
                                ForEach(marks) { mark in
                                    Button(role: .destructive) {
                                        updatePage { $0.pdfTextMarkups?.removeAll { $0.id == mark.id } }
                                    } label: {
                                        Text(mark.query)
                                    }
                                }
                            }
                        }
                    }
                    .frame(minWidth: 48, minHeight: 48)
                }
                icon("Layers", "square.3.layers.3d", selected: showLayers) { showLayers.toggle() }
                Menu("Ink settings", systemImage: "slider.horizontal.3") {
                    Button("Pen") { inkSettingsTool = .pen }
                    Button("Highlighter") { inkSettingsTool = .marker }
                    Picker("Eraser mode", selection: $eraserMode) {
                        ForEach(InkEraserMode.allCases, id: \.self) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    Toggle("Apple Pencil only", isOn: $pencilOnly)
                }
                .frame(minWidth: 48, minHeight: 48)
            }
            .padding(.horizontal, 8)
        }
        .scrollIndicators(.hidden)
    }

    private var activeInkColor: UIColor {
        if tool == .marker { return CustomInkColor.decode(markerCustomColor) ?? markerColor.color }
        return CustomInkColor.decode(penCustomColor) ?? penColor.color
    }

    private var paper: some View {
        let pageID = page.id
        return ZStack(alignment: .topLeading) {
            darkPaper ? Color.black : Color.white
            if let artwork = page.coverArtwork {
                NotebookCoverArtwork(cover: artwork, parameters: page.coverParameters)
            } else if let pattern = page.paperPattern, notebook.kind == .notebook, page.pdfBackground == nil {
                PaperPatternView(pattern: pattern, dark: darkPaper, parameters: page.paperParameters)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            if let pdfImage {
                Image(uiImage: pdfImage).resizable().allowsHitTesting(false)
                if let marks = page.pdfTextMarkups, !marks.isEmpty {
                    let rotation = currentPDFPage?.rotation ?? 0
                    Canvas { context, size in
                        context.withCGContext {
                            PDFTextMarkupDrawing.draw(marks, in: $0, size: size, rotation: rotation)
                        }
                    }
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                }
            }
            if !editingImages || readOnly {
                ForEach(page.images ?? []) { image in
                    PageImageView(image: image, editing: false, paperSize: paperSize, move: { _ in }, remove: {})
                }
            }
            Text(page.text)
                .foregroundStyle(darkPaper ? .white : .black)
                .padding(24)
                .allowsHitTesting(false)
            ForEach(page.layers.filter(\.visible)) { layer in
                if canEdit && layer.id == activeLayer && !layer.locked {
                    InkSurface(
                        ink: layer.ink, tool: tool, color: activeInkColor,
                        width: tool == .marker ? markerWidth : penWidth,
                        eraser: eraserMode, darkPaper: darkPaper, pencilOnly: pencilOnly,
                        changed: { data, gesture in
                            updatePage(on: pageID, gesture: gesture) { page in
                                guard let index = page.layers.firstIndex(where: { $0.id == layer.id }),
                                      !page.layers[index].locked else { return }
                                page.layers[index].ink = data
                            }
                        }
                    )
                    .id(layer.id)
                    .allowsHitTesting(!editingImages)
                } else {
                    LayerImage(ink: layer.ink, darkPaper: darkPaper)
                        .allowsHitTesting(false)
                }
            }
            if pdfMatchPage == pageID && !pdfMatchRects.isEmpty {
                Canvas { context, _ in
                    var outline = Path()
                    for rect in pdfMatchRects { outline.addRect(rect.insetBy(dx: -1, dy: -1)) }
                    context.fill(outline, with: .color(.gray.opacity(0.16)))
                    context.stroke(outline, with: .color(.white), lineWidth: 3)
                    context.stroke(outline, with: .color(.black), lineWidth: 1)
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
            if editingImages && !readOnly {
                ForEach(page.images ?? []) { image in
                    PageImageView(image: image, editing: true, paperSize: paperSize) { frame in
                        updatePage(on: pageID) { page in
                            guard let index = page.images?.firstIndex(where: { $0.id == image.id }) else { return }
                            page.images?[index].frame = frame
                        }
                    } remove: {
                        updatePage(on: pageID) { $0.images?.removeAll { $0.id == image.id } }
                    } crop: {
                        cropRequest = CropRequest(pageID: pageID, image: image)
                    }
                }
            }
        }
        .frame(width: paperSize.width, height: paperSize.height)
        .clipped()
    }

    private var pageBar: some View {
        HStack {
            icon("Previous page", "chevron.left") { selectedPageID = notebook.pages[pageIndex - 1].id }
                .disabled(pageIndex == 0)
            Button { showPages = true } label: {
                Text("\(pageIndex + 1) / \(notebook.pages.count)")
                    .frame(minWidth: 48, minHeight: 48)
            }
            .accessibilityLabel(Text("Pages"))
            icon("Next page", "chevron.right") { selectedPageID = notebook.pages[pageIndex + 1].id }
                .disabled(pageIndex + 1 == notebook.pages.count)
            if !readOnly && notebook.kind == .notebook {
                icon("Add page", "plus", action: addPage)
            }
        }
    }

    private func pageNavigatorDismissed() {
        if pendingCoverPicker {
            pendingCoverPicker = false
            showCoverPicker = true
        } else {
            scrollToPDFMatch()
        }
    }

    private func showCoverPickerFromNavigator() {
        pendingCoverPicker = true
        showPages = false
    }

    private func setCover(_ pattern: PaperPattern, artwork: NotebookCover?, parameters: NotebookCoverParameters?) -> Bool {
        guard !readOnly, notebook.kind == .notebook, var updated = latestNotebook else { return false }
        let pages = PageBatch.cover(pattern, artwork: artwork, parameters: parameters, in: updated.pages)
        if pages == updated.pages { return true }
        updated.pages = pages
        guard save(updated) else { return false }
        selectedPageID = pages.first?.id
        return true
    }

    private func addPage() {
        guard var updated = latestNotebook,
              let index = updated.pages.firstIndex(where: { $0.id == page.id }) else { return }
        var newPage = NotePage()
        newPage.paperSize = updated.pages[index].paperSize
        updated.pages.insert(newPage, at: index + 1)
        if save(updated) { selectedPageID = newPage.id }
    }

    private func copyPages(_ ids: Set<UUID>) -> Bool {
        do {
            return try library.copyPages(from: notebook.id, selecting: ids)
        } catch {
            library.issue = error.localizedDescription
            return false
        }
    }

    private func cutPages(_ ids: Set<UUID>) -> Bool {
        guard canEdit, notebook.kind == .notebook else { return false }
        do {
            return try library.copyPages(from: notebook.id, selecting: ids) {
                deletePages(ids)
            }
        } catch {
            library.issue = error.localizedDescription
            return false
        }
    }

    private func pastePages() {
        guard canEdit, notebook.kind == .notebook, let clipboard = library.pageClipboard,
              var updated = latestNotebook,
              let index = updated.pages.firstIndex(where: { $0.id == page.id }) else { return }
        let copies = clipboard.pages.map(PageBatch.makeCopy)
        updated.pages.insert(contentsOf: copies, at: index + 1)
        if save(updated) {
            pagePDFs.merge(clipboard.sources) { _, clipboard in clipboard }
            selectedPageID = copies.first?.id
        }
    }

    private func duplicatePages(_ ids: Set<UUID>) -> Bool {
        guard var updated = latestNotebook,
              let result = PageBatch.duplicate(ids, in: updated.pages) else { return false }
        updated.pages = result.pages
        guard save(updated) else { return false }
        selectedPageID = result.firstCopy
        return true
    }

    private func deletePages(_ ids: Set<UUID>) -> Bool {
        guard var updated = latestNotebook,
              let result = PageBatch.remove(ids, from: updated.pages, current: page.id) else { return false }
        updated.pages = result.pages
        guard save(updated) else { return false }
        selectedPageID = result.current
        return true
    }

    private func editPaper(_ edit: PagePaperEdit, ids: Set<UUID>) -> Bool {
        guard !readOnly, notebook.kind == .notebook, var updated = latestNotebook,
              updated.pages.contains(where: { ids.contains($0.id) }) else { return false }
        let edited = PageBatch.editPaper(edit, for: ids, in: updated.pages)
        if edited == updated.pages { return true }
        updated.pages = edited
        return save(updated)
    }

    private func editBookmark(_ edit: BookmarkEdit, pageID: UUID) -> Bool {
        guard canEdit, notebook.kind != .canvas else { return false }
        return updatePage(on: pageID) { page in
            edit.apply(to: &page)
        }
    }

    private func movePages(_ indices: IndexSet, _ destination: Int) {
        guard var updated = latestNotebook,
              updated.pages.map(\.id) == notebook.pages.map(\.id) else { return }
        let current = page.id
        let hasCover = updated.pages.first?.isCover == true
        guard !hasCover || !indices.contains(0) else { return }
        updated.pages.move(fromOffsets: indices, toOffset: hasCover ? max(1, destination) : destination)
        if save(updated) { selectedPageID = current }
    }

    private var layerPanel: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Layers").font(.headline)
                Spacer()
                Menu {
                    Picker("Layer previews", selection: $layerPreviewMode) {
                        Text("No previews").tag(LayerPreviewMode.none)
                        Text("Ink bounds").tag(LayerPreviewMode.content)
                        if notebook.kind != .canvas {
                            Text("Whole page").tag(LayerPreviewMode.page)
                        }
                    }
                    Button(layerEditMode.isEditing ? "Done" : "Reorder layers") {
                        layerEditMode = layerEditMode.isEditing ? .inactive : .active
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .frame(minWidth: 48, minHeight: 48)
                }
                .accessibilityLabel(Text("Layer options"))
                icon("Add layer", "plus") {
                    let layer = DrawingLayer(name: String(localized: "Layer \(page.layers.count + 1)"))
                    updatePage { $0.layers.append(layer) }
                    selectedLayer = layer.id
                }
            }
            .padding(.leading, 8)
            List {
                ForEach(Array(page.layers.reversed())) { layer in
                    Button { selectedLayer = layer.id } label: {
                        HStack(spacing: 6) {
                            let mode = notebook.kind == .canvas && layerPreviewMode == .page ? .content : layerPreviewMode
                            if mode != .none {
                                LayerThumbnail(ink: layer.ink, mode: mode, pageSize: paperSize)
                                    .accessibilityHidden(true)
                            }
                            Image(systemName: layer.visible ? "eye" : "eye.slash")
                            Text(layer.name)
                            if layer.locked { Image(systemName: "lock") }
                        }
                        .frame(minHeight: 48)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(layer.id == activeLayer ? Color.primary.opacity(0.14) : Color.clear)
                    .contextMenu {
                        Button(layer.visible ? "Hide" : "Show") { updateLayer(layer.id) { $0.visible.toggle() } }
                        Button(layer.locked ? "Unlock" : "Lock") { updateLayer(layer.id) { $0.locked.toggle() } }
                        Button("Rename") {
                            layerName = layer.name
                            layerToRename = layer.id
                        }
                        Button("Move up") { moveLayer(layer.id, offset: 1) }
                        Button("Move down") { moveLayer(layer.id, offset: -1) }
                        Button("Delete layer", role: .destructive) {
                            if selectedLayer == layer.id { selectedLayer = nil }
                            updatePage { $0.layers.removeAll { $0.id == layer.id } }
                        }
                        .disabled(page.layers.count == 1)
                    }
                }
                .onMove { indices, destination in
                    let current = activeLayer
                    updatePage { page in
                        var topFirst = Array(page.layers.reversed())
                        topFirst.move(fromOffsets: indices, toOffset: destination)
                        page.layers = Array(topFirst.reversed())
                    }
                    selectedLayer = current
                }
            }
            .environment(\.editMode, $layerEditMode)
            .listStyle(.plain)
        }
        .frame(width: 210)
    }

    private func moveLayer(_ id: UUID, offset: Int) {
        guard let index = page.layers.firstIndex(where: { $0.id == id }),
              page.layers.indices.contains(index + offset) else { return }
        updatePage { $0.layers.swapAt(index, index + offset) }
    }

    private func icon(_ label: LocalizedStringKey, _ symbol: String, selected: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .frame(minWidth: 48, minHeight: 48)
                .foregroundStyle(selected ? Color(uiColor: .systemBackground) : .primary)
                .background(selected ? Color.primary : .clear)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }

    private func showFileImporter(for request: FileImport) {
        fileImport = request
        showingFileImporter = true
    }

    private func importFile(_ result: Result<URL, Error>) {
        do {
            let url = try result.get()
            switch fileImport {
            case .originalPDF:
                try library.restoreOriginalPDF(for: notebook.id, from: url)
                reloadPDF()
            case .pagePDF(let pageID):
                try library.restorePagePDF(for: notebook.id, pageID: pageID, from: url)
            case .insertPDF(let targetID):
                guard canEdit, notebook.kind == .notebook else { return }
                let source = try library.preparePDFInsertion(from: url)
                pdfInsertRequest = PDFInsertRequest(targetID: targetID, source: source)
            case .image(let targetID, let size):
                guard canEdit else { return }
                let image = try PageImageView.prepare(url, for: size)
                insertImage(image, on: targetID)
            }
        } catch {
            library.issue = error.localizedDescription
        }
    }

    private func insertPDF(_ request: PDFInsertRequest, indices: IndexSet) -> Bool {
        guard canEdit, notebook.kind == .notebook, var updated = latestNotebook,
              let index = updated.pages.firstIndex(where: { $0.id == request.targetID }) else {
            library.issue = CocoaError(.fileReadNoSuchFile).localizedDescription
            return false
        }
        do {
            let inserted = try library.insertedPDFPages(from: request.source, selecting: indices)
            updated.pages.insert(contentsOf: inserted, at: index + 1)
            guard save(updated) else { return false }
            retainPagePDFs()
            selectedPageID = inserted.first?.id
            return true
        } catch {
            library.issue = error.localizedDescription
            return false
        }
    }

    private func insertImage(_ image: PageImage, on targetID: UUID) {
        if updatePage(on: targetID, { $0.images = ($0.images ?? []) + [image] }), targetID == page.id {
            editingImages = true
        }
    }

    private func importImage(_ item: PhotosPickerItem) {
        imageImport?.cancel()
        let targetID = page.id
        let size = paperSize
        imageImport = Task {
            defer { if !Task.isCancelled { imageSelection = nil } }
            do {
                guard let original = try await item.loadTransferable(type: Data.self) else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                let image = try PageImageView.prepare(original, for: size)
                try Task.checkCancellation()
                insertImage(image, on: targetID)
            } catch is CancellationError {
                return
            } catch {
                if !Task.isCancelled { library.issue = error.localizedDescription }
            }
        }
    }

    private func exportPageImages(_ ids: Set<UUID>) throws -> [ExportFile] {
        guard let latestNotebook else { throw CocoaError(.fileReadNoSuchFile) }
        return try PageImageExport.files(for: latestNotebook, source: pdf, pagePDFs: pagePDFs, selecting: ids)
    }

    private func showPDFMatches(_ pageID: UUID, query: String) {
        clearPDFMatches()
        selectedPageID = pageID
        guard let index = notebook.pages.firstIndex(where: { $0.id == pageID }),
              let original = pdfSources.page(for: notebook.pages[index], at: index),
              let size = try? PDFPageGeometry.displaySize(original) else { return }
        pdfMatchPage = pageID
        pdfMatchQuery = query
        pdfMatchRects = PDFPageSearch.matchRects(on: original, query: query, displaySize: size)
    }

    private func addPDFMarkup(_ kind: PDFTextMarkup.Kind) {
        guard canEdit, pdfMatchPage == page.id,
              let original = currentPDFPage,
              let size = try? PDFPageGeometry.displaySize(original) else { return }
        let mark = PDFTextMarkup(kind: kind, query: pdfMatchQuery, rectangles: pdfMatchRects, paper: size)
        guard mark.isValid else { return }
        if updatePage({ $0.pdfTextMarkups = ($0.pdfTextMarkups ?? []) + [mark] }) {
            clearPDFMatches()
        }
    }

    private func scrollToPDFMatch() {
        guard pdfScrollTarget == nil, pdfMatchPage == page.id, let first = pdfMatchRects.first else { return }
        pdfScrollTarget = PaperScrollTarget(point: CGPoint(x: first.midX, y: first.midY))
    }

    private func clearPDFMatches() {
        pdfMatchPage = nil
        pdfMatchQuery = ""
        pdfMatchRects = []
        pdfScrollTarget = nil
    }

    private func saveViewState() {
        guard restoredViewState,
              library.notebooks.contains(where: { $0.id == notebook.id && $0.trashedAt == nil }) else { return }
        do {
            try library.viewStates.save(DocumentViewState(pageID: page.id, zoom: zoom), for: notebook.id, slot: viewStateSlot)
        } catch {
            if library.issue == nil { library.issue = error.localizedDescription }
        }
    }

    private func reloadPDF() {
        pdf = library.pdf(for: notebook)
        loadBackground()
    }

    private func retainPagePDFs() {
        for digest in Set((latestNotebook ?? notebook).pages.compactMap({ $0.pdfBackground?.digest }))
            where pagePDFs[digest] == nil {
            pagePDFs[digest] = library.pagePDF(for: digest)
        }
    }

    private func loadBackground() {
        guard let page = currentPDFPage else {
            pdfImage = nil
            return
        }
        guard let size = try? PDFPageGeometry.thumbnailRequest(page) else {
            pdfImage = nil
            return
        }
        pdfImage = page.thumbnail(of: size, for: .cropBox)
    }

    private func updateLayer(_ id: UUID, on pageID: UUID? = nil, _ change: (inout DrawingLayer) -> Void) {
        updatePage(on: pageID) { page in
            guard let index = page.layers.firstIndex(where: { $0.id == id }) else { return }
            change(&page.layers[index])
        }
    }

    @discardableResult private func updatePage(on pageID: UUID? = nil, gesture: UUID? = nil, _ change: (inout NotePage) -> Void) -> Bool {
        guard var updated = latestNotebook else { return false }
        let targetID = pageID ?? page.id
        guard let index = updated.pages.firstIndex(where: { $0.id == targetID }) else { return false }
        change(&updated.pages[index])
        return save(updated, gesture: gesture)
    }

    @discardableResult private func save(_ updated: Notebook, gesture: UUID? = nil) -> Bool {
        guard canEdit, let previous = library.notebooks.first(where: { $0.id == updated.id }) else { return false }
        guard previous.pages != updated.pages else { return false }
        var next = previous
        next.pages = updated.pages
        guard persist(next) else { return false }
        history.record(before: previous.pages, after: next.pages, gesture: gesture)
        return true
    }

    private func replay(redo: Bool) {
        guard !readOnly, let current = library.notebooks.first(where: { $0.id == notebook.id }) else { return }
        let apply: ([NotePage]) -> Bool = { pages in
            var updated = current
            updated.pages = pages
            return persist(updated)
        }
        if redo {
            history.redo(current: current.pages, persist: apply)
        } else {
            history.undo(current: current.pages, persist: apply)
        }
    }

    private func persist(_ updated: Notebook) -> Bool {
        do {
            try library.save(updated)
            return true
        } catch {
            library.issue = error.localizedDescription
            return false
        }
    }
}

private struct LayerImage: View {
    let ink: Data
    let darkPaper: Bool

    private func render(_ drawing: PKDrawing, bounds: CGRect) -> UIImage {
        var image = UIImage()
        UITraitCollection(userInterfaceStyle: darkPaper ? .dark : .light).performAsCurrent {
            image = drawing.image(from: bounds, scale: 2)
        }
        return image
    }

    var body: some View {
        if let drawing = try? PKDrawing(data: ink), !drawing.strokes.isEmpty {
            let bounds = drawing.bounds
            Image(uiImage: render(drawing, bounds: bounds))
                .position(x: bounds.midX, y: bounds.midY)
        }
    }
}
