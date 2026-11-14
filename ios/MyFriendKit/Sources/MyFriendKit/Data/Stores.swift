import Foundation

// ---------------------------------------------------------------------------
// 实体 ⇄ 数据表的最小映射，以及每个实体的最小 CRUD。
//
// 边界（有意为之）：**这里不写业务逻辑**。比如「记忆衰减到多少算归档」「主动节奏下一次
// 什么时候触发」都属于 Domain/仓储层的事。这个文件只回答三个问题：
//   1. 这条记录存哪张表、哪几列、按什么顺序绑定；
//   2. 一行结果怎么变回实体；
//   3. 最常用的「按 id / 按 persona / 计数 / 列表」怎么查。
//
// 为什么用 `INSERT OR REPLACE` 当 upsert：Room 的 `@Upsert` 语义就是「主键冲突则整行替换」，
// 备份导入也要求这份文件里的字段覆盖本机同 id 的记录，而不是留下旧字段的残留。
// ---------------------------------------------------------------------------

/// 能落进 SQLite 的实体。
public protocol SQLiteRecord {
    /// 表名（与 Room 一致）。
    static var tableName: String { get }
    /// 列名，顺序必须与 `bindings` 一一对应（个数不对会在 bind 时立刻报错）。
    static var columns: [String] { get }
    /// 按 `columns` 顺序的绑定值。
    var bindings: [SQLiteValue] { get }
    /// 从一行结果还原实体。
    init(row: SQLiteRow) throws
}

// MARK: - 通用仓储

/// 每个实体一个最小 CRUD 的泛型基类。
///
/// 用 class 而不是 struct 是为了让子类共享同一份 SQL 拼装（子类只加「按 persona 查」这类方法）。
open class SQLiteStore<Record: SQLiteRecord> {
    public let database: SQLiteDatabase

    public init(database: SQLiteDatabase) {
        self.database = database
    }

    public var tableName: String { Record.tableName }
    public var columns: [String] { Record.columns }

    private var selectPrefix: String {
        "SELECT \(columns.joined(separator: ", ")) FROM \(tableName)"
    }

    // MARK: 写入

    /// 单条 upsert。**不开事务**：调用方在批量导入时应自己包一层 `inTransaction`。
    ///
    /// 但要过连接锁：这条 autocommit 写不能落进别的线程正在跑的事务里
    /// （否则对方回滚时它会静默消失，见 0.10.2 审计 P0-1）。
    open func upsert(_ record: Record) throws {
        let placeholders = Array(repeating: "?", count: columns.count).joined(separator: ", ")
        let sql = "INSERT OR REPLACE INTO \(tableName) (\(columns.joined(separator: ", "))) VALUES (\(placeholders))"
        try database.withConnectionLock {
            let statement = try database.prepare(sql)
            try statement.bind(record.bindings)
            _ = try statement.step()
        }
    }

    /// 批量 upsert：一个事务，要么全进要么全不进（备份导入不该留下半份数据）。
    open func upsert(_ records: [Record]) throws {
        guard !records.isEmpty else { return }
        try database.inTransaction {
            for record in records {
                try upsert(record)
            }
        }
    }

    // MARK: 读取

    public func all(orderBy: String? = nil) throws -> [Record] {
        try fetch(where: nil, [], orderBy: orderBy, limit: nil)
    }

    public func count() throws -> Int {
        try count(where: nil, [])
    }

    public func byId(_ id: String) throws -> Record? {
        try first(where: "id = ?", [.text(id)])
    }

    /// 按条件取第一条（同样只拼参数化的 SQL，不把值拼进字符串）。
    public func first(where clause: String, _ arguments: [SQLiteValue] = [], orderBy: String? = nil) throws -> Record? {
        try fetch(where: clause, arguments, orderBy: orderBy, limit: 1).first
    }

    public func fetch(
        where clause: String?,
        _ arguments: [SQLiteValue] = [],
        orderBy: String? = nil,
        limit: Int? = nil
    ) throws -> [Record] {
        var sql = selectPrefix
        if let clause, !clause.isEmpty {
            sql += " WHERE \(clause)"
        }
        if let orderBy, !orderBy.isEmpty {
            sql += " ORDER BY \(orderBy)"
        }
        if let limit {
            sql += " LIMIT \(limit)"
        }
        let statement = try database.prepare(sql)
        try statement.bind(arguments)
        var rows: [Record] = []
        while try statement.step() {
            rows.append(try Record(row: SQLiteRow(statement: statement)))
        }
        return rows
    }

    public func count(where clause: String?, _ arguments: [SQLiteValue] = []) throws -> Int {
        var sql = "SELECT COUNT(*) FROM \(tableName)"
        if let clause, !clause.isEmpty {
            sql += " WHERE \(clause)"
        }
        return Int(try database.scalarInt64(sql, arguments))
    }

    // MARK: 删除

    public func delete(id: String) throws {
        try database.withConnectionLock {
            let statement = try database.prepare("DELETE FROM \(tableName) WHERE id = ?")
            try statement.bind([.text(id)])
            _ = try statement.step()
        }
    }

    public func delete(where clause: String, _ arguments: [SQLiteValue] = []) throws {
        try database.withConnectionLock {
            let statement = try database.prepare("DELETE FROM \(tableName) WHERE \(clause)")
            try statement.bind(arguments)
            _ = try statement.step()
        }
    }

    public func deleteAll() throws {
        try database.execute("DELETE FROM \(tableName)")
    }
}

// ---------------------------------------------------------------------------
// 实体映射：列名/绑定顺序必须与 Schema.swift 的建表语句一致。
// ---------------------------------------------------------------------------

extension PersonaEntity: SQLiteRecord {
    public static let tableName = "personas"
    public static let columns = [
        "id", "name", "description", "avatarUri", "avatarSeed", "prompt", "category",
        "version", "defaultApiConfigId", "modelOverride", "isDefault", "createdAt",
        "updatedAt", "personaCardJson", "quietSleepMinutes", "quietWakeMinutes",
        "mood", "moodIntensity", "moodReason", "moodUpdatedAt", "moodNudgedAt",
        // 1.9 的细分字段（三个列表列在库里是 TEXT(JSON 数组)）。
        "traits", "likes", "behaviors", "backstory", "quirks",
        "proactiveLevel", "memoryRetention", "extractionEvery",
        "sleepEnabled", "wakeThreshold", "dndOnSleep"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .text(name), .text(description), .optionalText(avatarUri),
            .int(avatarSeed), .text(prompt), .text(category), .int(version),
            .optionalText(defaultApiConfigId), .optionalText(modelOverride), .bool(isDefault),
            .int(createdAt), .int(updatedAt), .optionalText(personaCardJson),
            .optionalInt(quietSleepMinutes), .optionalInt(quietWakeMinutes),
            .optionalText(mood), .optionalInt(moodIntensity), .optionalText(moodReason),
            .optionalInt(moodUpdatedAt), .optionalInt(moodNudgedAt),
            .optionalText(PersonaTags.encode(traits, limit: PersonaFieldLimits.maxTraits)),
            .optionalText(PersonaTags.encode(likes, limit: PersonaFieldLimits.maxLikes)),
            .optionalText(PersonaTags.encode(behaviors, limit: PersonaFieldLimits.maxBehaviors)),
            .optionalText(backstory), .optionalText(quirks),
            .int(proactiveLevel), .int(memoryRetention), .int(extractionEvery),
            .bool(sleepEnabled), .int(wakeThreshold), .bool(dndOnSleep)
        ]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            id: try row.text("id"),
            name: try row.text("name"),
            description: try row.text("description"),
            avatarUri: try row.optionalText("avatarUri"),
            avatarSeed: try row.int("avatarSeed"),
            prompt: try row.text("prompt"),
            category: try row.text("category"),
            version: try row.int("version"),
            defaultApiConfigId: try row.optionalText("defaultApiConfigId"),
            modelOverride: try row.optionalText("modelOverride"),
            isDefault: try row.bool("isDefault"),
            createdAt: try row.int64("createdAt"),
            updatedAt: try row.int64("updatedAt"),
            personaCardJson: try row.optionalText("personaCardJson"),
            quietSleepMinutes: try row.optionalInt("quietSleepMinutes"),
            quietWakeMinutes: try row.optionalInt("quietWakeMinutes"),
            mood: try row.optionalText("mood"),
            moodIntensity: try row.optionalInt("moodIntensity"),
            moodReason: try row.optionalText("moodReason"),
            moodUpdatedAt: try row.optionalInt64("moodUpdatedAt"),
            moodNudgedAt: try row.optionalInt64("moodNudgedAt"),
            traits: PersonaTags.decode(try row.optionalText("traits"),
                                       limit: PersonaFieldLimits.maxTraits),
            likes: PersonaTags.decode(try row.optionalText("likes"),
                                      limit: PersonaFieldLimits.maxLikes),
            behaviors: PersonaTags.decode(try row.optionalText("behaviors"),
                                          limit: PersonaFieldLimits.maxBehaviors),
            backstory: try row.optionalText("backstory"),
            quirks: try row.optionalText("quirks"),
            // 老数据这几列是 NULL：走代码默认值，不回填（§三）。
            proactiveLevel: try row.optionalInt("proactiveLevel")
                ?? PersonaFieldLimits.proactiveLevelDefault,
            memoryRetention: try row.optionalInt("memoryRetention")
                ?? PersonaFieldLimits.memoryRetentionDefault,
            extractionEvery: try row.optionalInt("extractionEvery")
                ?? PersonaFieldLimits.extractionEveryDefault,
            sleepEnabled: try row.bool("sleepEnabled", true),
            wakeThreshold: try row.optionalInt("wakeThreshold")
                ?? PersonaFieldLimits.wakeThresholdDefault,
            dndOnSleep: try row.bool("dndOnSleep", false)
        )
    }
}

extension ApiConfigEntity: SQLiteRecord {
    public static let tableName = "api_configs"
    public static let columns = [
        "id", "name", "provider", "baseUrl", "apiKeyCipher", "model", "customHeadersJson",
        "allowInsecureHttp", "temperature", "topP", "maxOutputTokens", "frequencyPenalty",
        "presencePenalty", "createdAt",
        "updatedAt", "webToolsEnabled", "webToolsBaseUrl", "webToolsApiKeyCipher"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .text(name), .text(provider.stored), .text(baseUrl),
            .optionalText(apiKeyCipher), .text(model), .text(customHeadersJson),
            .bool(allowInsecureHttp), .optionalReal(temperature), .optionalReal(topP),
            .optionalInt(maxOutputTokens), .optionalReal(frequencyPenalty),
            .optionalReal(presencePenalty), .int(createdAt), .int(updatedAt),
            .bool(webToolsEnabled), .text(webToolsBaseUrl), .optionalText(webToolsApiKeyCipher)
        ]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            id: try row.text("id"),
            name: try row.text("name"),
            provider: ProviderType(stored: try row.optionalText("provider")),
            baseUrl: try row.text("baseUrl"),
            apiKeyCipher: try row.optionalText("apiKeyCipher"),
            model: try row.text("model"),
            customHeadersJson: try row.text("customHeadersJson", "{}"),
            allowInsecureHttp: try row.bool("allowInsecureHttp"),
            temperature: try row.optionalDouble("temperature"),
            topP: try row.optionalDouble("topP"),
            maxOutputTokens: try row.optionalInt("maxOutputTokens"),
            frequencyPenalty: try row.optionalDouble("frequencyPenalty"),
            presencePenalty: try row.optionalDouble("presencePenalty"),
            createdAt: try row.int64("createdAt"),
            updatedAt: try row.int64("updatedAt"),
            webToolsEnabled: try row.bool("webToolsEnabled"),
            webToolsBaseUrl: try row.text("webToolsBaseUrl"),
            webToolsApiKeyCipher: try row.optionalText("webToolsApiKeyCipher")
        )
    }
}

extension ChatSessionEntity: SQLiteRecord {
    public static let tableName = "chat_sessions"
    public static let columns = [
        "id", "personaId", "apiConfigId", "model", "title", "lastMessagePreview",
        "lastMessageAt", "unreadCount", "pinned", "createdAt", "updatedAt",
        "lastPersonaVersion", "syncState", "syncedPersonaVersion", "syncedMemoriesHash",
        "serverSessionId", "lastSyncError", "lastSyncAt", "lastMemoryExtractedCount",
        "historySummary", "historySummarizedUpTo", "historySummaryAt",
        "memoryRetentionOverride", "extractionEveryOverride"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .text(personaId), .optionalText(apiConfigId), .text(model), .text(title),
            .optionalText(lastMessagePreview), .optionalInt(lastMessageAt), .int(unreadCount),
            .bool(pinned), .int(createdAt), .int(updatedAt), .int(lastPersonaVersion),
            .text(syncState.stored), .optionalInt(syncedPersonaVersion),
            .optionalText(syncedMemoriesHash), .optionalText(serverSessionId),
            .optionalText(lastSyncError), .optionalInt(lastSyncAt), .int(lastMemoryExtractedCount),
            .optionalText(historySummary), .optionalInt(historySummarizedUpTo),
            .optionalInt(historySummaryAt),
            .optionalInt(memoryRetentionOverride), .optionalInt(extractionEveryOverride)
        ]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            id: try row.text("id"),
            personaId: try row.text("personaId"),
            apiConfigId: try row.optionalText("apiConfigId"),
            model: try row.text("model"),
            title: try row.text("title"),
            lastMessagePreview: try row.optionalText("lastMessagePreview"),
            lastMessageAt: try row.optionalInt64("lastMessageAt"),
            unreadCount: try row.int("unreadCount"),
            pinned: try row.bool("pinned"),
            createdAt: try row.int64("createdAt"),
            updatedAt: try row.int64("updatedAt"),
            lastPersonaVersion: try row.int("lastPersonaVersion"),
            syncState: SessionSyncState(stored: try row.optionalText("syncState")),
            syncedPersonaVersion: try row.optionalInt("syncedPersonaVersion"),
            syncedMemoriesHash: try row.optionalText("syncedMemoriesHash"),
            serverSessionId: try row.optionalText("serverSessionId"),
            lastSyncError: try row.optionalText("lastSyncError"),
            lastSyncAt: try row.optionalInt64("lastSyncAt"),
            lastMemoryExtractedCount: try row.int("lastMemoryExtractedCount"),
            historySummary: try row.optionalText("historySummary"),
            historySummarizedUpTo: try row.optionalInt64("historySummarizedUpTo"),
            historySummaryAt: try row.optionalInt64("historySummaryAt"),
            memoryRetentionOverride: try row.optionalInt("memoryRetentionOverride"),
            extractionEveryOverride: try row.optionalInt("extractionEveryOverride")
        )
    }
}

extension MessageEntity: SQLiteRecord {
    public static let tableName = "messages"
    public static let columns = [
        "id", "sessionId", "personaId", "role", "content", "createdAt", "updatedAt",
        "status", "errorMessage", "model", "edited", "attachmentsJson", "toolTraceJson",
        "readAt"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .text(sessionId), .text(personaId), .text(role.stored), .text(content),
            .int(createdAt), .int(updatedAt), .text(status.stored), .optionalText(errorMessage),
            .optionalText(model), .bool(edited), .optionalText(attachmentsJson),
            .optionalText(toolTraceJson), .optionalInt(readAt)
        ]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            id: try row.text("id"),
            sessionId: try row.text("sessionId"),
            personaId: try row.text("personaId"),
            role: MessageRole(stored: try row.optionalText("role")),
            content: try row.text("content"),
            createdAt: try row.int64("createdAt"),
            updatedAt: try row.int64("updatedAt"),
            status: MessageStatus(stored: try row.optionalText("status")),
            errorMessage: try row.optionalText("errorMessage"),
            model: try row.optionalText("model"),
            edited: try row.bool("edited"),
            attachmentsJson: try row.optionalText("attachmentsJson"),
            toolTraceJson: try row.optionalText("toolTraceJson"),
            readAt: try row.optionalInt64("readAt")
        )
    }
}

extension MemoryEntity: SQLiteRecord {
    public static let tableName = "memories"
    public static let columns = [
        "id", "personaId", "content", "category", "importance", "pinned",
        "sourceSessionId", "createdAt", "updatedAt", "lastUsedAt", "useCount",
        "drawer", "sourceQuote", "status", "strength", "archivedAt", "consolidatedAt",
        "tags", "happenedAt", "originalContent", "driftCount"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .text(personaId), .text(content), .text(category.stored),
            .int(importance), .bool(pinned), .optionalText(sourceSessionId),
            .int(createdAt), .int(updatedAt), .int(lastUsedAt), .int(useCount),
            .optionalText(drawer), .optionalText(sourceQuote), .text(status),
            .real(strength), .optionalInt(archivedAt), .optionalInt(consolidatedAt),
            .optionalText(tags), .optionalInt(happenedAt), .optionalText(originalContent),
            .int(driftCount)
        ]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            id: try row.text("id"),
            personaId: try row.text("personaId"),
            content: try row.text("content"),
            category: MemoryCategory(stored: try row.optionalText("category")),
            importance: try row.int("importance"),
            pinned: try row.bool("pinned"),
            sourceSessionId: try row.optionalText("sourceSessionId"),
            createdAt: try row.int64("createdAt"),
            updatedAt: try row.int64("updatedAt"),
            lastUsedAt: try row.int64("lastUsedAt"),
            useCount: try row.int("useCount"),
            drawer: try row.optionalText("drawer"),
            sourceQuote: try row.optionalText("sourceQuote"),
            status: try row.text("status", "ACTIVE"),
            strength: try row.double("strength", 1.0),
            archivedAt: try row.optionalInt64("archivedAt"),
            consolidatedAt: try row.optionalInt64("consolidatedAt"),
            tags: try row.optionalText("tags"),
            happenedAt: try row.optionalInt64("happenedAt"),
            originalContent: try row.optionalText("originalContent"),
            driftCount: try row.int("driftCount")
        )
    }
}

extension MemoryTermEntity: SQLiteRecord {
    public static let tableName = "memory_terms"
    public static let columns = ["memoryId", "term", "weight"]

    public var bindings: [SQLiteValue] {
        [.text(memoryId), .text(term), .real(weight)]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            memoryId: try row.text("memoryId"),
            term: try row.text("term"),
            weight: try row.double("weight", 1.0)
        )
    }
}

extension MemoryVectorEntity: SQLiteRecord {
    public static let tableName = "memory_vectors"
    public static let columns = ["memoryId", "provider", "dim", "vector", "updatedAt"]

    public var bindings: [SQLiteValue] {
        [.text(memoryId), .text(provider), .int(dim), .blob(vector), .int(updatedAt)]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            memoryId: try row.text("memoryId"),
            provider: try row.text("provider"),
            dim: try row.int("dim"),
            vector: try row.blob("vector"),
            updatedAt: try row.int64("updatedAt")
        )
    }
}

extension MemoryVersionEntity: SQLiteRecord {
    public static let tableName = "memory_versions"
    public static let columns = ["id", "memoryId", "at", "content", "beforeContent", "reason", "moodLabel"]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .text(memoryId), .int(at), .text(content),
            .optionalText(beforeContent), .optionalText(reason), .optionalText(moodLabel)
        ]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            id: try row.text("id"),
            memoryId: try row.text("memoryId"),
            at: try row.int64("at"),
            content: try row.text("content"),
            beforeContent: try row.optionalText("beforeContent"),
            reason: try row.optionalText("reason"),
            moodLabel: try row.optionalText("moodLabel")
        )
    }
}

extension MemoryConsolidationEntity: SQLiteRecord {
    public static let tableName = "memory_consolidations"
    public static let columns = [
        "id", "personaId", "at", "kept", "merged", "archived", "forgotten", "summary", "modelName"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .text(personaId), .int(at), .int(kept), .int(merged),
            .int(archived), .int(forgotten), .text(summary), .optionalText(modelName)
        ]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            id: try row.text("id"),
            personaId: try row.text("personaId"),
            at: try row.int64("at"),
            kept: try row.int("kept"),
            merged: try row.int("merged"),
            archived: try row.int("archived"),
            forgotten: try row.int("forgotten"),
            summary: try row.text("summary"),
            modelName: try row.optionalText("modelName")
        )
    }
}

extension PersonaPresetEntity: SQLiteRecord {
    public static let tableName = "persona_presets"
    public static let columns = [
        "id", "name", "description", "prompt", "category", "avatarUri", "avatarSeed",
        "createdAt", "updatedAt"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .text(name), .text(description), .text(prompt), .text(category),
            .optionalText(avatarUri), .int(avatarSeed), .int(createdAt), .int(updatedAt)
        ]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            id: try row.text("id"),
            name: try row.text("name"),
            description: try row.text("description"),
            prompt: try row.text("prompt"),
            category: try row.text("category"),
            avatarUri: try row.optionalText("avatarUri"),
            avatarSeed: try row.int("avatarSeed"),
            createdAt: try row.int64("createdAt"),
            updatedAt: try row.int64("updatedAt")
        )
    }
}

extension ProactiveRuleEntity: SQLiteRecord {
    public static let tableName = "proactive_rules"
    public static let columns = [
        "id", "personaId", "sessionId", "kind", "timeOfDayMinutes", "intervalMinutes",
        "hint", "note", "enabled", "pending", "source", "nextTriggerAt",
        "lastTriggeredAt", "lastError", "createdAt", "updatedAt"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .text(personaId), .optionalText(sessionId), .text(kind.stored),
            .optionalInt(timeOfDayMinutes), .optionalInt(intervalMinutes), .text(hint),
            .text(note), .bool(enabled), .bool(pending), .text(source.stored),
            .int(nextTriggerAt), .optionalInt(lastTriggeredAt), .optionalText(lastError),
            .int(createdAt), .int(updatedAt)
        ]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            id: try row.text("id"),
            personaId: try row.text("personaId"),
            sessionId: try row.optionalText("sessionId"),
            kind: ProactiveKind(stored: try row.optionalText("kind")),
            timeOfDayMinutes: try row.optionalInt("timeOfDayMinutes"),
            intervalMinutes: try row.optionalInt("intervalMinutes"),
            hint: try row.text("hint"),
            note: try row.text("note"),
            enabled: try row.bool("enabled", true),
            pending: try row.bool("pending"),
            source: ProactiveSource(stored: try row.optionalText("source")),
            nextTriggerAt: try row.int64("nextTriggerAt"),
            lastTriggeredAt: try row.optionalInt64("lastTriggeredAt"),
            lastError: try row.optionalText("lastError"),
            createdAt: try row.int64("createdAt"),
            updatedAt: try row.int64("updatedAt")
        )
    }
}

extension AgentIntentEntity: SQLiteRecord {
    public static let tableName = "agent_intents"
    public static let columns = [
        "id", "personaId", "sessionId", "kind", "earliestAt", "latestAt", "note",
        "hint", "status", "ruleId", "createdAt", "sentAt", "lastError"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .text(personaId), .optionalText(sessionId), .text(kind.stored),
            .int(earliestAt), .int(latestAt), .text(note), .text(hint), .text(status.stored),
            .optionalText(ruleId), .int(createdAt), .optionalInt(sentAt), .optionalText(lastError)
        ]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            id: try row.text("id"),
            personaId: try row.text("personaId"),
            sessionId: try row.optionalText("sessionId"),
            kind: AgentIntentKind(stored: try row.optionalText("kind")),
            earliestAt: try row.int64("earliestAt"),
            latestAt: try row.int64("latestAt"),
            note: try row.text("note"),
            hint: try row.text("hint"),
            status: AgentIntentStatus(stored: try row.optionalText("status")),
            ruleId: try row.optionalText("ruleId"),
            createdAt: try row.int64("createdAt"),
            sentAt: try row.optionalInt64("sentAt"),
            lastError: try row.optionalText("lastError")
        )
    }
}

extension ApiUsageEntity: SQLiteRecord {
    public static let tableName = "api_usage"
    public static let columns = [
        "id", "at", "kind", "personaId", "sessionId", "modelName",
        "promptTokens", "completionTokens", "costMicroUsd"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .int(at), .text(kind), .optionalText(personaId),
            .optionalText(sessionId), .optionalText(modelName), .int(promptTokens),
            .int(completionTokens), .int(costMicroUsd)
        ]
    }

    public init(row: SQLiteRow) throws {
        self.init(
            id: try row.text("id"),
            at: try row.int64("at"),
            kind: try row.text("kind"),
            personaId: try row.optionalText("personaId"),
            sessionId: try row.optionalText("sessionId"),
            modelName: try row.optionalText("modelName"),
            promptTokens: try row.int("promptTokens"),
            completionTokens: try row.int("completionTokens"),
            costMicroUsd: try row.int64("costMicroUsd")
        )
    }
}

// ---------------------------------------------------------------------------
// 每个实体的最小仓储：通用 CRUD 继承，加几个「以后仓储层一定会用到」的查询。
// ---------------------------------------------------------------------------

public final class PersonaStore: SQLiteStore<PersonaEntity> {
    /// 当前默认人格。理论上只有一个，万一数据里有多条（导入合并过）取最近更新的。
    public func defaultPersona() throws -> PersonaEntity? {
        try first(where: "isDefault = 1", orderBy: "updatedAt DESC")
    }

    public func allOrderedByUpdatedAt() throws -> [PersonaEntity] {
        try all(orderBy: "updatedAt DESC")
    }
}

public final class ApiConfigStore: SQLiteStore<ApiConfigEntity> {
    public func allOrderedByUpdatedAt() throws -> [ApiConfigEntity] {
        try all(orderBy: "updatedAt DESC")
    }
}

public final class ChatSessionStore: SQLiteStore<ChatSessionEntity> {
    /// 一个人格的那一段会话（1.9 起 `personaId` 上有唯一索引，最多一条）。
    ///
    /// 为什么还要 `byPersona`：迁移失败的库、或者备份导入过程中的中间状态，
    /// 理论上仍可能短暂地出现两条；界面与迁移都用「取最新一条」这个同一口径。
    public func session(forPersona personaId: String) throws -> ChatSessionEntity? {
        try first(where: "personaId = ?", [.text(personaId)],
                  orderBy: "COALESCE(lastMessageAt, updatedAt) DESC")
    }

    public func byPersona(_ personaId: String) throws -> [ChatSessionEntity] {
        try fetch(where: "personaId = ?", [.text(personaId)],
                  orderBy: "COALESCE(lastMessageAt, updatedAt) DESC")
    }

    public func count(personaId: String) throws -> Int {
        try count(where: "personaId = ?", [.text(personaId)])
    }

    /// 首页联系人列表用：按最近一条消息排序（没说过话的排在后面，但仍然要出现）。
    public func allOrderedByRecency() throws -> [ChatSessionEntity] {
        try all(orderBy: "COALESCE(lastMessageAt, updatedAt) DESC")
    }

    public func recent(limit: Int) throws -> [ChatSessionEntity] {
        try fetch(where: "lastMessageAt IS NOT NULL", [], orderBy: "lastMessageAt DESC", limit: limit)
    }

    // MARK: - 后台任务的定向写回（0.10.2 审计 P1-1）
    //
    // 抽记忆与压缩摘要是「读整行 → 调模型（几百毫秒到几十秒）→ 写回」的模式。
    // 原来写回用 `INSERT OR REPLACE`（整行替换），于是这段时间里用户新发的消息带来的
    // 预览 / 未读 / 标题、另一条后台任务写的字段、甚至「会话被删掉」都会被冲掉或复活。
    //
    // 现在只 UPDATE 自己那几列，并且带**乐观检查**：读到的旧值仍然成立才写，
    // 否则放弃这次后台结果（调用方只记日志，不弹错——后台任务失败不该打扰用户）。

    /// 只写「记忆抽到第几条」。`observed` 是开始时读到的那一行的值：
    /// 期间有人抢先改过（另一轮抽取、或备份导入）就不写。
    ///
    /// - Returns: false 表示乐观检查没过（会话被删 / 计数已被别人推进）。
    @discardableResult
    public func updateMemoryExtractedCount(
        id: String,
        observed: Int,
        count: Int,
        updatedAt: Int64
    ) throws -> Bool {
        let changed = try database.executeUpdate(
            """
            UPDATE chat_sessions SET lastMemoryExtractedCount = ?, updatedAt = ?
            WHERE id = ? AND lastMemoryExtractedCount = ?
            """,
            [.int(count), .int(updatedAt), .text(id), .int(observed)]
        )
        return changed > 0
    }

    /// 只写「历史摘要」那三列。
    ///
    /// 乐观检查用**单调性**而不是「旧值相等」：两次压缩同时跑时，压得更远的那一次
    /// 才应该生效（先到先得会让覆盖更多消息的那次白算）。`-1` 代表「还没有摘要」。
    ///
    /// - Returns: false 表示没写（会话被删 / 已经有人压得更远）。
    @discardableResult
    public func updateHistorySummary(
        id: String,
        summary: String,
        summarizedUpTo: Int64,
        summaryAt: Int64,
        updatedAt: Int64
    ) throws -> Bool {
        let changed = try database.executeUpdate(
            """
            UPDATE chat_sessions
            SET historySummary = ?, historySummarizedUpTo = ?, historySummaryAt = ?, updatedAt = ?
            WHERE id = ? AND COALESCE(historySummarizedUpTo, -1) < ?
            """,
            [
                .text(summary), .int(summarizedUpTo), .int(summaryAt), .int(updatedAt),
                .text(id), .int(summarizedUpTo)
            ]
        )
        return changed > 0
    }

    /// 只清空摘要三列（启动自检发现旧摘要不可用时用）。会话被删就返回 false。
    @discardableResult
    public func clearHistorySummary(id: String, updatedAt: Int64) throws -> Bool {
        let changed = try database.executeUpdate(
            """
            UPDATE chat_sessions SET historySummary = NULL, historySummarizedUpTo = NULL,
                historySummaryAt = NULL, updatedAt = ?
            WHERE id = ?
            """,
            [.int(updatedAt), .text(id)]
        )
        return changed > 0
    }
}

// ---------------------------------------------------------------------------
// 聊天记录检索的结果对象。
//
// 这是**界面唯一需要认识的东西**：一条命中、它所在的会话与人格、可渲染的摘要与高亮。
// 为什么不让界面自己去 join 会话 / 人格：那会把「标题为空怎么回落」这种规则复制到每个调用点，
// 而且 N+1 次查询会写在视图里。规则集中在这个文件里，界面只管画。
// ---------------------------------------------------------------------------

/// 一条检索命中。
///
/// 比规格里列的九个字段多了 `personaId` / `avatarSeed`：它们本来就要在 SQL 里查出来
/// （按人格过滤、头像底色），丢在数据层反而逼界面再查一次。
public struct SearchHit: Identifiable, Equatable, Sendable {
    /// 消息 id（也是跳转定位的锚点）。
    public var id: String
    public var sessionId: String
    /// 会话标题；标题为空时回落成人格名（界面不用再判一次空）。
    public var sessionTitle: String
    public var personaId: String
    public var personaName: String
    /// 人格头像 URI（没有头像时界面用 `avatarSeed` + 首字画一个稳定的圆）。
    public var avatar: String?
    public var avatarSeed: Int
    /// 摘要正文（带省略号的那一段）。
    public var snippet: String
    /// 摘要里的高亮区间（UTF-16 偏移，相对 `snippet`）。
    public var highlights: [Range<Int>]
    public var createdAt: Int64
    public var role: MessageRole

    public init(
        id: String,
        sessionId: String,
        sessionTitle: String,
        personaId: String,
        personaName: String,
        avatar: String? = nil,
        avatarSeed: Int = 0,
        snippet: String,
        highlights: [Range<Int>] = [],
        createdAt: Int64,
        role: MessageRole
    ) {
        self.id = id
        self.sessionId = sessionId
        self.sessionTitle = sessionTitle
        self.personaId = personaId
        self.personaName = personaName
        self.avatar = avatar
        self.avatarSeed = avatarSeed
        self.snippet = snippet
        self.highlights = highlights
        self.createdAt = createdAt
        self.role = role
    }

    /// 摘要按高亮切开的样子（直接交给 `Text` 拼；Kit 不依赖 SwiftUI）。
    public var snippetSegments: [SearchSnippet.Segment] {
        SearchSnippet(text: snippet, highlights: highlights).segments()
    }
}

/// 一页检索结果。
public struct SearchPage: Equatable, Sendable {
    public var hits: [SearchHit]
    /// 还有下一页（按同一查询 + 同一筛选条件继续往后翻）。
    public var hasMore: Bool
    /// 下一页的游标；`hasMore == false` 时是 nil。
    public var nextCursor: SearchCursor?

    public init(hits: [SearchHit] = [], hasMore: Bool = false, nextCursor: SearchCursor? = nil) {
        self.hits = hits
        self.hasMore = hasMore
        self.nextCursor = nextCursor
    }

    public static let empty = SearchPage()
}

public final class MessageStore: SQLiteStore<MessageEntity> {
    public func bySession(_ sessionId: String) throws -> [MessageEntity] {
        try fetch(where: "sessionId = ?", [.text(sessionId)], orderBy: "createdAt ASC")
    }

    /// 最近 `limit` 条（按时间升序返回）。
    ///
    /// 聊天页首屏与主动开口读历史都走这里：长会话下不该为了看最后 60 条把整段读进内存。
    public func recent(sessionId: String, limit: Int) throws -> [MessageEntity] {
        guard limit > 0 else { return [] }
        let rows = try fetch(
            where: "sessionId = ?", [.text(sessionId)],
            orderBy: "createdAt DESC, id DESC", limit: limit
        )
        return Array(rows.reversed())
    }

    /// 游标分页：取「严格早于 `(createdAt, id)`」的最近 `limit` 条，仍按时间升序返回。
    ///
    /// 为什么游标是两个字段：`createdAt` 是毫秒时间戳，同一毫秒里可能落下两条消息
    ///（主动开口与用户发送撞在一起时），只按时间取会漏掉或重复一条。`id` 是稳定的第二排序键。
    public func pageBefore(
        sessionId: String,
        createdAt: Int64,
        id: String,
        limit: Int
    ) throws -> [MessageEntity] {
        guard limit > 0 else { return [] }
        let rows = try fetch(
            where: "sessionId = ? AND (createdAt < ? OR (createdAt = ? AND id < ?))",
            [.text(sessionId), .int(createdAt), .int(createdAt), .text(id)],
            orderBy: "createdAt DESC, id DESC",
            limit: limit
        )
        return Array(rows.reversed())
    }

    /// 游标之前还有没有更早的消息（聊天页的「加载更早」只在这里判断到底）。
    public func countBefore(sessionId: String, createdAt: Int64, id: String) throws -> Int {
        try count(
            where: "sessionId = ? AND (createdAt < ? OR (createdAt = ? AND id < ?))",
            [.text(sessionId), .int(createdAt), .int(createdAt), .text(id)]
        )
    }

    public func count(sessionId: String) throws -> Int {
        try count(where: "sessionId = ?", [.text(sessionId)])
    }

    public func latest(sessionId: String) throws -> MessageEntity? {
        try first(where: "sessionId = ?", [.text(sessionId)], orderBy: "createdAt DESC")
    }

    public func byPersona(_ personaId: String) throws -> [MessageEntity] {
        try fetch(where: "personaId = ?", [.text(personaId)], orderBy: "createdAt ASC")
    }

    /// 某人格最近一段时间里对方说了几句（睡眠唤醒阈值要数这个，§六）。
    public func countUserMessages(personaId: String, since: Int64) throws -> Int {
        try count(where: "personaId = ? AND role = ? AND createdAt >= ?",
                  [.text(personaId), .text(MessageRole.user.stored), .int(since)])
    }

    /// 把一段会话里「还没被看到」的用户消息标成已读（模型看到它们的那一刻）。
    ///
    /// 返回改了几条：调用方不需要它，测试需要（断言「已读」到底写没写）。
    @discardableResult
    public func markUserMessagesRead(sessionId: String, at timestamp: Int64) throws -> Int {
        let unread = try fetch(
            where: "sessionId = ? AND role = ? AND readAt IS NULL",
            [.text(sessionId), .text(MessageRole.user.stored)],
            orderBy: "createdAt ASC"
        )
        for var message in unread {
            message.readAt = timestamp
            message.updatedAt = timestamp
            try upsert(message)
        }
        return unread.count
    }

    // MARK: - 聊天记录检索

    /// 一页多少条（界面每页 30 条）。
    public static let defaultSearchPageSize = 30

    /// 粗筛最多取多少条候选进内存精排。
    ///
    /// 为什么要有上限：`LIKE '%q%'` 命中几千条时，把整张表的正文读进内存再算 relevance
    /// 会在低端机上卡一下。代价是**超过 500 条命中时只在新最近的 500 条里排序**，
    /// 更旧的高分命中可能被截掉（见交付报告的风险项）；`hasMore` 只反映这批候选内部还有没有。
    public static let maxSearchCandidates = 500

    /// 会话 / 人格只为了给命中补标题与头像，缓存一下避免同一次检索里 N+1。
    private lazy var sessionLookup = ChatSessionStore(database: database)
    private lazy var personaLookup = PersonaStore(database: database)

    /// 检索聊天记录：**粗筛（SQL）→ 精排（Domain）→ 游标分页 → 补会话/人格信息**。
    ///
    /// 为什么是「LIKE 粗筛 + 内存精排」而不是建一张倒排索引表：
    /// 排序规则要的是「命中词元个数 + `ChineseTerms.relevance`」，这在 SQL 里算不出来；
    /// 真要做就得把词元、权重、分值的维护搬到写消息的每条路径上（发消息、撤回、编辑、
    /// 备份导入、清空上下文……），一处漏了就会「搜不到自己刚说的话」。
    /// 聊天记录的量级（一个人的对话）落在 500 条候选内，把复杂度放在 Domain 里值得。
    ///
    /// 用户输入里的 `%` / `_` / `\` 一律按普通字符处理（`LIKE ... ESCAPE '\'`）：
    /// 「100%」不会被当成「100 后面跟任意内容」。注意这只管**粗筛的匹配语义**——
    /// 规范还有一条「查询的任一词元命中也算命中」，所以「100%」仍会经词元「100」召回「1000」，
    /// 两端行为必须一致（`spec/search/README.md` 的 Ranking rules 第 1 条）。
    ///
    /// - Parameters:
    ///   - query: 查询词（内部 trim；空查询返回空页，不是「返回全部」）。
    ///   - personaId: 只看某个人格（nil = 全部）。
    ///   - since: 只要这个时刻之后（含边界）的消息（nil = 全部时间）。
    ///   - limit: 这一页最多几条。
    ///   - cursor: 上一页返回的游标（nil = 第一页）。
    ///   - candidateLimit: 粗筛上限（测试用它验证「候选被截断」时的行为）。
    ///   - snippetWindow: 摘要窗口宽度，默认 60（与 `spec/search` 一致）。
    public func search(
        query: String,
        personaId: String? = nil,
        since: Int64? = nil,
        limit: Int = MessageStore.defaultSearchPageSize,
        cursor: SearchCursor? = nil,
        candidateLimit: Int = MessageStore.maxSearchCandidates,
        snippetWindow: Int = SearchSnippet.defaultWindow
    ) throws -> SearchPage {
        let trimmed = query.trimmed
        guard !trimmed.isEmpty, limit > 0 else { return .empty }

        // 1) 粗筛：正文里出现「查询原文」或任意一个中文词元。
        //    词元这一半是中文的命根子：查「项目压力」时二元组能命中「项目压得我睡不着」。
        var patterns = [MessageStore.likePattern(trimmed)]
        for term in ChineseTerms.queryTerms(trimmed) where term != trimmed {
            patterns.append(MessageStore.likePattern(term))
        }
        var clause = "(" + patterns.map { _ in "content LIKE ? ESCAPE '\\'" }.joined(separator: " OR ") + ")"
        var arguments: [SQLiteValue] = patterns.map { .text($0) }
        if let personaId {
            clause += " AND personaId = ?"
            arguments.append(.text(personaId))
        }
        if let since {
            clause += " AND createdAt >= ?"
            arguments.append(.int(since))
        }
        let candidates = try fetch(
            where: clause,
            arguments,
            orderBy: "createdAt DESC, id ASC",
            limit: max(1, candidateLimit)
        )
        if candidates.isEmpty { return .empty }

        // 2) 精排：命中判断、命中词元数、relevance、四键排序都在 Domain 里（与规范用例同一套）。
        let items = candidates.map { message in
            SearchRankItem(
                id: message.id,
                content: message.content,
                createdAt: message.createdAt,
                personaId: message.personaId
            )
        }
        let ranked = SearchRanking.rank(items, query: trimmed, personaId: personaId, since: since)

        // 3) 游标分页：顺序是「分值优先」，所以游标必须带上四个排序键（见 `SearchCursor`）。
        let remaining: [SearchRankedHit]
        if let cursor {
            remaining = ranked.filter { SearchRanking.comesAfter($0, cursor: cursor) }
        } else {
            remaining = ranked
        }
        if remaining.isEmpty { return .empty }
        let page = Array(remaining.prefix(limit))
        let hasMore = remaining.count > page.count

        // 4) 补会话标题 / 人格名 / 头像 —— 只对**这一页**做，且同样的 id 只查一次。
        var messagesById: [String: MessageEntity] = [:]
        for candidate in candidates { messagesById[candidate.id] = candidate }
        var sessionCache: [String: ChatSessionEntity?] = [:]
        var personaCache: [String: PersonaEntity?] = [:]

        func session(_ id: String) -> ChatSessionEntity? {
            if let cached = sessionCache[id] { return cached }
            let loaded: ChatSessionEntity? = (try? sessionLookup.byId(id)) ?? nil
            sessionCache[id] = loaded
            return loaded
        }
        func persona(_ id: String) -> PersonaEntity? {
            if let cached = personaCache[id] { return cached }
            let loaded: PersonaEntity? = (try? personaLookup.byId(id)) ?? nil
            personaCache[id] = loaded
            return loaded
        }

        var hits: [SearchHit] = []
        hits.reserveCapacity(page.count)
        for rankedHit in page {
            guard let message = messagesById[rankedHit.item.id] else { continue }
            let sessionEntity = session(message.sessionId)
            let personaEntity = persona(message.personaId) ?? sessionEntity.flatMap { persona($0.personaId) }
            let personaName = personaEntity?.name ?? ""
            let title = (sessionEntity?.title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let snippet = SearchSnippet.build(
                content: message.content,
                query: trimmed,
                window: snippetWindow
            )
            hits.append(SearchHit(
                id: message.id,
                sessionId: message.sessionId,
                sessionTitle: title.isEmpty ? personaName : title,
                personaId: message.personaId,
                personaName: personaName,
                avatar: personaEntity?.avatarUri,
                avatarSeed: personaEntity?.avatarSeed ?? 0,
                snippet: snippet.text,
                highlights: snippet.highlights,
                createdAt: message.createdAt,
                role: message.role
            ))
        }

        let nextCursor = hasMore ? page.last.map(SearchCursor.init) : nil
        return SearchPage(hits: hits, hasMore: hasMore && nextCursor != nil, nextCursor: nextCursor)
    }

    /// 把用户输入包成 `LIKE` 模式：`%` / `_` / `\` 全部转义成普通字符。
    ///
    /// 与 SQL 里的 `ESCAPE '\'` 配套使用。SQLite 的 `LIKE` 对 ASCII 大小写不敏感，
    /// 对中文是逐字节比较 —— 正好符合「中文没有大小写」的事实。
    static func likePattern(_ text: String) -> String {
        var escaped = ""
        escaped.reserveCapacity(text.count + 2)
        for character in text {
            if character == "\\" || character == "%" || character == "_" {
                escaped.append("\\")
            }
            escaped.append(character)
        }
        return "%" + escaped + "%"
    }
}

public final class MemoryStore: SQLiteStore<MemoryEntity> {
    public func byPersona(_ personaId: String) throws -> [MemoryEntity] {
        try fetch(where: "personaId = ?", [.text(personaId)], orderBy: "importance DESC, updatedAt DESC")
    }

    public func count(personaId: String) throws -> Int {
        try count(where: "personaId = ?", [.text(personaId)])
    }

    public func pinned(personaId: String) throws -> [MemoryEntity] {
        try fetch(where: "personaId = ? AND pinned = 1", [.text(personaId)], orderBy: "importance DESC")
    }

    public func byStatus(_ status: String, personaId: String? = nil) throws -> [MemoryEntity] {
        if let personaId {
            return try fetch(where: "personaId = ? AND status = ?", [.text(personaId), .text(status)],
                             orderBy: "updatedAt DESC")
        }
        return try fetch(where: "status = ?", [.text(status)], orderBy: "updatedAt DESC")
    }
}

public final class MemoryTermStore: SQLiteStore<MemoryTermEntity> {
    public func terms(forMemory memoryId: String) throws -> [MemoryTermEntity] {
        try fetch(where: "memoryId = ?", [.text(memoryId)], orderBy: "weight DESC")
    }

    /// 重建一条记忆的倒排索引时用：先删干净再整批写入。
    public func replaceTerms(forMemory memoryId: String, with terms: [MemoryTermEntity]) throws {
        try database.inTransaction {
            try delete(where: "memoryId = ?", [.text(memoryId)])
            try upsert(terms)
        }
    }

    public func deleteTerms(forMemory memoryId: String) throws {
        try delete(where: "memoryId = ?", [.text(memoryId)])
    }
}

public final class MemoryVectorStore: SQLiteStore<MemoryVectorEntity> {
    public func vector(memoryId: String, provider: String) throws -> MemoryVectorEntity? {
        try first(where: "memoryId = ? AND provider = ?", [.text(memoryId), .text(provider)])
    }

    public func vectors(provider: String) throws -> [MemoryVectorEntity] {
        try fetch(where: "provider = ?", [.text(provider)])
    }

    public func count(provider: String) throws -> Int {
        try count(where: "provider = ?", [.text(provider)])
    }

    public func deleteVectors(forMemory memoryId: String) throws {
        try delete(where: "memoryId = ?", [.text(memoryId)])
    }
}

public final class MemoryVersionStore: SQLiteStore<MemoryVersionEntity> {
    public func versions(forMemory memoryId: String) throws -> [MemoryVersionEntity] {
        try fetch(where: "memoryId = ?", [.text(memoryId)], orderBy: "at DESC")
    }

    public func count(memoryId: String) throws -> Int {
        try count(where: "memoryId = ?", [.text(memoryId)])
    }
}

public final class MemoryConsolidationStore: SQLiteStore<MemoryConsolidationEntity> {
    public func byPersona(_ personaId: String) throws -> [MemoryConsolidationEntity] {
        try fetch(where: "personaId = ?", [.text(personaId)], orderBy: "at DESC")
    }

    public func count(personaId: String) throws -> Int {
        try count(where: "personaId = ?", [.text(personaId)])
    }

    public func latest(personaId: String) throws -> MemoryConsolidationEntity? {
        try first(where: "personaId = ?", [.text(personaId)], orderBy: "at DESC")
    }
}

public final class PersonaPresetStore: SQLiteStore<PersonaPresetEntity> {
    public func byCategory(_ category: String) throws -> [PersonaPresetEntity] {
        try fetch(where: "category = ?", [.text(category)], orderBy: "updatedAt DESC")
    }

    public func allOrderedByUpdatedAt() throws -> [PersonaPresetEntity] {
        try all(orderBy: "updatedAt DESC")
    }
}

public final class ProactiveRuleStore: SQLiteStore<ProactiveRuleEntity> {
    public func byPersona(_ personaId: String) throws -> [ProactiveRuleEntity] {
        try fetch(where: "personaId = ?", [.text(personaId)], orderBy: "nextTriggerAt ASC")
    }

    public func enabled(personaId: String) throws -> [ProactiveRuleEntity] {
        try fetch(where: "personaId = ? AND enabled = 1", [.text(personaId)], orderBy: "nextTriggerAt ASC")
    }

    public func count(personaId: String) throws -> Int {
        try count(where: "personaId = ?", [.text(personaId)])
    }

    /// 到了该触发的时间的规则（只按时间筛，是否真的开口由 Domain 的冲动曲线决定）。
    public func due(at now: Int64) throws -> [ProactiveRuleEntity] {
        try fetch(where: "enabled = 1 AND nextTriggerAt > 0 AND nextTriggerAt <= ?",
                  [.int(now)], orderBy: "nextTriggerAt ASC")
    }
}

public final class AgentIntentStore: SQLiteStore<AgentIntentEntity> {
    public func byPersona(_ personaId: String) throws -> [AgentIntentEntity] {
        try fetch(where: "personaId = ?", [.text(personaId)], orderBy: "earliestAt ASC")
    }

    public func byStatus(_ status: AgentIntentStatus, personaId: String? = nil) throws -> [AgentIntentEntity] {
        if let personaId {
            return try fetch(where: "personaId = ? AND status = ?",
                             [.text(personaId), .text(status.stored)], orderBy: "earliestAt ASC")
        }
        return try fetch(where: "status = ?", [.text(status.stored)], orderBy: "earliestAt ASC")
    }

    public func count(personaId: String) throws -> Int {
        try count(where: "personaId = ?", [.text(personaId)])
    }

    /// 窗口还没过、可以执行的念头。
    public func open(at now: Int64, personaId: String) throws -> [AgentIntentEntity] {
        try fetch(where: "personaId = ? AND status = ? AND latestAt >= ?",
                  [.text(personaId), .text(AgentIntentStatus.pending.stored), .int(now)],
                  orderBy: "earliestAt ASC")
    }
}

public final class ApiUsageStore: SQLiteStore<ApiUsageEntity> {
    public func since(_ at: Int64) throws -> [ApiUsageEntity] {
        try fetch(where: "at >= ?", [.int(at)], orderBy: "at DESC")
    }

    public func count(since at: Int64) throws -> Int {
        try count(where: "at >= ?", [.int(at)])
    }

    /// 某段时间里一共用了多少 token（读 + 写）。
    ///
    /// 1.9 起用量页只认 token：没有额度、没有拦截、也不给用户算钱了（§五）。
    public func sumTokens(since at: Int64) throws -> Int64 {
        try database.scalarInt64(
            "SELECT COALESCE(SUM(promptTokens + completionTokens), 0) FROM api_usage WHERE at >= ?",
            [.int(at)]
        )
    }
}

/// 一次性装配好所有仓储：后面的 Repository 层只需要一个入口。
///
/// 不用单例（`Database.shared`）：测试要能建临时库、App 要能迁移旧库，
/// 一个显式的 `DatabaseStores(database:)` 比全局状态好推理。
public struct DatabaseStores {
    public let database: SQLiteDatabase
    public let personas: PersonaStore
    public let apiConfigs: ApiConfigStore
    public let sessions: ChatSessionStore
    public let messages: MessageStore
    public let memories: MemoryStore
    public let memoryTerms: MemoryTermStore
    public let memoryVectors: MemoryVectorStore
    public let memoryVersions: MemoryVersionStore
    public let memoryConsolidations: MemoryConsolidationStore
    public let presets: PersonaPresetStore
    public let proactiveRules: ProactiveRuleStore
    public let agentIntents: AgentIntentStore
    public let apiUsage: ApiUsageStore

    public init(database: SQLiteDatabase) {
        self.database = database
        self.personas = PersonaStore(database: database)
        self.apiConfigs = ApiConfigStore(database: database)
        self.sessions = ChatSessionStore(database: database)
        self.messages = MessageStore(database: database)
        self.memories = MemoryStore(database: database)
        self.memoryTerms = MemoryTermStore(database: database)
        self.memoryVectors = MemoryVectorStore(database: database)
        self.memoryVersions = MemoryVersionStore(database: database)
        self.memoryConsolidations = MemoryConsolidationStore(database: database)
        self.presets = PersonaPresetStore(database: database)
        self.proactiveRules = ProactiveRuleStore(database: database)
        self.agentIntents = AgentIntentStore(database: database)
        self.apiUsage = ApiUsageStore(database: database)
    }

    /// 建库 + 装好所有仓储（App 启动走这一条）。
    public static func open(path: String) throws -> DatabaseStores {
        let database: SQLiteDatabase
        if path == ":memory:" {
            database = try SQLiteDatabase.inMemory()
        } else {
            database = try SQLiteDatabase(path: path)
        }
        try Schema.create(database)
        return DatabaseStores(database: database)
    }
}
