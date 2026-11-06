import Foundation

struct PaperPatternParameters: Codable, Equatable {
    var spacingMM: Double
    var headerMM: Double = 68 / NotebookPaperSize.pointsPerMillimeter
    var marginMM: Double = 79 / NotebookPaperSize.pointsPerMillimeter
    var opacity: Double = 1
    var squaresPerBlock: Int?

    static func defaults(for pattern: PaperPattern) -> Self {
        let points: Double
        switch pattern {
        case .blank, .ruled: points = 23
        case .grid: points = 16
        case .dots: points = 14
        case .engineering: return Self(spacingMM: 2, squaresPerBlock: 5)
        case .musicalStaves: return Self(spacingMM: 2)
        case .guitarTab: return Self(spacingMM: 3)
        }
        return Self(spacingMM: points / NotebookPaperSize.pointsPerMillimeter)
    }

    static func spacingRange(for pattern: PaperPattern) -> ClosedRange<Double> {
        switch pattern {
        case .ruled: 5...12
        case .engineering: 1...5
        case .musicalStaves: 1.2...3
        case .guitarTab: 2...5
        default: 3...15
        }
    }

    func isValid(for pattern: PaperPattern) -> Bool {
        pattern != .blank && Self.spacingRange(for: pattern).contains(spacingMM) &&
            (0...60).contains(headerMM) && (0...60).contains(marginMM) && (0...1).contains(opacity) && (2...10).contains(squaresPerBlock ?? 5)
    }
}
