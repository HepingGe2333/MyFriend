import Foundation
import XCTest
@testable import YapKit

/// 0.14 的节奏纯逻辑（`spec/experience/README.md` §4/§5）。
final class MessageRhythmTests: XCTestCase {

    private func mood(
        label: String = "平静",
        intensity: Int = 2,
        arousal: Int? = 2
    ) -> MoodState {
        MoodState(
            label: label,
            intensity: intensity,
            dimensions: arousal.map { MoodDimensions(valence: 0, arousal: $0, dominance: 3) }
        )
    }

    // MARK: - 打字延迟（§5）

    func testTypingDelayUsesTheSpecifiedFormula() {
        // 0.4 + 40×0.025 = 1.4 秒（唤醒度 2.5 时修正为 0）。
        let base = TypingDelay.seconds(
            charCount: 40,
            mood: MoodState(label: "平静", intensity: 2,
                            dimensions: MoodDimensions(valence: 0, arousal: 2, dominance: 3))
        )
        XCTAssertEqual(base, 1.4 + 0.075, accuracy: 0.0001,
                       "唤醒度 2 比基准 2.5 低半格 → +0.075 秒")

        // `MoodDimensions.arousal` 是整数，基准 2.5 用「2 级与 3 级的中点」表达：
        // 这里直接验证公式本体（0.4 + 40×0.025 = 1.4），修正项在下面的用例里单测。
        let neutral = TypingDelay.seconds(charCount: 40, mood: mood(arousal: 2))
            - TypingDelay.moodAdjustment(mood(arousal: 2))
        XCTAssertEqual(neutral, 1.4, accuracy: 0.0001, "公式本体：0.4 + 40×0.025")
    }

    func testTypingDelayIsClamped() {
        let shortest = TypingDelay.seconds(charCount: 0, mood: mood(arousal: 5))
        XCTAssertGreaterThanOrEqual(shortest, TypingDelay.minSeconds)
        XCTAssertEqual(shortest, TypingDelay.minSeconds, accuracy: 0.0001)

        // 字数上限 120 → 0.4 + 3.0 + 最慢修正 0.375 = 3.775 秒（还没触到 4.0 的上界）。
        let longest = TypingDelay.seconds(charCount: 10_000, mood: mood(arousal: 0))
        XCTAssertEqual(longest, 3.775, accuracy: 0.0001)
        XCTAssertLessThanOrEqual(longest, TypingDelay.maxSeconds)
    }

    func testLowArousalIsSlowerAndHighArousalIsFaster() {
        let low = TypingDelay.seconds(charCount: 30, mood: mood(arousal: 0))
        let mid = TypingDelay.seconds(charCount: 30, mood: mood(arousal: 2))
        let high = TypingDelay.seconds(charCount: 30, mood: mood(arousal: 5))
        XCTAssertGreaterThan(low, mid)
        XCTAssertGreaterThan(mid, high)
    }

    func testTiredLabelSlowsTypingFurther() {
        let calm = TypingDelay.seconds(charCount: 20, mood: mood(label: "平静", arousal: 2))
        let tired = TypingDelay.seconds(charCount: 20, mood: mood(label: "有点累", arousal: 2))
        XCTAssertGreaterThan(tired, calm)
    }

    func testTypingDelayWithoutDimensionsStillWorks() {
        let value = TypingDelay.seconds(
            charCount: 20, mood: MoodState(label: "平静", intensity: 3)
        )
        XCTAssertGreaterThanOrEqual(value, TypingDelay.minSeconds)
        XCTAssertLessThanOrEqual(value, TypingDelay.maxSeconds)
    }

    func testNanosNeverNegative() {
        XCTAssertEqual(TypingDelay.nanos(seconds: 0), 0)
        XCTAssertEqual(TypingDelay.nanos(seconds: -3), 0)
        XCTAssertEqual(TypingDelay.nanos(seconds: 1.5), 1_500_000_000)
    }

    // MARK: - 连发间隔（§4）

    func testGapStaysWithinEightHundredMillisToTwoPointFiveSeconds() {
        for count in [1, 20, 60, 119, 120, 10_000] {
            for arousal in [0, 2, 5] {
                let gap = MessageRhythm.gapSeconds(charCount: count, mood: mood(arousal: arousal))
                XCTAssertGreaterThanOrEqual(gap, MessageRhythm.minGapSeconds, "count=\(count)")
                XCTAssertLessThanOrEqual(gap, MessageRhythm.maxGapSeconds, "count=\(count)")
            }
        }
        // 短句更利落。
        XCTAssertLessThan(
            MessageRhythm.gapSeconds(charCount: 2, mood: mood()),
            MessageRhythm.gapSeconds(charCount: 120, mood: mood())
        )
    }

    // MARK: - 每条字数（§4）

    func testSendTextIsTrimmedAndCappedAt120() {
        XCTAssertNil(MessageRhythm.cleanSendText("   "))
        XCTAssertEqual(MessageRhythm.cleanSendText("  在吗  "), "在吗")
        let long = String(repeating: "字", count: 200)
        let cleaned = MessageRhythm.cleanSendText(long)
        XCTAssertEqual(cleaned?.count, MessageRhythm.maxMessageChars)
    }

    func testMessageCapIsFourPerRound() {
        XCTAssertEqual(MessageRhythm.maxMessagesPerRound, 4)
        XCTAssertEqual(MessageRhythm.maxSplitMessages, 3)
    }

    // MARK: - 按空行拆（§4）

    func testSplitKeepsSingleParagraphAsOne() {
        XCTAssertEqual(MessageRhythm.split("今天有点忙，晚点找你"), ["今天有点忙，晚点找你"])
    }

    func testSplitOnBlankLines() {
        let text = "今天有点忙。\n\n晚点找你。\n\n你先睡吧。"
        XCTAssertEqual(MessageRhythm.split(text), ["今天有点忙。", "晚点找你。", "你先睡吧。"])
    }

    func testSplitCapsAtThreeAndMergesTheHead() {
        let text = "一。\n\n二。\n\n三。\n\n四。\n\n五。"
        let chunks = MessageRhythm.split(text)
        XCTAssertEqual(chunks.count, 3)
        XCTAssertTrue(chunks[0].contains("一。"))
        XCTAssertTrue(chunks[0].contains("三。"), "段落太多时把开头几段并进第一条")
        XCTAssertEqual(chunks[1], "四。")
        XCTAssertEqual(chunks[2], "五。")
    }

    func testSplitIgnoresEmptyParagraphs() {
        XCTAssertEqual(MessageRhythm.split("一。\n\n\n\n二。"), ["一。", "二。"])
        XCTAssertEqual(MessageRhythm.split("   "), [])
    }
}
