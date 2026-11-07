import CoreGraphics
import Foundation

enum ScientificPaperDrawing {
    static func polar(in context: CGContext, size: CGSize, settings: PaperPatternParameters,
                      gray: CGFloat, preview: Bool) {
        let mm = CGFloat(NotebookPaperSize.pointsPerMillimeter)
        let bounds = CGRect(origin: .zero, size: size).insetBy(dx: 10 * mm, dy: 10 * mm)
        let spacing = CGFloat(settings.spacingMM) * mm
        let rings = Int(max(0, min(bounds.width, bounds.height)) / (2 * spacing))
        guard rings > 0 else { return }
        let center = CGPoint(x: bounds.midX, y: bounds.midY)
        let radius = CGFloat(rings) * spacing
        context.setLineWidth(preview ? 3 : 0.2 * mm)
        for ring in 1...rings {
            context.setStrokeColor(gray: gray, alpha: settings.opacity * (ring.isMultiple(of: 5) ? 1 : 0.45))
            let r = CGFloat(ring) * spacing
            context.strokeEllipse(in: CGRect(x: center.x - r, y: center.y - r, width: 2 * r, height: 2 * r))
        }
        let spokes = settings.spokes ?? 24
        for spoke in 0..<spokes {
            let angle = CGFloat(spoke) * 2 * .pi / CGFloat(spokes)
            context.setStrokeColor(gray: gray, alpha: settings.opacity * (spoke.isMultiple(of: 3) ? 1 : 0.45))
            context.move(to: center)
            context.addLine(to: CGPoint(x: center.x + radius * cos(angle), y: center.y - radius * sin(angle)))
            context.strokePath()
        }
    }

    // Android Fit.ROUND distributes complete columns over the available width.
    static func linearPositions(width: CGFloat, spacing: CGFloat) -> [CGFloat] {
        guard width > 0, spacing > 0 else { return [] }
        let count = max(1, Int((width / spacing).rounded(.toNearestOrEven)))
        return (0...count).map { CGFloat($0) * width / CGFloat(count) }
    }

    static func logarithmicPositions(height: CGFloat, decades: Int) -> [CGFloat] {
        guard height > 0, (1...6).contains(decades) else { return [] }
        let step = height / CGFloat(decades)
        return (0..<decades).flatMap { decade in
            (2...9).map { value in (CGFloat(decade + 1) - CGFloat(log10(Double(value)))) * step }
        }.sorted()
    }

    static func semilog(in context: CGContext, size: CGSize, settings: PaperPatternParameters,
                        gray: CGFloat, preview: Bool) {
        let mm = CGFloat(NotebookPaperSize.pointsPerMillimeter)
        let bounds = CGRect(origin: .zero, size: size).insetBy(dx: 12 * mm, dy: 15 * mm)
        guard bounds.width > 0, bounds.height > 0 else { return }
        context.setLineWidth(preview ? 2 : 0.18 * mm)
        context.setStrokeColor(gray: gray, alpha: settings.opacity * 0.5)
        for x in linearPositions(width: bounds.width, spacing: CGFloat(settings.spacingMM) * mm) {
            context.move(to: CGPoint(x: bounds.minX + x, y: bounds.minY))
            context.addLine(to: CGPoint(x: bounds.minX + x, y: bounds.maxY))
        }
        context.strokePath()
        let decades = settings.decades ?? 3
        context.setStrokeColor(gray: gray, alpha: settings.opacity * 0.55)
        for y in logarithmicPositions(height: bounds.height, decades: decades) {
            context.move(to: CGPoint(x: bounds.minX, y: bounds.minY + y))
            context.addLine(to: CGPoint(x: bounds.maxX, y: bounds.minY + y))
        }
        context.strokePath()
        context.setLineWidth(preview ? 4 : 0.35 * mm)
        context.setStrokeColor(gray: gray, alpha: settings.opacity)
        for index in 0...decades {
            let y = bounds.minY + CGFloat(index) * bounds.height / CGFloat(decades)
            context.move(to: CGPoint(x: bounds.minX, y: y))
            context.addLine(to: CGPoint(x: bounds.maxX, y: y))
        }
        context.strokePath()
    }
}
