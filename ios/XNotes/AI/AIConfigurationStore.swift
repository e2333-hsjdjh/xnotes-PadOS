import CryptoKit
import Foundation
import Observation
import Security

protocol AIEncryptionKeyStore {
    func key(createIfMissing: Bool) throws -> Data
}

struct AIKeychain: AIEncryptionKeyStore {
    private let service: String

    init(service: String = "com.xnotes.ios.ai.settings") { self.service = service }

    func key(createIfMissing: Bool) throws -> Data {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service, kSecAttrAccount as String: "encryption-key",
                                    kSecAttrSynchronizable as String: false]
        var result: CFTypeRef?
        var read = query
        read[kSecReturnData as String] = true
        read[kSecMatchLimit as String] = kSecMatchLimitOne
        let status = SecItemCopyMatching(read as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data, data.count == 32 { return data }
        guard status == errSecItemNotFound, createIfMissing else { throw AISettingsError.keyUnavailable }
        var bytes = Data(count: 32)
        let randomStatus = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard randomStatus == errSecSuccess else { throw AISettingsError.keyUnavailable }
        var insert = query
        insert[kSecValueData as String] = bytes
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(insert as CFDictionary, nil)
        // Another scene/process may have created it between lookup and insertion.
        if added == errSecDuplicateItem { return try key(createIfMissing: false) }
        guard added == errSecSuccess else { throw AISettingsError.keyUnavailable }
        return bytes
    }
}

@MainActor @Observable
final class AIConfigurationStore {
    private(set) var configuration = AIConfiguration()
    private(set) var loadIssue: String?
    private let directory: URL
    private let keys: any AIEncryptionKeyStore
    private let persist: (Data, URL) throws -> Void
    private var file: URL { directory.appending(path: "Providers.encrypted") }

    init(directory: URL = URL.applicationSupportDirectory.appending(path: "XNotesAI"),
         keys: any AIEncryptionKeyStore = AIKeychain(),
         persist: @escaping (Data, URL) throws -> Void = { data, url in
             try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
         }) {
        self.directory = directory
        self.keys = keys
        self.persist = persist
        guard FileManager.default.fileExists(atPath: file.path) else { return }
        do {
            let data = try Data(contentsOf: file)
            let key = try SymmetricKey(data: keys.key(createIfMissing: false))
            let opened = try AES.GCM.open(AES.GCM.SealedBox(combined: data), using: key)
            let saved = try JSONDecoder().decode(AIConfiguration.self, from: opened)
            try saved.validate()
            configuration = saved
        } catch {
            loadIssue = AISettingsError.corruptSettings.localizedDescription
        }
    }

    func save(_ updated: AIConfiguration) throws {
        guard loadIssue == nil else { throw AISettingsError.unreadableSettings }
        try updated.validate()
        let data = try JSONEncoder().encode(updated)
        let key = try SymmetricKey(data: keys.key(createIfMissing: !FileManager.default.fileExists(atPath: file.path)))
        guard let encrypted = try AES.GCM.seal(data, using: key).combined else { throw AISettingsError.keyUnavailable }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var excluded = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
        try persist(encrypted, file)
        configuration = updated
    }

    /// Clearing removes encrypted API credentials; the device-only master key is reusable.
    func clear() throws {
        if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
        configuration = AIConfiguration()
        loadIssue = nil
    }
}
