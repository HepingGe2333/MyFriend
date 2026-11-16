import Foundation

// 来源：app/src/main/java/com/yap/app/data/repo/AgentIntentRepository.kt（只取服务层用得到的那一半）
//
// 「他想什么时候找你」这件事的唯一真相来源。
//
// 规则（proactive_rules）只是节奏偏好，到点后会在这里变成一条带**时间窗口**的念头；
// 发不发、几点发，由自主循环在窗口里自己决定。这样他就不会像闹钟一样每次都在 22:30:00 出现。
//
// 为什么单独一个文件而不是塞进 AgentLoop：
// 心跳要问「今天说够了没 / 刚说过没有」，AgentLoop 要问「到点的念头 / 能不能开口」，
// Presence 要问「半小时内有没有挂着的念头」。三处共用同一套判定，也就共用同一份克制。
//
// 与 Android 的差别：
// - 没有 Flow（iOS 服务层拉取式）；`materializeDueRules` 的随机源由 `random` 闭包注入，
//   测试里能固定「今天就是不想说话」的分支；
// - `ProactiveNotifier`（通知）不在服务层，开口之后由 App 层决定要不要打扰。

/// 现在能不能主动开口的判定结果。
public struct ReachOutCheck: Equatable, Sendable {
    public var allowed: Bool
    /// 不允许时的一句中文原因（能念给他自己听，也能进日志）。
    public var reason: String?

    public init(allowed: Bool, reason: String? = nil) {
        self.allowed = allowed
        self.reason = reason
    }
}

public final class AgentIntentSupport {

    /// 规则到点后，意向窗口从这里开始往后散开的最大分钟数。
    static let scatterMaxMinutes = 90
    static let windowMinMinutes = 30
    static let windowMaxMinutes = 120
    /// 今天就是不想说话的概率。
    static let skipChance = 0.18
    /// 两条主动消息之间的最小间隔。
    public static let minGapMillis: Int64 = 90 * 60_000
    /// 追问：多久之后、多大几率、窗口多长。
    static let followUpMinMinutes = 25
    static let followUpMaxMinutes = 70
    static let followUpWindowMinutes = 20
    static let followUpChance = 0.45
    /// 已发但对方一直没回，之后的意向窗口往后推的分钟数。
    static let ignoredPushBackMinutes = 180

    private let stores: DatabaseStores
    private let ticks: AgentTickStoring
    private let now: () -> Int64
    private let random: () -> Double

    public init(
        stores: DatabaseStores,
        ticks: AgentTickStoring,
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        random: @escaping () -> Double = { Double.random(in: 0..<1) }
    ) {
        self.stores = stores
        self.ticks = ticks
        self.now = now
        self.random = random
    }

    // MARK: - 读

    public func pending(personaId: String) -> [AgentIntentEntity] {
        (try? stores.agentIntents.fetch(
            where: "personaId = ? AND status = 'PENDING'",
            [.text(personaId)],
            orderBy: "earliestAt ASC"
        )) ?? []
    }

    public func pendingCount(personaId: String) -> Int {
        (try? stores.agentIntents.count(where: "personaId = ? AND status = 'PENDING'", [.text(personaId)])) ?? 0
    }

    /// 窗口已经打开（或已经过期但还没发）的念头。
    public func due(now: Int64? = nil) -> [AgentIntentEntity] {
        let at = now ?? self.now()
        return (try? stores.agentIntents.fetch(
            where: "status = 'PENDING' AND earliestAt <= ?",
            [.int(at)],
            orderBy: "earliestAt ASC"
        )) ?? []
    }

    /// 把窗口已经过去的念头作废，返回条数。
    @discardableResult
    public func expireStale(now: Int64? = nil) -> Int {
        let at = now ?? self.now()
        let stale = (try? stores.agentIntents.fetch(
            where: "status = 'PENDING' AND latestAt < ?", [.int(at)]
        )) ?? []
        for intent in stale {
            mark(intent.id, status: .expired, sentAt: nil, error: nil)
        }
        return stale.count
    }

    /// 同一件事只想留一个念头：按「同一个人格 + 同一个动作 + 同一句备注」归并，
    /// 只留最早的那条，其余标成已放弃。返回丢掉的条数。
    @discardableResult
    public func collapseDuplicates() -> Int {
        let pending = (try? stores.agentIntents.fetch(
            where: "status = 'PENDING' AND note != ''", [], orderBy: "createdAt ASC"
        )) ?? []
        if pending.count < 2 { return 0 }
        var groups: [String: [AgentIntentEntity]] = [:]
        for intent in pending {
            let key = "\(intent.personaId)|\(intent.kind.stored)|\(intent.note.trimmingCharacters(in: .whitespacesAndNewlines))"
            groups[key, default: []].append(intent)
        }
        var dropped = 0
        for group in groups.values where group.count > 1 {
            let sorted = group.sorted { a, b in a.createdAt == b.createdAt ? a.id < b.id : a.createdAt < b.createdAt }
            for extra in sorted.dropFirst() {
                mark(extra.id, status: .dropped, sentAt: nil, error: "重复的念头，已经并成一条")
                dropped += 1
            }
        }
        return dropped
    }

    // MARK: - 写

    /// 留一个念头：同一句话重复想起时只把窗口往后挪，不再堆一条。
    @discardableResult
    public func plan(
        personaId: String,
        window: AgentWindow,
        kind: AgentIntentKind = .reachOut,
        note: String = "",
        hint: String = "",
        ruleId: String? = nil,
        sessionId: String? = nil,
        now: Int64? = nil
    ) -> String {
        let at = now ?? self.now()
        let earliest = max(window.earliestAt, at)
        let latest = max(window.latestAt, earliest + 15 * 60_000)
        let cleanNote = String(note.prefix(200))
        let trimNote = cleanNote.trimmingCharacters(in: .whitespacesAndNewlines)

        if !trimNote.isEmpty {
            let existing = pending(personaId: personaId).first {
                $0.kind == kind
                    && $0.note.trimmingCharacters(in: .whitespacesAndNewlines) == trimNote
                    && $0.ruleId == ruleId
            }
            if var existing {
                existing.earliestAt = earliest
                existing.latestAt = latest
                existing.hint = String(hint.prefix(600))
                if let sessionId { existing.sessionId = sessionId }
                _ = try? stores.agentIntents.upsert(existing)
                return existing.id
            }
        }

        let intent = AgentIntentEntity(
            id: UUID().uuidString,
            personaId: personaId,
            sessionId: sessionId,
            kind: kind,
            earliestAt: earliest,
            latestAt: latest,
            note: cleanNote,
            hint: String(hint.prefix(600)),
            status: .pending,
            ruleId: ruleId,
            createdAt: at
        )
        _ = try? stores.agentIntents.upsert(intent)
        return intent.id
    }

    public func markSent(id: String, at: Int64) {
        mark(id, status: .sent, sentAt: at, error: nil)
    }

    public func markDropped(id: String, reason: String) {
        mark(id, status: .dropped, sentAt: nil, error: String(reason.prefix(200)))
    }

    public func deleteForPersona(_ personaId: String) {
        _ = try? stores.agentIntents.delete(where: "personaId = ?", [.text(personaId)])
    }

    // MARK: - 规则 → 念头

    /// 把「到点的规则」变成念头。
    ///
    /// - 时间不再准点：在意向窗口里前后来回散，最多往后推 90 分钟；
    /// - 有 18% 的概率今天就是不想说，直接把规则推到下一次；
    /// - 最近主动过但一直没回音，窗口整体往后推。
    @discardableResult
    public func materializeDueRules(now: Int64? = nil) -> Int {
        let at = now ?? self.now()
        let dueRules = (try? stores.proactiveRules.fetch(
            where: "enabled = 1 AND nextTriggerAt > 0 AND nextTriggerAt <= ?",
            [.int(at)],
            orderBy: "nextTriggerAt ASC"
        )) ?? []
        if dueRules.isEmpty { return 0 }
        var created = 0
        for var rule in dueRules {
            let persona = try? stores.personas.byId(rule.personaId)
            let next = nextTriggerFor(
                kind: rule.kind,
                timeOfDayMinutes: jitteredMinutes(rule.kind, rule.timeOfDayMinutes),
                intervalMinutes: jitteredInterval(rule.kind, rule.intervalMinutes),
                from: at
            )
            let skip = rule.enabled && !rule.pending && random() < Self.skipChance
            if skip {
                rule.nextTriggerAt = next
                rule.lastTriggeredAt = at
                rule.lastError = nil
                rule.updatedAt = self.now()
                _ = try? stores.proactiveRules.upsert(rule)
                continue
            }
            if rule.enabled && !rule.pending {
                let earliest = at + Int64(int(5...Self.scatterMaxMinutes)) * 60_000
                let span = Int64(int(Self.windowMinMinutes...Self.windowMaxMinutes)) * 60_000
                let pushed = ignoredPushBack(personaId: persona?.id ?? rule.personaId, now: at)
                let window = AgentWindow(
                    earliestAt: earliest + pushed,
                    latestAt: earliest + span + pushed,
                    note: rule.note,
                    hint: rule.hint
                )
                plan(
                    personaId: rule.personaId,
                    window: window,
                    kind: .reachOut,
                    note: rule.note,
                    hint: rule.hint,
                    ruleId: rule.id,
                    sessionId: rule.sessionId,
                    now: at
                )
                created += 1
            }
            rule.nextTriggerAt = next
            rule.lastTriggeredAt = at
            rule.lastError = nil
            rule.updatedAt = self.now()
            _ = try? stores.proactiveRules.upsert(rule)
        }
        return created
    }

    // MARK: - 克制

    /// 一天里已经主动说了几次（上限由人格的 `proactiveLevel` 决定）。
    public func spokenToday(personaId: String, now: Int64? = nil) -> Int {
        let at = now ?? self.now()
        return ticks.spokenCount(personaId: personaId, since: ServiceTime.startOfDay(at))
    }

    public func lastSpokenAt(personaId: String) -> Int64? {
        ticks.lastSpokenAt(personaId: personaId)
    }

    /// 现在能不能主动开口：作息、频率、间隔、对话是否正热。
    ///
    /// 每日上限只有这一处判定。`limitOverride` 是给 AgentLoop / 心跳把自己已经算好的上限
    /// （含心情修正）带进来的；不传就回落到人格的 `proactiveLevel`。
    /// 1.9 起没有「每天最多主动几次」这个设置了（§一）。
    public func canReachOut(
        personaId: String,
        now: Int64? = nil,
        isConversationHot: Bool,
        limitOverride: Int? = nil
    ) -> ReachOutCheck {
        let at = now ?? self.now()
        guard let persona = try? stores.personas.byId(personaId) else {
            return ReachOutCheck(allowed: false, reason: "人格不存在")
        }
        // 带了上限就别再读一遍人格：同一轮里上限只能有一个数。
        let limit = limitOverride ?? persona.proactiveDailyCap
        if limit <= 0 { return ReachOutCheck(allowed: false, reason: "他不主动找你") }
        let spoken = spokenToday(personaId: personaId, now: at)
        if spoken >= limit { return ReachOutCheck(allowed: false, reason: "今天已经主动 \(spoken) 次，够了") }
        let minute = ServiceTime.minuteOfDay(at)
        if PersonaSleep.isAsleep(persona, minuteOfDay: minute) {
            return ReachOutCheck(allowed: false, reason: "他这个点在睡觉")
        }
        if let last = lastSpokenAt(personaId: personaId), at - last < Self.minGapMillis {
            return ReachOutCheck(allowed: false, reason: "刚说完没多久，再等等")
        }
        if isConversationHot {
            return ReachOutCheck(allowed: false, reason: "你们正聊着，不用他额外开口")
        }
        return ReachOutCheck(allowed: true)
    }

    /// 会话是不是正热：他刚发过、对方刚回。
    public func conversationHot(sessionId: String?, now: Int64? = nil) -> Bool {
        guard let sessionId else { return false }
        let at = now ?? self.now()
        guard let latest = (try? stores.messages.first(
            where: "sessionId = ?", [.text(sessionId)], orderBy: "createdAt DESC, id DESC"
        )) ?? nil else { return false }
        let age = at - latest.createdAt
        switch latest.role {
        case .user: return age < 5 * 60_000
        case .assistant: return age < 60_000
        case .system: return false
        }
    }

    /// 他刚主动说了一句、对方还没回：有一定概率过一会儿轻轻追一句。只排一次。
    @discardableResult
    public func planFollowUp(personaId: String, sessionId: String?, sentAt: Int64) -> String? {
        if random() >= Self.followUpChance { return nil }
        if pending(personaId: personaId).contains(where: { $0.kind == .followUp }) { return nil }
        let start = sentAt + Int64(int(Self.followUpMinMinutes...Self.followUpMaxMinutes)) * 60_000
        let window = AgentWindow(
            earliestAt: start,
            latestAt: start + Int64(Self.followUpWindowMinutes) * 60_000,
            note: "他刚才说的还没回音",
            hint: "不要催，不要问「在吗」，就像忽然又想起这件事，轻轻补一句"
        )
        return plan(
            personaId: personaId,
            window: window,
            kind: .followUp,
            note: window.note,
            hint: window.hint,
            sessionId: sessionId,
            now: sentAt
        )
    }

    /// 对方说话了：把还没发出去的追问作废。
    public func dropFollowUps(personaId: String) {
        for intent in pending(personaId: personaId) where intent.kind == .followUp {
            markDropped(id: intent.id, reason: "对方已经回来了")
        }
    }

    /// 在窗口里挑一个「像人」的时刻回来发：偏向窗口前半段，但不会准点。
    public func pickMoment(_ intent: AgentIntentEntity) -> Int64 {
        let span = max(intent.latestAt - intent.earliestAt, 15 * 60_000)
        let ratio = 0.15 + random() * 0.7
        return intent.earliestAt + Int64(Double(span) * ratio)
    }

    // MARK: - 内部

    private func mark(_ id: String, status: AgentIntentStatus, sentAt: Int64?, error: String?) {
        guard var intent = (try? stores.agentIntents.byId(id)) ?? nil else { return }
        intent.status = status
        intent.sentAt = sentAt
        intent.lastError = error
        _ = try? stores.agentIntents.upsert(intent)
    }

    /// 0..<1 的随机数 → [range] 里的整数（与 Kotlin 的 `Random.nextInt(from, until)` 同义）。
    private func int(_ range: ClosedRange<Int>) -> Int {
        let width = range.upperBound - range.lowerBound + 1
        let value = Int(Double(width) * random())
        return min(max(range.lowerBound + value, range.lowerBound), range.upperBound)
    }

    private func jitteredMinutes(_ kind: ProactiveKind, _ timeOfDayMinutes: Int?) -> Int? {
        if kind != .daily { return timeOfDayMinutes }
        let base = timeOfDayMinutes ?? 20 * 60
        // 每天浮动 ±25 分钟：人不会在同一个分钟数上出现。
        return min(max(base + int(-25...25), 0), 1439)
    }

    private func jitteredInterval(_ kind: ProactiveKind, _ intervalMinutes: Int?) -> Int? {
        if kind != .interval { return intervalMinutes }
        let base = max(intervalMinutes ?? 180, 15)
        let factor = 0.75 + random() * 0.6   // 0.75x ~ 1.35x
        return max(Int(Double(base) * factor), 15)
    }

    /// 上次主动之后对方一直没说话：这次把念头窗口整体往后挪，越没人理越安静。
    private func ignoredPushBack(personaId: String, now: Int64) -> Int64 {
        guard let lastSpoken = ticks.lastSpokenAt(personaId: personaId) else { return 0 }
        let lastUser = latestUserMessageAt(personaId: personaId)
        let userReplied = lastUser != nil && lastUser! > lastSpoken
        if userReplied { return 0 }
        // 至少沉默了 4 小时才算「被忽略」，之后每次多推 3 小时。
        let silenceHours = (now - lastSpoken) / 3_600_000
        if silenceHours < 4 { return 0 }
        return Int64(Self.ignoredPushBackMinutes) * 60_000
    }

    private func latestUserMessageAt(personaId: String) -> Int64? {
        let sessions = (try? stores.sessions.byPersona(personaId)) ?? []
        guard let session = sessions.max(by: {
            ($0.lastMessageAt ?? $0.createdAt) < ($1.lastMessageAt ?? $1.createdAt)
        }) else { return nil }
        let message = (try? stores.messages.first(
            where: "sessionId = ? AND role = 'USER'", [.text(session.id)], orderBy: "createdAt DESC"
        )) ?? nil
        return message?.createdAt
    }

    /// 下一次大概什么时候：daily 按 HH:mm（已加过抖动），interval 按小时数（已加过抖动）。
    private func nextTriggerFor(kind: ProactiveKind, timeOfDayMinutes: Int?, intervalMinutes: Int?, from: Int64) -> Int64 {
        switch kind {
        case .daily:
            let minutes = timeOfDayMinutes ?? 20 * 60
            let calendar = ServiceTime.calendar()
            let date = Date(timeIntervalSince1970: Double(from) / 1000.0)
            let start = calendar.startOfDay(for: date)
            var target = start.addingTimeInterval(TimeInterval(minutes * 60))
            if target <= date {
                target = calendar.date(byAdding: .day, value: 1, to: target) ?? target.addingTimeInterval(86_400)
            }
            return Int64((target.timeIntervalSince1970 * 1000.0).rounded())
        case .interval:
            return from + Int64(max(intervalMinutes ?? 180, 15)) * 60_000
        }
    }
}
