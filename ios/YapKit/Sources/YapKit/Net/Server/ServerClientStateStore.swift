import Foundation

// ---------------------------------------------------------------------------
// 服务端模式的**客户端侧簿记**（M3/M4）：同步游标 + 待发队列。
//
// 两张表（`Schema.serverSyncStateTable` / `Schema.serverOutboxTable`）都不进备份、
// 也不同步给服务端：它们是「这台设备把服务端的状态缓存到哪儿了」与
// 「这台设备还有哪句话没发出去」。服务端一旦收到消息就已经是权威，
// 设备换一台时这两份数据没有任何价值（游标重新从 0 拉一次即可）。
//
// 为什么都放 SQLite 而不是 UserDefaults：
// 1. **游标必须和数据同一个事务提交**。`ServerSyncService` 把「upsert 消息/会话/人格」
//    与「写游标」包在一个 `inTransaction` 里：要么一起成，要么一起不成。若游标在
//    UserDefaults（另一个存储），进程在两者之间被杀掉就会出现「游标前进、数据没进库」
//    → 那段历史永远不会再拉一次（服务端只按 since 增量返回）。
// 2. **待发队列必须熬过杀进程**，并且「发成功 → 出队」与「消息落库」顺序可控。
//
// 与本地模式的边界：这两张表只有服务端模式会写；本地模式下它们一直是空的，
// 备份/清空数据也不会因为多了两张表而改变任何用户可见行为（`DataReset` 里显式清）。
// ---------------------------------------------------------------------------

/// 服务端模式的一张 key-value 簿记表（`server_sync_state`）。
///
/// 键空间（都带前缀，避免以后加键时撞名）：
/// - `server.cursor`：`GET /v1/state` 与 `GET /v1/events` 共用的增量游标；
/// - `server.lastSyncAt`：上一次同步成功的本机时间（界面上的「刚同步过」）；
/// - `presence.<personaId>`：服务端下发的在场快照（JSON，本地没有 presence 表）；
/// - `mood.<personaId>`：服务端下发的心情快照（JSON，与 `personas.mood*` 列互为备份）。
public final class ServerSyncStateStore {

    /// 键名集中在这里：写错一个字符就是「游标永远从 0 开始」，而且不会报错。
    public enum Key {
        public static let cursor = "server.cursor"
        public static let lastSyncAt = "server.lastSyncAt"
        public static func presence(_ personaId: String) -> String { "presence.\(personaId)" }
        public static func mood(_ personaId: String) -> String { "mood.\(personaId)" }
        /// 某个本地会话「已经拉到服务端第几块」（翻更早的块时要用服务端的块号）。
        public static func block(_ sessionId: String) -> String { "server.block.\(sessionId)" }
        /// 服务端说这个会话上面还有没有更早的块（"1" / "0"）。
        public static func hasOlder(_ sessionId: String) -> String { "server.hasOlder.\(sessionId)" }

        // ---- 0.12.1：首次全量同步（bootstrap）的簿记 ----
        //
        // 完成标记按 **baseURL** 分开：换一台服务端就是「另一段关系」，必须重新搬一次。
        // 三个 id 映射是给「服务端不接受本机 id、回落成新建」准备的：没有它，重试会
        // 在服务端上再建一份同一个人/同一条记忆（服务端幂等只按 id，不按内容）。
        /// 某台服务端是否做过首次全量同步（JSON：时间/方向/条数）。
        public static func bootstrapDone(_ baseURL: String) -> String {
            "server_bootstrap_done:\(baseURL)"
        }
        /// 首次全量同步没做完时的进度（JSON；重试从这里接着跑）。
        public static func bootstrapProgress(_ baseURL: String) -> String {
            "server_bootstrap_progress:\(baseURL)"
        }
        /// 本地人格 id → 服务端人格 id（服务端另建了 id 时才有这一行）。
        public static func personaIdMap(_ localPersonaId: String) -> String {
            "server.idmap.persona.\(localPersonaId)"
        }
        /// 本地预设 id → 服务端预设 id。
        public static func presetIdMap(_ localPresetId: String) -> String {
            "server.idmap.preset.\(localPresetId)"
        }
        /// 本地记忆 id → 服务端记忆 id。
        public static func memoryIdMap(_ localMemoryId: String) -> String {
            "server.idmap.memory.\(localMemoryId)"
        }
    }

    private let database: SQLiteDatabase

    public init(database: SQLiteDatabase) {
        self.database = database
    }

    // MARK: 读

    /// 读一个键（不存在 / 读失败都返回 nil）。
    public func value(_ key: String) throws -> String? {
        let statement = try database.prepare(
            "SELECT value FROM \(Schema.serverSyncStateTable) WHERE key = ? LIMIT 1"
        )
        try statement.bind([.text(key)])
        guard try statement.step() else { return nil }
        return statement.text(at: 0)
    }

    /// 读一个键，失败当成「没有」——同步/事件循环里不该因为一次读失败就停摆。
    public func string(_ key: String) -> String? {
        (try? value(key)) ?? nil
    }

    /// 当前游标。读不到（新装 / 表刚建）就是 0：服务端按「首次同步」返回最新块。
    public func cursor() -> Int64 {
        Int64(string(Key.cursor) ?? "") ?? 0
    }

    /// 上一次同步成功的时间（毫秒），没有就是 nil。
    public func lastSyncAt() -> Int64? {
        Int64(string(Key.lastSyncAt) ?? "")
    }

    // MARK: 写

    /// 写一个键（`value == nil` 等于删除）。
    ///
    /// 调用方通常已经在 `inTransaction` 里（和一批数据 upsert 一起），
    /// 所以这里**不自己开事务**：SQLite 的事务状态是连接级的，嵌套会退化为外层。
    public func set(_ value: String?, for key: String, at timestamp: Int64) throws {
        if let value {
            try database.executeUpdate(
                """
                INSERT OR REPLACE INTO \(Schema.serverSyncStateTable) (key, value, updatedAt)
                VALUES (?, ?, ?)
                """,
                [.text(key), .text(value), .int(timestamp)]
            )
        } else {
            try database.executeUpdate(
                "DELETE FROM \(Schema.serverSyncStateTable) WHERE key = ?",
                [.text(key)]
            )
        }
    }

    /// 推进游标。**只能前进**：一个乱序到达的旧响应不该把游标推回去，
    /// 否则下一次同步会重复拉已经处理过的增量（幂等所以不致命，但白花流量）。
    @discardableResult
    public func advanceCursor(to value: Int64, at timestamp: Int64) throws -> Int64 {
        let current = cursor()
        let next = max(current, value)
        guard next != current else { return current }
        try set(String(next), for: Key.cursor, at: timestamp)
        return next
    }

    /// 清空（清空全部数据 / 从服务端重新全量拉时用）。
    public func deleteAll() throws {
        try database.executeUpdate("DELETE FROM \(Schema.serverSyncStateTable)")
    }
}

// MARK: - 待发队列

/// 一条「还没发出去」的用户消息。
///
/// `clientMessageId` 就是幂等键：服务端按它去重，所以**同一条消息重发不会变成两句**。
/// 这也是「离线排队 → 恢复后补发」敢用「重试整条」而不是「查询发到哪一步」的原因。
public struct ServerOutboxEntry: Equatable, Sendable {
    public var clientMessageId: String
    public var sessionId: String
    /// 冗余存 personaId：补发时即使会话已被删，也能在日志/界面里说清是谁的消息。
    public var personaId: String?
    public var content: String
    public var attachments: [String]
    public var replyTo: String?
    public var createdAt: Int64
    /// 已经尝试过几次（指数退避用它决定下一次的等待时间）。
    public var attempts: Int
    public var lastError: String?

    public init(
        clientMessageId: String,
        sessionId: String,
        personaId: String? = nil,
        content: String,
        attachments: [String] = [],
        replyTo: String? = nil,
        createdAt: Int64 = 0,
        attempts: Int = 0,
        lastError: String? = nil
    ) {
        self.clientMessageId = clientMessageId
        self.sessionId = sessionId
        self.personaId = personaId
        self.content = content
        self.attachments = attachments
        self.replyTo = replyTo
        self.createdAt = createdAt
        self.attempts = attempts
        self.lastError = lastError
    }

    /// 界面上「还没发出去」那一条的正文是否算空（只有附件）。
    public var hasVisibleContent: Bool {
        !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

/// 待发队列的读写（`server_outbox`）。
public final class ServerOutboxStore {

    private let database: SQLiteDatabase

    public init(database: SQLiteDatabase) {
        self.database = database
    }

    // MARK: 写

    /// 入队（同 id 覆盖：重复入队是幂等的）。
    public func enqueue(_ entry: ServerOutboxEntry) throws {
        try database.executeUpdate(
            """
            INSERT OR REPLACE INTO \(Schema.serverOutboxTable)
                (clientMessageId, sessionId, personaId, content, attachmentsJson, replyTo,
                 createdAt, attempts, lastError)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                .text(entry.clientMessageId),
                .text(entry.sessionId),
                entry.personaId.map { SQLiteValue.text($0) } ?? .null,
                .text(entry.content),
                .text(Self.encodeAttachments(entry.attachments)),
                entry.replyTo.map { SQLiteValue.text($0) } ?? .null,
                .int(entry.createdAt),
                .int(Int64(entry.attempts)),
                entry.lastError.map { SQLiteValue.text($0) } ?? .null
            ]
        )
    }

    /// 记一次失败（次数 +1、错误截断保存）。返回新的尝试次数。
    @discardableResult
    public func recordFailure(clientMessageId: String, error: String) throws -> Int {
        try database.executeUpdate(
            """
            UPDATE \(Schema.serverOutboxTable)
            SET attempts = attempts + 1, lastError = ?
            WHERE clientMessageId = ?
            """,
            [.text(String(error.prefix(300))), .text(clientMessageId)]
        )
        return try entry(clientMessageId: clientMessageId)?.attempts ?? 0
    }

    /// 出队（发送成功 / 用户撤回 / 会话已删）。
    public func remove(clientMessageId: String) throws {
        try database.executeUpdate(
            "DELETE FROM \(Schema.serverOutboxTable) WHERE clientMessageId = ?",
            [.text(clientMessageId)]
        )
    }

    public func deleteAll() throws {
        try database.executeUpdate("DELETE FROM \(Schema.serverOutboxTable)")
    }

    /// 某个会话的待发条目删掉（会话被删时；消息已经无处可发）。
    public func remove(sessionId: String) throws {
        try database.executeUpdate(
            "DELETE FROM \(Schema.serverOutboxTable) WHERE sessionId = ?",
            [.text(sessionId)]
        )
    }

    // MARK: 读

    /// 全部待发条目（按入队顺序，补发按这个顺序发）。
    public func all() -> [ServerOutboxEntry] {
        (try? fetch(where: nil)) ?? []
    }

    /// 某个会话的待发条目（聊天页要把它们当「还没发出去的本地消息」显示出来）。
    public func entries(sessionId: String) -> [ServerOutboxEntry] {
        (try? fetch(where: "sessionId = ?", [.text(sessionId)])) ?? []
    }

    public func entry(clientMessageId: String) throws -> ServerOutboxEntry? {
        try fetch(where: "clientMessageId = ?", [.text(clientMessageId)]).first
    }

    public func count() -> Int {
        (try? database.scalarInt64("SELECT COUNT(*) FROM \(Schema.serverOutboxTable)")) .map(Int.init) ?? 0
    }

    // MARK: 内部

    private func fetch(where clause: String?, _ arguments: [SQLiteValue] = []) throws -> [ServerOutboxEntry] {
        var sql = """
            SELECT clientMessageId, sessionId, personaId, content, attachmentsJson, replyTo,
                   createdAt, attempts, lastError
            FROM \(Schema.serverOutboxTable)
            """
        if let clause, !clause.isEmpty { sql += " WHERE \(clause)" }
        sql += " ORDER BY createdAt ASC, clientMessageId ASC"
        let statement = try database.prepare(sql)
        try statement.bind(arguments)
        var rows: [ServerOutboxEntry] = []
        while try statement.step() {
            rows.append(ServerOutboxEntry(
                clientMessageId: statement.text(at: 0) ?? "",
                sessionId: statement.text(at: 1) ?? "",
                personaId: statement.text(at: 2),
                content: statement.text(at: 3) ?? "",
                attachments: Self.decodeAttachments(statement.text(at: 4)),
                replyTo: statement.text(at: 5),
                createdAt: statement.int64(at: 6),
                attempts: Int(statement.int64(at: 7)),
                lastError: statement.text(at: 8)
            ))
        }
        return rows
    }

    /// 附件 id 列表按 JSON 数组存（与 `messages.attachmentsJson` 同一形状）。
    ///
    /// 坏 JSON 当空数组：队列里的一个坏字段不该让整条消息发不出去。
    static func encodeAttachments(_ attachments: [String]) -> String {
        guard !attachments.isEmpty else { return "[]" }
        guard let data = try? JSONSerialization.data(withJSONObject: attachments),
              let text = String(data: data, encoding: .utf8) else { return "[]" }
        return text
    }

    static func decodeAttachments(_ raw: String?) -> [String] {
        guard let raw, !raw.isEmpty,
              let data = raw.data(using: .utf8),
              let values = try? JSONSerialization.jsonObject(with: data) as? [String] else { return [] }
        return values
    }
}
