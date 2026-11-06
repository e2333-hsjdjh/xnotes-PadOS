import Foundation

enum LibraryLayout: String {
    case list, grid
}

enum LibrarySort: String {
    case name, modified, created, size

    func precedes(_ lhs: Notebook, _ rhs: Notebook, descending: Bool, size: (Notebook) -> Int64 = { _ in 0 }) -> Bool {
        let nameOrder = lhs.title.localizedStandardCompare(rhs.title)
        let comparison: ComparisonResult
        switch self {
        case .name:
            comparison = nameOrder
        case .modified:
            comparison = lhs.modified.compare(rhs.modified)
        case .size:
            let left = size(lhs)
            let right = size(rhs)
            comparison = left == right ? .orderedSame : left < right ? .orderedAscending : .orderedDescending
        case .created:
            // Older records have no creation timestamp.
            comparison = (lhs.created ?? lhs.modified).compare(rhs.created ?? rhs.modified)
        }
        if comparison != .orderedSame {
            return comparison == (descending ? .orderedDescending : .orderedAscending)
        }
        if nameOrder != .orderedSame { return nameOrder == .orderedAscending }
        return lhs.id.uuidString < rhs.id.uuidString
    }
}
