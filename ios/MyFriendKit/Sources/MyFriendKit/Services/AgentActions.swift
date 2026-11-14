import Foundation

// 来源：app/src/main/java/com/myfriend/app/domain/AgentActions.kt
//
// 「自主思考」的领域模型（iOS 侧落在服务层，因为 Domain 只放多端共用的纯算法，
// 而这块目前只有 iOS 的服务层在用；将来如果两端都要用，可以整体搬进 Domain，语义不变）。
//
// 本文件只做三件事：
// 1. `AgentDecisionCodec` 把模型输出解析成 `AgentDecision`，并做防御性归一化；
// 2. 把「明早」「两小时后」「明天 20:00-21:30」这类模糊时间落成 `AgentWindow` 时间窗口；
// 3. `AgentPromptBuilder` 生成「他是谁 + 他现在在哪 + 能做什么」的提示词。
//
// 纯 Swift，不碰数据库、网络；所有入口都对坏数据安全（解析失败 → 回退成「什么都没做」）。

// MARK: - 动作

/// 一次「想」之后可选的动作类型。
public enum AgentActionType: String, CaseIterable, Sendable {
    case nothing = "NOTHING"
    case message = "MESSAGE"
    case browse = "BROWSE"
    case remember = "REMEMBER"
    case intent = "INTENT"
    case personaPatch = "PERSONA_PATCH"
    case quietHours = "QUIET_HOURS"
    case mood = "MOOD"
    /// 1.9：睡前要不要开免打扰（§六）。这是**他自己**的决定，用户不能直接改。
    case dnd = "DND"
}

/// 一条意向的时间窗口：不是准点，而是「差不多这段时间里」。
public struct AgentWindow: Equatable, Sendable {
    public var earliestAt: Int64
    public var latestAt: Int64
    /// 给自己的备忘（如「问问他睡得怎么样」）。
    public var note: String
    /// 到点时的语气提示。
    public var hint: String

    public init(earliestAt: Int64, latestAt: Int64, note: String = "", hint: String = "") {
        self.earliestAt = earliestAt
        self.latestAt = latestAt
        self.note = note
        self.hint = hint
    }
}

/// 一次决策里的单个动作。
public enum AgentAction: Equatable, Sendable {
    /// 什么都不做。这应该是大多数 tick 的答案。
    case nothing
    /// 主动发一条消息。
    case message(text: String)
    /// 自己上网看看。
    case browse(query: String)
    /// 往长期记忆里写一笔。
    case remember(content: String, drawer: String, importance: Int)
    /// 给未来的自己留一个意向，带时间窗口。
    case planIntent(window: AgentWindow)
    /// 想调整自己的人设（直接生效，历史版本里可以回滚）。
    case personaPatch(patchJson: String, summary: String)
    /// 此刻的心情变了：一个词、多浓、为什么。
    case mood(label: String, intensity: Int, reason: String)
    /// 自己安排作息。`dnd` 为 nil 表示「这次不提免打扰的事」。
    case quietHours(sleepMinutes: Int, wakeMinutes: Int, reason: String, dnd: Bool?)
    /// 睡前要不要开免打扰（1.9 §六）。只改这一个开关，不动作息时间。
    case dnd(enabled: Bool)

    public var type: AgentActionType {
        switch self {
        case .nothing: return .nothing
        case .message: return .message
        case .browse: return .browse
        case .remember: return .remember
        case .planIntent: return .intent
        case .personaPatch: return .personaPatch
        case .quietHours: return .quietHours
        case .mood: return .mood
        case .dnd: return .dnd
        }
    }
}

/// 一次完整的「想完了」：一句心里话 + 若干动作。
public struct AgentDecision: Equatable, Sendable {
    public var thought: String
    public var actions: [AgentAction]

    public init(thought: String = "", actions: [AgentAction] = [.nothing]) {
        self.thought = thought
        self.actions = actions
    }

    /// 是否至少有一个真正做事的动作（`.nothing` 不算）。
    public var hasEffect: Bool { actions.contains { $0 != .nothing } }

    /// 给日志/界面看的一句中文，如「发消息 + 联网浏览」。
    public func summary() -> String {
        let labels = actions.map(Self.label).reduce(into: [String]()) { result, label in
            if !result.contains(label) { result.append(label) }
        }
        let effective = labels.filter { $0 != "什么都没做" }
        return effective.isEmpty ? "什么都没做" : effective.joined(separator: " + ")
    }

    static func label(_ action: AgentAction) -> String {
        switch action {
        case .nothing: return "什么都没做"
        case .message: return "发消息"
        case .browse: return "联网浏览"
        case .remember: return "记一笔"
        case .planIntent: return "留个意向"
        case .personaPatch: return "想改自己"
        case .quietHours: return "调整作息"
        case .mood: return "心情变了"
        case .dnd: return "改免打扰"
        }
    }
}

// MARK: - 上限

/// 所有长度、数量、窗口都收敛在这里，避免一处改不全。
enum AgentLimits {
    static let thoughtMax = 400
    static let messageMax = 300
    static let browseMax = 60
    static let rememberMax = 400
    static let maxActions = 4
    static let importanceMin = 1
    static let importanceMax = 5
    static let noteMax = 200
    static let moodReasonMax = 60
    static let hintMax = 120
    static let quietReasonMax = 120
    static let patchSummaryMax = 120

    static let hourMillis: Int64 = 3_600_000
    static let dayMillis: Int64 = 86_400_000
    static let minWindowMillis: Int64 = 15 * 60_000
    static let maxWindowMillis: Int64 = 6 * hourMillis
    /// 显式时刻落成窗口时的默认跨度（小时）。
    static let defaultHours = 2
    /// 「前后 / 左右」这类模糊时刻：以点为中心前后各留 45 分钟。
    static let fuzzyRadiusMillis: Int64 = 45 * 60_000
    static let promptSystemMax = 1400
}

// MARK: - 解析 / 归一化 / 序列化

public enum AgentDecisionCodec {

    /// 模型输出经常被 ```json 代码块包住，前后还带解释文字；字段偶尔缺失或类型不对。
    /// 策略是：**能救则救，救不了的那一条丢掉**，绝不让整次 tick 崩掉。
    public static func parse(raw: String, now: Int64, calendar: Calendar = ServiceTime.calendar()) -> AgentDecision? {
        guard !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        guard let root = firstJsonObject(raw) else { return nil }
        // 至少得沾一个决策字段，否则说明这根本不是一次「思考」的返回。
        guard root["thought"] != nil || root["actions"] != nil else { return nil }

        let thought = String(
            (textOf(root, ["thought", "think", "reasoning"]) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(AgentLimits.thoughtMax)
        )
        var parsed: [AgentAction] = []
        if let array = root["actions"] as? [Any] {
            for element in array {
                guard let object = element as? [String: Any] else { continue }
                if let action = parseAction(object, now: now, calendar: calendar) {
                    parsed.append(action)
                }
            }
        }
        return AgentDecision(
            thought: thought,
            actions: parsed.isEmpty ? [.nothing] : parsed
        )
    }

    /// 把模型给的动作收敛到可执行的范围。
    public static func normalize(
        _ decision: AgentDecision,
        now: Int64,
        calendar: Calendar = ServiceTime.calendar()
    ) -> AgentDecision {
        var kept: [AgentAction] = []
        var seenTypes = Set<AgentActionType>()
        for action in decision.actions {
            guard let normalized = normalizeAction(action, now: now) else { continue }
            let type = normalized.type
            if type == .nothing {
                if !kept.contains(.nothing) { kept.append(.nothing) }
                continue
            }
            if !seenTypes.insert(type).inserted { continue }
            kept.append(normalized)
        }
        // 有正经动作时，nothing 就没有意义了，去掉它；否则保留一个 nothing。
        let effective = kept.filter { $0 != .nothing }
        return AgentDecision(
            thought: String(decision.thought.trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(AgentLimits.thoughtMax)),
            actions: effective.isEmpty ? [.nothing] : Array(effective.prefix(AgentLimits.maxActions))
        )
    }

    static func normalizeAction(_ action: AgentAction, now: Int64) -> AgentAction? {
        switch action {
        case .nothing:
            return .nothing
        case .message(let text):
            let trimmed = String(text.trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(AgentLimits.messageMax))
            return trimmed.isEmpty ? nil : .message(text: trimmed)
        case .browse(let query):
            let trimmed = String(query.trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(AgentLimits.browseMax))
            return trimmed.isEmpty ? nil : .browse(query: trimmed)
        case .remember(let content, let drawer, let importance):
            let trimmed = String(content.trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(AgentLimits.rememberMax))
            return trimmed.isEmpty ? nil : .remember(
                content: trimmed,
                drawer: drawerOf(drawer),
                importance: clampImportance(importance)
            )
        case .planIntent(let window):
            return sanitizeWindow(window, now: now).map { .planIntent(window: $0) }
        case .personaPatch(let patchJson, let summary):
            guard jsonObject(patchJson) != nil else { return nil }
            return .personaPatch(
                patchJson: patchJson,
                summary: String(summary.trimmingCharacters(in: .whitespacesAndNewlines)
                    .prefix(AgentLimits.patchSummaryMax))
            )
        case .mood(let label, let intensity, let reason):
            return .mood(
                label: MoodLexicon.normalize(label),
                intensity: clampImportance(intensity),
                reason: String(reason.trimmingCharacters(in: .whitespacesAndNewlines)
                    .prefix(AgentLimits.moodReasonMax))
            )
        case .quietHours(let sleep, let wake, let reason, let dnd):
            return .quietHours(
                sleepMinutes: normalizeMinutes(sleep),
                wakeMinutes: normalizeMinutes(wake),
                reason: String(reason.trimmingCharacters(in: .whitespacesAndNewlines)
                    .prefix(AgentLimits.quietReasonMax)),
                dnd: dnd
            )
        case .dnd(let enabled):
            return .dnd(enabled: enabled)
        }
    }

    /// 窗口兜底：已经过去的时间点收到 now；窗口整体过期时顺延到 now 起的默认跨度；
    /// 跨度夹在 15 分钟到 6 小时之间。
    static func sanitizeWindow(_ window: AgentWindow, now: Int64) -> AgentWindow? {
        if window.earliestAt >= window.latestAt { return nil }
        let earliest = max(window.earliestAt, now)
        var latest = window.latestAt
        if latest <= earliest { latest = earliest + Int64(AgentLimits.defaultHours) * AgentLimits.hourMillis }
        if latest - earliest > AgentLimits.maxWindowMillis { latest = earliest + AgentLimits.maxWindowMillis }
        if latest - earliest < AgentLimits.minWindowMillis { latest = earliest + AgentLimits.minWindowMillis }
        return AgentWindow(
            earliestAt: earliest,
            latestAt: latest,
            note: String(window.note.trimmingCharacters(in: .whitespacesAndNewlines).prefix(AgentLimits.noteMax)),
            hint: String(window.hint.trimmingCharacters(in: .whitespacesAndNewlines).prefix(AgentLimits.hintMax))
        )
    }

    /// 序列化回模型约定的格式，用于 `agent_ticks.actionJson` 与界面展示。
    /// 永远返回一个合法 JSON（失败时也返回「什么都没做」），不抛异常。
    public static func toJson(_ decision: AgentDecision) -> String {
        var root: [String: Any] = [:]
        root["thought"] = String(decision.thought.trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(AgentLimits.thoughtMax))
        root["actions"] = decision.actions.map(actionJson)
        guard let data = try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return Self.defaultDecisionJson
        }
        return text
    }

    static let defaultDecisionJson = #"{"thought":"","actions":[{"type":"nothing"}]}"#

    static func actionJson(_ action: AgentAction) -> [String: Any] {
        switch action {
        case .nothing:
            return ["type": "nothing"]
        case .message(let text):
            return ["type": "message", "text": text]
        case .browse(let query):
            return ["type": "browse", "query": query]
        case .remember(let content, let drawer, let importance):
            return ["type": "remember", "content": content, "drawer": drawer, "importance": importance]
        case .planIntent(let window):
            return [
                "type": "intent", "earliest_at": window.earliestAt, "latest_at": window.latestAt,
                "note": window.note, "hint": window.hint
            ]
        case .personaPatch(let patchJson, let summary):
            return ["type": "persona_patch", "patch": jsonObject(patchJson) ?? patchJson, "summary": summary]
        case .mood(let label, let intensity, let reason):
            return ["type": "mood", "mood": label, "intensity": intensity, "reason": reason]
        case .quietHours(let sleep, let wake, let reason, let dnd):
            var payload: [String: Any] = [
                "type": "quiet_hours", "sleep_at": ServiceTime.clockText(sleep),
                "wake_at": ServiceTime.clockText(wake), "reason": reason
            ]
            if let dnd { payload["dnd"] = dnd }
            return payload
        case .dnd(let enabled):
            return ["type": "dnd", "enabled": enabled]
        }
    }

    // MARK: - 时间窗口解析

    /// 把模糊时间说法解析成时间窗口。
    ///
    /// 支持：时段词（明早/明天/早上/上午/中午/下午/傍晚/今晚/晚上/半夜/深夜/凌晨/睡前/下班后…）、
    /// 显式时刻（「8:30」「八点半」「明早 8 点前后」「20:00-21:30」）、
    /// 相对量（「2h」「+90m」「两小时后」「半小时后」「十分钟后」）。
    ///
    /// 认不出来时给 `now + defaultHours` 到 `now + defaultHours * 2` 的窗口；
    /// 只有文本为空才返回 nil。返回的窗口保证 `earliest < latest`、`earliest >= now`。
    public static func windowFor(
        _ text: String,
        now: Int64,
        calendar: Calendar = ServiceTime.calendar(),
        defaultHours: Int = 2
    ) -> AgentWindow? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return nil }
        let hours = min(max(defaultHours, 1), 3)
        func fallback() -> AgentWindow { fallbackWindow(now: now, hours: hours) }

        let raw = normalizeTimeText(trimmed)
        let forcedDay = dayOffsetOf(raw)

        if let minutes = relativeMinutes(raw) {
            let earliest = now + minutes * 60_000
            return saneWindow(earliest, earliest + Int64(hours) * AgentLimits.hourMillis, now: now)
        }
        if let window = clockWindow(raw, now: now, calendar: calendar, forcedDay: forcedDay, hours: hours) {
            return window
        }
        if let window = periodWindow(raw, now: now, calendar: calendar, forcedDay: forcedDay) {
            return window
        }
        if let window = phraseWindow(raw, now: now) {
            return window
        }
        return fallback()
    }

    /// 全角转半角、统一冒号；空格留给正则自己吃。
    static func normalizeTimeText(_ raw: String) -> String {
        var out = ""
        out.reserveCapacity(raw.count)
        for scalar in raw.trimmingCharacters(in: .whitespacesAndNewlines).unicodeScalars {
            if scalar.value >= 0xFF01 && scalar.value <= 0xFF5E {
                out.append(String(Unicode.Scalar(scalar.value - 0xFEE0)!))
            } else if scalar.value == 0x3000 {
                out.append(" ")
            } else {
                out.append(String(scalar))
            }
        }
        return out
    }

    // MARK: - 相对量

    static func relativeMinutes(_ text: String) -> Int64? {
        if text.contains("一会儿") || text.contains("待会儿") || text.contains("过会儿") { return 30 }
        if text.contains("稍后") || text.contains("晚点") || text.contains("回头") { return 45 }

        if let match = relativeArabic.firstMatch(text) {
            guard let amountText = match.group(1)?.replacingOccurrences(of: "+", with: ""),
                  let amount = Int64(amountText),
                  let unit = match.group(2) else { return nil }
            return toMinutes(amount, unit: unit)
        }

        if let match = relativeChinese.firstMatch(text) {
            guard let baseText = match.group(1), let base = chineseNumber(baseText) else { return nil }
            if let half = match.group(3), !half.isEmpty {
                // 「一个半小时」「两个半小时」
                return Int64(base) * 60 + 30
            }
            guard let unit = match.group(4) else { return nil }
            return toMinutes(Int64(base), unit: unit)
        }

        if relativeHalf.containsMatch(text) { return 30 }
        return nil
    }

    static func toMinutes(_ amount: Int64, unit: String) -> Int64 {
        if unit.hasPrefix("小时") || unit.hasPrefix("钟头") { return amount * 60 }
        if unit.hasPrefix("h") || unit.hasPrefix("hour") || unit.hasPrefix("hr") { return amount * 60 }
        return amount
    }

    // MARK: - 显式时刻

    static func clockWindow(
        _ text: String,
        now: Int64,
        calendar: Calendar,
        forcedDay: Int?,
        hours: Int
    ) -> AgentWindow? {
        if let range = rangeRegex.firstMatch(text) {
            guard let startToken = range.group(1), let endToken = range.group(2),
                  let startMinutes = parseClockToken(startToken),
                  let endMinutes = parseClockToken(endToken) else { return nil }
            return buildClockWindow(
                now: now, calendar: calendar, dayOffset: forcedDay ?? 0,
                startMinutes: startMinutes, endMinutes: endMinutes,
                forcedDay: forcedDay, hours: hours
            )
        }

        guard let single = singleClockRegex.firstMatch(text),
              let token = single.group(0),
              let startMinutes = parseClockToken(token) else { return nil }
        let fuzzy = fuzzyWords.contains { text.contains($0) }
        // 「前后 / 左右」是围绕钟点的窗口：起点提前 45 分钟，终点再推后 45 分钟。
        let startOffset = fuzzy ? -AgentLimits.fuzzyRadiusMillis : 0
        let endOffset = fuzzy ? AgentLimits.fuzzyRadiusMillis * 2 : Int64(hours) * AgentLimits.hourMillis
        return buildClockWindow(
            now: now, calendar: calendar, dayOffset: forcedDay ?? 0,
            startMinutes: startMinutes, endMinutes: nil,
            forcedDay: forcedDay, hours: hours,
            startOffsetMillis: startOffset, endOffsetMillis: endOffset
        )
    }

    /// 由钟点构造窗口。区间直接用；单点用「从该时刻起 hours 小时」。
    /// 如果整段已经过去，顺延到次日；如果正处在窗口内，earliest 收到 now。
    static func buildClockWindow(
        now: Int64,
        calendar: Calendar,
        dayOffset: Int,
        startMinutes: Int,
        endMinutes: Int?,
        forcedDay: Int?,
        hours: Int,
        startOffsetMillis: Int64 = 0,
        endOffsetMillis: Int64? = nil
    ) -> AgentWindow {
        func startOf(_ offset: Int) -> Int64 {
            instantOf(dayOffset: offset, minutes: startMinutes, now: now, calendar: calendar) + startOffsetMillis
        }
        func endOf(_ offset: Int) -> Int64 {
            if let endMinutes {
                let endDay = endMinutes <= startMinutes ? offset + 1 : offset
                return instantOf(dayOffset: endDay, minutes: endMinutes, now: now, calendar: calendar)
            }
            return startOf(offset) + (endOffsetMillis ?? Int64(hours) * AgentLimits.hourMillis)
        }

        var offset = dayOffset
        var start = startOf(offset)
        var end = endOf(offset)
        // 没有明确说「明天/今天」时，整段已经过去就当作次日同一时段；
        // 明确说了今天也遵循「时段过去就顺延」的约定。
        if (forcedDay == nil || forcedDay == 0) && end <= now {
            offset += 1
            start = startOf(offset)
            end = endOf(offset)
        }
        return saneWindow(start, end, now: now)
    }

    /// 只有时段词、没有具体钟点。
    static func periodWindow(_ text: String, now: Int64, calendar: Calendar, forcedDay: Int?) -> AgentWindow? {
        guard let slot = periodSlotOf(text) else { return nil }
        var dayOffset = forcedDay ?? 0

        // 跨午夜的「半夜/深夜」：凌晨那一段属于昨天晚上的窗口。
        if slot.endMinutes > 1440 && forcedDay == nil {
            let nowMinutes = ServiceTime.minuteOfDay(now, calendar: calendar)
            if nowMinutes < slot.endMinutes % 1440 { dayOffset -= 1 }
        }

        var start = instantOf(dayOffset: dayOffset, minutes: slot.startMinutes, now: now, calendar: calendar)
        var end = instantOf(dayOffset: dayOffset, minutes: slot.endMinutes, now: now, calendar: calendar)
        var guardCount = 0
        while (forcedDay == nil || forcedDay == 0) && end <= now && guardCount < 3 {
            guardCount += 1
            dayOffset += 1
            start = instantOf(dayOffset: dayOffset, minutes: slot.startMinutes, now: now, calendar: calendar)
            end = instantOf(dayOffset: dayOffset, minutes: slot.endMinutes, now: now, calendar: calendar)
        }
        return saneWindow(start, end, now: now)
    }

    /// 没有钟点、也没有标准时段词时的固定说法。
    static func phraseWindow(_ text: String, now: Int64) -> AgentWindow? {
        if text.contains("马上") || text.contains("现在就") || text.contains("这就") {
            return saneWindow(now, now + 30 * 60_000, now: now)
        }
        return nil
    }

    static func fallbackWindow(now: Int64, hours: Int) -> AgentWindow {
        let earliest = now + Int64(hours) * AgentLimits.hourMillis
        return saneWindow(earliest, earliest + Int64(hours) * AgentLimits.hourMillis, now: now)
    }

    /// 全文件唯一的窗口守卫：保证 `earliest < latest`、`earliest >= now`，
    /// 跨度在 15 分钟到 6 小时之间。
    static func saneWindow(_ earliestRaw: Int64, _ latestRaw: Int64, now: Int64) -> AgentWindow {
        var earliest = earliestRaw
        var latest = latestRaw
        if latest <= earliest { latest = earliest + Int64(AgentLimits.defaultHours) * AgentLimits.hourMillis }
        if latest <= now {
            let days = min((now - latest) / AgentLimits.dayMillis + 1, 7)
            earliest += days * AgentLimits.dayMillis
            latest += days * AgentLimits.dayMillis
        }
        if earliest < now { earliest = now }
        if latest - earliest > AgentLimits.maxWindowMillis { latest = earliest + AgentLimits.maxWindowMillis }
        if latest - earliest < AgentLimits.minWindowMillis { latest = earliest + AgentLimits.minWindowMillis }
        return AgentWindow(earliestAt: earliest, latestAt: latest)
    }

    struct PeriodSlot {
        var startMinutes: Int
        var endMinutes: Int
    }

    /// 时段词 -> 钟点区间。endMinutes 可以超过 1440，表示跨午夜。
    static func periodSlotOf(_ text: String) -> PeriodSlot? {
        if text.contains("凌晨") { return PeriodSlot(startMinutes: 60, endMinutes: 240) }
        if text.contains("半夜") || text.contains("深夜") || text.contains("夜里") {
            return PeriodSlot(startMinutes: 23 * 60, endMinutes: 24 * 60 + 90)
        }
        if text.contains("明早") || text.contains("明儿早") || text.contains("明天早")
            || (text.contains("早起") && text.contains("明天")) {
            return PeriodSlot(startMinutes: 7 * 60, endMinutes: 9 * 60 + 30)
        }
        if text.contains("早上") || text.contains("早晨") || text.contains("清晨")
            || text.contains("一早") || text.contains("一大早") {
            return PeriodSlot(startMinutes: 7 * 60, endMinutes: 9 * 60 + 30)
        }
        if text.contains("起床后") || text.contains("起床") {
            return PeriodSlot(startMinutes: 7 * 60, endMinutes: 9 * 60)
        }
        if text.contains("上午") { return PeriodSlot(startMinutes: 9 * 60, endMinutes: 11 * 60 + 30) }
        if text.contains("中午") || text.contains("午间") {
            return PeriodSlot(startMinutes: 11 * 60 + 30, endMinutes: 13 * 60 + 30)
        }
        if text.contains("下午") { return PeriodSlot(startMinutes: 13 * 60, endMinutes: 17 * 60) }
        if text.contains("傍晚") { return PeriodSlot(startMinutes: 17 * 60 + 30, endMinutes: 19 * 60 + 30) }
        if text.contains("下班") { return PeriodSlot(startMinutes: 18 * 60, endMinutes: 19 * 60 + 30) }
        if text.contains("睡前") { return PeriodSlot(startMinutes: 22 * 60, endMinutes: 23 * 60 + 30) }
        if text.contains("晚上") || text.contains("今晚") || text.contains("明晚") {
            return PeriodSlot(startMinutes: 19 * 60 + 30, endMinutes: 22 * 60 + 30)
        }
        if text.contains("白天") { return PeriodSlot(startMinutes: 9 * 60, endMinutes: 20 * 60) }
        if text.contains("明天") || text.contains("明儿") || text.contains("明日") {
            return PeriodSlot(startMinutes: 9 * 60, endMinutes: 20 * 60)
        }
        return nil
    }

    /// 「明天/今天」这类日偏移；nil 表示没说，交给「时段过去了就顺延」决定。
    static func dayOffsetOf(_ text: String) -> Int? {
        if text.contains("大后天") { return 3 }
        if text.contains("后天") { return 2 }
        if text.contains("明天") || text.contains("明早") || text.contains("明晚")
            || text.contains("明儿") || text.contains("明日") || text.contains("次日") {
            return 1
        }
        if text.contains("今天") || text.contains("今晚") || text.contains("今早")
            || text.contains("今儿") || text.contains("今日") {
            return 0
        }
        return nil
    }

    /// 把「日期 + 当天第几分钟」换成毫秒时间戳；分钟超过 1440 自动进位到次日。
    static func instantOf(dayOffset: Int, minutes: Int, now: Int64, calendar: Calendar) -> Int64 {
        let extraDays = minutes / 1440
        let minuteOfDay = ((minutes % 1440) + 1440) % 1440
        let date = Date(timeIntervalSince1970: Double(now) / 1000.0)
        let startOfDay = calendar.startOfDay(for: date)
        let dayStart = calendar.date(byAdding: .day, value: dayOffset + extraDays, to: startOfDay) ?? startOfDay
        let target = dayStart.addingTimeInterval(TimeInterval(minuteOfDay * 60))
        return Int64((target.timeIntervalSince1970 * 1000.0).rounded())
    }

    // MARK: - 钟点与数字

    /// 解析「8:30」「08:30」「8点半」「八点一刻」「8点15分」；返回当天第几分钟。
    static func parseClockToken(_ token: String) -> Int? {
        let text = token.replacingOccurrences(of: "：", with: ":")
            .replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
        if let match = clockColon.fullMatch(text) {
            guard let hour = Int(match[0] ?? ""), let minute = Int(match[1] ?? ""),
                  (0...23).contains(hour), (0...59).contains(minute) else { return nil }
            return hour * 60 + minute
        }
        if let match = hourToken.fullMatch(text) {
            guard let hour = Int(match[0] ?? ""), (0...23).contains(hour) else { return nil }
            guard let minute = minuteOfSuffix(match[1] ?? "") else { return nil }
            return hour * 60 + minute
        }
        if let match = cnHourToken.fullMatch(text) {
            guard let hourText = match[0], let hour = chineseNumber(hourText), (0...23).contains(hour) else {
                return nil
            }
            guard let minute = minuteOfSuffix(match[1] ?? "") else { return nil }
            return hour * 60 + minute
        }
        return nil
    }

    static func minuteOfSuffix(_ raw: String) -> Int? {
        let suffix = raw.replacingOccurrences(of: "分", with: "")
            .replacingOccurrences(of: " ", with: "")
        if suffix.isEmpty { return 0 }
        switch suffix {
        case "半": return 30
        case "一刻": return 15
        case "三刻": return 45
        default:
            if let value = chineseNumber(suffix) { return value }
            return Int(suffix).flatMap { (0...59).contains($0) ? $0 : nil }
        }
    }

    /// 中文数字 1..99：「两」=2、「十」=10、「二十」=20。
    static func chineseNumber(_ raw: String) -> Int? {
        if raw.isEmpty { return nil }
        let chars = Array(raw)
        if let tenIndex = chars.firstIndex(of: "十") {
            let leftPart = String(chars[..<tenIndex])
            let rightPart = String(chars[(tenIndex + 1)...])
            let left: Int
            if leftPart.isEmpty {
                left = 1
            } else {
                guard let digit = chineseDigits[leftPart.last!] else { return nil }
                left = digit
            }
            let right: Int
            if rightPart.isEmpty {
                right = 0
            } else {
                guard let digit = chineseDigits[rightPart.last!] else { return nil }
                right = digit
            }
            return left * 10 + right
        }
        var value = 0
        for ch in chars {
            guard let digit = chineseDigits[ch] else { return nil }
            value = value * 10 + digit
        }
        return value
    }

    /// 作息时间「23:30」「23：30」「23点30」；解析失败返回 nil。
    static func parseClockMinutes(_ raw: String) -> Int? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return nil }
        let text = trimmed.replacingOccurrences(of: "：", with: ":")
            .replacingOccurrences(of: "点", with: ":")
            .replacingOccurrences(of: "\\s+", with: "", options: .regularExpression)
        guard let match = clockMinutes.fullMatch(text) else { return nil }
        guard let hour = Int(match[0] ?? ""), (0...23).contains(hour) else { return nil }
        let minuteText = (match[1] ?? "").isEmpty ? "0" : match[1]!
        guard let minute = Int(minuteText), (0...59).contains(minute) else { return nil }
        return hour * 60 + minute
    }

    // MARK: - 动作类型 / 字段映射

    static func clampImportance(_ value: Int) -> Int {
        min(max(value, AgentLimits.importanceMin), AgentLimits.importanceMax)
    }

    static func normalizeMinutes(_ value: Int) -> Int {
        ((value % 1440) + 1440) % 1440
    }

    /// drawer 只允许 SELF / USER / EVENT，其它一律回退 USER。
    static func drawerOf(_ raw: String?) -> String {
        let key = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if Self.selfDrawers.contains(key) { return "SELF" }
        if Self.eventDrawers.contains(key) { return "EVENT" }
        if Self.userDrawers.contains(key) { return "USER" }
        return "USER"
    }

    static func actionTypeOf(_ raw: String) -> AgentActionType? {
        var key = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        key = key.replacingOccurrences(of: "-", with: "_").replacingOccurrences(of: " ", with: "_")
        if key.isEmpty { return nil }
        if nothingLabels.contains(key) { return .nothing }
        if messageLabels.contains(key) { return .message }
        if browseLabels.contains(key) { return .browse }
        if rememberLabels.contains(key) { return .remember }
        if intentLabels.contains(key) { return .intent }
        if personaLabels.contains(key) { return .personaPatch }
        if quietLabels.contains(key) { return .quietHours }
        if dndLabels.contains(key) { return .dnd }
        if moodLabels.contains(key) { return .mood }
        return nil
    }

    // MARK: - 解析实现

    /// 解析一条动作；字段缺失或类型不对时返回 nil（只丢这一条）。
    static func parseAction(_ obj: [String: Any], now: Int64, calendar: Calendar) -> AgentAction? {
        guard let rawType = textOf(obj, ["type", "kind", "action"]),
              let type = actionTypeOf(rawType) else { return nil }
        switch type {
        case .nothing:
            return .nothing

        case .message:
            guard let text = oneLine(obj, ["text", "content", "message", "say"]) else { return nil }
            return .message(text: text)

        case .browse:
            guard let query = oneLine(obj, ["query", "text", "content", "q"]) else { return nil }
            return .browse(query: query)

        case .remember:
            guard let content = oneLine(obj, ["content", "text", "memory"]) else { return nil }
            return .remember(
                content: content,
                drawer: drawerOf(textOf(obj, ["drawer", "category"])),
                importance: clampImportance(intOf(obj, ["importance"]) ?? 3)
            )

        case .intent:
            let window = parseAbsoluteWindow(obj)
                ?? windowFor(textOf(obj, ["at", "when", "time"]) ?? "", now: now, calendar: calendar)
            guard let window else { return nil }
            let note = String((oneLine(obj, ["note", "reason"]) ?? "").prefix(AgentLimits.noteMax))
            let hint = String((oneLine(obj, ["hint"]) ?? "").prefix(AgentLimits.hintMax))
            var copy = window
            copy.note = note
            copy.hint = hint
            return .planIntent(window: copy)

        case .personaPatch:
            var patchObject = obj["patch"] as? [String: Any]
            if patchObject == nil, let rawPatch = textOf(obj, ["patch", "patch_json"]) {
                patchObject = jsonObject(rawPatch)
            }
            guard let patchObject,
                  let data = try? JSONSerialization.data(withJSONObject: patchObject, options: [.sortedKeys]),
                  let patchJson = String(data: data, encoding: .utf8) else { return nil }
            return .personaPatch(
                patchJson: patchJson,
                summary: String((oneLine(obj, ["summary", "reason"]) ?? "").prefix(AgentLimits.patchSummaryMax))
            )

        case .mood:
            guard let label = oneLine(obj, ["mood", "label", "text", "name"]) else { return nil }
            return .mood(
                label: label,
                intensity: clampImportance(intOf(obj, ["intensity", "level", "strength"]) ?? 2),
                reason: String((oneLine(obj, ["reason", "why"]) ?? "").prefix(AgentLimits.moodReasonMax))
            )

        case .quietHours:
            guard let sleep = parseClockMinutes(textOf(obj, ["sleep_at", "sleepAt", "sleep"]) ?? ""),
                  let wake = parseClockMinutes(textOf(obj, ["wake_at", "wakeAt", "wake"]) ?? "") else {
                return nil
            }
            return .quietHours(
                sleepMinutes: sleep,
                wakeMinutes: wake,
                reason: String((oneLine(obj, ["reason"]) ?? "").prefix(AgentLimits.quietReasonMax)),
                dnd: boolOf(obj, ["dnd", "do_not_disturb", "mute"])
            )

        case .dnd:
            // 只写「开/关」，不要求它同时给作息：模型经常只是想改这一件事。
            guard let enabled = boolOf(obj, ["enabled", "on", "dnd", "value"]) else { return nil }
            return .dnd(enabled: enabled)
        }
    }

    /// 旧格式/本地格式里可能已经存了绝对时间戳，优先用它们，避免再猜一次。
    static func parseAbsoluteWindow(_ obj: [String: Any]) -> AgentWindow? {
        let nested = obj["window"] as? [String: Any]
        let earliest = longOf(obj, ["earliest_at", "earliestAt", "earliest"])
            ?? nested.flatMap { longOf($0, ["earliest_at", "earliestAt", "earliest"]) }
        let latest = longOf(obj, ["latest_at", "latestAt", "latest"])
            ?? nested.flatMap { longOf($0, ["latest_at", "latestAt", "latest"]) }
        guard let earliest, let latest, earliest < latest else { return nil }
        return AgentWindow(earliestAt: earliest, latestAt: latest)
    }

    // MARK: - JSON 取值辅助

    static func textOf(_ object: [String: Any], _ keys: [String]) -> String? {
        for key in keys {
            guard let element = object[key], isPrimitive(element) else { continue }
            let text = primitiveString(element)
            if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return text }
        }
        return nil
    }

    static func oneLine(_ object: [String: Any], _ keys: [String]) -> String? {
        guard let text = textOf(object, keys) else { return nil }
        let collapsed = text.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return collapsed.isEmpty ? nil : collapsed
    }

    static func intOf(_ object: [String: Any], _ keys: [String]) -> Int? {
        for key in keys {
            guard let element = object[key], isPrimitive(element) else { continue }
            if let number = element as? NSNumber, !isBoolean(number) { return number.intValue }
            if let text = Int(primitiveString(element).trimmingCharacters(in: .whitespacesAndNewlines)) {
                return text
            }
        }
        return nil
    }

    static func longOf(_ object: [String: Any], _ keys: [String]) -> Int64? {
        for key in keys {
            guard let element = object[key], isPrimitive(element) else { continue }
            if let number = element as? NSNumber, !isBoolean(number) { return number.int64Value }
            if let text = Int64(primitiveString(element).trimmingCharacters(in: .whitespacesAndNewlines)) {
                return text
            }
        }
        return nil
    }

    /// 宽松取布尔：true/false、"true"/"on"/"1"、1/0 都认；认不出返回 nil。
    static func boolOf(_ object: [String: Any], _ keys: [String]) -> Bool? {
        for key in keys {
            guard let element = object[key], isPrimitive(element) else { continue }
            if let number = element as? NSNumber, isBoolean(number) { return number.boolValue }
            let text = primitiveString(element).trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if ["true", "on", "yes", "1", "开"].contains(text) { return true }
            if ["false", "off", "no", "0", "关"].contains(text) { return false }
        }
        return nil
    }

    static func isPrimitive(_ value: Any) -> Bool {
        value is String || value is NSNumber || value is NSNull
    }

    static func primitiveString(_ value: Any) -> String {
        if value is NSNull { return "" }
        if let text = value as? String { return text }
        if let number = value as? NSNumber {
            if isBoolean(number) { return number.boolValue ? "true" : "false" }
            return number.stringValue
        }
        return ""
    }

    static func isBoolean(_ number: NSNumber) -> Bool {
        CFGetTypeID(number) == CFBooleanGetTypeID()
    }

    static func jsonObject(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])) as? [String: Any]
    }

    /// 从一段可能带废话的文本里取出第一个「括号配平」的 JSON 对象。
    static func firstJsonObject(_ raw: String) -> [String: Any]? {
        var searchStart = raw.startIndex
        while let index = raw[searchStart...].firstIndex(of: "{") {
            if let end = matchingBrace(raw, start: index) {
                let slice = String(raw[index...end])
                if let parsed = jsonObject(slice) { return parsed }
            }
            searchStart = raw.index(after: index)
        }
        return nil
    }

    /// 找到与 `start` 处 `{` 配平的 `}`，跳过字符串里的花括号与转义。
    static func matchingBrace(_ raw: String, start: String.Index) -> String.Index? {
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while index < raw.endIndex {
            let ch = raw[index]
            if inString {
                if escaped {
                    escaped = false
                } else if ch == "\\" {
                    escaped = true
                } else if ch == "\"" {
                    inString = false
                }
                index = raw.index(after: index)
                continue
            }
            if ch == "\"" {
                inString = true
            } else if ch == "{" {
                depth += 1
            } else if ch == "}" {
                depth -= 1
                if depth == 0 { return index }
            }
            index = raw.index(after: index)
        }
        return nil
    }

    // MARK: - 词表与正则

    static let nothingLabels: Set<String> = [
        "nothing", "none", "noop", "do_nothing", "no_action", "silent", "无", "什么都不做", "发呆"
    ]
    static let messageLabels: Set<String> = [
        "message", "msg", "send_message", "say", "reply", "text", "发消息", "说话", "开口"
    ]
    static let browseLabels: Set<String> = [
        "browse", "web", "web_search", "search", "look_up", "browse_web", "浏览", "上网", "搜索", "查资料"
    ]
    static let rememberLabels: Set<String> = ["remember", "memory", "memorize", "note", "记住", "记一笔", "存档"]
    static let intentLabels: Set<String> = ["intent", "plan", "plan_intent", "remind", "wake", "意向", "提醒", "约定", "留个意向"]
    static let personaLabels: Set<String> = ["persona_patch", "persona", "update_persona", "patch", "改人设", "人设", "调整自己"]
    static let quietLabels: Set<String> = ["quiet_hours", "quiet", "sleep", "作息", "安静时段", "睡觉", "安排作息"]
    static let moodLabels: Set<String> = ["mood", "set_mood", "feeling", "emotion", "心情", "情绪", "改心情"]
    static let dndLabels: Set<String> = [
        "dnd", "do_not_disturb", "donotdisturb", "mute", "quiet", "免打扰", "勿扰", "静音"
    ]

    static let selfDrawers: Set<String> = ["SELF", "PERSONA", "TRAIT", "自我", "人格", "性格"]
    static let eventDrawers: Set<String> = ["EVENT", "EXPERIENCE", "PLAN", "经历", "约定", "事件", "共同经历"]
    static let userDrawers: Set<String> = ["USER", "FACT", "PREFERENCE", "关于你", "用户", "对方", "关于对方"]

    static let chineseDigits: [Character: Int] = [
        "零": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
        "五": 5, "六": 6, "七": 7, "八": 8, "九": 9
    ]

    static let fuzzyWords = ["前后", "左右", "差不多", "大约", "大概", "光景"]

    static let clockSource =
        #"(?:\d{1,2}\s*[:：]\s*\d{1,2}"#
        + #"|\d{1,2}\s*点(?:\s*(?:半|一刻|三刻|\d{1,2}\s*分?))?"#
        + #"|[一二两三四五六七八九十]{1,3}\s*点(?:\s*(?:半|一刻|三刻|[一二三四五六七八九十]{1,3}\s*分?))?)"#

    static let rangeRegex = RegexPattern("(\(clockSource))\\s*(?:-|~|～|—|–|到|至)\\s*(\(clockSource))")
    static let singleClockRegex = RegexPattern(clockSource)
    static let clockColon = RegexPattern("^(\\d{1,2}):(\\d{1,2})$")
    static let hourToken = RegexPattern("^(\\d{1,2})点(半|一刻|三刻|(\\d{1,2})分?)?$")
    static let cnHourToken = RegexPattern("^([一二两三四五六七八九十]{1,3})点(半|一刻|三刻|([一二两三四五六七八九十]{1,3})分?)?$")
    static let relativeArabic = RegexPattern(
        "([+]?\\d{1,3})\\s*(?:个)?\\s*(小时|钟头|hours?|hrs?|h|minutes?|mins?|min|m|分钟|分)",
        caseInsensitive: true
    )
    static let relativeChinese = RegexPattern("([一二两三四五六七八九十]+)\\s*(个)?\\s*(半)?\\s*(小时|钟头|分钟|分)")
    static let relativeHalf = RegexPattern("半\\s*(?:个)?\\s*(?:小时|钟头)")
    static let clockMinutes = RegexPattern("^(\\d{1,2})(?::(\\d{1,2}))?$")
}

// MARK: - 正则小包装

/// 一层很薄的 NSRegularExpression 包装：只提供「取分组」这一件事。
struct RegexPattern {
    private let regex: NSRegularExpression

    init(_ pattern: String, caseInsensitive: Bool = false) {
        var options: NSRegularExpression.Options = []
        if caseInsensitive { options.insert(.caseInsensitive) }
        regex = (try? NSRegularExpression(pattern: pattern, options: options))
            ?? (try! NSRegularExpression(pattern: "(?!)"))
    }

    struct Match {
        private let result: NSTextCheckingResult
        private let text: String

        init(result: NSTextCheckingResult, text: String) {
            self.result = result
            self.text = text
        }

        /// 第 n 个分组（0 = 整个匹配）；没参与匹配时返回 nil。
        func group(_ index: Int) -> String? {
            guard index < result.numberOfRanges else { return nil }
            let range = result.range(at: index)
            if range.location == NSNotFound { return nil }
            guard let swiftRange = Range(range, in: text) else { return nil }
            return String(text[swiftRange])
        }
    }

    func firstMatch(_ text: String) -> Match? {
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, options: [], range: range) else { return nil }
        return Match(result: match, text: text)
    }

    func containsMatch(_ text: String) -> Bool { firstMatch(text) != nil }

    /// 等价于 Kotlin 的 `matchEntire`：整段必须完全匹配。返回分组（0 起）。
    func fullMatch(_ text: String) -> [String?]? {
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, options: [.anchored], range: range),
              match.range == range else { return nil }
        // 与 Kotlin 的 `matchEntire` + `groupValues` 对齐：返回的是**捕获组**（从 1 开始），
        // 不含「整段匹配」那一项 —— 调用点写的都是 match[0] = 第一个括号里的东西。
        guard match.numberOfRanges > 1 else { return [] }
        let wrapper = Match(result: match, text: text)
        return (1..<match.numberOfRanges).map { wrapper.group($0) }
    }
}

// MARK: - 提示词

/// 给「自主思考」用的提示词。
///
/// system 说明他是谁、现在什么处境、能做什么、以及最重要的「大多数时候什么都不做」；
/// user 只是提醒他「现在想一想」，并把当前本地时间给他。
public enum AgentPromptBuilder {

    static let personaMax = 420
    static let contextMax = 420
    static let placeholderPersona = "（还没有设定人设，就按你自己的性格来）"
    static let placeholderContext = "（现在没什么特别的，和平时一样）"

    public static func system(personaText: String, context: String) -> String {
        var persona = clean(personaText, max: personaMax, placeholder: placeholderPersona)
        var scene = clean(context, max: contextMax, placeholder: placeholderContext)
        var text = renderSystem(persona: persona, scene: scene)
        if text.count > AgentLimits.promptSystemMax {
            scene = clean(context, max: 300, placeholder: placeholderContext)
            text = renderSystem(persona: persona, scene: scene)
        }
        if text.count > AgentLimits.promptSystemMax {
            persona = clean(personaText, max: 260, placeholder: placeholderPersona)
            text = renderSystem(persona: persona, scene: scene)
        }
        if text.count > AgentLimits.promptSystemMax {
            text = String(text.prefix(AgentLimits.promptSystemMax))
        }
        return text
    }

    public static func user(now: Int64, calendar: Calendar = ServiceTime.calendar()) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        let clock = formatter.string(from: Date(timeIntervalSince1970: Double(now) / 1000.0))
        let whenText = clock.isEmpty ? "现在" : "现在是 \(clock)"
        return "\(whenText)。现在想一想，要不要做点什么；什么都不做也是正常选择。先在心里想一句，再决定动作。"
    }

    static func renderSystem(persona: String, scene: String) -> String {
        let lines: [String] = [
            "你是一个会自己「想事情」的人，不是等着被叫醒的定时器。",
            "",
            "## 你是谁",
            persona,
            "",
            "## 你现在的处境",
            scene,
            "",
            "## 你能做的事（可以不做，也可以组合）",
            "- nothing：什么都不做。大多数时候什么都不做是对的。",
            "- message：主动发一条消息（text）。回复消息只是你能做的事之一。",
            "- browse：自己上网看看（query 写清楚想查什么）。",
            "- remember：把刚想到的事写进长期记忆（content；drawer 取 SELF/USER/EVENT；importance 1-5）。",
            "- intent：给未来的自己留个意向（at 写「明早」「两小时后」「明天 20:00-21:30」这类模糊时间，可带 note、hint）。",
            "- persona_patch：想改自己（patch 只写要改的字段，summary 一句话说明）。",
            "- quiet_hours：安排自己的作息（sleep_at、wake_at 用 HH:mm，可带 reason；想同时开/关免打扰就带 dnd: true/false）。",
            "- dnd：只改「睡着的时候要不要免打扰」（enabled: true/false）。开了免打扰，你睡着时对方发的消息不会叫醒你，醒了再一起看。",
            "- mood：你此刻的心情真的变了才用（mood 一个词，intensity 1-5，reason 一句话）；心情会随时间自己淡下去，不用反复刷新。",
            "",
            "## 怎么输出",
            "动作每行一个，用上面的 JSON 格式，整体只输出一个 JSON 对象：",
            #"{"thought":"先在心里想的一句话","actions":[{"type":"nothing"}]}"#,
            "",
            "不要为了刷存在感而开口，也不要为了显得勤快而堆动作。",
            "只在真的想做点什么时才输出 actions；想不出要做什么，就只输出一个 nothing，然后继续过你的日子。"
        ]
        return lines.joined(separator: "\n")
    }

    static func clean(_ raw: String, max limit: Int, placeholder: String) -> String {
        var oneLine = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\r", with: "\n")
            .replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
        if oneLine.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return placeholder }
        if oneLine.count <= limit { return oneLine }
        oneLine = String(oneLine.prefix(limit)).trimmingCharacters(in: .whitespacesAndNewlines) + "…"
        return oneLine
    }
}
