import XCTest
@testable import YapKit

/// 记忆生命周期的纯逻辑单测（移植自 Android 的 MemoryLifecycleTest）。
///
/// 衰减、阈值、pinned 保底、抽屉差异、坏数据。
final class MemoryLifecycleTests: XCTestCase {

    let now: Int64 = 1_760_000_000_000
    let day: Int64 = MemoryLifecycle.DAY_MILLIS

    func state(
        id: String = "m1",
        tier: MemoryTier = .active,
        pinned: Bool = false,
        importance: Int = 3,
        useCount: Int = 0,
        lastUsedAt: Int64 = 0,
        createdAt: Int64? = nil,
        strength: Double = 1.0,
        lastConsolidatedAt: Int64 = 0,
        drawer: MemoryDrawer = .event
    ) -> MemoryLifecycleState {
        MemoryLifecycleState(
            id: id,
            tier: tier,
            pinned: pinned,
            importance: importance,
            useCount: useCount,
            lastUsedAt: lastUsedAt,
            createdAt: createdAt ?? now,
            strength: strength,
            lastConsolidatedAt: lastConsolidatedAt,
            drawer: drawer
        )
    }

    // MARK: 1. effectiveStrength

    func testBrandNewImportantMemoryScoresHigh() {
        let memory = state(importance: 5, useCount: 3, lastUsedAt: now, createdAt: now)
        let strength = MemoryLifecycle.effectiveStrength(memory, now: now)
        XCTAssertGreaterThan(strength, 0.85, "新记忆应该接近满值，实际 \(strength)")
        XCTAssertLessThanOrEqual(strength, 1.0)
    }

    func testArchivedMemoryHasZeroStrength() {
        let memory = state(tier: .archived, pinned: true, importance: 5)
        XCTAssertEqual(0.0, MemoryLifecycle.effectiveStrength(memory, now: now), accuracy: 0.0001)
    }

    func testPinnedMemoryNeverFallsBelowFloor() {
        let memory = state(pinned: true, importance: 1, createdAt: now - 4000 * day, strength: 0.0)
        let strength = MemoryLifecycle.effectiveStrength(memory, now: now)
        XCTAssertGreaterThanOrEqual(strength, MemoryLifecycle.PINNED_FLOOR,
                                    "pinned 应该被保底，实际 \(strength)")
    }

    func testOutOfRangeImportanceIsClamped() {
        let tooHigh = MemoryLifecycle.effectiveStrength(state(importance: 9), now: now)
        let max = MemoryLifecycle.effectiveStrength(state(importance: 5), now: now)
        let tooLow = MemoryLifecycle.effectiveStrength(state(importance: -3), now: now)
        let min = MemoryLifecycle.effectiveStrength(state(importance: 1), now: now)
        XCTAssertEqual(max, tooHigh, accuracy: 0.0001)
        XCTAssertEqual(min, tooLow, accuracy: 0.0001)
    }

    func testBrokenStrengthStaysWithinZeroToOne() {
        for value in [Double.nan, -5.0, 99.0] {
            let strength = MemoryLifecycle.effectiveStrength(state(strength: value), now: now)
            XCTAssertTrue(strength >= 0.0 && strength <= 1.0, "坏数据也应该落在 0..1，实际 \(strength)")
        }
    }

    func testFutureTimestampsDoNotThrow() {
        let memory = state(lastUsedAt: now + 10 * day, createdAt: now + 10 * day)
        let strength = MemoryLifecycle.effectiveStrength(memory, now: now)
        XCTAssertTrue(strength >= 0.0 && strength <= 1.0)
    }

    // MARK: 2. decayStep

    func testUnusedMemoryDecaysEachDay() {
        let memory = state(importance: 3, createdAt: now - day, strength: 1.0)
        let next = MemoryLifecycle.decayStep(memory, now: now)
        XCTAssertLessThan(next, 1.0, "未使用的一天应该掉，实际 \(next)")
        XCTAssertGreaterThan(next, 0.9, "一天不该掉太多，实际 \(next)")
    }

    func testRecentlyUsedMemoryHeals() {
        let memory = state(useCount: 3, lastUsedAt: now, strength: 0.5, lastConsolidatedAt: now)
        let next = MemoryLifecycle.decayStep(memory, now: now)
        XCTAssertGreaterThan(next, 0.5, "刚被用过应该回血，实际 \(next)")
    }

    func testHigherImportanceDecaysSlower() {
        let young = state(importance: 1, createdAt: now - 5 * day, strength: 0.6)
        let important = state(importance: 5, createdAt: now - 5 * day, strength: 0.6)
        let lowNext = MemoryLifecycle.decayStep(young, now: now)
        let highNext = MemoryLifecycle.decayStep(important, now: now)
        XCTAssertGreaterThan(highNext, lowNext, "5 分应该比 1 分掉得慢：\(highNext) vs \(lowNext)")
    }

    func testLongOfflineIsCappedAtMaxDecayDays() {
        // 重要度 1 的衰减系数是 1.5，离线一整年也只按 MAX_DECAY_DAYS 天算。
        let memory = state(importance: 1, createdAt: now - 3650 * day, strength: 0.9)
        let next = MemoryLifecycle.decayStep(memory, now: now)
        let expected = max(0.0, 0.9 - MemoryLifecycle.DECAY_PER_DAY * 1.5 * MemoryLifecycle.MAX_DECAY_DAYS)
        XCTAssertEqual(expected, next, accuracy: 0.0001)
    }

    func testArchivedMemoryDoesNotDecay() {
        let memory = state(tier: .archived, createdAt: now - 30 * day, strength: 0.7)
        XCTAssertEqual(0.0, MemoryLifecycle.decayStep(memory, now: now), accuracy: 0.0001)
    }

    func testPinnedMemoryStaysAboveFloorAfterDecay() {
        let memory = state(pinned: true, importance: 1, createdAt: now - 100 * day, strength: 0.6)
        let next = MemoryLifecycle.decayStep(memory, now: now)
        XCTAssertGreaterThanOrEqual(next, MemoryLifecycle.PINNED_FLOOR)
    }

    // MARK: 3. nextStatus

    func testActiveButLowDemotes() {
        let action = MemoryLifecycle.nextStatus(state(tier: .active),
                                                strength: MemoryLifecycle.DORMANT_THRESHOLD - 0.01,
                                                now: now)
        guard case let .demote(id, to, _) = action else { return XCTFail("应该沉底，实际 \(action)") }
        XCTAssertEqual("m1", id)
        XCTAssertEqual(MemoryTier.dormant, to)
    }

    func testActiveAndHealthyKeeps() {
        let action = MemoryLifecycle.nextStatus(state(tier: .active), strength: 0.9, now: now)
        guard case let .keep(id) = action else { return XCTFail("应该留着，实际 \(action)") }
        XCTAssertEqual("m1", id)
    }

    func testDormantBelowArchiveLineIsArchived() {
        let memory = state(tier: .dormant, createdAt: now - 10 * day)
        let action = MemoryLifecycle.nextStatus(memory,
                                                strength: MemoryLifecycle.ARCHIVE_THRESHOLD - 0.01,
                                                now: now)
        guard case let .demote(_, to, _) = action else { return XCTFail("应该归档，实际 \(action)") }
        XCTAssertEqual(MemoryTier.archived, to)
    }

    func testDormantRecalledPromotes() {
        let memory = state(tier: .dormant, lastUsedAt: now)
        let action = MemoryLifecycle.nextStatus(memory,
                                                strength: MemoryLifecycle.DORMANT_THRESHOLD
                                                    + MemoryLifecycle.RECOVER_MARGIN,
                                                now: now)
        guard case let .promote(id, _) = action else { return XCTFail("应该回升，实际 \(action)") }
        XCTAssertEqual("m1", id)
    }

    func testOldAndLowIsForgotten() {
        let memory = state(tier: .dormant,
                           createdAt: now - (Int64(MemoryLifecycle.FORGET_AGE_DAYS) + 1) * day)
        let action = MemoryLifecycle.nextStatus(memory, strength: MemoryLifecycle.FORGET_THRESHOLD, now: now)
        guard case let .forget(_, reason) = action else { return XCTFail("很旧 + 很低应该忘掉") }
        XCTAssertFalse(reason.isEmpty)
    }

    func testLowButNewIsNotForgotten() {
        let memory = state(tier: .dormant, createdAt: now - 3 * day)
        let action = MemoryLifecycle.nextStatus(memory, strength: MemoryLifecycle.FORGET_THRESHOLD, now: now)
        if case .forget = action { XCTFail("很低但还很新不该忘掉") }
    }

    func testPinnedNeverDemotesOrForgets() {
        let memory = state(tier: .dormant, pinned: true, createdAt: now - 5000 * day)
        let action = MemoryLifecycle.nextStatus(memory, strength: 0.0, now: now)
        guard case .keep = action else { return XCTFail("钉住的一律 Keep，实际 \(action)") }
    }

    func testRecentlyArchivedStaysPut() {
        let memory = state(tier: .archived, createdAt: now - 40 * day)
        guard case .keep = MemoryLifecycle.nextStatus(memory, strength: 0.0, now: now) else {
            return XCTFail("归档不久的记忆保持不动")
        }
    }

    func testLongArchivedAndLowIsForgotten() {
        let memory = state(tier: .archived,
                           createdAt: now - (Int64(MemoryLifecycle.FORGET_AGE_DAYS) + 30) * day)
        guard case .forget = MemoryLifecycle.nextStatus(memory, strength: 0.0, now: now) else {
            return XCTFail("归档很久又很低应该忘掉")
        }
    }

    // MARK: 4. dailyUpdate / 阈值辅助

    func testDailyUpdateReturnsBothActionAndStrength() {
        let memory = state(tier: .active, createdAt: now - 20 * day, strength: 0.46,
                           lastConsolidatedAt: now - 20 * day)
        let update = MemoryLifecycle.dailyUpdate(memory, now: now)
        // 20 天没动过，补算 14 天后分量被夹到 0。
        XCTAssertEqual(0.0, update.strength, accuracy: 0.0001)
        guard case .demote = update.action else { return XCTFail("掉到阈值以下应该沉底") }
    }

    func testKeepStrengthNeverBelowCurrent() {
        let memory = state(importance: 4, strength: 0.5)
        let strength = MemoryLifecycle.keepStrength(memory, now: now)
        XCTAssertGreaterThan(strength, 0.5, "keep 应该加固，实际 \(strength)")
        XCTAssertLessThanOrEqual(strength, 1.0)
    }

    func testRecallHealsLessThanKeep() {
        let memory = state(importance: 3, strength: 0.4)
        let keep = MemoryLifecycle.keepStrength(memory, now: now)
        let recall = MemoryLifecycle.recallStrength(memory, now: now)
        XCTAssertLessThan(recall, keep, "召回不该比 keep 更重：\(recall) vs \(keep)")
        XCTAssertGreaterThan(recall, 0.4)
    }

    func testForgetDependsOnThresholdAndAge() {
        let old = state(createdAt: now - 300 * day)
        let young = state(createdAt: now - day)
        XCTAssertTrue(MemoryLifecycle.shouldForget(old, strength: MemoryLifecycle.FORGET_THRESHOLD, now: now))
        XCTAssertFalse(MemoryLifecycle.shouldForget(old,
                                                    strength: MemoryLifecycle.FORGET_THRESHOLD + 0.05,
                                                    now: now))
        XCTAssertFalse(MemoryLifecycle.shouldForget(young, strength: 0.0, now: now))
    }

    func testUnknownTimestampsTreatedAsOldest() {
        let unknown = state(lastUsedAt: 0, createdAt: 0)
        XCTAssertEqual(Double.greatestFiniteMagnitude,
                       MemoryLifecycle.ageInDays(unknown, now: now), accuracy: 0.0)
        let strength = MemoryLifecycle.effectiveStrength(unknown, now: now)
        XCTAssertTrue(strength >= 0.0 && strength <= 1.0)
    }

    // MARK: 抽屉差异

    func testDrawerDecayConstants() {
        XCTAssertLessThan(MemoryLifecycle.DRAWER_DECAY_USER, MemoryLifecycle.DRAWER_DECAY_SELF,
                          "USER 应该最慢")
        XCTAssertLessThan(MemoryLifecycle.DRAWER_DECAY_SELF, MemoryLifecycle.DRAWER_DECAY_EVENT,
                          "SELF 应该居中")
        XCTAssertEqual(1.0, MemoryLifecycle.DRAWER_DECAY_EVENT, accuracy: 0.0001,
                       "共同经历保持基准速度")
    }

    func testUserDrawerDecaysSlowerThanEvent() {
        let user = state(importance: 3, createdAt: now - day, drawer: .user)
        let event = state(importance: 3, createdAt: now - day, drawer: .event)
        let userNext = MemoryLifecycle.decayStep(user, now: now)
        let eventNext = MemoryLifecycle.decayStep(event, now: now)
        XCTAssertGreaterThan(userNext, eventNext, "USER 应该掉得少：\(userNext) vs \(eventNext)")
        XCTAssertGreaterThan(userNext, 0.97, "USER 一天只掉一点点，实际 \(userNext)")
    }

    func testSelfDrawerInTheMiddle() {
        let user = MemoryLifecycle.decayStep(
            state(createdAt: now - 10 * day, strength: 0.8, drawer: .user), now: now)
        let selfDrawer = MemoryLifecycle.decayStep(
            state(createdAt: now - 10 * day, strength: 0.8, drawer: .selfDrawer), now: now)
        let event = MemoryLifecycle.decayStep(
            state(createdAt: now - 10 * day, strength: 0.8, drawer: .event), now: now)
        XCTAssertGreaterThan(user, selfDrawer, "USER 最慢：\(user)")
        XCTAssertGreaterThan(selfDrawer, event, "SELF 居中：\(selfDrawer) vs \(event)")
    }

    func testSameEventSinksAtDifferentSpeedsPerDrawer() {
        // 同样是 12 天没人提起、分量 0.6：共同经历已经接近沉底，关于对方的事还稳稳的。
        let event = MemoryLifecycle.decayStep(
            state(createdAt: now - 12 * day, strength: 0.6, drawer: .event), now: now)
        let user = MemoryLifecycle.decayStep(
            state(createdAt: now - 12 * day, strength: 0.6, drawer: .user), now: now)
        XCTAssertGreaterThan(user - event, 0.05, "USER 应该保留更多分量：\(user) vs \(event)")
    }

    func testLegacyDataDefaultsToEventDrawer() {
        let legacy = MemoryLifecycleState(id: "m1", createdAt: now - 5 * day, strength: 0.7)
        let explicitEvent = state(createdAt: now - 5 * day, strength: 0.7, drawer: .event)
        XCTAssertEqual(MemoryDrawer.event, legacy.drawer)
        XCTAssertEqual(MemoryLifecycle.decayStep(explicitEvent, now: now),
                       MemoryLifecycle.decayStep(legacy, now: now), accuracy: 0.0001)
    }

    func testUnknownTierStringIsActive() {
        XCTAssertEqual(MemoryTier.active, MemoryTier.parse(nil))
        XCTAssertEqual(MemoryTier.active, MemoryTier.parse(""))
        XCTAssertEqual(MemoryTier.active, MemoryTier.parse("乱写的"))
        XCTAssertEqual(MemoryTier.dormant, MemoryTier.parse(" dormant "))
        XCTAssertEqual(MemoryTier.archived, MemoryTier.parse("ARCHIVED"))
    }

    func testActionCarriesId() {
        XCTAssertEqual("m1", LifecycleAction.keep(id: "m1").id)
        XCTAssertEqual("m1", LifecycleAction.demote(id: "m1", to: .dormant, strength: 0.4).id)
        XCTAssertEqual("m1", LifecycleAction.promote(id: "m1", strength: 0.6).id)
        XCTAssertEqual("m1", LifecycleAction.forget(id: "m1", reason: "太旧").id)
    }
}
