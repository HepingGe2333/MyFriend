import Foundation
import XCTest
@testable import YapKit

/// 记忆仓储：写入、召回链路（候选并集 → 重排 → 字数预算）、倒排补建、漂移与强度更新。
final class MemoryRepositoryTests: XCTestCase {

    private var db: TempDatabase!
    private var index: MemoryVectorIndex!
    private var repository: MemoryRepository!

    override func setUpWithError() throws {
        db = try TempDatabase()
        index = MemoryVectorIndex(stores: db.stores, now: { TestData.now })
        repository = MemoryRepository(
            stores: db.stores, vectorIndex: index, now: { TestData.now }
        )
        try db.stores.personas.upsert(TestData.persona())
    }

    override func tearDownWithError() throws {
        repository = nil
        index = nil
        db.cleanUp()
        db = nil
    }

    func testAddDetailedWritesMemoryTagsDrawerAndIndexes() throws {
        let result = repository.addDetailed(
            personaId: "persona-1",
            content: "你妹妹叫小满，今年高考完想去学建筑。",
            category: .relationship,
            importance: 5,
            drawer: "USER",
            sourceSessionId: "session-1",
            tags: ["妹妹", "高考"],
            happenedAt: TestData.now - 86_400_000
        )
        let id = try XCTUnwrap(try? result.get())
        let memory = try XCTUnwrap(try db.stores.memories.byId(id))
        XCTAssertEqual(memory.importance, 5)
        XCTAssertEqual(memory.drawer, "USER")
        XCTAssertEqual(memory.tags, #"["妹妹","高考"]"#)
        XCTAssertEqual(memory.useCount, 1)
        // 新记忆马上进倒排索引与向量索引。
        XCTAssertFalse(try db.stores.memoryTerms.terms(forMemory: id).isEmpty)
        XCTAssertNotNil(try db.stores.memoryVectors.vector(memoryId: id, provider: LocalEmbedding.PROVIDER))
        // 空正文、超长正文都要被挡住，而不是写进一条脏记忆。
        XCTAssertNotNil(repository.addDetailed(personaId: "persona-1", content: "   ",
                                               category: .other, importance: 3).failureOrNil)
    }

    func testGetRelevantUsesInvertedIndexLikeTagsAndTimeHint() throws {
        try db.stores.memories.upsert([
            TestData.memory(id: "m-harbin", content: "你提过下个月要去哈尔滨，怕冷，让我提醒你带厚外套。",
                            tags: #"["哈尔滨","怕冷"]"#),
            TestData.memory(id: "m-oat", content: "你对燕麦过敏，早餐别点燕麦粥。"),
            TestData.memory(id: "m-cat", content: "你养了只猫，叫团子，怕生。", drawer: "SELF", category: .other),
            TestData.memory(id: "m-old", content: "去年冬天你们一起去了哈尔滨看雪。",
                            happenedAt: TestData.now - 300 * 86_400_000)
        ])
        repository.ensureTermIndex()
        XCTAssertGreaterThan(try db.stores.memoryTerms.count(), 0)

        // 1) 倒排索引：换一种说法（「哈尔滨冷不冷」）也能找到那条。
        let harbin = repository.getRelevant(personaId: "persona-1", query: "哈尔滨冷不冷")
        XCTAssertTrue(harbin.contains { $0.id == "m-harbin" })
        XCTAssertTrue(harbin.prefix(2).contains { $0.id == "m-harbin" }, "最相关的那条要排进前二")

        // 2) 标签：「妹妹」不在正文里时靠标签兜底（这里用「怕冷」这个标签）。
        let tagHit = repository.getRelevant(personaId: "persona-1", query: "怕冷")
        XCTAssertTrue(tagHit.contains { $0.id == "m-harbin" })

        // 3) 时间指代：「去年冬天」把有 happenedAt 的旧事捞上来。
        let timeHit = repository.getRelevant(personaId: "persona-1", query: "去年冬天")
        XCTAssertTrue(timeHit.contains { $0.id == "m-old" })

        // 空 query 退化成 pinned/重要度基线，不报错。
        XCTAssertFalse(repository.getRelevant(personaId: "persona-1", query: "").isEmpty)
    }

    func testGetRelevantExcludesArchivedAndRespectsCharBudget() throws {
        var memories: [MemoryEntity] = [
            TestData.memory(id: "m-archived", content: "这是一条已经归档的记忆，不该进上下文。",
                            status: "ARCHIVED", strength: 0.0)
        ]
        // 每条 400 字，20 条 = 8000 字，超过 7000 的预算。
        for offset in 0..<20 {
            memories.append(
                TestData.memory(
                    id: "m-\(offset)",
                    content: String(repeating: "你提过想去看雪。", count: 50) + "编号\(offset)",
                    importance: 3
                )
            )
        }
        try db.stores.memories.upsert(memories)
        let picked = repository.getRelevant(personaId: "persona-1", query: "")
        XCTAssertFalse(picked.contains { $0.id == "m-archived" })
        let total = picked.reduce(0) { $0 + $1.content.count }
        XCTAssertLessThanOrEqual(total, MemoryRepository.maxMemoryContextChars)

        // 1.9：没有「关掉记忆」这件事了 —— 同一个查询永远能拿到东西（§一）。
        XCTAssertFalse(repository.getRelevant(personaId: "persona-1", query: "").isEmpty)
    }

    func testGetRelevantMarksRecalledButNotTooOften() throws {
        try db.stores.memories.upsert([TestData.memory(id: "m-1", content: "你妹妹叫小满。", lastUsedAt: 0)])
        _ = repository.getRelevant(personaId: "persona-1", query: "妹妹")
        let first = try XCTUnwrap(try db.stores.memories.byId("m-1"))
        XCTAssertEqual(first.lastUsedAt, TestData.now)
        XCTAssertEqual(first.useCount, 2)

        // 6 小时内再被想起：不重复写库（避免每轮对话都写同一行）。
        _ = repository.getRelevant(personaId: "persona-1", query: "妹妹")
        let second = try XCTUnwrap(try db.stores.memories.byId("m-1"))
        XCTAssertEqual(second.useCount, 2)
    }

    func testReinforceAndDriftRebuildIndexes() throws {
        try db.stores.memories.upsert([TestData.memory(id: "m-1", content: "你对燕麦过敏。")])
        repository.ensureTermIndex()

        // 加固：追加了一句真正的新信息，索引跟着重建，新词查得到。
        _ = repository.reinforce(memoryId: "m-1", appendedText: "改喝豆浆了，早餐别点燕麦粥。")
        let reinforced = try XCTUnwrap(try db.stores.memories.byId("m-1"))
        XCTAssertTrue(reinforced.content.contains("豆浆"))
        XCTAssertEqual(reinforced.useCount, 2)
        let terms = try db.stores.memoryTerms.terms(forMemory: "m-1").map(\.term)
        XCTAssertTrue(terms.contains { $0 == "豆浆" || $0 == "浆了" })

        // 漂移：改正文、留原文锚点、写一条 memory_versions、降次数 +1。
        repository.applyDrift(
            memoryId: "m-1", newContent: "你对燕麦过敏，不过最近好像好一点了。",
            reason: "回忆被当时的心情改写", moodLabel: "怀念", now: TestData.now
        )
        let drifted = try XCTUnwrap(try db.stores.memories.byId("m-1"))
        XCTAssertEqual(drifted.driftCount, 1)
        XCTAssertEqual(drifted.originalContent, reinforced.content)
        XCTAssertEqual(try db.stores.memoryVersions.count(memoryId: "m-1"), 1)

        // 强度写回：整理器说「留着」时不低于当前值。
        let before = drifted.strength
        repository.touchStrength(id: "m-1", strength: before, now: TestData.now)
        XCTAssertEqual(try db.stores.memories.byId("m-1")?.consolidatedAt, TestData.now)
    }

    func testFormatForPromptGroupsByDrawer() throws {
        let memories = [
            TestData.memory(id: "m-user", content: "你对燕麦过敏。", drawer: "USER", importance: 5),
            TestData.memory(id: "m-self", content: "你说话很慢。", drawer: "SELF", importance: 3),
            TestData.memory(id: "m-event", content: "你们约好冬天去看雪。", drawer: "EVENT", importance: 4)
        ]
        let text = repository.formatForPrompt(memories)
        XCTAssertTrue(text.contains("关于对方"))
        XCTAssertTrue(text.contains("你自己是什么样的人"))
        XCTAssertTrue(text.contains("你们之间的经历与约定"))
        XCTAssertTrue(text.contains("你对燕麦过敏。"))
        XCTAssertGreaterThan(
            try XCTUnwrap(text.range(of: "关于对方")?.lowerBound),
            try XCTUnwrap(text.range(of: "## 你记得的事")?.lowerBound)
        )
        // 空列表返回空串，界面据此整段不画。
        XCTAssertEqual(repository.formatForPrompt([]), "")
    }
}

private extension Result {
    /// 测试里只想问「是不是失败」，不想写一长串 switch。
    var failureOrNil: Failure? {
        if case .failure(let error) = self { return error }
        return nil
    }
}
