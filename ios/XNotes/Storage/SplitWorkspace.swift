import Foundation

struct SplitWorkspace: Codable, Equatable {
    let leading: UUID
    let trailing: UUID

    func canRestore(from notebooks: [Notebook]) -> Bool {
        let active = notebooks.filter { $0.trashedAt == nil }
        guard let left = active.first(where: { $0.id == leading }),
              active.contains(where: { $0.id == trailing }) else { return false }
        return leading != trailing || left.kind != .canvas
    }
}
