import Foundation
import XCTest
@testable import YapKit

/// 「服务端模式下客户端只同步」的策略与接线（0.13）。
///
/// 服务端模式下他在服务端那边独立运行（主动消息/情绪/进化/日记提醒）。本机再跑一套本地
/// 自主逻辑就会出现双份消息、双份情绪判定。这里钉住：
/// 1. 策略函数（服务端模式一票否决；本地模式行为不变；读不出来保持现状）；
/// 2. 心跳与 AgentLoop 真的在服务端模式下停住（不是只在某个入口判断）。
final class ServerModeAutonomyTests: XCTestCase {

    // MARK: - 策略

    func testServerModeStopsLocalAutonomyEvenWhenLoopEnabled() {
        XCTAssertEqual(
            LocalAutonomy.proactiveGate(agentLoopEnabled: true, serverModeActive: true),
            .stop
        )
        XCTAssertFalse(LocalAutonomy.localAutonomyAllowed(serverModeActive: true))
        XCTAssertFalse(LocalAutonomy.shouldRunLocalTick(serverModeActive: true))
    }

    func testLocalModeKeepsPreviousBehavior() {
        XCTAssertEqual(
            LocalAutonomy.proactiveGate(agentLoopEnabled: true, serverModeActive: false),
            .run
        )
        XCTAssertEqual(
            LocalAutonomy.proactiveGate(agentLoopEnabled: false, serverModeActive: false),
            .stop
        )
        XCTAssertEqual(
            LocalAutonomy.proactiveGate(agentLoopEnabled: nil, serverModeActive: false),
            .keep
        )
    }

    func testServerModeWinsOverUnknownSwitch() {
        XCTAssertEqual(
            LocalAutonomy.proactiveGate(agentLoopEnabled: nil, serverModeActive: true),
            .stop
        )
    }

    // MARK: - 心跳

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var ticks: InMemoryAgentTickStore!
    private var intents: AgentIntentSupport!
    private var mood: MoodRepository!

    override func setUpWithError() throws {
        db = try TempDatabase()
        settings = InMemorySettings(AppSettings(agentLoopEnabled: true))
        ticks = InMemoryAgentTickStore()
        mood = MoodRepository(stores: db.stores, now: { TestData.now })
        intents = AgentIntentSupport(
            stores: db.stores, ticks: ticks, now: { TestData.now }, random: { 0.5 }
        )
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.sessions.upsert(TestData.session(lastMessageAt: TestData.now - 4 * 3_600_000))
    }

    override func tearDownWithError() throws {
        mood = nil
        intents = nil
        ticks = nil
        settings = nil
        db.cleanUp()
        db = nil
    }

    private func makeHeartbeat(
        serverModeActive: Bool,
        tickCalls: @escaping (Int64) -> Void
    ) -> HeartbeatController {
        HeartbeatController(
            stores: db.stores,
            settings: settings,
            intents: intents,
            ticks: ticks,
            mood: mood,
            tickRunner: { now in
                tickCalls(now)
                return AgentLoop.Outcome(nextDelayMinutes: 6, acted: true, summary: "发了一条消息")
            },
            now: { TestData.now },
            roll: { 0.0 },
            isServerMode: { serverModeActive }
        )
    }

    func testHeartbeatDoesNothingInServerMode() async throws {
        var ticked: [Int64] = []
        let controller = makeHeartbeat(serverModeActive: true) { ticked.append($0) }

        let beat = await controller.beat(now: TestData.now)

        XCTAssertFalse(beat.fired)
        XCTAssertFalse(beat.acted)
        XCTAssertEqual(beat.why, LocalAutonomy.serverModeWhy)
        XCTAssertTrue(ticked.isEmpty, "服务端模式下不该叫他去思考")
    }

    // MARK: - AgentLoop

    func testAgentLoopTickStopsInServerMode() async throws {
        let loop = AgentLoop(
            stores: db.stores,
            settings: settings,
            completion: FakeCompletionClient(),
            memoryRepository: MemoryRepository(stores: db.stores),
            intents: intents,
            ticks: ticks,
            presence: PresenceRepository(stores: db.stores, ticks: ticks, intents: intents),
            mood: mood,
            isServerMode: { true }
        )

        let outcome = await loop.tick(config: nil, model: "x", now: TestData.now)

        XCTAssertFalse(outcome.acted)
        XCTAssertTrue(outcome.summary.contains("服务端模式"))
    }
}
