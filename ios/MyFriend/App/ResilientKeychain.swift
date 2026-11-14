import Foundation

/// Keychain 的「够用就好」包装：优先真 Keychain，失败就临时落到 UserDefaults。
///
/// 为什么需要它：模拟器上跑的是**未签名**构建（`CODE_SIGNING_ALLOWED=NO`），
/// 这种进程没有 Keychain 需要的应用标识 entitlement，`SecItemAdd` 会直接返回
/// `errSecMissingEntitlement (-34018)`。表现就是「设置了 API Key，发消息却说没填 Key」——
/// 真机（Xcode 自动签名）不会有这个问题。
///
/// 处理方式刻意分两层：
/// - 真机 / 已签名构建：走 Keychain，Key 不落 UserDefaults（备份能看到明文，这是硬规矩）；
/// - 模拟器上的未签名调试构建：Keychain 写失败时把 Key 放进 UserDefaults，并在日志里明说
///   「这是调试兜底」。这样调试链路能跑通，又不会让人误以为 Key 存得很安全。
final class ResilientKeychain: KeychainBackend {

    private let primary = SystemKeychain()
    private let fallbackPrefix = "debug.keychain-fallback."
    private let lock = NSLock()

    func set(_ value: String, service: String, account: String) throws {
        do {
            try primary.set(value, service: service, account: account)
            removeFallback(service: service, account: account)
            Self.log("Keychain 写入成功（\(account)）")
        } catch {
            lock.lock()
            UserDefaults.standard.set(value, forKey: key(service: service, account: account))
            lock.unlock()
            Self.log("Keychain 不可用（\(error)），本次调试把它放在 UserDefaults 里：\(account)")
        }
    }

    func get(service: String, account: String) -> String? {
        if let value = primary.get(service: service, account: account), !value.isEmpty {
            return value
        }
        lock.lock()
        defer { lock.unlock() }
        return UserDefaults.standard.string(forKey: key(service: service, account: account))
    }

    func delete(service: String, account: String) throws {
        try? primary.delete(service: service, account: account)
        removeFallback(service: service, account: account)
    }

    private func removeFallback(service: String, account: String) {
        lock.lock()
        UserDefaults.standard.removeObject(forKey: key(service: service, account: account))
        lock.unlock()
    }

    private func key(service: String, account: String) -> String {
        fallbackPrefix + service + "." + account
    }

    /// 写进统一日志，方便在模拟器上排查「为什么 Key 读不到」。
    private static func log(_ message: String) {
        #if DEBUG
        DebugSeeding.log("[keychain] " + message)
        #endif
    }
}
