import Foundation

// ---------------------------------------------------------------------------
// Keychain 的「够用就好」包装（0.10.2 审计 P1-5 / 安全审计 P1-1）。
//
// 为什么需要兜底：模拟器上跑的是**未签名**构建（`CODE_SIGNING_ALLOWED=NO`），这种进程没有
// Keychain 需要的应用标识 entitlement，`SecItemAdd` 会直接返回 `errSecMissingEntitlement (-34018)`。
// 表现是「设置了 API Key，发消息却说没填 Key」——真机（Xcode 自动签名）不会有这个问题。
//
// **兜底只允许出现在「DEBUG + 模拟器」**：
// 它把 Key 明文写进 `UserDefaults`（`Library/Preferences/*.plist`），而这个文件默认会进
// iCloud/iTunes 整机备份。真机上任何一次 `SecItemAdd` 失败（锁屏未解锁的
// `errSecInteractionNotAllowed`、Keychain 数据损坏…）都会走到这里，于是「Key 只在 Keychain、
// 不进备份」这条硬规矩就静默失效了。所以真机/Release 上**写失败就抛错**，让设置页明确提示
// 「这台设备无法安全保存密钥」，而不是悄悄写明文。
//
// 判据集中在 `KeychainFallbackPolicy`，用一个编译条件表达；`ResilientKeychain`（App 层）
// 只是把 `SystemKeychain` 与日志接上去，逻辑全在这里，所以能在 `swift test` 里逐条钉住。
// ---------------------------------------------------------------------------

/// 明文兜底到底允不允许。
public enum KeychainFallbackPolicy {

    /// 只有「DEBUG + 模拟器」才允许把 Key 放进 `UserDefaults`。
    ///
    /// 单独写成 static var 而不是散在 `#if` 里，是为了让测试能断言这件事
    /// （在 `swift test` 这个 macOS 进程里，它必须是 false —— 与真机同一条路径）。
    public static var allowsPlaintextFallback: Bool {
        #if DEBUG && targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    /// 给日志用的一句话：失败时为什么不能兜底。
    public static var deniedReason: String {
        allowsPlaintextFallback
            ? "当前是 DEBUG + 模拟器构建：Keychain 不可用时允许调试兜底"
            : "当前构建不允许把 API Key 明文落盘（只有 DEBUG + 模拟器才允许）：Keychain 写失败即报错，请重试或换台设备"
    }
}

/// Keychain + （仅限模拟器调试构建的）`UserDefaults` 兜底。
public final class ResilientKeychainBackend: KeychainBackend {

    /// 兜底键前缀。启动时会把历史遗留的这类条目清掉（升级上来的模拟器可能还有）。
    public static let fallbackPrefix = "debug.keychain-fallback."

    private let primary: KeychainBackend
    private let fallbackDefaults: UserDefaults?
    private let allowsFallback: Bool
    private let log: (String) -> Void
    private let lock = NSLock()

    /// - Parameters:
    ///   - primary: 真 Keychain（App 传 `SystemKeychain()`，测试传假实现）。
    ///   - fallbackDefaults: 兜底写哪儿；传 nil 表示不启用兜底。
    ///   - allowsFallback: 是否允许兜底（默认按编译条件；测试显式传两遍）。
    public init(
        primary: KeychainBackend,
        fallbackDefaults: UserDefaults? = nil,
        allowsFallback: Bool = KeychainFallbackPolicy.allowsPlaintextFallback,
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.primary = primary
        self.fallbackDefaults = fallbackDefaults
        self.allowsFallback = allowsFallback
        self.log = log
    }

    /// 当前实例到底会不会写明文（App 层日志与测试用）。
    public var plaintextFallbackEnabled: Bool { allowsFallback && fallbackDefaults != nil }

    public func set(_ value: String, service: String, account: String) throws {
        do {
            try primary.set(value, service: service, account: account)
            // 真 Keychain 成功了，就把模拟器时代留下的明文清掉。
            removeFallback(service: service, account: account)
            log("Keychain 写入成功（\(account)）")
        } catch {
            guard plaintextFallbackEnabled else {
                // 真机 / Release：绝不写 UserDefaults，把错误抛给上层。
                log("Keychain 写不进去（\(account)）：\(error)。\(KeychainFallbackPolicy.deniedReason)")
                throw error
            }
            lock.lock()
            fallbackDefaults?.set(value, forKey: key(service: service, account: account))
            lock.unlock()
            log("Keychain 不可用（\(error)），本次调试把它放在 UserDefaults 里：\(account)")
        }
    }

    public func get(service: String, account: String) -> String? {
        if let value = primary.get(service: service, account: account), !value.isEmpty {
            return value
        }
        guard allowsFallback else { return nil }
        lock.lock()
        defer { lock.unlock() }
        return fallbackDefaults?.string(forKey: key(service: service, account: account))
    }

    public func delete(service: String, account: String) throws {
        try? primary.delete(service: service, account: account)
        removeFallback(service: service, account: account)
    }

    /// 启动时清掉历史遗留的明文兜底条目（0.10.2 之前，真机也可能写过）。
    ///
    /// - Returns: 清掉了几条。
    @discardableResult
    public func clearLegacyFallbacks() -> Int {
        guard let fallbackDefaults else { return 0 }
        lock.lock()
        defer { lock.unlock() }
        let stale = fallbackDefaults.dictionaryRepresentation().keys.filter {
            $0.hasPrefix(Self.fallbackPrefix)
        }
        for key in stale { fallbackDefaults.removeObject(forKey: key) }
        return stale.count
    }

    private func removeFallback(service: String, account: String) {
        lock.lock()
        fallbackDefaults?.removeObject(forKey: key(service: service, account: account))
        lock.unlock()
    }

    private func key(service: String, account: String) -> String {
        Self.fallbackPrefix + service + "." + account
    }
}
