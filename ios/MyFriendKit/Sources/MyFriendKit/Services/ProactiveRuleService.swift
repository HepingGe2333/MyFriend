import Foundation

// 来源：app/src/main/java/com/myfriend/app/data/repo/ProactiveProtocol.kt
//       + app/src/main/java/com/myfriend/app/data/repo/ProactiveRepository.kt（只管「落库」这一半）
//
// 「主动唤醒」的对话协议 + 规则落库。
//
// ## 协议
//
// 客户端在系统提示词里说明能力，模型如果想建立主动联系的节奏，就输出一个 myfriend-wake 代码块：
//
// ```myfriend-wake
// {"kind":"daily","time":"22:30","note":"每晚睡前问候","hint":"轻声问对方今天过得怎么样"}
// ```
//
// 客户端解析后从正文里去掉代码块，规则**直接生效**（1.3 起不再等用户点头）；
// 从此 `AgentIntentSupport.materializeDueRules` 会在到点前后把它变成一条带时间窗口的念头，
// 有心跳 / 自主循环决定要不要开口。
//
// ## 为什么要单独一个文件
//
// 协议解析（纯文本）与落库（读库、算下一次触发）是两件事，但都属于「主动唤醒」这条链路：
// 放在一处，测试可以直接构造提案，不用起聊天。
//
// ## 与 Android 的差异（都在报告里）
//
// - Android 的 `ProactiveRepository` 还要做「到点用模型生成一条真实消息并弹通知」，那部分在 iOS
//   由 `AgentLoop` + App 层通知负责（`AgentLoop.onMessageDelivered`），这里只负责规则本身；
// - Android 用 WorkManager 排下一次唤醒；iOS 没有常驻后台，靠 `BGAppRefreshTask` 与前台心跳，
//   所以这里只算 `nextTriggerAt`（谁去触发不归它管）；
// - `pending` 标记：Android 用它表示「刚安排，聊天页要提示一次」。iOS 的 `materializeDueRules`
//   把 `pending = true` 理解成「这一轮先不触发」，置 true 会让刚建好的规则首轮被跳过，
//   所以这里建规则时 `pending = false`（列保留、备份互通不受影响）。

/// 一条「主动唤醒」提案：模型在回复里提议的节奏。
public struct ProactiveProposal: Equatable, Sendable {
    /// 每天固定时刻，或每隔一段时间。
    public var kind: ProactiveKind
    /// daily 时为「距零点分钟数」。
    public var timeOfDayMinutes: Int?
    /// interval 时为间隔分钟数。
    public var intervalMinutes: Int?
    /// 一句话说明这条规则（展示在规则列表里）。
    public var note: String
    /// 到点时要怎么写这条消息的指引。
    public var hint: String

    public init(
        kind: ProactiveKind,
        timeOfDayMinutes: Int? = nil,
        intervalMinutes: Int? = nil,
        note: String = "",
        hint: String = ""
    ) {
        self.kind = kind
        self.timeOfDayMinutes = timeOfDayMinutes
        self.intervalMinutes = intervalMinutes
        self.note = note
        self.hint = hint
    }
}

public enum ProactiveProtocol {

    /// 给模型看的代码块语言。与 Android 的 `BLOCK_LANGUAGE` 逐字一致。
    public static let blockLanguage = "myfriend-wake"

    /// 早期 iOS 版本提示词里写的是这个名字（已没有模型会用，但旧会话里可能还留着）。
    public static let legacyBlockLanguage = "myfriend-proactive"

    /// 系统提示词里的「你可以主动联系对方」那一段。与 Android 的 `CAPABILITY_PROMPT` 逐字一致。
    public static let capabilityPrompt: String = {
        var lines: [String] = [
            "## 你可以主动联系对方",
            "你可以为这段关系设定「主动唤醒」节奏：到了设定时间，应用会替你主动开口，这条消息由你按人格与本段对话的风格写出来。",
            "当对方希望你有规律地主动找他时（例如「每天晚上十点半来问我一句」「每隔几小时来陪我一下」），在回复末尾追加一个代码块，格式如下：",
            "",
            "```\(blockLanguage)",
            #"{"kind":"daily","time":"22:30","note":"每晚睡前问候","hint":"像往常一样轻声问对方今天过得怎么样"}"#,
            "```",
            "",
            "字段说明：",
            "- kind：daily（每天固定时刻）或 interval（每隔一段时间）",
            "- time：daily 时必填，24 小时制 HH:mm",
            "- intervalHours：interval 时必填，1-24 的整数小时",
            "- note：一句话说明这条规则，会展示在规则列表里",
            "- hint：到点时要怎么写这条消息的指引，用对你人格口吻的描述",
            "",
            "这个设定**不需要对方批准，会直接生效**，之后你会按这个节奏自己来找他；所以只在他明确表达过这种意愿时设定一次，别自作主张。",
            "时间不用精确：应用会在这个时刻前后浮动一段时间，你出现得更像人而不是闹钟。",
            "不要在普通闲聊里添加这个代码块，也不要承诺代码块以外的定时能力。"
        ]
        return lines.joined(separator: "\n")
    }()

    // MARK: - 解析

    /// 从模型输出里取出提案，同时把代码块从正文里去掉。
    ///
    /// - Returns: `body` 是可以给用户看的正文；`proposal` 解析成功时才有；
    ///   `payloadJson` 是被剥出来的原始 JSON（坏块只从正文里去掉，不递出去）。
    public static func extract(_ rawText: String) -> (body: String, proposal: ProactiveProposal?, payloadJson: String?) {
        let raw = rawText
        guard !raw.isEmpty else { return ("", nil, nil) }
        guard let found = firstBlock(in: raw) else {
            return (raw.trimmingCharacters(in: .whitespacesAndNewlines), nil, nil)
        }
        let payload = found.payload.trimmingCharacters(in: .whitespacesAndNewlines)
        var cleaned = String(raw[raw.startIndex..<found.range.lowerBound])
        cleaned += String(raw[found.range.upperBound...])
        let body = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
        let proposal = parse(payload)
        // 提案必须是能解析的 JSON 对象；坏块只从正文里去掉，不递出去。
        let payloadJson: String?
        if !payload.isEmpty,
           let data = payload.data(using: .utf8),
           (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] != nil {
            payloadJson = payload
        } else {
            payloadJson = nil
        }
        return (body, proposal, payloadJson)
    }

    /// 解析提案 JSON。kind 只认 daily（含别名）与 interval（含别名），坏数据返回 nil。
    public static func parse(_ payload: String) -> ProactiveProposal? {
        guard let data = payload.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return nil
        }
        let kindText = string(root, "kind")?.lowercased() ?? ""
        let note = String((string(root, "note") ?? "").prefix(120))
        let hint = String((string(root, "hint") ?? "").prefix(600))
        let defaultNote = "主动开口，用你的方式关心对方此刻的状态。"
        switch kindText {
        case "daily", "daily_time", "daily_time_of_day", "once":
            // 1.9 语义里 kind 只有 daily / interval；模型偶尔写 once（「就这一次」），
            // 按「每天这个点」处理比直接丢掉更接近他的本意（多一条规则一眼就能看见并关掉）。
            guard let minutes = parseTimeOfDay(string(root, "time")) else { return nil }
            return ProactiveProposal(
                kind: .daily,
                timeOfDayMinutes: minutes,
                intervalMinutes: nil,
                note: note.isEmpty ? "每天 \(formatTimeOfDay(minutes)) 主动问候" : note,
                hint: hint.isEmpty ? defaultNote : hint
            )
        case "interval", "every", "interval_hours":
            guard let hours = int(root, "intervalHours") ?? int(root, "interval_hours"),
                  (1...24).contains(hours) else { return nil }
            return ProactiveProposal(
                kind: .interval,
                timeOfDayMinutes: nil,
                intervalMinutes: hours * 60,
                note: note.isEmpty ? "每 \(hours) 小时主动关心一次" : note,
                hint: hint.isEmpty ? defaultNote : hint
            )
        default:
            return nil
        }
    }

    /// `HH:mm` → 距零点分钟数。认全角冒号、去空白；格式不对返回 nil（不猜）。
    public static func parseTimeOfDay(_ raw: String?) -> Int? {
        guard let raw else { return nil }
        let cleaned = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "：", with: ":")
        if cleaned.isEmpty { return nil }
        let parts = cleaned.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        guard let hour = Int(parts[0].trimmingCharacters(in: .whitespaces)),
              let minute = Int(parts[1].trimmingCharacters(in: .whitespaces)) else { return nil }
        guard (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        return hour * 60 + minute
    }

    /// 距零点分钟数 → `HH:mm`（负数与超过一天的值都折回 0..1439）。
    public static func formatTimeOfDay(_ minutes: Int) -> String {
        let dayMinutes = 24 * 60
        let safe = ((minutes % dayMinutes) + dayMinutes) % dayMinutes
        return String(format: "%02d:%02d", safe / 60, safe % 60)
    }

    // MARK: - 内部

    /// 找一个闭合的协议块；两种语言标记都认（新的是 myfriend-wake，旧的是 myfriend-proactive）。
    static func firstBlock(in text: String) -> (range: Range<String.Index>, payload: String)? {
        let candidates = [blockLanguage, legacyBlockLanguage]
        for language in candidates {
            let marker = "```\(language)"
            var searchStart = text.startIndex
            while let start = text.range(of: marker, options: [.caseInsensitive], range: searchStart..<text.endIndex) {
                let afterMarker = start.upperBound
                if let end = text.range(of: "```", range: afterMarker..<text.endIndex) {
                    let payload = String(text[afterMarker..<end.lowerBound])
                    return (start.lowerBound..<end.upperBound, payload)
                }
                // 没闭合：从下一个可能的标记继续找。
                searchStart = afterMarker
            }
        }
        return nil
    }

    static func string(_ object: [String: Any], _ key: String) -> String? {
        if let text = object[key] as? String { return text }
        if let number = object[key] as? NSNumber { return number.stringValue }
        return nil
    }

    static func int(_ object: [String: Any], _ key: String) -> Int? {
        if let number = object[key] as? NSNumber { return number.intValue }
        if let text = object[key] as? String,
           let value = Int(text.trimmingCharacters(in: .whitespaces)) { return value }
        return nil
    }
}

// MARK: - 落库

/// `ChatService` 需要的那一件小事：把回复里的提案变成一条真的规则。
///
/// 单独抽协议是为了让聊天链路的测试不必背数据库；默认实现在 `ProactiveRuleService`。
public protocol ProactiveRuleWriting: AnyObject {
    /// 建一条规则（同一人格同一节奏已有就更新它，不堆重复）。
    /// - Returns: 规则 id。
    func createFromProposal(
        personaId: String,
        sessionId: String?,
        proposal: ProactiveProposal,
        at: Int64
    ) -> Result<String, Error>
}

/// 主动唤醒规则的读写。
///
/// 只做「规则」这一层：写 `proactive_rules`、算 `nextTriggerAt`、开关。到点把它变成念头的是
/// `AgentIntentSupport.materializeDueRules`（已经有、有测试），这里不重复实现。
public final class ProactiveRuleService: ProactiveRuleWriting {

    /// 两次主动之间的最小间隔（分钟）：比这更密的 interval 会被夹上来。
    public static let minIntervalMinutes = 15
    /// 一句话说明的长度上限（与 Android 的 `note.take(120)` 一致）。
    public static let maxNoteChars = 120
    /// 语气指引的长度上限（与 Android 的 `hint.take(600)` 一致）。
    public static let maxHintChars = 600

    private let stores: DatabaseStores
    private let now: () -> Int64
    private let calendar: Calendar

    public init(
        stores: DatabaseStores,
        now: @escaping () -> Int64 = { DomainClock.nowMillis },
        calendar: Calendar = ServiceTime.calendar()
    ) {
        self.stores = stores
        self.now = now
        self.calendar = calendar
    }

    // MARK: - 写

    /// 把提案落库。重复的节奏（同人格、同 kind、同时间/间隔）只更新那一条。
    @discardableResult
    public func createFromProposal(
        personaId: String,
        sessionId: String?,
        proposal: ProactiveProposal,
        at: Int64
    ) -> Result<String, Error> {
        guard !personaId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let persona = (try? stores.personas.byId(personaId)) ?? nil else {
            return .failure(ProactiveRuleError.personaNotFound)
        }
        if proposal.kind == .daily, proposal.timeOfDayMinutes == nil {
            return .failure(ProactiveRuleError.missingTime)
        }
        let interval = proposal.intervalMinutes.map { max($0, Self.minIntervalMinutes) }
        let note = String(proposal.note.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxNoteChars))
        let hint = String(proposal.hint.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxHintChars))
        let next = Self.computeNextTrigger(
            kind: proposal.kind,
            timeOfDayMinutes: proposal.timeOfDayMinutes,
            intervalMinutes: interval,
            from: at,
            calendar: calendar
        )
        do {
            let enabled = try stores.proactiveRules.fetch(where: "personaId = ? AND enabled = 1",
                                                          [.text(persona.id)])
            let duplicate = enabled.first {
                $0.kind == proposal.kind
                    && $0.timeOfDayMinutes == proposal.timeOfDayMinutes
                    && $0.intervalMinutes == interval
            }
            if var existing = duplicate {
                existing.enabled = true
                existing.pending = false
                if !note.isEmpty { existing.note = note }
                if !hint.isEmpty { existing.hint = hint }
                if let sessionId, !sessionId.isEmpty { existing.sessionId = sessionId }
                existing.nextTriggerAt = next
                existing.lastError = nil
                existing.updatedAt = at
                try stores.proactiveRules.upsert(existing)
                return .success(existing.id)
            }
            let id = UUID().uuidString
            let rule = ProactiveRuleEntity(
                id: id,
                personaId: persona.id,
                sessionId: (sessionId?.isEmpty == false ? sessionId : nil),
                kind: proposal.kind,
                timeOfDayMinutes: proposal.timeOfDayMinutes,
                intervalMinutes: interval,
                hint: hint.isEmpty ? "主动开口，用你的方式关心对方此刻的状态。" : hint,
                note: note.isEmpty ? Self.defaultNote(for: proposal, interval: interval) : note,
                enabled: true,
                pending: false,
                source: .ai,
                nextTriggerAt: next,
                lastTriggeredAt: nil,
                lastError: nil,
                createdAt: at,
                updatedAt: at
            )
            try stores.proactiveRules.upsert(rule)
            return .success(id)
        } catch {
            return .failure(error)
        }
    }

    /// 从模型写的提案 JSON 直接落库（App 层拿到 `ChatCompletion.proactiveProposalJson` 时用）。
    @discardableResult
    public func createFromJson(
        personaId: String,
        sessionId: String?,
        proposalJson: String,
        at: Int64? = nil
    ) -> Result<String, Error> {
        guard let proposal = ProactiveProtocol.parse(proposalJson) else {
            return .failure(ProactiveRuleError.unparsableProposal)
        }
        return createFromProposal(personaId: personaId, sessionId: sessionId, proposal: proposal, at: at ?? now())
    }

    /// 用户手动建一条规则（iOS 目前没有「主动唤醒」页，先留给测试 / 将来的入口）。
    @discardableResult
    public func createManual(
        personaId: String,
        sessionId: String?,
        kind: ProactiveKind,
        timeOfDayMinutes: Int?,
        intervalMinutes: Int?,
        hint: String,
        note: String,
        at: Int64? = nil
    ) -> Result<String, Error> {
        let stamp = at ?? now()
        if kind == .daily, timeOfDayMinutes == nil {
            return .failure(ProactiveRuleError.missingTime)
        }
        if kind == .interval {
            guard let intervalMinutes, intervalMinutes >= Self.minIntervalMinutes else {
                return .failure(ProactiveRuleError.badInterval)
            }
        }
        guard let persona = (try? stores.personas.byId(personaId)) ?? nil else {
            return .failure(ProactiveRuleError.personaNotFound)
        }
        let next = Self.computeNextTrigger(
            kind: kind, timeOfDayMinutes: timeOfDayMinutes,
            intervalMinutes: intervalMinutes, from: stamp, calendar: calendar
        )
        let rule = ProactiveRuleEntity(
            id: UUID().uuidString,
            personaId: persona.id,
            sessionId: sessionId,
            kind: kind,
            timeOfDayMinutes: timeOfDayMinutes,
            intervalMinutes: intervalMinutes,
            hint: String(hint.prefix(Self.maxHintChars)),
            note: String(note.prefix(Self.maxNoteChars)),
            enabled: true,
            pending: false,
            source: .manual,
            nextTriggerAt: next,
            createdAt: stamp,
            updatedAt: stamp
        )
        do {
            try stores.proactiveRules.upsert(rule)
            return .success(rule.id)
        } catch {
            return .failure(error)
        }
    }

    /// 打开 / 关掉一条规则（关掉时把下一次触发清零，别留着它到点）。
    @discardableResult
    public func setEnabled(id: String, enabled: Bool, at: Int64? = nil) -> Bool {
        guard var rule = (try? stores.proactiveRules.byId(id)) ?? nil else { return false }
        let stamp = at ?? now()
        rule.enabled = enabled
        rule.pending = false
        rule.nextTriggerAt = enabled
            ? Self.computeNextTrigger(
                kind: rule.kind,
                timeOfDayMinutes: rule.timeOfDayMinutes,
                intervalMinutes: rule.intervalMinutes,
                from: stamp,
                calendar: calendar
            )
            : 0
        rule.updatedAt = stamp
        do {
            try stores.proactiveRules.upsert(rule)
            return true
        } catch {
            return false
        }
    }

    /// 删掉一条规则（用户在列表里长按删除）。
    ///
    /// 只删规则本身；已经产生的对话、念头都不动（用户删的是「以后别再按这个节奏找我」）。
    @discardableResult
    public func delete(id: String) -> Bool {
        guard let rule = (try? stores.proactiveRules.byId(id)) ?? nil else { return false }
        do {
            try stores.proactiveRules.delete(id: rule.id)
            return true
        } catch {
            return false
        }
    }

    /// 到点的规则（只按时间筛；是否真的开口由 `AgentIntentSupport` 的克制判定决定）。
    public func due(at timestamp: Int64? = nil) -> [ProactiveRuleEntity] {
        (try? stores.proactiveRules.due(at: timestamp ?? now())) ?? []
    }

    public func rules(personaId: String) -> [ProactiveRuleEntity] {
        (try? stores.proactiveRules.byPersona(personaId)) ?? []
    }

    // MARK: - 时间

    /// 下一次触发时间。
    ///
    /// - daily：今天的 HH:mm 已经过了就排到明天；
    /// - interval：从现在起 N 分钟后（至少 15 分钟）。
    ///
    /// 与 Android 的 `computeNextTrigger` 同语义。故意不做随机抖动：抖动是「到点之后要不要真的开口」
    /// 那一步的事（`AgentIntentSupport.materializeDueRules`），规则本身要能被界面与测试预期。
    public static func computeNextTrigger(
        kind: ProactiveKind,
        timeOfDayMinutes: Int?,
        intervalMinutes: Int?,
        from: Int64,
        calendar: Calendar = ServiceTime.calendar()
    ) -> Int64 {
        switch kind {
        case .daily:
            let minutes = timeOfDayMinutes ?? 20 * 60
            let startOfToday = ServiceTime.startOfDay(from, calendar: calendar)
            let target = startOfToday + Int64(minutes) * 60_000
            return target > from ? target : target + 24 * 60 * 60_000
        case .interval:
            let interval = max(intervalMinutes ?? 180, minIntervalMinutes)
            return from + Int64(interval) * 60_000
        }
    }

    static func defaultNote(for proposal: ProactiveProposal, interval: Int?) -> String {
        switch proposal.kind {
        case .daily:
            let minutes = proposal.timeOfDayMinutes ?? 0
            return "每天 \(ProactiveProtocol.formatTimeOfDay(minutes)) 主动问候"
        case .interval:
            let hours = (interval ?? 180) / 60
            return "每 \(hours) 小时主动关心一次"
        }
    }
}

public enum ProactiveRuleError: Error, LocalizedError, Equatable {
    case personaNotFound
    case missingTime
    case badInterval
    case unparsableProposal

    public var errorDescription: String? {
        switch self {
        case .personaNotFound: return "找不到这个人格"
        case .missingTime: return "每天固定的规则要写明时间（HH:mm）"
        case .badInterval: return "间隔至少要 15 分钟"
        case .unparsableProposal: return "这段提案看不懂，没有建规则"
        }
    }
}


// MARK: - 给界面看的「一行规则」

/// 一条规则在列表里长什么样（纯数据，不碰 SwiftUI，单测能直接钉文案）。
public struct ProactiveRulePresentation: Equatable, Sendable {
    public var id: String
    /// 「林深」。
    public var personaName: String
    /// 主标题：规则的一句话说明（没写就兜底成「主动问候」）。
    public var note: String
    /// 展开的写作指引（列表里平时不显示）。
    public var hint: String
    /// 「每天」/「每 3 小时」。
    public var kindLabel: String
    /// 「每天 22:30」/「每 3 小时」。
    public var scheduleLabel: String
    /// 「下次大概 今天 22:30 前后」/「现在关着」。
    public var nextLabel: String
    /// 「他自己安排的」/「你设的」。
    public var sourceLabel: String
    public var enabled: Bool
    public var nextTriggerAt: Int64

    public init(
        id: String, personaName: String, note: String, hint: String,
        kindLabel: String, scheduleLabel: String, nextLabel: String,
        sourceLabel: String, enabled: Bool, nextTriggerAt: Int64
    ) {
        self.id = id
        self.personaName = personaName
        self.note = note
        self.hint = hint
        self.kindLabel = kindLabel
        self.scheduleLabel = scheduleLabel
        self.nextLabel = nextLabel
        self.sourceLabel = sourceLabel
        self.enabled = enabled
        self.nextTriggerAt = nextTriggerAt
    }
}

/// 规则 → 列表行。文案与 Android `ProactiveScreen.kt` 里那几句一致（「下次大概 … 前后」「现在关着」）。
public enum ProactiveRulePresenter {

    public static let emptyNoteFallback = "主动问候"

    /// 单条映射。
    public static func present(
        rule: ProactiveRuleEntity,
        personaName: String?,
        now: Int64 = DomainClock.nowMillis,
        calendar: Calendar = ServiceTime.calendar()
    ) -> ProactiveRulePresentation {
        let note = rule.note.trimmingCharacters(in: .whitespacesAndNewlines)
        let hint = rule.hint.trimmingCharacters(in: .whitespacesAndNewlines)
        return ProactiveRulePresentation(
            id: rule.id,
            personaName: (personaName ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "他" : personaName!.trimmingCharacters(in: .whitespacesAndNewlines),
            note: note.isEmpty ? emptyNoteFallback : note,
            hint: hint,
            kindLabel: kindLabel(rule),
            scheduleLabel: scheduleLabel(rule),
            nextLabel: nextLabel(rule, now: now, calendar: calendar),
            sourceLabel: rule.source == .ai ? "他自己安排的" : "你设的",
            enabled: rule.enabled,
            nextTriggerAt: rule.nextTriggerAt
        )
    }

    /// 一批规则：按「开着优先、下次触发早的在前」排，关掉的排最后。
    public static func present(
        rules: [ProactiveRuleEntity],
        personas: [PersonaEntity],
        now: Int64 = DomainClock.nowMillis,
        calendar: Calendar = ServiceTime.calendar()
    ) -> [ProactiveRulePresentation] {
        let names = Dictionary(uniqueKeysWithValues: personas.map { ($0.id, $0.name) })
        return rules
            .map { present(rule: $0, personaName: names[$0.personaId], now: now, calendar: calendar) }
            .sorted { lhs, rhs in
                if lhs.enabled != rhs.enabled { return lhs.enabled }
                if lhs.nextTriggerAt != rhs.nextTriggerAt { return lhs.nextTriggerAt < rhs.nextTriggerAt }
                return lhs.id < rhs.id
            }
    }

    // MARK: - 文案

    static func kindLabel(_ rule: ProactiveRuleEntity) -> String {
        switch rule.kind {
        case .daily: return "每天"
        case .interval: return "间隔"
        }
    }

    static func scheduleLabel(_ rule: ProactiveRuleEntity) -> String {
        switch rule.kind {
        case .daily:
            let minutes = rule.timeOfDayMinutes ?? 0
            return "每天 \(ProactiveProtocol.formatTimeOfDay(minutes))"
        case .interval:
            let minutes = rule.intervalMinutes ?? 0
            if minutes > 0, minutes % 60 == 0 {
                return "每 \(minutes / 60) 小时"
            }
            return "每 \(minutes) 分钟"
        }
    }

    static func nextLabel(
        _ rule: ProactiveRuleEntity,
        now: Int64,
        calendar: Calendar
    ) -> String {
        guard rule.enabled else { return "现在关着" }
        guard rule.nextTriggerAt > 0 else { return "下次时间还没排定" }
        let next = rule.nextTriggerAt
        let day: String
        if ServiceTime.startOfDay(next, calendar: calendar) == ServiceTime.startOfDay(now, calendar: calendar) {
            day = "今天"
        } else if ServiceTime.startOfDay(next, calendar: calendar)
            == ServiceTime.startOfDay(now, calendar: calendar) + 24 * 60 * 60_000 {
            day = "明天"
        } else {
            day = formatDay(next, calendar: calendar)
        }
        return "下次大概 \(day) \(ProactiveProtocol.formatTimeOfDay(minuteOfDay(next, calendar: calendar))) 前后"
    }

    static func minuteOfDay(_ millis: Int64, calendar: Calendar) -> Int {
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        return calendar.component(.hour, from: date) * 60 + calendar.component(.minute, from: date)
    }

    static func formatDay(_ millis: Int64, calendar: Calendar) -> String {
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        let components = calendar.dateComponents([.month, .day], from: date)
        return "\(components.month ?? 1)月\(components.day ?? 1)日"
    }
}
