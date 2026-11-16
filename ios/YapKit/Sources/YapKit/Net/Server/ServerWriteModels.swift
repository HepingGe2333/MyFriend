import Foundation

// ---------------------------------------------------------------------------
// 服务端**写路径**的线上模型（M3）：新建会话 / 标已读 / 上报活动 / 注册推送 / 健康检查。
//
// 字段名逐字对应 `agentd` 的响应（`CrudRoutes.kt` / `AuthorityRoutes.kt` / `Api.kt`），
// 与 `ServerModels` 同一口径：不改名、不猜、缺字段给安全的默认值。
//
// 这些接口都是**幂等**的（`POST /v1/sessions` 已存在就返回既有会话、
// 标已读传同一个 upTo 结果一样、活动上报是「覆盖当前状态」），所以客户端失败重试不需要
// 任何去重簿记——除了发消息那条必须带 `clientMessageId`（见 `ServerOutboxStore`）。
// ---------------------------------------------------------------------------

/// `GET /health` 的响应（设置页「测试连接」用）。
public struct ServerHealth: Decodable, Equatable, Sendable {
    public var ok: Bool
    /// 服务名（agentd 固定回 `yap-agentd`，将来可能改）。
    public var service: String?
    /// 服务端版本（部署排查时最有用的一句话）。
    public var version: String?

    public init(ok: Bool = false, service: String? = nil, version: String? = nil) {
        self.ok = ok
        self.service = service
        self.version = version
    }

    private enum CodingKeys: String, CodingKey { case ok, service, version }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ok = try container.decodeIfPresent(Bool.self, forKey: .ok) ?? false
        service = try container.decodeIfPresent(String.self, forKey: .service)
        version = try container.decodeIfPresent(String.self, forKey: .version)
    }

    /// 「测试连接」成功时给用户看的一行（不带任何凭据）。
    public var describe: String {
        var text = String(localized: "连接正常")
        if let version, !version.isEmpty {
            text += String(format: String(localized: "（服务端 %@）"), version)
        }
        return text
    }
}

/// `POST /v1/sessions` 的请求体。
public struct ServerCreateSessionRequest: Encodable, Equatable, Sendable {
    public var personaId: String
    public var title: String?

    public init(personaId: String, title: String? = nil) {
        self.personaId = personaId
        self.title = title?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private enum CodingKeys: String, CodingKey { case personaId, title }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(personaId, forKey: .personaId)
        if let title, !title.isEmpty { try container.encode(title, forKey: .title) }
    }
}

/// `POST /v1/sessions` 的响应：`created=false` 表示这个人格已经有会话了（服务端返回既有的）。
public struct ServerSessionCreation: Decodable, Equatable, Sendable {
    public var created: Bool
    public var session: ServerSession

    public init(created: Bool = false, session: ServerSession) {
        self.created = created
        self.session = session
    }

    private enum CodingKeys: String, CodingKey { case created, session }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        created = try container.decodeIfPresent(Bool.self, forKey: .created) ?? false
        session = try container.decode(ServerSession.self, forKey: .session)
    }
}

/// `POST /v1/sessions/{id}/read` 的请求体（`upTo` 省略 = 全部标已读到最新）。
public struct ServerMarkReadRequest: Encodable, Equatable, Sendable {
    public var upTo: Int64?

    public init(upTo: Int64? = nil) {
        self.upTo = upTo
    }

    private enum CodingKeys: String, CodingKey { case upTo }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(upTo, forKey: .upTo)
    }
}

/// `POST /v1/sessions/{id}/read` 的响应。
public struct ServerReadResult: Decodable, Equatable, Sendable {
    public var sessionId: String
    /// 服务端标完之后一定回到 0（保留这个字段是为了让「没清干净」当场可见）。
    public var unread: Int
    public var readAt: Int64
    /// 这次实际标了几条（旧服务端可能没有这个字段）。
    public var marked: Int?
    public var session: ServerSession?

    public init(
        sessionId: String,
        unread: Int = 0,
        readAt: Int64 = 0,
        marked: Int? = nil,
        session: ServerSession? = nil
    ) {
        self.sessionId = sessionId
        self.unread = unread
        self.readAt = readAt
        self.marked = marked
        self.session = session
    }

    private enum CodingKeys: String, CodingKey { case sessionId, unread, readAt, marked, session }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sessionId = try container.decodeIfPresent(String.self, forKey: .sessionId) ?? ""
        unread = try container.decodeIfPresent(Int.self, forKey: .unread) ?? 0
        readAt = try container.decodeIfPresent(Int64.self, forKey: .readAt) ?? 0
        marked = try container.decodeIfPresent(Int.self, forKey: .marked)
        session = try container.decodeIfPresent(ServerSession.self, forKey: .session)
    }
}

/// `POST /v1/personas/{id}/activity` 的请求体（规范 §4 的 `set_activity` 参数）。
///
/// 服务端要求 `activity` 非空；`minutes` 由服务端夹到 15–480，客户端再夹一次只是为了
/// 「发出去的报文自己就是合法的」（`ToolArgClamp.activityMinutes` 与它同一口径）。
public struct ServerActivityRequest: Encodable, Equatable, Sendable {
    public var kind: String
    public var activity: String
    public var minutes: Int?
    public var availability: String?

    public init(kind: String, activity: String, minutes: Int? = nil, availability: String? = nil) {
        self.kind = kind
        self.activity = activity
        self.minutes = minutes
        self.availability = availability
    }

    private enum CodingKeys: String, CodingKey { case kind, activity, minutes, availability }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(kind, forKey: .kind)
        try container.encode(activity, forKey: .activity)
        try container.encodeIfPresent(minutes, forKey: .minutes)
        try container.encodeIfPresent(availability, forKey: .availability)
    }
}

/// `POST /v1/personas/{id}/activity` 的响应里客户端真正需要的那一点（`activity` 回显）。
public struct ServerActivityResult: Decodable, Equatable, Sendable {
    public var kind: String?
    public var activity: String?
    /// 服务端定的结束时刻（毫秒）；客户端以服务端为准（它是权威时间）。
    public var until: Int64?
    public var availability: String?

    public init(kind: String? = nil, activity: String? = nil, until: Int64? = nil, availability: String? = nil) {
        self.kind = kind
        self.activity = activity
        self.until = until
        self.availability = availability
    }

    private enum CodingKeys: String, CodingKey {
        case kind, activity, until, availability, persona
    }

    /// 服务端把结果包在 `{"persona":{…},"activity":{…}}` 里（`AuthorityRoutes.activity`），
    /// 所以先看有没有嵌套的 `activity` 对象；没有就按「直接把活动对象当根」读
    ///（两种形状都认，服务端以后收敛成一种也不会把客户端读挂）。
    public init(from decoder: Decoder) throws {
        let root = try decoder.container(keyedBy: CodingKeys.self)
        let container: KeyedDecodingContainer<CodingKeys>
        if let nested = try? root.nestedContainer(keyedBy: CodingKeys.self, forKey: .activity) {
            container = nested
        } else {
            container = root
        }
        kind = try container.decodeIfPresent(String.self, forKey: .kind)
        activity = try container.decodeIfPresent(String.self, forKey: .activity)
        until = try container.decodeIfPresent(Int64.self, forKey: .until)
        availability = try container.decodeIfPresent(String.self, forKey: .availability)
    }
}

/// `POST /v1/push/register` 的请求体。
public struct ServerPushRegistrationRequest: Encodable, Equatable, Sendable {
    /// `ios` / `android`（服务端只认这两个小写值）。
    public var platform: String
    public var token: String
    /// iOS 用 bundleId；Android 用 packageName。这里按平台给其中一个。
    public var bundleId: String?

    public init(platform: String, token: String, bundleId: String? = nil) {
        self.platform = platform
        self.token = token
        self.bundleId = bundleId
    }

    private enum CodingKeys: String, CodingKey { case platform, token, bundleId }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(platform, forKey: .platform)
        try container.encode(token, forKey: .token)
        try container.encodeIfPresent(bundleId, forKey: .bundleId)
    }
}

/// `POST /v1/push/register` 的响应。
public struct ServerPushRegistration: Decodable, Equatable, Sendable {
    /// 服务端说「登记好了」。
    public var registered: Bool
    /// 这次是新建还是覆盖同一 token（覆盖是正常的：重装 App 会拿到新 token）。
    public var created: Bool
    public var platform: String?
    public var bundleId: String?

    public init(registered: Bool = false, created: Bool = false, platform: String? = nil, bundleId: String? = nil) {
        self.registered = registered
        self.created = created
        self.platform = platform
        self.bundleId = bundleId
    }

    private enum CodingKeys: String, CodingKey { case registered, created, platform, bundleId }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        registered = try container.decodeIfPresent(Bool.self, forKey: .registered) ?? false
        created = try container.decodeIfPresent(Bool.self, forKey: .created) ?? false
        platform = try container.decodeIfPresent(String.self, forKey: .platform)
        bundleId = try container.decodeIfPresent(String.self, forKey: .bundleId)
    }
}
