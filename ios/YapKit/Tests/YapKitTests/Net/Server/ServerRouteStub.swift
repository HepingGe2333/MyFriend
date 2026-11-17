import Foundation

/// 按「方法 + 路径」路由的假 HTTP 传输（bootstrap 那种一次跑几十个请求的测试用它）。
///
/// 与 `ServerURLProtocolStub` 的分工：那一份是**顺序队列**（一个请求对应一个预置响应），
/// 适合「一次请求」的契约测试与 SSE 取消；bootstrap 的请求顺序会随数据变化（先 state、
/// 再按需要建人格/会话、再逐块拉消息……），用队列写得又长又脆。这里让测试注册路由，
/// 由假服务端按请求**自己算**响应 —— 测的是「编排跑完以后服务端/本地的状态对不对」，
/// 而不是「第 7 个请求的 body 是什么」。
///
/// 只支持「一次性把响应字节给完」：没有取消、没有分片（那些在 `ServerURLProtocolStub`）。
final class ServerRouteStub: URLProtocol {

    // MARK: - 请求

    struct Request {
        var method: String
        var path: String
        var query: [String: String]
        var body: Data
        var authorization: String?
        /// 请求头（键已小写）：媒体上传要断言 `Content-Type`。
        var headers: [String: String] = [:]

        var queryString: String { query.map { "\($0.key)=\($0.value)" }.sorted().joined(separator: "&") }

        var jsonObject: [String: Any]? {
            (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
        }
    }

    // MARK: - 响应

    enum Reply {
        case json([String: Any], status: Int = 200)
        case raw(String, status: Int = 200)
        case failure(URLError)

        static func error(_ message: String, code: String, status: Int) -> Reply {
            .json(["error": ["message": message, "code": code]], status: status)
        }
    }

    private struct Route {
        var method: String?
        var prefix: String
        var suffix: String
        var reply: (Request) -> Reply
    }

    private static let lock = NSLock()
    private static var routes: [Route] = []
    private static var log: [Request] = []

    // MARK: - 测试侧 API

    /// 清掉路由与请求记录（每个测试 `setUp` 调一次）。
    static func reset() {
        lock.lock()
        routes.removeAll()
        log.removeAll()
        lock.unlock()
    }

    /// 注册一条路由。按注册顺序匹配，第一条命中即用。
    ///
    /// - Parameters:
    ///   - method: nil = 任何方法（同一路径的 GET/POST 要分开时再指定）。
    ///   - prefix: 路径前缀（例如 `/v1/sessions/`）。
    ///   - suffix: 路径后缀（例如 `/messages/import`），用来区分同前缀的不同端点。
    static func route(
        method: String? = nil,
        prefix: String = "",
        suffix: String = "",
        reply: @escaping (Request) -> Reply
    ) {
        lock.lock()
        routes.append(Route(method: method, prefix: prefix, suffix: suffix, reply: reply))
        lock.unlock()
    }

    static var recorded: [Request] {
        lock.lock()
        defer { lock.unlock() }
        return log
    }

    static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ServerRouteStub.self]
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 15
        return URLSession(configuration: configuration)
    }

    // MARK: - 内部

    private static func respond(to request: Request) -> Reply {
        lock.lock()
        log.append(request)
        let snapshot = routes
        lock.unlock()
        for route in snapshot {
            if let method = route.method, method != request.method { continue }
            guard request.path.hasPrefix(route.prefix),
                  request.path.hasSuffix(route.suffix) else { continue }
            return route.reply(request)
        }
        return .error("没有登记这条路由：\(request.method) \(request.path)", code: "stub_missing", status: 501)
    }

    // MARK: - URLProtocol

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        var query: [String: String] = [:]
        if let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems {
            for item in items { query[item.name] = item.value ?? "" }
        }
        var body = Data()
        if let httpBody = request.httpBody {
            body = httpBody
        } else if let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let read = stream.read(&buffer, maxLength: buffer.count)
                if read <= 0 { break }
                body.append(buffer, count: read)
            }
        }
        let captured = Request(
            method: request.httpMethod?.uppercased() ?? "GET",
            path: url.path,
            query: query,
            body: body,
            authorization: request.value(forHTTPHeaderField: "Authorization"),
            headers: Dictionary(
                uniqueKeysWithValues: (request.allHTTPHeaderFields ?? [:]).map {
                    ($0.key.lowercased(), $0.value)
                }
            )
        )

        let reply = Self.respond(to: captured)
        switch reply {
        case .failure(let error):
            client?.urlProtocol(self, didFailWithError: error)
        case .json(let object, let status):
            guard JSONSerialization.isValidJSONObject(object),
                  let data = try? JSONSerialization.data(withJSONObject: object) else {
                client?.urlProtocol(self, didFailWithError: URLError(.cannotParseResponse))
                return
            }
            send(data, status: status, url: url)
        case .raw(let text, let status):
            send(Data(text.utf8), status: status, url: url)
        }
    }

    override func stopLoading() {}

    private func send(_ data: Data, status: Int, url: URL) {
        guard let http = HTTPURLResponse(
            url: url,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        ) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
}
