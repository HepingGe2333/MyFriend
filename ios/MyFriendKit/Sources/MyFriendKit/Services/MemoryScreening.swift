import Foundation

// 来源：app/src/main/java/com/myfriend/app/domain/MemoryScreening.kt
//
// 记忆筛查：把「模型一次抽一堆」变成「同一件事出现两次才落库」，以及召回时的本地重排。
//
// 为什么它在 Services 而不是 Domain：iOS 的 Domain 目前只放了「纯算法且被多端共用」的那几块
//（ChineseTerms / LocalEmbedding / VectorMath / MemoryLifecycle / Recall …）。MemoryScreening
// 需要 `MemoryDrawer` / `MemoryTags`（已在 Domain/MemoryCommon.swift）+ 召回预算裁剪，
// 更靠近「仓储怎么挑记忆」，所以按移植计划落在服务层。
//
// 全部是纯函数：不碰数据库、不读时钟（now 由调用方传入），所有入口对空列表与空字符串安全。

// MARK: - 一行已有记忆的最小视图

/// 一行记忆的「筛查视图」：不依赖数据库实体，便于单测。
public struct MemoryRow: Equatable, Sendable {
    public var id: String
    public var content: String
    public var drawer: MemoryDrawer
    /// 1..5。
    public var importance: Int
    public var pinned: Bool
    /// 被提到过几次。
    public var hits: Int
    public var lastHitAt: Int64
    public var createdAt: Int64
    /// 结构化标签（人 / 事 / 物 / 情绪）。
    public var tags: [String]
    /// 这件事**发生**的时间（不是被记住的时间）；推断不出来就是 nil，此时不加权。
    public var happenedAt: Int64?

    public init(
        id: String,
        content: String,
        drawer: MemoryDrawer,
        importance: Int,
        pinned: Bool = false,
        hits: Int = 1,
        lastHitAt: Int64 = 0,
        createdAt: Int64 = 0,
        tags: [String] = [],
        happenedAt: Int64? = nil
    ) {
        self.id = id
        self.content = content
        self.drawer = drawer
        self.importance = importance
        self.pinned = pinned
        self.hits = hits
        self.lastHitAt = lastHitAt
        self.createdAt = createdAt
        self.tags = tags
        self.happenedAt = happenedAt
    }
}

/// 模型抽取出来的一条候选记忆。
public struct MemoryCandidate: Equatable, Sendable {
    public var content: String
    public var drawer: MemoryDrawer
    public var importance: Int
    /// 触发它的原话片段。
    public var sourceQuote: String
    /// 模型给的原始 category，如 FACT/PREFERENCE/EVENT/RELATIONSHIP/OTHER。
    public var categoryName: String
    public var confidence: Float
    /// 模型给的短标签（人 / 事 / 物 / 情绪），最多 5 个；给不出来就是空。
    public var tags: [String]
    /// 这件事发生的时间（由「昨天/上周三/去年冬天」换算出的毫秒）；换算不出来是 nil。
    public var happenedAt: Int64?

    public init(
        content: String,
        drawer: MemoryDrawer,
        importance: Int,
        sourceQuote: String = "",
        categoryName: String = "",
        confidence: Float = 0.6,
        tags: [String] = [],
        happenedAt: Int64? = nil
    ) {
        self.content = content
        self.drawer = drawer
        self.importance = importance
        self.sourceQuote = sourceQuote
        self.categoryName = categoryName
        self.confidence = confidence
        self.tags = tags
        self.happenedAt = happenedAt
    }
}

/// 一次筛查的判定。
public enum MemoryDecision: Equatable {
    /// 第一次提到：先只留一个「回声」，不下沉为长期记忆。
    case echo(candidate: MemoryCandidate)
    /// 第二次提到同一件事：正式落库。`mergeIntoId` 非空表示还可以并进那条已有记忆。
    case promote(candidate: MemoryCandidate, mergeIntoId: String?)
    /// 已有记忆再被提到：加固（hits+1、可能升重要度），可选补充内容。
    case reinforce(id: String, appendedText: String?)
    /// 丢弃（一次性的情绪、太含糊、和人格无关）。
    case reject(reason: String)
}

// MARK: - MemoryScreening

public enum MemoryScreening {

    // ---------- 判定阈值 ----------

    /// 少于 8 字通常没有可长期复用的信息（「今天好累啊」「在吗」之类）。
    static let minContentLength = 8

    /// 超过 300 字更像一段叙述而不是一条记忆，交给摘要而不是原样落库。
    static let maxContentLength = 300

    /// 模型自己都不确定的信息不落库，避免噪声污染人格。
    static let minConfidence: Float = 0.35

    /// 与已有记忆相似度到这个程度，视为「同一件事又说了」→ 加固。
    static let reinforceSimilarity = 0.72

    /// 与抽屉里的回声相似度到这个程度，视为「第二次提到」→ 正式落库。
    /// 比加固阈值低：回声只是临时线索，宁可多认一次重复，也不要丢掉真正重要的事。
    static let sameTopicSimilarity = 0.60

    /// 新内容要带来至少这么多字的新信息，才值得追加到已有记忆后面。
    static let newInfoChars = 6

    /// 一个分句与已有记忆的词元重合度低于此值，才算「新信息」。
    static let fragmentOverlapLimit = 0.60

    /// 归一化后不足 8 字的短句信息量太少，容易误判成同一件事，按比例衰减。
    static let shortTextLength = 8.0

    /// 约定、身份、家人这类信息，只要出现就至少 4 分，避免被时间冲淡。
    static let importanceFloorForCommitment = 4

    // ---------- 召回权重 ----------
    //
    // 下面这些数字是他「想事的习惯」，不是设置项：用户看不到、也调不了。
    // 它们只在同一个 query 下决定几条记忆谁先谁后，不改变「记不记得住」本身。

    /// 钉住的记忆先摆出来：用户亲手钉的，本来就该排在前面。
    static let pinnedBonus = 2.0

    /// 和 query 的词元重合度（加权余弦 + 覆盖率）：回忆首先是「找对东西」。
    static let relevanceWeight = 3.0

    /// 这条记忆本身有多重要（1..5 压到 0..1）。
    static let importanceWeight = 1.0

    /// 新鲜度：不是越新越好，但太旧的东西该往后排。
    static let freshnessWeight = 0.8
    static let freshnessHalfLifeDays = 30.0
    static let millisPerDay = 86_400_000.0

    /// 标签命中的加成：query 里出现人名/主题词时，带这个标签的记忆稍微靠前。
    static let tagWeight = 1.2

    /// 一条记忆最多算几个标签命中（再多也不加分，避免标签堆得多的记忆占便宜）。
    static let tagMaxHits = 2

    /// 时间指代（「去年冬天」）落在 happenedAt 附近的加成。
    static let timeWeight = 1.0

    /// 向量相似度的上限加成：最多 0.8。
    ///
    /// 为什么是 0.8：它是「字面近似的语义」（见 LocalEmbedding），能补「换个说法字面前缀对不上」的漏，
    /// 但也可能整段跑偏。这个上限比新鲜度（0.8）、时间指代（1.0）、重要度（1.0）、钉住（2.0）都不高，
    /// 所以它**只能在同一档相关度的记忆之间微调先后**，不可能把一条明显不重要、时间对不上的记忆
    /// 顶到一条被钉住的重要记忆前面。换句话说：向量帮他「想起来」，不替他决定「什么更要紧」。
    static let vectorWeight = 0.8

    /// 时间区间的软边界：区间外按 120 天半衰期衰减，一年外几乎不加权。
    static let timeHintHalfLifeDays = 120.0

    /// 抽屉只当背景色：聊到某个话题时，关于对方的事实最容易用上，共同的经历次之，
    /// 「他自己是什么样的人」更像底色。权重差得很少，盖不过命中本身。
    static let drawerWeight: [MemoryDrawer: Double] = [
        .user: 1.0,
        .event: 0.96,
        .selfDrawer: 0.92
    ]

    // ---------- 词表 ----------

    /// 不携带信息的口头前缀，比较相似度前先剥掉。
    static let noInfoPrefixes = [
        "我觉得", "我感觉", "说实话", "说真的", "其实", "反正",
        "然后", "就是", "大概", "好像", "不过", "而且",
        "今天", "昨天", "刚才", "现在", "刚刚", "这会儿"
    ]

    /// 归一化时要清掉的标点（全角半角都算）。
    static let punctuation: Set<Character> = Set("，。！？、；：,.!?;:'\"“”‘’（）()【】[]…—～·")

    /// 纯寒暄候选，剥干净后没剩几个字就丢弃。
    static let greetings = [
        "你好", "您好", "在吗", "在不在", "嗨", "哈喽", "早安", "晚安",
        "早上好", "中午好", "晚上好", "拜拜", "再见", "睡了吗", "吃了吗",
        "吃饭了吗", "哈哈", "嘿嘿", "呵呵", "嗯嗯", "好的", "收到",
        "谢谢", "没事", "辛苦了"
    ]

    static let latinGreetings: Set<String> = ["hi", "hello", "ok", "bye", "goodnight"]

    /// 时效词：和情绪词一起出现，说明是当下的情绪。
    static let timeWordRegex = KotlinRegexPublic("今天|昨天|刚才|刚刚|现在|今晚|这会儿|这两天|最近|突然|忽然|此刻|这天")

    /// 情绪词：一次性情绪的典型表达。
    static let emotionWordRegex = KotlinRegexPublic(
        "好累|很累|累死|累了|难受|难过|开心|高兴|烦|无聊|困|emo|啊啊|郁闷|焦虑|崩溃|孤独|委屈|生气|想哭|睡不着|失眠|丧"
    )

    /// 按标点切句，用来判断新内容里哪一句是真正的新信息。
    static let fragmentSeparators = CharacterSet(charactersIn: "，。；！？、,;!?\n\r")

    /// 值得给重要度保底的信息类型：约定、身份、家人。
    static let importantKeywords = [
        "约定", "答应", "说好", "约好", "承诺", "纪念日", "生日", "名字", "叫",
        "职业", "工作", "家人", "妈妈", "爸爸", "父母", "家乡", "过敏", "忌口",
        "每周", "每天", "每年", "每次"
    ]

    // ---------- 抽屉映射 ----------

    /// 把模型给的 category 名映射到抽屉。兼容旧枚举与中英文自定义标签。
    public static func drawerOf(_ categoryName: String) -> MemoryDrawer {
        MemoryDrawer.fromCategoryName(categoryName)
    }

    // ---------- 归一化与相似度 ----------

    /// 归一化：去空白、统一全半角标点、去「我觉得/今天」这类无信息前缀，便于比较。
    public static func normalize(_ text: String) -> String {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "" }
        var halfWidth = ""
        halfWidth.reserveCapacity(text.count)
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
        var compact = ""
        compact.reserveCapacity(lowered.count)
        for ch in lowered where !ch.isWhitespace && !punctuation.contains(ch) {
            compact.append(ch)
        }
        var result = compact
        // 反复剥前缀：「我觉得今天其实…」要全部剥掉，但要给正文至少留 2 个字。
        var changed = true
        while changed && result.count > 2 {
            changed = false
            for prefix in noInfoPrefixes where result.count - prefix.count >= 2 && result.hasPrefix(prefix) {
                result = String(result.dropFirst(prefix.count))
                changed = true
                break
            }
        }
        return result
    }

    /// 0..1 的相似度：词元 Jaccard + 包含关系加权，中文按字 bigram 切词。
    public static func similarity(_ a: String, _ b: String) -> Double {
        let na = normalize(a)
        let nb = normalize(b)
        if na.isEmpty || nb.isEmpty { return 0.0 }
        if na == nb { return 1.0 }

        let ta = tokenize(na)
        let tb = tokenize(nb)
        if ta.isEmpty || tb.isEmpty { return 0.0 }

        let inter = Double(ta.intersection(tb).count)
        let jaccard = inter / Double(ta.count + tb.count - Int(inter))
        let containment = inter / Double(min(ta.count, tb.count))

        // 同一件事换说法时字面重合往往很高，bigram 之外再补一层字符包含度。
        let charsA = Set(na)
        let charsB = Set(nb)
        let charInter = Double(charsA.intersection(charsB).count)
        let charContainment = charInter / Double(min(charsA.count, charsB.count))

        var score = 0.40 * jaccard + 0.35 * containment + 0.25 * charContainment

        let minLength = min(na.count, nb.count)
        // 短句（「对方喜欢猫」vs「对方喜欢狗」）词元太少，容易虚高，按长度衰减。
        if Double(minLength) < shortTextLength {
            score *= Double(minLength) / shortTextLength
        }
        // 一方完整包含另一方几乎总是同一件事。
        if minLength >= 5 && (na.contains(nb) || nb.contains(na)) {
            let longer = max(na.count, nb.count)
            score = max(score, 0.75 + 0.25 * Double(minLength) / Double(longer))
        }
        return min(max(score, 0.0), 1.0)
    }

    // ---------- 判定 ----------

    /// 判定一条候选：加固已有的 / 第二次提到落库 / 先留回声 / 丢弃。
    ///
    /// - Parameter echoes: 之前只被提到过一次的候选（平铺列表，只跟同一个抽屉比）。
    public static func decide(
        _ candidate: MemoryCandidate,
        existing: [MemoryRow],
        echoes: [MemoryCandidate],
        now: Int64
    ) -> MemoryDecision {
        let content = candidate.content.trimmingCharacters(in: .whitespacesAndNewlines)
        if let reason = rejectReason(candidate, content: content) {
            return .reject(reason: reason)
        }

        // 1) 已有记忆高度相似 → 加固，不新增。
        let bestExisting = bestExistingMatch(content: content, drawer: candidate.drawer, existing: existing)
        if let bestExisting, bestExisting.similarity >= reinforceSimilarity {
            let appended = appendedSentence(candidateContent: content, existingContent: bestExisting.row.content)
            return .reinforce(id: bestExisting.row.id, appendedText: appended)
        }

        // 2) 与该抽屉的回声相似 → 第二次提到，正式落库。
        let bestEcho = bestEchoMatch(candidate: candidate, content: content, echoes: echoes)
        if let bestEcho, bestEcho.similarity >= sameTopicSimilarity {
            let mergeIntoId = bestExisting
                .flatMap { existing in
                    existing.similarity >= sameTopicSimilarity && existing.row.drawer == candidate.drawer
                        ? existing.row.id : nil
                }
            // 两次出现取更大的重要度；约定/身份类信息再兜底到 4。
            let importance = max(
                candidate.importance,
                max(bestEcho.candidate.importance, commitmentFloor(content, bestEcho.candidate.content))
            )
            var promoted = candidate
            promoted.content = content
            promoted.importance = min(max(importance, 1), 5)
            return .promote(candidate: promoted, mergeIntoId: mergeIntoId)
        }

        // 3) 第一次提到 → 只留回声（now 只用于调用方记录时间，判定本身不需要）。
        var echoed = candidate
        echoed.content = content
        return .echo(candidate: echoed)
    }

    /// 召回排序（本地重排）：pinned / 词元重合度 / 向量相似度 / 标签命中 / 时间指代 / 重要度 / 新鲜度 / 抽屉背景。
    ///
    /// 这一步**不查库**：查库只负责给候选，排序全在内存里算。所以索引没建好、query 为空、
    /// 向量表空着、数据库出问题，排序本身依旧成立——最坏也只是候选少一点、少一路信号。
    public static func rerankForRecall(
        memories: [MemoryRow],
        query: String,
        now: Int64,
        vectorScores: [String: Double] = [:]
    ) -> [MemoryRow] {
        if memories.isEmpty { return [] }
        let span = ChineseTerms.timeSpanOf(query, now: now)
        return memories
            .map { ($0, recallScore(memory: $0, query: query, now: now, span: span, vectorScores: vectorScores)) }
            .sorted { a, b in
                if a.1 != b.1 { return a.1 > b.1 }
                if a.0.lastHitAt != b.0.lastHitAt { return a.0.lastHitAt > b.0.lastHitAt }
                if a.0.createdAt != b.0.createdAt { return a.0.createdAt > b.0.createdAt }
                return Lexicographic.utf16Less(a.0.id, b.0.id)
            }
            .map(\.0)
    }

    /// 旧名字，保留给既有调用点：语义已经是新的本地重排。
    public static func rankForRecall(
        memories: [MemoryRow],
        query: String,
        now: Int64,
        vectorScores: [String: Double] = [:]
    ) -> [MemoryRow] {
        rerankForRecall(memories: memories, query: query, now: now, vectorScores: vectorScores)
    }

    /// 按字符预算挑选，返回按分数降序的前 N 条。
    ///
    /// 超预算的那条跳过，后面的短记忆仍可能塞得下（顺序不变）。
    public static func selectWithinBudget(
        memories: [MemoryRow],
        query: String,
        now: Int64,
        maxChars: Int,
        vectorScores: [String: Double] = [:]
    ) -> [MemoryRow] {
        if memories.isEmpty || maxChars <= 0 { return [] }
        var result: [MemoryRow] = []
        var used = 0
        for memory in rerankForRecall(memories: memories, query: query, now: now, vectorScores: vectorScores) {
            let cost = memory.content.count
            if used + cost > maxChars { continue }
            result.append(memory)
            used += cost
        }
        return result
    }

    // ---------- 内部：拒绝规则 ----------

    static func rejectReason(_ candidate: MemoryCandidate, content: String) -> String? {
        if content.isEmpty { return "内容为空" }
        if content.count < minContentLength { return "内容太短，没有可长期复用的信息" }
        if content.count > maxContentLength { return "内容太长，不像一条记忆" }
        if candidate.confidence < minConfidence { return "模型自己也不确定，先不留" }
        if isSmallTalk(content) { return "只是寒暄，没有长期价值" }
        if isOneOffEmotion(content, importance: candidate.importance) { return "一次性的情绪，不必长期记住" }
        return nil
    }

    /// 全是寒暄：把问候语逐个剥掉后只剩语气词。
    static func isSmallTalk(_ content: String) -> Bool {
        let text = normalize(content)
        if text.isEmpty { return true }
        if latinGreetings.contains(text) { return true }
        var rest = text
        var changed = true
        while changed {
            changed = false
            for greeting in greetings where rest.contains(greeting) {
                rest = rest.replacingOccurrences(of: greeting, with: "")
                changed = true
            }
        }
        return rest.count <= 2
    }

    /// 一次性情绪：带时效词 + 情绪词，且模型给的长期重要度本来就不高。
    static func isOneOffEmotion(_ content: String, importance: Int) -> Bool {
        if importance > 2 { return false }
        let text = content.lowercased()
        return timeWordRegex.containsMatch(in: text) && emotionWordRegex.containsMatch(in: text)
    }

    // ---------- 内部：匹配与追加 ----------

    static func bestExistingMatch(
        content: String,
        drawer: MemoryDrawer,
        existing: [MemoryRow]
    ) -> (row: MemoryRow, similarity: Double)? {
        if existing.isEmpty { return nil }
        var best: (row: MemoryRow, similarity: Double)?
        for row in existing {
            let score = similarity(content, row.content)
            guard score > 0 else { continue }
            guard let current = best else {
                best = (row, score)
                continue
            }
            if score > current.similarity
                || (score == current.similarity && row.drawer == drawer && current.row.drawer != drawer)
                || (score == current.similarity && (row.drawer == drawer) == (current.row.drawer == drawer)
                    && row.importance > current.row.importance) {
                best = (row, score)
            }
        }
        return best
    }

    static func bestEchoMatch(
        candidate: MemoryCandidate,
        content: String,
        echoes: [MemoryCandidate]
    ) -> (candidate: MemoryCandidate, similarity: Double)? {
        // echoes 是平铺列表，只跟同一个抽屉比，避免「关于你」和「共同经历」串味。
        let sameDrawer = echoes.filter {
            $0.drawer == candidate.drawer && !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        if sameDrawer.isEmpty { return nil }
        var best: (candidate: MemoryCandidate, similarity: Double)?
        for echo in sameDrawer {
            let score = similarity(content, echo.content)
            guard score > 0 else { continue }
            guard let current = best else {
                best = (echo, score)
                continue
            }
            if score > current.similarity
                || (score == current.similarity && echo.importance > current.candidate.importance) {
                best = (echo, score)
            }
        }
        return best
    }

    /// 判断新内容里是否有值得追加的句子。
    ///
    /// 按标点切句，逐句与已有记忆比对词元重合度：重合度低的分句才是新信息。
    /// 累加起来不足 6 字就返回 nil（只是换个说法，不该把记忆越写越长）。
    static func appendedSentence(candidateContent: String, existingContent: String) -> String? {
        let existingNorm = normalize(existingContent)
        if existingNorm.isEmpty { return nil }
        let existingTokens = tokenize(existingNorm)
        if existingTokens.isEmpty { return nil }

        var fresh: [String] = []
        var freshChars = 0
        for fragment in candidateContent.components(separatedBy: fragmentSeparators) {
            let trimmed = fragment.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { continue }
            let fragmentNorm = normalize(trimmed)
            if fragmentNorm.isEmpty { continue }
            let fragmentTokens = tokenize(fragmentNorm)
            if fragmentTokens.isEmpty { continue }
            let overlap = Double(fragmentTokens.intersection(existingTokens).count) / Double(fragmentTokens.count)
            if overlap >= fragmentOverlapLimit { continue }
            fresh.append(trimmed)
            freshChars += fragmentNorm.count
        }

        if fresh.isEmpty || freshChars < newInfoChars { return nil }
        let sentence = fresh
            .joined(separator: "，")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "。，；！？、,;!?"))
        if sentence.isEmpty { return nil }
        return sentence + "。"
    }

    /// 约定 / 身份 / 家人类关键词出现时，重要度至少 4。
    static func commitmentFloor(_ texts: String...) -> Int {
        let raw = texts.joined(separator: " ").lowercased()
        if raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return 0 }
        return importantKeywords.contains(where: { raw.contains($0) }) ? importanceFloorForCommitment : 0
    }

    // ---------- 内部：召回打分 ----------

    static func recallScore(
        memory: MemoryRow,
        query: String,
        now: Int64,
        span: TimeSpan?,
        vectorScores: [String: Double]
    ) -> Double {
        let pinned = memory.pinned ? pinnedBonus : 0.0
        let relevance = ChineseTerms.relevance(query: query, document: memory.content)
        // 向量分是「换个说法也像」的一路补充：和词元重合度叠加，但有自己的上限。
        let vector = (vectorScores[memory.id] ?? 0.0).isFinite
            ? min(max(vectorScores[memory.id] ?? 0.0, 0.0), 1.0)
            : 0.0
        let importance = Double(min(max(memory.importance, 1), 5)) / 5.0
        let fresh = freshness(memory, now: now)
        let tag = tagAffinity(query: query, tags: memory.tags)
        let time = timeAffinity(span: span, happenedAt: memory.happenedAt)
        let drawer = drawerWeight[memory.drawer] ?? 1.0
        let score = pinned
            + relevance * relevanceWeight
            + vector * vectorWeight
            + importance * importanceWeight
            + fresh * freshnessWeight
            + tag * tagWeight
            + time * timeWeight
        return score * drawer
    }

    /// 标签命中 0..1：query 里出现这个标签（「妹妹」），或标签的词元被 query 覆盖。
    ///
    /// 标签只用来**加分**，不用来筛选：结构是给他查记忆用的辅助，
    /// 不能把「他记得的事」变成一条结构化查询的结果。
    public static func tagAffinity(query: String, tags: [String]) -> Double {
        let hits = ChineseTerms.tagHits(query: query, tags: tags, max: tagMaxHits)
        if hits <= 0 { return 0.0 }
        return Double(hits) / Double(tagMaxHits)
    }

    /// 时间指代邻近 0..1：落在区间里算满分，区间外按半衰期给残值。
    ///
    /// 没有 happenedAt 就一律 0，**不拿 createdAt 顶替**：那是「什么时候记住的」，
    /// 不是「什么时候发生的」。宁可少加一次权，也不要把「刚记下来的往事」当成「最近发生的事」。
    public static func timeAffinity(span: TimeSpan?, happenedAt: Int64?) -> Double {
        guard let span, let happenedAt, happenedAt > 0 else { return 0.0 }
        if span.contains(at: happenedAt) { return 1.0 }
        let days = span.distanceDays(at: happenedAt)
        return min(max(exp(-days / timeHintHalfLifeDays) * 0.5, 0.0), 1.0)
    }

    /// 新鲜度 0..1：以 30 天为半衰期，越久没被提到越低；没有时间戳时按最旧处理。
    static func freshness(_ memory: MemoryRow, now: Int64) -> Double {
        let last = memory.lastHitAt > 0 ? memory.lastHitAt : memory.createdAt
        if last <= 0 { return 0.0 }
        let days = Double(max(now - last, 0)) / millisPerDay
        return exp(-days / freshnessHalfLifeDays)
    }

    // ---------- 内部：切词 ----------

    /// 词元集合：中文按相邻两个字（bigram）切，英文/数字按整词切。
    /// 中文不做分词，bigram 在短文本上足够稳，也不会因为分词错误崩掉。
    static func tokenize(_ normalized: String) -> Set<String> {
        if normalized.isEmpty { return [] }
        var tokens = Set<String>()
        var cjk = ""
        var latin = ""

        func flushCjk() {
            if cjk.isEmpty { return }
            if cjk.count == 1 {
                tokens.insert("c:" + cjk)
            } else {
                let chars = Array(cjk)
                for index in 0..<(chars.count - 1) {
                    tokens.insert(String([chars[index], chars[index + 1]]))
                }
            }
            cjk = ""
        }

        func flushLatin() {
            if latin.isEmpty { return }
            tokens.insert("w:" + latin)
            latin = ""
        }

        for ch in normalized {
            if ChineseTerms.isCjk(ch) {
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
        return tokens
    }
}

/// 给筛查用的正则小包装（与 ChineseTerms 内部的 `KotlinRegex` 同义，
/// 但那个是 internal 且只在自己文件里用；这里是服务层自己的需要）。
struct KotlinRegexPublic {
    private let regex: NSRegularExpression

    init(_ pattern: String) {
        regex = (try? NSRegularExpression(pattern: pattern)) ?? (try! NSRegularExpression(pattern: "(?!)"))
    }

    func containsMatch(in text: String) -> Bool {
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.firstMatch(in: text, options: [], range: range) != nil
    }
}
