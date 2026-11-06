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
