import Foundation
import XCTest
@testable import YapKit

/// 数据层：建库、16 张表、v8、每个实体写读一遍、按 persona 查询、事务回滚。
///
/// 这里用真实的临时文件库（不是 `:memory:`）：WAL、`user_version`、关闭再打开这些行为
/// 只有在文件库上才看得出差别 —— 而它们正是「App 重启后数据还在」的前提。
final class SQLiteStoreTests: XCTestCase {

    private var databaseURL: URL!
    private var stores: DatabaseStores!

    override func setUpWithError() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-tests", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        databaseURL = directory.appendingPathComponent("db-\(UUID().uuidString).sqlite")
        stores = try DatabaseStores.open(path: databaseURL.path)
    }

    override func tearDownWithError() throws {
        stores = nil
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(atPath: databaseURL.path + suffix)
        }
    }

    // MARK: - 建表

    func testSchemaCreatesEveryTableAndSetsUserVersionToRoomV18() throws {
        XCTAssertEqual(Schema.version, 20,
                       "v20 = v19 的人格语言/主题总结 + 0.15 的模型优化档位（personas/presets）")
        XCTAssertEqual(Schema.tables.count, 18,
                       "18 张记录型表（v15 两张 + v16 stickers + v18 两张）")
        XCTAssertEqual(Schema.supplementaryTables.count, 4, "四张服务层用表（两张 1.8 遗留 + 两张服务端模式）")
        XCTAssertEqual(Schema.derivedTables, ["message_blocks"], "1 张派生表（块元数据）")
        XCTAssertEqual(Schema.allTables.count, 23, "18 + 4 + message_blocks")

        let names = try stores.database.tableNames()
        for table in Schema.allTables {
            XCTAssertTrue(names.contains(table), "缺表：\(table)")
        }
        // 规范点名要有的表（块分页、日记板、人格进化的迁移都依赖它们）。
        XCTAssertTrue(names.contains("message_blocks"), "v11 必须建 message_blocks")
        XCTAssertTrue(names.contains(Schema.diaryNotesTable), "v15 必须建 diary_notes")
        XCTAssertTrue(names.contains(Schema.personaPendingPatchesTable), "v15 必须建 persona_pending_patches")
        XCTAssertTrue(names.contains(Schema.stickersTable), "v16 必须建 stickers")
        XCTAssertTrue(names.contains(Schema.mentalActivitiesTable), "v18 必须建 mental_activities")
        XCTAssertTrue(names.contains(Schema.sessionMemoryTable), "v18 必须建 session_memory")
        XCTAssertEqual(try stores.database.userVersion(), Schema.version)
    }

    /// 建表语句的列顺序必须与实体映射的绑定顺序一致；
    /// 不一致时 SQL 不会报错（列名都在），但值会悄悄错位 —— 所以这里逐表核对。
    func testSchemaColumnsMatchEntityMappings() throws {
        let mappings: [(String, [String])] = [
            (PersonaEntity.tableName, PersonaEntity.columns),
            (ApiConfigEntity.tableName, ApiConfigEntity.columns),
            (ChatSessionEntity.tableName, ChatSessionEntity.columns),
            (MessageEntity.tableName, MessageEntity.columns),
            (MessageBlockEntity.tableName, MessageBlockEntity.columns),
            (MemoryEntity.tableName, MemoryEntity.columns),
            (MemoryTermEntity.tableName, MemoryTermEntity.columns),
            (MemoryVectorEntity.tableName, MemoryVectorEntity.columns),
            (MemoryVersionEntity.tableName, MemoryVersionEntity.columns),
            (MemoryConsolidationEntity.tableName, MemoryConsolidationEntity.columns),
            (PersonaPresetEntity.tableName, PersonaPresetEntity.columns),
            (ProactiveRuleEntity.tableName, ProactiveRuleEntity.columns),
            (AgentIntentEntity.tableName, AgentIntentEntity.columns),
            (ApiUsageEntity.tableName, ApiUsageEntity.columns),
            (DiaryNoteEntity.tableName, DiaryNoteEntity.columns),
            (PersonaPendingPatchEntity.tableName, PersonaPendingPatchEntity.columns),
            (StickerEntity.tableName, StickerEntity.columns),
            (MentalActivityEntity.tableName, MentalActivityEntity.columns),
            (SessionMemoryEntity.tableName, SessionMemoryEntity.columns)
        ]
        XCTAssertEqual(Set(mappings.map(\.0)), Set(Schema.tables + Schema.derivedTables))

        for (table, expectedColumns) in mappings {
            let statement = try stores.database.prepare("PRAGMA table_info(\(table))")
            var actual: [String] = []
            while try statement.step() {
                actual.append(try SQLiteRow(statement: statement).text("name"))
            }
            XCTAssertEqual(actual, expectedColumns, "表 \(table) 的列与实体映射不一致")
        }
    }

    // MARK: - 每个实体写读一遍

    func testPersonaRoundTrip() throws {
        var persona = makePersona()
        try stores.personas.upsert(persona)

        XCTAssertEqual(try stores.personas.byId(persona.id), persona)
        XCTAssertEqual(try stores.personas.count(), 1)
        XCTAssertEqual(try stores.personas.defaultPersona(), persona)

        // 同 id 再写一次 = 覆盖，不是插入第二条。
        persona.mood = "平静"
        try stores.personas.upsert(persona)
        XCTAssertEqual(try stores.personas.count(), 1)
        XCTAssertEqual(try stores.personas.byId(persona.id)?.mood, "平静")

        try stores.personas.delete(id: persona.id)
        XCTAssertNil(try stores.personas.byId(persona.id))
        XCTAssertEqual(try stores.personas.count(), 0)
    }

    func testApiConfigRoundTripKeepsLocalOnlySecretsInDatabase() throws {
        // 密文/自定义 Header 只在本机库里；备份层（BackupApiConfig）根本没有这两个字段。
        let config = ApiConfigEntity(
            id: "config-1",
            name: "DeepSeek 官方",
            provider: .openai,
            baseUrl: "https://api.deepseek.com",
            apiKeyCipher: "keychain-ref-1",
            model: "deepseek-chat",
            customHeadersJson: #"{"X-App":"myfriend"}"#,
            allowInsecureHttp: false,
            temperature: 0.8,
            topP: 0.95,
            maxOutputTokens: 2048,
            frequencyPenalty: 0.1,
            presencePenalty: 0.2,
            createdAt: 1_700_000_000_000,
            updatedAt: 1_700_000_100_000,
            webToolsEnabled: true,
            webToolsProvider: "TAVILY",
            webToolsBaseUrl: "https://api.tavily.com",
            webToolsApiKeyCipher: "keychain-ref-2"
        )
        try stores.apiConfigs.upsert(config)
        XCTAssertEqual(try stores.apiConfigs.byId(config.id), config)
        XCTAssertEqual(try stores.apiConfigs.allOrderedByUpdatedAt().count, 1)
    }

    func testChatSessionRoundTripAndPersonaQueries() throws {
        let session = makeSession()
        try stores.sessions.upsert(session)
        XCTAssertEqual(try stores.sessions.byId(session.id), session)
        XCTAssertEqual(try stores.sessions.byPersona(session.personaId).count, 1)
        XCTAssertEqual(try stores.sessions.count(personaId: session.personaId), 1)
        XCTAssertEqual(try stores.sessions.byPersona("别的 persona").count, 0)
        XCTAssertEqual(try stores.sessions.recent(limit: 5).first, session)
    }

    func testMessageRoundTripOrderingAndLatest() throws {
        let first = makeMessage(id: "msg-1", createdAt: 1_000, role: .user)
        let second = makeMessage(id: "msg-2", createdAt: 2_000, role: .assistant)
        try stores.messages.upsert([second, first]) // 故意乱序写入

        XCTAssertEqual(try stores.messages.byId("msg-1"), first)
        XCTAssertEqual(try stores.messages.bySession(first.sessionId).map(\.id), ["msg-1", "msg-2"])
        XCTAssertEqual(try stores.messages.count(sessionId: first.sessionId), 2)
        XCTAssertEqual(try stores.messages.latest(sessionId: first.sessionId)?.id, "msg-2")
        XCTAssertEqual(try stores.messages.byPersona(first.personaId).count, 2)
    }

    func testMemoryRoundTripAndScopedQueries() throws {
        let memory = makeMemory(id: "memory-1", personaId: "persona-1", pinned: true, status: "ACTIVE")
        let other = makeMemory(id: "memory-2", personaId: "persona-2", pinned: false, status: "ARCHIVED")
        try stores.memories.upsert([memory, other])

        XCTAssertEqual(try stores.memories.byId("memory-1"), memory)
        XCTAssertEqual(try stores.memories.byPersona("persona-1").map(\.id), ["memory-1"])
        XCTAssertEqual(try stores.memories.count(personaId: "persona-1"), 1)
        XCTAssertEqual(try stores.memories.pinned(personaId: "persona-1").map(\.id), ["memory-1"])
        XCTAssertEqual(try stores.memories.byStatus("ARCHIVED").map(\.id), ["memory-2"])
        XCTAssertEqual(try stores.memories.byStatus("ACTIVE", personaId: "persona-1").map(\.id), ["memory-1"])
    }

    func testMemoryTermCompositeKeyAndRebuild() throws {
        let terms = [
            MemoryTermEntity(memoryId: "memory-1", term: "哈尔", weight: 0.9),
            MemoryTermEntity(memoryId: "memory-1", term: "尔滨", weight: 0.8),
            MemoryTermEntity(memoryId: "memory-2", term: "外套", weight: 1.0)
        ]
        try stores.memoryTerms.upsert(terms)

        XCTAssertEqual(try stores.memoryTerms.terms(forMemory: "memory-1").map(\.term), ["哈尔", "尔滨"])
        XCTAssertEqual(try stores.memoryTerms.count(), 3)

        // 重建一条记忆的倒排索引：先删后写，别的记忆的词不许动。
        try stores.memoryTerms.replaceTerms(
            forMemory: "memory-1",
            with: [MemoryTermEntity(memoryId: "memory-1", term: "哈尔滨", weight: 1.0)]
        )
        XCTAssertEqual(try stores.memoryTerms.terms(forMemory: "memory-1").map(\.term), ["哈尔滨"])
        XCTAssertEqual(try stores.memoryTerms.terms(forMemory: "memory-2").map(\.term), ["外套"])

        try stores.memoryTerms.deleteTerms(forMemory: "memory-1")
        XCTAssertTrue(try stores.memoryTerms.terms(forMemory: "memory-1").isEmpty)
    }

    func testMemoryVectorRoundTripWithBlob() throws {
        // float32 小端字节：与 Android 存进 BLOB 的形状一致。
        let vector = Data([0x00, 0x00, 0x80, 0x3F, 0x00, 0x00, 0x00, 0x40]) // [1.0, 2.0]
        let entity = MemoryVectorEntity(
            memoryId: "memory-1", provider: "local-hash-v1", dim: 2, vector: vector, updatedAt: 1_700_000_000_000
        )
        try stores.memoryVectors.upsert(entity)

        XCTAssertEqual(try stores.memoryVectors.vector(memoryId: "memory-1", provider: "local-hash-v1"), entity)
        XCTAssertEqual(try stores.memoryVectors.vectors(provider: "local-hash-v1").count, 1)
        XCTAssertEqual(try stores.memoryVectors.count(provider: "local-hash-v1"), 1)

        try stores.memoryVectors.deleteVectors(forMemory: "memory-1")
        XCTAssertNil(try stores.memoryVectors.vector(memoryId: "memory-1", provider: "local-hash-v1"))
    }

    func testMemoryVersionRoundTrip() throws {
        let version = MemoryVersionEntity(
            id: "version-1", memoryId: "memory-1", at: 1_700_000_500_000,
            content: "你妹妹叫小满。", beforeContent: "你妹妹高考完了。",
            reason: "回忆被当时的心情改写", moodLabel: "怀念"
        )
        try stores.memoryVersions.upsert(version)
        XCTAssertEqual(try stores.memoryVersions.byId(version.id), version)
        XCTAssertEqual(try stores.memoryVersions.versions(forMemory: "memory-1").count, 1)
        XCTAssertEqual(try stores.memoryVersions.count(memoryId: "memory-1"), 1)
    }

    func testMemoryConsolidationRoundTrip() throws {
        let run = MemoryConsolidationEntity(
            id: "run-1", personaId: "persona-1", at: 1_700_000_700_000,
            kept: 12, merged: 3, archived: 2, forgotten: 1,
            summary: "把两件关于加班的事并成了一件", modelName: "deepseek-chat"
        )
        try stores.memoryConsolidations.upsert(run)
        XCTAssertEqual(try stores.memoryConsolidations.byId(run.id), run)
        XCTAssertEqual(try stores.memoryConsolidations.latest(personaId: "persona-1"), run)
        XCTAssertEqual(try stores.memoryConsolidations.count(personaId: "persona-1"), 1)
    }

    func testPresetRoundTripAndCategory() throws {
        let preset = PersonaPresetEntity(
            id: "preset-1", name: "温柔模板", description: "慢一点的说话方式",
            prompt: "你说话很慢", category: "温柔", avatarUri: nil, avatarSeed: 7,
            createdAt: 1_700_000_000_000, updatedAt: 1_700_000_100_000
        )
        try stores.presets.upsert(preset)
        XCTAssertEqual(try stores.presets.byId(preset.id), preset)
        XCTAssertEqual(try stores.presets.byCategory("温柔").count, 1)
        XCTAssertEqual(try stores.presets.byCategory("别的").count, 0)
        XCTAssertEqual(try stores.presets.allOrderedByUpdatedAt().count, 1)
    }

    func testProactiveRuleRoundTripAndDueWindow() throws {
        let rule = ProactiveRuleEntity(
            id: "rule-1", personaId: "persona-1", sessionId: "session-1",
            kind: .daily, timeOfDayMinutes: 22 * 60, intervalMinutes: nil,
            hint: "晚上问问今天怎么样", note: "他答应过", enabled: true, pending: false,
            source: .ai, nextTriggerAt: 1_700_002_000_000,
            lastTriggeredAt: 1_700_000_500_000, lastError: nil,
            createdAt: 1_700_000_000_000, updatedAt: 1_700_000_600_000
        )
        try stores.proactiveRules.upsert(rule)
        XCTAssertEqual(try stores.proactiveRules.byId(rule.id), rule)
        XCTAssertEqual(try stores.proactiveRules.byPersona("persona-1").count, 1)
        XCTAssertEqual(try stores.proactiveRules.enabled(personaId: "persona-1").count, 1)
        XCTAssertEqual(try stores.proactiveRules.count(personaId: "persona-1"), 1)
        XCTAssertTrue(try stores.proactiveRules.due(at: 1_700_001_000_000).isEmpty)
        XCTAssertEqual(try stores.proactiveRules.due(at: 1_700_002_000_000).map(\.id), ["rule-1"])
    }

    func testAgentIntentRoundTripAndOpenWindow() throws {
        let intent = AgentIntentEntity(
            id: "intent-1", personaId: "persona-1", sessionId: "session-1",
            kind: .reachOut, earliestAt: 1_700_003_000_000, latestAt: 1_700_003_600_000,
            note: "想问问他面试怎么样", hint: "别太准点", status: .pending,
            ruleId: "rule-1", createdAt: 1_700_000_100_000, sentAt: nil, lastError: nil
        )
        try stores.agentIntents.upsert(intent)
        XCTAssertEqual(try stores.agentIntents.byId(intent.id), intent)
        XCTAssertEqual(try stores.agentIntents.byPersona("persona-1").count, 1)
        XCTAssertEqual(try stores.agentIntents.byStatus(.pending, personaId: "persona-1").count, 1)
        XCTAssertEqual(try stores.agentIntents.count(personaId: "persona-1"), 1)
        // 窗口内可以执行；窗口过去就不该再被取出来。
        XCTAssertEqual(try stores.agentIntents.open(at: 1_700_003_100_000, personaId: "persona-1").count, 1)
        XCTAssertTrue(try stores.agentIntents.open(at: 1_700_004_000_000, personaId: "persona-1").isEmpty)
    }

    func testApiUsageRoundTripAndTokenSum() throws {
        let usage = [
            ApiUsageEntity(id: "u-1", at: 1_700_000_000_000, kind: "CHAT", personaId: "persona-1",
                           sessionId: "session-1", modelName: "deepseek-chat",
                           promptTokens: 1200, completionTokens: 400, costMicroUsd: 1_500),
            ApiUsageEntity(id: "u-2", at: 1_700_000_100_000, kind: "CONSOLIDATION", personaId: "persona-1",
                           promptTokens: 900, completionTokens: 100, costMicroUsd: 700),
            ApiUsageEntity(id: "u-3", at: 1_600_000_000_000, kind: "CHAT", costMicroUsd: 99)
        ]
        try stores.apiUsage.upsert(usage)

        XCTAssertEqual(try stores.apiUsage.byId("u-1"), usage[0])
        XCTAssertEqual(try stores.apiUsage.count(since: 1_650_000_000_000), 2)
        // 1.9：用量只按 token 统计（没有额度、也没有「本月花了多少钱」这件事）。
        XCTAssertEqual(try stores.apiUsage.sumTokens(since: 1_650_000_000_000), 2_600)
        XCTAssertEqual(try stores.apiUsage.since(1_650_000_000_000).map(\.id), ["u-2", "u-1"])
    }

    // MARK: - v15：日记板与待确认的人格变化

    /// 日记板：写读、按时间倒序、到点提醒、只提醒一次、跨人格隔离、硬删除。
    func testDiaryNoteRoundTripReminderAndIsolation() throws {
        let now: Int64 = 1_794_500_000_000
        let notes = [
            DiaryNoteEntity(id: "d-1", personaId: "persona-1", content: "明天问问他到家没",
                            createdAt: now - 3000, remindAt: now - 1000, updatedAt: now - 3000),
            DiaryNoteEntity(id: "d-2", personaId: "persona-1", content: "他提过想看那部电影",
                            createdAt: now - 2000, updatedAt: now - 2000),
            DiaryNoteEntity(id: "d-3", personaId: "persona-2", content: "别人格的便条",
                            createdAt: now - 1000, remindAt: now - 500, updatedAt: now - 1000)
        ]
        for note in notes { try stores.diaryNotes.upsert(note) }

        XCTAssertEqual(try stores.diaryNotes.byId("d-1"), notes[0])
        // 按 createdAt 倒序；`limit` 夹在 1…50。
        XCTAssertEqual(try stores.diaryNotes.byPersona("persona-1").map(\.id), ["d-2", "d-1"])
        XCTAssertEqual(try stores.diaryNotes.byPersona("persona-1", limit: 1).map(\.id), ["d-2"])
        XCTAssertEqual(try stores.diaryNotes.byPersona("persona-1", limit: 999).count, 2)
        XCTAssertEqual(try stores.diaryNotes.count(personaId: "persona-1"), 2)

        // 到点提醒：只属于这个人格，`remindedAt` 写一次之后不再出现。
        XCTAssertEqual(try stores.diaryNotes.due(personaId: "persona-1", now: now).map(\.id), ["d-1"])
        XCTAssertEqual(try stores.diaryNotes.due(now: now).map(\.id), ["d-1", "d-3"])
        XCTAssertTrue(try stores.diaryNotes.markReminded(id: "d-1", at: now))
        XCTAssertFalse(try stores.diaryNotes.markReminded(id: "d-1", at: now), "同一条只提醒一次")
        XCTAssertTrue(try stores.diaryNotes.due(personaId: "persona-1", now: now).isEmpty)
        XCTAssertEqual(try stores.diaryNotes.byId("d-1")?.remindedAt, now)

        // 硬删除：删了就查不到，也不能跨人格删。
        XCTAssertFalse(try stores.diaryNotes.remove(id: "d-2", personaId: "persona-2"),
                       "不能删别人格的便条")
        XCTAssertTrue(try stores.diaryNotes.remove(id: "d-2", personaId: "persona-1"))
        XCTAssertNil(try stores.diaryNotes.byId("d-2"))
        XCTAssertEqual(try stores.diaryNotes.count(personaId: "persona-1"), 1)
    }

    /// 待确认的人格变化：写读、去重、过期清理。
    func testPersonaPendingPatchRoundTripDedupeAndExpiry() throws {
        let now: Int64 = 1_794_500_000_000
        let patch = PersonaPendingPatchEntity(
            id: "p-1", personaId: "persona-1", sessionId: "session-1",
            patchJson: #"[{"field":"traits","op":"add","value":"更耐心"}]"#,
            summary: "性格多了「更耐心」", reason: "最近几次都在耐心听", createdAt: now
        )
        XCTAssertTrue(try stores.personaPendingPatches.insertIfNew(patch))
        XCTAssertFalse(try stores.personaPendingPatches.insertIfNew(PersonaPendingPatchEntity(
            id: "p-1-dup", personaId: "persona-1",
            patchJson: patch.patchJson, summary: "换个说法", reason: "", createdAt: now + 1
        )), "同一份 patch 不重复堆")
        let old = PersonaPendingPatchEntity(
            id: "p-old", personaId: "persona-1",
            patchJson: #"[{"field":"likes","op":"remove","value":"夜里散步"}]"#,
            summary: "喜欢的事少了「夜里散步」", reason: "", createdAt: now - PersonaEvolution.pendingTtlMillis - 1
        )
        try stores.personaPendingPatches.upsert(old)

        XCTAssertEqual(try stores.personaPendingPatches.byPersona("persona-1").map(\.id), ["p-1", "p-old"])
        XCTAssertEqual(try stores.personaPendingPatches.removeExpired(now: now), 1)
        XCTAssertEqual(try stores.personaPendingPatches.byPersona("persona-1").map(\.id), ["p-1"])

        try stores.personaPendingPatches.remove(id: "p-1")
        XCTAssertEqual(try stores.personaPendingPatches.count(personaId: "persona-1"), 0)
    }

    // MARK: - 事务与持久化

    func testTransactionRollsBackOnError() throws {
        let memory = makeMemory(id: "memory-tx", personaId: "persona-1")
        XCTAssertThrowsError(
            try stores.database.inTransaction {
                try stores.memories.upsert(memory)
                throw TestFailure.boom
            }
        )
        XCTAssertEqual(try stores.memories.count(), 0)
    }

    func testNestedTransactionDoesNotStartAnotherOne() throws {
        // 外层事务里调用「自己也开事务」的批量 upsert，必须能跑通（不能 BEGIN 两次）。
        let memories = (0..<5).map { makeMemory(id: "memory-\($0)", personaId: "persona-1") }
        try stores.database.inTransaction {
            try stores.memories.upsert(memories)
        }
        XCTAssertEqual(try stores.memories.count(), 5)
    }

    func testDataSurvivesClosingAndReopening() throws {
        let memory = makeMemory(id: "memory-persist", personaId: "persona-1")
        try stores.memories.upsert(memory)
        // 0.13：日记板与待确认的人格变化也要「关掉再打开还在」（重启不丢）。
        let note = DiaryNoteEntity(
            id: "diary-persist", personaId: "persona-1", content: "重启之后还要在",
            createdAt: 1_700_000_000_000, remindAt: 1_700_000_600_000,
            updatedAt: 1_700_000_000_000
        )
        try stores.diaryNotes.upsert(note)
        let patch = PersonaPendingPatchEntity(
            id: "patch-persist", personaId: "persona-1",
            patchJson: #"[{"field":"traits","op":"add","value":"更耐心"}]"#,
            summary: "性格多了「更耐心」", reason: "最近很耐心", createdAt: 1_700_000_000_000
        )
        try stores.personaPendingPatches.upsert(patch)
        stores = nil // 触发 close()

        let reopened = try DatabaseStores.open(path: databaseURL.path)
        XCTAssertEqual(try reopened.memories.byId(memory.id), memory)
        XCTAssertEqual(try reopened.diaryNotes.byId(note.id), note)
        XCTAssertEqual(try reopened.personaPendingPatches.byId(patch.id), patch)
        XCTAssertEqual(try reopened.database.userVersion(), Schema.version)
        stores = reopened
    }

    func testSchemaCreateIsIdempotent() throws {
        // 每次启动都会调 Schema.create；重复调用不能炸，也不能清数据。
        let memory = makeMemory(id: "memory-keep", personaId: "persona-1")
        try stores.memories.upsert(memory)
        try Schema.create(stores.database)
        XCTAssertEqual(try stores.memories.byId(memory.id), memory)
        XCTAssertEqual(try stores.database.userVersion(), Schema.version)
    }

    // MARK: - 样例

    private func makePersona() -> PersonaEntity {
        PersonaEntity(
            id: "persona-1",
            name: "林岸",
            description: "住在海边的人，说话慢",
            avatarUri: "file:///data/avatar.png",
            avatarSeed: 42,
            prompt: "你叫林岸，住在海边。",
            category: "伴侣",
            version: 3,
            defaultApiConfigId: "config-1",
            modelOverride: "deepseek-chat",
            isDefault: true,
            createdAt: 1_700_000_000_000,
            updatedAt: 1_700_000_100_000,
            personaCardJson: #"{"identity":{"name":"林岸"}}"#,
            quietSleepMinutes: 23 * 60 + 30,
            quietWakeMinutes: 7 * 60,
            mood: "疲惫",
            moodIntensity: 3,
            moodReason: "连着加了三天班",
            moodUpdatedAt: 1_700_000_200_000,
            moodNudgedAt: 1_700_000_300_000
        )
    }

    private func makeSession() -> ChatSessionEntity {
        ChatSessionEntity(
            id: "session-1",
            personaId: "persona-1",
            apiConfigId: "config-1",
            model: "deepseek-chat",
            title: "加班到深夜",
            lastMessagePreview: "那我等你回来",
            lastMessageAt: 1_700_000_900_000,
            unreadCount: 2,
            pinned: true,
            createdAt: 1_700_000_000_000,
            updatedAt: 1_700_000_950_000,
            lastPersonaVersion: 3,
            syncState: .synced,
            syncedPersonaVersion: 3,
            syncedMemoriesHash: "hash-abc",
            serverSessionId: "server-9",
            lastSyncError: nil,
            lastSyncAt: 1_700_000_940_000,
            lastMemoryExtractedCount: 12,
            historySummary: "你连着加了三天班，他每天都等到很晚。",
            historySummarizedUpTo: 1_700_000_800_000,
            historySummaryAt: 1_700_000_850_000
        )
    }

    private func makeMessage(id: String, createdAt: Int64, role: MessageRole) -> MessageEntity {
        MessageEntity(
            id: id,
            sessionId: "session-1",
            personaId: "persona-1",
            role: role,
            content: role == .user ? "今天又加班。" : "那我等你回来。",
            createdAt: createdAt,
            updatedAt: createdAt,
            status: .complete,
            errorMessage: nil,
            model: role == .assistant ? "deepseek-chat" : nil,
            edited: false,
            attachmentsJson: nil,
            toolTraceJson: role == .assistant ? #"[{"name":"web_search","ok":true}]"# : nil
        )
    }

    private func makeMemory(
        id: String,
        personaId: String,
        pinned: Bool = false,
        status: String = "ACTIVE"
    ) -> MemoryEntity {
        MemoryEntity(
            id: id,
            personaId: personaId,
            content: "你妹妹叫小满，今年高考完想去学建筑。",
            category: .relationship,
            importance: 87,
            pinned: pinned,
            sourceSessionId: "session-1",
            createdAt: 1_700_000_400_000,
            updatedAt: 1_700_000_500_000,
            lastUsedAt: 1_700_000_600_000,
            useCount: 7,
            drawer: "USER",
            sourceQuote: "我妹妹小满今年高考",
            status: status,
            strength: 0.42,
            archivedAt: 1_700_000_700_000,
            consolidatedAt: 1_700_000_800_000,
            tags: #"["妹妹","高考","建筑"]"#,
            happenedAt: 1_690_000_000_000,
            originalContent: "你妹妹叫小满，想去学建筑。",
            driftCount: 2
        )
    }
}

private enum TestFailure: Error {
    case boom
}
