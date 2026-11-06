import PDFKit
import PencilKit
import UIKit

enum NotebookPDFExport {
    static func data(for notebook: Notebook, source: PDFDocument?) throws -> Data {
        guard notebook.kind != .canvas,
              notebook.kind != .pdf || source?.pageCount == notebook.pages.count else {
            throw CocoaError(.fileReadCorruptFile)
        }

        let pages = try notebook.pages.enumerated().map { index, page in
            let original = source?.page(at: index)
            let size = try original.map(displaySize) ?? (page.paperSize ?? .letter).dimensions
            return (page, original, CGRect(origin: .zero, size: size))
        }
        var renderError: Error?
        let renderer = UIGraphicsPDFRenderer(bounds: .zero)
        let data = renderer.pdfData { context in
            do {
                for (page, original, bounds) in pages {
                    context.beginPage(withBounds: bounds, pageInfo: [:])
                    let graphics = context.cgContext
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
                    try drawContents(page, in: bounds)
                }
            } catch {
                renderError = error
            }
        }
        if let renderError { throw renderError }
        return data
    }

    static func thumbnailRequest(_ page: PDFPage) throws -> CGSize {
        let size = page.bounds(for: .cropBox).size
        guard size.width.isFinite, size.height.isFinite, size.width > 0, size.height > 0 else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let scale = 792 / max(size.width, size.height)
        return CGSize(width: max(1, size.width * scale), height: max(1, size.height * scale))
    }

    private static func displaySize(_ page: PDFPage) throws -> CGSize {
        let request = try thumbnailRequest(page)
        let crop = page.bounds(for: .cropBox).size
        let rotated = page.rotation % 180 != 0
        let display = rotated ? CGSize(width: crop.height, height: crop.width) : crop
        let scale = min(request.width / display.width, request.height / display.height)
        return CGSize(width: max(1, (display.width * scale).rounded(.down)),
                      height: max(1, (display.height * scale).rounded(.down)))
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

    private static func drawContents(_ page: NotePage, in bounds: CGRect) throws {
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
                    drawing.image(from: visibleBounds, scale: 2).draw(in: visibleBounds)
                }
            }
        }
    }
}
