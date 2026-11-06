import SwiftUI

@main
struct XNotesApp: App {
    @State private var library = LibraryStore()
    @AppStorage("appearance") private var appearance = "system"

    var body: some Scene {
        WindowGroup {
            LibraryView(library: library)
                .preferredColorScheme(appearance == "system" ? nil : appearance == "dark" ? .dark : .light)
                .tint(.primary)
        }
    }
}
