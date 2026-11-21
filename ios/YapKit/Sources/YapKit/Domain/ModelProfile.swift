import Foundation

// ---------------------------------------------------------------------------
// 0.15「模型优化」开关（`personas.modelProfile` / `persona_presets.modelProfile`，schema v20）。
//
// 与 Android `domain/ModelProfile.kt` 同一套语义（0.15 两端一致，连提示词文案都逐字对齐）：
//
// 同一个产品要同时伺候两类模型：DeepSeek 系（campus-chat 也在这一类里）对「显式工具规则」
// 很敏感——把「什么时候该调 set_activity / remember / web_search」写成一段规则，它就会真的照做；
// GLM 系在同样的规则上收益不明显，反而会让提示词变长、更容易把规则念出来（lean 更好）。
//
// 所以这里不是「模型参数」，而是**提示词装配策略**：
// - `DEEPSEEK`：注入 `# 工具使用` 段；
// - `GLM`：不注入（lean），其余提示词一个字节都不变；
// - `AUTO`（默认）：按当前实际使用的 model 名推断（含 `glm` → GLM，否则 DEEPSEEK）。
//
// 为什么 AUTO 落到 DEEPSEEK 而不是「什么都不加」：完全不注入是 GLM 专属的优化，
// 而绝大多数配置是 DeepSeek / OpenAI 兼容端点；判不出来时选「规则更全」的那一档。
//
// 存储是 TEXT（默认 `'AUTO'`），认不出的值一律按 `AUTO` 处理（老库、手改数据、
// 以后新增的档位都能安全读）。这个文件是纯逻辑：不读库、不读网络、不读资源。
// ---------------------------------------------------------------------------

/// 「模型优化」档位（数据库存 `AUTO` / `DEEPSEEK` / `GLM`）。
public enum ModelProfile: String, CaseIterable, Sendable {
    /// 按当前模型名自动推断（默认值）。
    case auto = "AUTO"
    /// DeepSeek 系（campus-chat 也走这一档）：注入工具使用规则。
    case deepseek = "DEEPSEEK"
    /// GLM 系：精简提示词，不注入工具使用规则。
    case glm = "GLM"

    /// 没填 / 认不出的名字都回落 `AUTO`（而不是猜一个厂商）。
    public static let fallback: ModelProfile = .auto

    /// 列的 SQL 默认值，同时也是「没选过」的语义。
    public static let defaultStored = "AUTO"

    /// 宽容解析数据库里的字符串：大小写、前后空白都不计较，未知值 → `AUTO`。
    public init(stored: String?) {
        let key = (stored ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
        self = ModelProfile(rawValue: key) ?? .auto
    }

    /// 写库用规范值（`AUTO` / `DEEPSEEK` / `GLM`），永远不写空串。
    public var stored: String { rawValue }

    /// 按模型名推断厂商档位：名字里含 `glm`（不区分大小写）→ GLM，否则 DeepSeek。
    ///
    /// 只认 `glm`：`glm-4.5` / `GLM-4-Plus` / `zhipu/glm-4` 都算；
    /// `gpt-4o` / `deepseek-chat` / `campus-chat` / 空串都落到 DeepSeek。
    public static func inferred(fromModel model: String?) -> ModelProfile {
        (model ?? "").lowercased().contains("glm") ? .glm : .deepseek
    }

    /// 这一轮实际生效的档位（永远不是 `AUTO`）。
    ///
    /// - Parameters:
    ///   - profile: 人格/预设上选的值；`nil` = 没选过（按 `AUTO`）。
    ///   - model: 这一轮真正要发出去的 model 名（`session.model` 优先，其次配置里的）。
    public static func effective(_ profile: ModelProfile?, model: String?) -> ModelProfile {
        let chosen = profile ?? .auto
        return chosen == .auto ? inferred(fromModel: model) : chosen
    }

    /// 这一档要不要注入 `# 工具使用`。GLM 走 lean（不注入），其余注入。
    public var injectsToolUsagePrompt: Bool { self == .deepseek }
}

/// `# 工具使用` 提示块：按 `ModelProfile` 决定注入不注入。
///
/// 逐字对齐 Android `domain/ModelProfile.kt::ModelProfilePrompt`（提示词差一个字，
/// 模型的调用率就会差一截；这类改动必须两端一起做）。
///
/// 与「# 你能做什么」的分工：`ToolCatalog.capabilityPrompt` 回答「你能做什么」（工具清单、
/// 参数、与其它工具的区别）；这里回答「什么时候该用、用的时候别犯什么错」。
/// 刻意只覆盖三个最容易「说了不做」的工具：`set_activity` / `remember` / `web_search`
///（外加 `memorize`：翻这段会话本身）。
public enum ModelProfilePrompt {

    /// 中文人格的标题。
    public static let section = "# 工具使用"
    /// 英文人格的标题。
    public static let sectionEN = "# Tool use"

    /// 按优化档生成这一段（含标题）；GLM / AUTO+glm 返回空串（lean：不注入）。
    ///
    /// - Parameters:
    ///   - profile: 人格/预设上的档位（可以是 `nil` = 没选过）。
    ///   - modelName: 只在 `profile` 是 `AUTO` 时用得上（推断模型家族）。
    ///   - webEnabled: 没联网能力时不提 `web_search`，改成一句「没有联网能力就说不确定」。
    ///   - language: 人格的提示词语言（`PersonaLanguagePrompt.language(for:)`）。
    public static func toolsSection(
        profile: ModelProfile?,
        modelName: String?,
        webEnabled: Bool = true,
        language: PromptLanguage = .zh
    ) -> String {
        guard ModelProfile.effective(profile, model: modelName) == .deepseek else { return "" }
        let title = language == .zh ? section : sectionEN
        return ([title] + rules(webEnabled: webEnabled, language: language).map { "- " + $0 })
            .joined(separator: "\n")
    }

    /// 四条固定规则 + 一条「工具结果只是素材」的收尾。
    ///
    /// 顺序是有意的：先「怎么表现自己的状态」（最容易被瞎编），再「记住事实」（最容易被
    /// 「记住了」糊弄过去），最后才是联网（最容易被当成万能搜索）。
    public static func rules(webEnabled: Bool, language: PromptLanguage = .zh) -> [String] {
        guard language == .zh else { return englishRules(webEnabled: webEnabled) }
        var lines: [String] = [
            "想让他知道你此刻在忙什么时，调用 set_activity 声明活动与结束时间；"
                + "不忙就传空，不要凭空编日程，也不要在正文里反复汇报自己在做什么。",
            "对方说出关于他自己的长期事实（生日、工作、家人、喜欢和讨厌的东西）时，调用 remember 记下来；"
                + "只在正文里说「我记住了」不算记过。",
            "想不起以前聊过的事、或者对方问「你还记得吗」时，调用 memorize 去翻记忆；"
                + "翻不到就老实说不记得，不要编一段过去。"
        ]
        if webEnabled {
            lines.append(
                "需要实时信息（天气、新闻、价格、赛程、某个人的近况）时调用 web_search；"
                    + "搜不到或结果对不上就说不知道，不要用记忆编造成事实。"
            )
        } else {
            lines.append("你现在没有联网能力：不确定的事直接说不知道，「回头再查」也比编一个答案强。")
        }
        lines.append(
            "工具结果是给你看的素材：用自己的话把结论说完，不要提工具名，"
                + "也不要把 JSON、代码块、参数原样念给对方。"
        )
        return lines
    }

    /// 英文规则（与中文五条一一对应；措辞逐字对齐 Android 的 `enRules`）。
    public static func englishRules(webEnabled: Bool) -> [String] {
        var lines: [String] = [
            "When you want them to know what you are busy with, call set_activity with the activity "
                + "and when it ends; pass nothing when you are free, and never invent a schedule.",
            "When they tell you a lasting fact about themselves (birthday, job, family, likes, dislikes), "
                + "call remember; saying \"I'll remember that\" in the reply does not count.",
            "When you cannot recall something from earlier, or they ask whether you remember, call memorize "
                + "to search your memories; if nothing comes back, say so instead of inventing a past."
        ]
        if webEnabled {
            lines.append(
                "For real-time facts (weather, news, prices, fixtures, what someone is up to) call web_search; "
                    + "if the results do not answer it, say you are not sure instead of making something up."
            )
        } else {
            lines.append("You have no web access right now: say you are not sure rather than guessing.")
        }
        lines.append(
            "Tool results are material for you: say the conclusion in your own words, "
                + "never mention tool names or read JSON or code blocks to them."
        )
        return lines
    }
}
