import SwiftUI

extension PDFTextMarkup.Kind {
    var label: LocalizedStringKey {
        switch self {
        case .highlight: "Highlight text"
        case .underline: "Underline text"
        case .strikeout: "Strike through text"
        }
    }
}

enum PDFTextMarkupDrawing {
    static func draw(_ marks: [PDFTextMarkup], in context: CGContext, size: CGSize, rotation: Int) {
        context.saveGState()
        defer { context.restoreGState() }
        context.setLineWidth(1.3)
        let angle = (rotation % 360 + 360) % 360
        for mark in marks {
            for region in mark.regions {
                let rect = CGRect(x: region.minX * size.width, y: region.minY * size.height,
                                  width: region.width * size.width, height: region.height * size.height)
                switch mark.kind {
                case .highlight:
                    context.setFillColor(red: 1, green: 0.82, blue: 0, alpha: 0.32)
                    context.fill(rect)
                case .underline, .strikeout:
                    context.setStrokeColor(gray: 0.18, alpha: 0.9)
                    if angle == 90 || angle == 270 {
                        let baseline = angle == 90 ? rect.minX + 1 : rect.maxX - 1
                        let x = mark.kind == .underline ? baseline : rect.midX
                        context.move(to: CGPoint(x: x, y: rect.minY))
                        context.addLine(to: CGPoint(x: x, y: rect.maxY))
                    } else {
                        let baseline = angle == 180 ? rect.minY + 1 : rect.maxY - 1
                        let y = mark.kind == .underline ? baseline : rect.midY
                        context.move(to: CGPoint(x: rect.minX, y: y))
                        context.addLine(to: CGPoint(x: rect.maxX, y: y))
                    }
                    context.strokePath()
                }
            }
        }
    }
}
