import PDFKit
import PencilKit
import UIKit

enum NotebookPageDrawing {
    static func draw(_ page: NotePage, original: PDFPage?, in bounds: CGRect,
                     with graphics: CGContext, inkScale: CGFloat) throws {
        graphics.saveGState()
        defer { graphics.restoreGState() }
        graphics.clip(to: bounds)
        graphics.setFillColor(UIColor.white.cgColor)
        graphics.fill(bounds)
        if let original {
            try draw(original, in: bounds, with: graphics)
        } else if let pattern = page.paperPattern {
            pattern.draw(in: graphics, size: bounds.size, dark: false)
        }
        try drawContents(page, in: bounds, inkScale: inkScale)
    }

    private static func draw(_ page: PDFPage, in bounds: CGRect, with graphics: CGContext) throws {
        guard let reference = page.pageRef else { throw CocoaError(.fileReadCorruptFile) }
        graphics.saveGState()
        defer { graphics.restoreGState() }
        graphics.translateBy(x: 0, y: bounds.height)
        graphics.scaleBy(x: 1, y: -1)
        graphics.concatenate(reference.getDrawingTransform(.cropBox, rect: bounds, rotate: 0,
                                                          preserveAspectRatio: true))
        graphics.drawPDFPage(reference)
    }

    private static func drawContents(_ page: NotePage, in bounds: CGRect, inkScale: CGFloat) throws {
        for image in page.images ?? [] {
            guard let bitmap = UIImage(data: image.data) else { throw CocoaError(.fileReadCorruptFile) }
            bitmap.draw(in: image.frame)
        }

        let textBounds = bounds.insetBy(dx: 24, dy: 24)
        if !page.text.isEmpty, textBounds.width > 0, textBounds.height > 0 {
            (page.text as NSString).draw(with: textBounds,
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                attributes: [.font: UIFont.preferredFont(forTextStyle: .body), .foregroundColor: UIColor.black],
                context: nil)
        }

        for layer in page.layers where layer.visible && !layer.ink.isEmpty {
            let drawing = try PKDrawing(data: layer.ink)
            let visibleBounds = drawing.bounds.intersection(bounds).integral
            if !visibleBounds.isEmpty {
                UITraitCollection(userInterfaceStyle: .light).performAsCurrent {
                    drawing.image(from: visibleBounds, scale: inkScale).draw(in: visibleBounds)
                }
            }
        }
    }
}
