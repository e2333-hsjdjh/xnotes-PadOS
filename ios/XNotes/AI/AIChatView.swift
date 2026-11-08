import SwiftUI

struct AIChatView: View {
    @Bindable var sessions: AISessions
    @Bindable var settings: AIConfigurationStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage("trashRetentionDays") private var retentionDays = -1
    @State private var activeID: UUID?
    @State private var isVisible = false
    @State private var showingHistory = false
    @State private var showingSettings = false
    @State private var imagePicker: AIImagePickerRequest?
    @State private var pickerTickets: Set<AIImageImportTicket> = []
    @State private var error: String?

    init(sessions: AISessions, settings: AIConfigurationStore, initialConversationID: UUID? = nil, sourceDocumentID: UUID? = nil, selectionChanged: @escaping (UUID?) -> Void = { _ in }) {
        self.sessions = sessions
        self.settings = settings
        self.sourceDocumentID = sourceDocumentID
        self.selectionChanged = selectionChanged
        _activeID = State(initialValue: initialConversationID)
    }

    let sourceDocumentID: UUID?
    let selectionChanged: (UUID?) -> Void

    private var conversation: AIConversation? { sessions.conversation(activeID) }

    var body: some View {
        NavigationStack {
            VStack {
                if let conversation {
                    ScrollViewReader { scroll in
                        ScrollView {
                            LazyVStack(alignment: .leading) {
                                ForEach(conversation.turns) { turn in turnView(turn) }
                                Color.clear.frame(height: 1).id("answer-end")
                            }
                            .padding()
                        }
                        .onChange(of: conversation.turns.last?.answer) { _, _ in scroll.scrollTo("answer-end", anchor: .bottom) }
                    }
                    Divider()
                    composer(conversation)
                } else {
                    ContentUnavailableView("AI", systemImage: "sparkles", description: Text("Start a conversation or open history."))
                }
            }
            .background(Color(uiColor: .systemBackground))
            .navigationTitle(conversation?.title.isEmpty == false ? Text(verbatim: conversation!.title) : Text("AI"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { sessions.releaseEmpty(activeID); dismiss() } }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("New conversation", systemImage: "plus") { newConversation() }
                    Menu("AI options", systemImage: "ellipsis") {
                        Button("Conversation history", systemImage: "clock") { showingHistory = true }
                        Button("AI settings", systemImage: "gearshape") { showingSettings = true }
                    }
                }
            }
            .sheet(item: $imagePicker, onDismiss: {
                let closed = pickerTickets.filter { $0.id != imagePicker?.id }
                closed.forEach { sessions.cancelImageSelection($0) }
                pickerTickets.subtract(closed)
            }) { request in
                switch request.kind {
                case .files:
                    AIImageFilePicker { url in
                        if imagePicker?.id == request.id { imagePicker = nil }
                        if let url {
                            sessions.importImage(request.ticket) {
                                try await Task.detached { try AIImagePreparation.read(url) }.value
                            }
                        }
                    }
                case .photos:
                    AIImagePhotoPicker { provider in
                        if imagePicker?.id == request.id { imagePicker = nil }
                        if let provider { sessions.importImage(request.ticket) { try await AIPhotoTransfer.read(provider) } }
                    }
                }
            }
            .sheet(isPresented: $showingSettings) { AISettingsView(store: settings) }
            .sheet(isPresented: $showingHistory) {
                AIHistoryView(sessions: sessions, retentionDays: retentionDays, sourceDocumentID: sourceDocumentID) { id in
                    sessions.releaseEmpty(activeID)
                    activeID = id
                }
            }
            .alert("AI error", isPresented: Binding(get: { error != nil || sessions.issue != nil }, set: {
                if !$0 { error = nil; sessions.issue = nil }
            })) {
                Button("OK") { error = nil; sessions.issue = nil }
            } message: { Text(error ?? sessions.issue ?? "") }
            .onAppear {
                isVisible = true
                if conversation == nil || conversation?.trashedAt != nil { newConversation() }
                selectionChanged(activeID)
                sessions.purgeExpiredTrash(days: retentionDays)
            }
            .onDisappear { isVisible = false }
            .onChange(of: activeID) { _, id in selectionChanged(id) }
            .onChange(of: conversation?.trashedAt) { _, value in
                if isVisible && value != nil { newConversation() }
            }
            .onChange(of: conversation == nil) { _, missing in
                if isVisible && missing { newConversation() }
            }
        }
        .tint(.primary)
    }

    private func composer(_ conversation: AIConversation) -> some View {
        VStack(alignment: .leading) {
            if let images = conversation.draftImages, !images.isEmpty {
                AIImageStrip(images: images, remove: { imageID in perform { try sessions.removeImage(conversation.id, imageID: imageID) } })
            }
            if sessions.imageImportCounts[conversation.id, default: 0] > 0 {
                HStack {
                    ProgressView("Importing image…")
                    Button("Cancel image imports") { sessions.cancelImageImports(conversation.id) }
                }
            }
            if let error = sessions.imageImportErrors[conversation.id] {
                Text(error).foregroundStyle(UITokens.secondaryText)
                Button("Discard failed image import") { sessions.discardImageImportError(conversation.id) }
            }
            HStack {
                Menu {
                    ForEach(settings.configuration.chatProviders) { provider in
                        Section(provider.label) {
                            ForEach(provider.models, id: \.self) { model in
                                Button(model) { perform { try sessions.setModel(conversation.id, choice: AIModelChoice(providerID: provider.id, model: model)) } }
                            }
                        }
                    }
                } label: {
                    let choice = conversation.model ?? settings.configuration.defaultModel
                    if let choice, let provider = settings.configuration.provider(for: choice) { Text(verbatim: provider.label + " · " + choice.model) }
                    else { Text("Choose a model") }
                }
                .frame(minHeight: UITokens.minTouchTarget)
                .disabled(conversation.isRunning || settings.configuration.chatProviders.isEmpty)
                Menu {
                    Button("Follow default") { perform { try sessions.setEffort(conversation.id, effort: nil) } }
                    ForEach(AIThinkingEffort.allCases, id: \.self) { effort in
                        Button { perform { try sessions.setEffort(conversation.id, effort: effort) } } label: { Text(effort.label) }
                    }
                } label: {
                    Text((conversation.effort ?? settings.configuration.effort).label)
                }
                .accessibilityLabel(Text("Thinking depth"))
                .frame(minHeight: UITokens.minTouchTarget)
                .disabled(conversation.isRunning)
                Menu("Attach image", systemImage: "photo.badge.plus") {
                    Button("From photo library") {
                        if let ticket = sessions.beginImageSelection(conversation.id) {
                            pickerTickets.insert(ticket)
                            imagePicker = AIImagePickerRequest(ticket: ticket, kind: .photos)
                        }
                    }
                    Button("From files") {
                        if let ticket = sessions.beginImageSelection(conversation.id) {
                            pickerTickets.insert(ticket)
                            imagePicker = AIImagePickerRequest(ticket: ticket, kind: .files)
                        }
                    }
                }
                .frame(minHeight: UITokens.minTouchTarget)
                .disabled((conversation.draftImages?.count ?? 0) + sessions.imageImportCounts[conversation.id, default: 0] >= 6)
                Spacer()
                if conversation.isRunning {
                    Button("Stop", systemImage: "stop.fill") { sessions.stop(conversation.id) }
                        .frame(minHeight: UITokens.minTouchTarget)
                } else {
                    Button("Send", systemImage: "arrow.up") { perform { try sessions.send(conversation.id, configuration: settings.configuration) } }
                        .frame(minHeight: UITokens.minTouchTarget)
                        .disabled((conversation.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && conversation.draftImages?.isEmpty != false) || settings.loadIssue != nil || sessions.imageImportCounts[conversation.id, default: 0] > 0 || sessions.imageImportErrors[conversation.id] != nil)
                }
            }
            TextField("Message AI", text: Binding(get: { sessions.conversation(conversation.id)?.draft ?? "" }, set: {
                sessions.updateDraft(conversation.id, text: $0)
            }), prompt: Text("Message AI").foregroundColor(UITokens.secondaryText), axis: .vertical)
            .lineLimit(2...8)
            .frame(minHeight: UITokens.minTouchTarget)
            Text("Sending shares this conversation with the selected provider. Notes stay local unless explicitly attached.")
                .foregroundStyle(UITokens.secondaryText)
        }
        .padding()
    }

    private func turnView(_ turn: AITurn) -> some View {
        VStack(alignment: .leading) {
            Text("You").bold()
            if let images = turn.images, !images.isEmpty { AIImageStrip(images: images) }
            if !turn.question.isEmpty { Text(verbatim: turn.question).textSelection(.enabled) }
            Divider()
            Text("AI").bold()
            if !turn.answer.isEmpty { AIMarkdownView(source: turn.answer) }
            if turn.status == .generating {
                HStack { ProgressView(); Text(turn.phase.label) }
            } else if turn.status != .completed { Text(turn.status.label).foregroundStyle(UITokens.secondaryText) }
            if turn.thinkingDropped == true {
                Text("The model did not accept the selected thinking setting. This request used the provider default.")
                    .foregroundStyle(UITokens.secondaryText)
            }
            if let error = turn.error { Text(error).foregroundStyle(UITokens.secondaryText) }
            if !turn.answer.isEmpty {
                Button("Copy answer", systemImage: "doc.on.doc") { UIPasteboard.general.string = turn.answer }
                    .frame(minHeight: UITokens.minTouchTarget)
            }
        }
        .padding(.vertical)
    }

    private func newConversation() {
        sessions.releaseEmpty(activeID)
        activeID = sessions.newConversation(sourceDocumentID: sourceDocumentID)
    }

    private func perform(_ action: () throws -> Void) {
        do { try action() } catch { self.error = error.localizedDescription }
    }
}

extension AIStreamPhase {
    var label: LocalizedStringKey {
        switch self {
        case .retryingWithoutThinking: "Requesting without the rejected thinking setting…"
        case .waiting: "Waiting for provider…"
        case .thinking: "Provider is thinking…"
        case .answering: "Receiving answer…"
        }
    }
}

extension AITurnStatus {
    var label: LocalizedStringKey {
        switch self {
        case .generating: "Generating…"
        case .completed: "Completed"
        case .stopped: "Stopped"
        case .failed: "Failed"
        case .interrupted: "Interrupted after restart"
        case .limited: "Output limit reached"
        case .filtered: "Response filtered by provider"
        }
    }
}
