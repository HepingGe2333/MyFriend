import Foundation
import XCTest
@testable import YapKit

/// 主动对话的诊断（0.13 真机反馈第一批）：七道闸每一道都要能说清「为什么没主动」，
/// 并且每一拍的结论都要进日志。
final class ProactiveDiagnosticsTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var ticks: InMemoryAgentTickStore!
    private var intents: AgentIntentSupport!
    private var mood: MoodRepository!
    private var logs: [String] = []

    private let personaId = "persona-1"

    override func setUpWithError() throws {
        db = try TempDatabase()
        settings = InMemorySettings(AppSettings(agentLoopEnabled: true))
        ticks = InMemoryAgentTickStore()
        mood = MoodRepository(stores: db.stores, now: { TestData.now })
        intents = AgentIntentSupport(stores: db.stores, ticks: ticks, now: { TestData.now }, random: { 0.5 })
        logs = []
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.sessions.upsert(TestData.session(lastMessageAt: TestData.now - 4 * 3_600_000))
    }

    override func tearDownWithError() throws {
        ticks = nil
        intents = nil
        mood = nil
        settings = nil
        db.cleanUp()
        db = nil
    }

    private func makeController(roll: Double = 0.0) -> HeartbeatController {
        HeartbeatController(
            stores: db.stores,
            settings: settings,
            intents: intents,
            ticks: ticks,
            mood: mood,
            tickRunner: { _ in AgentLoop.Outcome(nextDelayMinutes: 6, acted: false, summary: "") },
            now: { TestData.now },
            roll: { roll },
            log: { [unowned self] in self.logs.append($0) }
        )
    }

    private func setLastInteraction(minutesAgo: Int) throws {
        let at = TestData.now - Int64(minutesAgo) * 60_000
        var current = settings.load()
        current.heartbeatLastResetAt = at
        settings.save(current)
        try db.stores.messages.upsert([
            TestData.message(id: "msg-last", role: .user, content: "先睡了。", createdAt: at)
        ])
    }

    // MARK: - 七道闸

    func testLoopDisabledIsReported() {
        settings.save(AppSettings(agentLoopEnabled: false))
        let diagnosis = makeController().explain(now: TestData.now)
        XCTAssertEqual(.loopDisabled, diagnosis.gate)
        XCTAssertTrue(diagnosis.logLine().contains("常驻循环"))
    }

    func testNoPersonaIsReported() throws {
        try db.stores.personas.deleteAll()
        let diagnosis = makeController().explain(now: TestData.now)
        XCTAssertEqual(.noPersona, diagnosis.gate)
    }

    func testAsleepIsReported() throws {
        let minute = ServiceTime.minuteOfDay(TestData.now)
        var persona = TestData.persona()
        persona.sleepEnabled = true
        persona.quietSleepMinutes = minute
        persona.quietWakeMinutes = (minute + 120) % 1440
        try db.stores.personas.upsert(persona)
        let diagnosis = makeController().explain(now: TestData.now)
        XCTAssertEqual(.asleep, diagnosis.gate)
        XCTAssertTrue(diagnosis.detail.contains("分钟后醒"))
    }

    func testBusyActivityIsReported() throws {
        var persona = TestData.persona()
        persona.activityJson = PersonaActivity(
            kind: .work, activity: "在事务所赶图", minutes: 120,
            availability: .busy, startedAt: TestData.now - 60_000
        ).jsonString()
        try db.stores.personas.upsert(persona)
        let diagnosis = makeController().explain(now: TestData.now)
        XCTAssertEqual(.busy, diagnosis.gate)
        XCTAssertEqual("在事务所赶图", diagnosis.activity)
    }

    func testCoolingDownReportsMinutesLeft() throws {
        try setLastInteraction(minutesAgo: 2)
        let diagnosis = makeController().explain(now: TestData.now)
        XCTAssertEqual(.coolingDown, diagnosis.gate)
        XCTAssertEqual(Heartbeat.COOL_DOWN_MINUTES - 2, diagnosis.cooldownMinutesLeft, accuracy: 0.01)
    }

    func testTooSoonAfterTickIsReported() throws {
        try setLastInteraction(minutesAgo: 120)
        ticks.insert(AgentTick(
            id: "tick-1", personaId: personaId, at: TestData.now - 60_000,
            thought: "刚想过", actionJson: "{}", outcome: "什么都没做", messageId: nil
        ))
        let diagnosis = makeController().explain(now: TestData.now)
        XCTAssertEqual(.tooSoonAfterTick, diagnosis.gate)
    }

    func testDailyCapReachedIsReported() throws {
        try setLastInteraction(minutesAgo: 120)
        var persona = TestData.persona()
        persona.proactiveLevel = 1   // 每天最多 1 次
        try db.stores.personas.upsert(persona)
        // 今天说过一次（用当天开始之后的时间，跨时区也不会掉到昨天）。
        let spokenAt = ServiceTime.startOfDay(TestData.now) + 60_000
        ticks.insert(AgentTick(
            id: "tick-spoken", personaId: personaId, at: spokenAt,
            thought: "想找他", actionJson: "{}", outcome: "发了一条消息",
            messageId: "msg-proactive"
        ))
        let diagnosis = makeController().explain(now: TestData.now)
        XCTAssertEqual(.dailyCapReached, diagnosis.gate)
        XCTAssertEqual(1, diagnosis.spokenToday)
        XCTAssertEqual(1, diagnosis.dailyCap)
    }

    func testReadyGateShowsProbability() throws {
        try setLastInteraction(minutesAgo: 180)
        let diagnosis = makeController().explain(now: TestData.now)
        XCTAssertEqual(.ready, diagnosis.gate)
        XCTAssertTrue(diagnosis.detail.contains("概率"))
        XCTAssertGreaterThan(diagnosis.minutesSinceInteraction, 170)
    }

    // MARK: - 日志（可观测）

    func testEveryBeatWritesOneLogLine() async throws {
        try setLastInteraction(minutesAgo: 2)
        _ = await makeController(roll: 0.99).beat(now: TestData.now)
        XCTAssertEqual(1, logs.count)
        XCTAssertTrue(logs[0].hasPrefix("[heartbeat]"))
        XCTAssertTrue(logs[0].contains("刚聊完"), "日志要说明为什么没动：\(logs[0])")
    }

    func testFiredBeatIsLogged() async throws {
        try setLastInteraction(minutesAgo: 240)
        let beat = await makeController(roll: 0.0).beat(now: TestData.now)
        XCTAssertTrue(beat.fired)
        XCTAssertTrue(logs.contains { $0.contains("起心动念") })
    }

    // MARK: - 修复：换人格之后不该被上一个人格的「刚想过」挡住

    func testRecentTickOfAnotherPersonaDoesNotBlock() async throws {
        var other = TestData.persona(id: "persona-2", isDefault: false)
        other.name = "另一个人"
        try db.stores.personas.upsert(other)
        // 另一个人格 1 分钟前刚想过；当前人格已经 4 小时没互动。
        ticks.insert(AgentTick(
            id: "tick-other", personaId: "persona-2", at: TestData.now - 60_000,
            thought: "刚想过", actionJson: "{}", outcome: "什么都没做", messageId: nil
        ))
        try setLastInteraction(minutesAgo: 240)
        var current = settings.load()
        current.activePersonaId = personaId
        settings.save(current)

        let diagnosis = makeController().explain(now: TestData.now)
        XCTAssertEqual(.ready, diagnosis.gate, "别人的思考记录不该挡住这个人格")
        let beat = await makeController(roll: 0.0).beat(now: TestData.now)
        XCTAssertTrue(beat.fired, "别人的 tick 不该让这一拍直接返回「刚想过」")
    }
}
