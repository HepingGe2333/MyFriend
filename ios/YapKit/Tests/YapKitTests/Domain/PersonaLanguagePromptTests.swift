import Foundation
import XCTest
@testable import YapKit

/// 0.15「人格语言」的守卫测试。
///
/// 钉四件事：
/// 1. **标签解析**：空 / 认不出 → 中文（产品默认）；`en-US` / `中文` / `English` 都认；
/// 2. **中文逐字不变**：语言段、反 AI、工具能力、心情四段必须与旧实现（`AntiAIPrompt` /
///    `ToolCatalog.capabilityPrompt` / `MoodPrompt`）**一字不差**——0.15 只允许「按人格语言
///    多出一条英文路径」，不允许顺手改中文提示词；
/// 3. **英文真的换了语言**：标题、指令、工具说明、心情段都是英文，且覆盖 `ToolCatalog.all`
///    的每一个工具（少一个工具的说明，模型就少一只「手」）；
/// 4. **语言是人格属性，不是页面属性**：这些函数只吃 `languageTag`，没有 `Locale` / `Bundle`
///    参数，同一个人格在任何界面语言下产出逐字相同。
final class PersonaLanguagePromptTests: XCTestCase {

    // MARK: - 工具方法

    private func persona(_ tag: String?, name: String = "林深") -> PersonaEntity {
        PersonaEntity(id: "persona-1", name: name, languageTag: tag)
    }

    private static let cjk = try! NSRegularExpression(pattern: #"[\u4e00-\u9fff]"#)

    private func containsCJK(_ text: String) -> Bool {
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return Self.cjk.firstMatch(in: text, range: range) != nil
    }

    // MARK: - 1. 标签解析

    func testEmptyOrNilTagResolvesToChinese() {
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: nil), .zh)
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: ""), .zh)
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: "   "), .zh)
        XCTAssertEqual(PersonaLanguagePrompt.fallback, .zh)
    }

    func testTagParsingRecognizesCommonForms() {
        // BCP-47 及其变体
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: "en"), .en)
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: "en-US"), .en)
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: "EN"), .en)
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: " en_us "), .en)
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: "zh"), .zh)
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: "zh-CN"), .zh)
        // 母语名 / 英文名（人格设置里可能存的是显示名）
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: "中文"), .zh)
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: "English"), .en)
    }

    func testUnknownTagFallsBackToChinese() {
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: "klingon"), .zh)
        XCTAssertEqual(PersonaLanguagePrompt.language(forTag: "xx-YY"), .zh)
    }

    func testLanguageForPersonaReadsLanguageTag() {
        XCTAssertEqual(PersonaLanguagePrompt.language(for: persona(nil)), .zh)
        XCTAssertEqual(PersonaLanguagePrompt.language(for: persona("")), .zh)
        XCTAssertEqual(PersonaLanguagePrompt.language(for: persona("en-US")), .en)
        XCTAssertEqual(PersonaLanguagePrompt.language(for: persona("中文")), .zh)
        // 认得出但不在 zh/en 里的标签也照实解析（模板回落英文，但语言本身不丢）
        XCTAssertEqual(PersonaLanguagePrompt.language(for: persona("ja")), .ja)
    }

    // MARK: - 2. 语言段

    func testChineseLanguageSectionIsContractVerbatim() {
        let section = PersonaLanguagePrompt.languageSection(for: persona(nil))
        // 标题必须来自共享契约常量，不是这里另写的一份中文。
        XCTAssertTrue(section.hasPrefix(PromptContract.Section.LANGUAGE + "\n"))
        XCTAssertEqual(section, PromptContract.Section.LANGUAGE + "\n" + "用中文思考和说话。")
        XCTAssertTrue(section.contains("用中文思考和说话"))
        XCTAssertEqual(
            PersonaLanguagePrompt.languageSection(language: .zh),
            PersonaLanguagePrompt.languageSection(for: persona("zh-CN"))
        )
    }

    func testEnglishLanguageSectionSpeaksEnglish() {
        let section = PersonaLanguagePrompt.languageSection(for: persona("en"))
        XCTAssertEqual(section, "## Language\nThink and speak in English.")
        XCTAssertTrue(section.contains("Think and speak in English"))
        XCTAssertFalse(section.contains("## 语言"))
        XCTAssertFalse(section.contains("用中文"))
    }

    // MARK: - 3. 反 AI 味

    func testChineseAntiAIIsVerbatim() {
        let section = PersonaLanguagePrompt.antiAISection(language: .zh)
        XCTAssertEqual(section, AntiAIPrompt.text())
        XCTAssertTrue(section.hasPrefix("# 别像 AI"))
        XCTAssertTrue(section.contains("不要每句话都反问"))
    }

    func testEnglishAntiAIIsEnglishAndKeepsCoreRules() {
        let section = PersonaLanguagePrompt.antiAISection(language: .en)
        XCTAssertTrue(section.hasPrefix("# Don't sound like an AI"))
        XCTAssertTrue(section.contains("Don't end every sentence with a question"))
        XCTAssertTrue(section.contains("stay_silent"))
        // iOS 独有的「不要旁白」也必须有英文版（否则英文人格满屏舞台说明）。
        XCTAssertTrue(section.contains(PersonaLanguagePrompt.englishNoNarrationRule))
        XCTAssertFalse(section.contains("不要每句话都反问"))
        XCTAssertNotEqual(section, PersonaLanguagePrompt.antiAISection(language: .zh))
    }

    // MARK: - 4. 工具能力段

    func testChineseCapabilitiesIsVerbatim() {
        for webEnabled in [true, false] {
            XCTAssertEqual(
                PersonaLanguagePrompt.capabilitiesSection(webEnabled: webEnabled, language: .zh),
                ToolCatalog.capabilityPrompt(webEnabled: webEnabled)
            )
            XCTAssertEqual(
                ToolCatalog.capabilityPrompt(webEnabled: webEnabled, language: .zh),
                ToolCatalog.capabilityPrompt(webEnabled: webEnabled)
            )
        }
        XCTAssertTrue(
            PersonaLanguagePrompt.capabilitiesSection(webEnabled: true, language: .zh)
                .hasPrefix("# 你能做什么")
        )
    }

    func testBoundariesFollowLanguage() {
        // 中文逐字复用 `PromptBuilder.defaultBoundary`（规范文案，不许复制第二份）。
        XCTAssertEqual(PersonaLanguagePrompt.boundariesSection(language: .zh), PromptBuilder.defaultBoundary)
        let english = PersonaLanguagePrompt.boundariesSection(language: .en)
        XCTAssertTrue(english.hasPrefix("# Boundaries"))
        XCTAssertTrue(english.contains("Don't explain that you're an AI"))
        XCTAssertFalse(english.contains("第一人称"))
        XCTAssertNotEqual(english, PersonaLanguagePrompt.boundariesSection(language: .zh))
    }

    func testEnglishCapabilitiesCoversEveryTool() {
        let prompt = PersonaLanguagePrompt.capabilitiesSection(webEnabled: true, language: .en)
        XCTAssertTrue(prompt.hasPrefix("# What you can do"))
        XCTAssertFalse(prompt.contains("# 你能做什么"))

        // 表必须与工具集合一一对应：少一个工具、多一个不存在的键都算失败。
        let names = Set(ToolCatalog.all.map(\.name))
        XCTAssertEqual(ToolCatalog.englishDescriptions.count, ToolCatalog.all.count)
        XCTAssertEqual(Set(ToolCatalog.englishDescriptions.keys), names)

        // 中文段新增的「必须调用 remember / memorize」在英文段也必须有一句。
        XCTAssertTrue(prompt.contains(ToolCatalog.englishMemoryDiscipline))

        for spec in ToolCatalog.all {
            // 签名按 schema 推出（参数名不翻译），说明来自英文表。
            let signature = ToolCatalog.signature(spec)
            XCTAssertTrue(prompt.contains("- " + signature + ":"), "英文能力段缺这个工具：\(signature)")
            XCTAssertTrue(prompt.contains(spec.name), "英文能力段找不到工具名：\(spec.name)")
        }
    }

    func testEnglishDescriptionsAreShortAndTranslated() {
        for spec in ToolCatalog.all {
            let description = ToolCatalog.englishDescription(for: spec)
            XCTAssertFalse(description.isEmpty, "\(spec.name) 的英文说明是空的")
            XCTAssertLessThanOrEqual(description.count, 140, "\(spec.name) 的英文说明太长（≤140 字符）")
            XCTAssertFalse(containsCJK(description), "\(spec.name) 的英文说明里混了中文：\(description)")
            XCTAssertNotEqual(description, spec.description, "\(spec.name) 没有英文说明，回退成了中文")
        }
    }

    func testEnglishCapabilitiesHidesWebToolsWhenOffline() {
        let offline = PersonaLanguagePrompt.capabilitiesSection(webEnabled: false, language: .en)
        XCTAssertTrue(offline.contains(ToolCatalog.englishOfflineNote))
        XCTAssertTrue(offline.contains("no internet access"))
        // 摘掉的是「工具行」；代码块示例里仍然可能出现 web_search 这个名字。
        XCTAssertFalse(offline.contains("- " + ToolCatalog.signature(ToolCatalog.byName(ToolCatalog.webSearch)!)))
        XCTAssertFalse(offline.contains("- " + ToolCatalog.signature(ToolCatalog.byName(ToolCatalog.webFetch)!)))
        // 离线说明与「副作用 / 代码块约定」也必须是英文（中文人格那段才用中文）。
        XCTAssertFalse(offline.contains("你现在没有联网能力"))
        XCTAssertTrue(offline.contains(ToolProtocol.blockLanguage))
    }

    // MARK: - 5. 心情

    func testChineseMoodSectionIsVerbatim() {
        let state = MoodState(
            label: "有点累", intensity: 3, reason: "加班到很晚", updatedAt: 1,
            dimensions: MoodDimensions(valence: -1, arousal: 1, dominance: 2)
        )
        XCTAssertEqual(PersonaLanguagePrompt.moodSection(state, language: .zh), MoodPrompt.line(state))
        XCTAssertTrue(PersonaLanguagePrompt.moodSection(state, language: .zh).contains("# 你的心情"))
    }

    func testEnglishMoodSectionIsEnglish() {
        let state = MoodState(
            label: "雀跃", intensity: 4, reason: "刚聊到想去的地方", updatedAt: 1,
            dimensions: MoodDimensions(valence: 2, arousal: 4, dominance: 3)
        )
        let section = PersonaLanguagePrompt.moodSection(state, language: .en)
        XCTAssertTrue(section.hasPrefix("\n\n# Your mood"), "心情段的前导空行要与中文同形")
        XCTAssertTrue(section.contains("Mood: 雀跃 (intensity 4/5)."))
        XCTAssertTrue(section.contains("Pleasantness 2 / Energy 4/5 / Control 3/5."))
        XCTAssertTrue(section.contains("Reason: 刚聊到想去的地方"))
        XCTAssertTrue(section.contains("Don't report the numbers"))
        XCTAssertFalse(section.contains("# 你的心情"))
        XCTAssertFalse(section.contains("愉快度"))
    }

    func testEnglishMoodHintsFollowDimensions() {
        // 很愉快 + 高唤醒：两句（愉快度一句 + 唤醒度一句），与中文同一套挑选规则。
        let excited = PersonaLanguagePrompt.moodSection(
            MoodState(label: "兴奋", intensity: 5, dimensions: MoodDimensions(valence: 2, arousal: 5, dominance: 3)),
            language: .en
        )
        XCTAssertTrue(excited.contains("Good mood"))
        XCTAssertTrue(excited.contains("wound up"))

        // 很不悦 + 低唤醒：同样两句。
        let low = PersonaLanguagePrompt.moodSection(
            MoodState(label: "低落", intensity: 4, dimensions: MoodDimensions(valence: -2, arousal: 0, dominance: 3)),
            language: .en
        )
        XCTAssertTrue(low.contains("upset"))
        XCTAssertTrue(low.contains("Low on energy"))

        // 基准心情：退化成一句「自然就好」，不硬凑。
        let calm = PersonaLanguagePrompt.moodSection(MoodState(label: "平静", intensity: 2), language: .en)
        XCTAssertTrue(calm.contains("Just talk naturally"))

        // 空心情也要有英文基准标签（不能把中文「平静」塞进英文提示词）。
        let empty = PersonaLanguagePrompt.moodSection(MoodState(), language: .en)
        XCTAssertTrue(empty.contains(PersonaLanguagePrompt.englishBaselineMoodLabel))
        XCTAssertFalse(empty.contains("平静"))
    }

    // MARK: - 6. 备忘录 / 心理活动

    func testMemoLeadMatchesExistingChineseMemoSentence() {
        let block = SessionMemoryPolicy.memoBlock(short: "最近在改方案", long: nil)
        let chineseLead = PersonaLanguagePrompt.memoLead(language: .zh)
        XCTAssertFalse(chineseLead.isEmpty)
        XCTAssertTrue(block.contains(chineseLead), "中文备忘录引导句必须与 SessionMemoryPolicy 那句逐字相同")

        let englishLead = PersonaLanguagePrompt.memoLead(language: .en)
        XCTAssertTrue(englishLead.hasPrefix("This is your own notebook"))
        XCTAssertEqual(PersonaLanguagePrompt.memoTitle(language: .zh), "# 你的备忘录")
        XCTAssertEqual(PersonaLanguagePrompt.memoTitle(language: .en), "# Your notebook")
    }

    func testMentalActivityLineMatchesExistingPolicy() {
        let now: Int64 = 1_700_000_000_000
        // 单行念头：与 `MentalActivityPolicy.promptLine` 去掉前导空行后**逐字相同**
        //（同前缀、同 60 字截断；生产路径上念头落库前已经过 `sanitizeThought`，都是单行）。
        let thought = "有点累"
        let policyLine = MentalActivityPolicy.promptLine(thought: thought, at: now, now: now)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let chinese = PersonaLanguagePrompt.mentalActivityLine(thought: thought, language: .zh)
        XCTAssertEqual(chinese, policyLine)
        XCTAssertTrue(chinese.hasPrefix("你刚才心里想："))

        // 多行/多余空白：这里走共享契约的 `sanitizeThought`（压成一行），与已有心理活动
        // 清洗（`MentalActivityPolicy.sanitizeThought`）同口径。
        let messy = "  有点  累\n了 "
        XCTAssertEqual(
            PersonaLanguagePrompt.mentalActivityLine(thought: messy, language: .zh),
            "你刚才心里想：" + (MentalActivityPolicy.sanitizeThought(messy) ?? "")
        )

        let english = PersonaLanguagePrompt.mentalActivityLine(thought: messy, language: .en)
        XCTAssertTrue(english.hasPrefix("You were just thinking: "))
        XCTAssertTrue(english.contains("有点 累 了"), "念头内容不做翻译，只换行前缀")

        XCTAssertEqual(PersonaLanguagePrompt.mentalActivityLine(thought: "   ", language: .zh), "")
        XCTAssertEqual(PersonaLanguagePrompt.mentalActivityLine(thought: nil, language: .en), "")
        // 念头统一截到 60 字（与 `PromptContract.MentalActivity` 一致）。
        let long = String(repeating: "想", count: 100)
        XCTAssertEqual(
            PersonaLanguagePrompt.mentalActivityLine(thought: long, language: .zh).count,
            "你刚才心里想：".count + 60
        )
    }

    // MARK: - 7. 只吃人格标签（与页面语言无关）

    func testOutputDependsOnlyOnPersonaLanguageTag() {
        // 只有名字 / 介绍 / 提示词不同、语言标签相同：所有段落必须逐字相同。
        // 这些函数没有 `Locale` / `Bundle` 参数，所以「换界面语言」根本进不来。
        let a = PersonaEntity(id: "a", name: "林深", description: "温柔", prompt: "你叫林深。", languageTag: "en")
        let b = PersonaEntity(id: "b", name: "Ada", description: "", prompt: "", languageTag: "en")
        XCTAssertEqual(PersonaLanguagePrompt.languageSection(for: a), PersonaLanguagePrompt.languageSection(for: b))
        XCTAssertEqual(
            PersonaLanguagePrompt.capabilitiesSection(webEnabled: true, language: PersonaLanguagePrompt.language(for: a)),
            PersonaLanguagePrompt.capabilitiesSection(webEnabled: true, language: PersonaLanguagePrompt.language(for: b))
        )
        // 纯函数：同样入参调两次，结果一样（没有隐式时钟 / 随机 / 资源依赖）。
        XCTAssertEqual(
            PersonaLanguagePrompt.antiAISection(language: PersonaLanguagePrompt.language(for: a)),
            PersonaLanguagePrompt.antiAISection(language: PersonaLanguagePrompt.language(for: a))
        )
        // 换标签才换语言：同一个人的两版人格，输出必须不同。
        let chinese = PersonaEntity(id: "a", name: "林深", prompt: "你叫林深。", languageTag: "zh")
        XCTAssertNotEqual(
            PersonaLanguagePrompt.languageSection(for: a),
            PersonaLanguagePrompt.languageSection(for: chinese)
        )
    }
}
