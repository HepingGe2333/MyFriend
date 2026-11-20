package com.yap.agentd.experience

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import com.yap.app.domain.MemoryContract
import java.time.Clock
import java.time.LocalDate

/**
 * `memorize(query)`：在**这段会话的历史原文**里检索「很久以前聊过什么」
 * （`docs/design/context-memory-v2.md`「过去的细节通过主题总结检索 + memorize 工具取回」）。
 *
 * 与 `recall` 的区别（工具描述里也写了，否则模型会混）：
 * - `recall` 翻的是**长期记忆库**（`memories.json`，模型自己挑着记下的那几句话）；
 * - `memorize` 翻的是**聊天历史本身**：对方说过的原话、当时的话题与时间。
 *
 * 三条打分规则（顺序有意义，全部可单测）：
 * 1. **关键词**：整句命中 5 分、每个词元命中 2 分（词元按空白/标点拆，中文再加二元组）；
 * 2. **主题段**：命中的主题总结单独成为一条「那段时间聊了什么」，并按它的命中给同段原句加分；
 * 3. **时间衰减**：`0.55 + 0.45 × 1/(1 + 天数/7)`——昨天的事比三个月前的更容易想起来，
 *    但久远但高度相关的仍然能浮上来（衰减只乘在关键词分上，不会把强命中压没）。
 *
 * 输出形状固定为 `<回忆：时间段 —— 摘要；你原话：「…」>`（设计文档的注入示例），
 * 由 `ToolExecutor` 作为工具结果回灌给模型；模型可以顺着这句话回答，而不是编。
 */
class SessionRecall(
    private val store: FileStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** 一次最多往前翻多少条消息（历史可能十万条，检索必须有一个成本上限）。 */
    private val maxScanMessages: Int = MAX_SCAN_MESSAGES
) {

    /** 一条「想起来的事」。 */
    data class Hit(
        val kind: Kind,
        /** `2026-11-18 晚`（可能为空：很久以前的数据没有 createdAt）。 */
        val timeLabel: String,
        /** 主题总结（主题命中时是它本身；原句命中时是它所在时段的主题）。 */
        val topicSummary: String,
        /** 关键原句（对方/他当时说的那一句，截断到 [MAX_QUOTE_CHARS]）。 */
        val quote: String?,
        val score: Double,
        val messageId: String?
    ) {
        enum class Kind { TOPIC, MESSAGE }

        /** 渲染成 `- <回忆：…>` 一行；没有内容返回 null。 */
        fun line(): String? {
            val summary = topicSummary.replace(Regex("\\s+"), " ").trim()
            val text = quote?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (summary.isEmpty() && text.isEmpty()) return null
            val label = timeLabel.trim()
            val body = buildString {
                if (label.isNotEmpty()) append(label).append(" —— ")
                if (summary.isNotEmpty()) append(summary)
                if (text.isNotEmpty()) {
                    if (summary.isNotEmpty()) append("；")
                    append("你原话：「").append(text).append("」")
                }
            }
            return "- <回忆：$body>"
        }
    }

    /** 一次检索的结果（[text] 是要回灌给模型的中文）。 */
    data class Result(
        val query: String,
        val hits: List<Hit>,
        val text: String,
        val scanned: Int
    )

    /** 在 [sessionId] 的历史里找和 [query] 有关的事。**不抛异常**（坏数据只让结果变少）。 */
    fun recall(sessionId: String, query: String, limit: Int = DEFAULT_LIMIT, now: Long = clock.millis()): Result {
        val cleaned = query.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return Result(cleaned, emptyList(), NOTHING, 0)
        val scan = scanMessages(sessionId)
        val topics = runCatching { store.sessionMemory(sessionId)?.topics.orEmpty() }.getOrDefault(emptyList())
        val tokens = tokens(cleaned)
        val zone = clock.zone
        val labelOf: (Long) -> String = { at -> TopicSummaryRules.timeLabel(at, zone) }

        val hits = ArrayList<Hit>()

        // ---- 主题段：先按主题找到「那段时间聊了什么」
        val topicHits = topics.mapNotNull { topic ->
            val score = matchScore(topic.summary, tokens, cleaned)
            if (score <= 0) return@mapNotNull null
            val quote = scan
                .filter { labelOf(it.createdAt) == topic.timeLabel }
                .maxByOrNull { matchScore(it.content, tokens, cleaned) }
                ?.takeIf { matchScore(it.content, tokens, cleaned) > 0 }
            Hit(
                kind = Hit.Kind.TOPIC,
                timeLabel = topic.timeLabel,
                topicSummary = topic.summary,
                quote = quote?.content?.take(MAX_QUOTE_CHARS),
                score = score * TOPIC_WEIGHT * topicDecay(topic.timeLabel, now, clock.zone),
                messageId = quote?.id
            )
        }
        hits += topicHits

        // ---- 原句：按关键词 + 时间衰减排序
        val topicByLabel = topics.associateBy { it.timeLabel }
        for (message in scan) {
            if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) continue
            val score = matchScore(message.content, tokens, cleaned)
            if (score <= 0) continue
            val label = labelOf(message.createdAt)
            val topic = topicByLabel[label]
            val topicScore = topic?.let { matchScore(it.summary, tokens, cleaned) * TOPIC_MESSAGE_BONUS } ?: 0.0
            val recency = decayOf(ageDays(message.createdAt, now))
            hits += Hit(
                kind = Hit.Kind.MESSAGE,
                timeLabel = label,
                topicSummary = topic?.summary.orEmpty(),
                quote = message.content.take(MAX_QUOTE_CHARS),
                score = (score + topicScore) * recency,
                messageId = message.id
            )
        }

        // ---- 去重：同一条原句只留最高分；同主题的若干原句最多留 1 条
        val best = LinkedHashMap<String, Hit>()
        for (hit in hits.sortedByDescending { it.score }) {
            val key = when {
                hit.kind == Hit.Kind.TOPIC -> "topic:${hit.timeLabel}:${hit.topicSummary}"
                hit.messageId != null -> "message:${hit.messageId}"
                else -> "quote:${hit.quote}"
            }
            if (best.containsKey(key)) continue
            if (hit.kind == Hit.Kind.MESSAGE && best.values.any {
                    it.kind == Hit.Kind.TOPIC && it.timeLabel == hit.timeLabel && it.messageId == hit.messageId
                }
            ) {
                continue
            }
            best[key] = hit
        }
        val top = best.values
            .sortedByDescending { it.score }
            .take(limit.coerceIn(1, MAX_LIMIT))
            .sortedWith(compareBy({ it.timeLabel }, { -it.score }))
        val lines = top.mapNotNull { it.line() }
        val text = if (lines.isEmpty()) NOTHING else lines.joinToString("\n").take(MAX_RESULT_CHARS)
        return Result(cleaned, top, text, scan.size)
    }

    // ------------------------------------------------------------------ 内部

    /** 最近 [maxScanMessages] 条消息（升序）；读块失败时返回已读到的部分。 */
    private fun scanMessages(sessionId: String): List<Message> {
        val blocks = runCatching { store.blocks(sessionId) }.getOrDefault(emptyList())
        val collected = ArrayList<Message>()
        for (block in blocks.asReversed()) {
            val messages = runCatching { store.blockMessages(sessionId, block.index) }.getOrDefault(emptyList())
            collected.addAll(0, messages.filter { !it.blocked && it.content.isNotBlank() })
            if (collected.size >= maxScanMessages) break
        }
        return if (collected.size > maxScanMessages) {
            collected.subList(collected.size - maxScanMessages, collected.size).toList()
        } else {
            collected
        }
    }

    /** 关键词命中分：整句 5 分、每词元 2 分；主题段按同一把尺子量。 */
    private fun matchScore(text: String, tokens: List<String>, query: String): Double {
        if (text.isBlank()) return 0.0
        var score = 0.0
        if (text.contains(query, ignoreCase = true)) score += 5.0
        for (token in tokens) {
            if (token.isNotEmpty() && text.contains(token, ignoreCase = true)) score += 2.0
        }
        return score
    }

    /**
     * 查询拆词：空白/中英文标点当分隔符；中文再补二元组（「哈尔滨」能匹配「去哈尔滨」，
     * 也能匹配「哈尔滨那边冷」）。最多 [MAX_TOKENS] 个。
     */
    internal fun tokens(query: String): List<String> {
        val direct = query
            .split(Regex("[\\s,，、。;；:：!！?？/\\\\|()（）\\[\\]【】<>《》\"'“”‘’]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val keywords = TopicSummaryRules.keywords(query).toList()
        return (direct + keywords).distinct().take(MAX_TOKENS)
    }

    private fun ageDays(at: Long, now: Long): Double {
        if (at <= 0L) return 365.0
        return ((now - at).coerceAtLeast(0L) / 86_400_000.0)
    }

    /** 时间衰减：`0.55 + 0.45 / (1 + 天数/7)`（越近越接近 1，越久越接近 0.55）。 */
    private fun decayOf(days: Double): Double = 0.55 + 0.45 / (1.0 + days / 7.0)

    /**
     * 主题段的衰减：时间段形如 `2026-11-18 晚`，取前 10 位当日 0 点作为近似时刻
     * （主题本来就是「那一段时间的概括」，精确到小时没有意义）。
     */
    private fun topicDecay(label: String, now: Long, zone: java.time.ZoneId): Double {
        val datePart = label.trim().take(10)
        val at = runCatching {
            LocalDate.parse(datePart).atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrNull()
        return decayOf(ageDays(at ?: now, now))
    }

    companion object {
        /** 默认想起几条（3 条：一段主题 + 一两句原话，再多会把提示词塞满）。 */
        const val DEFAULT_LIMIT = 3

        /** 一次最多几条。 */
        const val MAX_LIMIT = 8

        /** 一次最多往前翻多少条消息。 */
        const val MAX_SCAN_MESSAGES = 600

        /** 查询最多拆几个词元。 */
        const val MAX_TOKENS = 16

        /** 关键原句截断长度。 */
        const val MAX_QUOTE_CHARS = 60

        /** 检索结果总字数上限。 */
        const val MAX_RESULT_CHARS = 900

        /** 主题命中相对原句命中的权重（主题是「那段时间的概括」，值得排在前面）。 */
        const val TOPIC_WEIGHT = 1.25

        /** 原句所在时段的主题也命中时，额外加分系数。 */
        const val TOPIC_MESSAGE_BONUS = 0.5

        /** 什么都没翻到时回灌给模型的一句（模型据此老实说「想不太起来了」）。 */
        const val NOTHING = "（翻了一遍聊天记录，没找到相关的事）"
    }
}
