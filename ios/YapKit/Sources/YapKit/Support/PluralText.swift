import Foundation

// ---------------------------------------------------------------------------
// 数量文案：中文只有一种说法，英文要分单复数。
//
// key 仍是中文原文（与 App 侧 `Lcount` 同一套约定）；英文资源里再给一条 `key + ".one"`。
// 为什么 Kit 要自己实现一份：`Localization.swift` 在 App target，Kit 不能依赖它。
//
// 没有资源包时（`swift test`、预览）`NSLocalizedString` 会原样返回 key，
// 于是 `.one` 变体「找不到」并回落到普通 key —— 输出仍是中文原文，
// 契约测试（agent-core 用例、备份摘要）断言的正是这一份。
// ---------------------------------------------------------------------------

public enum PluralText {
    /// `count == 1` 且有单数变体时用变体，否则用普通 key；两者都给 `count` 当第一个参数。
    public static func format(_ key: String, _ count: Int) -> String {
        let oneKey = key + ".one"
        let one = NSLocalizedString(oneKey, comment: "")
        let template = (count == 1 && one != oneKey) ? one : NSLocalizedString(key, comment: "")
        return String(format: template, count)
    }

    /// 只挑模板，不格式化：给「计数不是第一个占位符」的句子用
    /// （调用方按自己的参数顺序 `String(format:)`，例如「%@ 里有 %d 条读不出来」）。
    public static func template(_ key: String, _ count: Int) -> String {
        let oneKey = key + ".one"
        let one = NSLocalizedString(oneKey, comment: "")
        return (count == 1 && one != oneKey) ? one : NSLocalizedString(key, comment: "")
    }

    /// 多个计数拼一句摘要时的分隔符（中英都是「 · 」）。
    public static func list(_ parts: [String]) -> String {
        parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}
