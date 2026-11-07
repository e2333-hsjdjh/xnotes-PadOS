import SwiftUI

struct AIHistoryView: View {
    @Bindable var sessions: AISessions
    let retentionDays: Int
    let select: (UUID) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var trash = false
    @State private var query = ""
    @State private var deleting: AIConversation?
    @State private var renaming: AIConversation?
    @State private var name = ""
    @State private var error: String?

    private var visible: [AIConversation] {
        sessions.conversations.filter { conversation in
            !conversation.turns.isEmpty && (conversation.trashedAt != nil) == trash &&
            (query.isEmpty || conversation.title.localizedStandardContains(query) || conversation.turns.contains {
                $0.question.localizedStandardContains(query) || $0.answer.localizedStandardContains(query)
            })
        }.sorted { $0.updated > $1.updated }
    }

    var body: some View {
        NavigationStack {
            List {
                Group {
                    if sessions.damagedCount > 0 { Text("Some conversations could not be read. Their files were kept.") }
                    if let error { Text(error) }
                    if visible.isEmpty { ContentUnavailableView(trash ? "AI trash is empty" : "No conversations", systemImage: "bubble.left.and.bubble.right") }
                    ForEach(visible) { conversation in
                        Button {
                            guard !trash else { return }
                            select(conversation.id)
                            dismiss()
                        } label: {
                            VStack(alignment: .leading) {
                                Text(conversation.title.isEmpty ? String(localized: "New conversation") : conversation.title)
                                if conversation.isRunning { Text("Generating…") }
                                else { Text(conversation.updated, style: .date).foregroundStyle(UITokens.secondaryText) }
                            }
                            .frame(minHeight: UITokens.minTouchTarget)
                            .foregroundStyle(.primary)
                        }
                        .contextMenu {
                            if trash {
                                Button("Restore") { perform { try sessions.restore(conversation.id) } }
                                Button("Delete permanently", role: .destructive) { deleting = conversation }
                            } else {
                                Button("Rename") { name = conversation.title; renaming = conversation }
                                Button(retentionDays == 0 ? "Delete permanently" : "Move to trash", role: .destructive) { deleting = conversation }
                            }
                        }
                    }
                }
                .aiSettingsRows()
            }
            .aiSettingsSurface()
            .searchable(text: $query)
            .navigationTitle(trash ? Text("AI trash") : Text("Conversation history"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(trash ? "Conversation history" : "AI trash") { trash.toggle(); query = "" }
                }
            }
            .confirmationDialog("Delete this conversation?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                                titleVisibility: .visible, presenting: deleting) { conversation in
                Button(trash || retentionDays == 0 ? "Delete permanently" : "Move to trash", role: .destructive) {
                    perform {
                        if trash { try sessions.deletePermanently(conversation.id) }
                        else { try sessions.trash(conversation.id, retentionDays: retentionDays) }
                    }
                    deleting = nil
                }
            } message: { _ in Text("Generating an answer stops only after you confirm deletion.") }
            .alert("Rename conversation", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("Title", text: $name)
                Button("Save") { if let renaming { perform { try sessions.rename(renaming.id, title: name) } } }
                Button("Cancel", role: .cancel) {}
            }
        }
    }

    private func perform(_ action: () throws -> Void) {
        do { try action() } catch { self.error = error.localizedDescription }
    }
}
