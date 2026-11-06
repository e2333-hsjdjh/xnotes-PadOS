import PencilKit
import SwiftUI

enum LayerPreviewMode: String {
    case none, content, page
}

struct LayerThumbnail: View {
    let ink: Data
    let mode: LayerPreviewMode
    let pageSize: CGSize

    @State private var preview: UIImage?

    private struct RenderInput: Equatable {
        let ink: Data
        let mode: LayerPreviewMode
        let pageSize: CGSize
    }

    var body: some View {
        let input = RenderInput(ink: ink, mode: mode, pageSize: pageSize)
        RoundedRectangle(cornerRadius: 3)
            .fill(Color(white: 0.52))
            .overlay {
                if let preview {
                    Image(uiImage: preview)
                        .resizable()
                        .scaledToFit()
                        .padding(2)
                }
            }
            .frame(width: 44, height: 38)
            .clipped()
            .task(id: input) {
                preview = nil
                do {
                    try await Task.sleep(for: .milliseconds(100))
                    let image = Self.render(ink: input.ink, mode: input.mode, pageSize: input.pageSize)
                    try Task.checkCancellation()
                    preview = image
                } catch is CancellationError {
                    return
                } catch {
                    return
                }
            }
    }

    @MainActor
    static func render(ink: Data, mode: LayerPreviewMode, pageSize: CGSize) -> UIImage? {
        guard mode != .none, let drawing = try? PKDrawing(data: ink), !drawing.strokes.isEmpty else {
            return nil
        }
        let bounds = mode == .page ? CGRect(origin: .zero, size: pageSize) : drawing.bounds
        let edge = max(bounds.width, bounds.height)
        guard !bounds.isEmpty, !bounds.isInfinite, edge.isFinite else { return nil }

        let scale = min(3, 132 / edge)
        var preview: UIImage?
        UITraitCollection(userInterfaceStyle: .light).performAsCurrent {
            preview = drawing.image(from: bounds, scale: scale)
        }
        return preview
    }
}
