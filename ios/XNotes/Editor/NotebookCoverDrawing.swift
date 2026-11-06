import SwiftUI

extension NotebookCover {
    var label: LocalizedStringKey {
        switch self {
        case .cloth: "Book Cloth"
        case .kanagawa: "Kanagawa"
        case .earthrise: "Earthrise"
        case .almondBlossom: "Almond Blossom"
        case .bubbleNet: "Bubble Net"
        case .cosmicCliffs: "Cosmic Cliffs"
        case .floe: "Floe"
        case .pineForest: "Pine Forest"
        case .saintTropez: "Saint-Tropez"
        case .strawberryThief: "Strawberry Thief"
        }
    }

    var usesLightInk: Bool {
        switch self {
        case .kanagawa, .almondBlossom, .floe, .saintTropez: false
        default: true
        }
    }

    var colors: (paper: UInt32, label: UInt32, rule: UInt32) {
        switch self {
        case .cloth: (0x6E2A2C, 0xF1E8D4, 0x3B2A26)
        case .kanagawa: (0xFAEAC6, 0xFDF8EC, 0x1F3D66)
        case .earthrise: (0x000000, 0xEFEEEA, 0x2A2A2E)
        case .almondBlossom: (0x6E8E84, 0xF6F2E3, 0x24484C)
        case .bubbleNet: (0x47676B, 0xF1F5F4, 0x12303C)
        case .cosmicCliffs: (0x604F5A, 0xF5F1E8, 0x1D2B4D)
        case .floe: (0x4C7399, 0xF4F8FB, 0x123A66)
        case .pineForest: (0x6D6951, 0xF3ECD9, 0x34412C)
        case .saintTropez: (0xB6AEA8, 0xFDF6EE, 0x3F4D7D)
        case .strawberryThief: (0x67665E, 0xF1E8D4, 0x22374D)
        }
    }

    static func color(_ rgb: UInt32) -> CGColor {
        CGColor(red: CGFloat((rgb >> 16) & 255) / 255,
                green: CGFloat((rgb >> 8) & 255) / 255,
                blue: CGFloat(rgb & 255) / 255, alpha: 1)
    }

    private static let artworks: [Self: UIImage] = Dictionary(uniqueKeysWithValues: allCases.compactMap { cover in
        guard let url = Bundle.main.url(forResource: "cover-\(cover.rawValue)", withExtension: "webp"),
              let data = try? Data(contentsOf: url), let image = UIImage(data: data) else { return nil }
        return (cover, image)
    })

    func draw(in context: CGContext, size: CGSize, parameters: NotebookCoverParameters? = nil) {
        let bounds = CGRect(origin: .zero, size: size)
        context.saveGState()
        defer { context.restoreGState() }
        context.clip(to: bounds)

        let colors = colors
        context.setFillColor(Self.color(self == .cloth ? parameters?.clothColor ?? colors.paper : colors.paper))
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
            default:
                let scale = max(size.width / image.size.width, size.height / image.size.height)
                let width = image.size.width * scale
                let height = image.size.height * scale
                let offsetY = (size.height - height) * (self == .pineForest ? 1 : 0.5)
                frame = CGRect(x: (size.width - width) / 2, y: offsetY, width: width, height: height)
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
        let labelScale = CGFloat(parameters?.labelScale ?? 1)
        guard labelScale > 0 else { return }
        let labelEdge = edge * labelScale
        let wide = max(0, size.width - size.height)
        let center: CGPoint
        switch self {
        case .cloth: center = CGPoint(x: size.width / 2, y: size.height * 0.30)
        case .kanagawa: center = CGPoint(x: size.width / 2 + wide * 0.8, y: size.height * 0.20)
        case .earthrise: center = CGPoint(x: size.width / 2, y: size.height * 0.90 - wide * 0.05)
        case .almondBlossom: center = CGPoint(x: size.width / 2, y: size.height * 0.24)
        case .bubbleNet: center = CGPoint(x: size.width / 2, y: size.height * 0.18)
        case .cosmicCliffs, .pineForest: center = CGPoint(x: size.width / 2, y: size.height * 0.20 - wide * 0.15)
        case .floe: center = CGPoint(x: size.width / 2 - wide * 0.8, y: size.height * 0.20)
        case .saintTropez: center = CGPoint(x: size.width / 2, y: size.height * 0.18 - wide * 0.1)
        case .strawberryThief: center = CGPoint(x: size.width / 2, y: size.height * 0.28)
        }
        let plate = CGRect(x: center.x - labelEdge * 0.29, y: center.y - labelEdge * 0.095,
                           width: labelEdge * 0.58, height: labelEdge * 0.19)
        context.setFillColor(Self.color(parameters?.labelColor ?? colors.label))
        context.fill(plate)
        context.setStrokeColor(Self.color(parameters?.ruleColor ?? colors.rule))
        context.setLineWidth(max(0.5, edge * 0.0016))
        context.stroke(plate.insetBy(dx: labelEdge * 0.008, dy: labelEdge * 0.008))
    }
}

struct NotebookCoverArtwork: View {
    let cover: NotebookCover
    var parameters: NotebookCoverParameters? = nil

    var body: some View {
        Canvas { context, size in
            context.withCGContext { cover.draw(in: $0, size: size, parameters: parameters) }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
