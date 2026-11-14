import XCTest
@testable import MyFriendKit

/// 冲动曲线的纯领域测试（移植自 Android 的 HeartbeatTest）。
///
/// 这里断言的不是「大概像个人」，而是曲线的形状本身：
/// 刚互动完不高、先降到一个清晰的谷底、之后单调爬升、三个小时之后停在上限；
/// 外加情绪放大、深夜减半、坏输入（负时间 / NaN / 无穷大）不产生 NaN 或负概率，
/// 以及掷骰子与重置时钟的边界。
final class HeartbeatTests: XCTestCase {

    /// 所有断言都留一点浮点余量：这些是数学形状，不是像素。
    let eps = 1e-9

    func p(_ minutes: Double, _ mood: MoodState = MoodState(), hour: Int = 14) -> Double {
        Heartbeat.probability(minutesSinceReset: minutes, mood: mood, now: 0, hourOfDay: hour)
    }

    func mood(_ label: String, _ intensity: Int) -> MoodState {
        MoodState(label: label, intensity: intensity, updatedAt: 0)
    }

    // MARK: 曲线形状

    func testJustAfterInteractionIsNotThePeak() {
        // 他刚说完话，人是收回来的：0.35 只是起点，远不是上限。
        XCTAssertEqual(Heartbeat.INITIAL, p(0.0), accuracy: eps)
        XCTAssertLessThan(p(0.0), p(180.0))
    }

    func testFloorAroundTenMinutes() {
        let floor = p(Heartbeat.COOL_DOWN_MINUTES)
        XCTAssertGreaterThan(floor, Heartbeat.FLOOR, "谷底应该在 0.10 上方一点，实际 \(floor)")
        XCTAssertLessThan(floor, 0.13, "谷底应该明显低于起点，实际 \(floor)")
    }

    func testFloorIsBelowStartAndOneHour() {
        let bottom = p(10.0)
        XCTAssertGreaterThan(p(0.0), bottom)
        XCTAssertLessThan(bottom, p(60.0))
    }

    func testFloorIsMinimumAmongSamples() {
        let bottom = p(10.0)
        for t in [0.0, 5.0, 15.0, 20.0, 30.0, 60.0, 120.0, 180.0] {
            XCTAssertGreaterThanOrEqual(p(t), bottom - eps, "t=\(t) 不该低于谷底")
        }
    }

    func testMonotonicClimbFromTenMinutesToThreeHours() {
        var previous = p(Heartbeat.COOL_DOWN_MINUTES)
        var t = Heartbeat.COOL_DOWN_MINUTES
        while t <= Heartbeat.RAMP_MINUTES {
            let current = p(t)
            XCTAssertGreaterThanOrEqual(current, previous - eps,
                                        "t=\(t) 应当不低于上一拍（\(previous) vs \(current)）")
            previous = current
            t += 5.0
        }
    }

    func testStaysAtCeilingAfterThreeHours() {
        XCTAssertEqual(Heartbeat.CEILING, p(180.0), accuracy: 1e-6)
        XCTAssertEqual(p(180.0), p(240.0), accuracy: eps)
        XCTAssertEqual(p(180.0), p(600.0), accuracy: eps)
    }

    func testOneHourAlreadyMissesYou() {
        // 三小时是上限，一小时应该走完一大半：0.10 到 0.85 之间的 0.32 左右。
        let at60 = p(60.0)
        XCTAssertGreaterThan(at60, 0.30, "一小时应该高于 0.30，实际 \(at60)")
        XCTAssertLessThan(at60, Heartbeat.CEILING)
    }

    // MARK: 情绪放大

    func testStrongPositiveMoodBoosts() {
        XCTAssertGreaterThan(p(60.0, mood("雀跃", 4)), p(60.0))
    }

    func testStrongNegativeMoodAlsoBoosts() {
        // 难过的时候同样想找人说话：这不是「负面才被照顾」，是「心里不静」。
        XCTAssertGreaterThan(p(60.0, mood("低落", 4)), p(60.0))
    }

    func testWeakMoodDoesNotBoost() {
        XCTAssertEqual(p(60.0), p(60.0, mood("开心", 2)), accuracy: eps)
    }

    func testCalmDoesNotBoost() {
        XCTAssertEqual(1.0, Heartbeat.moodBoost(mood("平静", 5)), accuracy: eps)
        XCTAssertEqual(p(60.0), p(60.0, mood("平静", 5)), accuracy: eps)
    }

    func testUnknownMoodDoesNotBoost() {
        // 词表里没有的词按中性处理：不猜他的心情，也就不改他的节奏。
        XCTAssertEqual(1.0, Heartbeat.moodBoost(mood("说不上来", 5)), accuracy: eps)
    }

    func testMissingSomeoneCountsAsRestless() {
        // 想念的极性是 0，但劲头是负的：照样放大。
        XCTAssertGreaterThan(p(60.0, mood("想念", 3)), p(60.0))
    }

    func testStrongerMoodBoostsMore() {
        let b3 = Heartbeat.moodBoost(mood("雀跃", 3))
        let b4 = Heartbeat.moodBoost(mood("雀跃", 4))
        let b5 = Heartbeat.moodBoost(mood("雀跃", 5))
        XCTAssertLessThan(b3, b4)
        XCTAssertLessThan(b4, b5)
        XCTAssertGreaterThanOrEqual(b3, Heartbeat.MOOD_BOOST_MIN - eps)
        XCTAssertLessThanOrEqual(b5, Heartbeat.MOOD_BOOST_MAX + eps)
    }

    func testMoodBoostNeverRunsAway() {
        // 强度传 99 也只到 1.55，夹在 1.15~1.6 之间。
        let boost = Heartbeat.moodBoost(mood("兴奋", 99))
        XCTAssertLessThanOrEqual(boost, Heartbeat.MOOD_BOOST_MAX + eps)
        XCTAssertEqual(1.55, boost, accuracy: eps)
    }

    // MARK: 深夜

    func testNightHalvesEverything() {
        XCTAssertEqual(p(60.0, hour: 14) * 0.5, p(60.0, hour: 3), accuracy: eps)
        XCTAssertEqual(p(0.0, hour: 14) * 0.5, p(0.0, hour: 0), accuracy: eps)
    }

    func testFiveAmIsNightSixAmIsNot() {
        XCTAssertLessThan(p(60.0, hour: 5), p(60.0, hour: 14))
        XCTAssertEqual(p(60.0, hour: 14), p(60.0, hour: 6), accuracy: eps)
    }

    func testNightAndMoodStack() {
        let expected = p(60.0) * Heartbeat.moodBoost(mood("雀跃", 4)) * 0.5
        XCTAssertEqual(expected, p(60.0, mood("雀跃", 4), hour: 3), accuracy: eps)
    }

    func testCurveShapeSurvivesNight() {
        // 夜里只是整体调暗，谷底和爬升还在。
        XCTAssertLessThan(p(10.0, hour: 3), p(60.0, hour: 3))
        XCTAssertLessThan(p(60.0, hour: 3), p(180.0, hour: 3))
    }

    // MARK: 坏输入

    func testNegativeMinutesTreatedAsJustInteracted() {
        XCTAssertEqual(p(0.0), p(-30.0), accuracy: eps)
    }

    func testNaNMinutesTreatedAsJustInteracted() {
        XCTAssertEqual(p(0.0), p(Double.nan), accuracy: eps)
    }

    func testHugeMinutesDoNotOverflow() {
        XCTAssertEqual(Heartbeat.CEILING, p(1e9), accuracy: 1e-6)
        XCTAssertEqual(Heartbeat.CEILING, p(Double.infinity), accuracy: 1e-6)
    }

    func testProbabilityAlwaysWithinSafeRange() {
        let times: [Double] = [-1.0, 0.0, 5.0, 10.0, 60.0, 180.0, 1e6, Double.nan]
        let moods = [
            MoodState(), mood("雀跃", 5), mood("低落", 5), mood("平静", 5), mood("说不上来", 5), mood("兴奋", 0)
        ]
        let hours = [-5, 0, 3, 7, 12, 23, 99]
        for t in times {
            for m in moods {
                for h in hours {
                    let value = p(t, m, hour: h)
                    XCTAssertFalse(value.isNaN, "不该出现 NaN：t=\(t) mood=\(m.label) hour=\(h)")
                    XCTAssertGreaterThanOrEqual(value, Heartbeat.MIN_PROBABILITY - eps, "不该是负数：\(value)")
                    XCTAssertLessThanOrEqual(value, Heartbeat.MAX_PROBABILITY + eps, "不该超过上限：\(value)")
                }
            }
        }
    }

    func testExtremeMoodCannotPushPastCeiling() {
        // 三小时（已在上限 0.85）再乘 1.55：夹到 0.9，而不是 1.3。
        XCTAssertEqual(Heartbeat.MAX_PROBABILITY, p(180.0, mood("兴奋", 5)), accuracy: 1e-6)
    }

    // MARK: 掷骰子

    func testZeroRollAlwaysFires() {
        XCTAssertTrue(Heartbeat.shouldFire(probability: Heartbeat.MIN_PROBABILITY, roll: 0.0))
    }

    func testRollOfOneNeverFires() {
        XCTAssertFalse(Heartbeat.shouldFire(probability: 0.5, roll: 1.0))
    }

    func testRollEqualToProbabilityDoesNotFire() {
        XCTAssertFalse(Heartbeat.shouldFire(probability: 0.5, roll: 0.5))
        XCTAssertTrue(Heartbeat.shouldFire(probability: 0.5, roll: 0.4999))
    }

    func testZeroOrBadProbabilityNeverFires() {
        XCTAssertFalse(Heartbeat.shouldFire(probability: 0.0, roll: 0.0))
        XCTAssertFalse(Heartbeat.shouldFire(probability: Double.nan, roll: 0.0))
        XCTAssertFalse(Heartbeat.shouldFire(probability: 0.5, roll: Double.nan))
    }

    // MARK: 重置时钟

    func testResetOnlyTouchesStartTime() {
        let before = HeartbeatState(lastResetAt: 1_000, lastFiredAt: 500)
        let after = Heartbeat.nextReset(before, now: 20_000)
        XCTAssertEqual(20_000, after.lastResetAt)
        XCTAssertEqual(500, after.lastFiredAt)
    }

    func testFiringRecordsTheMoment() {
        let after = Heartbeat.nextReset(HeartbeatState(lastResetAt: 1_000), now: 20_000, fired: true)
        XCTAssertEqual(20_000, after.lastResetAt)
        XCTAssertEqual(20_000, after.lastFiredAt)
    }

    func testClockRollbackDoesNotCorruptState() {
        let before = HeartbeatState(lastResetAt: 50_000, lastFiredAt: 40_000)
        XCTAssertEqual(before, Heartbeat.nextReset(before, now: 1_000))
        XCTAssertEqual(before, Heartbeat.nextReset(before, now: 0))
    }

    func testLatestMarkTakesMaximum() {
        XCTAssertEqual(100, Heartbeat.latestMark(nil, 0, 100, 50))
        XCTAssertEqual(0, Heartbeat.latestMark(nil, nil))
        XCTAssertEqual(100, Heartbeat.latestMark(-5, 100))
        XCTAssertEqual(0, Heartbeat.latestMark(-5))
    }

    func testMinutesBetween() {
        XCTAssertEqual(1.0, Heartbeat.minutesBetween(lastResetAt: 60_000, now: 120_000), accuracy: eps)
        XCTAssertEqual(0.5, Heartbeat.minutesBetween(lastResetAt: 60_000, now: 90_000), accuracy: eps)
        XCTAssertEqual(0.0, Heartbeat.minutesBetween(lastResetAt: 0, now: 120_000), accuracy: eps)
        XCTAssertEqual(0.0, Heartbeat.minutesBetween(lastResetAt: 500_000, now: 100_000), accuracy: eps)
    }

    // MARK: 每分钟 → 每一拍

    func testMinuteProbabilityToHalfMinuteBeat() {
        // 两次独立的半分钟机会，合起来才是这一分钟的概率。
        let perBeat = Heartbeat.perBeatProbability(perMinute: 0.10, intervalMinutes: 0.5)
        XCTAssertEqual(1.0 - pow(0.9, 0.5), perBeat, accuracy: 1e-9)
        XCTAssertEqual(0.10, 1.0 - (1.0 - perBeat) * (1.0 - perBeat), accuracy: 1e-9)
    }

    func testSparserBeatsRaisePerBeatProbabilityButNotPerMinute() {
        let half = Heartbeat.perBeatProbability(perMinute: 0.5, intervalMinutes: 0.5)
        let two = Heartbeat.perBeatProbability(perMinute: 0.5, intervalMinutes: 2.0)
        XCTAssertGreaterThan(two, half)
        // 四拍半分钟 ≈ 一次两分钟
        let fourBeats = 1.0 - pow(1.0 - half, 4.0)
        XCTAssertEqual(two, fourBeats, accuracy: 1e-9)
    }

    func testPerBeatConversionSurvivesBadInput() {
        XCTAssertEqual(0.0, Heartbeat.perBeatProbability(perMinute: 0.0, intervalMinutes: 0.5), accuracy: eps)
        XCTAssertEqual(0.0, Heartbeat.perBeatProbability(perMinute: Double.nan, intervalMinutes: 0.5), accuracy: eps)
        XCTAssertEqual(0.0, Heartbeat.perBeatProbability(perMinute: -1.0, intervalMinutes: 0.5), accuracy: eps)
        // 间隔坏掉时退回默认节拍，而不是除零或返回 NaN
        let fallback = Heartbeat.perBeatProbability(perMinute: 0.2, intervalMinutes: Double.nan)
        XCTAssertEqual(Heartbeat.perBeatProbability(perMinute: 0.2, intervalMinutes: Heartbeat.BEAT_MINUTES),
                       fallback, accuracy: 1e-9)
        XCTAssertLessThanOrEqual(Heartbeat.perBeatProbability(perMinute: 1.0, intervalMinutes: 0.5), 1.0)
    }

    func testCooldownWindowIsControllerBusinessButCurveIsLow() {
        // 曲线在 t=0 仍有值（0.35），是控制器用 COOL_DOWN_MINUTES 决定这段时间干脆不掷。
        XCTAssertGreaterThan(Heartbeat.probability(minutesSinceReset: 0.0), 0.3)
        XCTAssertLessThan(Heartbeat.probability(minutesSinceReset: Heartbeat.COOL_DOWN_MINUTES), 0.2)
    }
}
