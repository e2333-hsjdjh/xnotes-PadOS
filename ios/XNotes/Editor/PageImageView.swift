import ImageIO
import SwiftUI

struct PageImageView: View {
    let image: PageImage
    let editing: Bool
    let paperSize: CGSize
    let move: (CGRect) -> Void
    let remove: () -> Void

    @GestureState private var translation = CGSize.zero

    var body: some View {
        let frame = translation == .zero ? image.frame : positioned(translation)
        Group {
            if editing {
                bitmap
                    .background(.clear)
                    .overlay { Rectangle().strokeBorder(.white, lineWidth: 3) }
                    .overlay { Rectangle().strokeBorder(.black, style: StrokeStyle(lineWidth: 1, dash: [4])) }
                    .contentShape(Rectangle())
                    .gesture(DragGesture(coordinateSpace: .named(PaperCoordinates.name))
                        .updating($translation) { value, translation, _ in translation = value.translation }
                        .onEnded { move(positioned($0.translation)) })
                    .contextMenu {
                        Button("Enlarge image", systemImage: "plus.magnifyingglass") { resize(by: 1.25) }
                        Button("Shrink image", systemImage: "minus.magnifyingglass") { resize(by: 0.8) }
                        Button("Remove image", systemImage: "trash", role: .destructive, action: remove)
                    }
            } else {
                bitmap
                    .allowsHitTesting(false)
            }
        }
        .frame(width: frame.width, height: frame.height)
        .position(x: frame.midX, y: frame.midY)
    }

    @ViewBuilder private var bitmap: some View {
        if let bitmap = UIImage(data: image.data) {
            Image(uiImage: bitmap).resizable()
        }
    }

    private func positioned(_ offset: CGSize) -> CGRect {
        bounded(image.frame.offsetBy(dx: offset.width, dy: offset.height))
    }

    private func bounded(_ frame: CGRect) -> CGRect {
        CGRect(x: min(max(0, frame.minX), max(0, paperSize.width - frame.width)),
               y: min(max(0, frame.minY), max(0, paperSize.height - frame.height)),
               width: frame.width, height: frame.height)
    }

    private func resize(by factor: CGFloat) {
        let frame = image.frame
        let scale = min(factor, paperSize.width / frame.width, paperSize.height / frame.height)
        let size = CGSize(width: frame.width * scale, height: frame.height * scale)
        guard min(size.width, size.height) >= 8 else { return }
        move(bounded(CGRect(x: frame.midX - size.width / 2, y: frame.midY - size.height / 2,
                            width: size.width, height: size.height)))
    }

    static func prepare(_ data: Data, for paper: CGSize) throws -> PageImage {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 2048
        ]
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let pixels = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
              let encoded = UIImage(cgImage: pixels).pngData() else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let scale = min(paper.width * 0.6 / CGFloat(pixels.width), paper.height * 0.6 / CGFloat(pixels.height))
        let size = CGSize(width: CGFloat(pixels.width) * scale, height: CGFloat(pixels.height) * scale)
        return PageImage(data: encoded, frame: CGRect(origin: CGPoint(x: 24, y: 24), size: size))
    }
}
