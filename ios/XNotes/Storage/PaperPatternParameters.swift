import Foundation

struct PaperPatternParameters: Codable, Equatable {
    var spacingMM: Double
    var headerMM: Double = 68 / NotebookPaperSize.pointsPerMillimeter
    var marginMM: Double = 79 / NotebookPaperSize.pointsPerMillimeter
    var opacity: Double = 1
    var squaresPerBlock: Int?
    var cueFraction: Double?
    var summaryMM: Double?
    var xHeight: Double?
    var slant: Double?
    var spokes: Int?
    var decades: Int?

    static func defaults(for pattern: PaperPattern) -> Self {
        let points: Double
        switch pattern {
        case .blank, .ruled: points = 23
        case .grid: points = 16
        case .dots: points = 14
        case .engineering: return Self(spacingMM: 2, squaresPerBlock: 5)
        case .musicalStaves: return Self(spacingMM: 2)
        case .guitarTab: return Self(spacingMM: 3)
        case .cornell: return Self(spacingMM: 8, cueFraction: 0.3, summaryMM: 50)
        case .calligraphy: return Self(spacingMM: 3, xHeight: 5, slant: 7)
        case .isometricGrid: return Self(spacingMM: 8)
        case .isometricDots: return Self(spacingMM: 6)
        case .polar: return Self(spacingMM: 8, spokes: 24)
        case .semilog: return Self(spacingMM: 5, decades: 3)
        }
        return Self(spacingMM: points / NotebookPaperSize.pointsPerMillimeter)
    }

    static func spacingRange(for pattern: PaperPattern) -> ClosedRange<Double> {
        switch pattern {
        case .ruled, .cornell: 5...12
        case .engineering: 1...5
        case .musicalStaves: 1.2...3
        case .guitarTab: 2...5
        case .calligraphy: 1...8
        case .isometricGrid: 4...25
        case .isometricDots: 3...20
        case .polar: 3...25
        case .semilog: 2...15
        default: 3...15
        }
    }

    func isValid(for pattern: PaperPattern) -> Bool {
        pattern != .blank && Self.spacingRange(for: pattern).contains(spacingMM) &&
            (0...60).contains(headerMM) && (0...60).contains(marginMM) && (0...1).contains(opacity) && (2...10).contains(squaresPerBlock ?? 5) &&
            (0.15...0.45).contains(cueFraction ?? 0.3) && (25...90).contains(summaryMM ?? 50) &&
            (3...8).contains(xHeight ?? 5) && (0...20).contains(slant ?? 7) &&
            (4...72).contains(spokes ?? 24) && (1...6).contains(decades ?? 3)
    }
}
