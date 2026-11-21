import Foundation

// ---------------------------------------------------------------------------
// 0.15「高置信自动记忆」：只在正文说「记住了」不算记住，模型也常常真的忘了调 `remember`。
//
// 真实短板（体验审计）：用户说「我对花生过敏」，模型回一句「好，我记住了」，
// 这一轮没有任何 `remember` 调用 —— 下一轮它就不记得了。提示词已经写了「只在正文里说
// 记住了不算」，但提示词不是保险栓：过敏、忌口、生日、纪念日、重要约定这类信息，
// 说漏一次代价很大（可能是真出事），所以再加一条**纯规则**的兜底：
//
// 1. 用户这一轮的话里明确出现高置信模式（第一人称的过敏 / 忌口 / 生日 / 纪念日 / 重要约定）；
// 2. 这一轮模型**没有调用** `remember`（调过就不补，免得写两遍）；
// 3. 仓储里还没有同一件事（按正文归一化后的包含关系去重）。
//
// 三个条件都成立时，由 `ChatService` 在回复落库之后写一条 `USER` 抽屉的记忆，
// importance 4–5、tags 带 `auto` 与类型标签（与 Android `domain/AutoMemory.kt` 同一套落库值，
// 记忆会随备份跨端同步，两端写出来的东西必须长得一样）。
//
// 这里**不碰数据库、不碰提示词、不调模型**：全是纯函数，
// 所以「哪些话算高置信」「取哪一段当记忆」「算不算重复」都能被直接钉死。
// ---------------------------------------------------------------------------

/// 高置信自动记忆（纯规则）。
public enum HighConfidenceMemory {

    /// 自动记忆的统一标签：写进 `memories.tags`，记忆抽屉/日志据此区分来源。
    ///
    /// 用小写英文而不是中文：它是**落库值**（会进备份、跨端同步），不是界面文案。
    /// 与 Android `AutoMemory.TAG` 逐字一致。
    public static let autoTag = "auto"

    /// 命中规则。顺序 = 优先级（过敏/忌口事关安全，先认它；约定最轻）。
    ///
    /// case 与 tag 与 Android `AutoMemory.Kind` 一致：ALLERGY（含忌口）、BIRTHDAY、
    /// ANNIVERSARY、PROMISE。
    public enum Rule: String, CaseIterable, Sendable {
        /// 过敏 / 忌口 / 不能吃 / 吃不了 / 不能碰：忘了会出事。
        case allergy = "ALLERGY"
        /// 生日。
        case birthday = "BIRTHDAY"
        /// 纪念日。
        case anniversary = "ANNIVERSARY"
        /// 重要约定 / 说好了。
        case promise = "PROMISE"

        /// 落库时一起打上的类型标签（与 `autoTag` 一起进 `tags`）。
        public var tag: String {
            switch self {
            case .allergy: return "allergy"
            case .birthday: return "birthday"
            case .anniversary: return "anniversary"
            case .promise: return "promise"
            }
        }

        /// 写进记忆库时的重要度（4–5：过敏/生日/纪念日记到最牢，约定低一档）。
        public var importance: Int {
            switch self {
            case .allergy, .birthday, .anniversary: return 5
            case .promise: return 4
            }
        }
    }

    /// 一次命中：规则 + 取出来的原句片段 + 重要度 + 标签。
    public struct Hit: Equatable, Sendable {
        public var rule: Rule
        /// 原句里的关键片段（同一小句，≤ `maxFragmentChars` 字，不改写、不推断）。
        public var content: String
        public var importance: Int
        public var tags: [String]

        public init(rule: Rule, content: String, importance: Int, tags: [String]) {
            self.rule = rule
            self.content = content
            self.importance = importance
            self.tags = tags
        }
    }

    /// 自动记忆正文的长度上限：够看清是什么事，又不至于把整段独白抄进去。
    public static let maxFragmentChars = 80

    /// 允许自动记的几类长期事实（顺序就是优先级）。
    ///
    /// 规则刻意做得**窄**：
    /// - 只认**第一人称**：「我朋友对花生过敏」「他生日是……」不是「我」的事，不自动记；
    /// - 过敏那一类要求「我」与「过敏/忌口/不能吃……」出现在同一小句里（≤12 字间隔），
    ///   免得把「我最近听说有人海鲜过敏」记成自己的忌口；
    /// - 不认口味偏好（「我喜欢吃草莓」）与临时安排（「今天不吃饭」）。
    ///
    /// 与 Android `AutoMemory.RULES` 同一套语义（两端同一份规则）。
    static let rules: [(rule: Rule, pattern: String)] = [
        (.allergy, "(我|本人|咱)(?!朋友|朋|妈|爸|哥|姐|弟|妹|家|同事|同学|领导|老师|他|她)"
            + "[^。！？!?\\n]{0,12}(过敏|忌口|不能吃|吃不了|不能喝|不能碰)"),
        (.birthday, "(我的?生日|我生日)"),
        (.anniversary, "(我们的?纪念日)"),
        (.promise, "(重要约定|我们的约定|我答应过你|我答应你|我们说好|我们说定|约好以后)")
    ]

    /// 否定说法：命中规则但语义相反时不算（「我不过敏」「我没忌口」）。
    public static let negations = ["不过敏", "没过敏", "没有过敏", "不忌口", "没忌口", "不用忌口"]

    /// 问句不算事实：「我生日是哪天」不是记忆，「我生日是三月十四号」才是。
    ///
    /// 为什么要有这一条：`生日` / `过敏` 这些词在问句里出现得比陈述句还多，
    /// 不筛的话会写出「我生日是哪天」这种没有任何信息量的记忆，还占掉召回预算。
    /// 代价是带「吗 / 呢」的陈述句也会被跳过（宁可漏，模型多半还是会自己调 `remember`）。
    public static let questionMarkers: [String] = [
        "?", "？", "吗", "呢", "什么", "几号", "几岁", "多少", "哪天", "哪年", "哪一天", "是不是", "好不好"
    ]

    // MARK: - 检测

    /// 用户这一轮的话里有没有高置信事实；没有就返回 nil。
    ///
    /// 多条规则同时命中时只取优先级最高的那一条（一条消息通常只装得下一件事；
    /// 真有两件事，下一轮再说也还来得及）。
    public static func detect(_ message: String) -> Hit? {
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        for entry in rules {
            guard let regex = try? Regex(entry.pattern) else { continue }
            guard let match = text.firstMatch(of: regex) else { continue }
            let matched = String(text[match.range])
            // 「我不过敏」这种命中是反的：跳过这条规则，继续看下一条。
            guard !negations.contains(where: { matched.contains($0) }) else { continue }
            let content = clause(containing: match.range, in: text)
            guard content.count >= 2 else { continue }
            guard !questionMarkers.contains(where: { content.contains($0) }) else { continue }
            return Hit(
                rule: entry.rule,
                content: content,
                importance: entry.rule.importance,
                tags: [autoTag, entry.rule.tag]
            )
        }
        return nil
    }

    /// 同一件事是不是已经记过了（防止同一句反复说就写一堆重复记忆）。
    ///
    /// 判据只有一条：去掉空白与标点后，两条内容互相包含。**不**做模糊相似度：
    /// 自动记忆宁可漏掉一次「换个说法的同一件事」，也不要在抽屉里堆两条几乎一样的记忆。
    public static func isDuplicate(of content: String, among existingContents: [String]) -> Bool {
        let target = normalized(content)
        guard target.count >= 3 else { return false }
        for existing in existingContents {
            let other = normalized(existing)
            guard other.count >= 3 else { continue }
            if other.contains(target) || target.contains(other) { return true }
        }
        return false
    }

    /// 归一化：只留字母与数字（中文汉字属于字母），去掉空白、标点与符号。
    static func normalized(_ text: String) -> String {
        String(text.lowercased().filter { $0.isLetter || $0.isNumber })
    }

    /// 命中所属的那一小句：向左到上一个句末标点，向右到下一个句末标点，取 ≤80 字。
    ///
    /// 只在句末标点（。！？；换行）处切，不在逗号处切：
    /// 「我对花生过敏，别点带坚果的菜」整句都是有用的信息，切成「我对花生过敏」会丢掉后半个提醒。
    static func clause(containing range: Range<String.Index>, in text: String) -> String {
        let breakers: Set<Character> = ["。", "！", "？", "；", ";", "!", "?", "\n", "\r"]
        var start = text.startIndex
        var cursor = range.lowerBound
        while cursor > text.startIndex {
            let previous = text.index(before: cursor)
            if breakers.contains(text[previous]) {
                start = cursor
                break
            }
            cursor = previous
        }
        var end = text.endIndex
        cursor = range.upperBound
        while cursor < text.endIndex {
            if breakers.contains(text[cursor]) {
                end = cursor
                break
            }
            cursor = text.index(after: cursor)
        }
        let fragment = text[start..<end].trimmingCharacters(in: .whitespacesAndNewlines)
        // 与 Android 一致：句内连续空白压成一个空格，再截断。
        let collapsed = fragment.replacingOccurrences(
            of: "\\s+", with: " ", options: .regularExpression
        )
        return String(collapsed.prefix(maxFragmentChars))
    }
}
