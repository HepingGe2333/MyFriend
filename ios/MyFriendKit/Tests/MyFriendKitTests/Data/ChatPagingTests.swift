import Foundation
import XCTest
@testable import MyFriendKit

/// 聊天页窗口化分页的存储层语义：首屏 60、上滑每次 60、游标到最早、同一毫秒不重复。
///
/// 「未读 / 免打扰走全表」这条也钉在这里：窗口只影响**读出来画什么**，
/// 不影响任何计数查询（`unreadCount`、`countUserMessages`、`markUserMessagesRead`）。
final class ChatPagingTests: XCTestCase {

    private var temp: TempDatabase!
    private let sessionId = "session-1"
    private let personaId = "persona-1"
    private let base: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        try temp.stores.personas.upsert(TestData.persona(id: personaId))
        try temp.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    @discardableResult
    private func seed(_ count: Int, sameTimestampForAll: Bool = false) throws -> [MessageEntity] {
        let messages = (0..<count).map { index in
            TestData.message(
                id: String(format: "m-%03d", index),
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index) 句",
                createdAt: sameTimestampForAll ? base : base + Int64(index) * 1000
            )
        }
        try temp.stores.messages.upsert(messages)
        return messages
    }

    // MARK: - 首屏

    func testFirstPageTakesLatestSixtyInAscendingOrder() throws {
        try seed(100)
        let page = try temp.stores.messages.recent(sessionId: sessionId, limit: MessagePaging.pageSize)
        XCTAssertEqual(page.count, 60)
        XCTAssertEqual(page.first?.id, "m-040")
        XCTAssertEqual(page.last?.id, "m-099")
        XCTAssertEqual(page.map(\.id), page.map(\.id).sorted(), "按时间升序交给界面")
    }

    func testFewerThanOnePageReturnsEverything() throws {
        try seed(5)
        let page = try temp.stores.messages.recent(sessionId: sessionId, limit: MessagePaging.pageSize)
        XCTAssertEqual(page.count, 5)
        let oldest = try XCTUnwrap(page.first)
        XCTAssertEqual(
            try temp.stores.messages.countBefore(sessionId: sessionId, createdAt: oldest.createdAt, id: oldest.id),
            0,
            "不足一页时没有更早的"
        )
    }

    func testExactlyOnePageHasNoEarlier() throws {
        try seed(60)
        let page = try temp.stores.messages.recent(sessionId: sessionId, limit: MessagePaging.pageSize)
        XCTAssertEqual(page.count, 60)
        let oldest = try XCTUnwrap(page.first)
        XCTAssertEqual(
            try temp.stores.messages.countBefore(sessionId: sessionId, createdAt: oldest.createdAt, id: oldest.id),
            0,
            "恰好 60 条时不该显示「加载更早」"
        )
    }

    // MARK: - 游标翻页

    func testCursorWalksBackToTheEarliest() throws {
        try seed(130)
        var loaded = try temp.stores.messages.recent(sessionId: sessionId, limit: MessagePaging.pageSize)
        XCTAssertEqual(loaded.count, 60)

        var oldest = try XCTUnwrap(loaded.first)
        XCTAssertEqual(
            try temp.stores.messages.countBefore(sessionId: sessionId, createdAt: oldest.createdAt, id: oldest.id),
            70
        )
        let second = try temp.stores.messages.pageBefore(
            sessionId: sessionId, createdAt: oldest.createdAt, id: oldest.id, limit: MessagePaging.pageSize
        )
        XCTAssertEqual(second.count, 60)
        loaded = MessagePaging.prepend(second, to: loaded)
        XCTAssertEqual(loaded.count, 120)
        XCTAssertEqual(loaded.first?.id, "m-010")

        oldest = try XCTUnwrap(loaded.first)
        let third = try temp.stores.messages.pageBefore(
            sessionId: sessionId, createdAt: oldest.createdAt, id: oldest.id, limit: MessagePaging.pageSize
        )
        XCTAssertEqual(third.count, 10)
        loaded = MessagePaging.prepend(third, to: loaded)
        XCTAssertEqual(loaded.count, 130)
        XCTAssertEqual(loaded.first?.id, "m-000")
        oldest = try XCTUnwrap(loaded.first)
        XCTAssertEqual(
            try temp.stores.messages.countBefore(sessionId: sessionId, createdAt: oldest.createdAt, id: oldest.id),
            0,
            "到最早一条后不再显示「加载更早」"
        )
        // 已经到底：再取一页是空的，窗口不变。
        let empty = try temp.stores.messages.pageBefore(
            sessionId: sessionId, createdAt: oldest.createdAt, id: oldest.id, limit: MessagePaging.pageSize
        )
        XCTAssertTrue(empty.isEmpty)
        XCTAssertEqual(MessagePaging.prepend(empty, to: loaded).count, 130)
    }

    func testSameMillisecondUsesIdAsTiebreak() throws {
        try seed(4, sameTimestampForAll: true)
        let page = try temp.stores.messages.recent(sessionId: sessionId, limit: 2)
        XCTAssertEqual(page.map(\.id), ["m-002", "m-003"])
        let oldest = try XCTUnwrap(page.first)
        let earlier = try temp.stores.messages.pageBefore(
            sessionId: sessionId, createdAt: oldest.createdAt, id: oldest.id, limit: 10
        )
        XCTAssertEqual(earlier.map(\.id), ["m-000", "m-001"], "同一毫秒靠 id 稳定排序，不漏不重")
    }

    func testPrependDeduplicatesOverlappingPages() throws {
        try seed(10)
        let window = try temp.stores.messages.recent(sessionId: sessionId, limit: 4)
        let overlap = try temp.stores.messages.recent(sessionId: sessionId, limit: 8)

        let once = MessagePaging.prepend(overlap, to: window)
        let twice = MessagePaging.prepend(overlap, to: once)
        XCTAssertEqual(once.map(\.id), overlap.map(\.id), "重复的一页按 id 去重")
        XCTAssertEqual(twice.map(\.id), once.map(\.id), "同一页重复合并必须是幂等的")
        XCTAssertEqual(Set(twice.map(\.id)).count, twice.count, "没有重复 id")
    }

    // MARK: - 新消息只往后追加（0.11.0 P2-5）

    /// 用户上滑加载到 120 条之后，连着来 5 条新消息：已加载的那 120 条必须还在窗口里。
    ///
    /// 0.10.2 的写法是「重读最后 max(pageSize, loaded) 条」：来一条新消息，窗口就往后
    /// 滑一格，最早那一页被挤掉、滚动位置跳动。现在以「窗口最后一条」为游标只取更新的，
    /// 再接在已加载区间后面。
    func testFiveNewMessagesKeepTheLoadedHundredAndTwenty() throws {
        try seed(120)
        var loaded = try temp.stores.messages.recent(sessionId: sessionId, limit: 120)
        XCTAssertEqual(loaded.count, 120)
        let before = loaded.map(\.id)

        // 模拟「心跳在这段时间里写了 5 条」。
        let fresh = (120..<125).map { index in
            TestData.message(
                id: String(format: "m-%03d", index),
                role: .assistant,
                content: "新消息 \(index)",
                createdAt: base + Int64(index) * 1000
            )
        }
        try temp.stores.messages.upsert(fresh)
        let cursor = try XCTUnwrap(loaded.last)
        let newer = try temp.stores.messages.pageAfter(
            sessionId: sessionId, createdAt: cursor.createdAt, id: cursor.id, limit: 50
        )
        loaded = MessagePaging.appendingNewer(newer, to: loaded)

        XCTAssertEqual(loaded.count, 125)
        XCTAssertEqual(Array(loaded.prefix(120)).map(\.id), before, "已加载的 120 条原封不动")
        XCTAssertEqual(Array(loaded.suffix(5)).map(\.id), fresh.map(\.id), "新消息接在后面")
        XCTAssertEqual(loaded.map(\.id), loaded.map(\.id).sorted(), "仍然是时间升序")
    }

    /// 重复合并同一批新消息是幂等的（轮询与流结束可能同时触发一次重读）。
    func testAppendingNewerIsIdempotent() throws {
        try seed(10)
        var loaded = try temp.stores.messages.recent(sessionId: sessionId, limit: 6)
        let newer = try temp.stores.messages.recent(sessionId: sessionId, limit: 4)
        loaded = MessagePaging.appendingNewer(newer, to: loaded)
        let once = loaded
        loaded = MessagePaging.appendingNewer(newer, to: loaded)
        XCTAssertEqual(loaded.map(\.id), once.map(\.id))
        XCTAssertEqual(Set(loaded.map(\.id)).count, loaded.count, "没有重复 id")
    }

    /// 长时间停留的页面不能把窗口重新涨成「几千条」：超过上限时从最旧的一端丢。
    func testWindowCapDropsOldestOnly() throws {
        try seed(10)
        let loaded = try temp.stores.messages.recent(sessionId: sessionId, limit: 8)
        XCTAssertEqual(loaded.map(\.id), ["m-002", "m-003", "m-004", "m-005", "m-006", "m-007", "m-008", "m-009"])
        let cursor = try XCTUnwrap(loaded.last)
        try temp.stores.messages.upsert((10..<13).map { index in
            TestData.message(
                id: String(format: "m-%03d", index),
                role: .assistant,
                content: "新消息 \(index)",
                createdAt: base + Int64(index) * 1000
            )
        })
        let newer = try temp.stores.messages.pageAfter(
            sessionId: sessionId, createdAt: cursor.createdAt, id: cursor.id, limit: 100
        )
        let capped = MessagePaging.appendingNewer(newer, to: loaded, cap: 5)
        XCTAssertEqual(capped.count, 5)
        XCTAssertEqual(capped.map(\.id), ["m-008", "m-009", "m-010", "m-011", "m-012"],
                       "丢的是最旧的那几条，最新的一定留着")
    }

    /// 窗口还空着（刚进页面 / 清空上下文之后）时，新消息就是窗口本身。
    func testAppendingToEmptyWindowUsesTheNewMessages() throws {
        try seed(3)
        let newer = try temp.stores.messages.recent(sessionId: sessionId, limit: 3)
        XCTAssertEqual(MessagePaging.appendingNewer(newer, to: []).map(\.id), newer.map(\.id))
        XCTAssertTrue(MessagePaging.appendingNewer([], to: []).isEmpty)
    }

    // MARK: - 未读 / 免打扰走全表

    func testUnreadAndWakeCountsIgnoreTheWindow() throws {
        try seed(130)
        // 未读是会话行上的一个计数列，不是「窗口里没读过几条」。
        var session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        session.unreadCount = 3
        try temp.stores.sessions.upsert(session)

        let window = try temp.stores.messages.recent(sessionId: sessionId, limit: MessagePaging.pageSize)
        XCTAssertEqual(window.count, 60, "窗口只读最近 60 条")
        XCTAssertEqual(try temp.stores.sessions.byId(sessionId)?.unreadCount, 3,
                       "读窗口不该动会话上的未读计数")

        // 已读回执与睡眠唤醒阈值都是全表查询：窗口里没有的早期消息也照样被数到。
        let marked = try temp.stores.messages.markUserMessagesRead(sessionId: sessionId, at: base + 999_999)
        XCTAssertEqual(marked, 65, "130 条里奇数位是助手消息，用户消息共 65 条，全部在整段会话里")
        XCTAssertEqual(try temp.stores.messages.countUserMessages(personaId: personaId, since: base), 65,
                       "睡眠唤醒阈值数的是全表，与窗口无关")
        XCTAssertEqual(try temp.stores.messages.count(sessionId: sessionId), 130)
        XCTAssertEqual(try temp.stores.messages.recent(sessionId: sessionId, limit: 60).count, 60)
    }
}
