import SwiftUI

enum InkColor: String, CaseIterable {
    case black, red, blue, green, yellow, violet

    var color: UIColor {
        switch self {
        case .black: .black
        case .red: UIColor(red: 0.82, green: 0.17, blue: 0.19, alpha: 1)
        case .blue: UIColor(red: 0.16, green: 0.35, blue: 0.79, alpha: 1)
        case .green: UIColor(red: 0.12, green: 0.51, blue: 0.28, alpha: 1)
        case .yellow: UIColor(red: 0.94, green: 0.69, blue: 0.08, alpha: 1)
        case .violet: UIColor(red: 0.52, green: 0.25, blue: 0.71, alpha: 1)
        }
    }

    var label: LocalizedStringKey {
        switch self {
        case .black: "Black ink"
        case .red: "Red ink"
        case .blue: "Blue ink"
        case .green: "Green ink"
        case .yellow: "Yellow ink"
        case .violet: "Violet ink"
        }
    }
}

enum CustomInkColor {
    static func decode(_ hex: String) -> UIColor? {
        guard hex.utf8.count == 6, let rgb = UInt32(hex, radix: 16) else { return nil }
        return UIColor(red: CGFloat((rgb >> 16) & 255) / 255,
                       green: CGFloat((rgb >> 8) & 255) / 255,
                       blue: CGFloat(rgb & 255) / 255, alpha: 1)
    }

    static func encode(_ color: UIColor) -> String? {
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let rgb = color.cgColor.converted(to: space, intent: .relativeColorimetric, options: nil),
              let components = rgb.components, components.count == 4 else { return nil }
        let bytes = components.prefix(3).map { Int((min(max($0, 0), 1) * 255).rounded()) }
        return String(format: "%02X%02X%02X", bytes[0], bytes[1], bytes[2])
    }
}
