import Foundation

enum BackupExport {
    static func files(for documents: [Notebook], encode: (Notebook) throws -> Data) throws -> [ExportFile] {
        guard !documents.isEmpty else { throw CocoaError(.fileReadNoSuchFile) }
        var used: Set<String> = []
        return try documents.map { document in
            let stem = String(document.title.replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: ":", with: "_")
                .trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
            let base = stem.isEmpty || stem.hasPrefix(".") ? "XNotes " + stem : stem
            var name = base + ".xnotes"
            var suffix = 2
            while !used.insert(name.precomposedStringWithCanonicalMapping.lowercased()).inserted {
                name = "\(base) (\(suffix)).xnotes"
                suffix += 1
            }
            return ExportFile(data: try encode(document), filename: name)
        }
    }
}
