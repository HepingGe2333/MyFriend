import Foundation

// ---------------------------------------------------------------------------
// 服务端**写路径**（M3）：新建会话 / 标已读 / 上报占用时间 / 注册推送 / 测试连接。
//
// 单独一文件的理由：`ServerAPIClient.swift` 是「读」的那一半（state / sessions /
// messages / events / SSE 发送），这里全是「写」的小接口。两者共用同一个
// `post`/`health` 实现（在同模块里可见），错误映射与超时口径也完全一致。
//
// 共同约定：
// - 所有方法都**不吞错**：网络/鉴权/参数错误原样抛 `ServerAPIError`，由调用方决定
//   「这条失败要不要重试、要不要给用户看」；
// - 路径段一律经 `pathSegment` 编码（会话/人格 id 由服务端生成，仍按不可信处理）。
// ---------------------------------------------------------------------------

public extension ServerAPIClient {

    // MARK: - 测试连接

    /// 设置页「测试连接」：打 `/health`，回服务名与版本。
    ///
    /// 只验证「地址通、令牌对、服务是 agentd」，**不触发任何同步**（见 `health()` 的注释）。
    func testConnection() async throws -> ServerHealth {
        try await health()
    }

    // MARK: - 新建会话

    /// 新建（或取回既有的）某个会话。
    ///
    /// 服务端按「一人格一会话」保证幂等：已存在时 `created = false` 并返回既有会话，
    /// 所以客户端不需要先查再建（那会多一次往返，还会在并发时撞车）。
    func createSession(personaId: String, title: String? = nil) async throws -> ServerSessionCreation {
        let trimmed = personaId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "personaId 不能为空")
        }
        return try await post(
            path: "/sessions",
            body: ServerCreateSessionRequest(personaId: trimmed, title: title),
            as: ServerSessionCreation.self
        )
    }

    // MARK: - 标已读

    /// 把会话标为已读（服务端 `unread` 归零）。
    ///
    /// - Parameter upTo: 只标到这个时刻（毫秒）；nil = 全部标到最新。
    func markRead(sessionId: String, upTo: Int64? = nil) async throws -> ServerReadResult {
        let trimmed = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "sessionId 不能为空")
        }
        if let upTo, upTo < 0 {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "upTo 不能是负数")
        }
        return try await post(
            path: "/sessions/\(Self.writePathSegment(trimmed))/read",
            body: ServerMarkReadRequest(upTo: upTo),
            as: ServerReadResult.self
        )
    }

    // MARK: - 占用时间（set_activity）

    /// 上报「他接下来这段时间在做什么」（规范 §4）。
    ///
    /// 服务端把它写进人格的 `activityJson`，主动引擎据此决定要不要开口
    /// （`BUSY` 不主动开口、`LIMITED` 降权）。客户端**不因为这次失败就当成没设置**：
    /// 本地那一份仍然显示着「在忙」，下一次同步会被服务端的权威值覆盖。
    func reportActivity(
        personaId: String,
        activity: String,
        kind: String = "OTHER",
        minutes: Int? = nil,
        availability: String? = nil
    ) async throws -> ServerActivityResult {
        let trimmedId = personaId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedId.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "personaId 不能为空")
        }
        let trimmedActivity = activity.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedActivity.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "activity 不能为空")
        }
        return try await post(
            path: "/personas/\(Self.writePathSegment(trimmedId))/activity",
            body: ServerActivityRequest(
                kind: kind,
                activity: trimmedActivity,
                minutes: minutes,
                availability: availability
            ),
            as: ServerActivityResult.self
        )
    }

    // MARK: - 推送注册

    /// 注册 APNs/FCM token（M6 的脚手架：服务端有 token 就交给可插拔的 `PushSender`）。
    ///
    /// iOS 这边目前只做到「拿到 token 就登记」；没有 APNs 凭据时服务端降级为
    /// 「客户端前台同步」，与 `docs/design/server-authoritative.md` §3.6 一致。
    func registerPushToken(
        token: String,
        bundleId: String? = nil,
        platform: String = "ios"
    ) async throws -> ServerPushRegistration {
        let trimmed = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw ServerAPIError(code: ServerAPIError.Code.invalidArgument, message: "推送 token 不能为空")
        }
        return try await post(
            path: "/push/register",
            body: ServerPushRegistrationRequest(platform: platform, token: trimmed, bundleId: bundleId),
            as: ServerPushRegistration.self
        )
    }
}

// MARK: - 工具函数（写路径共用）

extension ServerAPIClient {
    /// 路径段编码：不能把 `/`、`?`、`#` 留在里面。
    ///
    /// 与 `ServerAPIClient.swift` 里那份 `private static func pathSegment` 同一实现；
    /// 那份是 private，extension 里用不到，所以这里再暴露一份 `internal` 的。
    static func writePathSegment(_ value: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}
