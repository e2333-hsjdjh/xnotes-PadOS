import UIKit

enum StudyPaperDrawing {
    private static let mm = CGFloat(NotebookPaperSize.pointsPerMillimeter)

    static func cornell(in context: CGContext, size: CGSize, settings: PaperPatternParameters,
                        gray: CGFloat, preview: Bool) {
        let header = min(22 * mm, size.height)
        let summary = max(header, size.height - CGFloat(settings.summaryMM ?? 50) * mm)
        let column = size.width * CGFloat(settings.cueFraction ?? 0.3)
        let gap = CGFloat(settings.spacingMM) * mm
        context.setLineWidth(preview ? 5 : 0.35 * mm)
        line(context, from: CGPoint(x: 0, y: header), to: CGPoint(x: size.width, y: header))
        line(context, from: CGPoint(x: 0, y: summary), to: CGPoint(x: size.width, y: summary))
        line(context, from: CGPoint(x: column, y: header), to: CGPoint(x: column, y: summary))
        context.strokePath()

        context.setLineWidth(preview ? 3 : 0.55)
        for y in stride(from: header + gap, to: summary, by: gap) {
            line(context, from: CGPoint(x: column, y: y), to: CGPoint(x: size.width, y: y))
        }
        for y in stride(from: summary + gap, to: size.height, by: gap) {
            line(context, from: CGPoint(x: 0, y: y), to: CGPoint(x: size.width, y: y))
        }
        context.strokePath()

        let attributes: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 3 * mm),
            .foregroundColor: UIColor(white: gray, alpha: settings.opacity)
        ]
        UIGraphicsPushContext(context)
        defer { UIGraphicsPopContext() }
        if summary > header {
            (String(localized: "Cues") as NSString).draw(at: CGPoint(x: 4 * mm, y: header + mm), withAttributes: attributes)
            (String(localized: "Notes") as NSString).draw(at: CGPoint(x: column + 4 * mm, y: header + mm), withAttributes: attributes)
        }
        if size.height > summary {
            (String(localized: "Summary") as NSString).draw(at: CGPoint(x: 4 * mm, y: summary + mm), withAttributes: attributes)
        }
    }

    static func calligraphy(in context: CGContext, size: CGSize, settings: PaperPatternParameters,
                            gray: CGFloat, preview: Bool) {
        let bounds = CGRect(origin: .zero, size: size).insetBy(dx: 12 * mm, dy: 15 * mm)
        guard bounds.width > 0, bounds.height > 0 else { return }
        let nib = CGFloat(settings.spacingMM) * mm
        let xHeight = CGFloat(settings.xHeight ?? 5)
        let slant = CGFloat(settings.slant ?? 7) * .pi / 180
        let shift = tan(slant) * bounds.height
        context.clip(to: bounds)
        context.setStrokeColor(gray: gray, alpha: settings.opacity * 0.3)
        context.setLineWidth(preview ? 2 : 0.15 * mm)
        for x in stride(from: bounds.minX - shift, through: bounds.maxX, by: 12 * mm / cos(slant)) {
            line(context, from: CGPoint(x: x, y: bounds.maxY), to: CGPoint(x: x + shift, y: bounds.minY))
        }
        context.strokePath()

        let origins = MusicPaperDrawing.spacedPositions(length: size.height, inset: 15 * mm,
                                                        period: nib * (xHeight + 10))
        for top in origins {
            context.setFillColor(gray: gray, alpha: settings.opacity * 0.12)
            context.fill(CGRect(x: bounds.minX, y: top + nib * 4, width: bounds.width, height: nib * xHeight))
            for (offset, dashed, heavy) in [(CGFloat(0), true, false), (4, false, false),
                                             (4 + xHeight, false, true), (8 + xHeight, true, false)] {
                let y = top + offset * nib
                context.setStrokeColor(gray: gray, alpha: settings.opacity * (dashed ? 0.6 : 1))
                context.setLineWidth(preview ? (heavy ? 5 : 3) : (heavy ? 0.4 * mm : 0.55))
                context.setLineDash(phase: 0, lengths: dashed ? [mm, mm] : [])
                line(context, from: CGPoint(x: bounds.minX, y: y), to: CGPoint(x: bounds.maxX, y: y))
                context.strokePath()
            }
        }
    }

    private static func line(_ context: CGContext, from: CGPoint, to: CGPoint) {
        context.move(to: from)
        context.addLine(to: to)
    }
}
