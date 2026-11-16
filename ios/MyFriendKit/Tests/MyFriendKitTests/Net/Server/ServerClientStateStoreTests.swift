import Foundation
import XCTest
@testable import MyFriendKit

/// 服务端模式的客户端侧簿记：游标表 / 待发队列 / 数据清理，以及「服务端权威状态」的展示映射。
final class ServerClientStateStoreTests: XCTestCase {

    private var temp: TempDatabase!

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
    }

    override func tearDownWithError() throws {
        temp?.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - server_sync_state

    func testStateStoreRoundTripsValuesAndRowsAreUniquePerKey() throws {
        let store = ServerSyncStateStore(database: temp.stores.database)
        XCTAssertNil(try store.value("server.cursor"))
        XCTAssertEqual(store.cursor(), 0, "没有游标 = 首次同步")

        // 同键覆盖（key 是主键，不会留下两行）
        try store.set("7", for: ServerSyncStateStore.Key.cursor, at: 100)
        XCTAssertEqual(store.cursor(), 7)
        try store.set("8", for: ServerSyncStateStore.Key.cursor, at: 200)
        XCTAssertEqual(store.cursor(), 8)

        try store.set("42", for: ServerSyncStateStore.Key.cursor, at: 300)
        XCTAssertEqual(store.cursor(), 42)
        XCTAssertEqual(try store.value(ServerSyncStateStore.Key.cursor), "42")

        // 删除一个键
        try store.set(nil, for: ServerSyncStateStore.Key.cursor, at: 400)
        XCTAssertEqual(store.cursor(), 0)
    }

    func testLastSyncAtIsReadable() throws {
        let store = ServerSyncStateStore(database: temp.stores.database)
        XCTAssertNil(store.lastSyncAt())
        try store.set("1800000000000", for: ServerSyncStateStore.Key.lastSyncAt, at: 1)
        XCTAssertEqual(store.lastSyncAt(), 1_800_000_000_000)
    }

    /// 坏值（有人手改过库/旧版本写坏）不当回事：游标读成 0，同步重头拉一次。
    func testCorruptedCursorFallsBackToZero() throws {
        let store = ServerSyncStateStore(database: temp.stores.database)
        try store.set("not-a-number", for: ServerSyncStateStore.Key.cursor, at: 1)
        XCTAssertEqual(store.cursor(), 0)
    }

    // MARK: - server_outbox

    func testOutboxEnqueueIsIdempotentAndOrdered() throws {
        let store = ServerOutboxStore(database: temp.stores.database)
        try store.enqueue(ServerOutboxEntry(
            clientMessageId: "c-2", sessionId: "s1", content: "第二句", createdAt: 200
        ))
        try store.enqueue(ServerOutboxEntry(
            clientMessageId: "c-1", sessionId: "s1", content: "第一句", createdAt: 100
        ))
        // 重复入队（同一个幂等键）不该变成两条
        try store.enqueue(ServerOutboxEntry(
            clientMessageId: "c-1", sessionId: "s1", content: "第一句", createdAt: 100
        ))

        XCTAssertEqual(store.count(), 2)
        XCTAssertEqual(store.all().map(\.clientMessageId), ["c-1", "c-2"], "按入队时间补发")
        XCTAssertEqual(store.entries(sessionId: "s1").count, 2)
        XCTAssertTrue(store.entries(sessionId: "s2").isEmpty)
    }

    func testOutboxRecordsFailureAndRemoves() throws {
        let store = ServerOutboxStore(database: temp.stores.database)
        try store.enqueue(ServerOutboxEntry(
            clientMessageId: "c-1", sessionId: "s1", content: "在吗", createdAt: 1
        ))

        XCTAssertEqual(try store.recordFailure(clientMessageId: "c-1", error: "连不上"), 1)
        XCTAssertEqual(try store.recordFailure(clientMessageId: "c-1", error: "还是连不上"), 2)
        let entry = try XCTUnwrap(store.entry(clientMessageId: "c-1"))
        XCTAssertEqual(entry.attempts, 2)
        XCTAssertEqual(entry.lastError, "还是连不上")

        try store.remove(clientMessageId: "c-1")
        XCTAssertEqual(store.count(), 0)
    }

    func testOutboxAttachmentsSurviveRoundTripAndBadJSONDegrades() throws {
        let store = ServerOutboxStore(database: temp.stores.database)
        try store.enqueue(ServerOutboxEntry(
            clientMessageId: "c-1",
            sessionId: "s1",
            content: "两张图",
            attachments: ["media-1", "media-2"],
            replyTo: "m0",
            createdAt: 1
        ))
        let entry = try XCTUnwrap(store.entry(clientMessageId: "c-1"))
        XCTAssertEqual(entry.attachments, ["media-1", "media-2"])
        XCTAssertEqual(entry.replyTo, "m0")

        // 坏 JSON 当空数组：一个坏字段不该让整条消息发不出去
        XCTAssertEqual(ServerOutboxStore.decodeAttachments("{oops"), [])
        XCTAssertEqual(ServerOutboxStore.encodeAttachments([]), "[]")
    }

    func testOutboxRemoveBySession() throws {
        let store = ServerOutboxStore(database: temp.stores.database)
        try store.enqueue(ServerOutboxEntry(clientMessageId: "c-1", sessionId: "s1", content: "a", createdAt: 1))
        try store.enqueue(ServerOutboxEntry(clientMessageId: "c-2", sessionId: "s2", content: "b", createdAt: 2))

        try store.remove(sessionId: "s1")

        XCTAssertEqual(store.all().map(\.clientMessageId), ["c-2"])
    }

    // MARK: - 清空数据

    /// 「删除全部本地数据」要连游标与队列一起清掉：留着游标会让之后只拉增量，历史永远补不回来。
    func testClearAllWipesServerBookkeeping() throws {
        let state = ServerSyncStateStore(database: temp.stores.database)
        try state.set("99", for: ServerSyncStateStore.Key.cursor, at: 1)
        let outbox = ServerOutboxStore(database: temp.stores.database)
        try outbox.enqueue(ServerOutboxEntry(clientMessageId: "c-1", sessionId: "s1", content: "a", createdAt: 1))

        let reset = DataReset(
            stores: temp.stores,
            settings: SettingsStore(defaults: try XCTUnwrap(UserDefaults(suiteName: "reset-\(UUID().uuidString)"))),
            apiKeys: ApiKeyStore(backend: InMemoryKeychain()),
            media: .shared
        )
        _ = reset.clearAll()

        XCTAssertEqual(state.cursor(), 0)
        XCTAssertEqual(outbox.count(), 0)
    }

    // MARK: - 服务端权威状态的展示映射

    func testMoodForDisplayUsesServerValues() {
        let live = ServerLiveState(
            mood: ServerMood(personaId: "p1", label: "低落", intensity: 4, updatedAt: 500),
            presence: nil,
            updatedAt: 500
        )
        let mood = try? XCTUnwrap(live.moodForDisplay)
        XCTAssertEqual(mood?.label, "低落")
        XCTAssertEqual(mood?.intensity, 4)
        XCTAssertEqual(mood?.updatedAt, 500)

        XCTAssertNil(ServerLiveState().moodForDisplay)
        XCTAssertNil(ServerLiveState(mood: ServerMood(personaId: "p1", label: "")).moodForDisplay)
    }

    func testPresenceForDisplayUsesServerDetailAsText() throws {
        let live = ServerLiveState(
            presence: ServerPresence(personaId: "p1", kind: "BUSY", detail: "在家，安静地待着", updatedAt: 700)
        )
        let presence = try XCTUnwrap(live.presenceForDisplay)
        XCTAssertEqual(presence.kind, PresenceKind(rawValue: "BUSY") ?? .idle)
        XCTAssertEqual(presence.text, "在家，安静地待着")
        XCTAssertEqual(presence.sinceAt, 700)
        XCTAssertTrue(presence.isMeaningful())

        // 没有 detail 时不给界面画一行空状态；认不出的 kind 退回 idle。
        XCTAssertNil(ServerLiveState(presence: ServerPresence(personaId: "p1", kind: "IDLE", detail: " ")).presenceForDisplay)
        let unknown = try XCTUnwrap(
            ServerLiveState(presence: ServerPresence(personaId: "p1", kind: "TELEPORTING", detail: "在上升")).presenceForDisplay
        )
        XCTAssertEqual(unknown.kind, .idle)
    }
}
