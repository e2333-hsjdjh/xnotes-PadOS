import SwiftUI

struct PaneLibraryView: View {
    let documents: [Notebook]
    let folders: [LibraryFolder]
    let open: (UUID) -> Void
    let close: () -> Void

    @State private var query = ""
    @State private var folderID: UUID?
    @State private var showingFolders = false

    private var navigation: PaneFolderNavigation {
        PaneFolderNavigation(folders: folders, selectedID: folderID)
    }

    private func navigate(_ id: UUID?) {
        folderID = id
        query = ""
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: close) {
                    Image(systemName: "xmark")
                        .frame(minWidth: 48, minHeight: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Close pane"))
                if let current = navigation.current {
                    Text(current.name).font(.headline).lineLimit(1)
                } else {
                    Text("Choose document").font(.headline).lineLimit(1)
                }
                Spacer()
                if !folders.isEmpty {
                    Button { showingFolders = true } label: {
                        Image(systemName: "folder")
                            .frame(minWidth: 48, minHeight: 48)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Folders"))
                }
            }
            .padding(.horizontal, 8)
            Divider()
            TextField("Search documents", text: $query)
                .textFieldStyle(.roundedBorder)
                .frame(minHeight: 48)
                .padding(.horizontal, 12)
            List {
                let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
                let children = term.isEmpty ? navigation.children : []
                let matches = navigation.visibleDocuments(documents, query: term)
                if term.isEmpty, let current = navigation.current {
                    Button { navigate(current.parentID) } label: {
                        Label("Parent folder", systemImage: "arrow.up")
                            .frame(minHeight: 48)
                    }
                }
                ForEach(children) { folder in
                    Button { navigate(folder.id) } label: {
                        Label(folder.name, systemImage: "folder")
                            .frame(minHeight: 48)
                    }
                }
                if matches.isEmpty && children.isEmpty {
                    if term.isEmpty {
                        ContentUnavailableView("No documents", systemImage: "tray")
                    } else {
                        ContentUnavailableView.search(text: query)
                    }
                }
                ForEach(matches) { document in
                    Button { open(document.id) } label: {
                        Label(document.title, systemImage: document.kind.symbol)
                            .frame(minHeight: 48)
                    }
                }
            }
            .listStyle(.plain)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .sheet(isPresented: $showingFolders) {
            FolderPicker(folders: folders, select: navigate)
        }
    }
}
