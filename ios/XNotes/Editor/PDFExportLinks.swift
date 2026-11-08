import PDFKit

/// Uses the PDF graphics context directly: serializing PDFKit GoTo actions can lose page references.
enum PDFExportLinks {
    struct Page {
        let original: PDFPage?
        let bounds: CGRect
        let scope: Int
    }
    struct Plan {
        var links: [(CGRect, URL?, String?)] = []
        var destinations: [(String, CGPoint)] = []
        func draw(in context: CGContext) {
            for (name, point) in destinations { context.addDestination(name as CFString, at: point) }
            for (rect, url, name) in links {
                if let url { context.setURL(url as CFURL, for: rect) }
                else if let name { context.setDestination(name as CFString, for: rect) }
            }
        }
    }
    private struct Key: Hashable {
        let document: ObjectIdentifier
        let index: Int
        init?(_ page: PDFPage) {
            guard let document = page.document else { return nil }
            let index = document.index(for: page)
            guard index != NSNotFound else { return nil }
            self.document = ObjectIdentifier(document); self.index = index
        }
    }
    static func plans(for pages: [Page]) -> [Plan] {
        var result = Array(repeating: Plan(), count: pages.count)
        var destinations: [Int: [Key: [Int]]] = [:]
        for (index, page) in pages.enumerated() {
            if let original = page.original, let key = Key(original) { destinations[page.scope, default: [:]][key, default: []].append(index) }
        }
        for (index, page) in pages.enumerated() {
            guard let original = page.original, let reference = original.pageRef else { continue }
            let transform = reference.getDrawingTransform(.cropBox, rect: page.bounds, rotate: 0, preserveAspectRatio: true)
            for (annotationIndex, annotation) in original.annotations.enumerated() where isLink(annotation) && annotation.shouldDisplay {
                let rect = annotation.bounds.applying(transform).intersection(page.bounds)
                guard !rect.isEmpty, !rect.isInfinite else { continue }
                if let url = (annotation.action as? PDFActionURL)?.url ?? annotation.url {
                    result[index].links.append((rect, url, nil))
                } else if let destination = (annotation.action as? PDFActionGoTo)?.destination ?? annotation.destination,
                          let target = destination.page, let key = Key(target) {
                    let candidates = destinations[page.scope]?[key] ?? []
                    let targetIndex: Int
                    if Key(original) == key { targetIndex = index }
                    else if candidates.count == 1 { targetIndex = candidates[0] }
                    else { continue }
                    guard let targetReference = target.pageRef else { continue }
                    let crop = targetReference.getBoxRect(.cropBox)
                    let point = destination.point
                    let x = point.x == kPDFDestinationUnspecifiedValue || !point.x.isFinite ? crop.minX : point.x
                    let y = point.y == kPDFDestinationUnspecifiedValue || !point.y.isFinite ? crop.maxY : point.y
                    let targetTransform = targetReference.getDrawingTransform(.cropBox, rect: pages[targetIndex].bounds, rotate: 0, preserveAspectRatio: true)
                    let mapped = CGPoint(x: x, y: y).applying(targetTransform)
                    let name = "xnotes-link-\(index)-\(annotationIndex)"
                    result[targetIndex].destinations.append((name, mapped))
                    result[index].links.append((rect, nil, name))
                }
            }
        }
        return result
    }
    private static func isLink(_ annotation: PDFAnnotation) -> Bool {
        annotation.type?.trimmingCharacters(in: CharacterSet(charactersIn: "/")) == "Link"
    }
}
