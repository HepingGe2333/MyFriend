import Foundation

/// 服务端 API 的统一错误。
///
/// 字段与 `agentd` 的错误体 `{"error":{"message":"…","code":"…"}}` 对应：
/// - `code`：稳定标识（`not_found` / `rate_limited` / `generation_failed` / 客户端合成的 `timeout`…）；
/// - `status`：HTTP 状态码；SSE 事件里的 `error`、传输层错误为 nil；
/// - `message`：直接可展示的中文。
///
/// **不含请求头、Token、URL 或响应原文**：错误对象会被打进日志、展示到界面，
/// 任何一处带出 Authorization 都等于把共享密钥写进日志文件。
public struct ServerAPIError: Error, LocalizedError, Equatable, Sendable, CustomStringConvertible {
    public let code: String
    public let status: Int?
    public let message: String

    public init(code: String, status: Int? = nil, message: String) {
        self.code = code
        self.status = status
        self.message = message
    }

    public var errorDescription: String? { message }

    /// 与 `ServerAPIError` 的结构体默认打印一致，但显式写出来是为了防止以后有人
    /// 往里面加字段时顺手把请求上下文也打进去。
    public var description: String {
        "ServerAPIError(code: \(code), status: \(status.map(String.init) ?? "nil"), message: \(message))"
    }

    // MARK: - 服务端/传输层常用码

    public enum Code {
        public static let notConfigured = "not_configured"
        public static let disabled = "server_disabled"
        public static let invalidArgument = "invalid_argument"
        public static let invalidResponse = "invalid_response"
        public static let decodeFailed = "decode_failed"
        public static let invalidSSE = "invalid_sse"
        public static let unsupportedEvent = "unsupported_event"
        public static let timeout = "timeout"
        public static let networkError = "network_error"
        public static let cancelled = "cancelled"
        public static let unauthorized = "unauthorized"
        public static let rateLimited = "rate_limited"
        public static let serverError = "server_error"
    }
}
