import Foundation
import XCTest
@testable import MyFriendKit

/// 聊天页分页的存储层语义（v11：**一页 = 一块**，规范 `spec/messages-blocks/README.md` §3）。
///
/// 钉住四件事：
/// 1. 首屏只读**最新那一块**（不是「最近 60 条」）；
/// 2. 向上 / 向下各加载一块，块与块之间不重不漏，读到最早 / 最新就停；
/// 3. 短块、满块、编号空洞、删除后缩小的块都按块号走，不按偏移量；
/// 4. 未读 / 已读 / 免打扰仍然是全表查询，与窗口无关。
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
                id: String(format: "m-%04d", index),
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index) 句",
                createdAt: sameTimestampForAll ? base : base + Int64(index) * 1000
            )
        }
        try temp.stores.messages.upsert(messages)
        return messages
    }

    // MARK: - 首屏

    /// 首屏 = 最新块。130 条 → 块 0 有 200 条上限，所以只有 1 块，全部读出来。
    func testFirstScreenIsExactlyTheNewestBlock() throws {
        try seed(130)
        XCTAssertEqual(try temp.stores.messages.lastBlockIndex(sessionId: sessionId), 0)

        let window = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        XCTAssertEqual(window.blockIndex, 0)
        XCTAssertEqual(window.messages.count, 130, "一块装得下就一块全给")
        XCTAssertTrue(window.isNewest)
        XCTAssertNil(window.olderBlockIndex)
        XCTAssertNil(window.newerBlockIndex)
    }

    /// 450 条 → 3 块（200/200/50）。首屏只读第 2 块，不碰前面两块。
    func testFirstScreenReadsOnlyTheNewestBlockOfALongSession() throws {
        try seed(450)
        XCTAssertEqual(try temp.stores.messages.lastBlockIndex(sessionId: sessionId), 2)

        var observed: [(method: String, rows: Int)] = []
        temp.stores.messages.queryObserver = { method, rows in observed.append((method, rows)) }
        let window = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        temp.stores.messages.queryObserver = nil

        XCTAssertEqual(window.blockIndex, 2)
        XCTAssertEqual(window.messages.count, 50, "最后一块只有 50 条")
        XCTAssertEqual(window.messages.first?.id, "m-0400")
        XCTAssertEqual(window.messages.last?.id, "m-0449")
        XCTAssertEqual(window.olderBlockIndex, 1)
        XCTAssertNil(window.newerBlockIndex)
        XCTAssertEqual(observed.map(\.method), ["block"], "首屏只读一次块，不扫消息表")
        XCTAssertEqual(observed.first?.rows, 50, "读多少行由块大小决定，与会话总长无关")
    }

    func testEmptySessionHasNoBlock() throws {
        XCTAssertNil(try temp.stores.messages.lastBlockIndex(sessionId: sessionId))
        XCTAssertNil(try temp.stores.messages.blocksAround(sessionId: sessionId))
    }

    // MARK: - 向上加载（更早）

    func testLoadingOlderBlocksWalksBackToTheEarliest() throws {
        try seed(450)
        var window = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        var loaded = window.messages
        XCTAssertEqual(window.olderBlockIndex, 1)

        // 第一页：块 1。
        let older = try XCTUnwrap(try temp.stores.messages.block(1, sessionId: sessionId))
        loaded = MessagePaging.prepend(older.messages, to: loaded)
        XCTAssertEqual(loaded.count, 250)
        XCTAssertEqual(loaded.first?.id, "m-0200", "块 1 的第一条")
        XCTAssertEqual(loaded.last?.id, "m-0449", "窗口末尾不动（滚动锚定）")
        XCTAssertEqual(older.olderBlockIndex, 0)
        XCTAssertEqual(older.newerBlockIndex, 2)

        // 第二页：块 0，然后就没有更早的了。
        let oldest = try XCTUnwrap(try temp.stores.messages.block(0, sessionId: sessionId))
        loaded = MessagePaging.prepend(oldest.messages, to: loaded)
        XCTAssertEqual(loaded.count, 450)
        XCTAssertEqual(loaded.first?.id, "m-0000")
        XCTAssertNil(oldest.olderBlockIndex)
        XCTAssertEqual(try temp.stores.messages.olderBlockIndex(sessionId: sessionId, before: 0), nil)
    }

    // MARK: - 向下加载（更新）

    /// 定位到老块之后，往下翻能一块一块回到最新（v11 新增能力）。
    func testLoadingNewerBlocksWalksForwardToTheNewest() throws {
        try seed(450)
        var window = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId, blockIndex: 0))
        var loaded = window.messages
        XCTAssertEqual(window.blockIndex, 0)
        XCTAssertEqual(window.newerBlockIndex, 1)
        XCTAssertFalse(window.isNewest)

        let middle = try XCTUnwrap(try temp.stores.messages.block(1, sessionId: sessionId))
        loaded = MessagePaging.appendingNewer(middle.messages, to: loaded, cap: .max)
        XCTAssertEqual(loaded.count, 400)
        XCTAssertEqual(loaded.first?.id, "m-0000", "向下加载不该把上面的历史挤掉")
        XCTAssertEqual(loaded.last?.id, "m-0399")
        XCTAssertEqual(middle.newerBlockIndex, 2)

        let newest = try XCTUnwrap(try temp.stores.messages.block(2, sessionId: sessionId))
        loaded = MessagePaging.appendingNewer(newest.messages, to: loaded, cap: .max)
        XCTAssertEqual(loaded.count, 450)
        XCTAssertEqual(loaded.last?.id, "m-0449")
        XCTAssertTrue(newest.isNewest)
        XCTAssertNil(try temp.stores.messages.newerBlockIndex(sessionId: sessionId, after: 2))
    }

    /// 首屏在最新块时没有「向下加载」这回事。
    func testNewestBlockHasNoNewerNeighbour() throws {
        try seed(10)
        let window = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        XCTAssertNil(window.newerBlockIndex)
        XCTAssertNil(try temp.stores.messages.newerBlockIndex(sessionId: sessionId, after: window.blockIndex))
    }

    // MARK: - 短块 / 边界 / 空洞

    func testShortBlockIsAFullPage() throws {
        try seed(3)
        let window = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        XCTAssertEqual(window.messages.count, 3, "短块就是一页，不补齐")
    }

    /// 恰好 200 条：只有 1 块（满块），下一条会开第 2 块（追加规则）。
    func testExactlyBlockSizeStillOneBlock() throws {
        try seed(MessageBlocks.blockSize)
        XCTAssertEqual(try temp.stores.messages.lastBlockIndex(sessionId: sessionId), 0)
        let window = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        XCTAssertEqual(window.messages.count, MessageBlocks.blockSize)
        XCTAssertNil(window.olderBlockIndex)

        try temp.stores.messages.upsert(TestData.message(
            id: "m-next", role: .assistant, content: "第 200 句", createdAt: base + 999_999
        ))
        XCTAssertEqual(try temp.stores.messages.lastBlockIndex(sessionId: sessionId), 1)
        let newest = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        XCTAssertEqual(newest.blockIndex, 1)
        XCTAssertEqual(newest.messages.map(\.id), ["m-next"])
        XCTAssertEqual(newest.olderBlockIndex, 0, "上一块是满的 200 条，不是 1 条")
    }

    /// 中间某一块被删空：块号留洞，`olderBlockIndex` 要跳过洞。
    func testDeletedBlockLeavesGapAndPagingSkipsIt() throws {
        try seed(650) // 块 0/1/2（200/200/200）+ 50 条在块 3
        XCTAssertEqual(try temp.stores.messages.lastBlockIndex(sessionId: sessionId), 3)

        // 把块 2 的 200 条删干净（m-0400…m-0599）。
        try temp.stores.messages.delete(where: "sessionId = ? AND blockIndex = ?", [.text(sessionId), .int(2)])
        XCTAssertNil(try temp.stores.messages.blockIndex(ofMessage: "m-0400"))
        XCTAssertEqual(try temp.stores.messages.lastBlockIndex(sessionId: sessionId), 3, "块号不重排")

        let newest = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        XCTAssertEqual(newest.blockIndex, 3)
        XCTAssertEqual(newest.olderBlockIndex, 1, "跳过被删空的块 2")

        // 请求一个已经不存在的块号：回落到更旧的最近一块，而不是给一片空白。
        let fallback = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId, blockIndex: 2))
        XCTAssertEqual(fallback.blockIndex, 1)
    }

    /// 同一毫秒两条消息：块内按 `(createdAt, id)` 稳定排序，不漏不重。
    func testSameMillisecondIsOrderedByIDInsideTheBlock() throws {
        try seed(4, sameTimestampForAll: true)
        let window = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        XCTAssertEqual(window.messages.map(\.id), ["m-0000", "m-0001", "m-0002", "m-0003"])
    }

    // MARK: - 滚动锚定 / 并发去重

    /// 往上加载一块时，窗口**末尾**（用户正在看的位置）必须原封不动。
    func testPrependKeepsTheScrollAnchor() throws {
        try seed(450)
        let newest = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        let anchor = try XCTUnwrap(newest.messages.last)
        let older = try XCTUnwrap(try temp.stores.messages.block(1, sessionId: sessionId))
        let merged = MessagePaging.prepend(older.messages, to: newest.messages)
        XCTAssertEqual(merged.last?.id, anchor.id, "末尾不跳")
        XCTAssertEqual(merged.first?.id, older.messages.first?.id)
        XCTAssertEqual(merged.map(\.id), merged.map(\.id).sorted(), "整体仍是时间升序")
    }

    /// 同一块被合并两次（`onAppear` 连喊几次 / 重读）：按 id 去重，条数不翻倍。
    func testMergingTheSameBlockTwiceIsIdempotent() throws {
        try seed(450)
        let newest = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        let older = try XCTUnwrap(try temp.stores.messages.block(1, sessionId: sessionId))
        let once = MessagePaging.prepend(older.messages, to: newest.messages)
        let twice = MessagePaging.prepend(older.messages, to: once)
        XCTAssertEqual(twice.map(\.id), once.map(\.id))
        XCTAssertEqual(Set(twice.map(\.id)).count, twice.count, "没有重复 id")
    }

    /// 新消息到达：已加载区间只往后长，早先加载的块一条都不少（0.11.0 P2-5 的块版）。
    func testNewMessagesAppendAndKeepLoadedBlocks() throws {
        try seed(450)
        var window = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        let older = try XCTUnwrap(try temp.stores.messages.block(1, sessionId: sessionId))
        window.messages = MessagePaging.prepend(older.messages, to: window.messages)
        let before = window.messages.map(\.id)

        let fresh = (450..<455).map { index in
            TestData.message(
                id: String(format: "m-%04d", index),
                role: .assistant,
                content: "新消息 \(index)",
                createdAt: base + Int64(index) * 1000
            )
        }
        try temp.stores.messages.upsert(fresh)
        let cursor = try XCTUnwrap(window.messages.last)
        let newer = try temp.stores.messages.pageAfter(
            sessionId: sessionId, createdAt: cursor.createdAt, id: cursor.id, limit: 100
        )
        let merged = MessagePaging.appendingNewer(newer, to: window.messages)
        XCTAssertEqual(Array(merged.prefix(before.count)).map(\.id), before, "已加载的两块原封不动")
        XCTAssertEqual(Array(merged.suffix(5)).map(\.id), fresh.map(\.id))
        // 450 条 = 200 + 200 + 50；再加 5 条还是第 2 块（第三块 55 条），块号不动。
        XCTAssertEqual(try temp.stores.messages.lastBlockIndex(sessionId: sessionId), 2,
                       "455 条还是 3 块（第三块 55 条）")
    }

    /// 新消息开出**新的一块**时，已加载区间的最新块号要跟着推到最后一块。
    func testNewBlockIsReflectedInTheLoadedRange() throws {
        try seed(MessageBlocks.blockSize)
        let window = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        XCTAssertEqual(window.blockIndex, 0)

        try temp.stores.messages.upsert(TestData.message(
            id: "m-new-block", role: .assistant, content: "跨块的新消息", createdAt: base + 900_000
        ))
        XCTAssertEqual(try temp.stores.messages.lastBlockIndex(sessionId: sessionId), 1)
        let last = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        XCTAssertEqual(last.blockIndex, 1)
        XCTAssertEqual(last.messages.map(\.id), ["m-new-block"])
        XCTAssertNil(try temp.stores.messages.newerBlockIndex(sessionId: sessionId, after: last.blockIndex))
    }

    // MARK: - 未读 / 免打扰走全表

    func testUnreadAndWakeCountsIgnoreTheWindow() throws {
        try seed(450)
        // 未读是会话行上的一个计数列，不是「窗口里没读过几条」。
        var session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        session.unreadCount = 3
        try temp.stores.sessions.upsert(session)

        let window = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        XCTAssertEqual(window.messages.count, 50, "首屏只读最新一块")
        XCTAssertEqual(try temp.stores.sessions.byId(sessionId)?.unreadCount, 3,
                       "读窗口不该动会话上的未读计数")

        // 已读回执与睡眠唤醒阈值都是全表查询：窗口里没有的早期消息也照样被数到。
        let marked = try temp.stores.messages.markUserMessagesRead(sessionId: sessionId, at: base + 999_999)
        XCTAssertEqual(marked, 225, "450 条里偶数位是用户消息，共 225 条，全部在整段会话里")
        XCTAssertEqual(
            try temp.stores.messages.countUserMessages(personaId: personaId, since: base),
            225,
            "睡眠唤醒阈值数的是全表，与窗口无关"
        )
        XCTAssertEqual(try temp.stores.messages.count(sessionId: sessionId), 450)
    }
}
