import XCTest
@testable import YapKit

/// 多维心情的纯领域测试（schema v17，`spec/mood/README.md`）。
///
/// 覆盖：范围夹取、旧数据映射、单次变化幅度、按小时衰减、按维度的行为提示。
final class MoodDimensionsTests: XCTestCase {

    let hour: Int64 = 3_600_000
    let now: Int64 = 1_800_000_000_000

    // MARK: - 范围

    func testBaselineIsZeroTwoThree() {
        XCTAssertEqual(0, MoodDimensions.baseline.valence)
        XCTAssertEqual(2, MoodDimensions.baseline.arousal)
        XCTAssertEqual(3, MoodDimensions.baseline.dominance)
        XCTAssertTrue(MoodDimensions.baseline.isBaseline)
    }

    func testRangesAreClamped() {
        let dimensions = MoodDimensions(valence: 9, arousal: -4, dominance: 11)
        XCTAssertEqual(2, dimensions.valence)
        XCTAssertEqual(0, dimensions.arousal)
        XCTAssertEqual(5, dimensions.dominance)
        XCTAssertEqual(-2, MoodDimensions.clampValence(-99))
        XCTAssertEqual(5, MoodDimensions.clampArousal(99))
        XCTAssertEqual(0, MoodDimensions.clampDominance(-1))
    }

    // MARK: - 旧数据映射（规范 §1）

    func testLegacyLabelsMapToValence() {
        XCTAssertEqual(-1, MoodDimensions.fromLegacy(label: "有点累", intensity: 3).valence)
        XCTAssertEqual(-1, MoodDimensions.fromLegacy(label: "心烦", intensity: 4).valence)
        // 规范 §1 点名的「累 / 烦」：词表里没有单字，也要给方向（与 Android 同一套 hints）。
        XCTAssertEqual(-1, MoodDimensions.fromLegacy(label: "累", intensity: 3).valence)
        XCTAssertEqual(-1, MoodDimensions.fromLegacy(label: "烦", intensity: 4).valence)
        XCTAssertEqual(1, MoodDimensions.fromLegacy(label: "高兴", intensity: 4).valence)
        XCTAssertEqual(0, MoodDimensions.fromLegacy(label: "平静", intensity: 2).valence)
        XCTAssertEqual(1, MoodDimensions.fromLegacy(label: "开心", intensity: 4).valence)
        // 词表里没有的词不猜极性。
        XCTAssertEqual(0, MoodDimensions.fromLegacy(label: "说不上来", intensity: 3).valence)
    }

    func testLegacyArousalComesFromIntensityAndDominanceIsThree() {
        let dimensions = MoodDimensions.fromLegacy(label: "低落", intensity: 4)
        XCTAssertEqual(4, dimensions.arousal)
        XCTAssertEqual(3, dimensions.dominance)
        // intensity 越界也要夹进 0..5。
        XCTAssertEqual(5, MoodDimensions.fromLegacy(label: "低落", intensity: 9).arousal)
        XCTAssertEqual(0, MoodDimensions.fromLegacy(label: "低落", intensity: -3).arousal)
    }

    func testStateResolvesDimensionsFromLegacyLabelWhenMissing() {
        let legacy = MoodState(label: "开心", intensity: 4)
        XCTAssertNil(legacy.dimensions, "老数据不该假装判过")
        XCTAssertEqual(1, legacy.resolvedDimensions.valence)
        XCTAssertEqual(4, legacy.resolvedDimensions.arousal)

        let judged = MoodState(
            label: "开心", intensity: 4,
            dimensions: MoodDimensions(valence: 2, arousal: 5, dominance: 4)
        )
        XCTAssertEqual(2, judged.resolvedDimensions.valence, "判过就以判定为准，不再走词表")
    }

    // MARK: - 单次变化幅度（规范 §2：≤2）

    func testLimitedChangeCapsEachDimension() {
        let previous = MoodDimensions(valence: 2, arousal: 5, dominance: 5)
        let next = MoodDimensions(valence: -2, arousal: 0, dominance: 0)
        let limited = previous.limitedChange(to: next, maxDelta: 2)
        XCTAssertEqual(0, limited.valence)
        XCTAssertEqual(3, limited.arousal)
        XCTAssertEqual(3, limited.dominance)
    }

    func testLimitedChangeKeepsSmallMoves() {
        let previous = MoodDimensions.baseline
        let next = MoodDimensions(valence: -1, arousal: 4, dominance: 2)
        XCTAssertEqual(next, previous.limitedChange(to: next))
    }

    // MARK: - 衰减（规范 §2：每小时向 0/2/3 靠一步）

    func testDecayMovesOneStepPerHourTowardBaseline() {
        let extreme = MoodDimensions(valence: -2, arousal: 5, dominance: 0)
        XCTAssertEqual(
            MoodDimensions(valence: -1, arousal: 4, dominance: 1),
            extreme.decayed(toward: .baseline, steps: 1)
        )
        XCTAssertEqual(
            MoodDimensions(valence: 0, arousal: 3, dominance: 2),
            extreme.decayed(toward: .baseline, steps: 2)
        )
        XCTAssertEqual(MoodDimensions.baseline, extreme.decayed(toward: .baseline, steps: 9))
    }

    func testMoodDriftDecaysDimensionsByHour() {
        let state = MoodState(
            label: "低落", intensity: 4, reason: "被一句话伤到",
            updatedAt: now, dimensions: MoodDimensions(valence: -2, arousal: 5, dominance: 1)
        )
        let twoHours = MoodDrift.decay(state, now: now + 2 * hour)
        XCTAssertEqual(MoodDimensions(valence: 0, arousal: 3, dominance: 3), twoHours.dimensions)
        // 6 小时之后情绪散干净：三维回到基准。
        let faded = MoodDrift.decay(state, now: now + MoodDrift.FADE_AFTER_MS)
        XCTAssertEqual(MoodDimensions.baseline, faded.dimensions)
    }

    func testLegacyMoodKeepsNoDimensionsAfterDecay() {
        let legacy = MoodState(label: "雀跃", intensity: 4, updatedAt: now)
        let faded = MoodDrift.decay(legacy, now: now + 2 * hour)
        XCTAssertNil(faded.dimensions, "没判过就不该凭空造出三维")
    }

    // MARK: - 按维度的行为

    func testDimensionPathReactsToValence() {
        let low = MoodBehavior.from(
            MoodState(
                label: "低落", intensity: 4,
                dimensions: MoodDimensions(valence: -2, arousal: 3, dominance: 3)
            ),
            now: now
        )
        XCTAssertGreaterThan(low.tickIntervalMultiplier, 1.0, "心情差不该更勤快")
        XCTAssertEqual(-1, low.dailyMessageCapDelta)
        XCTAssertFalse(low.replyLengthHint.isEmpty)
        XCTAssertFalse(MoodBehavior.wantsToShare(low))

        let high = MoodBehavior.from(
            MoodState(
                label: "开心", intensity: 4,
                dimensions: MoodDimensions(valence: 2, arousal: 3, dominance: 3)
            ),
            now: now
        )
        XCTAssertLessThan(high.tickIntervalMultiplier, 1.0)
        XCTAssertEqual(1, high.dailyMessageCapDelta)
    }

    func testLowArousalShortensReplies() {
        let tired = MoodBehavior.from(
            MoodState(
                label: "有点累", intensity: 3,
                dimensions: MoodDimensions(valence: 0, arousal: 0, dominance: 3)
            ),
            now: now
        )
        XCTAssertTrue(tired.replyLengthHint.contains("短"))
        XCTAssertGreaterThan(tired.tickIntervalMultiplier, 1.0)
    }

    func testLowDominanceHoldsBackSharing() {
        let quiet = MoodBehavior.from(
            MoodState(
                label: "有点委屈", intensity: 3,
                dimensions: MoodDimensions(valence: -1, arousal: 2, dominance: 0)
            ),
            now: now
        )
        XCTAssertTrue(quiet.replyLengthHint.contains("拿主意"))
        XCTAssertFalse(quiet.note.isEmpty)
    }

    func testBaselineDimensionsChangeNothing() {
        let state = MoodState(
            label: "平静", intensity: 2, dimensions: MoodDimensions.baseline
        )
        XCTAssertTrue(MoodBehavior.from(state, now: now).isNeutral())
    }

    func testLegacyPathIsUnchanged() {
        // 没有三维时行为必须与 0.12 完全一致（升级不该让节奏突然变了）。
        let legacy = MoodBehavior.from(MoodState(label: "有点累", intensity: 2), now: now)
        XCTAssertEqual(1.3, legacy.tickIntervalMultiplier, accuracy: 0.0001)
        XCTAssertEqual(-1, legacy.dailyMessageCapDelta)
    }
}
