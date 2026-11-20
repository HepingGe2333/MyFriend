import Foundation

// ---------------------------------------------------------------------------
// 0.15 会话记忆 v2（`docs/design/context-memory-v2.md`）：
//
// 1. **短期记忆**：每 5 轮、或模型认为计划变了，更新一次（≤200 字）；
// 2. **长期记忆**：每 15–40 轮由模型决定要不要更新（≤500 字）；
// 3. **主题总结**：每 15–40 轮滚动更新，或话题明显切换时；每段 ≤120 字、最多 12 段，
//    带上「哪一天、大概什么时候」的时间段；
// 4. **`memorize` 检索**：模型说「让我想想」时，在会话历史里按「关键词 + 主题段 + 时间衰减」
//    找旧事，渲染成 `<回忆：…>` 注回下一轮上下文。
//
// 这里全是纯函数：时间由参数传入、消息是只读结构、不碰库也不碰网络。
// 更新节奏的三个数字与 `MemoryContract.Summary` 共用同一份常量，不在服务层再写一遍。
// ---------------------------------------------------------------------------

public enum SessionMemoryV2 {

    // MARK: - 节奏

    /// 短期记忆：每 5 轮，或模型判断计划/待办发生变化时。
    public static func shouldUpdateShort(
        currentUserCount: Int,
        upTo: Int,
        planChanged: Bool
    ) -> Bool {
        if planChanged { return true }
        return SessionMemoryPolicy.shouldUpdate(
            currentUserCount: currentUserCount,
            upTo: upTo,
            interval: MemoryContract.Summary.SHORT_UPDATE_EVERY_ROUNDS
        )
    }

    /// 模型「看过之后认为还不用更新」的哨兵输出（长期摘要）。
    ///
    /// 用它而不是 `nil`：`nil` 要表示「调用失败、下次重试」，两者语义完全不同 ——
    /// 混在一起会让一次模型说「再等等」变成下一轮又烧一次调用。
    public static let skipLongUpdate = "SKIP"

    /// 长期记忆：不到 15 轮不看；超过 40 轮必须更新；中间由模型决定。
    public static func shouldUpdateLong(
        currentUserCount: Int,
        upTo: Int,
        modelWantsUpdate: Bool
    ) -> Bool {
        let delta = currentUserCount - max(upTo, 0)
        if delta >= MemoryContract.Summary.LONG_UPDATE_MAX_ROUNDS { return true }
        guard delta >= MemoryContract.Summary.LONG_UPDATE_MIN_ROUNDS else { return false }
        return modelWantsUpdate
    }

    /// 主题总结：与长期记忆同一条 15–40 的节奏；话题明显切换时立刻更新。
    public static func shouldUpdateTopics(
        currentUserCount: Int,
        upTo: Int,
        topicChanged: Bool,
        modelWantsUpdate: Bool
    ) -> Bool {
        // 话题切换：已经有主题段时立刻更新；**第一次建主题段**仍要攒够 15 轮，
        // 否则聊两句就多一段「今天聊了在吗」，主题总结会碎成一堆噪音。
        if topicChanged {
            if upTo > 0 { return true }
            return currentUserCount - max(upTo, 0) >= MemoryContract.Summary.LONG_UPDATE_MIN_ROUNDS
        }
        return shouldUpdateLong(
            currentUserCount: currentUserCount,
            upTo: upTo,
            modelWantsUpdate: modelWantsUpdate
        )
    }

    /// 话题是否「明显切换」：最近一句与主题段里的关键词几乎没有重合。
    ///
    /// 判据刻意宽松（重合率 < 1/3 才算切换）：换个说法接着聊不算新话题，
    /// 否则主题总结会碎成一堆一句话的段。
    public static func topicChanged(_ query: String, topics: [MemoryContract.TopicSegment]) -> Bool {
        let tokens = Set(MemorizeSearch.tokens(query))
        guard !tokens.isEmpty else { return false }
        let topicTokens = Set(topics.flatMap { MemorizeSearch.tokens($0.summary + " " + $0.timeLabel) })
        guard !topicTokens.isEmpty else { return true }
        let overlap = tokens.intersection(topicTokens).count
        return Double(overlap) / Double(tokens.count) < 1.0 / 3.0
    }

    // MARK: - 主题时间段

    /// 一天里的时段（用中文，而不是 `HH:mm`：主题总结是「他自己记得的」）。
    public enum DayPeriod: String, CaseIterable, Sendable {
        case lateNight = "深夜"
        case earlyMorning = "清早"
        case morning = "上午"
        case noon = "中午"
        case afternoon = "下午"
        case dusk = "傍晚"
        case evening = "晚"

        public static func of(hour: Int) -> DayPeriod {
            switch hour {
            case 0..<5: return .lateNight
            case 5..<8: return .earlyMorning
            case 8..<11: return .morning
            case 11..<13: return .noon
            case 13..<17: return .afternoon
            case 17..<19: return .dusk
            case 19..<23: return .evening
            default: return .lateNight
            }
        }
    }

    /// 主题段的时间标签：`2026-11-18 晚`。
    public static func timeLabel(at millis: Int64, calendar: Calendar) -> String {
        guard millis > 0 else { return "" }
        let date = Date(timeIntervalSince1970: Double(millis) / 1000.0)
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = PromptContract.Time.DATE_PATTERN
        let day = formatter.string(from: date)
        let hour = calendar.component(.hour, from: date)
        return day + " " + DayPeriod.of(hour: hour).rawValue
    }

    /// 追加一段主题总结：超 12 段时从最旧的丢；同一时间段内合并而不是新起一段。
    public static func appendTopic(
        _ existing: [MemoryContract.TopicSegment],
        summary: String,
        at millis: Int64,
        calendar: Calendar,
        maxSegments: Int
    ) -> [MemoryContract.TopicSegment] {
        let clean = MemoryContract.Injection.flatten(summary)
        guard !clean.isEmpty else { return existing }
        let label = timeLabel(at: millis, calendar: calendar)
        var topics = existing
        if let index = topics.lastIndex(where: { $0.timeLabel == label && !label.isEmpty }) {
            // 同一时间段：把新摘要并进最后一段（各自 ≤120 字）。
            let merged = topics[index].summary + "；" + clean
            topics[index] = MemoryContract.TopicSegment(
                timeLabel: label,
                summary: String(merged.prefix(MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS))
            )
        } else {
            topics.append(MemoryContract.TopicSegment(
                timeLabel: label,
                summary: String(clean.prefix(MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS))
            ))
        }
        let limit = max(maxSegments, 1)
        if topics.count > limit { topics = Array(topics.suffix(limit)) }
        return topics
    }

    // MARK: - 主题总结的序列化（落库）

    /// 主题总结存成 JSON 数组：`[{"timeLabel":"…","summary":"…"}]`。
    ///
    /// 存 JSON 而不是「一行一段的文本」：时间段与正文要分开（注入时排版、检索时按时间段加权），
    /// 拍平成文本之后这两件事就都做不到了。坏数据 / 空 → nil（不写空列）。
    public static func encodeTopics(_ topics: [MemoryContract.TopicSegment]) -> String? {
        let items = topics
            .filter { !MemoryContract.Injection.flatten($0.summary).isEmpty }
            .prefix(MemoryContract.Summary.TOPIC_MAX_SEGMENTS)
            .map { topic -> [String: String] in
                ["timeLabel": MemoryContract.Injection.flatten(topic.timeLabel),
                 "summary": String(MemoryContract.Injection.flatten(topic.summary)
                    .prefix(MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS))]
            }
        guard !items.isEmpty else { return nil }
        guard let data = try? JSONSerialization.data(withJSONObject: items, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    /// 解码主题总结；坏 JSON / 缺字段都安静降级（宁可少几段，不要让提示词装配失败）。
    public static func decodeTopics(_ raw: String?) -> [MemoryContract.TopicSegment] {
        let text = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let data = text.data(using: .utf8),
              let items = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
            return []
        }
        return items.prefix(MemoryContract.Summary.TOPIC_MAX_SEGMENTS).compactMap { item in
            let summary = MemoryContract.Injection.flatten(item["summary"] as? String)
            guard !summary.isEmpty else { return nil }
            return MemoryContract.TopicSegment(
                timeLabel: MemoryContract.Injection.flatten(item["timeLabel"] as? String),
                summary: String(summary.prefix(MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS))
            )
        }
    }

    /// 注入用的主题段（`## 对话主题` + 每行 `- 时间段：摘要`）。
    public static func topicBlock(_ topics: [MemoryContract.TopicSegment]) -> String {
        let lines = topics
            .prefix(MemoryContract.Summary.TOPIC_MAX_SEGMENTS)
            .compactMap { MemoryContract.Injection.topicLine($0) }
        guard !lines.isEmpty else { return "" }
        return ([MemoryContract.Injection.TOPIC_TITLE] + lines).joined(separator: "\n")
    }

    /// 主题更新的提示词（15–40 轮滚动更新 / 话题切换时）。
    ///
    /// 要求「带时间段、每段 ≤120 字、最多 12 段」，与注入形状同一条口径：
    /// 模型写出来的东西会原样进下一轮上下文，格式不对当场就能在装配时被截断。
    public static func topicUpdateMessages(
        personaName: String,
        existing: [MemoryContract.TopicSegment],
        dialogue: String,
        nowLabel: String,
        topicChanged: Bool
    ) -> [CompletionMessage] {
        let system = """
        你在帮「\(personaName)」整理他自己的对话主题笔记。他只会用它来记住「我们大概聊过哪些事」。
        规则：
        - 按时间分段，每段一行，形如 `时间段｜这一段的主题`；时间段用「\(nowLabel)」这种写法；
        - 每段不超过 \(MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS) 字，最多 \(MemoryContract.Summary.TOPIC_MAX_SEGMENTS) 段；
        - 只写事实与主题（聊了什么、定了什么），不要评价、不要建议、不要编造；
        - 旧段落保留原文；\(topicChanged ? "这一次话题明显换了，另起一段。" : "如果还是同一个话题，把它并进最后一段。")
        只输出 JSON 数组，不要解释：
        [{"timeLabel":"2026-11-18 晚","summary":"加班与方案改版"}]
        """
        let existingText = existing.isEmpty
            ? "（还没有）"
            : existing.map { "- \($0.timeLabel)：\($0.summary)" }.joined(separator: "\n")
        let user = """
        ## 已有的主题笔记
        \(existingText)

        ## 最近这一段对话
        \(dialogue.isEmpty ? "（没什么内容）" : dialogue)

        现在请输出更新后的主题笔记（JSON 数组）。
        """
        return [.system(system), .user(user)]
    }
}

// MARK: - memorize 检索

/// 一条「回忆」命中：哪段时间、谁说的、原句、为什么排在这里。
public struct MemorizeHit: Equatable, Sendable {
    public var messageId: String
    /// `true` = 对方说的，`false` = 他说的。
    public var fromUser: Bool
    public var at: Int64
    /// 时间段标签（`2026-11-18 晚`）。
    public var timeLabel: String
    /// 命中的原句（已截断）。
    public var quote: String
    /// 命中的主题段（可能为空）。
    public var topic: MemoryContract.TopicSegment?
    public var score: Double

    public init(
        messageId: String,
        fromUser: Bool,
        at: Int64,
        timeLabel: String,
        quote: String,
        topic: MemoryContract.TopicSegment?,
        score: Double
    ) {
        self.messageId = messageId
        self.fromUser = fromUser
        self.at = at
        self.timeLabel = timeLabel
        self.quote = quote
        self.topic = topic
        self.score = score
    }
}

/// `memorize(query)` 的纯检索：关键词 + 主题段 + 时间衰减。
public enum MemorizeSearch {

    /// 一次最多回忆几条 / 多少字（回忆是「他想了想」，不是把历史全倒出来）。
    public static let maxHits = 6
    public static let maxQuotedChars = 80
    public static let maxRenderedChars = 900
    /// 时间衰减半衰期：7 天（一周前的细节还记得，一个月前的只剩个影子）。
    public static let halfLifeMillis: Double = 7 * 24 * 60 * 60 * 1000

    /// 中文检索用的虚词/高频字：不去掉的话「我」「的」会让任何两句都「相关」。
    private static let stopwords: Set<String> = [
        "我们", "你们", "他们", "这个", "那个", "什么", "怎么", "就是", "还是", "可以",
        "一下", "不是", "没有", "现在", "时候", "觉得", "知道", "因为", "所以", "但是",
        "然后", "其实", "自己", "真的", "已经", "如果", "这么", "那么", "起来", "出来",
        "一个", "这种", "那种", "有点", "一点", "不要", "不会", "不能", "可能"
    ]

    private static let stopChars: Set<Character> = Set("的了是在和就都而及与着或一个也这那你我他她它们很还又再吗吧呢啊呀哦嗯")

    /// 把一句话切成检索词元：中文取二元组（去虚词），英文/数字取整词。
    public static func tokens(_ raw: String) -> [String] {
        let lowered = raw.lowercased()
        var result: [String] = []
        var cjkBuffer: [Character] = []
        var latinBuffer: [Character] = []

        func flushCJK() {
            let chars = cjkBuffer.filter { !$0.isWhitespace && !stopChars.contains($0) }
            if chars.count == 1 {
                result.append(String(chars))
            } else if chars.count >= 2 {
                for index in 0..<(chars.count - 1) {
                    let pair = String(chars[index...(index + 1)])
                    if !stopwords.contains(pair) { result.append(pair) }
                }
            }
            cjkBuffer.removeAll()
        }

        func flushLatin() {
            let word = String(latinBuffer)
            if word.count >= 2, !stopwords.contains(word) { result.append(word) }
            latinBuffer.removeAll()
        }

        for character in lowered {
            if character.isASCII, character.isLetter || character.isNumber {
                flushCJK()
                latinBuffer.append(character)
            } else if isCJK(character) {
                flushLatin()
                cjkBuffer.append(character)
            } else {
                flushCJK()
                flushLatin()
            }
        }
        flushCJK()
        flushLatin()
        return Array(Set(result)).sorted()
    }

    private static func isCJK(_ character: Character) -> Bool {
        guard let scalar = character.unicodeScalars.first else { return false }
        return (0x4E00...0x9FFF).contains(scalar.value)
            || (0x3400...0x4DBF).contains(scalar.value)
    }

    /// 主入口：在会话历史里找「和 query 有关、而且不太久」的几句。
    public static func search(
        query: String,
        messages: [MessageEntity],
        topics: [MemoryContract.TopicSegment],
        now: Int64,
        calendar: Calendar,
        limit: Int = maxHits
    ) -> [MemorizeHit] {
        let queryTokens = Set(tokens(query))
        let topicTokens = topics.map { Set(tokens($0.summary + " " + $0.timeLabel)) }
        var hits: [MemorizeHit] = []
        for message in messages {
            guard message.role == .user || message.role == .assistant else { continue }
            guard !message.blocked, message.status != .error else { continue }
            let content = message.content.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !content.isEmpty else { continue }
            let contentTokens = Set(tokens(content))
            let keywordHits = queryTokens.isEmpty
                ? 0
                : queryTokens.intersection(contentTokens).count
            // 关键词一个都不沾：与这句话无关，直接跳过（回忆不能变成「把历史全倒出来」）。
            if !queryTokens.isEmpty && keywordHits == 0 { continue }

            // 主题段加成：这句话所属的时间段被主题笔记记过，说明它是「一件有名字的事」。
            var topicBoost = 0.0
            var matchedTopic: MemoryContract.TopicSegment?
            if !queryTokens.isEmpty {
                for index in topics.indices.reversed() {
                    if !topicTokens[index].isDisjoint(with: queryTokens) {
                        topicBoost = 1.5
                        matchedTopic = topics[index]
                        break
                    }
                }
            }

            let age = Double(max(now - message.createdAt, 0))
            let decay = pow(0.5, age / halfLifeMillis)
            // 关键词是主信号（×2），主题段加成固定，时间衰减只做「同样相关时更近的排前面」。
            let score = (Double(keywordHits) * 2.0 + topicBoost) * (0.35 + 0.65 * decay)
            hits.append(MemorizeHit(
                messageId: message.id,
                fromUser: message.role == .user,
                at: message.createdAt,
                timeLabel: SessionMemoryV2.timeLabel(at: message.createdAt, calendar: calendar),
                quote: String(content.prefix(maxQuotedChars)),
                topic: matchedTopic,
                score: score
            ))
        }
        return hits
            .sorted { lhs, rhs in
                if abs(lhs.score - rhs.score) > 1e-9 { return lhs.score > rhs.score }
                // 同分：新的在前（同样的相关度，人先想起最近的事）。
                return lhs.at > rhs.at
            }
            .prefix(max(limit, 1))
            .map { $0 }
    }

    /// 渲染成注入给模型的「回忆」块：`<回忆：2026-11-18 晚 · 你：「…」>`。
    ///
    /// 为什么带时间段与原句：模型要能说「你上周说过……」而不是含糊地「我记得你说过」；
    /// 原句还能防止它把摘要当成事实自由发挥。
    public static func render(_ hits: [MemorizeHit], query: String) -> String {
        guard !hits.isEmpty else {
            let short = String(MemoryContract.Injection.flatten(query).prefix(40))
            return short.isEmpty
                ? "（你想了想，什么也没想起来。）"
                : "<回忆：想不起关于「\(short)」的细节了。>"
        }
        var lines: [String] = []
        var chars = 0
        for hit in hits {
            let who = hit.fromUser ? "你" : "他"
            var line = "<回忆："
            if !hit.timeLabel.isEmpty { line += hit.timeLabel + " · " }
            if let topic = hit.topic {
                line += "主题「\(topic.summary)」 · "
            }
            line += "\(who)：「\(hit.quote)」>"
            if chars + line.count > maxRenderedChars { break }
            lines.append(line)
            chars += line.count
        }
        return lines.joined(separator: "\n")
    }
}
