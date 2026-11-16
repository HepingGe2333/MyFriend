import Foundation
import XCTest
@testable import YapKit

/// 服务端状态同步（M4 基础）：合并 / 游标 / 幂等 / 覆盖边界 / 重试 / 首次补块。
///
/// 全部用真 SQLite（临时文件）+ 假 HTTP（`ServerURLProtocolStub`）：
/// 「游标与数据同一个事务」这种事只有真库才测得出来。
final class ServerSyncServiceTests: XCTestCase {

    private var temp: TempDatabase!

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerURLProtocolStub.reset()
        temp = try TempDatabase()
    }

    override func tearDownWithError() throws {
        ServerURLProtocolStub.reset()
        temp?.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    /// 一份完整的快照：一个人格、一个会话、两条消息、心情、在场、游标 12345。
    private static func snapshotJSON(
        cursor: Int64 = 12_345,
        messageIds: [String] = ["m1", "m2"],
        hasMoreMessages: Bool = false,
        mood: String = #"{"personaId":"p1","label":"平静","intensity":3,"updatedAt":450}"#,
        presence: String = #"{"personaId":"p1","kind":"IDLE","detail":"在听","updatedAt":460}"#
    ) -> String {
        let messages = messageIds.enumerated().map { index, id in
            """
            {
              "id": "\(id)",
              "sessionId": "s1",
              "blockIndex": \(index + 2),
              "role": "\(index == messageIds.count - 1 ? "ASSISTANT" : "USER")",
              "content": "第\(index + 1)句",
              "createdAt": \(1000 + index),
              "readAt": null,
              "attachments": [],
              "source": "CHAT",
              "clientMessageId": null,
              "replyTo": null,
              "promptTokens": null,
              "completionTokens": null
            }
            """
        }.joined(separator: ",")
        return """
        {
          "serverTime": 1700000000000,
          "personas": [
            { "id": "p1", "name": "林深", "nameEn": "Lin Shen", "description": "慢热",
              "avatarUrl": "/v1/media/a1", "isDefault": true, "createdAt": 100, "updatedAt": 200 }
          ],
          "sessions": [
            { "id": "s1", "personaId": "p1", "title": "夜里散步", "createdAt": 300,
              "updatedAt": 400, "lastMessageAt": 1001, "lastBlockIndex": 3,
              "unread": 1, "messageCount": 2 }
          ],
          "messages": [\(messages)],
          "mood": \(mood),
          "presence": \(presence),
          "serverCursor": \(cursor),
          "hasMoreMessages": \(hasMoreMessages)
        }
        """
    }

    private func makeService(
        retry: ServerSyncService.RetryPolicy = .init(maxAttempts: 3, baseDelaySeconds: 0.5),
        delays: @escaping @Sendable (Double) -> Void = { _ in },
        token: String? = "secret-token"
    ) -> ServerSyncService {
        let client = ServerTestSupport.client(token: token)
        return ServerSyncService(
            client: client,
            stores: temp.stores,
            retry: retry,
            now: { 1_800_000_000_000 },
            log: { _ in },
            sleeper: { seconds in delays(seconds) }
        )
    }

    // MARK: - 首次同步

    func testFirstSyncWritesPersonasSessionsMessagesAndCursor() async throws {
        ServerURLProtocolStub.enqueue(.json(Self.snapshotJSON()))
        let service = makeService()

        let outcome = await service.syncNow(fillLatestBlocks: false)

        guard case .synced(let report) = outcome else {
            return XCTFail("期望 synced，实际 \(outcome)")
        }
        XCTAssertEqual(report.cursor, 12_345)
        XCTAssertEqual(report.personas, 1)
        XCTAssertEqual(report.sessions, 1)
        XCTAssertEqual(report.messages, 2)
        XCTAssertTrue(report.firstSync)
        XCTAssertTrue(report.moodUpdated)
        XCTAssertTrue(report.presenceUpdated)

        XCTAssertEqual(try temp.stores.personas.count(), 1)
        XCTAssertEqual(try temp.stores.sessions.count(), 1)
        XCTAssertEqual(try temp.stores.messages.count(sessionId: "s1"), 2)

        let persona = try XCTUnwrap(temp.stores.personas.byId("p1"))
        XCTAssertEqual(persona.name, "林深")
        XCTAssertEqual(persona.description, "慢热")
        XCTAssertTrue(persona.isDefault)
        // 心情写回人格的心情列（界面已经在读它，不需要新表）。
        XCTAssertEqual(persona.mood, "平静")
        XCTAssertEqual(persona.moodIntensity, 3)

        let session = try XCTUnwrap(temp.stores.sessions.byId("s1"))
        XCTAssertEqual(session.personaId, "p1")
        XCTAssertEqual(session.title, "夜里散步")
        XCTAssertEqual(session.unreadCount, 1)
        XCTAssertEqual(session.serverSessionId, "s1")
        // 预览取自快照里最新那条消息（与本地 ChatService 同一口径）。
        XCTAssertEqual(session.lastMessagePreview, "第2句")

        let messages = try temp.stores.messages.bySession("s1")
        XCTAssertEqual(messages.map(\.id), ["m1", "m2"])
        XCTAssertEqual(messages.last?.role, .assistant)
        XCTAssertEqual(messages.first?.role, .user)

        // 游标落库（下次同步要用它）。
        let cursor = await service.cursor()
        XCTAssertEqual(cursor, 12_345)
        let lastSyncAt = await service.lastSyncAt()
        XCTAssertEqual(lastSyncAt, 1_800_000_000_000)

        // 请求带的是「首次同步」的 since=0。
        let query = ServerTestSupport.queryItems(ServerURLProtocolStub.recordedRequests[0])
        XCTAssertEqual(query["since"], "0")
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests[0].url?.path, "/v1/state")
    }

    /// 服务端的块号**不落进本地**：本地块由 `MessageBlockStore` 自己维护，
    /// 塞服务端块号会让 `messages.blockIndex` 与 `message_blocks` 不自洽。
    func testServerBlockIndexIsNotWrittenIntoLocalMessages() async throws {
        ServerURLProtocolStub.enqueue(.json(Self.snapshotJSON()))
        let service = makeService()
        _ = await service.syncNow(fillLatestBlocks: false)

        let messages = try temp.stores.messages.bySession("s1")
        XCTAssertEqual(messages.map(\.blockIndex), [0, 0], "两条消息都该落在本地第 0 块")
        XCTAssertEqual(try temp.stores.messageBlocks.blockCount(sessionId: "s1"), 1)
        XCTAssertTrue(try temp.stores.messageBlocks.inconsistencies().isEmpty)
    }

    /// 首次同步 + 服务端说消息被截断 → 逐个会话补拉最新块。
    func testFirstSyncFillsLatestBlockWhenServerSaysTruncated() async throws {
        ServerURLProtocolStub.enqueue(
            .json(Self.snapshotJSON(messageIds: [], hasMoreMessages: true)),
            .json(ServerTestSupport.messagePageJSON)
        )
        let service = makeService()

        let outcome = await service.syncNow()

        guard case .synced(let report) = outcome else {
            return XCTFail("期望 synced，实际 \(outcome)")
        }
        XCTAssertEqual(report.fetchedBlocks, 1)
        // 补块把 m1（ASSISTANT「在的」）拉进来了。
        let messages = try temp.stores.messages.bySession("s1")
        XCTAssertEqual(messages.map(\.id), ["m1"])
        XCTAssertEqual(messages.first?.content, "在的")
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests.count, 2)
        XCTAssertEqual(
            ServerURLProtocolStub.recordedRequests[1].url?.path,
            "/v1/sessions/s1/messages"
        )
    }

    // MARK: - 增量与游标

    func testSecondSyncSendsCursorAndMergesOnlyNewMessages() async throws {
        ServerURLProtocolStub.enqueue(.json(Self.snapshotJSON()))
        let service = makeService()
        _ = await service.syncNow(fillLatestBlocks: false)

        ServerURLProtocolStub.enqueue(.json(Self.snapshotJSON(cursor: 12_400, messageIds: ["m1", "m2", "m3"])))
        let outcome = await service.syncNow(fillLatestBlocks: false)

        guard case .synced(let report) = outcome else {
            return XCTFail("期望 synced，实际 \(outcome)")
        }
        XCTAssertFalse(report.firstSync)
        XCTAssertEqual(report.cursor, 12_400)
        XCTAssertEqual(try temp.stores.messages.count(sessionId: "s1"), 3)
        // 第二次请求带上了上次的游标。
        let query = ServerTestSupport.queryItems(ServerURLProtocolStub.recordedRequests[1])
        XCTAssertEqual(query["since"], "12345")
    }

    /// 同一份快照应用两次：结果完全一致（幂等），游标不回退。
    func testApplyingSameSnapshotTwiceIsIdempotent() async throws {
        // 两次响应：一次给 `syncNow`，一次给下面单独取的那份快照（同一个 stub 队列）。
        ServerURLProtocolStub.enqueue(.json(Self.snapshotJSON()), .json(Self.snapshotJSON()))
        let service = makeService()
        let snapshot = try await ServerTestSupport.client().state()
        _ = await service.syncNow(fillLatestBlocks: false)

        _ = try await service.apply(snapshot)
        _ = try await service.apply(snapshot)

        XCTAssertEqual(try temp.stores.messages.count(sessionId: "s1"), 2)
        XCTAssertEqual(try temp.stores.sessions.count(), 1)
        XCTAssertEqual(try temp.stores.personas.count(), 1)
        let cursor = await service.cursor()
        XCTAssertEqual(cursor, 12_345)
    }

    /// 游标只前进：迟到的旧响应不该把它推回去。
    func testCursorAdvanceNeverGoesBackwards() throws {
        let state = ServerSyncStateStore(database: temp.stores.database)
        XCTAssertEqual(state.cursor(), 0)
        try state.advanceCursor(to: 100, at: 1)
        XCTAssertEqual(state.cursor(), 100)
        try state.advanceCursor(to: 40, at: 2)
        XCTAssertEqual(state.cursor(), 100)
        try state.advanceCursor(to: 140, at: 3)
        XCTAssertEqual(state.cursor(), 140)
    }

    // MARK: - 合并（不覆盖本地字段）

    func testSyncKeepsLocalOnlyColumns() async throws {
        // 本地已经有这个人格：提示词、绑定的 API 配置、世界设定都是「本机才有的东西」。
        var local = PersonaEntity(
            id: "p1",
            name: "旧名字",
            description: "旧描述",
            prompt: "本机写的人设提示词",
            defaultApiConfigId: "cfg-1"
        )
        local.worldJson = #"{"worldName":"临港新城"}"#
        try temp.stores.personas.upsert(local)
        // 会话也有本机字段：钉住 + 摘要进度。
        var session = ChatSessionEntity(id: "s1", personaId: "p1", title: "旧标题")
        session.pinned = true
        session.historySummary = "更早之前聊过搬家的路"
        try temp.stores.sessions.upsert(session)

        ServerURLProtocolStub.enqueue(.json(Self.snapshotJSON()))
        let service = makeService()
        _ = await service.syncNow(fillLatestBlocks: false)

        let persona = try XCTUnwrap(temp.stores.personas.byId("p1"))
        XCTAssertEqual(persona.name, "林深", "服务端权威的是名字/描述")
        XCTAssertEqual(persona.prompt, "本机写的人设提示词", "提示词不该被同步冲掉")
        XCTAssertEqual(persona.defaultApiConfigId, "cfg-1")
        XCTAssertEqual(persona.worldJson, #"{"worldName":"临港新城"}"#)

        let merged = try XCTUnwrap(temp.stores.sessions.byId("s1"))
        XCTAssertEqual(merged.title, "夜里散步")
        XCTAssertTrue(merged.pinned, "钉住是用户在本机的选择")
        XCTAssertEqual(merged.historySummary, "更早之前聊过搬家的路")
    }

    /// 本地模式先建过会话（本地 id）时：同步**不能**把服务端那条插成第二行，
    /// 消息也要挂在本地会话上（否则界面按本地 id 读不到刚同步来的历史）。
    func testSyncKeepsLocalSessionIdentityAndCachesMessagesUnderIt() async throws {
        try temp.stores.personas.upsert(PersonaEntity(id: "p1", name: "林深"))
        try temp.stores.sessions.upsert(ChatSessionEntity(
            id: "local-1", personaId: "p1", title: "本机标题", createdAt: 100
        ))
        ServerURLProtocolStub.enqueue(.json(Self.snapshotJSON()))
        let service = makeService()

        _ = await service.syncNow(fillLatestBlocks: false)

        XCTAssertEqual(try temp.stores.sessions.count(), 1, "一人格只能有一条会话")
        let claimed = try XCTUnwrap(temp.stores.sessions.byId("local-1"))
        XCTAssertEqual(claimed.serverSessionId, "s1")
        XCTAssertEqual(claimed.title, "夜里散步")
        XCTAssertEqual(try temp.stores.messages.count(sessionId: "local-1"), 2)
        XCTAssertEqual(try temp.stores.messages.count(sessionId: "s1"), 0)
        XCTAssertEqual(claimed.lastMessagePreview, "第2句")
    }

    // MARK: - 重试与退避

    func testRetriesWithExponentialBackoffThenSucceeds() async throws {
        let delays = DelayRecorder()
        ServerURLProtocolStub.enqueue(
            .init(failure: URLError(.notConnectedToInternet)),
            .init(failure: URLError(.timedOut)),
            .json(Self.snapshotJSON())
        )
        let service = makeService(delays: { delays.record($0) })

        let outcome = await service.syncNow(fillLatestBlocks: false)

        guard case .synced = outcome else { return XCTFail("期望最终成功，实际 \(outcome)") }
        XCTAssertEqual(delays.values, [0.5, 1.0], "退避 0.5s → 1s")
        let cursor = await service.cursor()
        XCTAssertEqual(cursor, 12_345)
    }

    func testGivesUpAfterMaxAttemptsAndDoesNotAdvanceCursor() async throws {
        let delays = DelayRecorder()
        ServerURLProtocolStub.enqueue(
            .init(failure: URLError(.cannotConnectToHost)),
            .init(failure: URLError(.cannotConnectToHost))
        )
        let service = makeService(
            retry: .init(maxAttempts: 2, baseDelaySeconds: 0.1),
            delays: { delays.record($0) }
        )

        let outcome = await service.syncNow(fillLatestBlocks: false)

        guard case .failed(let message, let attempts) = outcome else {
            return XCTFail("期望 failed，实际 \(outcome)")
        }
        XCTAssertEqual(attempts, 2)
        XCTAssertTrue(message.contains("连不上服务端"), "错误文案要能直接看：\(message)")
        XCTAssertEqual(delays.values, [0.1])
        let cursor = await service.cursor()
        XCTAssertEqual(cursor, 0, "失败时游标不能前进（否则会丢一段历史）")
        XCTAssertEqual(try temp.stores.personas.count(), 0)
    }

    /// 服务端模式关掉时同步直接跳过（本地模式不受影响）。
    func testSkipsWhenServerModeIsOff() async throws {
        let service = ServerSyncService(
            client: ServerAPIClient(config: ServerConfig(
                baseURL: URL(string: "https://server.example.com"), token: nil, enabled: false
            ), session: ServerURLProtocolStub.makeSession()),
            stores: temp.stores
        )
        let outcome = await service.syncNow()
        guard case .skipped = outcome else { return XCTFail("期望 skipped，实际 \(outcome)") }
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty, "关掉服务端模式时不该发请求")
    }

    // MARK: - 心情 / 在场

    /// 心情与在场都要能被界面读到：心情在人格列上，在场在 `server_sync_state` 里。
    func testMoodAndPresenceAreReadableAfterSync() async throws {
        ServerURLProtocolStub.enqueue(.json(Self.snapshotJSON()))
        let service = makeService()
        _ = await service.syncNow(fillLatestBlocks: false)

        let live = await service.liveState()
        XCTAssertEqual(live.mood?.label, "平静")
        XCTAssertEqual(live.presence?.kind, "IDLE")
        XCTAssertEqual(live.presence?.detail, "在听")

        let state = ServerSyncStateStore(database: temp.stores.database)
        let presenceJSON = try XCTUnwrap(state.string(ServerSyncStateStore.Key.presence("p1")))
        let decoded = try JSONDecoder().decode(ServerPresence.self, from: Data(presenceJSON.utf8))
        XCTAssertEqual(decoded.kind, "IDLE")
    }

    /// 服务端说「没有心情」时不该把本地已有心情清掉（本地模式仍然在用那一列）。
    func testSnapshotWithoutMoodKeepsExistingMood() async throws {
        var persona = PersonaEntity(id: "p1", name: "林深")
        persona.mood = "有点累"
        persona.moodIntensity = 2
        try temp.stores.personas.upsert(persona)

        let json = Self.snapshotJSON(mood: "null", presence: "null")
        ServerURLProtocolStub.enqueue(.json(json))
        let service = makeService()
        _ = await service.syncNow(fillLatestBlocks: false)

        let merged = try XCTUnwrap(temp.stores.personas.byId("p1"))
        XCTAssertEqual(merged.mood, "有点累")
    }
}

/// 记录退避时长（`ServerSyncService` 的 sleeper 是注入的，测试不真的睡）。
private final class DelayRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [Double] = []

    func record(_ value: Double) {
        lock.lock()
        recorded.append(value)
        lock.unlock()
    }

    var values: [Double] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }
}
