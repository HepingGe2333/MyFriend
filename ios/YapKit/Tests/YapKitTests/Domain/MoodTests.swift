import XCTest
@testable import YapKit

/// 心情的纯领域测试（移植自 Android 的 MoodTest，去掉 agent 协议那部分）。
///
/// 覆盖：词表规整、随时间淡去、引导时机、提示词措辞。
final class MoodTests: XCTestCase {

    let hour: Int64 = 3_600_000
    let minute: Int64 = 60_000
    let now: Int64 = 1_800_000_000_000

    // MARK: 词表

    func testEmptyLabelFallsBackToBaseline() {
        XCTAssertEqual(MoodLexicon.BASELINE, MoodLexicon.normalize(""))
        XCTAssertEqual(MoodLexicon.BASELINE, MoodLexicon.normalize("   "))
    }

    func testLabelDropsWhitespaceAndIsLimited() {
        XCTAssertEqual("有点累", MoodLexicon.normalize("  有点 累 "))
        XCTAssertEqual(12, MoodLexicon.normalize("一二三四五六七八九十一二三四五").count)
    }

    func testUnknownWordIsNeutral() {
        let word = MoodLexicon.classify("说不上来")
        XCTAssertEqual(0, word.valence)
        XCTAssertEqual(0, word.energy)
        XCTAssertEqual("说不上来", word.label)
    }

    func testNegativeAndLowEnergyChecks() {
        XCTAssertTrue(MoodLexicon.isNegative("低落"))
        XCTAssertFalse(MoodLexicon.isNegative("平静"))
        XCTAssertTrue(MoodLexicon.isLowEnergy("有点累"))
        XCTAssertFalse(MoodLexicon.isLowEnergy("雀跃"))
    }

    func testDisplayIncludesIntensityAndReason() {
        let state = MoodState(label: "雀跃", intensity: 4, reason: "刚聊到想去的地方")
        XCTAssertEqual("雀跃（4/5） · 刚聊到想去的地方", state.display())
        XCTAssertEqual(MoodLexicon.BASELINE, MoodState().display())
    }

    // MARK: 衰减

    func testMoodFadesBackToBaselineAfterHours() {
        let state = MoodState(label: "低落", intensity: 4, reason: "被一句话伤到", updatedAt: now)
        let faded = MoodDrift.decay(state, now: now + MoodDrift.FADE_AFTER_MS)
        XCTAssertEqual(MoodLexicon.BASELINE, faded.label)
        XCTAssertEqual(1, faded.intensity)
        XCTAssertEqual("", faded.reason)
    }

    func testIntensityDropsEachHourButNeverToZero() {
        let state = MoodState(label: "雀跃", intensity: 4, reason: "好消息", updatedAt: now)
        let twoHours = MoodDrift.decay(state, now: now + 2 * hour)
        XCTAssertEqual("雀跃", twoHours.label)
        XCTAssertEqual(2, twoHours.intensity)

        let fiveHours = MoodDrift.decay(state, now: now + 5 * hour)
        XCTAssertEqual(1, fiveHours.intensity)
    }

    func testNegativeMoodGetsFlattenedFirst() {
        let state = MoodState(label: "低落", intensity: 2, reason: "小事", updatedAt: now)
        let faded = MoodDrift.decay(state, now: now + 2 * hour)
        XCTAssertEqual(1, faded.intensity)
        XCTAssertEqual(MoodLexicon.BASELINE, faded.label)
        XCTAssertEqual("", faded.reason)
    }

    func testFreshMoodIsNotDiscounted() {
        let state = MoodState(label: "期待", intensity: 3, reason: "明天见面", updatedAt: now)
        XCTAssertEqual(state, MoodDrift.decay(state, now: now + 30_000))
    }

    func testEmptyMoodDecaysToBaselineLabel() {
        XCTAssertEqual(MoodLexicon.BASELINE, MoodDrift.decay(MoodState(), now: now).label)
    }

    // MARK: 引导时机

    func testThreeSentencesTriggerReflection() {
        let state = MoodState(label: "平静", intensity: 1, updatedAt: now)
        XCTAssertFalse(MoodDrift.shouldReflect(state, spokenSinceUpdate: 2, now: now + minute))
        XCTAssertTrue(MoodDrift.shouldReflect(state, spokenSinceUpdate: 3, now: now + minute))
    }

    func testLongSilencePlusOneSentenceAlsoWorthReflecting() {
        let state = MoodState(label: "平静", intensity: 1, updatedAt: now)
        XCTAssertTrue(MoodDrift.shouldReflect(state, spokenSinceUpdate: 1, now: now + 5 * hour))
        XCTAssertFalse(MoodDrift.shouldReflect(state, spokenSinceUpdate: 0, now: now + 5 * hour))
    }

    func testJustNudgedDoesNotAskAgain() {
        let state = MoodState(label: "平静", intensity: 1, updatedAt: now)
        let nudgedAt = now + 2 * hour
        XCTAssertFalse(MoodDrift.shouldReflect(state, spokenSinceUpdate: 0,
                                              now: now + 5 * hour, lastTouchAt: nudgedAt))
    }

    // MARK: 提示词

    func testPromptCarriesMoodAndReason() {
        let line = MoodPrompt.line(MoodState(label: "有点累", intensity: 3, reason: "今天加班"))
        XCTAssertTrue(line.contains("有点累"))
        XCTAssertTrue(line.contains("3/5"))
        XCTAssertTrue(line.contains("今天加班"))
        XCTAssertTrue(line.contains("更短"))
    }

    func testPromptWithoutMoodStillSaysBaseline() {
        let line = MoodPrompt.line(MoodState())
        XCTAssertTrue(line.contains("心情"))
        XCTAssertTrue(line.contains(MoodLexicon.BASELINE))
    }

    func testReflectionNudgeIsFirstPerson() {
        let nudge = MoodPrompt.reflectionNudge(MoodState(label: "平静", intensity: 1), spokenSinceUpdate: 4)
        XCTAssertTrue(nudge.contains("现在我的心情变得"))
        XCTAssertTrue(nudge.contains("set_mood"))
        XCTAssertTrue(nudge.contains("4"))
        XCTAssertFalse(nudge.contains("请更新"), "不要机械指令")
    }
}
