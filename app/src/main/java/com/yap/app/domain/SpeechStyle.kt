package com.yap.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 话风（`spec/persona-guidance/README.md` §3）。
 *
 * 回答「他怎么说话」：一句 tone、若干他真说过的常用语、若干语言习惯与不该出现的表达。
 * 常用语是**参考不是模板**（§1.1 第 4 条）：提示词里最多列 6 条，并要求「自然使用，最多带 1 处」。
 */
data class SpeechStyle(
    val tone: String = "",
    val phrases: List<String> = emptyList(),
    val habits: List<String> = emptyList(),
    val avoid: List<String> = emptyList()
) {
    fun isEmpty(): Boolean =
        tone.isBlank() && phrases.isEmpty() && habits.isEmpty() && avoid.isEmpty()

    /**
     * §6.3 的「你怎么说话」段正文（不含 `#` 标题，由 [PromptBuilder] 统一加）。
     *
     * 每一行的文案都来自 [PromptBuilder.Scaffold]：脚手架的 voice 段只有 rules / tone / phrases /
     * no_narration 四类行，所以 [habits] 与 [avoid] **不进提示词**（数据仍然保存、编辑器里能改；
     * 它们的语义由 tone 和两条 voice_rules 覆盖）。多列一条「习惯：…」就等于给两端加一行
     * 规范之外的中文，「两端逐字一致」的契约测试会立刻出现差异。
     *
     * phrases 只取前 [MAX_PROMPT_PHRASES] 条：常用语一多，模型就会开始背台词。
     */
    fun renderForPrompt(): String {
        val lines = mutableListOf<String>()
        lines += PromptBuilder.Scaffold.VOICE_RULES
        if (tone.isNotBlank()) {
            lines += PromptBuilder.Scaffold.VOICE_TONE.replace("{tone}", tone.trim().take(120))
        }
        val used = phrases.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_PROMPT_PHRASES)
        if (used.isNotEmpty()) {
            lines += PromptBuilder.Scaffold.VOICE_PHRASES
                .replace("{phrases}", used.joinToString("、") { "「$it」" })
        }
        lines += PromptBuilder.Scaffold.VOICE_NO_NARRATION
        return lines.joinToString("\n")
    }

    companion object {
        /** 提示词里最多几条常用语。 */
        const val MAX_PROMPT_PHRASES = 6

        /** 本地兜底最多几条常用语（§3.2）。 */
        const val MAX_LOCAL_PHRASES = 12
    }
}

/**
 * 话风 JSON 的编解码。
 *
 * 与 [WorldPresetCodec] 一样分宽松/严格两种：
 * - [decode]：缺字段 → 空数组；坏 JSON → `null`；单项类型不对就跳过那一项；
 * - [decodeStrict]：给编辑器保存用，字段类型明显不对时 `null`。
 */
object SpeechStyleCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun decode(raw: String?): SpeechStyle? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        return runCatching {
            SpeechStyle(
                tone = root.str("tone"),
                phrases = root.strings("phrases"),
                habits = root.strings("habits"),
                avoid = root.strings("avoid", 40)
            )
        }.getOrNull()
    }

    fun decodeOrEmpty(raw: String?): SpeechStyle = decode(raw) ?: SpeechStyle()

    fun decodeStrict(raw: String?): SpeechStyle? {
        if (raw.isNullOrBlank()) return SpeechStyle()
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val keys = listOf("phrases", "habits", "avoid")
        keys.forEach { key ->
            val element = root[key] ?: return@forEach
            if (element !is JsonArray) return null
            if (element.any { it !is JsonPrimitive || !(it as JsonPrimitive).isString }) return null
        }
        val toneElement = root["tone"]
        if (toneElement != null && (toneElement !is JsonPrimitive || !(toneElement as JsonPrimitive).isString)) {
            return null
        }
        return decode(raw)
    }

    fun encode(style: SpeechStyle): String = runCatching {
        JsonObject(
            mapOf(
                "tone" to JsonPrimitive(style.tone.trim()),
                "phrases" to JsonArray(style.phrases.map { it.trim() }.filter { it.isNotBlank() }.map(::JsonPrimitive)),
                "habits" to JsonArray(style.habits.map { it.trim() }.filter { it.isNotBlank() }.map(::JsonPrimitive)),
                "avoid" to JsonArray(style.avoid.map { it.trim() }.filter { it.isNotBlank() }.take(20).map(::JsonPrimitive))
            )
        ).toString()
    }.getOrDefault("{}")

    private fun JsonObject.str(key: String): String =
        ((this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: "").trim()

    private fun JsonObject.strings(key: String, max: Int = Int.MAX_VALUE): List<String> =
        ((this[key] as? JsonArray)?.toList().orEmpty()).mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotBlank() }
        }.distinct().take(max)
}

/**
 * §3.2 的本地兜底：没有 API Key / 调用失败时，用**聊天记录本身**做一个粗略总结。
 *
 * 规则照抄规范：
 * - 按行去掉 `昵称：` 前缀，取长度 2–20 的短句；
 * - 频次 ≥ 2 的句子进 phrases（最多 12 条）；
 * - tone 用「简短、口语」占位；habits 按平均句长与感叹号比例给 2 条；avoid 给通用 3 条。
 *
 * 它不追求准确，只追求「界面能干活、风格不至于完全空着」，并且结果会明确标注
 * `source = heuristic`（界面提示「这是本地粗略总结，可手动修改」）。
 */
object SpeechStyleHeuristic {

    const val TONE = "简短、口语"
    private val AVOID = listOf("长篇大论", "书面化表达", "夸张语气")

    fun summarize(rawSample: String): SpeechStyle {
        val sentences = rawSample.lineSequence()
            .map { stripSpeaker(it) }
            .map { it.trim() }
            .filter { it.length in 2..20 }
            .toList()
        val frequency = sentences.groupingBy { it }.eachCount()
        val phrases = frequency.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(SpeechStyle.MAX_LOCAL_PHRASES)
        if (sentences.isEmpty()) {
            return SpeechStyle(tone = TONE, avoid = AVOID)
        }
        val averageLength = sentences.sumOf { it.length }.toDouble() / sentences.size
        val exclamationRatio = sentences.count { it.contains('!') || it.contains('！') }.toDouble() / sentences.size
        val habits = buildList {
            add(if (averageLength <= 10.0) "句子很短，多为陈述或安排" else "句子中等长度，偶尔会多说两句")
            add(if (exclamationRatio < 0.2) "少用感叹号" else "会用感叹号表达情绪，但不滥用")
        }
        return SpeechStyle(tone = TONE, phrases = phrases, habits = habits, avoid = AVOID)
    }

    /** 去掉 `昵称：` 前缀（中英文冒号），只留说话内容。 */
    fun stripSpeaker(line: String): String {
        val text = line.trim()
        if (text.startsWith("http://") || text.startsWith("https://")) return text
        val index = listOf(text.indexOf('：'), text.indexOf(':')).filter { it in 1..16 }.minOrNull()
            ?: return text
        return text.substring(index + 1).trim()
    }
}
