import CryptoKit
import Foundation
import PDFKit
import UIKit

@main struct Probe {
    @MainActor static func main() throws {
        let fm = FileManager.default
        let root = URL(fileURLWithPath: CommandLine.arguments[1]).appending(path: "library-tree-probe-\(UUID())")
        defer { try? fm.removeItem(at: root) }
        let source = LibraryStore(directory: root.appending(path: "source"))
        let parent = LibraryFolder(name: "Parent")
        let child = LibraryFolder(name: "Child", parentID: parent.id)
        let empty = LibraryFolder(name: "Empty", parentID: child.id)
        let outside = LibraryFolder(name: "Outside")
        for folder in [parent, child, empty, outside] { try source.saveFolder(folder) }
        var note = Notebook(title: "Body", kind: .notebook, folderID: child.id)
        note.pages[0].text = "Preserve this text"
        try source.save(note)
        var trash = Notebook(title: "Trash", kind: .notebook, folderID: child.id)
        trash.trashedAt = .now
        try source.save(trash)
        try source.save(Notebook(title: "Outside", kind: .notebook, folderID: outside.id))
        let fixture = root.appending(path: "fixture.pdf")
        let pdfBytes = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792)).pdfData { context in
            context.beginPage()
            context.beginPage()
        }
        try pdfBytes.write(to: fixture)
        _ = try source.importFile(fixture, into: parent.id)
        _ = try source.importFile(fixture, into: child.id)
        let whole = try JSONDecoder().decode(LibraryArchive.self, from: source.libraryArchive(root: nil))
        precondition(whole.folders.count == 4 && whole.notebooks.count == 4)
        let bytes = try source.libraryArchive(root: parent.id)
        let archive = try JSONDecoder().decode(LibraryArchive.self, from: bytes)
        precondition(archive.folders.count == 3 && archive.notebooks.count == 3 && archive.pdfs.count == 1)
        precondition(archive.pdfs.values.first == pdfBytes)
        let backup = root.appending(path: "subtree.xnoteslibrary")
        try bytes.write(to: backup)
        let targetRoot = root.appending(path: "target")
        let target = LibraryStore(directory: targetRoot)
        let existing = Notebook(title: "Existing", kind: .notebook)
        try target.save(existing)
        let container = try target.importLibrary(backup, into: nil)
        let second = try target.importLibrary(backup, into: container)
        precondition(container != second && target.notebooks.count == 7 && target.folders.count == 8)
        precondition(Set(target.notebooks.map(\.id)).count == 7)
        precondition(target.notebooks.contains { $0.id == existing.id })
        precondition(target.notebooks.filter { $0.title == "Body" }.allSatisfy { $0.pages[0].text == "Preserve this text" })
        let reopened = LibraryStore(directory: targetRoot)
        precondition(reopened.issue == nil && reopened.notebooks.count == 7 && reopened.folders.count == 8)
        precondition(reopened.notebooks.filter { $0.kind == .pdf }.allSatisfy { reopened.pdf(for: $0)?.pageCount == 2 })
        var invalid = archive
        invalid.folders[0].parentID = invalid.folders[0].id
        try JSONEncoder().encode(invalid).write(to: backup)
        do { _ = try target.importLibrary(backup, into: nil); fatalError("Accepted cycle") } catch {}
        invalid = archive
        invalid.pdfs = [:]
        try JSONEncoder().encode(invalid).write(to: backup)
        do { _ = try target.importLibrary(backup, into: nil); fatalError("Accepted missing original") } catch {}
        invalid = archive
        invalid.pdfs = archive.pdfs.mapValues { $0 + Data([0]) }
        try JSONEncoder().encode(invalid).write(to: backup)
        do { _ = try target.importLibrary(backup, into: nil); fatalError("Accepted changed PDF bytes") } catch {}
        precondition(target.notebooks.count == 7 && !LibraryRestoreTransaction.isPending(in: targetRoot))
        // Force a late commit failure, then resume the exact durable journal at startup.
        let failureRoot = root.appending(path: "failure")
        let failureStore = LibraryStore(directory: failureRoot)
        try fm.createDirectory(at: failureRoot.appending(path: "Folders.plist"), withIntermediateDirectories: false)
        let recovered = Notebook(title: "Journal recovery", kind: .notebook)
        do {
            try LibraryRestoreTransaction.commit(notes: [recovered], folders: [], pdfs: [:], in: failureRoot)
            fatalError("Expected blocked folder publication")
        } catch {}
        precondition(LibraryRestoreTransaction.isPending(in: failureRoot))
        do { try failureStore.save(existing); fatalError("Wrote during pending recovery") } catch {}
        let blocked = LibraryStore(directory: failureRoot)
        precondition(blocked.issue != nil && blocked.notebooks.isEmpty)
        try fm.removeItem(at: failureRoot.appending(path: "Folders.plist"))
        let resumed = LibraryStore(directory: failureRoot)
        precondition(resumed.issue == nil && resumed.notebooks.first?.id == recovered.id)
        precondition(!LibraryRestoreTransaction.isPending(in: failureRoot))
        print("PASS subtree+empty folders, trash exclusion, PDF dedup+bytes, copy IDs, repeated import, reopen, corrupt payload rejection, pending write gate and interrupted restore replay")
    }
}
