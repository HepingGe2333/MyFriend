import Foundation

// ---------------------------------------------------------------------------
// 「可以什么都不填」（0.13 真机反馈第一批：预设/人格编辑不再强制填满）。
//
// 背景：以前的编辑页要求名字非空才让保存，空提示词又会让系统提示词里连一段
// 「# 你是谁」都没有 —— 用户只是想先建一个人再慢慢写，却被表单挡住。
//
// 这一版把「空」变成**合法输入**，并且给每个空字段一个确定的兜底：
// - 名字空 → 界面传来的本地化默认名（`L("未命名")`，Kit 自己不读资源）；
// - 提示词空 → 由名字 + 一句话介绍生成；两样都空 → 系统默认人设文本；
// - 世界 / 话风 / 作息 / 活动本来就允许为空（`null = 没有设定`，见 `spec/persona-guidance`）。
//
// 为什么兜底放在 Kit 而不是编辑页：备份接回来的旧人格、服务端下发的人格、种子人格
// 都可能名字为空，提示词必须在**渲染那一刻**也兜得住，而不是只在保存按钮那一次。
// 所以这里的两个函数各有两个调用点：编辑页保存时（写库前）与 `PromptBuilder`（渲染时）。
// ---------------------------------------------------------------------------

public enum PersonaDraft {

    /// Kit 内没有本地化资源时的兜底名（与 `Resources/*.lproj/Localizable.strings` 的
    /// `未命名` 同义）。App 层会把自己翻译好的名字传进来，这里只在极端情况下用到。
    public static let fallbackName = "未命名"

    /// 名字：去空白后为空 → `fallback`；`fallback` 也空 → `fallbackName`。
    public static func resolvedName(_ raw: String, fallback: String = "") -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
        let cleanFallback = fallback.trimmingCharacters(in: .whitespacesAndNewlines)
        return cleanFallback.isEmpty ? fallbackName : cleanFallback
    }

    /// 系统默认人设文本：名字、介绍、提示词全空时用它。
    ///
    /// 刻意写成「一个普通人」而不是「一个 AI 助手」：系统提示词后面还会拼上
    /// `# 边界`（不暴露 AI 身份），这里再自称助手会自相矛盾。
    public static let systemDefaultPrompt = """
    你是一个普通人，有自己的生活、脾气和情绪，不是谁的助手。
    你会累、会烦、会没话说，也会突然想起对方。按你自己的性子说话，不用刻意讨好，也不用每句都接得很漂亮。
    """

    /// 提示词空时的生成规则（与编辑页的提示语一致）：
    /// - 名字 + 介绍 → 「你叫 X。介绍」
    /// - 只有名字 → 「你叫 X。」+ 一句「按自己的性子说话」
    /// - 只有介绍 → 介绍 + 那句「按自己的性子说话」
    /// - 都没有 → `systemDefaultPrompt`
    public static func generatedPrompt(name: String, description: String) -> String {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)
        let selfNote = "按你自己的性子说话，不用刻意讨好对方。"
        switch (trimmedName.isEmpty, trimmedDescription.isEmpty) {
        case (false, false):
            return "你叫\(trimmedName)。\(trimmedDescription)"
        case (false, true):
            return "你叫\(trimmedName)。\(selfNote)"
        case (true, false):
            return "\(trimmedDescription)\n\(selfNote)"
        case (true, true):
            return systemDefaultPrompt
        }
    }

    /// 提示词：非空保留原文（**不截断、不改写**），空则走 `generatedPrompt`。
    ///
    /// 保留原文很重要：人设哈希（`PersonaTranslation.sourceHash`）与「改了提示词才升版本号」
    /// 都拿原文比对，这里顺手 trim 一下就会让两端算出不同的哈希。
    public static func resolvedPrompt(_ raw: String, name: String, description: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return raw }
        return generatedPrompt(name: name, description: description)
    }
}
