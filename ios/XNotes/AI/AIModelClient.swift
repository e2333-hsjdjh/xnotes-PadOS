import Foundation

enum AIEndpoints {
    static func completion(_ raw: String) throws -> URL {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed), components.scheme == "https",
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil, components.url != nil else {
            throw AISettingsError.invalidEndpoint
        }
        let base = trimmed.replacingOccurrences(of: "/+$", with: "", options: .regularExpression)
        guard let result = URL(string: base.hasSuffix("/chat/completions") ? base : base + "/chat/completions") else {
            throw AISettingsError.invalidEndpoint
        }
        return result
    }

    static func models(_ raw: String) throws -> URL {
        let completion = try completion(raw).absoluteString
        guard let result = URL(string: String(completion.dropLast("/chat/completions".count)) + "/models") else {
            throw AISettingsError.invalidEndpoint
        }
        return result
    }

    static func validateKey(_ key: String) throws {
        guard !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !key.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
            throw AISettingsError.invalidKey
        }
    }
}

/// Redirects are rejected so an API credential is never forwarded to another route.
final class AINoRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

struct AIModelClient {
    static let byteLimit = 4 * 1024 * 1024

    static func request(endpoint: String, key: String) throws -> URLRequest {
        try AIEndpoints.validateKey(key)
        var request = URLRequest(url: try AIEndpoints.models(endpoint))
        request.httpMethod = "GET"
        request.timeoutInterval = 30
        request.setValue("Bearer " + key, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    static func models(endpoint: String, key: String, configuration: URLSessionConfiguration = .ephemeral) async throws -> [String] {
        let request = try request(endpoint: endpoint, key: key)
        configuration.urlCache = nil
        configuration.urlCredentialStorage = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        let session = URLSession(configuration: configuration, delegate: AINoRedirects(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        do {
            let (bytes, response) = try await session.bytes(for: request)
            guard let response = response as? HTTPURLResponse else { throw AISettingsError.invalidResponse }
            guard response.statusCode == 200 else { throw AISettingsError.http(response.statusCode) }
            guard response.expectedContentLength <= byteLimit else { throw AISettingsError.responseTooLarge }
            var data = Data()
            for try await byte in bytes {
                if data.count.isMultiple(of: 4096) { try Task.checkCancellation() }
                guard data.count < byteLimit else { throw AISettingsError.responseTooLarge }
                data.append(byte)
            }
            try Task.checkCancellation()
            return try decode(data)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as AISettingsError {
            throw error
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw AISettingsError.connection
        }
    }

    static func decode(_ data: Data) throws -> [String] {
        guard data.count <= byteLimit else { throw AISettingsError.responseTooLarge }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let entries = object["data"] as? [Any] else { throw AISettingsError.invalidResponse }
        let models = AIProvider.uniqueModels(entries.compactMap { entry in
            guard let entry = entry as? [String: Any], let id = entry["id"] as? String, id.utf16.count <= 200 else { return nil }
            return id
        })
        guard models.count <= 2000 else { throw AISettingsError.responseTooLarge }
        return models.sorted { $0.localizedStandardCompare($1) == .orderedAscending }
    }
}
