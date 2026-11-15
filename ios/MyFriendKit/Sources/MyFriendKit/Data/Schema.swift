import Foundation

/// SQLite 建表语句：表名与列名逐字对齐 Android Room v12
/// （`spec/messages-blocks/README.md` 的 `messages.blockIndex` + `message_blocks`）。
///
/// 为什么不直接用 `Entities.kt` 的 data class 生成：SQLite 的列顺序、`NOT NULL`、
/// 默认值都是 Room 导出 schema 的一部分，手写一份「看得见」的 DDL，评审时能一眼对照；
/// 也让 iOS 这边的库结构和 Android 的导出文件可以直接 diff。
///
/// 几点与 Room 的对齐说明：
/// - `user_version = 12`：Room 用 version 表达 schema 演进，iOS 用同一个数字，
///   这样「这份库是 v12」这件事两端含义相同（v11 = 分块历史：`messages.blockIndex`
///   + `message_blocks`，见 `spec/messages-blocks/README.md`；v12 = 联网工具可以选
///   第三方搜索服务：`api_configs.webToolsProvider`，见 `spec/web-search/README.md`）。
/// - 不建 `room_master_table`：那是 Room 用来校验 identityHash 的内部表，
///   iOS 不读 Room 的迁移历史；真正需要互通的是**备份 JSON**，不是数据库文件。
/// - 布尔一律 `INTEGER NOT NULL`（0/1），枚举一律 `TEXT`（存 Kotlin 枚举名），
///   时间一律 `INTEGER`（毫秒时间戳），金额一律 `INTEGER`（micro-USD）——
///   与 Room 的 affinity 完全一致，将来若真要读 Android 导出的库也不会错位。
public enum Schema {

    /// 与 Android Room 的数据库版本号一致。
    ///
    /// 1.9 = v10：一人一段会话（唯一索引 + 迁移）、消息已读、人格细分字段、会话覆盖两列。
    /// 0.12 = v11：分块历史（`messages.blockIndex` + `message_blocks`）、区块分页、无上限检索。
    /// 0.12（同版第二次 schema 变更）= v12：`api_configs.webToolsProvider`（搜索服务选择）。
    public static let version: Int32 = 12

    /// 「记录型」表名（每张表都有一个 `SQLiteRecord` 实体 + 仓储，测试用它核对「一张不少」）。
    ///
    /// 这 13 张是 iOS 第一版就有的；`supplementaryTables` 里那两张是 1.8 遗留的缺表，
    /// 0.9.1 补进了建表语句（见下面的常量说明），`derivedTables` 是 v11 的派生表 ——
    /// **完整表清单**是 `allTables`。
    public static let tables: [String] = [
        "personas",
        "chat_sessions",
        "messages",
        "memories",
        "memory_terms",
        "memory_vectors",
        "memory_versions",
        "memory_consolidations",
        "persona_presets",
        "proactive_rules",
        "agent_intents",
        "api_configs",
        "api_usage"
    ]

    /// 一人一段会话的唯一索引名（迁移与测试都按这个名字找它）。
    public static let uniquePersonaSessionIndex = "index_chat_sessions_personaId_unique"

    // MARK: - 补进 v10 的两张「服务层用表」

    /// 自主循环「想一下」的留痕（心跳的「刚想过」、Presence 的「在想事情」、今天的主动次数都读它）。
    public static let agentTicksTable = "agent_ticks"
    /// 只被提到过一次的候选记忆（同一件事第二次提到才升级成长期记忆）。
    public static let memoryEchoesTable = "memory_echoes"

    /// 这两张表**不是记录型仓储**（读写是手写 SQL，见 `AgentTickStore` / `MemoryEchoStore`），
    /// 所以不在 `tables` 里；但它们的 DDL 与其他表一起在 `createStatements` 中，
    /// `create()` 每次打开库都会执行（全部 `IF NOT EXISTS`），因此**老库升级后也会被补建**。
    ///
    /// 为什么补 DDL 不改 `user_version`：这是 1.8 就欠着的两张表（Android Room v10 里一直有），
    /// 属于「iOS 建表语句漏了」而不是 schema 版本演进；补上之后当次的 `user_version` 没有变
    ///（0.9.1 时是 10，v11 之后是 11），两端「同一版号同一含义」这条约定不受影响。
    public static let supplementaryTables: [String] = [agentTicksTable, memoryEchoesTable]

    // MARK: - v11 的派生表：分块历史

    /// 分块历史的块元数据表（规范 `spec/messages-blocks/README.md` §1）。
    public static let messageBlocksTable = "message_blocks"

    /// 派生表：内容完全由 `messages` 决定（表里的每一行都能从消息重算出来），
    /// 所以它没有独立的业务实体/仓储，迁移与写入都走 `MessageBlockStore`。
    ///
    /// 与 `tables` 分开列，是为了让「记录型表 13 张」「派生表 1 张」这两件事在测试里
    /// 各自可断言 —— 两者混在一起会让「一张表一个实体」这条约定失效。
    public static let derivedTables: [String] = [messageBlocksTable]

    /// 完整表清单（13 张记录型 + 2 张服务层用表 + 1 张派生表）。
    public static let allTables: [String] = tables + supplementaryTables + derivedTables

    /// `message_blocks` 的建表语句。
    ///
    /// 单独抽成常量是因为**迁移要先建它再回填**（`createStatements` 在迁移之后才执行），
    /// 两处必须是同一份 DDL，否则「升级上来的库」和「新装的库」会差一列。
    ///
    /// 索引：主键 `(sessionId, blockIndex)` 覆盖「按会话取一块 / 取上一块 / 取下一块 /
    /// 按块号升序枚举」；另建一条与 Room `@Index` 同名的普通索引，保证两端表结构可逐字 diff
    ///（`createStatements` 里那条 `index_message_blocks_sessionId_blockIndex`）。
    public static let messageBlocksDDL = """
        CREATE TABLE IF NOT EXISTS message_blocks (
            sessionId TEXT NOT NULL,
            blockIndex INTEGER NOT NULL,
            firstMessageId TEXT NOT NULL,
            lastMessageId TEXT NOT NULL,
            firstCreatedAt INTEGER NOT NULL,
            lastCreatedAt INTEGER NOT NULL,
            messageCount INTEGER NOT NULL,
            PRIMARY KEY (sessionId, blockIndex)
        )
        """

    /// v11 给 `messages` 加的列：块号（默认 0，迁移里按 `(createdAt, id)` 回填）。
    public static let messageBlockColumn = (name: "blockIndex", ddl: "INTEGER NOT NULL DEFAULT 0")

    /// v12 给 `api_configs` 加的列：联网工具用哪个搜索服务（`spec/web-search/README.md` §1）。
    ///
    /// `NOT NULL DEFAULT 'DEEPSEEK'` 是规范里逐字写明的：老库补列时每一行都自动落到
    /// 旧的 provider 名（0.12 改名后读库时归一化成 `MESSAGEAPI`，与之前的行为等价）；
    /// **DDL 默认值不变**，也不为改名升 schema —— 归一化只在代码层做。
    public static let webToolsProviderColumn = (
        name: "webToolsProvider",
        ddl: "TEXT NOT NULL DEFAULT 'DEEPSEEK'"
    )

    /// 1.9 给 `personas` 加的列（迁移按这个顺序 ADD COLUMN，老库补列也用它）。
    ///
    /// 为什么是「可空 + 代码里有默认值」而不是 `NOT NULL DEFAULT x`：
    /// 老数据不需要回填（人格没填过这些字段，本来就该走代码默认值），
    /// 而 SQLite 的 ADD COLUMN 对可空列最省事、也最不会写坏已有行。
    public static let personaProfileColumns: [(name: String, ddl: String)] = [
        ("traits", "TEXT"),
        ("likes", "TEXT"),
        ("behaviors", "TEXT"),
        ("backstory", "TEXT"),
        ("quirks", "TEXT"),
        ("proactiveLevel", "INTEGER"),
        ("memoryRetention", "INTEGER"),
        ("extractionEvery", "INTEGER"),
        ("sleepEnabled", "INTEGER"),
        ("wakeThreshold", "INTEGER"),
        ("dndOnSleep", "INTEGER")
    ]

    /// 1.9 给 `chat_sessions` 加的列（会话级覆盖，空 = 继承人格）。
    public static let sessionOverrideColumns: [(name: String, ddl: String)] = [
        ("memoryRetentionOverride", "INTEGER"),
        ("extractionEveryOverride", "INTEGER")
    ]

    /// 第一条 SQL 总是建表，后面的 index 只是为了让「按 persona 查、按时间排」不扫全表。
    public static let createStatements: [String] = [
        """
        CREATE TABLE IF NOT EXISTS personas (
            id TEXT NOT NULL,
            name TEXT NOT NULL,
            description TEXT NOT NULL,
            avatarUri TEXT,
            avatarSeed INTEGER NOT NULL,
            prompt TEXT NOT NULL,
            category TEXT NOT NULL,
            version INTEGER NOT NULL,
            defaultApiConfigId TEXT,
            modelOverride TEXT,
            isDefault INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            personaCardJson TEXT,
            quietSleepMinutes INTEGER,
            quietWakeMinutes INTEGER,
            mood TEXT,
            moodIntensity INTEGER,
            moodReason TEXT,
            moodUpdatedAt INTEGER,
            moodNudgedAt INTEGER,
            traits TEXT,
            likes TEXT,
            behaviors TEXT,
            backstory TEXT,
            quirks TEXT,
            proactiveLevel INTEGER,
            memoryRetention INTEGER,
            extractionEvery INTEGER,
            sleepEnabled INTEGER,
            wakeThreshold INTEGER,
            dndOnSleep INTEGER,
            PRIMARY KEY (id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_personas_isDefault ON personas (isDefault)",
        "CREATE INDEX IF NOT EXISTS index_personas_updatedAt ON personas (updatedAt)",

        """
        CREATE TABLE IF NOT EXISTS persona_presets (
            id TEXT NOT NULL,
            name TEXT NOT NULL,
            description TEXT NOT NULL,
            prompt TEXT NOT NULL,
            category TEXT NOT NULL,
            avatarUri TEXT,
            avatarSeed INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            PRIMARY KEY (id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_persona_presets_updatedAt ON persona_presets (updatedAt)",
        "CREATE INDEX IF NOT EXISTS index_persona_presets_category ON persona_presets (category)",

        // 注意：apiKeyCipher / customHeadersJson / webToolsApiKeyCipher 只在**本机数据库**里，
        // 永远不进备份（BackupApiConfig 没有这三个字段）。
        // 1.9：Harness 三列整组删除（实体、备份、DDL 都不再有）。
        """
        CREATE TABLE IF NOT EXISTS api_configs (
            id TEXT NOT NULL,
            name TEXT NOT NULL,
            provider TEXT NOT NULL,
            baseUrl TEXT NOT NULL,
            apiKeyCipher TEXT,
            model TEXT NOT NULL,
            customHeadersJson TEXT NOT NULL,
            allowInsecureHttp INTEGER NOT NULL,
            temperature REAL,
            topP REAL,
            maxOutputTokens INTEGER,
            frequencyPenalty REAL,
            presencePenalty REAL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            webToolsEnabled INTEGER NOT NULL DEFAULT 0,
            webToolsProvider TEXT NOT NULL DEFAULT 'DEEPSEEK',
            webToolsBaseUrl TEXT NOT NULL DEFAULT '',
            webToolsApiKeyCipher TEXT,
            PRIMARY KEY (id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_api_configs_updatedAt ON api_configs (updatedAt)",

        """
        CREATE TABLE IF NOT EXISTS chat_sessions (
            id TEXT NOT NULL,
            personaId TEXT NOT NULL,
            apiConfigId TEXT,
            model TEXT NOT NULL,
            title TEXT NOT NULL,
            lastMessagePreview TEXT,
            lastMessageAt INTEGER,
            unreadCount INTEGER NOT NULL,
            pinned INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            lastPersonaVersion INTEGER NOT NULL,
            syncState TEXT NOT NULL,
            syncedPersonaVersion INTEGER,
            syncedMemoriesHash TEXT,
            serverSessionId TEXT,
            lastSyncError TEXT,
            lastSyncAt INTEGER,
            lastMemoryExtractedCount INTEGER NOT NULL,
            historySummary TEXT,
            historySummarizedUpTo INTEGER,
            historySummaryAt INTEGER,
            memoryRetentionOverride INTEGER,
            extractionEveryOverride INTEGER,
            PRIMARY KEY (id)
        )
        """,
        // 一人一段：personaId 上必须是唯一索引，否则「取人格的那一段」这件事随时会被写坏。
        // 注意它必须建在**迁移删除多余会话之后**（见 migrateLegacyData）。
        "CREATE UNIQUE INDEX IF NOT EXISTS \(uniquePersonaSessionIndex) ON chat_sessions (personaId)",
        "CREATE INDEX IF NOT EXISTS index_chat_sessions_lastMessageAt ON chat_sessions (lastMessageAt)",
        "CREATE INDEX IF NOT EXISTS index_chat_sessions_pinned ON chat_sessions (pinned)",

        """
        CREATE TABLE IF NOT EXISTS messages (
            id TEXT NOT NULL,
            sessionId TEXT NOT NULL,
            personaId TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            status TEXT NOT NULL,
            errorMessage TEXT,
            model TEXT,
            edited INTEGER NOT NULL,
            attachmentsJson TEXT,
            toolTraceJson TEXT,
            readAt INTEGER,
            blockIndex INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_messages_sessionId_createdAt ON messages (sessionId, createdAt)",
        "CREATE INDEX IF NOT EXISTS index_messages_sessionId_role ON messages (sessionId, role)",
        "CREATE INDEX IF NOT EXISTS index_messages_createdAt ON messages (createdAt)",
        // v11：区块分页「按 (sessionId, blockIndex) 取一块、块内按 createdAt 排」，
        // 读多少行与会话长度无关。索引名与 Android Room v11 的导出 schema
        //（`app/schemas/com.myfriend.app.data.local.MyFriendDatabase/11.json`）逐字一致。
        "CREATE INDEX IF NOT EXISTS index_messages_sessionId_blockIndex_createdAt "
            + "ON messages (sessionId, blockIndex, createdAt)",

        // v11：分块历史的块元数据。内容由 messages 派生（见 `MessageBlockStore`）。
        messageBlocksDDL,
        // 与 Room 的 `@Index("sessionId", "blockIndex")` 对齐（主键虽然已经覆盖，
        // 但两端表结构要能直接 diff：索引名与列序都照导出文件写）。
        "CREATE INDEX IF NOT EXISTS index_message_blocks_sessionId_blockIndex "
            + "ON message_blocks (sessionId, blockIndex)",

        """
        CREATE TABLE IF NOT EXISTS memories (
            id TEXT NOT NULL,
            personaId TEXT NOT NULL,
            content TEXT NOT NULL,
            category TEXT NOT NULL,
            importance INTEGER NOT NULL,
            pinned INTEGER NOT NULL,
            sourceSessionId TEXT,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            lastUsedAt INTEGER NOT NULL,
            useCount INTEGER NOT NULL,
            drawer TEXT,
            sourceQuote TEXT,
            status TEXT NOT NULL,
            strength REAL NOT NULL,
            archivedAt INTEGER,
            consolidatedAt INTEGER,
            tags TEXT,
            happenedAt INTEGER,
            originalContent TEXT,
            driftCount INTEGER NOT NULL,
            PRIMARY KEY (id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_memories_personaId ON memories (personaId)",
        "CREATE INDEX IF NOT EXISTS index_memories_personaId_importance ON memories (personaId, importance)",
        "CREATE INDEX IF NOT EXISTS index_memories_updatedAt ON memories (updatedAt)",

        // 中文倒排索引：字二元组 → 记忆。主键是 (memoryId, term)，一个记忆有很多词。
        """
        CREATE TABLE IF NOT EXISTS memory_terms (
            memoryId TEXT NOT NULL,
            term TEXT NOT NULL,
            weight REAL NOT NULL,
            PRIMARY KEY (memoryId, term)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_memory_terms_term ON memory_terms (term)",

        // 本地向量：一条记忆一个 provider 一份向量；BLOB 是归一化后的 float32 小端字节。
        """
        CREATE TABLE IF NOT EXISTS memory_vectors (
            memoryId TEXT NOT NULL,
            provider TEXT NOT NULL,
            dim INTEGER NOT NULL,
            vector BLOB NOT NULL,
            updatedAt INTEGER NOT NULL,
            PRIMARY KEY (memoryId, provider)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_memory_vectors_provider ON memory_vectors (provider)",

        """
        CREATE TABLE IF NOT EXISTS memory_versions (
            id TEXT NOT NULL,
            memoryId TEXT NOT NULL,
            at INTEGER NOT NULL,
            content TEXT NOT NULL,
            beforeContent TEXT,
            reason TEXT,
            moodLabel TEXT,
            PRIMARY KEY (id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_memory_versions_memoryId_at ON memory_versions (memoryId, at)",

        """
        CREATE TABLE IF NOT EXISTS memory_consolidations (
            id TEXT NOT NULL,
            personaId TEXT NOT NULL,
            at INTEGER NOT NULL,
            kept INTEGER NOT NULL,
            merged INTEGER NOT NULL,
            archived INTEGER NOT NULL,
            forgotten INTEGER NOT NULL,
            summary TEXT NOT NULL,
            modelName TEXT,
            PRIMARY KEY (id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_memory_consolidations_personaId_at ON memory_consolidations (personaId, at)",
        "CREATE INDEX IF NOT EXISTS index_memory_consolidations_at ON memory_consolidations (at)",

        """
        CREATE TABLE IF NOT EXISTS proactive_rules (
            id TEXT NOT NULL,
            personaId TEXT NOT NULL,
            sessionId TEXT,
            kind TEXT NOT NULL,
            timeOfDayMinutes INTEGER,
            intervalMinutes INTEGER,
            hint TEXT NOT NULL,
            note TEXT NOT NULL,
            enabled INTEGER NOT NULL,
            pending INTEGER NOT NULL,
            source TEXT NOT NULL,
            nextTriggerAt INTEGER NOT NULL,
            lastTriggeredAt INTEGER,
            lastError TEXT,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            PRIMARY KEY (id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_proactive_rules_personaId ON proactive_rules (personaId)",
        "CREATE INDEX IF NOT EXISTS index_proactive_rules_nextTriggerAt ON proactive_rules (nextTriggerAt)",
        "CREATE INDEX IF NOT EXISTS index_proactive_rules_enabled ON proactive_rules (enabled)",

        """
        CREATE TABLE IF NOT EXISTS agent_intents (
            id TEXT NOT NULL,
            personaId TEXT NOT NULL,
            sessionId TEXT,
            kind TEXT NOT NULL,
            earliestAt INTEGER NOT NULL,
            latestAt INTEGER NOT NULL,
            note TEXT NOT NULL,
            hint TEXT NOT NULL,
            status TEXT NOT NULL,
            ruleId TEXT,
            createdAt INTEGER NOT NULL,
            sentAt INTEGER,
            lastError TEXT,
            PRIMARY KEY (id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_agent_intents_personaId_status ON agent_intents (personaId, status)",
        "CREATE INDEX IF NOT EXISTS index_agent_intents_earliestAt ON agent_intents (earliestAt)",
        "CREATE INDEX IF NOT EXISTS index_agent_intents_latestAt ON agent_intents (latestAt)",

        """
        CREATE TABLE IF NOT EXISTS api_usage (
            id TEXT NOT NULL,
            at INTEGER NOT NULL,
            kind TEXT NOT NULL,
            personaId TEXT,
            sessionId TEXT,
            modelName TEXT,
            promptTokens INTEGER NOT NULL,
            completionTokens INTEGER NOT NULL,
            costMicroUsd INTEGER NOT NULL,
            PRIMARY KEY (id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_api_usage_at ON api_usage (at)",
        // 心跳/自主循环的思考留痕：Presence、「刚想过没」、「刚被你叫醒」都读它。
        // 表名见 `Schema.agentTicksTable`（1.8 遗留的缺表，0.9.1 补进建表语句）。
        """
        CREATE TABLE IF NOT EXISTS agent_ticks (
            id TEXT NOT NULL,
            personaId TEXT NOT NULL,
            at INTEGER NOT NULL,
            thought TEXT NOT NULL,
            actionJson TEXT NOT NULL,
            outcome TEXT NOT NULL,
            messageId TEXT,
            PRIMARY KEY (id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_agent_ticks_personaId_at ON agent_ticks (personaId, at)",
        "CREATE INDEX IF NOT EXISTS index_agent_ticks_at ON agent_ticks (at)",

        // 「同一件事第二次提到才落库」的回声：只被提过一次的候选先放这里，别急着变成记忆。
        // 表名见 `Schema.memoryEchoesTable`（同上，补建不改 user_version）。
        """
        CREATE TABLE IF NOT EXISTS memory_echoes (
            id TEXT NOT NULL,
            personaId TEXT NOT NULL,
            drawer TEXT NOT NULL,
            content TEXT NOT NULL,
            importance INTEGER NOT NULL,
            sourceQuote TEXT NOT NULL,
            categoryName TEXT NOT NULL,
            confidence REAL NOT NULL,
            createdAt INTEGER NOT NULL,
            lastSeenAt INTEGER NOT NULL,
            PRIMARY KEY (id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_memory_echoes_personaId_drawer ON memory_echoes (personaId, drawer)",
        "CREATE INDEX IF NOT EXISTS index_memory_echoes_createdAt ON memory_echoes (createdAt)",

        "CREATE INDEX IF NOT EXISTS index_api_usage_kind_at ON api_usage (kind, at)",
        "CREATE INDEX IF NOT EXISTS index_api_usage_personaId_at ON api_usage (personaId, at)"
    ]

    /// 建库入口：App 首次启动、以及测试里建临时库都走这里。
    ///
    /// 幂等（全部 `IF NOT EXISTS`），所以每次启动都可以无脑调用；
    /// `user_version` 是「这份库的 schema 是第几版」的唯一事实来源。
    ///
    /// 老库（v8–v11）走两条路：
    /// 1. 先补列 + 删多余的会话 + 建块表并回填块号（`migrateLegacyData`）——必须在建唯一索引**之前**；
    /// 2. 再跑一遍 `createStatements`，把缺的表与索引补齐。
    public static func create(_ database: SQLiteDatabase) throws {
        try database.execute("PRAGMA journal_mode = WAL")
        let previousVersion = try database.userVersion()
        let isFreshDatabase = previousVersion == 0 && !database.hasTable("personas")

        try database.inTransaction {
            if !isFreshDatabase && previousVersion < version {
                try migrateLegacyData(database)
            }
            for statement in createStatements {
                try database.execute(statement)
            }
        }
        if try database.userVersion() != version {
            try database.setUserVersion(version)
        }
    }

    // MARK: - v8/v9/v10/v11 → v12

    /// 老库升到 v12：补列（含 v12 的 `webToolsProvider`）、清 Harness 列、一人一段、
    /// 补默认配置、建块表并回填块号。
    ///
    /// 为什么放在 `create()` 里而不是另开一个迁移器：这个 App 只有一个库、一条时间线，
    /// 迁移必须和建表在同一个事务里，否则「库是半新半旧」的状态比升级失败更糟。
    ///
    /// v10 → v11 的四步与规范 §5 一一对应：
    /// 1. `messages` 补 `blockIndex INTEGER NOT NULL DEFAULT 0`；
    /// 2. 建 `message_blocks`（`createStatements` 在迁移之后才跑，所以这里先建一次）；
    /// 3. 按会话 `(createdAt, id)` 序 `ROWNUM / 200` 回填块号，再由块号生成块行；
    /// 4. 删掉孤儿块（会话已不存在 / 块里已经没有消息）。
    static func migrateLegacyData(_ database: SQLiteDatabase) throws {
        for column in personaProfileColumns {
            try addColumnIfMissing(database, table: "personas", column: column.name, type: column.ddl)
        }
        for column in sessionOverrideColumns {
            try addColumnIfMissing(database, table: "chat_sessions",
                                   column: column.name, type: column.ddl)
        }
        try addColumnIfMissing(database, table: "messages", column: "readAt", type: "INTEGER")
        try addColumnIfMissing(
            database, table: "messages",
            column: messageBlockColumn.name, type: messageBlockColumn.ddl
        )
        // v12：搜索服务选择。`addColumnIfMissing` 本身幂等，重复打开不会重复加列；
        // 列上的 DEFAULT 让老行直接读成旧值 DEEPSEEK（实体层归一化成 MESSAGEAPI）。
        try addColumnIfMissing(
            database, table: "api_configs",
            column: webToolsProviderColumn.name, type: webToolsProviderColumn.ddl
        )

        // Harness 三列：1.9 整组删除。留着它们会让「没有默认值的 NOT NULL 列」
        // 直接把新 INSERT 全部顶回去（我们的写库语句已经不再提供这三列的值）。
        try dropColumnIfExists(database, table: "api_configs", column: "harnessMode")
        try dropColumnIfExists(database, table: "api_configs", column: "harnessUrl")
        try dropColumnIfExists(database, table: "api_configs", column: "harnessSyncPath")

        // 一人一段会删掉多余的会话与消息 —— 必须发生在回填块号**之前**，
        // 否则会先给马上要删的消息算一遍块号、再留下没人引用的块。
        try collapseSessionsToPersona(database)

        try database.execute(messageBlocksDDL)
        try backfillMessageBlocks(database)
    }

    /// v10 → v11 的回填：给每条消息算块号，并由块号生成块行。
    ///
    /// 用窗口函数（`ROW_NUMBER() OVER (PARTITION BY sessionId ORDER BY createdAt, id)`）
    /// 而不是逐会话在 Swift 里排序：消息可能有几十万条，把它整表读进内存正是这一版要消掉的事。
    /// 中间结果放进 TEMP 表，`UPDATE ... SET blockIndex = (SELECT ...)` 就是一次索引连接，
    /// 不是每行重算一个子查询。
    ///
    /// 幂等：先清空块表再从当前消息重算，所以重复跑（测试里会跑）结果一样。
    static func backfillMessageBlocks(_ database: SQLiteDatabase) throws {
        guard database.hasTable("messages"), database.hasTable(messageBlocksTable) else { return }
        guard columnExists(database, table: "messages", column: messageBlockColumn.name) else { return }

        try database.execute("DROP TABLE IF EXISTS temp.message_block_assignment")
        try database.execute("""
            CREATE TEMP TABLE message_block_assignment AS
            SELECT m.id AS messageId,
                   m.sessionId AS sessionId,
                   m.createdAt AS createdAt,
                   (ROW_NUMBER() OVER (
                        PARTITION BY m.sessionId
                        ORDER BY m.createdAt ASC, m.id ASC
                   ) - 1) / \(MessageBlocks.blockSize) AS blockIndex
            FROM messages m
            """)
        // TEMP 表没有索引时，下面这条 UPDATE 的相关子查询是 O(n²)：10 万条实测 123.5s，
        // 加索引后 0.0085s（大库基准的发现）。索引只服务于这次迁移，迁移结束随 TEMP 表一起消失。
        try database.execute(
            "CREATE INDEX IF NOT EXISTS temp.idx_message_block_assignment_messageId " +
                "ON message_block_assignment(messageId)"
        )
        try database.execute(
            "CREATE INDEX IF NOT EXISTS temp.idx_message_block_assignment_session_block " +
                "ON message_block_assignment(sessionId, blockIndex)"
        )
        // 空库：TEMP 表存在但没有行，两条语句都是空操作。
        try database.execute("""
            UPDATE messages
            SET blockIndex = COALESCE(
                (SELECT a.blockIndex FROM message_block_assignment a WHERE a.messageId = messages.id),
                0
            )
            """)
        try database.execute("DELETE FROM \(messageBlocksTable)")
        try database.execute("""
            INSERT INTO \(messageBlocksTable)
                (sessionId, blockIndex, firstMessageId, lastMessageId,
                 firstCreatedAt, lastCreatedAt, messageCount)
            SELECT a.sessionId, a.blockIndex,
                   (SELECT f.messageId FROM message_block_assignment f
                     WHERE f.sessionId = a.sessionId AND f.blockIndex = a.blockIndex
                     ORDER BY f.createdAt ASC, f.messageId ASC LIMIT 1),
                   (SELECT l.messageId FROM message_block_assignment l
                     WHERE l.sessionId = a.sessionId AND l.blockIndex = a.blockIndex
                     ORDER BY l.createdAt DESC, l.messageId DESC LIMIT 1),
                   MIN(a.createdAt), MAX(a.createdAt), COUNT(*)
            FROM message_block_assignment a
            GROUP BY a.sessionId, a.blockIndex
            """)
        try database.execute("DROP TABLE IF EXISTS temp.message_block_assignment")

        // 规范 §5 第 4 条：孤儿块（会话已经不在）直接删掉 —— 理论上一条都没有。
        try database.execute("""
            DELETE FROM \(messageBlocksTable)
            WHERE sessionId NOT IN (SELECT id FROM chat_sessions)
            """)
    }

    /// 一人一段：每个人格只留最新的那一段，其余会话连同消息一起删。
    ///
    /// 数据损失是这一版明确要求的产品决定（「只保留最新那段，其余直接删除」）；
    /// 这里把「怎么算最新」写死成 `COALESCE(lastMessageAt, updatedAt)`，与规格 §2.2 一致。
    static func collapseSessionsToPersona(_ database: SQLiteDatabase) throws {
        guard database.hasTable("chat_sessions") else { return }

        struct Row {
            let id: String
            let personaId: String
            let sortAt: Int64
        }
        var rows: [Row] = []
        let statement = try database.prepare("""
            SELECT id, personaId, COALESCE(lastMessageAt, updatedAt) AS sortAt, createdAt
            FROM chat_sessions
            ORDER BY personaId ASC, sortAt DESC, createdAt DESC, id DESC
            """)
        while try statement.step() {
            rows.append(Row(
                id: statement.text(at: 0) ?? "",
                personaId: statement.text(at: 1) ?? "",
                sortAt: statement.int64(at: 2)
            ))
        }

        var keepByPersona: [String: String] = [:]
        var dropIds: [String] = []
        for row in rows {
            if keepByPersona[row.personaId] == nil {
                keepByPersona[row.personaId] = row.id
            } else {
                dropIds.append(row.id)
            }
        }

        for id in dropIds {
            try database.execute("DELETE FROM messages WHERE sessionId = \(quoted(id))")
            // 主动规则只是「引用」这段会话：把引用摘掉，规则本身留着（那是他给自己定的节奏）。
            database.executeIgnoringFailure(
                "UPDATE proactive_rules SET sessionId = NULL WHERE sessionId = \(quoted(id))"
            )
            try database.execute("DELETE FROM chat_sessions WHERE id = \(quoted(id))")
        }

        // 保留段的 apiConfigId 为空时补成「当前默认配置」= 最近改过的那个配置。
        if database.hasTable("api_configs") {
            database.executeIgnoringFailure("""
                UPDATE chat_sessions
                SET apiConfigId = (SELECT id FROM api_configs ORDER BY updatedAt DESC LIMIT 1)
                WHERE (apiConfigId IS NULL OR apiConfigId = '')
                  AND EXISTS (SELECT 1 FROM api_configs)
                """)
        }

        // 老的非唯一索引留着只会多一份写入开销（唯一索引已经覆盖同一个查询）。
        database.executeIgnoringFailure("DROP INDEX IF EXISTS index_chat_sessions_personaId")
    }

    // MARK: - 迁移小工具

    static func columnExists(_ database: SQLiteDatabase, table: String, column: String) -> Bool {
        guard let statement = try? database.prepare("PRAGMA table_info(\(table))") else { return false }
        while (try? statement.step()) == true {
            if statement.text(at: 1) == column { return true }
        }
        return false
    }

    static func addColumnIfMissing(_ database: SQLiteDatabase, table: String, column: String, type: String) throws {
        guard database.hasTable(table) else { return }
        guard !columnExists(database, table: table, column: column) else { return }
        try database.execute("ALTER TABLE \(table) ADD COLUMN \(column) \(type)")
    }

    /// 删一列（SQLite ≥ 3.35 的 ALTER TABLE DROP COLUMN）。
    ///
    /// 为什么失败也继续：这一列如果本来就没了（新库）、或者列上还挂着索引（老库的
    /// `index_api_configs_updatedAt` 不涉及它，理论上不会），删不掉也不该阻止 App 启动；
    /// 真正的后果只有一个 —— 那条 `NOT NULL` 列会让写库失败，所以这里再兜一次：
    /// 删不掉就把整张表按「新结构 ∩ 老结构」重建一遍。
    static func dropColumnIfExists(_ database: SQLiteDatabase, table: String, column: String) throws {
        guard database.hasTable(table) else { return }
        guard columnExists(database, table: table, column: column) else { return }
        do {
            try database.execute("ALTER TABLE \(table) DROP COLUMN \(column)")
        } catch {
            // 老 SQLite 不支持 DROP COLUMN：只能重建这张表。
            try rebuildApiConfigsTable(database)
        }
    }

    /// `api_configs` 的重建路径（只有在 DROP COLUMN 不可用时才会走到）。
    ///
    /// 列取「新结构里有的 ∩ 老库里有的」，所以 1.7 之前没有 webTools* 列的库也能过。
    static func rebuildApiConfigsTable(_ database: SQLiteDatabase) throws {
        let newColumns = [
            "id", "name", "provider", "baseUrl", "apiKeyCipher", "model", "customHeadersJson",
            "allowInsecureHttp", "temperature", "topP", "maxOutputTokens", "frequencyPenalty",
            "presencePenalty", "createdAt", "updatedAt", "webToolsEnabled", "webToolsProvider",
            "webToolsBaseUrl", "webToolsApiKeyCipher"
        ]
        let existing = newColumns.filter { columnExists(database, table: "api_configs", column: $0) }
        guard existing.contains("id") else { return }
        let list = existing.joined(separator: ", ")
        try database.execute("ALTER TABLE api_configs RENAME TO api_configs_legacy")
        if let definition = createStatements.first(where: { $0.contains("CREATE TABLE IF NOT EXISTS api_configs ") }) {
            try database.execute(definition)
        }
        try database.execute("INSERT OR REPLACE INTO api_configs (\(list)) SELECT \(list) FROM api_configs_legacy")
        try database.execute("DROP TABLE api_configs_legacy")
    }

    /// 把字符串安全地放进 SQL（迁移里只有 id 这类主键，仍然不直接拼裸值）。
    static func quoted(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "''") + "'"
    }
}

extension SQLiteDatabase {
    /// 迁移里的「可失败但不必中断」的语句（DROP INDEX、UPDATE 引用这类收拾残局的活）。
    fileprivate func executeIgnoringFailure(_ sql: String) {
        try? execute(sql)
    }
}
