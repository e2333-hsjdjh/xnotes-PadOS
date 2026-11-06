import CryptoKit
import Foundation
import Observation
import PDFKit
import PencilKit
import UniformTypeIdentifiers

@MainActor @Observable
final class LibraryStore {
    private(set) var notebooks: [Notebook] = []
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
            loadFolders()
            let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            for file in files where file.pathExtension == "json" {
                do {
                    let notebook = try JSONDecoder().decode(Notebook.self, from: Data(contentsOf: file))
                    guard file.deletingPathExtension().lastPathComponent == notebook.id.uuidString,
                          isValid(notebook) else { throw CocoaError(.fileReadCorruptFile) }
                    notebooks.append(notebook)
                } catch {
                    unreadableCount += 1
                }
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

    private func writeFolders(_ updated: [LibraryFolder]) throws {
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
        var updated = notebook
        if let digest = updated.pdfDigest,
           !notebooks.contains(where: { $0.id == updated.id && $0.pdfDigest == digest }) {
            try pdfResources.protect(digest)
        }
        updated.modified = .now
        let file = directory.appending(path: "\(updated.id.uuidString).json")
        try JSONEncoder().encode(updated).write(to: file, options: .atomic)
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
        guard notebooks.contains(where: { $0.id == id && ($0.trashedAt != nil) == requiresTrash }) else {
            throw CocoaError(.fileNoSuchFile)
        }
        let file = directory.appending(path: "\(id.uuidString).json")
        guard try file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else {
            throw CocoaError(.fileWriteUnknown)
        }
        try FileManager.default.removeItem(at: file)
        notebooks.removeAll { $0.id == id }
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
    func sweepUnreferencedPDFs(now: Date = .now) throws -> Set<String> {
        guard documentScanCompleted, unreadableCount == 0 else { return [] }
        return try pdfResources.sweep(referenced: Set(notebooks.compactMap(\.pdfDigest)), now: now)
    }

    func restoreOriginalPDF(for documentID: UUID, from url: URL) throws {
        guard let notebook = notebooks.first(where: { $0.id == documentID }),
              notebook.kind == .pdf, let expected = notebook.pdfDigest else {
            throw CocoaError(.fileNoSuchFile)
        }
        let contents = try readFile(url)
        let candidate: Data
        if url.pathExtension.lowercased() == "pdf" {
            candidate = contents
        } else {
            let archive = try JSONDecoder().decode(NotebookArchive.self, from: contents)
            guard archive.version == 1, let original = archive.originalPDF else {
                throw PDFRecoveryError.missingOriginal
            }
            candidate = original
        }
        guard digest(candidate) == expected else { throw PDFRecoveryError.differentOriginal }
        guard try pageCount(candidate) == notebook.pages.count else {
            throw CocoaError(.fileReadCorruptFile)
        }

        let destination = pdfURL(expected)
        if (try? Data(contentsOf: destination, options: .mappedIfSafe)) != candidate {
            try candidate.write(to: destination, options: .atomic)
        }
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
            guard archive.version == 1, isValid(notebook),
                  notebook.pages.allSatisfy({ page in
                      page.layers.allSatisfy { $0.ink.isEmpty || (try? PKDrawing(data: $0.ink)) != nil }
                  }) else { throw CocoaError(.fileReadCorruptFile) }
            if notebook.kind == .pdf {
                guard let pdf = archive.originalPDF, try pageCount(pdf) == notebook.pages.count,
                      digest(pdf) == notebook.pdfDigest else { throw CocoaError(.fileReadCorruptFile) }
                notebook.pdfDigest = try preservePDF(pdf)
            }
            notebook.id = UUID()
            notebook.trashedAt = nil
        }
        notebook.folderID = folderID
        try save(notebook)
        return notebook.id
    }

    func archive(for notebook: Notebook) throws -> Data {
        let pdf = try notebook.pdfDigest.map { try Data(contentsOf: pdfURL($0)) }
        var portable = notebook
        portable.folderID = nil
        return try JSONEncoder().encode(NotebookArchive(version: 1, notebook: portable, originalPDF: pdf))
    }

    private func isValid(_ notebook: Notebook) -> Bool {
        guard !notebook.pages.isEmpty,
              Set(notebook.pages.map(\.id)).count == notebook.pages.count,
              notebook.pages.allSatisfy({ page in
                  !page.layers.isEmpty && Set(page.layers.map(\.id)).count == page.layers.count &&
                  validImages(page.images ?? []) && (page.paperSize?.isValid ?? true) &&
                  Set((page.bookmarks ?? []).map(\.id)).count == (page.bookmarks ?? []).count
              }) else { return false }
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
        let hash = digest(data)
        let file = pdfURL(hash)
        if FileManager.default.fileExists(atPath: file.path) {
            guard try Data(contentsOf: file, options: .mappedIfSafe) == data else {
                throw CocoaError(.fileReadCorruptFile)
            }
        } else {
            try data.write(to: file, options: .atomic)
        }
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
    static let xnotesArchive = UTType(exportedAs: "com.xnotes.ios.archive", conformingTo: .data)
}
