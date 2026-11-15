import Foundation

// ---------------------------------------------------------------------------
// 服务端快照 → 界面可直接消费的首页/聊天页状态（P1a 的纯函数层）。
//
// 这一层刻意不碰网络与数据库：
// - 输入是 `ServerAPIClient` 解出来的只读模型；
// - 输出是 `ServerHomeState` / `ServerChatState`；
// - 同一份输入永远得到同一份输出，`swift test` 不需要模拟器、不需要起服务端。
//
// 为什么不让界面直接拿 `ServerStateSnapshot`：
// 1. 首页只想要「会话行 + 预览」，聊天页只想要「当前块 + 两端还有没有」，把筛选/排序/
//    预览文本散在 SwiftUI 里，P1b 换界面时就会漏掉一处；
// 2. 预览文本与本地模式 `ChatService.refreshPreview` 的口径（换行变空格、去首尾空白、
//    截断 100 字）必须一致，否则同一段对话在「本地模式 / 服务端模式」下显示不同；
// 3. 服务端 `hasMoreMessages` 是**全局**截断标记（首次同步所有会话各取最新一块时，
//    只要有一个会话还有更早的块它就是 true），映射到具体会话的 `hasMoreOlder` 只能是
//    「保守地认为可能还有」；P1b 打开聊天页后应改用消息分页接口返回的精确标记。
// ---------------------------------------------------------------------------

/// 首页一行的会话摘要（服务端版本）。
public struct ServerSessionSummary: Equatable, Sendable {
    public var id: String
    public var personaId: String
    public var title: String
    public var unread: Int
    public var lastMessageAt: Int64?
    /// 由快照里该会话最新一条消息推导；没有消息时为 nil。
    public var lastMessagePreview: String?
    public var lastBlockIndex: Int
    public var messageCount: Int64
    public var createdAt: Int64
    public var updatedAt: Int64

    public init(
        id: String,
        personaId: String,
        title: String = "",
        unread: Int = 0,
        lastMessageAt: Int64? = nil,
        lastMessagePreview: String? = nil,
        lastBlockIndex: Int = -1,
        messageCount: Int64 = 0,
        createdAt: Int64 = 0,
        updatedAt: Int64 = 0
    ) {
        self.id = id
        self.personaId = personaId
        self.title = title
        self.unread = unread
        self.lastMessageAt = lastMessageAt
        self.lastMessagePreview = lastMessagePreview
        self.lastBlockIndex = lastBlockIndex
        self.messageCount = messageCount
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    /// 排序口径与本地首页 `SessionDigest.sortAt` 一致：说过话按最后一句时间，没说过的排最后。
    public var sortAt: Int64 { lastMessageAt ?? 0 }
}

/// 首页状态（服务端版本）。
public struct ServerHomeState: Equatable, Sendable {
    public var serverTime: Int64
    public var serverCursor: Int64
    public var personas: [ServerPersona]
    public var sessions: [ServerSessionSummary]
    public var mood: ServerMood?
    public var presence: ServerPresence?
    /// 首次同步时消息被截断（聊天页要用分页接口继续翻更早的块）。
    public var hasMoreMessages: Bool

    public init(
        serverTime: Int64 = 0,
        serverCursor: Int64 = 0,
        personas: [ServerPersona] = [],
        sessions: [ServerSessionSummary] = [],
        mood: ServerMood? = nil,
        presence: ServerPresence? = nil,
        hasMoreMessages: Bool = false
    ) {
        self.serverTime = serverTime
        self.serverCursor = serverCursor
        self.personas = personas
        self.sessions = sessions
        self.mood = mood
        self.presence = presence
        self.hasMoreMessages = hasMoreMessages
    }

    /// 默认人格（服务端 `isDefault`）。没有标记时返回第一个人格，保持服务端顺序。
    public var defaultPersonaId: String? {
        personas.first(where: { $0.isDefault })?.id ?? personas.first?.id
    }

    public func session(id: String) -> ServerSessionSummary? {
        sessions.first(where: { $0.id == id })
    }
}

/// 聊天页当前窗口（服务端版本）。
public struct ServerChatState: Equatable, Sendable {
    public var sessionId: String
    /// 当前块号；空会话/翻过头为 nil。
    public var blockIndex: Int?
    public var messages: [ServerMessage]
    public var hasMoreOlder: Bool
    public var hasMoreNewer: Bool

    public init(
        sessionId: String,
        blockIndex: Int? = nil,
        messages: [ServerMessage] = [],
        hasMoreOlder: Bool = false,
        hasMoreNewer: Bool = false
    ) {
        self.sessionId = sessionId
        self.blockIndex = blockIndex
        self.messages = messages
        self.hasMoreOlder = hasMoreOlder
        self.hasMoreNewer = hasMoreNewer
    }
}

public enum ServerChatSync {

    /// 预览文本截断长度：与 `ChatService.refreshPreview` 的 100 字一致。
    public static let previewLimit = 100

    // MARK: - 首页

    /// 状态快照 → 首页状态。
    ///
    /// 会话要在这里排序：`GET /v1/state` 的 `sessions` 是存储里的插入顺序，**不是**最近说话顺序
    /// （`GET /v1/sessions` 才排过）；首页如果直接照抄，就会把半个月没说话的会话排在最上面。
    /// 排序口径与本地首页一致：`sortAt` 倒序，其次 `createdAt` 倒序，最后 id 倒序（UTF-16，
    /// 与 SQLite 的 `ORDER BY … id DESC` 和 Kotlin `compareTo` 同序）。
    public static func homeState(from snapshot: ServerStateSnapshot) -> ServerHomeState {
        let latestBySession = latestMessagesBySession(snapshot.messages)
        let sessions = snapshot.sessions.map { session -> ServerSessionSummary in
            let latest = latestBySession[session.id]
            return ServerSessionSummary(
                id: session.id,
                personaId: session.personaId,
                title: session.title,
                unread: session.unread,
                lastMessageAt: session.lastMessageAt ?? latest?.createdAt,
                lastMessagePreview: latest.flatMap { previewText($0) },
                lastBlockIndex: session.lastBlockIndex,
                messageCount: session.messageCount,
                createdAt: session.createdAt,
                updatedAt: session.updatedAt
            )
        }.sorted(by: homeOrder)
        return ServerHomeState(
            serverTime: snapshot.serverTime,
            serverCursor: snapshot.serverCursor,
            personas: snapshot.personas,
            sessions: sessions,
            mood: snapshot.mood,
            presence: snapshot.presence,
            hasMoreMessages: snapshot.hasMoreMessages
        )
    }

    // MARK: - 聊天页

    /// 状态快照 → 某个会话的聊天页状态。
    ///
    /// 首次同步（`since=0`）时快照里每个会话只有最新一块，所以这里把 `hasMoreMessages`
    /// 当作 `hasMoreOlder` 的保守值、`hasMoreNewer` 恒为 false（最新块后面没有更多）。
    /// 增量快照（`since>0`）不应该拿来**替换**已加载窗口 —— 它只含新消息，P1b 应做合并；
    /// 这里仍按输入原样返回，方便单测与调试。
    public static func chatState(
        from snapshot: ServerStateSnapshot,
        sessionId: String
    ) -> ServerChatState {
        let messages = sortedMessages(snapshot.messages.filter { $0.sessionId == sessionId })
        let session = snapshot.sessions.first(where: { $0.id == sessionId })
        let blockIndex: Int? = {
            if let last = session?.lastBlockIndex, last >= 0 { return last }
            return messages.last?.blockIndex
        }()
        return ServerChatState(
            sessionId: sessionId,
            blockIndex: blockIndex,
            messages: messages,
            hasMoreOlder: snapshot.hasMoreMessages,
            hasMoreNewer: false
        )
    }

    /// 消息分页响应 → 聊天页状态。这是「翻更早/更新」路径上的精确映射。
    public static func chatState(from page: ServerMessagePage) -> ServerChatState {
        let messages = sortedMessages(page.messages)
        let blockIndex = page.blockIndex ?? messages.last?.blockIndex
        return ServerChatState(
            sessionId: page.sessionId,
            blockIndex: blockIndex,
            messages: messages,
            hasMoreOlder: page.hasMoreOlder,
            hasMoreNewer: page.hasMoreNewer
        )
    }

    /// 某个会话在快照里的消息（升序）。
    public static func messages(
        from snapshot: ServerStateSnapshot,
        sessionId: String
    ) -> [ServerMessage] {
        sortedMessages(snapshot.messages.filter { $0.sessionId == sessionId })
    }

    // MARK: - 预览文本

    /// 一条消息的首页预览。
    ///
    /// 口径与本地 `ChatService.refreshPreview` 一致：换行变空格、去首尾空白、截断 100 字。
    /// 空正文（例如只有图片）返回 nil，由界面决定显示什么占位。
    public static func previewText(_ message: ServerMessage) -> String? {
        previewText(message.content, limit: previewLimit)
    }

    public static func previewText(_ content: String, limit: Int = previewLimit) -> String? {
        let flattened = content
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !flattened.isEmpty, limit > 0 else { return nil }
        guard flattened.count > limit else { return flattened }
        return String(flattened.prefix(limit))
    }

    // MARK: - 内部

    /// 首页会话排序：最近说话 > 创建更晚 > id 更大。空会话 `sortAt = 0` 自然沉底。
    private static func homeOrder(_ lhs: ServerSessionSummary, _ rhs: ServerSessionSummary) -> Bool {
        if lhs.sortAt != rhs.sortAt { return lhs.sortAt > rhs.sortAt }
        if lhs.createdAt != rhs.createdAt { return lhs.createdAt > rhs.createdAt }
        return Lexicographic.utf16Less(rhs.id, lhs.id)
    }

    private static func latestMessagesBySession(_ messages: [ServerMessage]) -> [String: ServerMessage] {
        var result: [String: ServerMessage] = [:]
        for message in messages {
            if let existing = result[message.sessionId] {
                if precedes(existing, message) { result[message.sessionId] = message }
            } else {
                result[message.sessionId] = message
            }
        }
        return result
    }

    private static func sortedMessages(_ messages: [ServerMessage]) -> [ServerMessage] {
        messages.sorted(by: precedes)
    }

    /// `(createdAt, id)` 升序，`id` 用 UTF-16 码元序 —— 与本地
    /// `MessageBlocks.precedes`、服务端 `compareBy({it.createdAt},{it.id})` 一致。
    private static func precedes(_ lhs: ServerMessage, _ rhs: ServerMessage) -> Bool {
        if lhs.createdAt != rhs.createdAt { return lhs.createdAt < rhs.createdAt }
        return Lexicographic.utf16Less(lhs.id, rhs.id)
    }
}
