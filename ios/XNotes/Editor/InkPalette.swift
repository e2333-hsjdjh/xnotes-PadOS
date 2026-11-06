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
