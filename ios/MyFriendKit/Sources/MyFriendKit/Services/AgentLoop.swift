import Foundation

// 来源：app/src/main/java/com/myfriend/app/data/repo/AgentLoop.kt
//
// 他自己的「想一下」。
//
// 一次 tick = 看清处境 → 让模型决定要不要做点什么 → 执行 → 把这次思考记下来。
// 这里刻意不假设他一定要说话：默认答案是「什么都不做」，说话只是他能做的一件事。
//
// ## 谁负责什么
//
// - 模型只负责「决定」：一句心里话 + 若干动作（解析与归一化在 `AgentDecisionCodec`）；
// - 本类负责「把决定变成现实」：发消息、记事、留念头、改心情、排作息；
// - 时间、心情分寸、每日上限、对话是否正热，全在 Domain 与服务层的既有判定里，不在这里重写。
//
// ## 与 Android 的差别（都在报告里列了）
//
// 1. API 配置由调用方传进来（`config` / `model`）：iOS 的 Key 在 Keychain，
//    「解析成一次请求」是 App 层的活，服务层不碰 Keychain。
// 2. `persona_patch`：iOS 还没有 PersonaCard / PersonaRevision 的移植，所以默认**不生效**
//    （返回「想改自己但没改成功」）。App 层可以把 `personaPatchApplier` 接上真正的实现。
// 3. `browse`：上网抓取（IdleBrowsing）不在本次服务层范围，用可选回调注入；
//    没接时如实记一句「想上网看看，没看成」。
// 4. 通知（ProactiveNotifier）不在服务层：发出去的消息通过 `onMessageDelivered` 回调交给 App 层，
//    由它决定要不要打扰。
// 5. 同步（PersonaSync）尚未移植，所有 `markNativeSessionsDirty` 调用都没有搬。
// 6. `agent_ticks` 曾缺失，0.9.1 已补进 Schema（老库升级后自动建表）：留痕现在重启仍在。

public final class AgentLoop {

    /// 一次思考的结果：下次多久之后再来想、这次到底做了什么。
    public struct Outcome: Equatable, Sendable {
        public var nextDelayMinutes: Int
        public var acted: Bool
        public var summary: String

        public init(nextDelayMinutes: Int, acted: Bool, summary: String) {
            self.nextDelayMinutes = nextDelayMinutes
            self.acted = acted
            self.summary = summary
        }
    }

    /// 浏览一次的结果（App 层把 IdleBrowsing 包一层传进来）。
    public struct BrowseOutcome: Equatable, Sendable {
        public var succeeded: Bool
        public var message: String

        public init(succeeded: Bool, message: String = "") {
            self.succeeded = succeeded
            self.message = message
        }
    }

    public static let maxOutputTokens = 420
    public static let temperature = 0.9

    /// 什么都不做之后，隔多久再想一次（分钟，会再加随机）。
    static let idleMin = 6
    static let idleMax = 15
    /// 刚说完话之后，给他一点「说完就放下」的时间。
    static let afterSpeakMin = 25
    static let afterSpeakMax = 45
    /// 出错/没配置时退避得久一点，别把电和额度烧在失败上。
    static let backoffMinutes = 30

    private let stores: DatabaseStores
    private let settings: SettingsProviding
    private let completion: CompletionClient
    private let memoryRepository: MemoryRepository
    private let intents: AgentIntentSupport
    private let ticks: AgentTickStoring
    private let presence: PresenceRepository
    private let mood: MoodRepository
    private let now: () -> Int64
    private let random: () -> Double
    private let openSessionId: () -> String?
    private let personaPatchApplier: ((String, String, String) -> Bool)?
    private let browseOnce: ((String, Int64) -> BrowseOutcome?)?
    private let browsingDigest: (String) -> String?
    private let onMessageDelivered: ((MessageEntity, ChatSessionEntity) -> Void)?
    private let lock = NSLock()

    public init(
        stores: DatabaseStores,
        settings: SettingsProviding,
        completion: CompletionClient,
        memoryRepository: MemoryRepository,
        intents: AgentIntentSupport,
        ticks: AgentTickStoring,
        presence: PresenceRepository,
        mood: MoodRepository,
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        random: @escaping () -> Double = { Double.random(in: 0..<1) },
        openSessionId: @escaping () -> String? = { nil },
        personaPatchApplier: ((String, String, String) -> Bool)? = nil,
        browseOnce: ((String, Int64) -> BrowseOutcome?)? = nil,
        browsingDigest: @escaping (String) -> String? = { _ in nil },
        onMessageDelivered: ((MessageEntity, ChatSessionEntity) -> Void)? = nil
    ) {
        self.stores = stores
        self.settings = settings
        self.completion = completion
        self.memoryRepository = memoryRepository
        self.intents = intents
        self.ticks = ticks
        self.presence = presence
        self.mood = mood
        self.now = now
        self.random = random
        self.openSessionId = openSessionId
        self.personaPatchApplier = personaPatchApplier
        self.browseOnce = browseOnce
        self.browsingDigest = browsingDigest
        self.onMessageDelivered = onMessageDelivered
    }

    /// 最近一次思考的时间（任何人格）：服务刚被拉起时用来说明「是不是想得太近了」。
    public func lastTickAt() -> Int64? {
        ticks.lastTickAt()
    }

    /// 想一次。
    public func tick(config: CompletionConfig?, model: String, now: Int64? = nil) async -> Outcome {
        lock.lock()
        defer { lock.unlock() }
        let at = now ?? self.now()
        let settings = settings.load()
        if !settings.agentLoopEnabled {
            return Outcome(nextDelayMinutes: Self.idleMax, acted: false, summary: "常驻循环已关闭")
        }
        guard let persona = pickPersona(settings.activePersonaId) else {
            return Outcome(nextDelayMinutes: Self.backoffMinutes, acted: false, summary: "还没有可以对话的人格")
        }

        // 先把过期的念头作废，再把到点的节奏变成「想在某个窗口里找他」。
        intents.expireStale(now: at)
        intents.collapseDuplicates()
        intents.materializeDueRules(now: at)

        // 他睡觉的时候不思考：既省电，也更像人。
        let minute = ServiceTime.minuteOfDay(at)
        if PersonaSleep.isAsleep(persona, minuteOfDay: minute) {
            return Outcome(
                nextDelayMinutes: PersonaSleep.minutesUntilWake(persona, minuteOfDay: minute),
                acted: false,
                summary: "他在睡觉"
            )
        }

        let session = (try? stores.sessions.session(forPersona: persona.id)) ?? nil
        guard let config else {
            return Outcome(nextDelayMinutes: Self.backoffMinutes, acted: false, summary: "还没有可用的 API 配置")
        }

        let dueIntents = intents.due(now: at).filter { $0.personaId == persona.id }

        // 心情不只是措辞：它也决定他今天多久想一次、还愿不愿意开口、还想不想上网。
        let moodState = mood.current(personaId: persona.id, now: at)
        let tuning = MoodBehavior.from(moodState, now: at)
        // 每天最多主动几次 = 人格的 `proactiveLevel`（1→1 次 … 5→5 次），再叠心情修正。
        // 1.9 起没有「每天最多主动几次」这个设置了（§一）。
        let dailyCap = MoodBehavior.effectiveDailyCap(baseCap: persona.proactiveDailyCap, tuning: tuning)

        let contextText = buildContext(
            persona: persona, session: session, now: at, dailyLimit: dailyCap,
            dueIntents: dueIntents, tuning: tuning
        )
        let personaText = personaTextForPrompt(persona) + mood.promptLine(personaId: persona.id, now: at)

        let response: CompletionResult
        do {
            response = try await completion.complete(
                config: config,
                model: session.map { $0.model.isEmpty ? model : $0.model } ?? model,
                messages: [
                    .system(AgentPromptBuilder.system(personaText: personaText, context: contextText)),
                    .user(AgentPromptBuilder.user(now: at))
                ],
                params: CompletionParams(temperature: Self.temperature, maxOutputTokens: Self.maxOutputTokens),
                kind: .agent,
                context: UsageContext(personaId: persona.id, sessionId: session?.id)
            )
        } catch {
            return Outcome(
                nextDelayMinutes: Self.backoffMinutes,
                acted: false,
                summary: String("想的时候出错了：\(error.localizedDescription)".prefix(120))
            )
        }

        // 解析失败就回退成「什么都没做」——这是刻意的：一次坏 JSON 不该让他做任何事。
        guard let parsed = AgentDecisionCodec.parse(raw: response.text, now: at) else {
            return Outcome(nextDelayMinutes: Self.idleMax, acted: false, summary: "这次没想清楚")
        }
        let decision = AgentDecisionCodec.normalize(parsed, now: at)
        let execution = execute(
            decision: decision, persona: persona, session: session,
            dueIntents: dueIntents, now: at, tuning: tuning, dailyCap: dailyCap
        )
        // 心情也进这句内心记录：用户在「他最近在想什么」里能看到「今天他话少」。
        let summary = buildSummary(tuning: tuning, outcome: execution.outcome)

        ticks.insert(
            AgentTick(
                id: UUID().uuidString,
                personaId: persona.id,
                at: at,
                thought: String(decision.thought.prefix(400)),
                actionJson: AgentDecisionCodec.toJson(decision),
                outcome: summary,
                messageId: execution.messageId
            )
        )
        // 思考日志留最近 7 天就够，多了只是占地方。
        ticks.deleteOlderThan(at - 7 * 24 * 3_600_000)

        let baseDelay = execution.messageId != nil
            ? randomInt(Self.afterSpeakMin...Self.afterSpeakMax)
            : randomInt(Self.idleMin...Self.idleMax)
        return Outcome(
            nextDelayMinutes: MoodBehavior.adjustDelayMinutes(baseMinutes: baseDelay, tuning: tuning),
            acted: execution.acted,
            summary: summary
        )
    }

    /// 给界面/服务显示的一句话：他刚刚在想什么。
    public func latestThought(personaId: String) -> String? {
        ticks.latest(personaId: personaId)?.thought
    }

    // MARK: - 执行

    struct Execution {
        var outcome: String
        var acted: Bool
        var messageId: String?
    }

    private func execute(
        decision: AgentDecision,
        persona: PersonaEntity,
        session: ChatSessionEntity?,
        dueIntents: [AgentIntentEntity],
        now: Int64,
        tuning: MoodTuning,
        dailyCap: Int
    ) -> Execution {
        var notes: [String] = []
        var messageId: String?

        for action in decision.actions {
            switch action {
            case .nothing:
                notes.append("什么都没做")

            case .message(let text):
                if messageId != nil { continue }
                guard let session else {
                    notes.append("想说话但还没有会话")
                    continue
                }
                let hot = openSessionId() == session.id || intents.conversationHot(sessionId: session.id, now: now)
                // 每日上限只留这一个真源：把这一轮已经算好的（含心情修正的）dailyCap 通过
                // limitOverride 带进来。这里不再自查一遍——两处判断迟早会打架。
                let check = intents.canReachOut(
                    personaId: persona.id,
                    now: now,
                    isConversationHot: hot,
                    limitOverride: dailyCap
                )
                if !check.allowed {
                    notes.append("本想说话，忍住了（\(check.reason ?? "")）")
                    continue
                }
                let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmed.isEmpty { continue }
                if let saved = deliver(persona: persona, session: session, text: trimmed, now: now) {
                    messageId = saved.id
                    notes.append("发了一条消息")
                    if let intent = dueIntents.first {
                        intents.markSent(id: intent.id, at: now)
                    }
                    intents.planFollowUp(personaId: persona.id, sessionId: session.id, sentAt: now)
                } else {
                    notes.append("消息没发出去")
                }

            case .browse:
                // 没兴致的时候，他连网页都懒得刷：这不是被限制，是不想。
                if !MoodBehavior.wantsToBrowse(tuning) {
                    notes.append("今天不太想上网")
                    continue
                }
                if let browseOnce {
                    let result = browseOnce(persona.id, now)
                    notes.append(result?.succeeded == true
                        ? String(result?.message.prefix(60) ?? "自己上网看了看")
                        : "想上网看看，没看成")
                } else {
                    notes.append("想上网看看，没看成")
                }

            case .remember(let content, let drawer, let importance):
                let category: MemoryCategory
                switch drawer.uppercased() {
                case "EVENT": category = .event
                case "SELF": category = .other
                default: category = .fact
                }
                let ok = memoryRepository.addDetailed(
                    personaId: persona.id,
                    content: content,
                    category: category,
                    importance: importance,
                    drawer: drawer.uppercased(),
                    sourceQuote: "他自己想到的",
                    sourceSessionId: session?.id
                )
                notes.append((try? ok.get()) != nil ? "记下了一件事" : "想记但没记成")

            case .planIntent(let window):
                let id = intents.plan(
                    personaId: persona.id,
                    window: window,
                    kind: .reachOut,
                    note: window.note,
                    hint: window.hint,
                    sessionId: session?.id,
                    now: now
                )
                notes.append(id.isEmpty ? "念头没留下" : "给未来的自己留了个念头")

            case .mood(let label, let intensity, let reason):
                if let saved = mood.setMood(personaId: persona.id, label: label,
                                            intensity: intensity, reason: reason, now: now) {
                    notes.append("心情变成「\(saved.label)」")
                } else {
                    notes.append("想改心情但没记住")
                }

            case .quietHours(let sleep, let wake, let reason, let dnd):
                let ok = applyQuietHours(personaId: persona.id, sleep: sleep, wake: wake,
                                         reason: reason, dnd: dnd)
                if ok && dnd != nil {
                    notes.append("给自己排了作息，并把免打扰\(dnd == true ? "打开" : "关掉")了")
                } else {
                    notes.append(ok ? "给自己排了作息" : "作息没排上")
                }

            case .dnd(let enabled):
                notes.append(setDnd(personaId: persona.id, enabled: enabled)
                    ? (enabled ? "睡前开了免打扰" : "睡前不开免打扰了")
                    : "想改免打扰但没记住")

            case .personaPatch(let patchJson, let summary):
                let ok = personaPatchApplier?(persona.id, patchJson, summary) ?? false
                notes.append(ok ? "调整了自己的人设" : "想改自己但没改成功")
            }
        }

        if notes.isEmpty { notes.append("什么都没做") }
        let outcome = String(notes.joined(separator: "；").prefix(200))
        let acted = messageId != nil || notes.contains { $0 != "什么都没做" }
        return Execution(outcome: outcome, acted: acted, messageId: messageId)
    }

    /// 这次 tick 的一句话：心情的分寸在前，做了什么在后。
    func buildSummary(tuning: MoodTuning, outcome: String) -> String {
        let note = tuning.note.trimmingCharacters(in: .whitespacesAndNewlines)
        let text = note.isEmpty ? outcome : "\(note)；\(outcome)"
        return String(text.prefix(200))
    }

    // MARK: - 落库

    /// 把他开口说的一句写进库，并把会话的预览/时间/未读一起更新。
    ///
    /// 返回 nil 表示没写成（数据库异常）；调用方据此记「消息没发出去」。
    func deliver(
        persona: PersonaEntity,
        session: ChatSessionEntity,
        text: String,
        now: Int64
    ) -> MessageEntity? {
        let latest = (try? stores.messages.first(
            where: "sessionId = ?", [.text(session.id)], orderBy: "createdAt DESC, id DESC"
        )) ?? nil
        // 同一毫秒内不能有两条消息（时间戳是排序的唯一依据）。
        let stamp = now <= (latest?.createdAt ?? 0) ? (latest?.createdAt ?? 0) + 1 : now
        let message = MessageEntity(
            id: UUID().uuidString,
            sessionId: session.id,
            personaId: persona.id,
            role: .assistant,
            content: text,
            createdAt: stamp,
            updatedAt: stamp,
            status: .complete,
            model: session.model.isEmpty ? nil : session.model
        )
        do {
            try stores.database.inTransaction {
                try stores.messages.upsert(message)
                if let fresh = (try? stores.sessions.byId(session.id)) ?? nil {
                    var updated = fresh
                    updated.lastMessagePreview = String(text.replacingOccurrences(of: "\n", with: " ")
                        .trimmingCharacters(in: .whitespacesAndNewlines).prefix(100))
                    updated.lastMessageAt = stamp
                    updated.updatedAt = stamp
                    if openSessionId() != session.id {
                        updated.unreadCount = fresh.unreadCount + 1
                    }
                    try stores.sessions.upsert(updated)
                }
            }
        } catch {
            return nil
        }
        onMessageDelivered?(message, session)
        return message
    }

    /// 自己安排作息：写 persona 的两个列（人设卡里的「作息」一行目前还没有移植，
    /// 所以只落这两个与行为直接相关的列）。
    ///
    /// `dnd` 非空时顺带把「睡前开不开免打扰」也落库（§6.1：这个开关是模型改的，用户改不了）。
    /// 安排作息本身就意味着「他会主动睡觉」，所以顺手把 `sleepEnabled` 打开。
    private func applyQuietHours(personaId: String, sleep: Int, wake: Int,
                                reason: String, dnd: Bool? = nil) -> Bool {
        _ = reason
        guard var persona = (try? stores.personas.byId(personaId)) ?? nil else { return false }
        persona.quietSleepMinutes = ((sleep % 1440) + 1440) % 1440
        persona.quietWakeMinutes = ((wake % 1440) + 1440) % 1440
        persona.sleepEnabled = true
        if let dnd { persona.dndOnSleep = dnd }
        persona.updatedAt = now()
        return (try? stores.personas.upsert(persona)) != nil
    }

    /// 只改免打扰开关（不动作息）。
    private func setDnd(personaId: String, enabled: Bool) -> Bool {
        guard var persona = (try? stores.personas.byId(personaId)) ?? nil else { return false }
        persona.dndOnSleep = enabled
        persona.updatedAt = now()
        return (try? stores.personas.upsert(persona)) != nil
    }

    // MARK: - 处境

    func buildContext(
        persona: PersonaEntity,
        session: ChatSessionEntity?,
        now: Int64,
        dailyLimit: Int,
        dueIntents: [AgentIntentEntity],
        tuning: MoodTuning
    ) -> String {
        let hour = ServiceTime.hourOfDay(now)
        let greeting: String
        switch hour {
        case 5...10: greeting = "早上"
        case 11...13: greeting = "中午"
        case 14...17: greeting = "下午"
        case 18...22: greeting = "晚上"
        default: greeting = "深夜"
        }
        var text = "现在：\(ServiceTime.formatted(now))（\(greeting)）\n"
        if let hint = PersonaSleep.hint(persona) {
            text += "你的作息：\(hint)\n"
        }
        // 他得知道自己在什么光景里：刚醒、在发呆、一个人翻着东西……
        // 这不是系统状态，是他对自己此刻的感知；措辞用第二人称，别让他读成别人。
        let presence = self.presence.current(personaId: persona.id, now: now)
        if !presence.detail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            text += "你现在：\(presence.detail)\n"
        }
        let spoken = intents.spokenToday(personaId: persona.id, now: now)
        text += "今天你已经主动开口 \(spoken) 次（上限 \(dailyLimit)）\n"
        // 心情带来的分寸也写进处境：他「今天话少」是看得见的，而不是一个隐藏参数。
        if !tuning.note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            text += "你今天的状态：\(tuning.note)\n"
        }

        if let session {
            let messages = (try? stores.messages.bySession(session.id)) ?? []
            let lastUser = messages.last { $0.role == .user && !$0.content.isEmpty }
            let lastAssistant = messages.last { $0.role == .assistant && !$0.content.isEmpty }
            if let lastUser {
                text += "对方上次说话：\(ServiceTime.timeAgo(lastUser.createdAt, now: now))，"
                text += "「\(String(lastUser.content.replacingOccurrences(of: "\n", with: " ").prefix(60)))」\n"
            }
            if let lastAssistant {
                text += "你上次说话：\(ServiceTime.timeAgo(lastAssistant.createdAt, now: now))，"
                text += "「\(String(lastAssistant.content.replacingOccurrences(of: "\n", with: " ").prefix(60)))」\n"
            }
            let recent = messages
                .filter { ($0.role == .user || $0.role == .assistant) && !$0.content.isEmpty }
                .suffix(6)
            if !recent.isEmpty {
                text += "最近几句：\n"
                for message in recent {
                    let who = message.role == .user ? "对方" : "你"
                    text += "- \(who)：\(String(message.content.replacingOccurrences(of: "\n", with: " ").prefix(80)))\n"
                }
            }
        } else {
            text += "你们还没有对话记录。\n"
        }

        if !dueIntents.isEmpty {
            text += "你之前留下的念头（现在正好在窗口里）：\n"
            for intent in dueIntents.prefix(3) {
                let note = intent.note.trimmingCharacters(in: .whitespacesAndNewlines)
                var line = "- \(formatWindow(intent))：\(note.isEmpty ? "想找他说句话" : note)"
                if !intent.hint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    line += "（\(intent.hint)）"
                }
                text += line + "\n"
            }
        } else {
            text += "你现在没有特别挂着的念头。\n"
        }

        if let digest = browsingDigest(persona.id) {
            let trimmed = digest.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                text += "你最近一个人看到的：\n\(String(trimmed.prefix(500)))\n"
            }
        }
        // 想不想看、想不想讲，都是他此刻的状态，不是能力问题。
        if !MoodBehavior.wantsToBrowse(tuning) {
            text += "今天没什么上网看看的兴致，刷到什么都不太提得起劲。\n"
        } else if !MoodBehavior.wantsToShare(tuning) {
            text += "看到的东西你现在不太想主动讲给他听，除非他正好问到。\n"
        }

        let memories = memoryRepository.getRelevant(personaId: persona.id, query: "", limit: 8)
        if !memories.isEmpty {
            text += "你记得的几件事：\n"
            for memory in memories.prefix(6) {
                text += "- \(String(memory.content.prefix(50)))\n"
            }
        }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func formatWindow(_ intent: AgentIntentEntity) -> String {
        "\(ServiceTime.clockText(minutesFromMillis(intent.earliestAt)))-"
            + "\(ServiceTime.clockText(minutesFromMillis(intent.latestAt)))"
    }

    private func minutesFromMillis(_ millis: Int64) -> Int {
        ServiceTime.minuteOfDay(millis)
    }

    /// 人设渲染：与聊天共用同一份「他是谁」（`PromptBuilder.personaProfileBody`）。
    ///
    /// 为什么不再各写一份：1.9 给「他是谁」加了五个结构化字段，
    /// 如果聊天里带、他自己想事情时不带，他会**在独处时忘记自己是什么样的人**，
    /// 于是主动开口的语气和聊天里的对不上——这正是两端最容易分叉的地方。
    func personaTextForPrompt(_ persona: PersonaEntity) -> String {
        let body = PromptBuilder.personaProfileBody(persona)
        return body.isEmpty ? "（还没有设定人设，就按你自己的性格来）" : body
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

    private func randomInt(_ range: ClosedRange<Int>) -> Int {
        let width = range.upperBound - range.lowerBound + 1
        let value = Int(Double(width) * random())
        return min(max(range.lowerBound + value, range.lowerBound), range.upperBound)
    }
}
