// 来源：app/src/main/java/com/myfriend/app/domain/ImportanceScoring.kt
//
// 重要性打分器：整理器和「联网搜索打分」共用同一套「值得记吗」的判据与解析。
//
// 全部是纯函数，不碰数据库；坏 JSON、缺字段、越界数值都不会抛异常，
// 一律降级成「什么都不做」——整理器和打分器都跑在后台，它们挂掉不该影响聊天。

import Foundation

/// 整理器能下的五种指令。
public enum MemoryOp: String, CaseIterable {
    /// 留着：模型明确说这条重要，加固一点分量。
    case keep = "KEEP"
    /// 合并：把几条零碎记忆并成一条，保留最老的那条，其余归档。
    case merge = "MERGE"
    /// 归档：移出上下文，但数据还留着。
    case archive = "ARCHIVE"
    /// 忘记：真的删掉。
    case forget = "FORGET"
    /// 提权：这条以后会更常用，重要度往上调。
    case raise = "RAISE"
}

/// 一条整理指令。`ids` 里是模型抄回来的记忆编号（也可以是真实 id，整理器会自己解析）。
public struct MemoryOperation: Equatable {
    public var op: MemoryOp
    public var ids: [String]
    /// merge 用的合并后正文；其它指令一般没有。
    public var content: String?
    /// raise 用的新重要度（1..5）。
    public var importance: Int?
    /// 模型给的中文理由，只用于留痕/日志，不落库到记忆本身。
    public var reason: String

    public init(op: MemoryOp, ids: [String] = [], content: String? = nil,
                importance: Int? = nil, reason: String = "") {
        self.op = op
        self.ids = ids
        self.content = content
        self.importance = importance
        self.reason = reason
    }
}

/// 一次整理的完整计划。模型什么都没给时是 `isEmpty` 的空计划，调用方按「什么都不做」处理。
public struct ConsolidationPlan: Equatable {
    public var operations: [MemoryOperation]
    public var summary: String

    public init(operations: [MemoryOperation] = [], summary: String = "") {
        self.operations = operations
        self.summary = summary
    }

    public var isEmpty: Bool { operations.isEmpty }
}

/// 一次「这东西值不值得记」的判断结果。
/// `drawer` 表示记到哪个抽屉：SELF 关于他自己 / USER 关于对方 / EVENT 共同经历。
public struct SearchScore: Equatable {
    public var important: Bool
    public var importance: Int
    public var takeaway: String
    public var drawer: MemoryDrawer

    public init(important: Bool, importance: Int, takeaway: String, drawer: MemoryDrawer = .user) {
        self.important = important
        self.importance = importance
        self.takeaway = takeaway
        self.drawer = drawer
    }
}

/// 重要性打分与整理指令解析。
public enum ImportanceScoring {

    /// 重要度门槛：3 分才是「以后可能用得上」，低于它的搜索结果不值得写进记忆。
    public static let REMEMBER_FLOOR = 3

    public static let MIN_IMPORTANCE = 1
    public static let MAX_IMPORTANCE = 5

    /// 一条记忆正文的上限（和数据仓库的 2000 字限制保持余量）。
    static let MAX_CONTENT_CHARS = 800
    static let MAX_SUMMARY_CHARS = 200
    static let MAX_TAKEAWAY_CHARS = 200
    static let MAX_REASON_CHARS = 120

    /// 单次整理最多认多少条指令：模型偶尔会把同一批记忆反复列，截断比全量执行安全。
    public static let MAX_OPERATIONS = 12

    /// 单条指令最多带多少个 id（merge 也够用了）。
    public static let MAX_IDS_PER_OPERATION = 8

    /// 「值得记吗」的共享判据。
    ///
    /// 整理器（每日整理）和打分器（联网搜索）都用这一份，保证「他愿意记住什么」是同一个标准：
    /// 否则会出现「聊天里不记、上网却记」的分裂人格。
    public static let SHARED_CRITERIA =
        "值得记：稳定的身份与生活背景、长期偏好与忌讳、明确的约定/承诺/纪念日、"
        + "重要的人际关系、能影响以后相处的事实、他自己真正在意的观点。\n"
        + "不值得记：一次性的情绪、寒暄客套、随口闲聊、天气式的天气、模型的猜测与推测、"
        + "已经在记忆里写过的事。\n"
        + "重要度：5 = 身份级（名字、家人、重大约定）；4 = 长期偏好与忌讳；"
        + "3 = 以后可能用得上；2 = 只是近况；1 = 几乎没用。"

    // MARK: 公开：提示词

    /// 整理器的 system 提示词。整理跑在一个「随风叉、用完即弃」的会话里：
    /// 没有历史、没有工具、没有消息表，只靠这段判据 + 下面那份编号清单工作。
    public static func consolidationSystemPrompt(personaSummary: String) -> String {
        let trimmed = personaSummary.trimmed
        let who = trimmed.isEmpty ? "他" : trimmed
        return [
            "你是「\(who)」的长期记忆整理器。你不是在聊天：你在给自己整理抽屉。",
            "把这段日子里记住的事过一遍：留下真正重要的，把零碎重复的并起来，把不重要的挪出抽屉。",
            "",
            "判据：",
            SHARED_CRITERIA,
            "",
            "抽屉语义：",
            "- SELF：关于「你自己」的（性格、习惯、说话方式、你在意什么）",
            "- USER：关于「对方」的（身份、偏好、忌讳、身边的人）",
            "- EVENT：你们之间的经历、约定、纪念日",
            "",
            "清单里的「状态」：还在用 / 已经沉下去 / 已归档。",
            "已经沉下去和已归档的都不再进上下文；对它们下 keep 等于把它重新拿回来，请谨慎。",
            "",
            "清单里可能还会有两列，它们是你自己当初留下的线索：",
            "- 「标签」：这条记忆挂着的短词（人 / 事 / 物 / 情绪，如 妹妹、失眠、杭州），用来在以后被一句话勾出来；",
            "- 「发生在」：这件事**发生**的时间（不是记下来的时间）。人回忆时会说「去年冬天」，靠的就是它。",
            "两列都可能没有；没有就是当时没判断出来。",
            "",
            "只输出 JSON，不要 Markdown 代码块，不要解释。格式：",
            "{\"operations\":[{\"op\":\"keep|merge|archive|forget|raise\",\"ids\":[\"清单里的编号\"],\"content\":\"可选：合并后的正文\",\"importance\":1到5的整数,\"reason\":\"一句中文理由\"}],\"summary\":\"一句话说这次整理做了什么\"}",
            "",
            "规则：",
            "- 一条记忆最多出现在一条指令里；没把握就别下指令，它会自己慢慢变淡。",
            "- merge 至少带 2 个编号，保留最老的那条，content 写合并后的正文（不超过 300 字）。",
            "  **合并时不许丢掉时间和标签**：正文里要写清原来的发生时间（「去年冬天」这种说法就行），",
            "  被并起来那几条的标签要合在一起带过来（最多 5 个）。",
            "- raise 必须带 importance；forget 只给「半年后肯定想不起来」的事，谨慎再用。",
            "- 最多 \(MAX_OPERATIONS) 条指令。"
        ].joined(separator: "\n")
    }

    /// 打分器的 system 提示词：判断「他刚上网看到的这些东西值不值得记住」。
    /// 和整理器共用 `SHARED_CRITERIA`，只是输出格式不同。
    public static func searchScoringSystemPrompt(personaSummary: String) -> String {
        let trimmed = personaSummary.trimmed
        let who = trimmed.isEmpty ? "他" : trimmed
        return [
            "你负责替「\(who)」判断：他刚上网看到的这些东西，值不值得记住。",
            "",
            "判据：",
            SHARED_CRITERIA,
            "",
            "只输出 JSON，不要 Markdown 代码块，不要解释。格式：",
            "{\"important\":true或false,\"importance\":1到5的整数,\"takeaway\":\"一句话，用第三人称写成一条记忆\",\"drawer\":\"SELF|USER|EVENT\"}",
            "规则：",
            "- important 为 false 时 importance 填 1。",
            "- takeaway 只在 important 为 true 时才有意义，不超过 80 字，不要写「搜索结果显示」这类废话。",
            "- drawer：SELF 关于他自己、USER 关于对方、EVENT 你们之间的事。"
        ].joined(separator: "\n")
    }

    // MARK: 公开：解析

    /// 把模型给的重要度夹进 1..5。
    public static func clampImportance(_ value: Int) -> Int {
        min(Swift.max(value, MIN_IMPORTANCE), MAX_IMPORTANCE)
    }

    /// 宽松解析模型给的 tags：认 JSON 数组（`["妹妹","失眠"]`）、单个字符串、逗号/顿号分隔的散串。
    ///
    /// 结果交给 `MemoryTags` 清洗：去空白、去重、单个标签 ≤ 12 字、最多 5 个。
    /// 坏数据（`null`、数字、乱七八糟的括号）一律当「没有标签」，不抛异常——
    /// 标签只是查记忆的辅助，不能因为它把整条记忆丢了。
    public static func parseTags(_ raw: String?) -> [String] {
        let text = (raw ?? "").trimmed
        if text.isEmpty || text.lowercased() == "null" { return [] }
        let parsed = jsonValue(text)
        var pieces: [String] = []
        if let array = parsed as? [Any] {
            for element in array where isPrimitive(element) {
                pieces.append(primitiveString(element))
            }
        } else if let value = parsed, isPrimitive(value) {
            // JsonNull 的 contentOrNull 是 nil：Kotlin 端 listOfNotNull 之后就是空列表。
            if !(value is NSNull) { pieces.append(primitiveString(value)) }
        } else {
            pieces = [text]
        }
        let split = pieces
            .flatMap { splitBySeparators($0, separators: [",", "，", "、", ";", "；", "/", "|", "\n"]) }
            .map { $0.trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "\"'[]")) }
            .filter { !$0.isEmpty }
        return MemoryTags.sanitize(split)
    }

    /// 把模型写的「昨天 / 上周三 / 去年冬天 / 上个月」换算成毫秒；算不出来返回 nil（= 不写这个字段）。
    ///
    /// 换算规则：拿到的是那段时间的**区间**，取中点当作「这件事发生的时间」。
    /// 只精确到区间是诚实的——人本来也只记得「去年冬天」，不会记得具体哪一天；
    /// 取中点既不会把「去年冬天」错算成「今年春天」，也不会让时间指代加权落空。
    ///
    /// 如果模型直接给了毫秒时间戳（≥ 10 位数字），就原样采用；给年份（如 2025）不算时间戳。
    public static func parseRelativeTime(_ raw: String?, now: Int64) -> Int64? {
        let text = (raw ?? "").trimmed
        if text.isEmpty || text.lowercased() == "null" { return nil }
        if text.count >= 10 {
            if let millis = Int64(text), millis > 0 { return millis }
        }
        guard let span = ChineseTerms.timeSpanOf(text, now: now) else { return nil }
        return span.center
    }

    /// 这条搜索命中值不值得落库：模型说重要，并且重要度到 3 分。
    public static func shouldRemember(_ score: SearchScore) -> Bool {
        score.important && score.importance >= REMEMBER_FLOOR
    }

    /// 宽松解析整理器输出。
    ///
    /// 能容忍：```json 代码块、前后多余的解释文字、字段名大小写/别名（op/action、ids/id）、
    /// 编号是数字还是字符串、重要度是数字还是字符串、未知 op、空 ids。
    /// 不能容忍的（坏 JSON、不是对象、完全没有 operations）→ 返回空计划，让调用方什么都不做。
    public static func parseConsolidation(_ raw: String) -> ConsolidationPlan {
        guard let root = extractObject(raw) else { return ConsolidationPlan() }
        let summary = String(stringOf(root, ["summary", "一句话", "note"]).prefix(MAX_SUMMARY_CHARS))
        guard let array = (root["operations"] ?? root["ops"] ?? root["actions"]) as? [Any] else {
            return ConsolidationPlan(operations: [], summary: summary)
        }
        let operations = array.compactMap { element -> MemoryOperation? in
            guard let object = element as? [String: Any] else { return nil }
            return parseOperation(object)
        }
        return ConsolidationPlan(operations: Array(operations.prefix(MAX_OPERATIONS)), summary: summary)
    }

    /// 宽松解析打分器输出；一个对象都抠不出来时返回 nil（= 这次不记）。
    ///
    /// 缺字段时的降级：
    /// - 没有 important：按 importance ≥ `REMEMBER_FLOOR` 推断；
    /// - 没有 importance：important 为真按 3 分，否则 1 分；
    /// - 没有 drawer / drawer 不认识：按 USER；
    /// - 没有 takeaway：空串（调用方不会用它写记忆）。
    public static func parseSearchScore(_ raw: String) -> SearchScore? {
        guard let root = extractObject(raw) else { return nil }
        let importanceRaw = intOf(root, ["importance", "weight", "score"]) ?? intOf(root, ["level"])
        let importantRaw = booleanOf(root, ["important", "worth", "remember"])
        let importance: Int
        if let importanceRaw {
            importance = clampImportance(importanceRaw)
        } else if importantRaw == true {
            importance = REMEMBER_FLOOR
        } else {
            importance = MIN_IMPORTANCE
        }
        let important = importantRaw ?? (importance >= REMEMBER_FLOOR)
        let takeaway = String(stringOf(root, ["takeaway", "summary", "content", "memory"])
            .prefix(MAX_TAKEAWAY_CHARS))
        let drawer = drawerOf(stringOf(root, ["drawer", "type", "category"]))
        return SearchScore(important: important, importance: importance, takeaway: takeaway, drawer: drawer)
    }

    // MARK: 内部：解析

    static func parseOperation(_ object: [String: Any]) -> MemoryOperation? {
        guard let op = opOf(stringOf(object, ["op", "operation", "action", "type"])) else { return nil }
        let ids = idsOf(object)
        if ids.isEmpty { return nil }
        let importance = intOf(object, ["importance", "weight", "score"]).map { clampImportance($0) }
        let contentRaw = String(stringOf(object, ["content", "text", "merged", "body"])
            .prefix(MAX_CONTENT_CHARS))
        let content = contentRaw.trimmed.isEmpty ? nil : contentRaw
        let reason = String(stringOf(object, ["reason", "why", "note"]).prefix(MAX_REASON_CHARS))

        // 各指令的最低要求：merge 至少要两条才叫合并；raise 没有目标重要度就没意义。
        let valid: Bool
        switch op {
        case .merge: valid = ids.count >= 2
        case .raise: valid = importance != nil
        default: valid = true
        }
        if !valid { return nil }
        return MemoryOperation(op: op,
                               ids: Array(ids.prefix(MAX_IDS_PER_OPERATION)),
                               content: content,
                               importance: importance,
                               reason: reason)
    }

    static func idsOf(_ object: [String: Any]) -> [String] {
        guard let raw = object["ids"] ?? object["id"] ?? object["memoryIds"] else { return [] }
        var collected: [String] = []
        if let array = raw as? [Any] {
            for element in array where isPrimitive(element) {
                collected.append(primitiveString(element))
            }
        } else if isPrimitive(raw) {
            collected = [primitiveString(raw)]
        }
        var seen = Set<String>()
        var out: [String] = []
        let pieces = collected.flatMap {
            splitBySeparators($0, separators: [",", "，", "、", ";", "；", " "])
        }
        for piece in pieces {
            let trimmed = piece.trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "\"'[]"))
            if trimmed.isEmpty { continue }
            if seen.insert(trimmed).inserted { out.append(trimmed) }
        }
        return out
    }

    static func opOf(_ raw: String) -> MemoryOp? {
        let key = raw.trimmed.uppercased()
        if key.isEmpty { return nil }
        if key == "KEEP" || key.contains("保留") || key.contains("留着") { return .keep }
        if key == "MERGE" || key.contains("合并") || key.contains("并起") { return .merge }
        if key == "ARCHIVE" || key.contains("归档") || key.contains("沉下去") { return .archive }
        if key == "FORGET" || key.contains("忘记") || key.contains("遗忘") || key.contains("删除") {
            return .forget
        }
        if key == "RAISE" || key.contains("提权") || key.contains("提升") || key.contains("上调") {
            return .raise
        }
        return nil
    }

    static func drawerOf(_ raw: String) -> MemoryDrawer {
        let key = raw.trimmed.uppercased()
        if let drawer = MemoryDrawer(rawValue: key) { return drawer }
        // 认不出来就交给统一映射（它会把 FACT/PREFERENCE 之类归到 USER）。
        return key.isEmpty ? .user : MemoryDrawer.fromCategoryName(key)
    }

    /// 从一段可能带 ``` 代码块或前后解释的文字里抠出第一个 JSON 对象。
    static func extractObject(_ raw: String) -> [String: Any]? {
        if raw.trimmed.isEmpty { return nil }
        var cleaned = raw.trimmed
        cleaned = cleaned.removing(prefix: "```json")
        cleaned = cleaned.removing(prefix: "```JSON")
        cleaned = cleaned.removing(prefix: "```")
        if cleaned.hasSuffix("```") { cleaned = String(cleaned.dropLast(3)) }
        cleaned = cleaned.trimmed
        let chars = Array(cleaned)
        guard let start = chars.firstIndex(of: "{"), let end = chars.lastIndex(of: "}"), end > start else {
            return nil
        }
        let slice = String(chars[start...end])
        return jsonValue(slice) as? [String: Any]
    }

    static func stringOf(_ object: [String: Any], _ keys: [String]) -> String {
        for key in keys {
            guard let element = object[key], isPrimitive(element) else { continue }
            let text = primitiveString(element)
            if !text.trimmed.isEmpty { return text }
        }
        return ""
    }

    static func intOf(_ object: [String: Any], _ keys: [String]) -> Int? {
        for key in keys {
            guard let element = object[key], isPrimitive(element) else { continue }
            if let number = element as? NSNumber, !isBoolean(number) { return number.intValue }
            if let text = Int(primitiveString(element).trimmed) { return text }
        }
        return nil
    }

    static func booleanOf(_ object: [String: Any], _ keys: [String]) -> Bool? {
        for key in keys {
            guard let element = object[key], isPrimitive(element) else { continue }
            if let number = element as? NSNumber, isBoolean(number) { return number.boolValue }
            switch primitiveString(element).trimmed.lowercased() {
            case "true", "1", "yes", "y", "是", "重要", "值得": return true
            case "false", "0", "no", "n", "否", "不重要", "不值得": return false
            default: continue
            }
        }
        return nil
    }

    /// JSON 里的「标量」：字符串 / 数字 / 布尔 / null。对象和数组不算。
    static func isPrimitive(_ value: Any) -> Bool {
        value is String || value is NSNumber || value is NSNull
    }

    /// 对应 Kotlin 的 `JsonPrimitive.contentOrNull.orEmpty()`。
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

    static func jsonValue(_ text: String) -> Any? {
        guard let data = text.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }

    static func splitBySeparators(_ text: String, separators: Set<Character>) -> [String] {
        var parts: [String] = []
        var current = ""
        for ch in text {
            if separators.contains(ch) {
                parts.append(current)
                current = ""
            } else {
                current.append(ch)
            }
        }
        parts.append(current)
        return parts
    }
}

extension ImportanceScoring {
    /// 分隔符集合在 Swift 里要显式给出 Character，字符串字面量方便阅读。
    static func splitBySeparators(_ text: String, separators: [String]) -> [String] {
        var set = Set<Character>()
        for separator in separators {
            for ch in separator { set.insert(ch) }
        }
        return splitBySeparators(text, separators: set)
    }
}
