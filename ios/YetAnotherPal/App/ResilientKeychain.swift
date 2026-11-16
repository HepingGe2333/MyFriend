import Foundation

/// Keychain 的 App 侧装配：真 Keychain + 只在「DEBUG + 模拟器」生效的调试兜底。
///
/// 兜底逻辑本身在 `YapKit`（`Support/KeychainFallback.swift`，有单测）：这里只做两件事：
/// 1. 把 `SystemKeychain` 接上；
/// 2. 启动时清掉历史遗留的明文条目（0.10.2 之前，真机上失败也可能写过 `UserDefaults`）。
///
/// 注意：**真机 / Release 上写 Keychain 失败会抛错**，设置页会提示「这个 Key 没能保存」。
/// 这是有意为之：`UserDefaults` 会进 iCloud/iTunes 整机备份，明文 Key 落在里面
/// 与「Key 只在 Keychain、不进备份」这条承诺直接冲突（见 `docs/IOS-PARITY.md`）。
final class ResilientKeychain: KeychainBackend {

    private let impl: ResilientKeychainBackend

    init() {
        impl = ResilientKeychainBackend(
            primary: SystemKeychain(),
            // 只有编译条件允许时才会真的用到这个 defaults（真机传了也不会写）。
            fallbackDefaults: .standard,
            log: { message in
                #if DEBUG
                DebugSeeding.log("[keychain] " + message)
                #endif
            }
        )
        let cleared = impl.clearLegacyFallbacks()
        #if DEBUG
        if cleared > 0 {
            DebugSeeding.log("[keychain] 清掉了 \(cleared) 条历史明文兜底（不应该存在）")
        }
        #endif
    }

    /// 当前构建会不会把明文写进 UserDefaults（模拟器调试构建才是 true）。
    var plaintextFallbackEnabled: Bool { impl.plaintextFallbackEnabled }

    func set(_ value: String, service: String, account: String) throws {
        try impl.set(value, service: service, account: account)
    }

    func get(service: String, account: String) -> String? {
        impl.get(service: service, account: account)
    }

    func delete(service: String, account: String) throws {
        try impl.delete(service: service, account: account)
    }
}
