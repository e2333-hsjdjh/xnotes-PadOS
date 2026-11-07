import SwiftUI
import UniformTypeIdentifiers

struct ExportFile: FileDocument {
    static let readableContentTypes: [UTType] = [.xnotesArchive, .xnotesLibrary, .pdf, .png]
    var data = Data()
    var filename: String?

    init(data: Data = Data(), filename: String? = nil) {
        self.data = data
        self.filename = filename
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        let file = FileWrapper(regularFileWithContents: data)
        file.preferredFilename = filename
        return file
    }
}
