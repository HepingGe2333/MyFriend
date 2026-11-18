import XCTest
@testable import YapKit

/// 「什么都可以不填」（0.13 真机反馈第一批）：预设/人格编辑不再强制填满。
///
/// 这一组钉两件事：
/// 1. 空字段的兜底是**确定的**（名字 → 未命名、提示词 → 名字 + 介绍 → 系统默认人设）；
/// 2. 兜底在**渲染那一刻**也生效（备份接回来的旧人格、空人格也必须有 `# 你是谁`）。
final class PersonaDraftTests: XCTestCase {

    // MARK: - 名字

    func testEmptyNameFallsBackToLocalizedDefault() {
        XCTAssertEqual("未命名", PersonaDraft.resolvedName("", fallback: "未命名"))
        XCTAssertEqual("未命名", PersonaDraft.resolvedName("   ", fallback: "未命名"))
        XCTAssertEqual("Untitled", PersonaDraft.resolvedName("\n", fallback: "Untitled"))
        // 界面没传本地化名字时，Kit 自己的兜底也要非空。
        XCTAssertEqual(PersonaDraft.fallbackName, PersonaDraft.resolvedName("", fallback: ""))
    }

    func testFilledNameIsOnlyTrimmed() {
        XCTAssertEqual("林深", PersonaDraft.resolvedName("  林深 ", fallback: "未命名"))
    }

    // MARK: - 提示词

    func testPromptGeneratedFromNameAndDescription() {
        let prompt = PersonaDraft.resolvedPrompt(
            "", name: "林深", description: "一个嘴硬心软的建筑师"
        )
        XCTAssertEqual("你叫林深。一个嘴硬心软的建筑师", prompt)
    }

    func testPromptGeneratedFromNameOnly() {
        let prompt = PersonaDraft.resolvedPrompt("", name: "林深", description: " ")
        XCTAssertTrue(prompt.hasPrefix("你叫林深。"))
        XCTAssertTrue(prompt.contains("自己的性子"))
    }

    func testPromptGeneratedFromDescriptionOnly() {
        let prompt = PersonaDraft.resolvedPrompt("", name: "", description: "一个安静的人")
        XCTAssertTrue(prompt.contains("一个安静的人"))
        XCTAssertFalse(prompt.contains("你叫"), "没有名字时不该编一个名字")
    }

    func testPromptFallsBackToSystemDefaultWhenEverythingEmpty() {
        XCTAssertEqual(
            PersonaDraft.systemDefaultPrompt,
            PersonaDraft.resolvedPrompt("", name: "", description: "")
        )
        XCTAssertFalse(PersonaDraft.systemDefaultPrompt.isEmpty)
        // 默认人设不能被写成「助手」（`# 边界` 明令不暴露 AI 身份）。
        XCTAssertFalse(PersonaDraft.systemDefaultPrompt.contains("助手，"))
    }

    func testFilledPromptIsKeptVerbatim() {
        // 原文保留（人设哈希/版本号都拿原文比对，顺手 trim 会让两端算出不同哈希）。
        let raw = "  你叫林深。\n说话很短。  "
        XCTAssertEqual(raw, PersonaDraft.resolvedPrompt(raw, name: "别的名字", description: "别的介绍"))
    }

    // MARK: - 渲染兜底

    func testPersonaSectionIsNeverEmpty() {
        let empty = PersonaEntity(id: "p", name: "", description: "", prompt: "")
        let section = PromptBuilder.personaSection(empty)
        XCTAssertTrue(section.hasPrefix("# 你是谁"))
        XCTAssertTrue(section.contains(PersonaDraft.systemDefaultPrompt))
        XCTAssertFalse(PromptBuilder.personaProfileBody(empty).isEmpty)
    }

    func testNameOnlyPersonaStillHasIdentity() {
        let persona = PersonaEntity(id: "p", name: "林深", description: "", prompt: "")
        let body = PromptBuilder.personaProfileBody(persona)
        XCTAssertTrue(body.contains("林深"))
        let system = PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: PromptBuilder.personaSection(persona),
            personaName: persona.name
        ))
        XCTAssertTrue(system.contains("# 你是谁"))
        XCTAssertTrue(system.contains("林深"))
    }

    /// 世界 / 话风 / 作息 / 活动全空时，提示词照常能拼出来（空节整节不出现，不留空标题）。
    func testAllOptionalGuidanceFieldsCanBeEmpty() {
        let persona = PersonaEntity(
            id: "p", name: "林深", description: "一个安静的人", prompt: "",
            quietSleepMinutes: nil, quietWakeMinutes: nil, sleepEnabled: false,
            worldJson: nil, speechStyleJson: nil, activityJson: nil
        )
        XCTAssertNil(WorldPreset.decode(persona.worldJson))
        XCTAssertNil(SpeechStyle.decode(persona.speechStyleJson))
        XCTAssertNil(PersonaActivity.decode(persona.activityJson, now: 1_800_000_000_000))
        let system = PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: PromptBuilder.personaSection(persona),
            personaName: persona.name,
            worldBlock: PromptBuilder.worldSection(WorldPreset.decode(persona.worldJson)),
            speechStyleBlock: PromptBuilder.speechStyleSection(SpeechStyle.decode(persona.speechStyleJson)),
            relationshipBlock: PromptBuilder.relationshipSection(persona: persona)
        ))
        XCTAssertTrue(system.contains("# 你所在的世界"))
        XCTAssertTrue(system.contains("你没有特别的世界设定"), "空世界要给一句「没有设定」而不是留空")
        XCTAssertTrue(system.contains("# 你们的关系"))
        XCTAssertFalse(system.contains("# 你怎么说话"), "没学过话风就不该出现空的话风节")
    }
}
