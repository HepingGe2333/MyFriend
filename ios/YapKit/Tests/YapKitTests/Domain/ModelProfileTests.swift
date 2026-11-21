import XCTest
@testable import YapKit

/// 0.15「模型优化」开关的纯逻辑（`ModelProfile` / `ModelProfilePrompt`）。
///
/// 钉四件事：
/// 1. 默认值：没填 / 认不出 → `AUTO`（老库升级后就是这一档）；
/// 2. 显式选择原样保留（DeepSeek / GLM 不会被模型名改掉）；
/// 3. `AUTO` 按当前模型名推断（含 `glm` → GLM，其余 → DeepSeek，大小写不敏感）；
/// 4. 只有 DeepSeek 档注入「# 工具使用」；GLM 档一个字都不加（lean）。
final class ModelProfileTests: XCTestCase {

    // MARK: - 解析 / 默认值

    func testMissingOrUnknownValueFallsBackToAuto() {
        XCTAssertEqual(ModelProfile(stored: nil), .auto)
        XCTAssertEqual(ModelProfile(stored: ""), .auto)
        XCTAssertEqual(ModelProfile(stored: "   "), .auto)
        XCTAssertEqual(ModelProfile(stored: "SOMETHING_NEW"), .auto, "未来版本的新档位 → 自动")
        XCTAssertEqual(ModelProfile.fallback, .auto)
    }

    func testStoredValuesAreCanonical() {
        XCTAssertEqual(ModelProfile(stored: "auto"), .auto)
        XCTAssertEqual(ModelProfile(stored: " deepseek "), .deepseek)
        XCTAssertEqual(ModelProfile(stored: "GlM"), .glm)
        XCTAssertEqual(ModelProfile.auto.stored, "AUTO")
        XCTAssertEqual(ModelProfile.deepseek.stored, "DEEPSEEK")
        XCTAssertEqual(ModelProfile.glm.stored, "GLM")
    }

    // MARK: - AUTO 推断

    func testAutoInfersGLMFromModelNameCaseInsensitively() {
        for model in ["glm-4", "GLM-4-Flash", "zhipu/glm-4.6", "campus-glm"] {
            XCTAssertEqual(ModelProfile.inferred(fromModel: model), .glm, "应当是 GLM：\(model)")
        }
        for model in ["deepseek-chat", "campus-chat", "gpt-4o", ""] {
            XCTAssertEqual(ModelProfile.inferred(fromModel: model), .deepseek,
                           "应当是 DeepSeek 档：\(model)")
        }
        XCTAssertEqual(ModelProfile.inferred(fromModel: "glmish"),
                       .glm, "名字里出现 glm 就算（推断刻意简单：多给规则比少给安全）")
    }

    func testEffectiveKeepsExplicitChoiceAndInfersOnlyForAuto() {
        XCTAssertEqual(ModelProfile.effective(.auto, model: "glm-4"), .glm)
        XCTAssertEqual(ModelProfile.effective(.auto, model: "deepseek-chat"), .deepseek)
        XCTAssertEqual(ModelProfile.effective(.deepseek, model: "glm-4"), .deepseek,
                       "显式选择优先于模型名")
        XCTAssertEqual(ModelProfile.effective(.glm, model: "deepseek-chat"), .glm)
    }

    func testOnlyDeepSeekInjectsToolUsagePrompt() {
        XCTAssertTrue(ModelProfile.deepseek.injectsToolUsagePrompt)
        XCTAssertFalse(ModelProfile.glm.injectsToolUsagePrompt)
        XCTAssertFalse(ModelProfile.auto.injectsToolUsagePrompt, "AUTO 要先解析才知道，不能直接注入")
    }

    // MARK: - 提示词块

    func testDeepSeekBlockMentionsTheThreeToolRules() {
        let block = ModelProfilePrompt.toolsSection(
            profile: .deepseek, modelName: "deepseek-chat", language: .zh
        )
        XCTAssertTrue(block.hasPrefix(ModelProfilePrompt.section))
        XCTAssertTrue(block.contains("set_activity"))
        XCTAssertTrue(block.contains("memorize"))
        XCTAssertTrue(block.contains("web_search"))
        XCTAssertTrue(block.contains("remember"))
        XCTAssertTrue(block.contains("不要提工具名"), "工具结果只是素材（与 Android 同一句）")
    }

    func testGLMBlockIsEmpty() {
        XCTAssertEqual(ModelProfilePrompt.toolsSection(profile: .glm, modelName: "deepseek-chat", language: .zh), "")
        XCTAssertEqual(ModelProfilePrompt.toolsSection(profile: .glm, modelName: "deepseek-chat", language: .en), "")
    }

    func testEnglishBlockUsesEnglishTitle() {
        let block = ModelProfilePrompt.toolsSection(profile: .deepseek, modelName: "deepseek-chat", language: .en)
        XCTAssertTrue(block.hasPrefix(ModelProfilePrompt.sectionEN))
        XCTAssertFalse(block.contains(ModelProfilePrompt.section))
    }

    func testToolsSectionResolvesAutoAndHonorsWebFlag() {
        XCTAssertEqual(
            ModelProfilePrompt.toolsSection(profile: nil, modelName: "glm-4", language: .zh),
            "",
            "没填 = AUTO；模型名是 glm → lean"
        )
        XCTAssertTrue(
            ModelProfilePrompt.toolsSection(profile: ModelProfile(stored: "DEEPSEEK"),
                                            modelName: "glm-4", language: .zh)
                .hasPrefix(ModelProfilePrompt.section)
        )
        XCTAssertTrue(
            ModelProfilePrompt.toolsSection(profile: .auto, modelName: "deepseek-chat", language: .zh)
                .hasPrefix(ModelProfilePrompt.section)
        )
        // 没联网：不提 web_search，改成「没有联网能力」。
        let offline = ModelProfilePrompt.toolsSection(
            profile: .deepseek, modelName: "deepseek-chat", webEnabled: false, language: .zh
        )
        XCTAssertFalse(offline.contains("web_search"))
        XCTAssertTrue(offline.contains("没有联网能力"))
        XCTAssertTrue(offline.contains("set_activity"), "该有的规则仍然在")
    }

    // MARK: - 拼进 system 的顺序

    func testPromptBuilderPlacesToolUsageInsideToolsSection() throws {
        let input = SystemPromptInput(
            personaText: "你叫林深。",
            personaName: "林深",
            toolsCapability: "## 工具\n- set_activity",
            toolUsageBlock: ModelProfilePrompt.toolsSection(profile: .deepseek, modelName: "deepseek-chat", language: .zh)
        )
        let system = PromptBuilder.systemPrompt(input)
        let capability = try XCTUnwrap(system.range(of: "## 工具"))
        let toolUsage = try XCTUnwrap(system.range(of: ModelProfilePrompt.section))
        XCTAssertLessThan(capability.lowerBound, toolUsage.lowerBound)
        // 不注入时整段不出现（GLM 的 lean 版）。
        let lean = PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: "你叫林深。",
            personaName: "林深",
            toolsCapability: "## 工具\n- set_activity",
            toolUsageBlock: ""
        ))
        XCTAssertFalse(lean.contains(ModelProfilePrompt.section))
    }

    // MARK: - 预设 → 人格

    func testPresetApplierCopiesModelProfile() {
        let now: Int64 = 1_794_500_000_000
        let preset = PersonaPresetEntity(id: "preset-1", name: "底稿", prompt: "你叫临岸。",
                                         modelProfile: .glm)
        let existing = PersonaEntity(id: "p1", name: "旧名字", prompt: "旧提示词")
        let applied = PersonaPresetApplier.applying(preset, to: existing, now: now)
        XCTAssertEqual(applied.modelProfile, .glm, "应用预设要把模型优化档位一起带过去")
        XCTAssertEqual(applied.updatedAt, now)

        let fresh = PersonaPresetApplier.newPersona(from: preset, now: now)
        XCTAssertEqual(fresh.modelProfile, .glm, "复制成新人格也要带过去")
    }
}
