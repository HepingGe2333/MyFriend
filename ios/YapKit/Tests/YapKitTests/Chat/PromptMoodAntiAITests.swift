import XCTest
@testable import YapKit

/// 提示词层的两个契约：
/// 1. **多维心情段**（`spec/mood/README.md` §3）：有三维数值、有按维度的行为提示、
///    并且明确禁止「报数值 / 说「我的情绪是…」」；
/// 2. **反 AI 味**（§5）：0.15 profile 的 core 五条真的进了系统提示词，且排在 `# 边界` 之前。
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

    /// 0.15 profile 的 `anti=core`：**只保留五条**（不反问 / 不总结 / 不列点 / 不总安慰 / 不旁白）。
    ///
    /// 删掉的三条（短句与口头禅、不主动给建议、stay_silent 的用法）在评测里与这五条重复，
    /// 而且删掉之后综合分更高（`eval/reports/context-search-axes-*.md`）。
    func testAntiAISectionCoversCoreFiveRules() {
        XCTAssertEqual(AntiAIPrompt.section, "# 别像 AI")
        XCTAssertEqual(4, AntiAIPrompt.rules.count, "四条「不要」")
        XCTAssertEqual(5, AntiAIPrompt.coreRules.count, "加上旁白正好五条")
        let text = AntiAIPrompt.text()
        // 五条逐条对应关键字。
        XCTAssertTrue(text.contains("反问"))
        XCTAssertTrue(text.contains("总结"))
        XCTAssertTrue(text.contains("列点"))
        XCTAssertTrue(text.contains("安慰") || text.contains("认同"))
        XCTAssertTrue(text.contains("旁白") || text.contains("舞台说明"))
        // 提示词里正好五条 bullet，不多出一条。
        let bullets = text.split(separator: "\n").filter { $0.hasPrefix("- ") }
        XCTAssertEqual(bullets.count, 5, "core 之外不许再塞规则：\(text)")
    }

    /// 被删掉的那几条不许「又从别的段落漏回来」。
    func testRemovedRulesAreGone() {
        let text = AntiAIPrompt.text()
        XCTAssertFalse(text.contains("口头禅"))
        XCTAssertFalse(text.contains("不知道就说不知道"))
        // stay_silent **留**在第四条里（「可以不回」是这五条的一部分），
        // 删掉的是「什么时候调用 stay_silent」那一条单独的规则。
        XCTAssertTrue(text.contains("stay_silent"))
        XCTAssertFalse(text.contains("只已读不回"))
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

    /// 中英文人格**同步**：两边都正好五条，且中文一侧逐字等于共享契约。
    ///
    /// 这条是「契约测试」：反 AI 文案是两端逐字一致的规则，任何一端多一条少一条
    /// （中文不列点、英文却列点）都会让同一个产品里的两个人格观感分叉。
    func testChineseAndEnglishAntiAIStayInSync() {
        let chinese = PersonaLanguagePrompt.antiAISection(language: .zh)
        let english = PersonaLanguagePrompt.antiAISection(language: .en)
        func bullets(_ text: String) -> Int {
            text.split(separator: "\n").filter { $0.hasPrefix("- ") }.count
        }
        XCTAssertEqual(bullets(chinese), 5, chinese)
        XCTAssertEqual(bullets(english), 5, english)
        // 中文一侧逐字复用共享契约（不再抄第二份）。
        XCTAssertEqual(AntiAIPrompt.rules, PromptContract.AntiAi.RULES)
        XCTAssertEqual(PromptContract.AntiAi.RULES.count, 4)
        // 英文四条与中文四条一一对应（条数相同、语言不同）。
        XCTAssertEqual(
            PromptTemplates.body(section: .antiAi, language: .en).count,
            PromptContract.AntiAi.RULES.count
        )
        XCTAssertNotEqual(chinese, english)
    }

    func testAntiAISectionCanBeRemovedForTests() {
        let system = PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: "# 你是谁\n你是林深。", antiAIPrompt: ""
        ))
        XCTAssertFalse(system.contains(AntiAIPrompt.section))
    }
}
