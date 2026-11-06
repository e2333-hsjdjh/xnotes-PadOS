import Foundation

enum PageBatch {
    static func duplicate(_ ids: Set<UUID>, in pages: [NotePage]) -> (pages: [NotePage], firstCopy: UUID)? {
        var firstCopy: UUID?
        var result: [NotePage] = []
        for page in pages {
            result.append(page)
            guard ids.contains(page.id) else { continue }
            var copy = page
            copy.id = UUID()
            if let images = copy.images {
                copy.images = images.map {
                    var image = $0
                    image.id = UUID()
                    return image
                }
            }
            for index in copy.layers.indices { copy.layers[index].id = UUID() }
            firstCopy = firstCopy ?? copy.id
            result.append(copy)
        }
        guard let firstCopy else { return nil }
        return (result, firstCopy)
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
