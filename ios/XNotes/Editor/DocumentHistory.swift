import Foundation
import Observation

@Observable
final class DocumentHistory {
    private struct Entry {
        let before: [NotePage]
        var after: [NotePage]
        let gesture: UUID?
    }

    private var past: [Entry] = []
    private var future: [Entry] = []
    private let limit = 50

    var canUndo: Bool { !past.isEmpty }
    var canRedo: Bool { !future.isEmpty }

    func record(before: [NotePage], after: [NotePage], gesture: UUID? = nil) {
        guard before != after else { return }
        if let gesture, let last = past.last, last.gesture == gesture, last.after == before {
            past[past.count - 1].after = after
        } else {
            past.append(Entry(before: before, after: after, gesture: gesture))
            if past.count > limit { past.removeFirst() }
        }
        future.removeAll()
    }

    func undo(current: [NotePage], persist: ([NotePage]) -> Bool) {
        guard let entry = past.last else { return }
        guard current == entry.after else { clear(); return }
        guard persist(entry.before) else { return }
        past.removeLast()
        future.append(entry)
    }

    func redo(current: [NotePage], persist: ([NotePage]) -> Bool) {
        guard let entry = future.last else { return }
        guard current == entry.before else { clear(); return }
        guard persist(entry.after) else { return }
        future.removeLast()
        past.append(entry)
    }

    func clear() {
        past.removeAll()
        future.removeAll()
    }
}
