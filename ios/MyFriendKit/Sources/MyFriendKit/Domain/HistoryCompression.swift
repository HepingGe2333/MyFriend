// 来源：app/src/main/java/com/myfriend/app/domain/HistoryCompression.kt
//
// 长会话的「滚动记忆」：聊得太久之后，把最前面那段收成一段他自己记得住的话。
//
// ## 为什么要有这个
//
// 上下文塞不下整段历史的时候，最简单的做法是「从后往前截」——可截掉的那部分，
// 在**这个人**看来等于没发生过：他不记得你上周说过要去哈尔滨，也不记得答应过你什么。
// 所以这里不是在省 token，而是在替他留住「我们聊过什么」。
//
// ## 分工
//
// 这个文件只有纯逻辑：什么时候该压（`plan`）、压的时候怎么跟他说（`summaryPrompt` /
// `mergePrompt`）、模型写回来的东西能不能当成记忆（`isSummaryAcceptable`）。
// 真正读库、调模型、落库在数据层里。时间与条数一律由参数传进来，
// 所以这些规则可以脱开数据库和网络单测。
//
// ## 不会删任何东西
//
// 压缩只写一段摘要和「压到哪一条为止」的时间点；原始消息一条都不动，
// 分页、搜索、导出看到的还是完整的聊天记录。

import Foundation

/// 一段对话在「要被收成记忆」时的样子。
///
/// 只留压缩真正需要的四件事：谁说的、什么时候说的、说了什么、以及一个稳定的 id。
/// 刻意不直接拿数据库实体：这一层不认数据库，也方便单测构造。
public struct MsgView {
    /// 消息 id，只用于同一时间戳时的稳定排序与排查
    public var id: String
    /// 消息时间（毫秒）；非正数视为坏数据，直接忽略
    public var at: Int64
    /// 是不是对方说的（true = 他说的，false = 我自己说的）
    public var fromUser: Bool
    /// 正文；只有图片、没有字的消息算「没内容」
    public var text: String

    public init(id: String = "", at: Int64 = 0, fromUser: Bool = false, text: String = "") {
        self.id = id
        self.at = at
        self.fromUser = fromUser
        self.text = text
    }

    /// 有没有可读的字。只有图片、或者被编辑成空的消息不参与摘要。
    public var hasText: Bool { !text.trimmed.isEmpty }
}

/// 一次压缩的结论。
///
/// - `compressUpTo`: 压到哪一条消息的时间为止（该消息及更早的进摘要）；不用压时为 nil
/// - `keepFrom`: 从这个时间点开始的消息继续逐字留在上下文里；没有消息时为 nil
/// - `reason`: 中文理由，给日志与调试面板看，不是给用户看的文案
public struct CompressionPlan: Equatable {
    public var compressUpTo: Int64?
    public var keepFrom: Int64?
    public var reason: String

    public init(compressUpTo: Int64? = nil, keepFrom: Int64? = nil, reason: String = "") {
        self.compressUpTo = compressUpTo
        self.keepFrom = keepFrom
        self.reason = reason
    }

    /// 这次需不需要真的去调一次模型。
    public var shouldCompress: Bool { compressUpTo != nil }
}

/// 历史压缩的全部判定与提示词。
public enum HistoryCompression {

    /// 最近这么多条继续逐字留在上下文里：近处的话要原样，别让他把「你刚才那句」记成概括。
    public static let DEFAULT_MAX_VERBATIM = 24

    /// 更早的那部分至少攒到这么多条，才值得为它花一次调用。
    public static let DEFAULT_MIN_TO_COMPRESS = 12

    /// 更早那部分的总字数超过这个数，即使条数不多也压（有人一发就是几千字）。
    public static let DEFAULT_MAX_CHARS = 24_000

    /// 一次最多压这么多条：绝不把一年历史一口气塞给模型。
    public static let DEFAULT_MAX_BATCH = 40

    /// 摘要的长度上限（模型被要求写 200 字，这里留一点超出余地）。
    public static let MAX_SUMMARY_CHARS = 300

    /// 太短的「摘要」等于没记，直接当失败。
    public static let MIN_SUMMARY_CHARS = 6

    /// 从带代码块的回复里剥出来之后，至少还要剩这么多字才算一段记忆。
    ///
    /// 模型偶尔会把工具调用当成正文写回来（「嗯，这个我想记下来。```myfriend-tool …```」），
    /// 如果只剥掉代码块、把前面那半句留下，就会把一句「我想记下来」永久当成他的回忆。
    public static let MIN_SALVAGED_CHARS = 24

    /// 旧版本写进库里的摘要，低于这个长度就当它是残句（启动自检用）。
    public static let MIN_LEGACY_CHARS = 20

    /// 拼进 system 的那一行标题，界面之外的措辞都收在这里。
    public static let SUMMARY_HEADER = "【更早之前你记得的】"

    /// 单条消息在提示词里最多留这么多字，免得一条长消息把整段挤掉。
    static let MAX_TRANSCRIPT_CHARS = 9_000
    static let MAX_MESSAGE_CHARS = 400
    static let MIN_MESSAGE_CHARS = 60
    static let MAX_PREVIOUS_CHARS = 400

    // MARK: 该不该压

    /// 决定这一次要不要压、压到哪一条。
    ///
    /// 规则（顺序有意义）：
    /// 1. 按时间排好（坏时间、乱序输入都不抛异常）；
    /// 2. 最近 `maxVerbatim` 条永远逐字保留；
    /// 3. 更早、且还没被摘要盖住的部分，条数 ≥ `minToCompress`，或者总字数超过 `maxChars`，才值得压；
    /// 4. 一次最多压 `maxBatch` 条，从最旧的开始 —— 这样每一次都在往前挪一小步，
    ///    而不是把整段旧历史一次性交给模型；
    /// 5. 摘要边界一定严格早于逐字保留的起点，时间戳挤在一起时宁可不压。
    ///
    /// 任何参数传坏了（负数、0）都按「至少 1」处理：宁可少压一点，也不能让压缩跑飞。
    public static func plan(
        messages: [MsgView],
        summaryUpTo: Int64?,
        maxVerbatim: Int = DEFAULT_MAX_VERBATIM,
        minToCompress: Int = DEFAULT_MIN_TO_COMPRESS,
        maxChars: Int = DEFAULT_MAX_CHARS,
        maxBatch: Int = DEFAULT_MAX_BATCH
    ) -> CompressionPlan {
        // 时间不明（非正数）的消息没法参与「压到哪一条为止」的判断，直接跳过。
        let ordered = messages
            .filter { $0.at > 0 }
            .sorted { a, b in
                if a.at != b.at { return a.at < b.at }
                return Lexicographic.utf16Less(a.id, b.id)
            }
        if ordered.isEmpty { return CompressionPlan(reason: "还没聊过什么，不用收") }

        let keepCount = min(Swift.max(maxVerbatim, 1), ordered.count)
        let boundary = ordered.count - keepCount
        let keepFrom = ordered[boundary].at
        if boundary <= 0 {
            return CompressionPlan(keepFrom: keepFrom, reason: "只有 \(ordered.count) 条，全部逐字留着")
        }

        let older = Array(ordered[0..<boundary])
        let uncovered: [MsgView]
        if let summaryUpTo, summaryUpTo > 0 {
            uncovered = older.filter { $0.at > summaryUpTo }
        } else {
            uncovered = older
        }
        if uncovered.isEmpty {
            return CompressionPlan(keepFrom: keepFrom, reason: "更早的部分都已经收进摘要里了")
        }

        let chars = uncovered.reduce(0) { $0 + Swift.max($1.text.count, 0) }
        let worthIt = uncovered.count >= Swift.max(minToCompress, 1)
            || chars > Swift.max(maxChars, 1)
        if !worthIt {
            return CompressionPlan(
                keepFrom: keepFrom,
                reason: "更早的 \(uncovered.count) 条（\(chars) 字）还短，先留着"
            )
        }

        // 从最旧的开始压一批；同时保证不会压到「最近那几条要逐字保留的」。
        let batch = Array(uncovered.prefix(Swift.max(maxBatch, 1)))
        let safe = batch.prefix { $0.at < keepFrom }
        if safe.isEmpty {
            return CompressionPlan(
                keepFrom: keepFrom,
                reason: "更早的消息和要逐字保留的那段挤在同一时间点上，这次先不动"
            )
        }
        return CompressionPlan(
            compressUpTo: safe.last!.at,
            keepFrom: keepFrom,
            reason: "把更早的 \(safe.count) 条收成一段记忆，最近 \(keepCount) 条逐字留着"
        )
    }

    // MARK: 怎么让他记

    /// 分叉调用用的 system：只讲规矩，没有会话、没有身份、没有工具。
    ///
    /// 刻意写成「你自己在心里记一笔」，而不是「请对文本做摘要」——
    /// 这两种说法写出来的东西完全不同，前者像记忆，后者像会议纪要。
    public static func systemPrompt() -> String {
        joinLines([
            "你在替你自己的记性做一件事：把已经聊过去的一段，收成你自己还留得住的一句话。",
            "这件事不会出现在对话里，对方也不知道。以后你想起他的时候，靠的就是这段记忆。",
            "",
            "写的时候：",
            "- 用第一人称写，就像你自己在心里记下的；对方就是你口中的「你」。",
            "- 留事实与约定：答应过什么、约好的时间地点、他提过的人和事、他在意或怕的东西、你们之间的称呼。",
            "- 丢掉寒暄、重复的话、一次性的语气词；但事实和约定一条都不能丢。",
            "- 不许编造：对话里没有的事、没说过的心思，一个字都不许加。",
            "- 不要写成记录：不要「用户说」「本次对话」「以上」这类词，也不要分点或加小标题。",
            "",
            "只回那段记忆本身，不要解释、不要引号、不要前缀。"
        ])
    }

    /// 分叉调用用的 user：要收的那段对话（有旧摘要时一并给出，请他合成一段）。
    ///
    /// - Parameters:
    ///   - previous: 之前已经记住的那段（没有就传 nil）
    ///   - chunk: 这次要收进去的对话（按时间先后给，函数内部会自己再排一次）
    public static func summaryPrompt(previous: String?, chunk: [MsgView]) -> String {
        let previousText = previous?.trimmed
        let hasPrevious = !(previousText ?? "").isEmpty
        var lines: [String] = [
            "把下面这段对话收成一段记忆。",
            "",
            "要求：",
            "- 用第一人称写，像你自己在心里记下的；说对方的时候用「你」，不要写「用户」「对方」。",
            "- 保留事实与约定：答应过的事、约好的时间地点、他在意的东西、你们之间的称呼和玩笑。",
            "- 丢掉寒暄与重复；同一件事说过几遍的，只留一遍。",
            "- 不许编造对话里没有的内容，也不要替对方补心理活动。",
            "- 一段话，不超过 200 字；不要分点、不要小标题、不要「以上」「本次」这类词。",
            ""
        ]
        if hasPrevious {
            lines.append("【你之前记得的】")
            lines.append(clip(previousText!, max: MAX_PREVIOUS_CHARS))
            lines.append("")
            lines.append("【这次新聊过去的一段】")
        } else {
            lines.append("【这段对话】")
        }
        lines.append(renderTranscript(chunk))
        lines.append("")
        if hasPrevious {
            lines.append("把「之前记得的」和「新聊到的」合成一段记忆，不超过 220 字，只写现在还值得记住的部分。")
        } else {
            lines.append("只回那段记忆本身，不要任何前缀。")
        }
        return joinLines(lines)
    }

    /// 分叉调用用的 user（合并那一步）：把旧摘要和新的一段合成一段。
    ///
    /// 一次调用里合不了（比如先算出新一段、再和旧的并）时用这个；平时
    /// `summaryPrompt` 已经在同一次调用里请他合成了。
    ///
    /// - Parameters:
    ///   - previous: 之前记得的那段
    ///   - chunkSummary: 新收进来的那一段（也可以是原对话，效果一样）
    public static func mergePrompt(previous: String, chunkSummary: String) -> String {
        joinLines([
            "把两段记忆合成一段。",
            "",
            "【之前记得的】",
            clip(previous.trimmed, max: MAX_PREVIOUS_CHARS),
            "",
            "【新收进来的】",
            clip(chunkSummary.trimmed, max: MAX_TRANSCRIPT_CHARS),
            "",
            "合成的时候：",
            "- 用第一人称写，像你自己在心里记下的；说对方的时候用「你」。",
            "- 两段里说的是同一件事，就合成一句；后面改了口径的，以新的为准。",
            "- 事实与约定必须留着，寒暄和重复可以丢；不许编造对话里没有的内容。",
            "- 只回一段话，不超过 220 字，不要分点、不要标题、不要任何前缀。"
        ])
    }

    // MARK: 写回来的能不能用

    /// 把模型写回来的原文洗成一段可用的记忆；洗不出来就返回 nil。
    ///
    /// 比「先剥代码块、再校验」多一道保险：**剥掉了东西**（说明它写过代码块或工具调用）就要求
    /// 剩下的正文足够长（`MIN_SALVAGED_CHARS`），一句「嗯，这个我想记下来。」留不下来。
    /// 顺带清掉「摘要：」「【记忆】」这类前缀与包裹的引号。
    public static func salvageSummary(_ raw: String) -> String? {
        let original = raw.trimmed
        if original.isEmpty { return nil }
        if looksLikeToolBlock(original) && !ChineseTerms.containsSubstring(original, "```") { return nil }
        let stripped = stripCodeBlocks(original).trimmed
        let unwrapped = stripPrefixes(stripped)
        let cleaned = stripWrappingQuotes(unwrapped)
        if cleaned.isEmpty { return nil }
        if cleaned != stripWrappingQuotes(stripPrefixes(original)) {
            // 真的剥掉过东西：剩下的必须能独立成一段话。
            if cleaned.count < MIN_SALVAGED_CHARS { return nil }
        }
        return cleaned
    }

    /// 旧版本写进库里的摘要还健不健康（启动自检用）。
    ///
    /// 比 `isSummaryAcceptable` 更严：那种「剥完只剩半句」的残句在通用判据下是合法的，
    /// 但作为一段回忆太短了（`MIN_LEGACY_CHARS`），启动时清掉、下次重新压。
    public static func isLegacySummaryHealthy(_ text: String) -> Bool {
        if !isSummaryAcceptable(text) { return false }
        return text.trimmed.count >= MIN_LEGACY_CHARS
    }

    /// 模型写回来的这段，能不能当真记下来。
    ///
    /// 宁可不记，也不能把一段废话（「无」「没有」）、一段道歉（「抱歉，我无法……」）、
    /// 或者跟旧摘要一模一样的文字写进他的记忆里。
    ///
    /// - Parameters:
    ///   - text: 模型写回来的原文
    ///   - previous: 之前那段摘要（用来判断「一个字没变」）
    public static func isSummaryAcceptable(_ text: String, previous: String? = nil) -> Bool {
        let clean = text.trimmed
        if clean.count < MIN_SUMMARY_CHARS { return false }
        if clean.count > MAX_SUMMARY_CHARS { return false }
        // 全是标点、表情、空白的不算记忆。
        if !hasReadableChars(clean) { return false }
        if isPlaceholder(clean) { return false }
        if looksLikeRefusal(clean) { return false }
        // 一段记忆里不该出现代码块：模型偶尔会把工具调用（```myfriend-tool …```）当成正文写回来，
        // 那种东西一旦存成「他还记得的事」，之后每一轮都会被当成记忆塞进提示词，
        // 既不像人话，也会把他的工具协议搞乱。
        if looksLikeToolBlock(clean) { return false }
        let before = previous?.trimmed
        if let before, !before.isEmpty, squash(clean) == squash(before) { return false }
        return true
    }

    /// 把摘要变成 system 里的一段。
    ///
    /// 只有「真的压过」（有摘要、也有压到哪一条的时间点）才注入；
    /// 否则返回空串，调用方拼接时不用判断。
    public static func memoryBlock(summary: String?, summarizedUpTo: Int64?) -> String {
        let text = summary?.trimmed ?? ""
        guard let summarizedUpTo, summarizedUpTo > 0 else { return "" }
        if text.isEmpty { return "" }
        return "\n\n\(SUMMARY_HEADER)\n\(clip(text, max: MAX_SUMMARY_CHARS))"
    }

    // MARK: 内部

    /// 把对话排成「你：…／我：…」的样子；消息太多时按条数均分长度，保证每一条都露个脸。
    static func renderTranscript(_ chunk: [MsgView]) -> String {
        let readable = chunk
            .filter(\.hasText)
            .sorted { a, b in
                if a.at != b.at { return a.at < b.at }
                return Lexicographic.utf16Less(a.id, b.id)
            }
        if readable.isEmpty { return "（这一段没有说出什么内容）" }
        let perMessage = min(Swift.max(MAX_TRANSCRIPT_CHARS / readable.count, MIN_MESSAGE_CHARS),
                             MAX_MESSAGE_CHARS)
        return readable.map { message in
            (message.fromUser ? "你：" : "我：") + clip(message.text, max: perMessage)
        }.joined(separator: "\n")
    }

    /// 压成一行，超长截断；提示词里不需要换行，换行只会让人以为在写清单。
    static func clip(_ text: String, max limit: Int) -> String {
        let flat = ChineseTerms.collapseWhitespace(text).trimmed
        if flat.count <= limit { return flat }
        return String(flat.prefix(Swift.max(limit, 1))) + "…"
    }

    /// 去掉 ``` 围起来的块。
    ///
    /// 两种形态分开处理，因为它们意思完全不同：
    /// - **整段被围栏包住**（模型习惯把答案包进 ```…```）：里面的正文就是答案，取出来；
    /// - **正文里夹着一块**（多半是工具调用或代码示例）：整块删掉，只留正文。
    ///
    /// 「删掉之后还剩什么」由 `salvageSummary` 的长度门槛兜住，这里不做判断。
    static func stripCodeBlocks(_ raw: String) -> String {
        let trimmed = raw.trimmed
        if trimmed.isEmpty { return "" }
        let chars = Array(trimmed)
        if starts(with: chars, prefix: "```") {
            let firstLine = indexOf(chars, "\n", from: 0)
            if firstLine > 0 {
                let innerStart = firstLine + 1
                let close = indexOf(chars, "```", from: innerStart)
                let inner = close >= 0
                    ? String(chars[innerStart..<close])
                    : String(chars[innerStart...])
                let rest = close >= 0 ? String(chars[(close + 3)...]) : ""
                let combined = (inner + "\n" + rest).trimmed
                if ChineseTerms.containsSubstring(combined, "```") && combined != trimmed {
                    return stripCodeBlocks(combined)
                }
                return combined
            }
        }
        if !ChineseTerms.containsSubstring(trimmed, "```") { return trimmed }
        var builder = ""
        var index = 0
        while index < chars.count {
            let open = indexOf(chars, "```", from: index)
            if open < 0 {
                builder += String(chars[index...])
                break
            }
            builder += String(chars[index..<open])
            let close = indexOf(chars, "```", from: open + 3)
            if close < 0 { break }
            index = close + 3
        }
        return builder.trimmed
    }

    /// 「摘要：」「【记忆】」这类前缀模型偶尔会自己加。
    static func stripPrefixes(_ text: String) -> String {
        var out = text.trimmed
        while true {
            let before = out
            out = out.removing(prefix: "摘要：").removing(prefix: "摘要:").trimmed
                .removing(prefix: "【记忆】").removing(prefix: "【摘要】").trimmed
            if out == before { return out }
        }
    }

    /// 整段被引号包住时去掉引号（只去一层）。
    static func stripWrappingQuotes(_ text: String) -> String {
        let trimmed = text.trimmed
        if trimmed.count < 2 { return trimmed }
        guard let first = trimmed.first, let last = trimmed.last else { return trimmed }
        let paired = (first == "\"" && last == "\"")
            || (first == "\u{201C}" && last == "\u{201D}")
            || (first == "「" && last == "」")
        if !paired { return trimmed }
        return String(Array(trimmed)[1..<(trimmed.count - 1)]).trimmed
    }

    /// 有没有真的字（汉字、字母、数字）；标点和表情不算。
    static func hasReadableChars(_ text: String) -> Bool {
        text.contains { $0.isLetter || $0.isNumber }
    }

    /// 「无」「没有」「暂无」这类等于什么都没记。
    static func isPlaceholder(_ text: String) -> Bool {
        let bare = squash(text)
        if bare.count <= 3 {
            return PLACEHOLDER_WORDS.contains(bare) || bare.trimmed.isEmpty
        }
        return PLACEHOLDER_WORDS.contains(bare)
    }

    /// 是不是把工具调用 / 代码块当成正文写回来了。
    static func looksLikeToolBlock(_ text: String) -> Bool {
        if ChineseTerms.containsSubstring(text, "```") { return true }
        let squeezed = text.lowercased().replacingOccurrences(of: " ", with: "")
        return ChineseTerms.containsSubstring(squeezed, "myfriend-tool")
            || (ChineseTerms.containsSubstring(squeezed, "\"arguments\":")
                && ChineseTerms.containsSubstring(squeezed, "\"name\":"))
    }

    /// 模型在推脱 / 道歉，说明这次没得到能用的东西。
    static func looksLikeRefusal(_ text: String) -> Bool {
        REFUSAL_PREFIXES.contains { text.hasPrefix($0) }
    }

    /// 去掉空白和标点，只留字：用来比较「有没有变」和「是不是一句废话」。
    static func squash(_ text: String) -> String {
        NOISE.replaceAll(in: text, with: "").trimmed
    }

    static func joinLines(_ lines: [String]) -> String {
        lines.joined(separator: "\n")
    }

    static func starts(with chars: [Character], prefix: String) -> Bool {
        let prefixChars = Array(prefix)
        guard chars.count >= prefixChars.count else { return false }
        return Array(chars[0..<prefixChars.count]) == prefixChars
    }

    static func indexOf(_ chars: [Character], _ needle: String, from start: Int) -> Int {
        let target = Array(needle)
        if target.isEmpty { return min(start, chars.count) }
        guard chars.count >= target.count else { return -1 }
        var index = max(start, 0)
        while index + target.count <= chars.count {
            if Array(chars[index..<(index + target.count)]) == target { return index }
            index += 1
        }
        return -1
    }

    /// Java 正则里的 `\p{Punct}` 只覆盖 ASCII 标点，这里展开写死，再补上中文标点。
    static let NOISE = KotlinRegex(
        ##"[\s!"#$%&'()*+,\-./:;<=>?@\[\]\\^_`{|}~，。、！？；：（）【】「」『』《》…—～·“”‘’]+"##)

    static let PLACEHOLDER_WORDS: Set<String> = [
        "无", "没有", "暂无", "无内容", "空", "略", "没有了", "没有内容", "nothing", "none", "null"
    ]

    static let REFUSAL_PREFIXES = ["抱歉", "对不起", "我无法", "无法完成", "作为", "很抱歉"]
}

extension String {
    /// Kotlin 的 `removePrefix`：只去开头一次。
    func removing(prefix: String) -> String {
        if hasPrefix(prefix) { return String(dropFirst(prefix.count)) }
        return self
    }

    /// Kotlin 的 `removeSuffix`：只去结尾一次。
    func removing(suffix: String) -> String {
        if hasSuffix(suffix) { return String(dropLast(suffix.count)) }
        return self
    }
}
