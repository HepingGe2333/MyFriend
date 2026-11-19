import Foundation
import XCTest
@testable import YapKit

/// 服务端模式聊天（M3）：SSE 落库 / 幂等 clientMessageId / 离线队列与补发 / 参数拒绝。
final class ServerChatGatewayTests: XCTestCase {

    private var temp: TempDatabase!

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerURLProtocolStub.reset()
        temp = try TempDatabase()
        try temp.stores.personas.upsert(PersonaEntity(id: "p1", name: "林深"))
        try temp.stores.sessions.upsert(ChatSessionEntity(
            id: "s1", personaId: "p1", title: "夜里散步", createdAt: 100, updatedAt: 200
        ))
    }

    override func tearDownWithError() throws {
        ServerURLProtocolStub.reset()
        temp?.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func gateway() -> ServerChatGateway {
        ServerChatGateway(
            client: ServerTestSupport.client(),
            stores: temp.stores,
            now: { 1_800_000_000_000 },
            log: { _ in }
        )
    }

    /// 一段完整的 SSE：accepted → delta → tool → delta → done。
    private static func sseResponse() -> ServerURLProtocolStub.Response {
        let body = ServerTestSupport.sseFrame(
            "accepted", #"{"userMessageId":"u1","createdAt":1700}"#
        )
            + ServerTestSupport.sseFrame("delta", #"{"text":"在的，"}"#)
            + ServerTestSupport.sseFrame(
                "tool", #"{"name":"web_search","status":"running","summary":"搜一下明天天气"}"#
            )
            + ServerTestSupport.sseFrame(
                "tool", #"{"name":"web_search","status":"ok","summary":"明天晴"}"#
            )
            + ServerTestSupport.sseFrame("delta", #"{"text":"刚到家的路上看了下天气。"}"#)
            + ServerTestSupport.sseFrame(
                "done", #"{"assistantMessageId":"a1","usage":{"promptTokens":12,"completionTokens":34}}"#
            )
        return .sse(body)
    }

    private func collect(_ stream: AsyncThrowingStream<ChatEvent, Error>) async -> (events: [ChatEvent], error: Error?) {
        var events: [ChatEvent] = []
        do {
            for try await event in stream { events.append(event) }
            return (events, nil)
        } catch {
            return (events, error)
        }
    }

    /// 0.14.1 §8：新服务端不再发 delta，而是 `typing.started` → `message.created`（可多条）
    /// → `typing.stopped` → `done`。客户端必须直接上屏，`done` 不能把正文覆盖成空。
    private static func sseDelayedResponse() -> ServerURLProtocolStub.Response {
        func message(_ id: String, _ content: String, _ createdAt: Int64) -> String {
            // SSE 的 `data:` 只加在第一行：JSON 必须是一行，否则后续行会被当成 SSE 字段。
            "{\"message\":{\"id\":\"\(id)\",\"sessionId\":\"s1\",\"blockIndex\":1,"
                + "\"role\":\"ASSISTANT\",\"content\":\"\(content)\",\"createdAt\":\(createdAt),"
                + "\"readAt\":null,\"attachments\":[],\"source\":\"CHAT\","
                + "\"clientMessageId\":null,\"replyTo\":\"u1\","
                + "\"promptTokens\":null,\"completionTokens\":null}}"
        }
        let body = ServerTestSupport.sseFrame(
            "accepted", #"{"userMessageId":"u1","createdAt":1700}"#
        )
            + ServerTestSupport.sseFrame("typing.started", #"{"sessionId":"s1","personaId":"p1","at":1700}"#)
            + ServerTestSupport.sseFrame("message.created", message("a1", "第一句", 1700))
            + ServerTestSupport.sseFrame("message.created", message("a2", "第二句", 1701))
            + ServerTestSupport.sseFrame("typing.stopped", #"{"sessionId":"s1","personaId":"p1","at":1702}"#)
            + ServerTestSupport.sseFrame(
                "done", #"{"assistantMessageId":"a2","usage":{"promptTokens":12,"completionTokens":34}}"#
            )
        return .sse(body)
    }

    func testMessageCreatedSSEDisplaysMessagesAndDoneKeepsContent() async throws {
        ServerURLProtocolStub.enqueue(Self.sseDelayedResponse())
        let gateway = gateway()

        guard case .started(let stream) = gateway.sendChecked(sessionId: "s1", text: "在吗") else {
            return XCTFail("应当直接开始")
        }
        let result = await collect(stream)
        XCTAssertNil(result.error)

        // 每条 message.created 都变成 `.appended`（界面立刻上屏，按顺序）。
        let appended = result.events.compactMap { event -> MessageEntity? in
            if case .appended(let message) = event { return message }
            return nil
        }
        XCTAssertEqual(appended.map(\.id), ["a1", "a2"])
        XCTAssertEqual(appended.map(\.content), ["第一句", "第二句"])

        // `done` 不能拿空 delta 把第二条覆盖成空气泡。
        let done = result.events.compactMap { event -> ChatCompletion? in
            if case .done(let completion) = event { return completion }
            return nil
        }
        XCTAssertEqual(done.first?.message.id, "a2")
        XCTAssertEqual(done.first?.message.content, "第二句")

        let stored = try temp.stores.messages.bySession("s1")
        XCTAssertEqual(stored.map(\.id), ["u1", "a1", "a2"])
        XCTAssertEqual(stored.map(\.content), ["在吗", "第一句", "第二句"])
        XCTAssertEqual(gateway.pendingEntries(sessionId: "s1").count, 0)
    }

    // MARK: - 发送成功

    func testSendStreamsDeltasPersistsMessagesAndDequeues() async throws {
        ServerURLProtocolStub.enqueue(Self.sseResponse())
        let gateway = gateway()

        guard case .started(let stream) = gateway.sendChecked(sessionId: "s1", text: "你在干嘛") else {
            return XCTFail("应当直接开始")
        }
        // 发送前：队列里有一条，界面上有一条「还没发出去」的占位气泡。
        XCTAssertEqual(gateway.pendingEntries(sessionId: "s1").count, 1)

        let result = await collect(stream)
        XCTAssertNil(result.error)

        // 1) 增量是**全文**（界面直接覆盖），两次 delta 各一份
        let deltas = result.events.compactMap { event -> String? in
            if case .delta(let text) = event { return text }
            return nil
        }
        XCTAssertEqual(deltas, ["在的，", "在的，刚到家的路上看了下天气。"])

        // 2) 工具痕迹：开始一次 + 完成一次（同一个工具两次事件）
        let started = result.events.filter {
            if case .toolStarted = $0 { return true }
            return false
        }
        let finished = result.events.filter {
            if case .toolFinished = $0 { return true }
            return false
        }
        XCTAssertEqual(started.count, 1)
        XCTAssertEqual(finished.count, 1)

        // 3) done 里带的是**已落库**的助手消息
        let done = result.events.compactMap { event -> ChatCompletion? in
            if case .done(let completion) = event { return completion }
            return nil
        }
        XCTAssertEqual(done.count, 1)
        XCTAssertEqual(done.first?.message.id, "a1")
        XCTAssertEqual(done.first?.message.content, "在的，刚到家的路上看了下天气。")

        // 4) 缓存里：用户消息用服务端 id（占位那条已替换），助手消息也在
        let messages = try temp.stores.messages.bySession("s1")
        XCTAssertEqual(messages.map(\.id), ["u1", "a1"])
        XCTAssertEqual(messages.first?.role, .user)
        XCTAssertEqual(messages.first?.content, "你在干嘛")
        XCTAssertNil(try temp.stores.messages.byId(ServerChatGateway.pendingIdPrefix + "x"))

        // 5) 队列已出队
        XCTAssertEqual(gateway.pendingEntries(sessionId: "s1").count, 0)

        // 6) 会话预览跟着更新
        let session = try XCTUnwrap(temp.stores.sessions.byId("s1"))
        XCTAssertEqual(session.lastMessagePreview, "在的，刚到家的路上看了下天气。")

        // 7) 请求体里带了幂等键与正文
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/sessions/s1/messages")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "text/event-stream")
        let body = try XCTUnwrap(ServerURLProtocolStub.recordedBodies.first)
        let object = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: body) as? [String: Any]
        )
        XCTAssertEqual(object["content"] as? String, "你在干嘛")
        XCTAssertNotNil(object["clientMessageId"] as? String)
    }

    // MARK: - 离线排队与补发

    func testOfflineSendKeepsQueueAndPendingBubble() async throws {
        ServerURLProtocolStub.enqueue(.init(failure: URLError(.notConnectedToInternet)))
        let gateway = gateway()

        guard case .started(let stream) = gateway.sendChecked(sessionId: "s1", text: "在吗") else {
            return XCTFail("应当先入队")
        }
        let result = await collect(stream)

        // 失败：流里报错（界面弹提示），但**队列与气泡都还在**（用户的字没丢）
        XCTAssertNotNil(result.error)
        let pending = gateway.pendingEntries(sessionId: "s1")
        XCTAssertEqual(pending.count, 1)
        XCTAssertEqual(pending.first?.attempts, 1)
        XCTAssertNotNil(pending.first?.lastError)

        let messages = try temp.stores.messages.bySession("s1")
        XCTAssertEqual(messages.count, 1)
        XCTAssertEqual(messages.first?.id, ServerChatGateway.pendingIdPrefix + (pending.first?.clientMessageId ?? ""))
        XCTAssertEqual(messages.first?.status, .error, "界面上看得出「还没发出去」")
    }

    func testFlushOutboxResendsWithSameClientMessageId() async throws {
        // 第一次：网络不通（入队后发送失败）
        ServerURLProtocolStub.enqueue(.init(failure: URLError(.notConnectedToInternet)))
        let gateway = gateway()
        guard case .started(let stream) = gateway.sendChecked(sessionId: "s1", text: "在吗") else {
            return XCTFail("应当先入队")
        }
        _ = await collect(stream)
        let queuedId = try XCTUnwrap(gateway.pendingEntries(sessionId: "s1").first?.clientMessageId)

        // 第二次：网络恢复，补发成功
        ServerURLProtocolStub.enqueue(Self.sseResponse())
        let sent = await gateway.flushOutbox()

        XCTAssertEqual(sent, 1)
        XCTAssertEqual(gateway.pendingEntries(sessionId: "s1").count, 0)

        // 两次请求的 clientMessageId 必须一致（服务端靠它去重，否则会变成两句）
        let bodies = ServerURLProtocolStub.recordedBodies
        XCTAssertEqual(bodies.count, 2)
        let ids = try bodies.map { body -> String in
            let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: body) as? [String: Any])
            return try XCTUnwrap(object["clientMessageId"] as? String)
        }
        XCTAssertEqual(ids[0], queuedId)
        XCTAssertEqual(ids[1], queuedId, "补发必须复用同一个幂等键")

        // 落库：占位气泡被替换成服务端 id 的那一条，助手回复也在
        let messages = try temp.stores.messages.bySession("s1")
        XCTAssertEqual(messages.map(\.id), ["u1", "a1"])
    }

    func testRecoverPendingMessagesRebuildsBubbles() async throws {
        try ServerOutboxStore(database: temp.stores.database).enqueue(
            ServerOutboxEntry(
                clientMessageId: "c-1",
                sessionId: "s1",
                personaId: "p1",
                content: "在吗",
                createdAt: 1_000
            )
        )
        let gateway = gateway()

        let restored = gateway.recoverPendingMessages()

        XCTAssertEqual(restored, 1)
        let messages = try temp.stores.messages.bySession("s1")
        XCTAssertEqual(messages.map(\.id), [ServerChatGateway.pendingIdPrefix + "c-1"])
        // 幂等：再跑一次不会重复插入
        XCTAssertEqual(gateway.recoverPendingMessages(), 0)
        XCTAssertEqual(try temp.stores.messages.count(sessionId: "s1"), 1)
    }

    // MARK: - 参数拒绝（草稿要还回去）

    func testRejectsImageMessageWithoutTouchingQueue() throws {
        let gateway = gateway()
        let outcome = gateway.sendChecked(
            sessionId: "s1",
            text: "看图",
            attachments: [MessageAttachment(uri: "file:///tmp/a.jpg")]
        )
        guard case .rejected(let error) = outcome else { return XCTFail("带图片应当被拒绝") }
        XCTAssertTrue(error.localizedDescription.contains("图片"))
        XCTAssertEqual(gateway.pendingEntries(sessionId: "s1").count, 0)
        XCTAssertEqual(try temp.stores.messages.count(sessionId: "s1"), 0)
    }

    func testRejectsMissingSessionAndEmptyText() throws {
        let gateway = gateway()
        guard case .rejected = gateway.sendChecked(sessionId: "nope", text: "在吗") else {
            return XCTFail("会话不存在应当拒绝")
        }
        guard case .rejected = gateway.sendChecked(sessionId: "s1", text: "   ") else {
            return XCTFail("空消息应当拒绝")
        }
        XCTAssertEqual(try temp.stores.messages.count(sessionId: "s1"), 0)
    }

    // MARK: - 打开会话按块拉

    func testLoadLatestBlockCachesMessagesAndUpdatesPreview() async throws {
        ServerURLProtocolStub.enqueue(.json(ServerTestSupport.messagePageJSON))
        let gateway = gateway()

        let state = try await gateway.loadLatestBlock(sessionId: "s1")

        XCTAssertEqual(state.blockIndex, 2)
        XCTAssertTrue(state.hasMoreOlder)
        let messages = try temp.stores.messages.bySession("s1")
        XCTAssertEqual(messages.map(\.id), ["m1"])
        XCTAssertEqual(messages.first?.content, "在的")
        XCTAssertEqual(try temp.stores.sessions.byId("s1")?.lastMessagePreview, "在的")

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.url?.path, "/v1/sessions/s1/messages")
        XCTAssertEqual(ServerTestSupport.queryItems(request)["limitBlocks"], "1")
        XCTAssertNil(ServerTestSupport.queryItems(request)["beforeBlock"])
    }

    func testLoadOlderBlockSendsBeforeBlockAndIsIdempotent() async throws {
        ServerURLProtocolStub.enqueue(
            .json(ServerTestSupport.messagePageJSON),
            .json(ServerTestSupport.messagePageJSON)
        )
        let gateway = gateway()

        _ = try await gateway.loadLatestBlock(sessionId: "s1")
        _ = try await gateway.loadOlderBlock(sessionId: "s1", beforeBlock: 2)

        XCTAssertEqual(ServerTestSupport.queryItems(ServerURLProtocolStub.recordedRequests[1])["beforeBlock"], "2")
        // 同一块拉两次不会写重复（id 是主键）
        XCTAssertEqual(try temp.stores.messages.count(sessionId: "s1"), 1)
    }

    // MARK: - 服务端块号（翻更早的块）

    /// 翻页要用**服务端**的块号：它记在 `server_sync_state` 里，不是本地的块号。
    func testBlockPositionIsRememberedForOlderPaging() async throws {
        ServerURLProtocolStub.enqueue(
            .json(ServerTestSupport.messagePageJSON), // blockIndex 2, hasMoreOlder true
            .json(ServerTestSupport.emptyMessagePageJSON) // 再往前没有了
        )
        let gateway = gateway()

        _ = try await gateway.loadLatestBlock(sessionId: "s1")
        XCTAssertTrue(gateway.hasOlderOnServer(sessionId: "s1"), "服务端说还有更早的块")

        _ = try await gateway.loadOlderBlockRememberingPosition(sessionId: "s1")

        let query = ServerTestSupport.queryItems(ServerURLProtocolStub.recordedRequests[1])
        XCTAssertEqual(query["beforeBlock"], "2", "翻页要带服务端记下来的块号")
    }

    /// 没记过块号（第一次进来就上滑）：先拉最新块，不瞎猜一个 beforeBlock。
    func testOlderPagingWithoutRememberedPositionLoadsLatestFirst() async throws {
        ServerURLProtocolStub.enqueue(.json(ServerTestSupport.messagePageJSON))
        let gateway = gateway()

        _ = try await gateway.loadOlderBlockRememberingPosition(sessionId: "s1")

        let query = ServerTestSupport.queryItems(ServerURLProtocolStub.recordedRequests[0])
        XCTAssertNil(query["beforeBlock"])
        XCTAssertTrue(gateway.hasOlderOnServer(sessionId: "s1"))
    }

    // MARK: - 本地 id 与服务端 id 不同

    /// 本机先建过会话（local-1），服务端那条是 s1：请求要带 s1，缓存要挂 local-1。
    func testSendUsesServerSessionIdInRequestButLocalIdInCache() async throws {
        try temp.stores.sessions.delete(id: "s1")
        try temp.stores.sessions.upsert(ChatSessionEntity(
            id: "local-1", personaId: "p1", title: "本机标题", createdAt: 100)
        )
        try ServerSessionResolver(stores: temp.stores).localSessionId(
            forServer: ServerSession(id: "s1", personaId: "p1", title: "夜里散步"),
            at: 200
        )
        ServerURLProtocolStub.enqueue(Self.sseResponse())
        let gateway = gateway()

        guard case .started(let stream) = gateway.sendChecked(sessionId: "local-1", text: "你在干嘛") else {
            return XCTFail("应当直接开始")
        }
        _ = await collect(stream)

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.url?.path, "/v1/sessions/s1/messages", "发往服务端要用服务端 id")
        let messages = try temp.stores.messages.bySession("local-1")
        XCTAssertEqual(messages.map(\.id), ["u1", "a1"], "缓存挂在本地会话上")
        XCTAssertEqual(try temp.stores.messages.count(sessionId: "s1"), 0)
    }

    func testEnsureSessionDoesNotCreateSecondRowForSamePersona() async throws {
        try temp.stores.sessions.delete(id: "s1")
        try temp.stores.sessions.upsert(ChatSessionEntity(
            id: "local-1", personaId: "p1", title: "本机标题", createdAt: 100)
        )
        ServerURLProtocolStub.enqueue(.json("""
        { "created": true, "session": { "id": "s1", "personaId": "p1", "title": "夜里散步",
          "createdAt": 100, "updatedAt": 200, "lastMessageAt": null, "lastBlockIndex": -1,
          "unread": 0, "messageCount": 0 } }
        """))
        let gateway = gateway()

        _ = try await gateway.ensureSession(personaId: "p1")

        XCTAssertEqual(try temp.stores.sessions.count(), 1)
        XCTAssertEqual(try temp.stores.sessions.byId("local-1")?.serverSessionId, "s1")
    }

    // MARK: - 写路径：建会话 / 标已读

    func testEnsureSessionRecordsServerSessionId() async throws {
        ServerURLProtocolStub.enqueue(.json("""
        {
          "created": true,
          "session": {
            "id": "s1", "personaId": "p1", "title": "夜里散步", "createdAt": 100,
            "updatedAt": 200, "lastMessageAt": null, "lastBlockIndex": -1,
            "unread": 0, "messageCount": 0
          }
        }
        """))
        let gateway = gateway()

        let session = try await gateway.ensureSession(personaId: "p1")

        XCTAssertEqual(session.id, "s1")
        let local = try XCTUnwrap(temp.stores.sessions.byId("s1"))
        XCTAssertEqual(local.serverSessionId, "s1")
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.url?.path, "/v1/sessions")
    }

    func testMarkReadClearsLocalUnread() async throws {
        var session = try XCTUnwrap(temp.stores.sessions.byId("s1"))
        session.unreadCount = 3
        try temp.stores.sessions.upsert(session)
        ServerURLProtocolStub.enqueue(.json("""
        { "sessionId": "s1", "unread": 0, "readAt": 1800000000000, "marked": 3 }
        """))
        let gateway = gateway()

        await gateway.markRead(sessionId: "s1")

        XCTAssertEqual(try temp.stores.sessions.byId("s1")?.unreadCount, 0)
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests.first?.url?.path, "/v1/sessions/s1/read")
    }

    /// 标已读失败只记日志：不该抛、不该改本地未读（下次同步会修正）。
    func testMarkReadFailureIsSwallowed() async throws {
        var session = try XCTUnwrap(temp.stores.sessions.byId("s1"))
        session.unreadCount = 2
        try temp.stores.sessions.upsert(session)
        ServerURLProtocolStub.enqueue(.init(failure: URLError(.notConnectedToInternet)))
        let gateway = gateway()

        await gateway.markRead(sessionId: "s1")

        XCTAssertEqual(try temp.stores.sessions.byId("s1")?.unreadCount, 2)
    }
}
