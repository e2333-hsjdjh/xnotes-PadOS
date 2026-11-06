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
                    let preview = try NotebookPageDrawing.render(input.page, original: original, longestEdge: 160)
                    try Task.checkCancellation()
                    image = preview
                } catch {
                    // Leave the placeholder when the row disappears or a page cannot render.
                }
            }
    }
}
