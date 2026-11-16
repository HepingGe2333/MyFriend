import Foundation
import XCTest
@testable import YapKit

/// 每日整理：清单挑选、按模型指令改库、留痕、绝不删消息。
final class MemoryConsolidatorTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var repository: MemoryRepository!
    private var consolidator: MemoryConsolidator!

    override func setUpWithError() throws {
        db = try TempDatabase()
        // 1.9：记忆与整理都内置开启，settings 只是为了「没有开关」这件事本身。
        settings = InMemorySettings(AppSettings())
        let index = MemoryVectorIndex(stores: db.stores, now: { TestData.now })
        repository = MemoryRepository(stores: db.stores, vectorIndex: index,
                                      now: { TestData.now })
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.sessions.upsert(TestData.session())
        try db.stores.messages.upsert([
            TestData.message(id: "msg-1", role: .user, content: "今天又加班到十一点。",
                             createdAt: TestData.now - 2 * 3_600_000),
            TestData.message(id: "msg-2", role: .assistant, content: "那你早点睡，别硬撑。",
                             createdAt: TestData.now - 3_600_000)
        ])
    }

    override func tearDownWithError() throws {
        consolidator = nil
        repository = nil
        settings = nil
        db.cleanUp()
        db = nil
    }

    private func makeConsolidator(_ client: CompletionClient) -> MemoryConsolidator {
        MemoryConsolidator(
            stores: db.stores,
            memoryRepository: repository,
            completion: client,
            now: { TestData.now }
        )
    }

    func testConsolidationAppliesMergeKeepArchiveAndNeverTouchesMessages() async throws {
        // 清单顺序 = pinned DESC, importance DESC, updatedAt DESC：把 importance 拉开，
        // 编号 1..5 才与 m-1..m-5 一一对应（模型抄回来的编号就是清单序号）。
        try db.stores.memories.upsert([
            TestData.memory(id: "m-1", content: "你对燕麦过敏。", importance: 5,
                            createdAt: TestData.now - 40 * 86_400_000),
            TestData.memory(id: "m-2", content: "你早餐不吃燕麦，改喝豆浆。", importance: 4,
                            createdAt: TestData.now - 30 * 86_400_000),
            TestData.memory(id: "m-3", content: "你提过想养一只橘猫。", importance: 3),
            TestData.memory(id: "m-4", content: "你妹妹叫小满。", importance: 2),
            TestData.memory(id: "m-5", content: "上周三你们聊到很晚。", importance: 1)
        ])
        // 模型只认得清单编号：第 1、2 条合并，第 3 条归档，第 4 条提权。
        let plan = """
        {"operations":[
          {"op":"merge","ids":["1","2"],"content":"你对燕麦过敏，早餐改喝豆浆。","importance":4,"reason":"同一件事"},
          {"op":"archive","ids":["3"],"reason":"只是一时兴起"},
          {"op":"raise","ids":["4"],"importance":5,"reason":"家人很重要"},
          {"op":"keep","ids":["5"],"reason":"最近还在提"}
        ],"summary":"并了两条过敏，归档养猫，家人提权"}
        """
        let client = FakeCompletionClient(text: plan)
        consolidator = makeConsolidator(client)

        let messagesBefore = try db.stores.messages.count()
        let result = await consolidator.consolidate(
            personaId: "persona-1", config: TestData.config(), model: "deepseek-chat"
        )

        XCTAssertEqual(result.status, .done)
        XCTAssertEqual(result.merged, 1)
        XCTAssertEqual(result.archived, 1)
        XCTAssertEqual(result.kept, 2)
        XCTAssertEqual(client.callCount, 1)
        XCTAssertEqual(client.calls.first?.kind, .consolidation)

        let merged = try XCTUnwrap(try db.stores.memories.byId("m-1"))
        XCTAssertTrue(merged.content.contains("豆浆"))
        XCTAssertEqual(merged.importance, 4)
        XCTAssertEqual(try db.stores.memories.byId("m-2")?.status, "ARCHIVED")
        XCTAssertEqual(try db.stores.memories.byId("m-3")?.status, "ARCHIVED")
        XCTAssertEqual(try db.stores.memories.byId("m-4")?.importance, 5, "raise 要把重要度提上去")
        XCTAssertEqual(try db.stores.memories.byId("m-5")?.status, "ACTIVE")

        // 整理留痕写了一条，摘要与计数都在。
        let record = try XCTUnwrap(try db.stores.memoryConsolidations.latest(personaId: "persona-1"))
        XCTAssertEqual(record.merged, 1)
        XCTAssertEqual(record.archived, 1)
        XCTAssertEqual(record.summary, "并了两条过敏，归档养猫，家人提权")
        XCTAssertEqual(record.modelName, "deepseek-chat")

        // 硬规矩：整理绝不删消息。
        XCTAssertEqual(try db.stores.messages.count(), messagesBefore)
        XCTAssertEqual(try db.stores.sessions.count(), 1)
    }

    func testUnmentionedMemoriesDecayByLifecycle() async throws {
        // 一条很旧、分量很低的记忆：模型没提到它，应该被时间沉到 DORMANT。
        try db.stores.memories.upsert([
            TestData.memory(
                id: "m-old",
                content: "很久以前提过一次的琐事。",
                importance: 1,
                status: "ACTIVE",
                strength: 0.5,
                useCount: 0,
                lastUsedAt: 0,
                createdAt: TestData.now - 30 * 86_400_000
            )
        ])
        consolidator = makeConsolidator(FakeCompletionClient(text: #"{"operations":[],"summary":"这次没怎么动"}"#))
        let result = await consolidator.consolidate(
            personaId: "persona-1", config: TestData.config(), model: "deepseek-chat"
        )
        XCTAssertEqual(result.status, .done)
        let memory = try XCTUnwrap(try db.stores.memories.byId("m-old"))
        XCTAssertEqual(memory.status, "DORMANT")
        XCTAssertLessThan(memory.strength, 0.45)
    }

    func testMissingConfigReturnsStatusWithoutCallingModel() async {
        // 1.9 起没有「整理已关闭」这个状态：只有「没人格 / 没记忆 / 没配置」三种跳过（§一）。
        let client = FakeCompletionClient(text: #"{"operations":[]}"#)
        consolidator = makeConsolidator(client)
        try? db.stores.memories.upsert([TestData.memory(id: "m-1", content: "你对燕麦过敏。")])
        let noConfig = await consolidator.consolidate(
            personaId: "persona-1", config: nil, model: "deepseek-chat"
        )
        XCTAssertEqual(noConfig.status, .noConfig)
        XCTAssertEqual(client.callCount, 0)
    }

    func testModelFailureDoesNotWriteConsolidationRecord() async throws {
        try db.stores.memories.upsert([TestData.memory(id: "m-1", content: "你对燕麦过敏。")])
        let client = FakeCompletionClient([.failure(CompletionFailure("网络断了"))])
        consolidator = makeConsolidator(client)
        let result = await consolidator.consolidate(
            personaId: "persona-1", config: TestData.config(), model: "deepseek-chat"
        )
        XCTAssertEqual(result.status, .failed)
        XCTAssertTrue(result.summary.contains("网络断了"))
        XCTAssertNil(try db.stores.memoryConsolidations.latest(personaId: "persona-1"))
        // 失败也是「什么都没改」：记忆还活着。
        XCTAssertEqual(try db.stores.memories.byId("m-1")?.status, "ACTIVE")
    }

    func testPromptListingBalancesDrawers() throws {
        // 三个抽屉各 40 条：每个抽屉先占 30 个座位，正好填满 90 的总上限。
        var memories: [MemoryEntity] = []
        for drawer in ["SELF", "USER", "EVENT"] {
            for index in 0..<40 {
                memories.append(
                    TestData.memory(id: "\(drawer)-\(index)", content: "记忆\(drawer)\(index)。",
                                    drawer: drawer, importance: 3)
                )
            }
        }
        let selected = consolidator ?? makeConsolidator(FakeCompletionClient(text: "{}"))
        let promptMemories = selected.selectPromptMemories(memories)
        XCTAssertEqual(promptMemories.count, MemoryConsolidator.maxPromptMemories)
        for drawer in ["SELF", "USER", "EVENT"] {
            XCTAssertEqual(
                promptMemories.filter { $0.drawer == drawer }.count,
                MemoryConsolidator.maxPromptPerDrawer,
                "\(drawer) 抽屉要占满自己的位置"
            )
        }
        XCTAssertEqual(promptMemories.count, 90)
    }
}
