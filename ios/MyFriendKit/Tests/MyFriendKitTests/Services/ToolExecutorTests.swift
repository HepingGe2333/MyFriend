import Foundation
import XCTest
@testable import MyFriendKit

/// 聊天侧工具执行器（`Services/ToolExecutor.swift`）的行为。
///
/// 对齐的对象是 Android 的 `data/repo/ToolExecutor.kt`：工具名、参数夹取、
/// 回灌给模型的中文形状、动作卡片的标题。这里逐条钉住：
/// 1. **参数夹取**（importance 1–5、count 1–12、depth 宽松解析、minuts 5–720）；
/// 2. **落库**（remember → 先回声后正式记忆、set_mood → persona、set_quiet_hours → persona + dnd、
///    defer_reply → agent_intents + 已读）；
/// 3. **失败要有中文原因**（没开联网、时间格式不对、不认识这个工具）。
final class ToolExecutorTests: XCTestCase {

    private var temp: TempDatabase!
    private var apiKeys: ApiKeyStore!
    private var memory: MemoryRepository!
    private var recall: RecallService!
    private var mood: MoodRepository!
    private var intents: AgentIntentSupport!
    private var echoes: InMemoryMemoryEchoStore!
    private var executor: ToolExecutor!

    private let personaId = "persona-1"
    private let sessionId = "session-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
        temp = try TempDatabase()
        apiKeys = ApiKeyStore(backend: InMemoryKeychain())
        memory = MemoryRepository(stores: temp.stores, vectorIndex: MemoryVectorIndex(stores: temp.stores))
        recall = RecallService(stores: temp.stores, memoryRepository: memory)
        mood = MoodRepository(stores: temp.stores, now: { self.now })
        intents = AgentIntentSupport(stores: temp.stores, ticks: AgentTickStore(database: temp.stores.database))
        echoes = InMemoryMemoryEchoStore()

        try temp.stores.personas.upsert(TestData.persona(id: personaId))
        try temp.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
        try temp.stores.apiConfigs.upsert(ApiConfigEntity(
            id: "config-1", name: "测试配置", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat"
        ))

        executor = ToolExecutor(
            stores: temp.stores,
            apiKeys: apiKeys,
            memory: memory,
            recallService: recall,
            mood: mood,
            personaCards: PersonaCardRepository(stores: temp.stores),
            intents: intents,
            echoes: echoes,
            webTools: WebToolsClient(session: StubURLProtocol.makeSession()),
            now: { self.now }
        )
    }

    override func tearDownWithError() throws {
        StubURLProtocol.reset()
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹取与兜底

    func testUnknownToolFailsLoudly() async throws {
        let result = await run("没有这个工具", arguments: [:])
        XCTAssertFalse(result.ok)
        XCTAssertTrue(result.errorMessage?.contains("不认识这个工具") == true)
        XCTAssertTrue(result.contentForModel.contains("这次调用没成功"))
        XCTAssertTrue(result.contentForModel.contains("不要向对方解释工具细节"))
    }

    func testRememberClampsImportanceAndDefaultsDrawer() async throws {
        // 模型写错大小写与越界重要度：仍然落成一条 USER 抽屉、重要度 5 的记忆（第二次提到）。
        _ = await run("remember", arguments: [
            "content": "对方说天冷的时候想收到一句提醒，不喜欢被反复叮嘱",
            "drawer": "user",
            "importance": 9
        ])
        let result = await run("remember", arguments: [
            "content": "对方说天冷的时候想收到一句提醒，不喜欢被反复叮嘱",
            "drawer": "user",
            "importance": 9
        ])
        XCTAssertTrue(result.ok)
        let memories = try temp.stores.memories.byPersona(personaId)
        let stored = try XCTUnwrap(memories.first)
        XCTAssertEqual(stored.importance, 5, "importance 要夹在 1–5")
        XCTAssertEqual(stored.drawer, "USER", "drawer 大小写不敏感，缺省按 USER")
        XCTAssertEqual(stored.category, .fact)
        XCTAssertEqual(stored.sourceSessionId, sessionId)
    }

    func testSetMoodClampsIntensityAndPersists() async throws {
        let result = await run("set_mood", arguments: ["mood": "有点低落", "intensity": 9, "reason": "听你说累"])
        XCTAssertTrue(result.ok)
        XCTAssertEqual(result.traceTitle, "心情变了")
        let persona = try XCTUnwrap(try temp.stores.personas.byId(personaId))
        XCTAssertEqual(persona.mood, "有点低落")
        // 越界的强度按 Android 的 `takeIf { it in 1..5 }` 处理：当作没写，回落到默认。
        XCTAssertEqual(persona.moodIntensity, 2)
        XCTAssertEqual(persona.moodReason, "听你说累")
    }

    func testSetQuietHoursRejectsBadClock() async throws {
        let result = await run("set_quiet_hours", arguments: [
            "sleep_at": "25:00", "wake_at": "07:00", "reason": "早点睡"
        ])
        XCTAssertFalse(result.ok)
        XCTAssertEqual(result.errorMessage, "睡觉时间要写成 HH:mm")
        let persona = try XCTUnwrap(try temp.stores.personas.byId(personaId))
        XCTAssertNil(persona.quietSleepMinutes)
    }

    // MARK: - 落库

    func testRememberEchoFirstThenPromote() async throws {
        let arguments: [String: Any] = [
            "content": "对方在准备一个很重要的项目，压力来自排期",
            "drawer": "EVENT",
            "importance": 4
        ]
        let first = await run("remember", arguments: arguments)
        XCTAssertTrue(first.ok)
        XCTAssertEqual(first.traceTitle, "记下了（还在确认）", "第一次听到只留回声，不落长期记忆")
        XCTAssertTrue(try temp.stores.memories.byPersona(personaId).isEmpty)
        XCTAssertEqual(echoes.byPersona(personaId).count, 1)

        let second = await run("remember", arguments: arguments)
        XCTAssertTrue(second.ok)
        XCTAssertEqual(second.traceTitle, "记住了", "第二次提到同一件事才真正记住")
        let memories = try temp.stores.memories.byPersona(personaId)
        XCTAssertEqual(memories.count, 1)
        XCTAssertEqual(memories.first?.importance, 4)
        XCTAssertEqual(memories.first?.drawer, "EVENT")
    }

    func testSetQuietHoursWritesWindowAndDnd() async throws {
        let result = await run("set_quiet_hours", arguments: [
            "sleep_at": "22:30", "wake_at": "07:00", "reason": "想早点睡", "dnd": true
        ])
        XCTAssertTrue(result.ok)
        XCTAssertEqual(result.traceTitle, "安排了自己的作息")
        XCTAssertEqual(result.summary, "22:30 睡 · 07:00 起")
        let persona = try XCTUnwrap(try temp.stores.personas.byId(personaId))
        XCTAssertEqual(persona.quietSleepMinutes, 22 * 60 + 30)
        XCTAssertEqual(persona.quietWakeMinutes, 7 * 60)
        XCTAssertTrue(persona.dndOnSleep, "dnd 是模型的决定，要落库")
        XCTAssertTrue(result.contentForModel.contains("免打扰"))
    }

    func testDeferReplyClampsMinutesAndPlansIntent() async throws {
        try temp.stores.messages.upsert(TestData.message(
            id: "m-user", role: .user, content: "在吗", createdAt: now - 1000
        ))
        let result = await run("defer_reply", arguments: ["minutes": 9999, "reason": "现在不太想说话"])
        XCTAssertTrue(result.ok)
        XCTAssertEqual(result.traceTitle, "决定先不回")
        let intentsList = try temp.stores.agentIntents.byPersona(personaId)
        let intent = try XCTUnwrap(intentsList.first)
        XCTAssertEqual(intent.kind, .reachOut)
        // 5–720 分钟夹取：9999 被夹到 720。
        XCTAssertEqual(intent.earliestAt, now + 720 * 60_000)
        XCTAssertEqual(intent.note, "现在不太想说话")
        let message = try XCTUnwrap(try temp.stores.messages.byId("m-user"))
        XCTAssertNotNil(message.readAt, "选择不回也要留下「已读」")
    }

    // MARK: - 回忆

    func testRecallEmptyIsOkNotAnError() async throws {
        let result = await run("recall", arguments: ["query": "哈尔滨", "depth": "3"])
        XCTAssertTrue(result.ok, "翻不到不是错误：那意味着「想不太起来」")
        XCTAssertEqual(result.traceTitle, "回想了一下")
        XCTAssertEqual(result.summary, "翻了翻，没想起什么")
        XCTAssertTrue(result.contentForModel.contains("确实想不起来了"))
    }

    func testRecallFindsExistingMemory() async throws {
        try temp.stores.memories.upsert(TestData.memory(
            id: "mem-1", content: "对方下个月要去哈尔滨看雪", createdAt: now - 86_400_000
        ))
        let result = await run("recall", arguments: ["query": "哈尔滨", "depth": "2"])
        XCTAssertTrue(result.ok)
        XCTAssertTrue(result.summary.contains("翻了翻记忆"))
        XCTAssertTrue(result.contentForModel.contains("哈尔滨"))
    }

    // MARK: - 人设

    func testUpdatePersonaNeedsACard() async throws {
        // 完全没有设定的人格（连一段提示词都没有）→ 拆不出卡 → 明确拒绝，而不是假装改了。
        var persona = try XCTUnwrap(try temp.stores.personas.byId(personaId))
        persona.prompt = ""
        try temp.stores.personas.upsert(persona)
        let result = await run("update_persona", arguments: [
            "patch": ["identity": ["role": "海边书店的店员"]],
            "reason": "聊到工作"
        ])
        XCTAssertFalse(result.ok)
        XCTAssertTrue(result.errorMessage?.contains("还没有人设") == true)
    }

    func testUpdatePersonaAppliesPatchToCard() async throws {
        var persona = try XCTUnwrap(try temp.stores.personas.byId(personaId))
        persona.personaCardJson = PersonaCardCodec.encode(PersonaCard(
            identity: PersonaIdentity(name: "林岸", role: "自由摄影师"),
            traits: [PersonaTrait(name: "慢热", strength: 0.7)],
            bond: "你们认识很久了。"
        ))
        try temp.stores.personas.upsert(persona)

        let result = await run("update_persona", arguments: [
            "patch": ["voice": ["quirks": "打字前会先停一下"]],
            "reason": "对他更了解了一点"
        ])
        XCTAssertTrue(result.ok, result.errorMessage ?? "")
        XCTAssertEqual(result.traceTitle, "调整了自己的人设")
        let updated = try XCTUnwrap(try temp.stores.personas.byId(personaId))
        let card = PersonaCardCodec.decode(updated.personaCardJson)
        XCTAssertEqual(card.voice.quirks, "打字前会先停一下")
        XCTAssertEqual(card.identity.role, "自由摄影师", "补丁只改写的字段，别的不能丢")
    }

    // MARK: - 联网

    func testWebSearchWithoutWebToolsFailsClearly() async throws {
        let result = await run("web_search", arguments: ["query": "今天有什么新闻"])
        XCTAssertFalse(result.ok)
        XCTAssertEqual(result.errorMessage, "这个人格还没有开启联网")
        XCTAssertTrue(result.contentForModel.contains("这次调用没成功"))
    }

    func testWebSearchClampsCountAndRendersHits() async throws {
        try await enableWebTools()
        StubURLProtocol.enqueue(.json("""
        {"results":[
          {"title":"关于「今天」的一条参考","url":"https://example.com/a","site_name":"示例站点",
           "date":"2026-11-14","summary":"这是摘要","authority":3,"rerank_score":0.9}
        ]}
        """))
        let result = await run("web_search", arguments: ["query": "今天的重要新闻", "count": 99])
        XCTAssertTrue(result.ok, result.errorMessage ?? "")
        XCTAssertEqual(result.traceTitle, "联网搜索")
        XCTAssertTrue(result.contentForModel.contains("搜索结果（按相关度）"))
        XCTAssertTrue(result.contentForModel.contains("https://example.com/a"))

        let body = try XCTUnwrap(StubURLProtocol.recordedBodies.first)
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(root["count"] as? Int, 12, "count 要夹在 1–12")
        XCTAssertEqual(root["query"] as? String, "今天的重要新闻")
    }

    // MARK: - 0.12：五个搜索服务接线

    /// TAVILY：base 留空用规范默认地址；没有联网专用 Key 时复用主 Key。
    func testWebSearchUsesTavilyDefaultBaseAndFallsBackToMainKey() async throws {
        try temp.stores.apiConfigs.upsert(ApiConfigEntity(
            id: "config-1", name: "测试配置", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat",
            webToolsEnabled: true, webToolsProvider: "TAVILY", webToolsBaseUrl: ""
        ))
        try apiKeys.setAPIKey("sk-main", for: "config-1")
        StubURLProtocol.enqueue(.json("""
        {"results":[{"title":"塔维利","url":"https://example.com/t","content":"摘要"}]}
        """))

        let result = await run("web_search", arguments: ["query": "今天", "count": 2])
        XCTAssertTrue(result.ok, result.errorMessage ?? "")
        XCTAssertTrue(result.contentForModel.contains("https://example.com/t"))
        XCTAssertEqual(StubURLProtocol.recordedRequests.first?.url?.absoluteString,
                       "https://api.tavily.com/search")
        let body = try XCTUnwrap(StubURLProtocol.recordedBodies.first)
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["api_key"] as? String, "sk-main", "联网 Key 留空时复用主 Key")
        XCTAssertEqual(json["max_results"] as? Int, 2)
    }

    /// 缺 Key 时不能再报「这个人格还没有开启联网」：那是另一件事，会让用户去开关里瞎找。
    func testWebSearchMissingKeyIsAnExplicitKeyError() async throws {
        try temp.stores.apiConfigs.upsert(ApiConfigEntity(
            id: "config-1", name: "测试配置", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat",
            webToolsEnabled: true, webToolsProvider: "DEEPSEEK",
            webToolsBaseUrl: "https://api.deepseek.com"
        ))
        let result = await run("web_search", arguments: ["query": "今天"])
        XCTAssertFalse(result.ok)
        XCTAssertNotEqual(result.errorMessage, "这个人格还没有开启联网")
        XCTAssertTrue(result.errorMessage?.contains("API Key") == true, result.errorMessage ?? "")
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "没有 Key 不该发请求")
    }

    /// 非 DEEPSEEK 的 web_fetch 由本端 GET，HTML 去掉标签后回灌给模型；工具结果文本形状不变。
    func testWebFetchClientSideStripsHTMLAndKeepsResultShape() async throws {
        try temp.stores.apiConfigs.upsert(ApiConfigEntity(
            id: "config-1", name: "测试配置", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat",
            webToolsEnabled: true, webToolsProvider: "SERPER",
            webToolsBaseUrl: "https://google.serper.dev"
        ))
        try apiKeys.setWebToolsAPIKey("sk-web", for: "config-1")
        StubURLProtocol.enqueue(.json(
            "<html><head><title>一篇文章</title><style>x{}</style></head><body>" +
            "<script>alert(1)</script><p>正文第一段</p><p>正文第二段</p></body></html>"
        ))
        let result = await run("web_fetch", arguments: ["url": "https://example.com/post"])
        XCTAssertTrue(result.ok, result.errorMessage ?? "")
        XCTAssertEqual(result.traceTitle, "读了网页")
        XCTAssertTrue(result.contentForModel.contains("《一篇文章》"))
        XCTAssertTrue(result.contentForModel.contains("https://example.com/post"))
        XCTAssertTrue(result.contentForModel.contains("正文第一段"))
        XCTAssertFalse(result.contentForModel.contains("<p>"), "HTML 标签要去掉")
        XCTAssertFalse(result.contentForModel.contains("alert(1)"), "脚本要去掉")
        let request = try XCTUnwrap(StubURLProtocol.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.value(forHTTPHeaderField: "User-Agent"), "MyFriend/1.0")
    }

    // MARK: - 夹具

    private func enableWebTools() async throws {
        try temp.stores.apiConfigs.upsert(ApiConfigEntity(
            id: "config-1", name: "测试配置", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat",
            webToolsEnabled: true, webToolsBaseUrl: "https://api.deepseek.com"
        ))
        try apiKeys.setWebToolsAPIKey("sk-web", for: "config-1")
    }

    private func run(_ name: String, arguments: [String: Any]) async -> ToolResult {
        let data = try! JSONSerialization.data(withJSONObject: arguments, options: [.sortedKeys])
        let json = String(data: data, encoding: .utf8) ?? "{}"
        let invocation = ToolInvocation(id: "call-1", name: name, argumentsJson: json)
        let persona = (try? temp.stores.personas.byId(personaId)) ?? TestData.persona(id: personaId)
        return await executor.run(invocation, persona: persona, sessionId: sessionId)
    }
}
