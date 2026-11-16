import Foundation

// 来源：app/src/main/java/com/yap/app/data/repo/PresenceRepository.kt
//
// 「他此刻在做什么」的数据拼装。
//
// 只读：这里从不写库、也没有任何「设置状态」的方法。它做的事就是把他最近留下的痕迹
// （后台想过什么、说过什么、翻过什么、心里记着什么、是不是在休息）拼成 `PresenceInput`，
// 交给 Domain/PresenceDeriver 推成一句话。
//
// 30 秒脉冲由**调用方**驱动（iOS 的 SwiftUI 用 TimelineView/Timer 重算），
// 这个类不持有定时器、也不订阅数据库：一次调用就是一次当下的快照。
//
// 与 Android 的差别：Android 的 `observe()` 是 Flow（tick 变化 + 30 秒脉冲 combine）；
// iOS 这边由界面层每 30 秒调一次 `current(personaId:)`，语义相同（读的都是同一批痕迹）。

public final class PresenceRepository {

    /// 只为判断「刚才有没有翻东西」而读的最近几条思考留痕。
    static let recentTicks = 30

    private let stores: DatabaseStores
    private let ticks: AgentTickStoring
    private let intents: AgentIntentSupport
    private let now: () -> Int64
    private let calendar: Calendar

    public init(
        stores: DatabaseStores,
        ticks: AgentTickStoring,
        intents: AgentIntentSupport,
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        calendar: Calendar = ServiceTime.calendar()
    ) {
        self.stores = stores
        self.ticks = ticks
        self.intents = intents
        self.now = now
        self.calendar = calendar
    }

    /// 此刻的他：不订阅、不等待，问一次就回答一次。
    ///
    /// 返回的 `detail` 换成了**他自己视角**的说法（「刚醒，脑子还没转」），
    /// 而不是界面用的第三人称（「他有好一阵没动静了」）：这段字会原样拼进他的提示词，
    /// 用「他」开头会让他读起来像在说别人。
    public func current(personaId: String, now: Int64? = nil) -> Presence {
        let at = now ?? self.now()
        let presence = snapshot(personaId: personaId, now: at)
        var copy = presence
        copy.detail = Self.selfDescription(presence)
        return copy
    }

    /// 把推导结果翻成他自己会说的那句话。认不出的状态就不硬写，退回原来的解释。
    public static func selfDescription(_ presence: Presence) -> String {
        switch presence.kind {
        case .asleep: return "这会儿睡下了，脑子是歇着的"
        case .justWoke: return "刚醒，脑子还没转过来"
        case .browsing: return "正一个人翻着点东西，看到有意思的会想讲给他听"
        case .thinking: return "刚一个人坐着想了一阵，没急着开口"
        case .writing: return "人还在对话框这边，刚说了话"
        case .waiting: return "心里揣着一件事，在等一个合适的时机"
        // 「在忙别的」是给界面看的说法，他自己不会这么讲自己。
        case .away: return "有阵子没顾上这边，在过自己的日子"
        case .idle: return presence.detail.contains("这么晚") ? "这么晚了还没睡，一个人坐着" : "在发呆，没什么事"
        }
    }

    /// 把这一瞬间能查到的痕迹拼成一次推导。任何一处查不到都不影响其它几处。
    public func snapshot(personaId: String, now: Int64? = nil) -> Presence {
        let at = now ?? self.now()
        let persona = (try? stores.personas.byId(personaId)) ?? nil
        let minute = ServiceTime.minuteOfDay(at, calendar: calendar)
        let asleepNow = persona.map { PersonaSleep.isAsleep($0, minuteOfDay: minute) } ?? false
        let sinceWake = persona.flatMap { minutesSinceWake(persona: $0, now: at) }
        let recent = ticks.recent(personaId: personaId, limit: Self.recentTicks)
        let lastTick = recent.first
        let lastBrowse = recent.first(where: Self.looksLikeBrowsing)
        // 最近在聊的那个会话：一次查询同时拿到「最近一条消息」和「他最后说的那句」。
        let session = latestSession(personaId: personaId)
        let lastMessage = session.flatMap { session in
            (try? stores.messages.first(
                where: "sessionId = ?", [.text(session.id)], orderBy: "createdAt DESC, id DESC"
            )) ?? nil
        }
        let lastOwnMessage = session.flatMap { session in
            (try? stores.messages.first(
                where: "sessionId = ? AND role = 'ASSISTANT'", [.text(session.id)], orderBy: "createdAt DESC"
            )) ?? nil
        }
        let input = PresenceInput(
            asleepNow: asleepNow,
            minutesSinceWake: sinceWake,
            minutesSinceLastTick: ServiceTime.minutesBetween(lastTick?.at, at),
            lastTickOutcome: lastTick?.outcome,
            minutesSinceLastBrowse: ServiceTime.minutesBetween(lastBrowse?.at, at),
            minutesSinceLastMessage: ServiceTime.minutesBetween(lastMessage?.createdAt, at),
            minutesSinceLastAssistantMessage: ServiceTime.minutesBetween(lastOwnMessage?.createdAt, at),
            hasPendingIntentSoon: hasIntentSoon(personaId: personaId, now: at),
            hourOfDay: ServiceTime.hourOfDay(at, calendar: calendar)
        )
        return PresenceDeriver.derive(input, now: at)
    }

    // MARK: - 内部

    private func latestSession(personaId: String) -> ChatSessionEntity? {
        ((try? stores.sessions.byPersona(personaId)) ?? []).max {
            ($0.lastMessageAt ?? $0.createdAt) < ($1.lastMessageAt ?? $1.createdAt)
        }
    }

    /// 距离最近一次「醒来」过了多少分钟。
    ///
    /// 只按他自己定的作息算：今天的起床时间还没到，就用昨天那个。
    /// 他没安排作息时返回 nil —— 不知道就别猜。
    private func minutesSinceWake(persona: PersonaEntity, now: Int64) -> Int? {
        guard let wake = persona.quietWakeMinutes else { return nil }
        // 睡和醒定在同一个点上等于没安排作息，这时候「起床时间」没有意义，别硬算。
        if persona.quietSleepMinutes == wake { return nil }
        let wakeMinute = ((wake % 1440) + 1440) % 1440
        let date = Date(timeIntervalSince1970: Double(now) / 1000.0)
        let startOfDay = calendar.startOfDay(for: date)
        var wakeAt = startOfDay.addingTimeInterval(TimeInterval(wakeMinute * 60))
        if Int64((wakeAt.timeIntervalSince1970 * 1000.0).rounded()) > now {
            wakeAt = calendar.date(byAdding: .day, value: -1, to: wakeAt) ?? wakeAt.addingTimeInterval(-86_400)
        }
        let wakeMillis = Int64((wakeAt.timeIntervalSince1970 * 1000.0).rounded())
        return ServiceTime.minutesBetween(wakeMillis, now)
    }

    /// 他给自己留的念头里，有没有落在接下来半小时内的（还没过期的）。
    private func hasIntentSoon(personaId: String, now: Int64) -> Bool {
        let horizon = now + Int64(PresenceRules.INTENT_SOON_MINUTES) * 60_000
        return intents.pending(personaId: personaId).contains { intent in
            intent.earliestAt <= horizon && intent.latestAt >= now
        }
    }

    static func looksLikeBrowsing(_ tick: AgentTick) -> Bool {
        let text = tick.outcome
        if text.contains("上网") || text.contains("浏览") || text.contains("翻了翻") { return true }
        return tick.actionJson.contains("\"browse\"") || tick.actionJson.contains("\"web_search\"")
    }
}
