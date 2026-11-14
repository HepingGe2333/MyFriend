// 来源：app/src/main/java/com/myfriend/app/domain/ToolArgClamp.kt
//
// 工具参数的夹取（模型给什么，都不该越界）。
//
// 模型时常给「差不多」的数字：`importance: 9`、`count: 50`、`minutes: 3000`。
// 这些值如果直接落库/发请求，会出现「一条记忆重要到 9/5」「一次搜索要 50 条结果」
// 「他说三天后再回」这类用户无法理解的状态。所以每一处入口都夹一次。
//
// 为什么单独一个文件、而且是纯函数：
// - 夹取规则（1–5 / 1–12 / 5–720）是**两端必须一致**的事实，
//   由 `spec/agent-core/cases/tool-args.json` 钉住；
// - 以前 iOS 在 ToolExecutor / ChatService / ChatRhythmService 里各写一份 `min(max(...))`，
//   值虽然一样，但没有任何一处能被契约测试直接调用。0.10.0 收成这一个入口。

import Foundation

/// 工具参数的数值边界。
public enum ToolArgClamp {

    // MARK: remember

    /// 记忆重要度：1 很轻 … 5 很重要。
    public static let importanceMin = 1
    public static let importanceMax = 5
    /// 缺省重要度：模型没写时按「普通」记。
    public static let importanceDefault = 3

    // MARK: web_search

    /// 一次搜索结果条数：至少 1，最多 12（再多只会把上下文塞满）。
    public static let countMin = 1
    public static let countMax = 12
    /// 缺省结果条数。
    public static let countDefault = 6

    // MARK: defer_reply

    /// 推迟回复的分钟数：5 分钟到 12 小时（规格 §四）。
    public static let minutesMin = 5
    public static let minutesMax = 720
    /// 缺省推迟 45 分钟（规格 §四）。
    public static let minutesDefault = 45

    public static func clampImportance(_ value: Int) -> Int {
        min(max(value, importanceMin), importanceMax)
    }

    public static func clampCount(_ value: Int) -> Int {
        min(max(value, countMin), countMax)
    }

    public static func clampDeferMinutes(_ value: Int) -> Int {
        min(max(value, minutesMin), minutesMax)
    }
}
