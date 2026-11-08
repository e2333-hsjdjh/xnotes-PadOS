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

struct PageImageCropView<Footer: View>: View {
    let image: PageImage
    var title: LocalizedStringKey = "Crop image"
    var confirmation: LocalizedStringKey = "Apply"
    var allowFullImage = false
    let footer: Footer
    let apply: (CGRect) -> Bool

    init(image: PageImage, title: LocalizedStringKey = "Crop image", confirmation: LocalizedStringKey = "Apply",
         allowFullImage: Bool = false, @ViewBuilder footer: () -> Footer, apply: @escaping (CGRect) -> Bool) {
        self.image = image
        self.title = title
        self.confirmation = confirmation
        self.allowFullImage = allowFullImage
        self.footer = footer()
        self.apply = apply
    }

    @Environment(\.dismiss) private var dismiss
    @State private var topLeft = CGPoint.zero
    @State private var bottomRight = CGPoint(x: 1, y: 1)
    private let space = "image.crop"

    private var region: CGRect {
        CGRect(x: topLeft.x, y: topLeft.y, width: bottomRight.x - topLeft.x, height: bottomRight.y - topLeft.y)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: UITokens.noSpacing) {
                GeometryReader { geometry in
                    if let bitmap = UIImage(data: image.data), geometry.size.width > UITokens.minTouchTarget, geometry.size.height > UITokens.minTouchTarget {
                        let scale = min((geometry.size.width - UITokens.minTouchTarget) / bitmap.size.width,
                                        (geometry.size.height - UITokens.minTouchTarget) / bitmap.size.height)
                        let size = CGSize(width: bitmap.size.width * scale, height: bitmap.size.height * scale)
                        ZStack {
                            Image(uiImage: bitmap).resizable()
                            Canvas { context, _ in
                                let box = CGRect(x: region.minX * size.width, y: region.minY * size.height,
                                                 width: region.width * size.width, height: region.height * size.height)
                                var mask = Path(CGRect(origin: .zero, size: size))
                                mask.addRect(box)
                                context.fill(mask, with: .color(.black.opacity(0.55)), style: FillStyle(eoFill: true))
                                context.stroke(Path(box), with: .color(.black), lineWidth: UITokens.overlayOutline)
                                context.stroke(Path(box), with: .color(.white), lineWidth: UITokens.hairline)
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
                footer
            }
            .navigationTitle(Text(title))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button { if apply(region) { dismiss() } } label: { Text(confirmation) }
                        .disabled(!allowFullImage && region == CGRect(x: 0, y: 0, width: 1, height: 1))
                }
            }
        }
    }

    private func handle(_ point: CGPoint, size: CGSize, leading: Bool) -> some View {
        Circle().fill(.white).overlay { Circle().strokeBorder(.black, lineWidth: 2) }
            .frame(width: UITokens.cropHandle, height: UITokens.cropHandle)
            .frame(width: UITokens.minTouchTarget, height: UITokens.minTouchTarget)
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

extension PageImageCropView where Footer == EmptyView {
    init(image: PageImage, title: LocalizedStringKey = "Crop image", confirmation: LocalizedStringKey = "Apply",
         allowFullImage: Bool = false, apply: @escaping (CGRect) -> Bool) {
        self.init(image: image, title: title, confirmation: confirmation, allowFullImage: allowFullImage, footer: { EmptyView() }, apply: apply)
    }
}
