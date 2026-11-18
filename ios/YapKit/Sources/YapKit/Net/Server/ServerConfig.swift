import Foundation

// ---------------------------------------------------------------------------
// 服务端权威模式的连接配置（P1a）。
//
// 这一层只回答一个问题：**现在能不能、以及该往哪里发请求**。
// 它不读数据库、不碰 UI。令牌的来源由 `ServerCredentialReading`（默认实现
// `ServerCredentialStore`：Keychain + UserDefaults）提供，`load(...)` 不去 new 它 ——
// 命令行/单测进程里 SystemKeychain 可能弹系统授权框，所以默认不读 Keychain，
// 由 App 装配层显式注入（`ServerConfig.loadForApp()`）。
//
// 来源优先级（与 `docs/design/server-authoritative.md` §7 的安全边界一致）：
// 1. 设备上的设置（设置页的开关/地址 + Keychain 里的令牌，M3 新增，最高优先级）；
// 2. 进程环境变量（`YAP_SERVER_URL` / `YAP_SERVER_TOKEN`），
//    方便 `swift run`、Xcode Scheme、命令行调试时临时覆盖；
// 3. Info.plist（`YetAnotherPalServerURL` / `YetAnotherPalServerToken`），发布包内配置的兜底；
// 4. 都没有 → `enabled = false`，客户端继续走本地模式。
//
// HTTP 只在「调试构建 + 本机/局域网地址」下放行：
// - 明文 HTTP 会把 Bearer Token 和聊天内容交给同网段所有人，对外必须 TLS 反代；
// - Release 构建里即使有人把地址写成 `http://127.0.0.1`，也直接报错（不能悄悄降级，
//   也不能让 ATS 在系统层静默拦掉后说不清原因）。这与 `NetworkGuard` 的既有取舍一致。
// ---------------------------------------------------------------------------

/// 服务端连接配置。
public struct ServerConfig: Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {

    /// 环境变量名。App 进程的 Scheme/CI 可以直接设置。
    public enum EnvironmentKey {
        public static let url = "YAP_SERVER_URL"
        public static let token = "YAP_SERVER_TOKEN"
        public static let enabled = "YAP_SERVER_ENABLED"
    }

    /// Info.plist 的兜底键名。
    ///
    /// 主键用驼峰（Apple 的 plist 惯例）；同时接受全大写下划线写法，免得配置的人
    /// 从环境变量名直接抄过来时以为没生效。
    public enum InfoPlistKey {
        public static let url = "YetAnotherPalServerURL"
        public static let token = "YetAnotherPalServerToken"
        public static let enabled = "YetAnotherPalServerEnabled"

        static let urlAliases = [url, EnvironmentKey.url]
        static let tokenAliases = [token, EnvironmentKey.token]
        static let enabledAliases = [enabled, EnvironmentKey.enabled]
    }

    /// 已经过校验、去掉末尾斜杠的基地址（例如 `https://myfriend.example.com` 或
    /// `http://127.0.0.1:8787`）。没有配置时为 nil。
    public var baseURL: URL?

    /// 共享 Bearer Token。可为空（服务端没开鉴权时）。
    ///
    /// **不要打印它**：`description` / `debugDescription` 都把它脱敏成 `<redacted>`。
    public var token: String?

    /// 用户/配置显式开关。没有地址时即使这里是 true 也不可用（见 `isUsable`）。
    public var enabled: Bool

    public init(baseURL: URL? = nil, token: String? = nil, enabled: Bool = true) {
        self.baseURL = baseURL
        self.token = Self.cleaned(token)
        self.enabled = enabled
    }

    /// 能不能真的发请求：开关打开且地址有效。
    public var isUsable: Bool { enabled && baseURL != nil }

    /// 这台服务端是不是就跑在本机上（`127.0.0.0/8`、`::1`、`localhost`）。
    ///
    /// 用途只有一个：**本机部署才允许客户端把模型配置推上去**（`POST /v1/llm-config`）。
    /// 远程服务器必须由部署者自己配 Key —— 让任何客户端把 Key 推到公网服务上，
    /// 等于把「谁付账、走哪家模型」交给不可信的第三方。服务端那边也按监听地址再挡一次
    /// （`AgentdServices.allowClientLlmConfig`），两边都不靠对方的判断。
    ///
    /// 只认能**静态判定**的回环写法：不解析 DNS（`my-mac.local` 解析到哪台机器，
    /// 客户端在发请求前无法验证），也不认局域网地址（192.168/10/172.16 不是本机）。
    public var isLoopback: Bool {
        guard let host = baseURL?.host else { return false }
        return Self.isLoopbackHost(host)
    }

    static func isLoopbackHost(_ host: String) -> Bool {
        var value = host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if value.hasPrefix("["), value.hasSuffix("]") {
            value = String(value.dropFirst().dropLast())
        }
        if let percent = value.firstIndex(of: "%") { value = String(value[..<percent]) }
        if value == "localhost" || value.hasSuffix(".localhost") { return true }
        if value == "::1" || value == "0:0:0:0:0:0:0:1" { return true }
        // IPv4 回环：整个 127/8。
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4,
              let first = Int(parts[0]),
              parts.allSatisfy({ Int($0).map { (0...255).contains($0) } ?? false }) else {
            return false
        }
        return first == 127
    }

    /// 有没有配置 Token（不暴露 Token 本身）。
    public var hasToken: Bool { token != nil }

    public var description: String {
        let address = baseURL?.absoluteString ?? "nil"
        let secret = hasToken ? "<redacted>" : "nil"
        return "ServerConfig(baseURL: \(address), token: \(secret), enabled: \(enabled))"
    }

    public var debugDescription: String { description }
}

// MARK: - 配置错误

/// 配置层错误。`message` 是可以直接给用户看的中文；错误本身**不包含 Token**，
/// 也不把完整的原始地址塞进文案（地址里可能被误粘进 Token/账号）。
public enum ServerConfigError: Error, Equatable, LocalizedError, Sendable, CustomStringConvertible {
    case invalidURL(String)
    case unsupportedScheme(String)
    case credentialsInURL
    case insecureHTTPNotAllowedInRelease
    case insecureHTTPRequiresLocalNetwork(String)
    case invalidEnabledFlag(String)

    public var message: String {
        switch self {
        case .invalidURL:
            return "服务端地址格式不正确，请填写完整的 https 地址"
        case .unsupportedScheme:
            return "服务端地址只支持 https（调试时可用本机/局域网的 http）"
        case .credentialsInURL:
            return "服务端地址里不要带账号密码；Token 请单独填写"
        case .insecureHTTPNotAllowedInRelease:
            return "这个版本的 App 不允许明文 HTTP 连接服务端（只有调试版可以）。请把地址改成 https:// 或使用 TLS 反向代理。"
        case .insecureHTTPRequiresLocalNetwork(let host):
            return "出于安全要求，明文 HTTP 只允许本机或局域网地址（当前是 \(host)）。请改用 https://。"
        case .invalidEnabledFlag(let raw):
            return "服务端开关的取值无法识别（\(raw)），请使用 true/false"
        }
    }

    public var errorDescription: String? { message }

    /// 即使有人 `print(error)` / 把错误打进日志，也只打印脱敏后的文案，
    /// 不会把 `.invalidURL` 里保存的原始地址（可能被误粘进 Token）带出去。
    public var description: String { message }
}

// MARK: - 读取与校验

public extension ServerConfig {

    /// 当前构建是否允许明文 HTTP（只有 DEBUG）。
    static var buildAllowsInsecureHTTP: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    /// 从「设备设置 / 环境变量 / Info.plist」组装配置。
    ///
    /// - Parameters:
    ///   - environment: 默认 `ProcessInfo.processInfo.environment`；测试可注入。
    ///   - infoDictionary: 默认 `Bundle.main.infoDictionary`；测试可注入。
    ///   - buildAllowsInsecureHTTP: 默认跟随构建；测试用 .init 注入「Release」。
    ///   - credentials: 设备设置（Keychain 令牌 + UserDefaults 开关/地址）。
    ///     默认 `nil` = 不读 Keychain（命令行测试进程里读系统 Keychain 可能弹授权框）；
    ///     App 走 `loadForApp(...)`，那里会显式传 `ServerCredentialStore()`。
    /// - Returns: 没有配置地址时返回 `enabled = false` 的空配置（不抛错，App 回退本地模式）。
    /// - Throws: 配了地址但不合法（格式/协议/Release 明文 http）时抛 `ServerConfigError`。
    static func load(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        infoDictionary: [String: Any]? = Bundle.main.infoDictionary,
        buildAllowsInsecureHTTP: Bool = buildAllowsInsecureHTTP,
        credentials: ServerCredentialReading? = nil
    ) throws -> ServerConfig {
        let rawURL = firstNonEmpty(
            credentials?.storedBaseURLText(),
            environment[EnvironmentKey.url],
            infoDictionary.flatMap { firstNonEmptyString(in: $0, keys: InfoPlistKey.urlAliases) }
        )
        // 令牌：Keychain 优先。环境变量里的令牌是开发者临时覆盖（CI、抓包联调），
        // 不该把用户在设置页保存的那一份顶掉——用户看不到环境变量，只会觉得「设置没生效」。
        let token = firstNonEmpty(
            credentials?.token(),
            environment[EnvironmentKey.token],
            infoDictionary.flatMap { firstNonEmptyString(in: $0, keys: InfoPlistKey.tokenAliases) }
        )
        guard let rawURL else {
            // 没地址 = 服务端模式不可用。开关即便是 true 也不算可用（`isUsable` 要求地址）。
            return ServerConfig(baseURL: nil, token: token, enabled: false)
        }

        let url = try validatedURL(rawURL, buildAllowsInsecureHTTP: buildAllowsInsecureHTTP)

        let explicitEnabled: Bool?
        if let stored = credentials?.modeEnabled() {
            explicitEnabled = stored
        } else if let raw = firstNonEmpty(environment[EnvironmentKey.enabled]) {
            explicitEnabled = try parseEnabledFlag(raw)
        } else if let raw = infoDictionary.flatMap({ firstNonEmptyString(in: $0, keys: InfoPlistKey.enabledAliases) }) {
            explicitEnabled = try parseEnabledFlag(raw)
        } else if let raw = infoDictionary?.firstValue(forAnyKey: InfoPlistKey.enabledAliases) {
            explicitEnabled = parseEnabledValue(raw)
        } else {
            explicitEnabled = nil
        }

        return ServerConfig(
            baseURL: url,
            token: token,
            enabled: explicitEnabled ?? true
        )
    }

    /// App 启动/设置页改完配置后走这一条：设备设置（Keychain + UserDefaults）优先。
    ///
    /// 与 `load(...)` 的区别只是「默认值不同」：这里默认真的去读设备设置。
    /// 装配层（`AppEnvironment`）必须用这个入口，否则设置页填的地址/令牌不会生效。
    static func loadForApp(
        credentials: ServerCredentialReading = ServerCredentialStore(),
        environment: [String: String] = ProcessInfo.processInfo.environment,
        infoDictionary: [String: Any]? = Bundle.main.infoDictionary,
        buildAllowsInsecureHTTP: Bool = buildAllowsInsecureHTTP
    ) throws -> ServerConfig {
        try load(
            environment: environment,
            infoDictionary: infoDictionary,
            buildAllowsInsecureHTTP: buildAllowsInsecureHTTP,
            credentials: credentials
        )
    }

    /// 校验并规范化基地址。
    ///
    /// 规则（与任务书一致）：
    /// - `https`：任意主机都允许；
    /// - `http`：必须同时满足「当前构建允许明文」（DEBUG）与「主机是本机/局域网」；
    /// - 其他协议、带账号密码、带 query/fragment 的地址一律拒绝。
    public static func validatedURL(
        _ raw: String,
        buildAllowsInsecureHTTP: Bool = buildAllowsInsecureHTTP
    ) throws -> URL {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let url = URL(string: trimmed),
              let scheme = url.scheme?.lowercased() else {
            throw ServerConfigError.invalidURL(trimmed)
        }
        // 先判协议：`file:///tmp/server` 这类没有 host 的地址也应该报「不支持协议」，
        // 而不是含混的「格式不正确」（用户据此才知道该改成 https）。
        guard scheme == "https" || scheme == "http" else {
            throw ServerConfigError.unsupportedScheme(scheme)
        }
        guard let host = url.host, !host.isEmpty else {
            throw ServerConfigError.invalidURL(trimmed)
        }
        guard url.user == nil, url.password == nil else {
            throw ServerConfigError.credentialsInURL
        }
        guard url.query == nil, url.fragment == nil else {
            throw ServerConfigError.invalidURL(trimmed)
        }
        if scheme == "http" {
            guard buildAllowsInsecureHTTP else {
                throw ServerConfigError.insecureHTTPNotAllowedInRelease
            }
            guard isLocalNetworkHost(host) else {
                throw ServerConfigError.insecureHTTPRequiresLocalNetwork(host)
            }
        }
        return normalizedBaseURL(url)
    }

    /// 把用户输入的地址规范成「同一台服务端只有一种写法」。
    ///
    /// 用途：`server_sync_consent:<url>` 这类**按地址做键**的簿记。
    /// 不合法（空 / 协议不对 / Release 里的明文 http / 带账号密码）→ nil：
    /// 这类地址本身就不该被保存，更不该被记住「用户同意过」。
    public static func normalizedBaseURLText(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty,
              let url = try? validatedURL(trimmed) else {
            return nil
        }
        return normalizedBaseURL(url).absoluteString
    }

    /// 这个主机是不是「本机 / 局域网」。
    ///
    /// 只认能静态判定的写法：loopback、RFC1918 私网、link-local、IPv6 ULA/link-local，
    /// 以及 `.local`（Bonjour）。普通域名（哪怕是公司内网 DNS）**不**当作局域网 ——
    /// 名字解析到哪台机器，客户端在发请求前无法验证。
    static func isLocalNetworkHost(_ host: String) -> Bool {
        var value = host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if value.hasPrefix("["), value.hasSuffix("]") {
            value = String(value.dropFirst().dropLast())
        }
        // IPv6 的 zone id（fe80::1%en0）不参与前缀判断。
        if let percent = value.firstIndex(of: "%") { value = String(value[..<percent]) }

        if value == "localhost" || value.hasSuffix(".localhost") { return true }
        if value.hasSuffix(".local") { return true }
        if value == "::1" || value == "0:0:0:0:0:0:0:1" { return true }

        if value.contains(":") {
            if value.hasPrefix("fe80:") { return true } // IPv6 link-local
            if value.hasPrefix("fc") || value.hasPrefix("fd") { return true } // ULA fc00::/7
            return false
        }

        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        let octets = parts.compactMap { Int($0) }
        guard octets.count == 4, octets.allSatisfy({ (0...255).contains($0) }) else { return false }
        let first = octets[0]
        let second = octets[1]
        if first == 127 { return true } // 整个 127/8 都是 loopback
        if first == 10 { return true }
        if first == 172, (16...31).contains(second) { return true }
        if first == 192, second == 168 { return true }
        if first == 169, second == 254 { return true } // link-local
        return false
    }
}

// MARK: - 内部工具

private extension ServerConfig {

    static func cleaned(_ raw: String?) -> String? {
        guard let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
            return nil
        }
        return value
    }

    static func firstNonEmpty(_ values: String?...) -> String? {
        for value in values {
            if let cleaned = cleaned(value) { return cleaned }
        }
        return nil
    }

    static func firstNonEmptyString(in dictionary: [String: Any], keys: [String]) -> String? {
        for key in keys {
            if let value = dictionary[key] as? String,
               let cleaned = cleaned(value) {
                return cleaned
            }
        }
        return nil
    }

    static func normalizedBaseURL(_ url: URL) -> URL {
        var text = url.absoluteString
        while text.count > 1, text.hasSuffix("/") {
            text.removeLast()
        }
        // `https://host/` 去掉斜杠后是 `https://host`，URL 仍然有效；
        // 保留原字符串而不是重建 URLComponents，避免把已编码的路径再解一遍。
        return URL(string: text) ?? url
    }

    static func parseEnabledFlag(_ raw: String) throws -> Bool {
        switch raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "1", "true", "yes", "on", "enabled":
            return true
        case "0", "false", "no", "off", "disabled":
            return false
        default:
            throw ServerConfigError.invalidEnabledFlag(raw)
        }
    }

    static func parseEnabledValue(_ raw: Any) -> Bool? {
        if let value = raw as? Bool { return value }
        if let value = raw as? NSNumber { return value.boolValue }
        if let value = raw as? String {
            return try? parseEnabledFlag(value)
        }
        return nil
    }
}

private extension Dictionary where Key == String, Value == Any {
    func firstValue(forAnyKey keys: [String]) -> Any? {
        for key in keys {
            if let value = self[key] { return value }
        }
        return nil
    }
}
