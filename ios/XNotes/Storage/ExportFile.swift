import SwiftUI
import UniformTypeIdentifiers

struct ExportFile: FileDocument {
    static let readableContentTypes: [UTType] = [.xnotesArchive, .pdf]
    var data = Data()

    init(data: Data = Data()) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
