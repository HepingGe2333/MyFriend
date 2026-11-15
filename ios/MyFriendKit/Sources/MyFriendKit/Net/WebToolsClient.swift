import Foundation

// ---------------------------------------------------------------------------
// 联网工具（web_search / web_fetch）的网络客户端。
//
// 0.12 起搜索可以选五个服务（`spec/web-search/README.md` 是唯一契约）：
//   MESSAGEAPI 兼容厂商的 Search / Fetch（服务端抓网页，与 0.11 之前完全一致；
//              0.12 之前这个 provider 叫 DEEPSEEK，旧值仍能读）
//   TAVILY    POST {base}/search
//   BRAVE     GET  {base}/web/search?q=…&count=…
//   SERPER    POST {base}/search
//   GENERIC   自建服务：POST {base}（地址原样使用，不追加路径）
//
// 所有 provider 都收敛成同一份 `WebSearchHit`（去重、丢非 http(s)、snippet 截 500），
// 所以工具执行器、打分器与动作卡片完全不需要知道这次用的是哪家。
//
// 只有一条规矩值得强调：**不偷偷把请求发到 http://**。API Key 明文走明文 HTTP 等于
// 把它交给同网段的每一个人；本地参考服务（harness/server.mjs，8787 端口）必须由上层
// 在配置里显式打开「允许不安全 HTTP」，本类才肯发。
//
// 只依赖 Foundation；`URLSession` 可注入（测试用 StubURLProtocol 假装服务端）。
// 不能用 iOS 专有 API：本包同时跑在 iOS 16 与 macOS 13 上（macOS 上跑 `swift test`）。
// ---------------------------------------------------------------------------

// MARK: - 搜索服务

/// `api_configs.webToolsProvider` 的五个合法取值（规范 §1）。
///
/// 库里存的是 `String`（而不是这个枚举）：备份/数据库是跨端输入，遇到不认识的
/// provider 时退回 `MESSAGEAPI` 比在类型层面把整条配置读丢更安全。
///
/// **0.12 改名**：这个 provider 以前叫 `DEEPSEEK`，现在代表「任何提供同一套
/// `/v1/tools/search`、`/v1/tools/fetch` 接口的兼容厂商」。老库/老备份里的
/// `"DEEPSEEK"` 在读取时归一化成 `MESSAGEAPI`，写库/写备份一律写新值；
/// 数据库 DDL 的默认值仍是历史的 `'DEEPSEEK'`（不改 schema，只在代码层归一化）。
public enum WebToolsProvider: String, CaseIterable, Sendable {
    case messageAPI = "MESSAGEAPI"
    case tavily = "TAVILY"
    case brave = "BRAVE"
    case serper = "SERPER"
    case generic = "GENERIC"

    /// 旧版本的 provider 名（读库/读备份时归一化成现在的取值）。
    ///
    /// 只放「曾经写出去过」的名字：`DEEPSEEK` 是 0.12 之前唯一的服务端 tools provider。
    private static let legacyAliases: [String: WebToolsProvider] = [
        "DEEPSEEK": .messageAPI
    ]

    /// 宽松读法：大小写/空白都容忍；旧值 `DEEPSEEK` 归一到 `MESSAGEAPI`；
    /// 不认识的值也按 `MESSAGEAPI` 处理（老库、新备份都安全）。
    public init(stored: String?) {
        let normalized = stored?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased() ?? ""
        self = WebToolsProvider(rawValue: normalized)
            ?? Self.legacyAliases[normalized]
            ?? .messageAPI
    }

    /// 写库/写备份用的规范值。
    ///
    /// 旧值（`DEEPSEEK`）、大小写/空白差异、以及不认识的取值都收敛成 `MESSAGEAPI`：
    /// 这一列在两端只认五个枚举名，别把历史名字继续写回库和备份里。
    public static func normalizedStored(_ raw: String?) -> String {
        WebToolsProvider(stored: raw).rawValue
    }

    /// 规范 §1 的默认地址。`nil` 表示「没有默认值」（MESSAGEAPI 跟 LLM 配置走，GENERIC 必填）。
    public var defaultBaseUrl: String? {
        switch self {
        case .messageAPI: return nil
        case .tavily: return "https://api.tavily.com"
        case .brave: return "https://api.search.brave.com/res/v1"
        case .serper: return "https://google.serper.dev"
        case .generic: return nil
        }
    }

    /// 配置里 `webToolsBaseUrl` 为空时真正要用的地址。
    ///
    /// - MESSAGEAPI：沿用 LLM 配置的 base URL（这是 0.9.1 以来的行为），LLM 地址也没填时
    ///   回落到官方地址；
    /// - 其它：规范里的默认值；
    /// - GENERIC：没有默认值，返回 nil（调用方报「地址不合法」，绝不猜一个地址发出去）。
    public func resolvedBaseUrl(llmBaseUrl: String = "") -> String? {
        switch self {
        case .messageAPI:
            let base = llmBaseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
            return base.isEmpty ? "https://api.deepseek.com" : base
        default:
            return defaultBaseUrl
        }
    }

    /// 搜索必须带 Key 吗？GENERIC 是自建服务，可以完全不开鉴权。
    public var requiresAPIKey: Bool { self != .generic }

    /// `web_fetch` 是不是由服务端代抓（只有 MESSAGEAPI 兼容厂商是这样）。
    public var usesServerSideFetch: Bool { self == .messageAPI }
}

// MARK: - 传输模型

/// 一条搜索结果。字段名与 Android `WebSearchHit` 一一对应（JSON 里是 `site_name` /
/// `rerank_score`，Swift 里换成驼峰）。
public struct WebSearchHit: Equatable, Sendable {
    public var title: String
    public var url: String
    public var snippet: String
    public var summary: String
    public var siteName: String?
    public var date: String?
    /// 权威度；服务端没给时是 -1（与 Android 一致，不是 0——0 会被误读成「很低」）。
    public var authority: Int
    /// 重排得分；缺省 0。
    public var rerankScore: Double

    public init(
        title: String = "",
        url: String = "",
        snippet: String = "",
        summary: String = "",
        siteName: String? = nil,
        date: String? = nil,
        authority: Int = -1,
        rerankScore: Double = 0
    ) {
        self.title = title
        self.url = url
        self.snippet = snippet
        self.summary = summary
        self.siteName = siteName
        self.date = date
        self.authority = authority
        self.rerankScore = rerankScore
    }
}

/// 一个抓取到的网页正文。与 Android `WebPage` 同形。
public struct WebPage: Equatable, Sendable {
    public var url: String
    public var title: String
    public var markdown: String

    public init(url: String = "", title: String = "", markdown: String = "") {
        self.url = url
        self.title = title
        self.markdown = markdown
    }
}

/// 一次联网工具调用需要的全部访问信息。
///
/// `apiKey` 是明文，只在内存里活一次请求：它由上层从 Keychain 解出来（`ApiKeyStore`
/// 的 `webToolsAPIKey(for:)`，留空则复用主 Key），本类型不负责持久化。
public struct WebToolsAccess: Equatable, Sendable {
    public var baseUrl: String
    public var apiKey: String
    public var allowInsecureHttp: Bool
    /// 这次调用用哪家搜索服务。默认 MESSAGEAPI，保持 0.11 的调用点不用改。
    public var provider: WebToolsProvider

    public init(
        baseUrl: String,
        apiKey: String,
        allowInsecureHttp: Bool = false,
        provider: WebToolsProvider = .messageAPI
    ) {
        self.baseUrl = baseUrl
        self.apiKey = apiKey
        self.allowInsecureHttp = allowInsecureHttp
        self.provider = provider
    }
}

// MARK: - 错误

/// 联网工具的错误。
///
/// 与 Android 一样，`message` 是能直接给用户/模型看的中文：这套文案会被工具执行器
/// 原样回灌给模型，也会出现在「工具调用失败」的气泡里，所以不能把
/// `NSURLErrorDomain Code=-1009` 这种东西漏出去。
public enum WebToolsFailure: Error, LocalizedError, Equatable {
    /// baseUrl 不是合法的 http(s) 地址（也包含「是 http 但没允许不安全 HTTP」）。
    case invalidBaseUrl(String)
    /// 要抓取的网页地址不是合法的 http(s) 链接。
    case invalidUrl(String)
    /// 要抓取的网页地址指向本机/内网字面量（0.11.1 安全审计 P2-5）。
    case localUrl(String)
    /// 要抓取的网页地址里带 userinfo（口令会随 URL 发给第三方抓取服务）。
    case credentialsInURL(String)
    /// 搜索关键词为空（trim 之后）。
    case emptyQuery
    /// 配置里没填联网工具 Key（也不会退回空 Key 发请求：那只会换来 401）。
    case missingAPIKey
    /// HTTP 非 2xx。`message` 是一句带 provider 名与状态码的中文。
    case http(status: Int, message: String)
    /// 响应体超过 2 MiB（规范 §5）：不解析、不回灌给模型。
    case responseTooLarge(Int)
    /// 网络层失败（超时、DNS、断网）。`message` 是中文。
    case network(String)

    public var errorDescription: String? {
        switch self {
        case .invalidBaseUrl:
            return String(localized: "联网工具地址不是合法的 http/https 链接")
        case .invalidUrl:
            return String(localized: "这个链接不是合法的 http/https 链接")
        case .localUrl:
            return String(localized: "这个链接指向本机或内网地址，联网工具不会去访问")
        case .credentialsInURL:
            return String(localized: "这个链接里带着账号或密码，联网工具不会把它发出去")
        case .emptyQuery:
            return String(localized: "搜索关键词不能为空")
        case .missingAPIKey:
            return String(localized: "还没有填联网工具需要的 API Key")
        case let .http(status, message):
            let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? Self.httpFallbackMessage(status: status) : trimmed
        case .responseTooLarge:
            return String(localized: "联网服务的响应太大了（超过 2 MiB），已经拒绝")
        case let .network(message):
            let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? String(localized: "联网工具请求失败，请检查网络后重试") : trimmed
        }
    }

    /// 工具执行器要把它原样回灌给模型，所以给一个「一句人话」的取值（永不为 nil）。
    public var message: String {
        errorDescription ?? String(localized: "联网工具调用失败，请稍后再试")
    }

    /// 兜底文案也必须是中文（`http` 的 message 理论上总是由客户端填好）。
    static func httpFallbackMessage(status: Int) -> String {
        String(format: String(localized: "联网工具返回了 HTTP %d"), status)
    }
}

// MARK: - 客户端

/// 五个搜索服务的统一客户端。
public final class WebToolsClient: @unchecked Sendable {

    /// 搜索请求超时。联网工具是「模型调用链上的一步」，卡 20 秒还没回就该让模型换个说法，
    /// 而不是把整轮对话堵住（聊天本身的超时是 180 秒，见 `OpenAICompatibleClient`）。
    public static let requestTimeout: TimeInterval = 20
    /// 客户端自己抓网页的超时（规范 §4：10 秒）。
    public static let fetchTimeout: TimeInterval = 10
    /// 响应体上限（规范 §5）：超过就直接拒绝，不把几十 MB 的网页读进内存再截断。
    public static let maxResponseBytes = 2 * 1024 * 1024
    /// 客户端抓网页返回给模型的正文上限（规范 §4）。
    public static let maxFetchedCharacters = 8_000
    /// 归一化后的 snippet 上限（规范 §3）。
    public static let maxSnippetCharacters = 500

    /// 默认条数（与 Android 的 `DEFAULT_COUNT` 一致）。
    static let defaultCount = 6
    /// 条数上限：模型偶尔会填 100，服务端不认，这里自己夹住。
    static let maxCount = 12
    /// 服务端抓网页时的正文上限（与 Android 的 `MAX_MARKDOWN_CHARS` 一致）。
    static let maxMarkdownCharacters = 12_000
    /// 截断提示：让模型知道「后面还有，但没给你」，它就不会假装读到了结尾。
    static let truncatedSuffix = "\n\n…（内容过长已截断）"

    static let searchUserAgent = "MyFriend-iOS"
    /// 规范 §4 写死的 UA：客户端抓网页时用这个（不是 App 内部标识）。
    static let fetchUserAgent = "MyFriend/1.0"

    private let session: URLSession
    /// 日志出口：只写 provider / 状态码 / 截断后的响应体，绝不把完整正文或 Key 写出去。
    private let log: (String) -> Void

    public init(session: URLSession = .shared, log: @escaping (String) -> Void = { _ in }) {
        self.session = session
        self.log = log
    }

    // MARK: 端点归一化

    /// 归一化 MESSAGEAPI 兼容厂商的 baseUrl 并拼出工具端点，与 Android `toolsEndpoint` 逐条对齐：
    ///
    /// 1. 两侧空白与尾斜杠去掉；
    /// 2. 用户误粘进来的 `/tools/search`、`/tools/fetch` 去掉（设置页常见）；
    /// 3. 已经以 `/v1` 结尾就沿用，否则补 `/v1`；
    /// 4. `tool` 若自带路径（`tools/search`）就直接拼，否则补 `tools/`。
    ///
    /// 结果形如 `https://api.deepseek.com/v1/tools/search`。
    /// 非法地址（空、无 host、非 http(s)、http 但没允许不安全 HTTP）返回 nil——
    /// 调用方要知道「这是配置问题」，而不是「网络问题」。
    public static func toolsEndpoint(
        baseUrl: String,
        tool: String,
        allowInsecureHttp: Bool = false
    ) -> URL? {
        var base = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        for suffix in ["/tools/search", "/tools/fetch"] where base.hasSuffix(suffix) {
            base.removeLast(suffix.count)
        }
        while base.hasSuffix("/") { base.removeLast() }
        guard !base.isEmpty else { return nil }

        let root = base.hasSuffix("/v1") ? base : "\(base)/v1"
        var path = tool.trimmingCharacters(in: .whitespacesAndNewlines)
        while path.hasPrefix("/") { path.removeFirst() }
        if path.isEmpty { path = "search" }
        let text = path.contains("/") ? "\(root)/\(path)" : "\(root)/tools/\(path)"
        return validatedEndpoint(text, allowInsecureHttp: allowInsecureHttp)
    }

    /// 非 MESSAGEAPI 的「base + 固定路径」：默认地址已经包含各自的前缀
    /// （Brave 默认是 `…/res/v1`），这里只负责把两段拼起来。
    static func appendedEndpoint(
        baseUrl: String,
        path: String,
        allowInsecureHttp: Bool = false
    ) -> URL? {
        var base = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        var suffix = path.trimmingCharacters(in: .whitespacesAndNewlines)
        while suffix.hasPrefix("/") { suffix.removeFirst() }
        guard !base.isEmpty else { return nil }
        return validatedEndpoint("\(base)/\(suffix)", allowInsecureHttp: allowInsecureHttp)
    }

    /// URL 形状 + 明文 HTTP 闸（所有搜索端点共用）。
    private static func validatedEndpoint(_ text: String, allowInsecureHttp: Bool) -> URL? {
        guard let url = URL(string: text),
              let scheme = url.scheme?.lowercased(),
              let host = url.host, !host.isEmpty else { return nil }
        if scheme == "https" { return url }
        if scheme == "http" && allowInsecureHttp { return url }
        return nil
    }

    // MARK: 搜索

    /// 搜索候选网页，返回统一归一化后的结果。
    ///
    /// - Parameters:
    ///   - query: 搜索关键词（会 trim；空则抛 `.emptyQuery`）。
    ///   - count: 想要几条；`<= 0` 用 6，`> 12` 夹到 12（与 Android 的 coerceIn(1,12) 同义）。
    ///   - oriQuestion: 仅 MESSAGEAPI 使用；nil/空白时不发送该字段（同 Android）。
    ///   - needContent: 仅 MESSAGEAPI 使用；请求体里始终带上该字段（同 Android）。
    public func search(
        query: String,
        count: Int = 6,
        oriQuestion: String? = nil,
        needContent: Bool = false,
        access: WebToolsAccess
    ) async throws -> [WebSearchHit] {
        let keyword = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyword.isEmpty else { throw WebToolsFailure.emptyQuery }
        let provider = access.provider
        let key = try requireKeyIfNeeded(access)
        let limit = Self.clampedCount(count)

        let hits: [WebSearchHit]
        switch provider {
        case .messageAPI:
            let endpoint = try resolveMessageAPIEndpoint(tool: "search", access: access)
            var payload: [String: Any] = [
                "query": keyword,
                "count": limit,
                "need_content": needContent
            ]
            // 与 Android 一致：ori_question 只在真的有时才发，空白字符串也不算有。
            if let ori = oriQuestion?.trimmingCharacters(in: .whitespacesAndNewlines), !ori.isEmpty {
                payload["ori_question"] = ori
            }
            let body = try await postJSON(
                endpoint: endpoint,
                payload: payload,
                headers: ["Authorization": "Bearer \(key ?? "")"],
                provider: provider
            )
            hits = Self.parseMessageAPISearch(body)

        case .tavily:
            let endpoint = try resolveAppendedEndpoint(path: "search", access: access)
            let payload: [String: Any] = [
                "api_key": key ?? "",
                "query": keyword,
                "max_results": limit,
                "include_answer": false
            ]
            let body = try await postJSON(
                endpoint: endpoint,
                payload: payload,
                headers: ["Authorization": "Bearer \(key ?? "")"],
                provider: provider
            )
            hits = Self.parseTavilySearch(body)

        case .brave:
            guard let base = Self.appendedEndpoint(
                baseUrl: resolvedBase(access),
                path: "web/search",
                allowInsecureHttp: access.allowInsecureHttp
            ) else {
                throw WebToolsFailure.invalidBaseUrl(access.baseUrl)
            }
            guard var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
                throw WebToolsFailure.invalidBaseUrl(access.baseUrl)
            }
            var items = components.queryItems ?? []
            items.append(URLQueryItem(name: "q", value: keyword))
            items.append(URLQueryItem(name: "count", value: "\(limit)"))
            components.queryItems = items
            guard let endpoint = components.url else {
                throw WebToolsFailure.invalidBaseUrl(access.baseUrl)
            }
            var request = URLRequest(url: endpoint)
            request.httpMethod = "GET"
            request.timeoutInterval = Self.requestTimeout
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            request.setValue(Self.searchUserAgent, forHTTPHeaderField: "User-Agent")
            request.setValue(key, forHTTPHeaderField: "X-Subscription-Token")
            let body = String(decoding: try await send(request, provider: provider), as: UTF8.self)
            hits = Self.parseBraveSearch(body)

        case .serper:
            let endpoint = try resolveAppendedEndpoint(path: "search", access: access)
            let payload: [String: Any] = ["q": keyword, "num": limit]
            let body = try await postJSON(
                endpoint: endpoint,
                payload: payload,
                headers: ["X-API-KEY": key ?? ""],
                provider: provider
            )
            hits = Self.parseSerperSearch(body)

        case .generic:
            let endpoint = try resolveGenericEndpoint(access: access)
            let payload: [String: Any] = ["query": keyword, "count": limit]
            var headers: [String: String] = [:]
            if let key, !key.isEmpty { headers["Authorization"] = "Bearer \(key)" }
            let body = try await postJSON(
                endpoint: endpoint,
                payload: payload,
                headers: headers,
                provider: provider
            )
            hits = Self.parseGenericSearch(body)
        }
        return Self.normalized(hits, limit: limit)
    }

    // MARK: 抓取

    /// 抓单个网页正文。
    ///
    /// - MESSAGEAPI：服务端代抓（行为与 0.11 一致）；
    /// - 其它 provider：本端自己 `GET`（规范 §4：10 秒、≤2 MiB、UA `MyFriend/1.0`），
    ///   去掉标签/脚本/样式、压缩空白、最多 8000 字符。
    public func fetch(url: String, access: WebToolsAccess) async throws -> WebPage {
        let target = url.trimmingCharacters(in: .whitespacesAndNewlines)
        try Self.validatePageUrl(target)

        if access.provider.usesServerSideFetch {
            let key = try requireKeyIfNeeded(access)
            let endpoint = try resolveMessageAPIEndpoint(tool: "fetch", access: access)
            let body = try await postJSON(
                endpoint: endpoint,
                payload: ["url": target],
                headers: ["Authorization": "Bearer \(key ?? "")"],
                provider: access.provider
            )
            return Self.parseMessageAPIPage(body, requestedUrl: target)
        }

        guard let endpoint = URL(string: target) else {
            throw WebToolsFailure.invalidUrl(target)
        }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "GET"
        request.timeoutInterval = Self.fetchTimeout
        request.setValue(Self.fetchUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue(
            "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8",
            forHTTPHeaderField: "Accept"
        )
        let data = try await send(request, provider: access.provider)
        let html = String(decoding: data, as: UTF8.self)
        let title = Self.htmlTitle(html) ?? target
        return WebPage(
            url: target,
            title: title,
            markdown: Self.plainText(fromHTML: html, limit: Self.maxFetchedCharacters)
        )
    }

    // MARK: - 请求

    /// 搜索要 Key，GENERIC 可以不要；返回值对 GENERIC 可能为 nil。
    private func requireKeyIfNeeded(_ access: WebToolsAccess) throws -> String? {
        let key = access.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if key.isEmpty {
            // 规范 §5：缺 Key 明确报错（GENERIC 无 Key 除外）。
            if access.provider.requiresAPIKey { throw WebToolsFailure.missingAPIKey }
            return nil
        }
        return key
    }

    /// 配置里填的地址；为空时按 provider 的默认值兜底。
    private func resolvedBase(_ access: WebToolsAccess) -> String {
        let trimmed = access.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
        return access.provider.resolvedBaseUrl() ?? ""
    }

    private func resolveMessageAPIEndpoint(tool: String, access: WebToolsAccess) throws -> URL {
        let base = resolvedBase(access)
        guard let url = Self.toolsEndpoint(
            baseUrl: base, tool: tool, allowInsecureHttp: access.allowInsecureHttp
        ) else {
            throw WebToolsFailure.invalidBaseUrl(access.baseUrl)
        }
        return url
    }

    private func resolveAppendedEndpoint(path: String, access: WebToolsAccess) throws -> URL {
        let base = resolvedBase(access)
        guard let url = Self.appendedEndpoint(
            baseUrl: base, path: path, allowInsecureHttp: access.allowInsecureHttp
        ) else {
            throw WebToolsFailure.invalidBaseUrl(access.baseUrl)
        }
        return url
    }

    private func resolveGenericEndpoint(access: WebToolsAccess) throws -> URL {
        // 规范 §2：GENERIC 的 base URL 原样使用，不追加路径。
        let base = access.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !base.isEmpty,
              let url = Self.validatedEndpoint(base, allowInsecureHttp: access.allowInsecureHttp) else {
            throw WebToolsFailure.invalidBaseUrl(access.baseUrl)
        }
        return url
    }

    /// 一次 JSON POST。返回响应体原文；非 2xx 抛 `.http`。
    private func postJSON(
        endpoint: URL,
        payload: [String: Any],
        headers: [String: String],
        provider: WebToolsProvider
    ) async throws -> String {
        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(withJSONObject: payload) else {
            throw WebToolsFailure.network(String(localized: "联网工具请求构造失败，请稍后再试"))
        }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        // 只改这一条请求的超时，不动 session 的全局配置（App 其它请求的超时是另一回事）。
        request.timeoutInterval = Self.requestTimeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(Self.searchUserAgent, forHTTPHeaderField: "User-Agent")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        request.httpBody = data
        let responseData = try await send(request, provider: provider)
        return String(decoding: responseData, as: UTF8.self)
    }

    /// 发请求 + 三件套检查：网络错误翻中文、响应过大拒绝、非 2xx 带 provider/状态。
    private func send(_ request: URLRequest, provider: WebToolsProvider) async throws -> Data {
        let responseData: Data
        let response: URLResponse
        do {
            (responseData, response) = try await session.data(for: request)
        } catch let failure as WebToolsFailure {
            throw failure
        } catch let urlError as URLError {
            throw Self.mapURLFailure(urlError)
        } catch is CancellationError {
            // 用户点了停止 / 上层取消 Task：这不是「网络坏了」，别报吓人的中文。
            throw CancellationError()
        } catch {
            throw WebToolsFailure.network(String(localized: "联网工具请求失败，请检查网络后重试"))
        }

        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if responseData.count > Self.maxResponseBytes {
            log("[web-tools] \(provider.rawValue) 响应 \(responseData.count) 字节，超过 \(Self.maxResponseBytes)")
            throw WebToolsFailure.responseTooLarge(responseData.count)
        }
        guard (200..<300).contains(status) else {
            // 原始响应体只写日志，截到 200 字符；绝不回灌给模型（规范 §5）。
            log("[web-tools] \(provider.rawValue) HTTP \(status): \(Self.logExcerpt(responseData))")
            throw WebToolsFailure.http(
                status: status,
                message: Self.httpMessage(provider: provider, status: status)
            )
        }
        return responseData
    }

    /// 带 provider 名与状态码的中文错误（模型抄进回复也看得懂）。
    static func httpMessage(provider: WebToolsProvider, status: Int) -> String {
        switch status {
        case 401, 403:
            return String(
                format: String(localized: "「%@」说没有权限（HTTP %d），检查一下搜索服务的 Key"),
                provider.rawValue, status
            )
        case 429:
            return String(
                format: String(localized: "「%@」限流了（HTTP %d），过一会儿再试"),
                provider.rawValue, status
            )
        case 500...599:
            return String(
                format: String(localized: "「%@」暂时不可用（HTTP %d），过一会儿再试"),
                provider.rawValue, status
            )
        default:
            return String(
                format: String(localized: "「%@」返回了 HTTP %d，检查一下地址与 Key"),
                provider.rawValue, status
            )
        }
    }

    private static func logExcerpt(_ data: Data) -> String {
        let text = String(decoding: data.prefix(200), as: UTF8.self)
        return text.replacingOccurrences(of: "\n", with: " ")
    }

    // MARK: - 响应解析（全部容错：坏 JSON / 缺字段都不抛）

    /// MESSAGEAPI 兼容接口的旧解析：`results` / `data` / `hits` 都认。
    static func parseMessageAPISearch(_ body: String) -> [WebSearchHit] {
        guard let root = jsonObject(body) else { return [] }
        let rawItems: [Any]
        if let array = root as? [Any] {
            rawItems = array
        } else if let object = root as? [String: Any] {
            rawItems = (object["results"] as? [Any])
                ?? (object["data"] as? [Any])
                ?? (object["hits"] as? [Any])
                ?? []
        } else {
            rawItems = []
        }
        return rawItems.compactMap { item -> WebSearchHit? in
            guard let object = item as? [String: Any] else { return nil }
            return hit(from: object, titleKeys: ["title", "name"],
                       urlKeys: ["url", "link", "href"],
                       snippetKeys: ["snippet", "description", "abstract"],
                       summaryKeys: ["summary", "content"])
        }
    }

    /// TAVILY：`results[]` 里的 `content` 是 snippet。
    static func parseTavilySearch(_ body: String) -> [WebSearchHit] {
        items(in: body, path: ["results"]).compactMap { object in
            hit(from: object, titleKeys: ["title"], urlKeys: ["url"],
                snippetKeys: ["content", "snippet"], summaryKeys: [])
        }
    }

    /// BRAVE：`web.results[]`，摘要字段叫 `description`。
    static func parseBraveSearch(_ body: String) -> [WebSearchHit] {
        guard let root = jsonObject(body) as? [String: Any],
              let web = root["web"] as? [String: Any],
              let items = web["results"] as? [Any] else { return [] }
        return items.compactMap { item -> WebSearchHit? in
            guard let object = item as? [String: Any] else { return nil }
            return hit(from: object, titleKeys: ["title"], urlKeys: ["url"],
                       snippetKeys: ["description", "snippet"], summaryKeys: [])
        }
    }

    /// SERPER：`organic[]`，链接字段叫 `link`。
    static func parseSerperSearch(_ body: String) -> [WebSearchHit] {
        items(in: body, path: ["organic"]).compactMap { object in
            hit(from: object, titleKeys: ["title"], urlKeys: ["link", "url"],
                snippetKeys: ["snippet", "description"], summaryKeys: [])
        }
    }

    /// GENERIC：`{"results":[{"title","url","snippet"}]}`。
    static func parseGenericSearch(_ body: String) -> [WebSearchHit] {
        items(in: body, path: ["results"]).compactMap { object in
            hit(from: object, titleKeys: ["title"], urlKeys: ["url", "link"],
                snippetKeys: ["snippet", "content", "description"], summaryKeys: ["summary"])
        }
    }

    /// 归一化（规范 §3）：丢掉非 http(s) 的 URL、按 URL 去重（保留首次出现）、
    /// snippet 截到 500 字符、最多 `limit` 条。
    static func normalized(_ hits: [WebSearchHit], limit: Int) -> [WebSearchHit] {
        var seen = Set<String>()
        var result: [WebSearchHit] = []
        for var hit in hits {
            let url = hit.url.trimmingCharacters(in: .whitespacesAndNewlines)
            guard isHTTPURL(url) else { continue }
            guard seen.insert(url).inserted else { continue }
            hit.url = url
            hit.snippet = Self.truncated(hit.snippet, limit: Self.maxSnippetCharacters)
            result.append(hit)
            if result.count >= max(1, limit) { break }
        }
        return result
    }

    /// 只要 http/https 且有 host：`javascript:`、`file:`、相对路径一律丢掉。
    static func isHTTPURL(_ text: String) -> Bool {
        guard let url = URL(string: text),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = url.host, !host.isEmpty else { return false }
        return true
    }

    /// 解析 MESSAGEAPI 兼容接口的抓取响应：兼容 `markdown` / `content` / `text` 三种字段名，也认 `data` 包一层。
    static func parseMessageAPIPage(_ body: String, requestedUrl: String) -> WebPage {
        let fallback = WebPage(url: requestedUrl, title: requestedUrl, markdown: "")
        guard let object = jsonObject(body) else { return fallback }
        // 极少数网关会直接把正文当 JSON 字符串回。
        if let text = object as? String {
            return WebPage(url: requestedUrl, title: requestedUrl,
                           markdown: truncateMarkdown(text))
        }
        guard let root = object as? [String: Any] else { return fallback }
        let fields = (root["data"] as? [String: Any]) ?? root
        let resolvedUrl = text(fields, ["url", "link"]) ?? requestedUrl
        let title = text(fields, ["title", "name"]) ?? resolvedUrl
        let markdown = truncateMarkdown(text(fields, ["markdown", "content", "text"]) ?? "")
        return WebPage(url: resolvedUrl, title: title, markdown: markdown)
    }

    private static func jsonObject(_ body: String) -> Any? {
        try? JSONSerialization.jsonObject(with: Data(body.utf8))
    }

    private static func items(in body: String, path: [String]) -> [[String: Any]] {
        guard var current = jsonObject(body) as? [String: Any] else { return [] }
        for (index, key) in path.enumerated() {
            if index == path.count - 1 {
                guard let array = current[key] as? [Any] else { return [] }
                return array.compactMap { $0 as? [String: Any] }
            }
            guard let next = current[key] as? [String: Any] else { return [] }
            current = next
        }
        return []
    }

    private static func hit(
        from object: [String: Any],
        titleKeys: [String],
        urlKeys: [String],
        snippetKeys: [String],
        summaryKeys: [String]
    ) -> WebSearchHit {
        WebSearchHit(
            title: text(object, titleKeys) ?? "",
            url: text(object, urlKeys) ?? "",
            snippet: text(object, snippetKeys) ?? "",
            summary: text(object, summaryKeys) ?? "",
            siteName: text(object, ["site_name", "siteName", "site"]),
            date: text(object, ["date", "published_at", "publishedAt", "time"]),
            authority: intValue(object, ["authority", "authority_score"]) ?? -1,
            rerankScore: doubleValue(object, ["rerank_score", "rerankScore", "score"]) ?? 0
        )
    }

    /// 取第一个「trim 之后非空」的字段。
    ///
    /// 数字/布尔也当文本收（有的服务端会把 `date` 写成时间戳、`authority` 写成 "3"），
    /// 类型不符就当没有——绝不因为一个字段类型奇怪就整条丢掉。
    private static func text(_ object: [String: Any], _ keys: [String]) -> String? {
        for key in keys {
            let raw: String?
            if let value = object[key] as? String {
                raw = value
            } else if let number = object[key] as? NSNumber {
                raw = number.stringValue
            } else {
                raw = nil
            }
            guard let raw else { continue }
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { return trimmed }
        }
        return nil
    }

    private static func intValue(_ object: [String: Any], _ keys: [String]) -> Int? {
        // 先走数字类型（服务端正常情况），再退回字符串解析（有的网关把数字写成 "3"）。
        for key in keys {
            if let number = object[key] as? NSNumber { return number.intValue }
        }
        guard let raw = text(object, keys) else { return nil }
        if let value = Int(raw) { return value }
        return Double(raw).map { Int($0) }
    }

    private static func doubleValue(_ object: [String: Any], _ keys: [String]) -> Double? {
        for key in keys {
            if let number = object[key] as? NSNumber { return number.doubleValue }
        }
        guard let raw = text(object, keys) else { return nil }
        return Double(raw)
    }

    // MARK: - 网页正文清洗（规范 §4）

    /// 从 HTML 里取 `<title>`；没有就 nil（调用方用 URL 兜底）。
    static func htmlTitle(_ html: String) -> String? {
        guard let regex = try? NSRegularExpression(
            pattern: "(?is)<title[^>]*>(.*?)</title>", options: []
        ) else { return nil }
        let range = NSRange(html.startIndex..<html.endIndex, in: html)
        guard let match = regex.firstMatch(in: html, options: [], range: range),
              let captured = Range(match.range(at: 1), in: html) else { return nil }
        let title = htmlToPlainText(String(html[captured]))
        return title.isEmpty ? nil : String(title.prefix(200))
    }

    /// HTML → 纯文本：去掉 script/style/noscript 与所有标签、解常见实体、压缩空白，
    /// 最后截到 `limit` 字符（规范 §4）。
    static func plainText(fromHTML html: String, limit: Int = maxFetchedCharacters) -> String {
        var text = html
        for pattern in [
            "(?is)<script\\b[^>]*>.*?</script>",
            "(?is)<style\\b[^>]*>.*?</style>",
            "(?is)<noscript\\b[^>]*>.*?</noscript>",
            "(?is)<!--.*?-->"
        ] {
            if let regex = try? NSRegularExpression(pattern: pattern, options: []) {
                let range = NSRange(text.startIndex..<text.endIndex, in: text)
                text = regex.stringByReplacingMatches(
                    in: text, options: [], range: range, withTemplate: " "
                )
            }
        }
        let collapsed = htmlToPlainText(text)
        return String(collapsed.prefix(max(0, limit)))
    }

    /// 去标签 + 解实体 + 压缩空白（`htmlTitle` 与 `plainText` 共用）。
    private static func htmlToPlainText(_ html: String) -> String {
        var text = html
        if let regex = try? NSRegularExpression(pattern: "(?is)<[^>]+>", options: []) {
            let range = NSRange(text.startIndex..<text.endIndex, in: text)
            text = regex.stringByReplacingMatches(in: text, options: [], range: range, withTemplate: " ")
        }
        text = decodeHTMLEntities(text)
        // 「压缩空白」：换行/制表/连续空格都收成一个空格（模型要的是正文，不是排版）。
        text = text.components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 只解最常见的实体：足以让正文可读，又不至于引入一个完整的 HTML 解析器。
    static func decodeHTMLEntities(_ text: String) -> String {
        var result = text
        let named: [(String, String)] = [
            ("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
            ("&quot;", "\""), ("&apos;", "'"), ("&#39;", "'"), ("&mdash;", "—"),
            ("&ndash;", "–"), ("&hellip;", "…")
        ]
        for (entity, replacement) in named {
            result = result.replacingOccurrences(of: entity, with: replacement)
        }
        // 数字实体：&#20013; 与 &#x4E2D;。
        if let regex = try? NSRegularExpression(pattern: "&#(x?[0-9A-Fa-f]+);", options: []) {
            let range = NSRange(result.startIndex..<result.endIndex, in: result)
            let matches = regex.matches(in: result, options: [], range: range)
            for match in matches.reversed() {
                guard let full = Range(match.range, in: result),
                      let digitsRange = Range(match.range(at: 1), in: result) else { continue }
                let digits = String(result[digitsRange])
                let value = digits.hasPrefix("x") || digits.hasPrefix("X")
                    ? UInt32(digits.dropFirst(), radix: 16)
                    : UInt32(digits, radix: 10)
                guard let value, let scalar = UnicodeScalar(value) else { continue }
                result.replaceSubrange(full, with: String(scalar))
            }
        }
        return result
    }

    // MARK: - 小工具

    private static func truncated(_ text: String, limit: Int) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count > limit else { return trimmed }
        return String(trimmed.prefix(limit))
    }

    private static func truncateMarkdown(_ markdown: String) -> String {
        let trimmed = markdown.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count > maxMarkdownCharacters else { return trimmed }
        return String(trimmed.prefix(maxMarkdownCharacters)) + truncatedSuffix
    }

    static func clampedCount(_ count: Int) -> Int {
        if count <= 0 { return defaultCount }
        return Swift.min(count, maxCount)
    }

    /// 系统级错误翻成中文。原文只写进日志，不丢给模型：
    /// 模型看到英文系统报错只会把它抄进回复里。
    private static func mapURLFailure(_ error: URLError) -> Error {
        switch error.code {
        case .cancelled:
            return CancellationError()
        case .timedOut:
            return WebToolsFailure.network(String(localized: "联网工具请求超时，稍后再试"))
        case .cannotFindHost, .dnsLookupFailed:
            return WebToolsFailure.network(String(localized: "无法解析联网工具地址，请检查网络连接"))
        case .notConnectedToInternet, .networkConnectionLost,
             .cannotConnectToHost, .secureConnectionFailed,
             .internationalRoamingOff, .dataNotAllowed:
            return WebToolsFailure.network(String(localized: "网络连接中断，请检查网络后重试"))
        default:
            return WebToolsFailure.network(String(localized: "联网工具请求失败，请检查网络后重试"))
        }
    }

    /// 要抓取的网页地址必须是 http(s) 且不是本机/内网字面量。
    ///
    /// 这个地址最终由服务端（或本端）去访问，本端只做形状校验；但「形状」包含与 Android
    /// `WebUrlPolicy` 对齐的**内网字面量**规则（0.11.1 安全审计 P2-5 / iOS 审计 P2-17）：
    /// 同一句模型生成的 `http://192.168.1.5/admin`，Android 报错、iOS 以前照发。
    /// 规则表与绕过用例都在 `WebUrlPolicyTests`（`WebUrlPolicy` 是纯函数，便于逐条钉住）。
    static func validatePageUrl(_ raw: String) throws {
        switch WebUrlPolicy.check(raw) {
        case nil:
            return
        case .localHost:
            throw WebToolsFailure.localUrl(raw)
        case .credentialsInURL:
            throw WebToolsFailure.credentialsInURL(raw)
        case .empty, .unsupportedScheme, .missingHost, .ambiguousAuthority, .invalidHost:
            throw WebToolsFailure.invalidUrl(raw)
        }
    }
}
