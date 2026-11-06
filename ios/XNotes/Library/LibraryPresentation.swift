import Foundation

enum LibraryLayout: String {
    case list, grid
}

enum LibrarySort: String {
    case name, modified, created

    func precedes(_ lhs: Notebook, _ rhs: Notebook, descending: Bool) -> Bool {
        let nameOrder = lhs.title.localizedStandardCompare(rhs.title)
        let comparison: ComparisonResult
        switch self {
        case .name:
            comparison = nameOrder
        case .modified:
            comparison = lhs.modified.compare(rhs.modified)
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
