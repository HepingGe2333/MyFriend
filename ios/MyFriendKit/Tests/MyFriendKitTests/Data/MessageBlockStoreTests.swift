import Foundation
import XCTest
@testable import MyFriendKit

/// 块维护的存储语义（规范 `spec/messages-blocks/README.md` §1–§2）。
///
/// 这一层钉的是「**所有写路径都经过 `MessageBlockStore`**」之后的块表长什么样：
/// 追加、锚点插入、删一条、清空、删除后缩小、空块留洞、不重平衡、幂等 upsert……
/// 每条用例最后都用 `inconsistencies()` 核对一遍三条不变量：
/// 消息的块号一定指向存在的块行、块的 `messageCount` 等于实际条数、边界 id 与时间一致。
///
/// 为什么这些必须走真库：`INSERT OR REPLACE` 会把没写的列重置成默认值（`blockIndex` 就是
/// 默认 0 的那一列），这个坑只有真库 + 真 SQL 才暴露得出来。
final class MessageBlockStoreTests: XCTestCase {

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

    private var blocks: MessageBlockStore { temp.stores.messageBlocks }

    @discardableResult
    private func seed(_ count: Int, from start: Int = 0, sameTimestamp: Bool = false) throws -> [MessageEntity] {
        let messages = (start..<(start + count)).map { index in
            TestData.message(
                id: String(format: "m-%04d", index),
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index) 句",
                createdAt: sameTimestamp ? base : base + Int64(index) * 1000
            )
        }
        try temp.stores.messages.upsert(messages)
        return messages
    }

    private func assertConsistent(file: StaticString = #filePath, line: UInt = #line) throws {
        let issues = try blocks.inconsistencies()
        XCTAssertTrue(issues.isEmpty, "块表不自洽：\(issues)", file: file, line: line)
    }

    /// 一次重读：块表 + 由消息反推的块号必须一致（避免只信块行的自述）。
    private func blockSummary(sessionId: String = "session-1") throws -> [String] {
        try blocks.blocks(sessionId: sessionId).map {
            "\($0.blockIndex):\($0.messageCount):\($0.firstMessageId)…\($0.lastMessageId)"
        }
    }

    // MARK: - 追加

    func testAppendCreatesFirstBlockAndKeepsBoundaries() throws {
        try seed(1)
        let block = try XCTUnwrap(try blocks.block(sessionId: sessionId, blockIndex: 0))
        XCTAssertEqual(block.messageCount, 1)
        XCTAssertEqual(block.firstMessageId, "m-0000")
        XCTAssertEqual(block.lastMessageId, "m-0000")
        XCTAssertEqual(block.firstCreatedAt, base)
        XCTAssertEqual(block.lastCreatedAt, base)
        try assertConsistent()
    }

    func testAppendFillsBlockThenOpensTheNextOne() throws {
        try seed(MessageBlocks.blockSize)
        XCTAssertEqual(try blocks.blockCount(sessionId: sessionId), 1)
        XCTAssertEqual(try XCTUnwrap(try blocks.lastBlock(sessionId: sessionId)).messageCount, MessageBlocks.blockSize)

        try seed(1, from: MessageBlocks.blockSize)
        XCTAssertEqual(try blocks.blockCount(sessionId: sessionId), 2)
        let second = try XCTUnwrap(try blocks.block(sessionId: sessionId, blockIndex: 1))
        XCTAssertEqual(second.messageCount, 1)
        XCTAssertEqual(second.firstMessageId, "m-0200")
        XCTAssertEqual(second.lastMessageId, "m-0200")
        try assertConsistent()
    }

    /// 追加只动 `last…`，`first…` 不变（规范 §2 第 3 条）。
    func testAppendOnlyMovesTheLastBoundary() throws {
        try seed(3)
        let before = try XCTUnwrap(try blocks.block(sessionId: sessionId, blockIndex: 0))
        try seed(1, from: 3)
        let after = try XCTUnwrap(try blocks.block(sessionId: sessionId, blockIndex: 0))
        XCTAssertEqual(after.firstMessageId, before.firstMessageId)
        XCTAssertEqual(after.firstCreatedAt, before.firstCreatedAt)
        XCTAssertEqual(after.lastMessageId, "m-0003")
        XCTAssertEqual(after.messageCount, 4)
        try assertConsistent()
    }

    /// 同一毫秒两条：追加时 `last…` 按 `(createdAt, id)` 判定，边界取 id 更大的那条。
    func testAppendSameTimestampUsesIDForTheBoundary() throws {
        try seed(3, sameTimestamp: true)
        let block = try XCTUnwrap(try blocks.block(sessionId: sessionId, blockIndex: 0))
        XCTAssertEqual(block.lastMessageId, "m-0002", "同一毫秒里 id 更大的排在后面")
        try assertConsistent()
    }

    // MARK: - 锚点插入（编辑用户消息）

    func testInsertAtAnchorLandsInTheAnchorBlockAndGrowsIt() throws {
        try seed(3)
        let edited = TestData.message(
            id: "m-edited", role: .user, content: "改过的那句", createdAt: base + 100_000
        )
        XCTAssertTrue(try blocks.insert(edited, atAnchor: "m-0001"))
        let block = try XCTUnwrap(try blocks.block(sessionId: sessionId, blockIndex: 0))
        XCTAssertEqual(block.messageCount, 4)
        XCTAssertEqual(block.lastMessageId, "m-edited", "新消息更晚，边界跟着改")
        XCTAssertEqual(block.firstMessageId, "m-0000", "first… 不动")
        XCTAssertEqual(try temp.stores.messages.byId("m-edited")?.blockIndex, 0)
        try assertConsistent()
    }

    /// 通过 `MessageStore` 的公开入口也能做锚点插入（编辑用户消息的写路径接线）。
    func testMessageStoreExposesAnchorInsert() throws {
        try seed(3)
        let edited = TestData.message(
            id: "m-edited", role: .user, content: "改过的那句", createdAt: base + 100_000
        )
        XCTAssertTrue(try temp.stores.messages.insert(edited, atAnchor: "m-0002"))
        XCTAssertEqual(try temp.stores.messages.byId("m-edited")?.blockIndex, 0)
        XCTAssertEqual(try blocks.block(sessionId: sessionId, blockIndex: 0)?.messageCount, 4)
        try assertConsistent()
    }

    /// 锚点插入到**饱和的块**也不开新块、不重平衡（规范 §2：块可以超、只按锚点走）。
    func testAnchorInsertDoesNotRebalanceAFullBlock() throws {
        try seed(MessageBlocks.blockSize)
        let edited = TestData.message(
            id: "m-edited", role: .user, content: "改过的那句", createdAt: base + 100_000
        )
        XCTAssertTrue(try blocks.insert(edited, atAnchor: "m-0005"))
        let block = try XCTUnwrap(try blocks.block(sessionId: sessionId, blockIndex: 0))
        XCTAssertEqual(block.messageCount, MessageBlocks.blockSize + 1)
        XCTAssertEqual(try blocks.blockCount(sessionId: sessionId), 1, "不因为超了 200 就切块")
        // 规范：块可以超、不重平衡；审计只查「元数据与消息是否对得上」。
        try assertConsistent()
    }

    /// 锚点已经不存在：插入失败，消息不落库（编辑一条被删掉的消息）。
    func testInsertAtMissingAnchorFailsAndWritesNothing() throws {
        try seed(2)
        let edited = TestData.message(id: "m-edited", role: .user, content: "改过", createdAt: base + 1)
        XCTAssertFalse(try blocks.insert(edited, atAnchor: "m-gone"))
        XCTAssertNil(try temp.stores.messages.byId("m-edited"))
        try assertConsistent()
    }

    // MARK: - 删除

    func testDeletingAMessageRecomputesBoundaries() throws {
        try seed(5)
        try temp.stores.messages.delete(id: "m-0000")
        var block = try XCTUnwrap(try blocks.block(sessionId: sessionId, blockIndex: 0))
        XCTAssertEqual(block.messageCount, 4)
        XCTAssertEqual(block.firstMessageId, "m-0001", "first… 从剩下的行重算")
        XCTAssertEqual(block.firstCreatedAt, base + 1000)

        try temp.stores.messages.delete(id: "m-0004")
        block = try XCTUnwrap(try blocks.block(sessionId: sessionId, blockIndex: 0))
        XCTAssertEqual(block.lastMessageId, "m-0003")
        XCTAssertEqual(block.messageCount, 3)
        try assertConsistent()
    }

    /// 块被删空：块行消失、编号留洞、后面的块**不重排**。
    func testEmptyBlockIsDeletedAndNumberingIsNotRebalanced() throws {
        try seed(450) // 块 0/1 各 200，块 2 有 50
        XCTAssertEqual(try blocks.blocks(sessionId: sessionId).map(\.blockIndex), [0, 1, 2])

        try temp.stores.messages.delete(where: "sessionId = ? AND blockIndex = ?", [.text(sessionId), .int(1)])
        XCTAssertEqual(try blocks.blocks(sessionId: sessionId).map(\.blockIndex), [0, 2],
                       "块 1 删空 → 行消失，块 2 仍是 2（不往前挪）")
        XCTAssertEqual(try XCTUnwrap(try blocks.block(sessionId: sessionId, blockIndex: 2)).messageCount, 50)
        try assertConsistent()
    }

    /// 删掉一部分：块缩小，但不与相邻块合并、也不补齐。
    func testShrinkingBlocksAreNotRebalanced() throws {
        try seed(450) // 块 0/1 各 200，块 2 有 50
        // 从块 1 删掉 190 条（m-0200…m-0389），剩 10 条。
        try temp.stores.messages.delete(
            where: "sessionId = ? AND blockIndex = ? AND id < ?",
            [.text(sessionId), .int(1), .text("m-0390")]
        )
        let summary = try blocks.blocks(sessionId: sessionId)
        XCTAssertEqual(summary.map(\.blockIndex), [0, 1, 2])
        XCTAssertEqual(summary.map(\.messageCount), [200, 10, 50], "块只会变小，不会被合并或补齐")
        XCTAssertEqual(summary[1].firstMessageId, "m-0390", "删除后 first… 从剩下的行重算")
        try assertConsistent()
    }

    // MARK: - 其它写路径

    /// 「已读回执」会逐条 `upsert` 同一批消息：块号不能因为 `INSERT OR REPLACE` 被重置成 0。
    func testUpsertingAnExistingMessageKeepsItsBlock() throws {
        try seed(450)
        let target = try XCTUnwrap(try temp.stores.messages.byId("m-0300"))
        XCTAssertEqual(target.blockIndex, 1)

        var updated = target
        updated.readAt = base + 999_999
        updated.updatedAt = base + 999_999
        try temp.stores.messages.upsert(updated)

        let reloaded = try XCTUnwrap(try temp.stores.messages.byId("m-0300"))
        XCTAssertEqual(reloaded.blockIndex, 1, "upsert 一条已存在的消息不该把它挪到块 0")
        XCTAssertEqual(reloaded.readAt, updated.readAt)
        try assertConsistent()
    }

    /// 合并导入改了同 id 消息的时间戳：块边界要跟着重算。
    func testUpsertingAnExistingMessageWithNewTimestampRefreshesBoundaries() throws {
        try seed(3)
        let last = try XCTUnwrap(try temp.stores.messages.byId("m-0002"))
        var moved = last
        moved.createdAt = base - 10_000 // 变成最早的一条
        try temp.stores.messages.upsert(moved)

        let block = try XCTUnwrap(try blocks.block(sessionId: sessionId, blockIndex: 0))
        XCTAssertEqual(block.firstMessageId, "m-0002", "时间改了，边界按新的 (createdAt, id) 序")
        XCTAssertEqual(block.lastMessageId, "m-0001")
        try assertConsistent()
    }

    /// 清空上下文 / 删人格走的是 `delete(where:)`：消息与块一起没。
    func testDeleteBySessionClearsBlocks() throws {
        try seed(450)
        try temp.stores.messages.delete(where: "sessionId = ?", [.text(sessionId)])
        XCTAssertEqual(try temp.stores.messages.count(), 0)
        XCTAssertEqual(try blocks.blockCount(sessionId: sessionId), 0)
    }

    func testDeleteAllClearsBlocks() throws {
        try seed(450)
        try temp.stores.messages.deleteAll()
        XCTAssertEqual(try temp.stores.messages.count(), 0)
        XCTAssertEqual(try blocks.count(), 0, "块表也要清")
    }

    /// 删会话：消息与块一起走（`ChatSessionStore` 的级联，v11 加）。
    func testDeletingASessionClearsItsMessagesAndBlocks() throws {
        try seed(450)
        try temp.stores.sessions.delete(id: sessionId)
        XCTAssertNil(try temp.stores.sessions.byId(sessionId))
        XCTAssertEqual(try temp.stores.messages.count(sessionId: sessionId), 0)
        XCTAssertEqual(try blocks.count(), 0)
        XCTAssertTrue(try blocks.inconsistencies().isEmpty)
    }

    // MARK: - 多会话隔离 / 幂等

    func testBlocksAreIsolatedPerSession() throws {
        try temp.stores.sessions.upsert(TestData.session(id: "session-2", personaId: "persona-2"))
        try seed(3)
        let other = (0..<3).map { index in
            TestData.message(
                id: "s2-m\(index)", sessionId: "session-2", personaId: "persona-2",
                role: .user, content: "另一段 \(index)", createdAt: base + Int64(index) * 1000
            )
        }
        try temp.stores.messages.upsert(other)

        XCTAssertEqual(try blocks.blockCount(sessionId: sessionId), 1)
        XCTAssertEqual(try blocks.blockCount(sessionId: "session-2"), 1)
        XCTAssertEqual(try temp.stores.messages.byId("s2-m1")?.blockIndex, 0)
        XCTAssertEqual(try blocks.block(sessionId: sessionId, blockIndex: 0)?.messageCount, 3)
        try assertConsistent()
    }

    /// 反复 upsert 同一条新消息：不会把块撑大两次（append 只对「真的新行」生效）。
    func testRepeatedUpsertOfANewMessageIsIdempotent() throws {
        try seed(2)
        let extra = TestData.message(
            id: "m-extra", role: .user, content: "重复写", createdAt: base + 50_000
        )
        try temp.stores.messages.upsert(extra)
        try temp.stores.messages.upsert(extra)
        let block = try XCTUnwrap(try blocks.block(sessionId: sessionId, blockIndex: 0))
        XCTAssertEqual(block.messageCount, 3)
        XCTAssertEqual(try temp.stores.messages.count(), 3)
        try assertConsistent()
    }

    /// `removeOrphanBlocks`：会话已经不在的块行与空块行都要清掉（迁移与替换导入的收尾）。
    func testRemoveOrphanBlocksDropsBlocksOfDeletedSessions() throws {
        try seed(3)
        // 绕过仓储的级联，直接删会话与消息（模拟老库里的历史脏数据：只剩块行）。
        try temp.stores.database.execute("DELETE FROM chat_sessions")
        try temp.stores.database.execute("DELETE FROM messages")
        XCTAssertEqual(try blocks.count(), 1, "先确认块行还在")
        let issues = try blocks.inconsistencies()
        XCTAssertTrue(issues.contains { $0.contains("孤儿块") }, issues.description)

        XCTAssertEqual(try blocks.removeOrphanBlocks(), 1)
        XCTAssertEqual(try blocks.count(), 0)
        XCTAssertTrue(try blocks.inconsistencies().isEmpty)
    }

    /// 自洽审计本身要能发现坏数据（否则上面那些 `assertConsistent` 都是空断言）。
    func testAuditDetectsBrokenMetadata() throws {
        try seed(5)
        try temp.stores.database.execute(
            "UPDATE message_blocks SET messageCount = 99, lastMessageId = 'ghost' WHERE blockIndex = 0"
        )
        let issues = try blocks.inconsistencies()
        XCTAssertTrue(issues.contains { $0.contains("messageCount=99") }, issues.description)
        XCTAssertTrue(issues.contains { $0.contains("lastMessageId 在消息表里不存在") }, issues.description)
    }

    // MARK: - 纯规则

    func testAppendAssignmentRules() {
        XCTAssertEqual(
            MessageBlocks.appendAssignment(lastBlockIndex: nil, lastBlockMessageCount: 0),
            MessageBlocks.Assignment(blockIndex: 0, createsBlock: true)
        )
        XCTAssertEqual(
            MessageBlocks.appendAssignment(lastBlockIndex: 3, lastBlockMessageCount: 0),
            MessageBlocks.Assignment(blockIndex: 3, createsBlock: false)
        )
        XCTAssertEqual(
            MessageBlocks.appendAssignment(lastBlockIndex: 3, lastBlockMessageCount: MessageBlocks.blockSize - 1),
            MessageBlocks.Assignment(blockIndex: 3, createsBlock: false)
        )
        XCTAssertEqual(
            MessageBlocks.appendAssignment(lastBlockIndex: 3, lastBlockMessageCount: MessageBlocks.blockSize),
            MessageBlocks.Assignment(blockIndex: 4, createsBlock: true)
        )
    }

    func testBackfillIndexMatchesBlockSize() {
        XCTAssertEqual(MessageBlocks.backfillBlockIndex(rowNumber: 1), 0)
        XCTAssertEqual(MessageBlocks.backfillBlockIndex(rowNumber: 200), 0)
        XCTAssertEqual(MessageBlocks.backfillBlockIndex(rowNumber: 201), 1)
        XCTAssertEqual(MessageBlocks.backfillBlockIndex(rowNumber: 400), 1)
        XCTAssertEqual(MessageBlocks.backfillBlockIndex(rowNumber: 401), 2)
    }

    func testMetadataPrefersEarliestAndLatestByTimestampThenID() {
        let sameTime = [
            TestData.message(id: "b", role: .user, content: "", createdAt: 10),
            TestData.message(id: "a", role: .user, content: "", createdAt: 10),
            TestData.message(id: "c", role: .user, content: "", createdAt: 20)
        ]
        let metadata = MessageBlocks.metadata(sessionId: "s", blockIndex: 0, messages: sameTime)
        XCTAssertEqual(metadata?.firstMessageId, "a")
        XCTAssertEqual(metadata?.lastMessageId, "c")
        XCTAssertEqual(metadata?.messageCount, 3)
        XCTAssertNil(MessageBlocks.metadata(sessionId: "s", blockIndex: 0, messages: []))
    }

    func testAnchoredOnlyMovesLastWhenTheNewMessageSortsAfter() {
        let block = MessageBlockEntity(
            sessionId: "s", blockIndex: 0, firstMessageId: "a", lastMessageId: "b",
            firstCreatedAt: 1, lastCreatedAt: 2, messageCount: 2
        )
        let later = TestData.message(id: "c", role: .user, content: "", createdAt: 3)
        let earlier = TestData.message(id: "z", role: .user, content: "", createdAt: 0)

        let afterLater = MessageBlocks.anchored(block, with: later)
        XCTAssertEqual(afterLater.lastMessageId, "c")
        XCTAssertEqual(afterLater.messageCount, 3)

        let afterEarlier = MessageBlocks.anchored(block, with: earlier)
        XCTAssertEqual(afterEarlier.lastMessageId, "b", "排在旧 last 之前就不动 last…")
        XCTAssertEqual(afterEarlier.messageCount, 3)
    }
}
