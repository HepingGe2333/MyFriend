import Foundation
import XCTest
@testable import YapKit

/// 备份 v7（0.15）：拉黑字段 / 语言 / 心理活动 / 会话备忘录 / 媒体哈希。
///
/// 这一层测的是**服务层的编排**（导出收哪些行、导入怎么写回库、旧备份会不会清掉新数据）；
/// 纯格式的键集合与跨端 fixture 在 `BackupCodecTests` / `BackupInteropTests` 里。
final class BackupV7Tests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var service: BackupService!
    private var base: URL!
    private let fileManager = FileManager.default
    private let personaId = "persona-1"
    private let sessionId = "session-1"

    private static let jpegBase64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA0JCgsKCA0LCgsODg0PEyAVExISEyccHhcgLikxMC4pLSwzOko+MzZGNywtQFdBRkxOUlNSMj5aYVpQYEpRUk//2wBDAQ4ODhMREyYVFSZPNS01T09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT0//wAARCAACAAIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwCeiiivMPZP/9k="
    private static let jpegBytes = Data(base64Encoded: jpegBase64)!

    override func setUpWithError() throws {
        try super.setUpWithError()
        db = try TempDatabase()
        settings = InMemorySettings(AppSettings(activePersonaId: personaId))
        base = fileManager.temporaryDirectory
            .appendingPathComponent("mf-backup-v7-\(UUID().uuidString)", isDirectory: true)
        try fileManager.createDirectory(at: base, withIntermediateDirectories: true)
        service = BackupService(
            stores: db.stores,
            settings: settings,
            appVersion: "0.15.0-ios",
            avatarFileExists: { [fileManager] path in fileManager.fileExists(atPath: path) },
            mediaDirectory: base.appendingPathComponent("attachments", isDirectory: true),
            stickerDirectory: base.appendingPathComponent("stickers", isDirectory: true),
            audioDirectory: base.appendingPathComponent("audio", isDirectory: true),
            videoDirectory: base.appendingPathComponent("video", isDirectory: true),
            now: { TestData.now }
        )
    }

    override func tearDownWithError() throws {
        service = nil
        settings = nil
        db.cleanUp()
        db = nil
        if let base { try? fileManager.removeItem(at: base) }
        base = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private var topicsJson: String? {
        SessionMemoryV2.encodeTopics([
            MemoryContract.TopicSegment(timeLabel: "2026-11-18 晚上", summary: "哈尔滨的行程与怕冷"),
            MemoryContract.TopicSegment(timeLabel: "2026-11-19 早上", summary: "旧书店下周盘点")
        ])
    }

    private func seedV7() throws {
        var persona = TestData.persona(id: personaId, name: "林岸")
        // 0.15 的四样：语言 + 拉黑三字段。
        persona.languageTag = "zh"
        persona.blockedUntil = TestData.now + 6 * 3_600_000
        persona.blockReason = "想安静一会儿"
        persona.blockProposalJson = #"{"proposalId":"p-1","hours":6}"#
        try db.stores.personas.upsert([persona])
        try db.stores.sessions.upsert([TestData.session(id: sessionId, personaId: personaId)])

        let normal = TestData.message(
            id: "m1", sessionId: sessionId, personaId: personaId,
            role: .user, content: "你在吗", createdAt: TestData.now - 2_000
        )
        var blocked = TestData.message(
            id: "m2", sessionId: sessionId, personaId: personaId,
            role: .user, content: "拉黑期间说的那句", createdAt: TestData.now - 1_000
        )
        blocked.blocked = true
        try db.stores.messages.upsert([normal, blocked])

        try db.stores.mentalActivities.upsert([
            MentalActivityEntity(
                id: "mental-1", sessionId: sessionId, personaId: personaId,
                at: TestData.now - 500, thought: "他刚才那句话让我有点想笑",
                moodLabel: "轻快", moodIntensity: 3,
                moodValence: 1, moodArousal: 3, moodDominance: 4
            ),
            MentalActivityEntity(
                id: "mental-2", sessionId: nil, personaId: personaId,
                at: TestData.now - 900, thought: "想问问他那本书看完了没"
            )
        ])
        try db.stores.sessionMemory.upsert([
            SessionMemoryEntity(
                sessionId: sessionId, personaId: personaId,
                shortSummary: "最近在聊哈尔滨", longSummary: "认识三年了",
                shortUserCount: 5, longUserCount: 18,
                shortUpdatedAt: TestData.now - 400, longUpdatedAt: TestData.now - 3_000,
                topicsJson: topicsJson, topicUserCount: 12, topicUpdatedAt: TestData.now - 300,
                updatedAt: TestData.now - 300
            )
        ])
    }

    private func freshService() throws -> (TempDatabase, BackupService) {
        let other = try TempDatabase()
        let service = BackupService(
            stores: other.stores,
            settings: InMemorySettings(AppSettings()),
            appVersion: "0.15.0-ios",
            avatarFileExists: { [fileManager] path in fileManager.fileExists(atPath: path) },
            mediaDirectory: base.appendingPathComponent("restored-attachments", isDirectory: true),
            stickerDirectory: base.appendingPathComponent("restored-stickers", isDirectory: true),
            audioDirectory: base.appendingPathComponent("restored-audio", isDirectory: true),
            videoDirectory: base.appendingPathComponent("restored-video", isDirectory: true),
            now: { TestData.now }
        )
        return (other, service)
    }

    // MARK: - 导出

    func testExportCarriesV7Fields() throws {
        try seedV7()
        let json = try service.export()
        let bundle = try XCTUnwrap(BackupCodec.decode(json).bundle)

        XCTAssertEqual(bundle.formatVersion, 7)
        let persona = try XCTUnwrap(bundle.personas.first)
        XCTAssertEqual(persona.languageTag, "zh")
        XCTAssertEqual(persona.blockedUntil, TestData.now + 6 * 3_600_000)
        XCTAssertEqual(persona.blockReason, "想安静一会儿")
        XCTAssertEqual(persona.blockProposalJson, #"{"proposalId":"p-1","hours":6}"#)

        XCTAssertEqual(bundle.messages.map(\.blocked), [false, true])
        XCTAssertEqual(bundle.mentalActivities.map(\.id).sorted(), ["mental-1", "mental-2"])
        let mental = try XCTUnwrap(bundle.mentalActivities.first { $0.id == "mental-1" })
        XCTAssertEqual(mental.thought, "他刚才那句话让我有点想笑")
        XCTAssertEqual(mental.moodValence, 1)
        XCTAssertEqual(mental.sessionId, sessionId)

        let memory = try XCTUnwrap(bundle.sessionMemory.first)
        XCTAssertEqual(memory.sessionId, sessionId)
        XCTAssertEqual(memory.shortUserMessages, 5)
        XCTAssertEqual(memory.longUserMessages, 18)
        XCTAssertEqual(memory.topicUserMessages, 12)
        XCTAssertEqual(memory.topics.map(\.summary), ["哈尔滨的行程与怕冷", "旧书店下周盘点"])
        XCTAssertNil(bundle.proactiveFailures, "客户端导出不写服务端诊断段")
    }

    func testExportMediaCarriesSha256OfRawBytes() throws {
        // 造一张真的头像文件 + media 引用（导出才会带上 media 段）。
        let avatarDirectory = base.appendingPathComponent("attachments", isDirectory: true)
        try fileManager.createDirectory(at: avatarDirectory, withIntermediateDirectories: true)
        let avatarName = "avatar-\(UUID().uuidString).jpg"
        try Self.jpegBytes.write(to: avatarDirectory.appendingPathComponent(avatarName))
        var persona = TestData.persona(id: personaId, name: "林岸")
        persona.avatarUri = avatarDirectory.appendingPathComponent(avatarName).absoluteString
        try db.stores.personas.upsert([persona])
        try db.stores.sessions.upsert([TestData.session(id: sessionId, personaId: personaId)])

        let bundle = try XCTUnwrap(BackupCodec.decode(try service.export()).bundle)
        let media = try XCTUnwrap(bundle.media.first)
        XCTAssertEqual(media.sha256, ImageContentHash.sha256Hex(Self.jpegBytes))
        XCTAssertEqual(media.bytes, Self.jpegBytes.count)
        // 解出来的字节与哈希一致（这就是导入端要校验的东西）。
        let bytes = try XCTUnwrap(BackupMediaTransfer.decode(media.data))
        XCTAssertEqual(media.sha256, ImageContentHash.sha256Hex(bytes))
    }

    // MARK: - 导入

    func testImportRestoresV7TablesAndBlockFields() throws {
        try seedV7()
        let json = try service.export()

        let (other, otherService) = try freshService()
        defer { other.cleanUp() }
        _ = try otherService.importBackup(text: json, mode: .replace)

        let persona = try XCTUnwrap(other.stores.personas.byId(personaId))
        XCTAssertEqual(persona.languageTag, "zh")
        XCTAssertEqual(persona.blockedUntil, TestData.now + 6 * 3_600_000)
        XCTAssertEqual(persona.blockReason, "想安静一会儿")
        XCTAssertNotNil(persona.blockProposalJson)

        let messages = try other.stores.messages.bySession(sessionId)
        XCTAssertEqual(messages.first { $0.id == "m2" }?.blocked, true)
        XCTAssertEqual(messages.first { $0.id == "m1" }?.blocked, false)

        let activities = try other.stores.mentalActivities.recent(personaId: personaId, limit: 50)
        XCTAssertEqual(activities.map(\.id).sorted(), ["mental-1", "mental-2"])
        XCTAssertEqual(activities.first { $0.id == "mental-1" }?.thought, "他刚才那句话让我有点想笑")

        let memory = try XCTUnwrap(other.stores.sessionMemory.bySession(sessionId))
        XCTAssertEqual(memory.shortUserCount, 5)
        XCTAssertEqual(memory.longUserCount, 18)
        XCTAssertEqual(memory.topicUserCount, 12)
        XCTAssertEqual(memory.shortSummary, "最近在聊哈尔滨")
        XCTAssertEqual(memory.topics.map(\.summary), ["哈尔滨的行程与怕冷", "旧书店下周盘点"])
    }

    func testImportingSameBackupTwiceIsIdempotent() throws {
        try seedV7()
        let json = try service.export()
        let (other, otherService) = try freshService()
        defer { other.cleanUp() }

        _ = try otherService.importBackup(text: json, mode: .merge)
        _ = try otherService.importBackup(text: json, mode: .merge)

        XCTAssertEqual((try other.stores.mentalActivities.recent(personaId: personaId, limit: 50)).count, 2)
        XCTAssertEqual((try other.stores.sessionMemory.bySession(sessionId))?.topics.count, 2)
        XCTAssertEqual((try other.stores.messages.bySession(sessionId)).count, 2)
    }

    /// v6 备份（没有 v7 段）合并导入后，本机已有的 0.15 数据不能被清掉。
    func testV6BackupMergeKeepsExistingV7Data() throws {
        try seedV7()
        let (other, otherService) = try freshService()
        defer { other.cleanUp() }
        _ = try otherService.importBackup(text: try service.export(), mode: .replace)

        // 一份「v6 形状」的备份：同一个人格与会话，但没有 0.15 的段与字段。
        var v6 = BackupSamples.iosBundle()
        v6.formatVersion = 6
        v6.mentalActivities = []
        v6.sessionMemory = []
        v6.personas = v6.personas.map { persona in
            var copy = persona
            copy.blockedUntil = nil
            copy.blockReason = nil
            copy.blockProposalJson = nil
            copy.languageTag = nil
            return copy
        }
        v6.messages = v6.messages.map { message in
            var copy = message
            copy.blocked = false
            return copy
        }
        // 用同一个人格 id / 会话 id，才能「合并到同一行」。
        v6.personas = v6.personas.map { persona in
            var copy = persona
            if copy.id == BackupSamples.personaId { copy.id = personaId }
            return copy
        }
        v6.sessions = v6.sessions.map { session in
            var copy = session
            if copy.personaId == BackupSamples.personaId { copy.personaId = personaId }
            if copy.id == BackupSamples.sessionId { copy.id = sessionId }
            return copy
        }
        v6.messages = v6.messages.map { message in
            var copy = message
            if copy.sessionId == BackupSamples.sessionId { copy.sessionId = sessionId }
            if copy.personaId == BackupSamples.personaId { copy.personaId = personaId }
            return copy
        }
        _ = try otherService.importBundle(v6, mode: .merge)

        XCTAssertEqual((try other.stores.mentalActivities.recent(personaId: personaId, limit: 50)).count, 2,
                       "旧备份不该清掉心理活动")
        XCTAssertNotNil((try other.stores.sessionMemory.bySession(sessionId)), "旧备份不该清掉会话备忘录")
    }

    // MARK: - 服务端 extensions

    func testServerExtensionsImportLandsInLocalTables() throws {
        guard let fixtureDirectory = RepoLayout.backupFixtureDirectory() else {
            throw XCTSkip("找不到 fixtures/backup")
        }
        let url = fixtureDirectory.appendingPathComponent("server-export-v7-extensions.json")
        guard fileManager.fileExists(atPath: url.path) else {
            throw XCTSkip("server-export-v7-extensions.json 不在仓库里")
        }
        let raw = try String(contentsOf: url, encoding: .utf8)
        let bundle = try XCTUnwrap(BackupCodec.decode(raw).bundle)
        XCTAssertFalse(bundle.mentalActivities.isEmpty)

        _ = try service.importBundle(bundle, mode: .replace)

        XCTAssertFalse((try db.stores.mentalActivities.all()).isEmpty, "extensions.mentalActivities 要落表")
        XCTAssertFalse((try db.stores.sessionMemory.all()).isEmpty, "extensions.sessionMemory 要落表")
        let failures = ProactiveFailureStore(database: db.stores.database).all()
        XCTAssertEqual(failures.count, 1, "extensions.proactiveFailures 要落本机诊断表")
        XCTAssertEqual(failures.first?.reason, "generation_failed")

        // 诊断段不会被再导出成客户端备份的一部分。
        let reExported = try service.export()
        XCTAssertFalse(reExported.contains("proactiveFailures"))
        XCTAssertFalse(reExported.contains("\"extensions\""))
    }

    // MARK: - 媒体哈希校验

    func testTamperedMediaShaIsSkippedWithWarning() throws {
        // 先造一张真头像，导出一份带 media 的备份。
        let avatarDirectory = base.appendingPathComponent("attachments", isDirectory: true)
        try fileManager.createDirectory(at: avatarDirectory, withIntermediateDirectories: true)
        let avatarName = "avatar-\(UUID().uuidString).jpg"
        try Self.jpegBytes.write(to: avatarDirectory.appendingPathComponent(avatarName))
        var persona = TestData.persona(id: personaId, name: "林岸")
        persona.avatarUri = avatarDirectory.appendingPathComponent(avatarName).absoluteString
        try db.stores.personas.upsert([persona])
        try db.stores.sessions.upsert([TestData.session(id: sessionId, personaId: personaId)])

        var bundle = try XCTUnwrap(BackupCodec.decode(try service.export()).bundle)
        XCTAssertEqual(bundle.media.count, 1, "backup.media=\(bundle.media)")
        // 篡改声明哈希：字节与 sha256 对不上 → 这一条必须被跳过。
        bundle.media[0].sha256 = ImageContentHash.sha256Hex(Data("tampered".utf8))

        let (other, otherService) = try freshService()
        defer { other.cleanUp() }
        let summary = try otherService.importBundle(bundle, mode: .replace)

        XCTAssertTrue(
            summary.warnings.contains { $0.contains("没接回来") },
            "应当提示有附件没接回来：\(summary.warnings)"
        )
        XCTAssertNil((try other.stores.personas.byId(personaId))?.avatarUri,
                     "校验失败的媒体不能落库引用")
        let restored = base.appendingPathComponent("restored-attachments", isDirectory: true)
        XCTAssertTrue(
            ((try? fileManager.contentsOfDirectory(atPath: restored.path)) ?? []).isEmpty,
            "校验失败的媒体不能落盘"
        )
    }
}
