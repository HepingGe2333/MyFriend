import Foundation
import XCTest
@testable import MyFriendKit

/// 启动/回前台的「补算」去重与前台 30 秒一拍（0.10.2 审计 P1-2 / P1-3）。
///
/// 这两条缺陷的共同点是：**单元测试之外看不出来**——真机上表现为「双倍 token、两条主动消息」
/// 和「用户一直开着 App 却一拍都不发生」。所以判据被抽成纯逻辑（`CatchUpGuard`、
/// `ForegroundPulse`）+ `HeartbeatController.beat` 的互斥标记，在这里逐条钉住。
final class CatchUpAndForegroundPulseTests: XCTestCase {

    private let baseNow: Int64 = 1_794_500_000_000

    // MARK: - CatchUpGuard

    func testSecondCatchUpIsRejectedWhileFirstIsInFlight() {
        let guardrail = CatchUpGuard()
        XCTAssertTrue(guardrail.begin(now: baseNow))
        XCTAssertFalse(guardrail.begin(now: baseNow), "上一次还没结束，不能重复补算")
        XCTAssertTrue(guardrail.isInFlight)
        guardrail.finish()
        XCTAssertFalse(guardrail.isInFlight)
    }

    func testCatchUpIsThrottledForMinimumInterval() {
        let guardrail = CatchUpGuard()
        XCTAssertTrue(guardrail.begin(now: baseNow))
        guardrail.finish()
        // 结束后立刻再来一次（冷启动 + 回前台的真实时间差就在毫秒级）必须被挡住。
        XCTAssertFalse(guardrail.begin(now: baseNow + 500))
        XCTAssertFalse(guardrail.begin(now: baseNow + 29_999))
        // 过了最小间隔才允许。
        XCTAssertTrue(guardrail.begin(now: baseNow + CatchUpGuard.defaultMinimumIntervalMillis))
        XCTAssertEqual(guardrail.lastStartAt, baseNow + CatchUpGuard.defaultMinimumIntervalMillis)
    }

    func testCatchUpGuardSupportsCustomInterval() {
        let guardrail = CatchUpGuard()
        XCTAssertTrue(guardrail.begin(now: baseNow, minimumIntervalMillis: 1_000))
        guardrail.finish()
        XCTAssertFalse(guardrail.begin(now: baseNow + 999, minimumIntervalMillis: 1_000))
        XCTAssertTrue(guardrail.begin(now: baseNow + 1_000, minimumIntervalMillis: 1_000))
    }

    /// 并发打进来时只能有一个赢（这就是冷启动那一刻的真实情况）。
    func testOnlyOneConcurrentCallerWins() {
        let guardrail = CatchUpGuard()
        let lock = NSLock()
        var winners = 0
        DispatchQueue.concurrentPerform(iterations: 8) { index in
            if guardrail.begin(now: baseNow + Int64(index)) {
                lock.lock()
                winners += 1
                lock.unlock()
            }
        }
        XCTAssertEqual(winners, 1, "同一次启动只该有一次补算")
    }

    // MARK: - ForegroundPulse

    func testNextFireAndRemaining() {
        XCTAssertEqual(
            ForegroundPulse.nextFireAt(now: baseNow, intervalMillis: 30_000),
            baseNow + 30_000
        )
        XCTAssertEqual(ForegroundPulse.nextFireAt(now: baseNow, intervalMillis: -5), baseNow)
        XCTAssertEqual(
            ForegroundPulse.remainingMillis(now: baseNow + 10_000, nextFireAt: baseNow + 30_000),
            20_000
        )
        XCTAssertEqual(ForegroundPulse.remainingMillis(now: baseNow + 40_000, nextFireAt: baseNow + 30_000), 0)
    }

    func testIntervalFollowsLowPower() {
        XCTAssertEqual(ForegroundPulse.intervalMillis(isLowPower: false), 30_000)
        XCTAssertEqual(ForegroundPulse.intervalMillis(isLowPower: true), 120_000)
        // 与心跳类本身保持一致（概率换算用的是同一个数）。
        let db = try! TempDatabase()
        defer { db.cleanUp() }
        let settings = InMemorySettings(AppSettings(agentLoopEnabled: true))
        let ticks = InMemoryAgentTickStore()
        let normal = HeartbeatController(
            stores: db.stores, settings: settings,
            intents: AgentIntentSupport(stores: db.stores, ticks: ticks),
            ticks: ticks, mood: MoodRepository(stores: db.stores),
            tickRunner: { _ in nil }, isLowPower: { false }
        )
        let low = HeartbeatController(
            stores: db.stores, settings: settings,
            intents: AgentIntentSupport(stores: db.stores, ticks: ticks),
            ticks: ticks, mood: MoodRepository(stores: db.stores),
            tickRunner: { _ in nil }, isLowPower: { true }
        )
        XCTAssertEqual(normal.checkIntervalMillis(), ForegroundPulse.intervalMillis(isLowPower: false))
        XCTAssertEqual(low.checkIntervalMillis(), ForegroundPulse.intervalMillis(isLowPower: true))
    }

    func testPulseDoesNotRunInBackgroundOrWithoutConfig() {
        XCTAssertTrue(ForegroundPulse.shouldRun(isActive: true, hasApiConfig: true, hasPersona: true))
        XCTAssertFalse(ForegroundPulse.shouldRun(isActive: false, hasApiConfig: true, hasPersona: true),
                       "进后台必须停")
        XCTAssertFalse(ForegroundPulse.shouldRun(isActive: true, hasApiConfig: false, hasPersona: true),
                       "没有 API 配置时每拍都是空转")
        XCTAssertFalse(ForegroundPulse.shouldRun(isActive: true, hasApiConfig: true, hasPersona: false))
    }

    // MARK: - beat 并发只跑一次

    func testConcurrentBeatsOnlyRunOneSideEffect() async throws {
        let db = try TempDatabase()
        defer { db.cleanUp() }
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.sessions.upsert(TestData.session(lastMessageAt: TestData.now - 4 * 3_600_000))

        // 让「距离上次互动过了 4 小时」变成一件确定的事（否则第一拍只会把时钟归位）。
        var initial = AppSettings(agentLoopEnabled: true)
        initial.heartbeatLastResetAt = TestData.now - 4 * 3_600_000
        let settings = InMemorySettings(initial)
        let ticks = InMemoryAgentTickStore()
        let moods = MoodRepository(stores: db.stores, now: { TestData.now })
        let intents = AgentIntentSupport(
            stores: db.stores, ticks: ticks, now: { TestData.now }, random: { 0.5 }
        )
        let calls = CallCounter()
        let controller = HeartbeatController(
            stores: db.stores,
            settings: settings,
            intents: intents,
            ticks: ticks,
            mood: moods,
            tickRunner: { _ in
                // 让这一拍真的「在跑」一会儿：模拟一次 LLM 调用。
                await calls.enter()
                try? await Task.sleep(nanoseconds: 60_000_000)
                await calls.leave()
                return AgentLoop.Outcome(nextDelayMinutes: 6, acted: true, summary: "发了一条消息")
            },
            now: { TestData.now },
            roll: { 0.0 }
        )

        async let first = controller.beat(now: TestData.now)
        async let second = controller.beat(now: TestData.now)
        let results = await [first, second]

        let peak = await calls.peak
        let total = await calls.total
        XCTAssertEqual(peak, 1, "两次并发 beat 不能同时真的想事情")
        XCTAssertEqual(total, 1, "两次并发 beat 只该有一次副作用（一次模型调用）")
        XCTAssertEqual(results.filter(\.fired).count, 1, "只该有一拍真的起心动念")
        XCTAssertTrue(results.contains { $0.why == "上一拍还没跑完" }, "被挡住的那一拍要说清原因")
    }
}

// MARK: - 小工具

private actor CallCounter {
    private(set) var total = 0
    private(set) var current = 0
    private(set) var peak = 0

    func enter() {
        total += 1
        current += 1
        peak = max(peak, current)
    }

    func leave() {
        current = max(current - 1, 0)
    }
}
