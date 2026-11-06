import CoreGraphics
import Foundation

enum IsometricPaperDrawing {
    static func rowSpacing(side: CGFloat) -> CGFloat { side * sqrt(3) / 2 }

    static func lines(in context: CGContext, size: CGSize, side: CGFloat, preview: Bool) {
        let period = rowSpacing(side: side)
        let reach = hypot(size.width, size.height) * 2 + period
        context.clip(to: CGRect(origin: .zero, size: size))
        context.setLineWidth(preview ? 3 : CGFloat(0.18 * NotebookPaperSize.pointsPerMillimeter))

        for degrees in [CGFloat(30), 90, 150] {
            let angle = degrees * .pi / 180
            let nx = sin(angle)
            let ny = cos(angle)
            let dx = cos(angle)
            let dy = -sin(angle)
            let projections = [0, size.width * nx, size.height * ny, size.width * nx + size.height * ny]
            let start = Int(ceil(projections.min()! / period))
            let end = Int(floor(projections.max()! / period))
            guard start <= end else { continue }
            for index in start...end {
                let distance = CGFloat(index) * period
                let x = nx * distance
                let y = ny * distance
                context.move(to: CGPoint(x: x - dx * reach, y: y - dy * reach))
                context.addLine(to: CGPoint(x: x + dx * reach, y: y + dy * reach))
            }
        }
        context.strokePath()
    }

    static func dots(in context: CGContext, size: CGSize, side: CGFloat, preview: Bool) {
        let vertical = rowSpacing(side: side)
        let radius = preview ? 2.5 : CGFloat(0.35 * NotebookPaperSize.pointsPerMillimeter)
        let rows = Int(floor(size.height / vertical))
        context.clip(to: CGRect(origin: .zero, size: size))
        for row in 0...rows {
            let offset = row.isMultiple(of: 2) ? 0 : side / 2
            let y = CGFloat(row) * vertical
            for x in stride(from: offset, through: size.width, by: side) {
                context.addEllipse(in: CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2))
            }
        }
        context.fillPath()
    }
}
