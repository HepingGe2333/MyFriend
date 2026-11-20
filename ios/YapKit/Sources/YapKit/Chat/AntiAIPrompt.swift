import Foundation

// ---------------------------------------------------------------------------
// 反「AI 味」约束（`spec/mood/README.md` §5，提示词层）。
//
// 真机上的 AI 味长什么样：每句都在反问、总结对方说过的话、动不动列三点、
// 一上来就安慰、每段开头都是同一个口头禅、对方只是随口一句却回了一大段。
//
// 这些不是「语气偏好」，是**可数的坏习惯**，所以写成可以对着数的几条「不要」。
// 为什么单独一个文件：它是所有端逐字一致的规则（和 `# 边界` 同一性质），
// 散落在调用点就会某一端少一条；`PromptBuilder` 只负责把它拼在 `# 边界` 之前。
//
// **0.15 起只保留 core 五条**（`spec/context-profile/README.md`，Bench v2 搜索结论
// `anti=core`）：不反问 / 不总结 / 不列点 / 不总安慰 / 不旁白。
// 另外三条（短句与口头禅、不主动给建议、stay_silent 的用法）在评测里互相重复、
// 且「短句」「不列点」已经覆盖了它们的行为，删掉之后提示词更短、综合分更高
//（`eval/reports/context-search-axes-*.md`：core 87.8 vs full 86.5，硬失败率 16.7% vs 25.0%）。
//
// **两端逐字一致**：文案与 Android `PromptBuilder.ANTI_AI_RULES` / 共享契约
// `PromptContract.AntiAi.RULES` 相同（那一份不在 `scaffold.json` 里，所以由两边各自钉住；
// 改动要两端一起改）。
// ---------------------------------------------------------------------------

public enum AntiAIPrompt {

    /// 段标题。刻意写得像一句人话（`# 别像 AI`），模型对它的服从度比
    /// 「Output constraints」这类机械标题高。
    public static let section = "# 别像 AI"

    /// core 的四条「不要」（规范 §5 的第 1–4 条）。
    ///
    /// 顺序按「最常犯 → 最隐蔽」：反问与总结是第一眼就能看出的 AI 味，
    /// 「每次都安慰」是聊久了才发现的。
    ///
    /// **逐字复用共享契约**（`PromptContract.AntiAi.RULES`，Kotlin 真源的镜像），
    /// 不再在这里抄第二份：反 AI 文案是两端逐字一致的规则，抄两份迟早会漂。
    ///
    /// 与 `# 边界` 的分工：边界是「不许暴露身份 / 不许旁白」，这里是「不许像客服」。
    public static let rules: [String] = PromptContract.AntiAi.RULES

    /// core 的第五条：**旁白 / 舞台说明**（评测里反复出现「沉默了一会儿」「他看着你」）。
    ///
    /// 为什么单独一条、不放 `rules` 里：`rules` 是规范 §5 的四条「不要」，与 Android 的
    /// `ANTI_AI_RULES` 逐字一致；旁白这一条是 0.15 补进 core 的硬约束，等 Android 的共享契约
    /// 也收进去之后再合并成一个数组。
    public static let noNarrationRule =
        "不要写动作、旁白或舞台说明（「沉默了一会儿」「他顿了顿」「他看着你」），"
        + "不要用括号写神态或心理描写；只说他会说出口的那句话。"

    /// core 五条（四条「不要」+ 旁白）。测试与跨端契约按这个数**对得上**：
    /// 提示词里必须正好是这五条，不多不少。
    public static var coreRules: [String] { rules + [noNarrationRule] }

    /// 拼好的整段（`# 别像 AI` + core 五条）。
    public static func text() -> String {
        ([section] + coreRules.map { "- " + $0 }).joined(separator: "\n")
    }

    /// 「这一轮没有别的规则」时的空实现（测试与特殊场景用）。
    public static let empty = ""
}
