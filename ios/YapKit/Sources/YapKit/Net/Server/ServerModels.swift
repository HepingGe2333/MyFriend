import Foundation

// ---------------------------------------------------------------------------
// `agentd` 服务端权威 API 的线上模型（`docs/design/server-authoritative.md` §3）。
//
// 字段名与 `agentd/.../store/StoreModel.kt` 的 `toJson()` **逐字对应**，不做改名：
// 这些结构是跨端契约，iOS 这边一改名，下一次 Android/iOS 对齐时就要靠猜。
//
// 与本地实体（`Data/Models.swift`）刻意分开：
// - 服务端只有它下发的列，本地实体有几十个仅本地使用的字段（同步状态、摘要进度……）；
// - 服务端角色/来源是字符串，遇到新值应原样保留而不是退回某个本地枚举默认值。
// 两条数据流的合并是 P1b 的事，这一层只负责「把 JSON 解成可信的只读模型」。
// ---------------------------------------------------------------------------

/// 服务端状态快照。
///
/// 任务书里叫 `StateSnapshot`；`ServerStateSnapshot` 是更明确的名字，保留别名方便对照文档。
public typealias StateSnapshot = ServerStateSnapshot

public struct ServerPersona: Codable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var nameEn: String
    public var description: String
    public var avatarUrl: String?
    public var isDefault: Bool
    public var createdAt: Int64
    public var updatedAt: Int64
    /// 0.14 拉黑（服务端契约：`/v1/state` 的 personas 带这两列，null = 未拉黑）。
    ///
    /// 本地模式不读它；服务端模式把它同步进 `personas.blockedUntil/blockReason`，
    /// 这样界面（红色感叹号、输入栏提示）与本地规则同一套。
    public var blockedUntil: Int64?
    public var blockReason: String?

    public init(
        id: String,
        name: String,
        nameEn: String = "",
        description: String = "",
        avatarUrl: String? = nil,
        isDefault: Bool = false,
        createdAt: Int64 = 0,
        updatedAt: Int64 = 0,
        blockedUntil: Int64? = nil,
        blockReason: String? = nil
    ) {
        self.id = id
        self.name = name
        self.nameEn = nameEn
        self.description = description
        self.avatarUrl = avatarUrl
        self.isDefault = isDefault
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.blockedUntil = blockedUntil
        self.blockReason = blockReason
    }
}

public struct ServerSession: Codable, Equatable, Sendable {
    public var id: String
    public var personaId: String
    public var title: String
    public var createdAt: Int64
    public var updatedAt: Int64
    /// 最后一条消息的时间；空会话是 null。
    public var lastMessageAt: Int64?
    /// 最新块号；没有任何消息时服务端下发 -1。
    public var lastBlockIndex: Int
    public var unread: Int
    public var messageCount: Int64

    public init(
        id: String,
        personaId: String,
        title: String = "",
        createdAt: Int64 = 0,
        updatedAt: Int64 = 0,
        lastMessageAt: Int64? = nil,
        lastBlockIndex: Int = -1,
        unread: Int = 0,
        messageCount: Int64 = 0
    ) {
        self.id = id
        self.personaId = personaId
        self.title = title
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.lastMessageAt = lastMessageAt
        self.lastBlockIndex = lastBlockIndex
        self.unread = unread
        self.messageCount = messageCount
    }
}

public struct ServerMessage: Codable, Equatable, Sendable {
    public var id: String
    public var sessionId: String
    public var blockIndex: Int
    /// `USER` / `ASSISTANT` / `SYSTEM`。保存字符串而不是本地枚举：服务端将来加新角色时
    /// 客户端至少能原样显示，不会因为「不认识」被悄悄当成 USER。
    public var role: String
    public var content: String
    public var createdAt: Int64
    public var readAt: Int64?
    /// 媒体 id 列表（本阶段是字符串，不是本地文件路径）。
    public var attachments: [String]
    /// `CHAT` / `PROACTIVE` / `IMPORT`。
    public var source: String
    public var clientMessageId: String?
    public var replyTo: String?
    public var promptTokens: Int?
    public var completionTokens: Int?
    /// 0.14：这条用户消息是否是拉黑期间发的（服务端契约里 `messages.blocked`）。
    public var blocked: Bool?

    public init(
        id: String,
        sessionId: String,
        blockIndex: Int = 0,
        role: String,
        content: String,
        createdAt: Int64 = 0,
        readAt: Int64? = nil,
        attachments: [String] = [],
        source: String = "CHAT",
        clientMessageId: String? = nil,
        replyTo: String? = nil,
        promptTokens: Int? = nil,
        completionTokens: Int? = nil,
        blocked: Bool? = nil
    ) {
        self.id = id
        self.sessionId = sessionId
        self.blockIndex = blockIndex
        self.role = role
        self.content = content
        self.createdAt = createdAt
        self.readAt = readAt
        self.attachments = attachments
        self.source = source
        self.clientMessageId = clientMessageId
        self.replyTo = replyTo
        self.promptTokens = promptTokens
        self.completionTokens = completionTokens
        self.blocked = blocked
    }

    public var isUser: Bool { role.uppercased() == "USER" }
    public var isAssistant: Bool { role.uppercased() == "ASSISTANT" }
}

public struct ServerMood: Codable, Equatable, Sendable {
    public var personaId: String
    public var label: String
    public var intensity: Int
    public var updatedAt: Int64
    /// 0.13 的三维心情（`spec/mood/README.md` §1）。老服务端不下发这三个键 → nil。
    ///
    /// 用可选而不是「默认 0/2/3」：`nil` 表示「这个服务端还不懂三维」，
    /// 此时本地退回词表推断（`MoodState.resolvedDimensions`），与升级前行为一致。
    public var valence: Int?
    public var arousal: Int?
    public var dominance: Int?
    /// 为什么会这样（一句话）。老服务端没有这个键。
    public var reason: String?

    public init(
        personaId: String,
        label: String,
        intensity: Int = 0,
        updatedAt: Int64 = 0,
        valence: Int? = nil,
        arousal: Int? = nil,
        dominance: Int? = nil,
        reason: String? = nil
    ) {
        self.personaId = personaId
        self.label = label
        self.intensity = intensity
        self.updatedAt = updatedAt
        self.valence = valence
        self.arousal = arousal
        self.dominance = dominance
        self.reason = reason
    }

    private enum CodingKeys: String, CodingKey {
        case personaId, label, intensity, updatedAt, valence, arousal, dominance, reason
    }

    /// 服务端下发的三维 → 本地 `MoodDimensions`；三个都缺就返回 nil。
    ///
    /// 缺一个/两个时**不猜**：三维是一组（愉快度/唤醒度/掌控感），拿默认值补齐会让
    /// 「他今天很平静」和「服务端没给我这一维」混成同一个状态（与 `MoodState.dimensions`
    /// 的取舍同一条理由）。所以只要有一个缺失，就整组退回词表推断。
    public var dimensions: MoodDimensions? {
        guard let valence, let arousal, let dominance else { return nil }
        return MoodDimensions(valence: valence, arousal: arousal, dominance: dominance)
    }
}

public struct ServerPresence: Codable, Equatable, Sendable {
    public var personaId: String
    public var kind: String
    public var detail: String
    public var updatedAt: Int64

    public init(personaId: String, kind: String, detail: String = "", updatedAt: Int64 = 0) {
        self.personaId = personaId
        self.kind = kind
        self.detail = detail
        self.updatedAt = updatedAt
    }
}

/// `GET /v1/state` 的响应。
public struct ServerStateSnapshot: Codable, Equatable, Sendable {
    public var serverTime: Int64
    public var personas: [ServerPersona]
    public var sessions: [ServerSession]
    /// 首次同步（`since=0`）只含每个会话的**最新一块**；更早的用消息分页接口。
    public var messages: [ServerMessage]
    public var mood: ServerMood?
    public var presence: ServerPresence?
    /// 单调递增游标：下一次同步带上它。
    public var serverCursor: Int64
    /// 契约外的可加字段：消息被截断（还有更早的块）时为 true。
    public var hasMoreMessages: Bool

    public init(
        serverTime: Int64 = 0,
        personas: [ServerPersona] = [],
        sessions: [ServerSession] = [],
        messages: [ServerMessage] = [],
        mood: ServerMood? = nil,
        presence: ServerPresence? = nil,
        serverCursor: Int64 = 0,
        hasMoreMessages: Bool = false
    ) {
        self.serverTime = serverTime
        self.personas = personas
        self.sessions = sessions
        self.messages = messages
        self.mood = mood
        self.presence = presence
        self.serverCursor = serverCursor
        self.hasMoreMessages = hasMoreMessages
    }

    private enum CodingKeys: String, CodingKey {
        case serverTime, personas, sessions, messages, mood, presence, serverCursor, hasMoreMessages
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        serverTime = try container.decode(Int64.self, forKey: .serverTime)
        personas = try container.decodeIfPresent([ServerPersona].self, forKey: .personas) ?? []
        sessions = try container.decodeIfPresent([ServerSession].self, forKey: .sessions) ?? []
        messages = try container.decodeIfPresent([ServerMessage].self, forKey: .messages) ?? []
        mood = try container.decodeIfPresent(ServerMood.self, forKey: .mood)
        presence = try container.decodeIfPresent(ServerPresence.self, forKey: .presence)
        serverCursor = try container.decodeIfPresent(Int64.self, forKey: .serverCursor) ?? 0
        // 老服务端/新服务端都可能不带它；缺省 false 比「让整次同步解码失败」合理。
        hasMoreMessages = try container.decodeIfPresent(Bool.self, forKey: .hasMoreMessages) ?? false
    }
}

/// `GET /v1/sessions` 的响应。
public struct ServerSessionsPage: Codable, Equatable, Sendable {
    public var serverTime: Int64
    public var sessions: [ServerSession]
    public var serverCursor: Int64

    public init(serverTime: Int64 = 0, sessions: [ServerSession] = [], serverCursor: Int64 = 0) {
        self.serverTime = serverTime
        self.sessions = sessions
        self.serverCursor = serverCursor
    }

    private enum CodingKeys: String, CodingKey { case serverTime, sessions, serverCursor }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        serverTime = try container.decodeIfPresent(Int64.self, forKey: .serverTime) ?? 0
        sessions = try container.decodeIfPresent([ServerSession].self, forKey: .sessions) ?? []
        serverCursor = try container.decodeIfPresent(Int64.self, forKey: .serverCursor) ?? 0
    }
}

/// `GET /v1/sessions/{id}/messages` 的响应：一页一块。
public struct ServerMessagePage: Codable, Equatable, Sendable {
    public var sessionId: String
    /// 翻过头 / 空会话时为 nil（服务端下发 null）。
    public var blockIndex: Int?
    public var hasMoreOlder: Bool
    public var hasMoreNewer: Bool
    public var messages: [ServerMessage]

    public init(
        sessionId: String,
        blockIndex: Int? = nil,
        hasMoreOlder: Bool = false,
        hasMoreNewer: Bool = false,
        messages: [ServerMessage] = []
    ) {
        self.sessionId = sessionId
        self.blockIndex = blockIndex
        self.hasMoreOlder = hasMoreOlder
        self.hasMoreNewer = hasMoreNewer
        self.messages = messages
    }

    private enum CodingKeys: String, CodingKey {
        case sessionId, blockIndex, hasMoreOlder, hasMoreNewer, messages
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sessionId = try container.decode(String.self, forKey: .sessionId)
        blockIndex = try container.decodeIfPresent(Int.self, forKey: .blockIndex)
        hasMoreOlder = try container.decodeIfPresent(Bool.self, forKey: .hasMoreOlder) ?? false
        hasMoreNewer = try container.decodeIfPresent(Bool.self, forKey: .hasMoreNewer) ?? false
        messages = try container.decodeIfPresent([ServerMessage].self, forKey: .messages) ?? []
    }
}

/// `GET /v1/events` 返回的一条增量事件。
public struct ServerEvent: Decodable, Equatable, Sendable {
    public var cursor: Int64
    /// `message.created` / `mood.changed` / `presence.changed`（将来可能更多）。
    public var type: String
    public var at: Int64
    public var sessionId: String?
    public var personaId: String?
    /// 类型相关的对象（`{message:{…}}` 等）。用值树保留未知形状，避免为每种事件加一个类型。
    public var payload: JSONValue?

    public init(
        cursor: Int64,
        type: String,
        at: Int64 = 0,
        sessionId: String? = nil,
        personaId: String? = nil,
        payload: JSONValue? = nil
    ) {
        self.cursor = cursor
        self.type = type
        self.at = at
        self.sessionId = sessionId
        self.personaId = personaId
        self.payload = payload
    }

    /// 便于消费 `payload.message` / `payload.mood`。
    public var payloadObject: [String: JSONValue]? {
        if case .object(let value)? = payload { return value }
        return nil
    }
}

/// `GET /v1/events` 的响应。`events` 为空表示长轮询超时，此时 `cursor` 原样返回。
public struct ServerEventsPage: Decodable, Equatable, Sendable {
    public var serverTime: Int64
    public var cursor: Int64
    public var events: [ServerEvent]

    public init(serverTime: Int64 = 0, cursor: Int64 = 0, events: [ServerEvent] = []) {
        self.serverTime = serverTime
        self.cursor = cursor
        self.events = events
    }

    private enum CodingKeys: String, CodingKey { case serverTime, cursor, events }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        serverTime = try container.decodeIfPresent(Int64.self, forKey: .serverTime) ?? 0
        cursor = try container.decodeIfPresent(Int64.self, forKey: .cursor) ?? 0
        events = try container.decodeIfPresent([ServerEvent].self, forKey: .events) ?? []
    }
}

// MARK: - 发送消息的流式事件

/// `done` 事件里的 token 用量。
public struct ServerChatUsage: Codable, Equatable, Sendable {
    public var promptTokens: Int
    public var completionTokens: Int

    public init(promptTokens: Int = 0, completionTokens: Int = 0) {
        self.promptTokens = max(promptTokens, 0)
        self.completionTokens = max(completionTokens, 0)
    }

    private enum CodingKeys: String, CodingKey { case promptTokens, completionTokens }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        promptTokens = max(try container.decodeIfPresent(Int.self, forKey: .promptTokens) ?? 0, 0)
        completionTokens = max(try container.decodeIfPresent(Int.self, forKey: .completionTokens) ?? 0, 0)
    }
}

/// `POST /v1/sessions/{id}/messages` 的 SSE 事件。
///
/// 顺序是 `accepted` → `delta*` → `tool*`（可选）→ `done`；任何阶段都可能以 `error` 结束。
/// 未知事件（将来的 `ping` 之类）由客户端**静默忽略**，不进入这个枚举。
public enum ServerChatEvent: Equatable, Sendable {
    case accepted(userMessageId: String, createdAt: Int64)
    case delta(text: String)
    case tool(name: String, status: String, summary: String?)
    case done(assistantMessageId: String, usage: ServerChatUsage)
    case error(ServerAPIError)

    /// SSE 的 `event:` 名字，便于日志/测试（不包含任何正文）。
    public var type: String {
        switch self {
        case .accepted: return "accepted"
        case .delta: return "delta"
        case .tool: return "tool"
        case .done: return "done"
        case .error: return "error"
        }
    }
}

/// `POST /v1/sessions/{id}/messages` 的请求体。
///
/// 字段名与契约一致；`replyTo` 为空时省略（服务端按缺省处理）。
public struct ServerSendMessageRequest: Codable, Equatable, Sendable {
    public var clientMessageId: String
    public var content: String
    public var attachments: [String]
    public var replyTo: String?

    public init(
        clientMessageId: String,
        content: String,
        attachments: [String] = [],
        replyTo: String? = nil
    ) {
        self.clientMessageId = clientMessageId
        self.content = content
        self.attachments = attachments
        self.replyTo = replyTo
    }

    private enum CodingKeys: String, CodingKey {
        case clientMessageId, content, attachments, replyTo
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(clientMessageId, forKey: .clientMessageId)
        try container.encode(content, forKey: .content)
        try container.encode(attachments, forKey: .attachments)
        try container.encodeIfPresent(replyTo, forKey: .replyTo)
    }
}
