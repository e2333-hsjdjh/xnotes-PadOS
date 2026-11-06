import CoreGraphics

extension PaperPattern {
    func draw(in context: CGContext, size: CGSize, dark: Bool, preview: Bool = false,
              parameters: PaperPatternParameters? = nil) {
        guard self != .blank, size.width > 0, size.height > 0 else { return }
        context.saveGState()
        defer { context.restoreGState() }

        let values = parameters.flatMap { $0.isValid(for: self) ? $0 : nil } ?? .defaults(for: self)
        let mm = NotebookPaperSize.pointsPerMillimeter
        let spacing = CGFloat(values.spacingMM * mm)
        let gray: CGFloat = dark ? 0.29 : 0.76
        context.setStrokeColor(gray: gray, alpha: values.opacity)
        context.setFillColor(gray: gray, alpha: values.opacity)
        context.setLineWidth(preview ? 4 : 0.55)

        switch self {
        case .blank:
            break
        case .ruled:
            for y in stride(from: CGFloat(values.headerMM * mm), to: size.height, by: spacing) {
                context.move(to: CGPoint(x: 0, y: y))
                context.addLine(to: CGPoint(x: size.width, y: y))
            }
            let margin = CGFloat(values.marginMM * mm)
            if margin > 0 && margin < size.width {
                context.move(to: CGPoint(x: margin, y: 0))
                context.addLine(to: CGPoint(x: margin, y: size.height))
            }
            context.strokePath()
        case .grid:
            for x in stride(from: spacing, to: size.width, by: spacing) {
                context.move(to: CGPoint(x: x, y: 0))
                context.addLine(to: CGPoint(x: x, y: size.height))
            }
            for y in stride(from: spacing, to: size.height, by: spacing) {
                context.move(to: CGPoint(x: 0, y: y))
                context.addLine(to: CGPoint(x: size.width, y: y))
            }
            context.strokePath()
        case .dots:
            let inset: CGFloat = 17
            let diameter: CGFloat = preview ? 5 : 1.7
            guard size.width >= 2 * inset, size.height >= 2 * inset else { return }
            let horizontalCount = Int((size.width - 2 * inset) / spacing)
            let verticalCount = Int((size.height - 2 * inset) / spacing)
            let startX = (size.width - CGFloat(horizontalCount) * spacing) / 2
            let startY = (size.height - CGFloat(verticalCount) * spacing) / 2
            for row in 0...verticalCount {
                for column in 0...horizontalCount {
                    context.addEllipse(in: CGRect(
                        x: startX + CGFloat(column) * spacing - diameter / 2,
                        y: startY + CGFloat(row) * spacing - diameter / 2,
                        width: diameter, height: diameter
                    ))
                }
            }
            context.fillPath()
        }
    }
}
