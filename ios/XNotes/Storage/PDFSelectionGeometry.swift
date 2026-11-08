import PDFKit

/// Maps PDFKit's bottom-up crop coordinates into the same top-down space as page rendering.
enum PDFSelectionGeometry {
    static func rectangles(_ selection: PDFSelection, on page: PDFPage, displaySize: CGSize) -> [CGRect] {
        guard displaySize.width > 0, displaySize.height > 0,
              displaySize.width.isFinite, displaySize.height.isFinite,
              let reference = page.pageRef else { return [] }
        let display = CGRect(origin: .zero, size: displaySize)
        let transform = reference.getDrawingTransform(.cropBox, rect: display, rotate: 0, preserveAspectRatio: true)
        return selection.selectionsByLine().compactMap { line in
            guard line.pages.contains(where: { $0 === page }) else { return nil }
            let bounds = line.bounds(for: page).applying(transform)
            let rect = CGRect(x: bounds.minX, y: display.height - bounds.maxY, width: bounds.width, height: bounds.height).intersection(display)
            guard !rect.isEmpty, !rect.isInfinite,
                  [rect.minX, rect.minY, rect.width, rect.height].allSatisfy(\.isFinite) else { return nil }
            return rect
        }
    }
}
