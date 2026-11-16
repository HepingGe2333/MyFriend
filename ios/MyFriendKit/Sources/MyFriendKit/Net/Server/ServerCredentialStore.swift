import Foundation

// ---------------------------------------------------------------------------
// 服务端模式的**配置来源与安全存储**（M3）：开关 / 地址 / 令牌。
//
// 三件东西的存放位置刻意不同：
// - **令牌**：Keychain（`KeychainBackend`，service 与 API Key 同一个 bundle service）。
//   Token 等于服务端的全部权限（读聊天、发消息、改人格），明文放 UserDefaults /
//   数据库 / 备份文件都是不可接受的；iOS 的 Keychain 是系统安全区，且不进 iTunes 备份的
//   分类外读取。与 `ApiKeyStore` 采用同一套封装与同一套「空字符串 = 清除」语义。
// - **开关与地址**：UserDefaults。它们不是秘密（地址可能出现在截图/日志里也无所谓），
//   而且设置页要反复读写；放 Keychain 只会让每次读取都可能触发一次系统鉴权。
// - **同步游标 / 待发队列**：SQLite（见 `ServerClientStateStore`），因为它们要和数据
//   同一个事务提交。
//
// `ServerConfig.load` 的来源优先级（高 → 低）：
//   1. 设备上的设置（本文件的 Keychain + UserDefaults，即用户在设置页填的）；
//   2. 进程环境变量（`MYFRIEND_SERVER_URL` / `_TOKEN` / `_ENABLED`，调试与 CI 用）；
//   3. Info.plist（发布包内预置的兜底）。
// 为什么设置页最高：它是用户当前明确表达的意愿。环境变量是开发者的临时覆盖，
// 不该把用户在界面上填的地址顶掉（否则「设置页看起来生效了、其实请求发到别处」）。
// ---------------------------------------------------------------------------

/// 服务端模式设置的读取面（`ServerConfig.load` 只依赖这几个方法，测试可注入）。
public protocol ServerCredentialReading: AnyObject {
    /// Keychain 里的服务端令牌；没有就是 nil。
    func token() -> String?
    /// 设置页的「服务端模式」开关；nil = 用户从没动过（交给环境变量/Info.plist 决定）。
    func modeEnabled() -> Bool?
    /// 设置页填的地址原文（**未校验**；校验统一在 `ServerConfig.validatedURL`）。
    func storedBaseURLText() -> String?
}

/// 服务端模式设置的实体：令牌进 Keychain，开关/地址进 UserDefaults。
public final class ServerCredentialStore: ServerCredentialReading {

    /// 与 `ApiKeyStore.service` 同一个 bundle service（同一份 Keychain 分区）。
    public static let service = ApiKeyStore.service

    /// Keychain 里的 account 名。与 API Key 的 `api-key.<id>` 不同命名空间，不会互相覆盖。
    public static let tokenAccount = "server.token"

    /// UserDefaults 键名（snake_case，与 `SettingsStore` 的风格一致）。
    public enum Key {
        public static let enabled = "server_mode_enabled"
        public static let baseURL = "server_mode_base_url"
    }

    private let keychain: KeychainBackend
    private let defaults: UserDefaults
    private let service: String

    public init(
        keychain: KeychainBackend = SystemKeychain(),
        defaults: UserDefaults = .standard,
        service: String = ServerCredentialStore.service
    ) {
        self.keychain = keychain
        self.defaults = defaults
        self.service = service
    }

    // MARK: 令牌（Keychain）

    public func token() -> String? {
        guard let raw = keychain.get(service: service, account: Self.tokenAccount) else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// 写令牌。`nil` / 空白 = 清除（空 Token 发请求只会得到 401，不如明确表示「没填」）。
    public func setToken(_ token: String?) throws {
        let trimmed = token?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let trimmed, !trimmed.isEmpty else {
            try keychain.delete(service: service, account: Self.tokenAccount)
            return
        }
        try keychain.set(trimmed, service: service, account: Self.tokenAccount)
    }

    public var hasToken: Bool { token() != nil }

    /// 脱敏展示用的文本：**只露末尾 4 位**，长度也一并遮住。
    ///
    /// 为什么不露前缀：有些部署会把服务名前缀写进 Token（`prod_…`），
    /// 露出来等于告诉旁观者「这是生产环境」。末尾 4 位足够用户确认「填的是不是那一串」。
    public func maskedToken() -> String? {
        guard let token = token() else { return nil }
        let suffix = String(token.suffix(4))
        return "••••••••" + suffix
    }

    // MARK: 开关与地址（UserDefaults）

    public func modeEnabled() -> Bool? {
        guard defaults.object(forKey: Key.enabled) != nil else { return nil }
        return defaults.bool(forKey: Key.enabled)
    }

    public func setModeEnabled(_ enabled: Bool?) {
        guard let enabled else {
            defaults.removeObject(forKey: Key.enabled)
            return
        }
        defaults.set(enabled, forKey: Key.enabled)
    }

    public func storedBaseURLText() -> String? {
        guard let raw = defaults.string(forKey: Key.baseURL) else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    public func setBaseURLText(_ text: String?) {
        let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let trimmed, !trimmed.isEmpty else {
            defaults.removeObject(forKey: Key.baseURL)
            return
        }
        defaults.set(trimmed, forKey: Key.baseURL)
    }

    /// 关掉服务端模式时清干净：令牌、开关一起清掉（地址留着，用户下次打开不用重填）。
    ///
    /// 为什么清令牌：用户关掉服务端模式通常意味着「不想让这台设备再连那台服务器」，
    /// 把 Token 留在 Keychain 里只是让下次误开时立刻又把数据发出去。
    public func clearToken() throws {
        try keychain.delete(service: service, account: Self.tokenAccount)
    }
}

// MARK: - 预览用

public extension ServerCredentialStore {
    /// 界面上「现在连的是谁」的一行摘要（地址 + 有没有令牌），**不含 Token**。
    ///
    /// 例：`https://chat.example.com · 已保存令牌`。地址原样显示是有意的：
    /// 用户需要能一眼确认「我现在连的是不是那台机器」。
    func summary(baseURL: URL?) -> String {
        let address = baseURL?.absoluteString ?? "未填写地址"
        return address + " · " + (hasToken ? "已保存令牌" : "没有令牌")
    }
}
