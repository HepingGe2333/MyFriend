// 来源：app/src/main/java/com/yap/app/domain/Presence.kt
//
// 他此刻在做什么。
//
// 这是**他自己的状态**，不是用户能设置的开关：界面只能看见，不能改。
// 推导只依赖「他最近做过什么」——睡没睡、刚醒没、刚说过话没、想了没、翻过东西没、
// 心里是不是记着一件准备开口的事。全都由时间推出来，所以他什么都不做的时候会自然地
// 变回「在发呆」，而不是卡在某个状态里。
//
// 这个文件是纯算法：不碰数据库 / 网络；时间一律由参数传进来；
// 坏数据（nil、负数、未来时间、超大值）都不抛异常，只是安静地当作「不知道」。

import Foundation

/// 他在做的事情的种类。界面不显示这些英文名字，只显示 `Presence.text` 里的中文。
public enum PresenceKind: String, CaseIterable {
    /// 他自己定的作息时间段里，睡着。
    case asleep = "ASLEEP"
    /// 刚睡醒的一小段，脑子还没转起来。
    case justWoke = "JUST_WOKE"
    /// 在翻东西：刚自己上网看过点什么。
    case browsing = "BROWSING"
    /// 在想事情：刚自己一个人想过一阵。
    case thinking = "THINKING"
    /// 在写东西：刚说过话，或者对方刚说话、他大概正在回去。
    case writing = "WRITING"
    /// 心里记着一件事，过一会儿想找你。
    case waiting = "WAITING"
    /// 在发呆：没什么特别的事，就是坐着。
    case idle = "IDLE"
    /// 在忙别的：好一阵子没动静了。
    case away = "AWAY"
}

/// 推导「他此刻在做什么」要看的几件事。
///
/// 时间单位一律是「多少分钟之前」，nil 表示不知道（没有这类记录）。
/// `minutesSinceLastBrowse` 是优化项：能精确知道上一次「上网看看」是什么时候时传进来；
/// 不知道也可以，用 `lastTickOutcome` 里能看出来的迹象兜底。
public struct PresenceInput {
    /// 现在是不是他给自己安排的休息时间。
    public var asleepNow: Bool
    /// 距离他最近一次「醒来」过了多少分钟（按他自己定的作息算）；不知道为 nil。
    public var minutesSinceWake: Int?
    /// 距离上一次后台「想一下」过了多少分钟。
    public var minutesSinceLastTick: Int?
    /// 上一次「想」之后干了什么的一句话（库里 action/outcome 的原文，可能是空串）。
    public var lastTickOutcome: String?
    /// 距离上一次「上网看看」过了多少分钟；不知道为 nil。
    public var minutesSinceLastBrowse: Int?
    /// 距离最近一条消息（谁说的都算）过了多少分钟。
    public var minutesSinceLastMessage: Int?
    /// 距离他自己最近说的一句话过了多少分钟。
    public var minutesSinceLastAssistantMessage: Int?
    /// 他留的念头里，有没有落在接下来半小时内的。
    public var hasPendingIntentSoon: Bool
    /// 现在几点（0..23），只用来让措辞更贴近当下，不参与排序。
    public var hourOfDay: Int

    public init(
        asleepNow: Bool = false,
        minutesSinceWake: Int? = nil,
        minutesSinceLastTick: Int? = nil,
        lastTickOutcome: String? = nil,
        minutesSinceLastBrowse: Int? = nil,
        minutesSinceLastMessage: Int? = nil,
        minutesSinceLastAssistantMessage: Int? = nil,
        hasPendingIntentSoon: Bool = false,
        hourOfDay: Int = 12
    ) {
        self.asleepNow = asleepNow
        self.minutesSinceWake = minutesSinceWake
        self.minutesSinceLastTick = minutesSinceLastTick
        self.lastTickOutcome = lastTickOutcome
        self.minutesSinceLastBrowse = minutesSinceLastBrowse
        self.minutesSinceLastMessage = minutesSinceLastMessage
        self.minutesSinceLastAssistantMessage = minutesSinceLastAssistantMessage
        self.hasPendingIntentSoon = hasPendingIntentSoon
        self.hourOfDay = hourOfDay
    }
}

/// 一次推导的结果。
///
/// - `text`: 一行中文，界面直接显示（如「在想事情」）。
/// - `detail`: 稍微多一句的解释，用在需要解释的地方（如「他刚自己呆着想了一阵，没急着开口」）。
/// - `sinceAt`: 这个状态大概从什么时候开始（毫秒时间戳）；不知道时为 0，界面就不显示「…前」。
/// - `updatedAt`: 这次推导的时刻。
public struct Presence: Equatable {
    public var kind: PresenceKind
    public var text: String
    public var detail: String
    public var sinceAt: Int64
    public var updatedAt: Int64

    public init(kind: PresenceKind = .idle, text: String = "", detail: String = "",
                sinceAt: Int64 = 0, updatedAt: Int64 = 0) {
        self.kind = kind
        self.text = text
        self.detail = detail
        self.sinceAt = sinceAt
        self.updatedAt = updatedAt
    }

    /// 有没有内容可显示：没有的话界面就整行不画。
    public func isMeaningful() -> Bool { !text.trimmed.isEmpty }

    /// 给界面用的一行：「在想事情 · 12 分钟前」。
    /// 不知道从什么时候开始（`sinceAt` 为 0）时就只显示那句话，别硬凑时间。
    public func display(now: Int64 = DomainClock.nowMillis) -> String {
        if text.trimmed.isEmpty { return "" }
        let ago = PresenceRules.relativeTime(ms: sinceAt, now: now)
        return ago.isEmpty ? text : "\(text) · \(ago)"
    }
}

/// 判定用的常量与理由。都写成分钟，方便和界面上的「3 分钟前」对得上。
public enum PresenceRules {

    /// 刚醒的窗口：45 分钟。
    /// 人起床后的懵一般持续半小时上下；再长就不算「刚醒」，再短会显得他没醒过。
    public static let JUST_WOKE_MINUTES = 45

    /// 「在写东西」的窗口：3 分钟。
    /// 消息刚发出去、或者对方刚说完，他这会儿人还在对话框前；超过三分钟就未必了。
    public static let WRITING_MINUTES = 3

    /// 「在翻东西」的窗口：15 分钟。
    /// 后台每几分钟才想一次，翻完一样东西的余温大概就一刻钟。
    public static let BROWSING_MINUTES = 15

    /// 「在想事情」的窗口：15 分钟。
    /// 和翻东西同一个尺度：一次「想」的痕迹只在这段时间内还算「正在想」。
    public static let THINKING_MINUTES = 15

    /// 「过一会儿想找你」的提前量：30 分钟。
    /// 只提前半小时说，太早说了会像提醒事项，不像一个人心里揣着件事。
    public static let INTENT_SOON_MINUTES = 30

    /// 「在忙别的」的门槛：3 小时。
    /// 三小时没有任何动静，才值得说一句他在忙别的；再短会像在盯着他。
    public static let AWAY_MINUTES = 180

    /// 深夜（0..4 点）时「在发呆」的措辞会不一样：这么晚还没睡，本身就是件事。
    public static let LATE_NIGHT_HOURS = 0...4

    /// 「多久以前」的中文说法：刚刚 / 3 分钟前 / 昨天 / 上周 / 很久以前。
    ///
    /// `ms` 是那个时刻（毫秒时间戳），`now` 是现在。0 或负数表示「不知道什么时候」，返回空串，
    /// 让调用方干脆别写这一句；未来的时间（时钟回拨）当作「刚刚」，不抛异常。
    public static func relativeTime(ms: Int64, now: Int64) -> String {
        if ms <= 0 { return "" }
        if now <= ms { return String(localized: "刚刚") }
        let delta = now - ms
        let minutes = delta / 60_000
        if minutes < 1 { return String(localized: "刚刚") }
        if minutes < 60 { return PluralText.format("%d 分钟前", Int(minutes)) }
        let hours = delta / 3_600_000
        if hours < 24 { return PluralText.format("%d 小时前", Int(hours)) }
        let days = delta / 86_400_000
        if days <= 1 { return String(localized: "昨天") }
        if days < 7 { return PluralText.format("%d 天前", Int(days)) }
        if days < 14 { return String(localized: "上周") }
        if days < 30 { return PluralText.format("%d 周前", Int(days / 7)) }
        if days < 365 { return PluralText.format("%d 个月前", Int(days / 30)) }
        return String(localized: "很久以前")
    }
}

/// 由「他最近做过什么」推出现在在做什么。
///
/// 规则是一串优先级，先命中的赢（越靠前越具体、越不容置疑）：
///
/// 1. 睡着了 → 睡着了；
/// 2. 刚醒（起床 45 分钟内）→ 刚醒；
/// 3. 3 分钟内说过话 → 在写东西；
/// 4. 15 分钟内翻过东西 → 在翻东西；
/// 5. 15 分钟内想过事情 → 在想事情；
/// 6. 半小时内有个想找你的念头 → 过一会儿想找你；
/// 7. 3 小时以上没动静 → 在忙别的；
/// 8. 其余 → 在发呆。
///
/// 为什么是这个顺序：睡觉和刚醒是生理事实，压过一切；「在写东西」有时效性最强，
/// 刚说完话的人不会同时在「翻东西」；「在等时机」是心里的打算，比「没动静」更值得说；
/// 「在忙别的」需要足够长的沉默才算，不然会显得在监视他。
public enum PresenceDeriver {

    /// 什么都没做、也没有任何记录时的状态：坐着发呆。最不冒犯的一种猜测。
    public static let IDLE_FALLBACK = Presence(
        kind: .idle,
        text: String(localized: "在发呆"),
        detail: String(localized: "没什么特别的事，他就坐着发发呆。")
    )

    public static func derive(_ input: PresenceInput, now: Int64) -> Presence {
        let sinceTick = minutesOrNil(input.minutesSinceLastTick)
        let sinceMessage = minutesOrNil(input.minutesSinceLastMessage)
        let sinceOwnMessage = minutesOrNil(input.minutesSinceLastAssistantMessage)
        let sinceWake = minutesOrNil(input.minutesSinceWake)
        let sinceBrowse = minutesOrNil(input.minutesSinceLastBrowse)
            ?? browseMinutesFromOutcome(input.lastTickOutcome, sinceTick: sinceTick)
        let candidates = [sinceTick, sinceMessage, sinceOwnMessage].compactMap { $0 }
        let sinceAnyActivity = candidates.min()
        let hour = min(Swift.max(input.hourOfDay, 0), 23)

        if input.asleepNow {
            return presence(kind: .asleep, text: String(localized: "睡着了"),
                            detail: String(localized: "他把这段时间设成了休息，消息会留着，醒了再看。"),
                            sinceMinutes: nil, now: now)
        }
        if let sinceWake, sinceWake <= PresenceRules.JUST_WOKE_MINUTES {
            let detail = (5...10).contains(hour)
                ? String(localized: "他一早刚起来，还没完全清醒。")
                : String(localized: "他刚睡醒，人还有点懵。")
            return presence(kind: .justWoke, text: String(localized: "刚醒"), detail: detail,
                            sinceMinutes: sinceWake, now: now)
        }
        if speaksRecently(sinceOwnMessage: sinceOwnMessage, sinceMessage: sinceMessage) {
            let detail = (sinceOwnMessage != nil && sinceOwnMessage! <= PresenceRules.WRITING_MINUTES)
                ? String(localized: "他刚说完一句，还在那儿。")
                : String(localized: "对方刚说了话，他大概正在写回来。")
            return presence(kind: .writing, text: String(localized: "在写东西"), detail: detail,
                            sinceMinutes: [sinceOwnMessage, sinceMessage].compactMap { $0 }.min(),
                            now: now)
        }
        if let sinceBrowse, sinceBrowse <= PresenceRules.BROWSING_MINUTES {
            return presence(kind: .browsing, text: String(localized: "在翻东西"),
                            detail: String(localized: "他正自己翻着些东西，看到有意思的才可能跟你说。"),
                            sinceMinutes: sinceBrowse, now: now)
        }
        if let sinceTick, sinceTick <= PresenceRules.THINKING_MINUTES {
            return presence(kind: .thinking, text: String(localized: "在想事情"),
                            detail: String(localized: "他刚一个人想了一阵，没急着开口。"),
                            sinceMinutes: sinceTick, now: now)
        }
        if input.hasPendingIntentSoon {
            return presence(kind: .waiting, text: String(localized: "过一会儿想找你"),
                            detail: String(localized: "他心里记着一件事，在等一个合适的时机开口。"),
                            sinceMinutes: nil, now: now)
        }
        if let sinceAnyActivity, sinceAnyActivity >= PresenceRules.AWAY_MINUTES {
            return presence(kind: .away, text: String(localized: "在忙别的"),
                            detail: String(localized: "他有好一阵没动静了，大概在忙自己的事。"),
                            sinceMinutes: sinceAnyActivity, now: now)
        }
        let detail = PresenceRules.LATE_NIGHT_HOURS.contains(hour)
            ? String(localized: "这么晚了还没睡，他就一个人坐着。")
            : String(localized: "没什么特别的事，他就坐着发发呆。")
        return presence(kind: .idle, text: String(localized: "在发呆"), detail: detail,
                        sinceMinutes: sinceAnyActivity, now: now)
    }

    /// 3 分钟内有消息来往（他自己说的，或者对方刚说、他正回）。
    static func speaksRecently(sinceOwnMessage: Int?, sinceMessage: Int?) -> Bool {
        if let sinceOwnMessage, sinceOwnMessage <= PresenceRules.WRITING_MINUTES { return true }
        if let sinceMessage, sinceMessage <= PresenceRules.WRITING_MINUTES { return true }
        return false
    }

    /// 上一次「想」的痕迹里能不能看出他在翻东西。
    ///
    /// 库里的 outcome 是他那一轮做过什么的一句话（如「自己上网看了看」「发了一条消息」），
    /// 也可能是动作类型名（browse / web / search）。认不出来就当没翻过。
    static func browseMinutesFromOutcome(_ outcome: String?, sinceTick: Int?) -> Int? {
        guard let sinceTick, let outcome, !outcome.trimmed.isEmpty else { return nil }
        return looksLikeBrowsing(outcome) ? sinceTick : nil
    }

    static func looksLikeBrowsing(_ outcome: String) -> Bool {
        let text = outcome.trimmed
        if text.isEmpty { return false }
        let keywords = ["上网", "浏览", "翻了翻", "翻东西", "查了查"]
        if keywords.contains(where: { ChineseTerms.containsSubstring(text, $0) }) { return true }
        let lower = text.lowercased()
        return lower.contains("browse") || lower.contains("\"web\"") || lower.contains("web_search")
    }

    /// 负数一律当作「刚刚」，不让坏数据变成一个奇怪的状态。
    static func minutesOrNil(_ value: Int?) -> Int? {
        guard let value else { return nil }
        return value < 0 ? 0 : value
    }

    static func presence(kind: PresenceKind, text: String, detail: String,
                         sinceMinutes: Int?, now: Int64) -> Presence {
        let sinceAt: Int64
        if let sinceMinutes {
            // 时间戳不要超过 now、也不要为负：坏数据把「多少分钟前」算大了的时候，
            // 界面按「不知道什么时候」处理，总比显示一个荒唐的时间强。
            let raw = now - Int64(sinceMinutes) * 60_000
            if raw <= 0 {
                sinceAt = 0
            } else if raw > now {
                sinceAt = now
            } else {
                sinceAt = raw
            }
        } else {
            sinceAt = 0
        }
        return Presence(kind: kind, text: text, detail: detail, sinceAt: sinceAt, updatedAt: now)
    }
}
