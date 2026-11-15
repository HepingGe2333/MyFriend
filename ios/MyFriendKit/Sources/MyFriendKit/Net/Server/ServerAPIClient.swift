import Foundation

// ---------------------------------------------------------------------------
// `agentd` 服务端权威 API 的传输层（P1a）。
//
// 只做三件事：拼请求、把 JSON 解成 `ServerModels`、把 SSE 解成 `ServerChatEvent`。
// 不读写本地数据库、不碰 UI、不决定重连策略（重连/待发送队列属于 P1b，见交付报告）。
//
// 为什么用 `URLSession` + `async/await`：
// - 系统自带，不新增依赖；
// - `bytes(for:)` 给的是真的字节流（不是整包缓冲），配合 `ServerSSEParser` 能处理半包；
// - `Task` 取消可以直接落到 URLSessionDataTask（`AsyncBytes.task`），
//   满足「客户端断开后服务端继续生成并落库」这一契约的客户端一侧。
//
// 超时（任务书要求）：
// - 普通请求：10s 请求超时 / 30s 资源超时（默认 session 配置）；
// - SSE：`URLRequest.timeoutInterval = 0`，只靠消费方取消（回复可能生成几十秒，
//   不能在中途被超时掐断；服务端在客户端断开后仍会继续生成并落库）。
// ---------------------------------------------------------------------------

public final class ServerAPIClient {

    /// 普通请求的请求级超时（等待响应头/数据）。
    public static let requestTimeout: TimeInterval = 10
    /// 普通请求的资源级超时（整个请求从开始到读完）。
    public static let resourceTimeout: TimeInterval = 30
    /// SSE 连接允许存活的上限。回复可能生成几十秒，给一周足够；真正的结束靠消费方取消。
    public static let sseResourceTimeout: TimeInterval = 60 * 60 * 24 * 7
    /// 非 2xx 时最多读多少错误体（错误体是一小段 JSON；不能让坏服务端把客户端读爆）。
    public static let maxErrorBodyBytes = 64 * 1024
    /// SSE 读缓冲的块大小（只是喂给解析器的批次，不是行上限）。
    private static let sseChunkBytes = 4 * 1024

    /// 连接配置。Token 只存在这里与请求头里，**不打印**。
    public let config: ServerConfig
    /// 普通 JSON 请求：10s 请求超时 / 30s 资源超时。
    private let session: URLSession
    /// SSE 请求：请求级超时 0，资源超时给一周；与普通请求分开
    /// （URLSession 的资源超时是 session 级配置，不能按请求改，共用会把长回复在 30s 掐断）。
    private let sseSession: URLSession

    /// - Parameters:
    ///   - config: 已校验/已加载的连接配置。
    ///   - session: 可注入（测试用自定义 `URLProtocol`；生产用默认配置的 session）。
    ///   - sseSession: 可单独注入 SSE 会话；不传时，若注入了 `session` 就复用它（测试场景）。
    public init(
        config: ServerConfig,
        session: URLSession? = nil,
        sseSession: URLSession? = nil
    ) {
        self.config = config
        if let session {
            self.session = session
            self.sseSession = sseSession ?? session
        } else {
            self.session = Self.makeSession()
            self.sseSession = sseSession ?? Self.makeSSESession()
        }
    }

    /// 普通请求用的默认 session：10s/30s 超时、不缓存、不等网络。
    public static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = requestTimeout
        configuration.timeoutIntervalForResource = resourceTimeout
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }

    /// SSE 用的默认 session：资源超时一周，请求超时由 `URLRequest.timeoutInterval = 0` 关掉。
    public static func makeSSESession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = sseResourceTimeout
        configuration.timeoutIntervalForResource = sseResourceTimeout
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }

    // MARK: - GET /v1/state

    /// 拉状态快照。
    ///
    /// - Parameters:
    ///   - since: 增量游标；首次同步传 0（只返回每个会话最新一块）。
    ///   - personaId: 只看某个人格时传；nil 表示全部。
    public func state(since: Int64 = 0, personaId: String? = nil) async throws -> ServerStateSnapshot {
        guard since >= 0 else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "since 不能是负数")
        }
        var query = [URLQueryItem(name: "since", value: String(since))]
        if let personaId = Self.cleaned(personaId) {
            query.append(URLQueryItem(name: "personaId", value: personaId))
        }
        return try await get(path: "/state", query: query, as: ServerStateSnapshot.self)
    }

    // MARK: - GET /v1/sessions

    public func sessions(personaId: String? = nil) async throws -> ServerSessionsPage {
        var query: [URLQueryItem] = []
        if let personaId = Self.cleaned(personaId) {
            query.append(URLQueryItem(name: "personaId", value: personaId))
        }
        return try await get(path: "/sessions", query: query, as: ServerSessionsPage.self)
    }

    // MARK: - GET /v1/sessions/{id}/messages

    /// 拉一页（一块）消息。
    ///
    /// - Parameters:
    ///   - beforeBlock: 往前翻一块（与 `afterBlock` 二选一）；
    ///   - afterBlock: 往后翻一块；
    ///   - 都不传：取最新一块。
    public func messages(
        sessionId: String,
        beforeBlock: Int? = nil,
        afterBlock: Int? = nil
    ) async throws -> ServerMessagePage {
        let sessionId = try Self.requireNonEmpty(sessionId, field: "sessionId")
        if beforeBlock != nil && afterBlock != nil {
            throw ServerAPIError(
                code: ServerAPIError.Code.invalidArgument,
                message: "beforeBlock 与 afterBlock 只能给一个（一页一块：前翻/后翻各一次）"
            )
        }
        var query = [URLQueryItem(name: "limitBlocks", value: "1")]
        if let beforeBlock {
            guard beforeBlock >= 0 else {
                throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "beforeBlock 不能是负数")
            }
            query.append(URLQueryItem(name: "beforeBlock", value: String(beforeBlock)))
        }
        if let afterBlock {
            guard afterBlock >= 0 else {
                throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "afterBlock 不能是负数")
            }
            query.append(URLQueryItem(name: "afterBlock", value: String(afterBlock)))
        }
        let path = "/sessions/\(Self.pathSegment(sessionId))/messages"
        return try await get(path: path, query: query, as: ServerMessagePage.self)
    }

    // MARK: - GET /v1/events

    /// 长轮询增量事件。
    ///
    /// - Parameters:
    ///   - since: 上次拿到的 `cursor`；空轮询时服务端原样返回，客户端不要前移。
    ///   - timeoutSeconds: 服务端最多等几秒（nil 用服务端默认，上限 25s）。
    ///   - limit: 一次最多几条（nil 用服务端默认）。
    public func events(
        since: Int64,
        timeoutSeconds: Int? = nil,
        limit: Int? = nil
    ) async throws -> ServerEventsPage {
        guard since >= 0 else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "since 不能是负数")
        }
        var query = [URLQueryItem(name: "since", value: String(since))]
        if let timeoutSeconds {
            query.append(URLQueryItem(name: "timeoutSeconds", value: String(max(timeoutSeconds, 0))))
        }
        if let limit {
            query.append(URLQueryItem(name: "limit", value: String(max(limit, 1))))
        }
        // 长轮询可能等 25s（服务端默认/上限），不能用普通请求的 10s 请求超时把它掐断。
        return try await get(
            path: "/events", query: query, as: ServerEventsPage.self,
            timeout: Self.resourceTimeout
        )
    }

    // MARK: - POST /v1/sessions/{id}/messages（SSE）

    /// 发送一条用户消息并消费 SSE 回复流。
    ///
    /// 事件顺序：`accepted` → `delta*` → `tool*`（可选）→ `done`；也可以以 `error` 结束。
    /// 未知事件被静默忽略；`done`/`error` 之后客户端主动停止读取并关闭连接。
    ///
    /// - Parameters:
    ///   - clientMessageId: 幂等键（同一条消息重发不会在服务端生成两条）。
    ///   - content: 正文。
    ///   - attachments: 媒体 id 列表（本阶段服务端只存 id）。
    ///   - replyTo: 回复哪条消息（可空）。
    /// - Returns: 可被 `Task` 取消的流；取消时会关闭底层连接（服务端继续生成并落库）。
    public func sendMessage(
        sessionId: String,
        clientMessageId: String,
        content: String,
        attachments: [String] = [],
        replyTo: String? = nil
    ) -> AsyncThrowingStream<ServerChatEvent, Error> {
        AsyncThrowingStream { continuation in
            let handle = SSEStreamHandle()
            let producer = Task { [weak self] in
                guard let self else {
                    continuation.finish(throwing: ServerAPIError(
                        code: ServerAPIError.Code.networkError, message: "服务端客户端已释放"
                    ))
                    return
                }
                do {
                    let sessionId = try Self.requireNonEmpty(sessionId, field: "sessionId")
                    let body = ServerSendMessageRequest(
                        clientMessageId: clientMessageId,
                        content: content,
                        attachments: attachments,
                        replyTo: replyTo
                    )
                    var request = try self.makeRequest(
                        path: "/sessions/\(Self.pathSegment(sessionId))/messages",
                        query: [],
                        method: "POST",
                        accept: "text/event-stream"
                    )
                    // SSE：请求级超时 0，靠消费方取消结束（回复可能生成几十秒）。
                    request.timeoutInterval = 0
                    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    request.httpBody = try JSONEncoder().encode(body)

                    try await self.runSSE(request: request, handle: handle, continuation: continuation)
                    continuation.finish()
                } catch is CancellationError {
                    continuation.finish(throwing: CancellationError())
                } catch let error as URLError where error.code == .cancelled {
                    continuation.finish(throwing: CancellationError())
                } catch let error as ServerAPIError {
                    continuation.finish(throwing: error)
                } catch let error as URLError {
                    continuation.finish(throwing: Self.mapURLError(error))
                } catch {
                    continuation.finish(throwing: ServerAPIError(
                        code: ServerAPIError.Code.networkError,
                        message: "连接服务端失败"
                    ))
                }
            }
            // 消费方取消/流的生命周期结束：关闭连接并取消生产者。
            continuation.onTermination = { _ in
                handle.cancel()
                producer.cancel()
            }
        }
    }

    // MARK: - 普通请求

    private func get<T: Decodable>(
        path: String,
        query: [URLQueryItem],
        as type: T.Type,
        timeout: TimeInterval = ServerAPIClient.requestTimeout
    ) async throws -> T {
        let request = try makeRequest(
            path: path, query: query, method: "GET", accept: "application/json", timeout: timeout
        )
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw ServerAPIError(
                    code: ServerAPIError.Code.invalidResponse,
                    message: "服务端没有返回 HTTP 响应"
                )
            }
            guard (200..<300).contains(http.statusCode) else {
                throw Self.error(from: data, status: http.statusCode)
            }
            do {
                return try JSONDecoder().decode(T.self, from: data)
            } catch {
                throw ServerAPIError(
                    code: ServerAPIError.Code.decodeFailed,
                    status: http.statusCode,
                    message: "服务端返回的数据格式不正确"
                )
            }
        } catch let error as ServerAPIError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError {
            throw Self.mapURLError(error)
        } catch {
            throw ServerAPIError(code: ServerAPIError.Code.networkError, message: "连接服务端失败")
        }
    }

    // MARK: - SSE 读取

    private func runSSE(
        request: URLRequest,
        handle: SSEStreamHandle,
        continuation: AsyncThrowingStream<ServerChatEvent, Error>.Continuation
    ) async throws {
        let (bytes, response) = try await sseSession.bytes(for: request)
        // 无论正常结束、提前收到 done/error，还是消费方取消，都要把底层连接关掉。
        defer { bytes.task.cancel() }
        handle.attach(bytes.task)

        guard let http = response as? HTTPURLResponse else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidResponse, message: "服务端没有返回 HTTP 响应")
        }
        guard (200..<300).contains(http.statusCode) else {
            // 非 2xx 时服务端回的是普通 JSON 错误体（不是 SSE）。
            var body = Data()
            for try await byte in bytes {
                try Task.checkCancellation()
                body.append(byte)
                if body.count >= Self.maxErrorBodyBytes { break }
            }
            throw Self.error(from: body, status: http.statusCode)
        }

        var parser = ServerSSEParser()
        var buffer = Data()
        buffer.reserveCapacity(Self.sseChunkBytes)

        for try await byte in bytes {
            try Task.checkCancellation()
            buffer.append(byte)
            // 一看到行结束就喂解析器：不能攒满 4KB 才吐事件，否则文字会一整段一整段地跳出来。
            // 长行（没有换行）由 chunk 上限兜底，避免缓冲无界增长。
            guard byte == 0x0A || byte == 0x0D || buffer.count >= Self.sseChunkBytes else {
                continue
            }
            if try emit(parser.feed(buffer), to: continuation) { return }
            buffer.removeAll(keepingCapacity: true)
        }
        if !buffer.isEmpty {
            if try emit(parser.feed(buffer), to: continuation) { return }
        }
        _ = try emit(parser.flush(), to: continuation)
    }

    /// 把一帧转成一个事件；未知事件返回 nil（静默忽略）。返回 true 表示应当停止读取。
    @discardableResult
    private func emit(
        _ frames: [ServerSSEFrame],
        to continuation: AsyncThrowingStream<ServerChatEvent, Error>.Continuation
    ) throws -> Bool {
        for frame in frames {
            guard let event = try Self.chatEvent(from: frame) else { continue }
            try Task.checkCancellation()
            continuation.yield(event)
            switch event {
            case .done, .error:
                return true
            case .accepted, .delta, .tool:
                continue
            }
        }
        return false
    }

    private static func chatEvent(from frame: ServerSSEFrame) throws -> ServerChatEvent? {
        guard let rawName = frame.event?.trimmingCharacters(in: .whitespacesAndNewlines),
              !rawName.isEmpty else {
            // 没有 event 名的 data 帧不是本契约里的任何事件；忽略。
            return nil
        }
        let name = rawName.lowercased()
        let data = Data(frame.data.utf8)
        let decoder = JSONDecoder()

        switch name {
        case "accepted":
            guard let payload = try? decoder.decode(SSEAcceptedPayload.self, from: data),
                  !payload.userMessageId.isEmpty else {
                throw invalidEvent(name)
            }
            return .accepted(userMessageId: payload.userMessageId, createdAt: payload.createdAt ?? 0)
        case "delta":
            guard let payload = try? decoder.decode(SSEDeltaPayload.self, from: data) else {
                throw invalidEvent(name)
            }
            return .delta(text: payload.text)
        case "tool":
            guard let payload = try? decoder.decode(SSEToolPayload.self, from: data),
                  !payload.name.isEmpty else {
                throw invalidEvent(name)
            }
            return .tool(
                name: payload.name,
                status: payload.status ?? "running",
                summary: payload.summary
            )
        case "done":
            guard let payload = try? decoder.decode(SSEDonePayload.self, from: data),
                  !payload.assistantMessageId.isEmpty else {
                throw invalidEvent(name)
            }
            return .done(
                assistantMessageId: payload.assistantMessageId,
                usage: payload.usage ?? ServerChatUsage()
            )
        case "error":
            let payload = try? decoder.decode(SSEErrorPayload.self, from: data)
            let code = Self.cleaned(payload?.code) ?? "server_error"
            let message = Self.cleaned(payload?.message) ?? "服务端生成回复时出错"
            return .error(ServerAPIError(code: code, status: nil, message: message))
        default:
            // 未知事件（例如服务端以后加的 ping）：忽略，不影响已收到的增量。
            return nil
        }
    }

    private static func invalidEvent(_ name: String) -> ServerAPIError {
        ServerAPIError(
            code: ServerAPIError.Code.invalidSSE,
            message: "服务端推送的 \(name) 事件格式不正确"
        )
    }

    // MARK: - 请求构造

    private func makeRequest(
        path: String,
        query: [URLQueryItem],
        method: String,
        accept: String,
        timeout: TimeInterval = ServerAPIClient.requestTimeout
    ) throws -> URLRequest {
        let url = try makeURL(path: path, query: query)
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = timeout
        request.setValue(accept, forHTTPHeaderField: "Accept")
        if let token = Self.cleaned(config.token) {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    private func makeURL(path: String, query: [URLQueryItem]) throws -> URL {
        guard config.enabled else {
            throw ServerAPIError(code: ServerAPIError.Code.disabled, message: "服务端模式已关闭")
        }
        guard let baseURL = config.baseURL else {
            throw ServerAPIError(code: ServerAPIError.Code.notConfigured, message: "还没有配置服务端地址")
        }
        var root = baseURL.absoluteString
        while root.hasSuffix("/") { root.removeLast() }
        if !root.hasSuffix("/v1") { root += "/v1" }
        guard var components = URLComponents(string: root + path) else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "服务端地址无效")
        }
        if !query.isEmpty { components.queryItems = query }
        guard let url = components.url else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "服务端地址无效")
        }
        return url
    }

    // MARK: - 错误映射

    static func error(from data: Data, status: Int) -> ServerAPIError {
        let envelope = try? JSONDecoder().decode(ServerErrorEnvelope.self, from: data)
        let code = Self.cleaned(envelope?.error?.code) ?? "http_\(status)"
        let message = Self.cleaned(envelope?.error?.message) ?? defaultMessage(for: status)
        return ServerAPIError(code: code, status: status, message: message)
    }

    private static func defaultMessage(for status: Int) -> String {
        switch status {
        case 401: return "服务端要求有效的访问 Token"
        case 403: return "服务端拒绝访问"
        case 404: return "服务端没有找到请求的资源"
        case 409: return "请求与服务端状态冲突"
        case 413: return "请求内容太大"
        case 429: return "请求太频繁，请稍后再试"
        case 500...599: return "服务端暂时不可用（HTTP \(status)）"
        default: return "服务端返回 HTTP \(status)"
        }
    }

    static func mapURLError(_ error: URLError) -> Error {
        switch error.code {
        case .cancelled:
            return CancellationError()
        case .timedOut:
            return ServerAPIError(code: ServerAPIError.Code.timeout, message: "连接服务端超时")
        case .notConnectedToInternet, .networkConnectionLost,
             .cannotConnectToHost, .cannotFindHost, .dnsLookupFailed:
            return ServerAPIError(
                code: ServerAPIError.Code.networkError,
                message: "连不上服务端（服务没启动或网络不通）"
            )
        default:
            return ServerAPIError(
                code: ServerAPIError.Code.networkError,
                message: "连接服务端失败（URLError \(error.code.rawValue)）"
            )
        }
    }

    // MARK: - 小工具

    private static func cleaned(_ raw: String?) -> String? {
        guard let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
            return nil
        }
        return value
    }

    private static func requireNonEmpty(_ raw: String, field: String) throws -> String {
        guard let value = cleaned(raw) else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "\(field) 不能为空")
        }
        return value
    }

    /// 路径段编码：不能把 `/`、`?`、`#` 留在里面（会话 id 由服务端生成，仍然按不可信处理）。
    private static func pathSegment(_ value: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

// MARK: - SSE 底层任务句柄

/// 持有 `URLSession.AsyncBytes.task`，让消费方取消时能立刻关闭连接。
///
/// `AsyncThrowingStream` 的 `onTermination` 与生产者 Task 可能在不同线程上到达，
/// 所以这里用锁而不是 `var`；`attach` 与 `cancel` 的先后顺序（取消先到 vs task 先到）
/// 都必须只取消一次、且不能漏掉。
private final class SSEStreamHandle: @unchecked Sendable {
    private let lock = NSLock()
    private var task: URLSessionDataTask?
    private var cancelled = false

    func attach(_ task: URLSessionDataTask) {
        lock.lock()
        if cancelled {
            lock.unlock()
            task.cancel()
            return
        }
        self.task = task
        lock.unlock()
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let task = self.task
        self.task = nil
        lock.unlock()
        task?.cancel()
    }
}

// MARK: - 线上错误体 / SSE data 载荷

private struct ServerErrorEnvelope: Decodable {
    struct Body: Decodable {
        var code: String?
        var message: String?
    }
    var error: Body?
}

private struct SSEAcceptedPayload: Decodable {
    var userMessageId: String
    var createdAt: Int64?
}

private struct SSEDeltaPayload: Decodable {
    var text: String
}

private struct SSEToolPayload: Decodable {
    var name: String
    var status: String?
    var summary: String?
}

private struct SSEDonePayload: Decodable {
    var assistantMessageId: String
    var usage: ServerChatUsage?
}

private struct SSEErrorPayload: Decodable {
    var code: String?
    var message: String?
}
