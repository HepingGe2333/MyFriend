import Foundation
import XCTest
@testable import YapKit

/// 向量索引：补建分页、检索、清理。
///
/// 这几个测试盯的是「不显眼但会悄悄坏掉」的约定：
/// 补建必须能补完超过一批的记忆（否则第 65 条永远没有向量）、
/// 坏 blob 不许把检索炸掉、换 provider 的旧向量不许继续参与排序。
final class MemoryVectorIndexTests: XCTestCase {

    private var db: TempDatabase!
    private var index: MemoryVectorIndex!

    override func setUpWithError() throws {
        db = try TempDatabase()
        index = MemoryVectorIndex(stores: db.stores, now: { TestData.now })
    }

    override func tearDownWithError() throws {
        index = nil
        db.cleanUp()
        db = nil
    }

    func testBackfillPagesThroughMoreThanTwoBatches() throws {
        try db.stores.personas.upsert(TestData.persona())
        // 130 条 > 64 × 2：补建必须问第三轮，否则最后 2 条永远没有向量。
        let memories = (0..<130).map { offset in
            TestData.memory(
                id: "memory-\(offset)",
                content: "第\(offset)条：你提过下个月想去哈尔滨看雪，怕冷。（编号\(offset)）"
            )
        }
        try db.stores.memories.upsert(memories)
        XCTAssertEqual(try db.stores.memoryVectors.count(provider: LocalEmbedding.PROVIDER), 0)

        let written = index.ensureIndex(personaId: "persona-1")
        XCTAssertEqual(written, 130)
        XCTAssertEqual(try db.stores.memoryVectors.count(provider: LocalEmbedding.PROVIDER), 130)

        // 每个 id 都真的有向量（不是只有前 128 条）。
        for memory in memories {
            XCTAssertNotNil(
                try db.stores.memoryVectors.vector(memoryId: memory.id, provider: LocalEmbedding.PROVIDER),
                "\(memory.id) 缺向量"
            )
        }
        // 确认过就不要再扫一遍。
        XCTAssertEqual(index.ensureIndex(personaId: "persona-1"), 0)
    }

    func testBackfillSkipsBlankContentAndDoesNotLoopForever() throws {
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.memories.upsert([
            TestData.memory(id: "memory-good", content: "你妹妹叫小满，今年高考完想去学建筑。"),
            TestData.memory(id: "memory-punctuation", content: "。。。，，，")
        ])
        let written = index.ensureIndex(personaId: "persona-1")
        // 纯标点算不出向量：不写、也不该无限重试。
        XCTAssertEqual(written, 1)
        XCTAssertNil(try db.stores.memoryVectors.vector(memoryId: "memory-punctuation", provider: LocalEmbedding.PROVIDER))
    }

    func testUpsertRemoveAndSearchIn() throws {
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.memories.upsert([
            TestData.memory(id: "m-harbin", content: "你提过下个月要去哈尔滨，怕冷，让我提醒你带厚外套。"),
            TestData.memory(id: "m-oat", content: "你对燕麦过敏，早餐别点燕麦粥。"),
            TestData.memory(id: "m-sister", content: "你妹妹叫小满，今年高考完想去学建筑。")
        ])
        index.ensureIndex(personaId: "persona-1")

        let hits = index.searchIn(
            memoryIds: ["m-harbin", "m-oat", "m-sister"],
            query: "哈尔滨冷不冷",
            limit: 3
        )
        XCTAssertEqual(hits.first?.memoryId, "m-harbin", "最像的那条必须排第一")
        XCTAssertTrue(hits.allSatisfy { $0.similarity >= 0 && $0.similarity <= 1 })

        // 正文改了就重算；删记忆就删向量。
        index.upsert(memoryId: "m-oat", content: "你改喝豆浆了，对燕麦不过敏了。")
        XCTAssertNotNil(try db.stores.memoryVectors.vector(memoryId: "m-oat", provider: LocalEmbedding.PROVIDER))
        index.remove(memoryId: "m-oat")
        XCTAssertNil(try db.stores.memoryVectors.vector(memoryId: "m-oat", provider: LocalEmbedding.PROVIDER))
        // 空正文 = 不该有向量。
        index.upsert(memoryId: "m-sister", content: "   ")
        XCTAssertNil(try db.stores.memoryVectors.vector(memoryId: "m-sister", provider: LocalEmbedding.PROVIDER))
    }

    func testCorruptAndForeignVectorsAreSkippedThenPruned() throws {
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.memories.upsert([
            TestData.memory(id: "m-1", content: "你提过下个月去哈尔滨。")
        ])
        index.ensureIndex(personaId: "persona-1")

        // 坏数据：长度不对的 blob（半个 float）。
        try db.stores.memoryVectors.upsert(
            MemoryVectorEntity(
                memoryId: "m-1", provider: LocalEmbedding.PROVIDER, dim: LocalEmbedding.DIM,
                vector: Data([0x00, 0x01]), updatedAt: TestData.now
            )
        )
        XCTAssertTrue(index.searchIn(memoryIds: ["m-1"], query: "哈尔滨", limit: 3).isEmpty)

        // 上一代 provider 的向量不参与检索，并在全库补建时被清掉。
        try db.stores.memoryVectors.upsert(
            MemoryVectorEntity(
                memoryId: "m-1", provider: "old-embedding-v0", dim: LocalEmbedding.DIM,
                vector: VectorMath.encode(LocalEmbedding.embed("哈尔滨")), updatedAt: TestData.now
            )
        )
        XCTAssertEqual(index.pruneForeignProviders(), 1)
        XCTAssertNil(try db.stores.memoryVectors.vector(memoryId: "m-1", provider: "old-embedding-v0"))
    }

    func testPruneOrphansReclaimsVectorsWithoutMemories() throws {
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.memories.upsert([TestData.memory(id: "m-live", content: "你妹妹叫小满。")])
        index.ensureIndex(personaId: "persona-1")
        // 一条记忆已经不在、向量还留着。
        try db.stores.memoryVectors.upsert(
            MemoryVectorEntity(
                memoryId: "m-gone", provider: LocalEmbedding.PROVIDER, dim: LocalEmbedding.DIM,
                vector: VectorMath.encode(LocalEmbedding.embed("已经不在的记忆")), updatedAt: TestData.now
            )
        )
        XCTAssertEqual(index.pruneOrphans(), 1)
        XCTAssertEqual(try db.stores.memoryVectors.count(provider: LocalEmbedding.PROVIDER), 1)
    }
}
