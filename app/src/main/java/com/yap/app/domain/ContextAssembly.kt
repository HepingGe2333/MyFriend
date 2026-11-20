package com.yap.app.domain

import com.yap.app.domain.ExperienceContract.ContextSegment

/**
 * 0.15 上下文装配 v2（`docs/design/context-memory-v2.md`）。
 *
 * 每轮固定六段、顺序固定：
 *
 * ```
 * 1. 完整设定 + 工具      （身份/世界/关系/话风/边界 + 「你能做什么」）
 * 2. 长期记忆             （≤500 字）
 * 3. 短期记忆             （≤200 字）
 * 4. 当前状态             （时间/活动/睡眠/心情）
 * 5. 对话主题总结         （≤12 段 × 120 字）
 * 6. 剩余预算 → 最近聊天原文
 * ```
 *
 * 这里只做「拼装 + 预算 + 超限压缩」，不读库、不读时钟、不拼具体人设：
 * 各段正文由调用方（`ChatRepository`）准备好传进来，所以顺序、标题、预算可以逐条断言。
 *
 * 预算来自共享契约 [ExperienceContract.practicalPlan]（28,000 字符产品口径，
 * 1 token ≈ 2 字符）；六段的字符上限也来自同一份规划，标题逐字复用
 * [MemoryContract.Injection] 与 [PromptContract.Section]，不在本文件另写一份中文。
 *
 * **超限时的压缩顺序**（设计文档「预算是硬约束」）：
 * 1. 先压主题总结：从最旧的一段开始丢，仍然不够就把每段再截短；
 * 2. 再减少历史：历史预算降到 0；
 * 3. 仍然超出（人设本身超过预算）时不再丢人设/记忆——它们是「他是谁」，
 *    宁可这一轮少带历史，也不能让模型忘记自己是谁；超出量记录在 [Result.overflowChars] 里供诊断。
 */
object ContextAssembly {

    /** 备忘录三段（按设计文档的来源划分，不是按注入顺序）。 */
    data class Memo(
        val longTerm: String = "",
        val shortTerm: String = "",
        val topics: List<MemoryContract.TopicSegment> = emptyList()
    )

    /**
     * 装配输入。
     *
     * @param personaAndTools 完整设定 + 工具（已含边界/语言指令等固定段）
     * @param memo 长/短期缓存与主题总结
     * @param currentState 当前状态段正文（不含标题）
     * @param extras 六段之外的附加块（心理活动、当前媒体、拉黑提示、表情清单…）；
     *        它们放在主题总结之后、历史之前，同样吃预算
     */
    data class Inputs(
        val personaAndTools: String = "",
        val memo: Memo = Memo(),
        val currentState: String = "",
        val extras: List<String> = emptyList(),
        val contextWindowTokens: Int = ExperienceContract.DEFAULT_CONTEXT_WINDOW_TOKENS,
        val maxOutputTokens: Int = ExperienceContract.DEFAULT_MAX_OUTPUT_TOKENS,
        val practicalChars: Int = ExperienceContract.PRACTICAL_HISTORY_CHARS
    )

    /** 装配结论：渲染好的 system 正文 + 这一轮留给历史的字符预算。 */
    data class Result(
        val blocks: List<String>,
        val plan: ExperienceContract.ContextPlan,
        val historyCharLimit: Int,
        val topicSegments: List<MemoryContract.TopicSegment>,
        val droppedTopicSegments: Int,
        val compressedTopics: Boolean,
        val overflowChars: Int
    ) {
        /** 真正发给模型的 system 文本（空段已被丢掉）。 */
        val system: String
            get() = blocks.filter { it.isNotBlank() }.joinToString("\n\n").trim()
    }

    /** 六段的键（按注入顺序）；测试与日志按它对齐。 */
    val SEGMENT_ORDER: List<ContextSegment> = ContextSegment.entries.toList()

    /**
     * 装配一次。
     *
     * @param inputs 各段正文
     * @param topicMaxSegments 主题总结最多保留几段（默认 [MemoryContract.Summary.TOPIC_MAX_SEGMENTS]；
     *        测试与「只剩很少预算」的调用方可以传更小）
     */
    fun assemble(
        inputs: Inputs,
        topicMaxSegments: Int = MemoryContract.Summary.TOPIC_MAX_SEGMENTS
    ): Result {
        val plan = ExperienceContract.practicalPlan(
            contextWindowTokens = inputs.contextWindowTokens,
            maxOutputTokens = inputs.maxOutputTokens,
            practicalChars = inputs.practicalChars
        )
        val budget = plan.historyCharBudget.coerceAtLeast(0)

        val longCap = minOf(
            plan.charLimitFor(ContextSegment.LONG_TERM_MEMORY),
            MemoryContract.Summary.LONG_TERM_MAX_CHARS
        ).coerceAtLeast(0)
        val shortCap = minOf(
            plan.charLimitFor(ContextSegment.SHORT_TERM_MEMORY),
            MemoryContract.Summary.SHORT_TERM_MAX_CHARS
        ).coerceAtLeast(0)
        val topicCap = minOf(
            plan.charLimitFor(ContextSegment.TOPIC_SUMMARY),
            MemoryContract.Summary.TOPIC_MAX_CHARS
        ).coerceAtLeast(0)
        val stateCap = plan.charLimitFor(ContextSegment.CURRENT_STATE).coerceAtLeast(0)
        val extrasCap = plan.charLimitFor(ContextSegment.REMAINING_HISTORY)
            .coerceAtLeast(0)
            .coerceAtMost(MAX_EXTRAS_CHARS)

        val persona = inputs.personaAndTools.trim()
        val longText = clip(inputs.memo.longTerm, longCap)
        val shortText = clip(inputs.memo.shortTerm, shortCap)
        val stateText = clip(inputs.currentState, stateCap)
        val extras = clipExtras(inputs.extras, extrasCap)

        // 主题总结：先按段数/段长规范收一次，再按预算压。
        val sanitized = inputs.memo.topics
            .mapNotNull { topic ->
                val summary = flatten(topic.summary)
                if (summary.isEmpty()) return@mapNotNull null
                val clipped = if (summary.length <= MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS) {
                    summary
                } else {
                    summary.take(MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS).trimEnd() + "…"
                }
                MemoryContract.TopicSegment(timeLabel = flatten(topic.timeLabel), summary = clipped)
            }
            .take(topicMaxSegments.coerceIn(0, MemoryContract.Summary.TOPIC_MAX_SEGMENTS))
        var topics = sanitized
        var dropped = 0
        var compressed = false
        var fixed = fixedLength(persona, longText, shortText, stateText, topics, extras)

        // 1) 先压主题总结：从最旧的一段开始丢。
        while (fixed > budget && topics.isNotEmpty()) {
            topics = topics.drop(1)
            dropped++
            compressed = true
            fixed = fixedLength(persona, longText, shortText, stateText, topics, extras)
        }
        // 1b) 还是超：把剩下的主题段按可用空间再截短（至少留 1 段，避免主题整段消失）。
        if (fixed > budget && topics.isNotEmpty()) {
            val topicBudget = (topicCap - (fixed - budget)).coerceAtLeast(MIN_TOPIC_CHARS)
            var remaining = topicBudget
            val shortened = ArrayList<MemoryContract.TopicSegment>()
            topics.forEach { topic ->
                if (remaining <= 0) {
                    dropped++
                    return@forEach
                }
                val take = minOf(topic.summary.length, remaining, MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS)
                if (take <= 0) return@forEach
                shortened += topic.copy(
                    summary = if (take >= topic.summary.length) topic.summary else topic.summary.take(take).trimEnd() + "…"
                )
                remaining -= take
            }
            topics = shortened
            compressed = true
            fixed = fixedLength(persona, longText, shortText, stateText, topics, extras)
        }
        // 2) 再减历史：附加块让位（它们不是「他是谁」）。
        //    从最后一个附加块开始丢：越靠后越像「这一轮的附注」，最不影响身份。
        var finalExtras = extras
        while (fixed > budget && finalExtras.isNotEmpty()) {
            finalExtras = finalExtras.dropLast(1)
            fixed = fixedLength(persona, longText, shortText, stateText, topics, finalExtras)
        }

        val historyCharLimit = (budget - fixed).coerceAtLeast(0)
        val blocks = buildList {
            if (persona.isNotBlank()) add(persona)
            val memoTitle = if (longText.isNotBlank() || shortText.isNotBlank() || topics.isNotEmpty()) {
                MemoryContract.Injection.TITLE
            } else {
                ""
            }
            if (longText.isNotBlank()) {
                add(
                    buildString {
                        if (memoTitle.isNotEmpty()) appendLine(memoTitle)
                        appendLine(MemoryContract.Injection.LONG_TERM_TITLE)
                        append("- $longText")
                    }.trimEnd()
                )
            }
            if (shortText.isNotBlank()) {
                add(
                    buildString {
                        // 长期存在时标题已在上一段；长期为空时短期承担标题。
                        if (longText.isBlank() && memoTitle.isNotEmpty()) appendLine(memoTitle)
                        appendLine(MemoryContract.Injection.SHORT_TERM_TITLE)
                        append("- $shortText")
                    }.trimEnd()
                )
            }
            if (stateText.isNotBlank()) {
                add(PromptContract.Section.STATE + "\n" + stateText)
            }
            if (topics.isNotEmpty()) {
                add(
                    buildString {
                        if (longText.isBlank() && shortText.isBlank() && memoTitle.isNotEmpty()) {
                            appendLine(memoTitle)
                        }
                        appendLine(MemoryContract.Injection.TOPIC_TITLE)
                        topics.forEach { topic ->
                            MemoryContract.Injection.topicLine(topic)?.let { appendLine(it) }
                        }
                    }.trimEnd()
                )
            }
            addAll(finalExtras.filter { it.isNotBlank() })
        }
        return Result(
            blocks = blocks,
            plan = plan,
            historyCharLimit = historyCharLimit,
            topicSegments = topics,
            droppedTopicSegments = dropped,
            compressedTopics = compressed,
            overflowChars = (fixed - budget).coerceAtLeast(0)
        )
    }

    /**
     * 从最近的消息往前，按字符预算挑一批原文（顺序保持不变）。
     *
     * 「至少留一条」是有意的：预算再紧也要把这一轮刚收到的那句话带给模型，
     * 否则他会对着空气回话。单条超预算时截到预算内（并补「…」）。
     */
    fun <T> trimHistory(
        history: List<T>,
        charLimit: Int,
        charsOf: (T) -> Int
    ): List<T> {
        if (history.isEmpty()) return history
        val limit = charLimit.coerceAtLeast(0)
        val picked = ArrayList<T>()
        var used = 0
        for (item in history.asReversed()) {
            val size = charsOf(item).coerceAtLeast(0)
            if (picked.isNotEmpty() && used + size > limit) break
            picked.add(item)
            used += size
        }
        return picked.asReversed()
    }

    private const val MAX_EXTRAS_CHARS = 4_000

    /** 主题段在极端压缩下的最小可读长度：再短就只剩一个词，不如整段丢掉。 */
    private const val MIN_TOPIC_CHARS = 60

    private fun fixedLength(
        persona: String,
        longText: String,
        shortText: String,
        stateText: String,
        topics: List<MemoryContract.TopicSegment>,
        extras: List<String>
    ): Int {
        var total = persona.length
        if (longText.isNotBlank()) total += MemoryContract.Injection.LONG_TERM_TITLE.length + longText.length + 4
        if (shortText.isNotBlank()) total += MemoryContract.Injection.SHORT_TERM_TITLE.length + shortText.length + 4
        if (stateText.isNotBlank()) total += PromptContract.Section.STATE.length + stateText.length + 2
        if (topics.isNotEmpty()) {
            total += MemoryContract.Injection.TOPIC_TITLE.length + 2
            topics.forEach { topic ->
                total += (MemoryContract.Injection.topicLine(topic)?.length ?: 0) + 1
            }
        }
        extras.forEach { total += it.length + 2 }
        if (longText.isNotBlank() || shortText.isNotBlank() || topics.isNotEmpty()) {
            total += MemoryContract.Injection.TITLE.length + 1
        }
        return total
    }

    private fun clipExtras(extras: List<String>, maxChars: Int): List<String> {
        if (maxChars <= 0) return emptyList()
        val result = ArrayList<String>()
        var used = 0
        for (raw in extras) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            val left = maxChars - used
            if (left <= 0) break
            val clipped = if (text.length <= left) text else text.take(left).trimEnd()
            if (clipped.isNotBlank()) {
                result += clipped
                used += clipped.length + 2
            }
        }
        return result
    }

    private fun clip(raw: String?, maxChars: Int): String {
        val text = flatten(raw)
        if (text.isEmpty() || maxChars <= 0) return ""
        return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "…"
    }

    private fun flatten(raw: String?): String =
        raw.orEmpty().replace(Regex("\\s+"), " ").trim()
}
