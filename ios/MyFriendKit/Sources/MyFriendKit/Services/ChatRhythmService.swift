import Foundation

// ---------------------------------------------------------------------------
// 聊天的「节奏」：他说不说话，由他自己决定（1.9 §四、§六）。
//
// 这一版把两件事从「设置项」变成了「他的选择」：
// 1. **回复节奏**：模型可以用 `defer_reply` 这一轮先不回，过一阵再说；
// 2. **免打扰/睡眠**：他睡不睡、睡的时候要不要开免打扰、被叫醒要几条消息，都在人格上。
//
// 这个文件把它们需要落库的那一半收在一处（心情拉回、tick 留痕、agent_intents），
// 让 `ChatService` 只需要一个很窄的协议：它只管「这一轮要不要说话」，
// 不关心 intent 怎么排、tick 怎么写。
// ---------------------------------------------------------------------------

/// `ChatService` 需要的那两件事。
///
/// 为什么是协议而不是直接依赖 `MoodRepository` / `AgentIntentSupport`：
/// 聊天链路的测试里有 90% 跟这两件事无关，注入一个假实现能让那些测试保持轻。
public protocol ChatRhythmHandling: AnyObject {
    /// 他睡着时被连续几条消息叫醒了：心情拉回 + 在「他最近在想什么」里留一句。
    func handleWokenUp(personaId: String, at: Int64)

    /// 模型这一轮选择不回（`defer_reply`）：写一条 REACH_OUT 的念头，到点再决定开不开口。
    func handleDeferredReply(personaId: String, sessionId: String,
                             minutes: Int, reason: String, at: Int64)
}

/// 真正的实现：全部落在既有的仓储上（不新建表，不新建第二种「念头」）。
public final class ChatRhythmService: ChatRhythmHandling {

    /// `defer_reply` 的分钟数缺省与夹取范围（规格 §四：缺省 45，夹在 5–720）。
    public static let defaultDeferMinutes = 45
    public static let deferMinutesRange = 5...720
    /// 念头窗口的跨度：到点之后的半小时内都算「合适的时机」。
    public static let deferWindowMinutes = 30
    /// reason 只进日志与「他最近在想什么」，所以限长 80 字。
    public static let deferReasonMax = 80

    private let stores: DatabaseStores
    private let mood: MoodRepository
    private let ticks: AgentTickStoring
    private let intents: AgentIntentSupport
    private let now: () -> Int64

    public init(
        stores: DatabaseStores,
        mood: MoodRepository,
        ticks: AgentTickStoring,
        intents: AgentIntentSupport,
        now: @escaping () -> Int64 = { DomainClock.nowMillis }
    ) {
        self.stores = stores
        self.mood = mood
        self.ticks = ticks
        self.intents = intents
        self.now = now
    }

    // MARK: - 被叫醒

    /// 唤醒之后心情要改善，并且留下痕迹 —— 这是「他被你吵醒了」唯一的证据。
    ///
    /// 界面**不**提示「他被你吵醒了」（§六）：这件事只写进 `persona.mood` 与
    /// 「他最近在想什么」，用户看到的是一句「刚醒」，而不是一句道歉。
    public func handleWokenUp(personaId: String, at: Int64) {
        guard let persona = (try? stores.personas.byId(personaId)) ?? nil else { return }
        let current = mood.current(personaId: personaId, now: at)
        let next = SleepGate.moodAfterWaking(label: current.label, intensity: current.intensity)
        _ = mood.setMood(
            personaId: personaId,
            label: next.label,
            intensity: next.intensity,
            reason: "睡着的时候被叫醒了，缓了一下",
            now: at
        )

        let wakeText = SleepSchedule.wakeClockText(persona)
        ticks.insert(AgentTick(
            id: UUID().uuidString,
            personaId: personaId,
            at: at,
            thought: "本来在睡，被连续几条消息叫醒了。",
            actionJson: #"{"thought":"被叫醒了","actions":[{"type":"nothing"}]}"#,
            outcome: SleepGate.wakeUpOutcome(wakeClockText: wakeText),
            messageId: nil
        ))
    }

    // MARK: - 这次先不回

    /// 模型（用 `defer_reply`）说「现在不想说话」时，落一条念头。
    ///
    /// 关键点（§四）：
    /// - **不产生助手消息**：一个空气泡比沉默更让人难受；
    /// - 用户消息**已经**是已读（模型看到了它才做出这个决定）；
    /// - 由心跳在到点时再问他一次，他仍然可以选择继续沉默。
    public func handleDeferredReply(personaId: String, sessionId: String,
                                    minutes: Int, reason: String, at: Int64) {
        let clamped = min(max(minutes, Self.deferMinutesRange.lowerBound),
                          Self.deferMinutesRange.upperBound)
        let note = String(reason.trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(Self.deferReasonMax))
        let earliest = at + Int64(clamped) * 60_000
        let window = AgentWindow(
            earliestAt: earliest,
            latestAt: earliest + Int64(Self.deferWindowMinutes) * 60_000,
            note: note.isEmpty ? "刚才没说话，过一会儿再看看" : note,
            hint: "他刚才自己选择先不说；现在如果你还想说，就用你自己的语气开口，别解释刚才为什么没回"
        )
        _ = intents.plan(
            personaId: personaId,
            window: window,
            kind: .reachOut,
            note: window.note,
            hint: window.hint,
            sessionId: sessionId,
            now: at
        )
    }

    /// 给「他最近在想什么」用的一句话（界面读 `AgentTickStore.latest`，这里只是拼装）。
    public static func deferredReplyNote(minutes: Int, reason: String) -> String {
        let clean = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return "刚才没说话，\(minutes) 分钟后再说" }
        return "\(clean)（\(minutes) 分钟后再说）"
    }
}
