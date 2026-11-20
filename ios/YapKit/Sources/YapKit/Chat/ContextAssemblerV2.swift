import Foundation

// ---------------------------------------------------------------------------
// 0.15 上下文装配 v2（`docs/design/context-memory-v2.md` + `spec/context-profile/README.md`）。
//
// 固定顺序（顺序就是语义，不能乱；与 `PromptBuilder.PromptSection.stateFirstOrder` 同源）：
//
// ```
// 1. 当前状态              ← 时间 / 在做什么 / 心情（直觉标签 + 三维）
// 2. 引导                  ← 这一轮的语气分寸（g12 / g17）
// 3. 人设                  ← 身份 / 世界 / 话风 / 关系
// 4. 工具                  ← 主动唤醒能力 + 工具清单
// 5. 反 AI                 ← core 五条
// 6. 记忆 / 备忘录          ← 长期（≤500）/ 短期（≤200）/ 对话主题
// 7. 语言                  ← 「用对方的语言回答」
// 8. 边界                  ← 硬约束，永远收尾
// ---
// 9. 最近聊天原文           ← 剩余预算全部给历史（带 [HH:mm] 时间戳）
// ```
//
// 0.15 有两处变化，都是 Bench v2 搜出来的（`eval/reports/context-search-combine-*.md`）：
// - **state_first**：状态与引导挪到人设前面（5 样本 85.3 vs 84.3，硬失败率 23.3% vs 26.7%）；
// - **分段可组合**：原来「人设+工具+反AI+边界」是一整块塞在第 1 段，现在按
//   `PromptSection` 一段一段排；记忆与主题插在反 AI 之后、语言之前。
//
// 预算（硬约束，`ExperienceContract.practicalPlan`）：
// - 段上限来自共享契约：长期 500 / 短期 200 / 主题 120×12；
// - 实际字符超限时**先压主题总结**（逐段截到 120 → 从最旧的一段开始丢），
//   再减少历史条数（从最旧的一条开始丢）；
// - 最新一条历史一定保留：一句话都不发的一轮请求没有意义（预算为 0 时才不留）。
// - 状态段会被段预算截断（时间行在最前，截尾不会丢「现在几点」）；**引导段不截**：
//   它是这一轮的行为指令，截一半比没有更糟。
//
// 为什么单独一个装配器：`PromptBuilder` 负责「每一段怎么写」，这里负责
// 「按什么顺序拼、每段还能占多少字」。两者分开之后，预算与裁剪能被穷举测试，
// 不需要构造一个人格、一个库、一条网络。
// ---------------------------------------------------------------------------

public struct ContextV2Input {
    /// 第 1 段：当前状态（时间 / 活动 / 在场 / 心情 / 心情分寸 / 心里那句话）。
    public var currentState: String
    /// 第 2 段：引导（`# 引导`，g12 / g17；空串 = 这一轮不加）。
    public var guidance: String
    /// 第 3 段：人设（身份 / 世界 / 话风 / 关系）。
    public var persona: String
    /// 第 4 段：主动唤醒能力 + 工具清单。
    public var tools: String
    /// 第 5 段：反 AI 味（core 五条）。
    public var antiAI: String
    /// 第 6 段：长期记忆摘要（≤500 字；空串 = 还没有）。
    public var longTermMemory: String
    /// 第 6 段：短期记忆摘要（≤200 字；空串 = 还没有）。
    public var shortTermMemory: String
    /// 第 6 段：对话主题总结（按时间从旧到新）。
    public var topics: [MemoryContract.TopicSegment]
    /// 第 7 段：「## 语言」小节的完整文本（空串 = 不加）。
    public var languageDirective: String
    /// 第 8 段：边界（`# 边界`）。
    public var boundary: String
    /// 第 9 段：最近聊天原文（从旧到新，已带时间戳）。
    public var history: [WireMessage]
    /// 模型上下文窗口（tokens）。
    public var contextWindowTokens: Int
    /// 单次最大输出（tokens）。
    public var maxOutputTokens: Int
    /// 产品侧收紧后的历史字符预算上限。
    public var practicalChars: Int
    /// 人格语言（0.15）：只影响**段标题**（中文人格看到 `# 你的备忘录`，英文人格看到
    /// `# Your notebook`）；正文内容由调用方按人格语言渲染好传进来。
    public var language: PromptLanguage

    public init(
        currentState: String = "",
        guidance: String = "",
        persona: String = "",
        tools: String = "",
        antiAI: String = "",
        longTermMemory: String = "",
        shortTermMemory: String = "",
        topics: [MemoryContract.TopicSegment] = [],
        languageDirective: String = "",
        boundary: String = "",
        history: [WireMessage] = [],
        contextWindowTokens: Int = ExperienceContract.DEFAULT_CONTEXT_WINDOW_TOKENS,
        maxOutputTokens: Int = ExperienceContract.DEFAULT_MAX_OUTPUT_TOKENS,
        practicalChars: Int = ExperienceContract.PRACTICAL_HISTORY_CHARS,
        language: PromptLanguage = .zh
    ) {
        self.currentState = currentState
        self.guidance = guidance
        self.persona = persona
        self.tools = tools
        self.antiAI = antiAI
        self.longTermMemory = longTermMemory
        self.shortTermMemory = shortTermMemory
        self.topics = topics
        self.languageDirective = languageDirective
        self.boundary = boundary
        self.history = history
        self.contextWindowTokens = contextWindowTokens
        self.maxOutputTokens = maxOutputTokens
        self.practicalChars = practicalChars
        self.language = language
    }

    /// 从 `PromptBuilder.sections(_:)` 的结果直接装：调用方只负责「把字段填进
    /// `SystemPromptInput`」，段怎么排由 `PromptSection.stateFirstOrder` 决定，
    /// 记忆与主题由本装配器按预算插进去。
    ///
    /// 注意：字典里的 `.memory` 键会被**忽略**——那一段必须由本装配器按
    /// 长期/短期/主题的预算重新拼（否则「完整 prompt」那条路上的摘要会绕过预算检查）。
    public init(
        sections: [PromptSection: String],
        longTermMemory: String = "",
        shortTermMemory: String = "",
        topics: [MemoryContract.TopicSegment] = [],
        history: [WireMessage] = [],
        contextWindowTokens: Int = ExperienceContract.DEFAULT_CONTEXT_WINDOW_TOKENS,
        maxOutputTokens: Int = ExperienceContract.DEFAULT_MAX_OUTPUT_TOKENS,
        practicalChars: Int = ExperienceContract.PRACTICAL_HISTORY_CHARS,
        language: PromptLanguage = .zh
    ) {
        self.init(
            currentState: sections[.state] ?? "",
            guidance: sections[.guidance] ?? "",
            persona: sections[.persona] ?? "",
            tools: sections[.tools] ?? "",
            antiAI: sections[.antiAI] ?? "",
            longTermMemory: longTermMemory,
            shortTermMemory: shortTermMemory,
            topics: topics,
            languageDirective: sections[.language] ?? "",
            boundary: sections[.boundary] ?? "",
            history: history,
            contextWindowTokens: contextWindowTokens,
            maxOutputTokens: maxOutputTokens,
            practicalChars: practicalChars,
            language: language
        )
    }
}

/// 装配结果：要发出去的 system + 裁剪过的历史 + 每一段实际占了多少字。
public struct ContextV2Output: Equatable, Sendable {
    public var system: String
    public var history: [WireMessage]
    public var plan: ExperienceContract.ContextPlan
    /// 各固定段实际字符数（用于诊断与测试；不含历史）。
    public var segmentChars: [ExperienceContract.ContextSegment: Int]
    /// 实际给历史用了多少字（= system 之外的正文预算）。
    public var historyCharBudget: Int
    /// 丢掉了几段主题总结（预算不够时从最旧的开始丢）。
    public var droppedTopics: Int
    /// 主题总结是否被逐段截短过。
    public var topicsTruncated: Bool
    /// 丢掉了多少条历史（预算不够时从最旧的开始丢）。
    public var droppedHistoryMessages: Int
    /// 固定段合计是否超出了预算（超出时历史会被压到 0 或仅剩最新一条）。
    public var fixedSegmentsOverBudget: Bool

    public init(
        system: String,
        history: [WireMessage],
        plan: ExperienceContract.ContextPlan,
        segmentChars: [ExperienceContract.ContextSegment: Int],
        historyCharBudget: Int,
        droppedTopics: Int,
        topicsTruncated: Bool,
        droppedHistoryMessages: Int,
        fixedSegmentsOverBudget: Bool
    ) {
        self.system = system
        self.history = history
        self.plan = plan
        self.segmentChars = segmentChars
        self.historyCharBudget = historyCharBudget
        self.droppedTopics = droppedTopics
        self.topicsTruncated = topicsTruncated
        self.droppedHistoryMessages = droppedHistoryMessages
        self.fixedSegmentsOverBudget = fixedSegmentsOverBudget
    }
}

public enum ContextAssemblerV2 {

    /// 段与段之间的分隔：与 `PromptBuilder.sectionSeparator` 同一形状（一条横线）。
    public static let sectionSeparator = "\n\n---\n"

    /// 段标题：中文逐字复用共享契约（`MemoryContract.Injection` / `PromptContract`）；
    /// 英文用 `PromptTemplates` 的 `# Your notebook` + 三行英文小标题。
    public struct Titles: Equatable, Sendable {
        public var memo: String
        public var longTerm: String
        public var shortTerm: String
        public var topic: String

        public static func of(_ language: PromptLanguage) -> Titles {
            if language == .zh {
                return Titles(
                    memo: MemoryContract.Injection.TITLE,
                    longTerm: MemoryContract.Injection.LONG_TERM_TITLE,
                    shortTerm: MemoryContract.Injection.SHORT_TERM_TITLE,
                    topic: MemoryContract.Injection.TOPIC_TITLE
                )
            }
            return Titles(
                memo: PromptTemplates.title(section: .memo, language: language),
                longTerm: "## Long-term memory",
                shortTerm: "## Short-term memory",
                topic: "## Past topics"
            )
        }
    }

    /// 把固定段按 `PromptSection.stateFirstOrder` 拼成 system，并按预算裁历史。
    public static func assemble(_ input: ContextV2Input) -> ContextV2Output {
        let contract = ExperienceContract.shared
        let plan = contract.practicalPlan(
            contextWindowTokens: input.contextWindowTokens,
            maxOutputTokens: input.maxOutputTokens,
            practicalChars: input.practicalChars
        )

        var segmentChars: [ExperienceContract.ContextSegment: Int] = [:]
        var sections: [PromptSection: String] = [:]

        // 1. 当前状态：受段预算约束，超了就截（时间行永远在最前，截尾不会丢「现在几点」）。
        let stateLimit = max(plan.charLimit(for: .currentState), 0)
        let stateText = clipState(input.currentState, limit: stateLimit)
        sections[.state] = stateText

        // 2. 引导：**不裁**（这一轮的行为指令，截一半比没有更糟）。
        let guidanceText = input.guidance.trimmingCharacters(in: .whitespacesAndNewlines)
        sections[.guidance] = guidanceText
        segmentChars[.currentState] = stateText.count + guidanceText.count

        // 3/4/5/7/8. 人设、工具、反 AI、语言、边界：**不可裁**
        //（人设不完整比历史少几条更糟；规则段截断会变成半句话）。
        let personaText = input.persona.trimmingCharacters(in: .whitespacesAndNewlines)
        let toolsText = input.tools.trimmingCharacters(in: .whitespacesAndNewlines)
        let antiAIText = input.antiAI.trimmingCharacters(in: .whitespacesAndNewlines)
        let languageText = input.languageDirective.trimmingCharacters(in: .whitespacesAndNewlines)
        let boundaryText = input.boundary.trimmingCharacters(in: .whitespacesAndNewlines)
        sections[.persona] = personaText
        sections[.tools] = toolsText
        sections[.antiAI] = antiAIText
        sections[.language] = languageText
        sections[.boundary] = boundaryText
        segmentChars[.fullPersonaAndTools] = personaText.count + toolsText.count
            + antiAIText.count + languageText.count + boundaryText.count

        let titles = Titles.of(input.language)

        // 6. 记忆 / 备忘录：长期（硬顶 500）→ 短期（硬顶 200）→ 对话主题。
        var memoBlocks: [String] = []
        let longLimit = min(plan.charLimit(for: .longTermMemory),
                            MemoryContract.Summary.LONG_TERM_MAX_CHARS)
        let longText = MemoryContract.Injection.clipParagraph(
            input.longTermMemory, max: max(longLimit, 0)
        )
        segmentChars[.longTermMemory] = longText.count
        if !longText.isEmpty {
            memoBlocks.append(titles.memo + "\n" + titles.longTerm + "\n- " + longText)
        }

        let shortLimit = min(plan.charLimit(for: .shortTermMemory),
                             MemoryContract.Summary.SHORT_TERM_MAX_CHARS)
        let shortText = MemoryContract.Injection.clipParagraph(
            input.shortTermMemory, max: max(shortLimit, 0)
        )
        segmentChars[.shortTermMemory] = shortText.count
        if !shortText.isEmpty {
            // 长期记忆已经写过 `# 你的备忘录` 时不再重复总标题。
            if longText.isEmpty {
                memoBlocks.append(titles.memo + "\n" + titles.shortTerm + "\n- " + shortText)
            } else {
                memoBlocks.append(titles.shortTerm + "\n- " + shortText)
            }
        }

        // 对话主题总结：**先压这里**（逐段截 120 → 从最旧的丢）。
        let topicBudget = max(plan.charLimit(for: .topicSummary), 0)
        let topics = compressTopics(
            input.topics,
            budget: topicBudget,
            maxSegments: MemoryContract.Summary.TOPIC_MAX_SEGMENTS,
            segmentChars: MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS
        )
        var topicLinesChars = 0
        if !topics.segments.isEmpty {
            let block = titles.topic + "\n" + topics.segments
                .compactMap { MemoryContract.Injection.topicLine($0) }
                .joined(separator: "\n")
            topicLinesChars = block.count
            memoBlocks.append(block)
        }
        segmentChars[.topicSummary] = topicLinesChars
        sections[.memory] = memoBlocks.joined(separator: "\n\n")

        // 按**唯一的顺序定义**拼（顺序不在这里写第二遍）。
        let blocks = PromptSection.stateFirstOrder
            .compactMap { section -> String? in
                let text = sections[section]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                return text.isEmpty ? nil : text
            }

        // 固定段合计 vs 预算：只有「固定段自己就超了」才需要动历史。
        let fixedChars = blocks.reduce(0) { $0 + $1.count } + sectionSeparator.count * max(blocks.count - 1, 0)
        let plannedFixed = plan.segments
            .filter { $0.segment != .remainingHistory }
            .reduce(0) { $0 + $1.charLimit }
        let overflow = max(fixedChars - plannedFixed, 0)
        let historyBudget = max(plan.charLimit(for: .remainingHistory) - overflow, 0)

        // 9. 历史：从新到旧保留；至少留最新一条（预算 > 0 时）。
        let system = blocks.joined(separator: sectionSeparator)
        let trimmed = trimHistory(input.history, budget: historyBudget)
        if historyBudget > 0, trimmed.isEmpty, let last = input.history.last {
            // 最新一句就算超预算也发（否则这轮请求没有可回应的内容）。
            let fallback = [last]
            segmentChars[.remainingHistory] = last.content.count
            return ContextV2Output(
                system: system,
                history: fallback,
                plan: plan,
                segmentChars: segmentChars,
                historyCharBudget: historyBudget,
                droppedTopics: topics.dropped,
                topicsTruncated: topics.truncated,
                droppedHistoryMessages: max(input.history.count - 1, 0),
                fixedSegmentsOverBudget: overflow > 0
            )
        }
        segmentChars[.remainingHistory] = trimmed.reduce(0) { $0 + $1.content.count }
        return ContextV2Output(
            system: system,
            history: trimmed,
            plan: plan,
            segmentChars: segmentChars,
            historyCharBudget: historyBudget,
            droppedTopics: topics.dropped,
            topicsTruncated: topics.truncated,
            droppedHistoryMessages: max(input.history.count - trimmed.count, 0),
            fixedSegmentsOverBudget: overflow > 0
        )
    }

    // MARK: - 历史裁剪

    /// 从**最新**往前留；最新一条无论多长都留（预算为 0 时一条都不留）。
    public static func trimHistory(_ history: [WireMessage], budget: Int) -> [WireMessage] {
        guard budget > 0 else { return [] }
        if history.isEmpty { return history }
        var kept: [WireMessage] = []
        var chars = 0
        for message in history.reversed() {
            if !kept.isEmpty && chars + message.content.count > budget { break }
            kept.insert(message, at: 0)
            chars += message.content.count
        }
        return kept
    }

    // MARK: - 主题压缩

    struct CompressedTopics {
        var segments: [MemoryContract.TopicSegment]
        var dropped: Int
        var truncated: Bool
    }

    /// 主题总结的两级压缩：
    /// 1. 每段超 120 字 → 截断（补「…」）；
    /// 2. 合计超过段预算 → 从**最旧**的一段开始丢（最近的对话主题最有用）。
    static func compressTopics(
        _ topics: [MemoryContract.TopicSegment],
        budget: Int,
        maxSegments: Int,
        segmentChars: Int
    ) -> CompressedTopics {
        let usable = topics.filter {
            !MemoryContract.Injection.flatten($0.summary).isEmpty
        }
        guard budget > 0 else {
            return CompressedTopics(segments: [], dropped: usable.count, truncated: false)
        }
        var truncated = false
        let clipped: [MemoryContract.TopicSegment] = usable.map { topic in
            let summary = MemoryContract.Injection.flatten(topic.summary)
            if summary.count > segmentChars {
                truncated = true
                return MemoryContract.TopicSegment(
                    timeLabel: topic.timeLabel,
                    summary: String(summary.prefix(segmentChars)) + "…"
                )
            }
            return MemoryContract.TopicSegment(
                timeLabel: topic.timeLabel,
                summary: summary
            )
        }
        // 最多 12 段：超出时同样从最旧的丢。
        var dropped = max(clipped.count - maxSegments, 0)
        var kept = dropped > 0 ? Array(clipped.suffix(maxSegments)) : clipped
        // 段预算：`## 对话主题` 标题 + 每行的「- 时间段：」都要算进去，留一点余量。
        let headerChars = MemoryContract.Injection.TOPIC_TITLE.count + 1
        while !kept.isEmpty {
            let block = headerChars + kept
                .compactMap { MemoryContract.Injection.topicLine($0) }
                .reduce(0) { $0 + $1.count + 1 }
            if block <= budget { break }
            kept.removeFirst()
            dropped += 1
        }
        return CompressedTopics(segments: kept, dropped: dropped, truncated: truncated)
    }

    // MARK: - 小工具

    /// 状态段截断：保留前 `limit` 字（时间行在最前），补「…」。
    static func clipState(_ raw: String, limit: Int) -> String {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return "" }
        guard limit > 0 else { return "" }
        if text.count <= limit { return text }
        return String(text.prefix(max(limit - 1, 0))).trimmingCharacters(in: .whitespaces) + "…"
    }
}
