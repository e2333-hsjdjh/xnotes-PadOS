import Foundation

enum AITurnStatus: String, Codable {
    case generating, completed, stopped, failed, interrupted, limited, filtered
}

enum AIStreamPhase: String, Codable {
    case waiting, thinking, answering
}

struct AITurn: Codable, Identifiable, Equatable {
    var id = UUID()
    let question: String
    var images: [AIImageAttachment]?
    var answer = ""
    var status = AITurnStatus.generating
    var phase = AIStreamPhase.waiting
    var error: String?
    let model: AIModelChoice
    let effort: AIThinkingEffort
}

struct AIConversation: Codable, Identifiable, Equatable {
    var version = 1
    var id = UUID()
    var title = ""
    var updated = Date()
    var turns: [AITurn] = []
    var draft = ""
    var draftImages: [AIImageAttachment]?
    var sourceDocumentID: UUID?
    var relatedDocumentIDs: [UUID]?
    var model: AIModelChoice?
    var effort: AIThinkingEffort?
    var trashedAt: Date?

    func appearsInHistory(query: String, documentID: UUID?, trash: Bool) -> Bool {
        guard !turns.isEmpty, (trashedAt != nil) == trash else { return false }
        if let documentID, sourceDocumentID != documentID && !(relatedDocumentIDs ?? []).contains(documentID) { return false }
        return query.isEmpty || title.localizedStandardContains(query) || turns.contains {
            $0.question.localizedStandardContains(query) || $0.answer.localizedStandardContains(query)
        }
    }

    var isRunning: Bool { turns.last?.status == .generating && trashedAt == nil }

    func validate(decodingImages: Bool = false) throws {
        guard version == 1, Set(turns.map(\.id)).count == turns.count,
              turns.dropLast().allSatisfy({ $0.status != .generating }),
              turns.allSatisfy({ (!$0.question.isEmpty || $0.images?.isEmpty == false) && !$0.model.providerID.isEmpty && !$0.model.model.isEmpty }) else {
            throw AIChatError.corruptHistory
        }
        for images in [draftImages ?? []] + turns.map({ $0.images ?? [] }) {
            guard images.count <= 6, Set(images.map(\.id)).count == images.count else { throw AIImageError.limit }
            for image in images { try image.validate(decoding: decodingImages) }
        }
    }
}

enum AIChatError: LocalizedError {
    case chooseModel, emptyQuestion, contextLimit, invalidStream, incompleteStream, emptyAnswer
    case responseLimit, historyLimit, toolsUnavailable, saveFailed, corruptHistory, historyUnavailable, connection, http(Int), httpDetail(Int, String)
    var errorDescription: String? {
        switch self {
        case .chooseModel: String(localized: "Choose an available provider and model before sending.")
        case .emptyQuestion: String(localized: "Enter a question before sending.")
        case .contextLimit: String(localized: "This conversation has reached 40 context messages. Start a new conversation.")
        case .invalidStream: String(localized: "The provider returned an invalid response stream.")
        case .incompleteStream: String(localized: "The response ended before completion. The partial answer was kept.")
        case .emptyAnswer: String(localized: "The provider did not return answer text.")
        case .historyLimit: String(localized: "The conversation exceeds the 64 MiB storage limit. Existing files were kept.")
        case .responseLimit: String(localized: "The response exceeded its size limit. The partial answer was kept.")
        case .toolsUnavailable: String(localized: "The provider requested a tool that was not offered.")
        case .saveFailed: String(localized: "The conversation could not be saved. Existing files were kept; the latest text may only be in memory.")
        case .corruptHistory: String(localized: "A conversation could not be read. Its file was kept.")
        case .historyUnavailable: String(localized: "Conversation storage is unavailable. No request was sent.")
        case .connection: String(localized: "The AI request failed. Check the provider and connection.")
        case .httpDetail(let code, let detail): String(format: String(localized: "AI request failed (HTTP %d): %@"), code, detail)
        case .http(let code): String(format: String(localized: "AI request failed (HTTP %d)."), code)
        }
    }
}
