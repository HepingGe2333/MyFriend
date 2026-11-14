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

    public static func sleepMinutes(_ persona: PersonaEntity) -> Int {
        wrap(persona.quietSleepMinutes ?? defaultSleepMinutes)
    }

    public static func wakeMinutes(_ persona: PersonaEntity) -> Int {
        wrap(persona.quietWakeMinutes ?? defaultWakeMinutes)
    }

    /// 现在（当天第几分钟）是不是在睡。
    ///
    /// `sleepEnabled = false` 时永远不睡；睡醒同一个点等于没排作息（脏数据不该让他整天装睡）。
    public static func isAsleep(_ persona: PersonaEntity, minuteOfDay: Int) -> Bool {
        guard persona.sleepEnabled else { return false }
        let sleep = sleepMinutes(persona)
        let wake = wakeMinutes(persona)
        if sleep == wake { return false }
        let minute = wrap(minuteOfDay)
        if sleep > wake {
            // 跨午夜：23:30–08:00 这种。
            return minute >= sleep || minute < wake
        }
        return minute >= sleep && minute < wake
    }

    /// 睡到几点：返回距离醒来的分钟数，至少 10 分钟（避免「1 分钟后又醒」的抖动）。
    public static func minutesUntilWake(_ persona: PersonaEntity, minuteOfDay: Int) -> Int {
        guard persona.sleepEnabled else { return 10 }
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
        return text.isEmpty ? "睡着了 · \(wake) 醒" : "\(text) · \(wake) 醒"
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
        guard SleepSchedule.isAsleep(persona, minuteOfDay: minute) else { return .reply }
        if persona.dndOnSleep { return .sleepingDoNotDisturb }
        let threshold = PersonaFieldLimits.clampWakeThreshold(persona.wakeThreshold)
        return recentUserMessages >= threshold ? .wokenUp : .sleepingWaiting
    }

    /// 睡眠中被叫醒时心情的恢复方式：强度 -1；如果是负面词，直接回到「平静」。
    ///
    /// 为什么不按「+1 心情值」来算：心情是一个词 + 一个强度，不是一条标量曲线。
    /// 「被你叫醒」这件事对负面心情的修复远大于对正面心情的加成，所以负面直接归位，
    /// 其余只把强度往下压一档（人被叫醒之后通常没刚才那么浓烈）。
    public static func moodAfterWaking(label: String, intensity: Int) -> (label: String, intensity: Int) {
        let word = MoodLexicon.normalize(label)
        if MoodLexicon.isNegative(word) {
            return (MoodLexicon.BASELINE, 1)
        }
        return (word, min(max(intensity - 1, 1), 5))
    }

    /// 「被你叫醒了」——写进 `agent_ticks.outcome` 与「他最近在想什么」的那一句。
    public static func wakeUpOutcome(wakeClockText: String) -> String {
        "睡得正沉，被你叫醒了（本来打算 \(wakeClockText) 醒）"
    }
}
