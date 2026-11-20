import Foundation

// ---------------------------------------------------------------------------
// 0.15「人格语言」：提示词的标题 / 指令 / 工具说明按**人格**的语言走，与页面语言无关。
//
// 背景（`docs/design/context-memory-v2.md`）：0.15 之前提示词跟着 App 的界面语言走
// （`AppLanguage.languageDirective` + `prompt_language_directive` 资源字符串）。
// 界面切成英文，中文人格也开始用英文思考；用户只想换一个界面，却把聊天对象换掉了。
// 0.15 把语言变成**人格属性**（`personas.languageTag`，schema v19，空 = 默认中文）。
//
// 本类型只做「把语言标签变成几段提示词文本」这件事，**全是纯函数**：
// 不读 `Locale`、不读 `Bundle`、不读资源、不读时钟。所以：
// - 同一份人格在任何设备、任何界面语言下产出的提示词逐字相同（测试钉住了这一点）；
// - 页面语言只影响 App 自己的 UI 文案（`Localizable.strings`），不进入这里。
//
// 中文侧**逐字复用**现有实现，绝不复制第二份：
// 语言标题取 `PromptContract.Section.LANGUAGE`，反 AI 取 `AntiAIPrompt.text()`，
// 能力段取 `ToolCatalog.capabilityPrompt(webEnabled:)`，心情取 `MoodPrompt.line(_:)`，
// 备忘录引导句与心理活动行取 `PromptTemplates`（Kotlin 真源的 iOS 镜像）。
// 英文侧新增的只有「英文标题 / 英文行为提示 / 英文三维行」这一点点模板。
// ---------------------------------------------------------------------------

/// 人格提示词的语言（0.15）。唯一的入口：人格上的 `languageTag` → 几段现成的提示词文本。
///
/// 命名刻意与 Kotlin 的 `PersonaLanguagePrompt`（如已存在）对齐：纯静态、无实例状态。
public enum PersonaLanguagePrompt {

    // MARK: - 语言解析

    /// 标签认不出时的回落语言：中文是产品的默认人格语言（与 `PromptLanguage.resolve` 的默认一致）。
    public static let fallback: PromptLanguage = .zh

    /// 「## 语言」的英文标题。
    ///
    /// 中文标题只有 `PromptContract.Section.LANGUAGE` 一份（这里不抄）；英文标题落在
    /// `PromptTemplates` 的六个核心段之外（语言段不在那六段里），所以由本类型持有唯一写法。
    public static let englishLanguageTitle = "## Language"

    /// 按人格上的标签解析语言；空 / 认不出 → `.zh`。
    ///
    /// 容忍 `zh-CN` / `en_US` / `中文` / `English` 这类写法（规则在 `PromptLanguage.parse`）。
    public static func language(forTag tag: String?) -> PromptLanguage {
        PromptLanguage.resolve(tag, fallback: fallback)
    }

    /// 这个人格说哪种语言（唯一真源：`PersonaEntity.languageTag`）。
    public static func language(for persona: PersonaEntity) -> PromptLanguage {
        language(forTag: persona.languageTag)
    }

    // MARK: - 语言段

    /// 「## 语言」整段：标题 + 一句写给模型的语言指令。
    ///
    /// - 中文：`## 语言` + `用中文思考和说话。`
    /// - 非中文：`## Language` + `Think and speak in English.`（其余语言用 `englishName`）
    ///
    /// 形状刻意与旧的 `AppLanguage.languageDirective` 一致（两行、无前导空行）：
    /// `PromptBuilder.languageSection` 负责把它拼到 system 末尾，这里只管内容。
    public static func languageSection(language: PromptLanguage) -> String {
        let title = language == .zh ? PromptContract.Section.LANGUAGE : englishLanguageTitle
        return title + "\n" + language.directive()
    }

    /// 同上，按人格取语言。
    public static func languageSection(for persona: PersonaEntity) -> String {
        languageSection(language: language(for: persona))
    }

    // MARK: - 反 AI 味

    /// 英文的「不要旁白 / 舞台说明」一条（对应 iOS 独有 的 `AntiAIPrompt.noNarrationRule`）。
    ///
    /// 为什么不在 `PromptTemplates` 里：`PromptTemplates` 是 Kotlin 真源的逐字镜像
    /// （`SharedContractsParityTests` 逐条比对），而「禁止旁白」这一条是 iOS 这一轮补的
    /// 硬约束，Android 的共享契约里还没有。等两端对齐之后再收进共享契约；在那之前
    /// 中文侧走 `AntiAIPrompt.text()`（它自己带这一条），英文侧由这里补上语义相同的一句，
    /// 免得「中文人格不旁白、英文人格满屏旁白」。
    public static let englishNoNarrationRule =
        "Don't write actions, narration or stage directions (\"a pause\", \"he glances at you\"); "
        + "no moods or gestures in parentheses — only the words he would actually say."

    /// 「# 别像 AI」段。
    ///
    /// - 中文：逐字复用 `AntiAIPrompt.text()`（与 Android `SECTION_ANTI_AI` 同一份文案，
    ///   含 iOS 独有的「不要旁白」那条）；
    /// - 非中文：`PromptTemplates.render(.antiAi, .en)`（英文七条，与中文一一对应）
    ///   + 一条英文的「不要旁白」（对应 `AntiAIPrompt.noNarrationRule`）。
    public static func antiAISection(language: PromptLanguage) -> String {
        guard language != .zh else { return AntiAIPrompt.text() }
        return PromptTemplates.render(section: .antiAi, language: language)
            + "\n- " + englishNoNarrationRule
    }

    // MARK: - 边界

    /// 「# 边界」段。
    ///
    /// - 中文：逐字复用 `PromptBuilder.defaultBoundary`（`PersonaPromptScaffold` 的规范文案，
    ///   所有端一致）；
    /// - 非中文：`PromptTemplates.render(.boundaries, .en)`（英文四条，与中文一一对应）。
    ///
    /// 为什么这个入口也要给：边界是**规则**，一个英文人格读到中文边界只会把它当「设定」
    /// 而不是硬约束；`SystemPromptInput.boundaryBlock` 是字符串入参，调用方直接接上即可。
    public static func boundariesSection(language: PromptLanguage) -> String {
        language == .zh
            ? PromptBuilder.defaultBoundary
            : PromptTemplates.render(section: .boundaries, language: language)
    }

    // MARK: - 工具能力

    /// 「# 你能做什么」段。
    ///
    /// - 中文：逐字复用 `ToolCatalog.capabilityPrompt(webEnabled:)`（含副作用行、代码块示例）；
    /// - 非中文：`ToolCatalog.capabilityPrompt(webEnabled:language:)`（英文工具说明，见
    ///   `ToolCatalogLanguage.swift`）。
    public static func capabilitiesSection(webEnabled: Bool, language: PromptLanguage) -> String {
        ToolCatalog.capabilityPrompt(webEnabled: webEnabled, language: language)
    }

    // MARK: - 心情

    /// 英文里「没有心情」时的基准标签。
    ///
    /// 中文侧直接用 `MoodLexicon.BASELINE`（「平静」）；英文人格读到「平静」会不协调，
    /// 所以空心情时给一个英文词。**有**心情时标签原样透传（那是数据，不翻译）。
    public static let englishBaselineMoodLabel = "calm"

    /// 「# 你的心情」/「# Your mood」整段（**带前导空行**，与 `MoodPrompt.line` 同形）。
    ///
    /// - 中文：逐字复用 `MoodPrompt.line(state)`（标题 + 心情行 + 三维 + 行为提示 + 原因 + 不报数值）；
    /// - 非中文：`# Your mood` + `Mood: … (intensity n/5).` + 三维一行 + 1–2 句英文行为提示
    ///   + 可选的 `Reason:` + `Don't report the numbers…`。
    ///
    /// 行为提示的挑选规则与中文 `MoodPrompt.behaviorHints` 同构（先愉快度，再从唤醒度与
    /// 掌控感里挑离基准更远的那一维），最多两句：心情段是「怎么说」的提示，不是参数表。
    public static func moodSection(_ state: MoodState, language: PromptLanguage) -> String {
        guard language != .zh else { return MoodPrompt.line(state) }

        let dimensions = state.isEmpty() ? MoodDimensions.baseline : state.resolvedDimensions
        let label = state.isEmpty() ? englishBaselineMoodLabel : state.label
        let intensity = state.isEmpty() ? 1 : min(max(state.intensity, 1), 5)

        var lines: [String] = [PromptTemplates.title(section: .mood, language: language)]
        lines.append(PromptTemplates.moodLine(language: language, label: label, intensity: intensity))
        lines.append(englishDimensionsLine(dimensions))
        lines.append("- How this shows up when you speak: " + englishBehaviorHints(dimensions))
        let reason = state.reason.trimmingCharacters(in: .whitespacesAndNewlines)
        if !reason.isEmpty {
            // 与中文 `原因：…` 同一行文（只给模型自己看，不上屏）。
            lines.append("Reason: \(reason)")
        }
        // 最后一句「别报数值」直接取镜像里的英文原文，不在这里再抄一遍。
        if let closing = PromptTemplates.body(section: .mood, language: language).last,
           !closing.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            lines.append("- " + closing)
        }
        return "\n\n" + lines.joined(separator: "\n")
    }

    /// 英文的「愉快度 / 唤醒度 / 掌控感」一行（与 `MoodPrompt.dimensionLine` 对应）。
    ///
    /// 数值本身原样输出：三维是给模型自己看的状态，不是给人看的文案。
    public static func englishDimensionsLine(_ dimensions: MoodDimensions) -> String {
        "Pleasantness \(dimensions.valence) / Energy \(dimensions.arousal)/5 / Control \(dimensions.dominance)/5."
    }

    /// 英文的 1–2 句行为提示（规则与 `MoodPrompt.behaviorHints` 一一对应）。
    public static func englishBehaviorHints(_ dimensions: MoodDimensions) -> String {
        var hints: [String] = []
        if dimensions.valence <= -2 {
            hints.append("You're pretty upset: say less and shorter, but don't play the victim or keep explaining how you feel.")
        } else if dimensions.valence <= -1 {
            hints.append("A little displeased: keep your tone flat, don't pretend everything is fine, and don't start new topics.")
        } else if dimensions.valence >= 2 {
            hints.append("Good mood: lighter tone, you can talk a bit more, but don't be cheesy or over the top.")
        } else if dimensions.valence >= 1 {
            hints.append("Mood's fine: relaxed tone, no need to be stiff.")
        }

        let arousalGap = abs(dimensions.arousal - MoodDimensions.baseline.arousal)
        let dominanceGap = abs(dimensions.dominance - MoodDimensions.baseline.dominance)
        if arousalGap >= dominanceGap {
            if dimensions.arousal <= 1 {
                hints.append("Low on energy: reply shorter and slower; one short sentence is enough.")
            } else if dimensions.arousal >= 4 {
                hints.append("A bit wound up: you'll want to talk a lot — hold back, don't dump a wall of text.")
            }
        } else {
            if dimensions.dominance <= 1 {
                hints.append("Not feeling sure of yourself: don't decide for them, listen more, lean on \"mm\" and \"okay\".")
            } else if dimensions.dominance >= 4 {
                hints.append("More sure of yourself: don't beat around the bush or keep double-checking them.")
            }
        }
        if hints.isEmpty {
            hints.append("Just talk naturally; no need to perform your mood.")
        }
        // 中文用 `joined(separator: "")`（中文不加空格），英文两句之间要一个空格。
        return hints.prefix(2).joined(separator: " ")
    }

    // MARK: - 备忘录

    /// 中文备忘录引导句。
    ///
    /// 它是 `SessionMemoryPolicy.memoBlock` 里实际注入的那一句的**唯一副本**（测试拿
    /// `memoBlock` 的真实输出逐字比对，两处一漂就红）。0.15 不改中文提示词，所以这里跟
    /// **实际生效**的那句走：共享契约镜像（`PromptTemplates`）写的是「这是你自己记下的**摘要**…」，
    /// 与这里差「对话」两个字——等两端把这句收敛成一份之后，这里应当改成读共享契约。
    public static let chineseMemoLead =
        "这是你自己记下的对话摘要（不是人设，会随时间更新）；回答时自然地用，不要逐条复述。"

    /// 备忘录段前的引导句（「这是你自己记下的对话摘要…」/「This is your own notebook…」）。
    ///
    /// 只给句子，不含标题与前导空行：标题（`# 你的备忘录` / `# Your notebook`，
    /// 见 `memoTitle(language:)`）与拼装顺序属于调用方。
    public static func memoLead(language: PromptLanguage) -> String {
        language == .zh
            ? chineseMemoLead
            : (PromptTemplates.body(section: .memo, language: language).first ?? "")
    }

    /// 备忘录段的标题。
    ///
    /// 中文取 `PromptContract.Section.MEMO`（= `MemoryContract.Injection.TITLE`），
    /// 英文取镜像的 `# Your notebook`；调用方拼 `标题 + "\n" + memoLead` 即可。
    public static func memoTitle(language: PromptLanguage) -> String {
        PromptTemplates.title(section: .memo, language: language)
    }

    // MARK: - 心理活动

    /// 心理活动那一行（「你刚才心里想：…」/「You were just thinking: …」）。
    ///
    /// 中文与 `MentalActivityPolicy.promptLine` 的行前缀、念头清洗（压成一行、≤60 字）逐字等价，
    /// 但**不带**前导空行与「新鲜度」判定：注入时机（30 分钟内）是调用方的事；
    /// 念头为空时返回空串（整段不出现）。
    public static func mentalActivityLine(thought: String?, language: PromptLanguage) -> String {
        PromptTemplates.mentalActivityLine(language: language, thought: thought)
    }
}
