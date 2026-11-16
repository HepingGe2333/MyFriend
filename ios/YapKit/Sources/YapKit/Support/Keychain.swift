import Foundation

#if canImport(Security)
import Security
#endif

/// Keychain 的薄封装。
///
/// 为什么要有 `KeychainBackend` 这层协议：Keychain 是**有身份**的存储（同一个 bundle id /
/// 签名才稳定读写）。在 `swift test` 这种命令行进程里，签名是构建产物的临时身份，
/// 写一条进去可能弹出系统授权框 —— 测试不该被一个弹窗卡住。所以把「怎么存」抽成协议：
/// App 里用 `SystemKeychain`，测试里用 `InMemoryKeychain`，业务代码（`ApiKeyStore`）两边都测。
///
/// 真机上 API Key 必须进 Keychain（Android 是 Keystore 加密后的 DataStore 字符串），
/// 不能放 UserDefaults：备份、iTunes 备份、越狱设备都能看到明文。
public protocol KeychainBackend: AnyObject {
    func set(_ value: String, service: String, account: String) throws
    func get(service: String, account: String) -> String?
    func delete(service: String, account: String) throws
}

public enum KeychainError: Error, LocalizedError, CustomStringConvertible {
    /// 当前平台没有 Security 框架（理论上 iOS/macOS 都有，这里只是把边界写清楚）。
    case unavailable
    case operationFailed(status: Int32, action: String)

    public var errorDescription: String? { description }

    public var description: String {
        switch self {
        case .unavailable:
            return "当前平台不支持 Keychain"
        case .operationFailed(let status, let action):
            return "Keychain \(action)失败（OSStatus \(status)）"
        }
    }
}

#if canImport(Security)

/// 系统 Keychain 实现：只做「按 service + account 存一个字符串」这一件事。
///
/// `kSecAttrAccessible` 用 `kSecAttrAccessibleAfterFirstUnlock`：App 在后台补算心跳、
/// 做整理时也要能读 Key；但不给 `Always`，锁屏未解锁时连本 App 也读不到。
public final class SystemKeychain: KeychainBackend {
    public init() {}

    public func set(_ value: String, service: String, account: String) throws {
        guard let data = value.data(using: .utf8) else {
            throw KeychainError.operationFailed(status: -1, action: "编码")
        }
        // 先删再加：SecItemUpdate 需要区分「不存在」，删除后重加最简单，也不会留下重复项。
        try delete(service: service, account: account)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
            kSecValueData as String: data
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw KeychainError.operationFailed(status: status, action: "写入")
        }
    }

    public func get(service: String, account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecReturnData as String: true
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess,
              let data = item as? Data,
              let text = String(data: data, encoding: .utf8) else {
            // 读不到就是「没填过」：上层按「需要用户重新填 Key」处理，不当成错误。
            return nil
        }
        return text
    }

    public func delete(service: String, account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.operationFailed(status: status, action: "删除")
        }
    }
}

#else

public final class SystemKeychain: KeychainBackend {
    public init() {}
    public func set(_ value: String, service: String, account: String) throws { throw KeychainError.unavailable }
    public func get(service: String, account: String) -> String? { nil }
    public func delete(service: String, account: String) throws { throw KeychainError.unavailable }
}

#endif

/// 测试与 macOS 命令行环境用的内存实现：语义与 Keychain 一致（service + account 唯一）。
public final class InMemoryKeychain: KeychainBackend {
    private var storage: [String: String] = [:]
    private let lock = NSLock()

    public init() {}

    public func set(_ value: String, service: String, account: String) throws {
        lock.lock(); defer { lock.unlock() }
        storage["\(service)\u{1F}\(account)"] = value
    }

    public func get(service: String, account: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return storage["\(service)\u{1F}\(account)"]
    }

    public func delete(service: String, account: String) throws {
        lock.lock(); defer { lock.unlock() }
        storage.removeValue(forKey: "\(service)\u{1F}\(account)")
    }

    /// 仅供测试断言「删干净了没有」。
    public var storedCount: Int {
        lock.lock(); defer { lock.unlock() }
        return storage.count
    }
}
