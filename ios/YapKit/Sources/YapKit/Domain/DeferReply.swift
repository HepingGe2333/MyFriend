// 来源：app/src/main/java/com/yap/app/domain/DeferReply.kt
//
// 模型选择「这一轮先不回」时的时间计划。
//
// 规格 §四把边界写死了：minutes 缺省 45，夹在 5–720；reason ≤80 字，只进日志和「他最近在想什么」。
// 这些数字如果散在 ChatService / ChatRhythmService / ToolExecutor 里，改一处忘一处就会出现
// 「模型写了 3000 分钟，他三天后才回」这种用户完全无法理解的沉默。
// 所以收成一个纯函数，契约用例 `spec/agent-core/cases/defer-plan.json` 直接把边界钉住。
//
// 这个文件是 0.10.0 补的：在此之前 iOS 把夹取逻辑抄了三份（ChatService 拦截、
// ChatRhythmService 落库、ToolExecutor 实现），值虽然一样，但没有任何一处能单独被契约测试调用。

import Foundation

/// 一次 `defer_reply` 的时间计划。
public struct DeferPlan: Equatable, Sendable {
    /// 夹取后的分钟数（5–720）。
    public var minutes: Int
    /// 清洗后的理由（trim + 最多 80 字）；可以为空。
    public var reason: String
    /// 最早可以考虑开口的时刻（毫秒时间戳）。
    public var earliestAt: Int64
    /// 决策窗口的结束时刻（毫秒时间戳）= earliestAt + 30 分钟。
    public var latestAt: Int64

    public init(minutes: Int, reason: String, earliestAt: Int64, latestAt: Int64) {
        self.minutes = minutes
        self.reason = reason
        self.earliestAt = earliestAt
        self.latestAt = latestAt
    }
}

/// `defer_reply` 的常量与纯函数。
public enum DeferReply {

    /// 工具名（与 `ToolCatalog` 一致）：模型调用它，就表示这一轮选择不回。
    public static let toolName = "defer_reply"

    public static let defaultMinutes = ToolArgClamp.minutesDefault
    public static let minMinutes = ToolArgClamp.minutesMin
    public static let maxMinutes = ToolArgClamp.minutesMax
    public static let maxReasonChars = 80

    /// 到点后给他留的决策窗口：不是闹钟，到点只是「可以开始想这件事了」。
    public static let windowMinutes = 30

    /// 这一轮是否被模型主动推迟。
    ///
    /// 只看工具名，不看模型最后有没有输出文字：模型经常在调用工具的同时继续写正文，
    /// 那些字如果落库，就变成「他说了话但界面说已读」，自相矛盾。
    public static func wasRequested(_ toolNames: [String]) -> Bool {
        toolNames.contains {
            $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == toolName
        }
    }

    /// 把模型给的 minutes / reason 变成确定的计划。
    ///
    /// - Parameters:
    ///   - minutes: 模型给的分钟数；nil 用缺省 45，越界夹到 5–720。
    ///   - reason: 模型给的理由；trim 后截到 80 字。
    ///   - now: 现在（毫秒），由调用方传入 —— 纯函数不读时钟。
    public static func plan(minutes: Int?, reason: String?, now: Int64) -> DeferPlan {
        let safeMinutes = ToolArgClamp.clampDeferMinutes(minutes ?? defaultMinutes)
        let cleanReason = String(
            (reason ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxReasonChars)
        )
        let earliest = now + Int64(safeMinutes) * 60_000
        return DeferPlan(
            minutes: safeMinutes,
            reason: cleanReason,
            earliestAt: earliest,
            latestAt: earliest + Int64(windowMinutes) * 60_000
        )
    }
}
