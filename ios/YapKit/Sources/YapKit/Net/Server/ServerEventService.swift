import Foundation

// ---------------------------------------------------------------------------
// 服务端主动消息 / 状态变化的**前台事件订阅**（任务书第 4 条）。
//
// 契约（`docs/design/server-authoritative.md` §3.4）：`GET /v1/events?since=<cursor>`
// 长轮询，最多等 25 秒；空轮询时 `cursor` **原样返回**（客户端不能因此前移游标）。
// 事件三类：
// - `message.created` → `{message:{…}}`：他主动开口（或别处发来的消息）；
// - `mood.changed`    → `{mood:{…}}`；
// - `presence.changed`→ `{presence:{…}}`。
//
// 这一层做三件事，别的都不做：
// 1. 把新消息**写进本地缓存**（界面下次读库就能看到，不需要另开一条数据通道）；
// 2. 把「有新消息」交给注入的通知器（App 层复用现有 `UNUserNotificationCenter` 逻辑）；
// 3. 更新心情/在场的活状态（界面订阅）。
//
// 为什么事件也走「缓存 → 界面读库」而不是直接推给界面：服务端模式下聊天页/首页读的
// 都是本地缓存（`ChatService` 的查询、`HomeView` 的会话行）。事件是**增量来源**，
// 若它另走一条内存通道，同一个会话就会有两个真相（库里旧、内存里新），
// 一旦界面重建就退回去了。
// ---------------------------------------------------------------------------

/// 一条「他发来消息」的通知请求。
///
/// 刻意只带**展示需要**的字段（标题/正文/会话 id）：通知在锁屏上可见，
/// 不该把整条消息对象（含 token、附件路径）递出去。
public struct ServerIncomingNotification: Equatable, Sendable {
    public var personaId: String
    public var sessionId: String
    /// 首页/通知标题用的人名；拿不到时为空串（通知器自己回退）。
    public var title: String
    /// 已经压平换行、截断过的正文（通知正文不适合多行大段）。
    public var body: String
    public var at: Int64
    /// 服务端此前的未读数（通知里可以说「还有 2 条」）。
    public var unread: Int

    public init(
        personaId: String,
        sessionId: String,
        title: String,
        body: String,
        at: Int64,
        unread: Int = 0
    ) {
        self.personaId = personaId
        self.sessionId = sessionId
        self.title = title
        self.body = body
        self.at = at
        self.unread = unread
    }
}

/// 通知器的注入面（App 层用现有通知中心实现；测试里换成计数用的假实现）。
public protocol ServerEventNotifying: AnyObject, Sendable {
    /// 有新消息进缓存时调一次。实现方自己决定「前台/正在这个会话」时不弹。
    func notifyIncoming(_ notification: ServerIncomingNotification) async
}

/// 什么都不做的通知器（默认值：单测、Kit 自己跑、用户关掉通知时都用它）。
public final class NoServerNotifications: ServerEventNotifying {
    public init() {}
    public func notifyIncoming(_ notification: ServerIncomingNotification) async {}
}

// MARK: - 单次轮询的结果

public struct ServerEventPollReport: Equatable, Sendable {
    /// 处理完之后客户端应保存的游标（没有事件时与之前相同）。
    public var cursor: Int64
    public var newMessages: [ServerMessage]
    public var moodChanged: Bool
    public var presenceChanged: Bool
    /// 这次轮询里 `typing.started` / `typing.stopped`（或 `message.created` 收起输入）
    /// 改变了哪个会话的输入状态；界面据此立刻刷新「正在输入…」。
    public var typingChanged: Bool
    /// 这次长轮询是不是空手而归（服务端等满了 25 秒）。
    public var timedOutEmpty: Bool

    public init(
        cursor: Int64 = 0,
        newMessages: [ServerMessage] = [],
        moodChanged: Bool = false,
        presenceChanged: Bool = false,
        typingChanged: Bool = false,
        timedOutEmpty: Bool = false
    ) {
        self.cursor = cursor
        self.newMessages = newMessages
        self.moodChanged = moodChanged
        self.presenceChanged = presenceChanged
        self.typingChanged = typingChanged
        self.timedOutEmpty = timedOutEmpty
    }
}

/// 事件订阅器。
///
/// 用 `actor` 的理由与 `ServerSyncService` 相同：游标是单一写入点，
/// 两个并发的长轮询各自「从同一个 since 开始」会把同一批事件处理两遍（通知会重复弹）。
public actor ServerEventService {

    private let client: ServerAPIClient
    private let stores: DatabaseStores
    private let state: ServerSyncStateStore
    /// 服务端媒体 id → 附件元信息（0.13：语音/视频同步回来要知道自己是什么）。
    private let mediaMeta: ServerMediaMetaStore
    /// 服务端会话 id ⇄ 本地会话 id（事件里带的是服务端的 id）。
    private let sessions: ServerSessionResolver
    private let notifier: ServerEventNotifying
    /// 界面是不是正开着某个会话（开着就不弹通知：用户已经在看）。
    private let isSessionOpen: @Sendable (String) -> Bool
    /// 现在是不是前台。后台时**不弹**本地通知（那是另一套后台策略的事，
    /// 前台事件流的职责只是把数据补进缓存）。
    private let isForeground: @Sendable () -> Bool
    private let now: () -> Int64
    private let log: (String) -> Void
    /// 活状态变化的回调（心情/在场/输入中）。在 actor 内部同步调用，实现方自己要 hop 到主线程。
    private let onLiveState: (@Sendable (ServerLiveState) -> Void)?
    /// 一轮轮询处理完的回调（App 层据此立刻刷新聊天页：新消息直接上屏、收起输入指示）。
    /// 在 actor 内部同步调用，实现方自己要 hop 到主线程。
    private let onEventReport: (@Sendable (ServerEventPollReport) -> Void)?

    private var live = ServerLiveState()
    private var running = false
    /// 上一次轮询失败后的退避秒数（成功即清零）。
    private var failureBackoff = 0.0

    public init(
        client: ServerAPIClient,
        stores: DatabaseStores,
        state: ServerSyncStateStore? = nil,
        notifier: ServerEventNotifying = NoServerNotifications(),
        isSessionOpen: @escaping @Sendable (String) -> Bool = { _ in false },
        isForeground: @escaping @Sendable () -> Bool = { true },
        onLiveState: (@Sendable (ServerLiveState) -> Void)? = nil,
        onEventReport: (@Sendable (ServerEventPollReport) -> Void)? = nil,
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.client = client
        self.stores = stores
        self.state = state ?? ServerSyncStateStore(database: stores.database)
        self.mediaMeta = ServerMediaMetaStore(database: stores.database)
        self.sessions = ServerSessionResolver(stores: stores)
        self.notifier = notifier
        self.isSessionOpen = isSessionOpen
        self.isForeground = isForeground
        self.onLiveState = onLiveState
        self.onEventReport = onEventReport
        self.now = now
        self.log = log
    }

    public func liveState() -> ServerLiveState { live }

    public func cursor() -> Int64 { state.cursor() }

    // MARK: 单次轮询

    /// 长轮询一次（最多在服务端等 `timeoutSeconds` 秒），把事件写进缓存并通知。
    ///
    /// 空轮询（`timedOutEmpty = true`）时**不动游标**：服务端说了「没有新事件」，
    /// 游标应当保持不变，下一次接着问同一段。
    public func pollOnce(timeoutSeconds: Int? = nil, limit: Int? = nil) async throws -> ServerEventPollReport {
        guard client.config.isUsable else {
            throw ServerAPIError(code: ServerAPIError.Code.disabled, message: "服务端模式已关闭")
        }
        let since = state.cursor()
        let page = try await client.events(since: since, timeoutSeconds: timeoutSeconds, limit: limit)
        guard !page.events.isEmpty else {
            return ServerEventPollReport(cursor: since, timedOutEmpty: true)
        }

        var report = ServerEventPollReport(cursor: since)
        let at = now()
        // 事件里带的消息可能没有会话/人格（服务端只保证 message 有 sessionId），
        // 这里先把会话表读一份进内存：一个轮询页最多几十条，一次查询够用。
        var notifications: [ServerIncomingNotification] = []

        try stores.database.inTransaction {
            for event in page.events {
                switch event.type {
                case ServerEventType.messageCreated:
                    // 0.14.1 §8：一条事件可能带多条消息（服务端连发），**按服务端给的顺序**
                    // 落库；界面收到回调后一次重读，看到的就是同一顺序。
                    for serverMessage in Self.messages(from: event) {
                        // 这条事件属于哪个本地会话？本机没有对应会话就跳过（不凭空造空会话）。
                        guard let localSessionId = sessions.existingLocalSessionId(
                            serverSessionId: serverMessage.sessionId
                        ) else { continue }
                        let message = serverMessage
                        let personaId = event.personaId
                            ?? ((try? stores.sessions.byId(localSessionId)) ?? nil)?.personaId
                            ?? ""
                        let existing = (try? stores.messages.byId(message.id)) ?? nil
                        var record = ServerSyncMapping.message(
                            from: message, existing: existing, personaId: personaId, now: at,
                            mediaMeta: mediaMeta
                        )
                        record.sessionId = localSessionId
                        try stores.messages.upsert(record)
                        if var session = ((try? stores.sessions.byId(localSessionId)) ?? nil) {
                            // 未读只在「他说的 + 用户没开着这个会话」时 +1：用户正看着聊天页，
                            // 再涨未读只会让首页多出一个点不掉的红点。
                            if message.isAssistant && !isSessionOpen(localSessionId) {
                                session.unreadCount += 1
                            }
                            if let preview = ServerChatSync.previewText(message) {
                                session.lastMessagePreview = preview
                            }
                            session.lastMessageAt = max(session.lastMessageAt ?? 0, message.createdAt)
                            session.updatedAt = max(session.updatedAt, at)
                            try stores.sessions.upsert(session)
                        }
                        report.newMessages.append(message)
                        if let notification = Self.notification(
                            for: message,
                            localSessionId: localSessionId,
                            personaId: personaId,
                            now: at,
                            stores: stores
                        ) {
                            notifications.append(notification)
                        }
                        // 「消息到了」就等于「不再输入了」：哪怕服务端的 typing.stopped
                        // 晚到/丢了，输入指示也必须立刻收起（规范 §8）。
                        if live.typing.removeValue(forKey: localSessionId) != nil {
                            report.typingChanged = true
                        }
                    }
                case ServerEventType.typingStarted:
                    // 只有前台且服务端真的推了 typing 事件才显示（规范 §8）。
                    // 离线期间落库的消息不会走这里，所以同步回来的历史不会补打字动画。
                    guard isForeground(),
                          let localSessionId = Self.typingSessionId(
                              from: event,
                              sessions: sessions
                          ) else { continue }
                    live.typing[localSessionId] = at
                    live.updatedAt = at
                    report.typingChanged = true
                case ServerEventType.typingStopped:
                    guard let localSessionId = Self.typingSessionId(
                        from: event,
                        sessions: sessions
                    ) else { continue }
                    if live.typing.removeValue(forKey: localSessionId) != nil {
                        live.updatedAt = at
                        report.typingChanged = true
                    }
                case ServerEventType.moodChanged:
                    if let mood = Self.mood(from: event) {
                        if let existing = (try? stores.personas.byId(mood.personaId)) ?? nil {
                            try stores.personas.upsert(ServerSyncMapping.applyingMood(mood, to: existing))
                        }
                        try state.set(
                            ServerSyncService.encodeJSON(mood),
                            for: ServerSyncStateStore.Key.mood(mood.personaId),
                            at: at
                        )
                        live.mood = mood
                        report.moodChanged = true
                    }
                case ServerEventType.presenceChanged:
                    if let presence = Self.presence(from: event) {
                        try state.set(
                            ServerSyncService.encodeJSON(presence),
                            for: ServerSyncStateStore.Key.presence(presence.personaId),
                            at: at
                        )
                        live.presence = presence
                        live.updatedAt = at
                        report.presenceChanged = true
                    }
                default:
                    // 未知事件类型（服务端以后加的）：忽略，但**游标照样前进**
                    // （否则下次还会拿到它，游标永远卡在这一条上）。
                    continue
                }
            }
            // 游标 = 本页最后一条事件的 cursor（与消息落库同一事务）。
            let lastCursor = page.events.map(\.cursor).max() ?? since
            try state.advanceCursor(to: max(lastCursor, page.cursor), at: at)
            report.cursor = state.cursor()
        }

        if report.moodChanged || report.presenceChanged || report.typingChanged {
            live.updatedAt = at
            onLiveState?(live)
        }
        if report.moodChanged || report.presenceChanged || report.typingChanged
            || !report.newMessages.isEmpty {
            onEventReport?(report)
        }

        // 通知放在事务外：通知中心是异步的，让它跟着数据库事务一起回滚没有意义。
        for notification in notifications {
            guard isForeground() else { continue }
            guard !isSessionOpen(notification.sessionId) else { continue }
            await notifier.notifyIncoming(notification)
        }
        return report
    }

    // MARK: 长轮询循环

    /// 前台订阅循环：一直长轮询到取消为止。
    ///
    /// 失败退避：0.5s → 1s → 2s → 4s（上限 8s）；成功一次就清零。
    /// 为什么不用「固定 1 秒重试」：服务端挂掉时 1 秒一次的长轮询会把电池和流量一起打掉。
    public func runLoop(
        timeoutSeconds: Int? = 25,
        maxBackoffSeconds: Double = 8
    ) async {
        guard !running else { return }
        running = true
        defer { running = false }

        while !Task.isCancelled {
            do {
                _ = try await pollOnce(timeoutSeconds: timeoutSeconds)
                failureBackoff = 0
            } catch is CancellationError {
                return
            } catch {
                if let server = error as? ServerAPIError, server.isNotConfiguredOrDisabled { return }
                failureBackoff = failureBackoff == 0 ? 0.5 : min(failureBackoff * 2, maxBackoffSeconds)
                log("服务端事件订阅失败（\(Int(failureBackoff * 1000))ms 后重试）："
                    + ServerSyncService.describe(error))
                try? await Task.sleep(nanoseconds: UInt64(failureBackoff * 1_000_000_000))
            }
        }
    }

    public func stop() {
        running = false
    }

    /// 清空「正在输入」（进后台 / 停事件流时调）。
    ///
    /// 为什么必须清：typing 是**瞬时**状态。App 进后台再回来时，服务端可能早已把
    /// 那批消息投递完了；如果本地还留着 `typing.started`，用户会看到一条离线消息
    /// 前面凭空演一次「正在输入…」—— 正是规范 §8 明确禁止的「补打字动画」。
    public func clearTyping() {
        guard !live.typing.isEmpty else { return }
        live.typing.removeAll()
        live.updatedAt = now()
        onLiveState?(live)
    }

    // MARK: 事件 → 模型

    /// 三类事件的名字（服务端 `StoreEventType`）。
    public enum ServerEventType {
        public static let messageCreated = "message.created"
        public static let moodChanged = "mood.changed"
        public static let presenceChanged = "presence.changed"
        /// 0.14.1 §8：服务端入队后先发 `typing.started`，到 `visibleAt` 发
        /// `message.created`，全部发完发 `typing.stopped`。
        public static let typingStarted = "typing.started"
        public static let typingStopped = "typing.stopped"
    }

    /// 从 `{message:{…}}` / `{messages:[…]}` 解出这一条事件里的消息；形状不对就跳过
    /// 这一条而不是整页失败。顺序保持服务端给的顺序（多条连发必须按序上屏）。
    static func messages(from event: ServerEvent) -> [ServerMessage] {
        guard let object = event.payloadObject else { return [] }
        if let messageValue = object["message"] {
            return decode(ServerMessage.self, from: messageValue).map { [$0] } ?? []
        }
        if case .array(let values)? = object["messages"] {
            return values.compactMap { decode(ServerMessage.self, from: $0) }
        }
        return []
    }

    /// 兼容旧调用：一条事件只带一条消息时取第一条。
    static func message(from event: ServerEvent) -> ServerMessage? {
        messages(from: event).first
    }

    /// typing 事件属于哪个本地会话：事件顶层的 `sessionId`（或 payload 里的）是服务端 id。
    static func typingSessionId(
        from event: ServerEvent,
        sessions: ServerSessionResolver
    ) -> String? {
        var raw = event.sessionId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if raw.isEmpty, let object = event.payloadObject {
            if case .string(let value)? = object["sessionId"] { raw = value }
        }
        guard !raw.isEmpty else { return nil }
        return sessions.existingLocalSessionId(serverSessionId: raw) ?? raw
    }

    static func mood(from event: ServerEvent) -> ServerMood? {
        guard let object = event.payloadObject, let value = object["mood"] else { return nil }
        return decode(ServerMood.self, from: value)
    }

    static func presence(from event: ServerEvent) -> ServerPresence? {
        guard let object = event.payloadObject, let value = object["presence"] else { return nil }
        return decode(ServerPresence.self, from: value)
    }

    /// `JSONValue` → 目标模型：把值树写回 JSON 文本再用 `JSONDecoder` 解。
    ///
    /// 为什么不写三份手取值代码：这三个模型都是 `Codable`，而且服务端 JSON 形状就是
    /// 它们（`ServerModels` 逐个字段对齐过）。走一趟序列化只多几百字节，换来「只有一套
    /// 字段映射」，以后服务端加字段不需要在三个地方补。
    ///
    /// `JSONValue` 只有 `Decodable` 合成实现，所以这里手写一个「值树 → Foundation 对象」
    /// 的转换（`JSONValue.plainObject`），再交给 `JSONSerialization`。
    static func decode<T: Decodable>(_ type: T.Type, from value: JSONValue) -> T? {
        guard let data = value.jsonData() else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    /// 一条消息要不要弹通知、弹什么。
    ///
    /// 只通知**他说的**（`ASSISTANT`）：用户自己发的消息在别的设备上会以 `USER` 事件出现，
    /// 给用户弹「你发了一条消息」显然是错的。`SYSTEM` 也不弹。
    static func notification(
        for message: ServerMessage,
        localSessionId: String,
        personaId: String,
        now: Int64,
        stores: DatabaseStores
    ) -> ServerIncomingNotification? {
        guard message.isAssistant else { return nil }
        let persona = (try? stores.personas.byId(personaId)) ?? nil
        let session = (try? stores.sessions.byId(localSessionId)) ?? nil
        let body = ServerChatSync.previewText(message.content, limit: 120) ?? ""
        guard !body.isEmpty else { return nil }
        let unread = session?.unreadCount ?? 0
        return ServerIncomingNotification(
            personaId: personaId,
            sessionId: localSessionId,
            title: session?.title.isEmpty == false ? (session?.title ?? "") : (persona?.name ?? ""),
            body: body,
            at: message.createdAt > 0 ? message.createdAt : now,
            unread: unread
        )
    }
}

// MARK: - 值树 → JSON 字节

extension JSONValue {
    /// 把值树转成 JSON 字节（只给「值树 → Codable 模型」这一条路用）。
    ///
    /// 坏值（NaN/Infinity、非字符串键）返回 nil：调用方按「这条事件读不懂就跳过」处理，
    /// 不让一条坏事件把整页事件丢掉。
    func jsonData() -> Data? {
        guard JSONSerialization.isValidJSONObject(plainObject) else { return nil }
        return try? JSONSerialization.data(withJSONObject: plainObject)
    }

    /// Foundation 可序列化的等价对象（`NSNull` / `NSNumber` / `String` / `Array` / `Dictionary`）。
    var plainObject: Any {
        switch self {
        case .null: return NSNull()
        case .bool(let value): return value
        case .int(let value): return NSNumber(value: value)
        case .double(let value): return NSNumber(value: value)
        case .string(let value): return value
        case .array(let items): return items.map { $0.plainObject }
        case .object(let fields): return fields.mapValues { $0.plainObject }
        }
    }
}
