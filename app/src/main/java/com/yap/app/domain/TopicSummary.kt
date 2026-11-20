package com.yap.app.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 0.15 的「对话主题总结」（`docs/design/context-memory-v2.md`）。
 *
 * 与 0.14 的短期/长期摘要不同：主题总结不是「最近聊了什么」的一段流水账，
 * 而是**按主题分段**的滚动摘要——每段一个时间段 + ≤120 字，最多 12 段。
 * 它的用途是让模型在长对话里还能想起「上上周我们聊过他换工作的事」，
 * 以及给 `memorize` 工具当检索线索。
 *
 * 更新节奏（设计文档「更新节奏」）：
 * - 每 15–40 轮由模型决定要不要滚一段新的；
 * - 话题明显切换时（用户说「换个话题」「对了」之类）提前滚一段；
 * - 超过 12 段时从最旧的丢（[sanitize]）。
 *
 * 这里全是纯函数：时间由参数传入，模型输出宽松解析，坏数据不抛异常。
 */
object TopicSummary {

    /** 主题总结最多保留几段（与 [MemoryContract.Summary.TOPIC_MAX_SEGMENTS] 同源）。 */
    const val MAX_SEGMENTS: Int = MemoryContract.Summary.TOPIC_MAX_SEGMENTS

    /** 每段最多多少字（与共享契约同源）。 */
    const val SEGMENT_MAX_CHARS: Int = MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS

    /** 滚动更新的轮次区间（与长期缓存同一组数字）。 */
    const val UPDATE_MIN_ROUNDS: Int = MemoryContract.Summary.LONG_UPDATE_MIN_ROUNDS
    const val UPDATE_MAX_ROUNDS: Int = MemoryContract.Summary.LONG_UPDATE_MAX_ROUNDS

    /** 一个时间段里最多取几条原话给模型当素材（成本闸，不是产品语义）。 */
    const val SOURCE_LINES: Int = 24

    /** 给模型看的单条原话最多多少字。 */
    const val SOURCE_LINE_CHARS: Int = 120

    /**
     * 该不该滚一段新的主题总结。
     *
     * @param userCount 当前可见用户消息数
     * @param lastUpdatedCount 上次更新时的用户消息数
     * @param topicChanged 话题切换（[isTopicSwitchMarker] 命中或模型自己判断换了话题）
     */
    fun shouldUpdate(
        userCount: Int,
        lastUpdatedCount: Int,
        topicChanged: Boolean = false,
        minRounds: Int = UPDATE_MIN_ROUNDS,
        maxRounds: Int = UPDATE_MAX_ROUNDS
    ): Boolean {
        if (userCount <= 0) return false
        if (topicChanged && userCount > lastUpdatedCount.coerceAtLeast(0)) return true
        val fresh = (userCount - lastUpdatedCount.coerceAtLeast(0)).coerceAtLeast(0)
        val min = minRounds.coerceAtLeast(1)
        // 15 轮起就可以问模型（它可以选择「这一段没什么好记的」返回空数组），
        // 40 轮强制；中间每跨过 15 轮问一次，不是每条消息都问。
        return fresh >= min
    }

    /** 是否已经到了「必须更新」的程度（40 轮），调用方据此不再问模型、直接滚。 */
    fun isForced(userCount: Int, lastUpdatedCount: Int, maxRounds: Int = UPDATE_MAX_ROUNDS): Boolean =
        userCount.coerceAtLeast(0) - lastUpdatedCount.coerceAtLeast(0) >= maxRounds.coerceAtLeast(1)

    /**
     * 话题切换的轻量标记（只认明确的说法）。
     *
     * 与 [SessionMemoryPolicy.planChanged] 同一条原则：这个判断会触发一次模型调用，
     * 误判代价是钱和延迟，所以只认「对方自己说换话题」这类高置信信号。
     */
    fun isTopicSwitchMarker(text: String?): Boolean {
        val value = text.orEmpty().trim()
        if (value.isEmpty()) return false
        return SWITCH_MARKERS.any { value.contains(it) }
    }

    private val SWITCH_MARKERS = listOf(
        "换个话题", "换一个话题", "不说这个了", "说点别的", "对了，", "对了,", "话说回来",
        "刚才那事", "另一件事", "顺便说"
    )

    /** 时间段格式：`2026-11-18 晚`（日期 + 一天里的时段）。 */
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    /**
     * 时间段标签：`2026-11-18 晚`。
     *
     * 为什么不用「3 天前」：主题总结是要长期留着的，相对时间会随着时间推移变成错的；
     * 绝对日期 + 时段既稳定，又能让模型在「昨天/上周」这类指代里对上号。
     */
    fun timeLabel(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (epochMillis <= 0L) return ""
        val zoned = runCatching { Instant.ofEpochMilli(epochMillis).atZone(zone) }.getOrNull() ?: return ""
        return zoned.format(DATE) + " " + periodWord(zoned.hour)
    }

    /** 一天里的时段（给时间段标签与 `memorize` 的回忆文案共用）。 */
    fun periodWord(hourOfDay: Int): String = when (hourOfDay.coerceIn(0, 23)) {
        in 0..4 -> "凌晨"
        in 5..7 -> "早上"
        in 8..10 -> "上午"
        in 11..12 -> "中午"
        in 13..17 -> "下午"
        in 18..19 -> "傍晚"
        in 20..22 -> "晚上"
        else -> "深夜"
    }

    /**
     * 规整主题段：去空白、压成一行、每段截到 120 字、最多 12 段（超出从最旧丢）。
     *
     * @param segments 按时间从旧到新
     */
    fun sanitize(segments: List<MemoryContract.TopicSegment>): List<MemoryContract.TopicSegment> {
        val cleaned = segments.mapNotNull { segment ->
            val summary = flatten(segment.summary)
            if (summary.isEmpty()) return@mapNotNull null
            MemoryContract.TopicSegment(
                timeLabel = flatten(segment.timeLabel),
                summary = if (summary.length <= SEGMENT_MAX_CHARS) {
                    summary
                } else {
                    summary.take(SEGMENT_MAX_CHARS).trimEnd() + "…"
                }
            )
        }
        return if (cleaned.size <= MAX_SEGMENTS) cleaned else cleaned.takeLast(MAX_SEGMENTS)
    }

    /**
     * 把一段新的主题追加到已有总结上（滚动更新）。
     *
     * 如果新段的 `timeLabel` 与最后一段相同，就把内容并进最后一段（同一时段不拆两段）；
     * 否则追加成新的一段。结果统一走 [sanitize]（长度与段数上限）。
     */
    fun append(
        existing: List<MemoryContract.TopicSegment>,
        timeLabel: String,
        summary: String
    ): List<MemoryContract.TopicSegment> {
        val label = flatten(timeLabel)
        val text = flatten(summary)
        if (text.isEmpty()) return sanitize(existing)
        val list = existing.toMutableList()
        val last = list.lastOrNull()
        if (last != null && flatten(last.timeLabel) == label && label.isNotEmpty()) {
            val merged = flatten(last.summary) + " " + text
            list[list.lastIndex] = last.copy(summary = merged)
        } else {
            list += MemoryContract.TopicSegment(timeLabel = label, summary = text)
        }
        return sanitize(list)
    }

    /**
     * 宽松解析模型给的 JSON：`[{"time":"2026-11-18 晚","summary":"…"}]`。
     *
     * 也认包装形状 `{"topics":[…]}`、单对象、以及 markdown 代码块；解析不出来返回空列表
     * （调用方保留旧总结，绝不因为一次坏输出把记忆清空）。
     */
    fun parse(raw: String): List<MemoryContract.TopicSegment> {
        val text = stripFences(raw)
        if (text.isEmpty()) return emptyList()
        return runCatching { com.yap.app.core.json.Json.parse(text) }
            .getOrNull()
            .let { parseValue(it) }
            .let(::sanitize)
    }

    private fun parseValue(value: com.yap.app.core.json.JsonValue?): List<MemoryContract.TopicSegment> {
        if (value == null) return emptyList()
        return when (value) {
            is com.yap.app.core.json.JsonValue.Arr -> value.items.flatMap { parseValue(it) }
            is com.yap.app.core.json.JsonValue.Obj -> {
                val nested = value.fields["topics"] ?: value.fields["segments"]
                if (nested != null) {
                    parseValue(nested)
                } else {
                    val summary = value.string("summary") ?: value.string("content") ?: value.string("topic")
                    if (summary.isNullOrBlank()) {
                        emptyList()
                    } else {
                        listOf(
                            MemoryContract.TopicSegment(
                                // `timeLabel` 是服务端 extensions / 跨端备份 v7 的规范键；
                                // `time` 是模型输出与本地 topicsJson 的旧写法。三种都认。
                                timeLabel = value.string("timeLabel")
                                    ?: value.string("time")
                                    ?: value.string("period")
                                    ?: "",
                                summary = summary
                            )
                        )
                    }
                }
            }
            else -> emptyList()
        }
    }

    private fun com.yap.app.core.json.JsonValue.Obj.string(key: String): String? =
        (fields[key] as? com.yap.app.core.json.JsonValue.Str)?.value

    /**
     * 滚一段主题总结的 user 提示词（system 在 [systemPrompt]）。
     *
     * @param previous 已有的主题总结（从旧到新）
     * @param conversation 这一段对话的原话（`对方：…` / `你：…`）
     */
    fun userPrompt(previous: List<MemoryContract.TopicSegment>, conversation: List<String>): String = buildString {
        if (previous.isNotEmpty()) {
            appendLine("你已有的对话主题（从旧到新，最多 12 段）：")
            previous.forEach { segment ->
                val line = MemoryContract.Injection.topicLine(segment)
                if (line != null) appendLine(line)
            }
            appendLine()
        }
        appendLine("这一段新的对话（按时间顺序）：")
        conversation.map { it.trim() }.filter { it.isNotBlank() }.takeLast(SOURCE_LINES).forEach {
            appendLine("- ${it.take(SOURCE_LINE_CHARS)}")
        }
        appendLine()
        append("把这一段对话并进主题总结：如果话题与最后一段相同，就合并；不同就新起一段。只输出 JSON 数组。")
    }

    /** 滚主题总结的 system 提示词（面向模型，固定中文）。 */
    fun systemPrompt(): String = buildString {
        appendLine("你在帮「你自己」整理与对方的对话主题。按话题分段，每段一句话，第一人称。")
        appendLine("要求：")
        appendLine("- 每段只写这段对话的主题与结论（例如「他在考虑换工作，怕裸辞」），不要流水账；")
        appendLine("- 保留对以后有用的事实、约定、情绪变化；寒暄和重复丢掉；")
        appendLine("- 每段不超过 120 字，最多保留 12 段；太旧的段直接不要；")
        appendLine("- time 写成「YYYY-MM-DD 时段」（时段用凌晨/早上/上午/中午/下午/傍晚/晚上/深夜）；")
        appendLine("- 只输出 JSON 数组，例如：[{\"time\":\"2026-11-18 晚上\",\"summary\":\"…\"}]")
    }.trimEnd()

    /** 编码成落库用的 JSON（与 [parse] 对称；空列表存空数组）。 */
    fun encode(segments: List<MemoryContract.TopicSegment>): String =
        buildString {
            append('[')
            sanitize(segments).forEachIndexed { index, segment ->
                if (index > 0) append(',')
                append("{\"time\":\"")
                append(escape(segment.timeLabel))
                append("\",\"summary\":\"")
                append(escape(segment.summary))
                append("\"}")
            }
            append(']')
        }

    private fun escape(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", " ")

    /** 模型偶尔会套一层 ```json：剥掉再解析。 */
    private fun stripFences(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(text)
        val body = fence?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { text }
        val start = body.indexOfFirst { it == '[' || it == '{' }
        val end = body.indexOfLast { it == ']' || it == '}' }
        return if (start >= 0 && end > start) body.substring(start, end + 1) else body
    }

    private fun flatten(raw: String?): String =
        raw.orEmpty().replace(Regex("\\s+"), " ").trim()
}
