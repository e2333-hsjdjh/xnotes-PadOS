import CoreGraphics
import Foundation

struct NotebookPaperSize: Codable, Equatable {
    let width: Double
    let height: Double

    static let letter = Self(width: 612, height: 792)
    static let pointsPerMillimeter = 72.0 / 25.4
    static let millimeterLimits = 20.0...1000.0

    init(width: Double, height: Double) {
        self.width = width
        self.height = height
    }

    init(widthMM: Double, heightMM: Double) {
        self.init(width: widthMM * Self.pointsPerMillimeter, height: heightMM * Self.pointsPerMillimeter)
    }

    static func enclosing(width: Double, height: Double) -> Self {
        let minimum = millimeterLimits.lowerBound * pointsPerMillimeter
        let maximum = millimeterLimits.upperBound * pointsPerMillimeter
        return Self(width: min(max(width, minimum), maximum),
                    height: min(max(height, minimum), maximum))
    }

    var dimensions: CGSize { CGSize(width: width, height: height) }
    var isValid: Bool {
        let limits = Self.millimeterLimits
        let minimum = limits.lowerBound * Self.pointsPerMillimeter
        let maximum = limits.upperBound * Self.pointsPerMillimeter
        return (minimum...maximum).contains(width) && (minimum...maximum).contains(height)
    }

    var landscape: Self { Self(width: height, height: width) }
}

enum PaperSizePreset: String, CaseIterable, Identifiable {
    case a4, a5, letter, legal, custom

    var id: Self { self }

    var portrait: NotebookPaperSize? {
        switch self {
        case .a4: NotebookPaperSize(widthMM: 210, heightMM: 297)
        case .a5: NotebookPaperSize(widthMM: 148, heightMM: 210)
        case .letter: .letter
        case .legal: NotebookPaperSize(width: 612, height: 1008)
        case .custom: nil
        }
    }
}
