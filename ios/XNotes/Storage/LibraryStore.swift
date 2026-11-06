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
    private let directory: URL

    private var folderCatalog: URL { directory.appending(path: "Folders.plist") }

    init(directory: URL = URL.applicationSupportDirectory.appending(path: "XNotes")) {
        self.directory = directory
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

    func save(_ notebook: Notebook) throws {
        var updated = notebook
        updated.modified = .now
        let file = directory.appending(path: "\(updated.id.uuidString).json")
        try JSONEncoder().encode(updated).write(to: file, options: .atomic)
        if let index = notebooks.firstIndex(where: { $0.id == updated.id }) {
            notebooks[index] = updated
        } else {
            notebooks.append(updated)
        }
    }

    func delete(_ notebook: Notebook) throws {
        try FileManager.default.removeItem(at: directory.appending(path: "\(notebook.id.uuidString).json"))
        notebooks.removeAll { $0.id == notebook.id }
    }

    func pdf(for notebook: Notebook) -> PDFDocument? {
        guard let digest = notebook.pdfDigest else { return nil }
        return PDFDocument(url: pdfURL(digest))
    }

    func importFile(_ url: URL, into folderID: UUID? = nil) throws -> UUID {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        var notebook: Notebook
        if url.pathExtension.lowercased() == "pdf" {
            let count = try pageCount(data)
            notebook = Notebook(title: url.deletingPathExtension().lastPathComponent, kind: .pdf)
            notebook.pages = (0..<count).map { _ in NotePage() }
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
                  validImages(page.images ?? [])
              }) else { return false }
        if notebook.kind == .pdf {
            guard let digest = notebook.pdfDigest else { return false }
            return digest.utf8.count == 64 && digest.utf8.allSatisfy { byte in
                (48...57).contains(byte) || (97...102).contains(byte)
            }
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
        guard let pdf = PDFDocument(data: data), !pdf.isLocked, pdf.pageCount > 0 else {
            throw CocoaError(.fileReadCorruptFile)
        }
        return pdf.pageCount
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

extension UTType {
    static let xnotesArchive = UTType(exportedAs: "com.xnotes.ios.archive", conformingTo: .data)
}
