import SwiftUI

enum BookmarkEdit {
    case add(String)
    case rename(UUID, String)
    case remove(UUID)

    func apply(to page: inout NotePage) {
        var marks = page.bookmarks ?? []
        switch self {
        case .add(let label):
            let name = label.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty else { return }
            marks.append(PageBookmark(label: name))
        case .rename(let id, let label):
            let name = label.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, let index = marks.firstIndex(where: { $0.id == id }) else { return }
            marks[index].label = name
        case .remove(let id):
            marks.removeAll { $0.id == id }
        }
        page.bookmarks = marks.isEmpty ? nil : marks
    }
}

struct PageBookmarksView: View {
    let pages: [NotePage]
    let current: UUID
    let editable: Bool
    let edit: (BookmarkEdit, UUID) -> Bool
    let open: (UUID) -> Void

    @State private var draft: Draft?
    @State private var label = ""

    private struct Draft {
        let pageID: UUID
        let bookmarkID: UUID?
    }

    var body: some View {
        List {
            if !pages.contains(where: { $0.bookmarks?.isEmpty == false }) {
                ContentUnavailableView("No bookmarks", systemImage: "bookmark")
            }
            ForEach(Array(pages.enumerated()), id: \.element.id) { index, page in
                ForEach(page.bookmarks ?? []) { bookmark in
                    Button { open(page.id) } label: {
                        VStack(alignment: .leading) {
                            Text(bookmark.label)
                            Text("Page \(index + 1)").font(.caption).foregroundStyle(.secondary)
                        }
                        .frame(minHeight: 48)
                    }
                    .contextMenu {
                        if editable {
                            Button("Rename", systemImage: "pencil") {
                                label = bookmark.label
                                draft = Draft(pageID: page.id, bookmarkID: bookmark.id)
                            }
                            Button("Remove bookmark", systemImage: "trash", role: .destructive) {
                                _ = edit(.remove(bookmark.id), page.id)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Bookmarks")
        .toolbar {
            if editable {
                ToolbarItem(placement: .primaryAction) {
                    Button("Add bookmark", systemImage: "plus") {
                        guard let index = pages.firstIndex(where: { $0.id == current }) else { return }
                        label = String(localized: "Page \(index + 1)")
                        draft = Draft(pageID: current, bookmarkID: nil)
                    }
                }
            }
        }
        .alert(draft?.bookmarkID == nil ? "Add bookmark" : "Rename bookmark", isPresented: Binding(
            get: { draft != nil },
            set: { if !$0 { draft = nil } }
        )) {
            TextField("Name", text: $label)
            Button("Save") {
                guard let draft, editable else { return }
                let action = draft.bookmarkID.map { BookmarkEdit.rename($0, label) } ?? .add(label)
                _ = edit(action, draft.pageID)
            }
            .disabled(label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button("Cancel", role: .cancel) {}
        }
    }
}
