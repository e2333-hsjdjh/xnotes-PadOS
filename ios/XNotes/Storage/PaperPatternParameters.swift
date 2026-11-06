import Foundation

struct PaperPatternParameters: Codable, Equatable {
    var spacingMM: Double
    var headerMM: Double = 68 / NotebookPaperSize.pointsPerMillimeter
    var marginMM: Double = 79 / NotebookPaperSize.pointsPerMillimeter
    var opacity: Double = 1

    static func defaults(for pattern: PaperPattern) -> Self {
        let points: Double
        switch pattern {
        case .blank, .ruled: points = 23
        case .grid: points = 16
        case .dots: points = 14
        }
        return Self(spacingMM: points / NotebookPaperSize.pointsPerMillimeter)
    }

    static func spacingRange(for pattern: PaperPattern) -> ClosedRange<Double> {
        pattern == .ruled ? 5...12 : 3...15
    }

    func isValid(for pattern: PaperPattern) -> Bool {
        pattern != .blank && Self.spacingRange(for: pattern).contains(spacingMM) &&
            (0...60).contains(headerMM) && (0...60).contains(marginMM) && (0...1).contains(opacity)
    }
}
