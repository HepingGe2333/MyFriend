import XCTest
@testable import MyFriendKit

/// 心情驱动行为的纯领域测试（移植自 Android 的 MoodBehaviorTest）。
///
/// 覆盖：各心情档的乘数、每日上限的增减与边界、间隔的夹取、深夜的安静、
/// 「未知词不猜」，以及提示词只加很短的一行。
final class MoodBehaviorTests: XCTestCase {

    /// 2026-05-01 的某个本地时刻；用本地时间构造，换时区也不会飘。
    func at(_ hour: Int, _ minute: Int = 0) -> Int64 {
        var components = DateComponents()
        components.year = 2026
        components.month = 5
        components.day = 1
        components.hour = hour
        components.minute = minute
        let date = Calendar.current.date(from: components)!
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    /// 下午两点：肯定不是深夜。
    lazy var day: Int64 = at(14)

    /// 凌晨三点：深夜档。
    lazy var night: Int64 = at(3)

    func mood(_ label: String, _ intensity: Int = 3, reason: String = "") -> MoodState {
        MoodState(label: label, intensity: intensity, reason: reason, updatedAt: day)
    }

    // MARK: 基准

    func testCalmChangesNothing() {
        XCTAssertTrue(MoodBehavior.from(MoodState(label: "平静", intensity: 5), now: day).isNeutral())
        XCTAssertTrue(MoodBehavior.from(MoodState(), now: day).isNeutral())
    }

    func testUnknownMoodDoesNotChangeBehaviour() {
        // 词表里没有的词按中性处理：宁可不猜，也不要替他想出一个心情。
        XCTAssertTrue(MoodBehavior.from(mood("说不上来", 4), now: day).isNeutral())
    }

    func testMildPositiveOnlyLightensPace() {
        let tuning = MoodBehavior.from(mood("温柔", 2), now: day)
        XCTAssertEqual(0.95, tuning.tickIntervalMultiplier, accuracy: 0.0001)
        XCTAssertEqual(0, tuning.dailyMessageCapDelta)
        XCTAssertTrue(tuning.replyLengthHint.isEmpty)
        XCTAssertTrue(tuning.note.isEmpty)
        XCTAssertTrue(MoodBehavior.wantsToBrowse(tuning))
    }

    func testMildNegativeOnlySlightlyQuieter() {
        let tuning = MoodBehavior.from(mood("想念", 2), now: day)
        XCTAssertEqual(1.1, tuning.tickIntervalMultiplier, accuracy: 0.0001)
        XCTAssertEqual(0.8, tuning.browseBias, accuracy: 0.0001)
        XCTAssertTrue(tuning.replyLengthHint.isEmpty)
    }

    // MARK: 低能量 / 低落

    func testTiredMeansFewerThoughtsAndWords() {
        let tuning = MoodBehavior.from(mood("有点累", 2), now: day)
        XCTAssertEqual(1.3, tuning.tickIntervalMultiplier, accuracy: 0.0001)
        XCTAssertEqual(-1, tuning.dailyMessageCapDelta)
        XCTAssertEqual(0.35, tuning.browseBias, accuracy: 0.0001)
        XCTAssertEqual(0.30, tuning.shareBias, accuracy: 0.0001)
        XCTAssertFalse(MoodBehavior.wantsToBrowse(tuning))
        XCTAssertFalse(MoodBehavior.wantsToShare(tuning))
        XCTAssertTrue(tuning.replyLengthHint.contains("短一点"))
        XCTAssertFalse(tuning.note.isEmpty)
    }

    func testLowMoodIsSlowerThanTired() {
        let tired = MoodBehavior.from(mood("有点累", 3), now: day)
        let low = MoodBehavior.from(mood("低落", 3), now: day)
        XCTAssertGreaterThan(low.tickIntervalMultiplier, tired.tickIntervalMultiplier)
        XCTAssertTrue((1.2...1.6).contains(low.tickIntervalMultiplier))
        XCTAssertEqual(-1, low.dailyMessageCapDelta)
    }

    func testStrongerMoodMeansLongerInterval() {
        let faint = MoodBehavior.from(mood("有点累", 1), now: day)
        let strong = MoodBehavior.from(mood("有点累", 5), now: day)
        XCTAssertEqual(1.2, faint.tickIntervalMultiplier, accuracy: 0.0001)
        XCTAssertEqual(1.6, strong.tickIntervalMultiplier, accuracy: 0.0001)
        XCTAssertGreaterThan(strong.tickIntervalMultiplier, faint.tickIntervalMultiplier)
    }

    func testLowMoodAtMaxIsClamped() {
        // 1.3 + 0.1*4 = 1.7，必须夹回 1.6：再难过也不能把循环拖死。
        XCTAssertEqual(1.6, MoodBehavior.from(mood("难过", 5), now: day).tickIntervalMultiplier,
                       accuracy: 0.0001)
    }

    // MARK: 雀跃

    func testHappyMoodIsMoreActive() {
        let tuning = MoodBehavior.from(mood("雀跃", 4), now: day)
        XCTAssertEqual(0.85, tuning.tickIntervalMultiplier, accuracy: 0.0001)
        XCTAssertEqual(1, tuning.dailyMessageCapDelta)
        XCTAssertEqual(1.25, tuning.browseBias, accuracy: 0.0001)
        XCTAssertEqual(1.35, tuning.shareBias, accuracy: 0.0001)
        XCTAssertTrue(MoodBehavior.wantsToBrowse(tuning))
        XCTAssertTrue(tuning.replyLengthHint.contains("多"))
    }

    func testHappyButFaintOnlySlightlyFaster() {
        let tuning = MoodBehavior.from(mood("雀跃", 2), now: day)
        XCTAssertEqual(0.92, tuning.tickIntervalMultiplier, accuracy: 0.0001)
        XCTAssertEqual(0, tuning.dailyMessageCapDelta)
    }

    // MARK: 每日上限

    func testLowMoodLowersDailyCap() {
        let tuning = MoodBehavior.from(mood("低落", 3), now: day)
        XCTAssertEqual(2, MoodBehavior.effectiveDailyCap(baseCap: 3, tuning: tuning))
        XCTAssertEqual(1, MoodBehavior.effectiveDailyCap(baseCap: 1, tuning: tuning))
    }

    func testHappyMoodRaisesDailyCapUpToFive() {
        let tuning = MoodBehavior.from(mood("雀跃", 5), now: day)
        XCTAssertEqual(4, MoodBehavior.effectiveDailyCap(baseCap: 3, tuning: tuning))
        XCTAssertEqual(5, MoodBehavior.effectiveDailyCap(baseCap: 4, tuning: tuning))
        XCTAssertEqual(5, MoodBehavior.effectiveDailyCap(baseCap: 5, tuning: tuning))
    }

    func testMoodCannotTurnProactiveMessagesBackOn() {
        let happy = MoodBehavior.from(mood("雀跃", 5), now: day)
        XCTAssertEqual(0, MoodBehavior.effectiveDailyCap(baseCap: 0, tuning: happy))
        XCTAssertEqual(0, MoodBehavior.effectiveDailyCap(baseCap: -1, tuning: happy))
    }

    // MARK: 间隔

    func testDelayMinutesAreClampedToOneThroughSixty() {
        let low = MoodBehavior.from(mood("低落", 5), now: day)      // 1.6
        XCTAssertEqual(10, MoodBehavior.adjustDelayMinutes(baseMinutes: 6, tuning: low))    // 9.6 -> 10
        XCTAssertEqual(60, MoodBehavior.adjustDelayMinutes(baseMinutes: 45, tuning: low))   // 72 -> 60
        XCTAssertEqual(60, MoodBehavior.adjustDelayMinutes(baseMinutes: 60, tuning: low))
        let happy = MoodBehavior.from(mood("雀跃", 4), now: day)    // 0.85
        XCTAssertEqual(1, MoodBehavior.adjustDelayMinutes(baseMinutes: 1, tuning: happy))   // 0.85 -> 1
        XCTAssertEqual(1, MoodBehavior.adjustDelayMinutes(baseMinutes: -3, tuning: happy))  // 先夹成 1 再算
    }

    // MARK: 深夜

    func testNightIsQuieterForEveryone() {
        let daytime = MoodBehavior.from(mood("有点累", 3), now: day)
        let deepNight = MoodBehavior.from(mood("有点累", 3), now: night)
        XCTAssertGreaterThan(deepNight.tickIntervalMultiplier, daytime.tickIntervalMultiplier)
        XCTAssertLessThan(deepNight.browseBias, daytime.browseBias)
        XCTAssertFalse(deepNight.note.isEmpty)
        XCTAssertTrue(deepNight.note.contains("夜里更安静"))
    }

    func testNightMultipliersAreApplied() {
        let daytime = MoodBehavior.from(mood("温柔", 2), now: day)
        let deepNight = MoodBehavior.from(mood("温柔", 2), now: night)
        XCTAssertEqual(daytime.tickIntervalMultiplier * 1.15, deepNight.tickIntervalMultiplier,
                       accuracy: 0.0001)
        XCTAssertEqual(daytime.browseBias * 0.7, deepNight.browseBias, accuracy: 0.0001)
        XCTAssertEqual(daytime.shareBias * 0.7, deepNight.shareBias, accuracy: 0.0001)
    }

    // MARK: 提示词

    func testFaintMoodAddsNothingToPrompt() {
        XCTAssertEqual("", MoodBehavior.promptLine(MoodState(label: "平静", intensity: 4), now: day))
        XCTAssertEqual("", MoodBehavior.promptLine(MoodState(label: "温柔", intensity: 2), now: day))
    }

    func testStrongMoodAddsOneShortLine() {
        let line = MoodBehavior.promptLine(mood("有点累", 3), now: day)
        XCTAssertTrue(line.hasPrefix("\n\n"))
        XCTAssertTrue(line.contains("短一点"))
        XCTAssertLessThan(line.count, 60)
    }

    func testShareBiasFollowsMood() {
        XCTAssertFalse(MoodBehavior.wantsToShare(MoodBehavior.from(mood("有点累", 3), now: day)))
        XCTAssertTrue(MoodBehavior.wantsToShare(MoodBehavior.from(mood("温柔", 2), now: day)))
    }

    func testReasonDoesNotAffectTuning() {
        let withReason = MoodBehavior.from(mood("低落", 3, reason: "对方说这周很忙"), now: day)
        let without = MoodBehavior.from(mood("低落", 3), now: day)
        XCTAssertEqual(without, withReason)
    }
}
