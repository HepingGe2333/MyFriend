import Foundation
import XCTest
@testable import MyFriendKit

/// 备份：导出 → 清库 → 导入还原；以及和 Android 黄金样本的结构互通。
///
/// 这是服务层最关键的一条链：用户换手机时只有一次机会，任何「少了一半」都不该被静默吞掉。
final class BackupServiceTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var vectorIndex: MemoryVectorIndex!
    private var service: BackupService!
    /// 附件目录（头像 + 聊天图）：每个用例一份临时目录，绝不往 `MediaStorage.shared` 里写测试数据。
    private var attachments: URL!
    private let fileManager = FileManager.default

    override func setUpWithError() throws {
        db = try TempDatabase()
        settings = InMemorySettings(
            AppSettings(
                themeMode: .dark,
                accentColor: 0xFF112233,
                activePersonaId: "persona-1",
                agentLoopEnabled: false
            )
        )
        vectorIndex = MemoryVectorIndex(stores: db.stores, now: { TestData.now })
        attachments = fileManager.temporaryDirectory
            .appendingPathComponent("mf-backup-service-\(UUID().uuidString)", isDirectory: true)
        try fileManager.createDirectory(at: attachments, withIntermediateDirectories: true)
        service = BackupService(
            stores: db.stores, settings: settings, vectorIndex: vectorIndex,
            appVersion: "0.9.1-ios",
            avatarFileExists: { [fileManager] path in fileManager.fileExists(atPath: path) },
            mediaDirectory: attachments,
            now: { TestData.now }
        )
        try seed()
    }

    override func tearDownWithError() throws {
        service = nil
        vectorIndex = nil
        settings = nil
        db.cleanUp()
        db = nil
        if let attachments { try? fileManager.removeItem(at: attachments) }
        attachments = nil
    }

    private func seed() throws {
        try db.stores.personas.upsert([
            TestData.persona(),
            TestData.persona(id: "persona-2", name: "陈屿", isDefault: false)
        ])
        let summaryUpTo = TestData.now - 86_400_000
        try db.stores.sessions.upsert(
            TestData.session(summary: "你提过下个月要去哈尔滨，怕冷。", summarizedUpTo: summaryUpTo)
        )
        try db.stores.messages.upsert([
            TestData.message(id: "msg-1", role: .user, content: "下个月去哈尔滨。",
                             createdAt: TestData.now - 3 * 86_400_000),
            TestData.message(id: "msg-2", role: .assistant, content: "记得带厚外套。",
                             createdAt: TestData.now - 3 * 86_400_000 + 1000),
            TestData.message(id: "msg-3", role: .user, content: "好。",
                             createdAt: TestData.now - 2 * 86_400_000)
        ])
        try db.stores.memories.upsert([
            TestData.memory(id: "m-1", content: "你提过下个月要去哈尔滨，怕冷。",
                            status: "DORMANT", strength: 0.4,
                            tags: #"["哈尔滨","怕冷"]"#, happenedAt: TestData.now - 30 * 86_400_000),
            TestData.memory(id: "m-2", content: "你对燕麦过敏。", importance: 5)
        ])
        try db.stores.memoryConsolidations.upsert(
            MemoryConsolidationEntity(id: "run-1", personaId: "persona-1", at: TestData.now - 86_400_000,
                                      kept: 3, merged: 1, archived: 1, forgotten: 0,
                                      summary: "并了一条", modelName: "deepseek-chat")
        )
        try db.stores.agentIntents.upsert(
            AgentIntentEntity(id: "intent-1", personaId: "persona-1", kind: .reachOut,
                              earliestAt: TestData.now, latestAt: TestData.now + 3_600_000,
                              note: "问问他到家没", status: .pending, createdAt: TestData.now)
        )
        try db.stores.proactiveRules.upsert(
            ProactiveRuleEntity(id: "rule-1", personaId: "persona-1", kind: .daily,
                                timeOfDayMinutes: 22 * 60, hint: "晚上问问今天怎么样", note: "他答应过",
                                enabled: true, source: .ai, nextTriggerAt: TestData.now + 86_400_000,
                                createdAt: TestData.now - 86_400_000, updatedAt: TestData.now)
        )
        try db.stores.presets.upsert(
            PersonaPresetEntity(id: "preset-1", name: "温柔模板", description: "", prompt: "你说话很慢",
                                category: "温柔", avatarSeed: 7,
                                createdAt: TestData.now - 1000, updatedAt: TestData.now)
        )
        try db.stores.apiConfigs.upsert(
            ApiConfigEntity(
                id: "config-1", name: "DeepSeek 官方", provider: .openai,
                baseUrl: "https://api.deepseek.com", apiKeyCipher: "keychain-ref-1",
                model: "deepseek-chat", customHeadersJson: #"{"X-App":"myfriend"}"#,
                createdAt: TestData.now - 1000, updatedAt: TestData.now
            )
        )
    }

    // MARK: - 导出

    func testExportContainsEverythingAndNoSecrets() throws {
        let json = try service.export()
        XCTAssertFalse(BackupCodec.containsSecretField(json))
        XCTAssertFalse(json.contains("keychain-ref-1"), "密钥引用不许进备份")
        XCTAssertFalse(json.contains("customHeadersJson"))

        let bundle = try XCTUnwrap(service.preview(fileText: json).bundle)
        XCTAssertEqual(bundle.format, "myfriend.backup")
        XCTAssertEqual(bundle.formatVersion, 3)
        XCTAssertEqual(bundle.appVersion, "0.9.1-ios")
        XCTAssertTrue(bundle.apiKeysOmitted)
        XCTAssertEqual(bundle.personas.count, 2)
        XCTAssertEqual(bundle.memories.count, 2)
        // 只有 persona-1 有会话；persona-2 还没说过话，导出里就没有他那一段（一人最多一段）。
        XCTAssertEqual(bundle.sessions.count, 1)
        XCTAssertEqual(bundle.messages.count, 3)
        XCTAssertEqual(bundle.presets.count, 1)
        XCTAssertEqual(bundle.proactiveRules.count, 1)
        XCTAssertEqual(bundle.agentIntents.count, 1)
        XCTAssertEqual(bundle.memoryConsolidations.count, 1)
        XCTAssertEqual(bundle.apiConfigs.count, 1)
        // 会话的滚动摘要三个字段一起带走，否则换端后他会「突然忘事」。
        XCTAssertEqual(bundle.sessions.first?.summary, "你提过下个月要去哈尔滨，怕冷。")
        XCTAssertEqual(bundle.sessions.first?.summarizedUpTo, TestData.now - 86_400_000)
        // 记忆的结构字段也在。
        XCTAssertEqual(bundle.memories.first { $0.id == "m-1" }?.tags, #"["哈尔滨","怕冷"]"#)
        XCTAssertEqual(bundle.memories.first { $0.id == "m-1" }?.status, "DORMANT")
        // 设置只带可移植那部分（1.9 只剩外观与「常驻思考」）。
        XCTAssertEqual(bundle.settings?.themeMode, "DARK")
        XCTAssertEqual(bundle.settings?.agentLoopEnabled, false)
        XCTAssertEqual(bundle.formatVersion, 3)
        // 没有图片时 media 是空数组（键永远存在）。
        XCTAssertTrue(bundle.media.isEmpty)
    }

    // MARK: - 导出 → 清库 → 导入还原

    func testExportClearThenImportRestoresCountsAndContents() throws {
        let json = try service.export()
        let before = try XCTUnwrap(service.preview(fileText: json).bundle)

        let reset = DataReset(stores: db.stores, settings: settings, vectorIndex: vectorIndex)
        let cleared = try reset.clearChats()
        XCTAssertEqual(cleared.messages, 3)
        XCTAssertEqual(try db.stores.messages.count(), 0)
        XCTAssertEqual(try db.stores.memories.count(), 0)
        XCTAssertEqual(try db.stores.sessions.count(), 0)
        // 清空聊天与记忆：人格与 API 配置要留着。
        XCTAssertEqual(try db.stores.personas.count(), 2)
        XCTAssertEqual(try db.stores.apiConfigs.count(), 1)
        // 词元也不能留下孤儿。
        XCTAssertEqual(try db.stores.memoryTerms.count(), 0)

        let summary = try service.importBackup(text: json, mode: .merge)
        XCTAssertEqual(summary.personas, 2)
        XCTAssertEqual(summary.memories, 2)
        XCTAssertEqual(summary.sessions, 1)
        XCTAssertEqual(summary.messages, 3)
        XCTAssertTrue(summary.describe().contains("3 句话"))

        XCTAssertEqual(try db.stores.messages.count(), 3)
        XCTAssertEqual(try db.stores.sessions.count(), 1)
        XCTAssertEqual(try db.stores.memories.count(), 2)
        XCTAssertEqual(try db.stores.messages.bySession("session-1").map(\.content),
                       ["下个月去哈尔滨。", "记得带厚外套。", "好。"])
        let session = try XCTUnwrap(try db.stores.sessions.byId("session-1"))
        XCTAssertEqual(session.historySummary, before.sessions.first?.summary)
        XCTAssertEqual(session.historySummarizedUpTo, before.sessions.first?.summarizedUpTo)
        let restoredMemory = try XCTUnwrap(try db.stores.memories.byId("m-1"))
        XCTAssertEqual(restoredMemory.content, "你提过下个月要去哈尔滨，怕冷。")
        XCTAssertEqual(restoredMemory.strength, 0.4, accuracy: 0.000_001)
        XCTAssertEqual(restoredMemory.tags, #"["哈尔滨","怕冷"]"#)
        // 导入后索引重建：倒排与向量都要有。
        XCTAssertFalse(try db.stores.memoryTerms.terms(forMemory: "m-1").isEmpty)
        XCTAssertNotNil(try db.stores.memoryVectors.vector(memoryId: "m-1", provider: LocalEmbedding.PROVIDER))
        // 设置也接回来了（1.9 只剩可移植的那几项）。
        XCTAssertEqual(settings.load().themeMode, .dark)
        XCTAssertEqual(settings.load().agentLoopEnabled, false)
    }

    func testReplaceModeClearsLocalOnlyContentAndKeepsApiKey() throws {
        let json = try service.export()
        // 本机多出一条「备份里没有」的记忆：替换之后必须消失。
        try db.stores.memories.upsert([TestData.memory(id: "m-local-only", content: "这条只在这台手机上。")])

        let summary = try service.importBackup(text: json, mode: .replace)
        XCTAssertEqual(summary.mode, .replace)
        XCTAssertNil(try db.stores.memories.byId("m-local-only"))
        XCTAssertEqual(try db.stores.memories.count(), 2)

        // API 配置从不删除，只覆盖非敏感字段：本机 Key 必须留着。
        let config = try XCTUnwrap(try db.stores.apiConfigs.byId("config-1"))
        XCTAssertEqual(config.apiKeyCipher, "keychain-ref-1")
        XCTAssertEqual(config.customHeadersJson, #"{"X-App":"myfriend"}"#)
    }

    func testBadJSONIsRejectedWithoutTouchingDatabase() throws {
        let message = failureMessage {
            _ = try service.importBackup(text: "这不是备份", mode: .merge)
        }
        XCTAssertNotNil(message)
        XCTAssertTrue(message?.contains("不是一份有效的备份") == true)
        XCTAssertEqual(try db.stores.memories.count(), 2)
    }

    // MARK: - v11：备份仍是 v2，块元数据是派生的（导入时重建）

    private func seedExtraMessages(_ count: Int) throws {
        try db.stores.messages.upsert((0..<count).map { index in
            TestData.message(
                id: String(format: "extra-%03d", index),
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index) 句",
                createdAt: TestData.now - 86_400_000 + Int64(index) * 1000
            )
        })
    }

    func testReplaceImportRebuildsBlocksWithTheAppendRule() throws {
        try seedExtraMessages(250) // 原有 3 条 + 250 条 = 253 条 → 2 块
        XCTAssertEqual(
            try db.stores.messageBlocks.blocks(sessionId: "session-1").map(\.messageCount),
            [200, 53]
        )

        let json = try service.export()
        XCTAssertTrue(json.contains(#""formatVersion":3"#), "备份格式是 v3")
        XCTAssertFalse(json.contains("blockIndex"), "块号是派生数据，绝不进备份")

        let reset = DataReset(stores: db.stores, settings: settings, vectorIndex: vectorIndex)
        _ = try reset.clearChats()
        XCTAssertEqual(try db.stores.messageBlocks.count(), 0, "清空要把块一起清")

        _ = try service.importBackup(text: json, mode: .replace)
        XCTAssertEqual(try db.stores.messages.count(), 253)
        let blocks = try db.stores.messageBlocks.blocks(sessionId: "session-1")
        XCTAssertEqual(blocks.map(\.messageCount), [200, 53], "导入时按消息顺序用追加规则重建")
        XCTAssertEqual(blocks.first?.firstMessageId, "msg-1")
        XCTAssertTrue(try db.stores.messageBlocks.inconsistencies().isEmpty)
    }

    func testMergeImportKeepsBlocksSelfConsistent() throws {
        let json = try service.export()
        _ = try service.importBackup(text: json, mode: .merge)
        _ = try service.importBackup(text: json, mode: .merge)
        XCTAssertEqual(try db.stores.messages.count(), 3)
        XCTAssertEqual(
            try db.stores.messageBlocks.blocks(sessionId: "session-1").map(\.messageCount),
            [3],
            "同 id 消息再导一次不该把块撑大"
        )
        XCTAssertTrue(try db.stores.messageBlocks.inconsistencies().isEmpty)
    }

    // MARK: - 与 Android 黄金样本互通

    func testAndroidFixtureImportsIntoEmptyDatabase() throws {
        let directory = try XCTUnwrap(RepoLayout.backupFixtureDirectory(), "找不到 fixtures/backup")
        let text = try String(contentsOf: directory.appendingPathComponent("android-export-v1.json"),
                              encoding: .utf8)

        // 先清空（替换模式）：把 Android 那份样本完整接进来。
        let summary = try service.importBackup(text: text, mode: .replace)
        XCTAssertEqual(summary.personas, 1)
        XCTAssertEqual(summary.memories, 1)
        XCTAssertEqual(summary.sessions, 1)
        XCTAssertEqual(summary.messages, 2)
        XCTAssertEqual(summary.rules, 1)
        XCTAssertEqual(summary.presets, 1)

        let memory = try XCTUnwrap(try db.stores.memories.byId("m-1"))
        XCTAssertTrue(memory.content.contains("哈尔滨"))
        XCTAssertEqual(memory.drawer, "USER")
        let session = try XCTUnwrap(try db.stores.sessions.byId("22222222-2222-2222-2222-222222222222"))
        XCTAssertEqual(session.historySummary, "你提过下个月要去哈尔滨，怕冷。")
        XCTAssertEqual(try db.stores.messages.count(), 2)
        // 倒排索引随导入重建，样本里的记忆马上能搜到。
        XCTAssertFalse(try db.stores.memoryTerms.terms(forMemory: memory.id).isEmpty)
        // 备份里没有 Key：接回来的配置缺 Key 要提醒用户补一次。
        XCTAssertTrue(summary.warnings.contains { $0.contains("还缺 Key") })
    }

    // MARK: - 1.9：一人一段 / 细分字段 / 读回

    /// 备份里同一人格有多段：导入只留最新那段，其余消息不进库（§八）。
    ///
    /// 这条不只是「产品规则」——`chat_sessions.personaId` 上有唯一索引，
    /// 不合并的话导入会直接写失败。
    func testImportKeepsOnlyTheNewestSessionPerPersona() throws {
        var bundle = BackupSamples.iosBundle()
        let older = BackupSession(
            id: "ios-session-old", personaId: BackupSamples.personaId,
            apiConfigId: BackupSamples.configId, model: "deepseek-chat",
            title: "更早的对话", lastMessagePreview: "更早",
            lastMessageAt: bundle.sessions[0].lastMessageAt! - 86_400_000,
            createdAt: bundle.sessions[0].createdAt - 86_400_000,
            updatedAt: bundle.sessions[0].updatedAt - 86_400_000
        )
        bundle.sessions.append(older)
        bundle.messages.append(BackupMessage(
            id: "ios-msg-old", sessionId: older.id, personaId: BackupSamples.personaId,
            role: "USER", content: "这段会被丢掉", createdAt: older.lastMessageAt!
        ))

        let summary = try service.importBundle(bundle, mode: .replace)
        XCTAssertEqual(summary.sessions, 1, "一人只留一段")
        XCTAssertEqual(try db.stores.sessions.count(personaId: BackupSamples.personaId), 1)
        XCTAssertNil(try db.stores.sessions.byId("ios-session-old"))
        XCTAssertNil(try db.stores.messages.byId("ios-msg-old"), "被合并掉的那段的消息不该进库")
        XCTAssertTrue(summary.warnings.contains { $0.contains("多余的对话") })
        // 保留的是「最新」那一段。
        XCTAssertNotNil(try db.stores.sessions.byId(BackupSamples.sessionId))
    }

    /// 合并模式下，备份里那段和本机那段属于同一个人：并进本机那段，消息一条都不丢。
    ///
    /// Android 的 `BackupRepository` 用 `sessionIdMap` 做同一件事。两端必须一致，
    /// 否则同一份备份在 Android / iOS 上「接回来」的结果不同，用户会以为哪边坏了。
    func testMergeJoinsBackupSessionIntoLocalOneInsteadOfDroppingIt() throws {
        let localSessionId = "local-session-1"
        // 把本机的 persona-1 换成样本里的那个人：让备份与本机确实是「同一个人」。
        try db.stores.personas.upsert([
            TestData.persona(id: BackupSamples.personaId, name: "沈迟", isDefault: true)
        ])
        try db.stores.sessions.upsert(
            TestData.session(id: localSessionId, personaId: BackupSamples.personaId)
        )
        try db.stores.messages.upsert([
            TestData.message(
                id: "local-msg-1", sessionId: localSessionId, personaId: BackupSamples.personaId,
                role: .user, content: "本机说过的这句话", createdAt: TestData.now - 60_000
            )
        ])

        let summary = try service.importBundle(BackupSamples.iosBundle(), mode: .merge)

        // 一人一段：备份那段不单独建，本机那段还在。
        XCTAssertEqual(try db.stores.sessions.count(personaId: BackupSamples.personaId), 1)
        XCTAssertNil(try db.stores.sessions.byId(BackupSamples.sessionId))
        XCTAssertNotNil(try db.stores.sessions.byId(localSessionId))
        // 两边的消息落在同一段对话里，一条没丢。
        let texts = try db.stores.messages.bySession(localSessionId).map(\.content)
        XCTAssertTrue(texts.contains("本机说过的这句话"))
        XCTAssertTrue(texts.contains("我奶奶在那本《诗经》上写过一行字。"))
        XCTAssertEqual(try db.stores.messages.count(sessionId: BackupSamples.sessionId), 0)
        XCTAssertEqual(summary.skipped, 0, "并进来以后不该再有「落不进会话」的消息")
        XCTAssertTrue(summary.warnings.contains { $0.contains("并进本机那段") })
    }

    /// v1 的备份接进来：旧开关被忽略、人格补默认值、会话照旧一段。
    func testImportAndroidV1BackupIgnoresRetiredSettings() throws {
        let directory = try XCTUnwrap(RepoLayout.backupFixtureDirectory())
        let url = directory.appendingPathComponent("android-export-v1.json")
        let text = try String(contentsOf: url, encoding: .utf8)

        let summary = try service.importBackup(text: text, mode: .replace)
        XCTAssertEqual(summary.personas, 1)
        XCTAssertEqual(summary.sessions, 1)
        // v1 → v2 的一句提醒（§八）。
        XCTAssertTrue(summary.warnings.contains { $0.contains("1.9 之前的备份") })

        let persona = try XCTUnwrap(try db.stores.personas.byId("11111111-1111-1111-1111-111111111111"))
        XCTAssertEqual(persona.name, "林深")
        XCTAssertEqual(persona.traits, [], "v1 没有细分字段：默认空")
        XCTAssertEqual(persona.proactiveLevel, 3)
        // 本机的设置没有被 v1 里那串旧开关改坏：主题照备份，记忆类开关根本不存在。
        XCTAssertEqual(settings.load().themeMode, .system)
    }

    /// 导出 → 导入往返：细分字段、会话覆盖、已读时间都要一致。
    func testRoundTripKeepsProfileFieldsOverridesAndReadAt() throws {
        var persona = TestData.persona()
        persona.traits = ["慢热", "嘴硬心软"]
        persona.likes = ["夜里散步"]
        persona.behaviors = ["先听完再给建议"]
        persona.backstory = "在旧书店长大。"
        persona.quirks = "叫他阿迟。"
        persona.proactiveLevel = 5
        persona.memoryRetention = 4
        persona.extractionEvery = 9
        persona.sleepEnabled = true
        persona.wakeThreshold = 12
        persona.dndOnSleep = true
        try db.stores.personas.upsert(persona)

        var session = try XCTUnwrap(try db.stores.sessions.byId("session-1"))
        session.memoryRetentionOverride = 2
        session.extractionEveryOverride = 11
        try db.stores.sessions.upsert(session)

        var message = try XCTUnwrap(try db.stores.messages.byId("msg-1"))
        message.readAt = TestData.now - 1000
        try db.stores.messages.upsert(message)

        let json = try service.export()
        let summary = try service.importBundle(
            try XCTUnwrap(BackupCodec.decode(json).bundle), mode: .replace
        )
        XCTAssertEqual(summary.personas, 2)

        let restored = try XCTUnwrap(try db.stores.personas.byId(persona.id))
        XCTAssertEqual(restored.traits, ["慢热", "嘴硬心软"])
        XCTAssertEqual(restored.likes, ["夜里散步"])
        XCTAssertEqual(restored.behaviors, ["先听完再给建议"])
        XCTAssertEqual(restored.backstory, "在旧书店长大。")
        XCTAssertEqual(restored.quirks, "叫他阿迟。")
        XCTAssertEqual(restored.proactiveLevel, 5)
        XCTAssertEqual(restored.memoryRetention, 4)
        XCTAssertEqual(restored.extractionEvery, 9)
        XCTAssertEqual(restored.wakeThreshold, 12)
        XCTAssertTrue(restored.dndOnSleep)

        let restoredSession = try XCTUnwrap(try db.stores.sessions.byId("session-1"))
        XCTAssertEqual(restoredSession.memoryRetentionOverride, 2)
        XCTAssertEqual(restoredSession.extractionEveryOverride, 11)
        XCTAssertEqual(try db.stores.messages.byId("msg-1")?.readAt, TestData.now - 1000)
    }

    // MARK: - 导入大小上限（0.10.2 安全审计 P2-3）

    /// 超限的备份在**读文件之前**就被拒：不会把几百 MB 读成字符串再 OOM。
    func testReadBackupTextRejectsOversizedFileBeforeReading() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-backup-limit-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let huge = directory.appendingPathComponent("huge.json")
        FileManager.default.createFile(atPath: huge.path, contents: Data())
        let handle = try FileHandle(forWritingTo: huge)
        // 稀疏文件：只改文件长度，不真的写 256 MB 数据。
        try handle.truncate(atOffset: UInt64(BackupService.maxImportBytes + 1))
        try handle.close()

        XCTAssertThrowsError(try service.readBackupText(at: huge)) { error in
            let importError = error as? BackupImportError
            XCTAssertEqual(importError?.message, BackupService.tooLargeMessage)
            XCTAssertTrue(importError?.message.contains("256 MB") ?? false)
        }

        // 小文件照常读出来（闸不能把正常备份挡在外面）。
        let small = directory.appendingPathComponent("small.json")
        let json = try service.export()
        try json.write(to: small, atomically: true, encoding: .utf8)
        XCTAssertEqual(try service.readBackupText(at: small), json)
    }

    /// 就算调用方绕过 `readBackupText`（剪贴板/旧代码），解析前还有一道按字节数的闸。
    func testImportRejectsOversizedText() throws {
        let oversized = String(repeating: "a", count: BackupService.maxImportBytes + 1)
        XCTAssertThrowsError(try service.importBackup(text: oversized, mode: .merge)) { error in
            XCTAssertEqual((error as? BackupImportError)?.message, BackupService.tooLargeMessage)
        }
        // 库一点没动。
        XCTAssertEqual(try db.stores.personas.count(), 2)
    }

    func testImportLimitBoundaryIs256MB() {
        // v3：128 MiB 图片 base64 之后约 171 MiB，加上文字仍在 256 MiB 之内。
        XCTAssertEqual(BackupService.maxImportBytes, 256 * 1024 * 1024)
        XCTAssertEqual(BackupService.maxExportBytes, 256 * 1024 * 1024)
        XCTAssertEqual(BackupService.maxMediaBytes, 128 * 1024 * 1024)
        XCTAssertEqual(BackupService.maxMediaFileBytes, 8 * 1024 * 1024)
    }

    // MARK: - 导出不带设备绝对路径（0.10.2 安全审计 P2-5）

    /// 本机头像是 `file:///…/attachments/avatar-….jpg`：导出时置空，备份里没有设备路径。
    func testExportStripsLocalAbsoluteAvatarUri() throws {
        var persona = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        persona.avatarUri = "file:///var/mobile/Containers/Data/Application/ABC/Documents/attachments/avatar-1.jpg"
        try db.stores.personas.upsert(persona)
        var preset = try XCTUnwrap(try db.stores.presets.byId("preset-1"))
        preset.avatarUri = "/Users/someone/Library/preset.png"
        try db.stores.presets.upsert(preset)

        let json = try service.export()
        XCTAssertFalse(json.contains("file:///"), "备份里不该出现设备绝对路径：\(json.prefix(200))")
        XCTAssertFalse(json.contains("/Users/someone"))
        XCTAssertFalse(json.contains("avatar-1.jpg"))

        let bundle = try XCTUnwrap(service.preview(fileText: json).bundle)
        XCTAssertNil(bundle.personas.first { $0.id == "persona-1" }?.avatarUri)
        XCTAssertNil(bundle.presets.first { $0.id == "preset-1" }?.avatarUri)

        // 老备份里的绝对路径仍然能读进来（导入侧「文件不存在则清空」照旧）。
        let withPath = json.replacingOccurrences(
            of: "\"name\":\"林岸\"",
            with: "\"name\":\"林岸\",\"avatarUri\":\"file:///var/mobile/Containers/x/avatar-1.jpg\""
        )
        XCTAssertTrue(withPath.contains("file:///"), "夹具应该真的把路径写回去了")
        let decoded = try XCTUnwrap(BackupCodec.decode(withPath).bundle)
        XCTAssertEqual(decoded.personas.first?.avatarUri, "file:///var/mobile/Containers/x/avatar-1.jpg")
    }

    /// v3 的头像策略：只写**裸文件名**（与 Android `BackupAvatarPath.portable` 同一条规则）。
    func testExportedAvatarUriPolicy() {
        XCTAssertNil(BackupService.exportedAvatarUri(nil))
        XCTAssertNil(BackupService.exportedAvatarUri("   "))
        // 承载设备路径的值一律收成裸名字（路径本身不写进备份）。
        XCTAssertEqual(
            BackupService.exportedAvatarUri("file:///var/mobile/Containers/Data/Application/ABC/attachments/avatar-1.jpg"),
            "avatar-1.jpg"
        )
        XCTAssertEqual(
            BackupService.exportedAvatarUri("FILE:///var/mobile/attachments/avatar-2.png"),
            "avatar-2.png"
        )
        // 不是我们自己的头像文件（没有 `avatar-` 前缀）→ 不写，避免把 /etc/passwd 写成 passwd。
        XCTAssertNil(BackupService.exportedAvatarUri("file:///var/mobile/a.jpg"))
        XCTAssertNil(BackupService.exportedAvatarUri("/var/mobile/a.jpg"))
        XCTAssertNil(BackupService.exportedAvatarUri("file:///etc/passwd"))
        // 带目录穿越的整条值直接拒绝。
        XCTAssertNil(BackupService.exportedAvatarUri("file:///var/mobile/../databases/avatar-1.jpg"))
        // 非文件 URI（网络头像 / content://）不是我们的文件：v3 不写（字节带不走）。
        XCTAssertNil(BackupService.exportedAvatarUri("https://example.com/avatar-1.png"))
        XCTAssertNil(BackupService.exportedAvatarUri("content://media/external/images/avatar-1.jpg"))
        // 已经是裸名字的（导入后再导出）原样保留。
        XCTAssertEqual(BackupService.exportedAvatarUri("  avatar-3.jpg  "), "avatar-3.jpg")
    }

    // MARK: - v3：头像与聊天图片真的跟着备份换手机

    private static let jpegBytes = Data(base64Encoded: BackupSamples.sampleJPEGBase64)!
    private static let pngBytes = Data(base64Encoded: BackupSamples.samplePNGBase64)!

    /// 一个稳定的短摘要（FNV-1a 64）：报告里用它证明「换手机前后是同一批字节」。
    private static func digest(_ data: Data) -> String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in data {
            hash = (hash ^ UInt64(byte)) &* 0x100_0000_01b3
        }
        return String(format: "%016llx", hash)
    }

    @discardableResult
    private func writeMedia(_ name: String, _ data: Data, in directory: URL) throws -> String {
        let url = directory.appendingPathComponent(name)
        try data.write(to: url)
        return url.absoluteString
    }

    private func restoredFileNames(in directory: URL) -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []).sorted()
    }

    /// 一次完整的换机：两台设备、两份目录、两份库。
    func testV3RoundTripCarriesAvatarsAndChatImagesToANewPhone() throws {
        // ---- 旧手机：两本人格头像、一张「我的头像」、一个预设头像、两张聊天图 ----
        let persona1Avatar = try writeMedia("avatar-persona-1.jpg", Self.jpegBytes, in: attachments)
        let persona2Avatar = try writeMedia("avatar-persona-2.png", Self.pngBytes, in: attachments)
        let presetAvatar = try writeMedia("avatar-preset-1.png", Self.pngBytes, in: attachments)
        let userAvatar = try writeMedia("avatar-me.jpg", Self.jpegBytes, in: attachments)
        let chat1 = try writeMedia("chatImage-msg-1.jpg", Self.jpegBytes, in: attachments)
        let chat2 = try writeMedia("chatImage-msg-2.png", Self.pngBytes, in: attachments)

        var persona1 = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        persona1.avatarUri = persona1Avatar
        try db.stores.personas.upsert(persona1)
        var persona2 = try XCTUnwrap(try db.stores.personas.byId("persona-2"))
        persona2.avatarUri = persona2Avatar
        try db.stores.personas.upsert(persona2)
        var preset = try XCTUnwrap(try db.stores.presets.byId("preset-1"))
        preset.avatarUri = presetAvatar
        try db.stores.presets.upsert(preset)
        var current = settings.load()
        current.myAvatarUri = userAvatar
        settings.save(current)

        func withAttachments(_ message: MessageEntity, _ json: String) -> MessageEntity {
            var copy = message
            copy.attachmentsJson = json
            return copy
        }
        try db.stores.messages.upsert([
            withAttachments(
                try XCTUnwrap(try db.stores.messages.byId("msg-1")),
                #"[{"uri":"\#(chat1)","mime":"image/jpeg","width":2,"height":2}]"#
            ),
            withAttachments(
                try XCTUnwrap(try db.stores.messages.byId("msg-2")),
                #"[{"uri":"\#(chat2)","mime":"image/png","width":2,"height":2}]"#
            )
        ])

        // ---- 导出 ----
        let exported = try service.exportWithWarnings()
        XCTAssertTrue(exported.warnings.isEmpty, "没有超限时不该有导出提醒：\(exported.warnings)")
        let bundle = try XCTUnwrap(BackupCodec.decode(exported.text).bundle)

        XCTAssertEqual(bundle.media.count, 6, "两张人格头像 + 我的头像 + 预设头像 + 两张聊天图")
        XCTAssertEqual(bundle.media.map(\.kind), [
            "PERSONA_AVATAR", "PERSONA_AVATAR", "USER_AVATAR", "PERSONA_AVATAR", "CHAT_IMAGE", "CHAT_IMAGE"
        ])
        let totalMediaBytes = bundle.media.reduce(0) { $0 + $1.bytes }
        XCTAssertEqual(totalMediaBytes, Self.jpegBytes.count * 3 + Self.pngBytes.count * 3)
        for media in bundle.media {
            let decoded = try XCTUnwrap(BackupMediaTransfer.decode(media.data))
            XCTAssertEqual(decoded.count, media.bytes)
            print("v3 export media: \(media.kind) \(media.fileName) \(media.bytes) bytes fnv=\(Self.digest(decoded))")
        }

        // 裸文件名 = 关联键；备份里**没有**设备路径。
        XCTAssertEqual(bundle.personas.first { $0.id == "persona-1" }?.avatarUri, "avatar-persona-1.jpg")
        XCTAssertEqual(bundle.personas.first { $0.id == "persona-2" }?.avatarUri, "avatar-persona-2.png")
        XCTAssertEqual(bundle.presets.first?.avatarUri, "avatar-preset-1.png")
        XCTAssertFalse(exported.text.contains("file:///"), "备份里不该出现设备绝对路径")
        XCTAssertFalse(exported.text.contains(attachments.path), "备份里不该出现旧容器的目录")
        XCTAssertFalse(exported.text.contains("/var/"))
        // 「我的头像」不进 BackupSettings（那是设备路径），只以 USER_AVATAR 走 media。
        XCTAssertFalse(exported.text.contains(userAvatar))
        let messageUris = bundle.messages.flatMap { AttachmentsCodec.decode($0.attachmentsJson).map(\.uri) }
        XCTAssertEqual(Set(messageUris), ["chatImage-msg-1.jpg", "chatImage-msg-2.png"])
        for uri in messageUris {
            XCTAssertFalse(uri.contains("/"), "v3 的附件 uri 只写裸文件名：\(uri)")
        }
        // USER_AVATAR 没有归属人格：JSON 里不写 personaId（预设头像也一样）。
        let root = try XCTUnwrap(JSONValueParser.object(from: exported.text))
        guard case .array(let mediaItems)? = root["media"] else { return XCTFail("media 段丢了") }
        for item in mediaItems {
            guard case .object(let object) = item,
                  case .string(let kind)? = object["kind"],
                  case .string(let fileName)? = object["fileName"] else {
                return XCTFail("media 条目形状不对")
            }
            if kind == "USER_AVATAR" || fileName == "avatar-preset-1.png" {
                XCTAssertNil(object["personaId"], "\(fileName) 不该写 personaId")
            } else {
                let expectedPersonaId = fileName == "avatar-persona-2.png" ? "persona-2" : "persona-1"
                XCTAssertEqual(object["personaId"], .string(expectedPersonaId), fileName)
            }
        }

        // ---- 新手机：空库 + 空附件目录 ----
        let newDB = try TempDatabase()
        defer { newDB.cleanUp() }
        let newDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("mf-backup-newphone-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: newDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: newDirectory) }
        let newSettings = InMemorySettings()
        let newService = BackupService(
            stores: newDB.stores, settings: newSettings, appVersion: "0.12.0-ios",
            avatarFileExists: { path in FileManager.default.fileExists(atPath: path) },
            mediaDirectory: newDirectory,
            now: { TestData.now }
        )
        let summary = try newService.importBackup(text: exported.text, mode: .replace)
        XCTAssertEqual(summary.personas, 2)
        XCTAssertEqual(summary.messages, bundle.messages.count)
        XCTAssertFalse(
            summary.warnings.contains { $0.contains("图片没有跟着备份走") },
            "v3 备份里的图片不该再丢：\(summary.warnings)"
        )
        XCTAssertFalse(
            summary.warnings.contains { $0.contains("头像文件找不到") },
            "v3 备份里的头像不该再丢：\(summary.warnings)"
        )

        // 6 个文件真的落在新手机的附件目录里，名字是新的 UUID（绝不复用备份里的 fileName）。
        let names = restoredFileNames(in: newDirectory)
        XCTAssertEqual(names.count, 6)
        XCTAssertEqual(names.filter { $0.hasPrefix("avatar-") }.count, 4)
        XCTAssertEqual(names.filter { $0.hasPrefix("chatImage-") }.count, 2)
        for name in names {
            XCTAssertFalse(
                ["avatar-persona-1.jpg", "avatar-persona-2.png", "avatar-preset-1.png",
                 "avatar-me.jpg", "chatImage-msg-1.jpg", "chatImage-msg-2.png"].contains(name),
                "落盘名必须重建，不能复用备份里的名字：\(name)"
            )
            let size = (try? newDirectory.appendingPathComponent(name).resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
            let data = try Data(contentsOf: newDirectory.appendingPathComponent(name))
            print("v3 import media: \(name) \(size) bytes fnv=\(Self.digest(data))")
        }
        XCTAssertEqual(
            Set(names.map { $0.split(separator: ".").last.map(String.init) ?? "" }), ["jpg", "png"],
            "扩展名来自 mime（jpeg→jpg / png→png）"
        )

        // 头像：人格/预设的 avatarUri 指向新文件，且字节与旧手机一致。
        func assertRestoredAvatar(_ stored: String?, matchesOriginal original: Data, label: String) throws {
            let uri = try XCTUnwrap(stored, "\(label) 的头像没接回来")
            let url = try XCTUnwrap(URL(string: uri))
            XCTAssertTrue(
                AttachmentLocation.isWithin(newDirectory, url),
                "\(label) 的头像要落在新附件目录里：\(uri)"
            )
            XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
            XCTAssertEqual(try Data(contentsOf: url), original, "\(label) 的字节要一模一样")
        }
        try assertRestoredAvatar(
            newDB.stores.personas.byId("persona-1")?.avatarUri, matchesOriginal: Self.jpegBytes, label: "persona-1"
        )
        try assertRestoredAvatar(
            newDB.stores.personas.byId("persona-2")?.avatarUri, matchesOriginal: Self.pngBytes, label: "persona-2"
        )
        try assertRestoredAvatar(
            newDB.stores.presets.byId("preset-1")?.avatarUri, matchesOriginal: Self.pngBytes, label: "preset-1"
        )
        // 「我的头像」：设置里写成新路径。
        try assertRestoredAvatar(newSettings.load().myAvatarUri, matchesOriginal: Self.jpegBytes, label: "我的头像")

        // 聊天图片：消息的 attachmentsJson 指向新文件，元数据（mime / 宽高）还在。
        for (messageId, expected) in [("msg-1", Self.jpegBytes), ("msg-2", Self.pngBytes)] {
            let message = try XCTUnwrap(try newDB.stores.messages.byId(messageId))
            let restored = AttachmentsCodec.decode(message.attachmentsJson)
            XCTAssertEqual(restored.count, 1, "\(messageId) 应该接回一张图")
            let attachment = try XCTUnwrap(restored.first)
            let url = try XCTUnwrap(URL(string: attachment.uri))
            XCTAssertTrue(AttachmentLocation.isWithin(newDirectory, url))
            XCTAssertEqual(try Data(contentsOf: url), expected)
            XCTAssertEqual(attachment.width, 2)
            XCTAssertEqual(attachment.height, 2)
            XCTAssertEqual(
                attachment.mime,
                messageId == "msg-1" ? "image/jpeg" : "image/png"
            )
            XCTAssertFalse(attachment.uri.contains("chatImage-msg-"), "不能指回旧文件名")
        }
    }

    /// v1/v2 备份（没有 media）：行为与 v3 之前一字不差 —— 图片丢掉、给老警告，头像清空。
    func testV1BackupWithoutMediaKeepsTheOldBehaviour() throws {
        let directory = try XCTUnwrap(RepoLayout.backupFixtureDirectory())
        let text = try String(
            contentsOf: directory.appendingPathComponent("ios-export-v1.json"), encoding: .utf8
        )
        let summary = try service.importBackup(text: text, mode: .replace)

        XCTAssertEqual(summary.personas, 1)
        XCTAssertTrue(
            summary.warnings.contains { $0.contains("图片没有跟着备份走") },
            "老备份没有 media，要保留老警告：\(summary.warnings)"
        )
        XCTAssertTrue(
            summary.warnings.contains { $0.contains("头像文件找不到") },
            "老备份的头像路径在这台手机上没有文件：\(summary.warnings)"
        )
        XCTAssertFalse(
            summary.warnings.contains { $0.contains("没接回来（文件损坏或格式不支持）") },
            "没有 media 就不该有媒体警告：\(summary.warnings)"
        )
        let persona = try XCTUnwrap(try db.stores.personas.byId("ios-persona-1"))
        XCTAssertNil(persona.avatarUri, "老备份里的设备路径一律不落库")
        let messages = try db.stores.messages.bySession(BackupSamples.sessionId)
        XCTAssertTrue(messages.allSatisfy { ($0.attachmentsJson ?? "").isEmpty }, "老备份的图片引用丢掉")
        XCTAssertEqual(restoredFileNames(in: attachments), [], "老备份不会写任何媒体文件")
    }

    /// v2 备份（没有 media）也照样能接回来：同样只剩文字，但正文与记忆一条不少。
    func testV2BackupWithoutMediaStillImports() throws {
        let directory = try XCTUnwrap(RepoLayout.backupFixtureDirectory())
        let text = try String(
            contentsOf: directory.appendingPathComponent("ios-export-v2.json"), encoding: .utf8
        )
        let summary = try service.importBackup(text: text, mode: .replace)
        XCTAssertEqual(summary.personas, 1)
        XCTAssertEqual(summary.messages, 3)
        XCTAssertTrue(
            summary.warnings.contains { $0.contains("图片没有跟着备份走") },
            "\(summary.warnings)"
        )
        XCTAssertEqual(restoredFileNames(in: attachments), [])
    }

    /// 同一份 v3 备份接两次、换两台手机：落盘名各不相同（新 UUID），字节一致。
    func testV3ImportGeneratesFreshFileNamesEveryTime() throws {
        let avatarURL = try writeMedia("avatar-persona-1.jpg", Self.jpegBytes, in: attachments)
        var persona = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        persona.avatarUri = avatarURL
        try db.stores.personas.upsert(persona)
        let text = try service.export()

        func importOnFreshPhone() throws -> [String] {
            let newDB = try TempDatabase()
            defer { newDB.cleanUp() }
            let directory = FileManager.default.temporaryDirectory
                .appendingPathComponent("mf-backup-phone-\(UUID().uuidString)", isDirectory: true)
            defer { try? FileManager.default.removeItem(at: directory) }
            let backup = BackupService(
                stores: newDB.stores, settings: InMemorySettings(),
                avatarFileExists: { FileManager.default.fileExists(atPath: $0) },
                mediaDirectory: directory, now: { TestData.now }
            )
            _ = try backup.importBackup(text: text, mode: .replace)
            return restoredFileNames(in: directory)
        }
        let first = try importOnFreshPhone()
        let second = try importOnFreshPhone()
        XCTAssertEqual(first.count, 1)
        XCTAssertEqual(second.count, 1)
        XCTAssertNotEqual(first, second, "每次导入都要生成新的 UUID 文件名")
        XCTAssertFalse(first[0].contains("avatar-persona-1"))
    }

    /// 媒体导入提醒：坏数据与超限分开计数，导入照样成功。
    func testV3ImportWarnsSeparatelyForBrokenAndOversizedMedia() throws {
        var bundle = BackupBundle(
            personas: [BackupPersona(id: "persona-1", name: "林岸", avatarUri: "avatar-bad.jpg")]
        )
        bundle.media = [
            BackupMedia(id: "m1", kind: "PERSONA_AVATAR", personaId: "persona-1",
                        fileName: "avatar-bad.jpg", mime: "image/jpeg", bytes: 999,
                        data: Data([0xFF, 0xD8]).base64EncodedString()),
            BackupMedia(id: "m2", kind: "USER_AVATAR", fileName: "avatar-huge.jpg",
                        mime: "image/jpeg", bytes: BackupService.maxMediaFileBytes + 1,
                        data: Data([0xFF]).base64EncodedString())
        ]
        let summary = try service.importBundle(bundle, mode: .replace)
        XCTAssertTrue(
            summary.warnings.contains { $0.contains("没接回来（文件损坏或格式不支持）") },
            "\(summary.warnings)"
        )
        XCTAssertTrue(
            summary.warnings.contains { $0.contains("没有接回来（超出上限）") },
            "\(summary.warnings)"
        )
        // 库里该有的人格还在，只是头像没了。
        let persona = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        XCTAssertNil(persona.avatarUri)
        XCTAssertNil(settings.load().myAvatarUri, "坏掉的 USER_AVATAR 不该写进设置")
    }
}
