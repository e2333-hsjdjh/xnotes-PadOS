import SwiftUI

struct FolderPicker: View {
    let folders: [LibraryFolder]
    let select: (UUID?) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Button { choose(nil) } label: {
                    Label("Library", systemImage: "books.vertical")
                        .frame(minHeight: 48)
                }
                OutlineGroup(FolderBranch.tree(from: folders), children: \.children) { branch in
                    Button { choose(branch.id) } label: {
                        Label(branch.folder.name, systemImage: "folder")
                            .frame(minHeight: 48)
                    }
                }
            }
            .navigationTitle("Folders")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func choose(_ folder: UUID?) {
        select(folder)
        dismiss()
    }
}
