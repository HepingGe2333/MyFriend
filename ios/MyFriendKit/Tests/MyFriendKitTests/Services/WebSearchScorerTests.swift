import Foundation
import XCTest
@testable import MyFriendKit

/// 联网搜索之后的「值不值得记」打分（`WebSearchScorer`，对齐 Android 同名类）。
///
/// 这里钉住五件事：
/// 1. **解析容错**：```json 代码块、前后废话、坏 JSON 都不能炸；
/// 2. **阈值边界**：模型说重要但只给 2 分 → 不落库；3 分 → 落库（`REMEMBER_FLOOR`）；
/// 3. **失败降级**：模型调用报错只返回「没记」，不抛给搜索结果；
/// 4. **写入形状**：来源是「他自己上网看到的」，抽屉/类别与 Android 的 `categoryOf` 一致；
/// 5. **去重**：同一件事已经在记忆里，不做第二次写入。
final class WebSearchScorerTests: XCTestCase {

    private var temp: TempDatabase!
    private var memory: MemoryRepository!
    private var scorer: WebSearchScorer!
    private var completion: FakeCompletionClient!

    private let personaId = "persona-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        memory = MemoryRepository(stores: temp.stores, vectorIndex: MemoryVectorIndex(stores: temp.stores))
        try temp.stores.personas.upsert(TestData.persona(id: personaId))
        completion = FakeCompletionClient()
        scorer = WebSearchScorer(
            stores: temp.stores,
            memory: memory,
            completion: completion,
            now: { self.now }
        )
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    private func score(_ raw: String) async -> WebSearchScoreResult {
        completion.fallback = .success(CompletionResult(text: raw))
        return await scorer.score(
            personaId: personaId,
            query: "杭州 冬天 天气",
            hitsSummary: "1. 杭州冬季平均气温 3-10 度\n   https://example.com/hz\n   湿冷，体感比温度更低。",
            config: TestData.config(),
            model: "deepseek-chat"
        )
    }

    private func memories() -> [MemoryEntity] {
        (try? temp.stores.memories.byPersona(personaId)) ?? []
    }

    // MARK: - 解析容错

    func testFencedJsonIsParsedAndWritten() async throws {
        let raw = """
        ```json
        {"important":true,"importance":4,"takeaway":"对方怕杭州冬天的湿冷","drawer":"USER"}
        ```
        以上是判断。
        """
        let result = await score(raw)
        XCTAssertTrue(result.scored)
        XCTAssertTrue(result.important)
        XCTAssertEqual(result.importance, 4)
        XCTAssertNotNil(result.memoryId, result.message)

        let stored = try XCTUnwrap(memories().first)
        XCTAssertEqual(stored.content, "对方怕杭州冬天的湿冷")
        XCTAssertEqual(stored.sourceQuote, WebSearchScorer.sourceQuote)
        XCTAssertEqual(stored.drawer, "USER")
        XCTAssertEqual(stored.category, .fact)
        XCTAssertFalse(stored.pinned)
        // 用量类目与 Android 的 `UsageKind.SCORING` 一致；判断类任务温度压低、输出上限 300。
        XCTAssertEqual(completion.calls.last?.kind, .scoring)
        XCTAssertEqual(completion.calls.last?.params.temperature, WebSearchScorer.temperature)
        XCTAssertEqual(completion.calls.last?.params.maxOutputTokens, WebSearchScorer.maxOutputTokens)
    }

    func testSelfDrawerMapsToOtherCategory() async throws {
        _ = await score(#"{"important":true,"importance":5,"takeaway":"他自己其实很怕冷","drawer":"SELF"}"#)
        let stored = try XCTUnwrap(memories().first)
        XCTAssertEqual(stored.drawer, "SELF")
        XCTAssertEqual(stored.category, .other, "与 Android 的 categoryOf(SELF) 一致")
    }

    func testGarbageReplyDegradesToNothing() async throws {
        let result = await score("我看了下，这些都不太重要，随便聊聊吧。")
        XCTAssertFalse(result.scored)
        XCTAssertNil(result.memoryId)
        XCTAssertFalse(result.message.isEmpty)
        XCTAssertTrue(memories().isEmpty)
    }

    func testBlankInputDoesNotCallModel() async {
        let before = completion.callCount
        let result = await scorer.score(
            personaId: personaId, query: " ", hitsSummary: "  ",
            config: TestData.config(), model: "deepseek-chat"
        )
        XCTAssertFalse(result.scored)
        XCTAssertEqual(completion.callCount, before, "没有内容就不该白花一次调用")
    }

    // MARK: - 阈值边界

    func testImportanceTwoIsNotRemembered() async {
        let result = await score(#"{"important":true,"importance":2,"takeaway":"杭州冬天有点冷"}"#)
        XCTAssertTrue(result.scored)
        XCTAssertTrue(result.important)
        XCTAssertNil(result.memoryId, "important=true 但只有 2 分，低于 REMEMBER_FLOOR")
        XCTAssertTrue(memories().isEmpty)
    }

    func testImportanceThreeIsRemembered() async {
        let result = await score(#"{"important":true,"importance":3,"takeaway":"杭州冬天是湿冷"}"#)
        XCTAssertNotNil(result.memoryId)
        XCTAssertEqual(memories().count, 1)
    }

    func testMissingImportanceInfersFromImportantFlag() async {
        let result = await score(#"{"important":true,"takeaway":"杭州冬天是湿冷"}"#)
        XCTAssertEqual(result.importance, ImportanceScoring.REMEMBER_FLOOR)
        XCTAssertNotNil(result.memoryId)
    }

    // MARK: - 失败降级

    func testCompletionFailureDoesNotThrow() async {
        completion.fallback = .failure(CompletionFailure("网络断了"))
        let result = await scorer.score(
            personaId: personaId, query: "杭州 冬天",
            hitsSummary: "1. 湿冷", config: TestData.config(), model: "deepseek-chat"
        )
        XCTAssertFalse(result.scored)
        XCTAssertNil(result.memoryId)
        XCTAssertTrue(result.message.contains("网络断了"), result.message)
        XCTAssertTrue(memories().isEmpty)
    }

    func testMissingPersonaDoesNotThrow() async {
        let result = await scorer.score(
            personaId: "not-there", query: "杭州 冬天",
            hitsSummary: "1. 湿冷", config: TestData.config(), model: "deepseek-chat"
        )
        XCTAssertFalse(result.scored)
        XCTAssertTrue(result.message.contains("人格"))
    }

    func testForkedMessagesContainPromptAndSummary() {
        let messages = scorer.forkedMessages(
            persona: TestData.persona(id: personaId),
            query: "杭州 冬天",
            hitsSummary: "1. 湿冷"
        )
        XCTAssertEqual(messages.count, 2, "打分只带 system + user 两条，绝不带历史")
        XCTAssertEqual(messages.first?.role, "system")
        XCTAssertTrue(messages.first?.content.contains(ImportanceScoring.SHARED_CRITERIA) == true)
        XCTAssertTrue(messages.last?.content.contains("杭州 冬天") == true)
        XCTAssertEqual(completion.callCount, 0, "拼提示词本身不该发请求")
    }

    // MARK: - 写入去重

    func testExistingSimilarMemoryIsNotWrittenTwice() async throws {
        _ = memory.addDetailed(
            personaId: personaId,
            content: "对方怕杭州冬天的湿冷",
            category: .fact,
            importance: 4,
            drawer: "USER"
        )
        let before = memories().count
        let result = await score(#"{"important":true,"importance":4,"takeaway":"对方怕杭州冬天的湿冷"}"#)
        XCTAssertTrue(result.scored)
        XCTAssertNil(result.memoryId, "同一件事不重复写")
        XCTAssertEqual(memories().count, before)
        XCTAssertTrue(result.message.contains("已经有了"), result.message)
    }

    // MARK: - 工具接线

    func testToolExecutorSchedulesScoringAfterSuccessfulSearch() async throws {
        let recording = RecordingScorer()
        let keys = ApiKeyStore(backend: InMemoryKeychain())
        try keys.setAPIKey("sk-test", for: "config-1")
        try temp.stores.sessions.upsert(TestData.session(id: "session-1", personaId: personaId))
        try temp.stores.apiConfigs.upsert(ApiConfigEntity(
            id: "config-1", name: "测试配置", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat", webToolsEnabled: true
        ))
        let executor = ToolExecutor(
            stores: temp.stores,
            apiKeys: keys,
            memory: memory,
            recallService: RecallService(stores: temp.stores, memoryRepository: memory),
            mood: MoodRepository(stores: temp.stores, now: { self.now }),
            personaCards: PersonaCardRepository(stores: temp.stores),
            intents: AgentIntentSupport(stores: temp.stores, ticks: AgentTickStore(database: temp.stores.database)),
            echoes: InMemoryMemoryEchoStore(),
            webTools: WebToolsClient(session: StubURLProtocol.makeSession()),
            scorer: recording,
            now: { self.now }
        )
        StubURLProtocol.reset()
        StubURLProtocol.enqueue(.json("""
        {"results":[{"title":"杭州冬季","url":"https://example.com/hz","summary":"湿冷","authority":3}]}
        """))
        let persona = try XCTUnwrap(try temp.stores.personas.byId(personaId))
        let result = await executor.run(
            ToolInvocation(name: "web_search", argumentsJson: #"{"query":"杭州冬天"}"#),
            persona: persona,
            sessionId: "session-1"
        )
        XCTAssertTrue(result.ok, result.errorMessage ?? "")
        let recorded = try XCTUnwrap(recording.calls.last)
        XCTAssertEqual(recorded.query, "杭州冬天")
        XCTAssertTrue(recorded.summary.contains("杭州冬季"))
        XCTAssertEqual(recorded.model, "deepseek-chat")
        XCTAssertEqual(recorded.config.apiKey, "sk-test")
    }
}

/// 只记录「有没有被叫」的假打分器（工具链路测试用）。
private final class RecordingScorer: WebSearchScoring {
    struct Call: Equatable {
        var personaId: String
        var query: String
        var summary: String
        var config: CompletionConfig
        var model: String
    }

    private(set) var calls: [Call] = []

    func scoreInBackground(
        personaId: String,
        query: String,
        hitsSummary: String,
        config: CompletionConfig,
        model: String
    ) {
        calls.append(
            Call(personaId: personaId, query: query, summary: hitsSummary, config: config, model: model)
        )
    }
}
