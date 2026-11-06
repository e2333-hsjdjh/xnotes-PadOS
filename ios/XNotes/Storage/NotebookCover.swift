import Foundation

enum NotebookCover: String, Codable, CaseIterable, Identifiable {
    case cloth, kanagawa, earthrise
    case almondBlossom = "almond-blossom"
    case bubbleNet = "bubble-net"
    case cosmicCliffs = "cosmic-cliffs"
    case floe
    case pineForest = "pine-forest"
    case saintTropez = "saint-tropez"
    case strawberryThief = "strawberry-thief"

    var id: Self { self }
}

struct NotebookCoverParameters: Codable, Equatable {
    var labelScale: Double = 1
    var labelColor: UInt32?
    var ruleColor: UInt32?
    var clothColor: UInt32?

    var isValid: Bool {
        (0...1.4).contains(labelScale) &&
        [labelColor, ruleColor, clothColor].compactMap { $0 }.allSatisfy { $0 <= 0xFFFFFF }
    }
}
