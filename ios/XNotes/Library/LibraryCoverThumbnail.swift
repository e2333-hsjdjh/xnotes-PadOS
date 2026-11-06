import PDFKit
import SwiftUI

struct LibraryCoverThumbnail: View {
    let notebook: Notebook
    let library: LibraryStore

    @Environment(\.colorScheme) private var scheme
    @State private var image: UIImage?

    private struct Input: Equatable {
        let page: NotePage?
        let digest: String?
        let revision: Int
        let dark: Bool
    }

    var body: some View {
        let first = notebook.pages.first
        let digest = first?.pdfBackground?.digest ?? notebook.pdfDigest
        let dark = scheme == .dark && digest == nil
        let input = Input(page: first, digest: digest, revision: digest == nil ? 0 : library.pdfRestorationRevision, dark: dark)
        RoundedRectangle(cornerRadius: 4)
            .fill(Color(uiColor: .secondarySystemBackground))
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().scaledToFit().padding(4)
                } else {
                    Image(systemName: notebook.kind.symbol).font(.title2)
                }
            }
            .frame(height: 92)
            .accessibilityHidden(true)
            .task(id: input) {
                image = nil
                guard let page = input.page else { return }
                do {
                    try await Task.sleep(for: .milliseconds(120))
                    let source = input.digest.flatMap(library.pagePDF(for:))
                    let original = source?.page(at: page.pdfBackground?.index ?? 0)
                    guard input.digest == nil || original != nil else { return }
                    let rendered = try NotebookPageDrawing.render(page, original: original, longestEdge: 240,
                                                                  canvas: notebook.kind == .canvas, dark: input.dark)
                    try Task.checkCancellation()
                    image = rendered
                } catch {
                    // Keep the document icon for cancelled or unreadable previews.
                }
            }
    }
}
