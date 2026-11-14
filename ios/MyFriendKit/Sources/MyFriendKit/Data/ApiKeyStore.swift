import Foundation

/// API Key 的存放：Keychain（service `com.myfriend.app.ios`）。
///
/// 与 Android 的差别：Android 把 Keystore 加密后的密文塞进 DataStore 字符串
/// （所以数据库/备份里到处是 `apiKeyCipher`）；iOS 有 Keychain，Key 直接放系统安全区，
/// 数据库里**不存任何与 Key 有关的字段**（`ApiConfigEntity.apiKeyCipher` 在 iOS 上留空，
/// 只为对齐 Room 的列、以及将来从 Android 迁移时能放下密文引用）。
///
/// 这也解释了备份为什么天然安全：备份读的是数据库 + 显式白名单字段，Keychain 不在其中。
public final class ApiKeyStore {

    /// 与 App 的 bundle id 对齐：profile 里所有 Key 都挂在这个 service 下。
    public static let service = "com.myfriend.app.ios"

    /// account 命名：一个配置一条 Key，删配置时按 id 删得掉，不会误伤别的配置。
    public enum Account {
        public static func apiKey(configId: String) -> String { "api-key.\(configId)" }
        public static func webToolsAPIKey(configId: String) -> String { "web-tools-api-key.\(configId)" }
    }

    private let backend: KeychainBackend
    private let service: String

    public init(backend: KeychainBackend = SystemKeychain(), service: String = ApiKeyStore.service) {
        self.backend = backend
        self.service = service
    }

    // MARK: 主 Key

    /// 写入（或清除）某个配置的主 Key。
    ///
    /// 空字符串视为「清除」：用户在设置页把输入框删空，不应该留下一条空 Key——
    /// 空 Key 发请求只会得到 401，不如明确表示「还没填」。
    public func setAPIKey(_ key: String?, for configId: String) throws {
        try write(key, account: Account.apiKey(configId: configId))
    }

    public func apiKey(for configId: String) -> String? {
        backend.get(service: service, account: Account.apiKey(configId: configId))
    }

    // MARK: 联网工具专用 Key（留空则复用主 Key，与 Android 同语义）

    public func setWebToolsAPIKey(_ key: String?, for configId: String) throws {
        try write(key, account: Account.webToolsAPIKey(configId: configId))
    }

    public func webToolsAPIKey(for configId: String) -> String? {
        backend.get(service: service, account: Account.webToolsAPIKey(configId: configId))
    }

    // MARK: 生命周期

    /// 删配置时调用：主 Key 与联网 Key 一起清掉，不留孤儿条目。
    public func deleteKeys(for configId: String) throws {
        try backend.delete(service: service, account: Account.apiKey(configId: configId))
        try backend.delete(service: service, account: Account.webToolsAPIKey(configId: configId))
    }

    private func write(_ key: String?, account: String) throws {
        let trimmed = key?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let trimmed, !trimmed.isEmpty else {
            try backend.delete(service: service, account: account)
            return
        }
        try backend.set(trimmed, service: service, account: account)
    }
}
