import CryptoKit
import Foundation

/// A durable, idempotent journal is replayed before the library is scanned.
/// Once prepared, the operation commits forward; existing document IDs are never reused.
enum LibraryRestoreTransaction {
    private struct Journal: Codable {
        let notes: [Notebook]
        let folders: [LibraryFolder]
        let pdfs: [String: Data]
    }

    private static func journalURL(in directory: URL) -> URL {
        directory.appending(path: "LibraryRestore.pending")
    }

    static func commit(notes: [Notebook], folders: [LibraryFolder], pdfs: [String: Data], in directory: URL) throws {
        let fm = FileManager.default
        let url = journalURL(in: directory)
        guard !fm.fileExists(atPath: url.path),
              notes.allSatisfy({ !fm.fileExists(atPath: directory.appending(path: "\($0.id.uuidString).json").path) }) else {
            throw CocoaError(.fileWriteFileExists)
        }
        for (digest, data) in pdfs {
            let target = directory.appending(path: digest).appendingPathExtension("pdf")
            if fm.fileExists(atPath: target.path) {
                guard try Data(contentsOf: target, options: .mappedIfSafe) == data else {
                    throw CocoaError(.fileReadCorruptFile)
                }
            }
        }
        // Publish the complete recovery payload before making any document visible.
        let journal = Journal(notes: notes, folders: folders, pdfs: pdfs)
        try JSONEncoder().encode(journal).write(to: url, options: .atomic)
        try recover(in: directory)
    }

    static func isPending(in directory: URL) -> Bool {
        FileManager.default.fileExists(atPath: journalURL(in: directory).path)
    }

    static func recover(in directory: URL) throws {
        let url = journalURL(in: directory)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        let journal = try JSONDecoder().decode(Journal.self, from: Data(contentsOf: url))
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        for (digest, data) in journal.pdfs {
            let actual = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            guard actual == digest else { throw CocoaError(.fileReadCorruptFile) }
            try publish(data, to: directory.appending(path: digest).appendingPathExtension("pdf"))
        }
        for note in journal.notes {
            let target = directory.appending(path: "\(note.id.uuidString).json")
            let data = try encoder.encode(note)
            if FileManager.default.fileExists(atPath: target.path) {
                let existing = try JSONDecoder().decode(Notebook.self, from: Data(contentsOf: target))
                guard try encoder.encode(existing) == data else { throw CocoaError(.fileWriteFileExists) }
            } else {
                try data.write(to: target, options: .atomic)
            }
        }
        try PropertyListEncoder().encode(journal.folders).write(to: directory.appending(path: "Folders.plist"), options: .atomic)
        try FileManager.default.removeItem(at: url)
    }

    private static func publish(_ data: Data, to url: URL) throws {
        if FileManager.default.fileExists(atPath: url.path) {
            guard try Data(contentsOf: url, options: .mappedIfSafe) == data else { throw CocoaError(.fileReadCorruptFile) }
        } else {
            try data.write(to: url, options: .atomic)
        }
    }
}
