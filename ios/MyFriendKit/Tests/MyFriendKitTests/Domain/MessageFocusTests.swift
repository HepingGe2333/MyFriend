import Foundation
import XCTest
@testable import MyFriendKit

/// 「从搜索结果跳进会话并定位到那条消息」的纯逻辑 + 真实库集成。
///
/// 0.10.1 的分页只解决「首屏 60 条 + 上滑加载更早」；定位多了一步：
/// 目标可能在很前面，得**一页页往前取，直到它进入窗口**。这段循环放在
/// `MessagePaging.window(reaching:)` 里（不碰数据库，取页由调用方给），
/// 所以「目标不存在」「加载器不给新页」这些边界都能在这里跑完。
final class MessageFocusTests: XCTestCase {

    private func messages(_ range: Range<Int>) -> [MessageEntity] {
        range.map { index in
            TestData.message(
                id: String(format: "m-%03d", index),
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index) 句",
                createdAt: 1_794_500_000_000 + Int64(index) * 1_000
            )
        }
    }

    // MARK: - 纯逻辑

    func testTargetAlreadyInsideWindowLoadsNothing() {
        let start = messages(60..<120)
        var calls = 0
        let result = MessagePaging.window(reaching: "m-090", from: start) { _, _ in
            calls += 1
            return messages(0..<60)
        }
        XCTAssertTrue(result.found)
        XCTAssertEqual(result.pagesLoaded, 0)
        XCTAssertEqual(calls, 0, "首屏就有目标时不该再读库")
        XCTAssertEqual(result.messages.count, 60)
    }

    func testLoadsPageByPageUntilTargetAppears() {
        let start = messages(60..<120)
        var page = 0
        let result = MessagePaging.window(reaching: "m-005", from: start) { _, _ in
            defer { page += 1 }
            // 每页 30 条更早的消息，一直退到最早。
            let upper = 60 - page * 30
            guard upper > 0 else { return nil }
            return messages(max(0, upper - 30)..<upper)
        }
        XCTAssertTrue(result.found)
        XCTAssertEqual(result.messages.first?.id, "m-000", "一直翻到最早那条")
        XCTAssertEqual(result.messages.last?.id, "m-119", "窗口末尾不该被定位操作改掉")
        XCTAssertEqual(result.messages.count, 120)
        XCTAssertEqual(result.messages.map(\.id), result.messages.map(\.id).sorted(), "窗口保持时间升序")
        XCTAssertEqual(result.pagesLoaded, 2)
        XCTAssertFalse(result.reachedEarliest)
    }

    func testMissingTargetStopsWhenNoMoreHistory() {
        let start = messages(60..<120)
        var page = 0
        let result = MessagePaging.window(reaching: "ghost", from: start) { _, _ in
            defer { page += 1 }
            let upper = 60 - page * 60
            return upper > 0 ? messages(0..<upper) : nil
        }
        XCTAssertFalse(result.found)
        XCTAssertTrue(result.reachedEarliest, "翻到没有更早的消息才算「它真的不在库里」")
        XCTAssertEqual(result.messages.count, 120)
    }

    func testMaxPagesGuardsAgainstEndlessLoading() {
        let start = messages(1_000..<1_060)
        let result = MessagePaging.window(reaching: "ghost", from: start, maxPages: 3) { _, page in
            // 永远给得出新的一页（模拟一个很大的库），但永远不含目标。
            let upper = 1_000 - page * 60
            return messages(max(0, upper - 60)..<upper)
        }
        XCTAssertFalse(result.found)
        XCTAssertEqual(result.pagesLoaded, 3, "上限保护住「点一下卡几秒」")
        XCTAssertFalse(result.reachedEarliest)
    }

    func testEmptyLoaderStopsImmediately() {
        let start = messages(60..<120)
        let result = MessagePaging.window(reaching: "ghost", from: start) { _, _ in [] }
        XCTAssertFalse(result.found)
        XCTAssertTrue(result.reachedEarliest)
        XCTAssertEqual(result.messages.count, 60)
    }

    /// 加载器一直返回同一页（游标没往前走）：合并后条数不增长就该停，不能死循环。
    func testRepeatedPageStopsInsteadOfLooping() {
        let start = messages(60..<120)
        let result = MessagePaging.window(reaching: "ghost", from: start) { _, _ in messages(60..<120) }
        XCTAssertFalse(result.found)
        XCTAssertEqual(result.pagesLoaded, 1)
        XCTAssertEqual(result.messages.count, 60)
    }

    func testEmptyTargetIdIsANoOp() {
        let start = messages(60..<120)
        let result = MessagePaging.window(reaching: "", from: start) { _, _ in
            XCTFail("空 id 不该去读库")
            return nil
        }
        XCTAssertFalse(result.found)
        XCTAssertEqual(result.pagesLoaded, 0)
    }

    // MARK: - 真实库集成（与 0.10.1 的分页复用同一条游标）

    func testFocusFindsOldMessageInALongConversation() throws {
        let temp = try TempDatabase()
        defer { temp.cleanUp() }
        let sessionId = "session-1"
        try temp.stores.personas.upsert(TestData.persona(id: "persona-1"))
        try temp.stores.sessions.upsert(TestData.session(id: sessionId, personaId: "persona-1"))
        let all = (0..<130).map { index in
            TestData.message(
                id: String(format: "m-%03d", index),
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index) 句",
                createdAt: 1_794_500_000_000 + Int64(index) * 1_000
            )
        }
        try temp.stores.messages.upsert(all)

        // 首屏：最近 60 条（与聊天页一致）。
        let firstScreen = try temp.stores.messages.recent(
            sessionId: sessionId, limit: MessagePaging.pageSize
        )
        XCTAssertEqual(firstScreen.first?.id, "m-070")

        let result = MessagePaging.window(reaching: "m-005", from: firstScreen) { oldest, _ in
            guard let oldest else { return nil }
            return try? temp.stores.messages.pageBefore(
                sessionId: sessionId,
                createdAt: oldest.createdAt,
                id: oldest.id,
                limit: MessagePaging.pageSize
            )
        }
        XCTAssertTrue(result.found)
        // 目标在第三页之后：往前取到「m-000…m-009」那一页时它才进入窗口，
        // 所以窗口前面也一并读到了最早一条（这比「读到目标就停」多一页，但少一次边界判断）。
        XCTAssertEqual(result.pagesLoaded, 2)
        XCTAssertEqual(result.messages.first?.id, "m-000")
        XCTAssertTrue(result.messages.contains { $0.id == "m-005" })
        XCTAssertEqual(result.messages.count, 130)

        // 定位之后「加载更早」的状态要跟着更新：窗口前面已经没有更早的消息了。
        let oldest = try XCTUnwrap(result.messages.first)
        XCTAssertEqual(oldest.id, "m-000")
        XCTAssertEqual(
            try temp.stores.messages.countBefore(
                sessionId: sessionId, createdAt: oldest.createdAt, id: oldest.id
            ),
            0
        )
    }

    func testFocusOnDeletedMessageReportsNotFound() throws {
        let temp = try TempDatabase()
        defer { temp.cleanUp() }
        let sessionId = "session-1"
        try temp.stores.personas.upsert(TestData.persona(id: "persona-1"))
        try temp.stores.sessions.upsert(TestData.session(id: sessionId, personaId: "persona-1"))
        try temp.stores.messages.upsert((0..<10).map { index in
            TestData.message(
                id: String(format: "m-%03d", index),
                role: .user,
                content: "第 \(index) 句",
                createdAt: 1_794_500_000_000 + Int64(index) * 1_000
            )
        })
        let firstScreen = try temp.stores.messages.recent(sessionId: sessionId, limit: 5)
        let result = MessagePaging.window(reaching: "m-deleted", from: firstScreen) { oldest, _ in
            guard let oldest else { return nil }
            return try? temp.stores.messages.pageBefore(
                sessionId: sessionId,
                createdAt: oldest.createdAt,
                id: oldest.id,
                limit: 5
            )
        }
        XCTAssertFalse(result.found)
        XCTAssertEqual(result.messages.count, 10, "窗口仍然把它能读到的都读出来")
    }
}
