import XCTest
@testable import YapKit

/// 0.15「心情直觉化」的规则测试：
/// 每条用户消息后判定、取消「每维 ≤2」的硬限制（只做范围夹取）、
/// 提示词「先直觉结论后行为指导」、以及同一轮里 `set_mood` 可以再改。
final class MoodIntuitionTests: XCTestCase {

    private func mood(arousal: Int = 2) -> MoodState {
        MoodState(
            label: "平静", intensity: 3,
            dimensions: MoodDimensions(valence: 0, arousal: arousal, dominance: 3)
        )
    }

    // MARK: - 每一条用户消息后判定

    func testJudgementRunsAfterEveryUserMessage() {
        XCTAssertEqual(MoodJudgementRules.triggerUserMessages, 1, "0.15：每条用户消息后都判一次")
    }

    // MARK: - 取消 ≤2 限制

    func testBigJumpIsKeptInsteadOfBeingSmoothed() {
        let previous = MoodState(
            label: "雀跃", intensity: 5,
            dimensions: MoodDimensions(valence: 2, arousal: 5, dominance: 5)
        )
        let judgement = MoodJudgement(
            label: "崩溃", intensity: 1, valence: -2, arousal: 0, dominance: 0
        )
        let merged = MoodJudgementRules.merged(judgement, previous: previous)
        XCTAssertEqual(merged.resolvedDimensions, MoodDimensions(valence: -2, arousal: 0, dominance: 0),
                       "一句话从雀跃到崩溃就该如实写下来")
        XCTAssertEqual(merged.intensity, 1)
        XCTAssertEqual(merged.label, "崩溃")
    }

    func testMergedStillClampsOutOfRangeValues() {
        let judgement = MoodJudgement(
            label: "烦", intensity: 99, valence: -99, arousal: 99, dominance: -99
        )
        let merged = MoodJudgementRules.merged(judgement, previous: mood())
        XCTAssertEqual(merged.resolvedDimensions.valence, -2)
        XCTAssertEqual(merged.resolvedDimensions.arousal, MoodDimensions.arousalRange.upperBound)
        XCTAssertEqual(merged.resolvedDimensions.dominance, MoodDimensions.dominanceRange.lowerBound)
        XCTAssertEqual(merged.intensity, 5)
    }

    func testParseClampsPhysicalRangesOnly() {
        let judgement = XCTUnwrapOrFail(MoodJudgementRules.parse("""
        {"label":"炸了","valence":-9,"arousal":9,"dominance":-4,"intensity":9}
        """, previous: mood()))
        XCTAssertEqual(judgement.valence, -2)
        XCTAssertEqual(judgement.arousal, 5)
        XCTAssertEqual(judgement.dominance, 0)
        XCTAssertEqual(judgement.intensity, 5)
    }

    // MARK: - 提示词：先直觉结论、后行为指导

    func testMoodPromptPutsIntuitionBeforeBehaviourGuidance() throws {
        let text = MoodPrompt.line(MoodState(
            label: "有点烦", intensity: 4, reason: "被催图",
            dimensions: MoodDimensions(valence: -2, arousal: 4, dominance: 1)
        ))
        let intuition = try XCTUnwrap(text.range(of: "先凭直觉说"))
        let dimensions = try XCTUnwrap(text.range(of: "心情：有点烦"))
        let guidance = try XCTUnwrap(text.range(of: "- 这会影响你怎么说"))
        XCTAssertLessThan(intuition.lowerBound, dimensions.lowerBound, "直觉结论要在维度之前")
        XCTAssertLessThan(dimensions.lowerBound, guidance.lowerBound, "行为指导要排在直觉结论之后")
        // 规范里的两句原样保留（不许报数值 / 不许自述情绪）。
        XCTAssertTrue(text.contains("不要直接报数值"))
        XCTAssertTrue(text.contains("我的情绪是"))
    }

    func testJudgementPromptAsksForIntuitionFirst() {
        let messages = MoodJudgementRules.messages(
            previous: mood(), recentDialogue: "- 对方：今天好累", earlierSummary: nil
        )
        let text = messages.map(\.content).joined(separator: "\n")
        XCTAssertTrue(text.contains("先凭感觉给结论"), "判定提示词要先要直觉结论")
        XCTAssertTrue(text.contains("不要为了「变化平滑」"), "要明确禁止把强烈感受压成中间值")
        XCTAssertTrue(text.contains("guidance"), "行为指导只写一句（可省略）")
    }

    // MARK: - set_mood 可以带三维、同一轮可以再改

    func testSetMoodAcceptsExplicitDimensionsAndCanChangeAgain() throws {
        let stores = try DatabaseStores.open(path: ":memory:")
        let repository = MoodRepository(stores: stores, now: { 1_000 })
        try stores.personas.upsert(PersonaEntity(id: "p1", name: "林深"))
        let first = try XCTUnwrap(repository.setMood(
            personaId: "p1", label: "有点烦", intensity: 4,
            reason: "被催图", valence: -2, arousal: 4, dominance: 1, now: 1_000
        ))
        XCTAssertEqual(try XCTUnwrap(first.dimensions),
                       MoodDimensions(valence: -2, arousal: 4, dominance: 1))

        // 同一轮里再改一次：以最后一次为准，不被「一轮只能改一次」挡住。
        let second = try XCTUnwrap(repository.setMood(
            personaId: "p1", label: "平静下来了", intensity: 2,
            reason: "想通了", valence: 0, arousal: 2, dominance: 3, now: 1_100
        ))
        XCTAssertEqual(second.label, "平静下来了")
        XCTAssertEqual(second.dimensions, MoodDimensions(valence: 0, arousal: 2, dominance: 3))
        XCTAssertEqual(repository.current(personaId: "p1", now: 1_100).label, "平静下来了")
    }

    func testSetMoodWithoutDimensionsFallsBackToLexicon() throws {
        let stores = try DatabaseStores.open(path: ":memory:")
        let repository = MoodRepository(stores: stores, now: { 1_000 })
        try stores.personas.upsert(PersonaEntity(id: "p1", name: "林深"))
        let state = try XCTUnwrap(repository.setMood(
            personaId: "p1", label: "开心", intensity: 4, now: 1_000
        ))
        XCTAssertEqual(try XCTUnwrap(state.dimensions),
                       MoodDimensions.fromLegacy(label: "开心", intensity: 4))
    }

    func testSetMoodClampsDimensionsFromModel() throws {
        let stores = try DatabaseStores.open(path: ":memory:")
        let repository = MoodRepository(stores: stores, now: { 1_000 })
        try stores.personas.upsert(PersonaEntity(id: "p1", name: "林深"))
        let state = try XCTUnwrap(repository.setMood(
            personaId: "p1", label: "烦", intensity: 99,
            valence: -99, arousal: 99, dominance: -99, now: 1_000
        ))
        let dimensions = try XCTUnwrap(state.dimensions)
        XCTAssertEqual(dimensions.valence, -2)
        XCTAssertEqual(dimensions.arousal, 5)
        XCTAssertEqual(dimensions.dominance, 0)
        XCTAssertEqual(state.intensity, 5)
    }
}

// MARK: - 小工具

private func XCTUnwrapOrFail<T>(_ value: T?, file: StaticString = #filePath, line: UInt = #line) -> T {
    guard let value else {
        XCTFail("解析失败", file: file, line: line)
        fatalError("解析失败")
    }
    return value
}
