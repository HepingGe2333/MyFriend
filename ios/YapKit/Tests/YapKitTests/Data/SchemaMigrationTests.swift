import Foundation
import XCTest
@testable import YapKit

/// v8/v9/v10 → v12 的迁移（§2.2、`spec/messages-blocks/README.md` §5 与
/// `spec/web-search/README.md` §1）。
///
/// 这是 1.9 唯一会**删用户数据**的地方（一人一段，其余会话连同消息直接删），
/// 所以它必须被一条真库测试钉住：造一个 1.8 形状的旧库，打开它，然后逐条核对
/// 「留了哪一段、删了什么、补了什么、索引在不在」。
final class SchemaMigrationTests: XCTestCase {

    private var directory: URL!
    private var databasePath: String!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-migration-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        databasePath = directory.appendingPathComponent("legacy.sqlite").path
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        try super.tearDownWithError()
    }

    private let personaId = "persona-legacy"
    private let configId = "config-legacy"
    private let now: Int64 = 1_794_500_000_000

    /// 造一个「1.8 的库」：先把库建成当前版本，再把 1.9 / v11 加的东西拆掉、灌进旧形状的数据。
    ///
    /// 为什么这么做而不是手写一整套 v8 DDL：手写的那份迟早会和真实 schema 漂开，
    /// 而「在真库上回退」用的是同一份建表语句，测出来的就是真实迁移路径。
    private func seedLegacyDatabase() throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let database = try XCTUnwrap(stores?.database)

        // 1) 先把库「退回 1.8」：去掉唯一索引与 1.9 新加的那些列，再塞回 Harness 三列。
        //    顺序很重要：唯一索引还在的时候根本插不进两段会话。
        try database.execute("DROP INDEX IF EXISTS \(Schema.uniquePersonaSessionIndex)")
        try database.execute("ALTER TABLE chat_sessions DROP COLUMN memoryRetentionOverride")
        try database.execute("ALTER TABLE chat_sessions DROP COLUMN extractionEveryOverride")
        try database.execute("ALTER TABLE messages DROP COLUMN readAt")
        try database.execute("ALTER TABLE api_configs ADD COLUMN harnessMode TEXT")
        try database.execute("ALTER TABLE api_configs ADD COLUMN harnessUrl TEXT")
        try database.execute("ALTER TABLE api_configs ADD COLUMN harnessSyncPath TEXT")
        for column in Schema.personaProfileColumns {
            try database.execute("ALTER TABLE personas DROP COLUMN \(column.name)")
        }

        // 2) 灌旧数据：一个人格、一个 API 配置、两段会话（旧的先建、新的后建）。
        try database.execute("""
            INSERT INTO personas (id, name, description, avatarSeed, prompt, category, version,
                                  isDefault, createdAt, updatedAt)
            VALUES ('\(personaId)', '林岸', '旧数据', 7, '你叫林岸。', '朋友', 2, 1, 1, \(now))
            """)
        try database.execute("""
            INSERT INTO api_configs (id, name, provider, baseUrl, model, customHeadersJson,
                                     allowInsecureHttp, createdAt, updatedAt)
            VALUES ('\(configId)', '旧配置', 'OPENAI', 'https://api.example.com', 'deepseek-chat',
                    '{}', 0, 1, \(now))
            """)
        try database.execute("""
            INSERT INTO chat_sessions (id, personaId, apiConfigId, model, title, lastMessagePreview,
                lastMessageAt, unreadCount, pinned, createdAt, updatedAt, lastPersonaVersion,
                syncState, syncedPersonaVersion, syncedMemoriesHash, serverSessionId, lastSyncError,
                lastSyncAt, lastMemoryExtractedCount, historySummary, historySummarizedUpTo,
                historySummaryAt)
            VALUES ('session-old', '\(personaId)', '\(configId)', 'deepseek-chat', '早的那段', '旧',
                    \(now - 86_400_000), 0, 0, 1, \(now - 86_400_000), 2, 'STATELESS',
                    NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL),
                   ('session-new', '\(personaId)', NULL, 'deepseek-chat', '新的那段', '新',
                    \(now), 0, 0, 2, \(now), 2, 'STATELESS',
                    NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL)
            """)
        try database.execute("""
            INSERT INTO messages (id, sessionId, personaId, role, content, createdAt, updatedAt,
                                  status, edited)
            VALUES ('msg-old', 'session-old', '\(personaId)', 'USER', '旧的还在吗', 1, 1, 'COMPLETE', 0),
                   ('msg-new', 'session-new', '\(personaId)', 'USER', '新的这句', 2, 2, 'COMPLETE', 0)
            """)
        // 主动规则引用了要被删掉的那一段。
        try database.execute("""
            INSERT INTO proactive_rules (id, personaId, sessionId, kind, hint, note, enabled, pending,
                                         source, nextTriggerAt, createdAt, updatedAt)
            VALUES ('rule-1', '\(personaId)', 'session-old', 'DAILY', '问问他', '', 1, 0, 'MANUAL', 0, 1, 1)
            """)

        // 3) 记成 v8：`Schema.create` 看到这个数字才会走迁移。
        try database.setUserVersion(8)
        stores = nil
        _ = stores
    }

    /// 迁移的总账：一人一段、别的删干净、唯一索引与补齐都到位。
    func testMigrationCollapsesSessionsToNewestAndAddsV10Columns() throws {
        try seedLegacyDatabase()

        let stores = try DatabaseStores.open(path: databasePath)

        // 1) 版本号与唯一索引。
        XCTAssertEqual(try stores.database.userVersion(), Schema.version)
        let indexNames = try indexNames(stores.database)
        XCTAssertTrue(indexNames.contains(Schema.uniquePersonaSessionIndex), "唯一索引必须建起来")

        // 2) 一人只剩那一段（最新的一段）。
        let sessions = try stores.sessions.all()
        XCTAssertEqual(sessions.count, 1)
        XCTAssertEqual(sessions.first?.id, "session-new", "保留按 COALESCE(lastMessageAt, updatedAt) 最新的那段")

        // 3) 旧那段的消息确实删了。
        XCTAssertNil(try stores.messages.byId("msg-old"))
        XCTAssertEqual(try stores.messages.byId("msg-new")?.content, "新的这句")

        // 4) 主动规则的 sessionId 引用被摘掉，规则本身留着。
        let rule = try XCTUnwrap(try stores.proactiveRules.byId("rule-1"))
        XCTAssertNil(rule.sessionId, "指向已删会话的引用要清掉")

        // 5) 保留段的 apiConfigId 补成了当前默认配置。
        XCTAssertEqual(sessions.first?.apiConfigId, configId)

        // 6) 1.9 的新列都在（老数据走代码默认值）。
        let persona = try XCTUnwrap(try stores.personas.byId(personaId))
        XCTAssertEqual(persona.traits, [])
        XCTAssertEqual(persona.proactiveLevel, PersonaFieldLimits.proactiveLevelDefault)
        XCTAssertTrue(persona.sleepEnabled)

        // 7) Harness 三列被丢掉：实体已经不再绑定它们，留着会让 INSERT 撞 NOT NULL。
        let apiConfigColumns = try columnNames(stores.database, table: "api_configs")
        for legacy in ["harnessMode", "harnessUrl", "harnessSyncPath"] {
            XCTAssertFalse(apiConfigColumns.contains(legacy), "\(legacy) 应当已经被删掉")
        }
        // 写一条新的 API 配置不会报错（迁移是否彻底最直接的证据）。
        try stores.apiConfigs.upsert(ApiConfigEntity(
            id: "config-new", name: "迁移后新建", baseUrl: "https://x.example",
            model: "deepseek-chat"
        ))
        XCTAssertNotNil(try stores.apiConfigs.byId("config-new"))
    }

    /// 迁移只跑一次，也不会把已经合规的库改坏（幂等）。
    func testMigrationIsIdempotent() throws {
        try seedLegacyDatabase()
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        _ = stores
        stores = nil

        let reopened = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try reopened.database.userVersion(), Schema.version)
        XCTAssertEqual(try reopened.sessions.count(), 1)
        XCTAssertEqual(try reopened.messages.count(), 1)
        XCTAssertNotNil(try reopened.personas.byId(personaId))
        // v11：块表也只能有一份，不能因为重复打开而多出块行。
        XCTAssertEqual(try reopened.messageBlocks.count(), 1)
        XCTAssertTrue(try reopened.messageBlocks.inconsistencies().isEmpty)
    }

    /// 空库也能正常建起来（迁移不能只在「有数据」时才成立）。
    func testFreshDatabaseHasEverything() throws {
        let stores = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try stores.database.userVersion(), Schema.version)
        XCTAssertTrue(try indexNames(stores.database).contains(Schema.uniquePersonaSessionIndex))
        XCTAssertEqual(try stores.personas.count(), 0)
        XCTAssertEqual(try stores.sessions.count(), 0)
    }

    // MARK: - 0.9.1：补进 v10 的两张表（agent_ticks / memory_echoes）

    /// 空库：这两张表也要建出来（连同索引）。
    func testFreshDatabaseCreatesSupplementaryTables() throws {
        let stores = try DatabaseStores.open(path: databasePath)
        let names = try stores.database.tableNames()
        XCTAssertEqual(
            Schema.supplementaryTables,
            ["agent_ticks", "memory_echoes", "server_sync_state", "server_outbox"]
        )
        for table in Schema.supplementaryTables {
            XCTAssertTrue(names.contains(table), "缺表：\(table)")
        }
        let indexes = try indexNames(stores.database)
        XCTAssertTrue(indexes.contains("index_agent_ticks_personaId_at"))
        XCTAssertTrue(indexes.contains("index_agent_ticks_at"))
        XCTAssertTrue(indexes.contains("index_memory_echoes_personaId_drawer"))
        XCTAssertTrue(indexes.contains("index_memory_echoes_createdAt"))
        // 服务端模式的两张客户端侧表（游标 / 待发队列）连同索引一起建出来。
        XCTAssertTrue(indexes.contains("index_server_outbox_sessionId"))
        XCTAssertTrue(indexes.contains("index_server_outbox_createdAt"))
    }

    /// 老库：v10 但缺这两张表（0.9.1 之前建的库）→ 打开一次就自动补建，`user_version` 不变。
    func testLegacyV10DatabaseWithoutSupplementaryTablesGetsThemOnOpen() throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        try stores?.database.execute("DROP TABLE agent_ticks")
        try stores?.database.execute("DROP TABLE memory_echoes")
        XCTAssertEqual(try stores?.database.userVersion(), Schema.version)
        XCTAssertFalse(try XCTUnwrap(stores).database.hasTable(Schema.agentTicksTable))
        stores = nil

        // 「升级后第一次启动」：同一个路径再打开一次。
        let reopened = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try reopened.database.userVersion(), Schema.version)
        let names = try reopened.database.tableNames()
        for table in Schema.supplementaryTables {
            XCTAssertTrue(names.contains(table), "老库升级后缺表：\(table)")
        }
        XCTAssertEqual(try reopened.personas.count(), 0)
    }

    /// 回声真的写进库：**关掉再打开**还在（这就是「杀掉进程重启后回声仍在」的库级证明）。
    func testMemoryEchoSurvivesReopen() throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let store = MemoryEchoStore(database: try XCTUnwrap(stores).database)
        XCTAssertFalse(store.isUsingFallback, "表在，就不该退回内存实现")
        store.insertAll([MemoryEcho(
            id: "echo-1", personaId: personaId, drawer: "USER",
            content: "对方说天冷的时候想收到一句提醒",
            importance: 4, sourceQuote: "天冷的时候提醒我", categoryName: "FACT",
            confidence: 0.8, createdAt: now, lastSeenAt: now
        )])
        stores = nil

        let reopened = try DatabaseStores.open(path: databasePath)
        let echoes = MemoryEchoStore(database: reopened.database).byPersona(personaId)
        XCTAssertEqual(echoes.count, 1)
        XCTAssertEqual(echoes.first?.content, "对方说天冷的时候想收到一句提醒")
        XCTAssertEqual(echoes.first?.drawer, "USER")
        XCTAssertEqual(echoes.first?.importance, 4)
        XCTAssertEqual(echoes.first?.createdAt, now)
    }

    /// 自主循环的 tick 也要真的落表（不再只是进程内留痕）。
    func testAgentTickIsPersistedToTheTable() throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let database = try XCTUnwrap(stores).database
        let ticks = AgentTickStore(database: database)
        XCTAssertFalse(ticks.isUsingFallback, "表在，就不该退回内存实现")
        ticks.insert(AgentTick(
            id: "tick-1", personaId: personaId, at: now,
            thought: "刚好在这个时间段", actionJson: #"{"actions":[{"type":"nothing"}]}"#,
            outcome: "什么都没做"
        ))
        let rows = try database.scalarInt64(
            "SELECT COUNT(*) FROM agent_ticks", []
        )
        XCTAssertEqual(rows, 1, "必须真的写进表")
        stores = nil

        let reopened = try DatabaseStores.open(path: databasePath)
        let again = AgentTickStore(database: reopened.database)
        XCTAssertEqual(again.lastTickAt(), now, "重启后「刚想过」还在")
        XCTAssertEqual(again.latest(personaId: personaId)?.thought, "刚好在这个时间段")
    }

    // MARK: - v10 → v11：分块历史（规范 §5）

    /// 造一个「v10 的库」：有消息、没有块表、`messages` 没有 `blockIndex`。
    ///
    /// 与上面的 v8 用例同一个手法：先在真库上建到 v11，再把 v11 加的东西拆掉、灌旧数据、
    /// 把版本号记回 10，这样测到的就是真实的 `Schema.create` 升级路径。
    ///
    /// - Parameters:
    ///   - descendingCreatedAt: 插入顺序与时间顺序相反（用来证明回填按 `(createdAt, id)` 排）。
    ///   - sameTimestamp: 所有消息同一毫秒（用来证明 `id` 是第二排序键）。
    private func seedV10Database(
        messageCount: Int,
        descendingCreatedAt: Bool = false,
        sameTimestamp: Bool = false
    ) throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let database = try XCTUnwrap(stores?.database)

        try database.execute("DROP TABLE \(Schema.messageBlocksTable)")
        try database.execute("DROP INDEX IF EXISTS index_messages_sessionId_blockIndex_createdAt")
        try database.execute("ALTER TABLE messages DROP COLUMN blockIndex")

        try database.execute("""
            INSERT INTO personas (id, name, description, avatarSeed, prompt, category, version,
                                  isDefault, createdAt, updatedAt)
            VALUES ('\(personaId)', '林岸', 'v10 的数据', 7, '你叫林岸。', '朋友', 2, 1, 1, \(now))
            """)
        try database.execute("""
            INSERT INTO chat_sessions (id, personaId, apiConfigId, model, title, lastMessagePreview,
                lastMessageAt, unreadCount, pinned, createdAt, updatedAt, lastPersonaVersion,
                syncState, lastMemoryExtractedCount)
            VALUES ('session-1', '\(personaId)', NULL, 'deepseek-chat', '旧的对话', '旧',
                \(now), 0, 0, 1, \(now), 2, 'STATELESS', 0)
            """)
        for index in 0..<messageCount {
            let offset: Int64
            if sameTimestamp {
                offset = 0
            } else if descendingCreatedAt {
                offset = Int64(messageCount - 1 - index)
            } else {
                offset = Int64(index)
            }
            let createdAt = now + offset * 1000
            try database.execute("""
                INSERT INTO messages (id, sessionId, personaId, role, content, createdAt, updatedAt,
                                      status, edited)
                VALUES ('m-\(String(format: "%03d", index))', 'session-1', '\(personaId)', 'USER',
                        '第 \(index) 句', \(createdAt), \(createdAt), 'COMPLETE', 0)
                """)
        }
        try database.setUserVersion(10)
        stores = nil
        database.close()
    }

    /// 250 条消息升级：一条不丢、块号按 `(createdAt, id)` 回填、块行边界正确。
    func testV10ToV11BackfillsBlockIndexAndBuildsBlocks() throws {
        try seedV10Database(messageCount: 250)
        let stores = try DatabaseStores.open(path: databasePath)

        XCTAssertEqual(try stores.database.userVersion(), Schema.version)
        XCTAssertTrue(try stores.database.hasTable(Schema.messageBlocksTable), "v11 要建块表")
        XCTAssertEqual(try stores.messages.count(), 250, "迁移不能丢消息")

        // 块号回填：前 200 条 → 块 0，后 50 条 → 块 1。
        XCTAssertEqual(try stores.messages.blockIndex(ofMessage: "m-000"), 0)
        XCTAssertEqual(try stores.messages.blockIndex(ofMessage: "m-199"), 0)
        XCTAssertEqual(try stores.messages.blockIndex(ofMessage: "m-200"), 1)
        XCTAssertEqual(try stores.messages.blockIndex(ofMessage: "m-249"), 1)

        let blocks = try stores.messageBlocks.blocks(sessionId: "session-1")
        XCTAssertEqual(blocks.map(\.blockIndex), [0, 1])
        XCTAssertEqual(blocks.map(\.messageCount), [200, 50])
        XCTAssertEqual(blocks[0].firstMessageId, "m-000")
        XCTAssertEqual(blocks[0].lastMessageId, "m-199")
        XCTAssertEqual(blocks[1].firstMessageId, "m-200")
        XCTAssertEqual(blocks[1].lastMessageId, "m-249")
        XCTAssertEqual(blocks[0].firstCreatedAt, now)
        XCTAssertEqual(blocks[1].lastCreatedAt, now + 249 * 1000)
        XCTAssertTrue(try stores.messageBlocks.inconsistencies().isEmpty)
    }

    /// 回填按**时间**排序，不是插入顺序（老库里的行序不等于对话顺序）。
    func testV10BackfillOrdersByTimestampNotInsertionOrder() throws {
        try seedV10Database(messageCount: 250, descendingCreatedAt: true)
        let stores = try DatabaseStores.open(path: databasePath)

        XCTAssertEqual(try stores.messages.blockIndex(ofMessage: "m-249"), 0, "最旧的一条落在块 0")
        XCTAssertEqual(try stores.messages.blockIndex(ofMessage: "m-000"), 1, "最新的一条落在块 1")
        let blocks = try stores.messageBlocks.blocks(sessionId: "session-1")
        XCTAssertEqual(blocks[0].firstMessageId, "m-249")
        XCTAssertEqual(blocks[1].lastMessageId, "m-000")
        XCTAssertTrue(try stores.messageBlocks.inconsistencies().isEmpty)
    }

    /// 同一毫秒的一批消息：块内边界靠 `id` 稳定排序（不漏不重）。
    func testV10BackfillUsesIDForSameTimestamp() throws {
        try seedV10Database(messageCount: 201, sameTimestamp: true)
        let stores = try DatabaseStores.open(path: databasePath)
        let blocks = try stores.messageBlocks.blocks(sessionId: "session-1")
        XCTAssertEqual(blocks.map(\.messageCount), [200, 1])
        XCTAssertEqual(blocks[0].firstMessageId, "m-000")
        XCTAssertEqual(blocks[0].lastMessageId, "m-199")
        XCTAssertEqual(blocks[1].firstMessageId, "m-200")
        XCTAssertEqual(blocks[1].messageCount, 1)
    }

    /// 空库（有表、没数据）也要能迁移：块表建出来、没有块行。
    func testV10WithNoMessagesMigratesToAnEmptyBlockTable() throws {
        try seedV10Database(messageCount: 0)
        let stores = try DatabaseStores.open(path: databasePath)

        XCTAssertEqual(try stores.database.userVersion(), Schema.version)
        XCTAssertEqual(try stores.messages.count(), 0)
        XCTAssertEqual(try stores.messageBlocks.count(), 0)
        XCTAssertTrue(try stores.messageBlocks.inconsistencies().isEmpty)
    }

    /// 同一份 v10 库打开两次：块不会翻倍（回填是「清空后重建」，不是追加）。
    func testV11MigrationIsIdempotent() throws {
        try seedV10Database(messageCount: 250)
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let blocksAfterFirstOpen = try XCTUnwrap(stores).messageBlocks.count()
        XCTAssertEqual(blocksAfterFirstOpen, 2)
        stores = nil

        let reopened = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try reopened.messageBlocks.count(), blocksAfterFirstOpen)
        XCTAssertEqual(try reopened.messages.count(), 250)
        XCTAssertEqual(try reopened.messageBlocks.blocks(sessionId: "session-1").map(\.messageCount), [200, 50])
        XCTAssertTrue(try reopened.messageBlocks.inconsistencies().isEmpty)
    }

    /// 规范 §5 第 4 条：孤儿块（会话已不存在）要清掉。这里直接对回填函数造脏数据。
    func testBackfillRemovesOrphanBlocks() throws {
        let stores = try DatabaseStores.open(path: databasePath)
        try stores.personas.upsert(TestData.persona(id: personaId))
        try stores.sessions.upsert(TestData.session(id: "session-1", personaId: personaId))
        try stores.messages.upsert([
            TestData.message(id: "m-1", role: .user, content: "还在", createdAt: now)
        ])
        // 绕过仓储的级联删会话与消息，留下「块行还在、会话没了」的脏状态。
        try stores.database.execute("DELETE FROM chat_sessions")
        try stores.database.execute("DELETE FROM messages")
        XCTAssertEqual(try stores.messageBlocks.count(), 1)

        try Schema.backfillMessageBlocks(stores.database)
        XCTAssertEqual(try stores.messageBlocks.count(), 0, "孤儿块必须删掉")
        XCTAssertTrue(try stores.messageBlocks.inconsistencies().isEmpty)
    }

    // MARK: - v11 → v12：搜索服务选择（`spec/web-search/README.md` §1）

    /// 造一个「v11 的库」：当前 schema 建好后把 v12 加的列拆掉、灌一条配置、
    /// 把版本号记回 11 —— 和上面的 v8/v10 用例同一个手法，测的就是真实升级路径。
    private func seedV11Database(configId: String = "config-v11") throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let database = try XCTUnwrap(stores?.database)
        try database.execute("ALTER TABLE api_configs DROP COLUMN webToolsProvider")
        try database.execute("""
            INSERT INTO personas (id, name, description, avatarSeed, prompt, category, version,
                                  isDefault, createdAt, updatedAt)
            VALUES ('\(personaId)', '林岸', 'v11 的数据', 7, '你叫林岸。', '朋友', 2, 1, 1, \(now))
            """)
        try database.execute("""
            INSERT INTO api_configs (id, name, provider, baseUrl, model, customHeadersJson,
                                     allowInsecureHttp, createdAt, updatedAt,
                                     webToolsEnabled, webToolsBaseUrl, webToolsApiKeyCipher)
            VALUES ('\(configId)', '旧配置', 'OPENAI', 'https://api.example.com', 'deepseek-chat',
                    '{}', 0, 1, \(now), 1, 'https://api.deepseek.com', NULL)
            """)
        try database.setUserVersion(11)
        stores = nil
        database.close()
    }

    /// v11 → v12：加列、老数据不丢、老配置读成默认 MESSAGEAPI，且新 provider 能正常写读。
    func testV11ToV12AddsProviderColumnAndPreservesData() throws {
        try seedV11Database()
        let stores = try DatabaseStores.open(path: databasePath)

        XCTAssertEqual(try stores.database.userVersion(), Schema.version)
        let columns = try columnNames(stores.database, table: "api_configs")
        XCTAssertTrue(columns.contains(Schema.webToolsProviderColumn.name), "v12 必须补上 webToolsProvider")
        XCTAssertTrue(columns.contains("webToolsEnabled"), "老列不能因为迁移丢掉")
        XCTAssertTrue(columns.contains("webToolsBaseUrl"))
        XCTAssertTrue(columns.contains("webToolsApiKeyCipher"))

        // 老数据不丢：人格、配置、以及配置里的每一个非 provider 字段都还在。
        XCTAssertNotNil(try stores.personas.byId(personaId))
        let config = try XCTUnwrap(try stores.apiConfigs.byId("config-v11"))
        XCTAssertEqual(config.name, "旧配置")
        XCTAssertEqual(config.baseUrl, "https://api.example.com")
        XCTAssertEqual(config.model, "deepseek-chat")
        XCTAssertTrue(config.webToolsEnabled)
        XCTAssertEqual(config.webToolsBaseUrl, "https://api.deepseek.com")
        XCTAssertEqual(config.webToolsProvider, WebToolsProvider.messageAPI.rawValue, "老配置默认 MESSAGEAPI")

        // 新值能写能读（迁移不是把这一列变成只读的占位）。
        var updated = config
        updated.webToolsProvider = WebToolsProvider.tavily.rawValue
        try stores.apiConfigs.upsert(updated)
        XCTAssertEqual(
            try stores.apiConfigs.byId("config-v11")?.webToolsProvider,
            WebToolsProvider.tavily.rawValue
        )

        // 新建配置也能落到默认值。
        try stores.apiConfigs.upsert(ApiConfigEntity(
            id: "config-v12-new", name: "迁移后新建", baseUrl: "https://x.example",
            model: "deepseek-chat"
        ))
        XCTAssertEqual(
            try stores.apiConfigs.byId("config-v12-new")?.webToolsProvider,
            WebToolsProvider.messageAPI.rawValue
        )
    }

    /// 0.12 改名兼容：v12 补列的 DDL 默认值仍是历史 `DEEPSEEK`（不升 schema），
    /// 但实体读出来必须是 `MESSAGEAPI`；下一次写库会把这一列规范成新值。
    func testLegacyDeepSeekInDatabaseReadsAndWritesAsMessageAPI() throws {
        try seedV11Database()
        let stores = try DatabaseStores.open(path: databasePath)

        func rawProvider(id: String) throws -> String? {
            let statement = try stores.database.prepare(
                "SELECT webToolsProvider FROM api_configs WHERE id = ?"
            )
            try statement.bind([.text(id)])
            guard try statement.step() else { return nil }
            return statement.text(at: 0)
        }

        XCTAssertEqual(try rawProvider(id: "config-v11"), "DEEPSEEK",
                       "DDL 默认值保持历史值（不改 DDL、不升 schema）")
        XCTAssertEqual(
            try stores.apiConfigs.byId("config-v11")?.webToolsProvider,
            "MESSAGEAPI",
            "旧库里的 DEEPSEEK 读进来等价于 MESSAGEAPI"
        )

        // 大小写/空白变体也一样；读出来之后写回去一定落成规范值。
        try stores.database.executeUpdate(
            "UPDATE api_configs SET webToolsProvider = ? WHERE id = ?",
            [.text(" deepseek "), .text("config-v11")]
        )
        let legacy = try XCTUnwrap(try stores.apiConfigs.byId("config-v11"))
        XCTAssertEqual(legacy.webToolsProvider, "MESSAGEAPI")
        try stores.apiConfigs.upsert(legacy)
        XCTAssertEqual(try rawProvider(id: "config-v11"), "MESSAGEAPI", "写库一律写规范值")
    }

    /// 空库（有表、没数据）从 v11 升上来：列补上、版本对、不报错。
    func testV11ToV12OnEmptyDatabase() throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        try stores?.database.execute("ALTER TABLE api_configs DROP COLUMN webToolsProvider")
        try stores?.database.setUserVersion(11)
        stores = nil

        let reopened = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try reopened.database.userVersion(), Schema.version)
        XCTAssertTrue(
            try columnNames(reopened.database, table: "api_configs")
                .contains(Schema.webToolsProviderColumn.name)
        )
        XCTAssertEqual(try reopened.apiConfigs.count(), 0)
    }

    /// 同一份 v11 库打开两次：列不会重复、用户自己选的 provider 不会被迁移重置。
    func testV12MigrationIsIdempotent() throws {
        try seedV11Database()
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        var config = try XCTUnwrap(try stores?.apiConfigs.byId("config-v11"))
        config.webToolsProvider = WebToolsProvider.serper.rawValue
        try stores?.apiConfigs.upsert(config)
        stores = nil

        let reopened = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try reopened.database.userVersion(), Schema.version)
        XCTAssertEqual(try reopened.apiConfigs.count(), 1)
        XCTAssertEqual(
            try reopened.apiConfigs.byId("config-v11")?.webToolsProvider,
            WebToolsProvider.serper.rawValue,
            "迁移不能把用户已经选好的搜索服务改回默认值"
        )
        let columns = try columnNames(reopened.database, table: "api_configs")
        XCTAssertEqual(columns.filter { $0 == Schema.webToolsProviderColumn.name }.count, 1)
    }

    // MARK: - v12 → v13：人设引导三列（`spec/persona-guidance/README.md` §5）

    /// 造一个「v12 的库」：把 v13 加的五列拆掉、灌一份人格与预设、版本号记回 12。
    ///
    /// 与上面的用例同一个手法：在真库上回退，测到的就是真实的 `Schema.create` 升级路径。
    private func seedV12Database() throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let database = try XCTUnwrap(stores?.database)
        for column in Schema.personaGuidanceColumns {
            try database.execute("ALTER TABLE personas DROP COLUMN \(column.name)")
        }
        for column in Schema.presetGuidanceColumns {
            try database.execute("ALTER TABLE persona_presets DROP COLUMN \(column.name)")
        }
        try database.execute("""
            INSERT INTO personas (id, name, description, avatarSeed, prompt, category, version,
                                  isDefault, createdAt, updatedAt, traits, likes)
            VALUES ('\(personaId)', '林深', 'v12 的人格', 7, '你叫林深。', '朋友', 3,
                    1, 1, \(now), '["慢热"]', '["夜里散步"]')
            """)
        try database.execute("""
            INSERT INTO persona_presets (id, name, description, prompt, category, avatarSeed,
                                         createdAt, updatedAt)
            VALUES ('preset-v12', '旧预设', '', '预设提示词', '朋友', 3, 1, \(now))
            """)
        try database.setUserVersion(12)
        stores = nil
        database.close()
    }

    /// v12 → v13：数据一条不丢，新列默认 NULL（= 没有世界 / 话风 / 活动），版本号升到 13。
    func testV12ToV13AddsGuidanceColumnsWithoutLosingData() throws {
        try seedV12Database()
        let stores = try DatabaseStores.open(path: databasePath)

        XCTAssertEqual(try stores.database.userVersion(), Schema.version)
        let personaColumns = try columnNames(stores.database, table: "personas")
        for column in Schema.personaGuidanceColumns {
            XCTAssertTrue(personaColumns.contains(column.name), "缺列：personas.\(column.name)")
        }
        let presetColumns = try columnNames(stores.database, table: "persona_presets")
        for column in Schema.presetGuidanceColumns {
            XCTAssertTrue(presetColumns.contains(column.name), "缺列：persona_presets.\(column.name)")
        }

        // 数据不丢：旧的细分字段与预设都还在。
        let persona = try XCTUnwrap(try stores.personas.byId(personaId))
        XCTAssertEqual(persona.name, "林深")
        XCTAssertEqual(persona.version, 3)
        XCTAssertEqual(persona.traits, ["慢热"])
        XCTAssertEqual(persona.likes, ["夜里散步"])
        // 新列默认 null：老数据「没有世界/话风」是正确状态，不回填空 JSON。
        XCTAssertNil(persona.worldJson)
        XCTAssertNil(persona.speechStyleJson)
        XCTAssertNil(persona.activityJson)

        let preset = try XCTUnwrap(try stores.presets.byId("preset-v12"))
        XCTAssertEqual(preset.prompt, "预设提示词")
        XCTAssertNil(preset.worldJson)
        XCTAssertNil(preset.speechStyleJson)

        // 新列可写：写一份世界 + 话风，关掉再打开还在（实体绑定与列顺序都对得上）。
        var updated = persona
        updated.worldJson = WorldPreset(worldName: "临港新城", description: "沿海城市").jsonString()
        updated.speechStyleJson = SpeechStyle(tone: "简短直接", phrases: ["在忙，晚点说"]).jsonString()
        updated.activityJson = PersonaActivity(
            kind: .work, activity: "在事务所赶图", minutes: 90, availability: .busy, startedAt: now
        ).jsonString()
        try stores.personas.upsert(updated)
        stores.database.close()

        let reopened = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try reopened.database.userVersion(), Schema.version)
        let reloaded = try XCTUnwrap(try reopened.personas.byId(personaId))
        XCTAssertEqual(WorldPreset.decode(reloaded.worldJson)?.worldName, "临港新城")
        XCTAssertEqual(SpeechStyle.decode(reloaded.speechStyleJson)?.phrases, ["在忙，晚点说"])
        XCTAssertEqual(PersonaActivity.decode(reloaded.activityJson, now: now)?.activity, "在事务所赶图")
        // 二次打开不会重复加列（ALTER TABLE 会直接报错）。
        XCTAssertEqual(
            try reopened.database.scalarInt64(
                "SELECT COUNT(*) FROM pragma_table_info('personas') WHERE name = 'worldJson'", []
            ),
            1
        )
    }

    /// 空库（v12 但一个人格都没有）也要能升上来：列补齐、没有数据也成立。
    func testV12EmptyDatabaseMigratesToV13() throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let database = try XCTUnwrap(stores?.database)
        for column in Schema.personaGuidanceColumns {
            try database.execute("ALTER TABLE personas DROP COLUMN \(column.name)")
        }
        for column in Schema.presetGuidanceColumns {
            try database.execute("ALTER TABLE persona_presets DROP COLUMN \(column.name)")
        }
        try database.setUserVersion(12)
        stores = nil
        database.close()

        let reopened = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try reopened.database.userVersion(), Schema.version)
        XCTAssertEqual(try reopened.personas.count(), 0)
        for column in Schema.personaGuidanceColumns {
            XCTAssertTrue(
                try columnNames(reopened.database, table: "personas").contains(column.name),
                "空库升级也缺列：\(column.name)"
            )
        }
    }

    /// 迁移幂等：同一份 v12 库连开两次，列还是一列、数据还是一份。
    func testV13MigrationIsIdempotent() throws {
        try seedV12Database()
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        _ = stores
        stores = nil

        let reopened = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try reopened.database.userVersion(), Schema.version)
        XCTAssertEqual(try reopened.personas.count(), 1)
        XCTAssertEqual(try reopened.presets.count(), 1)
        let columns = try columnNames(reopened.database, table: "personas")
        for column in Schema.personaGuidanceColumns {
            XCTAssertTrue(columns.contains(column.name))
        }
    }


    // MARK: - v13 → v14：提示词翻译缓存两列（`spec/persona-translation/README.md` §4）

    /// 造一个「v13 的库」：把 v14 加的翻译两列拆掉、灌一份带世界的人格，版本号记回 13。
    private func seedV13Database() throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let database = try XCTUnwrap(stores?.database)
        for column in Schema.personaTranslationColumns {
            try database.execute("ALTER TABLE personas DROP COLUMN \(column.name)")
        }
        try database.execute("""
            INSERT INTO personas (id, name, description, avatarSeed, prompt, category, version,
                                  isDefault, createdAt, updatedAt, worldJson, speechStyleJson)
            VALUES ('\(personaId)', '林深', 'v13 的人格', 7, '你叫林深。', '朋友', 3,
                    1, 1, \(now), '{"worldName":"临港新城"}', '{"tone":"简短直接"}')
            """)
        try database.setUserVersion(13)
        stores = nil
        database.close()
    }

    /// v13 → v14：翻译两列补齐、数据一条不丢、老数据默认 NULL（= 还没翻译过）。
    func testV13ToV14AddsTranslationColumnsWithoutLosingData() throws {
        try seedV13Database()
        let stores = try DatabaseStores.open(path: databasePath)

        XCTAssertEqual(try stores.database.userVersion(), Schema.version)
        let columns = try columnNames(stores.database, table: "personas")
        for column in Schema.personaTranslationColumns {
            XCTAssertTrue(columns.contains(column.name), "缺列：personas.\(column.name)")
        }
        let persona = try XCTUnwrap(try stores.personas.byId(personaId))
        XCTAssertEqual(persona.name, "林深")
        XCTAssertEqual(persona.version, 3)
        XCTAssertEqual(WorldPreset.decode(persona.worldJson)?.worldName, "临港新城")
        XCTAssertNil(persona.translationJson, "老数据没有缓存是正确状态，不回填空 JSON")
        XCTAssertNil(persona.translationLang)

        // 两列可写、可被「只写缓存」的方法单独更新（不碰用户正在编辑的其他列）。
        let source = PersonaTranslatableSource(persona: persona)
        let translation = PersonaTranslation(
            lang: "en", translatedAt: now,
            sourceHash: PersonaTranslation.sourceHash(for: source),
            persona: .init(name: "Lin Shen"),
            world: WorldPreset(worldName: "Lingang New City")
        )
        let json = try XCTUnwrap(translation.jsonString())
        XCTAssertTrue(try stores.personas.updateTranslation(id: personaId, json: json, lang: "en"))
        stores.database.close()

        let reopened = try DatabaseStores.open(path: databasePath)
        let reloaded = try XCTUnwrap(try reopened.personas.byId(personaId))
        XCTAssertEqual(reloaded.translationLang, "en")
        XCTAssertEqual(PersonaTranslation.decode(reloaded.translationJson)?.persona.name, "Lin Shen")
        XCTAssertEqual(reloaded.name, "林深", "原文不受影响")
        // 幂等：二次打开不会重复加列。
        XCTAssertEqual(
            try reopened.database.scalarInt64(
                "SELECT COUNT(*) FROM pragma_table_info('personas') WHERE name = 'translationJson'", []
            ),
            1
        )
    }

    /// 空库 v13 也要能升上来。
    func testV13EmptyDatabaseMigratesToV14() throws {
        var stores: DatabaseStores? = try DatabaseStores.open(path: databasePath)
        let database = try XCTUnwrap(stores?.database)
        for column in Schema.personaTranslationColumns {
            try database.execute("ALTER TABLE personas DROP COLUMN \(column.name)")
        }
        try database.setUserVersion(13)
        stores = nil
        database.close()

        let reopened = try DatabaseStores.open(path: databasePath)
        XCTAssertEqual(try reopened.database.userVersion(), Schema.version)
        let columns = try columnNames(reopened.database, table: "personas")
        for column in Schema.personaTranslationColumns {
            XCTAssertTrue(columns.contains(column.name))
        }
        XCTAssertEqual(try reopened.personas.count(), 0)
    }

    // MARK: - 小工具

    private func indexNames(_ database: SQLiteDatabase) throws -> Set<String> {
        let statement = try database.prepare("SELECT name FROM sqlite_master WHERE type = 'index'")
        var names = Set<String>()
        while try statement.step() {
            if let name = statement.text(at: 0) { names.insert(name) }
        }
        return names
    }

    private func columnNames(_ database: SQLiteDatabase, table: String) throws -> Set<String> {
        let statement = try database.prepare("PRAGMA table_info(\(table))")
        var names = Set<String>()
        while try statement.step() {
            if let name = statement.text(at: 1) { names.insert(name) }
        }
        return names
    }
}
