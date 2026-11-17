import Foundation

// ---------------------------------------------------------------------------
// 服务端 → 本地缓存的**状态同步**（M4 的基础，任务书第 2 条）。
//
// 分工：
// - `ServerSyncMapping`：纯函数，服务端模型 → 本地实体（可单测，不碰库/网络/时钟）；
// - `ServerSyncService`：拉 `/v1/state`（带 `since=cursor`），把映射结果 + 新游标
//   **放在同一个事务里**写进 SQLite，失败按指数退避重试。
//
// 三条关键设计：
// 1. **游标与数据同事务**（见 `ServerSyncStateStore` 的注释）：
//    「游标前进了但数据没进库」会永久丢一段历史，所以两者必须一起 COMMIT。
// 2. **幂等**：`INSERT OR REPLACE` + 服务端 id 就是本地 id，同一批数据重复应用结果不变
//    （重试用同一份快照也不会有副作用）。
// 3. **合并而不是覆盖**：本地实体有很多「只有本机知道」的字段（`apiConfigId`、摘要进度、
//    `pinned`、翻译缓存……）。映射时以本地既有行为底稿，只改服务端真正权威的那几列，
//    否则一次同步就会把用户在本机的编辑（钉住、翻译缓存）冲掉。
//
// 本地模式不受影响：没有任何 `ServerSyncService` 调用时，这些表一直是空的，
// 界面照旧从本地 `ChatService` 取数。
// ---------------------------------------------------------------------------

// MARK: - 服务端下发的「活状态」

/// 服务端权威的「他此刻的心情 / 在做什么」（M4）。
///
/// 本地模式这两件事是**推导**出来的（`MoodRepository` / `PresenceRepository` 从痕迹算），
/// 服务端模式下它们是**下发**的：服务端那边才是真正跑着主动引擎的那一侧。
/// 两者都不落成独立的表：心情写回 `personas.mood*` 列（界面已经在读它），
/// 在场快照写进 `server_sync_state` 的 `presence.<personaId>`（本地没有 presence 表，
/// 也不想为一份缓存加一张表）。
public struct ServerLiveState: Equatable, Sendable {
    public var mood: ServerMood?
    public var presence: ServerPresence?
    public var updatedAt: Int64

    public init(mood: ServerMood? = nil, presence: ServerPresence? = nil, updatedAt: Int64 = 0) {
        self.mood = mood
        self.presence = presence
        self.updatedAt = updatedAt
    }

    public var isEmpty: Bool { mood == nil && presence == nil }

    /// 界面要显示的心情（服务端权威，本地不再按时间冲淡）。
    ///
    /// 本地模式下 `MoodRepository.current` 会「顺手让时间冲淡」并写库；服务端模式下
    /// 心情由服务端下发，客户端不应该拿本机的衰减规则去改它（那会和服务端的判定打架）。
    public var moodForDisplay: MoodState? {
        guard let mood, !mood.label.isEmpty else { return nil }
        return MoodState(
            label: mood.label,
            intensity: mood.intensity > 0 ? mood.intensity : 2,
            reason: "",
            updatedAt: mood.updatedAt
        )
    }

    /// 界面要显示的「他此刻在做什么」。
    ///
    /// 服务端只下发 `kind` + 一句 `detail`（第三人称，例如「在家，安静地待着」），
    /// 所以直接用那句话当界面文案；`kind` 用来选图标/措辞档位。
    public var presenceForDisplay: Presence? {
        guard let presence else { return nil }
        let detail = presence.detail.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !detail.isEmpty else { return nil }
        let kind = PresenceKind(rawValue: presence.kind.uppercased()) ?? .idle
        return Presence(
            kind: kind,
            text: detail,
            detail: detail,
            sinceAt: presence.updatedAt,
            updatedAt: presence.updatedAt
        )
    }
}

// MARK: - 纯映射

public enum ServerSyncMapping {

    /// 服务端人格 → 本地人格。
    ///
    /// 只覆盖服务端**真正有**的四列（`name` / `description` / `updatedAt` / `isDefault`）：
    /// 其余几十列（提示词、作息、世界、话风、翻译缓存、`apiConfigId`）服务端没有下发，
    /// 用本地既有值原样保留；本地也没有记录时用实体默认值。
    public static func persona(
        from server: ServerPersona,
        existing: PersonaEntity?,
        now: Int64
    ) -> PersonaEntity {
        var record = existing ?? PersonaEntity(id: server.id, name: server.name)
        record.id = server.id
        record.name = server.name
        record.description = server.description
        record.isDefault = server.isDefault
        record.updatedAt = server.updatedAt > 0 ? server.updatedAt : now
        if record.createdAt == 0 { record.createdAt = server.createdAt > 0 ? server.createdAt : now }
        // 头像：服务端下发的是 `/v1/media/...` 这样的**服务端路径**，本地 `avatarUri`
        // 是沙盒里的 `file://`。两者不是同一个东西，硬塞进去会让界面去 decode 一个不存在的文件。
        // 这一版刻意不映射头像（见交付报告的「没做到」）。
        return record
    }

    /// 服务端心情 → 覆盖人格的心情四列（界面已经在读它们，不需要新表）。
    public static func applyingMood(
        _ mood: ServerMood,
        to persona: PersonaEntity
    ) -> PersonaEntity {
        var record = persona
        record.mood = mood.label.isEmpty ? record.mood : mood.label
        if mood.intensity > 0 { record.moodIntensity = mood.intensity }
        record.moodUpdatedAt = mood.updatedAt > 0 ? mood.updatedAt : record.moodUpdatedAt
        return record
    }

    /// 服务端会话 → 本地会话。
    ///
    /// 本地字段（`apiConfigId` / `model` / `pinned` / 摘要进度 / `syncState`）全部保留：
    /// 服务端下发的会话在客户端是**缓存**，这些列描述的仍然是本机怎么用它。
    /// `serverSessionId` 记下「这条本地会话对应服务端的哪一个」，写路径用它。
    public static func session(
        from server: ServerSession,
        existing: ChatSessionEntity?,
        now: Int64
    ) -> ChatSessionEntity {
        var record = existing ?? ChatSessionEntity(id: server.id, personaId: server.personaId)
        record.id = server.id
        record.personaId = server.personaId
        record.title = server.title
        record.unreadCount = max(server.unread, 0)
        record.lastMessageAt = server.lastMessageAt
        record.createdAt = server.createdAt > 0 ? server.createdAt : record.createdAt
        record.updatedAt = server.updatedAt > 0 ? server.updatedAt : now
        record.serverSessionId = server.id
        return record
    }

    /// 服务端消息 → 本地消息。
    ///
    /// - 块号：**不让服务端的 `blockIndex` 覆盖本地**。本地的块是
    ///   `MessageBlockStore` 按自己的规则维护的派生数据（写入顺序 + 块大小），
    ///   塞服务端的块号会让 `messages.blockIndex` 与 `message_blocks` 不自洽
    ///   （`inconsistencies()` 会立刻报出来）。服务端的块号只在**翻页请求**里用
    ///   （`ServerChatGateway.loadBlock`），不落库。
    /// - 附件：服务端下发 `["/v1/media/<id>"]`，本地 `attachmentsJson` 的 `uri` 存同一个
    ///   相对路径；渲染走 `ServerMediaCache`（带 Authorization、按显示尺寸解码），
    ///   不再写 M4 的 `server:<id>` + 非图片 mime 占位。
    public static func message(
        from server: ServerMessage,
        existing: MessageEntity?,
        personaId: String,
        now: Int64,
        mediaMeta: ServerMediaMetaStore? = nil
    ) -> MessageEntity {
        var record = existing ?? MessageEntity(
            id: server.id,
            sessionId: server.sessionId,
            personaId: personaId,
            role: .assistant,
            content: server.content
        )
        record.id = server.id
        record.sessionId = server.sessionId
        if !personaId.isEmpty { record.personaId = personaId }
        record.role = MessageRole(stored: server.role)
        record.content = server.content
        record.createdAt = server.createdAt > 0 ? server.createdAt : (existing?.createdAt ?? now)
        record.updatedAt = record.createdAt
        record.status = .complete
        record.readAt = server.readAt
        record.attachmentsJson = attachmentsJson(
            from: server.attachments, mediaMeta: mediaMeta
        ) ?? record.attachmentsJson
        return record
    }

    /// 服务端附件 → 本地 `attachmentsJson`。空列表返回 nil（= 没有附件）。
    ///
    /// 服务端下发的形状是 `["/v1/media/<id>"]`（契约 §3.6）；本地 `attachmentsJson`
    /// 的 `uri` 就存这个相对路径，渲染时由 `ServerMediaCache` 带 Authorization 取。
    /// `server:<id>` 是 M4 的旧形状，读到也认（`ServerMediaReference` 统一解析）。
    public static func attachmentsJson(
        from attachments: [String],
        mediaMeta: ServerMediaMetaStore? = nil
    ) -> String? {
        let items = attachments.compactMap { raw -> MessageAttachment? in
            // 服务端下发/我们入队时可能给裸 mediaId，也可能给 `/v1/media/<id>`（契约两种都收）；
            // 本地一律存标准相对路径，界面只认这一种。
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return nil }
            let id: String
            if let parsed = ServerMediaReference.mediaId(from: trimmed) {
                id = parsed
            } else {
                guard !trimmed.contains("/"), !trimmed.contains("\\") else { return nil }
                id = trimmed
            }
            // 0.13：语音/视频的 kind/mime/时长在服务端不下发，靠上传时记下的元信息补齐。
            if let enriched = mediaMeta?.attachment(mediaId: id) { return enriched }
            return MessageAttachment(uri: ServerMediaReference.uri(id: id))
        }
        return AttachmentsCodec.encode(items)
    }

    /// 会话列表里那一行预览：与本地 `ChatService.refreshPreview` 同一口径
    /// （`ServerChatSync.previewText`），这样两种模式下首页长得一样。
    public static func preview(from message: ServerMessage?) -> String? {
        guard let message else { return nil }
        return ServerChatSync.previewText(message)
    }
}

// MARK: - 同步结果

/// 一次同步的结果（界面/日志用；测试也断言它）。
public struct ServerSyncReport: Equatable, Sendable {
    public var cursor: Int64
    /// 这次从服务端拿到的、真正写进缓存的条数。
    public var personas: Int
    public var sessions: Int
    public var messages: Int
    /// 补拉最新块的会话数（首次同步 / 服务端说「消息被截断」时）。
    public var fetchedBlocks: Int
    public var moodUpdated: Bool
    public var presenceUpdated: Bool
    /// 是不是「首次同步」（`since == 0`）。
    public var firstSync: Bool
    public var at: Int64

    public init(
        cursor: Int64 = 0,
        personas: Int = 0,
        sessions: Int = 0,
        messages: Int = 0,
        fetchedBlocks: Int = 0,
        moodUpdated: Bool = false,
        presenceUpdated: Bool = false,
        firstSync: Bool = false,
        at: Int64 = 0
    ) {
        self.cursor = cursor
        self.personas = personas
        self.sessions = sessions
        self.messages = messages
        self.fetchedBlocks = fetchedBlocks
        self.moodUpdated = moodUpdated
        self.presenceUpdated = presenceUpdated
        self.firstSync = firstSync
        self.at = at
    }
}

public enum ServerSyncOutcome: Equatable, Sendable {
    /// 服务端模式没开 / 没有地址：什么都不做（本地模式照旧）。
    case skipped(reason: String)
    /// 拉到了并写进缓存。
    case synced(ServerSyncReport)
    /// 重试之后仍然失败。`attempts` 是实际尝试次数，`message` 是给日志/界面的一句话。
    case failed(message: String, attempts: Int)
}

// MARK: - 同步服务

/// 状态同步器：启动 / 回前台 / 事件流空闲时调一次 `syncNow()`。
///
/// 为什么是 `actor`：同一时刻只该有一次同步在跑（两次并发同步会各自拿着旧游标、
/// 各自写游标，最后一个写的赢——虽然幂等，但白跑一遍网络，还会让 `fetchedBlocks`
/// 的计数翻倍）。actor 隔离 + 一个 `inFlight` 标志把「正在同步」这件事说清楚。
public actor ServerSyncService {

    /// 失败重试策略。`attempts = 1` 表示不重试（只试一次）。
    public struct RetryPolicy: Equatable, Sendable {
        public var maxAttempts: Int
        /// 第一次重试前等多久；之后每次翻倍（0.5s → 1s → 2s …）。
        public var baseDelaySeconds: Double
        public var maxDelaySeconds: Double

        public init(
            maxAttempts: Int = 3,
            baseDelaySeconds: Double = 0.5,
            maxDelaySeconds: Double = 8
        ) {
            self.maxAttempts = max(1, maxAttempts)
            self.baseDelaySeconds = max(0, baseDelaySeconds)
            self.maxDelaySeconds = max(self.baseDelaySeconds, maxDelaySeconds)
        }

        /// 第 `attempt` 次失败之后要等多久（`attempt` 从 1 开始）。
        public func delay(after attempt: Int) -> Double {
            guard attempt >= 1 else { return 0 }
            let raw = baseDelaySeconds * pow(2, Double(attempt - 1))
            return min(raw, maxDelaySeconds)
        }
    }

    private let client: ServerAPIClient
    private let stores: DatabaseStores
    private let state: ServerSyncStateStore
    /// 服务端媒体 id → 附件元信息（0.13：语音/视频同步回来要知道自己是什么）。
    private let mediaMeta: ServerMediaMetaStore
    /// 服务端会话 id ⇄ 本地会话 id 的换算（见 `ServerSessionResolver` 的注释）。
    private let sessions: ServerSessionResolver
    private let now: () -> Int64
    private let log: (String) -> Void
    private let retry: RetryPolicy
    /// 可注入的「等一会儿」（测试里换成立即返回，不让单测真的睡 8 秒）。
    private let sleeper: (Double) async -> Void

    private var inFlight = false
    /// 上一次拿到的活状态（心情/在场）。界面订阅不到 actor，所以每次同步后回调一次。
    private var live: ServerLiveState

    public init(
        client: ServerAPIClient,
        stores: DatabaseStores,
        state: ServerSyncStateStore? = nil,
        retry: RetryPolicy = RetryPolicy(),
        now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        log: @escaping (String) -> Void = { _ in },
        sleeper: @escaping (Double) async -> Void = { seconds in
            guard seconds > 0 else { return }
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
        }
    ) {
        self.client = client
        self.stores = stores
        self.state = state ?? ServerSyncStateStore(database: stores.database)
        self.mediaMeta = ServerMediaMetaStore(database: stores.database)
        self.sessions = ServerSessionResolver(stores: stores)
        self.retry = retry
        self.now = now
        self.log = log
        self.sleeper = sleeper
        self.live = ServerLiveState()
    }

    // MARK: 读

    /// 当前游标（`GET /v1/state` 的 `since`）。
    public func cursor() -> Int64 { state.cursor() }

    /// 上一次同步成功的本机时间。
    public func lastSyncAt() -> Int64? { state.lastSyncAt() }

    /// 最近一次同步得到的心情 / 在场（进程内缓存）。
    public func liveState() -> ServerLiveState { live }

    // MARK: 同步

    /// 同步一次。
    ///
    /// - Parameters:
    ///   - fillLatestBlocks: 首次同步（`since == 0`）或服务端说「消息被截断」时，
    ///     逐个会话补拉**最新块**。为什么需要：`/v1/state` 的首次快照只带每个会话的
    ///     最新一块，若那个块自己都不完整（服务端按块截断），补拉一次才能保证
    ///     「打开会话立刻有内容」。
    ///   - force: 忽略「正在同步」标志（测试/手动重试用；生产路径不要传）。
    public func syncNow(
        fillLatestBlocks: Bool = true,
        force: Bool = false
    ) async -> ServerSyncOutcome {
        guard client.config.isUsable else {
            return .skipped(reason: "服务端模式未启用")
        }
        if inFlight && !force { return .skipped(reason: "已经有一次同步在跑") }
        inFlight = true
        defer { inFlight = false }

        let since = state.cursor()
        var attempt = 0
        var lastMessage = ""
        while attempt < retry.maxAttempts {
            attempt += 1
            do {
                let snapshot = try await client.state(since: since)
                let report = try await apply(snapshot, since: since, fillLatestBlocks: fillLatestBlocks)
                return .synced(report)
            } catch is CancellationError {
                return .failed(message: "同步被取消", attempts: attempt)
            } catch {
                lastMessage = Self.describe(error)
                log("服务端同步失败（第 \(attempt)/\(retry.maxAttempts) 次）：\(lastMessage)")
                guard attempt < retry.maxAttempts else { break }
                await sleeper(retry.delay(after: attempt))
            }
        }
        return .failed(message: lastMessage, attempts: attempt)
    }

    /// 把一份快照写进缓存（公开给测试与「事件流拿到一整页」的场景）。
    @discardableResult
    public func apply(
        _ snapshot: ServerStateSnapshot,
        since: Int64? = nil,
        fillLatestBlocks: Bool = false
    ) async throws -> ServerSyncReport {
        let at = now()
        let firstSync = (since ?? state.cursor()) == 0
        let personaBySession = Dictionary(
            snapshot.sessions.map { ($0.id, $0.personaId) },
            uniquingKeysWith: { first, _ in first }
        )

        // 首次同步 / 服务端说还有更早的块：补拉每个会话的**最新块**。
        // 这里用**服务端 id**（请求服务端当然要用它），落库时再换算成本地 id。
        var blocks: [String: [ServerMessage]] = [:]
        var fetchedBlocks = 0
        if fillLatestBlocks && (firstSync || snapshot.hasMoreMessages) {
            for session in snapshot.sessions where session.lastBlockIndex >= 0 {
                do {
                    let page = try await client.messages(sessionId: session.id)
                    blocks[session.id] = page.messages
                    fetchedBlocks += 1
                } catch let error as ServerAPIError where error.isNotConfiguredOrDisabled {
                    // 服务端模式被关掉/地址没了：不再继续拉，已拿到的快照照样落库。
                    break
                } catch {
                    // 单个会话补拉失败不影响其它会话：快照里已经有它最新的一块。
                    log("补拉会话 \(session.id) 的最新块失败：\(Self.describe(error))")
                }
            }
        }

        // 消息先按 (createdAt, id) 升序：本地块是按写入顺序切的，乱序写会让块边界错乱。
        var messages = snapshot.messages
        for list in blocks.values { messages += list }
        messages = deduplicated(messages).sorted { lhs, rhs in
            if lhs.createdAt != rhs.createdAt { return lhs.createdAt < rhs.createdAt }
            return lhs.id < rhs.id
        }

        var report = ServerSyncReport(
            cursor: snapshot.serverCursor,
            fetchedBlocks: fetchedBlocks,
            firstSync: firstSync,
            at: at
        )

        try stores.database.inTransaction {
            // 1) 人格（先建人格，消息/会话才有对应的 personaId）
            var personaIds = Set<String>()
            for serverPersona in snapshot.personas {
                let existing = (try? stores.personas.byId(serverPersona.id)) ?? nil
                let record = ServerSyncMapping.persona(from: serverPersona, existing: existing, now: at)
                try stores.personas.upsert(record)
                personaIds.insert(serverPersona.id)
            }
            report.personas = personaIds.count

            // 2) 会话：`ServerSessionResolver` 负责「本地那条同人格会话」与服务端会话的合并，
            //    绝不允许因为唯一索引冲突把本机会话（连同它的历史）冲掉。
            var localSessionIds = Set<String>()
            for serverSession in snapshot.sessions {
                let localId = try sessions.localSessionId(forServer: serverSession, at: at)
                localSessionIds.insert(localId)
            }
            report.sessions = localSessionIds.count

            // 3) 消息：服务端的 sessionId 要换算成本地 id（缓存与界面都按本地 id 找）。
            var writtenMessages = 0
            for serverMessage in messages {
                guard let localId = resolveLocalSessionId(for: serverMessage, snapshot: snapshot) else {
                    // 会话既不在快照里、本机也没有：跳过（下一次 /v1/state 补齐后再写）。
                    continue
                }
                let personaId = personaBySession[serverMessage.sessionId]
                    ?? ((try? stores.sessions.byId(localId)) ?? nil)?.personaId
                    ?? ""
                let existing = (try? stores.messages.byId(serverMessage.id)) ?? nil
                var record = ServerSyncMapping.message(
                    from: serverMessage, existing: existing, personaId: personaId, now: at,
                    mediaMeta: mediaMeta
                )
                record.sessionId = localId
                try stores.messages.upsert(record)
                writtenMessages += 1
            }
            report.messages = writtenMessages

            // 4) 会话预览（拿快照里每个会话的最后一条）
            try refreshSessionPreviews(messages: messages)

            // 5) 心情 → `personas.mood*` 列；在场 → `server_sync_state`
            if let mood = snapshot.mood {
                if let existing = (try? stores.personas.byId(mood.personaId)) ?? nil {
                    let updated = ServerSyncMapping.applyingMood(mood, to: existing)
                    try stores.personas.upsert(updated)
                    report.moodUpdated = true
                }
                try state.set(Self.encodeJSON(mood), for: ServerSyncStateStore.Key.mood(mood.personaId), at: at)
            }
            if let presence = snapshot.presence {
                try state.set(
                    Self.encodeJSON(presence),
                    for: ServerSyncStateStore.Key.presence(presence.personaId),
                    at: at
                )
                report.presenceUpdated = true
            }
            live = ServerLiveState(
                mood: snapshot.mood ?? live.mood,
                presence: snapshot.presence ?? live.presence,
                updatedAt: at
            )

            // 6) 游标（与上面同一事务：见文件头注释）
            try state.advanceCursor(to: snapshot.serverCursor, at: at)
            try state.set(String(at), for: ServerSyncStateStore.Key.lastSyncAt, at: at)
            report.cursor = state.cursor()
        }

        log("服务端同步完成：人格 \(report.personas) / 会话 \(report.sessions) / 消息 \(report.messages)"
            + "，游标 \(report.cursor)\(firstSync ? "（首次）" : "")")
        return report
    }

    // MARK: 内部

    /// 同一批快照里可能有重复消息（快照 + 补拉的块会重叠）。按 id 去重，保留后出现的
    /// （补拉的那一份可能更新，例如 `readAt` 已经被服务端改过）。
    private func deduplicated(_ messages: [ServerMessage]) -> [ServerMessage] {
        var seen: [String: Int] = [:]
        var result: [ServerMessage] = []
        for message in messages {
            if let index = seen[message.id] {
                result[index] = message
            } else {
                seen[message.id] = result.count
                result.append(message)
            }
        }
        return result
    }

    /// 用这批消息里「每个会话的最后一条」刷新会话预览与最后时间。
    ///
    /// 为什么不用 `lastMessagePreview` 的本地算法重算整段：增量同步只带来几条新消息，
    /// 从这里能拿到的最新一条就是对的；而重算整段需要把整段历史读出来（O(n) 每次同步）。
    private func refreshSessionPreviews(messages: [ServerMessage]) throws {
        var latestBySession: [String: ServerMessage] = [:]
        for message in messages {
            if let existing = latestBySession[message.sessionId] {
                if message.createdAt > existing.createdAt { latestBySession[message.sessionId] = message }
            } else {
                latestBySession[message.sessionId] = message
            }
        }
        for (serverSessionId, message) in latestBySession {
            // 预览挂在**本地**会话上（服务端 id 只用于换算）。
            let localId = sessions.existingLocalSessionId(serverSessionId: serverSessionId)
                ?? (try? sessions.localSessionId(
                    forServer: ServerSession(id: serverSessionId, personaId: ""), at: now()
                ))
                ?? serverSessionId
            guard var session = (try? stores.sessions.byId(localId)) ?? nil else { continue }
            guard let preview = ServerSyncMapping.preview(from: message) else { continue }
            session.lastMessagePreview = preview
            session.lastMessageAt = max(session.lastMessageAt ?? 0, message.createdAt)
            try stores.sessions.upsert(session)
        }
    }

    /// 一条服务端消息该落到哪个本地会话。
    ///
    /// 顺序：本地已有映射 → 快照里这个会话出现了（先建/认领它）→ nil（跳过）。
    private func resolveLocalSessionId(
        for message: ServerMessage,
        snapshot: ServerStateSnapshot
    ) -> String? {
        if let localId = sessions.existingLocalSessionId(serverSessionId: message.sessionId) {
            return localId
        }
        guard let serverSession = snapshot.sessions.first(where: { $0.id == message.sessionId }) else {
            return nil
        }
        return try? sessions.localSessionId(forServer: serverSession, at: now())
    }

    static func encodeJSON<T: Encodable>(_ value: T) -> String? {
        guard let data = try? JSONEncoder().encode(value) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// 给日志/界面的一句话（`ServerAPIError` 自带中文 `message`）。
    static func describe(_ error: Error) -> String {
        if let server = error as? ServerAPIError { return server.message }
        if error is CancellationError { return "已取消" }
        return error.localizedDescription
    }
}

// MARK: - 小工具

extension ServerAPIError {
    /// 「服务端模式没配/被关掉」——这类错误不该重试（配置问题不是网络抖动）。
    var isNotConfiguredOrDisabled: Bool {
        code == ServerAPIError.Code.disabled || code == ServerAPIError.Code.notConfigured
    }
}
