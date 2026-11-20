package com.yap.app.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.ln

/**
 * `memorize` 工具：在**会话历史**里检索旧事（0.15 上下文/记忆 v2）。
 *
 * 设计文档里的用法：
 *
 * > 模型说「让我想想 / 让我回忆一下」→ 调用 `memorize(query)`；工具在会话历史里检索
 * > （关键词 + 主题段 + 时间衰减），返回「<回忆：某段时间的对话摘要 + 关键原句>」，
 * > 注入到下一轮上下文（作为工具结果），并可显示一行「（他想了想）」。
 *
 * 与 1.5 的 `recall` 工具的分工：
 * - `recall` 查的是**记忆库/浏览摘要/全库聊天**，有三档深度；
 * - `memorize` 只查**这段会话历史 + 主题总结**，回答的是「你之前是不是说过…」这种
 *   就在嘴边、想一想就能想起来的事。它不碰归档记忆，也不联网。
 *
 * 这里全是纯函数：消息、主题、时间都由调用方传进来；坏输入返回空结果，不抛异常。
 */
object Memorize {

    /** 工具名（与 [ToolCatalog] 一致；单独放常量避免两处漂）。 */
    const val TOOL_NAME = "memorize"

    /** 检索线索最多 300 字（与共享契约 [MemoryContract.Retrieval] 同源）。 */
    const val MAX_QUERY_CHARS = MemoryContract.Retrieval.MAX_QUERY_CHARS

    /** 一次回忆最多带几条原话。 */
    const val MAX_QUOTES = 4

    /** 一次回忆注入上下文的字符上限（<回忆：…> 整段，含尖括号）。 */
    const val MAX_RECALL_CHARS = 800

    /** 单条原话在回忆里最多留多少字。 */
    const val QUOTE_CHARS = 90

    /** 时间衰减的半衰期（天）：两周前的同一句话，分数只有刚说过的一半。 */
    const val HALF_LIFE_DAYS: Double = 14.0

    /** 工具动作卡片上显示的一行（用户可见，由 UI 取资源；这里是兜底文案）。 */
    const val THOUGHT_LINE = "（他想了想）"

    /** `<回忆：…>` 的固定前后缀（协议形状，改它等于改模型看到的东西）。 */
    const val RECALL_PREFIX = "<回忆："
    const val RECALL_SUFFIX = ">"

    /** 一条会话历史（纯视图，不依赖 Room 实体）。 */
    data class HistoryMessage(
        val sessionId: String = "",
        val sessionTitle: String = "",
        val fromUser: Boolean = true,
        val content: String = "",
        val at: Long = 0L
    )

    /** 一条命中：哪段时间、谁说的、原话、为什么被选中。 */
    data class Hit(
        val at: Long,
        val fromUser: Boolean,
        val text: String,
        val score: Double,
        val sessionTitle: String = "",
        val matchedTokens: List<String> = emptyList()
    )

    /** 一次回忆的结果（渲染好的文本 + 是否为空 + 命中明细）。 */
    data class Recall(
        val query: String,
        val hits: List<Hit>,
        val topics: List<MemoryContract.TopicSegment>,
        val text: String
    ) {
        val isEmpty: Boolean get() = hits.isEmpty() && topics.isEmpty()
    }

    /**
     * 规整检索线索：压成一行、截到 [MAX_QUERY_CHARS]。
     * 空线索返回空串（调用方应直接给出 [empty]）。
     */
    fun query(raw: String?): String =
        raw.orEmpty().replace(Regex("\\s+"), " ").trim().take(MAX_QUERY_CHARS)

    /**
     * 在会话历史里按「关键词 + 主题段 + 时间衰减」排序。
     *
     * 打分（越大越靠前）：
     * - 每个不同的查询词元命中 +1；整句原样出现再 +0.8；
     * - 时间衰减：`0.5 ^ (天数 / 14)`，刚说过的最重；
     * - 同一句话只保留一次（重复消息不重复占位置）。
     *
     * @param messages 会话历史（顺序不限；渲染时会按时间排序）
     * @param topics 主题总结（也参与检索：命中的主题段会优先带出来）
     * @param query 检索线索
     * @param now 当前时间
     * @param limit 最多返回几条（夹在 1..[MAX_QUOTES]）
     */
    fun rank(
        messages: List<HistoryMessage>,
        topics: List<MemoryContract.TopicSegment> = emptyList(),
        query: String,
        now: Long,
        limit: Int = MAX_QUOTES
    ): List<Hit> {
        val text = query(query)
        if (text.isEmpty()) return emptyList()
        val tokens = tokensOf(text)
        if (tokens.isEmpty()) return emptyList()
        val max = limit.coerceIn(1, MAX_QUOTES)
        val seen = HashSet<String>()
        val hits = ArrayList<Hit>()
        messages.forEach { message ->
            val content = flatten(message.content)
            if (content.isEmpty()) return@forEach
            val key = (if (message.fromUser) "u" else "a") + "|" + content
            if (!seen.add(key)) return@forEach
            val matched = tokens.filter { content.contains(it, ignoreCase = true) }
            if (matched.isEmpty()) return@forEach
            val exact = if (content.contains(text, ignoreCase = true)) 0.8 else 0.0
            val score = matched.size + exact + timeDecayDays(message.at, now)
            hits += Hit(
                at = message.at,
                fromUser = message.fromUser,
                text = content.take(QUOTE_CHARS),
                score = score,
                sessionTitle = message.sessionTitle,
                matchedTokens = matched
            )
        }
        return hits.sortedWith(
            compareByDescending<Hit> { it.score }.thenByDescending { it.at }
        ).take(max)
    }

    /**
     * 与查询相关的主题段（最多 3 段，新的在前）。
     *
     * 主题段是「已经概括过的事」，比原话更省上下文；命中的主题会写在回忆开头。
     */
    fun matchingTopics(
        topics: List<MemoryContract.TopicSegment>,
        query: String,
        limit: Int = 3
    ): List<MemoryContract.TopicSegment> {
        val tokens = tokensOf(query(query))
        if (tokens.isEmpty()) return emptyList()
        return topics
            .mapNotNull { topic ->
                val text = flatten(topic.timeLabel) + " " + flatten(topic.summary)
                val matched = tokens.count { text.contains(it, ignoreCase = true) }
                if (matched <= 0) null else topic to matched
            }
            .sortedWith(compareByDescending<Pair<MemoryContract.TopicSegment, Int>> { it.second })
            .take(limit.coerceAtLeast(1))
            .map { it.first }
    }

    /**
     * 渲染成给模型看的 `<回忆：…>`。
     *
     * 形状（设计文档）：某段时间的对话摘要 + 关键原句。长度硬上限 [MAX_RECALL_CHARS]；
     * 什么都没命中时返回 [empty]（让模型知道「真的想不起来」，而不是以为工具坏了）。
     */
    fun recall(
        messages: List<HistoryMessage>,
        topics: List<MemoryContract.TopicSegment>,
        query: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Recall {
        val text = query(query)
        if (text.isEmpty()) {
            return Recall(query = "", hits = emptyList(), topics = emptyList(), text = empty(text))
        }
        val hits = rank(messages, topics, text, now)
        val relatedTopics = matchingTopics(topics, text)
        if (hits.isEmpty() && relatedTopics.isEmpty()) {
            return Recall(query = text, hits = emptyList(), topics = emptyList(), text = empty(text))
        }
        val body = buildString {
            append(RECALL_PREFIX)
            val lead = relatedTopics.lastOrNull()
            if (lead != null) {
                val label = flatten(lead.timeLabel)
                val summary = flatten(lead.summary).take(120)
                if (summary.isNotEmpty()) {
                    append(if (label.isEmpty()) summary else "$label，$summary")
                }
            }
            if (hits.isNotEmpty()) {
                if (isNotEmpty()) append("。")
                append("你想起几句原话：")
                hits.sortedBy { it.at }.forEach { hit ->
                    val whenText = timeWord(hit.at, now, zone)
                    val who = if (hit.fromUser) "对方" else "你"
                    append("\n· ")
                    if (whenText.isNotEmpty()) append("$whenText ")
                    append("$who：「${hit.text}」")
                }
            }
            append(RECALL_SUFFIX)
        }
        return Recall(
            query = text,
            hits = hits,
            topics = relatedTopics,
            text = clipRecall(body)
        )
    }

    /** 什么都没想起来时的中文回答（模型据此自然地说「想不起来了」）。 */
    fun empty(query: String?): String {
        val text = this.query(query)
        val tail = if (text.isEmpty()) "" else "「$text」"
        return "$RECALL_PREFIX${tail}这件事你想不起来了；不要编，直接说记不清。$RECALL_SUFFIX"
    }

    /** 供 UI 显示的一行（工具卡片副标题）；与 [THOUGHT_LINE] 相同。 */
    fun thoughtLine(): String = THOUGHT_LINE

    // ------------------------------------------------------------------ 内部

    /**
     * 查询词元：中文取字二元组（单字也保留），英文取小写词（≥2 字母）。
     *
     * 为什么用二元组而不是分词：会话历史是用户随手打的，没有词典可用；
     * 二元组对「加班」「方案」这类词的召回足够，而且绝不会把一个词切错。
     */
    internal fun tokensOf(raw: String): List<String> {
        val text = raw.lowercase()
        val result = LinkedHashSet<String>()
        var cjkRun = StringBuilder()
        fun flushCjk() {
            val run = cjkRun.toString()
            cjkRun = StringBuilder()
            if (run.isEmpty()) return
            if (run.length == 1) {
                if (run[0] !in STOP_SINGLE) result += run
                return
            }
            for (index in 0 until run.length - 1) {
                val bigram = run.substring(index, index + 2)
                if (bigram[0] in STOP_SINGLE && bigram[1] in STOP_SINGLE) continue
                result += bigram
            }
        }
        text.forEach { char ->
            if (char.isCjk()) {
                cjkRun.append(char)
            } else {
                flushCjk()
            }
        }
        flushCjk()
        Regex("[a-z0-9]{2,}").findAll(text).forEach { result += it.value }
        return result.toList()
    }

    private fun Char.isCjk(): Boolean =
        this in '\u4e00'..'\u9fff' || this in '\u3400'..'\u4dbf'

    /** 虚词单字：二元组两端都是它们时没有检索价值。 */
    private val STOP_SINGLE = setOf(
        '的', '了', '是', '在', '我', '你', '他', '她', '它', '就', '也', '很', '都', '和',
        '吗', '呢', '吧', '啊', '呀', '哦', '嗯', '把', '被', '给', '对', '有', '没', '不',
        '这', '那', '会', '要', '得', '地', '着', '过', '与', '或', '但', '而', '又', '再'
    )

    /** 时间衰减：`0.5 ^ (天数 / 14)`；`at <= 0` 或时钟倒退按「很久以前」给 0。 */
    internal fun timeDecayDays(at: Long, now: Long): Double {
        if (at <= 0L || now <= at) return 0.0
        val days = (now - at).toDouble() / DAY_MS
        return exp(-ln(2.0) * days / HALF_LIFE_DAYS).coerceIn(0.0, 1.0)
    }

    /** 原话在回忆里的时间词：`昨天` / `3 天前` / `2026-11-18 晚上`。 */
    internal fun timeWord(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (at <= 0L) return ""
        if (now <= at) return "刚才"
        val days = ((now - at) / DAY_MS).toInt()
        return when {
            days <= 0 -> "今天"
            days == 1 -> "昨天"
            days <= 6 -> "$days 天前"
            else -> {
                val label = runCatching {
                    Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
                }.getOrNull()
                val period = runCatching {
                    TopicSummary.periodWord(Instant.ofEpochMilli(at).atZone(zone).hour)
                }.getOrNull().orEmpty()
                listOfNotNull(label, period.takeIf { it.isNotEmpty() }).joinToString(" ")
            }
        }
    }

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /** 截到 [MAX_RECALL_CHARS]，保证 `<回忆：…>` 的尾尖括号还在。 */
    internal fun clipRecall(text: String): String {
        if (text.length <= MAX_RECALL_CHARS) return text
        val body = text.removePrefix(RECALL_PREFIX).removeSuffix(RECALL_SUFFIX)
        val room = (MAX_RECALL_CHARS - RECALL_PREFIX.length - RECALL_SUFFIX.length - 1).coerceAtLeast(0)
        return RECALL_PREFIX + body.take(room).trimEnd() + "…" + RECALL_SUFFIX
    }

    private fun flatten(raw: String?): String =
        raw.orEmpty().replace(Regex("\\s+"), " ").trim()
}
