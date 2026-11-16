import Foundation
import XCTest
@testable import YapKit

/// 自主循环：处境 → 模型决策 → 执行 → 留痕；以及动作解析的容错（失败回退成 nothing）。
final class AgentLoopTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var repository: MemoryRepository!
    private var intents: AgentIntentSupport!
    private var ticks: InMemoryAgentTickStore!
    private var mood: MoodRepository!
    private var presence: PresenceRepository!

    override func setUpWithError() throws {
        db = try TempDatabase()
        // 1.9：每天最多主动几次由人格的 `proactiveLevel` 决定，设置里已经没有这一项。
        settings = InMemorySettings(AppSettings(agentLoopEnabled: true))
        let index = MemoryVectorIndex(stores: db.stores, now: { TestData.now })
        repository = MemoryRepository(stores: db.stores, vectorIndex: index,
                                      now: { TestData.now })
        ticks = InMemoryAgentTickStore()
        mood = MoodRepository(stores: db.stores, now: { TestData.now })
        intents = AgentIntentSupport(
            stores: db.stores, ticks: ticks, now: { TestData.now }, random: { 0.5 }
        )
        presence = PresenceRepository(
            stores: db.stores, ticks: ticks, intents: intents, now: { TestData.now }
        )
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.sessions.upsert(TestData.session())
        try db.stores.messages.upsert([
            TestData.message(id: "msg-1", role: .user, content: "今天加班到十一点。",
                             createdAt: TestData.now - 5 * 3_600_000),
            TestData.message(id: "msg-2", role: .assistant, content: "那早点睡。",
                             createdAt: TestData.now - 4 * 3_600_000)
        ])
    }

    override func tearDownWithError() throws {
        presence = nil
        mood = nil
        intents = nil
        ticks = nil
        repository = nil
        settings = nil
        db.cleanUp()
        db = nil
    }

    private func makeLoop(
        _ client: CompletionClient,
        openSessionId: String? = nil,
        personaPatchApplier: ((String, String, String) -> Bool)? = nil,
        browseOnce: ((String, Int64) -> AgentLoop.BrowseOutcome?)? = nil
    ) -> AgentLoop {
        AgentLoop(
            stores: db.stores,
            settings: settings,
            completion: client,
            memoryRepository: repository,
            intents: intents,
            ticks: ticks,
            presence: presence,
            mood: mood,
            now: { TestData.now },
            random: { 0.5 },
            openSessionId: { openSessionId },
            personaPatchApplier: personaPatchApplier,
            browseOnce: browseOnce
        )
    }

    // MARK: - 执行

    func testMessageActionDeliversAndRecordsTick() async throws {
        let client = FakeCompletionClient(text: """
        {"thought":"想问问他到家没","actions":[{"type":"message","text":"到家了吗？"}]}
        """)
        let loop = makeLoop(client)
        let outcome = await loop.tick(config: TestData.config(), model: "deepseek-chat", now: TestData.now)

        XCTAssertTrue(outcome.acted)
        XCTAssertTrue(outcome.summary.contains("发了一条消息"))
        let messages = try db.stores.messages.bySession("session-1")
        XCTAssertEqual(messages.last?.role, .assistant)
        XCTAssertEqual(messages.last?.content, "到家了吗？")
        // 没正在看这个会话 → 未读 +1，预览更新。
        let session = try XCTUnwrap(try db.stores.sessions.byId("session-1"))
        XCTAssertEqual(session.unreadCount, 1)
        XCTAssertEqual(session.lastMessagePreview, "到家了吗？")
        // 思考留痕：一句心里话 + 动作 JSON + 消息 id。
        let tick = try XCTUnwrap(ticks.latest(personaId: "persona-1"))
        XCTAssertEqual(tick.thought, "想问问他到家没")
        XCTAssertEqual(tick.messageId, messages.last?.id)
        XCTAssertTrue(tick.actionJson.contains("\"message\""))
        // 下次间隔：说完话之后 25–45 分钟（random 0.5 → 35），再经心情/深夜系数调整。
        XCTAssertGreaterThanOrEqual(outcome.nextDelayMinutes, 25)
        XCTAssertLessThanOrEqual(outcome.nextDelayMinutes, 45 * 2)
    }

    func testWhenConversationIsOpenNoUnreadIsAdded() async throws {
        let client = FakeCompletionClient(text: #"{"thought":"","actions":[{"type":"message","text":"在。"}]}"#)
        let loop = makeLoop(client, openSessionId: "session-1")
        _ = await loop.tick(config: TestData.config(), model: "deepseek-chat", now: TestData.now)
        XCTAssertEqual(try db.stores.sessions.byId("session-1")?.unreadCount, 0)
    }

    func testUnparseableOutputFallsBackToNothing() async throws {
        let client = FakeCompletionClient(text: "我觉得今天不用说什么。")
        let loop = makeLoop(client)
        let before = try db.stores.messages.count()
        let outcome = await loop.tick(config: TestData.config(), model: "deepseek-chat", now: TestData.now)

        XCTAssertFalse(outcome.acted)
        XCTAssertEqual(outcome.summary, "这次没想清楚")
        XCTAssertEqual(try db.stores.messages.count(), before)
        XCTAssertNil(ticks.latest(personaId: "persona-1"), "没想清楚就不该留一条「想过」")
    }

    func testRememberMoodQuietHoursAndIntentActions() async throws {
        let client = FakeCompletionClient(text: """
        {"thought":"记下几件事","actions":[
          {"type":"remember","content":"你最近在准备面试。","drawer":"USER","importance":4},
          {"type":"mood","mood":"雀跃","intensity":4,"reason":"他面试过了"},
          {"type":"quiet_hours","sleep_at":"23:30","wake_at":"07:00","reason":"想早点睡"},
          {"type":"intent","at":"两小时后","note":"问问他面试怎么样","hint":"别太准点"}
        ]}
        """)
        let loop = makeLoop(client)
        let outcome = await loop.tick(config: TestData.config(), model: "deepseek-chat", now: TestData.now)
        XCTAssertTrue(outcome.acted)

        let memories = try db.stores.memories.byPersona("persona-1")
        XCTAssertEqual(memories.count, 1)
        XCTAssertEqual(memories.first?.content, "你最近在准备面试。")
        XCTAssertEqual(memories.first?.drawer, "USER")

        let persona = try XCTUnwrap(try db.stores.personas.byId("persona-1"))
        XCTAssertEqual(persona.mood, "雀跃")
        XCTAssertEqual(persona.moodIntensity, 4)
        XCTAssertEqual(persona.quietSleepMinutes, 23 * 60 + 30)
        XCTAssertEqual(persona.quietWakeMinutes, 7 * 60)

        let intentsRows = try db.stores.agentIntents.byPersona("persona-1")
        XCTAssertEqual(intentsRows.count, 1)
        let intent = try XCTUnwrap(intentsRows.first)
        XCTAssertEqual(intent.note, "问问他面试怎么样")
        XCTAssertEqual(intent.hint, "别太准点")
        XCTAssertGreaterThanOrEqual(intent.earliestAt, TestData.now)
        XCTAssertLessThanOrEqual(intent.latestAt - intent.earliestAt, 6 * 3_600_000)
    }

    func testBrowseAndPersonaPatchReportHonestFailuresWithoutHooks() async throws {
        let client = FakeCompletionClient(text: """
        {"thought":"","actions":[
          {"type":"browse","query":"哈尔滨 天气"},
          {"type":"persona_patch","patch":{"identity":{"name":"林岸"}},"summary":"换个说法"}
        ]}
        """)
        let loop = makeLoop(client)
        let outcome = await loop.tick(config: TestData.config(), model: "deepseek-chat", now: TestData.now)
        XCTAssertTrue(outcome.summary.contains("想上网看看，没看成"))
        XCTAssertTrue(outcome.summary.contains("想改自己但没改成功"))
    }

    func testPersonaPatchUsesInjectedApplier() async throws {
        var applied: [String] = []
        let client = FakeCompletionClient(text: """
        {"thought":"","actions":[{"type":"persona_patch","patch":{"identity":{"name":"林岸"}},"summary":"换个说法"}]}
        """)
        let loop = makeLoop(client, personaPatchApplier: { _, patch, _ in
            applied.append(patch)
            return true
        })
        let outcome = await loop.tick(config: TestData.config(), model: "deepseek-chat", now: TestData.now)
        XCTAssertEqual(outcome.summary, "调整了自己的人设")
        XCTAssertEqual(applied.count, 1)
    }

    func testSleepingOrMissingConfigReturnsWithoutCallingModel() async throws {
        let client = FakeCompletionClient(text: #"{"thought":"","actions":[]}"#)
        let minute = ServiceTime.minuteOfDay(TestData.now)
        try db.stores.personas.upsert(
            TestData.persona(quietSleepMinutes: minute, quietWakeMinutes: (minute + 60) % 1440)
        )
        let sleeping = await makeLoop(client).tick(config: TestData.config(), model: "deepseek-chat",
                                                   now: TestData.now)
        XCTAssertEqual(sleeping.summary, "他在睡觉")
        XCTAssertEqual(client.callCount, 0)

        try db.stores.personas.upsert(TestData.persona())
        let noConfig = await makeLoop(client).tick(config: nil, model: "deepseek-chat", now: TestData.now)
        XCTAssertEqual(noConfig.summary, "还没有可用的 API 配置")
        XCTAssertEqual(client.callCount, 0)
    }

    func testModelFailureBacksOff() async throws {
        let client = FakeCompletionClient([.failure(CompletionFailure("连接超时"))])
        let loop = makeLoop(client)
        let outcome = await loop.tick(config: TestData.config(), model: "deepseek-chat", now: TestData.now)
        XCTAssertFalse(outcome.acted)
        XCTAssertTrue(outcome.summary.contains("连接超时"))
        XCTAssertEqual(outcome.nextDelayMinutes, AgentLoop.backoffMinutes)
    }

    func testTicksFallBackToMemoryWhenTableMissing() async throws {
        // 表被人为删掉（老库、坏库、或者有人在别的分支上改过 Schema）时，
        // 服务层退回进程内留痕：不崩、不往别的表里写假数据，重启后这两类留痕会丢，但功能仍在。
        try db.stores.database.execute("DROP TABLE agent_ticks")
        let store = AgentTickStore(database: db.stores.database)
        XCTAssertTrue(store.isUsingFallback)
        store.insert(AgentTick(personaId: "persona-1", at: TestData.now, thought: "想"))
        XCTAssertEqual(store.latest(personaId: "persona-1")?.thought, "想")
        store.deleteAll()
        XCTAssertNil(store.latest(personaId: "persona-1"))
    }

    // MARK: - 决策解析

    func testDecisionCodecParsesCodeFenceAndNormalizes() {
        let raw = """
        ```json
        {"thought":"想一下","actions":[
          {"type":"send_message","text":"  到家了吗？  "},
          {"type":"message","text":"重复的动作应该被去掉"},
          {"type":"nothing"}
        ]}
        ```
        """
        let parsed = try? XCTUnwrap(AgentDecisionCodec.parse(raw: raw, now: TestData.now))
        XCTAssertEqual(parsed?.thought, "想一下")
        let normalized = AgentDecisionCodec.normalize(try! XCTUnwrap(parsed), now: TestData.now)
        XCTAssertEqual(normalized.actions, [.message(text: "到家了吗？")], "同类型只留一个，nothing 被去掉")

        // 这不是一次决策 → nil；调用方据此回退成 nothing。
        XCTAssertNil(AgentDecisionCodec.parse(raw: "今天没什么想说的", now: TestData.now))
        XCTAssertNil(AgentDecisionCodec.parse(raw: "{}", now: TestData.now))
    }

    func testDecisionCodecSerializesAndSanitizesWindows() throws {
        // 用合法窗口序列化 → 再解析回来，语义不变；非法窗口由 normalize 单独收敛。
        let decision = AgentDecision(
            thought: "留个念头",
            actions: [.planIntent(window: AgentWindow(
                earliestAt: TestData.now + 3_600_000,
                latestAt: TestData.now + 2 * 3_600_000,
                note: "问问他"
            ))]
        )
        let json = AgentDecisionCodec.toJson(decision)
        XCTAssertTrue(json.contains("\"type\":\"intent\""))
        XCTAssertTrue(json.contains("问问他"))

        let parsed = try XCTUnwrap(AgentDecisionCodec.parse(raw: json, now: TestData.now))
        let window = try XCTUnwrap(parsed.actions.first)
        guard case .planIntent(let value) = window else {
            return XCTFail("应该是 intent 动作")
        }
        XCTAssertGreaterThanOrEqual(value.earliestAt, TestData.now)
        XCTAssertGreaterThan(value.latestAt, value.earliestAt)
        XCTAssertLessThanOrEqual(value.latestAt - value.earliestAt, 6 * 3_600_000)
    }

    func testExplicitClockParsingAndRanges() throws {
        // 回归：fullMatch 曾经把「整段匹配」也当成第一个分组，导致 23:30 / 8:30 全解析不出来。
        XCTAssertEqual(AgentDecisionCodec.parseClockMinutes("23:30"), 23 * 60 + 30)
        XCTAssertEqual(AgentDecisionCodec.parseClockMinutes("07:00"), 7 * 60)
        XCTAssertEqual(AgentDecisionCodec.parseClockMinutes("23点30"), 23 * 60 + 30)
        XCTAssertNil(AgentDecisionCodec.parseClockMinutes("25:99"))

        let range = try XCTUnwrap(AgentDecisionCodec.windowFor("明天 20:00-21:30", now: TestData.now))
        let start = ServiceTime.minuteOfDay(range.earliestAt)
        let end = ServiceTime.minuteOfDay(range.latestAt)
        XCTAssertEqual(start, 20 * 60)
        XCTAssertEqual(end, 21 * 60 + 30)
        XCTAssertGreaterThan(range.earliestAt, TestData.now)
    }

    func testWindowForRelativeAndPeriodPhrases() throws {
        let inTwoHours = try XCTUnwrap(AgentDecisionCodec.windowFor("两小时后", now: TestData.now))
        XCTAssertEqual(inTwoHours.earliestAt, TestData.now + 2 * 3_600_000)
        XCTAssertGreaterThan(inTwoHours.latestAt, inTwoHours.earliestAt)

        let tomorrowMorning = try XCTUnwrap(AgentDecisionCodec.windowFor("明早", now: TestData.now))
        XCTAssertGreaterThan(tomorrowMorning.earliestAt, TestData.now)
        // 「明早」落在下一个 7:00–9:30 之间；以固定时钟（当地 00:13）算，大约是 30 小时后。
        XCTAssertLessThan(tomorrowMorning.earliestAt, TestData.now + 32 * 3_600_000)
        // 跨度收敛在 15 分钟–6 小时之间。
        XCTAssertGreaterThanOrEqual(tomorrowMorning.latestAt - tomorrowMorning.earliestAt, 15 * 60_000)
        XCTAssertLessThanOrEqual(tomorrowMorning.latestAt - tomorrowMorning.earliestAt, 6 * 3_600_000)

        XCTAssertNil(AgentDecisionCodec.windowFor("   ", now: TestData.now))
        // 认不出来的说法给一个安全的兜底窗口，而不是崩掉。
        let fallback = try XCTUnwrap(AgentDecisionCodec.windowFor("某个时候", now: TestData.now))
        XCTAssertGreaterThan(fallback.earliestAt, TestData.now)
    }
}
