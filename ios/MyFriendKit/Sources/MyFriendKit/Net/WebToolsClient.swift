import Foundation

// ---------------------------------------------------------------------------
// DeepSeek 官方 Search / Fetch 接口（联网工具）的网络客户端。
//
// 来源：Android `network/DeepSeekWebTools.kt`。这是给 AI 用的联网工具，不参与普通
// 聊天推理：模型先 search 拿到候选网页，再按需 fetch 抓正文，结果作为「工具结果」
// 回灌给模型。本文件只做网络与解析（无 UI、无存储），端点：
//   POST {baseUrl}/v1/tools/search   {"query":…, "count":…, "ori_question":…, "need_content":…}
//   POST {baseUrl}/v1/tools/fetch    {"url":…}
//
// 为什么单独一个客户端、而不是塞进 OpenAICompatibleClient：联网工具的 baseUrl / Key
// 都可以和聊天配置分开（`webToolsBaseUrl` / `webToolsAPIKey`），失败语义也不同——
// 聊天失败要重试、要记 usage；联网工具失败只要给模型一句人话，它自己会换个关键词。
//
// 只依赖 Foundation；`URLSession` 可注入（测试用 StubURLProtocol 假装服务端）。
// 不能用 iOS 专有 API：本包同时跑在 iOS 16 与 macOS 13 上（macOS 上跑 `swift test`）。
// ---------------------------------------------------------------------------

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

    public init(baseUrl: String, apiKey: String, allowInsecureHttp: Bool = false) {
        self.baseUrl = baseUrl
        self.apiKey = apiKey
        self.allowInsecureHttp = allowInsecureHttp
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
    /// 搜索关键词为空（trim 之后）。
    case emptyQuery
    /// 配置里没填联网工具 Key（也不会退回空 Key 发请求：那只会换来 401）。
    case missingAPIKey
    /// HTTP 非 2xx。`message` 优先取响应体里的 `error.message`。
    case http(status: Int, message: String)
    /// 网络层失败（超时、DNS、断网）。`message` 是中文。
    case network(String)

    public var errorDescription: String? {
        switch self {
        case .invalidBaseUrl:
            return "联网工具地址不是合法的 http/https 链接"
        case .invalidUrl:
            return "这个链接不是合法的 http/https 链接"
        case .emptyQuery:
            return "搜索关键词不能为空"
        case .missingAPIKey:
            return "还没有填联网工具需要的 API Key"
        case let .http(_, message):
            let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? "联网工具请求失败，请稍后再试" : trimmed
        case let .network(message):
            let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? "联网工具请求失败，请检查网络后重试" : trimmed
        }
    }

    /// 工具执行器要把它原样回灌给模型，所以给一个「一句人话」的取值（永不为 nil）。
    public var message: String {
        errorDescription ?? "联网工具调用失败，请稍后再试"
    }
}

// MARK: - 客户端

/// DeepSeek 官方 Search / Fetch 的客户端。
///
/// 只有一条规矩值得强调：**不偷偷把请求发到 http://**。API Key 明文走明文 HTTP 等于
/// 把它交给同网段的每一个人；本地参考服务（harness/server.mjs，8787 端口）必须由上层
/// 在配置里显式打开「允许不安全 HTTP」，本类才肯发。
public final class WebToolsClient: @unchecked Sendable {

    /// 单次请求超时。联网工具是「模型调用链上的一步」，卡 20 秒还没回就该让模型换个说法，
    /// 而不是把整轮对话堵住（聊天本身的超时是 180 秒，见 `OpenAICompatibleClient`）。
    public static let requestTimeout: TimeInterval = 20

    /// 默认条数（与 Android 的 `DEFAULT_COUNT` 一致）。
    static let defaultCount = 6
    /// 条数上限：模型偶尔会填 100，服务端不认，这里自己夹住。
    static let maxCount = 12
    /// 正文上限（与 Android 的 `MAX_MARKDOWN_CHARS` 一致）：一页几十万字会把上下文撑爆。
    static let maxMarkdownCharacters = 12_000
    /// 截断提示：让模型知道「后面还有，但没给你」，它就不会假装读到了结尾。
    static let truncatedSuffix = "\n\n…（内容过长已截断）"

    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    // MARK: 端点归一化

    /// 归一化 baseUrl 并拼出工具端点，与 Android `toolsEndpoint` 逐条对齐：
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

        guard let url = URL(string: text),
              let scheme = url.scheme?.lowercased(),
              let host = url.host, !host.isEmpty else { return nil }
        if scheme == "https" { return url }
        if scheme == "http" && allowInsecureHttp { return url }
        return nil
    }

    // MARK: 搜索

    /// 调用 DeepSeek Search，返回候选网页；`results` 缺失/为空/坏 JSON 时返回空数组。
    ///
    /// - Parameters:
    ///   - query: 搜索关键词（会 trim；空则抛 `.emptyQuery`）。
    ///   - count: 想要几条；`<= 0` 用 6，`> 12` 夹到 12（与 Android 的 coerceIn(1,12) 同义）。
    ///   - oriQuestion: 原始问题，让服务端据此重排；nil/空白时不发送该字段（同 Android）。
    ///   - needContent: 是否让服务端顺带返回正文摘要；请求体里始终带上该字段（同 Android）。
    public func search(
        query: String,
        count: Int = 6,
        oriQuestion: String? = nil,
        needContent: Bool = false,
        access: WebToolsAccess
    ) async throws -> [WebSearchHit] {
        let keyword = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyword.isEmpty else { throw WebToolsFailure.emptyQuery }
        let key = try requireKey(access.apiKey)
        let endpoint = try resolveEndpoint(baseUrl: access.baseUrl, tool: "search",
                                           allowInsecureHttp: access.allowInsecureHttp)

        var payload: [String: Any] = [
            "query": keyword,
            "count": Self.clampedCount(count),
            "need_content": needContent
        ]
        // 与 Android 一致：ori_question 只在真的有时才发，空白字符串也不算有。
        if let ori = oriQuestion?.trimmingCharacters(in: .whitespacesAndNewlines), !ori.isEmpty {
            payload["ori_question"] = ori
        }

        let body = try await post(endpoint: endpoint, apiKey: key, payload: payload)
        return Self.parseSearch(body)
    }

    // MARK: 抓取

    /// 调用 DeepSeek Fetch，抓单个网页正文。
    ///
    /// 与 Android 一致：服务端没回 url 就用请求里的那个；标题为空时兜底成 url
    /// （否则工具结果里会出现「《》 https://…」这种半句话）。
    public func fetch(url: String, access: WebToolsAccess) async throws -> WebPage {
        let target = url.trimmingCharacters(in: .whitespacesAndNewlines)
        try Self.validatePageUrl(target)
        let key = try requireKey(access.apiKey)
        let endpoint = try resolveEndpoint(baseUrl: access.baseUrl, tool: "fetch",
                                           allowInsecureHttp: access.allowInsecureHttp)
        let body = try await post(endpoint: endpoint, apiKey: key, payload: ["url": target])
        return Self.parsePage(body, requestedUrl: target)
    }

    // MARK: - 请求

    private func requireKey(_ apiKey: String) throws -> String {
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { throw WebToolsFailure.missingAPIKey }
        return key
    }

    private func resolveEndpoint(baseUrl: String, tool: String, allowInsecureHttp: Bool) throws -> URL {
        guard let url = Self.toolsEndpoint(baseUrl: baseUrl, tool: tool,
                                           allowInsecureHttp: allowInsecureHttp) else {
            throw WebToolsFailure.invalidBaseUrl(baseUrl)
        }
        return url
    }

    /// 一次 POST。返回响应体原文；非 2xx 抛 `.http`。
    private func post(endpoint: URL, apiKey: String, payload: [String: Any]) async throws -> String {
        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(withJSONObject: payload) else {
            throw WebToolsFailure.network("联网工具请求构造失败，请稍后再试")
        }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        // 只改这一条请求的超时，不动 session 的全局配置（App 其它请求的超时是另一回事）。
        request.timeoutInterval = Self.requestTimeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("MyFriend-iOS", forHTTPHeaderField: "User-Agent")
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.httpBody = data

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
            throw WebToolsFailure.network("联网工具请求失败，请检查网络后重试")
        }

        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let body = String(decoding: responseData, as: UTF8.self)
        guard (200..<300).contains(status) else {
            throw WebToolsFailure.http(
                status: status,
                message: Self.errorMessage(from: body, status: status)
            )
        }
        return body
    }

    // MARK: - 响应解析

    /// 容错解析搜索响应：`results` / `data` / `hits` 都认；坏 JSON 与缺失字段都不抛。
    ///
    /// 为什么不严格 Codable：这是模型侧的工具，宁可「少几条结果」也不能因为服务端多加
    /// 一个字段就把整轮联网弄挂。
    static func parseSearch(_ body: String) -> [WebSearchHit] {
        guard let object = try? JSONSerialization.jsonObject(with: Data(body.utf8)) else { return [] }
        let rawItems: [Any]
        if let array = object as? [Any] {
            rawItems = array
        } else if let root = object as? [String: Any] {
            rawItems = (root["results"] as? [Any])
                ?? (root["data"] as? [Any])
                ?? (root["hits"] as? [Any])
                ?? []
        } else {
            rawItems = []
        }
        return rawItems.compactMap { item -> WebSearchHit? in
            guard let object = item as? [String: Any] else { return nil }
            return hit(from: object)
        }
    }

    /// 容错解析抓取响应：兼容 `markdown` / `content` / `text` 三种字段名，也认 `data` 包一层。
    static func parsePage(_ body: String, requestedUrl: String) -> WebPage {
        let fallback = WebPage(url: requestedUrl, title: requestedUrl, markdown: "")
        guard let object = try? JSONSerialization.jsonObject(with: Data(body.utf8)) else {
            return fallback
        }
        // 极少数网关会直接把正文当 JSON 字符串回。
        if let text = object as? String {
            return WebPage(url: requestedUrl, title: requestedUrl,
                           markdown: truncate(text))
        }
        guard let root = object as? [String: Any] else { return fallback }
        let fields = (root["data"] as? [String: Any]) ?? root
        let resolvedUrl = text(fields, ["url", "link"]) ?? requestedUrl
        let title = text(fields, ["title", "name"]) ?? resolvedUrl
        let markdown = truncate(text(fields, ["markdown", "content", "text"]) ?? "")
        return WebPage(url: resolvedUrl, title: title, markdown: markdown)
    }

    private static func hit(from object: [String: Any]) -> WebSearchHit {
        WebSearchHit(
            title: text(object, ["title", "name"]) ?? "",
            url: text(object, ["url", "link", "href"]) ?? "",
            snippet: text(object, ["snippet", "description", "abstract"]) ?? "",
            summary: text(object, ["summary", "content"]) ?? "",
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

    private static func truncate(_ markdown: String) -> String {
        let trimmed = markdown.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count > maxMarkdownCharacters else { return trimmed }
        return String(trimmed.prefix(maxMarkdownCharacters)) + truncatedSuffix
    }

    private static func clampedCount(_ count: Int) -> Int {
        if count <= 0 { return defaultCount }
        return Swift.min(count, maxCount)
    }

    // MARK: - 错误文案

    /// HTTP 非 2xx 的 message：优先取 `error.message`，其次 `error`（字符串）/ `message`，
    /// 都拿不到就用「联网工具返回了 HTTP \(status)」。
    static func errorMessage(from body: String, status: Int) -> String {
        let detail: String?
        if let object = try? JSONSerialization.jsonObject(with: Data(body.utf8)),
           let root = object as? [String: Any] {
            if let error = root["error"] as? [String: Any] {
                detail = text(error, ["message", "msg", "detail"])
            } else if let error = root["error"] as? String {
                detail = error
            } else {
                detail = text(root, ["message", "msg"])
            }
        } else {
            detail = nil
        }
        guard let message = detail?.trimmingCharacters(in: .whitespacesAndNewlines),
              !message.isEmpty else {
            return "联网工具返回了 HTTP \(status)"
        }
        return String(message.prefix(400))
    }

    /// 系统级错误翻成中文。原文只写进日志，不丢给模型：
    /// 模型看到英文系统报错只会把它抄进回复里。
    private static func mapURLFailure(_ error: URLError) -> Error {
        switch error.code {
        case .cancelled:
            return CancellationError()
        case .timedOut:
            return WebToolsFailure.network("联网工具请求超时，稍后再试")
        case .cannotFindHost, .dnsLookupFailed:
            return WebToolsFailure.network("无法解析联网工具地址，请检查网络连接")
        case .notConnectedToInternet, .networkConnectionLost,
             .cannotConnectToHost, .secureConnectionFailed,
             .internationalRoamingOff, .dataNotAllowed:
            return WebToolsFailure.network("网络连接中断，请检查网络后重试")
        default:
            return WebToolsFailure.network("联网工具请求失败，请检查网络后重试")
        }
    }

    /// 要抓取的网页地址必须是 http(s)：这个地址最终由服务端去访问，本端只做形状校验
    /// （`javascript:`、`file:` 这类交给模型乱填只会白白烧一次请求）。
    private static func validatePageUrl(_ raw: String) throws {
        guard !raw.isEmpty,
              let url = URL(string: raw),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = url.host, !host.isEmpty else {
            throw WebToolsFailure.invalidUrl(raw)
        }
    }
}
