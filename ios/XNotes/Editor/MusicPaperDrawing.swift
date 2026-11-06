import CoreGraphics
import CoreText
import Foundation

enum MusicPaperDrawing {
    static func positions(height: CGFloat, gap: CGFloat) -> [CGFloat] {
        let inset = CGFloat(15 * NotebookPaperSize.pointsPerMillimeter)
        let usable = height - inset * 2
        let period = gap * 10
        guard period > 0, usable >= period else { return [] }
        let count = Int(usable / period)
        if count == 1 { return [inset + (usable - period) / 2] }
        let stride = (usable - period) / CGFloat(count - 1)
        return (0..<count).map { inset + CGFloat($0) * stride }
    }

    static func draw(in context: CGContext, size: CGSize, gap: CGFloat, tablature: Bool,
                     gray: CGFloat, opacity: Double) {
        let inset = CGFloat(12 * NotebookPaperSize.pointsPerMillimeter)
        guard size.width > inset * 2 else { return }
        let right = size.width - inset
        let origins = positions(height: size.height, gap: gap)
        let lineCount = tablature ? 6 : 5
        let firstLine: CGFloat = tablature ? 2 : 3

        for origin in origins {
            let top = origin + firstLine * gap
            for index in 0..<lineCount {
                let y = top + CGFloat(index) * gap
                context.move(to: CGPoint(x: inset, y: y))
                context.addLine(to: CGPoint(x: right, y: y))
            }
            if tablature {
                for x in [inset, right] {
                    context.move(to: CGPoint(x: x, y: top))
                    context.addLine(to: CGPoint(x: x, y: top + 5 * gap))
                }
            }
        }
        context.strokePath()

        if tablature {
            let font = CTFontCreateWithName("TimesNewRomanPS-BoldMT" as CFString, gap * 1.3, nil)
            let color = CGColor(gray: gray, alpha: opacity)
            let labels = zip(["T", "A", "B"], [CGFloat(3), 4.5, 6]).map { character, position in
                let text = NSAttributedString(string: character, attributes: [
                    NSAttributedString.Key(kCTFontAttributeName as String): font,
                    NSAttributedString.Key(kCTForegroundColorAttributeName as String): color
                ])
                return (CTLineCreateWithAttributedString(text), position)
            }
            for origin in origins {
                for (line, position) in labels {
                    let bounds = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)
                    context.saveGState()
                    context.translateBy(x: inset + 1.4 * gap, y: origin + position * gap)
                    context.scaleBy(x: 1, y: -1)
                    context.textMatrix = .identity
                    context.textPosition = CGPoint(x: -bounds.midX, y: -bounds.midY)
                    CTLineDraw(line, context)
                    context.restoreGState()
                }
            }
        }
    }
}
