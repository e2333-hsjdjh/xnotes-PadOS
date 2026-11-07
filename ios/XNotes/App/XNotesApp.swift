import SwiftUI

@main
struct XNotesApp: App {
    @State private var library = LibraryStore()
    @State private var aiSettings = AIConfigurationStore()
    @AppStorage("appearance") private var appearance = "system"

    var body: some Scene {
        WindowGroup {
            LibraryView(library: library, aiSettings: aiSettings)
                .preferredColorScheme(appearance == "system" ? nil : appearance == "dark" ? .dark : .light)
                .tint(.primary)
        }
    }
}
