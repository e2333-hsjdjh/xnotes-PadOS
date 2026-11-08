import Foundation

/// Show a bounded provider explanation, never an HTML response or an echoed API key.
enum AIProviderFailure {
    static let byteLimit = 64 * 1024
    static func detail(_ data: Data, key: String) -> String {
        guard data.count <= byteLimit,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return "" }
        let error = object["error"]
        guard var raw = (error as? [String: Any])?["message"] as? String ?? error as? String ?? object["message"] as? String else { return "" }
        if !key.isEmpty { raw = raw.replacingOccurrences(of: key, with: "***") }
        let cleanKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanKey.isEmpty { raw = raw.replacingOccurrences(of: cleanKey, with: "***") }
        let clean = raw.components(separatedBy: .whitespacesAndNewlines.union(.controlCharacters)).filter { !$0.isEmpty }.joined(separator: " ")
        return clean.count <= 300 ? clean : String(clean.prefix(299)) + "…"
    }
}
