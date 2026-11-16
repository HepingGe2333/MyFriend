import Foundation

// ---------------------------------------------------------------------------
// 睡眠、免打扰、唤醒阈值（1.9 §六）。
//
// 这一版把「几点睡几点醒」从一个固定语义改成三件事：
// 1. `sleepEnabled`：他**会不会主动睡觉**。false = 他不睡觉，任何时间都会回；
// 2. `dndOnSleep`：睡前要不要开免打扰。**由模型决定**（动作里带 dnd），用户不能直接改；
// 3. `wakeThreshold`：睡眠中被叫醒需要的连续消息数（1–20）。
//
// 规矩（逐条对应规格，顺序就是判定顺序）：
// ```
// if !sleepEnabled or 不在睡眠窗口: 正常回复
// if dndOnSleep: 不回复、不唤醒、不显示已读后的任何反应
// else:
//     最近 30 分钟内该人格会话里用户发的消息数（含这一条）
//     if 数量 >= wakeThreshold: 唤醒（mood 拉回）+ 立刻回复
//     else: 不回复（但已读），等他自然醒
// ```
//
// 为什么单独一个 Domain 文件：这是**产品的判断**，不是某一条链路（聊天 / 心跳）的细节，
// 而且它必须能被单测逐条钉住（阈值、窗口、免打扰三件事最容易在重构里悄悄改掉）。
// 时间一律由参数传进来，坏数据（nil、负数、跨午夜）都不抛异常。
// ---------------------------------------------------------------------------

/// 睡眠判定与窗口计算。纯函数，不碰数据库。
public enum SleepSchedule {

    /// 没设作息时的默认窗口：23:30 睡、08:00 醒（§6.1）。
    ///
    /// 为什么给默认值而不是「没设就不睡」：`sleepEnabled` 已经表达「他会不会主动睡觉」，
    /// 打开它却没有时间段的人，最合理的解释就是「按普通人的作息睡」。
    public static let defaultSleepMinutes = 23 * 60 + 30
    public static let defaultWakeMinutes = 8 * 60

    /// 睡着所需的最少连续消息数窗口：最近 30 分钟（§6.2）。
    public static let wakeWindowMillis: Int64 = 30 * 60 * 1000

    /// 作息窗口（睡、醒两个「当天第几分钟」）。
    ///
    /// 与 Kotlin `SleepPolicy.window` 一字不差：没设就用默认；睡醒定在同一个点等于没安排，
    /// 退回默认窗口 —— 而不是「全天都在睡」，也不是「永远不睡」。脏数据不该改变他的作息语义。
    public static func window(sleepMinutes: Int?, wakeMinutes: Int?) -> (sleep: Int, wake: Int) {
        let sleep = wrap(sleepMinutes ?? defaultSleepMinutes)
        let wake = wrap(wakeMinutes ?? defaultWakeMinutes)
        return sleep == wake ? (defaultSleepMinutes, defaultWakeMinutes) : (sleep, wake)
    }

    public static func sleepMinutes(_ persona: PersonaEntity) -> Int {
        window(sleepMinutes: persona.quietSleepMinutes, wakeMinutes: persona.quietWakeMinutes).sleep
    }

    public static func wakeMinutes(_ persona: PersonaEntity) -> Int {
        window(sleepMinutes: persona.quietSleepMinutes, wakeMinutes: persona.quietWakeMinutes).wake
    }

    /// 在不在睡眠窗口里（支持跨午夜，例如 23:30–08:00）。
    public static func inWindow(minuteOfDay: Int, sleepMinutes: Int, wakeMinutes: Int) -> Bool {
        let minute = wrap(minuteOfDay)
        let sleep = wrap(sleepMinutes)
        let wake = wrap(wakeMinutes)
        if sleep == wake { return false }
        if sleep > wake { return minute >= sleep || minute < wake }
        return minute >= sleep && minute < wake
    }

    /// 现在（当天第几分钟）是不是在睡。
    ///
    /// `sleepEnabled = false` 时永远不睡；睡醒同一个点走 `window` 的默认兜底。
    public static func isAsleep(_ persona: PersonaEntity, minuteOfDay: Int) -> Bool {
        guard persona.sleepEnabled else { return false }
        let window = window(sleepMinutes: persona.quietSleepMinutes, wakeMinutes: persona.quietWakeMinutes)
        return inWindow(minuteOfDay: minuteOfDay, sleepMinutes: window.sleep, wakeMinutes: window.wake)
    }

    /// 睡到几点：返回距离醒来的分钟数，至少 10 分钟（避免「1 分钟后又醒」的抖动）。
    ///
    /// 醒着时返回 0 —— 与 Kotlin `SleepPolicy.minutesUntilWake` 一致。旧实现醒着时也返回
    /// 一个「距离下一次醒来」的正数（不睡觉的人固定在 10），调用方一旦在醒着时误用，
    /// 就会把循环推迟十几个小时；0 表示「不需要等」，误用也不会有害。
    public static func minutesUntilWake(_ persona: PersonaEntity, minuteOfDay: Int) -> Int {
        guard isAsleep(persona, minuteOfDay: minuteOfDay) else { return 0 }
        let diff = ((wakeMinutes(persona) - wrap(minuteOfDay)) + 1440) % 1440
        return max(diff, 10)
    }

    /// 「23:30 醒」：聊天页状态行与提示词里都用这一句。
    public static func wakeClockText(_ persona: PersonaEntity) -> String {
        ServiceTime.clockText(wakeMinutes(persona))
    }

    /// 给提示词的一句「你的作息：每天 23:30 睡觉，08:00 醒来」。
    /// 不睡觉的人没有这一句 —— 他不该在提示词里读到一段不存在的作息。
    public static func hint(_ persona: PersonaEntity) -> String? {
        guard persona.sleepEnabled else { return nil }
        return "每天 \(ServiceTime.clockText(sleepMinutes(persona))) 睡觉，"
            + "\(ServiceTime.clockText(wakeMinutes(persona))) 醒来"
    }

    /// 聊天页顶栏的状态行：睡着时是「睡着了 · 08:00 醒」，其余照旧用 `Presence.display()`。
    ///
    /// 为什么把「几点醒」拼在这里而不是塞进 Presence：Presence 是「他在做什么」的纯推导，
    /// 不该知道人格的作息表；而这一行是界面文案，属于展示层的一次拼接。
    public static func statusLine(_ persona: PersonaEntity?, presenceText: String,
                                  now: Int64 = DomainClock.nowMillis) -> String {
        let text = presenceText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let persona, isAsleep(persona, minuteOfDay: ServiceTime.minuteOfDay(now))
        else { return text }
        let wake = wakeClockText(persona)
        return text.isEmpty
            ? String(format: String(localized: "睡着了 · %@ 醒"), wake)
            : String(format: String(localized: "%@ · %@ 醒"), text, wake)
    }

    static func wrap(_ minutes: Int) -> Int {
        ((minutes % 1440) + 1440) % 1440
    }
}

/// 这一轮「他会不会回」的判定结果。
///
/// 只有 `.reply` / `.wokenUp` 会真的走一次模型调用；其余两种都是「消息落库、这次不说话」。
/// 它们的差别只在界面与已读上（见 `SleepGate` 的注释）。
public enum SleepReplyDecision: Equatable, Sendable {
    /// 醒着（或者根本不睡觉）：正常回复。
    case reply
    /// 在睡、开了免打扰：不回复、不唤醒、也不显示「已读」后的任何反应。
    case sleepingDoNotDisturb
    /// 在睡、没开免打扰，但消息数还没到阈值：不回（但已读），等他自然醒。
    case sleepingWaiting
    /// 在睡、没开免打扰、消息数到了阈值：被叫醒了，这一轮正常回，并顺手把心情拉回。
    case wokenUp

    /// 这一轮要不要真的去调模型。
    public var shouldReply: Bool {
        self == .reply || self == .wokenUp
    }

    /// 要不要写「已读」。免打扰时连「已读」都不留 —— 那是「他不想被打扰」的一部分。
    public var marksRead: Bool {
        self != .sleepingDoNotDisturb
    }
}

/// 把「人格 + 最近消息数 + 现在几点」翻成上面那个判定。
public enum SleepGate {

    /// 一次判定的纯结果（与 Kotlin `SleepPolicy.Decision` 字段一一对应）。
    ///
    /// 单独有这一层，是为了让契约用例（`spec/agent-core/cases/sleep-decision.json`）
    /// 能直接喂显式参数，而不必构造一个数据库实体。
    public struct Evaluation: Equatable, Sendable {
        public var asleep: Bool
        public var dnd: Bool
        public var woken: Bool

        public init(asleep: Bool = false, dnd: Bool = false, woken: Bool = false) {
            self.asleep = asleep
            self.dnd = dnd
            self.woken = woken
        }

        /// 这一轮要不要真的去生成回复。
        public var shouldReply: Bool { !asleep || woken }

        /// 要不要给用户消息写「已读」。免打扰是唯一的例外（规格 §6.2）。
        public var marksRead: Bool { !dnd }
    }

    /// 显式参数的判定（纯函数）。
    public static func evaluate(
        sleepEnabled: Bool,
        sleepMinutes: Int?,
        wakeMinutes: Int?,
        minuteOfDay: Int,
        dndOnSleep: Bool,
        recentUserMessages: Int,
        wakeThreshold: Int
    ) -> Evaluation {
        guard sleepEnabled else { return Evaluation() }
        let window = SleepSchedule.window(sleepMinutes: sleepMinutes, wakeMinutes: wakeMinutes)
        guard SleepSchedule.inWindow(
            minuteOfDay: minuteOfDay, sleepMinutes: window.sleep, wakeMinutes: window.wake
        ) else { return Evaluation() }
        if dndOnSleep { return Evaluation(asleep: true, dnd: true, woken: false) }
        let recent = max(recentUserMessages, 0)
        let threshold = PersonaFieldLimits.clampWakeThreshold(wakeThreshold)
        return Evaluation(asleep: true, dnd: false, woken: recent >= threshold)
    }

    /// - Parameters:
    ///   - persona: 人格（读 sleepEnabled / dndOnSleep / wakeThreshold / 作息）。
    ///   - recentUserMessages: 最近 30 分钟内该人格会话里用户发的消息数（**含这一条**）。
    ///   - now: 现在（毫秒）。
    public static func decide(
        persona: PersonaEntity,
        recentUserMessages: Int,
        now: Int64,
        calendar: Calendar = ServiceTime.calendar()
    ) -> SleepReplyDecision {
        let minute = ServiceTime.minuteOfDay(now, calendar: calendar)
        let evaluation = evaluate(
            sleepEnabled: persona.sleepEnabled,
            sleepMinutes: persona.quietSleepMinutes,
            wakeMinutes: persona.quietWakeMinutes,
            minuteOfDay: minute,
            dndOnSleep: persona.dndOnSleep,
            recentUserMessages: recentUserMessages,
            wakeThreshold: persona.wakeThreshold
        )
        guard evaluation.asleep else { return .reply }
        if evaluation.dnd { return .sleepingDoNotDisturb }
        return evaluation.woken ? .wokenUp : .sleepingWaiting
    }

    /// 睡眠中被叫醒时心情的恢复方式：强度 -1；如果是负面词，直接回到「平静」。
    ///
    /// 为什么不按「+1 心情值」来算：心情是一个词 + 一个强度，不是一条标量曲线。
    /// 「被你叫醒」这件事对负面心情的修复远大于对正面心情的加成，所以负面直接归位，
    /// 其余只把强度往下压一档（人被叫醒之后通常没刚才那么浓烈）。
    public static func moodAfterWaking(label: String, intensity: Int) -> (label: String, intensity: Int) {
        let word = MoodLexicon.normalize(label)
        // 强度一律 -1、夹在 1..5（规格 §6.2 的字面语义；与 Kotlin `MoodRecovery.afterWake` 一致）。
        // 负面词只换标签（→ 平静），不额外把强度压到 1：那是旧 iOS 实现多走的一步，
        // 会让同一句「被你叫醒了」在两端得到不同的 mood.intensity。
        let nextIntensity = min(max(intensity - 1, 1), 5)
        if MoodLexicon.isNegative(word) {
            return (MoodLexicon.BASELINE, nextIntensity)
        }
        return (word, nextIntensity)
    }

    /// 「被你叫醒了」——写进 `agent_ticks.outcome` 与「他最近在想什么」的那一句。
    public static func wakeUpOutcome(wakeClockText: String) -> String {
        "睡得正沉，被你叫醒了（本来打算 \(wakeClockText) 醒）"
    }
}
