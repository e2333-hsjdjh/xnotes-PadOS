import PDFKit
import SwiftUI

struct PageThumbnail: View {
    let page: NotePage
    let index: Int
    let pdf: PDFDocument?

    @State private var image: UIImage?

    private struct Input: Equatable {
        let page: NotePage
        let sourcePage: Int?
        let source: ObjectIdentifier?
    }

    var body: some View {
        let input = Input(page: page, sourcePage: pdf == nil ? nil : index,
                          source: pdf.map(ObjectIdentifier.init))
        RoundedRectangle(cornerRadius: 4)
            .fill(Color(uiColor: .secondarySystemBackground))
            .overlay {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .padding(3)
                }
            }
            .frame(width: 52, height: 66)
            .task(id: input) {
                image = nil
                do {
                    try await Task.sleep(for: .milliseconds(100))
                    let original = input.sourcePage.flatMap { pdf?.page(at: $0) }
                    let preview = try Self.render(input.page, original: original)
                    try Task.checkCancellation()
                    image = preview
                } catch {
                    // Leave the placeholder when the row disappears or a page cannot render.
                }
            }
    }

    @MainActor
    static func render(_ page: NotePage, original: PDFPage?) throws -> UIImage {
        let size = try original.map(PDFPageGeometry.displaySize) ?? (page.paperSize ?? .letter).dimensions
        let scale = 160 / max(size.width, size.height)
        let output = CGSize(width: max(1, size.width * scale), height: max(1, size.height * scale))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        var renderError: Error?
        let image = UIGraphicsImageRenderer(size: output, format: format).image { context in
            let graphics = context.cgContext
            graphics.scaleBy(x: output.width / size.width, y: output.height / size.height)
            do {
                try NotebookPageDrawing.draw(page, original: original, in: CGRect(origin: .zero, size: size),
                                             with: graphics, inkScale: scale)
            } catch {
                renderError = error
            }
        }
        if let renderError { throw renderError }
        return image
    }
}
