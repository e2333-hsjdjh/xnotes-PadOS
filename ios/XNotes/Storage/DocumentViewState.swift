import Foundation

struct PaperZoom: Codable, Equatable {
    static let inset: CGFloat = 16
    static let manualLimits: ClosedRange<CGFloat> = 0.1...4
    var mode: Mode = .fitWidth
    var locked = false

    enum Mode: Codable, Equatable {
        case fitWidth
        case manual(CGFloat)
    }

    func scale(for paperWidth: CGFloat, availableWidth: CGFloat) -> CGFloat {
        switch mode {
        case .fitWidth:
            guard paperWidth > 0, availableWidth > 2 * Self.inset else { return 1 }
            return min(1, (availableWidth - 2 * Self.inset) / paperWidth)
        case .manual(let factor):
            return factor.isFinite ? Self.clamped(factor) : 1
        }
    }

    mutating func adjust(_ factor: CGFloat, from scale: CGFloat) {
        guard !locked, factor.isFinite, factor > 0 else { return }
        mode = .manual(Self.clamped(scale * factor))
    }

    static func clamped(_ scale: CGFloat) -> CGFloat {
        min(manualLimits.upperBound, max(manualLimits.lowerBound, scale))
    }
}

struct DocumentViewState: Codable, Equatable {
    let pageID: UUID
    let zoom: PaperZoom

    func page(in pages: [NotePage]) -> UUID? {
        pages.first { $0.id == pageID }?.id ?? pages.first?.id
    }
}

struct DocumentViewStateStore {
    let directory: URL

    enum Slot: String, CaseIterable {
        case primary, secondary
    }

    func load(_ id: UUID, slot: Slot = .primary) -> DocumentViewState? {
        if let state = read(file(for: id, slot: slot)) { return state }
        return slot == .secondary ? read(file(for: id, slot: .primary)) : nil
    }

    private func read(_ file: URL) -> DocumentViewState? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode(DocumentViewState.self, from: data)
    }

    func save(_ state: DocumentViewState, for id: UUID, slot: Slot = .primary) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try JSONEncoder().encode(state).write(to: file(for: id, slot: slot), options: .atomic)
    }

    func remove(_ id: UUID) {
        for slot in Slot.allCases {
            try? FileManager.default.removeItem(at: file(for: id, slot: slot))
        }
    }

    private func file(for id: UUID, slot: Slot) -> URL {
        let name = slot == .primary ? id.uuidString : "\(id.uuidString)-\(slot.rawValue)"
        return directory.appending(path: name).appendingPathExtension("json")
    }
}
