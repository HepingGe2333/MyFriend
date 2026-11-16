import Foundation
import XCTest
@testable import MyFriendKit

/// 服务端事件（M3/M4）：事件 → 缓存 + 通知 + 心情/在场，空轮询不动游标。
final class ServerEventServiceTests: XCTestCase {

    private var temp: TempDatabase!
    private var notifier: RecordingNotifier!

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerURLProtocolStub.reset()
        temp = try TempDatabase()
        notifier = RecordingNotifier()
        // 事件里带着 s1 的消息：本地先有这个人格/会话，预览与未读才有地方落。
        try temp.stores.personas.upsert(PersonaEntity(id: "p1", name: "林深"))
        try temp.stores.sessions.upsert(ChatSessionEntity(
            id: "s1", personaId: "p1", title: "夜里散步", createdAt: 1, updatedAt: 1
        ))
    }

    override func tearDownWithError() throws {
        ServerURLProtocolStub.reset()
        temp?.cleanUp()
        temp = nil
        notifier = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private static func messageEvent(
        cursor: Int64 = 8,
        id: String = "m1",
        role: String = "ASSISTANT",
        content: String = "在的，刚到家",
        createdAt: Int64 = 1_500
    ) -> String {
        """
        {
          "cursor": \(cursor),
          "type": "message.created",
          "at": \(createdAt),
          "sessionId": "s1",
          "personaId": "p1",
          "payload": {
            "message": {
              "id": "\(id)",
              "sessionId": "s1",
              "blockIndex": 1,
              "role": "\(role)",
              "content": "\(content)",
              "createdAt": \(createdAt),
              "readAt": null,
              "attachments": [],
              "source": "PROACTIVE",
              "clientMessageId": null,
              "replyTo": null,
              "promptTokens": null,
              "completionTokens": null
            }
          }
        }
        """
    }

    private static func eventsJSON(_ events: [String], cursor: Int64) -> String {
        """
        { "serverTime": 1700000000000, "cursor": \(cursor), "events": [\(events.joined(separator: ","))] }
        """
    }

    private func makeService(
        sessionOpen: Bool = false,
        foreground: Bool = true,
        live: LiveStateRecorder? = nil
    ) -> ServerEventService {
        ServerEventService(
            client: ServerTestSupport.client(),
            stores: temp.stores,
            notifier: notifier,
            isSessionOpen: { _ in sessionOpen },
            isForeground: { foreground },
            onLiveState: live.map { recorder in { recorder.record($0) } },
            now: { 1_800_000_000_000 },
            log: { _ in }
        )
    }

    // MARK: - 消息事件

    func testMessageCreatedIsCachedAndNotified() async throws {
        ServerURLProtocolStub.enqueue(.json(Self.eventsJSON([Self.messageEvent()], cursor: 8)))
        let service = makeService()

        let report = try await service.pollOnce()

        XCTAssertEqual(report.newMessages.map(\.id), ["m1"])
        XCTAssertFalse(report.timedOutEmpty)
        XCTAssertEqual(report.cursor, 8)

        // 1) 进缓存（界面下次读库就能看到）
        let saved = try XCTUnwrap(temp.stores.messages.byId("m1"))
        XCTAssertEqual(saved.content, "在的，刚到家")
        XCTAssertEqual(saved.role, .assistant)
        XCTAssertEqual(saved.personaId, "p1")

        // 2) 会话预览/未读/最后时间跟着动
        let session = try XCTUnwrap(temp.stores.sessions.byId("s1"))
        XCTAssertEqual(session.lastMessagePreview, "在的，刚到家")
        XCTAssertEqual(session.unreadCount, 1)
        XCTAssertEqual(session.lastMessageAt, 1_500)

        // 3) 通知器收到一条（标题用人名，正文是消息预览）
        let notifications = await notifier.snapshot()
        XCTAssertEqual(notifications.count, 1)
        XCTAssertEqual(notifications.first?.title, "夜里散步")
        XCTAssertEqual(notifications.first?.body, "在的，刚到家")
        XCTAssertEqual(notifications.first?.sessionId, "s1")

        // 4) 游标前进并落库
        let cursor = await service.cursor()
        XCTAssertEqual(cursor, 8)
    }

    func testUserMessageIsCachedButNotNotified() async throws {
        ServerURLProtocolStub.enqueue(.json(
            Self.eventsJSON([Self.messageEvent(role: "USER", content: "我先睡了")], cursor: 8)
        ))
        let service = makeService()

        _ = try await service.pollOnce()

        XCTAssertNotNil(try temp.stores.messages.byId("m1"))
        let notifications = await notifier.snapshot()
        XCTAssertTrue(notifications.isEmpty, "自己说的话不该弹「他发来一条消息」")
        XCTAssertEqual(try temp.stores.sessions.byId("s1")?.unreadCount, 0)
    }

    /// 用户正开着这个会话：消息照样进缓存，但不弹通知（否则首页会多一个点不掉的红点）。
    func testOpenSessionSuppressesNotificationAndUnread() async throws {
        ServerURLProtocolStub.enqueue(.json(Self.eventsJSON([Self.messageEvent()], cursor: 8)))
        let service = makeService(sessionOpen: true)

        _ = try await service.pollOnce()

        let notifications = await notifier.snapshot()
        XCTAssertTrue(notifications.isEmpty)
        XCTAssertEqual(try temp.stores.sessions.byId("s1")?.unreadCount, 0)
        XCTAssertNotNil(try temp.stores.messages.byId("m1"), "缓存照写")
    }

    /// 后台时不弹本地通知（那一侧是 `AppNotifications` 的后台策略，前台事件流不管）。
    func testBackgroundDoesNotNotify() async throws {
        ServerURLProtocolStub.enqueue(.json(Self.eventsJSON([Self.messageEvent()], cursor: 8)))
        let service = makeService(foreground: false)

        _ = try await service.pollOnce()

        let notifications = await notifier.snapshot()
        XCTAssertTrue(notifications.isEmpty)
    }

    // MARK: - 空轮询

    /// 服务端等满 25 秒没有事件：游标原样返回，**不能前移**（否则会漏掉还没收到的事件）。
    func testEmptyPollKeepsCursor() async throws {
        ServerURLProtocolStub.enqueue(.json(Self.eventsJSON([], cursor: 0)))
        let service = makeService()

        let report = try await service.pollOnce(timeoutSeconds: 1)

        XCTAssertTrue(report.timedOutEmpty)
        XCTAssertTrue(report.newMessages.isEmpty)
        XCTAssertEqual(report.cursor, 0)
        let cursor = await service.cursor()
        XCTAssertEqual(cursor, 0)
    }

    /// 未知事件类型（服务端以后加的）也要让游标前进，否则会永远卡在这一条上。
    func testUnknownEventTypeStillAdvancesCursor() async throws {
        let unknown = """
        { "cursor": 9, "type": "typing", "at": 2000, "sessionId": "s1", "personaId": "p1", "payload": {} }
        """
        ServerURLProtocolStub.enqueue(.json(Self.eventsJSON([unknown], cursor: 9)))
        let service = makeService()

        let report = try await service.pollOnce()

        XCTAssertEqual(report.cursor, 9)
        let cursor = await service.cursor()
        XCTAssertEqual(cursor, 9)
    }

    // MARK: - 心情 / 在场

    func testMoodAndPresenceEventsUpdateLiveStateAndCache() async throws {
        let mood = """
        { "cursor": 10, "type": "mood.changed", "at": 2000, "personaId": "p1",
          "payload": { "mood": { "personaId": "p1", "label": "低落", "intensity": 4, "updatedAt": 2000 } } }
        """
        let presence = """
        { "cursor": 11, "type": "presence.changed", "at": 2001, "personaId": "p1",
          "payload": { "presence": { "personaId": "p1", "kind": "BUSY", "detail": "在赶图", "updatedAt": 2001 } } }
        """
        ServerURLProtocolStub.enqueue(.json(Self.eventsJSON([mood, presence], cursor: 11)))
        let recorder = LiveStateRecorder()
        let service = makeService(live: recorder)

        let report = try await service.pollOnce()

        XCTAssertTrue(report.moodChanged)
        XCTAssertTrue(report.presenceChanged)

        // 心情写回人格列（界面已经在读它）
        let persona = try XCTUnwrap(temp.stores.personas.byId("p1"))
        XCTAssertEqual(persona.mood, "低落")
        XCTAssertEqual(persona.moodIntensity, 4)

        // 在场进 `server_sync_state`（本地没有 presence 表）
        let state = ServerSyncStateStore(database: temp.stores.database)
        let raw = try XCTUnwrap(state.string(ServerSyncStateStore.Key.presence("p1")))
        let decoded = try JSONDecoder().decode(ServerPresence.self, from: Data(raw.utf8))
        XCTAssertEqual(decoded.kind, "BUSY")

        // 界面拿到的活状态
        let events = recorder.snapshot()
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events.first?.mood?.label, "低落")
        XCTAssertEqual(events.first?.presence?.detail, "在赶图")
    }

    // MARK: - 失败

    func testPollThrowsWhenServerIsUnreachable() async throws {
        ServerURLProtocolStub.enqueue(.init(failure: URLError(.notConnectedToInternet)))
        let service = makeService()

        do {
            _ = try await service.pollOnce()
            XCTFail("应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.code, ServerAPIError.Code.networkError)
        }
        let cursor = await service.cursor()
        XCTAssertEqual(cursor, 0, "失败时游标不动，下次重问同一段")
    }
}

// MARK: - 记录器

/// 记录通知（App 层真实实现是 `UNUserNotificationCenter`，这里只数次数与内容）。
private final class RecordingNotifier: ServerEventNotifying, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [ServerIncomingNotification] = []

    func notifyIncoming(_ notification: ServerIncomingNotification) async {
        lock.lock()
        items.append(notification)
        lock.unlock()
    }

    func snapshot() async -> [ServerIncomingNotification] {
        lock.lock()
        defer { lock.unlock() }
        return items
    }
}

/// 记录界面拿到的活状态。
private final class LiveStateRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [ServerLiveState] = []

    func record(_ state: ServerLiveState) {
        lock.lock()
        items.append(state)
        lock.unlock()
    }

    func snapshot() -> [ServerLiveState] {
        lock.lock()
        defer { lock.unlock() }
        return items
    }
}
