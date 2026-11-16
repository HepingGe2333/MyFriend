import Foundation
import XCTest
@testable import YapKit

/// 检索的「逐块 + 无候选上限」语义（规范 `spec/messages-blocks/README.md` §4）。
///
/// v10 的检索是「一条 `LIKE` 粗筛取最近 500 条 → 内存精排」，超过 500 条命中时更旧的高分
/// 结果会被悄悄截掉。v11 改成：**按块走完整个历史**，每块读进来精排、只保留游标之后的
/// 前 `limit + 1` 条。这一层钉四件事：
/// 1. 跨块命中（第 1 块与很后面的块都能被翻到）；
/// 2. > 500 条命中可以一页页翻完，不丢不重（旧的 500 上限已经不存在）；
/// 3. 人格 / 时间过滤跨块生效；
/// 4. **每条查询只读一块**（用 `queryObserver` 数行数），翻页期间新增消息不破坏稳定性。
final class MessageSearchBlocksTests: XCTestCase {

    private var temp: TempDatabase!
    private let base: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        try temp.stores.personas.upsert(TestData.persona(id: "persona-1", name: "林岸"))
        try temp.stores.personas.upsert(TestData.persona(id: "persona-2", name: "阿澈", isDefault: false))
        try temp.stores.sessions.upsert(TestData.session(id: "session-1", personaId: "persona-1"))
        try temp.stores.sessions.upsert(TestData.session(id: "session-2", personaId: "persona-2"))
    }

    override func tearDownWithError() throws {
        temp.stores.messages.queryObserver = nil
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    @discardableResult
    private func insert(
        _ id: String,
        _ content: String,
        at offset: Int64,
        sessionId: String = "session-1",
        personaId: String = "persona-1"
    ) throws -> MessageEntity {
        let message = TestData.message(
            id: id,
            sessionId: sessionId,
            personaId: personaId,
            role: .user,
            content: content,
            createdAt: base + offset
        )
        try temp.stores.messages.upsert(message)
        return message
    }

    // MARK: - 跨块命中

    /// 第 1 块里的一条与很后面那一块里的一条都必须能被搜到（逐块走完才有的能力）。
    func testHitsInTheFirstAndInALaterBlockAreBothFound() throws {
        // 420 条 → 3 块（200/200/20）。两条命中的正文完全一样，顺序只由 createdAt 决定。
        for index in 0..<420 {
            let content = index == 5 || index == 410 ? "哈尔滨的雪很大" : "普通的一句 \(index)"
            try insert(String(format: "m-%03d", index), content, at: Int64(index) * 1_000)
        }
        XCTAssertEqual(try temp.stores.messages.lastBlockIndex(sessionId: "session-1"), 2)
        XCTAssertEqual(try temp.stores.messages.blockIndex(ofMessage: "m-005"), 0)
        XCTAssertEqual(try temp.stores.messages.blockIndex(ofMessage: "m-410"), 2)

        let page = try temp.stores.messages.search(query: "哈尔滨", limit: 10)
        XCTAssertEqual(page.hits.map(\.id), ["m-410", "m-005"], "最近的在前（分值相同按 createdAt 降序）")
    }

    /// 命中分布在三块里：分页时跨块拼接，顺序与一次排完完全一致。
    func testPagingStitchesHitsAcrossBlocks() throws {
        for index in 0..<420 {
            let content = index % 7 == 0 ? "哈尔滨 \(index)" : "别的 \(index)"
            try insert(String(format: "m-%03d", index), content, at: Int64(index) * 1_000)
        }
        let all = try temp.stores.messages.search(query: "哈尔滨", limit: 1_000).hits.map(\.id)
        XCTAssertEqual(all.count, 60, "420 条里每 7 条一条命中")

        var collected: [String] = []
        var cursor: SearchCursor?
        for _ in 0..<10 {
            let page = try temp.stores.messages.search(query: "哈尔滨", limit: 25, cursor: cursor)
            collected.append(contentsOf: page.hits.map(\.id))
            guard page.hasMore, let next = page.nextCursor else { break }
            cursor = next
        }
        XCTAssertEqual(collected, all, "键集游标跨块翻页的顺序必须与一次排完一致")
        XCTAssertEqual(Set(collected).count, collected.count)
    }

    // MARK: - 无候选上限（> 500）

    /// 620 条命中（超过 v10 的 500 上限）：必须一条不少地翻得完。
    func testMoreThanFiveHundredHitsAreAllReachable() throws {
        let total = 620
        // 正文完全一样：顺序只由 createdAt 降序决定，断言才能钉在「一条不少」上。
        for index in 0..<total {
            try insert(String(format: "m-%03d", index), "哈尔滨", at: Int64(index) * 1_000)
        }
        XCTAssertEqual(try temp.stores.messages.lastBlockIndex(sessionId: "session-1"), 3,
                       "620 条 = 4 块")

        // 一次要一页大的：v10 只能给 500 条，现在全都要有。
        let big = try temp.stores.messages.search(query: "哈尔滨", limit: 1_000)
        XCTAssertEqual(big.hits.count, total, "旧的 500 条候选上限已经去掉")
        XCTAssertFalse(big.hasMore)
        XCTAssertEqual(big.hits.first?.id, "m-619", "最新的那条在最前")
        XCTAssertEqual(big.hits.last?.id, "m-000", "最旧的那条也在（旧的 500 上限会把它截掉）")

        // 逐页翻：30 条一页，翻完 21 页，不重不漏。
        var collected: [String] = []
        var cursor: SearchCursor?
        var pages = 0
        while pages < 100 {
            pages += 1
            let page = try temp.stores.messages.search(query: "哈尔滨", limit: 30, cursor: cursor)
            collected.append(contentsOf: page.hits.map(\.id))
            guard page.hasMore, let next = page.nextCursor else { break }
            cursor = next
        }
        XCTAssertEqual(pages, 21, "620 / 30 → 21 页")
        XCTAssertEqual(collected.count, total)
        XCTAssertEqual(collected, big.hits.map(\.id))
        XCTAssertEqual(Set(collected).count, total, "没有重复")
    }

    /// 命中最旧的那一块也能被翻到：证明遍历覆盖了历史的开头，而不是「最近 N 条」。
    func testOldestBlockHitIsNotLost() throws {
        for index in 0..<650 {
            let content = index == 0 ? "很久以前的哈尔滨" : "最近的话 \(index)"
            try insert(String(format: "m-%03d", index), content, at: Int64(index) * 1_000)
        }
        let page = try temp.stores.messages.search(query: "哈尔滨", limit: 30)
        XCTAssertEqual(page.hits.map(\.id), ["m-000"], "第 0 块里的那条也要搜得到")
    }

    // MARK: - 过滤跨块

    func testPersonaFilterSkipsOtherPersonasBlocks() throws {
        for index in 0..<420 {
            try insert(String(format: "a-%03d", index), "哈尔滨 A\(index)", at: Int64(index) * 1_000)
            try insert(
                String(format: "b-%03d", index), "哈尔滨 B\(index)", at: Int64(index) * 1_000 + 500,
                sessionId: "session-2", personaId: "persona-2"
            )
        }
        // 两段会话各 3 块。
        XCTAssertEqual(try temp.stores.messages.lastBlockIndex(sessionId: "session-2"), 2)

        let page = try temp.stores.messages.search(query: "哈尔滨", personaId: "persona-2", limit: 100)
        XCTAssertEqual(page.hits.count, 100)
        XCTAssertTrue(page.hits.allSatisfy { $0.personaId == "persona-2" })
        XCTAssertTrue(page.hasMore)
    }

    func testSinceFilterAppliesInsideEveryBlock() throws {
        for index in 0..<420 {
            try insert(String(format: "m-%03d", index), "哈尔滨", at: Int64(index) * 1_000)
        }
        // 只要后 20 条（都落在第 2 块里）。
        let since = base + Int64(400) * 1_000
        let page = try temp.stores.messages.search(query: "哈尔滨", since: since, limit: 100)
        XCTAssertEqual(page.hits.count, 20)
        XCTAssertEqual(page.hits.first?.id, "m-419")
        XCTAssertEqual(page.hits.last?.id, "m-400")
        XCTAssertTrue(page.hits.allSatisfy { $0.createdAt >= since })
    }

    // MARK: - 逐块读取（每条查询只读一块）

    /// 用 `queryObserver` 数真实读出来的行：每次检索都只读「一块」（≤ BLOCK_SIZE），
    /// 读的块数等于历史里的块数 —— 峰值内存 ≈ 一块 + 一页，与历史总长无关。
    func testEachQueryReadsExactlyOneBlock() throws {
        for index in 0..<450 {
            try insert(String(format: "m-%03d", index), "哈尔滨 \(index)", at: Int64(index) * 1_000)
        }
        XCTAssertEqual(try temp.stores.messages.lastBlockIndex(sessionId: "session-1"), 2)

        var reads: [Int] = []
        temp.stores.messages.queryObserver = { method, rows in
            if method == "searchBlock" { reads.append(rows) }
        }
        _ = try temp.stores.messages.search(query: "哈尔滨", limit: 30)
        temp.stores.messages.queryObserver = nil

        XCTAssertEqual(reads.count, 3, "3 块就 3 次块读取（每次一块）")
        XCTAssertEqual(reads, [200, 200, 50], "每次读的行数就是那一块的条数，不是整段会话")
        XCTAssertTrue(reads.allSatisfy { $0 <= MessageBlocks.blockSize })
    }

    /// 第二次翻页重新走块，但每次读的仍然只有一块（不缓存整段历史）。
    func testSecondPageAlsoReadsOneBlockAtATime() throws {
        for index in 0..<450 {
            try insert(String(format: "m-%03d", index), "哈尔滨 \(index)", at: Int64(index) * 1_000)
        }
        let first = try temp.stores.messages.search(query: "哈尔滨", limit: 30)
        let cursor = try XCTUnwrap(first.nextCursor)

        var reads: [Int] = []
        temp.stores.messages.queryObserver = { method, rows in
            if method == "searchBlock" { reads.append(rows) }
        }
        _ = try temp.stores.messages.search(query: "哈尔滨", limit: 30, cursor: cursor)
        temp.stores.messages.queryObserver = nil

        XCTAssertEqual(reads, [200, 200, 50])
    }

    // MARK: - 翻页期间的稳定性

    /// 翻页途中又发了几条命中的新消息：已翻过的页不受影响、新页不重复、总数只增不减。
    func testNewMessagesDuringPagingDoNotDuplicateOrLoseHits() throws {
        for index in 0..<60 {
            try insert(String(format: "m-%03d", index), "哈尔滨", at: Int64(index) * 1_000)
        }
        let first = try temp.stores.messages.search(query: "哈尔滨", limit: 30)
        XCTAssertEqual(first.hits.count, 30)
        let cursor = try XCTUnwrap(first.nextCursor)

        // 翻页期间来了 3 条命中（更新的）。
        for index in 60..<63 {
            try insert(String(format: "m-%03d", index), "哈尔滨", at: Int64(index) * 1_000)
        }

        let second = try temp.stores.messages.search(query: "哈尔滨", limit: 30, cursor: cursor)
        let ids = first.hits.map(\.id) + second.hits.map(\.id)
        XCTAssertEqual(Set(ids).count, ids.count, "键集游标保证不重复")
        XCTAssertEqual(second.hits.map(\.id).first, "m-029", "从游标之后继续，不受新消息影响")
        XCTAssertEqual(second.hits.map(\.id).last, "m-000")
        // 新消息排在游标**之前**（更新），所以不会插进后续页，也不会让最后一页多出来：
        // 这就是键集游标的稳定性 —— 翻页期间来新消息，已翻过的页与还没翻到的旧命中都不动。
        XCTAssertFalse(second.hasMore)
        XCTAssertFalse(second.hits.contains { $0.id == "m-060" }, "翻页期间的新消息不进这一轮结果")

        // 重新从第一页开始才看得到那 3 条新消息。
        let fresh = try temp.stores.messages.search(query: "哈尔滨", limit: 3)
        XCTAssertEqual(fresh.hits.map(\.id), ["m-062", "m-061", "m-060"])
    }
}
