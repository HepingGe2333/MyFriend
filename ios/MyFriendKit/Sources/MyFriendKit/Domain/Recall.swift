// 来源：app/src/main/java/com/myfriend/app/domain/Recall.kt
//
// 「回忆」：他想了多深，就允许翻多旧、翻多杂。
//
// 三档不是三个参数，而是三种态度：
// - QUICK 随口想想：只看还在用的记忆，最近 + 重要的优先，翻到一点就够；
// - NORMAL 认真回忆：把沉下去（DORMANT）的记忆也捞回来，再翻最近聊过的内容；
// - DEEP 翻箱倒柜：连归档（ARCHIVED）的都翻，跨全部会话搜聊天记录，还带上他自己上网看到的东西。
//
// 本文件是纯领域逻辑：不碰数据库、不读时钟（`now` 由调用方传入），
// 所有入口对空数据与坏数据都安全，不会抛异常。
//
// 只移植**纯函数部分**：档位定义、记忆/聊天/浏览 → 命中的构造、排序、渲染文案。
// 需要读写数据库的编排（查询倒排、拉会话、落库回写）留在数据层。

import Foundation

/// 回忆强度。level 是给模型和参数解析用的数字档位。
public enum RecallDepth: Int, CaseIterable {
    case quick = 1
    case normal = 2
    case deep = 3

    /// 数字档位（1/2/3）：给模型和参数解析用。和 Kotlin 的 `val level: Int` 一致。
    public var level: Int { rawValue }

    /// 给模型看的档位名字（也会进提示词，所以保持中文）。
    public var label: String {
        switch self {
        case .quick: return "随口想想"
        case .normal: return "认真回忆"
        case .deep: return "翻箱倒柜"
        }
    }

    /// 界面显示用的档位名（跟随界面语言）。提示词里仍用 `label`。
    public var displayLabel: String {
        switch self {
        case .quick: return String(localized: "随口想想")
        case .normal: return String(localized: "认真回忆")
        case .deep: return String(localized: "翻箱倒柜")
        }
    }

    /// 给模型看的内容字数上限：档位越高，翻出来的越啰嗦。
    public var maxChars: Int {
        switch self {
        case .quick: return 1200
        case .normal: return 3000
        case .deep: return 6000
        }
    }

    /// 最多给多少条命中，避免一次回忆把上下文塞满。
    public var maxHits: Int {
        switch self {
        case .quick: return 6
        case .normal: return 12
        case .deep: return 24
        }
    }

    /// 时间衰减的半衰期（天）：越深的档位越不嫌旧。
    public var halfLifeDays: Double {
        switch self {
        case .quick: return 21.0
        case .normal: return 60.0
        case .deep: return 180.0
        }
    }

    /// 是否带上沉下去（DORMANT）的记忆。
    public var includeDormant: Bool { self != .quick }

    /// 是否翻已归档（ARCHIVED）的记忆。
    public var includeArchived: Bool { self == .deep }

    /// 是否按关键词搜聊天记录。
    public var includeChatSearch: Bool { self != .quick }

    /// 是否再按会话扫一遍最近的消息做补充。
    public var scanRecentChats: Bool { self == .deep }

    /// 是否带上他独自上网看到的摘要。
    public var includeBrowsing: Bool { self != .quick }

    /// 这一档能不能看到某种状态的记忆。
    public func allows(_ state: RecallMemoryState) -> Bool {
        switch state {
        case .active: return true
        case .dormant: return includeDormant
        case .archived: return includeArchived
        }
    }

    /// 数字档位：0 及以下按最浅算，4 及以上按最深算（模型偶尔会给出越界值）。
    public static func fromLevel(_ level: Int) -> RecallDepth {
        if level <= 1 { return .quick }
        if level == 2 { return .normal }
        return .deep
    }

    /// 宽松解析模型给的 depth：数字（1/2/3，越界自动收拢）、英文（quick/normal/deep…）、
    /// 中文别名（随口想想 / 认真回忆 / 翻箱倒柜…）都认；认不出来时退回 NORMAL，不抛异常。
    public static func parse(_ raw: String?) -> RecallDepth {
        let text = (raw ?? "").trimmed.lowercased()
        if text.isEmpty { return .normal }
        // 先看数字：容忍 "3"、"第3档"、"level 2" 这类写法
        let digits = text.filter { $0.isNumber }
        if let numeric = Int(digits) { return fromLevel(numeric) }
        if QUICK_ALIASES.contains(text) { return .quick }
        if DEEP_ALIASES.contains(text) { return .deep }
        if NORMAL_ALIASES.contains(text) { return .normal }
        return .normal
    }

    static let QUICK_ALIASES: Set<String> = [
        "quick", "q", "fast", "light", "shallow", "simple",
        "随口", "随口想想", "随便想想", "随便", "简单想想", "浅", "浅一点", "浅尝", "快", "快速", "一", "第一档"
    ]

    static let NORMAL_ALIASES: Set<String> = [
        "normal", "n", "default", "medium", "mid", "standard",
        "认真", "认真回忆", "回忆", "正常", "普通", "一般", "中", "想一想", "二", "第二档"
    ]

    static let DEEP_ALIASES: Set<String> = [
        "deep", "d", "thorough", "full", "max", "deeper",
        "翻箱倒柜", "深入", "深", "最深", "仔细", "仔细想想", "彻底", "挖", "挖一挖", "全部", "都想", "三", "第三档"
    ]
}

/// 命中来自哪里：他自己的记忆 / 聊天记录 / 他独自上网看到的。
public enum RecallKind: String, CaseIterable {
    case memory = "MEMORY"
    case chat = "CHAT"
    case browsing = "BROWSING"

    public var label: String {
        switch self {
        case .memory: return "记忆"
        case .chat: return "聊天"
        case .browsing: return "他自己看到的"
        }
    }
}

/// 记忆的生命周期状态（纯领域名字，数据库里存的是同名字符串）。
public enum RecallMemoryState: String, CaseIterable {
    case active = "ACTIVE"
    case dormant = "DORMANT"
    case archived = "ARCHIVED"
}

/// 一条被翻出来的东西。
///
/// - `kind`: 来源（记忆 / 聊天 / 他看到的）
/// - `text`: 给模型看的正文，已经裁剪过
/// - `at`: 这件事发生（或上次被碰到）的时间，用来算新鲜度与显示日期
/// - `weight`: 0..1 的「本身有多重要」，与关键词命中、时间衰减一起决定排序
/// - `note`: 额外说明，例如归档记忆的「这是很久以前的事，他不太确定了」
/// - `source`: 来源补充，例如聊天所在的会话标题、记忆所在的抽屉
/// - `refId`: 记忆 id；聊天/浏览为空。仓库据此更新记忆的被使用次数。
/// - `tags`: 记忆的标签（人 / 事 / 物 / 情绪）；聊天与浏览为空。
/// - `happenedAt`: 这件事发生的时间；只有记忆才有，也只有在推断出来时才非空。
/// - `fromIndex`: 是不是倒排索引直接命中的：命中说明「词元真的对上了」，比子串偶然更可信。
public struct RecallHit: Equatable {
    public var kind: RecallKind
    public var text: String
    public var at: Int64
    public var weight: Double
    public var note: String
    public var source: String
    public var refId: String
    public var tags: [String]
    public var happenedAt: Int64?
    public var fromIndex: Bool

    public init(kind: RecallKind, text: String, at: Int64, weight: Double = 0.5,
                note: String = "", source: String = "", refId: String = "",
                tags: [String] = [], happenedAt: Int64? = nil, fromIndex: Bool = false) {
        self.kind = kind
        self.text = text
        self.at = at
        self.weight = weight
        self.note = note
        self.source = source
        self.refId = refId
        self.tags = tags
        self.happenedAt = happenedAt
        self.fromIndex = fromIndex
    }
}

/// 一行记忆的回忆视图：在「记忆行」之上补上状态与剩余分量。
public struct RecallMemoryInput {
    public var id: String
    public var content: String
    public var drawer: MemoryDrawer
    public var importance: Int
    public var pinned: Bool
    public var lastUsedAt: Int64
    public var createdAt: Int64
    public var state: RecallMemoryState
    public var strength: Double
    public var archivedAt: Int64?
    /// 结构化标签：给「他妹妹那件事」这类指代多一条线索。
    public var tags: [String]
    /// 这件事发生的时间（不是被记住的时间）；没有就是 nil。
    public var happenedAt: Int64?

    public init(id: String, content: String, drawer: MemoryDrawer = .event, importance: Int = 3,
                pinned: Bool = false, lastUsedAt: Int64 = 0, createdAt: Int64 = 0,
                state: RecallMemoryState = .active, strength: Double = 1.0,
                archivedAt: Int64? = nil, tags: [String] = [], happenedAt: Int64? = nil) {
        self.id = id
        self.content = content
        self.drawer = drawer
        self.importance = importance
        self.pinned = pinned
        self.lastUsedAt = lastUsedAt
        self.createdAt = createdAt
        self.state = state
        self.strength = strength
        self.archivedAt = archivedAt
        self.tags = tags
        self.happenedAt = happenedAt
    }

    /// 时间轴上的位置：优先用「上次被用到」，没有就用创建时间。
    public var at: Int64 { lastUsedAt > 0 ? lastUsedAt : createdAt }
}

/// 一次回忆的结果。`isEmpty` 为 true 时 `rendered` 是一句人话（不是错误）。
public struct RecallResult: Equatable {
    public var hits: [RecallHit]
    public var rendered: String
    public var isEmpty: Bool

    public init(hits: [RecallHit] = [], rendered: String = "", isEmpty: Bool = true) {
        self.hits = hits
        self.rendered = rendered
        self.isEmpty = isEmpty
    }
}

/// 回忆的排序与渲染（对应 Kotlin 的 `object Recall`）。
public enum Recall {

    // MARK: 权重

    /// 关键词命中的权重：回忆首先是「找对东西」。
    static let KEYWORD_WEIGHT = 2.0

    /// 标签命中：「他妹妹」这类指代，除了字面，还有他当初打的标签。
    static let TAG_WEIGHT = 0.8

    /// 一条记忆最多算几个标签命中。
    static let TAG_MAX_HITS = 2

    /// 时间指代邻近（「去年冬天」）：事件发生时间落在区间里的稍微靠前。
    static let TIME_WEIGHT = 0.9

    /// 时间区间外的软衰减半衰期（天）。
    static let TIME_HINT_HALF_LIFE_DAYS = 120.0

    /// 倒排索引直接命中的小加成：词元对上比子串偶然更可信，但不该盖过真正的相关度。
    static let INDEX_BONUS = 0.25

    /// 新鲜度的权重：不是越新越好，但太旧的东西该往后排。
    static let FRESHNESS_WEIGHT = 0.6

    /// 本身重要度的权重。
    static let QUALITY_WEIGHT = 1.0

    /// 单条命中的正文上限，防止一条长记忆占满整段回忆。
    static let MAX_HIT_TEXT_CHARS = 300

    static let DAY_MILLIS: Int64 = 86_400_000

    /// 归档命中时必须说的那句「他不太确定」。
    public static let ARCHIVED_NOTE = "这是很久以前的事，他不太确定了"

    // MARK: 记忆 → 命中

    /// 把记忆变成命中：按档位筛状态（QUICK 只看 ACTIVE，NORMAL 加上 DORMANT，DEEP 连 ARCHIVED 都翻），
    /// 再按「关键词 + 时间 + 重要度」排序取前 N 条。
    public static func memoryHits(
        memories: [RecallMemoryInput],
        query: String,
        depth: RecallDepth,
        now: Int64,
        indexHits: Set<String> = []
    ) -> [RecallHit] {
        if memories.isEmpty { return [] }
        let hits = memories
            .filter { !$0.content.trimmed.isEmpty }
            .filter { depth.allows($0.state) }
            .map { toHit($0, fromIndex: indexHits.contains($0.id)) }
        return Array(rank(hits, query: query, depth: depth, now: now).prefix(depth.maxHits))
    }

    static func toHit(_ memory: RecallMemoryInput, fromIndex: Bool) -> RecallHit {
        let safeStrength = min(Swift.max(memory.strength, 0.0), 1.0)
        // 沉下去的记忆还剩下多少分量；归档的只剩一点点，语气上要显得不确定。
        let stateFactor: Double
        switch memory.state {
        case .active: stateFactor = 0.75 + 0.25 * safeStrength
        case .dormant: stateFactor = 0.45 + 0.25 * safeStrength
        case .archived: stateFactor = 0.20 * safeStrength
        }
        let quality = (Double(min(Swift.max(memory.importance, 1), 5)) / 5.0) * 0.7
            + (memory.pinned ? 0.3 : 0.0)
        return RecallHit(
            kind: .memory,
            text: String(memory.content.trimmed.prefix(MAX_HIT_TEXT_CHARS)),
            at: memory.at,
            weight: min(Swift.max(quality * stateFactor, 0.0), 1.0),
            note: memory.state == .archived ? ARCHIVED_NOTE : "",
            source: memory.drawer.label,
            refId: memory.id,
            tags: memory.tags,
            happenedAt: memory.happenedAt,
            fromIndex: fromIndex
        )
    }

    // MARK: 聊天 / 浏览 → 命中

    /// 一条聊天记录。`role` 传 "USER" / "ASSISTANT" 时会补上「谁说的」。
    public static func chatHit(text: String, sessionTitle: String, at: Int64, role: String? = nil) -> RecallHit {
        let body = String(text.trimmed.prefix(MAX_HIT_TEXT_CHARS))
        let prefix: String
        switch (role ?? "").trimmed.uppercased() {
        case "USER": prefix = "对方说："
        case "ASSISTANT": prefix = "我说过："
        default: prefix = ""
        }
        let title = String(sessionTitle.trimmed.prefix(30))
        return RecallHit(
            kind: .chat,
            text: prefix + body,
            at: at,
            weight: 0.45,
            source: title.trimmed.isEmpty ? "" : "《\(title)》"
        )
    }

    /// 他独自上网看到的摘要；缓存里没有时间戳，只能按调用方给的「刚刚」算。
    public static func browsingHit(digest: String, at: Int64) -> RecallHit {
        RecallHit(
            kind: .browsing,
            text: String(digest.trimmed.prefix(MAX_HIT_TEXT_CHARS * 3)),
            at: at,
            weight: 0.40
        )
    }

    // MARK: 排序

    /// 综合排序：关键词命中 + 时间衰减 + 重要度/深度加权。
    ///
    /// 时间衰减的半衰期跟着档位走（QUICK 21 天 / NORMAL 60 天 / DEEP 180 天），
    /// 所以「翻箱倒柜」时很久以前的事才排得上来。
    public static func rank(_ hits: [RecallHit], query: String, depth: RecallDepth, now: Int64) -> [RecallHit] {
        if hits.isEmpty { return [] }
        // 时间指代只在这一次查询里解析一次：整批命中共用同一个区间。
        let span = ChineseTerms.timeSpanOf(query, now: now)
        return hits.sorted { a, b in
            let scoreA = score(a, query: query, depth: depth, now: now, span: span)
            let scoreB = score(b, query: query, depth: depth, now: now, span: span)
            if scoreA != scoreB { return scoreA > scoreB }
            if a.at != b.at { return a.at > b.at }
            return Lexicographic.utf16Less(a.text, b.text)
        }
    }

    static func score(_ hit: RecallHit, query: String, depth: RecallDepth,
                      now: Int64, span: TimeSpan?) -> Double {
        let keyword = keywordScore(text: hit.text + " " + hit.source, query: query)
        let fresh = freshness(at: hit.at, now: now, halfLifeDays: depth.halfLifeDays)
        let tag = tagScore(hit.tags, query: query)
        let time = timeScore(hit.happenedAt, span: span)
        var total = keyword * KEYWORD_WEIGHT + fresh * FRESHNESS_WEIGHT
            + min(Swift.max(hit.weight, 0.0), 1.0) * QUALITY_WEIGHT
            + tag * TAG_WEIGHT + time * TIME_WEIGHT
        if hit.fromIndex { total += INDEX_BONUS }
        return total
    }

    /// 标签命中 0..1（最多算 `TAG_MAX_HITS` 个）。
    static func tagScore(_ tags: [String], query: String) -> Double {
        let hits = ChineseTerms.tagHits(query: query, tags: tags, max: TAG_MAX_HITS)
        if hits <= 0 { return 0.0 }
        return Double(hits) / Double(TAG_MAX_HITS)
    }

    /// 时间指代邻近 0..1：区间里满分，区间外按半衰期给残值；没有发生时间就不加权。
    static func timeScore(_ happenedAt: Int64?, span: TimeSpan?) -> Double {
        guard let happenedAt, happenedAt > 0, let span else { return 0.0 }
        if span.contains(at: happenedAt) { return 1.0 }
        return min(Swift.max(exp(-span.distanceDays(at: happenedAt) / TIME_HINT_HALF_LIFE_DAYS) * 0.5, 0.0), 1.0)
    }

    /// 关键词命中率 0..1：整段包含查询算满分；否则按中文二元组 / 英文单词的加权覆盖率与余弦算。
    /// 空查询一律 0（此时完全靠新鲜度与重要度排）。
    ///
    /// 这里和「进上下文」的召回共用同一个 ChineseTerms：同一个查询词在两处拆出同一批词元，
    /// 否则会出现「上下文里挑中的记忆，回忆工具却翻不到」这种自相矛盾。
    public static func keywordScore(text: String, query: String) -> Double {
        ChineseTerms.relevance(query: query, document: text)
    }

    static func freshness(at: Int64, now: Int64, halfLifeDays: Double) -> Double {
        if at <= 0 { return 0.0 }
        let days = Double(Swift.max(now - at, 0)) / Double(DAY_MILLIS)
        return exp(-days / Swift.max(halfLifeDays, 1.0))
    }

    // MARK: 渲染

    /// 给模型看的中文段落：每条命中都带来源与日期，如「（记忆 · 3 周前）」「（聊天 · 昨天）」，
    /// 再按档位裁剪字数。没有命中时返回 `emptyAnswer`，而不是空白。
    public static func render(_ hits: [RecallHit], depth: RecallDepth, query: String,
                              now: Int64 = DomainClock.nowMillis) -> String {
        if hits.isEmpty { return emptyAnswer(query: query, depth: depth) }
        let header = query.trimmed.isEmpty
            ? "## 你翻了翻记忆（\(depth.label)）"
            : "## 关于「\(String(query.trimmed.prefix(40)))」，你翻了翻记忆（\(depth.label)）"
        let tail: String
        switch depth {
        case .quick: tail = "\n只是随口想想：想不起来就直说记不清，别硬圆。"
        case .normal: tail = "\n这些是翻出来的旧印象：自然带进话里就好，不要逐条复述。"
        case .deep: tail = "\n翻不到的，就是真的找不回来了：别把旧印象说成确定的事，也别编。"
        }
        let budget = Swift.max(depth.maxChars - header.count - tail.count - 2, 160)
        var body = ""
        for hit in hits {
            let line = "- （\(sourceLabel(hit, now: now))）\(String(hit.text.prefix(MAX_HIT_TEXT_CHARS)))"
            if body.count + line.count + 1 > budget { break }
            body += line + "\n"
        }
        if body.isEmpty, let first = hits.first {
            // 预算再紧也要给一条：截断正文，而不是整段留空。
            let label = sourceLabel(first, now: now)
            let room = Swift.max(budget - label.count - 4, 24)
            body = "- （" + label + "）" + String(first.text.prefix(room)) + "\n"
        }
        let full = header + "\n" + body + tail
        return String(full.prefix(depth.maxChars))
    }

    /// 「记忆 · 3 周前 · 关于对方」，必要时再补一句不确定。
    public static func sourceLabel(_ hit: RecallHit, now: Int64 = DomainClock.nowMillis) -> String {
        var parts: [String] = [hit.kind.label, dateLabel(at: hit.at, now: now)]
        let source = hit.source.trimmed
        if !source.isEmpty { parts.append(String(source.prefix(30))) }
        let note = hit.note.trimmed
        if !note.isEmpty { parts.append(note) }
        return parts.joined(separator: " · ")
    }

    /// 界面显示用的来源行：与 `sourceLabel` 同结构，但每个固定词都跟随界面语言。
    /// 记忆正文、聊天标题、抽屉名（`hit.source`）属于数据，原样保留。
    public static func displaySourceLabel(_ hit: RecallHit, now: Int64 = DomainClock.nowMillis) -> String {
        var parts: [String] = [displayKindLabel(hit.kind), displayDateLabel(at: hit.at, now: now)]
        let source = hit.source.trimmed
        if !source.isEmpty { parts.append(displaySourceName(source)) }
        let note = hit.note.trimmed
        if !note.isEmpty { parts.append(displayNote(note)) }
        return parts.joined(separator: " · ")
    }

    /// 抽屉名是固定词表（存库与提示词里仍是中文），界面显示时翻一下。
    static func displaySourceName(_ source: String) -> String {
        switch source {
        case "关于他自己": return String(localized: "关于他自己")
        case "关于对方": return String(localized: "关于对方")
        case "你们之间的经历": return String(localized: "你们之间的经历")
        default: return String(source.prefix(30))
        }
    }

    /// 「归档」那条说明是固定文案；其它 note 属于数据，原样保留。
    static func displayNote(_ note: String) -> String {
        note == ARCHIVED_NOTE ? String(localized: "这是很久以前的事，他不太确定了") : note
    }

    static func displayKindLabel(_ kind: RecallKind) -> String {
        switch kind {
        case .memory: return String(localized: "记忆")
        case .chat: return String(localized: "聊天")
        case .browsing: return String(localized: "他自己看到的")
        }
    }

    /// 界面显示用的相对日期（提示词里仍用 `dateLabel`）。
    public static func displayDateLabel(at: Int64, now: Int64 = DomainClock.nowMillis) -> String {
        if at <= 0 { return String(localized: "很久以前") }
        let delta = Swift.max(now - at, 0)
        let minutes = delta / 60_000
        if minutes < 1 { return String(localized: "刚刚") }
        if minutes < 60 { return String(format: String(localized: "%d 分钟前"), minutes) }
        let days = delta / DAY_MILLIS
        if days <= 0 { return String(localized: "今天") }
        if days == 1 { return String(localized: "昨天") }
        if days == 2 { return String(localized: "前天") }
        if days < 7 { return String(format: String(localized: "%d 天前"), days) }
        if days < 30 { return String(format: String(localized: "%d 周前"), days / 7) }
        if days < 365 { return String(format: String(localized: "%d 个月前"), days / 30) }
        return String(format: String(localized: "%d 年前"), days / 365)
    }

    /// 中文相对日期：刚刚 / N 分钟前 / 今天 / 昨天 / 前天 / N 天前 / N 周前 / N 个月前 / N 年前。
    /// 只做算术，不读日历（跨零点时可能把「昨天凌晨」说成今天，聊天里无所谓）。
    public static func dateLabel(at: Int64, now: Int64 = DomainClock.nowMillis) -> String {
        if at <= 0 { return "很久以前" }
        let delta = Swift.max(now - at, 0)
        let minutes = delta / 60_000
        if minutes < 1 { return "刚刚" }
        if minutes < 60 { return "\(minutes) 分钟前" }
        let days = delta / DAY_MILLIS
        if days <= 0 { return "今天" }
        if days == 1 { return "昨天" }
        if days == 2 { return "前天" }
        if days < 7 { return "\(days) 天前" }
        if days < 30 { return "\(days / 7) 周前" }
        if days < 365 { return "\(days / 30) 个月前" }
        return "\(days / 365) 年前"
    }

    /// 什么都没翻到时说的人话。
    /// QUICK/NORMAL 只是「想不太起来」；DEEP 是翻遍了也找不到——那意味着可能真的忘了。
    public static func emptyAnswer(query: String, depth: RecallDepth) -> String {
        let trimmed = query.trimmed
        let topic = trimmed.isEmpty ? "这件事" : "「\(String(trimmed.prefix(40)))」"
        switch depth {
        case .quick:
            return "关于\(topic)，我想不太起来了。你提一句关键词，我再慢慢想。"
        case .normal:
            return "关于\(topic)，我想不太起来了，脑子里没有这段。你要是愿意，可以多给我一点线索。"
        case .deep:
            return "关于\(topic)，我翻箱倒柜找了一遍，确实想不起来了——可能早就忘了。"
                + "有些久远又不重要的事，是真的找不回来；你要是还记得，跟我说说吧。"
        }
    }
}
