// 来源：app/src/main/java/com/yap/app/domain/ChineseTerms.kt
//
// 这一层只是「把中文拆成能查倒排索引的碎片」，不碰 UI / 数据库 / 网络。
// 移植时保留原文件的全部常量与分支：查询与记忆必须走同一套词元，
// 否则会出现「上下文里挑中的记忆，回忆工具却翻不到」这种自相矛盾。

import Foundation

// MARK: - 词元与区间

/// 一个检索词元与它的分量（0..1）。同一段文本里，权重按出现次数归一化：
/// 出现最多的那个词元是 1.0，其余按比例；只出现一次的词元拿到 1/最高次数。
///
/// 为什么不是 TF-IDF：语料就是他自己那几百条记忆，IDF 会被几条长记忆带偏；
/// 次数归一化足够让「反复提到的词」稍微更重要，又不至于让一条长记忆压过一切。
public struct TermWeight: Equatable {
    public let term: String
    public let weight: Double

    public init(term: String, weight: Double) {
        self.term = term
        self.weight = weight
    }
}

/// 一段时间范围（左闭右开），用来理解「昨天」「上个月」「去年冬天」这类时间指代。
///
/// 只做日历算术，不读系统时钟外的任何状态：同一个 `now` 永远算出同一个区间，方便单测。
public struct TimeSpan: Equatable {
    public let from: Int64
    public let to: Int64

    public init(from: Int64, to: Int64) {
        self.from = from
        self.to = to
    }

    /// 区间的中点：模型只说「去年冬天」时，用它当作「那件事发生的时间」的近似。
    public var center: Int64 { from + (to - from) / 2 }

    public func contains(at: Int64) -> Bool { at >= from && at < to }

    /// 距离这个区间有多少天（落在区间内是 0）。
    public func distanceDays(at: Int64) -> Double {
        let millis: Int64
        if at < from {
            millis = from - at
        } else if at >= to {
            millis = at - to
        } else {
            millis = 0
        }
        return Double(millis) / TimeSpan.dayMillis
    }

    static let dayMillis: Double = 86_400_000.0
}

/// 词元 → 权重的**有序**表。
///
/// Kotlin 端用 `LinkedHashMap`，插入顺序即权重降序；Swift 的 `Dictionary` 不保序，
/// 而嵌入函数要把权重累加进哈希桶——顺序变了，浮点求和的最后几位就会飘。
/// 所以这里显式保留顺序：`pairs` 是唯一的真源，`index` 只负责查得快。
public struct TermWeights {
    public private(set) var pairs: [(term: String, weight: Double)]
    private var index: [String: Int]

    public init() {
        self.pairs = []
        self.index = [:]
    }

    public init(_ pairs: [(term: String, weight: Double)]) {
        self.init()
        for pair in pairs { set(pair.term, pair.weight) }
    }

    public var isEmpty: Bool { pairs.isEmpty }
    public var count: Int { pairs.count }

    /// 与 Kotlin `map[key]` 同义：没有这个键返回 nil（而不是 0）。
    public subscript(term: String) -> Double? {
        guard let position = index[term] else { return nil }
        return pairs[position].weight
    }

    public func contains(_ term: String) -> Bool { index[term] != nil }

    /// 已存在的键**原位覆盖**（和 LinkedHashMap 一样不会把它挪到末尾）。
    public mutating func set(_ term: String, _ weight: Double) {
        if let position = index[term] {
            pairs[position].weight = weight
        } else {
            index[term] = pairs.count
            pairs.append((term: term, weight: weight))
        }
    }

    public var values: [Double] { pairs.map(\.weight) }
}

/// 按 UTF-16 码元比较字符串，与 Kotlin 的 `String.compareTo` 完全一致。
///
/// Swift 的 `<` 走 Unicode 规范序，在纯中文/ASCII 时与码元序相同，但混合场景下不保证，
/// 排序一旦不同，「同分按词元字典序」这条约定就会变，缓存/测试的稳定性都会受影响。
public enum Lexicographic {
    public static func utf16Less(_ lhs: String, _ rhs: String) -> Bool {
        var left = lhs.utf16.makeIterator()
        var right = rhs.utf16.makeIterator()
        while true {
            switch (left.next(), right.next()) {
            case let (l?, r?):
                if l != r { return l < r }
            case (nil, nil):
                return false
            case (nil, _):
                return true
            case (_, nil):
                return false
            }
        }
    }
}

// MARK: - ChineseTerms

/// 中文检索词元：把一句人话拆成「能拿去查倒排索引」的碎片。
///
/// ## 为什么是字二元组
///
/// 中文没有空格，词库又不该塞进 App（几个 G 的分词模型不现实）。字二元组是检索里最划算的折中：
/// 「项目压力」→ 项目 / 目压 / 压力，这样「项目压得我睡不着」也能被「项目压力」找到。
/// 分词会错，二元组只会多出一点噪声，不会整句查不到。
///
/// ## 为什么先删虚词再切
///
/// 「我养了猫」如果只按原顺序切，会得到 我养 / 养了 / 了猫 —— 三个都带着虚词，反而查不到「猫」。
/// 所以先把「的了吗呢」这类不携带信息的字去掉，让实词贴在一起：「我养了猫」→「养猫」→ 养猫。
/// 代价是「难过/困难」都会变成「难」：对着他自己的记忆够用，而且查询和记忆走同一个函数，不会错配。
///
/// ## 约定
///
/// - 纯 Swift：不碰数据库、不读时钟（`now` 由调用方传入）；
/// - 空串、纯标点、超长文本、怪字符一律安全返回空列表，绝不抛异常；
/// - 用户可见的地方看不到这里的任何常量和权重：这是他自己的记性，不是给用户调参的面板。
public enum ChineseTerms {

    /// 一次查询最多取多少个词元（喂给 SQL 的 IN 列表要克制）。
    public static let MAX_QUERY_TERMS = 24

    /// 一条记忆最多写多少个索引词元：正文最长几百字，再多的都是低频噪声。
    public static let MAX_TERMS_PER_TEXT = 160

    /// 单个汉字（删掉虚词后只剩一个字）本身就模糊，权重给低一点。
    static let SINGLE_CHAR_WEIGHT = 0.7

    /// 加权覆盖率与余弦的配比：覆盖率治「长记忆被长度拖低分」，余弦治「短记忆虚高」。
    static let WEIGHT_COVERAGE = 0.6
    static let WEIGHT_COSINE = 0.4

    static let DAY_MILLIS: Int64 = 86_400_000

    // MARK: 切词

    /// 把文本切成 (词元, 权重) 列表，按权重降序。
    /// 空串 / 纯标点 / 全是虚词时返回空列表。
    public static func terms(_ text: String) -> [TermWeight] {
        let counted = countedTerms(text)
        if counted.isEmpty { return [] }
        guard let peak = counted.values.max(), peak > 0.0 else { return [] }
        let weighted = counted.pairs.map { pair in
            TermWeight(term: pair.term, weight: min(Swift.max(pair.weight / peak, 0.0), 1.0))
        }
        let sorted = weighted.sorted { a, b in
            if a.weight != b.weight { return a.weight > b.weight }
            return Lexicographic.utf16Less(a.term, b.term)
        }
        return Array(sorted.prefix(MAX_TERMS_PER_TEXT))
    }

    /// 词元 → 权重，用于余弦。同 [`terms`]。
    public static func weights(_ text: String) -> TermWeights {
        let list = terms(text)
        var out = TermWeights()
        for item in list { out.set(item.term, item.weight) }
        return out
    }

    /// 拿去做倒排索引查询的词元（不含权重、已去重）。
    ///
    /// 取权重最高的前 `max` 个：查询里的虚词和长尾词不该把 IN 列表撑爆。
    public static func queryTerms(_ text: String, max limit: Int = MAX_QUERY_TERMS) -> [String] {
        if limit <= 0 { return [] }
        var seen = Set<String>()
        var out: [String] = []
        for item in terms(text).prefix(limit) where seen.insert(item.term).inserted {
            out.append(item.term)
        }
        return out
    }

    /// 「这段正文有多相关」的 0..1 分：加权覆盖率为主、余弦为辅。
    ///
    /// 整段包含查询时直接给 1.0 —— 中文换个说法（「在杭州工作」/「杭州工作」）时二元组可能漏，
    /// 但字面包含是确定的。空查询、空正文一律 0（此时排序完全交给重要度与新鲜度）。
    public static func relevance(query: String, document: String) -> Double {
        let q = normalize(query)
        if q.isEmpty { return 0.0 }
        let d = normalize(document)
        if d.isEmpty { return 0.0 }
        if containsSubstring(d, q) { return 1.0 }

        let qWeights = weights(q)
        if qWeights.isEmpty { return 0.0 }
        let dWeights = weights(d)
        if dWeights.isEmpty { return 0.0 }

        var matched = 0.0
        var total = 0.0
        for pair in qWeights.pairs {
            total += pair.weight
            guard let hit = dWeights[pair.term] else { continue }
            matched += pair.weight * hit
        }
        if total <= 0.0 || matched <= 0.0 { return 0.0 }
        let coverage = min(max(matched / total, 0.0), 1.0)
        let cos = cosine(qWeights, dWeights)
        return min(max(WEIGHT_COVERAGE * coverage + WEIGHT_COSINE * cos, 0.0), 1.0)
    }

    /// query 里命中了几个标签（最多 `max` 个，够了就不再数）。
    ///
    /// 标签是「妹妹」「失眠」这样的短词，判断靠词元重叠而不是子串乱猜：
    /// 「妹妹」命中 query「我妹妹最近失眠」，但「妹」不会因为 query 里有「姐妹」就算命中。
    public static func tagHits(query: String, tags: [String], max limit: Int = 2) -> Int {
        if limit <= 0 || query.trimmed.isEmpty || tags.isEmpty { return 0 }
        let queryNorm = normalize(query)
        if queryNorm.isEmpty { return 0 }
        let queryWeights = weights(queryNorm)
        var hits = 0
        for tag in tags {
            let tagNorm = normalize(tag)
            if tagNorm.isEmpty { continue }
            let matched = containsSubstring(queryNorm, tagNorm)
                || terms(tagNorm).contains { queryWeights.contains($0.term) }
            if matched { hits += 1 }
            if hits >= limit { return hits }
        }
        return hits
    }

    /// 加权余弦：两段文本的词元向量夹角。任一为空返回 0，不会除零。
    public static func cosine(_ a: TermWeights, _ b: TermWeights) -> Double {
        if a.isEmpty || b.isEmpty { return 0.0 }
        let shorter = a.count <= b.count ? a : b
        let longer = a.count <= b.count ? b : a
        var dot = 0.0
        for pair in shorter.pairs {
            guard let other = longer[pair.term] else { continue }
            dot += pair.weight * other
        }
        if dot <= 0.0 { return 0.0 }
        let normA = (a.values.reduce(0.0) { $0 + $1 * $1 }).squareRoot()
        let normB = (b.values.reduce(0.0) { $0 + $1 * $1 }).squareRoot()
        if normA <= 0.0 || normB <= 0.0 { return 0.0 }
        return min(max(dot / (normA * normB), 0.0), 1.0)
    }

    // MARK: 归一化

    /// 归一化：全角转半角、转小写、标点与空白换成空格、去掉「去年冬天」这类时间指代。
    ///
    /// 时间指代单独处理：它由 [`timeSpanOf`] 换成真实时间区间去加权，
    /// 留在词元里只会让「去年」这种词污染命中。
    public static func normalize(_ text: String) -> String {
        if text.trimmed.isEmpty { return "" }
        // 全角 ASCII（！到 ～）转半角，全角空格转半角空格。
        var halfWidth = ""
        halfWidth.reserveCapacity(text.unicodeScalars.count)
        for scalar in text.unicodeScalars {
            if scalar.value >= 0xFF01 && scalar.value <= 0xFF5E {
                halfWidth.append(String(Unicode.Scalar(scalar.value - 0xFEE0)!))
            } else if scalar.value == 0x3000 {
                halfWidth.append(" ")
            } else {
                halfWidth.append(String(scalar))
            }
        }
        let lowered = halfWidth.lowercased()
        var spaced = ""
        spaced.reserveCapacity(lowered.unicodeScalars.count)
        for ch in lowered {
            spaced.append(ch.isWhitespace || PUNCTUATION.contains(ch) ? " " : String(ch))
        }
        return collapseWhitespace(stripTimeExpressions(spaced)).trimmed
    }

    /// 去掉「昨天」「上周三」「上个月」「去年冬天」这类相对时间说法。
    /// 单独暴露出来是为了单测能把规则钉死；正文里真正的「冬天」（怕冷）不受影响，
    /// 只删带相对参照的说法（去年/今年/上/上个…）。
    public static func stripTimeExpressions(_ text: String) -> String {
        let text = text.trimmed
        if text.isEmpty { return text }
        var result = text
        for regex in STRIP_PATTERNS {
            result = regex.replaceAll(in: result, with: " ")
        }
        return result
    }

    // MARK: 内部：切词实现

    /// 词元 → 次数权重。查重与归一化都在 [`terms`] 里做，这里只管数。
    static func countedTerms(_ text: String) -> TermWeights {
        let normalized = normalize(text)
        if normalized.isEmpty { return TermWeights() }
        var counts = TermWeights()

        func add(_ term: String, _ weight: Double) {
            if term.isEmpty { return }
            counts.set(term, (counts[term] ?? 0.0) + weight)
        }

        var cjk: [Character] = []
        var latin: [Character] = []

        func flushCjk() {
            if cjk.isEmpty { return }
            // 先删虚词，让实词贴在一起：「我养了猫」→「养猫」。
            let content = cjk.filter { !STOP_CHARS.contains($0) }
            cjk.removeAll(keepingCapacity: true)
            if content.isEmpty { return }
            if content.count == 1 {
                let single = String(content[0])
                if !STOP_BIGRAMS.contains(single) { add(single, SINGLE_CHAR_WEIGHT) }
                return
            }
            for i in 0..<(content.count - 1) {
                let bigram = String([content[i], content[i + 1]])
                if STOP_BIGRAMS.contains(bigram) { continue }
                add(bigram, 1.0)
            }
        }

        func flushLatin() {
            if latin.isEmpty { return }
            let word = String(latin)
            latin.removeAll(keepingCapacity: true)
            if word.count < 2 && Int(word) == nil { return }
            if LATIN_STOP_WORDS.contains(word) { return }
            add(word, 1.0)
        }

        for ch in normalized {
            if isCjk(ch) {
                flushLatin()
                cjk.append(ch)
            } else if ch.isLetter || ch.isNumber {
                flushCjk()
                latin.append(ch)
            } else {
                flushCjk()
                flushLatin()
            }
        }
        flushCjk()
        flushLatin()
        return counts
    }

    // MARK: 时间指代

    /// 默认日历：与 Kotlin 的 `Calendar.getInstance()` 一致（系统时区、公历）。
    ///
    /// 允许调用方注入是为了单测能在固定时区里断言「上周三」到底是哪一天，
    /// 规则本身不受时区影响。
    public static func defaultCalendar() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        return calendar
    }

    /// 把查询里的时间指代解析成一段时间区间；解析不出来返回 nil（= 不加权，不猜）。
    ///
    /// 支持的写法：今天 / 昨天 / 前天 / 大前天 / 上周三 / 上周 / 这周 / 上个月 / 这个月 /
    /// 去年 / 今年 / 前年 / 去年冬天（春夏秋冬同理）/ N 天前 / N 周前 / N 个月前 / N 年前 /
    /// 最近 / 这两天 / 这些天。
    public static func timeSpanOf(_ text: String, now: Int64,
                                  calendar: Calendar = ChineseTerms.defaultCalendar()) -> TimeSpan? {
        let raw = text.trimmed
        if raw.isEmpty { return nil }
        if let span = numericSpanOf(raw, now: now, calendar: calendar) { return span }
        if let span = yearSeasonSpanOf(raw, now: now, calendar: calendar) { return span }
        if LAST_MONTH.containsMatch(in: raw) { return monthSpan(now: now, monthOffset: -1, calendar: calendar) }
        if THIS_MONTH.containsMatch(in: raw) { return monthSpan(now: now, monthOffset: 0, calendar: calendar) }
        if let span = weekdaySpanOf(raw, now: now, calendar: calendar) { return span }
        if LAST_WEEK.containsMatch(in: raw) { return weekSpan(now: now, weekOffset: -1, calendar: calendar) }
        if THIS_WEEK.containsMatch(in: raw) { return weekSpan(now: now, weekOffset: 0, calendar: calendar) }
        if let span = dayWordSpanOf(raw, now: now, calendar: calendar) { return span }
        if RECENT_WORDS.containsMatch(in: raw) {
            return TimeSpan(from: dayStart(now: now, offsetDays: -7, calendar: calendar),
                            to: dayStart(now: now, offsetDays: 1, calendar: calendar))
        }
        if let season = seasonWordOf(raw) { return seasonSpanIncumbent(season, now: now, calendar: calendar) }
        return nil
    }

    /// 查询里有没有可解析的时间指代。
    public static func hasTimeHint(_ text: String, now: Int64,
                                   calendar: Calendar = ChineseTerms.defaultCalendar()) -> Bool {
        timeSpanOf(text, now: now, calendar: calendar) != nil
    }

    static func numericSpanOf(_ text: String, now: Int64, calendar: Calendar) -> TimeSpan? {
        guard let match = NUMERIC_TIME.firstMatch(in: text) else { return nil }
        guard let rawCount = match.group(1, in: text),
              let count = chineseNumber(rawCount) else { return nil }
        if count <= 0 { return nil }
        let unit = match.group(2, in: text) ?? ""
        if unit.hasSuffix("天") || unit.hasSuffix("日") {
            return dayWordSpan(now: now, offsetDays: -count, calendar: calendar)
        }
        if unit.contains("周") || unit.contains("星期") {
            let end = dayStart(now: now, offsetDays: -(count * 7 - 1), calendar: calendar)
            return TimeSpan(from: dayStart(now: now, offsetDays: -(count * 7 + 6), calendar: calendar), to: end)
        }
        if unit.hasSuffix("月") {
            return monthSpan(now: now, monthOffset: -count, calendar: calendar)
        }
        return yearSpan(now: now, yearOffset: -count, calendar: calendar)
    }

    static func yearSeasonSpanOf(_ text: String, now: Int64, calendar: Calendar) -> TimeSpan? {
        guard let match = YEAR_WITH_SEASON.firstMatch(in: text) else { return nil }
        let marker = match.group(1, in: text) ?? ""
        let offset: Int
        switch marker {
        case "去年": offset = -1
        case "前年": offset = -2
        default: offset = 0
        }
        guard let season = seasonWordOf(match.group(2, in: text) ?? "") else {
            return yearSpan(now: now, yearOffset: offset, calendar: calendar)
        }
        let anchorYear = calendar.component(.year, from: date(fromMillis: now)) + offset
        // 「去年冬天」= 去年 12 月到今年 2 月：冬天是跨年的，锚点取开始下雪的那一年。
        return seasonSpan(anchorYear: anchorYear, season: season, calendar: calendar)
    }

    static func weekdaySpanOf(_ text: String, now: Int64, calendar: Calendar) -> TimeSpan? {
        guard let match = WEEKDAY_IN_WEEK.firstMatch(in: text) else { return nil }
        let marker = match.group(1, in: text) ?? ""
        let weekdayChar = match.group(3, in: text) ?? ""
        guard let index = WEEKDAY_INDEX[weekdayChar] else { return nil }
        let weekOffset = marker == "上" ? -1 : 0
        let monday = weekStart(now: now, calendar: calendar) + Int64(weekOffset * 7) * DAY_MILLIS
        let start = monday + Int64(index - 1) * DAY_MILLIS
        return TimeSpan(from: start, to: start + DAY_MILLIS)
    }

    static func dayWordSpanOf(_ text: String, now: Int64, calendar: Calendar) -> TimeSpan? {
        for (word, offset) in DAY_WORDS where containsSubstring(text, word) {
            return dayWordSpan(now: now, offsetDays: offset, calendar: calendar)
        }
        return nil
    }

    static func dayWordSpan(now: Int64, offsetDays: Int, calendar: Calendar) -> TimeSpan {
        TimeSpan(from: dayStart(now: now, offsetDays: offsetDays, calendar: calendar),
                 to: dayStart(now: now, offsetDays: offsetDays + 1, calendar: calendar))
    }

    static func weekSpan(now: Int64, weekOffset: Int, calendar: Calendar) -> TimeSpan {
        let start = weekStart(now: now, calendar: calendar) + Int64(weekOffset * 7) * DAY_MILLIS
        return TimeSpan(from: start, to: start + 7 * DAY_MILLIS)
    }

    static func monthSpan(now: Int64, monthOffset: Int, calendar: Calendar) -> TimeSpan {
        let year = calendar.component(.year, from: date(fromMillis: now))
        let month = calendar.component(.month, from: date(fromMillis: now)) - 1 + monthOffset
        return TimeSpan(from: monthStart(year: year, month0: month, calendar: calendar),
                        to: monthStart(year: year, month0: month + 1, calendar: calendar))
    }

    static func yearSpan(now: Int64, yearOffset: Int, calendar: Calendar) -> TimeSpan {
        let year = calendar.component(.year, from: date(fromMillis: now)) + yearOffset
        return TimeSpan(from: monthStart(year: year, month0: 0, calendar: calendar),
                        to: monthStart(year: year + 1, month0: 0, calendar: calendar))
    }

    static func seasonSpan(anchorYear: Int, season: String, calendar: Calendar) -> TimeSpan {
        let startMonth: Int
        switch season {
        case "春": startMonth = 2
        case "夏": startMonth = 5
        case "秋": startMonth = 8
        default: startMonth = 11
        }
        let start = monthStart(year: anchorYear, month0: startMonth, calendar: calendar)
        // monthStart 自己会跨年（11 + 3 = 14 → 次年 2 月），这里不能再加一年，否则冬天会变成两年长。
        let end = monthStart(year: anchorYear, month0: startMonth + 3, calendar: calendar)
        return TimeSpan(from: start, to: end)
    }

    /// 只说了「冬天」时的最近一次：12 月算今年的冬天，1、2 月还在这个冬天里，
    /// 3 月到 11 月说的都是刚过去的那个冬天。
    static func seasonSpanIncumbent(_ season: String, now: Int64, calendar: Calendar) -> TimeSpan {
        let year = calendar.component(.year, from: date(fromMillis: now))
        let month = calendar.component(.month, from: date(fromMillis: now))
        let anchorYear: Int
        if month >= 12 {
            anchorYear = year
        } else if month <= 2 {
            anchorYear = year - 1
        } else {
            anchorYear = season == "冬" ? year - 1 : year
        }
        return seasonSpan(anchorYear: anchorYear, season: season, calendar: calendar)
    }

    static func seasonWordOf(_ raw: String) -> String? {
        guard let match = SEASON_WORDS.firstMatch(in: raw) else { return nil }
        guard let word = match.group(0, in: raw), let first = word.first else { return nil }
        switch first {
        case "春": return "春"
        case "夏": return "夏"
        case "秋": return "秋"
        default: return "冬"
        }
    }

    static func dayStart(now: Int64, offsetDays: Int, calendar: Calendar) -> Int64 {
        let base = date(fromMillis: now)
        guard let shifted = calendar.date(byAdding: .day, value: offsetDays, to: base) else {
            return now
        }
        return millis(from: calendar.startOfDay(for: shifted))
    }

    /// 周一 00:00（中国人说「上周三」时按周一起算的那一周）。
    static func weekStart(now: Int64, calendar: Calendar) -> Int64 {
        // Calendar 里周日是 1、周一是 2；换成「周一 = 第 1 天」。
        let dayOfWeek = calendar.component(.weekday, from: date(fromMillis: now))
        let offset = (dayOfWeek + 5) % 7
        return dayStart(now: now, offsetDays: -offset, calendar: calendar)
    }

    static func monthStart(year: Int, month0: Int, calendar: Calendar) -> Int64 {
        let quotient = Int((Double(month0) / 12.0).rounded(.down))
        let normalizedYear = year + quotient
        let normalizedMonth = month0 - quotient * 12
        var components = DateComponents()
        components.year = normalizedYear
        components.month = normalizedMonth + 1
        components.day = 1
        components.hour = 0
        components.minute = 0
        components.second = 0
        guard let date = calendar.date(from: components) else { return 0 }
        return millis(from: date)
    }

    static func date(fromMillis millis: Int64) -> Date {
        Date(timeIntervalSince1970: Double(millis) / 1000.0)
    }

    static func millis(from date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000.0).rounded())
    }

    /// 「三」「十二」「两」这类中文数字（够用到 99，再大没有意义）。
    static func chineseNumber(_ raw: String) -> Int? {
        let text = raw.trimmed
        if text.isEmpty { return nil }
        if let value = Int(text) { return value }
        let digits: [Character: Int] = [
            "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
            "五": 5, "六": 6, "七": 7, "八": 8, "九": 9
        ]
        let chars = Array(text)
        if chars.count == 1 {
            if chars[0] == "十" { return 10 }
            return digits[chars[0]]
        }
        guard let tenIndex = chars.firstIndex(of: "十") else { return nil }
        let tens: Int
        if tenIndex == 0 {
            tens = 1
        } else {
            guard let value = digits[chars[0]] else { return nil }
            tens = value
        }
        let ones: Int
        if tenIndex == chars.count - 1 {
            ones = 0
        } else {
            guard let value = digits[chars[tenIndex + 1]] else { return nil }
            ones = value
        }
        return tens * 10 + ones
    }

    /// 汉字范围。与 LocalEmbedding / MemoryScreening 用同一套，
    /// 避免「同一个字在两处一个算中文一个不算」。
    public static func isCjk(_ ch: Character) -> Bool {
        guard ch.unicodeScalars.count == 1, let scalar = ch.unicodeScalars.first else { return false }
        return isCjkCode(scalar.value)
    }

    static func isCjkCode(_ code: UInt32) -> Bool {
        (0x4E00...0x9FFF).contains(code) || (0x3400...0x4DBF).contains(code) ||
            (0x3040...0x30FF).contains(code) || (0xAC00...0xD7AF).contains(code) ||
            (0xF900...0xFAFF).contains(code)
    }

    // MARK: 词表

    /// 不携带信息的标点（全角半角都算）。
    static let PUNCTUATION: Set<Character> =
        Set("，。！？、；：,.!?;:'\"“”‘’（）()【】[]{}…—～·-~`<>《》「」/\\|@#$%^&*+=_")

    /// 虚词表：代词、助词、语气词、程度副词。
    ///
    /// 刻意**不**删「不、没、有、和、说、想、要、会、上、下」：它们要么改变意思（不喜欢 ≠ 喜欢），
    /// 要么常是实词的一部分（家里、说话）。只删纯语法字，宁可留噪声也不要改语义。
    static let STOP_CHARS: Set<Character> =
        Set("的了着过地得是我你他她它咱您们就也都很太又再还才而且并或吗呢吧啊呀哦嗯嘛之其个这那在于与觉")

    /// 整词噪声：删掉虚词后仍会剩下的口水词二元组。
    static let STOP_BIGRAMS: Set<String> = [
        "什么", "怎么", "这个", "那个", "这些", "那些", "一下", "一点",
        "我们", "你们", "他们", "自己", "现在", "刚才", "然后", "就是",
        "因为", "所以", "如果", "但是", "可能", "应该", "知道", "觉得",
        "时候", "这样", "那样", "一个", "有点", "其实", "反正"
    ]

    static let LATIN_STOP_WORDS: Set<String> = [
        "the", "a", "an", "of", "to", "in", "on", "at", "and", "or", "is", "are",
        "was", "were", "be", "been", "am", "i", "you", "he", "she", "it", "we",
        "they", "me", "my", "your", "his", "her", "its", "our", "their", "this",
        "that", "these", "those", "for", "with", "but", "not", "no", "do", "does",
        "did", "so", "if", "then", "than", "as", "by", "from", "have", "has", "had",
        "will", "would", "can", "could", "should", "about", "there", "here"
    ]

    /// 「N 天前 / N 周前 / N 个月前 / N 年前」。
    static let NUMERIC_TIME = KotlinRegex(
        #"(\d{1,3}|[一二两三四五六七八九十]{1,3})\s*(天|日|周|个?星期|个?月|年)前"#)

    /// 「去年冬天 / 今年的春天 / 前年秋天」。
    static let YEAR_WITH_SEASON = KotlinRegex(#"(去年|今年|前年)(?:的)?(春天|夏天|秋天|冬天|春季|夏季|秋季|冬季)?"#)

    static let LAST_MONTH = KotlinRegex("上个?月")
    static let THIS_MONTH = KotlinRegex("这个?月|本月|当月")
    static let LAST_WEEK = KotlinRegex("上个?星期|上周|上星期")
    static let THIS_WEEK = KotlinRegex("这周|本周|这个?星期|这星期")

    /// 「上周三 / 这周五 / 星期三」，group1 是「上/这/本」，group3 是星期几。
    static let WEEKDAY_IN_WEEK = KotlinRegex(#"(?:(上|这|本)(?:个)?(?:星期|周)|(星期|周))([一二三四五六日天])"#)

    /// 相对天数：先长后短，避免「大前天」被「前天」抢先。
    static let DAY_WORDS: [(String, Int)] = [
        ("大前天", -3),
        ("前天", -2),
        ("昨天", -1),
        ("今天", 0)
    ]

    static let RECENT_WORDS = KotlinRegex("最近|这两天|这些天|这几天|前几天|前段时间")

    static let SEASON_WORDS = KotlinRegex("春天|夏天|秋天|冬天|春季|夏季|秋季|冬季")

    static let WEEKDAY_INDEX: [String: Int] = [
        "一": 1, "二": 2, "三": 3, "四": 4,
        "五": 5, "六": 6, "日": 7, "天": 7
    ]

    /// 从正文里抹掉的时间说法：只删带相对参照的，正文里的「冬天怕冷」不受影响。
    static let STRIP_PATTERNS: [KotlinRegex] = [
        NUMERIC_TIME,
        KotlinRegex(#"(去年|今年|前年)(?:的)?(?:春天|夏天|秋天|冬天|春季|夏季|秋季|冬季)?"#),
        KotlinRegex(#"上个?星期[一二三四五六日天]?"#),
        KotlinRegex(#"(上|这|本)(?:个)?(?:星期|周)[一二三四五六日天]?"#),
        KotlinRegex("上个?月|这个?月|本月|当月"),
        KotlinRegex("大前天|前天|昨天|今天|明日|明天|刚刚|刚才|现在"),
        KotlinRegex("最近|这两天|这些天|这几天|前几天|前段时间")
    ]

    // MARK: 小工具

    /// `String.contains` 在 Swift 里是字面包含，跟 Kotlin 的 `contains` 同义，
    /// 单独包一层只是为了让调用点读起来像原文。
    static func containsSubstring(_ haystack: String, _ needle: String) -> Bool {
        if needle.isEmpty { return true }
        return haystack.range(of: needle) != nil
    }

    /// 等价于 Kotlin 的 `replace(Regex("\\s+"), " ")`：把连续空白压成一个空格。
    static func collapseWhitespace(_ text: String) -> String {
        var out = ""
        out.reserveCapacity(text.count)
        var previousWasSpace = false
        for ch in text {
            if ch.isWhitespace {
                if !previousWasSpace { out.append(" ") }
                previousWasSpace = true
            } else {
                out.append(ch)
                previousWasSpace = false
            }
        }
        return out
    }
}

extension String {
    /// Kotlin 的 `trim()`：去掉首尾空白（Unicode 空白，与 `isWhitespace` 同义）。
    var trimmed: String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

// MARK: - 正则（对齐 Kotlin Regex 的用法）

/// 一层很薄的 `NSRegularExpression` 包装。
///
/// 为什么不用 Swift 的 `Regex` 字面量：这里的模式是照 Kotlin 原文逐字搬过来的
/// （含 `\d`、`\s`、命名分组之类），保持字符串形态最容易对照复核；
/// 而且模式在静态属性里编译一次就够，不存在性能问题。
struct KotlinRegex {
    private let regex: NSRegularExpression

    init(_ pattern: String) {
        // 模式都是常量，编不出来属于开发期错误，这里退化成「永不匹配」而不是崩掉调用方。
        regex = (try? NSRegularExpression(pattern: pattern))
            ?? (try? NSRegularExpression(pattern: "(?!)"))!
    }

    func firstMatch(in text: String) -> Match? {
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, options: [], range: range) else { return nil }
        return Match(result: match, text: text)
    }

    func containsMatch(in text: String) -> Bool { firstMatch(in: text) != nil }

    func matches(_ text: String) -> Bool {
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, options: [], range: range) else { return false }
        return match.range == range
    }

    func replaceAll(in text: String, with template: String) -> String {
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.stringByReplacingMatches(in: text, options: [], range: range, withTemplate: template)
    }

    struct Match {
        let result: NSTextCheckingResult
        let text: String

        /// 第 n 个分组；没参与匹配时返回 nil（与 Kotlin 的 groupValues 语义一致）。
        func group(_ index: Int, in text: String) -> String? {
            guard index < result.numberOfRanges else { return nil }
            let range = result.range(at: index)
            if range.location == NSNotFound { return nil }
            guard let swiftRange = Range(range, in: text) else { return nil }
            return String(text[swiftRange])
        }
    }
}
