import Foundation

enum PagePaperEdit {
    case size(NotebookPaperSize)
    case pattern(PaperPattern)

    func apply(to page: inout NotePage) {
        switch self {
        case .size(let size):
            page.paperSize = size == .letter ? nil : size
        case .pattern(let pattern):
            page.paperPattern = pattern == .blank ? nil : pattern
        }
    }
}

enum PageBatch {
    static func editPaper(_ edit: PagePaperEdit, for ids: Set<UUID>, in pages: [NotePage]) -> [NotePage] {
        pages.map { page in
            guard ids.contains(page.id), page.pdfBackground == nil else { return page }
            var updated = page
            edit.apply(to: &updated)
            return updated
        }
    }

    static func duplicate(_ ids: Set<UUID>, in pages: [NotePage]) -> (pages: [NotePage], firstCopy: UUID)? {
        var firstCopy: UUID?
        var result: [NotePage] = []
        for page in pages {
            result.append(page)
            guard ids.contains(page.id) else { continue }
            let copy = makeCopy(of: page)
            firstCopy = firstCopy ?? copy.id
            result.append(copy)
        }
        guard let firstCopy else { return nil }
        return (result, firstCopy)
    }

    static func makeCopy(of page: NotePage) -> NotePage {
        var copy = page
        copy.id = UUID()
        copy.bookmarks = nil
        if let images = copy.images {
            copy.images = images.map {
                var image = $0
                image.id = UUID()
                return image
            }
        }
        for index in copy.layers.indices { copy.layers[index].id = UUID() }
        if let marks = copy.pdfTextMarkups {
            copy.pdfTextMarkups = marks.map {
                var mark = $0
                mark.id = UUID()
                return mark
            }
        }
        return copy
    }

    static func remove(_ ids: Set<UUID>, from pages: [NotePage], current: UUID) -> (pages: [NotePage], current: UUID)? {
        let remaining = pages.filter { !ids.contains($0.id) }
        guard !remaining.isEmpty, remaining.count < pages.count else { return nil }
        guard let oldIndex = pages.firstIndex(where: { $0.id == current }) else {
            return (remaining, remaining[0].id)
        }
        let next = pages[oldIndex...].first { !ids.contains($0.id) } ?? remaining[remaining.count - 1]
        return (remaining, next.id)
    }
}
