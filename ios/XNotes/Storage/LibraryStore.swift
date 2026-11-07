import CryptoKit
import Foundation
import Observation
import PDFKit
import PencilKit
import UniformTypeIdentifiers

@MainActor @Observable
final class LibraryStore {
    private(set) var notebooks: [Notebook] = []
    struct PageClipboard {
        let pages: [NotePage]
        let sources: [String: PDFDocument]
    }

    private(set) var pageClipboard: PageClipboard?
    private var noteBytes: [UUID: Int] = [:]
    private var originalBytes: [String: Int] = [:]
    private(set) var folders: [LibraryFolder] = []
    private var folderCatalogReadable = true
    private(set) var unreadableCount = 0
    var issue: String?
    private(set) var pdfRestorationRevision = 0
    private let directory: URL
    let viewStates: DocumentViewStateStore
    private let pdfResources: PDFResourceCollector
    private var documentScanCompleted = false

    private var folderCatalog: URL { directory.appending(path: "Folders.plist") }

    init(directory: URL = URL.applicationSupportDirectory.appending(path: "XNotes")) {
        self.directory = directory
        pdfResources = PDFResourceCollector(directory: directory)
        viewStates = DocumentViewStateStore(directory: directory.appending(path: "ViewStates"))
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try LibraryRestoreTransaction.recover(in: directory)
            loadFolders()
            let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            for file in files where file.pathExtension == "json" {
                do {
                    let data = try Data(contentsOf: file)
                    let notebook = try JSONDecoder().decode(Notebook.self, from: data)
                    guard file.deletingPathExtension().lastPathComponent == notebook.id.uuidString,
                          isValid(notebook) else { throw CocoaError(.fileReadCorruptFile) }
                    notebooks.append(notebook)
                    noteBytes[notebook.id] = data.count
                } catch {
                    unreadableCount += 1
                }
            }
            for digest in Set(notebooks.flatMap(\.pdfDigests)) {
                originalBytes[digest] = try? pdfURL(digest).resourceValues(forKeys: [.fileSizeKey]).fileSize
            }
            documentScanCompleted = true
        } catch {
            issue = error.localizedDescription
        }
    }

    private func loadFolders() {
        guard FileManager.default.fileExists(atPath: folderCatalog.path) else { return }
        do {
            let stored = try PropertyListDecoder().decode([LibraryFolder].self, from: Data(contentsOf: folderCatalog))
            let ids = Set(stored.map(\.id))
            guard ids.count == stored.count else { throw CocoaError(.fileReadCorruptFile) }
            let groups = Dictionary(grouping: stored, by: \.parentID)
            var pending = groups[nil] ?? []
            var visited = 0
            while let folder = pending.popLast() {
                visited += 1
                pending.append(contentsOf: groups[folder.id] ?? [])
            }
            guard visited == stored.count else { throw CocoaError(.fileReadCorruptFile) }
            folders = stored
        } catch {
            folderCatalogReadable = false
            issue = error.localizedDescription
        }
    }

    func saveFolder(_ folder: LibraryFolder) throws {
        guard folderCatalogReadable,
              folder.parentID == nil || folders.contains(where: { $0.id == folder.parentID }) else {
            throw CocoaError(.fileWriteUnknown)
        }
        var updated = folders
        if let index = updated.firstIndex(where: { $0.id == folder.id }) {
            updated[index].name = folder.name
        } else {
            updated.append(folder)
        }
        try writeFolders(updated)
    }

    func moveFolder(_ id: UUID, to parentID: UUID?) throws {
        guard folderCatalogReadable, let index = folders.firstIndex(where: { $0.id == id }) else {
            throw CocoaError(.fileNoSuchFile)
        }
        if let parentID {
            guard folders.contains(where: { $0.id == parentID }),
                  !LibraryFolder.subtree(root: id, in: folders).contains(parentID) else {
                throw CocoaError(.fileWriteInvalidFileName)
            }
        }
        guard folders[index].parentID != parentID else { return }
        var updated = folders
        updated[index].parentID = parentID
        try writeFolders(updated)
    }

    func deleteFolder(_ folder: LibraryFolder) throws {
        guard folderCatalogReadable, unreadableCount == 0,
              !folders.contains(where: { $0.parentID == folder.id }),
              !notebooks.contains(where: { $0.folderID == folder.id }) else {
            throw CocoaError(.fileWriteUnknown)
        }
        try writeFolders(folders.filter { $0.id != folder.id })
    }

    private func ensureWritable() throws {
        guard documentScanCompleted, !LibraryRestoreTransaction.isPending(in: directory) else {
            throw LibraryRestoreError.pending
        }
    }

    private func writeFolders(_ updated: [LibraryFolder]) throws {
        try ensureWritable()
        try PropertyListEncoder().encode(updated).write(to: folderCatalog, options: .atomic)
        folders = updated
    }

    func moveDocument(_ id: UUID, to folderID: UUID?) throws {
        guard folderID == nil || folders.contains(where: { $0.id == folderID }),
              var document = notebooks.first(where: { $0.id == id }) else {
            throw CocoaError(.fileNoSuchFile)
        }
        document.folderID = folderID
        try save(document)
    }

    enum DocumentChange {
        case move(UUID?)
        case trash(Bool)
        case deletePermanently
        case deleteWithoutTrash
    }

    struct BatchResult {
        var saved: Set<UUID> = []
        var failures: [UUID: String] = [:]
    }

    func changeDocuments(_ ids: Set<UUID>, applying change: DocumentChange) -> BatchResult {
        var result = BatchResult()
        let timestamp = Date()
        for id in ids {
            do {
                switch change {
                case .deletePermanently:
                    try deleteDocument(id, requiresTrash: true)
                case .deleteWithoutTrash:
                    try deleteDocument(id, requiresTrash: false)
                case .move(let folderID):
                    try moveDocument(id, to: folderID)
                case .trash(let trashed):
                    guard var notebook = notebooks.first(where: { $0.id == id }) else {
                        throw CocoaError(.fileNoSuchFile)
                    }
                    if (notebook.trashedAt != nil) != trashed {
                        notebook.trashedAt = trashed ? timestamp : nil
                        try save(notebook)
                    }
                }
                result.saved.insert(id)
            } catch {
                result.failures[id] = error.localizedDescription
            }
        }
        return result
    }

    func save(_ notebook: Notebook) throws {
        try ensureWritable()
        var updated = notebook
        let previous = notebooks.first { $0.id == updated.id }
        for digest in updated.pdfDigests.subtracting(previous?.pdfDigests ?? []) {
            try pdfResources.protect(digest)
        }
        updated.modified = .now
        let file = directory.appending(path: "\(updated.id.uuidString).json")
        let data = try JSONEncoder().encode(updated)
        try data.write(to: file, options: .atomic)
        noteBytes[updated.id] = data.count
        if let index = notebooks.firstIndex(where: { $0.id == updated.id }) {
            notebooks[index] = updated
        } else {
            notebooks.append(updated)
        }
    }

    func purgeExpiredTrash(days: Int, now: Date = .now) -> BatchResult {
        guard (1...365).contains(days) else { return BatchResult() }
        let cutoff = now.addingTimeInterval(-Double(days) * 86_400)
        let expired = notebooks.compactMap { document in
            document.trashedAt.map { $0 <= cutoff } == true ? document.id : nil
        }
        return changeDocuments(Set(expired), applying: .deletePermanently)
    }

    private func deleteDocument(_ id: UUID, requiresTrash: Bool) throws {
        try ensureWritable()
        guard notebooks.contains(where: { $0.id == id && ($0.trashedAt != nil) == requiresTrash }) else {
            throw CocoaError(.fileNoSuchFile)
        }
        let file = directory.appending(path: "\(id.uuidString).json")
        guard try file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else {
            throw CocoaError(.fileWriteUnknown)
        }
        try FileManager.default.removeItem(at: file)
        notebooks.removeAll { $0.id == id }
        noteBytes.removeValue(forKey: id)
        viewStates.remove(id)
    }

    func pdf(for notebook: Notebook) -> PDFDocument? {
        guard let digest = notebook.pdfDigest,
              let document = PDFDocument(url: pdfURL(digest)), !document.isLocked,
              document.pageCount == notebook.pages.count else { return nil }
        pdfResources.track(document, digest: digest)
        return document
    }

    @discardableResult
    func copyPages(from id: UUID, selecting ids: Set<UUID>,
                   beforePublishing: () -> Bool = { true }) throws -> Bool {
        guard let notebook = notebooks.first(where: { $0.id == id && $0.trashedAt == nil }),
              notebook.kind != .canvas, !ids.isEmpty else { throw CocoaError(.fileReadNoSuchFile) }
        let pages = notebook.pages.enumerated().filter { ids.contains($0.element.id) }.map { index, original in
            var page = original
            if let digest = notebook.pdfDigest {
                page.pdfBackground = PDFPageReference(digest: digest, index: index)
            }
            return page
        }
        guard pages.count == ids.count else { throw CocoaError(.fileReadNoSuchFile) }
        var sources: [String: PDFDocument] = [:]
        for reference in pages.compactMap(\.pdfBackground) {
            if sources[reference.digest] == nil { sources[reference.digest] = pagePDF(for: reference.digest) }
            guard sources[reference.digest]?.page(at: reference.index) != nil else {
                throw CocoaError(.fileReadCorruptFile)
            }
        }
        guard beforePublishing() else { return false }
        pageClipboard = PageClipboard(pages: pages, sources: sources)
        return true
    }

    func pagePDF(for digest: String) -> PDFDocument? {
        guard PDFResourceCollector.isDigest(digest), let document = PDFDocument(url: pdfURL(digest)),
              !document.isLocked else { return nil }
        pdfResources.track(document, digest: digest)
        return document
    }

    struct PDFInsertion {
        let data: Data
        let document: PDFDocument
    }

    func preparePDFInsertion(from url: URL) throws -> PDFInsertion {
        let data = try readFile(url)
        return PDFInsertion(data: data, document: try readablePDF(data))
    }

    func insertedPDFPages(from source: PDFInsertion, selecting indices: IndexSet) throws -> [NotePage] {
        guard !indices.isEmpty, let last = indices.last, last < source.document.pageCount else {
            throw CocoaError(.fileReadNoSuchFile)
        }
        let hash = try preservePDF(source.data)
        return indices.map { index in
            NotePage(pdfBackground: PDFPageReference(digest: hash, index: index))
        }
    }

    @discardableResult
    func sweepUnreferencedPDFs(now: Date = .now) throws -> Set<String> {
        guard documentScanCompleted, unreadableCount == 0,
              !LibraryRestoreTransaction.isPending(in: directory) else { return [] }
        let removed = try pdfResources.sweep(referenced: Set(notebooks.flatMap(\.pdfDigests)), now: now)
        for digest in removed { originalBytes.removeValue(forKey: digest) }
        return removed
    }

    func restoreOriginalPDF(for documentID: UUID, from url: URL) throws {
        guard let notebook = notebooks.first(where: { $0.id == documentID }),
              notebook.kind == .pdf, let expected = notebook.pdfDigest else {
            throw CocoaError(.fileNoSuchFile)
        }
        let candidate = try recoveryPDF(from: url, expected: expected)
        guard try pageCount(candidate) == notebook.pages.count else {
            throw CocoaError(.fileReadCorruptFile)
        }
        try publishRecoveredPDF(candidate, digest: expected)
    }

    func restorePagePDF(for documentID: UUID, pageID: UUID, from url: URL) throws {
        guard let notebook = notebooks.first(where: { $0.id == documentID }),
              let reference = notebook.pages.first(where: { $0.id == pageID })?.pdfBackground else {
            throw CocoaError(.fileNoSuchFile)
        }
        let candidate = try recoveryPDF(from: url, expected: reference.digest)
        let count = try pageCount(candidate)
        let matching = notebook.pages.compactMap(\.pdfBackground).filter { $0.digest == reference.digest }
        guard matching.allSatisfy({ (0..<count).contains($0.index) }) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        try publishRecoveredPDF(candidate, digest: reference.digest)
    }

    private func recoveryPDF(from url: URL, expected: String) throws -> Data {
        let contents = try readFile(url)
        let candidate: Data
        if url.pathExtension.lowercased() == "pdf" {
            candidate = contents
        } else {
            let archive = try JSONDecoder().decode(NotebookArchive.self, from: contents)
            guard (1...2).contains(archive.version),
                  let original = archive.pagePDFs?[expected] ?? archive.originalPDF else {
                throw PDFRecoveryError.missingOriginal
            }
            candidate = original
        }
        guard digest(candidate) == expected else { throw PDFRecoveryError.differentOriginal }
        return candidate
    }

    private func publishRecoveredPDF(_ candidate: Data, digest expected: String) throws {
        try ensureWritable()
        let destination = pdfURL(expected)
        if (try? Data(contentsOf: destination, options: .mappedIfSafe)) != candidate {
            try candidate.write(to: destination, options: .atomic)
        }
        originalBytes[expected] = candidate.count
        pdfRestorationRevision += 1
    }

    func createAnnotationCopy(of documentID: UUID) throws -> UUID {
        guard let original = notebooks.first(where: { $0.id == documentID && $0.kind == .pdf }) else {
            throw CocoaError(.fileNoSuchFile)
        }
        let title = String(localized: "\(original.title) (Annotations)")
        var copy = Notebook(title: title, kind: .notebook, folderID: original.folderID)
        copy.pages = original.pages.map { source in
            var page = source
            page.pdfTextMarkups = nil
            let size = source.paperSize ?? .letter
            var width = size.width
            var height = size.height
            for image in page.images ?? [] {
                width = max(width, image.frame.maxX)
                height = max(height, image.frame.maxY)
            }
            for layer in page.layers where !layer.ink.isEmpty {
                guard let drawing = try? PKDrawing(data: layer.ink), !drawing.bounds.isEmpty else { continue }
                let bounds = drawing.bounds
                if bounds.maxX.isFinite { width = max(width, bounds.maxX) }
                if bounds.maxY.isFinite { height = max(height, bounds.maxY) }
            }
            page.paperSize = .enclosing(width: width, height: height)
            return page
        }
        try save(copy)
        return copy.id
    }

    private func readFile(_ url: URL) throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try Data(contentsOf: url)
    }

    func importFile(_ url: URL, into folderID: UUID? = nil) throws -> UUID {
        let data = try readFile(url)
        var notebook: Notebook
        if url.pathExtension.lowercased() == "pdf" {
            let pdf = try readablePDF(data)
            notebook = Notebook(title: url.deletingPathExtension().lastPathComponent, kind: .pdf)
            notebook.pages = (0..<pdf.pageCount).map { index in
                var page = NotePage()
                if let original = pdf.page(at: index), let size = try? PDFPageGeometry.displaySize(original) {
                    page.paperSize = .enclosing(width: size.width, height: size.height)
                }
                return page
            }
            notebook.pdfDigest = try preservePDF(data)
        } else {
            let archive = try JSONDecoder().decode(NotebookArchive.self, from: data)
            notebook = archive.notebook
            guard (1...2).contains(archive.version), isValid(notebook),
                  notebook.pages.allSatisfy({ page in
                      page.layers.allSatisfy { $0.ink.isEmpty || (try? PKDrawing(data: $0.ink)) != nil }
                  }) else { throw CocoaError(.fileReadCorruptFile) }
            if notebook.kind == .pdf {
                guard let pdf = archive.originalPDF, try pageCount(pdf) == notebook.pages.count,
                      digest(pdf) == notebook.pdfDigest else { throw CocoaError(.fileReadCorruptFile) }
                notebook.pdfDigest = try preservePDF(pdf)
            }
            let pagePDFs = archive.pagePDFs ?? [:]
            for (hash, references) in Dictionary(grouping: notebook.pages.compactMap(\.pdfBackground), by: \.digest) {
                guard let bytes = pagePDFs[hash], digest(bytes) == hash else { throw CocoaError(.fileReadCorruptFile) }
                let count = try pageCount(bytes)
                guard references.allSatisfy({ $0.index < count }) else { throw CocoaError(.fileReadCorruptFile) }
                _ = try preservePDF(bytes)
            }
            notebook.id = UUID()
            notebook.trashedAt = nil
        }
        notebook.folderID = folderID
        try save(notebook)
        return notebook.id
    }

    func libraryArchive(root: UUID?) throws -> Data {
        try ensureWritable()
        guard folderCatalogReadable, unreadableCount == 0, documentScanCompleted,
              root == nil || folders.contains(where: { $0.id == root }) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let ids = root.map { LibraryFolder.subtree(root: $0, in: folders) } ?? Set(folders.map(\.id))
        let selected = notebooks.filter { note in
            note.trashedAt == nil && (root == nil || note.folderID.map(ids.contains) == true)
        }
        let selectedFolders = folders.filter { ids.contains($0.id) }.map { original in
            var folder = original
            if folder.id == root { folder.parentID = nil }
            return folder
        }
        var pdfs: [String: Data] = [:]
        for hash in Set(selected.flatMap(\.pdfDigests)) {
            let bytes = try Data(contentsOf: pdfURL(hash), options: .mappedIfSafe)
            guard digest(bytes) == hash else { throw CocoaError(.fileReadCorruptFile) }
            pdfs[hash] = bytes
        }
        let archive = LibraryArchive(name: String(localized: "Library backup"), folders: selectedFolders,
                                     notebooks: selected, pdfs: pdfs)
        try archive.validateHierarchy()
        return try JSONEncoder().encode(archive)
    }

    /// Restores a copy below a fresh container, never replacing existing documents.
    @discardableResult
    func importLibrary(_ url: URL, into parentID: UUID?) throws -> UUID {
        try ensureWritable()
        guard folderCatalogReadable, parentID == nil || folders.contains(where: { $0.id == parentID }) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let archive = try JSONDecoder().decode(LibraryArchive.self, from: readFile(url))
        try archive.validateHierarchy()
        let references = Set(archive.notebooks.flatMap(\.pdfDigests))
        guard Set(archive.pdfs.keys) == references else { throw CocoaError(.fileReadCorruptFile) }
        var pageCounts: [String: Int] = [:]
        for (hash, bytes) in archive.pdfs {
            guard digest(bytes) == hash else { throw CocoaError(.fileReadCorruptFile) }
            pageCounts[hash] = try pageCount(bytes)
        }
        for note in archive.notebooks {
            guard isValid(note), note.pages.allSatisfy({ page in
                page.layers.allSatisfy { $0.ink.isEmpty || (try? PKDrawing(data: $0.ink)) != nil }
            }), note.pdfDigest.map({ pageCounts[$0] == note.pages.count }) ?? true,
            note.pages.compactMap(\.pdfBackground).allSatisfy({ $0.index < (pageCounts[$0.digest] ?? 0) }) else {
                throw CocoaError(.fileReadCorruptFile)
            }
        }
        let container = LibraryFolder(name: archive.name, parentID: parentID)
        let mapping = Dictionary(uniqueKeysWithValues: archive.folders.map { ($0.id, UUID()) })
        let restoredFolders = archive.folders.map { source in
            LibraryFolder(id: mapping[source.id]!, name: source.name,
                          parentID: source.parentID.flatMap { mapping[$0] } ?? container.id)
        }
        let restored = archive.notebooks.map { source in
            var note = source
            note.id = UUID()
            note.folderID = source.folderID.flatMap { mapping[$0] } ?? container.id
            return note
        }
        for hash in references { try pdfResources.protect(hash) }
        let updatedFolders = folders + [container] + restoredFolders
        try LibraryRestoreTransaction.commit(notes: restored, folders: updatedFolders, pdfs: archive.pdfs, in: directory)
        folders = updatedFolders
        notebooks.append(contentsOf: restored)
        for note in restored { noteBytes[note.id] = try JSONEncoder().encode(note).count }
        for (hash, data) in archive.pdfs { originalBytes[hash] = data.count }
        return container.id
    }

    func documentSize(_ notebook: Notebook) -> Int64 {
        let note = noteBytes[notebook.id] ?? 0
        let pdf = notebook.pdfDigests.reduce(0) { $0 + (originalBytes[$1] ?? 0) }
        return Int64(note) + Int64(pdf)
    }

    func archive(for notebook: Notebook) throws -> Data {
        func checkedPDF(_ expected: String) throws -> Data {
            let data = try Data(contentsOf: pdfURL(expected), options: .mappedIfSafe)
            guard digest(data) == expected else { throw CocoaError(.fileReadCorruptFile) }
            return data
        }
        let pdf = try notebook.pdfDigest.map(checkedPDF)
        var pagePDFs: [String: Data] = [:]
        for digest in Set(notebook.pages.compactMap({ $0.pdfBackground?.digest })) {
            pagePDFs[digest] = try checkedPDF(digest)
        }
        var portable = notebook
        portable.folderID = nil
        return try JSONEncoder().encode(NotebookArchive(version: pagePDFs.isEmpty ? 1 : 2, notebook: portable, originalPDF: pdf, pagePDFs: pagePDFs.isEmpty ? nil : pagePDFs))
    }

    private func isValid(_ notebook: Notebook) -> Bool {
        guard !notebook.pages.isEmpty,
              Set(notebook.pages.map(\.id)).count == notebook.pages.count,
              notebook.pages.allSatisfy({ page in
                  !page.layers.isEmpty && Set(page.layers.map(\.id)).count == page.layers.count &&
                  (page.coverArtwork == nil || (notebook.kind == .notebook && page.pdfBackground == nil)) &&
                  (page.coverParameters.map { page.coverArtwork != nil && $0.isValid } ?? true) &&
                  (page.paperParameters.map { $0.isValid(for: page.paperPattern ?? .blank) } ?? true) &&
                  validImages(page.images ?? []) && (page.paperSize?.isValid ?? true) &&
                  Set((page.bookmarks ?? []).map(\.id)).count == (page.bookmarks ?? []).count &&
                  (page.pdfTextMarkups ?? []).allSatisfy(\.isValid) &&
                  (page.pdfBackground.map {
                      notebook.kind == .notebook && $0.index >= 0 && PDFResourceCollector.isDigest($0.digest)
                  } ?? true)
              }) else { return false }
        let covers = notebook.pages.indices.filter { notebook.pages[$0].isCover == true }
        guard covers.isEmpty || (notebook.kind == .notebook && covers == [0] &&
                                 notebook.pages.count > 1 && notebook.pages[0].pdfBackground == nil) else { return false }
        if notebook.kind == .pdf {
            guard let digest = notebook.pdfDigest else { return false }
            return PDFResourceCollector.isDigest(digest)
        }
        return notebook.pdfDigest == nil && (notebook.kind != .canvas || notebook.pages.count == 1)
    }

    private func validImages(_ images: [PageImage]) -> Bool {
        Set(images.map(\.id)).count == images.count && images.allSatisfy { image in
            let frame = image.frame
            return [frame.origin.x, frame.origin.y, frame.width, frame.height].allSatisfy(\.isFinite) &&
                frame.origin.x >= 0 && frame.origin.y >= 0 &&
                frame.width > 0 && frame.height > 0 && !image.data.isEmpty
        }
    }

    private func pageCount(_ data: Data) throws -> Int {
        try readablePDF(data).pageCount
    }

    private func readablePDF(_ data: Data) throws -> PDFDocument {
        guard let pdf = PDFDocument(data: data), !pdf.isLocked, pdf.pageCount > 0 else {
            throw CocoaError(.fileReadCorruptFile)
        }
        return pdf
    }

    private func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private func pdfURL(_ digest: String) -> URL {
        directory.appending(path: digest).appendingPathExtension("pdf")
    }

    private func preservePDF(_ data: Data) throws -> String {
        try ensureWritable()
        let hash = digest(data)
        let file = pdfURL(hash)
        if FileManager.default.fileExists(atPath: file.path) {
            guard try Data(contentsOf: file, options: .mappedIfSafe) == data else {
                throw CocoaError(.fileReadCorruptFile)
            }
        } else {
            try data.write(to: file, options: .atomic)
        }
        originalBytes[hash] = data.count
        return hash
    }
}

private enum PDFRecoveryError: LocalizedError {
    case missingOriginal, differentOriginal

    var errorDescription: String? {
        switch self {
        case .missingOriginal:
            String(localized: "This backup does not contain an original PDF.")
        case .differentOriginal:
            String(localized: "This PDF does not match the original document.")
        }
    }
}

extension UTType {
    static let xnotesLibrary = UTType(exportedAs: "com.xnotes.ios.library", conformingTo: .data)
    static let xnotesArchive = UTType(exportedAs: "com.xnotes.ios.archive", conformingTo: .data)
}

private enum LibraryRestoreError: LocalizedError {
    case pending
    var errorDescription: String? {
        String(localized: "Library recovery is pending. Reopen the app to finish restoring before making changes.")
    }
}
