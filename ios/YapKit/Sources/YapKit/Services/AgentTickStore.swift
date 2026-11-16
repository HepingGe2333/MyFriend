import Foundation

// ---------------------------------------------------------------------------
// 两张「Schema 里还没有」的表，以及它们在补上之前的退路。
//
// 为什么值得单独一个文件：
// - 这两张表是服务层里唯一「按 Android 语义需要、但 iOS 的 Schema 还没建」的东西。
//   把「表不在怎么办」收在一处，其它文件就不用到处 try/catch no such table；
// - 表补上以后，只需要改这里（以及 Schema.swift），上层服务一行都不用动。
//
// 退路的行为是明确的：**进程内内存实现**。功能在本次运行里是对的
//（心跳的「刚想过」、Presence 的「在想事情」、抽取的「第二次提到才落库」都照常工作），
// 只是重启后这两类留痕会丢。不会崩、也不会写坏别的表。
// ---------------------------------------------------------------------------

// MARK: - 他的一次「想」

/// 一次自主思考的留痕。与 Android `AgentTickEntity` 逐字段一致。
public struct AgentTick: Equatable, Sendable {
    public var id: String
    public var personaId: String
    public var at: Int64
    /// 心里那句话（截断 400 字）。
    public var thought: String
    /// 这一轮决策的 JSON（`AgentDecisionCodec.toJson` 的输出）。
    public var actionJson: String
    /// 这一轮做了什么的一句话（含心情的分寸）。
    public var outcome: String
    /// 这一轮真的发出了消息时的消息 id。
    public var messageId: String?

    public init(
        id: String = UUID().uuidString,
        personaId: String,
        at: Int64,
        thought: String = "",
        actionJson: String = "",
        outcome: String = "",
        messageId: String? = nil
    ) {
        self.id = id
        self.personaId = personaId
        self.at = at
        self.thought = thought
        self.actionJson = actionJson
        self.outcome = outcome
        self.messageId = messageId
    }
}

/// 思考日志的读写。心跳、自主循环、Presence 都只通过这个协议看它。
public protocol AgentTickStoring {
    func insert(_ tick: AgentTick)
    func latest(personaId: String) -> AgentTick?
    /// 所有人格里最近一次思考的时间（防止服务被反复拉起就连着想好几次）。
    func lastTickAt() -> Int64?
    /// 最近一次「真的开口了」（两条主动消息之间的最小间隔靠它）。
    func lastSpokenAt(personaId: String) -> Int64?
    /// 从 `since` 起主动开口了几次（每日上限）。
    func spokenCount(personaId: String, since: Int64) -> Int
    /// 倒序取最近几条（Presence 判断「刚翻过东西」）。
    func recent(personaId: String, limit: Int) -> [AgentTick]
    func deleteOlderThan(_ at: Int64)
    func deleteAll()
    /// 删掉一个人格的全部思考留痕（删人格时用；0.10.2 审计 P1-6）。
    func deleteAll(personaId: String)
}

/// 进程内实现：表还没建时用它，也用于单测。
public final class InMemoryAgentTickStore: AgentTickStoring {
    private let lock = NSLock()
    private var ticks: [AgentTick] = []

    public init() {}

    public func insert(_ tick: AgentTick) {
        lock.lock()
        ticks.append(tick)
        // 与 Android 的「最近 200 条就够」同一个意思：内存实现更该有上限。
        if ticks.count > 500 {
            ticks.removeFirst(ticks.count - 500)
        }
        lock.unlock()
    }

    public func latest(personaId: String) -> AgentTick? {
        lock.lock()
        defer { lock.unlock() }
        return ticks.filter { $0.personaId == personaId }.max { $0.at < $1.at }
    }

    public func lastTickAt() -> Int64? {
        lock.lock()
        defer { lock.unlock() }
        return ticks.map(\.at).max()
    }

    public func lastSpokenAt(personaId: String) -> Int64? {
        lock.lock()
        defer { lock.unlock() }
        return ticks.filter { $0.personaId == personaId && $0.messageId != nil }.map(\.at).max()
    }

    public func spokenCount(personaId: String, since: Int64) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return ticks.filter { $0.personaId == personaId && $0.messageId != nil && $0.at >= since }.count
    }

    public func recent(personaId: String, limit: Int) -> [AgentTick] {
        lock.lock()
        defer { lock.unlock() }
        return ticks
            .filter { $0.personaId == personaId }
            .sorted { a, b in a.at == b.at ? a.id > b.id : a.at > b.at }
            .prefix(max(limit, 0))
            .map { $0 }
    }

    public func deleteOlderThan(_ at: Int64) {
        lock.lock()
        ticks.removeAll { $0.at < at }
        lock.unlock()
    }

    public func deleteAll() {
        lock.lock()
        ticks.removeAll()
        lock.unlock()
    }

    public func deleteAll(personaId: String) {
        lock.lock()
        ticks.removeAll { $0.personaId == personaId }
        lock.unlock()
    }
}

/// `agent_ticks` 的 SQLite 实现。
///
/// 表不在时（理论上不会发生：DDL 已在 `Schema.createStatements` 里）自动退回内存实现，并且只提示一次。
/// 表建好之后，同一个对象下一次调用就会走真实的 SQL —— 不需要改任何上层代码。
public final class AgentTickStore: AgentTickStoring {

    private let database: SQLiteDatabase
    private let fallback = InMemoryAgentTickStore()
    private let lock = NSLock()
    private var tableAvailable: Bool?
    /// 是否已经提示过「表不在」。只在日志里说一次，不刷屏。
    public private(set) var didWarnMissingTable = false

    public init(database: SQLiteDatabase) {
        self.database = database
    }

    /// 现在走的是内存退路还是真表（测试与诊断用）。
    public var isUsingFallback: Bool { !available() }

    private func available() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if let tableAvailable { return tableAvailable }
        let exists = database.hasTable(MissingTables.agentTicks)
        tableAvailable = exists
        if !exists { didWarnMissingTable = true }
        return exists
    }

    public func insert(_ tick: AgentTick) {
        guard available() else {
            fallback.insert(tick)
            return
        }
        let sql = """
        INSERT OR REPLACE INTO agent_ticks (id, personaId, at, thought, actionJson, outcome, messageId)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """
        // 连接锁：这条 autocommit 写不能落进别人正在跑的事务里（0.10.2 审计 P0-1）。
        database.withConnectionLock {
            guard let statement = try? database.prepare(sql) else {
                fallback.insert(tick)
                return
            }
            try? statement.bind([
                .text(tick.id), .text(tick.personaId), .int(tick.at), .text(tick.thought),
                .text(tick.actionJson), .text(tick.outcome), .optionalText(tick.messageId)
            ])
            _ = try? statement.step()
        }
    }

    public func latest(personaId: String) -> AgentTick? {
        queryRows(
            sql: "SELECT * FROM agent_ticks WHERE personaId = ? ORDER BY at DESC LIMIT 1",
            arguments: [.text(personaId)]
        ).first
    }

    public func lastTickAt() -> Int64? {
        guard available() else { return fallback.lastTickAt() }
        return (try? database.scalarInt64("SELECT MAX(at) FROM agent_ticks")) ?? fallback.lastTickAt()
    }

    public func lastSpokenAt(personaId: String) -> Int64? {
        guard available() else { return fallback.lastSpokenAt(personaId: personaId) }
        let sql = "SELECT MAX(at) FROM agent_ticks WHERE personaId = ? AND messageId IS NOT NULL"
        guard let value = try? database.scalarInt64(sql, [.text(personaId)]), value > 0 else {
            return fallback.lastSpokenAt(personaId: personaId)
        }
        return value
    }

    public func spokenCount(personaId: String, since: Int64) -> Int {
        guard available() else { return fallback.spokenCount(personaId: personaId, since: since) }
        let sql = "SELECT COUNT(*) FROM agent_ticks WHERE personaId = ? AND messageId IS NOT NULL AND at >= ?"
        return Int((try? database.scalarInt64(sql, [.text(personaId), .int(since)])) ?? 0)
    }

    public func recent(personaId: String, limit: Int) -> [AgentTick] {
        queryRows(
            sql: "SELECT * FROM agent_ticks WHERE personaId = ? ORDER BY at DESC LIMIT ?",
            arguments: [.text(personaId), .int(limit)]
        )
    }

    public func deleteOlderThan(_ at: Int64) {
        guard available() else {
            fallback.deleteOlderThan(at)
            return
        }
        _ = try? database.executeUpdate("DELETE FROM agent_ticks WHERE at < ?", [.int(at)])
    }

    public func deleteAll() {
        fallback.deleteAll()
        guard available() else { return }
        _ = try? database.execute("DELETE FROM agent_ticks")
    }

    public func deleteAll(personaId: String) {
        fallback.deleteAll(personaId: personaId)
        guard available() else { return }
        _ = try? database.executeUpdate("DELETE FROM agent_ticks WHERE personaId = ?", [.text(personaId)])
    }

    private func queryRows(sql: String, arguments: [SQLiteValue]) -> [AgentTick] {
        guard available() else {
            // 内存退路：把同一条 SQL 的语义手写一遍（只有两个调用点，不值得抽一层查询语言）。
            switch sql {
            case let s where s.contains("LIMIT 1"):
                return fallback.latest(personaId: arguments.first?.stringValue ?? "").map { [$0] } ?? []
            default:
                let limit = arguments.last?.integerValue ?? 30
                return fallback.recent(personaId: arguments.first?.stringValue ?? "", limit: limit)
            }
        }
        guard let statement = try? database.prepare(sql) else { return [] }
        try? statement.bind(arguments)
        var rows: [AgentTick] = []
        while (try? statement.step()) == true {
            let row = SQLiteRow(statement: statement)
            rows.append(
                AgentTick(
                    id: (try? row.text("id")) ?? UUID().uuidString,
                    personaId: (try? row.text("personaId")) ?? "",
                    at: (try? row.int64("at")) ?? 0,
                    thought: (try? row.text("thought")) ?? "",
                    actionJson: (try? row.text("actionJson")) ?? "",
                    outcome: (try? row.text("outcome")) ?? "",
                    messageId: try? row.optionalText("messageId")
                )
            )
        }
        return rows
    }
}

private extension SQLiteValue {
    var stringValue: String? {
        if case .text(let value) = self { return value }
        return nil
    }

    var integerValue: Int? {
        if case .integer(let value) = self { return Int(value) }
        return nil
    }
}

// MARK: - 只被提到过一次的候选记忆

/// 一条「回声」：模型这次说的事，只被提到过一次。
///
/// 与 Android `MemoryEchoEntity` 逐字段一致。同一件事第二次被提到时，
/// `MemoryScreening.decide` 才会把它升级成正式记忆（见 `MemoryExtraction`）。
public struct MemoryEcho: Equatable, Sendable {
    public var id: String
    public var personaId: String
    public var drawer: String
    public var content: String
    public var importance: Int
    public var sourceQuote: String
    public var categoryName: String
    public var confidence: Float
    public var createdAt: Int64
    public var lastSeenAt: Int64

    public init(
        id: String = UUID().uuidString,
        personaId: String,
        drawer: String,
        content: String,
        importance: Int,
        sourceQuote: String = "",
        categoryName: String = "",
        confidence: Float = 0.6,
        createdAt: Int64,
        lastSeenAt: Int64
    ) {
        self.id = id
        self.personaId = personaId
        self.drawer = drawer
        self.content = content
        self.importance = importance
        self.sourceQuote = sourceQuote
        self.categoryName = categoryName
        self.confidence = confidence
        self.createdAt = createdAt
        self.lastSeenAt = lastSeenAt
    }
}

public protocol MemoryEchoStoring {
    func byPersona(_ personaId: String) -> [MemoryEcho]
    func insertAll(_ echoes: [MemoryEcho])
    /// 删掉一条回声（已经升级成正式记忆的那条）。
    func delete(id: String)
    /// 删掉一个人格的全部回声：`remember` 升级成功后与 Android 的 `deleteByPersona` 同语义。
    func deleteAll(personaId: String)
    func deleteAll()
}

public final class InMemoryMemoryEchoStore: MemoryEchoStoring {
    private let lock = NSLock()
    private var echoes: [MemoryEcho] = []

    public init() {}

    public func byPersona(_ personaId: String) -> [MemoryEcho] {
        lock.lock()
        defer { lock.unlock() }
        return echoes.filter { $0.personaId == personaId }
    }

    public func insertAll(_ newEchoes: [MemoryEcho]) {
        lock.lock()
        for echo in newEchoes {
            if let index = echoes.firstIndex(where: { $0.id == echo.id }) {
                echoes[index] = echo
            } else {
                echoes.append(echo)
            }
        }
        lock.unlock()
    }

    public func delete(id: String) {
        lock.lock()
        echoes.removeAll { $0.id == id }
        lock.unlock()
    }

    public func deleteAll(personaId: String) {
        lock.lock()
        echoes.removeAll { $0.personaId == personaId }
        lock.unlock()
    }

    public func deleteAll() {
        lock.lock()
        echoes.removeAll()
        lock.unlock()
    }
}

/// `memory_echoes` 的 SQLite 实现；表不在时退回内存（理由同 `AgentTickStore`）。
public final class MemoryEchoStore: MemoryEchoStoring {

    private let database: SQLiteDatabase
    private let fallback = InMemoryMemoryEchoStore()
    private let lock = NSLock()
    private var tableAvailable: Bool?

    public init(database: SQLiteDatabase) {
        self.database = database
    }

    public var isUsingFallback: Bool { !available() }

    private func available() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if let tableAvailable { return tableAvailable }
        let exists = database.hasTable(MissingTables.memoryEchoes)
        tableAvailable = exists
        return exists
    }

    public func byPersona(_ personaId: String) -> [MemoryEcho] {
        guard available() else { return fallback.byPersona(personaId) }
        let sql = "SELECT * FROM memory_echoes WHERE personaId = ? ORDER BY createdAt DESC"
        guard let statement = try? database.prepare(sql) else { return [] }
        try? statement.bind([.text(personaId)])
        var rows: [MemoryEcho] = []
        while (try? statement.step()) == true {
            let row = SQLiteRow(statement: statement)
            rows.append(
                MemoryEcho(
                    id: (try? row.text("id")) ?? UUID().uuidString,
                    personaId: (try? row.text("personaId")) ?? "",
                    drawer: (try? row.text("drawer")) ?? "USER",
                    content: (try? row.text("content")) ?? "",
                    importance: (try? row.int("importance")) ?? 3,
                    sourceQuote: (try? row.text("sourceQuote")) ?? "",
                    categoryName: (try? row.text("categoryName")) ?? "",
                    confidence: Float((try? row.double("confidence")) ?? 0.6),
                    createdAt: (try? row.int64("createdAt")) ?? 0,
                    lastSeenAt: (try? row.int64("lastSeenAt")) ?? 0
                )
            )
        }
        return rows
    }

    public func insertAll(_ echoes: [MemoryEcho]) {
        guard !echoes.isEmpty else { return }
        guard available() else {
            fallback.insertAll(echoes)
            return
        }
        let sql = """
        INSERT OR REPLACE INTO memory_echoes
        (id, personaId, drawer, content, importance, sourceQuote, categoryName, confidence, createdAt, lastSeenAt)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        database.withConnectionLock {
            guard let statement = try? database.prepare(sql) else {
                fallback.insertAll(echoes)
                return
            }
            for echo in echoes {
                statement.reset()
                try? statement.bind([
                    .text(echo.id), .text(echo.personaId), .text(echo.drawer), .text(echo.content),
                    .int(echo.importance), .text(echo.sourceQuote), .text(echo.categoryName),
                    .real(Double(echo.confidence)), .int(echo.createdAt), .int(echo.lastSeenAt)
                ])
                _ = try? statement.step()
            }
        }
    }

    public func delete(id: String) {
        fallback.delete(id: id)
        guard available() else { return }
        _ = try? database.executeUpdate("DELETE FROM memory_echoes WHERE id = ?", [.text(id)])
    }

    public func deleteAll(personaId: String) {
        fallback.deleteAll(personaId: personaId)
        guard available() else { return }
        _ = try? database.executeUpdate("DELETE FROM memory_echoes WHERE personaId = ?", [.text(personaId)])
    }

    public func deleteAll() {
        fallback.deleteAll()
        guard available() else { return }
        _ = try? database.execute("DELETE FROM memory_echoes")
    }
}
