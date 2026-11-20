import Foundation
import XCTest
@testable import YapKit

/// 0.15 服务端多气泡去重（`ServerBubbleFeed`）。
///
/// 这里不测网络、不测数据库：只喂「进来的消息」、断言「哪些该立刻追加」，把
/// 「SSE 与事件流都消费同一条消息」这类时序问题钉成可复现的用例。
final class ServerBubbleFeedTests: XCTestCase {

    private func candidate(
        _ id: String,
        _ createdAt: Int64,
        _ source: ServerBubbleCandidate.Source = .unknown
    ) -> ServerBubbleCandidate {
        ServerBubbleCandidate(id: id, createdAt: createdAt, source: source)
    }

    private func ids(_ candidates: [ServerBubbleCandidate]) -> [String] {
        candidates.map(\.id)
    }

    // MARK: - 顺序与排序

    func testSequentialBatchIsAppendedInOrder() {
        var feed = ServerBubbleFeed()
        let accepted = feed.accept([
            candidate("m-1", 1_000),
            candidate("m-2", 1_001),
            candidate("m-3", 1_002)
        ])
        XCTAssertEqual(ids(accepted), ["m-1", "m-2", "m-3"])
        XCTAssertEqual(feed.latestCreatedAt, 1_002)
        XCTAssertEqual(feed.latestId, "m-3")
    }

    func testBatchIsSortedByCreatedAtThenId() {
        var feed = ServerBubbleFeed()
        let accepted = feed.accept([
            candidate("c", 2_000),
            candidate("a", 1_000),
            candidate("b", 1_000)
        ])
        XCTAssertEqual(ids(accepted), ["a", "b", "c"], "先 createdAt、再 id（a < b）")
    }

    func testSameTimestampSortsStablyById() {
        var feed = ServerBubbleFeed()
        let accepted = feed.accept([
            candidate("z", 5_000),
            candidate("a", 5_000),
            candidate("m", 5_000)
        ])
        XCTAssertEqual(ids(accepted), ["a", "m", "z"])
    }

    // MARK: - 去重（SSE + 事件流）

    func testSameMessageFromSseThenEventStreamAppearsOnce() {
        var feed = ServerBubbleFeed()
        let fromSSE = feed.accept([candidate("m-1", 1_000, .sse)])
        let fromEvents = feed.accept([candidate("m-1", 1_000, .eventStream)])
        XCTAssertEqual(ids(fromSSE), ["m-1"])
        XCTAssertEqual(fromEvents, [], "同一条消息后到的另一条通道不该再追加")
        XCTAssertTrue(feed.hasSeen(id: "m-1"))
    }

    func testSameMessageFromEventStreamThenSseAppearsOnce() {
        var feed = ServerBubbleFeed()
        XCTAssertEqual(ids(feed.accept([candidate("m-1", 1_000, .eventStream)])), ["m-1"])
        XCTAssertEqual(feed.accept([candidate("m-1", 1_000, .sse)]), [])
    }

    func testSameIdTwiceInsideOneBatchAppearsOnce() {
        var feed = ServerBubbleFeed()
        let accepted = feed.accept([
            candidate("m-1", 1_000, .sse),
            candidate("m-1", 1_000, .eventStream)
        ])
        XCTAssertEqual(ids(accepted), ["m-1"])
    }

    func testRepeatedDeliveryDoesNotFlicker() {
        var feed = ServerBubbleFeed()
        XCTAssertEqual(ids(feed.accept([candidate("m-1", 1_000)])), ["m-1"])
        for _ in 0..<5 {
            XCTAssertEqual(feed.accept([candidate("m-1", 1_000)]), [], "重复投递不触发 append（不闪）")
        }
    }

    func testSeededMessageDoesNotAppendAgain() {
        // 进页面 / 整页重读之后，窗口里已有的消息要先登记：再收到同一条时不该 append。
        var feed = ServerBubbleFeed(
            seenIds: ["m-1", "m-2"],
            lastCreatedAt: 2_000,
            lastId: "m-2"
        )
        XCTAssertEqual(feed.accept([candidate("m-1", 1_000)]), [])
        XCTAssertEqual(feed.accept([candidate("m-2", 2_000)]), [])
        XCTAssertTrue(feed.hasSeen(id: "m-2"))
    }

    // MARK: - 乱序 / 更旧

    func testOutOfOrderArrivalIsSortedIntoTheRightOrder() {
        var feed = ServerBubbleFeed()
        XCTAssertEqual(ids(feed.accept([candidate("m-2", 2_000)])), ["m-2"])
        // 更旧的一条晚到：不打扰当前视图（也不该插到 m-2 前面）。
        XCTAssertEqual(feed.accept([candidate("m-1", 1_000)]), [])
        XCTAssertEqual(feed.latestId, "m-2", "游标不被更旧的消息拉回去")
        XCTAssertTrue(feed.hasSeen(id: "m-1"), "更旧的也登记，避免每次投递都重复判定")
    }

    func testOlderMessageDoesNotDisturbCurrentView() {
        var feed = ServerBubbleFeed(seenIds: ["m-9"], lastCreatedAt: 9_000, lastId: "m-9")
        let accepted = feed.accept([
            candidate("m-3", 3_000),
            candidate("m-4", 4_000),
            candidate("m-10", 10_000)
        ])
        XCTAssertEqual(ids(accepted), ["m-10"], "只有严格更新的那条上屏")
    }

    func testMessagesArrivingInMultipleBatchesKeepAppending() {
        var feed = ServerBubbleFeed()
        XCTAssertEqual(ids(feed.accept([candidate("m-1", 1_000)])), ["m-1"])
        XCTAssertEqual(ids(feed.accept([candidate("m-2", 2_000)])), ["m-2"])
        XCTAssertEqual(ids(feed.accept([candidate("m-3", 3_000)])), ["m-3"])
        XCTAssertEqual(feed.latestId, "m-3")
    }

    // MARK: - 坏数据 / 空批

    func testEmptyBatchReturnsEmpty() {
        var feed = ServerBubbleFeed()
        XCTAssertEqual(feed.accept([]), [])
        XCTAssertNil(feed.latestId)
    }

    func testEmptyIdIsIgnored() {
        var feed = ServerBubbleFeed()
        XCTAssertEqual(feed.accept([candidate("", 1_000), candidate("   ", 2_000)]), [])
        XCTAssertFalse(feed.hasSeen(id: ""), "空 id 不占 seen 名额")
        // 同一批里后面那条正常的仍然能上屏。
        XCTAssertEqual(ids(feed.accept([candidate("m-1", 1_000)])), ["m-1"])
    }

    func testWhitespaceAroundIdIsTrimmed() {
        var feed = ServerBubbleFeed()
        XCTAssertEqual(ids(feed.accept([candidate("  m-1  ", 1_000)])), ["m-1"])
        XCTAssertEqual(feed.accept([candidate("m-1", 1_000)]), [], "trim 之后是同一条")
    }

    func testEmptyWindowAcceptsAnythingFirst() {
        // 窗口为空（刚进页面还没读到消息）时，第一条无论时间多早都该上屏。
        var feed = ServerBubbleFeed()
        XCTAssertEqual(ids(feed.accept([candidate("m-1", 0)])), ["m-1"])
        XCTAssertEqual(feed.latestCreatedAt, 0)
    }

    // MARK: - 重置

    func testResetSeedsANewWindow() {
        var feed = ServerBubbleFeed()
        _ = feed.accept([candidate("old", 1_000)])
        feed.reset(seenIds: ["new-1", "new-2"], lastCreatedAt: 5_000, lastId: "new-2")
        XCTAssertEqual(feed.accept([candidate("old", 1_000)]), [])
        XCTAssertEqual(feed.accept([candidate("new-2", 5_000)]), [])
        XCTAssertEqual(ids(feed.accept([candidate("new-3", 6_000)])), ["new-3"])
    }

    // MARK: - 给 ViewModel 用的形状

    func testAcceptedCountMatchesAppendedCountAfterDedup() {
        // 一批里 5 条，其中 2 条重复、1 条更旧：最终只应 append 2 条。
        var feed = ServerBubbleFeed(lastCreatedAt: 1_500, lastId: "m-0")
        let accepted = feed.accept([
            candidate("m-1", 1_000),        // 更旧
            candidate("m-2", 2_000, .sse),
            candidate("m-2", 2_000, .eventStream), // 重复
            candidate("m-3", 3_000, .eventStream),
            candidate("m-3", 3_000, .sse)   // 重复
        ])
        XCTAssertEqual(ids(accepted), ["m-2", "m-3"])
        XCTAssertEqual(feed.latestId, "m-3")
    }
}
