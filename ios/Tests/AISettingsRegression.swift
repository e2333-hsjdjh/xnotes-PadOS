import Foundation
import Security
import UIKit
import SwiftUI

private struct FixedKey: AIEncryptionKeyStore {
    var value = Data(repeating: 7, count: 32)
    func key(createIfMissing: Bool) throws -> Data { value }
}

private final class ModelStub: URLProtocol, @unchecked Sendable {
    static let lock = NSLock()
    static var status = 200
    static var body = Data()
    static var headers: [String: String] = [:]
    static var requests: [URLRequest] = []
    static var delayed = false
    private let stopLock = NSLock()
    private var stopped = false

    static func configure(status: Int = 200, body: String = "{\"data\":[{\"id\":\"test-model\"}]}",
                          headers: [String: String] = [:], delayed: Bool = false) {
        lock.lock(); defer { lock.unlock() }
        self.status = status; self.body = Data(body.utf8); self.headers = headers; self.delayed = delayed
        requests = []
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock()
        Self.requests.append(request)
        let status = Self.status, body = Self.body, headers = Self.headers, delayed = Self.delayed
        Self.lock.unlock()
        let respond: @Sendable () -> Void = { [self] in
            stopLock.lock(); defer { stopLock.unlock() }
            guard !stopped else { return }
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        }
        if delayed { DispatchQueue.global().asyncAfter(deadline: .now() + 2, execute: respond) }
        else { respond() }
    }
    override func stopLoading() { stopLock.lock(); stopped = true; stopLock.unlock() }
}

struct AISettingsRegression {
    @MainActor static func run() async throws {
        let fm = FileManager.default
        let directory = URL(fileURLWithPath: CommandLine.arguments[1]).appending(path: "ai-settings-\(UUID())")
        defer { try? fm.removeItem(at: directory) }
        let first = AIProvider(name: "First", endpoint: "https://provider.example/v1", key: "fake-secret-a", models: ["a"])
        let second = AIProvider(name: "Second", endpoint: "https://other.example/v1/chat/completions", key: "fake-secret-b", models: ["b"])
        var config = AIConfiguration(providers: [first, second])
        config.reconcileDefault()
        try config.validate()
        let oldChoice = config.defaultModel!
        config.providers[0].name = "Renamed"
        config.providers[0].endpoint = "https://renamed.example/v1"
        precondition(config.provider(for: oldChoice)?.id == first.id)
        config.providers.removeFirst()
        precondition(config.provider(for: oldChoice) == nil)
        config.reconcileDefault()
        precondition(config.defaultModel?.providerID == second.id)
        let store = AIConfigurationStore(directory: directory, keys: FixedKey())
        try store.save(config)
        let file = directory.appending(path: "Providers.encrypted")
        let encrypted = try Data(contentsOf: file)
        precondition(encrypted.range(of: Data(second.key.utf8)) == nil)
        precondition(encrypted.range(of: Data(second.endpoint.utf8)) == nil)
        let excluded = try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup
        precondition(excluded == true)
        let restored = AIConfigurationStore(directory: directory, keys: FixedKey())
        precondition(restored.loadIssue == nil && restored.configuration == config)
        let failed = AIConfigurationStore(directory: directory, keys: FixedKey(), persist: { _, _ in throw CocoaError(.fileWriteOutOfSpace) })
        var edited = config
        edited.providers[0].key = "replacement-fake-secret"
        do { try failed.save(edited); fatalError("Saved despite disk failure") } catch {}
        precondition(failed.configuration == config)
        let afterFailure = try Data(contentsOf: file)
        precondition(afterFailure == encrypted)
        let wrongKey = AIConfigurationStore(directory: directory, keys: FixedKey(value: Data(repeating: 8, count: 32)))
        precondition(wrongKey.loadIssue != nil)
        do { try wrongKey.save(config); fatalError("Replaced unreadable settings") } catch {}
        var corrupted = encrypted
        corrupted[corrupted.startIndex] ^= 1
        try corrupted.write(to: file)
        let corruptStore = AIConfigurationStore(directory: directory, keys: FixedKey())
        precondition(corruptStore.loadIssue != nil)
        do { try corruptStore.save(config); fatalError("Overwrote corrupt settings") } catch {}
        let retained = try Data(contentsOf: file)
        precondition(retained == corrupted)
        try corruptStore.clear()
        precondition(corruptStore.loadIssue == nil && !fm.fileExists(atPath: file.path))
        var tooMany = first
        tooMany.models = (0...200).map { "model-\($0)" }
        do { try tooMany.validate(); fatalError("Truncated models") } catch AISettingsError.tooManyModels {}
        precondition(tooMany.models.count == 201)
        for raw in ["http://provider.example/v1", "https://u:p@provider.example/v1", "https://provider.example/v1?q=x", "https://provider.example/#secret"] {
            do { _ = try AIEndpoints.models(raw); fatalError("Accepted unsafe endpoint") } catch AISettingsError.invalidEndpoint {}
        }
        let modelsURL = try AIEndpoints.models("https://provider.example/v1/chat/completions/")
        precondition(modelsURL.absoluteString == "https://provider.example/v1/models")
        do { try AIEndpoints.validateKey("key\r\nInjected: x"); fatalError("Accepted header injection") } catch AISettingsError.invalidKey {}
        let decoded = try AIModelClient.decode(Data("{\"data\":[{\"id\":\" b \"},{\"id\":4},{\"id\":\"a\"},{\"id\":\"b\"}]}".utf8))
        precondition(decoded == ["a", "b"])
        do { _ = try AIModelClient.decode(Data("{\"data\":null}".utf8)); fatalError("Accepted malformed list") } catch AISettingsError.invalidResponse {}
        let sessionConfig = URLSessionConfiguration.ephemeral
        sessionConfig.protocolClasses = [ModelStub.self]
        ModelStub.configure()
        let models = try await AIModelClient.models(endpoint: first.endpoint, key: first.key, configuration: sessionConfig)
        precondition(models == ["test-model"])
        precondition(ModelStub.requests.count == 1 && ModelStub.requests[0].url?.absoluteString == "https://provider.example/v1/models")
        precondition(ModelStub.requests[0].httpMethod == "GET" && ModelStub.requests[0].value(forHTTPHeaderField: "Authorization") == "Bearer " + first.key)
        ModelStub.configure(status: 401, body: "fake-secret-a")
        do { _ = try await AIModelClient.models(endpoint: first.endpoint, key: first.key, configuration: sessionConfig); fatalError("Ignored HTTP failure") }
        catch AISettingsError.http(let status) { precondition(status == 401) }
        ModelStub.configure(headers: ["Content-Length": "5000000"])
        do { _ = try await AIModelClient.models(endpoint: first.endpoint, key: first.key, configuration: sessionConfig); fatalError("Ignored size limit") }
        catch AISettingsError.responseTooLarge {}
        ModelStub.configure(delayed: true)
        let pending = Task { try await AIModelClient.models(endpoint: first.endpoint, key: first.key, configuration: sessionConfig) }
        try await Task.sleep(for: .milliseconds(50))
        pending.cancel()
        do { _ = try await pending.value; fatalError("Ignored cancellation") } catch is CancellationError {}
        let redirectSession = URLSession(configuration: .ephemeral)
        defer { redirectSession.invalidateAndCancel() }
        let task = redirectSession.dataTask(with: URL(string: "https://provider.example/v1/models")!)
        let redirect = HTTPURLResponse(url: task.originalRequest!.url!, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: nil)!
        var denied = false
        AINoRedirects().urlSession(redirectSession, task: task, willPerformHTTPRedirection: redirect,
                                  newRequest: URLRequest(url: URL(string: "https://untrusted.example")!)) { denied = $0 == nil }
        precondition(denied)
        // Dedicated test service, never the application's credential service.
        let service = "com.xnotes.ios.tests.ai.\(UUID())"
        defer { SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service] as CFDictionary) }
        let keychain = AIKeychain(service: service)
        let realKey = try keychain.key(createIfMissing: true)
        let reread = try keychain.key(createIfMissing: false)
        precondition(realKey.count == 32 && realKey == reread)
        var attributes: CFTypeRef?
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service, kSecReturnAttributes as String: true]
        precondition(SecItemCopyMatching(query as CFDictionary, &attributes) == errSecSuccess)
        let storedAttributes = attributes as! [String: Any]
        precondition(storedAttributes[kSecAttrAccessible as String] as? String == (kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String))
        print("PASS AI settings encryption, Keychain, atomic failure, corrupt file protection, stable provider IDs, limits, endpoint+key validation, model HTTP request, cancellation and redirect rejection")
    }
}

@main final class AISettingsTestApp: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Tests", sessionRole: session.role)
        configuration.delegateClass = AISettingsTestScene.self
        return configuration
    }
}

final class AISettingsTestScene: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        window = UIWindow(windowScene: scene)
        window?.rootViewController = UIViewController()
        window?.makeKeyAndVisible()
        Task { @MainActor in
            do {
                try await AISettingsRegression.run()
                AIMarkdownRegression.run()
                try await AIChatRegression.run()
                try await AIImageRegression.run()
                if let window { try await snapshots(window: window) }
                print("PASS AI settings native light and dark screenshots")
                print("PASS AI chat and history native light and dark screenshots")
                print("PASS AI image attachments and screenshot preview native screenshots")
                exit(0)
            } catch {
                print("FAIL AI settings regression: \(error.localizedDescription)")
                exit(1)
            }
        }
    }
}

@MainActor private func snapshots(window: UIWindow) async throws {
    let output = URL(fileURLWithPath: CommandLine.arguments[1])
    let directory = output.appending(path: "snapshot-settings")
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = AIConfigurationStore(directory: directory, keys: FixedKey())
    let provider = AIProvider(name: "Example provider", endpoint: "https://provider.example/v1", key: "fake-snapshot-key", models: ["example-chat"])
    var config = AIConfiguration(providers: [provider])
    config.reconcileDefault()
    try store.save(config)
    for dark in [false, true] {
        for editor in [false, true] {
            let view = editor ? AnyView(AIProviderEditor(provider: provider, isNew: false, accept: { _ in })) : AnyView(AISettingsView(store: store))
            let host = UIHostingController(rootView: view.environment(\.colorScheme, dark ? .dark : .light))
            host.overrideUserInterfaceStyle = dark ? .dark : .light
            window.rootViewController = host
            window.makeKeyAndVisible()
            try await Task.sleep(for: .milliseconds(500))
            host.view.layoutIfNeeded()
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
                window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            let name = "ai-\(editor ? "provider" : "settings")-\(dark ? "dark" : "light").png"
            try image.pngData()!.write(to: output.appending(path: name))
        }
    }
    let history = AIHistoryStore(directory: directory.appending(path: "History"))
    var record = AIConversation(title: "学习笔记")
    var turn = AITurn(question: "请解释这个公式的含义。", model: config.defaultModel!, effort: .providerDefault)
    turn.answer = AIMarkdownRegression.sample
    turn.status = .completed
    var imagePage = NotePage()
    imagePage.text = "截图内容：x^2 + y^2 = r^2"
    let screenshot = try NotebookPageDrawing.render(imagePage, original: nil, longestEdge: 800)
    let attachment = try AIImagePreparation.prepare(screenshot.pngData()!)
    turn.images = [attachment]
    record.turns = [turn]
    record.draftImages = [attachment]
    record.model = config.defaultModel
    try history.save(record)
    let sessions = AISessions(history: history)
    for dark in [false, true] {
        for transcript in [true, false] {
            let view = transcript ? AnyView(AIChatView(sessions: sessions, settings: store, initialConversationID: record.id)) : AnyView(AIHistoryView(sessions: sessions, retentionDays: 7, select: { _ in }))
            let host = UIHostingController(rootView: view.environment(\.colorScheme, dark ? .dark : .light))
            host.overrideUserInterfaceStyle = dark ? .dark : .light
            window.rootViewController = host
            window.makeKeyAndVisible()
            try await Task.sleep(for: .milliseconds(500))
            host.view.layoutIfNeeded()
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
                window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            try image.pngData()!.write(to: output.appending(path: "ai-\(transcript ? "chat" : "history")-\(dark ? "dark" : "light").png"))
        }
    }

    let request = AIScreenshotRequest(image: PageImage(data: screenshot.pngData()!, frame: CGRect(origin: .zero, size: screenshot.size)),
                                     documentID: UUID(), snapshot: imagePage, original: nil, canvas: false, dark: false)
    let preview = UIHostingController(rootView: AIScreenshotPreview(request: request, sessions: sessions, attached: { _ in }))
    window.rootViewController = preview
    window.makeKeyAndVisible()
    try await Task.sleep(for: .milliseconds(500))
    preview.view.layoutIfNeeded()
    let imageFormat = UIGraphicsImageRendererFormat(); imageFormat.scale = 1
    let previewImage = UIGraphicsImageRenderer(bounds: window.bounds, format: imageFormat).image { _ in
        window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
    }
    try previewImage.pngData()!.write(to: output.appending(path: "ai-screenshot-preview.png"))

}
