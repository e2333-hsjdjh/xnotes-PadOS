import PDFKit

enum PDFPageGeometry {
    static func thumbnailRequest(_ page: PDFPage) throws -> CGSize {
        let size = page.bounds(for: .cropBox).size
        guard size.width.isFinite, size.height.isFinite, size.width > 0, size.height > 0 else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let scale = 792 / max(size.width, size.height)
        return CGSize(width: max(1, size.width * scale), height: max(1, size.height * scale))
    }

    static func displaySize(_ page: PDFPage) throws -> CGSize {
        let request = try thumbnailRequest(page)
        let crop = page.bounds(for: .cropBox).size
        let rotated = page.rotation % 180 != 0
        let display = rotated ? CGSize(width: crop.height, height: crop.width) : crop
        let scale = min(request.width / display.width, request.height / display.height)
        return CGSize(width: max(1, (display.width * scale).rounded(.down)),
                      height: max(1, (display.height * scale).rounded(.down)))
    }
}
