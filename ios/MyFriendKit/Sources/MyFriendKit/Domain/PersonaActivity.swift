import Foundation

// ---------------------------------------------------------------------------
// 当前活动（`set_activity` 的落库形状）：他接下来这一段时间在做什么。
//
// 规范：`spec/persona-guidance/README.md` §4。
//
// 与「安静时段」的区别（§4 明文要求工具描述里写清）：
// - `set_quiet_hours` / 睡眠窗口 = **不被打扰**（这段时间他不该被叫醒）；
// - `set_activity` = **我在做事**（可以回，但更短、更晚、明确说几点后再说）。
//   两者可以同时存在：他睡着了同时也在「休息」；他在赶图但没开免打扰。
//
// 到 `until` 自动失效：**读取时判断**，不需要定时任务（也不该有：多一个后台线程
// 只会多一种「状态卡在忙碌」的 bug）。
// ---------------------------------------------------------------------------

/// 他在做什么的种类。界面不显示这些英文名（§4：不展示 `kind` 原值）。
public enum ActivityKind: String, CaseIterable, Sendable {
    case work = "WORK"
    case commute = "COMMUTE"
    case meal = "MEAL"
    case social = "SOCIAL"
    case chore = "CHORE"
    case study = "STUDY"
    case rest = "REST"
    case other = "OTHER"

    /// 认不出就 `OTHER`（契约 `spec/agent-core/cases/tool-args.json` 的 `activity.kind`）。
    public init(stored raw: String?) {
        let key = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        self = ActivityKind(rawValue: key) ?? .other
    }

    public var stored: String { rawValue }
}

/// 这段时间「能不能接话」。
public enum ActivityAvailability: String, CaseIterable, Sendable {
    /// 在忙：主动消息不该发（除非对方先说话），回复更短、更晚，要说几点后再说。
    case busy = "BUSY"
    /// 半忙：可以回，但要说明「在忙，回得慢」。
    case limited = "LIMITED"
    /// 有空。
    case free = "FREE"

    /// 认不出就 `BUSY`（契约默认值：宁可当他忙，也别让他假装有空）。
    public init(stored raw: String?) {
        let key = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        self = ActivityAvailability(rawValue: key) ?? .busy
    }

    public var stored: String { rawValue }
}

/// 主动开口的判定结果。
public enum ActivityProactiveGate: Equatable, Sendable {
    /// 照常掷骰子。
    case allow
    /// 这段时间不主动开口（对方先说话仍然会回）。
    case skip
    /// 降低概率（乘数 < 1）。
    case slowDown(factor: Double)
}

public struct PersonaActivity: Equatable, Sendable {

    /// `LIMITED` 时主动概率乘多少：半忙不是「不想你」，只是「这会儿不方便」。
    public static let limitedProbabilityFactor = 0.4

    public var kind: ActivityKind
    /// 一句话说明他在做什么（「在事务所赶第三版方案」）。**这不是给用户看的原文**，是提示词素材。
    public var activity: String
    /// 持续时间（分钟），已夹在 15–480。
    public var minutes: Int
    public var availability: ActivityAvailability
    /// 开始时刻（毫秒）。
    public var startedAt: Int64
    /// 失效时刻（毫秒）= `startedAt + minutes * 60_000`。
    public var until: Int64

    public init(
        kind: ActivityKind = .other,
        activity: String = "",
        minutes: Int = ToolArgClamp.activityMinutesDefault,
        availability: ActivityAvailability = .busy,
        startedAt: Int64 = 0,
        until: Int64 = 0
    ) {
        let trimmed = activity.trimmingCharacters(in: .whitespacesAndNewlines)
        self.kind = kind
        self.activity = trimmed.count > 200 ? String(trimmed.prefix(200)) : trimmed
        self.minutes = ToolArgClamp.clampActivityMinutes(minutes)
        self.availability = availability
        self.startedAt = startedAt > 0 ? startedAt : 0
        // `until` 的优先级：
        // 1. 显式给了 `until` 就用它（跨端备份可能只带 `until`，Android 的样本就是这样）；
        // 2. 否则 `until = startedAt + minutes`（规范 §4：`until = now + minutes`）；
        // 3. 两者都没有时按「从零点算 minutes 分钟」，调用方读出来会看到 isActive == false。
        if until > 0 {
            self.until = until
        } else if self.startedAt > 0 {
            self.until = self.startedAt + Int64(self.minutes) * 60_000
        } else {
            self.until = Int64(self.minutes) * 60_000
        }
    }

    /// 有没有实质内容（没写活动就不该落库）。
    public var isEmpty: Bool { activity.isEmpty }

    /// 这一刻还有效吗（到 `until` 自动失效，读取时判断）。
    public func isActive(now: Int64) -> Bool {
        guard until > 0 else { return false }
        return now < until
    }

    /// 还剩多少分钟（已经到期就是 0）。
    public func remainingMinutes(now: Int64) -> Int {
        guard isActive(now: now) else { return 0 }
        return max(Int((until - now + 59_999) / 60_000), 1)
    }

    /// 「21:30」这种结束时刻文本（提示词里用）。
    public func untilClockText(calendar: Calendar = ServiceTime.calendar()) -> String {
        PromptClock.clockText(until, calendar: calendar)
    }

    // MARK: - 解析 / 落库

    /// 库里的 JSON；空 / 坏 JSON / 已过期都返回 nil（过期的活动不该继续影响语气）。
    public static func decode(_ raw: String?, now: Int64) -> PersonaActivity? {
        guard let object = PromptGuidanceJSON.object(from: raw) else { return nil }
        let activity = from(object: object, now: now)
        guard !activity.isEmpty, activity.isActive(now: now) else { return nil }
        return activity
    }

    /// 不管过期与否都读出来（界面「他正在做什么」那一栏要显示真实数据；
    /// 过期的活动界面会显示成「没在忙什么」）。
    public static func decodeIgnoringExpiry(_ raw: String?) -> PersonaActivity? {
        guard let object = PromptGuidanceJSON.object(from: raw) else { return nil }
        let activity = from(object: object, now: 0)
        return activity.isEmpty ? nil : activity
    }

    /// 从工具参数建一条活动（`set_activity` 的入口；参数怎么脏都在这里被规整）。
    public static func fromToolArguments(
        activity rawActivity: String?,
        minutes: Any?,
        kind: String?,
        availability: String?,
        now: Int64
    ) -> PersonaActivity {
        PersonaActivity(
            kind: ActivityKind(stored: kind),
            activity: rawActivity ?? "",
            minutes: ToolArgClamp.activityMinutes(minutes),
            availability: ActivityAvailability(stored: availability),
            startedAt: now
        )
    }

    static func from(object: [String: Any], now: Int64) -> PersonaActivity {
        let minutes = ToolArgClamp.activityMinutes(object["minutes"])
        // `startedAt` / `until` 缺字段时按「从现在开始 minutes 分钟」重建：
        // 备份从另一端接回来时可能只带了 minutes（老版本没有这两个键）。
        let startedAt = PromptGuidanceJSON.int(object, "startedAt").map(Int64.init) ?? now
        let until = PromptGuidanceJSON.int(object, "until").map(Int64.init) ?? 0
        return PersonaActivity(
            kind: ActivityKind(stored: PromptGuidanceJSON.string(object, "kind", limit: 20)),
            activity: PromptGuidanceJSON.string(object, "activity", limit: 200) ?? "",
            minutes: minutes,
            availability: ActivityAvailability(
                stored: PromptGuidanceJSON.string(object, "availability", limit: 20)
            ),
            startedAt: startedAt,
            until: until
        )
    }

    public func jsonString() -> String? {
        guard !isEmpty else { return nil }
        let object: [String: Any] = [
            "kind": kind.stored,
            "activity": activity,
            "minutes": minutes,
            "availability": availability.stored,
            "startedAt": startedAt,
            "until": until
        ]
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    // MARK: - 主动开口

    /// 主动消息该不该开口（§4：BUSY 不发、LIMITED 降概率）。
    ///
    /// 只影响**主动**（心跳 / 主动规则）；对方先说话时仍然要回，只是回得更短。
    public static func proactiveGate(_ activity: PersonaActivity?, now: Int64) -> ActivityProactiveGate {
        guard let activity, activity.isActive(now: now) else { return .allow }
        switch activity.availability {
        case .busy: return .skip
        case .limited: return .slowDown(factor: limitedProbabilityFactor)
        case .free: return .allow
        }
    }

    // MARK: - 渲染

    /// 「# 你此刻的状态」里的活动那半句（不含时间前缀，时间由 `PromptBuilder` 拼）。
    ///
    /// 文案来自规范脚手架（`spec/persona-guidance/scaffold.json` 的 `state_busy` / `state_limited`）：
    /// 模板里写的是「你在{activity}」，而活动本身常常就是「在事务所赶方案」这种说法，
    /// 所以填进去之前先去掉一个句首的「在」，否则会读成「你在在事务所…」。
    public func renderStateLine(now: Int64, calendar: Calendar = ServiceTime.calendar()) -> String {
        let untilText = until > 0 ? untilClockText(calendar: calendar) : "稍后"
        let what = Self.activityPhrase(activity)
        switch availability {
        case .busy:
            return PersonaPromptScaffold.fill(
                PersonaPromptScaffold.stateBusy,
                ["activity": what, "until": untilText]
            )
        case .limited:
            return PersonaPromptScaffold.fill(
                PersonaPromptScaffold.stateLimited,
                ["activity": what, "until": untilText]
            )
        case .free:
            // 脚手架没有「有空」这一条；同形状写一句，不额外发明新规则。
            return "你在\(what)，大概 \(untilText) 结束。这段时间你有空。"
        }
    }

    /// 去掉活动句首的「在」（`在事务所赶方案` → `事务所赶方案`），只去一个。
    static func activityPhrase(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("在") { return String(trimmed.dropFirst()) }
        return trimmed
    }

    /// 界面那一行（只写他在做什么，不显示 `kind` 原值；§4）。
    public func displayText(now: Int64) -> String {
        isActive(now: now) ? activity : ""
    }
}
