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
        "sleepEnabled", "wakeThreshold", "dndOnSleep",
        // v13 的人设引导：世界预设 / 话风 / 当前活动（JSON 文本，可空）。
        "worldJson", "speechStyleJson", "activityJson"
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
            .bool(sleepEnabled), .int(wakeThreshold), .bool(dndOnSleep),
            .optionalText(worldJson), .optionalText(speechStyleJson), .optionalText(activityJson)
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
            dndOnSleep: try row.bool("dndOnSleep", false),
            // v13：三列可空；老库没有这三列时 `optionalText` 给 nil（= 没设定）。
            worldJson: try row.optionalText("worldJson"),
            speechStyleJson: try row.optionalText("speechStyleJson"),
            activityJson: try row.optionalText("activityJson")
        )
    }
}

extension ApiConfigEntity: SQLiteRecord {
    public static let tableName = "api_configs"
    public static let columns = [
        "id", "name", "provider", "baseUrl", "apiKeyCipher", "model", "customHeadersJson",
        "allowInsecureHttp", "temperature", "topP", "maxOutputTokens", "frequencyPenalty",
        "presencePenalty", "createdAt",
        "updatedAt", "webToolsEnabled", "webToolsProvider", "webToolsBaseUrl", "webToolsApiKeyCipher"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .text(name), .text(provider.stored), .text(baseUrl),
            .optionalText(apiKeyCipher), .text(model), .text(customHeadersJson),
            .bool(allowInsecureHttp), .optionalReal(temperature), .optionalReal(topP),
            .optionalInt(maxOutputTokens), .optionalReal(frequencyPenalty),
            .optionalReal(presencePenalty), .int(createdAt), .int(updatedAt),
            .bool(webToolsEnabled),
            // 写库一律写规范值：老库里的 `DEEPSEEK` 不会在保存之后继续留在这一列里。
            .text(WebToolsProvider.normalizedStored(webToolsProvider)),
            .text(webToolsBaseUrl), .optionalText(webToolsApiKeyCipher)
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
            // 读库时归一化：v11 老库/旧 DDL 默认值写的是 `DEEPSEEK`，读进来等价于 MESSAGEAPI。
            webToolsProvider: WebToolsProvider.normalizedStored(
                try row.optionalText("webToolsProvider")
            ),
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
        "readAt", "blockIndex"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .text(sessionId), .text(personaId), .text(role.stored), .text(content),
            .int(createdAt), .int(updatedAt), .text(status.stored), .optionalText(errorMessage),
            .optionalText(model), .bool(edited), .optionalText(attachmentsJson),
            .optionalText(toolTraceJson), .optionalInt(readAt), .int(blockIndex)
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
            readAt: try row.optionalInt64("readAt"),
            blockIndex: try row.int("blockIndex")
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
        "createdAt", "updatedAt", "worldJson", "speechStyleJson"
    ]

    public var bindings: [SQLiteValue] {
        [
            .text(id), .text(name), .text(description), .text(prompt), .text(category),
            .optionalText(avatarUri), .int(avatarSeed), .int(createdAt), .int(updatedAt),
            .optionalText(worldJson), .optionalText(speechStyleJson)
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
            updatedAt: try row.int64("updatedAt"),
            worldJson: try row.optionalText("worldJson"),
            speechStyleJson: try row.optionalText("speechStyleJson")
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

    /// 只写「最后一句预览 / 最后一条时间 / updatedAt」三列（0.11.1 审计 P2-15）。
    ///
    /// `ChatViewModel.refreshSessionPreview` 原来是「读整行 → 改三列 → `upsert` 整行 REPLACE」：
    /// 读与写之间别的线程（心跳、主动唤醒）写入的 `unreadCount` / `lastMessagePreview`
    /// 会被回滚成旧快照 —— 0.10.2 P1-1 的同一类问题在 UI 路径上的残留。
    /// 定向 UPDATE 只碰这三列，天然不会覆盖并发写入的未读数。
    ///
    /// - Returns: false 表示会话已经不在了（调用点不该再显示旧预览）。
    @discardableResult
    public func updatePreview(
        id: String,
        preview: String?,
        lastMessageAt: Int64?,
        updatedAt: Int64
    ) throws -> Bool {
        let changed = try database.executeUpdate(
            """
            UPDATE chat_sessions SET lastMessagePreview = ?, lastMessageAt = ?, updatedAt = ?
            WHERE id = ?
            """,
            [
                preview.map { SQLiteValue.text($0) } ?? .null,
                lastMessageAt.map { SQLiteValue.int($0) } ?? .null,
                .int(updatedAt),
                .text(id)
            ]
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

    // MARK: - 删除会话：连带消息与块（v11）

    // 删会话有三条入口（删人格、清空全部聊天、替换导入），以前每条都靠调用方「先删消息再删会话」，
    // 漏一处就会留下孤儿消息。v11 起块表也有同样的风险（块行指向一段不存在的会话），
    // 所以这里把「删会话 = 删它的一切」写死在仓储里，调用方不用再记顺序。

    public override func delete(id: String) throws {
        try database.inTransaction {
            try MessageBlockStore(database: database).deleteMessages(sessionId: id)
            try super.delete(id: id)
        }
    }

    public override func delete(where clause: String, _ arguments: [SQLiteValue] = []) throws {
        let trimmed = clause.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            try deleteAll()
            return
        }
        try database.inTransaction {
            let statement = try database.prepare("SELECT id FROM chat_sessions WHERE \(trimmed)")
            try statement.bind(arguments)
            var ids: [String] = []
            while try statement.step() {
                if let id = statement.text(at: 0) { ids.append(id) }
            }
            let blocks = MessageBlockStore(database: database)
            for id in ids {
                try blocks.deleteMessages(sessionId: id)
            }
            try super.delete(where: trimmed, arguments)
        }
    }

    public override func deleteAll() throws {
        try database.inTransaction {
            try MessageBlockStore(database: database).deleteAllMessages()
            try super.deleteAll()
        }
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

/// 一页消息 = 一块消息 + 它前后还有没有块（区块分页，规范 §3）。
///
/// 界面只认识它：`messages` 直接画，`olderBlockIndex` / `newerBlockIndex` 决定
/// 「上面还有更早的 / 下面还有更新的」两个加载入口显示不显示。
public struct MessageBlockWindow: Equatable, Sendable {
    /// 这一页的块号（块编号可能因为删空而出现空洞，所以不要假设它等于页码）。
    public var blockIndex: Int
    /// 这一块里的消息（`(createdAt, id)` 升序）。
    public var messages: [MessageEntity]
    /// 比它更旧的最近一块的块号（没有就 nil = 到最早了）。
    public var olderBlockIndex: Int?
    /// 比它更新的最近一块的块号（没有就 nil = 这一块就是最新的）。
    public var newerBlockIndex: Int?

    public init(
        blockIndex: Int,
        messages: [MessageEntity],
        olderBlockIndex: Int?,
        newerBlockIndex: Int?
    ) {
        self.blockIndex = blockIndex
        self.messages = messages
        self.olderBlockIndex = olderBlockIndex
        self.newerBlockIndex = newerBlockIndex
    }

    /// 这一块是不是最新那一块（界面据此决定要不要显示「下方还有更新的消息」）。
    public var isNewest: Bool { newerBlockIndex == nil }
}

public final class MessageStore: SQLiteStore<MessageEntity> {

    /// 块表的唯一入口（规范 `spec/messages-blocks/README.md`）。
    ///
    /// 消息的所有写路径都从这里过：`upsert` / `delete` / `deleteAll` 三个覆写把
    /// 「追加开新块、锚点插入、删空删块」的维护收在一处，调用方（聊天、主动开口、
    /// 备份导入、删人格、清空数据……）一行都不用改。
    public lazy var blocks = MessageBlockStore(database: database)

    // MARK: - 写：消息与块元数据一起维护（v11）

    /// 写一条消息（新增走追加规则；已存在保留原块，时间戳变了才重算边界）。
    public override func upsert(_ record: MessageEntity) throws {
        try blocks.upsertMaintainingBlocks(record)
    }

    public override func delete(id: String) throws {
        try blocks.deleteMessage(id: id)
    }

    public override func delete(where clause: String, _ arguments: [SQLiteValue] = []) throws {
        try blocks.deleteMessages(where: clause, arguments)
    }

    public override func deleteAll() throws {
        try blocks.deleteAllMessages()
    }

    /// 按锚点插入（编辑用户消息 = 在锚点那条后面重建一条）。返回 false = 锚点已经不在了。
    ///
    /// iOS 暂时没有「编辑用户消息」的界面入口，但写路径先接好：等编辑入口上线，
    /// 只调这一个方法就会走同一套块规则（锚点所在的块 + 不重平衡），不用再补一次维护逻辑。
    @discardableResult
    public func insert(_ message: MessageEntity, atAnchor anchorId: String) throws -> Bool {
        try blocks.insert(message, atAnchor: anchorId)
    }

    /// **整段**读取（时间升序）。
    ///
    /// 只该给「本来就需要全部消息」的地方用：备份导出、以及没有窗口可用的诊断路径。
    /// 0.11.0 P2-1 之后，**取上下文、拼心跳处境、抽记忆、记忆整理**一律走下面那几个窗口查询；
    /// v11 起「从搜索结果定位」也不再整段读，而是按块读（`MessageBlocks.focus`）。
    public func bySession(_ sessionId: String) throws -> [MessageEntity] {
        try observed("bySession", fetch(where: "sessionId = ?", [.text(sessionId)], orderBy: "createdAt ASC"))
    }

    /// 单测钩子：每物化完一批消息就回调「方法名 + 行数」。
    ///
    /// 为什么需要它：0.11.0 P2-1 的验收要证明「长会话只读窗口」。这件事从返回值上看不出来
    ///（`recent(limit:)` 与「读全文再 suffix」给的东西一模一样），只有数「到底从 SQLite
    /// 捞了多少行」才分得清。生产代码里恒为 nil，每次查询多一次 `if let` 的开销。
    ///
    /// 只该在单线程里装/卸（测试的 `setUp` 里装上，跑完置 nil），不做加锁：
    /// 写成 `NSLock` 的话每次查询都要抢一次锁，比它想验证的东西本身还贵。
    public var queryObserver: ((String, Int) -> Void)?

    private func observed(_ method: String, _ rows: [MessageEntity]) -> [MessageEntity] {
        queryObserver?(method, rows.count)
        return rows
    }

    /// 最近 `limit` 条（按时间升序返回）。
    ///
    /// 「最近 N 条」的窗口读取（拼提示词的上下文、主动开口读历史都走这里）。
    /// 聊天页的首屏在 v11 已改成「只读最新一块」(`blocksAround`)，不再用这个函数。
    /// 0.11.0 P2-1 起，`ChatService` 的上下文窗口（`PromptBuilder.contextRecentLimit`）也走这里。
    public func recent(sessionId: String, limit: Int) throws -> [MessageEntity] {
        guard limit > 0 else { return [] }
        let rows = try fetch(
            where: "sessionId = ?", [.text(sessionId)],
            orderBy: "createdAt DESC, id DESC", limit: limit
        )
        return observed("recent", Array(rows.reversed()))
    }

    /// 「正文算不算有内容」的 SQL 判定（0.11.1 审计 P2-11）。
    ///
    /// SQLite 的 `trim(x)` 默认只去空格（0x20），不去 `\t` / `\n` / `\r`；
    /// 而 Swift 的 `trimmingCharacters(in: .whitespacesAndNewlines)` 会去。
    /// 于是「只有换行/制表符」的消息会被 SQL 当成对话：拼进提示词变成 `对方：` 空行、
    /// 被 `countDialogue` 计入「对话够不够多」（提前触发记忆抽取）、被
    /// `latestUserMessage` 当成「对方上次说的话」。这里显式把 9/10/13/32 都当空白去掉，
    /// 与 Swift 侧同一语义；四个查询（`recentDialogue` / `countDialogue` /
    /// `latestUserMessage` / `latestAssistantMessage`）共用这一个表达式。
    static let hasVisibleContentSQL =
        "length(trim(content, char(9) || char(10) || char(13) || char(32))) > 0"

    /// 最近 `limit` 条**对话消息**（`role` 非 `SYSTEM`、正文 trim 之后非空，时间升序）。
    ///
    /// 拼心跳处境（「最近几句」）与抽记忆的对话窗口都用它：`system` 提示词与空消息
    /// 对这两处都没有意义，而「先全量读再 filter」正是 P2-1 要消掉的那件事。
    public func recentDialogue(sessionId: String, limit: Int) throws -> [MessageEntity] {
        guard limit > 0 else { return [] }
        let rows = try fetch(
            where: "sessionId = ? AND role <> ? AND \(Self.hasVisibleContentSQL)",
            [.text(sessionId), .text(MessageRole.system.stored)],
            orderBy: "createdAt DESC, id DESC",
            limit: limit
        )
        return observed("recentDialogue", Array(rows.reversed()))
    }

    /// 这段会话里「能算进对话」的消息一共有多少条（抽记忆判断「对话够不够多」用）。
    ///
    /// 只查计数、不读正文：长会话下 `count(*)` 走索引，比把几千条正文捞出来再 `filter` 便宜几个数量级。
    public func countDialogue(sessionId: String) throws -> Int {
        try count(
            where: "sessionId = ? AND role <> ? AND \(Self.hasVisibleContentSQL)",
            [.text(sessionId), .text(MessageRole.system.stored)]
        )
    }

    /// 最后一次「用户说的话」（精确查询：`ORDER BY createdAt DESC, id DESC LIMIT 1`）。
    ///
    /// 为什么不能拿窗口里最后一条用户消息代替：窗口是按「最近 N 条」截的，
    /// 这 N 条可能全是助手消息，用户上一次说话就被截在窗口外面了（0.11.0 P2-1）。
    public func latestUserMessage(sessionId: String) throws -> MessageEntity? {
        let rows = try fetch(
            where: "sessionId = ? AND role = ? AND \(Self.hasVisibleContentSQL)",
            [.text(sessionId), .text(MessageRole.user.stored)],
            orderBy: "createdAt DESC, id DESC",
            limit: 1
        )
        return observed("latestUserMessage", rows).first
    }

    /// 最后一次「他（助手）说的话」，语义同 `latestUserMessage`。
    public func latestAssistantMessage(sessionId: String) throws -> MessageEntity? {
        let rows = try fetch(
            where: "sessionId = ? AND role = ? AND \(Self.hasVisibleContentSQL)",
            [.text(sessionId), .text(MessageRole.assistant.stored)],
            orderBy: "createdAt DESC, id DESC",
            limit: 1
        )
        return observed("latestAssistantMessage", rows).first
    }

    /// 游标**之后**的消息（严格晚于 `(createdAt, id)`，按时间升序）。
    ///
    /// 聊天页的「只追加」仍用它：新消息（心跳 / 主动开口 / 流式结束）接在已加载窗口后面，
    /// 已加载的历史一条都不少（0.11.0 P2-5）。第二排序键 `id` 是「同一毫秒两条」的稳定游标。
    public func pageAfter(
        sessionId: String,
        createdAt: Int64,
        id: String,
        limit: Int
    ) throws -> [MessageEntity] {
        guard limit > 0 else { return [] }
        let rows = try fetch(
            where: "sessionId = ? AND (createdAt > ? OR (createdAt = ? AND id > ?))",
            [.text(sessionId), .int(createdAt), .int(createdAt), .text(id)],
            orderBy: "createdAt ASC, id ASC",
            limit: limit
        )
        return observed("pageAfter", rows)
    }

    public func count(sessionId: String) throws -> Int {
        try count(where: "sessionId = ?", [.text(sessionId)])
    }

    // MARK: - 区块分页（v11，规范 §3）

    /// 一页 = 一块（或更少：这块被删剩几条）。`blockIndex` 必须是真实存在的块。
    ///
    /// 返回的 `messages` 按 `(createdAt, id)` 升序；`olderBlockIndex` / `newerBlockIndex`
    /// 是「按块号找得到的上一块 / 下一块」（块可能因为删空而留下编号空洞，所以不是 ±1）。
    public func block(_ blockIndex: Int, sessionId: String) throws -> MessageBlockWindow? {
        guard let block = try blocks.block(sessionId: sessionId, blockIndex: blockIndex) else { return nil }
        let messages = try observed(
            "block",
            blocks.blockMessages(sessionId: sessionId, blockIndex: blockIndex)
        )
        return MessageBlockWindow(
            blockIndex: block.blockIndex,
            messages: messages,
            olderBlockIndex: try blocks.olderBlockIndex(sessionId: sessionId, before: block.blockIndex),
            newerBlockIndex: try blocks.newerBlockIndex(sessionId: sessionId, after: block.blockIndex)
        )
    }

    /// 这段会话最新一块的块号（没有消息时 nil）。首屏用它定位，不扫消息表。
    public func lastBlockIndex(sessionId: String) throws -> Int? {
        try blocks.lastBlockIndex(sessionId: sessionId)
    }

    /// 取 `blockIndex` 那一块，并带上它的上一块 / 下一块编号。
    ///
    /// - Parameter blockIndex: nil = 最新一块（首屏）；给了块号但那一块已经被删空时，
    ///   回落到「比它更旧的最近一块」（界面上就是「这一块不见了，给你看前面那块」），
    ///   再没有就返回 nil（这段会话已经空了）。
    public func blocksAround(sessionId: String, blockIndex: Int? = nil) throws -> MessageBlockWindow? {
        guard let requested = blockIndex else {
            guard let last = try blocks.lastBlockIndex(sessionId: sessionId) else { return nil }
            return try block(last, sessionId: sessionId)
        }
        if let exact = try block(requested, sessionId: sessionId) { return exact }
        guard let older = try blocks.olderBlockIndex(sessionId: sessionId, before: requested) else {
            return nil
        }
        return try block(older, sessionId: sessionId)
    }

    /// 比 `blockIndex` 更旧的最近一块的块号（向上加载一页）。
    public func olderBlockIndex(sessionId: String, before blockIndex: Int) throws -> Int? {
        try blocks.olderBlockIndex(sessionId: sessionId, before: blockIndex)
    }

    /// 比 `blockIndex` 更新的最近一块的块号（向下加载一页）。
    public func newerBlockIndex(sessionId: String, after blockIndex: Int) throws -> Int? {
        try blocks.newerBlockIndex(sessionId: sessionId, after: blockIndex)
    }

    /// 一条消息落在哪一块（搜索结果跳进会话时用它定位）。
    public func blockIndex(ofMessage id: String) throws -> Int? {
        try blocks.blockIndex(ofMessage: id)
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

    /// 会话 / 人格只为了给命中补标题与头像，缓存一下避免同一次检索里 N+1。
    private lazy var sessionLookup = ChatSessionStore(database: database)
    private lazy var personaLookup = PersonaStore(database: database)

    /// 检索聊天记录：**逐块枚举 → 每块读入内存精排 → 键集游标 → 补会话/人格信息**。
    ///
    /// 与 v10 的区别（规范 §4「无候选上限」）：不再用一条 `WHERE content LIKE ?` 把整张表
    /// 粗筛一遍、也不再有 500 条候选上限。检索按块走：
    /// 1. 枚举（可先按人格过滤的）会话的块，块号升序；
    /// 2. 每块一次查询读进来，用共享的 `SearchRanking` 精排（与 `spec/search/cases` 同一套规则）；
    /// 3. 只保留「排序位置严格在游标之后」的前 `limit + 1` 条（多出来的那一条就是 `hasMore` 的证据）；
    /// 4. 一块读完就丢掉它的正文，峰值内存 ≈ 一块 + 一页 + 前沿，与会话总长无关。
    ///
    /// 为什么仍然不建倒排索引表：排序规则要的是「命中词元个数 + `ChineseTerms.relevance`」，
    /// SQL 里算不出来；真要做就得把词元、权重、分值的维护搬到每条写消息的路径上
    ///（发消息、撤回、编辑、备份导入、清空上下文……），一处漏了就会「搜不到自己刚说的话」。
    ///
    /// 用户输入里的 `%` / `_` / `\` 现在只影响内存里的匹配（`SearchRanking.isHit` 是字面比较），
    /// 不再拼进 SQL，所以「100%」不会被当成通配符；「100」这个词元仍会召回「1000」，
    /// 那是规范规定的词元召回（`spec/search/README.md` Ranking rules 第 1 条）。
    ///
    /// - Parameters:
    ///   - query: 查询词（内部 trim；空查询返回空页，不是「返回全部」）。
    ///   - personaId: 只看某个人格（nil = 全部）。
    ///   - since: 只要这个时刻之后（含边界）的消息（nil = 全部时间）。
    ///   - limit: 这一页最多几条。
    ///   - cursor: 上一页返回的游标（nil = 第一页）。
    ///   - snippetWindow: 摘要窗口宽度，默认 60（与 `spec/search` 一致）。
    public func search(
        query: String,
        personaId: String? = nil,
        since: Int64? = nil,
        limit: Int = MessageStore.defaultSearchPageSize,
        cursor: SearchCursor? = nil,
        snippetWindow: Int = SearchSnippet.defaultWindow
    ) throws -> SearchPage {
        let trimmed = query.trimmed
        guard !trimmed.isEmpty, limit > 0 else { return .empty }

        // 前沿：全局最优的 limit + 1 条（第 limit + 1 条只用来判断「还有下一页」）。
        let frontierLimit = limit + 1
        var frontier: [SearchCandidate] = []
        frontier.reserveCapacity(frontierLimit)

        // 1) + 2) 逐块：读一块、精排、按键集游标并入前沿，读完即丢。
        var visitedBlocks = 0
        try enumerateBlocks(personaId: personaId) { sessionId, blockIndex in
            visitedBlocks += 1
            var clause = "sessionId = ? AND blockIndex = ?"
            var arguments: [SQLiteValue] = [.text(sessionId), .int(blockIndex)]
            if let personaId {
                clause += " AND personaId = ?"
                arguments.append(.text(personaId))
            }
            if let since {
                clause += " AND createdAt >= ?"
                arguments.append(.int(since))
            }
            let rows = try observed(
                "searchBlock",
                fetch(where: clause, arguments, orderBy: "createdAt ASC, id ASC")
            )
            if rows.isEmpty { return }
            let items = rows.map {
                SearchRankItem(
                    id: $0.id,
                    content: $0.content,
                    createdAt: $0.createdAt,
                    personaId: $0.personaId
                )
            }
            let ranked = SearchRanking.rank(items, query: trimmed, personaId: personaId, since: since)
            if ranked.isEmpty { return }
            var messagesById: [String: MessageEntity] = [:]
            for row in rows {
                messagesById[row.id] = row
            }
            for hit in ranked {
                if let cursor, !SearchRanking.comesAfter(hit, cursor: cursor) { continue }
                guard let message = messagesById[hit.item.id] else { continue }
                Self.insert(
                    SearchCandidate(hit: hit, message: message),
                    into: &frontier,
                    cap: frontierLimit
                )
            }
        }
        if visitedBlocks == 0 || frontier.isEmpty { return .empty }

        // 3) 这一页 + 「还有更多」的证据（多留的那一条）。
        let hasMore = frontier.count > limit
        let page = Array(frontier.prefix(limit))

        // 4) 补会话标题 / 人格名 / 头像 —— 只对**这一页**做，且同样的 id 只查一次。
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
        for candidate in page {
            let message = candidate.message
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

        let nextCursor = hasMore ? page.last.map { SearchCursor($0.hit) } : nil
        return SearchPage(hits: hits, hasMore: hasMore && nextCursor != nil, nextCursor: nextCursor)
    }

    /// 一条命中 + 它的完整消息（前沿要留着它，好在这一页返回时直接拼 `SearchHit`）。
    private struct SearchCandidate {
        var hit: SearchRankedHit
        var message: MessageEntity
    }

    /// 把一条候选按规范四键序插进前沿（前沿按 `SearchRanking.precedes` 升序，最多 `cap` 条）。
    ///
    /// 用二分插入而不是「收集完再排序」：前沿只有 `limit + 1` 条，插入是 O(log limit)，
    /// 整次检索的内存与时间都不会随命中总数增长（这正是「无上限」却仍然便宜的原因）。
    private static func insert(_ candidate: SearchCandidate, into frontier: inout [SearchCandidate], cap: Int) {
        if frontier.count >= cap, let last = frontier.last,
           !SearchRanking.precedes(candidate.hit, last.hit) {
            return
        }
        var low = 0
        var high = frontier.count
        while low < high {
            let mid = (low + high) / 2
            if SearchRanking.precedes(frontier[mid].hit, candidate.hit) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        frontier.insert(candidate, at: low)
        if frontier.count > cap {
            frontier.removeLast()
        }
    }

    /// 逐块枚举（块号升序）：**一次只回调一块**，块清单本身不物化。
    ///
    /// 人格过滤下沉到会话子查询（一段会话只属于一个人格）。一条块游标在 `step()` 期间
    /// 又开新的语句读这一块的消息 —— SQLite 允许同一个连接上有多条活动语句（各自一个游标），
    /// 因此这里不需要把块清单先读进数组：即便有几万块，内存里也只有「下一块」这一个编号。
    private func enumerateBlocks(
        personaId: String?,
        _ body: (String, Int) throws -> Void
    ) throws {
        var sql = """
            SELECT sessionId, blockIndex FROM message_blocks
            WHERE sessionId IN (SELECT id FROM chat_sessions
        """
        var arguments: [SQLiteValue] = []
        if let personaId {
            sql += " WHERE personaId = ?"
            arguments.append(.text(personaId))
        }
        sql += ") ORDER BY sessionId ASC, blockIndex ASC"
        let statement = try database.prepare(sql)
        try statement.bind(arguments)
        while try statement.step() {
            let sessionId = statement.text(at: 0) ?? ""
            let blockIndex = Int(statement.int64(at: 1))
            try body(sessionId, blockIndex)
        }
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
    /// 分块历史的块表（规范 `spec/messages-blocks/README.md`）。
    public let messageBlocks: MessageBlockStore
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
        self.messageBlocks = MessageBlockStore(database: database)
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
