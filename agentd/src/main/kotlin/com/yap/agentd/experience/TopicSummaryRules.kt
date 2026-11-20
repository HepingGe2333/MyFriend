package com.yap.agentd.experience

import com.yap.app.domain.MemoryContract
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 对话主题总结（`docs/design/context-memory-v2.md`「更新节奏」）的**纯规则层**。
 *
 * 主题总结是一段「滚动的、按话题分段的摘要」：每段带一个时间段（`2026-11-18 晚`），
 * 每段 ≤120 字，最多 12 段（再多的从旧到新丢）。三段纯逻辑在这里，LLM 调用在
 * [SessionMemoryService]：
 *
 * 1. [timeLabel]：把消息时间折成「日期 + 时段」（时段用中文直觉词：凌晨/早上/上午/中午/下午/晚/深夜）；
 * 2. [parse]：宽容解析模型输出（JSON / `时段｜摘要` / 纯摘要），坏输出返回 null；
 * 3. [merge]：把新的一段并进滚动列表（同一时段就合并、超过 12 段丢最旧）；
 * 4. [shouldSplit]：话题明显切换的**廉价判据**（关键词重合度），让「话题切换」不必等满 15 轮。
 *
 * 为什么要有纯规则层：主题总结会直接进提示词，格式一旦漂（多一个空行、少一个时间段）
 * 就会让模型把摘要当成清单；这些格式规则必须能单测。
 */
object TopicSummaryRules {

    /** 时段定义：`[起始小时, 标签)`；最后一个覆盖到 24 点。 */
    private val SLOTS: List<Pair<Int, String>> = listOf(
        0 to "凌晨",
        6 to "早上",
        9 to "上午",
        12 to "中午",
        14 to "下午",
        18 to "晚",
        23 to "深夜"
    )

    /** 话题切换的判据：最近消息与上一段主题的关键词重合度低于这个值就算「换话题了」。 */
    const val TOPIC_SWITCH_OVERLAP: Double = 0.08

    /**
     * 话题切换至少要看多少字（太短的「嗯」「好」不该触发新主题）。
     *
     * 16 字：一句「今天想聊聊养猫，楼下有只小猫」就够；「嗯」「好呀」不够。
     */
    const val TOPIC_SWITCH_MIN_CHARS: Int = 16

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    /** `2026-11-18 晚`：日期 + 时段（时段由小时决定，跨午夜按 0–6 点算「凌晨」）。 */
    fun timeLabel(at: Long, zone: ZoneId): String {
        if (at <= 0L) return ""
        val zoned = runCatching { Instant.ofEpochMilli(at).atZone(zone) }.getOrNull() ?: return ""
        val slot = SLOTS.lastOrNull { zoned.hour >= it.first }?.second ?: SLOTS.first().second
        return zoned.format(TIME_FORMAT) + " " + slot
    }

    /** 一段消息（升序）的时间段：取最后一条的时间；都没有时间时用 [fallback]。 */
    fun timeLabelOf(messages: List<Long>, zone: ZoneId, fallback: String = ""): String {
        val last = messages.filter { it > 0 }.maxOrNull() ?: return fallback
        return timeLabel(last, zone).ifBlank { fallback }
    }

    /**
     * 解析模型输出的一段主题：
     * - `{"timeLabel":"2026-11-18 晚","summary":"加班与方案改版"}`（推荐形状）；
     * - `2026-11-18 晚｜加班与方案改版` / `2026-11-18 晚: 加班与方案改版`；
     * - 纯摘要（没有时间段时用 [fallbackLabel]）。
     *
     * 返回 `null` 表示「没有可用内容」（空串、`SKIP`、只有标点）。
     */
    fun parse(raw: String?, fallbackLabel: String = ""): MemoryContract.TopicSegment? {
        val text = raw.orEmpty().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (text.isEmpty()) return null
        if (isSkip(text)) return null
        val json = parseJson(text)
        if (json != null) {
            val summary = flatten(json.first)
            if (summary.isEmpty() || isSkip(summary)) return null
            val label = flatten(json.second).ifBlank { fallbackLabel }
            return MemoryContract.TopicSegment(
                timeLabel = label.take(32),
                summary = summary.take(MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS)
            )
        }
        // 纯文本：先看有没有 `时段｜摘要` / `时段: 摘要` 的分隔。
        val pipe = text.indexOfFirst { it == '｜' || it == '|' }
        if (pipe > 0) {
            val label = text.substring(0, pipe).trim()
            val summary = text.substring(pipe + 1).trim()
            if (summary.isNotEmpty() && looksLikeTimeLabel(label)) {
                return MemoryContract.TopicSegment(
                    timeLabel = label.take(32),
                    summary = summary.take(MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS)
                )
            }
        }
        val single = flatten(text)
        if (single.isEmpty() || isSkip(single)) return null
        return MemoryContract.TopicSegment(
            timeLabel = fallbackLabel.take(32),
            summary = single.take(MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS)
        )
    }

    /** `SKIP` / `skip` / `先不更新` / `不用更新` 都当成「模型决定这次不写」。 */
    fun isSkip(raw: String?): Boolean {
        val text = raw.orEmpty().trim().lowercase()
        return text == "skip" || text == "no" || text == "none" ||
            text.contains("先不更新") || text.contains("不用更新") || text.contains("不需要更新")
    }

    /**
     * 把一段并进滚动主题列表：同一时间段就合并（总长 ≤120），否则追加；最多 12 段（丢最旧）。
     */
    fun merge(
        existing: List<MemoryContract.TopicSegment>,
        next: MemoryContract.TopicSegment,
        maxSegments: Int = MemoryContract.Summary.TOPIC_MAX_SEGMENTS
    ): List<MemoryContract.TopicSegment> {
        if (next.summary.isBlank()) return existing
        val limit = maxSegments.coerceIn(1, MemoryContract.Summary.TOPIC_MAX_SEGMENTS)
        val result = existing.toMutableList()
        val last = result.lastOrNull()
        if (last != null && last.timeLabel.isNotBlank() && last.timeLabel == next.timeLabel) {
            val merged = (last.summary.trim() + "；" + next.summary.trim())
            result[result.lastIndex] = last.copy(
                summary = merged.take(MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS)
            )
        } else {
            result += next
        }
        return if (result.size > limit) result.takeLast(limit) else result
    }

    /**
     * 话题是不是明显换了：最近几条消息与上一段主题的**关键词重合度**低于
     * [TOPIC_SWITCH_OVERLAP]，且最近消息够长（[TOPIC_SWITCH_MIN_CHARS]）。
     *
     * 为什么用重合度而不是再问一次模型：这是「要不要现在就更新」的**触发条件**，
     * 判断成本必须接近 0；重合度算错最多让主题早/晚更新一轮，不会伤到内容。
     */
    fun shouldSplit(recentText: String, lastTopic: MemoryContract.TopicSegment?): Boolean {
        val text = recentText.trim()
        if (text.length < TOPIC_SWITCH_MIN_CHARS) return false
        val last = lastTopic ?: return true
        val previous = last.summary.trim()
        if (previous.isEmpty()) return true
        val current = keywords(text)
        val older = keywords(previous)
        if (current.isEmpty() || older.isEmpty()) return false
        val overlap = current.intersect(older).size.toDouble() / current.size
        return overlap < TOPIC_SWITCH_OVERLAP
    }

    // ------------------------------------------------------------------ 内部

    /** 关键词：中文按二元组（单字太泛），英文/数字按词；去掉标点与停用词。 */
    internal fun keywords(text: String): Set<String> {
        val result = LinkedHashSet<String>()
        val normalized = text.lowercase()
        val english = Regex("[a-z0-9]{2,}").findAll(normalized).map { it.value }
        english.forEach { if (it !in STOP_WORDS) result += it }
        val cjk = Regex("[\\u4e00-\\u9fff]+").findAll(normalized).map { it.value }
        for (chunk in cjk) {
            if (chunk.length == 1) {
                if (chunk !in STOP_WORDS) result += chunk
                continue
            }
            for (index in 0 until chunk.length - 1) {
                val bigram = chunk.substring(index, index + 2)
                if (bigram !in STOP_WORDS) result += bigram
            }
        }
        return result
    }

    private fun looksLikeTimeLabel(text: String): Boolean =
        text.length <= 32 && (text.any { it.isDigit() } || SLOTS.any { text.contains(it.second) })

    /** `{timeLabel, summary}` 的宽松解析：返回 `(summary, timeLabel)`。 */
    private fun parseJson(text: String): Pair<String, String>? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val parsed = runCatching {
            com.yap.app.core.json.Json.parse(text.substring(start, end + 1))
        }.getOrNull() as? com.yap.app.core.json.JsonValue.Obj ?: return null
        val summary = (parsed.fields["summary"] as? com.yap.app.core.json.JsonValue.Str)?.value
            ?: (parsed.fields["topic"] as? com.yap.app.core.json.JsonValue.Str)?.value
            ?: return null
        val label = (parsed.fields["timeLabel"] as? com.yap.app.core.json.JsonValue.Str)?.value
            ?: (parsed.fields["time"] as? com.yap.app.core.json.JsonValue.Str)?.value
            ?: (parsed.fields["period"] as? com.yap.app.core.json.JsonValue.Str)?.value
            ?: ""
        return summary to label
    }

    private fun flatten(raw: String?): String = raw.orEmpty().replace(Regex("\\s+"), " ").trim()

    private val STOP_WORDS = setOf(
        "的", "了", "是", "在", "我", "你", "他", "她", "们", "这", "那", "一", "个",
        "就", "也", "都", "还", "和", "与", "吧", "吗", "呢", "啊", "嗯", "好", "不",
        "the", "and", "you", "for", "that", "this", "with", "was", "are", "but"
    )
}
