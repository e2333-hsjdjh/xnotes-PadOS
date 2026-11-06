import CoreGraphics
import Foundation

struct PDFTextMarkup: Codable, Equatable, Identifiable {
    enum Kind: String, Codable, CaseIterable {
        case highlight, underline, strikeout
    }

    var id = UUID()
    let kind: Kind
    let query: String
    let regions: [CGRect]

    init(kind: Kind, query: String, rectangles: [CGRect], paper: CGSize) {
        self.kind = kind
        self.query = query
        regions = rectangles.map {
            CGRect(x: $0.minX / paper.width, y: $0.minY / paper.height,
                   width: $0.width / paper.width, height: $0.height / paper.height)
        }
    }

    var isValid: Bool {
        !query.isEmpty && !regions.isEmpty && regions.count <= 1000 && regions.allSatisfy {
            !$0.isEmpty && !$0.isInfinite && $0.minX >= 0 && $0.minY >= 0 &&
                $0.maxX <= 1.000001 && $0.maxY <= 1.000001
        }
    }
}
