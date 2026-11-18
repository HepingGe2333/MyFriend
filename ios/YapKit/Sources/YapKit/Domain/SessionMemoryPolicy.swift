import Foundation

// 来源：`spec/experience/README.md` §6「记忆」的 `session_memory` 部分。
//
// 两份滚动摘要（短期 / 长期）的**所有数字与形状**都在这里：
// - 短期：每 5 条用户消息更新一次，≤200 字，覆盖最近 5 轮；
// - 长期：每 30 条用户消息更新一次，≤500 字，覆盖更早的对话；
// - 注入系统提示词时**短期在前、长期在后**，作为「你的备忘录」，属于可变区域（不写进人设）。
//
// 为什么单独一个纯逻辑文件：节流、上限、注入顺序是两端契约，也是「失败保留旧摘要」的判定基础；
// 放进服务层就会变成「测试要起一个库 + 假模型」才能验证的数字。

public enum SessionMemoryPolicy {

    /// 短期摘要的字数上限（规范 §6：≤200 字）。
    public static let shortLimit = 200
    /// 长期摘要的字数上限（规范 §6：≤500 字）。
    public static let longLimit = 500
    /// 短期摘要每次覆盖的轮数（规范 §6：最近 5 轮）。
    public static let shortRounds = 5
    /// 短期摘要的更新间隔：每 5 条用户消息（规范 §6）。
    public static let shortInterval = 5
    /// 长期摘要的更新间隔：每 30 条用户消息（规范 §6）。
    public static let longInterval = 30
    /// 长期摘要覆盖「更早的对话」时，最多往前看多少条消息（成本闸，不是产品语义）。
    public static let longWindowMessages = 60

    /// 这份摘要该不该更新：距上次更新已经又攒够 `interval` 条用户消息。
    ///
    /// 用「条数差」而不是「条数取模」：失败时不会写 `upTo`，下一次回复会重试；
    /// 如果按 `count % 5 == 0` 判，失败一次就要再等 5 条才补得上（而且可能永远补不上）。
    public static func shouldUpdate(currentUserCount: Int, upTo: Int, interval: Int) -> Bool {
        guard currentUserCount > 0 else { return false }
        return currentUserCount - max(upTo, 0) >= max(interval, 1)
    }

    /// 摘要清洗：压成一段、按上限截断；空串 → nil（不写空摘要）。
    public static func clean(_ raw: String?, limit: Int) -> String? {
        let flattened = (raw ?? "")
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !flattened.isEmpty else { return nil }
        return String(flattened.prefix(limit))
    }

    /// 注入系统提示词的那一段「你的备忘录」。
    ///
    /// 顺序**短期在前、长期在后**（规范 §6 明文）：短期是「刚才聊了什么」，
    /// 长期是「更早的事」；模型读到的顺序与人的记忆一致（先想起刚发生的）。
    /// 两份都空 → 空串（整节不出现，不留一个空标题）。
    public static func memoBlock(short: String?, long: String?) -> String {
        let shortText = clean(short, limit: shortLimit)
        let longText = clean(long, limit: longLimit)
        guard shortText != nil || longText != nil else { return "" }
        var lines: [String] = [
            "\n\n---\n## 你的备忘录",
            "这是你自己记下的对话摘要（不是人设，会随时间更新）；回答时自然地用，不要逐条复述。"
        ]
        if let shortText {
            lines.append("")
            lines.append("**最近几轮**")
            lines.append("- \(shortText)")
        }
        if let longText {
            lines.append("")
            lines.append("**更早以前**")
            lines.append("- \(longText)")
        }
        return lines.joined(separator: "\n")
    }
}
