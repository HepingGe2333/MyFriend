import XCTest
@testable import YapKit

/// 提示词层的两个契约：
/// 1. **多维心情段**（`spec/mood/README.md` §3）：有三维数值、有按维度的行为提示、
///    并且明确禁止「报数值 / 说「我的情绪是…」」；
/// 2. **反 AI 味**（§5）：六条约束真的进了系统提示词，且排在 `# 边界` 之后。
final class PromptMoodAntiAITests: XCTestCase {

    // MARK: - 多维心情段

    func testMoodSectionShapeMatchesSpec() {
        let line = MoodPrompt.line(MoodState(
            label: "烦", intensity: 4, reason: "被催图",
            dimensions: MoodDimensions(valence: -2, arousal: 4, dominance: 1)
        ))
        XCTAssertTrue(line.contains(MoodPrompt.sectionTitle))
        XCTAssertTrue(line.contains("先凭直觉说"))
        XCTAssertTrue(line.contains("心情：烦（强度 4/5）。"))
        XCTAssertTrue(line.contains("愉快度 -2 / 唤醒度 4/5 / 掌控感 1/5。"))
        XCTAssertTrue(line.contains("- 这会影响你怎么说："))
        XCTAssertTrue(line.contains("不要直接报数值"), "规范 §3 的最后一条必须原样出现")
        XCTAssertTrue(line.contains("我的情绪是"), "要明确禁止这种自述")
    }

    func testMoodSectionNeverLeaksNumbersInstruction() {
        // 数值是给模型自己看的；要求它「别说出口」的那句必须始终在（否则模型会念数值）。
        for label in ["平静", "有点累", "雀跃"] {
            let line = MoodPrompt.line(MoodState(label: label, intensity: 3))
            XCTAssertTrue(line.contains("不要直接报数值"), "\(label) 缺禁止报数值那句话")
        }
    }

    func testBehaviorHintsAreOneOrTwoSentences() {
        let extremes = [
            MoodDimensions(valence: -2, arousal: 0, dominance: 0),
            MoodDimensions(valence: 2, arousal: 5, dominance: 5),
            MoodDimensions.baseline
        ]
        for dimensions in extremes {
            let hints = MoodPrompt.behaviorHints(dimensions)
            XCTAssertFalse(hints.isEmpty)
            let sentences = hints.filter { $0 == "。" }.count
            XCTAssertLessThanOrEqual(sentences, 2, "规范 §3：1-2 句，不要写成参数表：\(hints)")
        }
    }

    func testSystemPromptCarriesMultiDimensionalMood() {
        let system = PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: "# 你是谁\n你是林深。",
            personaName: "林深",
            moodBlock: MoodPrompt.line(MoodState(
                label: "有点累", intensity: 3,
                dimensions: MoodDimensions(valence: -1, arousal: 1, dominance: 2)
            ))
        ))
        XCTAssertTrue(system.contains(MoodPrompt.sectionTitle))
        XCTAssertTrue(system.contains("愉快度 -1"))
    }

    // MARK: - 反 AI 味

    func testAntiAISectionCoversEveryRuleFromSpec() {
        XCTAssertEqual(AntiAIPrompt.section, "# 别像 AI")
        XCTAssertEqual(7, AntiAIPrompt.rules.count)
        let text = AntiAIPrompt.text()
        // 规范 §5 的六条，逐条对应关键字。
        XCTAssertTrue(text.contains("反问"))
        XCTAssertTrue(text.contains("总结"))
        XCTAssertTrue(text.contains("列点"))
        XCTAssertTrue(text.contains("安慰") || text.contains("认同"))
        XCTAssertTrue(text.contains("stay_silent"), "要给出「可以不回」这条出路")
        XCTAssertTrue(text.contains("口头禅"))
        XCTAssertTrue(text.contains("不知道就说不知道"))
    }

    func testAntiAISectionIsInDefaultSystemPrompt() {
        let system = PromptBuilder.systemPrompt(SystemPromptInput(personaText: "# 你是谁\n你是林深。"))
        XCTAssertTrue(system.contains(AntiAIPrompt.section))
        for rule in AntiAIPrompt.rules {
            XCTAssertTrue(system.contains(rule), "系统提示词里少了这条约束：\(rule)")
        }
    }

    func testAntiAISectionComesBeforeBoundaries() {
        let system = PromptBuilder.systemPrompt(SystemPromptInput(personaText: "# 你是谁\n你是林深。"))
        let boundary = try? XCTUnwrap(system.range(of: PersonaPromptScaffold.boundariesSection))
        let antiAI = try? XCTUnwrap(system.range(of: AntiAIPrompt.section))
        guard let boundary, let antiAI else {
            return XCTFail("系统提示词里缺边界或反 AI 段")
        }
        XCTAssertLessThan(antiAI.lowerBound, boundary.lowerBound,
                          "反 AI 味排在边界之前（与 Android 的拼接顺序一致），边界收尾最容易被记住")
    }

    func testAntiAISectionCanBeRemovedForTests() {
        let system = PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: "# 你是谁\n你是林深。", antiAIPrompt: ""
        ))
        XCTAssertFalse(system.contains(AntiAIPrompt.section))
    }
}
