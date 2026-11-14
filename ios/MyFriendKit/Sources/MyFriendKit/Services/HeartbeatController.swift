import Foundation

// 来源：app/src/main/java/com/myfriend/app/data/repo/HeartbeatController.kt
//
// 他的心跳：每半分钟问自己一句「现在想不想他」。
//
// 这不是一个「每 30 秒发一条消息」的机制，正好相反：绝大多数拍什么都不会发生。
// 每一拍只做一件很轻的事 —— 看距离上次互动过了多久、把这股冲动的概率算出来、掷一次骰子。
// 没中就什么都不做（几微秒 CPU）；中了才叫他去 `AgentLoop.tick`，由**他自己**看着处境决定
// 说什么、去翻点什么、还是干脆什么都不做。
//
// 三个刻意的设计（与 Android 完全一致）：
//
// 1. **真源是「最近一次互动」**：用户刚说话、他刚说话、上次冲动触发，取这几个时间里最新的那个。
//    这样不用改聊天仓库，也不会漏掉某一种互动；他说的每句话都会让曲线重新走一遍。
// 2. **跑完就重置**：不管模型最后决定做什么（甚至出错），概率时钟都回到起点。
//    否则沉默越久越容易连击，那就成了推送。
// 3. **门槛在掷骰子之前**：睡着、今天说够了、这个月额度用完、刚想过（5 分钟下限）——
//    这些都不掷，直接跳过。既省电省额度，也不会让「他」显得不知分寸。
//
// ## 算法全在 Domain
//
// 曲线、熬夜折扣、心情放大、perBeat 换算、时钟推进全在 `Domain/Heartbeat.swift`；
// 这里只做「编排 + 持久化」。测试因此可以把 clock / roll / 依赖全换成假的，
// 一条一条断言「刚聊完不掷」「掷中后重置」「睡眠 / 上限 / 额度拦住」。
//
// ## 与 Android 的差别
//
// - 电量：Android 在低电量时把节拍放宽到 2 分钟（设备约束）。iOS 的系统 SQLite/心跳都在前台跑，
//   「省电」这件事由 App 层（是否暂停前台定时器、BGAppRefresh 的调度策略）决定，
//   所以这里留 `isLowPower` / `lowPowerIntervalMillis` 两个注入口，默认 30 秒；
// - 没有前台服务：拍子由 App 层驱动（前台 Timer / 回到前台补算一次），这个类不持有定时器。

public final class HeartbeatController {

    /// 一拍里要读的那几件事（互动、心情、重置点），一分钟内不重复读。
    public static let snapshotTTLMillis: Int64 = 60_000
    /// 心跳的节拍：30 秒。比人一次「走神」的间隔短得多，所以它只是判断，不是动作。
    public static let beatIntervalMillis: Int64 = 30_000
    /// 省电时的节拍：2 分钟（设备约束，不是产品行为）。
    public static let lowPowerBeatIntervalMillis: Int64 = 2 * 60_000
    /// 两次真正思考之间至少隔这么久，哪怕服务刚被重新拉起。
    public static let firstTickGapMillis: Int64 = 5 * 60_000

    /// 一拍的结果。
    public struct Beat: Equatable, Sendable {
        /// 这一拍是不是真的起心动念了（掷中了）。
        public var fired: Bool
        /// 他最后是不是真的做了点什么（说话/上网/记事…）；命中但决定「什么都不做」时为 false。
        public var acted: Bool
        /// 他这一轮的一句中文记录，只在真的做了事的时候才值得显示。
        public var summary: String
        /// 没动的原因（内部用，中文，不进提示词也不给用户看）。
        public var why: String

        public init(fired: Bool = false, acted: Bool = false, summary: String = "", why: String = "") {
            self.fired = fired
            self.acted = acted
            self.summary = summary
            self.why = why
        }
    }

    /// 一拍要看的那几件事的缓存。
    ///
    /// 心跳每 30 秒一次，一天 2880 拍，而绝大多数拍注定什么都不做；如果每一拍都去问
    /// 「他最近说过什么、心情怎样、上次重置在哪」，光是这几条查询就够把手机从睡梦里叫醒几十次。
    /// 这些东西在 60 秒内的变化对他「想不想你」没有意义，所以缓存一分钟。
    struct BeatSnapshot {
        var at: Int64
        var personaId: String
        /// 概率时钟的起点（上次互动 / 上次冲动里最新的那个）。
        var resetAt: Int64
        var mood: MoodState
        var tuning: MoodTuning
    }

    private let stores: DatabaseStores
    private let settings: SettingsProviding
    private let intents: AgentIntentSupport
    private let ticks: AgentTickStoring
    private let mood: MoodRepository
    /// 真正「想一下」的那一步。返回 nil 表示这一轮没想成（但仍然算想过一回）。
    private let tickRunner: (Int64) async -> AgentLoop.Outcome?
    private let now: () -> Int64
    private let roll: () -> Double
    private let isLowPower: () -> Bool
    private let lock = NSLock()
    private var cached: BeatSnapshot?

    public init(
        stores: DatabaseStores,
        settings: SettingsProviding,
        intents: AgentIntentSupport,
        ticks: AgentTickStoring,
        mood: MoodRepository,
        tickRunner: @escaping (Int64) async -> AgentLoop.Outcome?,
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        roll: @escaping () -> Double = { Double.random(in: 0..<1) },
        isLowPower: @escaping () -> Bool = { false }
    ) {
        self.stores = stores
        self.settings = settings
        self.intents = intents
        self.ticks = ticks
        self.mood = mood
        self.tickRunner = tickRunner
        self.now = now
        self.roll = roll
        self.isLowPower = isLowPower
    }

    /// 一次「心跳」：轻到可以每半分钟跑一回；只有掷中的时候才会花时间。
    public func beat(now: Int64? = nil, roll: Double? = nil) async -> Beat {
        let at = now ?? self.now()
        let dice = roll ?? self.roll()
        let settings = settings.load()
        if !settings.agentLoopEnabled { return Beat(why: "常驻循环已关闭") }

        guard let persona = pickPersona(settings.activePersonaId) else {
            return Beat(why: "还没有可以对话的人格")
        }

        // 他睡觉的时候不掷：既省电，也更像人。
        let minute = ServiceTime.minuteOfDay(at)
        if PersonaSleep.isAsleep(persona, minuteOfDay: minute) {
            return Beat(why: "他在睡觉")
        }

        // 刚聊完先安静一会儿：收尾期不是「概率低一点」，而是这段时间根本不掷骰子。
        // 否则 p(0)=0.35 每半分钟掷一次，一分钟内六成会触发，「先降后升」等于不存在。
        let snapshot = snapshotFor(persona: persona, settings: settings, now: at)
        if snapshot.resetAt <= 0 {
            // 第一次跑起来：从「刚刚互动过」开始，而不是从三个小时的思念开始。
            saveHeartbeatState(lastResetAt: at, lastFiredAt: settings.heartbeatLastFiredAt)
            return Beat(why: "")
        }
        let minutes = Heartbeat.minutesBetween(lastResetAt: snapshot.resetAt, now: at)
        if minutes < Heartbeat.COOL_DOWN_MINUTES {
            return Beat(why: "刚聊完，先安静一会儿")
        }

        // 概率是「每分钟」的，心跳是离散采样：换算成这一拍的概率再掷。
        // 拿的是上一次真正决定的节拍间隔（低电量时是 2 分钟），不是写死的 30 秒。
        let perMinute = Heartbeat.probability(
            minutesSinceReset: minutes,
            mood: snapshot.mood,
            now: at,
            hourOfDay: ServiceTime.hourOfDay(at)
        )
        let perBeat = Heartbeat.perBeatProbability(perMinute: perMinute, intervalMinutes: intervalMinutes())
        if !Heartbeat.shouldFire(probability: perBeat, roll: dice) { return Beat(why: "") }

        // 中了才算这些账：没中就不花查询去确认「今天说够了没」「额度还剩多少」。
        if let lastTick = ticks.lastTickAt(), at - lastTick < Self.firstTickGapMillis {
            return Beat(why: "刚想过，再等等")
        }
        // 每天最多主动几次，现在由人格的 `proactiveLevel` 决定（§一、§三）。
        let dailyCap = MoodBehavior.effectiveDailyCap(baseCap: persona.proactiveDailyCap, tuning: snapshot.tuning)
        if dailyCap <= 0 { return Beat(why: "主动消息关掉了") }
        let spoken = intents.spokenToday(personaId: persona.id, now: at)
        if spoken >= dailyCap { return Beat(why: "今天说够了（\(spoken)/\(dailyCap)）") }

        // 让他自己看着处境决定做什么。做什么都记在他自己的思考日志里。
        let outcome = await tickRunner(at)
        // 不管他做了什么、甚至出错了，这一轮都算「想过一回」：曲线从此刻重新走。
        let finishedAt = max(at, self.now())
        saveHeartbeatState(lastResetAt: finishedAt, lastFiredAt: finishedAt)
        cached = BeatSnapshot(
            at: finishedAt,
            personaId: persona.id,
            resetAt: finishedAt,
            mood: cached?.mood ?? snapshot.mood,
            tuning: cached?.tuning ?? snapshot.tuning
        )
        return Beat(
            fired: true,
            acted: outcome?.acted ?? false,
            summary: outcome?.summary ?? "",
            why: outcome == nil ? "这一轮没想成" : ""
        )
    }

    /// 下一次心跳隔多久（毫秒）。默认 30 秒；App 层判定省电时放宽到 2 分钟。
    public func checkIntervalMillis() -> Int64 {
        isLowPower() ? Self.lowPowerBeatIntervalMillis : Self.beatIntervalMillis
    }

    /// 一拍覆盖多少分钟：跟真正用的节拍走。
    public func intervalMinutes() -> Double {
        Double(checkIntervalMillis()) / 60_000.0
    }

    /// 低内存 / 切后台时清掉快照：下次回到前台重新读一次真实状态。
    public func clearCache() {
        lock.lock()
        cached = nil
        lock.unlock()
    }

    // MARK: - 快照

    func snapshotFor(persona: PersonaEntity, settings: AppSettings, now: Int64) -> BeatSnapshot {
        lock.lock()
        if let fresh = cached, fresh.personaId == persona.id, now - fresh.at < Self.snapshotTTLMillis {
            lock.unlock()
            return fresh
        }
        lock.unlock()
        let moodState = mood.current(personaId: persona.id, now: now)
        let resetAt = Heartbeat.latestMark(
            settings.heartbeatLastResetAt,
            settings.heartbeatLastFiredAt,
            lastInteractionAt(personaId: persona.id)
        )
        let snapshot = BeatSnapshot(
            at: now,
            personaId: persona.id,
            resetAt: resetAt,
            mood: moodState,
            tuning: MoodBehavior.from(moodState, now: now)
        )
        lock.lock()
        cached = snapshot
        lock.unlock()
        return snapshot
    }

    /// 最近一次「有互动」的时刻：用户说的最后一句、他说的最后一句、他上次主动开口，取最大值。
    ///
    /// 都查不到就返回 0，交给调用方当「还没有起点」处理。
    func lastInteractionAt(personaId: String) -> Int64? {
        let session = ((try? stores.sessions.byPersona(personaId)) ?? []).max {
            ($0.lastMessageAt ?? $0.createdAt) < ($1.lastMessageAt ?? $1.createdAt)
        }
        let userAt = session.flatMap { session in
            (try? stores.messages.first(
                where: "sessionId = ? AND role = 'USER'", [.text(session.id)], orderBy: "createdAt DESC"
            )) ?? nil
        }?.createdAt
        let ownAt = session.flatMap { session in
            (try? stores.messages.first(
                where: "sessionId = ? AND role = 'ASSISTANT'", [.text(session.id)], orderBy: "createdAt DESC"
            )) ?? nil
        }?.createdAt
        // 他主动开口时也可能还没进最新那个会话，所以再直接问一次思考日志。
        let spokenAt = ticks.lastSpokenAt(personaId: personaId)
        return [userAt, ownAt, spokenAt].compactMap { $0 }.max()
    }

    // MARK: - 持久化

    /// 把概率时钟写回本机设置。
    ///
    /// 键名与 Android 完全一致（`heartbeat_last_reset_at` / `heartbeat_last_fired_at`，
    /// 见 `SettingsStore.Key`）。写失败只当没写上：下一拍会用旧值重试，不值得让他崩一次。
    func saveHeartbeatState(lastResetAt: Int64, lastFiredAt: Int64?) {
        var current = settings.load()
        if current.heartbeatLastResetAt == lastResetAt, current.heartbeatLastFiredAt == lastFiredAt {
            return
        }
        current.heartbeatLastResetAt = lastResetAt
        current.heartbeatLastFiredAt = lastFiredAt
        settings.save(current)
    }

    // MARK: - 辅助

    private func pickPersona(_ activePersonaId: String?) -> PersonaEntity? {
        if let activePersonaId, let persona = (try? stores.personas.byId(activePersonaId)) ?? nil {
            return persona
        }
        if let defaultPersona = (try? stores.personas.defaultPersona()) ?? nil {
            return defaultPersona
        }
        return ((try? stores.personas.all(orderBy: "updatedAt DESC")) ?? []).first
    }
}
