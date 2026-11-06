import Foundation

enum NotebookCover: String, Codable, CaseIterable, Identifiable {
    case cloth, kanagawa, earthrise

    var id: Self { self }
}
