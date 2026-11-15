import Foundation
import XCTest
@testable import MyFriendKit

/// 「从搜索结果跳进会话并定位到那条消息」的纯逻辑 + 真实库集成。
///
/// v11 起定位不再是「60 条一页地往前翻」：消息有块号，直接查目标落在第几块，
/// 只读那一块（再往前带一块上下文）—— 读多少行与目标有多久远无关。
/// 这段逻辑在 `MessageBlocks.focus` 里（不碰数据库，取块由调用方给），
/// 所以「目标块不存在」「上一块被删空（编号空洞）」「块表被写坏」这些边界都能在这里跑完。
final class MessageFocusTests: XCTestCase {

    private func messages(_ range: Range<Int>) -> [MessageEntity] {
        range.map { index in
            TestData.message(
                id: String(format: "m-%04d", index),
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index) 句",
                createdAt: 1_794_500_000_000 + Int64(index) * 1_000
            )
        }
    }

    /// 假块表：块号 → 消息。
    private func loader(_ blocks: [Int: [MessageEntity]]) -> (Int) -> [MessageEntity]? {
        { blocks[$0] }
    }

    // MARK: - 纯逻辑

    func testLoadsTargetBlockAndOneBlockOfContext() {
        let blocks = [0: messages(0..<200), 1: messages(200..<400), 2: messages(400..<410)]
        let result = MessageBlocks.focus(targetMessageId: "m-0205", targetBlock: 1, loadBlock: loader(blocks))

        XCTAssertTrue(result.found)
        XCTAssertEqual(result.blocksLoaded, 2, "目标块 + 往前一块上下文")
        XCTAssertEqual(result.lowerBlockIndex, 0)
        XCTAssertEqual(result.upperBlockIndex, 1)
        XCTAssertEqual(result.messages.first?.id, "m-0000")
        XCTAssertEqual(result.messages.last?.id, "m-0399")
        XCTAssertEqual(result.messages.count, 400)
        XCTAssertEqual(result.messages.map(\.id), result.messages.map(\.id).sorted(), "块号升序拼起来")
    }

    func testNewestBlockNeedsNoContext() {
        let blocks = [7: messages(0..<3)]
        let result = MessageBlocks.focus(targetMessageId: "m-0001", targetBlock: 7, loadBlock: loader(blocks))
        XCTAssertTrue(result.found)
        XCTAssertEqual(result.blocksLoaded, 1)
        XCTAssertEqual(result.lowerBlockIndex, 7)
        XCTAssertEqual(result.upperBlockIndex, 7)
        XCTAssertEqual(result.messages.count, 3)
    }

    /// 目标块不存在（消息被删掉了 / 块被删空了）：找不到，但**不崩**、也不去读别的块。
    func testMissingTargetBlockReportsNotFound() {
        var readIndexes: [Int] = []
        let result = MessageBlocks.focus(targetMessageId: "ghost", targetBlock: 5) { index in
            readIndexes.append(index)
            return nil
        }
        XCTAssertFalse(result.found)
        XCTAssertTrue(result.messages.isEmpty)
        XCTAssertEqual(result.blocksLoaded, 0)
        XCTAssertEqual(result.lowerBlockIndex, 5)
        XCTAssertEqual(result.upperBlockIndex, 5)
        XCTAssertEqual(readIndexes.first, 5)
        XCTAssertLessThanOrEqual(readIndexes.count, 1 + MessageBlocks.maxGapScan + 1, "不能无限往前扫")
    }

    /// 块号有空洞（前面那块被删空了）：跳过空洞继续往前找到最近一块真实存在的块。
    func testGapInBlockNumbersIsSkipped() {
        let blocks = [3: messages(0..<40), 9: messages(400..<440)]
        let result = MessageBlocks.focus(targetMessageId: "m-0400", targetBlock: 9, loadBlock: loader(blocks))
        XCTAssertTrue(result.found)
        XCTAssertEqual(result.blocksLoaded, 2)
        XCTAssertEqual(result.lowerBlockIndex, 3, "跳过空洞，取真正存在的上一块")
        XCTAssertEqual(result.upperBlockIndex, 9)
    }

    /// 块表被写坏（全是空洞）：扫到上限就停，不变成死循环。
    func testAllGapsStopsAtScanLimit() {
        var reads = 0
        let result = MessageBlocks.focus(targetMessageId: "m-0000", targetBlock: 10_000) { _ in
            reads += 1
            return nil
        }
        XCTAssertFalse(result.found)
        XCTAssertLessThanOrEqual(reads, 2 + MessageBlocks.maxGapScan)
    }

    /// 上一块返回空数组（块存在但没有消息 —— 正常不会出现）时停手，不再往前读。
    func testEmptyOlderBlockStopsLoading() {
        var readIndexes: [Int] = []
        let blocks = [4: messages(0..<5)]
        let result = MessageBlocks.focus(targetMessageId: "m-0002", targetBlock: 4) { index in
            readIndexes.append(index)
            if index == 4 { return blocks[4] }
            return []
        }
        XCTAssertTrue(result.found)
        XCTAssertEqual(result.blocksLoaded, 1)
        XCTAssertEqual(readIndexes, [4, 3])
    }

    func testTargetMessageNotInItsBlockIsNotFound() {
        let blocks = [0: messages(0..<10)]
        let result = MessageBlocks.focus(targetMessageId: "m-deleted", targetBlock: 0, loadBlock: loader(blocks))
        XCTAssertFalse(result.found)
        XCTAssertEqual(result.messages.count, 10, "窗口仍然把能读到的块读出来")
    }

    // MARK: - 真实库集成

    func testFocusFindsOldMessageInALongConversation() throws {
        let temp = try TempDatabase()
        defer { temp.cleanUp() }
        let sessionId = "session-1"
        try temp.stores.personas.upsert(TestData.persona(id: "persona-1"))
        try temp.stores.sessions.upsert(TestData.session(id: sessionId, personaId: "persona-1"))
        let all = (0..<450).map { index in
            TestData.message(
                id: String(format: "m-%03d", index),
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index) 句",
                createdAt: 1_794_500_000_000 + Int64(index) * 1_000
            )
        }
        try temp.stores.messages.upsert(all)

        // 首屏只有最新那一块：m-400…m-449。
        let firstScreen = try XCTUnwrap(try temp.stores.messages.blocksAround(sessionId: sessionId))
        XCTAssertEqual(firstScreen.blockIndex, 2)
        XCTAssertEqual(firstScreen.messages.first?.id, "m-400")

        // 目标是第 1 块里的老消息：只读目标块 + 它前面一块。
        let targetBlock = try XCTUnwrap(try temp.stores.messages.blockIndex(ofMessage: "m-205"))
        XCTAssertEqual(targetBlock, 1)
        let result = MessageBlocks.focus(targetMessageId: "m-205", targetBlock: targetBlock) { index in
            (try? temp.stores.messages.block(index, sessionId: sessionId))?.messages
        }
        XCTAssertTrue(result.found)
        XCTAssertEqual(result.lowerBlockIndex, 0)
        XCTAssertEqual(result.upperBlockIndex, 1)
        XCTAssertEqual(result.messages.count, 400, "两块 = 400 条，与「目标在第三十几块」无关")
        XCTAssertEqual(result.messages.first?.id, "m-000")
        XCTAssertEqual(result.messages.last?.id, "m-399")

        // 定位之后「加载更早 / 加载更新」的状态都能从块号区间算出来。
        XCTAssertNil(try temp.stores.messages.olderBlockIndex(sessionId: sessionId, before: 0))
        XCTAssertEqual(try temp.stores.messages.newerBlockIndex(sessionId: sessionId, after: 1), 2)
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
        XCTAssertNil(try temp.stores.messages.blockIndex(ofMessage: "m-deleted"),
                     "被删掉的消息查不到块号 —— 界面据此提示「没找到」")
    }
}
