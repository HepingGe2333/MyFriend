import Foundation
import XCTest
@testable import YapKit

/// 主动唤醒的「现在试一次」：走 `AgentLoop` 既有的开口路径（拼人格 + 记忆 + 最近历史 → 落消息 + 通知回调），
/// 失败把原因写回规则的 `lastError`，成功清掉上一次的红字；排期一律不动。
final class ProactiveFireTests: XCTestCase {

    private var temp: TempDatabase!
    private var settings: InMemorySettings!
    private var memory: MemoryRepository!
    private var mood: MoodRepository!
    private var intents: AgentIntentSupport!
    private var ticks: AgentTickStore!
    private var rules: ProactiveRuleService!
    private var completion: FakeCompletionClient!
    private var loop: AgentLoop!
    private var delivered: [MessageEntity] = []

    private let personaId = "persona-1"
    private let sessionId = "session-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        settings = InMemorySettings(AppSettings(activePersonaId: personaId, agentLoopEnabled: false))
        memory = MemoryRepository(stores: temp.stores, vectorIndex: MemoryVectorIndex(stores: temp.stores))
        mood = MoodRepository(stores: temp.stores, now: { self.now })
        ticks = AgentTickStore(database: temp.stores.database)
        intents = AgentIntentSupport(stores: temp.stores, ticks: ticks)
        rules = ProactiveRuleService(stores: temp.stores, now: { self.now })
        completion = FakeCompletionClient(
            fallback: .success(CompletionResult(text: "在吗，今天过得怎么样？"))
        )
        delivered = []

        try temp.stores.personas.upsert(TestData.persona(id: personaId))
        try temp.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
        try temp.stores.messages.upsert([
            TestData.message(id: "m-1", role: .user, content: "今天的会开得有点久", createdAt: now - 600_000),
            TestData.message(id: "m-2", role: .assistant, content: "辛苦啦，先喝口水", createdAt: now - 500_000)
        ])
        try temp.stores.apiConfigs.upsert(ApiConfigEntity(
            id: "config-1", name: "测试配置", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat"
        ))

        loop = AgentLoop(
            stores: temp.stores,
            settings: settings,
            completion: completion,
            memoryRepository: memory,
            intents: intents,
            ticks: ticks,
            presence: PresenceRepository(stores: temp.stores, ticks: ticks, intents: intents, now: { self.now }),
            mood: mood,
            now: { self.now },
            random: { 0.5 },
            onMessageDelivered: { [weak self] message, _ in self?.delivered.append(message) }
        )
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    private func makeRule(note: String = "每晚问一句") throws -> String {
        let result = rules.createManual(
            personaId: personaId,
            sessionId: sessionId,
            kind: .daily,
            timeOfDayMinutes: 22 * 60,
            intervalMinutes: nil,
            hint: "像平时一样轻声问一句",
            note: note,
            at: now - 60_000
        )
        return try XCTUnwrap(try result.get())
    }

    func testSuccessfulFireWritesMessageAndNotifies() async throws {
        let ruleId = try makeRule()
        let beforeRule = try XCTUnwrap(try temp.stores.proactiveRules.byId(ruleId))
        let outcome = await loop.fireProactiveRule(
            ruleId: ruleId, config: TestData.config(), model: "deepseek-chat", now: now
        )
        XCTAssertTrue(outcome.succeeded, outcome.error ?? "")
        let message = try XCTUnwrap(try temp.stores.messages.latest(sessionId: sessionId))
        XCTAssertEqual(message.role, .assistant)
        XCTAssertEqual(message.content, "在吗，今天过得怎么样？")
        XCTAssertEqual(delivered.count, 1, "App 层靠这个回调发通知")
        let session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertEqual(session.lastMessagePreview, "在吗，今天过得怎么样？")
        let afterRule = try XCTUnwrap(try temp.stores.proactiveRules.byId(ruleId))
        XCTAssertNil(afterRule.lastError, "成功之后不该留着上一次的失败原因")
        XCTAssertEqual(afterRule.nextTriggerAt, beforeRule.nextTriggerAt, "「试一次」不改排期")
        XCTAssertEqual(afterRule.lastTriggeredAt, beforeRule.lastTriggeredAt)
        // 口径（与 Android `ProactiveRepository.triggerNow` 一致）：用户在主动唤醒页点的
        // 「现在试一次」是一次用户触发的对话生成，用量记 CHAT（用量页计入聊天）；
        // 只有 AgentLoop 的自主开口（心跳/念头）才记 AGENT。
        XCTAssertEqual(completion.calls.last?.kind, .chat)
    }

    /// 对照口径：自主思考/心跳里开口仍然记 AGENT（两端已一致），不要被「试一次」带跑。
    func testAutonomousTickStaysAgentUsage() async {
        settings.save(AppSettings(activePersonaId: personaId, agentLoopEnabled: true))
        completion.fallback = .success(CompletionResult(
            text: #"{"thought":"随便想想","actions":[{"type":"nothing"}]}"#
        ))
        _ = await loop.tick(config: TestData.config(), model: "deepseek-chat", now: now)
        XCTAssertEqual(completion.calls.last?.kind, .agent,
                       "自主开口记 AGENT；只有用户点的「现在试一次」记 CHAT")
    }

    func testFailureStoresLocalizedReasonAndWritesNothing() async throws {
        let ruleId = try makeRule()
        completion.fallback = .failure(CompletionFailure("网络断了"))
        let outcome = await loop.fireProactiveRule(
            ruleId: ruleId, config: TestData.config(), model: "deepseek-chat", now: now
        )
        XCTAssertFalse(outcome.succeeded)
        XCTAssertTrue(outcome.error?.contains("网络断了") == true)
        let rule = try XCTUnwrap(try temp.stores.proactiveRules.byId(ruleId))
        XCTAssertTrue(rule.lastError?.contains("网络断了") == true, "失败原因要落库给列表行显示")
        XCTAssertEqual(try temp.stores.messages.count(sessionId: sessionId), 2, "失败不写消息")
        XCTAssertTrue(delivered.isEmpty)
    }

    func testSuccessClearsPreviousError() async throws {
        let ruleId = try makeRule()
        var rule = try XCTUnwrap(try temp.stores.proactiveRules.byId(ruleId))
        rule.lastError = "上次也是网络断了"
        try temp.stores.proactiveRules.upsert(rule)

        let outcome = await loop.fireProactiveRule(
            ruleId: ruleId, config: TestData.config(), model: "deepseek-chat", now: now
        )
        XCTAssertTrue(outcome.succeeded, outcome.error ?? "")
        XCTAssertNil(try XCTUnwrap(try temp.stores.proactiveRules.byId(ruleId)).lastError)
    }

    func testMissingRuleFailsWithoutWriting() async {
        let outcome = await loop.fireProactiveRule(
            ruleId: "no-such-rule", config: TestData.config(), model: "deepseek-chat", now: now
        )
        XCTAssertFalse(outcome.succeeded)
        XCTAssertTrue(outcome.error?.contains("规则") == true)
    }

    func testMissingConfigFailsWithReason() async throws {
        let ruleId = try makeRule()
        let outcome = await loop.fireProactiveRule(ruleId: ruleId, config: nil, model: "deepseek-chat", now: now)
        XCTAssertFalse(outcome.succeeded)
        XCTAssertTrue(outcome.error?.contains("API") == true)
        XCTAssertEqual(try XCTUnwrap(try temp.stores.proactiveRules.byId(ruleId)).lastError, outcome.error)
    }

    func testAsleepFailsInsteadOfWakingHim() async throws {
        let ruleId = try makeRule()
        // 22:30 睡、07:00 起，把 now 放到当地凌晨 2 点。
        var persona = try XCTUnwrap(try temp.stores.personas.byId(personaId))
        persona.sleepEnabled = true
        persona.quietSleepMinutes = 22 * 60 + 30
        persona.quietWakeMinutes = 7 * 60
        try temp.stores.personas.upsert(persona)
        let calendar = ServiceTime.calendar()
        let current = calendar.dateComponents([.hour], from: Date(timeIntervalSince1970: Double(now) / 1000))
        // 只有当地 2 点前后才测得到「在睡」，换一个明确落在睡眠窗口里的时刻。
        _ = current
        let startOfDay = ServiceTime.startOfDay(now, calendar: calendar)
        let twoAM = startOfDay + Int64(2 * 3_600_000)
        let outcome = await loop.fireProactiveRule(
            ruleId: ruleId, config: TestData.config(), model: "deepseek-chat", now: twoAM
        )
        XCTAssertFalse(outcome.succeeded)
        XCTAssertTrue(outcome.error?.contains("睡") == true, outcome.error ?? "")
        XCTAssertEqual(completion.callCount, 0, "在睡就不该问模型")
    }

    /// 「重复点击只跑一次」的可测那一半：同一时间只允许一轮主动开口在跑。
    ///
    /// 界面侧靠 `triggeringId` 把按钮禁用（SwiftUI 状态，`swift test` 覆盖不到）；
    /// Kit 侧靠 `AsyncMutex` 串行化，两个请求不会同时各写一条。
    func testConcurrentFiresNeverOverlap() async throws {
        let counting = ConcurrencyTrackingClient()
        let serialLoop = AgentLoop(
            stores: temp.stores,
            settings: settings,
            completion: counting,
            memoryRepository: memory,
            intents: intents,
            ticks: ticks,
            presence: PresenceRepository(stores: temp.stores, ticks: ticks, intents: intents, now: { self.now }),
            mood: mood,
            now: { self.now },
            random: { 0.5 }
        )
        let ruleId = try makeRule()
        async let first = serialLoop.fireProactiveRule(
            ruleId: ruleId, config: TestData.config(), model: "deepseek-chat", now: now
        )
        async let second = serialLoop.fireProactiveRule(
            ruleId: ruleId, config: TestData.config(), model: "deepseek-chat", now: now
        )
        let outcomes = await [first, second]
        XCTAssertTrue(outcomes.allSatisfy(\.succeeded))
        XCTAssertEqual(counting.maxConcurrent, 1, "两次触发必须串行，否则会并发写两条几乎一样的消息")
    }
}

/// 记录并发进出的假模型：胶囊里最多同时有几个请求。
private final class ConcurrencyTrackingClient: CompletionClient {
    private let lock = NSLock()
    private var active = 0
    private(set) var maxConcurrent = 0

    func complete(
        config: CompletionConfig,
        model: String,
        messages: [CompletionMessage],
        params: CompletionParams,
        kind: CompletionKind,
        context: UsageContext
    ) async throws -> CompletionResult {
        lock.lock()
        active += 1
        maxConcurrent = max(maxConcurrent, active)
        lock.unlock()
        try await Task.sleep(nanoseconds: 30_000_000)
        lock.lock()
        active -= 1
        lock.unlock()
        return CompletionResult(text: "记得早点休息。")
    }
}
