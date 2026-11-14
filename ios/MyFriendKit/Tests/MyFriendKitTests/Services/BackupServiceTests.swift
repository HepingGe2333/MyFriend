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
        service = BackupService(
            stores: db.stores, settings: settings, vectorIndex: vectorIndex,
            appVersion: "0.9.1-ios", now: { TestData.now }
        )
        try seed()
    }

    override func tearDownWithError() throws {
        service = nil
        vectorIndex = nil
        settings = nil
        db.cleanUp()
        db = nil
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
        XCTAssertEqual(bundle.formatVersion, 2)
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
        XCTAssertEqual(bundle.formatVersion, 2)
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
        // 稀疏文件：只改文件长度，不真的写 64 MB 数据。
        try handle.truncate(atOffset: UInt64(BackupService.maxImportBytes + 1))
        try handle.close()

        XCTAssertThrowsError(try service.readBackupText(at: huge)) { error in
            let importError = error as? BackupImportError
            XCTAssertEqual(importError?.message, BackupService.tooLargeMessage)
            XCTAssertTrue(importError?.message.contains("64 MB") ?? false)
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

    func testImportLimitBoundaryIs64MB() {
        XCTAssertEqual(BackupService.maxImportBytes, 64 * 1024 * 1024)
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

    func testExportedAvatarUriPolicy() {
        XCTAssertNil(BackupService.exportedAvatarUri(nil))
        XCTAssertNil(BackupService.exportedAvatarUri("   "))
        XCTAssertNil(BackupService.exportedAvatarUri("file:///var/mobile/a.jpg"))
        XCTAssertNil(BackupService.exportedAvatarUri("FILE:///var/mobile/a.jpg"))
        XCTAssertNil(BackupService.exportedAvatarUri("/var/mobile/a.jpg"))
        // 非文件 URI 不是「设备路径」，保留（将来若支持网络头像，两端口径一致）。
        XCTAssertEqual(BackupService.exportedAvatarUri("https://example.com/a.png"),
                       "https://example.com/a.png")
        XCTAssertEqual(BackupService.exportedAvatarUri("  https://example.com/a.png  "),
                       "https://example.com/a.png")
    }
}
