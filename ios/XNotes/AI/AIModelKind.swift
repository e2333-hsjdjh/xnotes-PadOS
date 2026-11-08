import Foundation

/// A name-based hint for the fetched list, never a restriction on manually entered model IDs.
enum AIModelKind: CaseIterable {
    case chat, image, other
    static func guess(_ model: String) -> Self {
        let name = model.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if ["gpt-image", "dall-e", "image", "imagen", "flux", "stable-diffusion", "sdxl", "sd3", "kolors", "cogview", "midjourney", "seedream"].contains(where: name.contains) { return .image }
        if ["embedding", "whisper", "tts", "transcribe", "moderation", "rerank", "audio", "realtime", "speech", "babbage", "davinci"].contains(where: name.contains) { return .other }
        return .chat
    }
}
