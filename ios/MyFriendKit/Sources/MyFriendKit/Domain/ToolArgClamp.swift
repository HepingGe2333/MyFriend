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

    // MARK: set_activity（人设引导 §4）

    /// 「他接下来这段时间在做什么」的持续分钟数：15 分钟到 8 小时。
    ///
    /// 边界与契约逐字一致（`spec/agent-core/cases/tool-args.json` 的 `activity.rules.minutes`）：
    /// 下界 15 是因为「忙 5 分钟」没有意义（模型下次开口时它已经过期），
    /// 上界 480 是因为超过 8 小时的「活动」不再是活动，而是忘了收尾的状态。
    public static let activityMinutesMin = 15
    public static let activityMinutesMax = 480
    /// 模型没写 / 写了个不是数字的东西时按 60 分钟算。
    public static let activityMinutesDefault = 60

    /// 夹取一个确定是数字的值。
    public static func clampActivityMinutes(_ value: Int) -> Int {
        min(max(value, activityMinutesMin), activityMinutesMax)
    }

    /// 从任意 JSON 值夹取：数字、数字字符串都认；认不出（null / "abc" / 布尔 / 数组）走默认值。
    ///
    /// 为什么不像 `clampImportance` 那样只收 `Int`：工具参数是模型写的，
    /// `{"minutes":"abc"}` 是真实会出现的形状（契约里就有这条用例）。
    public static func activityMinutes(_ raw: Any?) -> Int {
        if let number = raw as? NSNumber {
            // JSON 的 true/false 也会是 NSNumber（CFBoolean）：那不是分钟数。
            if CFGetTypeID(number) == CFBooleanGetTypeID() { return activityMinutesDefault }
            return clampActivityMinutes(number.intValue)
        }
        if let text = raw as? String {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let value = Int(trimmed) else { return activityMinutesDefault }
            return clampActivityMinutes(value)
        }
        return activityMinutesDefault
    }
}
