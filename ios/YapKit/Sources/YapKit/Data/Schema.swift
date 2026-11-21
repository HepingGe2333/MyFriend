import Foundation

/// SQLite 建表语句：表名与列名逐字对齐 Android Room 导出的最新 schema（当前 v16）
/// （`spec/messages-blocks/README.md` 的 `messages.blockIndex` + `message_blocks`）。
///
/// 为什么不直接用 `Entities.kt` 的 data class 生成：SQLite 的列顺序、`NOT NULL`、
/// 默认值都是 Room 导出 schema 的一部分，手写一份「看得见」的 DDL，评审时能一眼对照；
/// 也让 iOS 这边的库结构和 Android 的导出文件可以直接 diff。
///
/// 几点与 Room 的对齐说明：
/// - `user_version` 与 Room 的 version 相同（当前 v16）：Room 用 version 表达 schema 演进，iOS 用同一个数字，
///   这样「这份库是第几版」这件事两端含义相同（v11 = 分块历史：`messages.blockIndex`
///   + `message_blocks`，见 `spec/messages-blocks/README.md`；v12 = 联网工具可以选
///   第三方搜索服务：`api_configs.webToolsProvider`，见 `spec/web-search/README.md`；
///   v16 = 表情包 `stickers`，见 `spec/stickers/SCHEMA.md`）。
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
    /// 0.12（人设引导）= v13：`personas.worldJson` / `speechStyleJson` / `activityJson`、
    /// `persona_presets.worldJson` / `speechStyleJson`（见 `spec/persona-guidance/README.md` §5）。
    /// 0.12（提示词翻译）= v14：`personas.translationJson` / `translationLang`
    ///（见 `spec/persona-translation/README.md` §8：缓存列名两端固定）。
    /// 0.13 = v15：`diary_notes`（日记板，`spec/diary/README.md`）与
    /// `persona_pending_patches`（人格进化的待确认变化，`docs/design/persona-evolution.md`）。
    /// 两张都是**新表**（不改已有列），所以 14→15 是「建表 + 建索引」，没有数据回填。
    /// 0.13（表情包）= v16：`stickers`（`spec/stickers/SCHEMA.md`）。同样只有新表 + 索引，
    /// 15→16 没有数据回填；两端列名/顺序/可空/索引名逐字一致。
    /// 0.13（多维情绪）= v17：`personas.moodValence` / `moodArousal` / `moodDominance`
    ///（`spec/mood/README.md` §1）。规范里的 `moodLabel` 就是**已有的 `mood` 列**
    ///（两端都不改名，改名只会让老数据回填变成一次高风险重建），所以 16→17 只有三列加法 +
    /// 旧数据映射（只有 label/intensity 时按词表推三维）。
    /// 0.14（体验版）= v18：拉黑（`personas.blockedUntil` / `blockReason`、`messages.blocked`）
    /// 与两张新表 `mental_activities`（心理活动）`session_memory`（会话备忘录），
    /// 见 `spec/experience/README.md` §2/§3/§6。17→18 是「加两列 + 建两张表」，
    /// 没有数据回填；新列可空/有默认值，老库升级后行为不变（未拉黑、全部消息可见）。
    ///
    /// 0.15（上下文/记忆 v2）= v19：`personas.languageTag`（人格自己的提示词语言）
    /// 与 `session_memory` 的三列主题总结（`topicsJson` / `topicUserCount` / `topicUpdatedAt`）。
    /// 18→19 同样只有 ADD COLUMN（不回填），老库升级后行为不变（默认中文、还没总结过）。
    ///
    /// 0.15（模型优化）= v20：`personas.modelProfile` 与 `persona_presets.modelProfile`
    ///（`TEXT NOT NULL DEFAULT 'AUTO'`；`AUTO` / `DEEPSEEK` / `GLM`，见 `ModelProfile`）。
    /// 19→20 只有两列 ADD COLUMN：默认 `AUTO` 让老库里的每一个人格/预设都保持
    /// 「按当前模型名推断」，升级后行为与 0.15 之前一致，没有数据回填。
    public static let version: Int32 = 20

    /// 「记录型」表名（每张表都有一个 `SQLiteRecord` 实体 + 仓储，测试用它核对「一张不少」）。
    ///
    /// 前 13 张是 iOS 第一版就有的，0.13 补了两张（日记板 / 待确认人格变化），
    /// v16 一张（表情包），v18 两张（心理活动 / 会话备忘录）；
    /// `supplementaryTables` 里那两张是 1.8 遗留的缺表，
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
        "api_usage",
        // 0.13 的两张新表（记录型：都有实体 + 仓储 + 备份/界面读写）。
        "diary_notes",
        "persona_pending_patches",
        // v16：表情包（`spec/stickers/SCHEMA.md`）。
        "stickers",
        // v18：心理活动与会话备忘录（`spec/experience/README.md` §3/§6）。
        mentalActivitiesTable,
        sessionMemoryTable
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
    public static let supplementaryTables: [String] = [
        agentTicksTable, memoryEchoesTable, serverSyncStateTable, serverOutboxTable
    ]

    // MARK: - 服务端模式（M3/M4）的两张客户端侧表

    /// 服务端模式的客户端侧状态：同步游标（`since`）与「服务端权威的 presence 快照」。
    ///
    /// 为什么不放 UserDefaults / Keychain：游标必须和**同一批数据**一起提交，
    /// 否则「数据进了库、游标没前进」会重复拉一次（无害），
    /// 而「游标前进了、数据没进库」会**永久丢一段历史**（有害）。
    /// 只有和 upsert 同一个 SQLite 事务，才能保证两者的原子性。
    ///
    /// 它也不是记录型表（没有实体/仓储），形状就是一张 key-value：
    /// `server.cursor` / `presence.<personaId>` / `mood.<personaId>` 这类键。
    public static let serverSyncStateTable = "server_sync_state"

    /// 服务端模式的待发队列：`clientMessageId` 是幂等键，离线排队、恢复补发都靠它。
    ///
    /// 为什么也进 SQLite：待发消息必须在**杀进程之后还在**（用户打完字就去地铁了，
    /// 回来必须能补发），而且「补发成功 → 出队」要和消息落库同一个事务语义。
    public static let serverOutboxTable = "server_outbox"

    // MARK: - v11 的派生表：分块历史

    /// 分块历史的块元数据表（规范 `spec/messages-blocks/README.md` §1）。
    public static let messageBlocksTable = "message_blocks"

    /// 派生表：内容完全由 `messages` 决定（表里的每一行都能从消息重算出来），
    /// 所以它没有独立的业务实体/仓储，迁移与写入都走 `MessageBlockStore`。
    ///
    /// 与 `tables` 分开列，是为了让「记录型表 N 张」「派生表 1 张」这两件事在测试里
    /// 各自可断言 —— 两者混在一起会让「一张表一个实体」这条约定失效。
    public static let derivedTables: [String] = [messageBlocksTable]

    /// 完整表清单（18 张记录型 + 4 张服务层用表 + 1 张派生表 = 23）。
    ///
    /// 0.13 起记录型从 13 张变成 15 张（`diary_notes` / `persona_pending_patches`），
    /// v16 再加一张 `stickers`，v18 再加两张（`mental_activities` / `session_memory`）。
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

    /// v13 给 `personas` 加的列（人设引导，全部可空 = 没有世界预设 / 没学过话风 / 此刻没在忙）。
    ///
    /// 为什么原样存 JSON 文本而不是拆列：这三个东西永远是整组读写（提示词一次要拿全、
    /// 编辑页一次保存、备份原样搬走），拆开只会多三张表和一堆 join；
    /// 而且它们是「模型的说明书」，不是需要按字段查询的业务维度。
    /// 解析永远宽容（`WorldPreset.decode` / `SpeechStyle.decode` / `PersonaActivity.decode`）。
    public static let personaGuidanceColumns: [(name: String, ddl: String)] = [
        ("worldJson", "TEXT"),
        ("speechStyleJson", "TEXT"),
        ("activityJson", "TEXT")
    ]

    /// v14 给 `personas` 加的列：提示词翻译缓存（`spec/persona-translation/README.md` §4）。
    ///
    /// 两列都可空：null = 还没有缓存（与"翻译过但源语言相同"是两件事，后者会写一份
    /// `lang` 等于源语言的缓存，从而零请求）。
    public static let personaTranslationColumns: [(name: String, ddl: String)] = [
        ("translationJson", "TEXT"),
        ("translationLang", "TEXT")
    ]

    /// v13 给 `persona_presets` 加的列：预设可以带世界与话风（应用到人格时复制过去）。
    ///
    /// 预设没有 `activityJson`：当前活动是「这个人此刻在做什么」，不是可套用的设定。
    public static let presetGuidanceColumns: [(name: String, ddl: String)] = [
        ("worldJson", "TEXT"),
        ("speechStyleJson", "TEXT")
    ]

    /// v17 给 `personas` 加的三列：多维心情（`spec/mood/README.md` §1）。
    ///
    /// 三列都可空：`null` = 还没自动判定过（老数据、或者模型只写过 `set_mood` 的 label）。
    /// 可空是**语义**而不是偷懒：它让「判过、结论是 0/2/3」与「没判过、要退回词表」区分得开
    ///（见 `MoodState.dimensions`）。
    ///
    /// 范围：`moodValence` -2..2、`moodArousal` 0..5、`moodDominance` 0..5。
    /// 数据库不写 CHECK：范围夹取在 `MoodDimensions` 里（纯函数、可测），
    /// 而加 CHECK 会让老库迁移多一次整表重建 —— 不值得。
    public static let personaMoodDimensionColumns: [(name: String, ddl: String)] = [
        ("moodValence", "INTEGER"),
        ("moodArousal", "INTEGER"),
        ("moodDominance", "INTEGER")
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
            worldJson TEXT,
            speechStyleJson TEXT,
            activityJson TEXT,
            translationJson TEXT,
            translationLang TEXT,
            -- v17 的三维心情：与 Android 的 17.json 一样**追加在表尾**。
            -- ADD COLUMN 只能加在末尾，所以新库与升级过的库列序也一致。
            moodValence INTEGER,
            moodArousal INTEGER,
            moodDominance INTEGER,
            -- v18 的拉黑：到期时间与原因（`spec/experience/README.md` §2）。
            -- 同样追加在表尾（ADD COLUMN 只能加在末尾）。
            blockedUntil INTEGER,
            blockReason TEXT,
            -- v19：人格自己的提示词语言（0.15；可空 = 沿用默认中文）。
            -- 放在 blockProposalJson 之前：Android 19.json 就是按这个字段顺序导出的，
            -- 两端「新装的库」列表必须逐字一致（升级上来的库列序不同，但读的是列名）。
            languageTag TEXT,
            blockProposalJson TEXT,
            -- v20：模型优化（AUTO / DEEPSEEK / GLM；默认 AUTO = 按当前模型名推断）。
            -- NOT NULL DEFAULT 让 19→20 的 ADD COLUMN 不需要回填。
            modelProfile TEXT NOT NULL DEFAULT 'AUTO',
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
            worldJson TEXT,
            speechStyleJson TEXT,
            -- v20：预设也带模型优化档位（应用到人格时一起拷过去）。
            modelProfile TEXT NOT NULL DEFAULT 'AUTO',
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
            -- v18：拉黑期间用户发来的消息（模型永久看不到，`spec/experience/README.md` §2）。
            blocked INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (id)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_messages_sessionId_createdAt ON messages (sessionId, createdAt)",
        "CREATE INDEX IF NOT EXISTS index_messages_sessionId_role ON messages (sessionId, role)",
        "CREATE INDEX IF NOT EXISTS index_messages_createdAt ON messages (createdAt)",
        // v11：区块分页「按 (sessionId, blockIndex) 取一块、块内按 createdAt 排」，
        // 读多少行与会话长度无关。索引名与 Android Room v11 的导出 schema
        //（`app/schemas/com.yap.app.data.local.YapDatabase/11.json`）逐字一致。
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
        "CREATE INDEX IF NOT EXISTS index_api_usage_personaId_at ON api_usage (personaId, at)",

        // 服务端模式的客户端侧状态（同步游标 / presence 快照）。表名见 `serverSyncStateTable`。
        // 与 agent_ticks 同一性质：只有本端读写的缓存/簿记，没有实体与仓储，
        // 因此补建表不改 `user_version`（老库打开时 IF NOT EXISTS 直接补上）。
        """
        CREATE TABLE IF NOT EXISTS server_sync_state (
            key TEXT NOT NULL,
            value TEXT NOT NULL,
            updatedAt INTEGER NOT NULL,
            PRIMARY KEY (key)
        )
        """,
        // 服务端模式的待发队列（离线排队 / 幂等补发）。表名见 `serverOutboxTable`。
        """
        CREATE TABLE IF NOT EXISTS server_outbox (
            clientMessageId TEXT NOT NULL,
            sessionId TEXT NOT NULL,
            personaId TEXT,
            content TEXT NOT NULL,
            attachmentsJson TEXT NOT NULL DEFAULT '[]',
            replyTo TEXT,
            createdAt INTEGER NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0,
            lastError TEXT,
            PRIMARY KEY (clientMessageId)
        )
        """,
        "CREATE INDEX IF NOT EXISTS index_server_outbox_sessionId ON server_outbox (sessionId)",
        "CREATE INDEX IF NOT EXISTS index_server_outbox_createdAt ON server_outbox (createdAt)"
    ]
    // v15：日记板（`spec/diary/README.md`）与人格进化的待确认变化
    //（`docs/design/persona-evolution.md`）。DDL 与迁移共用同一份常量。
    + [diaryNotesDDL]
    + diaryNotesIndexStatements
    + [personaPendingPatchesDDL]
    + personaPendingPatchesIndexStatements
    // v16：表情包（`spec/stickers/SCHEMA.md`）。
    + [stickersDDL]
    + stickersIndexStatements
    // v18：心理活动与会话备忘录（`spec/experience/README.md` §3/§6）。
    + [mentalActivitiesDDL]
    + mentalActivitiesIndexStatements
    + [sessionMemoryDDL]
    + sessionMemoryIndexStatements

    // MARK: - v15 的两张新表

    /// 日记板：他自己的备忘录（`spec/diary/README.md` §数据）。
    ///
    /// 列与 Android 的 `diary_notes` 逐字对齐：`remindAt` / `remindedAt` 可空
    /// （null = 不提醒 / 还没提醒）；`remindedAt` 由心跳写，写了就不会再触发。
    public static let diaryNotesTable = "diary_notes"

    /// 待确认的人格变化（`docs/design/persona-evolution.md` §2）。
    ///
    /// Android 从 v14 起就有这张表（1.3 之前用来放「等用户点头」的人设补丁）；
    /// iOS 0.13 才建它，用途是人格进化评估产出的 `changes`：用户接受才写回人格卡。
    /// 列的形状与 Android `PersonaPendingPatchEntity` 逐字一致（备份/迁移要能互通）。
    public static let personaPendingPatchesTable = "persona_pending_patches"

    /// `diary_notes` 的建表语句（迁移与 `createStatements` 用的是同一份 DDL）。
    public static let diaryNotesDDL = """
        CREATE TABLE IF NOT EXISTS diary_notes (
            id TEXT NOT NULL,
            personaId TEXT NOT NULL,
            content TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            remindAt INTEGER,
            remindedAt INTEGER,
            updatedAt INTEGER NOT NULL,
            PRIMARY KEY (id)
        )
        """

    /// 日记板的索引（名字与列序与 Android Room 导出的 v15 逐字一致）：
    /// - `personaId` 覆盖「按人格翻」（再按 createdAt 排序，SQLite 会用同一个索引）；
    /// - `remindAt` 覆盖心跳的「到点了没」（全库扫的那条）。
    public static let diaryNotesIndexStatements: [String] = [
        "CREATE INDEX IF NOT EXISTS index_diary_notes_personaId ON diary_notes (personaId)",
        "CREATE INDEX IF NOT EXISTS index_diary_notes_remindAt ON diary_notes (remindAt)"
    ]

    /// `persona_pending_patches` 的建表语句（与 Android v14 的 Room 导出逐列一致）。
    public static let personaPendingPatchesDDL = """
        CREATE TABLE IF NOT EXISTS persona_pending_patches (
            id TEXT NOT NULL,
            personaId TEXT NOT NULL,
            sessionId TEXT,
            patchJson TEXT NOT NULL,
            summary TEXT NOT NULL,
            reason TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            PRIMARY KEY (id)
        )
        """

    public static let personaPendingPatchesIndexStatements: [String] = [
        "CREATE INDEX IF NOT EXISTS index_persona_pending_patches_personaId ON persona_pending_patches (personaId)",
        "CREATE INDEX IF NOT EXISTS index_persona_pending_patches_createdAt ON persona_pending_patches (createdAt)"
    ]

    // MARK: - v16 的表情包

    /// 表情包表名（`spec/stickers/SCHEMA.md`）。
    public static let stickersTable = "stickers"

    /// `stickers` 的建表语句（迁移与 `createStatements` 用同一份 DDL）。
    ///
    /// 逐字照 `spec/stickers/SCHEMA.md`：`id` 是 `NOT NULL PRIMARY KEY`（与其它表
    /// 末尾写 `PRIMARY KEY (id)` 的形状不同，但 PRAGMA 结果一致），`mime` 恒为 `image/gif`，
    /// `width/height/frames/bytes` 都有默认值（静态图就是 `frames = 1`）。
    public static let stickersDDL = """
        CREATE TABLE IF NOT EXISTS stickers (
            id TEXT NOT NULL PRIMARY KEY,
            ownerUserId TEXT,
            personaId TEXT,
            file TEXT NOT NULL,
            mime TEXT NOT NULL,
            width INTEGER NOT NULL DEFAULT 0,
            height INTEGER NOT NULL DEFAULT 0,
            frames INTEGER NOT NULL DEFAULT 1,
            bytes INTEGER NOT NULL DEFAULT 0,
            source TEXT NOT NULL,
            createdBy TEXT NOT NULL,
            createdAt INTEGER NOT NULL
        )
        """

    /// 表情索引（与 SCHEMA.md 逐字一致）：
    /// - `createdAt` 覆盖历史面板的「最近 30 / 全部」；
    /// - `personaId` 覆盖「这个人格做过哪些表情」。
    /// 规格给 `stickers` 写明 DEFAULT 的列（见 `stickersDDL`）。
    ///
    /// 单独列出来是给 `SchemaParityTests` 用的：Android 的 16.json 目前没有这些默认值
    /// （Room 需要 `@ColumnInfo(defaultValue = …)` 才导出），对齐测试要能区分
    /// 「iOS 自己多写了一个默认值」（红）与「规格要求、Android 还没补」（记录在案）。
    public static let stickersSpecDeclaredDefaults: Set<String> = ["width", "height", "frames", "bytes"]

    public static let stickersIndexStatements: [String] = [
        "CREATE INDEX IF NOT EXISTS index_stickers_createdAt ON stickers (createdAt)",
        "CREATE INDEX IF NOT EXISTS index_stickers_personaId ON stickers (personaId)"
    ]

    // MARK: - v18：拉黑、心理活动、会话备忘录（`spec/experience/README.md`）

    /// 心理活动表名：他每 3 分钟 / 每 3 轮写下的一句私密念头。
    public static let mentalActivitiesTable = "mental_activities"

    /// 会话备忘录表名：`shortSummary`（最近 5 轮）+ `longSummary`（更早的对话）。
    public static let sessionMemoryTable = "session_memory"

    /// v18 给 `personas` 加的两列：拉黑到期时间与原因（`spec/experience/README.md` §2）。
    ///
    /// 都可空：`blockedUntil` 为 null = 没被拉黑（或已到期解除）。
    /// 时间用毫秒时间戳（与全库一致），**到期即视为解除**，不写第二列状态位：
    /// 那样会多出一种「已经到期但状态还是 BLOCKED」的坏数据。
    public static let personaBlockColumns: [(name: String, ddl: String)] = [
        ("blockedUntil", "INTEGER"),
        ("blockReason", "TEXT"),
        // 未确认的拉黑提议（10 分钟有效）。放在人格行上而不是另建表：
        // Android 18.json 就是这么排的，两端 schema 必须逐字一致。
        ("blockProposalJson", "TEXT")
    ]

    /// v19 给 `personas` 加的列：人格自己的提示词语言（0.15，与页面语言无关）。
    ///
    /// 可空：null = 沿用默认中文（`PromptLanguage.resolve` 回落）。
    /// 只给**人格**加列，不给预设加：预设是模板，套用时可以不带语言（报告里写明了取舍）。
    public static let personaLanguageColumn: (name: String, ddl: String) = ("languageTag", "TEXT")

    /// v20 给 `personas` 加的列：模型优化档位（0.15，`ModelProfile`）。
    ///
    /// `NOT NULL DEFAULT 'AUTO'`：老库 ADD COLUMN 时每一行自动是 `AUTO`
    ///（= 按当前模型名推断），所以 19→20 没有数据回填，行为与升级前一致。
    /// 与 Android 的 `20.json` 对齐时列名/类型/默认值都要逐字一致（见 `SchemaParityTests`）。
    public static let personaModelProfileColumn: (name: String, ddl: String) = (
        "modelProfile", "TEXT NOT NULL DEFAULT 'AUTO'"
    )

    /// v20 给 `persona_presets` 加的列：预设也带同一个档位。
    ///
    /// 预设没有 `activityJson` 那种「此刻状态」，但模型优化是**设定**（换模型时整份预设一起走），
    /// 所以它和 `worldJson` / `speechStyleJson` 一样属于预设。
    public static let presetModelProfileColumn: (name: String, ddl: String) = (
        "modelProfile", "TEXT NOT NULL DEFAULT 'AUTO'"
    )

    /// v19 给 `session_memory` 加的三列：主题总结（0.15 上下文/记忆 v2）。
    ///
    /// `topicUserCount` 有 DEFAULT 0：老库里的会话行读出来就是「还没总结过」，迁移不回填。
    public static let sessionMemoryTopicColumns: [(name: String, ddl: String)] = [
        ("topicsJson", "TEXT"),
        ("topicUserCount", "INTEGER NOT NULL DEFAULT 0"),
        ("topicUpdatedAt", "INTEGER")
    ]

    /// v18 给 `messages` 加的列：拉黑期间用户发来的消息（模型永久看不到）。
    ///
    /// `NOT NULL DEFAULT 0` 与 Room 的 `@ColumnInfo(defaultValue = "0")` 对齐：
    /// 老库里每一条消息都自动是「未被屏蔽」，迁移不需要回填。
    public static let messageBlockedColumn = (name: "blocked", ddl: "INTEGER NOT NULL DEFAULT 0")

    /// `mental_activities` 的建表语句（迁移与 `createStatements` 共用同一份 DDL）。
    ///
    /// 列形状按规范 §3「会话 / 人格 / 时间 / 念头 / 心情快照」：
    /// - `thought` 是 ≤60 字的私密念头（**不发给用户**）；
    /// - 心情快照写的是**微调之后**的三维 + label/intensity，这样「他那时候是什么心情」
    ///   在事后回看时是自洽的（只存 delta 会让回看得靠链式重算）。
    public static let mentalActivitiesDDL = """
        CREATE TABLE IF NOT EXISTS mental_activities (
            id TEXT NOT NULL,
            personaId TEXT NOT NULL,
            sessionId TEXT,
            at INTEGER NOT NULL,
            thought TEXT NOT NULL,
            moodLabel TEXT,
            moodIntensity INTEGER,
            moodValence INTEGER,
            moodArousal INTEGER,
            moodDominance INTEGER,
            PRIMARY KEY (id)
        )
        """

    /// 与 Android 18.json 逐字一致的三条索引（含 `at` 那一条：清理「只留 50 条」时按时间扫）。
    public static let mentalActivitiesIndexStatements: [String] = [
        "CREATE INDEX IF NOT EXISTS index_mental_activities_personaId_at "
            + "ON mental_activities (personaId, at)",
        "CREATE INDEX IF NOT EXISTS index_mental_activities_sessionId_at "
            + "ON mental_activities (sessionId, at)",
        "CREATE INDEX IF NOT EXISTS index_mental_activities_at ON mental_activities (at)"
    ]

    /// `session_memory` 的建表语句（规范 §6）。
    ///
    /// 主键就是 `sessionId`（一人一段会话，会话级摘要天然一行一条）。
    /// `shortUpTo` / `longUpTo` 记的是「生成这份摘要时，会话里已有多少条**用户消息**」——
    /// 节流（每 5 / 30 条更新一次）与「失败保留旧摘要」都靠它：
    /// 失败时不写这一行，下一次回复会重试，不会因为「更新过」而把旧摘要永久钉住。
    public static let sessionMemoryDDL = """
        CREATE TABLE IF NOT EXISTS session_memory (
            sessionId TEXT NOT NULL,
            personaId TEXT NOT NULL,
            shortSummary TEXT,
            longSummary TEXT,
            shortUserCount INTEGER NOT NULL,
            longUserCount INTEGER NOT NULL,
            shortUpdatedAt INTEGER,
            longUpdatedAt INTEGER,
            updatedAt INTEGER NOT NULL,
            -- v19：主题总结（0.15 上下文/记忆 v2），JSON 数组 + 计数 + 时间。
            topicsJson TEXT,
            topicUserCount INTEGER NOT NULL DEFAULT 0,
            topicUpdatedAt INTEGER,
            PRIMARY KEY (sessionId)
        )
        """

    public static let sessionMemoryIndexStatements: [String] = [
        "CREATE INDEX IF NOT EXISTS index_session_memory_personaId ON session_memory (personaId)",
        "CREATE INDEX IF NOT EXISTS index_session_memory_updatedAt ON session_memory (updatedAt)"
    ]

    /// v18 的两张新表（幂等）。`createStatements` 里也包含它们，两处用的是同一份常量。
    static func ensureV18Tables(_ database: SQLiteDatabase) throws {
        try database.execute(mentalActivitiesDDL)
        for statement in mentalActivitiesIndexStatements {
            try database.execute(statement)
        }
        try database.execute(sessionMemoryDDL)
        for statement in sessionMemoryIndexStatements {
            try database.execute(statement)
        }
    }

    /// 建库入口：App 首次启动、以及测试里建临时库都走这里。
    ///
    /// 幂等（全部 `IF NOT EXISTS`），所以每次启动都可以无脑调用；
    /// `user_version` 是「这份库的 schema 是第几版」的唯一事实来源。
    ///
    /// 老库（v8–v12）走两条路：
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

    // MARK: - v8/v9/v10/v11/v12 → v16

    /// 老库升到 v16：补列（v12 的 `webToolsProvider`、v13 的人设引导三列、v14 的翻译两列）、
    /// 清 Harness 列、一人一段、补默认配置、建块表并回填块号、建 v15 的两张新表、建 v16 的表情表。
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
        // v13：世界预设 / 话风 / 当前活动。全部可空 —— 老数据没有这些设定是**正确**的状态，
        // 回填一个空 JSON 只会让「没填过」和「填了又清空」变得无法区分。加列本身幂等。
        for column in personaGuidanceColumns {
            try addColumnIfMissing(database, table: "personas", column: column.name, type: column.ddl)
        }
        // v14：提示词翻译缓存。同样可空、同样幂等（用户升级后译文会在下一次进聊天时补上）。
        for column in personaTranslationColumns {
            try addColumnIfMissing(database, table: "personas", column: column.name, type: column.ddl)
        }
        // v17：多维心情三列（可空 = 还没自动判定过）。
        for column in personaMoodDimensionColumns {
            try addColumnIfMissing(database, table: "personas", column: column.name, type: column.ddl)
        }
        for column in presetGuidanceColumns {
            try addColumnIfMissing(database, table: "persona_presets",
                                   column: column.name, type: column.ddl)
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

        // v15：日记板 + 待确认的人格变化。14→15 只有「建表 + 建索引」，没有数据回填；
        // 放在这里是为了让升级路径在迁移阶段就与目标 schema 一致（`createStatements`
        // 之后还会幂等地再跑一遍）。
        try ensureV15Tables(database)
        // v16：表情包。15→16 同样只有「建表 + 建索引」；幂等（IF NOT EXISTS），
        // 所以新库、老库、重复打开都走同一条路径。
        try ensureV16Tables(database)
        // v17：旧心情（只有 label + intensity）映射到三维；只回填「还没判过」的行。
        try backfillMoodDimensions(database)
        // v18：拉黑两列（可空）+ 两张新表（心理活动 / 会话备忘录）。
        // 17→18 没有数据回填：老库里每一行消息都自动是 blocked = 0，人格都是「未拉黑」。
        for column in personaBlockColumns {
            try addColumnIfMissing(database, table: "personas", column: column.name, type: column.ddl)
        }
        try addColumnIfMissing(
            database, table: "messages",
            column: messageBlockedColumn.name, type: messageBlockedColumn.ddl
        )
        try ensureV18Tables(database)
        // v19：人格的提示词语言 + 会话主题总结三列。都只 ADD COLUMN，不回填数据。
        try addColumnIfMissing(
            database, table: "personas",
            column: personaLanguageColumn.name, type: personaLanguageColumn.ddl
        )
        for column in sessionMemoryTopicColumns {
            try addColumnIfMissing(database, table: sessionMemoryTable,
                                   column: column.name, type: column.ddl)
        }
        // v20：模型优化档位。`NOT NULL DEFAULT 'AUTO'` 让老行自动落到「按模型名推断」，
        // 同样没有回填；预设表也要补（应用预设时会把这一档拷到人格）。
        try addColumnIfMissing(
            database, table: "personas",
            column: personaModelProfileColumn.name, type: personaModelProfileColumn.ddl
        )
        try addColumnIfMissing(
            database, table: "persona_presets",
            column: presetModelProfileColumn.name, type: presetModelProfileColumn.ddl
        )
    }

    /// 16 → 17 的旧数据映射（`spec/mood/README.md` §1）：
    /// valence 由 label 推（累/烦 → -1，平静 → 0，开心 → 1），arousal 由 intensity 推，
    /// dominance = 3。
    ///
    /// 只写 `moodValence IS NULL` 的行：判定过的心情（三列都有值）不会被升级覆盖；
    /// 没有心情的行保持 NULL（= 从来没判过，读取时退回词表）。幂等，重复打开也安全。
    static func backfillMoodDimensions(_ database: SQLiteDatabase) throws {
        guard database.hasTable("personas") else { return }
        for column in personaMoodDimensionColumns {
            guard columnExists(database, table: "personas", column: column.name) else { return }
        }
        struct Row {
            let id: String
            let label: String
            let intensity: Int
        }
        var rows: [Row] = []
        let select = try database.prepare("""
            SELECT id, mood, COALESCE(moodIntensity, 2)
            FROM personas
            WHERE mood IS NOT NULL AND mood <> '' AND moodValence IS NULL
            """)
        while try select.step() {
            rows.append(Row(
                id: select.text(at: 0) ?? "",
                label: select.text(at: 1) ?? "",
                intensity: Int(select.int64(at: 2))
            ))
        }
        guard !rows.isEmpty else { return }
        let update = try database.prepare("""
            UPDATE personas SET moodValence = ?, moodArousal = ?, moodDominance = ?
            WHERE id = ? AND moodValence IS NULL
            """)
        for row in rows {
            guard !row.id.isEmpty else { continue }
            let dimensions = MoodDimensions.fromLegacy(label: row.label, intensity: row.intensity)
            update.reset()
            try update.bind(.int(dimensions.valence), at: 1)
            try update.bind(.int(dimensions.arousal), at: 2)
            try update.bind(.int(dimensions.dominance), at: 3)
            try update.bind(.text(row.id), at: 4)
            _ = try update.step()
        }
    }

    /// v16 的表情表（幂等）。`createStatements` 里也包含它，两处用的是同一份常量。
    static func ensureV16Tables(_ database: SQLiteDatabase) throws {
        try database.execute(stickersDDL)
        for statement in stickersIndexStatements {
            try database.execute(statement)
        }
    }

    /// v15 的两张新表（幂等）。`createStatements` 里也包含它们，两处用的是同一份常量。
    static func ensureV15Tables(_ database: SQLiteDatabase) throws {
        try database.execute(diaryNotesDDL)
        for statement in diaryNotesIndexStatements {
            try database.execute(statement)
        }
        try database.execute(personaPendingPatchesDDL)
        for statement in personaPendingPatchesIndexStatements {
            try database.execute(statement)
        }
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
