import Foundation
import Security
import KursfensterCore

struct KeychainCredentials: CredentialVault {
    private let service = "de.wiresheep.kursfenster.ios.credentials"
    private var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "venicebeach"] }
    func load() throws -> Credentials {
        var request = query; request[kSecReturnData as String] = true; request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { throw BookingError.credentialsMissing }
        if status == errSecInteractionNotAllowed || status == errSecNotAvailable { throw BookingError.credentialsLocked }
        guard status == errSecSuccess, let data = result as? Data else { throw BookingError.credentialsMissing }
        do { return try JSONDecoder().decode(Credentials.self, from: data) }
        catch { throw BookingError.credentialsMissing }
    }
    func save(email: String, password: String) throws {
        let previous = try? load()
        let sameAccount = previous?.email.caseInsensitiveCompare(email) == .orderedSame
        let record = Credentials(accountID: sameAccount ? previous!.accountID : UUID(), email: email, password: password)
        let changes: [String: Any] = [kSecValueData as String: try JSONEncoder().encode(record),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, changes as CFDictionary)
        if status == errSecItemNotFound {
            var insert = query; changes.forEach { insert[$0.key] = $0.value }
            guard SecItemAdd(insert as CFDictionary, nil) == errSecSuccess else { throw BookingError.credentialsLocked }
        } else if status != errSecSuccess { throw BookingError.credentialsLocked }
    }
    func clear() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw BookingError.credentialsLocked }
    }
}

final class BookingRuntime: @unchecked Sendable {
    private static let lock = NSLock()
    private static var cached: BookingRuntime?
    static func current() throws -> BookingRuntime {
        lock.lock(); defer { lock.unlock() }
        if let cached { return cached }
        let runtime = try BookingRuntime(); cached = runtime; return runtime
    }
    let vault = KeychainCredentials()
    let coordinator: BookingCoordinator
    private init() throws {
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("Kursfenster", isDirectory: true)
        coordinator = try BookingCoordinator(store: JSONPlanStore(file: directory.appendingPathComponent("plans.json")), vault: vault, gateway: WidgetAPI())
    }
}
