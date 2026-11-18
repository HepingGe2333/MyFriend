import Foundation

// 来源：app/src/main/java/com/yap/app/data/repo/HeartbeatController.kt
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
    /// 0.14：活跃窗口里的心理活动（每 3 分钟 / 3 轮）。nil = 没接（测试与老调用点不用改）。
    ///
    /// 判定（该不该做）在 `MentalActivityService` 里；这里只在「他还在聊」时递一拍，
    /// 避免在早已没人说话的会话上每 30 秒白问一次。
    private let mentalActivityRunner: ((String, String, Int64) async -> Void)?
    /// 0.14：拉黑状态（同时负责「到期自动解除」的落库清列）。
    private let block: BlockRepository?
    private let now: () -> Int64
    private let roll: () -> Double
    private let isLowPower: () -> Bool
    /// 每一拍的结论写进日志（真机排查「他为什么不主动」时唯一的线索）。
    ///
    /// 默认空实现：测试与老调用点不用改；App 层接上 `DebugSeeding.log`。
    private let log: (String) -> Void
    /// 服务端模式判定（0.13）：他在服务端那边活着时，本机这一拍什么都不做。
    ///
    /// 用闭包而不是 Bool 参数：模式可以在运行中切换，每一拍都要问「此刻是不是」。
    /// 默认 `false` 让测试与老调用点不用改；App 层接的是 `serverModeActive`。
    private let isServerMode: () -> Bool
    /// agent 决策层（0.11.0）：每日主动上限的**基础值**走它（心情修正仍在本机）。
    private let agentCore: any AgentCoreClient
    private let lock = NSLock()
    private var cached: BeatSnapshot?
    /// 「这一拍归谁」的互斥（0.10.2 审计 P1-2）。
    ///
    /// 冷启动 `.task`、回前台 `onChange`、BGTask 时间片、前台 30 秒定时器可能同时打进来。
    /// 原来的 `beat()` 全程没有互斥：并发两次都会读到同一个 `lastResetAt`，都穿过
    /// 「刚想过」闸（那道闸在掷骰子之后才查）→ 两次 LLM 调用、两条主动消息、两条通知。
    ///
    /// 这里用「原子标记 + 直接跳过」而不是排队：心跳是**周期性采样**，
    /// 这一拍被占掉了，下一拍（30 秒后）再掷就是了；排队反而会让消息挤在一起。
    private let beatLock = NSLock()
    private var beating = false

    public init(
        stores: DatabaseStores,
        settings: SettingsProviding,
        intents: AgentIntentSupport,
        ticks: AgentTickStoring,
        mood: MoodRepository,
        agentCore: any AgentCoreClient = AgentCoreClientFactory.makeNative(),
        tickRunner: @escaping (Int64) async -> AgentLoop.Outcome?,
        mentalActivityRunner: ((String, String, Int64) async -> Void)? = nil,
        block: BlockRepository? = nil,
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        roll: @escaping () -> Double = { Double.random(in: 0..<1) },
        isLowPower: @escaping () -> Bool = { false },
        isServerMode: @escaping () -> Bool = { false },
        log: @escaping (String) -> Void = { _ in }
    ) {
        self.stores = stores
        self.settings = settings
        self.intents = intents
        self.ticks = ticks
        self.mood = mood
        self.agentCore = agentCore
        self.tickRunner = tickRunner
        self.mentalActivityRunner = mentalActivityRunner
        self.block = block
        self.now = now
        self.roll = roll
        self.isLowPower = isLowPower
        self.isServerMode = isServerMode
        self.log = log
    }

    /// 一次「心跳」：轻到可以每半分钟跑一回；只有掷中的时候才会花时间。
    ///
    /// **并发调用只会真的跑一次**：另一次立刻返回「上一拍还没跑完」（不留副作用）。
    ///
    /// 每一次的结论都会写一行日志（`log`）：真机上「他从不主动」几乎都是某一拍被某道闸
    /// 挡掉了，而以前这件事完全不可见。日志形如：`[heartbeat] 没动 · 刚聊完，先安静一会儿`。
    public func beat(now: Int64? = nil, roll: Double? = nil) async -> Beat {
        let beat = await beatInternal(now: now, roll: roll)
        log("[heartbeat] \(beat.fired ? "起心动念" : "没动")"
            + (beat.why.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "" : " · \(beat.why)"))
        return beat
    }

    private func beatInternal(now: Int64? = nil, roll: Double? = nil) async -> Beat {
        guard beginBeat() else { return Beat(why: "上一拍还没跑完") }
        defer { endBeat() }
        // 服务端模式：他在服务端那边活着，本机这一拍什么都不做（连设置/库都不读）。
        if isServerMode() { return Beat(why: LocalAutonomy.serverModeWhy) }
        let at = now ?? self.now()
        let dice = roll ?? self.roll()
        let settings = settings.load()
        if !settings.agentLoopEnabled { return Beat(why: "常驻循环已关闭") }

        guard let persona = pickPersona(settings.activePersonaId) else {
            return Beat(why: "还没有可以对话的人格")
        }

        // 日记板的到点提醒（`spec/diary/README.md` §提醒）：**每拍都查**，而且必须在
        // 掷骰子之前 —— 提醒是「我自己写下的时间到了」，不该靠概率才被想起。
        // 睡着/在忙也照样记标记与念头（念头会不会变成消息，由自主循环的睡眠判定决定）。
        _ = checkDiaryReminders(persona: persona, now: at)

        // 0.14 拉黑：到期自动解除（读一次就清列），拉黑期内不主动开口。
        if let blockState = block?.state(personaId: persona.id, now: at) {
            let minutes = max(0, (blockState.until - at) / 60_000)
            return Beat(why: "他还在拉黑期（还剩 \(minutes) 分钟）")
        }

        // 他睡觉的时候不掷：既省电，也更像人。
        let minute = ServiceTime.minuteOfDay(at)
        if PersonaSleep.isAsleep(persona, minuteOfDay: minute) {
            return Beat(why: "他在睡觉")
        }

        // 0.14 心理活动：活跃窗口里才做（规范 §3）。这与「掷骰子」无关 ——
        // 他不需要先起心动念才允许心里想一句；想不想主动开口是另一条线。
        if let mentalActivityRunner,
           let session = (try? stores.sessions.session(forPersona: persona.id)) ?? nil,
           ActiveWindow.isActive(lastMessageAt: session.lastMessageAt ?? session.updatedAt, now: at) {
            await mentalActivityRunner(persona.id, session.id, at)
        }

        // 占用时间（`spec/persona-guidance/README.md` §4）：
        // - BUSY：这段不主动开口（对方先说话仍然会回，只是更短）；
        // - LIMITED：照常想，但概率打折（半忙不是「不想你」，只是「这会儿不方便」）。
        // 它与安静时段是两件事：睡觉 = 不被打扰，活动 = 我在做事；两者可以同时存在。
        let activity = PersonaActivity.decode(persona.activityJson, now: at)
        var activityFactor = 1.0
        switch PersonaActivity.proactiveGate(activity, now: at) {
        case .skip:
            return Beat(why: "他在忙")
        case .slowDown(let factor):
            activityFactor = factor
        case .allow:
            break
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
        // 主动程度不只改「每天最多几次」，也改「多久想一次」：1 冷淡、5 黏（与 Android 一致）。
        let perMinute = Heartbeat.probability(
            minutesSinceReset: minutes,
            mood: snapshot.mood,
            now: at,
            hourOfDay: ServiceTime.hourOfDay(at)
        ) * ProactiveLevel.heartbeatWeight(persona.proactiveLevel) * activityFactor
        let perBeat = Heartbeat.perBeatProbability(perMinute: perMinute, intervalMinutes: intervalMinutes())
        if !Heartbeat.shouldFire(probability: perBeat, roll: dice) { return Beat(why: "") }

        // 中了才算这些账：没中就不花查询去确认「今天说够了没」「额度还剩多少」。
        // 0.13 修复：「刚想过」以前看的是**所有人格里最近的一次** tick。
        // 换人格之后新人格会被上一个人格的思考记录挡住 5 分钟（真机上表现为「换人之后他不说话」）。
        // 现在只看这个人的最近一次：两个实现（SQLite / 内存）都支持按人格查。
        if let lastTick = ticks.latest(personaId: persona.id)?.at,
           at - lastTick < Self.firstTickGapMillis {
            return Beat(why: "刚想过，再等等")
        }
        // 每天最多主动几次，现在由人格的 `proactiveLevel` 决定（§一、§三）。
        // 0.11.0：基础值走 agent 决策层（远程失败回退本机 —— 心跳不能因为内核不可用停摆）。
        // 心情修正仍在本机：`MoodBehavior` 需要 label 驱动的 `MoodTuning`，客户端的
        // `proactiveCap(level:moodIntensity:)` 只有强度。
        // 0.11.1 审计 P2-1：同上 —— 严格模式的失败在 `decide` 里已经记成可见诊断并抛出，
        // 这里不再「假装什么都没发生」地静默回退（回退路径只给普通 remote 模式）。
        // iOS 上没有 Swift 回落（`AgentCoreNativeFallback.proactiveCap` 返回 nil），
        // 失败后仍然要一个 baseCap：用 Kotlin（与默认本机实现同一份 `:agentcore`）再算一次。
        let decidedCap = try? await agentCore.decide(
            endpoint: "proactive-cap",
            nativeFallback: AgentCoreNativeFallback.proactiveCap(
                level: persona.proactiveLevel, moodIntensity: snapshot.mood.intensity
            )
        ) {
            try await agentCore.proactiveCap(
                level: persona.proactiveLevel, moodIntensity: snapshot.mood.intensity
            )
        }
        let baseCap: Int
        if let decidedCap {
            baseCap = decidedCap
        } else {
            // `??` 的右侧是 autoclosure，不能在这里 await —— 失败路径必须显式写出来。
            baseCap = await AgentCoreNativeFallback.proactiveCapAfterStrictFailure(
                level: persona.proactiveLevel, moodIntensity: snapshot.mood.intensity
            )
        }
        let dailyCap = MoodBehavior.effectiveDailyCap(baseCap: baseCap, tuning: snapshot.tuning)
        if dailyCap <= 0 { return Beat(why: "主动消息关掉了") }
        let spoken = intents.spokenToday(personaId: persona.id, now: at)
        if spoken >= dailyCap { return Beat(why: "今天说够了（\(spoken)/\(dailyCap)）") }

        // 让他自己看着处境决定做什么。做什么都记在他自己的思考日志里。
        let outcome = await tickRunner(at)
        // 不管他做了什么、甚至出错了，这一轮都算「想过一回」：曲线从此刻重新走。
        let finishedAt = max(at, self.now())
        saveHeartbeatState(lastResetAt: finishedAt, lastFiredAt: finishedAt)
        cacheBeatSnapshot(snapshot, personaId: persona.id, at: finishedAt)
        return Beat(
            fired: true,
            acted: outcome?.acted ?? false,
            summary: outcome?.summary ?? "",
            why: outcome == nil ? "这一轮没想成" : ""
        )
    }

    // MARK: - 诊断（他为什么不主动）

    /// 现在挡在「主动开口」前面的是哪道闸（`ProactiveDiagnosis`）。
    ///
    /// 只读、不改任何状态（不推进时钟、不掷骰子、不写库），所以设置页可以随手调。
    /// 判定顺序与 `beatInternal` 一致：任何一处顺序变了，诊断就会说谎 —— 两条路径共用同一组常量。
    public func explain(now: Int64? = nil) -> ProactiveDiagnosis {
        let at = now ?? self.now()
        let settings = settings.load()
        let persona = pickPersona(settings.activePersonaId)
        let personaId = persona?.id ?? ""
        let spoken = persona.map { intents.spokenToday(personaId: $0.id, now: at) } ?? 0
        let cap = persona?.proactiveDailyCap ?? 0

        func diagnosis(_ gate: ProactiveDiagnosis.Gate, _ detail: String,
                       minutes: Double = 0, cooldownLeft: Double = 0,
                       activity: String? = nil) -> ProactiveDiagnosis {
            ProactiveDiagnosis(
                gate: gate, detail: detail,
                minutesSinceInteraction: minutes,
                cooldownMinutesLeft: cooldownLeft,
                spokenToday: spoken, dailyCap: cap, activity: activity
            )
        }

        guard settings.agentLoopEnabled else {
            return diagnosis(.loopDisabled, "设置里关掉了「让他常驻后台继续活着」")
        }
        guard let persona else {
            return diagnosis(.noPersona, "还没有可以对话的人格")
        }
        let minute = ServiceTime.minuteOfDay(at)
        if PersonaSleep.isAsleep(persona, minuteOfDay: minute) {
            let wake = PersonaSleep.minutesUntilWake(persona, minuteOfDay: minute)
            return diagnosis(.asleep, "作息窗口里：大约 \(wake) 分钟后醒")
        }
        let activity = PersonaActivity.decode(persona.activityJson, now: at)
        if case .skip = PersonaActivity.proactiveGate(activity, now: at) {
            return diagnosis(.busy, "在忙：\(activity?.activity ?? "")", activity: activity?.activity)
        }
        let snapshot = snapshotFor(persona: persona, settings: settings, now: at)
        let minutes = Heartbeat.minutesBetween(lastResetAt: snapshot.resetAt, now: at)
        if minutes < Heartbeat.COOL_DOWN_MINUTES {
            return diagnosis(
                .coolingDown,
                "距上次互动 \(Int(minutes)) 分钟，冷却 \(Int(Heartbeat.COOL_DOWN_MINUTES)) 分钟",
                minutes: minutes,
                cooldownLeft: Heartbeat.COOL_DOWN_MINUTES - minutes,
                activity: activity?.activity
            )
        }
        if let lastTick = ticks.latest(personaId: personaId)?.at,
           at - lastTick < Self.firstTickGapMillis {
            let wait = (Self.firstTickGapMillis - (at - lastTick)) / 60_000
            return diagnosis(.tooSoonAfterTick, "刚想过，再过 \(wait) 分钟才可能再想", minutes: minutes)
        }
        if spoken >= cap {
            return diagnosis(.dailyCapReached, "今天已经开口 \(spoken) 次（上限 \(cap)）", minutes: minutes)
        }
        let perMinute = Heartbeat.probability(
            minutesSinceReset: minutes,
            mood: snapshot.mood,
            now: at,
            hourOfDay: ServiceTime.hourOfDay(at)
        ) * ProactiveLevel.heartbeatWeight(persona.proactiveLevel)
        return diagnosis(
            .ready,
            "每分钟冲动概率约 \(Int((perMinute * 100).rounded()))%，今天 \(spoken)/\(cap)",
            minutes: minutes,
            activity: activity?.activity
        )
    }

    // MARK: - 日记板提醒

    /// 把到点的日记提醒变成**他自己的念头**，并写 `remindedAt` 防重复。
    ///
    /// 规则（`spec/diary/README.md` §提醒）：
    /// - 同一条提醒只触发一次：先 `markReminded`（条件更新 `remindedAt IS NULL`），
    ///   只有真的改到行的那一拍才继续生成念头 —— 两拍并发时也只会有一条；
    /// - 错过超过 24 小时不补发：只清标记（否则「明天记得问他到家没」会在三天后诈尸）；
    /// - 念头的窗口给 1 小时：到点这一刻他在忙/在睡，也可以晚一点再想起来。
    ///
    /// - Returns: 这一拍真的生成了几条念头（测试与日志用）。
    @discardableResult
    public func checkDiaryReminders(now: Int64? = nil) -> Int {
        let at = now ?? self.now()
        guard let persona = pickPersona(settings.load().activePersonaId) else { return 0 }
        return checkDiaryReminders(persona: persona, now: at)
    }

    /// 指定人格的版本（`beat()` 里已经选好人格，不必再读一次设置）。
    @discardableResult
    func checkDiaryReminders(persona: PersonaEntity, now: Int64) -> Int {
        let due: [DiaryNoteEntity]
        do {
            due = try stores.diaryNotes.due(personaId: persona.id, now: now)
        } catch {
            return 0
        }
        guard !due.isEmpty else { return 0 }
        var planned = 0
        for note in due {
            // 条件更新：只有「还没提醒过」的那一拍能拿到 true。
            guard (try? stores.diaryNotes.markReminded(id: note.id, at: now)) == true else { continue }
            // 错过太久：标记已经清了，不补念头。
            guard !note.isStale(now: now) else { continue }
            let window = AgentWindow(
                earliestAt: now,
                latestAt: now + 60 * 60_000,
                note: "日记提醒：\(String(note.content.prefix(120)))",
                hint: "这条是你自己之前写在日记板上的提醒。现在到点了：想说什么、要不要主动开口，你自己决定；"
                    + "不想说也可以只是想一下。"
            )
            intents.plan(
                personaId: persona.id,
                window: window,
                kind: .followUp,
                note: window.note,
                hint: window.hint,
                now: now
            )
            planned += 1
        }
        return planned
    }

    // MARK: - 单拍互斥

    private func beginBeat() -> Bool {
        beatLock.lock()
        defer { beatLock.unlock() }
        if beating { return false }
        beating = true
        return true
    }

    private func endBeat() {
        beatLock.lock()
        beating = false
        beatLock.unlock()
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

    /// 把「刚刚想过一回」写回快照缓存（0.11.0 审计：读写都必须在这把锁里）。
    ///
    /// 单独一个**同步**方法而不是在 `beat()` 里直接 lock：`beat()` 是 async，
    /// 在异步上下文里调 `NSLock.lock()` 会被 Swift 6 判成错误（阻塞线程）。
    /// 锁只圈住一次纯内存赋值，里面不 await。
    private func cacheBeatSnapshot(_ snapshot: BeatSnapshot, personaId: String, at: Int64) {
        lock.lock()
        // 旧快照的心情 / 分寸如果不是这次读的，保留它（与原来锁外的语义一致，只是变成原子操作）。
        cached = BeatSnapshot(
            at: at,
            personaId: personaId,
            resetAt: at,
            mood: cached?.mood ?? snapshot.mood,
            tuning: cached?.tuning ?? snapshot.tuning
        )
        lock.unlock()
    }

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
