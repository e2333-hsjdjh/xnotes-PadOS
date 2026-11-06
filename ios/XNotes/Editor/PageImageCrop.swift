import SwiftUI

enum PageImageCrop {
    static func apply(_ region: CGRect, to image: PageImage) throws -> PageImage {
        guard let bitmap = UIImage(data: image.data)?.cgImage else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let width = CGFloat(bitmap.width)
        let height = CGFloat(bitmap.height)
        let bounds = CGRect(x: 0, y: 0, width: width, height: height)
        let pixels = CGRect(x: region.minX * width, y: region.minY * height,
                            width: region.width * width, height: region.height * height)
            .integral.intersection(bounds)
        guard !pixels.isEmpty, let cropped = bitmap.cropping(to: pixels),
              let bytes = UIImage(cgImage: cropped).pngData() else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let frame = image.frame
        var updated = PageImage(data: bytes, frame: CGRect(
            x: frame.minX + pixels.minX / width * frame.width,
            y: frame.minY + pixels.minY / height * frame.height,
            width: pixels.width / width * frame.width,
            height: pixels.height / height * frame.height
        ))
        updated.id = image.id
        return updated
    }
}

struct PageImageCropView: View {
    let image: PageImage
    let apply: (CGRect) -> Bool

    @Environment(\.dismiss) private var dismiss
    @State private var topLeft = CGPoint.zero
    @State private var bottomRight = CGPoint(x: 1, y: 1)
    private let space = "image.crop"

    private var region: CGRect {
        CGRect(x: topLeft.x, y: topLeft.y, width: bottomRight.x - topLeft.x, height: bottomRight.y - topLeft.y)
    }

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                if let bitmap = UIImage(data: image.data), geometry.size.width > 48, geometry.size.height > 48 {
                    let scale = min((geometry.size.width - 48) / bitmap.size.width,
                                    (geometry.size.height - 48) / bitmap.size.height)
                    let size = CGSize(width: bitmap.size.width * scale, height: bitmap.size.height * scale)
                    ZStack {
                        Image(uiImage: bitmap).resizable()
                        Canvas { context, _ in
                            let box = CGRect(x: region.minX * size.width, y: region.minY * size.height,
                                             width: region.width * size.width, height: region.height * size.height)
                            var mask = Path(CGRect(origin: .zero, size: size))
                            mask.addRect(box)
                            context.fill(mask, with: .color(.black.opacity(0.55)), style: FillStyle(eoFill: true))
                            context.stroke(Path(box), with: .color(.white), lineWidth: 2)
                        }
                        .allowsHitTesting(false)
                        handle(topLeft, size: size, leading: true)
                        handle(bottomRight, size: size, leading: false)
                    }
                    .frame(width: size.width, height: size.height)
                    .coordinateSpace(name: space)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle("Crop image")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") { if apply(region) { dismiss() } }
                        .disabled(region == CGRect(x: 0, y: 0, width: 1, height: 1))
                }
            }
        }
    }

    private func handle(_ point: CGPoint, size: CGSize, leading: Bool) -> some View {
        Circle().fill(.white).overlay { Circle().strokeBorder(.black, lineWidth: 2) }
            .frame(width: 18, height: 18)
            .frame(width: 48, height: 48)
            .contentShape(Rectangle())
            .position(x: point.x * size.width, y: point.y * size.height)
            .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .named(space)).onChanged { drag in
                let x = drag.location.x / size.width
                let y = drag.location.y / size.height
                if leading {
                    topLeft = CGPoint(x: min(max(0, x), bottomRight.x - 0.05),
                                      y: min(max(0, y), bottomRight.y - 0.05))
                } else {
                    bottomRight = CGPoint(x: max(min(1, x), topLeft.x + 0.05),
                                          y: max(min(1, y), topLeft.y + 0.05))
                }
            })
    }
}
