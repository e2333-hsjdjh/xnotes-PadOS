import PDFKit
import PencilKit
import UIKit

enum NotebookPageDrawing {
    static let canvasSize = CGSize(width: 2048, height: 2048)
    private static let textInset: CGFloat = 24
    private static let textOptions: NSStringDrawingOptions = [.usesLineFragmentOrigin, .usesFontLeading]

    static func canvasCrop(_ page: NotePage) throws -> CGRect {
        let paper = CGRect(origin: .zero, size: canvasSize)
        var content = CGRect.null
        for image in page.images ?? [] {
            content = content.union(image.frame.intersection(paper))
        }
        if !page.text.isEmpty {
            let frame = paper.insetBy(dx: textInset, dy: textInset)
            let measured = (page.text as NSString).boundingRect(
                with: frame.size, options: textOptions, attributes: textAttributes(dark: false), context: nil
            )
            if !measured.isEmpty {
                let placed = measured.offsetBy(dx: frame.minX, dy: frame.minY)
                content = content.union(placed.intersection(paper))
            }
        }
        for layer in page.layers where layer.visible && !layer.ink.isEmpty {
            let drawing = try PKDrawing(data: layer.ink)
            if !drawing.strokes.isEmpty {
                content = content.union(drawing.bounds.intersection(paper))
            }
        }
        guard !content.isEmpty else { return paper }
        return content.insetBy(dx: -16, dy: -16).integral.intersection(paper)
    }

    static func draw(_ page: NotePage, original: PDFPage?, in bounds: CGRect,
                     with graphics: CGContext, inkScale: CGFloat, crop: CGRect? = nil, dark: Bool = false) throws {
        graphics.saveGState()
        defer { graphics.restoreGState() }
        let visible = crop ?? bounds
        graphics.clip(to: CGRect(origin: .zero, size: visible.size))
        graphics.translateBy(x: -visible.minX, y: -visible.minY)
        graphics.setFillColor((dark ? UIColor.black : .white).cgColor)
        graphics.fill(visible)
        if let original {
            try draw(original, in: bounds, with: graphics)
            PDFTextMarkupDrawing.draw(page.pdfTextMarkups ?? [], in: graphics, size: bounds.size,
                                      rotation: original.rotation)
        } else if let artwork = page.coverArtwork {
            artwork.draw(in: graphics, size: bounds.size)
        } else if let pattern = page.paperPattern {
            pattern.draw(in: graphics, size: bounds.size, dark: dark)
        }
        try drawContents(page, in: bounds, visible: visible, inkScale: inkScale, dark: page.coverArtwork?.usesLightInk ?? dark)
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

    private static func textAttributes(dark: Bool) -> [NSAttributedString.Key: Any] {
        [.font: UIFont.preferredFont(forTextStyle: .body), .foregroundColor: dark ? UIColor.white : .black]
    }

    private static func drawContents(_ page: NotePage, in bounds: CGRect, visible: CGRect,
                                     inkScale: CGFloat, dark: Bool) throws {
        for image in page.images ?? [] where image.frame.intersects(visible) {
            guard let bitmap = UIImage(data: image.data) else { throw CocoaError(.fileReadCorruptFile) }
            bitmap.draw(in: image.frame)
        }

        let textBounds = bounds.insetBy(dx: textInset, dy: textInset)
        if !page.text.isEmpty, textBounds.width > 0, textBounds.height > 0 {
            (page.text as NSString).draw(with: textBounds,
                options: textOptions,
                attributes: textAttributes(dark: dark),
                context: nil)
        }

        for layer in page.layers where layer.visible && !layer.ink.isEmpty {
            let drawing = try PKDrawing(data: layer.ink)
            let visibleBounds = drawing.bounds.intersection(visible).integral
            if !visibleBounds.isEmpty {
                UITraitCollection(userInterfaceStyle: dark ? .dark : .light).performAsCurrent {
                    drawing.image(from: visibleBounds, scale: inkScale).draw(in: visibleBounds)
                }
            }
        }
    }

    @MainActor
    static func render(_ page: NotePage, original: PDFPage?, longestEdge: CGFloat,
                       canvas: Bool = false, dark: Bool = false) throws -> UIImage {
        let paper = canvas ? canvasSize : try original.map(PDFPageGeometry.displaySize) ?? (page.paperSize ?? .letter).dimensions
        let crop = canvas ? try canvasCrop(page) : nil
        let size = crop?.size ?? paper
        let scale = longestEdge / max(size.width, size.height)
        let output = CGSize(width: max(1, size.width * scale), height: max(1, size.height * scale))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        var renderError: Error?
        let image = UIGraphicsImageRenderer(size: output, format: format).image { context in
            let graphics = context.cgContext
            graphics.scaleBy(x: output.width / size.width, y: output.height / size.height)
            do {
                try NotebookPageDrawing.draw(page, original: original, in: CGRect(origin: .zero, size: paper),
                                             with: graphics, inkScale: scale, crop: crop, dark: dark)
            } catch {
                renderError = error
            }
        }
        if let renderError { throw renderError }
        return image
    }
}
