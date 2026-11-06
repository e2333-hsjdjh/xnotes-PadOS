import PDFKit
import UIKit

enum PDFMarkupExport {
    static func data(for notebook: Notebook, source: PDFDocument?) throws -> Data {
        guard notebook.kind == .pdf, let source, source.pageCount == notebook.pages.count,
              let originalBytes = source.dataRepresentation(),
              let copy = PDFDocument(data: originalBytes) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        for (index, note) in notebook.pages.enumerated() {
            guard let marks = note.pdfTextMarkups, !marks.isEmpty else { continue }
            guard let page = copy.page(at: index), let reference = page.pageRef else {
                throw CocoaError(.fileReadCorruptFile)
            }
            let size = try PDFPageGeometry.displaySize(page)
            let display = CGRect(origin: .zero, size: size)
            let inverse = reference.getDrawingTransform(.cropBox, rect: display, rotate: 0,
                                                        preserveAspectRatio: true).inverted()
            for mark in marks {
                for region in mark.regions {
                    let displayed = CGRect(x: region.minX * size.width, y: (1 - region.maxY) * size.height,
                                           width: region.width * size.width, height: region.height * size.height)
                    let bounds = displayed.applying(inverse).intersection(reference.getBoxRect(.cropBox))
                    guard !bounds.isEmpty else { continue }
                    let annotation = PDFAnnotation(bounds: bounds, forType: subtype(mark.kind), withProperties: nil)
                    annotation.color = mark.kind == .highlight ? .systemYellow : .black
                    annotation.contents = mark.query
                    annotation.quadrilateralPoints = [
                        CGPoint(x: 0, y: bounds.height), CGPoint(x: bounds.width, y: bounds.height),
                        .zero, CGPoint(x: bounds.width, y: 0)
                    ].map { NSValue(cgPoint: $0) }
                    page.addAnnotation(annotation)
                }
            }
        }
        guard let result = copy.dataRepresentation() else { throw CocoaError(.fileWriteUnknown) }
        return result
    }

    private static func subtype(_ kind: PDFTextMarkup.Kind) -> PDFAnnotationSubtype {
        switch kind {
        case .highlight: .highlight
        case .underline: .underline
        case .strikeout: .strikeOut
        }
    }
}
