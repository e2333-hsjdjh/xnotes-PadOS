import Foundation
import PDFKit

/// The library serializes writes, leases and sweeps on the main actor.
final class PDFResourceCollector {
    private final class Lease {
        let digest: String
        weak var document: PDFDocument?

        init(_ document: PDFDocument, digest: String) {
            self.document = document
            self.digest = digest
        }
    }

    private let directory: URL
    private var leases: [Lease] = []
    private let grace: TimeInterval = 7 * 86_400
    private var catalog: URL { directory.appending(path: "OrphanedPDFs.plist") }

    init(directory: URL) {
        self.directory = directory
    }

    static func isDigest(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.allSatisfy { byte in
            (48...57).contains(byte) || (97...102).contains(byte)
        }
    }

    func track(_ document: PDFDocument, digest: String) {
        leases.removeAll { $0.document == nil }
        leases.append(Lease(document, digest: digest))
    }

    func protect(_ digest: String) throws {
        var marks = try readMarks()
        if marks.removeValue(forKey: digest) != nil {
            try writeMarks(marks)
        }
    }

    @discardableResult
    func sweep(referenced: Set<String>, now: Date) throws -> Set<String> {
        leases.removeAll { $0.document == nil }
        let protected = referenced.union(leases.map(\.digest))
        let originalMarks = try readMarks()
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .isSymbolicLinkKey]
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: Array(keys))
        var updated: [String: Date] = [:]
        var expired: [(String, URL)] = []

        for file in files where file.pathExtension == "pdf" {
            let digest = file.deletingPathExtension().lastPathComponent
            guard Self.isDigest(digest), !protected.contains(digest) else { continue }
            let values = try file.resourceValues(forKeys: keys)
            guard values.isRegularFile == true, values.isSymbolicLink != true else { continue }
            let firstSeen = originalMarks[digest] ?? now
            updated[digest] = firstSeen
            if now.timeIntervalSince(firstSeen) >= grace {
                expired.append((digest, file))
            }
        }

        if updated != originalMarks { try writeMarks(updated) }
        var removed: Set<String> = []
        var firstError: Error?
        for (digest, file) in expired {
            do {
                try FileManager.default.removeItem(at: file)
                removed.insert(digest)
            } catch {
                firstError = firstError ?? error
            }
        }
        if let firstError { throw firstError }
        return removed
    }

    private func readMarks() throws -> [String: Date] {
        guard FileManager.default.fileExists(atPath: catalog.path) else { return [:] }
        return try PropertyListDecoder().decode([String: Date].self, from: Data(contentsOf: catalog))
    }

    private func writeMarks(_ marks: [String: Date]) throws {
        try PropertyListEncoder().encode(marks).write(to: catalog, options: .atomic)
    }
}
