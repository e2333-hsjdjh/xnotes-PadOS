import SwiftUI

struct PageNavigator: View {
    let pages: [NotePage]
    let current: UUID
    let editable: Bool
    let open: (UUID) -> Void
    let add: () -> Void
    let duplicate: (UUID) -> Void
    let delete: (UUID) -> Void
    let move: (IndexSet, Int) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var deleting: UUID?

    var body: some View {
        NavigationStack {
            List {
                if query.isEmpty {
                    ForEach(pages) { page in row(page) }
                        .onMove(perform: editable ? move : nil)
                } else {
                    let matches = pages.filter { $0.text.localizedStandardContains(query) }
                    if matches.isEmpty {
                        ContentUnavailableView.search(text: query)
                    }
                    ForEach(matches) { page in row(page) }
                }
            }
            .searchable(text: $query)
            .navigationTitle("Pages")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                if editable && query.isEmpty {
                    ToolbarItemGroup(placement: .primaryAction) {
                        EditButton()
                        Button("Add page", systemImage: "plus", action: add)
                    }
                }
            }
            .confirmationDialog("Delete this page?", isPresented: Binding(
                get: { deleting != nil },
                set: { if !$0 { deleting = nil } }
            ), titleVisibility: .visible) {
                Button("Delete page", role: .destructive) {
                    if let deleting { delete(deleting) }
                }
            }
        }
    }

    private func row(_ page: NotePage) -> some View {
        let number = (pages.firstIndex { $0.id == page.id } ?? 0) + 1
        return Button {
            open(page.id)
            dismiss()
        } label: {
            HStack {
                VStack(alignment: .leading) {
                    Text("Page \(number)")
                    if !page.text.isEmpty {
                        Text(page.text).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    }
                }
                Spacer()
                if page.id == current { Image(systemName: "checkmark") }
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .contextMenu {
            if editable {
                Button("Duplicate page", systemImage: "plus.square.on.square") { duplicate(page.id) }
                Button("Delete page", systemImage: "trash", role: .destructive) { deleting = page.id }
                    .disabled(pages.count == 1)
            }
        }
    }
}
