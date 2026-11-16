import Foundation

// ---------------------------------------------------------------------------
// 世界预设（World Preset）：他所在的那个世界。
//
// 规范：`spec/persona-guidance/README.md` §2。
//
// 为什么值得单独存一份结构：模型写不好「泛泛的温柔回复」，往往不是因为人格不够细，
// 而是因为它手里没有任何**可引用的事实**——现在几点、这个人通常在哪、周三晚上要干什么。
// 世界里放的正是这些事实：日程、关系、义务。提示词里把它们摆出来，模型才会在
// 「他妈周三要打电话」「这个点他一般在赶图」这种地方自然地带出细节。
//
// 解析永远宽容（§2.1：所有字段可空数组；`worldName` 为空表示没有世界预设）。
// 这个文件是纯数据 + 纯渲染：不读库、不读时钟（时间从参数进来）、不发请求。
// ---------------------------------------------------------------------------

/// 一天里的一个时间段：他在做什么。
public struct WorldScheduleItem: Equatable, Sendable {
    /// `工作日` / `周末` / `每天` / `周一`…`周日`（原样保留，渲染时归一化）。
    public var day: String
    /// 24 小时 `HH:mm`；空串表示没写。
    public var start: String
    public var end: String
    public var activity: String

    public init(day: String = "", start: String = "", end: String = "", activity: String = "") {
        self.day = day
        self.start = start
        self.end = end
        self.activity = activity
    }

    public var isEmpty: Bool {
        activity.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// 「工作日 09:00-18:30 在事务所画图」；时间缺一半也照写，不编造。
    public var renderedLine: String {
        var parts: [String] = []
        let dayText = Self.normalizedDayLabel(day)
        if !dayText.isEmpty { parts.append(dayText) }
        let time = Self.timeRangeText(start: start, end: end)
        if !time.isEmpty { parts.append(time) }
        return (parts + [activity]).joined(separator: " ")
    }

    /// 匹配今天是哪一类（`每天` 覆盖一切；认不出的日子不匹配，绝不猜）。
    public func matches(weekday: Int) -> Bool {
        let key = day.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if key.isEmpty { return false }
        if key == "每天" || key == "每日" || key == "daily" || key == "everyday" { return true }
        // Calendar 的 weekday：1 = 周日 … 7 = 周六。
        let isWeekend = weekday == 1 || weekday == 7
        if key == "工作日" || key == "weekday" || key == "weekdays" { return !isWeekend }
        if key == "周末" || key == "weekend" || key == "weekends" { return isWeekend }
        for (index, name) in PromptClock.chineseWeekdays.enumerated() {
            let calendarWeekday = index == 0 ? 1 : index + 1
            if key == name || key == "星期" + String(name.dropFirst()) { return calendarWeekday == weekday }
        }
        if key == "周天" || key == "星期天" { return weekday == 1 }
        return false
    }

    /// 「19:40」在不在这个时间段里；支持跨午夜（`start > end` 视为跨天）。
    public func contains(minuteOfDay: Int) -> Bool {
        guard let from = Self.parseMinutes(start), let to = Self.parseMinutes(end) else { return false }
        if from == to { return true }
        if from < to { return minuteOfDay >= from && minuteOfDay < to }
        return minuteOfDay >= from || minuteOfDay < to
    }

    static func parseMinutes(_ text: String) -> Int? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let pieces = trimmed.split(separator: ":")
        guard pieces.count == 2,
              let hour = Int(pieces[0]), let minute = Int(pieces[1]),
              (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        return hour * 60 + minute
    }

    static func timeRangeText(start: String, end: String) -> String {
        let from = start.trimmingCharacters(in: .whitespacesAndNewlines)
        let to = end.trimmingCharacters(in: .whitespacesAndNewlines)
        if from.isEmpty && to.isEmpty { return "" }
        if to.isEmpty { return from }
        if from.isEmpty { return "到 \(to)" }
        return "\(from)-\(to)"
    }

    /// 日子归一化：认得的写成规范叫法，认不出的原样保留（不丢人写的字）。
    static func normalizedDayLabel(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        let key = trimmed.lowercased()
        switch key {
        case "weekday", "weekdays": return "工作日"
        case "weekend", "weekends": return "周末"
        case "daily", "everyday": return "每天"
        default: return trimmed
        }
    }
}

/// 他世界里的一个人。
public struct WorldRelationship: Equatable, Sendable {
    /// 称呼（「母亲」「老周」）。
    public var name: String
    /// 关系（「母亲」「合伙人」）。
    public var relation: String
    /// 一句细节（「每周三晚打电话」）。
    public var note: String
    /// 亲密度 1–5（规范 §2.3 的「可选亲密度」）；nil = 不填。
    ///
    /// 与 Android `WorldRelationship.intimacy` 同一形状、同一个 key：不填就不写进 JSON，
    /// 老数据/另一端的宽容解析都读不出差别。
    public var intimacy: Int?

    public init(name: String = "", relation: String = "", note: String = "", intimacy: Int? = nil) {
        self.name = name
        self.relation = relation
        self.note = note
        self.intimacy = WorldRelationship.clampedIntimacy(intimacy)
    }

    /// 只认 1–5；越界当成没填（不把 0 或 9 写进提示词）。
    public static func clampedIntimacy(_ raw: Int?) -> Int? {
        guard let raw, (1...5).contains(raw) else { return nil }
        return raw
    }

    public var isEmpty: Bool {
        name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && relation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    public var renderedLine: String {
        var head = name
        let rel = relation.trimmingCharacters(in: .whitespacesAndNewlines)
        if !rel.isEmpty, rel != name { head += head.isEmpty ? rel : "（\(rel)）" }
        let detail = note.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = detail.isEmpty ? head : (head.isEmpty ? detail : "\(head)：\(detail)")
        guard let intimacy else { return body }
        return body.isEmpty ? "亲密度 \(intimacy)/5" : "\(body)，亲密度 \(intimacy)/5"
    }
}

/// 世界预设。字段与规范 §2.1 一一对应。
public struct WorldPreset: Equatable, Sendable {

    // MARK: 编辑器/渲染的**建议**上限（0.12：只用于提示与渲染，不再截断存储）
    //
    // 这些数字以前同时出现在三处：init（截断）、解析（截断）、编辑器（截断输入）。
    // 结果是「用户写下的东西悄悄少一截」，而且两端截到不同的长度，`sourceHash` 跟着漂。
    // 现在它们只表示两件事：
    // 1. 界面上的计数与超限提示（写超了照样保存）；
    // 2. 渲染提示词时 `notes` 只取前 1200 字（与 Android `WORLD_NOTES_CHARS` 一致）。

    /// 描述的建议字数（只说给界面听；存储与哈希保留原文）。
    public static let descriptionMax = 600
    /// 「其他背景（notes）」**渲染**时的字数上限（规范 §2.1）：只影响提示词，不影响存储。
    public static let maxNotesChars = 1200
    /// 日程建议条数（编辑器提示用；存储不截断）。
    public static let maxScheduleItems = 12
    /// 关系建议条数（编辑器提示用；存储不截断）。
    public static let maxRelationships = 8
    /// 义务建议条数（编辑器提示用；存储不截断）。
    public static let maxObligations = 6

    public var worldName: String
    public var era: String
    public var description: String
    public var schedule: [WorldScheduleItem]
    public var relationships: [WorldRelationship]
    public var obligations: [String]
    /// 兜底自由文本（规范 §2.1）：结构化字段表达不了的世界观（历史、规矩、行业细节、忌讳）。
    ///
    /// 渲染成 `其他背景：…` 附在世界段末尾，最多 `maxNotesChars` 字；超出截断（界面另有字数提示）。
    public var notes: String

    public init(
        worldName: String = "",
        era: String = "",
        description: String = "",
        schedule: [WorldScheduleItem] = [],
        relationships: [WorldRelationship] = [],
        obligations: [String] = [],
        notes: String = ""
    ) {
        // 0.12：存储/解析/哈希一律保留原文，这里只做「去首尾空白 / 过滤空条目」这类
        // 语义清洗，不再按字数或条数截断（截断只发生在渲染提示词那一步）。
        self.worldName = Self.trim(worldName)
        self.era = Self.trim(era)
        self.description = Self.trim(description)
        self.schedule = schedule.filter { !$0.isEmpty }
        self.relationships = relationships.filter { !$0.isEmpty }
        self.obligations = PersonaTags.normalize(obligations)
        self.notes = Self.trim(notes)
    }

    /// 空 = 没有世界预设（退回纯人设；提示词里用 `worldEmpty` 那一句说明"没有设定"）。
    public var isEmpty: Bool {
        worldName.isEmpty && era.isEmpty && description.isEmpty
            && schedule.isEmpty && relationships.isEmpty && obligations.isEmpty
            && notes.isEmpty
    }

    // MARK: - 解析

    /// 从库里的 JSON 文本读出来；空 / 坏 JSON 都返回 nil。
    public static func decode(_ raw: String?) -> WorldPreset? {
        guard let object = PromptGuidanceJSON.object(from: raw) else { return nil }
        let preset = from(object: object)
        return preset.isEmpty ? nil : preset
    }

    /// 从任意文本（模型输出，可能带代码块 / 解释）读出来。
    public static func parse(_ text: String?) -> WorldPreset? {
        decode(text)
    }

    /// 从一个 JSON 对象组装（缺字段就空，类型错就当没写）。
    public static func from(object: [String: Any]) -> WorldPreset {
        // 解析也不截断：字段多长就留多长（与 Android 的宽容解析同一口径）。
        let schedule = PromptGuidanceJSON.objectList(object, "schedule")
            .map { item in
                WorldScheduleItem(
                    day: PromptGuidanceJSON.string(item, "day") ?? "",
                    start: PromptGuidanceJSON.string(item, "start") ?? "",
                    end: PromptGuidanceJSON.string(item, "end") ?? "",
                    activity: PromptGuidanceJSON.string(item, "activity") ?? ""
                )
            }
        let relationships = PromptGuidanceJSON.objectList(object, "relationships")
            .map { item in
                WorldRelationship(
                    name: PromptGuidanceJSON.string(item, "name") ?? "",
                    relation: PromptGuidanceJSON.string(item, "relation") ?? "",
                    note: PromptGuidanceJSON.string(item, "note") ?? "",
                    intimacy: WorldRelationship.clampedIntimacy(
                        PromptGuidanceJSON.int(item, "intimacy")
                    )
                )
            }
        return WorldPreset(
            worldName: PromptGuidanceJSON.string(object, "worldName") ?? "",
            era: PromptGuidanceJSON.string(object, "era") ?? "",
            description: PromptGuidanceJSON.string(object, "description") ?? "",
            schedule: schedule,
            relationships: relationships,
            obligations: PromptGuidanceJSON.stringList(object, "obligations"),
            notes: PromptGuidanceJSON.string(object, "notes") ?? ""
        )
    }

    /// 落库用的 JSON（键顺序固定：写进备份后两端 diff 只出现真正的变化）。
    ///
    /// `notes` 只在真有内容时写（空串不落一个空键）；`intimacy` 同理（nil 不写）。
    /// 备份 v5 的 `worldJson` 原样带走整份字符串，所以 notes **不需要升备份版本**。
    public func jsonString() -> String? {
        guard !isEmpty else { return nil }
        var object: [String: Any] = [
            "worldName": worldName,
            "era": era,
            "description": description,
            "schedule": schedule.map { item in
                ["day": item.day, "start": item.start, "end": item.end, "activity": item.activity]
            },
            "relationships": relationships.map { person -> [String: Any] in
                var entry: [String: Any] = [
                    "name": person.name, "relation": person.relation, "note": person.note
                ]
                if let intimacy = person.intimacy { entry["intimacy"] = intimacy }
                return entry
            },
            "obligations": obligations,
            "notes": notes
        ]
        // 空数组照样写（规范 §2.1：所有字段可空数组）；只有整份为空才返回 nil。
        object = object.filter { !($0.value is String && ($0.value as? String ?? "").isEmpty) }
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    // MARK: - 渲染

    /// 此刻按日程「他应该在做什么」；今天没有匹配的时段就 nil。
    ///
    /// 只用来补一句上下文，**不替模型断定**：真到了那一刻，`set_activity` 才是权威。
    public func scheduledActivity(now: Int64, calendar: Calendar = ServiceTime.calendar()) -> WorldScheduleItem? {
        let parts = calendar.dateComponents([.weekday, .hour, .minute], from: Date(timeIntervalSince1970: Double(now) / 1000.0))
        let weekday = parts.weekday ?? 1
        let minuteOfDay = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        return schedule.first { $0.matches(weekday: weekday) && $0.contains(minuteOfDay: minuteOfDay) }
    }

    /// 「# 你所在的世界」那一段（文案来自规范脚手架 `scaffold.json`）。
    ///
    /// 空预设返回空串（由调用方改用 `worldEmpty` 那一句）：不能让提示词里出现只有标题的空段落。
    /// 义务沿用 Android 的 `- 必须：` 形状，`notes` 用 `其他背景：` 形状（规范 §2.1/§6.3）。
    public func renderForPrompt() -> String {
        guard !isEmpty else { return "" }
        var lines: [String] = [PersonaPromptScaffold.worldSection]
        // world_name 是规范里的一行：非空时用模板渲染，为空时整行省略（不自己拼「你所在的地方」）。
        let nameLine = PersonaPromptScaffold.worldNameLine(worldName)
        if !nameLine.isEmpty { lines.append(nameLine) }
        let intro = PersonaPromptScaffold.worldIntroLine(era: era, description: description)
        if !intro.isEmpty { lines.append(intro) }
        for item in schedule {
            lines.append("- " + item.renderedLine)
        }
        for person in relationships {
            lines.append("- " + person.renderedLine)
        }
        for obligation in obligations {
            lines.append("- 必须：" + obligation)
        }
        // 渲染时才截断 notes（规范 §2.1 的 1200 字）：存储里是完整原文，提示词里只取前 1200 字。
        // 与 Android `WorldPreset.renderForPrompt` 的 `notes.take(MAX_NOTES_CHARS)` 完全一致。
        let renderedNotes = notes.count > Self.maxNotesChars
            ? String(notes.prefix(Self.maxNotesChars)) : notes
        if !renderedNotes.isEmpty {
            lines.append(PersonaPromptScaffold.fill(
                PersonaPromptScaffold.worldNotes, ["notes": renderedNotes]
            ))
        }
        lines.append(PersonaPromptScaffold.worldHint)
        return lines.joined(separator: "\n")
    }

    static func trim(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - 编辑器用的「一行一条」文本

    /// 把日程渲染成「一行一条」，供编辑页的多行输入框使用：
    /// `工作日 09:00-18:30 在事务所画图`。
    public static func scheduleLines(_ items: [WorldScheduleItem]) -> String {
        items.map(\.renderedLine).joined(separator: "\n")
    }

    /// 从多行文本读日程：每行形如 `日子 开始-结束 干什么`（顺序可乱，缺哪块就空哪块）。
    ///
    /// 为什么是「一行一条」而不是给每条日程画一排输入框：日程是 3–8 条的小列表，
    /// 多行文本改起来快、也方便从别处粘贴；结构化编辑留给真正需要的人（手填 JSON 也行）。
    public static func parseScheduleLines(_ text: String) -> [WorldScheduleItem] {
        var items: [WorldScheduleItem] = []
        for rawLine in text.split(whereSeparator: { $0 == "\n" || $0 == "\r" }) {
            var line = String(rawLine).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { continue }
            if line.hasPrefix("-") { line = String(line.dropFirst()).trimmingCharacters(in: .whitespaces) }
            var day = ""
            var start = ""
            var end = ""
            // 先摘出时间范围（HH:mm-HH:mm），再从剩下的里面认日子。
            if let range = line.range(of: #"\d{1,2}:\d{2}\s*[-–~]\s*\d{1,2}:\d{2}"#, options: .regularExpression) {
                let time = String(line[range]).replacingOccurrences(of: " ", with: "")
                let parts = time.split(whereSeparator: { $0 == "-" || $0 == "–" || $0 == "~" })
                if parts.count == 2 {
                    start = String(parts[0])
                    end = String(parts[1])
                }
                line = (String(line[line.startIndex..<range.lowerBound])
                    + " " + String(line[range.upperBound...]))
                    .trimmingCharacters(in: .whitespaces)
            }
            let dayKeys = ["工作日", "周末", "每天", "每日"] + PromptClock.chineseWeekdays
            for key in dayKeys where line.hasPrefix(key) {
                day = key
                line = String(line.dropFirst(key.count)).trimmingCharacters(in: .whitespaces)
                break
            }
            let activity = line.trimmingCharacters(in: .whitespaces)
            if day.isEmpty && start.isEmpty && activity.isEmpty { continue }
            items.append(WorldScheduleItem(day: day, start: start, end: end, activity: activity))
        }
        return items
    }

    /// 关系渲染成「一行一条」：`母亲（母亲）：每周三晚打电话`。
    public static func relationshipLines(_ items: [WorldRelationship]) -> String {
        items.map(\.renderedLine).joined(separator: "\n")
    }

    /// 从多行文本读关系：`称呼：细节`，或 `称呼（关系）：细节`。
    public static func parseRelationshipLines(_ text: String) -> [WorldRelationship] {
        var items: [WorldRelationship] = []
        for rawLine in text.split(whereSeparator: { $0 == "\n" || $0 == "\r" }) {
            var line = String(rawLine).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { continue }
            if line.hasPrefix("-") { line = String(line.dropFirst()).trimmingCharacters(in: .whitespaces) }
            var name = line
            var relation = ""
            var note = ""
            if let colon = line.firstIndex(where: { $0 == "：" || $0 == ":" }) {
                name = String(line[line.startIndex..<colon]).trimmingCharacters(in: .whitespaces)
                note = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            }
            if let open = name.firstIndex(of: "（"), let close = name.firstIndex(of: "）"), open < close {
                relation = String(name[name.index(after: open)..<close]).trimmingCharacters(in: .whitespaces)
                name = String(name[name.startIndex..<open]).trimmingCharacters(in: .whitespaces)
            }
            // 亲密度是行尾的可选后缀「，亲密度 4/5」（`renderedLine` 写出来的形状），读回来还给它。
            var intimacy: Int?
            if let range = note.range(of: #"[，,]\s*亲密度\s*([1-5])\s*/\s*5\s*$"#, options: .regularExpression) {
                let matched = String(note[range])
                let digits = matched.filter { $0.isNumber }
                intimacy = Int(String(digits.prefix(1)))
                note = String(note[note.startIndex..<range.lowerBound])
                    .trimmingCharacters(in: .whitespacesAndNewlines)
            }
            if name.isEmpty && note.isEmpty { continue }
            items.append(WorldRelationship(name: name, relation: relation, note: note, intimacy: intimacy))
        }
        return items
    }

    /// 义务：一行一条（也容忍顿号分隔）。
    public static func parseObligationLines(_ text: String) -> [String] {
        let lines = text.split(whereSeparator: { $0 == "\n" || $0 == "\r" }).map(String.init)
        if lines.count > 1 {
            return PersonaTags.normalize(lines)
        }
        return PersonaTags.normalize(
            text.split(whereSeparator: { "，,、；;".contains($0) }).map(String.init)
        )
    }

    public static func obligationLines(_ obligations: [String]) -> String {
        obligations.joined(separator: "\n")
    }
}
