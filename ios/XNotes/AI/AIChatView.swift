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
    @State private var error: String?

    init(sessions: AISessions, settings: AIConfigurationStore, initialConversationID: UUID? = nil) {
        self.sessions = sessions
        self.settings = settings
        _activeID = State(initialValue: initialConversationID)
    }

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
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("New conversation", systemImage: "plus") { newConversation() }
                    Menu("AI options", systemImage: "ellipsis") {
                        Button("Conversation history", systemImage: "clock") { showingHistory = true }
                        Button("AI settings", systemImage: "gearshape") { showingSettings = true }
                    }
                }
            }
            .sheet(isPresented: $showingSettings) { AISettingsView(store: settings) }
            .sheet(isPresented: $showingHistory) {
                AIHistoryView(sessions: sessions, retentionDays: retentionDays) { id in
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
                if conversation == nil { newConversation() }
                sessions.purgeExpiredTrash(days: retentionDays)
            }
            .onDisappear {
                if !showingHistory && !showingSettings { isVisible = false; sessions.releaseEmpty(activeID) }
            }
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
            HStack {
                Menu {
                    ForEach(settings.configuration.providers) { provider in
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
                .disabled(conversation.isRunning || settings.configuration.providers.isEmpty)
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
                Spacer()
                if conversation.isRunning {
                    Button("Stop", systemImage: "stop.fill") { sessions.stop(conversation.id) }
                        .frame(minHeight: UITokens.minTouchTarget)
                } else {
                    Button("Send", systemImage: "arrow.up") { perform { try sessions.send(conversation.id, configuration: settings.configuration) } }
                        .frame(minHeight: UITokens.minTouchTarget)
                        .disabled(conversation.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || settings.loadIssue != nil)
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
            Text(verbatim: turn.question).textSelection(.enabled)
            Divider()
            Text("AI").bold()
            if !turn.answer.isEmpty { Text(markdown(turn.answer)).textSelection(.enabled) }
            if turn.status == .generating {
                HStack { ProgressView(); Text(turn.phase.label) }
            } else if turn.status != .completed { Text(turn.status.label).foregroundStyle(UITokens.secondaryText) }
            if let error = turn.error { Text(error).foregroundStyle(UITokens.secondaryText) }
            if !turn.answer.isEmpty {
                Button("Copy answer", systemImage: "doc.on.doc") { UIPasteboard.general.string = turn.answer }
                    .frame(minHeight: UITokens.minTouchTarget)
            }
        }
        .padding(.vertical)
    }

    private func markdown(_ source: String) -> AttributedString {
        (try? AttributedString(markdown: source, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(source)
    }

    private func newConversation() {
        sessions.releaseEmpty(activeID)
        activeID = sessions.newConversation()
    }

    private func perform(_ action: () throws -> Void) {
        do { try action() } catch { self.error = error.localizedDescription }
    }
}

extension AIStreamPhase {
    var label: LocalizedStringKey {
        switch self {
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
