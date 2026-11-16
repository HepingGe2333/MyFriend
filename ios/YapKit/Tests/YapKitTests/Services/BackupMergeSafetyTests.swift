import Foundation
import XCTest
@testable import YapKit

/// 备份导入的安全边界（0.10.2 安全审计 P1-0）与合并导入的数据完整性（P1-9 / P1-5）。
///
/// P1-0 的利用链很短：任何一份导出文件里都带着 API 配置的 id（UUID），
/// 攻击者拼一份「同 id、baseUrl 指向自己主机」的备份，用户一「合并导入」，
/// 本机 Key 与全部聊天就会发到那台主机上。修法是**地址不从备份迁移**、
/// 明文开关也不因备份被打开。P1-9 则是同一段合并逻辑里把本机会话整行覆盖、
/// 把 `attachmentsJson` 写成 nil，导致图片引用被抹掉、首页预览倒退。
final class BackupMergeSafetyTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var service: BackupService!
    private var apiKeys: ApiKeyStore!

    private let configId = "cfg-1"
    private let personaId = "persona-1"
    private let sessionId = "session-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        db = try TempDatabase()
        settings = InMemorySettings(AppSettings(activePersonaId: personaId))
        service = BackupService(
            stores: db.stores, settings: settings,
            vectorIndex: MemoryVectorIndex(stores: db.stores),
            appVersion: "0.10.2-ios", now: { self.now }
        )
        apiKeys = ApiKeyStore(backend: InMemoryKeychain())
        try db.stores.personas.upsert(TestData.persona(id: personaId))
    }

    override func tearDownWithError() throws {
        service = nil
        apiKeys = nil
        settings = nil
        db.cleanUp()
        db = nil
        try super.tearDownWithError()
    }

    // MARK: - 小工具

    private func localConfig(baseUrl: String = "https://api.deepseek.com") throws -> ApiConfigEntity {
        let config = ApiConfigEntity(
            id: configId, name: "DeepSeek 官方", provider: .openai,
            baseUrl: baseUrl, model: "deepseek-chat", allowInsecureHttp: false,
            createdAt: now - 1000, updatedAt: now - 1000
        )
        try db.stores.apiConfigs.upsert(config)
        try apiKeys.setAPIKey("sk-real-key", for: configId)
        return config
    }

    private func importedConfig(
        id: String? = nil,
        baseUrl: String,
        allowInsecureHttp: Bool = true
    ) -> BackupApiConfig {
        BackupApiConfig(
            id: id ?? configId, name: "DeepSeek 官方", provider: "OPENAI",
            baseUrl: baseUrl, model: "deepseek-chat",
            allowInsecureHttp: allowInsecureHttp,
            webToolsBaseUrl: baseUrl,
            createdAt: now - 1000, updatedAt: now
        )
    }

    private func importSingleConfig(_ config: BackupApiConfig) throws -> ImportSummary {
        try service.importBundle(
            BackupBundle(appVersion: "attacker", exportedAt: now, apiConfigs: [config]),
            mode: .merge
        )
    }

    // MARK: - P1-0：地址与明文开关不从备份迁移

    func testMergeImportDoesNotHijackExistingBaseUrl() throws {
        _ = try localConfig()
        let summary = try importSingleConfig(
            importedConfig(baseUrl: "https://evil.example.net", allowInsecureHttp: true)
        )

        let config = try XCTUnwrap(try db.stores.apiConfigs.byId(configId))
        XCTAssertEqual(config.baseUrl, "https://api.deepseek.com", "备份不能改本机的 API 地址")
        XCTAssertFalse(config.allowInsecureHttp, "备份不能把「允许不安全 HTTP」打开")
        XCTAssertEqual(apiKeys.apiKey(for: configId), "sk-real-key", "Key 仍在本机（备份里从来不装密钥）")
        XCTAssertTrue(
            summary.warnings.contains { $0.contains("地址与本机不同") },
            "地址差异必须写进 warnings：\(summary.warnings)"
        )
    }

    func testMergeImportAcceptsSameBaseUrlWithDifferentSpelling() throws {
        _ = try localConfig(baseUrl: "https://api.deepseek.com/v1")
        let summary = try importSingleConfig(
            importedConfig(baseUrl: "https://api.deepseek.com/v1/", allowInsecureHttp: false)
        )
        let config = try XCTUnwrap(try db.stores.apiConfigs.byId(configId))
        XCTAssertEqual(config.baseUrl, "https://api.deepseek.com/v1")
        XCTAssertFalse(
            summary.warnings.contains { $0.contains("地址与本机不同") },
            "只是尾部斜杠不同不算「改了地址」：\(summary.warnings)"
        )
    }

    func testNewConfigIdIsImportedButInsecureHttpIsForcedOff() throws {
        _ = try localConfig()
        let summary = try importSingleConfig(
            importedConfig(id: "cfg-new", baseUrl: "https://api.example.com", allowInsecureHttp: true)
        )
        let created = try XCTUnwrap(try db.stores.apiConfigs.byId("cfg-new"))
        XCTAssertEqual(created.baseUrl, "https://api.example.com", "新 id 按新配置导入")
        XCTAssertFalse(created.allowInsecureHttp, "新配置也不该被备份打开明文")
        XCTAssertNil(created.apiKeyCipher)
        XCTAssertNil(apiKeys.apiKey(for: "cfg-new"), "新配置的 Key 仍然为空，等用户自己填")
        XCTAssertTrue(summary.warnings.contains { $0.contains("允许不安全 HTTP") }, "\(summary.warnings)")
    }

    func testLocalInsecureHttpConsentSurvivesMerge() throws {
        try db.stores.apiConfigs.upsert(ApiConfigEntity(
            id: configId, name: "本机自己开的", provider: .openai,
            baseUrl: "https://api.deepseek.com", model: "deepseek-chat",
            allowInsecureHttp: true, createdAt: now, updatedAt: now
        ))
        _ = try importSingleConfig(importedConfig(baseUrl: "https://api.deepseek.com", allowInsecureHttp: false))
        let config = try XCTUnwrap(try db.stores.apiConfigs.byId(configId))
        XCTAssertTrue(config.allowInsecureHttp, "本机上用户自己开过的同意不能被备份关掉")
    }

    // MARK: - P1-9：合并导入保住本机会话与附件

    private func seedLocalSessionWithAttachment() throws -> (session: ChatSessionEntity, message: MessageEntity) {
        var session = TestData.session(id: sessionId, personaId: personaId)
        session.lastMessagePreview = "本机最新的一句"
        session.lastMessageAt = now
        session.unreadCount = 2
        session.lastMemoryExtractedCount = 12
        session.historySummary = "本机压得更远的一段"
        session.historySummarizedUpTo = now - 10_000
        session.historySummaryAt = now
        try db.stores.sessions.upsert(session)
        let message = MessageEntity(
            id: "msg-1", sessionId: sessionId, personaId: personaId, role: .user,
            content: "本机这条带着图", createdAt: now - 5_000, updatedAt: now - 5_000,
            status: .complete,
            attachmentsJson: "[{\"uri\":\"file:///tmp/mf-local-image.jpg\",\"mime\":\"image/jpeg\"}]"
        )
        try db.stores.messages.upsert(message)
        return (session, message)
    }

    func testMergeImportKeepsLocalAttachmentsAndPreview() throws {
        let local = try seedLocalSessionWithAttachment()
        // 备份是「导出那一刻」的快照：预览更旧、没有摘要、消息没有 attachmentsJson。
        let backup = BackupBundle(
            appVersion: "old", exportedAt: now - 86_400_000,
            personas: [BackupService.toBackupPersona(TestData.persona(id: personaId))],
            sessions: [BackupSession(
                id: sessionId, personaId: personaId, model: "deepseek-chat",
                title: "旧标题", lastMessagePreview: "备份里更旧的一句",
                lastMessageAt: now - 86_400_000, unreadCount: 9,
                createdAt: now - 100_000, updatedAt: now - 86_400_000,
                lastMemoryExtractedCount: 3
            )],
            messages: [BackupMessage(
                id: "msg-1", sessionId: sessionId, personaId: personaId, role: "USER",
                content: "本机这条带着图", createdAt: now - 5_000, updatedAt: now - 5_000,
                status: "COMPLETE", attachmentsJson: nil
            )]
        )

        let summary = try service.importBundle(backup, mode: .merge)
        XCTAssertTrue(
            !summary.warnings.contains { $0.contains("图片没有跟着备份走") },
            "本机已有带图消息时不该说「图片没跟过来」：\(summary.warnings)"
        )

        let session = try XCTUnwrap(try db.stores.sessions.byId(sessionId))
        XCTAssertEqual(session.lastMessagePreview, "本机最新的一句", "预览不能倒退")
        XCTAssertEqual(session.lastMessageAt, now)
        XCTAssertEqual(session.unreadCount, 2, "未读是本机的阅读状态，备份不能重置")
        XCTAssertEqual(session.historySummary, "本机压得更远的一段")
        XCTAssertEqual(session.historySummarizedUpTo, now - 10_000)
        XCTAssertEqual(session.lastMemoryExtractedCount, local.session.lastMemoryExtractedCount)

        let message = try XCTUnwrap(try db.stores.messages.byId("msg-1"))
        XCTAssertEqual(
            message.attachmentsJson,
            "[{\"uri\":\"file:///tmp/mf-local-image.jpg\",\"mime\":\"image/jpeg\"}]",
            "合并导入不能把本机的 attachmentsJson 写成 nil（那会把图片引用抹掉、文件变孤儿）"
        )
    }

    func testMergeImportFillsMissingLocalFieldsFromBackup() throws {
        // 本机会话没有标题、没有摘要、没有配置绑定：这些应该由备份补上。
        try db.stores.sessions.upsert(ChatSessionEntity(
            id: sessionId, personaId: personaId, model: "", title: "",
            createdAt: now - 200_000, updatedAt: now - 200_000
        ))
        let backup = BackupBundle(
            appVersion: "old", exportedAt: now,
            personas: [BackupService.toBackupPersona(TestData.persona(id: personaId))],
            sessions: [BackupSession(
                id: sessionId, personaId: personaId, apiConfigId: configId, model: "deepseek-chat",
                title: "备份里的标题", lastMessagePreview: "备份里的最后一句",
                lastMessageAt: now, createdAt: now - 200_000, updatedAt: now,
                lastMemoryExtractedCount: 4, summary: "备份里的摘要", summarizedUpTo: now - 1_000,
                summaryAt: now
            )]
        )
        _ = try service.importBundle(backup, mode: .merge)

        let session = try XCTUnwrap(try db.stores.sessions.byId(sessionId))
        XCTAssertEqual(session.title, "备份里的标题")
        XCTAssertEqual(session.model, "deepseek-chat")
        XCTAssertEqual(session.apiConfigId, configId)
        XCTAssertEqual(session.historySummary, "备份里的摘要")
        XCTAssertEqual(session.lastMemoryExtractedCount, 4)
        XCTAssertEqual(session.lastMessagePreview, "备份里的最后一句")
    }

    // MARK: - P1-5：备份 JSON 里不再有 6 个 sync 字段

    func testExportedSessionHasNoSyncFields() throws {
        try seedLocalSessionWithAttachment()
        let json = try service.export()
        for key in [
            "\"syncState\"", "\"syncedPersonaVersion\"", "\"syncedMemoriesHash\"",
            "\"serverSessionId\"", "\"lastSyncError\"", "\"lastSyncAt\""
        ] {
            XCTAssertFalse(json.contains(key), "备份里不该出现 \\(key)（Android 的 BackupSession 没有它）")
        }
        XCTAssertTrue(json.contains("\"lastPersonaVersion\""))
        XCTAssertTrue(json.contains("\"lastMemoryExtractedCount\""))
    }

    func testOldBackupWithSyncFieldsStillDecodes() throws {
        let old = """
        {"format":"myfriend.backup","formatVersion":2,"sessions":[
          {"id":"s1","personaId":"p1","model":"m","title":"t","createdAt":1,"updatedAt":2,
           "lastMemoryExtractedCount":3,"syncState":"SYNCED","syncedPersonaVersion":2,
           "syncedMemoriesHash":"h","serverSessionId":"srv","lastSyncError":"boom","lastSyncAt":9}
        ]}
        """
        guard case .success(let bundle, _) = BackupCodec.decode(old) else {
            return XCTFail("带老 sync 字段的备份必须还能读")
        }
        let session = try XCTUnwrap(bundle.sessions.first)
        XCTAssertEqual(session.id, "s1")
        XCTAssertEqual(session.lastMemoryExtractedCount, 3)
        // 再编码出去：这些字段按未知字段被丢掉，恢复成与 Android 一致的结构。
        let reEncoded = try BackupCodec.encode(bundle)
        XCTAssertFalse(reEncoded.contains("syncState"))
        XCTAssertFalse(reEncoded.contains("serverSessionId"))
    }
}
