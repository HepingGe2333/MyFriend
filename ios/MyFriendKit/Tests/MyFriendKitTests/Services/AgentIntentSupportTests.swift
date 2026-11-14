import Foundation
import XCTest
@testable import MyFriendKit

/// 「他想什么时候找你」的克制：每日上限、作息、最小间隔、对话正热，以及规则 → 念头的散开。
final class AgentIntentSupportTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var ticks: InMemoryAgentTickStore!
    private var roll: Double = 0.9
    private var intents: AgentIntentSupport!

    override func setUpWithError() throws {
        db = try TempDatabase()
        // 1.9：每日上限来自人格的 `proactiveLevel`（默认 3），不再来自设置。
        settings = InMemorySettings(AppSettings())
        ticks = InMemoryAgentTickStore()
        roll = 0.9
        intents = AgentIntentSupport(
            stores: db.stores, ticks: ticks,
            now: { TestData.now }, random: { [weak self] in self?.roll ?? 0.9 }
        )
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.sessions.upsert(TestData.session(lastMessageAt: TestData.now - 4 * 3_600_000))
    }

    override func tearDownWithError() throws {
        intents = nil
        ticks = nil
        settings = nil
        db.cleanUp()
        db = nil
    }

    func testSleepingPersonaBlocksReachOut() throws {
        let minute = ServiceTime.minuteOfDay(TestData.now)
        try db.stores.personas.upsert(
            TestData.persona(quietSleepMinutes: minute, quietWakeMinutes: (minute + 60) % 1440)
        )
        XCTAssertEqual(
            intents.canReachOut(personaId: "persona-1", now: TestData.now, isConversationHot: false),
            ReachOutCheck(allowed: false, reason: "他这个点在睡觉")
        )
    }

    func testHotConversationAndMinimumGapBlockReachOut() throws {
        XCTAssertFalse(intents.canReachOut(personaId: "persona-1", now: TestData.now,
                                           isConversationHot: true).allowed)
        // 半小时前刚主动说过：两条主动消息之间至少隔 90 分钟。
        ticks.insert(AgentTick(personaId: "persona-1", at: TestData.now - 30 * 60_000, messageId: "msg-a"))
        let check = intents.canReachOut(personaId: "persona-1", now: TestData.now, isConversationHot: false)
        XCTAssertFalse(check.allowed)
        XCTAssertEqual(check.reason, "刚说完没多久，再等等")

        // 两小时前说过（已经过了 90 分钟的最小间隔）：可以开口。
        ticks.deleteAll()
        ticks.insert(AgentTick(personaId: "persona-1", at: TestData.now - 7_200_000, messageId: "msg-b"))
        XCTAssertTrue(
            intents.canReachOut(personaId: "persona-1", now: TestData.now, isConversationHot: false).allowed
        )
    }

    func testDailyCapCountsOnlyToday() throws {
        // 固定时钟是当地 00:13：昨天那条不算今天。
        ticks.insert(AgentTick(personaId: "persona-1", at: TestData.now - 86_400_000, messageId: "msg-yesterday"))
        XCTAssertEqual(intents.spokenToday(personaId: "persona-1", now: TestData.now), 0)
        ticks.insert(AgentTick(personaId: "persona-1", at: TestData.now - 60_000, messageId: "msg-today"))
        XCTAssertEqual(intents.spokenToday(personaId: "persona-1", now: TestData.now), 1)
    }

    func testPlanCollapsesSameNoteAndMarksSent() throws {
        let window = AgentWindow(earliestAt: TestData.now + 3_600_000, latestAt: TestData.now + 5_400_000,
                                 note: "问问他面试怎么样")
        let first = intents.plan(personaId: "persona-1", window: window, note: "问问他面试怎么样",
                                 now: TestData.now)
        let second = intents.plan(
            personaId: "persona-1",
            window: AgentWindow(earliestAt: TestData.now + 7_200_000, latestAt: TestData.now + 9_000_000,
                                note: "问问他面试怎么样"),
            note: "问问他面试怎么样",
            now: TestData.now
        )
        XCTAssertEqual(first, second, "同一句话只留一个念头，只把窗口往后挪")
        let rows = try db.stores.agentIntents.byPersona("persona-1")
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows.first?.earliestAt, TestData.now + 7_200_000)

        intents.markSent(id: first, at: TestData.now)
        XCTAssertEqual(try db.stores.agentIntents.byId(first)?.status, .sent)
        XCTAssertEqual(try db.stores.agentIntents.byId(first)?.sentAt, TestData.now)
    }

    func testExpireStaleAndCollapseDuplicates() throws {
        try db.stores.agentIntents.upsert([
            AgentIntentEntity(id: "i-expired", personaId: "persona-1", kind: .reachOut,
                              earliestAt: TestData.now - 7_200_000, latestAt: TestData.now - 3_600_000,
                              note: "过期的", createdAt: TestData.now - 7_200_000),
            AgentIntentEntity(id: "i-dup-1", personaId: "persona-1", kind: .reachOut,
                              earliestAt: TestData.now, latestAt: TestData.now + 3_600_000,
                              note: "同一句", createdAt: TestData.now - 1000),
            AgentIntentEntity(id: "i-dup-2", personaId: "persona-1", kind: .reachOut,
                              earliestAt: TestData.now, latestAt: TestData.now + 3_600_000,
                              note: "同一句", createdAt: TestData.now - 500)
        ])
        XCTAssertEqual(intents.expireStale(now: TestData.now), 1)
        XCTAssertEqual(intents.collapseDuplicates(), 1)
        XCTAssertEqual(try db.stores.agentIntents.byId("i-expired")?.status, .expired)
        XCTAssertEqual(try db.stores.agentIntents.byId("i-dup-1")?.status, .pending)
        XCTAssertEqual(try db.stores.agentIntents.byId("i-dup-2")?.status, .dropped)
        XCTAssertEqual(intents.pendingCount(personaId: "persona-1"), 1)
    }

    func testDueRuleBecomesWindowedIntentAndReschedules() throws {
        try db.stores.proactiveRules.upsert(
            ProactiveRuleEntity(id: "rule-1", personaId: "persona-1", kind: .daily,
                                timeOfDayMinutes: 22 * 60, hint: "晚点问问今天怎么样", note: "他答应过",
                                enabled: true, source: .ai, nextTriggerAt: TestData.now - 60_000,
                                createdAt: TestData.now - 86_400_000, updatedAt: TestData.now)
        )
        roll = 0.9   // 大于 18% 的「今天不想说」，这一轮真的要排
        XCTAssertEqual(intents.materializeDueRules(now: TestData.now), 1)
        let rows = try db.stores.agentIntents.byPersona("persona-1")
        XCTAssertEqual(rows.count, 1)
        let intent = try XCTUnwrap(rows.first)
        XCTAssertEqual(intent.note, "他答应过")
        XCTAssertEqual(intent.ruleId, "rule-1")
        // 窗口不再准点：起点在 5–90 分钟后散开，跨度 30–120 分钟。
        XCTAssertGreaterThanOrEqual(intent.earliestAt - TestData.now, 5 * 60_000)
        XCTAssertLessThanOrEqual(intent.earliestAt - TestData.now, 90 * 60_000)
        XCTAssertGreaterThanOrEqual(intent.latestAt - intent.earliestAt, 30 * 60_000)
        XCTAssertLessThanOrEqual(intent.latestAt - intent.earliestAt, 120 * 60_000)
        // 规则本身要排到下一次。
        let rule = try XCTUnwrap(try db.stores.proactiveRules.byId("rule-1"))
        XCTAssertGreaterThan(rule.nextTriggerAt, TestData.now)
    }

    func testSkipChancePushesRuleWithoutCreatingIntent() throws {
        try db.stores.proactiveRules.upsert(
            ProactiveRuleEntity(id: "rule-1", personaId: "persona-1", kind: .interval,
                                intervalMinutes: 180, hint: "想说话", note: "每隔几小时",
                                enabled: true, source: .ai, nextTriggerAt: TestData.now - 60_000,
                                createdAt: TestData.now - 86_400_000, updatedAt: TestData.now)
        )
        roll = 0.0   // < 18%：今天就是不想说话
        XCTAssertEqual(intents.materializeDueRules(now: TestData.now), 0)
        XCTAssertTrue(try db.stores.agentIntents.byPersona("persona-1").isEmpty)
        XCTAssertGreaterThan(try XCTUnwrap(try db.stores.proactiveRules.byId("rule-1")).nextTriggerAt,
                             TestData.now)
    }
}
