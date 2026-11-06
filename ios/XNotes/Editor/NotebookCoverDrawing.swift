import SwiftUI

extension NotebookCover {
    var label: LocalizedStringKey {
        switch self {
        case .cloth: "Book Cloth"
        case .kanagawa: "Kanagawa"
        case .earthrise: "Earthrise"
        }
    }

    var usesLightInk: Bool { self != .kanagawa }

    private static let artworks: [Self: UIImage] = Dictionary(uniqueKeysWithValues: allCases.compactMap { cover in
        guard let url = Bundle.main.url(forResource: "cover-\(cover.rawValue)", withExtension: "webp"),
              let data = try? Data(contentsOf: url), let image = UIImage(data: data) else { return nil }
        return (cover, image)
    })

    func draw(in context: CGContext, size: CGSize) {
        let bounds = CGRect(origin: .zero, size: size)
        context.saveGState()
        defer { context.restoreGState() }
        context.clip(to: bounds)

        switch self {
        case .cloth: context.setFillColor(red: 0.43, green: 0.16, blue: 0.17, alpha: 1)
        case .kanagawa: context.setFillColor(red: 0.98, green: 0.92, blue: 0.78, alpha: 1)
        case .earthrise: context.setFillColor(gray: 0, alpha: 1)
        }
        context.fill(bounds)

        if let image = Self.artworks[self] {
            let frame: CGRect
            switch self {
            case .cloth:
                frame = bounds
            case .kanagawa:
                let width = max(size.width, size.height * 0.8133)
                let height = width * 1.4144
                frame = CGRect(x: 0, y: size.height - height, width: width, height: height)
            case .earthrise:
                let width = max(size.width, size.height * 1.1)
                let height = width / 1.4254
                frame = CGRect(x: (size.width - width) / 2, y: size.height - height, width: width, height: height)
            }
            UIGraphicsPushContext(context)
            image.draw(in: frame, blendMode: self == .cloth ? .multiply : .normal, alpha: 1)
            UIGraphicsPopContext()
        }

        let edge = min(size.width, size.height)
        if self == .cloth {
            context.setStrokeColor(gray: 0, alpha: 0.35)
            context.setLineWidth(max(0.5, edge * 0.003))
            context.stroke(bounds.insetBy(dx: edge * 0.05, dy: edge * 0.05))
        }
        let centerY: CGFloat
        switch self {
        case .cloth: centerY = size.height * 0.30
        case .kanagawa: centerY = size.height * 0.20
        case .earthrise: centerY = size.height * 0.90
        }
        let plate = CGRect(x: (size.width - edge * 0.58) / 2, y: centerY - edge * 0.095,
                           width: edge * 0.58, height: edge * 0.19)
        context.setFillColor(red: 0.97, green: 0.94, blue: 0.86, alpha: 1)
        context.fill(plate)
        context.setStrokeColor(gray: 0.25, alpha: 1)
        context.setLineWidth(max(0.5, edge * 0.0016))
        context.stroke(plate.insetBy(dx: edge * 0.008, dy: edge * 0.008))
    }
}

struct NotebookCoverArtwork: View {
    let cover: NotebookCover

    var body: some View {
        Canvas { context, size in
            context.withCGContext { cover.draw(in: $0, size: size) }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
