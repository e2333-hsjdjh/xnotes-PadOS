import Foundation
import Observation

@MainActor @Observable
final class AISessions {
    typealias Transport = @Sendable (AICompletionRequest, @escaping @Sendable (AIStreamEvent) async throws -> Void) async throws -> AIStreamFinish
    private(set) var conversations: [AIConversation] = []
    private(set) var damagedCount = 0
    var issue: String?
    @ObservationIgnored private let history: AIHistoryStore
    @ObservationIgnored private let transport: Transport
    @ObservationIgnored private var jobs: [UUID: Task<Void, Never>] = [:]
    @ObservationIgnored private var generations: [UUID: UUID] = [:]
    @ObservationIgnored private var checkpoints: [UUID: Date] = [:]
    private var ready = false

    init(history: AIHistoryStore = AIHistoryStore(directory: URL.applicationSupportDirectory.appending(path: "XNotesAI/History")),
         transport: @escaping Transport = { try await AIChatClient.stream($0, onEvent: $1) }) {
        self.history = history
        self.transport = transport
        do {
            let loaded = try history.readAll()
            damagedCount = loaded.damaged
            conversations = loaded.conversations
            ready = true
            for index in conversations.indices {
                var recovered = conversations[index]
                var changed = false
                for turn in recovered.turns.indices where recovered.turns[turn].status == .generating {
                    recovered.turns[turn].status = .interrupted
                    changed = true
                }
                if changed {
                    conversations[index] = recovered
                    do { try history.save(recovered) } catch { issue = AIChatError.saveFailed.localizedDescription }
                }
            }
        } catch { issue = AIChatError.historyUnavailable.localizedDescription }
    }

    func conversation(_ id: UUID?) -> AIConversation? { conversations.first { $0.id == id } }

    func newConversation() -> UUID {
        let conversation = AIConversation()
        conversations.append(conversation)
        return conversation.id
    }

    func releaseEmpty(_ id: UUID?) {
        guard let id, let conversation = conversation(id), conversation.turns.isEmpty, !conversation.isRunning else { return }
        conversations.removeAll { $0.id == id }
    }

    func updateDraft(_ id: UUID, text: String) {
        guard var conversation = conversation(id), conversation.trashedAt == nil else { return }
        conversation.draft = text
        publish(conversation)
        if !conversation.turns.isEmpty {
            do { try history.save(conversation) } catch { issue = AIChatError.saveFailed.localizedDescription }
        }
    }

    func setModel(_ id: UUID, choice: AIModelChoice?) throws {
        guard var conversation = conversation(id), !conversation.isRunning, conversation.trashedAt == nil else { return }
        conversation.model = choice
        try saveEdit(conversation)
    }

    func setEffort(_ id: UUID, effort: AIThinkingEffort?) throws {
        guard var conversation = conversation(id), !conversation.isRunning, conversation.trashedAt == nil else { return }
        conversation.effort = effort
        try saveEdit(conversation)
    }

    func rename(_ id: UUID, title: String) throws {
        guard var conversation = conversation(id), !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        conversation.title = title.trimmingCharacters(in: .whitespacesAndNewlines)
        try saveEdit(conversation)
    }

    func send(_ id: UUID, configuration: AIConfiguration) throws {
        guard ready, var conversation = conversation(id), conversation.trashedAt == nil, !conversation.isRunning else {
            throw AIChatError.historyUnavailable
        }
        let question = conversation.draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !question.isEmpty else { throw AIChatError.emptyQuestion }
        guard let choice = conversation.model ?? configuration.defaultModel,
              let provider = configuration.provider(for: choice) else { throw AIChatError.chooseModel }
        let effort = conversation.effort ?? configuration.effort
        let context = conversation.turns.filter { $0.status == .completed }.flatMap {
            [["role": "user", "content": $0.question], ["role": "assistant", "content": $0.answer]]
        } + [["role": "user", "content": question]]
        let request = AICompletionRequest(provider: provider, model: choice.model, effort: effort,
                                          maxTokens: configuration.maxTokens, instructions: configuration.instructions, messages: context)
        _ = try request.urlRequest()
        let turn = AITurn(question: question, model: choice, effort: effort)
        conversation.model = choice
        if conversation.title.isEmpty { conversation.title = String(question.prefix(40)) }
        conversation.turns.append(turn)
        conversation.updated = .now
        // Save the question before clearing the submitted draft or contacting a provider.
        try history.save(conversation)
        conversation.draft = ""
        publish(conversation)
        do { try history.save(conversation) } catch { issue = AIChatError.saveFailed.localizedDescription }
        let generation = UUID()
        generations[id] = generation
        checkpoints[id] = .now
        jobs[id] = Task { @MainActor [self] in
            do {
                try Task.checkCancellation()
                let finish = try await transport(request) { [self] event in
                    try await self.receive(event, id: id, generation: generation)
                }
                try Task.checkCancellation()
                let status: AITurnStatus
                switch finish {
                case .completed: status = .completed
                case .limited: status = .limited
                case .filtered: status = .filtered
                }
                if self.conversation(id)?.turns.last?.answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true && status == .completed {
                    throw AIChatError.emptyAnswer
                }
                finishTurn(id, generation: generation, status: status)
            } catch is CancellationError {
                finishTurn(id, generation: generation, status: .stopped)
            } catch {
                finishTurn(id, generation: generation, status: .failed, error: error.localizedDescription)
            }
            if generations[id] == generation {
                jobs[id] = nil
                generations[id] = nil
                checkpoints[id] = nil
            }
        }
    }

    func stop(_ id: UUID) {
        guard let generation = generations[id] else { return }
        jobs[id]?.cancel()
        finishTurn(id, generation: generation, status: .stopped)
    }

    func trash(_ id: UUID, retentionDays: Int, now: Date = .now) throws {
        guard var conversation = conversation(id) else { return }
        if retentionDays == 0 {
            try history.delete(id)
            conversations.removeAll { $0.id == id }
        } else {
            conversation.trashedAt = now
            if conversation.turns.last?.status == .generating { conversation.turns[conversation.turns.count - 1].status = .stopped }
            try history.save(conversation)
            publish(conversation)
        }
        generations[id] = nil
        jobs[id]?.cancel()
        jobs[id] = nil
        checkpoints[id] = nil
    }

    func restore(_ id: UUID) throws {
        guard var conversation = conversation(id), conversation.trashedAt != nil else { return }
        conversation.trashedAt = nil
        try saveEdit(conversation)
    }

    func deletePermanently(_ id: UUID) throws {
        guard conversation(id)?.trashedAt != nil else { return }
        try history.delete(id)
        conversations.removeAll { $0.id == id }
    }

    func purgeExpiredTrash(days: Int, now: Date = .now) {
        guard (1...365).contains(days) else { return }
        let expired = conversations.filter { $0.trashedAt.map { $0 <= now.addingTimeInterval(-Double(days) * 86400) } == true }
        for conversation in expired {
            do { try deletePermanently(conversation.id) } catch { issue = AIChatError.saveFailed.localizedDescription }
        }
    }

    private func receive(_ event: AIStreamEvent, id: UUID, generation: UUID) throws {
        guard generations[id] == generation, var conversation = conversation(id), conversation.isRunning else { throw CancellationError() }
        let index = conversation.turns.count - 1
        switch event {
        case .text(let text):
            conversation.turns[index].answer += text
            conversation.turns[index].phase = .answering
        case .thinking:
            if conversation.turns[index].answer.isEmpty { conversation.turns[index].phase = .thinking }
        }
        publish(conversation)
        if Date().timeIntervalSince(checkpoints[id] ?? .distantPast) >= 0.5 {
            do { try history.save(conversation); checkpoints[id] = .now }
            catch { issue = AIChatError.saveFailed.localizedDescription; throw AIChatError.saveFailed }
        }
    }

    private func finishTurn(_ id: UUID, generation: UUID, status: AITurnStatus, error: String? = nil) {
        guard generations[id] == generation, var conversation = conversation(id), conversation.isRunning else { return }
        let index = conversation.turns.count - 1
        conversation.turns[index].status = status
        conversation.turns[index].error = error
        conversation.updated = .now
        publish(conversation)
        do { try history.save(conversation) } catch { issue = AIChatError.saveFailed.localizedDescription }
    }

    private func saveEdit(_ conversation: AIConversation) throws {
        if !conversation.turns.isEmpty { try history.save(conversation) }
        publish(conversation)
    }

    private func publish(_ conversation: AIConversation) {
        if let index = conversations.firstIndex(where: { $0.id == conversation.id }) { conversations[index] = conversation }
    }
}
