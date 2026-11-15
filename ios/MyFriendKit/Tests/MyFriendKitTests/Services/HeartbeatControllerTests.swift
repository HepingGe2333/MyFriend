import Foundation
import XCTest
@testable import MyFriendKit

/// 心跳编排：假时钟 + 假骰子 + 假依赖，一条一条钉住 Android 的那几个行为。
final class HeartbeatControllerTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var ticks: InMemoryAgentTickStore!
    private var intents: AgentIntentSupport!
    private var mood: MoodRepository!
    private var tickCalls: [Int64] = []
    private var tickOutcome: AgentLoop.Outcome?

    override func setUpWithError() throws {
        db = try TempDatabase()
        // 1.9：每天最多主动几次由人格的 `proactiveLevel` 决定（默认 3），不再来自设置。
        settings = InMemorySettings(AppSettings(agentLoopEnabled: true))
        ticks = InMemoryAgentTickStore()
        mood = MoodRepository(stores: db.stores, now: { TestData.now })
        intents = AgentIntentSupport(
            stores: db.stores, ticks: ticks, now: { TestData.now }, random: { 0.5 }
        )
        tickCalls = []
        tickOutcome = AgentLoop.Outcome(nextDelayMinutes: 6, acted: true, summary: "发了一条消息")
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.sessions.upsert(TestData.session(lastMessageAt: TestData.now - 4 * 3_600_000))
    }

    override func tearDownWithError() throws {
        tickOutcome = nil
        ticks = nil
        intents = nil
        mood = nil
        settings = nil
        db.cleanUp()
        db = nil
    }

    private func makeController(roll: @escaping () -> Double = { 0.0 }) -> HeartbeatController {
        HeartbeatController(
            stores: db.stores,
            settings: settings,
            intents: intents,
            ticks: ticks,
            mood: mood,
            tickRunner: { [weak self] now in
                self?.tickCalls.append(now)
                return self?.tickOutcome
            },
            now: { TestData.now },
            roll: roll
        )
    }

    /// 让「距离上次互动过了 N 分钟」变成一件确定的事。
    private func setLastInteraction(minutesAgo: Int) throws {
        let at = TestData.now - Int64(minutesAgo) * 60_000
        var current = settings.load()
        current.heartbeatLastResetAt = at
        settings.save(current)
        try db.stores.messages.upsert([
            TestData.message(id: "msg-last", role: .user, content: "先睡了。", createdAt: at)
        ])
    }

    // MARK: - 刚聊完不掷

    func testJustTalkedDoesNotRoll() async throws {
        try setLastInteraction(minutesAgo: 2)
        let controller = makeController(roll: { 0.0 })   // 骰子一定中，但根本不该掷
        let beat = await controller.beat(now: TestData.now)
        XCTAssertFalse(beat.fired)
        XCTAssertEqual(beat.why, "刚聊完，先安静一会儿")
        XCTAssertTrue(tickCalls.isEmpty, "收尾期不掷骰子，更不该叫他去思考")
    }

    // MARK: - 占用时间（人设引导 §4）：BUSY 不张口、LIMITED 降概率

    private func setActivity(_ activity: PersonaActivity) throws {
        var persona = TestData.persona()
        persona.activityJson = activity.jsonString()
        try db.stores.personas.upsert(persona)
    }

    func testBusyActivitySkipsProactiveBeat() async throws {
        try setLastInteraction(minutesAgo: 120)
        try setActivity(PersonaActivity(
            kind: .work, activity: "在事务所赶图", minutes: 120,
            availability: .busy, startedAt: TestData.now - 60_000
        ))
        let controller = makeController(roll: { 0.0 })   // 骰子一定中，但不该走到掷骰子
        let beat = await controller.beat(now: TestData.now)
        XCTAssertFalse(beat.fired)
        XCTAssertEqual(beat.why, "他在忙")
        XCTAssertTrue(tickCalls.isEmpty, "在忙的时候不该叫他去思考怎么开口")
    }

    func testLimitedActivityLowersProbabilityWithoutBlocking() async throws {
        try setLastInteraction(minutesAgo: 120)
        try setActivity(PersonaActivity(
            kind: .work, activity: "在开会", minutes: 120,
            availability: .limited, startedAt: TestData.now - 60_000
        ))
        // 骰子一定中：LIMITED 只是把概率压到 0.4 倍，不是「禁止开口」（§4）。
        let certain = await makeController(roll: { 0.0 }).beat(now: TestData.now)
        XCTAssertNotEqual(certain.why, "他在忙", "LIMITED 不是 BUSY")
        XCTAssertTrue(certain.fired, "骰子必中时半忙仍然会开口")
        // 高骰子一定不中：概率被压过之后这一拍就不该开口。
        let unlikely = await makeController(roll: { 0.99 }).beat(now: TestData.now)
        XCTAssertFalse(unlikely.fired)
        XCTAssertFalse(tickCalls.isEmpty, "上面那次必中的拍子应当真的叫过他")
    }

    /// 过期的活动不该继续影响主动（读取时判断，不需要定时任务）。
    func testExpiredActivityDoesNotBlockProactive() async throws {
        try setLastInteraction(minutesAgo: 120)
        try setActivity(PersonaActivity(
            kind: .work, activity: "在开会", minutes: 30,
            availability: .busy, startedAt: TestData.now - 60 * 60_000
        ))
        let controller = makeController(roll: { 0.0 })
        let beat = await controller.beat(now: TestData.now)
        XCTAssertNotEqual(beat.why, "他在忙")
    }

    // MARK: - 掷中后重置

    func testFiringRunsAgentLoopAndResetsClock() async throws {
        try setLastInteraction(minutesAgo: 240)   // 四小时没人理，概率在上限附近
        let controller = makeController(roll: { 0.0 })
        let beat = await controller.beat(now: TestData.now)

        XCTAssertTrue(beat.fired)
        XCTAssertTrue(beat.acted)
        XCTAssertEqual(beat.summary, "发了一条消息")
        XCTAssertEqual(tickCalls, [TestData.now])

        let after = settings.load()
        XCTAssertEqual(after.heartbeatLastResetAt, TestData.now, "跑完就要把概率时钟拨回现在")
        XCTAssertEqual(after.heartbeatLastFiredAt, TestData.now)
    }

    func testNoFireWhenRollIsAboveProbability() async throws {
        try setLastInteraction(minutesAgo: 240)
        let controller = makeController(roll: { 0.999 })
        let beat = await controller.beat(now: TestData.now)
        XCTAssertFalse(beat.fired)
        XCTAssertEqual(beat.why, "")
        XCTAssertTrue(tickCalls.isEmpty)
        // 没掷中不该重置时钟（否则沉默越久越容易连击）。
        XCTAssertEqual(settings.load().heartbeatLastResetAt, TestData.now - 240 * 60_000)
    }

    // MARK: - 睡眠 / 上限 / 额度

    func testSleepingPersonaNeverRolls() async throws {
        let minute = ServiceTime.minuteOfDay(TestData.now)
        try db.stores.personas.upsert(
            TestData.persona(quietSleepMinutes: minute, quietWakeMinutes: (minute + 120) % 1440)
        )
        let controller = makeController(roll: { 0.0 })
        let beat = await controller.beat(now: TestData.now)
        XCTAssertFalse(beat.fired)
        XCTAssertEqual(beat.why, "他在睡觉")
        XCTAssertTrue(tickCalls.isEmpty)
    }

    func testDailyCapStopsAfterEnoughMessages() async throws {
        try setLastInteraction(minutesAgo: 240)
        // 今天已经主动说过 3 次（上限 3）：掷中也该在「开口前」被拦下。
        // 三次都落在核心收尾期之外（>10 分钟）、又都在今天之内（固定时钟是当地 00:13）。
        ticks.insert(AgentTick(personaId: "persona-1", at: TestData.now - 660_000, messageId: "msg-a"))
        ticks.insert(AgentTick(personaId: "persona-1", at: TestData.now - 700_000, messageId: "msg-b"))
        ticks.insert(AgentTick(personaId: "persona-1", at: TestData.now - 740_000, messageId: "msg-c"))
        let controller = makeController(roll: { 0.0 })
        let beat = await controller.beat(now: TestData.now)
        XCTAssertFalse(beat.fired)
        XCTAssertEqual(beat.why, "今天说够了（3/3）")
        XCTAssertTrue(tickCalls.isEmpty)
    }

    /// 人格的主动程度会改上限（§一、§三）：把 level 调到 1，今天说过 1 次就该停。
    func testProactiveLevelDecidesDailyCap() async throws {
        try setLastInteraction(minutesAgo: 240)
        var persona = TestData.persona()
        persona.proactiveLevel = 1
        try db.stores.personas.upsert(persona)
        ticks.insert(AgentTick(personaId: "persona-1", at: TestData.now - 660_000, messageId: "msg-a"))
        let controller = makeController(roll: { 0.0 })
        let beat = await controller.beat(now: TestData.now)
        XCTAssertFalse(beat.fired)
        XCTAssertEqual(beat.why, "今天说够了（1/1）")
        XCTAssertTrue(tickCalls.isEmpty)
    }

    /// 没有额度闸了（§五）：掷中就该真的去想，没有任何「钱包」挡在前面。
    func testNoQuotaGateBlocksTheBeatAnyMore() async throws {
        try setLastInteraction(minutesAgo: 240)
        let controller = makeController(roll: { 0.0 })
        let beat = await controller.beat(now: TestData.now)
        XCTAssertTrue(beat.fired)
        XCTAssertEqual(tickCalls.count, 1)
    }

    func testRecentTickBlocksAnotherRound() async throws {
        try setLastInteraction(minutesAgo: 240)
        // 两分钟前刚想过：再掷中也得等一会儿（防止一波连掷连着想好几次）。
        ticks.insert(AgentTick(personaId: "persona-1", at: TestData.now - 120_000))
        let controller = makeController(roll: { 0.0 })
        let beat = await controller.beat(now: TestData.now)
        XCTAssertFalse(beat.fired)
        XCTAssertEqual(beat.why, "刚想过，再等等")
        XCTAssertTrue(tickCalls.isEmpty)
    }

    func testFirstEverBeatOnlySetsTheStartingPoint() async throws {
        // 没有任何互动记录、也没有持久化的重置点：第一拍只把起点定下来，不掷。
        try db.stores.messages.deleteAll()
        var current = settings.load()
        current.heartbeatLastResetAt = 0
        current.heartbeatLastFiredAt = nil
        settings.save(current)
        let controller = makeController(roll: { 0.0 })
        let beat = await controller.beat(now: TestData.now)
        XCTAssertFalse(beat.fired)
        XCTAssertEqual(settings.load().heartbeatLastResetAt, TestData.now)
    }

    func testAgentLoopDisabledSkipsEverything() async throws {
        settings.save(AppSettings(agentLoopEnabled: false))
        let controller = makeController(roll: { 0.0 })
        let beat = await controller.beat(now: TestData.now)
        XCTAssertFalse(beat.fired)
        XCTAssertEqual(beat.why, "常驻循环已关闭")
    }

    func testIntervalFollowsLowPowerFlag() {
        let normal = makeController()
        XCTAssertEqual(normal.checkIntervalMillis(), HeartbeatController.beatIntervalMillis)
        XCTAssertEqual(normal.intervalMinutes(), 0.5, accuracy: 0.000_1)

        let lowPower = HeartbeatController(
            stores: db.stores, settings: settings, intents: intents, ticks: ticks, mood: mood,
            tickRunner: { _ in nil }, now: { TestData.now }, roll: { 0.0 }, isLowPower: { true }
        )
        XCTAssertEqual(lowPower.checkIntervalMillis(), HeartbeatController.lowPowerBeatIntervalMillis)
        XCTAssertEqual(lowPower.intervalMinutes(), 2.0, accuracy: 0.000_1)
    }
}
