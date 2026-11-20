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

    // MARK: - 打字延迟（0.15：均值 + ±35% 随机波动）

    /// 固定随机源：0.5 = 不抖动（正好等于均值）。
    private let neutral: () -> Double = { 0.5 }

    func testTypingDelayUsesSharedMean() {
        // 共享契约：clamp(0.3 + 字数×0.018, 0.3, 2.2) + 心情修正，再乘 ±35%。
        // 唤醒度 2 → (2.5 − 2) × 0.15 = +0.075 秒。
        let base = TypingDelay.seconds(
            charCount: 40,
            mood: MoodState(label: "平静", intensity: 2,
                            dimensions: MoodDimensions(valence: 0, arousal: 2, dominance: 3)),
            random: neutral
        )
        let expected = ExperienceContract.shared.typingBaseSeconds(length: 40)
            + TypingDelay.moodAdjustment(mood(arousal: 2))
        XCTAssertEqual(base, expected, accuracy: 0.0001)

        // 均值为 0 心情修正时，就是共享契约的均值本体。
        let calm = mood(arousal: 2)
        let meanOnly = TypingDelay.seconds(charCount: 40, mood: calm, random: neutral)
            - TypingDelay.moodAdjustment(calm)
        XCTAssertEqual(meanOnly,
                       ExperienceContract.shared.typingBaseSeconds(length: 40),
                       accuracy: 0.0001, "公式本体必须与共享契约同式")
    }

    func testTypingDelayJittersWithinThirtyFivePercent() {
        let mean = ExperienceContract.shared.typingBaseSeconds(length: 60)
            + TypingDelay.moodAdjustment(mood(arousal: 2))
        let fastest = TypingDelay.seconds(charCount: 60, mood: mood(arousal: 2), random: { 0 })
        let slowest = TypingDelay.seconds(charCount: 60, mood: mood(arousal: 2), random: { 1 })
        XCTAssertEqual(fastest, mean * 0.65, accuracy: 0.0001)
        XCTAssertEqual(slowest, mean * 1.35, accuracy: 0.0001)
        // 坏随机源按 0.5（均值）处理，不能让节奏跑飞。
        let nan = TypingDelay.seconds(charCount: 60, mood: mood(arousal: 2), random: { .nan })
        XCTAssertEqual(nan, mean, accuracy: 0.0001)
        let outOfRange = TypingDelay.seconds(charCount: 60, mood: mood(arousal: 2), random: { 9 })
        XCTAssertEqual(outOfRange, mean * 1.35, accuracy: 0.0001)
    }

    func testTypingDelayIsPositiveAndMeanGrowsWithLength() {
        let shortest = TypingDelay.seconds(charCount: 0, mood: mood(arousal: 5), random: neutral)
        XCTAssertGreaterThanOrEqual(shortest, ExperienceContract.TYPING_MIN_SECONDS)
        XCTAssertLessThanOrEqual(shortest, ExperienceContract.TYPING_MAX_SECONDS)
        XCTAssertLessThan(
            TypingDelay.seconds(charCount: 5, mood: mood(), random: neutral),
            TypingDelay.seconds(charCount: 100, mood: mood(), random: neutral)
        )
    }

    func testServerAuthoritativeModeAddsNoLocalDelay() {
        XCTAssertEqual(
            TypingDelay.seconds(charCount: 40, mood: MoodState(),
                                serverAuthoritative: true, random: neutral), 0
        )
    }

    func testLowArousalIsSlowerAndHighArousalIsFaster() {
        let low = TypingDelay.seconds(charCount: 30, mood: mood(arousal: 0), random: neutral)
        let mid = TypingDelay.seconds(charCount: 30, mood: mood(arousal: 2), random: neutral)
        let high = TypingDelay.seconds(charCount: 30, mood: mood(arousal: 5), random: neutral)
        XCTAssertGreaterThan(low, mid)
        XCTAssertGreaterThan(mid, high)
    }

    func testMoodAdjustmentIsClampedToSharedLimit() {
        for arousal in -5...12 {
            let adjustment = TypingDelay.moodAdjustment(mood(arousal: arousal))
            XCTAssertLessThanOrEqual(abs(adjustment),
                                     ExperienceContract.MAX_MOOD_ADJUSTMENT_SECONDS + 0.0001)
        }
        XCTAssertGreaterThan(
            TypingDelay.moodAdjustment(mood(label: "有点累", arousal: 2)),
            TypingDelay.moodAdjustment(mood(label: "平静", arousal: 2))
        )
    }

    func testNanosNeverNegative() {
        XCTAssertEqual(TypingDelay.nanos(seconds: 0), 0)
        XCTAssertEqual(TypingDelay.nanos(seconds: -3), 0)
        XCTAssertEqual(TypingDelay.nanos(seconds: 1.5), 1_500_000_000)
    }

    // MARK: - 连发间隔（0.15：共享契约 + 随机波动）

    func testGapUsesSharedBurstCurve() {
        // 均值 0.2 + 字数×0.012，夹到 0.2–1.5；随机 0.5 时不抖动。
        let short = MessageRhythm.gapSeconds(charCount: 2, mood: mood(), random: neutral)
        let long = MessageRhythm.gapSeconds(charCount: 120, mood: mood(), random: neutral)
        XCTAssertEqual(short,
                       ExperienceContract.shared.burstGapBaseSeconds(length: 2)
                           + MessageRhythm.gapSeconds(charCount: 2, mood: mood(), random: { 0.5 })
                           - ExperienceContract.shared.burstGapBaseSeconds(length: 2),
                       accuracy: 0.0001)
        XCTAssertLessThan(short, long, "短句更利落")
        // 抖动后的上界 = 均值上限 × 1.35。
        let slowest = MessageRhythm.gapSeconds(charCount: 10_000, mood: mood(), random: { 1 })
        XCTAssertEqual(slowest,
                       ExperienceContract.BURST_GAP_MAX_SECONDS * 1.35, accuracy: 0.0001)
        let fastest = MessageRhythm.gapSeconds(charCount: 0, mood: mood(), random: { 0 })
        XCTAssertGreaterThanOrEqual(fastest, ExperienceContract.BURST_GAP_MIN_SECONDS * 0.65 - 0.0001)
    }

    func testGapMoodAdjustmentMovesSameDirectionAsTyping() {
        let slow = MessageRhythm.gapSeconds(charCount: 40, mood: mood(arousal: 0), random: neutral)
        let fast = MessageRhythm.gapSeconds(charCount: 40, mood: mood(arousal: 5), random: neutral)
        XCTAssertGreaterThan(slow, fast)
    }

    // MARK: - 工具轮数（0.15）

    func testToolRoundsDefaultsAndClamp() {
        let contract = ExperienceContract.shared
        XCTAssertEqual(contract.clampToolRounds(requested: nil), 5)
        XCTAssertEqual(contract.clampToolRounds(requested: 0), 1)
        XCTAssertEqual(contract.clampToolRounds(requested: -3), 1)
        XCTAssertEqual(contract.clampToolRounds(requested: 3), 3)
        XCTAssertEqual(contract.clampToolRounds(requested: 15), 15)
        XCTAssertEqual(contract.clampToolRounds(requested: 99), 15)
        XCTAssertEqual(ChatService.maxToolRounds, ExperienceContract.DEFAULT_TOOL_ROUNDS)
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
