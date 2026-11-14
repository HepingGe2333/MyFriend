import Foundation
import XCTest
@testable import MyFriendKit

/// 「他此刻」：只读推导，30 秒脉冲由调用方驱动。
///
/// 这里钉的是「同样的痕迹必须推出同一句话」：睡觉/刚醒/在写 / 在翻 / 在想 / 在等 / 在发呆，
/// 以及他自己视角的那句解释（进提示词，不能写成第三人称）。
final class PresenceRepositoryTests: XCTestCase {

    private var db: TempDatabase!
    private var ticks: InMemoryAgentTickStore!
    private var intents: AgentIntentSupport!
    private var settings: InMemorySettings!
    private var presence: PresenceRepository!

    override func setUpWithError() throws {
        db = try TempDatabase()
        settings = InMemorySettings()
        ticks = InMemoryAgentTickStore()
        intents = AgentIntentSupport(stores: db.stores, ticks: ticks, now: { TestData.now })
        presence = PresenceRepository(stores: db.stores, ticks: ticks, intents: intents, now: { TestData.now })
    }

    override func tearDownWithError() throws {
        presence = nil
        intents = nil
        ticks = nil
        settings = nil
        db.cleanUp()
        db = nil
    }

    func testIdleWhenNothingHappenedAndSelfDescriptionIsFirstPerson() throws {
        try db.stores.personas.upsert(TestData.persona())
        let current = presence.current(personaId: "persona-1")
        XCTAssertEqual(current.kind, .idle)
        XCTAssertTrue(current.isMeaningful())
        // 固定时钟是当地 00:13：深夜的措辞与白天不同，且他自己不会说「他有好一阵没动静」。
        XCTAssertTrue(current.detail.contains("这么晚"))
        XCTAssertFalse(current.detail.contains("他有好一阵"))
    }

    func testAsleepWhenInsideQuietHours() throws {
        let minute = ServiceTime.minuteOfDay(TestData.now)
        try db.stores.personas.upsert(
            TestData.persona(quietSleepMinutes: minute, quietWakeMinutes: (minute + 120) % 1440)
        )
        let current = presence.current(personaId: "persona-1")
        XCTAssertEqual(current.kind, .asleep)
        XCTAssertEqual(current.text, "睡着了")
        XCTAssertEqual(current.detail, "这会儿睡下了，脑子是歇着的")
        XCTAssertEqual(current.updatedAt, TestData.now)
    }

    func testWritingWithinThreeMinutesOfAMessage() throws {
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.sessions.upsert(TestData.session(lastMessageAt: TestData.now - 60_000))
        try db.stores.messages.upsert([
            TestData.message(id: "msg-1", role: .assistant, content: "在呢。", createdAt: TestData.now - 60_000)
        ])
        let current = presence.snapshot(personaId: "persona-1")
        XCTAssertEqual(current.kind, .writing)
        XCTAssertEqual(current.text, "在写东西")
        XCTAssertEqual(current.sinceAt, TestData.now - 60_000)
    }

    func testBrowsingAndThinkingFromRecentTicks() throws {
        try db.stores.personas.upsert(TestData.persona())
        // 5 分钟前想过一回，那一轮的 outcome 说明他在翻东西。
        ticks.insert(AgentTick(personaId: "persona-1", at: TestData.now - 5 * 60_000,
                               outcome: "自己上网看了看"))
        XCTAssertEqual(presence.snapshot(personaId: "persona-1").kind, .browsing)

        ticks.deleteAll()
        ticks.insert(AgentTick(personaId: "persona-1", at: TestData.now - 5 * 60_000, outcome: "什么都没做"))
        let thinking = presence.snapshot(personaId: "persona-1")
        XCTAssertEqual(thinking.kind, .thinking)
        XCTAssertEqual(presence.current(personaId: "persona-1").detail, "刚一个人坐着想了一阵，没急着开口")
    }

    func testWaitingWhenAnIntentFallsWithinNextHalfHour() throws {
        try db.stores.personas.upsert(TestData.persona())
        _ = intents.plan(
            personaId: "persona-1",
            window: AgentWindow(earliestAt: TestData.now + 10 * 60_000,
                                latestAt: TestData.now + 40 * 60_000,
                                note: "问问他到家没"),
            now: TestData.now
        )
        let current = presence.snapshot(personaId: "persona-1")
        XCTAssertEqual(current.kind, .waiting)
        XCTAssertEqual(current.text, "过一会儿想找你")
    }

    func testDisplayIncludesRelativeTime() throws {
        try db.stores.personas.upsert(TestData.persona())
        ticks.insert(AgentTick(personaId: "persona-1", at: TestData.now - 12 * 60_000, outcome: "什么都没做"))
        let current = presence.snapshot(personaId: "persona-1")
        XCTAssertEqual(current.display(now: TestData.now), "在想事情 · 12 分钟前")
    }
}
