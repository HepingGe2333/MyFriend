import XCTest
@testable import YapKit

/// 自动情绪判定的校验层（纯函数，`spec/mood/README.md` §2）。
///
/// 模型输出是外部数据：这里的每条用例都对应一种真机上见过的坏输出
///（越界、字符串数字、只给维度、坏 JSON、从雀跃直接跳到崩溃）。
final class MoodJudgementTests: XCTestCase {

    let hour: Int64 = 3_600_000
    let now: Int64 = 1_800_000_000_000

    private var previous: MoodState {
        MoodState(
            label: "平静", intensity: 2, updatedAt: now,
            dimensions: MoodDimensions(valence: 0, arousal: 2, dominance: 3)
        )
    }

    // MARK: - 解析

    func testParsesWellFormedJson() throws {
        let judgement = try XCTUnwrap(MoodJudgementRules.parse("""
        {"label":"有点累","valence":-1,"arousal":1,"dominance":2,"intensity":3,"reason":"加班到现在"}
        """, previous: previous))
        XCTAssertEqual("有点累", judgement.label)
        XCTAssertEqual(-1, judgement.valence)
        XCTAssertEqual(1, judgement.arousal)
        XCTAssertEqual(2, judgement.dominance)
        XCTAssertEqual(3, judgement.intensity)
        XCTAssertEqual("加班到现在", judgement.reason)
    }

    func testParsesJsonInsideCodeBlockWithExplanation() throws {
        let raw = """
        好的，我判断一下：
        ```json
        {"label":"开心","valence":2,"arousal":4,"dominance":4,"intensity":4}
        ```
        以上。
        """
        let judgement = try XCTUnwrap(MoodJudgementRules.parse(raw, previous: previous))
        XCTAssertEqual("开心", judgement.label)
        XCTAssertEqual(2, judgement.valence)
    }

    func testParsesNumbersWrittenAsStrings() throws {
        let judgement = try XCTUnwrap(MoodJudgementRules.parse(
            #"{"label":"烦","valence":"-2","arousal":"4","dominance":"1","intensity":"4"}"#,
            previous: previous
        ))
        XCTAssertEqual(-2, judgement.valence)
        XCTAssertEqual(4, judgement.arousal)
        XCTAssertEqual(1, judgement.dominance)
    }

    func testClampsOutOfRangeValues() throws {
        let judgement = try XCTUnwrap(MoodJudgementRules.parse(
            #"{"label":"烦","valence":-9,"arousal":99,"dominance":-3}"#,
            previous: previous
        ))
        XCTAssertEqual(-2, judgement.valence)
        XCTAssertEqual(5, judgement.arousal)
        XCTAssertEqual(0, judgement.dominance)
    }

    func testMissingLabelKeepsPreviousWord() throws {
        // 只给了维度：不能因为缺 label 就把「有点累」写成「平静」。
        let tired = MoodState(
            label: "有点累", intensity: 3, updatedAt: now,
            dimensions: MoodDimensions(valence: -1, arousal: 1, dominance: 2)
        )
        let judgement = try XCTUnwrap(MoodJudgementRules.parse(
            #"{"valence":-2,"arousal":0,"dominance":1}"#, previous: tired
        ))
        XCTAssertEqual("有点累", judgement.label)
    }

    func testBadJsonReturnsNil() {
        XCTAssertNil(MoodJudgementRules.parse("今天有点累", previous: previous))
        XCTAssertNil(MoodJudgementRules.parse("", previous: previous))
        XCTAssertNil(MoodJudgementRules.parse("{}", previous: previous), "空对象没有信息量")
    }

    // MARK: - 幅度限制（规范 §2：≤2）

    func testMergedLimitsDeltaPerDimension() throws {
        let judgement = MoodJudgement(
            label: "崩溃", intensity: 1, valence: -2, arousal: 0, dominance: 0
        )
        let merged = MoodJudgementRules.merged(judgement, previous: MoodState(
            label: "雀跃", intensity: 4, updatedAt: now,
            dimensions: MoodDimensions(valence: 2, arousal: 5, dominance: 5)
        ))
        // 0.15：取消「每维最多动 2」的限制，只做物理范围夹取 —— 直觉是什么就是什么。
        XCTAssertEqual(-2, merged.resolvedDimensions.valence)
        XCTAssertEqual(0, merged.resolvedDimensions.arousal)
        XCTAssertEqual(0, merged.resolvedDimensions.dominance)
        XCTAssertEqual(1, merged.intensity, "强度也照模型说的（1）")
    }

    func testMergedKeepsSmallChanges() {
        let judgement = MoodJudgement(
            label: "有点累", intensity: 3, valence: 0, arousal: 1, dominance: 2
        )
        let merged = MoodJudgementRules.merged(judgement, previous: previous)
        XCTAssertEqual("有点累", merged.label)
        XCTAssertEqual(0, merged.resolvedDimensions.valence)
        XCTAssertEqual(1, merged.resolvedDimensions.arousal)
        XCTAssertEqual(2, merged.resolvedDimensions.dominance)
        XCTAssertEqual(3, merged.intensity)
    }

    // MARK: - 没有明显变化就不写

    func testNoVisibleChangeIsNotWritten() {
        let same = MoodJudgement(
            label: "平静", intensity: 2, valence: 0, arousal: 2, dominance: 3
        )
        XCTAssertFalse(MoodJudgementRules.shouldWrite(same, previous: previous))

        let moved = MoodJudgement(
            label: "平静", intensity: 2, valence: 0, arousal: 3, dominance: 3
        )
        XCTAssertTrue(MoodJudgementRules.shouldWrite(moved, previous: previous), "任一维度动了就要写")
    }

    func testIntensityOnlyChangeIsWritten() {
        let judgement = MoodJudgement(
            label: "平静", intensity: 3, valence: 0, arousal: 2, dominance: 3
        )
        XCTAssertTrue(MoodJudgementRules.shouldWrite(judgement, previous: previous))
    }

    // MARK: - 失败衰减（规范 §2）

    func testFailureDecayMovesTowardBaselineByHour() {
        let state = MoodState(
            label: "烦", intensity: 4, updatedAt: now,
            dimensions: MoodDimensions(valence: -2, arousal: 5, dominance: 0)
        )
        let oneHour = MoodJudgementRules.decayedOnFailure(state, now: now + hour)
        XCTAssertEqual(MoodDimensions(valence: -1, arousal: 4, dominance: 1), oneHour.dimensions)
        let threeHours = MoodJudgementRules.decayedOnFailure(state, now: now + 3 * hour)
        XCTAssertEqual(MoodDimensions(valence: 0, arousal: 2, dominance: 3), threeHours.dimensions)
    }

    func testFailureDecayDoesNotThrowOnBadClock() {
        // 时钟回拨不该让心情跳到一个荒唐的位置。
        let state = MoodState(
            label: "烦", intensity: 4, updatedAt: now,
            dimensions: MoodDimensions(valence: -2, arousal: 5, dominance: 0)
        )
        let reversed = MoodJudgementRules.decayedOnFailure(state, now: now - 5 * hour)
        XCTAssertEqual(state.dimensions, reversed.dimensions)
    }

    // MARK: - 提示词

    func testPromptCarriesPreviousMoodAndDialogue() {
        let messages = MoodJudgementRules.messages(
            previous: previous,
            recentDialogue: "- 对方：今天好累\n- 他：嗯，早点睡吧",
            earlierSummary: "上周聊过加班"
        )
        XCTAssertEqual(2, messages.count)
        XCTAssertEqual("system", messages[0].role)
        XCTAssertTrue(messages[0].content.contains("\"label\""), "必须要求合法 JSON")
        let user = messages[1].content
        XCTAssertTrue(user.contains("平静"))
        XCTAssertTrue(user.contains("愉快度 0"))
        XCTAssertTrue(user.contains("今天好累"))
        XCTAssertTrue(user.contains("上周聊过加班"))
    }

    func testDialogueRenderingLimitsToEightLines() {
        let messages = (0..<12).map { index in
            MessageEntity(
                id: "m-\(index)", sessionId: "s", personaId: "p",
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index) 句", createdAt: Int64(index), updatedAt: Int64(index),
                status: .complete
            )
        }
        let rendered = MoodJudgementRules.renderDialogue(messages)
        let lines = rendered.split(separator: "\n")
        XCTAssertEqual(8, lines.count, "最近 8 条（规范 §2）")
        XCTAssertTrue(rendered.contains("第 11 句"))
        XCTAssertFalse(rendered.contains("第 0 句"))
    }
}
