import Foundation

// ---------------------------------------------------------------------------
// 「他为什么不主动说话？」的诊断（0.13 真机反馈第一批）。
//
// 主动对话这条链路上有七道闸：常驻循环开关、有没有人格、睡没睡、忙不忙、
// 刚聊完的冷却、刚想过（5 分钟下限）、今天的主动次数上限。
// 任何一道关着都会让「他从不主动」看起来像坏了 —— 而用户看不到任何解释。
//
// 这个类型把七道闸收成一个**可打印的结论**：日志里能查、设置页能显示、测试能断言。
// 它只描述状态，不做决定：真正的判定仍在 `HeartbeatController.beat`
//（两处共用同一组常量，避免诊断说「可以发」而心跳不发）。
// ---------------------------------------------------------------------------

public struct ProactiveDiagnosis: Equatable, Sendable {

    /// 此刻挡在「主动开口」前面的那道闸。
    public enum Gate: String, Equatable, Sendable {
        /// 没有闸挡着：这一拍有概率开口。
        case ready
        /// 用户关掉了「让他常驻后台继续活着」。
        case loopDisabled
        /// 还没有可以对话的人格。
        case noPersona
        /// 正在作息睡眠窗口里（`spec/`：睡眠是「不被打扰」）。
        case asleep
        /// 当前活动是 BUSY（`spec/persona-guidance/README.md` §4）。
        case busy
        /// 刚聊完，还没过 `Heartbeat.COOL_DOWN_MINUTES`。
        case coolingDown
        /// 刚想过一回（`HeartbeatController.firstTickGapMillis` 之内）。
        case tooSoonAfterTick
        /// 今天主动开口的次数已经到上限（人格的 `proactiveLevel`）。
        case dailyCapReached

        /// 给人看的一句话（日志与设置页共用）。
        public var displayName: String {
            switch self {
            case .ready: return String(localized: "可以主动")
            case .loopDisabled: return String(localized: "常驻循环关着")
            case .noPersona: return String(localized: "还没有人格")
            case .asleep: return String(localized: "在睡觉")
            case .busy: return String(localized: "在忙")
            case .coolingDown: return String(localized: "刚聊完，先安静一会儿")
            case .tooSoonAfterTick: return String(localized: "刚想过一回")
            case .dailyCapReached: return String(localized: "今天说够了")
            }
        }
    }

    public var gate: Gate
    /// 一句中文说明（可以带数字，进日志）。
    public var detail: String
    /// 距离上次互动多少分钟；还没有互动时为 0。
    public var minutesSinceInteraction: Double
    /// 距离冷却结束还有多少分钟；不在冷却里为 0。
    public var cooldownMinutesLeft: Double
    /// 今天已经主动开口几次。
    public var spokenToday: Int
    /// 今天的上限（人格的 `proactiveLevel`，未叠心情修正）。
    public var dailyCap: Int
    /// 当前活动（有的话，用于说明「在忙什么」）。
    public var activity: String?

    public init(
        gate: Gate = .ready,
        detail: String = "",
        minutesSinceInteraction: Double = 0,
        cooldownMinutesLeft: Double = 0,
        spokenToday: Int = 0,
        dailyCap: Int = 0,
        activity: String? = nil
    ) {
        self.gate = gate
        self.detail = detail
        self.minutesSinceInteraction = minutesSinceInteraction
        self.cooldownMinutesLeft = cooldownMinutesLeft
        self.spokenToday = spokenToday
        self.dailyCap = dailyCap
        self.activity = activity
    }

    /// 一行日志：「主动对话：在忙（今天 1/3）—— 在事务所赶方案」。
    public func logLine() -> String {
        var text = "[proactive] \(gate.displayName)"
        if spokenToday > 0 || dailyCap > 0 {
            text += "（今天 \(spokenToday)/\(dailyCap)）"
        }
        let cleanDetail = detail.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanDetail.isEmpty { text += " · \(cleanDetail)" }
        return text
    }
}
