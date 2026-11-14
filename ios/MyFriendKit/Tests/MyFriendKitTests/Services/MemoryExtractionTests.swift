import Foundation
import XCTest
@testable import MyFriendKit

/// 记忆抽取：提示词 → JSON 解析 → 去重（第一次只留回声、第二次才落库）→ 落库。
final class MemoryExtractionTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var repository: MemoryRepository!
    private var echoStore: InMemoryMemoryEchoStore!

    override func setUpWithError() throws {
        db = try TempDatabase()
        // 1.9：记忆内置开启，没有开关要设；settings 仍然留着给「间隔」之类的留白。
        settings = InMemorySettings(AppSettings())
        let index = MemoryVectorIndex(stores: db.stores, now: { TestData.now })
        repository = MemoryRepository(stores: db.stores, vectorIndex: index,
                                      now: { TestData.now })
        echoStore = InMemoryMemoryEchoStore()
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.sessions.upsert(TestData.session())
        try db.stores.messages.upsert([
            TestData.message(id: "msg-1", role: .user, content: "我妹妹小满今年高考完了。", createdAt: TestData.now - 4000),
            TestData.message(id: "msg-2", role: .assistant, content: "她想学什么？", createdAt: TestData.now - 3000),
            TestData.message(id: "msg-3", role: .user, content: "她想学建筑，去年冬天去哈尔滨看过学校。",
                             createdAt: TestData.now - 2000),
            TestData.message(id: "msg-4", role: .assistant, content: "那挺好，她怕冷吗？", createdAt: TestData.now - 1000)
        ])
    }

    override func tearDownWithError() throws {
        repository = nil
        echoStore = nil
        settings = nil
        db.cleanUp()
        db = nil
    }

    private func makeExtraction(_ client: CompletionClient) -> MemoryExtraction {
        MemoryExtraction(
            stores: db.stores,
            memoryRepository: repository,
            completion: client,
            echoStore: echoStore,
            now: { TestData.now }
        )
    }

    private let responseJSON = """
    [
      {"content":"你妹妹叫小满，今年高考完，想学建筑。","category":"RELATIONSHIP","drawer":"USER",
       "importance":5,"confidence":0.9,"sourceQuote":"我妹妹小满今年高考完了","tags":["妹妹","高考","建筑"],
       "happenedAt":"去年冬天"},
      {"content":"你妹妹怕冷，去哈尔滨要带厚外套。","category":"FACT","drawer":"USER",
       "importance":3,"confidence":0.8,"sourceQuote":"她怕冷吗","tags":"妹妹, 哈尔滨, 怕冷",
       "happenedAt":""}
    ]
    """

    func testFirstMentionOnlyLeavesEchoAndSecondMentionPromotes() async throws {
        let client = FakeCompletionClient(texts: [responseJSON, responseJSON])
        let extraction = makeExtraction(client)

        let first = try await extraction.extract(
            sessionId: "session-1", config: TestData.config(), model: "deepseek-chat", force: true
        ).get()
        XCTAssertEqual(first.added, 0, "第一次提到不该直接落库")
        XCTAssertEqual(first.echoed, 2)
        XCTAssertEqual(try db.stores.memories.count(), 0)
        XCTAssertEqual(echoStore.byPersona("persona-1").count, 2)
        XCTAssertEqual(first.message, "记下了，还想再确认一次")
        // 抽完要把「已经抽到第几条消息」记下来。
        XCTAssertEqual(try db.stores.sessions.byId("session-1")?.lastMemoryExtractedCount, 4)

        // 第二次提到同一件事：升级成正式记忆，标签与发生时间都带上。
        let second = try await extraction.extract(
            sessionId: "session-1", config: TestData.config(), model: "deepseek-chat", force: true
        ).get()
        XCTAssertEqual(second.added, 2)
        XCTAssertEqual(try db.stores.memories.count(), 2)
        XCTAssertTrue(second.message.contains("+2"))

        let memories = try db.stores.memories.byPersona("persona-1")
        let sister = try XCTUnwrap(memories.first { $0.content.contains("小满") })
        XCTAssertEqual(sister.drawer, "USER")
        XCTAssertEqual(sister.importance, 5)
        XCTAssertNotNil(sister.tags)
        XCTAssertNotNil(sister.happenedAt, "「去年冬天」要换算成 happenedAt")
        // 落库即进索引：倒排与向量都要有。
        XCTAssertFalse(try db.stores.memoryTerms.terms(forMemory: sister.id).isEmpty)
        XCTAssertNotNil(try db.stores.memoryVectors.vector(memoryId: sister.id, provider: LocalEmbedding.PROVIDER))
    }

    func testExistingMemoryIsReinforcedNotDuplicated() async throws {
        try db.stores.memories.upsert([
            TestData.memory(id: "m-sister", content: "你妹妹叫小满，今年高考完想学建筑。", importance: 5)
        ])
        let client = FakeCompletionClient(text: responseJSON)
        let extraction = makeExtraction(client)
        let result = try await extraction.extract(
            sessionId: "session-1", config: TestData.config(), model: "deepseek-chat", force: true
        ).get()
        XCTAssertEqual(result.added, 0)
        XCTAssertEqual(result.reinforced, 1, "已有记忆再被提到应该是加固")
        XCTAssertEqual(try db.stores.memories.count(), 1)
        XCTAssertEqual(try db.stores.memories.byId("m-sister")?.useCount, 2)
    }

    func testSmallTalkIsRejected() async throws {
        let client = FakeCompletionClient(text: """
        [{"content":"晚安","category":"OTHER","drawer":"USER","importance":1,"confidence":0.9,"sourceQuote":"晚安"},
         {"content":"在吗","category":"OTHER","drawer":"USER","importance":1,"confidence":0.9,"sourceQuote":"在吗"}]
        """)
        let extraction = makeExtraction(client)
        let result = try await extraction.extract(
            sessionId: "session-1", config: TestData.config(), model: "deepseek-chat", force: true
        ).get()
        XCTAssertEqual(result.rejected, 2)
        XCTAssertEqual(try db.stores.memories.count(), 0)
        XCTAssertEqual(result.message, "没有发现需要新增的长期记忆")
    }

    func testSkipsWhenTooShortOrIntervalNotReached() async throws {
        let client = FakeCompletionClient(text: responseJSON)
        let extraction = makeExtraction(client)
        // 间隔没到（默认 6 条）：不调用模型，也不报错。
        let notYet = try await extraction.extract(
            sessionId: "session-1", config: TestData.config(), model: "deepseek-chat"
        ).get()
        XCTAssertEqual(notYet.added, 0)
        XCTAssertEqual(notYet.message, "还未到记忆整理间隔")
        XCTAssertEqual(client.callCount, 0)

        try db.stores.messages.delete(where: "id IN ('msg-2','msg-3','msg-4')")
        let tooShort = try await extraction.extract(
            sessionId: "session-1", config: TestData.config(), model: "deepseek-chat", force: true
        ).get()
        XCTAssertEqual(tooShort.message, "对话还不够多")
    }

    func testParsesCodeFencedJSONAndBadItemsAreDropped() {
        let raw = """
        ```json
        [{"content":"你提过下个月要去哈尔滨。","category":"EVENT","drawer":"EVENT","importance":"4"},
         "这是一条坏数据",
         {"category":"FACT"}]
        ```
        """
        let candidates = MemoryExtraction.parseCandidates(raw, now: TestData.now)
        XCTAssertEqual(candidates.count, 1)
        XCTAssertEqual(candidates.first?.drawer, .event)
        XCTAssertEqual(candidates.first?.importance, 4)
        XCTAssertEqual(MemoryExtraction.parseCandidates("完全不是 JSON", now: TestData.now).count, 0)
    }
}
