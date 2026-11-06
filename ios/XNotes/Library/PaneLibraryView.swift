import SwiftUI

struct PaneLibraryView: View {
    let documents: [Notebook]
    let open: (UUID) -> Void
    let close: () -> Void

    @State private var query = ""

    private var matches: [Notebook] {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return documents }
        return documents.filter { $0.title.localizedStandardContains(term) }
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
                Text("Choose document").font(.headline)
                Spacer()
            }
            .padding(.horizontal, 8)
            Divider()
            TextField("Search documents", text: $query)
                .textFieldStyle(.roundedBorder)
                .frame(minHeight: 48)
                .padding(.horizontal, 12)
            List {
                if matches.isEmpty {
                    if query.isEmpty {
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
    }
}
