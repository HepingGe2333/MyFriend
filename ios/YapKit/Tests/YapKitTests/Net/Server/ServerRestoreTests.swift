import Foundation
import XCTest
@testable import YapKit

/// 云端 → 本地：全量下载 + 条数校验（0.13「切回本地」的前置条件）。
///
/// 真 SQLite + 假服务端：要验的是「拉全了没有」和「校验失不失败」，
/// 而不是某个请求的 body（那是 bootstrap 的测试）。
final class ServerRestoreTests: XCTestCase {

    private var temp: TempDatabase!
    private var server: BootstrapFakeServer!
    private var settings: SettingsStore!
    private var defaults: UserDefaults!
    private var suiteName = ""

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerRouteStub.reset()
        temp = try TempDatabase()
        server = BootstrapFakeServer()
        server.registerRoutes()
        suiteName = "server-restore-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        settings = SettingsStore(defaults: defaults)
    }

    override func tearDownWithError() throws {
        ServerRouteStub.reset()
        temp?.cleanUp()
        temp = nil
        if !suiteName.isEmpty { defaults?.removePersistentDomain(forName: suiteName) }
        defaults = nil
        settings = nil
        server = nil
        try super.tearDownWithError()
    }

    private var baseURL: URL { URL(string: "https://server.example.com")! }

    private func makeService(
        retry: ServerSyncService.RetryPolicy = .init(maxAttempts: 1, baseDelaySeconds: 0)
    ) -> ServerBootstrapService {
        ServerBootstrapService(
            client: ServerAPIClient(
                config: ServerConfig(baseURL: baseURL, token: "secret-token"),
                session: ServerRouteStub.makeSession()
            ),
            stores: temp.stores,
            settings: settings,
            memories: MemoryRepository(stores: temp.stores, vectorIndex: MemoryVectorIndex(stores: temp.stores)),
            retry: retry,
            now: { 1_800_000_000_000 },
            log: { _ in },
            sleeper: { _ in }
        )
    }

    // MARK: - 全量拉取 + 校验通过

    func testDownloadAllPullsEverythingAndVerifiesCounts() async throws {
        server.seedPersona(id: "p1", name: "林深", description: "慢热", isDefault: true)
        server.seedSession(id: "s1", personaId: "p1", title: "夜里散步", unread: 2)
        server.seedMessages(sessionId: "s1", count: 450, startAt: 1_000, readCount: 10)
        server.seedMessage(
            sessionId: "s1",
            id: "srv-with-media",
            content: "看这张",
            createdAt: 2_000,
            attachments: ["/v1/media/m-1"]
        )
        server.presets = [["id": "pre-1", "name": "夜班记者", "createdAt": 1, "updatedAt": 1]]
        server.memories["p1"] = [[
            "id": "mem-1", "personaId": "p1", "content": "记住的事",
            "importance": 60, "tags": [], "createdAt": 1, "updatedAt": 1
        ]]
        server.setSettings(["themeMode": "DARK", "accentColor": 111])

        let outcome = await makeService().downloadAll()
        guard case .completed(let report) = outcome else {
            return XCTFail("期望 completed，实际 \(outcome)")
        }
        XCTAssertTrue(report.verification.passed, "校验没通过：\(report.verification.describe)")
        XCTAssertEqual(report.verification.sessionsChecked, 1)
        XCTAssertEqual(report.verification.messagesOnServer, 451)
        XCTAssertEqual(report.verification.messagesOnLocal, 451)
        XCTAssertEqual(report.verification.missingMessages, 0)
        XCTAssertEqual(report.verification.mediaRefs, 1)
        XCTAssertEqual(report.verification.missingMediaRefs, 0)
        XCTAssertEqual(report.personas, 1)
        XCTAssertEqual(report.sessions, 1)
        XCTAssertTrue(report.settingsPulled)

        // 本地真的有了全部内容（不只最新一块）。
        let messages = try temp.stores.messages.bySession("s1")
        XCTAssertEqual(messages.count, 451)
        XCTAssertEqual(messages.first?.id, "srv-s1-0")
        XCTAssertEqual(messages.last?.content, "看这张")
        let mediaMessage = try XCTUnwrap(messages.first { $0.id == "srv-with-media" })
        XCTAssertEqual(
            AttachmentsCodec.decode(mediaMessage.attachmentsJson).first?.uri,
            "/v1/media/m-1",
            "媒体引用要原样落进本地（渲染时按 id 取）"
        )
        XCTAssertEqual(try temp.stores.presets.count(), 1)
        XCTAssertEqual(try temp.stores.memories.byPersona("p1").count, 1)
        XCTAssertEqual(settings.load().themeMode, .dark)
        XCTAssertEqual(settings.load().accentColor, 111)
        XCTAssertEqual(try temp.stores.sessions.byId("s1")?.serverSessionId, "s1")

        // 全量下载不该冒充「首次同步已完成」（下次再开服务端模式仍会按需 bootstrap）。
        let done = await makeService().doneRecord()
        XCTAssertNil(done)
        // 游标前进了：之后走增量，而不是每次都从头拉。
        let state = ServerSyncStateStore(database: temp.stores.database)
        XCTAssertEqual(state.cursor(), server.cursor)
    }

    /// 本机独有的消息允许保留（合并语义），不算校验失败。
    func testLocalOnlyMessagesAreAllowedAndCountedAsExtras() async throws {
        try temp.stores.personas.upsert(PersonaEntity(id: "p1", name: "林深", createdAt: 1, updatedAt: 1))
        try temp.stores.sessions.upsert(ChatSessionEntity(
            id: "local-session", personaId: "p1", title: "本机这段", createdAt: 1, updatedAt: 1
        ))
        for index in 0..<2 {
            try temp.stores.messages.upsert(MessageEntity(
                id: "local-only-\(index)",
                sessionId: "local-session",
                personaId: "p1",
                role: .user,
                content: "本机独有的第 \(index + 1) 句",
                createdAt: 5_000 + Int64(index)
            ))
        }
        server.seedPersona(id: "p1", name: "林深")
        server.seedSession(id: "s1", personaId: "p1", title: "服务端这段")
        server.seedMessages(sessionId: "s1", count: 3, startAt: 1_000)

        let outcome = await makeService().downloadAll()
        guard case .completed(let report) = outcome else {
            return XCTFail("本机独有消息不该判失败，实际 \(outcome)")
        }
        XCTAssertTrue(report.verification.passed)
        XCTAssertEqual(report.verification.extraLocalMessages, 2)
        XCTAssertEqual(report.verification.missingMessages, 0)
        // 服务端会话被认领到本机那一段：两段历史合在一起。
        XCTAssertEqual(try temp.stores.sessions.byId("local-session")?.serverSessionId, "s1")
        XCTAssertEqual(try temp.stores.messages.bySession("local-session").count, 5)
    }

    // MARK: - 校验失败 → 不通过（调用方据此保持云端模式）

    func testVerificationFailureWhenLocalHasFewerMessagesThanServer() async throws {
        server.seedPersona(id: "p1", name: "林深")
        server.seedSession(id: "s1", personaId: "p1", title: "夜里散步")
        server.seedMessages(sessionId: "s1", count: 3, startAt: 1_000)
        // 服务端谎报条数（模拟「块没翻全」）：本地只有 3 条，它说 5 条。
        server.sessions[0].messageCountOverride = 5

        let outcome = await makeService().downloadAll()
        guard case .failed(let message, let verification) = outcome else {
            return XCTFail("应当判校验失败，实际 \(outcome)")
        }
        XCTAssertFalse(message.isEmpty)
        let checked = try XCTUnwrap(verification)
        XCTAssertFalse(checked.passed)
        XCTAssertEqual(checked.missingMessages, 2)
        XCTAssertEqual(checked.messagesOnServer, 5)
        XCTAssertEqual(checked.messagesOnLocal, 3)
        XCTAssertTrue(checked.describe.contains("没通过校验"))
        // 已经拉到的数据保留在本地（下次再试更快），但这次不算成功。
        XCTAssertEqual(try temp.stores.messages.bySession("s1").count, 3)
    }

    func testStateRequestFailureReturnsFailedWithoutVerification() async throws {
        server.failStateTimes = 5
        let outcome = await makeService().downloadAll()
        guard case .failed(let message, let verification) = outcome else {
            return XCTFail("期望 failed，实际 \(outcome)")
        }
        XCTAssertFalse(message.isEmpty)
        XCTAssertNil(verification)
        XCTAssertEqual(try temp.stores.personas.count(), 0)
    }

    func testDownloadAllIsSkippedWhenServiceIsNotConfigured() async {
        let service = ServerBootstrapService(
            client: ServerAPIClient(
                config: ServerConfig(baseURL: nil, token: nil, enabled: false),
                session: ServerRouteStub.makeSession()
            ),
            stores: temp.stores,
            retry: .init(maxAttempts: 1, baseDelaySeconds: 0),
            now: { 1 },
            log: { _ in },
            sleeper: { _ in }
        )
        let outcome = await service.downloadAll()
        guard case .skipped = outcome else {
            return XCTFail("没有配置服务端时应当 skipped，实际 \(outcome)")
        }
    }
}
