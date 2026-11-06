import PDFKit
import PencilKit
import PhotosUI
import SwiftUI

struct NotebookEditor<SplitChoices: View>: View {
    let notebook: Notebook
    let library: LibraryStore
    let close: () -> Void
    @ViewBuilder let splitChoices: () -> SplitChoices

    @Environment(\.colorScheme) private var scheme
    @State private var selectedPageID: UUID?
    @State private var showPages = false
    @State private var selectedLayer: UUID?
    @State private var tool: InkTool = .pen
    @State private var width = 3.0
    @State private var pencilOnly = false
    @State private var showLayers = false
    @State private var imageSelection: PhotosPickerItem?
    @State private var imageImport: Task<Void, Never>?
    @State private var editingImages = false
    @State private var editingText = false
    @State private var text = ""
    @State private var layerToRename: UUID?
    @State private var layerName = ""
    @State private var pdf: PDFDocument?
    @State private var pdfImage: UIImage?
    @State private var history = InkHistory()

    private var pageIndex: Int {
        notebook.pages.firstIndex { $0.id == selectedPageID } ?? 0
    }

    private var page: NotePage { notebook.pages[pageIndex] }
    private var activeLayer: UUID {
        page.layers.first { $0.id == selectedLayer }?.id ?? page.layers[page.layers.count - 1].id
    }
    private var darkPaper: Bool { scheme == .dark && notebook.kind != .pdf }
    private var paperSize: CGSize {
        if let pdfImage { return pdfImage.size }
        if notebook.kind == .canvas { return CGSize(width: 2048, height: 2048) }
        return CGSize(width: 612, height: 792)
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            tools
            Divider()
            HStack(spacing: 0) {
                if showLayers {
                    layerPanel
                    Divider()
                }
                ScrollView([.horizontal, .vertical]) {
                    paper.padding(16)
                }
                .background(Color(uiColor: .secondarySystemBackground))
                .accessibilityIdentifier("editor.paper")
            }
            if notebook.kind != .canvas {
                Divider()
                pageBar
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear {
            pdf = library.pdf(for: notebook)
            loadBackground()
        }
        .onDisappear { imageImport?.cancel() }
        .onChange(of: imageSelection) {
            if let imageSelection { importImage(imageSelection) }
        }
        .onChange(of: page.id) {
            selectedLayer = nil
            editingImages = false
            loadBackground()
        }
        .sheet(isPresented: $showPages) {
            PageNavigator(
                pages: notebook.pages,
                current: page.id,
                editable: notebook.kind == .notebook,
                pdf: pdf,
                open: { selectedPageID = $0 },
                add: addPage,
                duplicate: duplicatePage,
                delete: deletePage,
                move: movePages
            )
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

    private var header: some View {
        HStack {
            icon("Close document", "chevron.backward", action: close)
            Text(notebook.title).font(.headline).lineLimit(1)
            Spacer()
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
                icon("Undo", "arrow.uturn.backward") { history.undo() }
                icon("Redo", "arrow.uturn.forward") { history.redo() }
                icon("Page text", "textformat") {
                    text = page.text
                    editingText = true
                }
                PhotosPicker(selection: $imageSelection, matching: .images) {
                    Image(systemName: "photo.badge.plus")
                        .frame(minWidth: 48, minHeight: 48)
                        .accessibilityLabel(Text("Insert image"))
                }
                icon("Arrange images", "move.3d", selected: editingImages) { editingImages.toggle() }
                    .disabled(!editingImages && page.images?.isEmpty != false)
                icon("Layers", "square.3.layers.3d", selected: showLayers) { showLayers.toggle() }
                Menu("Ink settings", systemImage: "slider.horizontal.3") {
                    Picker("Width", selection: $width) {
                        Text("Thin").tag(1.5)
                        Text("Medium").tag(3.0)
                        Text("Thick").tag(7.0)
                    }
                    Toggle("Apple Pencil only", isOn: $pencilOnly)
                }
                .frame(minWidth: 48, minHeight: 48)
            }
            .padding(.horizontal, 8)
        }
        .scrollIndicators(.hidden)
    }

    private var paper: some View {
        let pageID = page.id
        return ZStack(alignment: .topLeading) {
            darkPaper ? Color.black : Color.white
            if let pdfImage {
                Image(uiImage: pdfImage).resizable().allowsHitTesting(false)
            } else if notebook.kind == .pdf {
                Text("Original PDF unavailable").foregroundStyle(.black).padding()
            }
            if !editingImages {
                ForEach(page.images ?? []) { image in
                    PageImageView(image: image, editing: false, paperSize: paperSize, move: { _ in }, remove: {})
                }
            }
            Text(page.text)
                .foregroundStyle(darkPaper ? .white : .black)
                .padding(24)
                .allowsHitTesting(false)
            ForEach(page.layers.filter(\.visible)) { layer in
                if layer.id == activeLayer && !layer.locked {
                    InkSurface(
                        ink: layer.ink, tool: tool, color: darkPaper ? .white : .black,
                        width: width, pencilOnly: pencilOnly,
                        changed: { data in updateLayer(layer.id, on: pageID) { $0.ink = data } },
                        history: history
                    )
                    .id(layer.id)
                    .allowsHitTesting(!editingImages)
                } else {
                    LayerImage(ink: layer.ink)
                        .allowsHitTesting(false)
                }
            }
            if editingImages {
                ForEach(page.images ?? []) { image in
                    PageImageView(image: image, editing: true, paperSize: paperSize) { frame in
                        updatePage(on: pageID) { page in
                            guard let index = page.images?.firstIndex(where: { $0.id == image.id }) else { return }
                            page.images?[index].frame = frame
                        }
                    } remove: {
                        updatePage(on: pageID) { $0.images?.removeAll { $0.id == image.id } }
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
            if notebook.kind == .notebook {
                icon("Add page", "plus", action: addPage)
            }
        }
    }

    private func addPage() {
        var updated = notebook
        let newPage = NotePage()
        updated.pages.insert(newPage, at: pageIndex + 1)
        if save(updated) { selectedPageID = newPage.id }
    }

    private func duplicatePage(_ id: UUID) {
        var updated = notebook
        guard let index = updated.pages.firstIndex(where: { $0.id == id }) else { return }
        var copy = updated.pages[index]
        copy.id = UUID()
        if let images = copy.images {
            copy.images = images.map { image in
                var copy = image
                copy.id = UUID()
                return copy
            }
        }
        for index in copy.layers.indices { copy.layers[index].id = UUID() }
        updated.pages.insert(copy, at: index + 1)
        if save(updated) { selectedPageID = copy.id }
    }

    private func deletePage(_ id: UUID) {
        var updated = notebook
        guard updated.pages.count > 1,
              let index = updated.pages.firstIndex(where: { $0.id == id }) else { return }
        let current = page.id
        updated.pages.remove(at: index)
        if save(updated) {
            selectedPageID = current == id ? updated.pages[min(index, updated.pages.count - 1)].id : current
        }
    }

    private func movePages(_ indices: IndexSet, _ destination: Int) {
        var updated = notebook
        let current = page.id
        updated.pages.move(fromOffsets: indices, toOffset: destination)
        if save(updated) { selectedPageID = current }
    }

    private var layerPanel: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Layers").font(.headline)
                Spacer()
                icon("Add layer", "plus") {
                    let layer = DrawingLayer(name: String(localized: "Layer \(page.layers.count + 1)"))
                    updatePage { $0.layers.append(layer) }
                    selectedLayer = layer.id
                }
            }
            .padding(.leading, 8)
            List {
                ForEach(page.layers.reversed()) { layer in
                    Button { selectedLayer = layer.id } label: {
                        HStack {
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
            }
            .listStyle(.plain)
        }
        .frame(width: 156)
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
                updatePage(on: targetID) { page in
                    page.images = (page.images ?? []) + [image]
                }
                if targetID == page.id { editingImages = true }
            } catch is CancellationError {
                return
            } catch {
                if !Task.isCancelled { library.issue = error.localizedDescription }
            }
        }
    }

    private func loadBackground() {
        guard let page = pdf?.page(at: pageIndex) else {
            pdfImage = nil
            return
        }
        guard let size = try? NotebookPDFExport.thumbnailRequest(page) else {
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

    private func updatePage(on pageID: UUID? = nil, _ change: (inout NotePage) -> Void) {
        var updated = library.notebooks.first { $0.id == notebook.id } ?? notebook
        let targetID = pageID ?? page.id
        guard let index = updated.pages.firstIndex(where: { $0.id == targetID }) else { return }
        change(&updated.pages[index])
        _ = save(updated)
    }

    @discardableResult private func save(_ updated: Notebook) -> Bool {
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

    var body: some View {
        if let drawing = try? PKDrawing(data: ink), !drawing.strokes.isEmpty {
            let bounds = drawing.bounds
            Image(uiImage: drawing.image(from: bounds, scale: 2))
                .position(x: bounds.midX, y: bounds.midY)
        }
    }
}
