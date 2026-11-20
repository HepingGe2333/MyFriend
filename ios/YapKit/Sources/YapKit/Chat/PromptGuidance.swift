import Foundation

// ---------------------------------------------------------------------------
// 「# 引导」段（0.15 profile，`spec/context-profile/README.md`）。
//
// 一句话说清它是什么：**这一轮他站在什么位置说话**。不是人设（那是常年不变的），
// 也不是心情（那是感受），而是「这一轮的语气分寸」。评测里它单独值 ~8 分
//（无引导 77.7 → 有引导 86–88，`eval/reports/bench2-guidance-summary-*.md`）。
//
// 两个变体来自同一套模板（`eval/experiment.py` 的 `GUIDANCE_TEMPLATE`）：
//
// ```
// # 引导
// 这一轮：{stance}
// - {move1}
// - {move2}
// - 不要{avoid}。
// ```
//
// - **g12**（有点防备，不轻易示弱）：心情偏负（valence < 0）或低唤醒（arousal < 2）时；
// - **g17**（只聊此刻，不翻旧账）：其余情况，以及**心情还没判定**的时候。
//
// 为什么心情缺失用 g17：没有心情数据时不猜「他是不是不高兴」，选更中性、
// 更不容易把对话带偏的那一条；而且 g17 的「只聊此刻」本身就是安全的默认值。
//
// **心情判定本身不在这里改**：这里只读 `MoodState.resolvedDimensions`（已有判定结果，
// 或者由词表从老 label 推出来的那份），不重新判一次。
//
// 位置固定：**心情之后、话风之前**（`PromptBuilder.PromptSection.stateFirstOrder`）。
// 人在读完自己的感受之后紧接着读「所以这轮怎么说话」，比放在人设前面更像自省；
// 放到话风后面又会被当成又一条风格设定。
// ---------------------------------------------------------------------------

public enum PromptGuidance: String, CaseIterable, Sendable {

    /// 有点防备，不轻易示弱（心情偏负 / 低唤醒）。
    case defensive = "g12"

    /// 只聊此刻，不翻旧账（默认）。
    case presentOnly = "g17"

    /// 中文段标题（`spec/context-profile/README.md` §注入规则）。
    public static let sectionTitle = "# 引导"

    /// 英文段标题。
    ///
    /// 与 `PersonaLanguagePrompt.englishLanguageTitle` 同一思路：引导段不在
    /// `PromptTemplates` 的六个核心段里（那是 Kotlin 真源的逐字镜像，不在这里加），
    /// 所以英文写法由本类型持有唯一一份。
    public static let englishSectionTitle = "# Guidance"

    // MARK: - 文案

    /// 中文的四个变量（逐字来自 `eval/experiment.py` 的 `GUIDANCE_VARIANTS`）。
    public var chineseParts: (stance: String, move1: String, move2: String, avoid: String) {
        switch self {
        case .defensive:
            return ("有点防备，不轻易示弱", "用短句挡一下", "被问急了才多露半句", "掏心掏肺")
        case .presentOnly:
            return ("只聊此刻，不翻旧账", "回应现在这句话", "不延伸过去", "反问")
        }
    }

    /// 英文的四个变量（与中文一一对应，不是另一套意思）。
    public var englishParts: (stance: String, move1: String, move2: String, avoid: String) {
        switch self {
        case .defensive:
            return ("a little guarded — don't show weakness easily",
                    "deflect with short lines",
                    "only say half a sentence more if they push",
                    "pour your heart out")
        case .presentOnly:
            return ("only what's happening right now — don't bring up the past",
                    "answer what they just said",
                    "don't reach back into the past",
                    "ask a question back")
        }
    }

    /// 「这一轮：…」那一句（中文）。
    public var stance: String { chineseParts.stance }

    /// 整段（带 `# 引导` 标题，**不带**前导空行；拼装由 `PromptBuilder` 负责）。
    ///
    /// 只有这一个渲染入口（不再提供同名实例方法：两个同名重载会让
    /// `PromptGuidance.section(.defensive)` 在调用点变成歧义）。
    public static func section(_ guidance: PromptGuidance, language: PromptLanguage = .zh) -> String {
        if language == .zh {
            let parts = guidance.chineseParts
            return ([sectionTitle, "这一轮：\(parts.stance)",
                     "- \(parts.move1)", "- \(parts.move2)", "- 不要\(parts.avoid)。"])
                .joined(separator: "\n")
        }
        let parts = guidance.englishParts
        return ([englishSectionTitle, "This turn: \(parts.stance)",
                 "- \(parts.move1)", "- \(parts.move2)", "- Don't \(parts.avoid)."])
            .joined(separator: "\n")
    }

    // MARK: - 选择

    /// 「低唤醒」的门槛（`arousal < 2`）。单独留一个常量是因为它正好压在
    /// **基准心情的唤醒度**上（`MoodDimensions.baseline = 0 / 2 / 3`）：门槛改成
    /// `< 2` 或 `≤ 1` 只需要动这一行，不必在规则里找魔法数字。
    public static let lowArousalThreshold = 2

    /// 这一轮用哪一条引导（**唯一的选择规则**，两端共用同一条判据）。
    ///
    /// - 心情缺失（还没判定过 / 老数据）→ g17；
    /// - `valence < 0`（不悦）或 `arousal < lowArousalThreshold`（没劲、累、困）→ g12；
    /// - 其余（有精神的平静偏上、愉快）→ g17。
    ///
    /// **一个需要两端确认的细节**：基准心情（平静）的唤醒度正好等于门槛（2），
    /// 所以按字面规则「平静」也会走 g12。`spec/context-profile/README.md` 的表格写
    /// 「正向/平静时用 g17」，与 §注入规则的 `arousal < 2` 在这一点上不一致；
    /// 这里按 §注入规则的字面实现（门槛收在 `lowArousalThreshold` 一处，改 `<` 是一行）。
    ///
    /// 用 `resolvedDimensions` 而不是 `dimensions`：0.17 之前的老库里只有 label / intensity，
    /// 心情段本身也是用这一份推出来的三维（`MoodPrompt.line`），两处必须同源，
    /// 否则会出现「心情段说他不悦、引导却让他只聊此刻」这种自相矛盾。
    public static func pick(for mood: MoodState?) -> PromptGuidance {
        guard let mood, !mood.isEmpty() else { return .presentOnly }
        let dimensions = mood.resolvedDimensions
        if dimensions.valence < 0 || dimensions.arousal < lowArousalThreshold { return .defensive }
        return .presentOnly
    }

    /// 「按这一刻的心情应该注入的引导段」（心情缺失 → g17）。
    public static func section(for mood: MoodState?, language: PromptLanguage = .zh) -> String {
        section(pick(for: mood), language: language)
    }
}
