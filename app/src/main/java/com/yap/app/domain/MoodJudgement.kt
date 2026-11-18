package com.yap.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 自动情绪判定（`spec/mood/README.md` §2）。
 *
 * 每 4 条用户消息（或隔了 24 小时）问一次模型：「上一轮情绪 + 最近 8 条对话摘要 → 现在是什么心情」，
 * 让情绪跟着真实对话走，而不是等他自己想起来调 `set_mood`。
 *
 * 四条刻意的边界（全部是纯函数，单测逐条钉住）：
 *
 * 1. **节流**：4 条用户消息，或 24 小时 + 至少 1 条新消息（[shouldJudge]）；
 * 2. **幅度限制**：单次每个维度最多动 2（[MoodDimensions.limitChange]）——情绪不该一秒崩掉；
 * 3. **没有明显变化就不写**（[MoodDimensions.isMeaningfulChange]）——否则 `moodUpdatedAt`
 *    被反复刷新，时间衰减永远不生效；
 * 4. **失败/无配置按时间衰减**（[decayAfterFailure]）：每小时向 0 / 2 / 3 靠一步，不报错、不打扰。
 *
 * 提示词里的固定中文放在 domain/ 而不是 `strings.xml`：它面向模型、不面向用户，
 * 翻译会改变模型行为（与 `docs/I18N.md` 的约定一致）。
 */
object MoodJudgement {

    /** 每多少条用户消息判定一次。 */
    const val USER_MESSAGES_PER_JUDGE: Int = 4

    /** 就算没攒够 4 条，隔这么久也判定一次（前提是至少有 1 条新消息）。 */
    const val INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

    /** 给模型看的最近对话条数（规范 §2「最近 8 条对话摘要」）。 */
    const val RECENT_MESSAGES: Int = 8

    /** 每条对话摘要最多多少字：8 条 × 80 字，够判断语气又不至于把上下文撑大。 */
    const val RECENT_MESSAGE_CHARS: Int = 80

    /** 判定请求要稳、要短，不要发挥。 */
    const val MAX_OUTPUT_TOKENS: Int = 220
    const val TEMPERATURE: Double = 0.2

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 这一轮要不要判定。
     *
     * @param userMessages 这个会话累计用户消息数（含刚发出的那条）
     * @param cursor 上次判定时的用户消息数
     * @param lastRunAt 上次判定的时间（毫秒）；从没判定过是 0
     */
    fun shouldJudge(userMessages: Int, cursor: Int, lastRunAt: Long, now: Long): Boolean {
        val fresh = (userMessages - cursor).coerceAtLeast(0)
        if (fresh >= USER_MESSAGES_PER_JUDGE) return true
        return fresh > 0 && lastRunAt > 0L && now - lastRunAt >= INTERVAL_MS
    }

    /**
     * 解析模型给的 JSON（`{label,valence,arousal,dominance,intensity,reason}`）。
     *
     * 返回 `null` 表示「这一轮不写库」：JSON 坏、字段全空、或与上一轮相比**没有明显变化**。
     * 越界值一律夹到规范范围（模型写 7 / -9 也不该让提示词出现离谱数字），
     * 变化幅度再用 [MoodDimensions.limitChange] 收一次。
     *
     * 兼容两种形状：直接是字段对象，或包在 `{"mood": {…}}` 里（模型常见输出）。
     */
    fun parse(raw: String, previous: MoodReading, now: Long = 0L): MoodReading? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val root = runCatching { json.parseToJsonElement(stripFences(text)) as? JsonObject }.getOrNull()
            ?: return null
        val moodObject = when {
            root["mood"] is JsonObject -> root["mood"] as JsonObject
            root["emotion"] is JsonObject -> root["emotion"] as JsonObject
            else -> root
        }
        val label = moodObject.text("label", "mood", "emotion", "feeling")
        val hasAnyDimension = listOf("valence", "arousal", "dominance", "intensity")
            .any { moodObject[it] is JsonPrimitive }
        // 只有一句 reason、没有任何维度：这不是判定结果，别拿它覆盖心情。
        if (label.isNullOrBlank() && !hasAnyDimension) return null

        val intensity = moodObject.int("intensity") ?: previous.intensity
        // 只给了标签、没给任何维度（模型偶尔偷懒）：按旧数据映射推一组，
        // 而不是沿用上一轮的维度——那会出现「开心（愉快度 -2）」这种自相矛盾的提示词。
        // 标签没变时不推：只补一句 reason 不算情绪变化（否则每 4 轮都会因为维度被重算而写库）。
        val legacyBase = if (
            !hasAnyDimension && !label.isNullOrBlank() &&
            MoodLexicon.normalize(label) != MoodLexicon.normalize(previous.label)
        ) {
            MoodDimensions.fromLegacy(label, intensity)
        } else {
            null
        }
        val normalized = MoodDimensions.normalize(
            label = label ?: previous.label.takeIf { it.isNotBlank() } ?: MoodLexicon.BASELINE,
            intensity = intensity,
            valence = moodObject.int("valence") ?: legacyBase?.valence ?: previous.valence,
            arousal = moodObject.int("arousal") ?: legacyBase?.arousal ?: previous.arousal,
            dominance = moodObject.int("dominance") ?: legacyBase?.dominance ?: previous.dominance,
            reason = moodObject.text("reason") ?: previous.reason,
            updatedAt = now
        )
        val limited = MoodDimensions.limitChange(previous, normalized)
        return limited.takeIf { MoodDimensions.isMeaningfulChange(previous, it) }
    }

    /**
     * 判定失败 / 没有可用 API 配置时的兜底：按时间衰减（规范 §2）。
     *
     * 不报错、不弹提示；`updatedAt` 不动——衰减不是一次新的情绪，下一轮仍然从原始时间算。
     */
    fun decayAfterFailure(previous: MoodReading, now: Long): MoodReading {
        val hours = MoodDimensions.elapsedHours(previous.updatedAt, now)
        if (hours <= 0L) return previous
        val decayed = MoodDimensions.decay(previous, hours)
        // 三个维度都回到基准、标签也还是中性偏负面的旧词时，顺手把强度也收到 1：
        // 否则提示词会写着「平静（强度 4/5）」，自相矛盾。
        val atBaseline = decayed.valence == MoodDimensions.BASELINE_VALENCE &&
            decayed.arousal == MoodDimensions.BASELINE_AROUSAL &&
            decayed.dominance == MoodDimensions.BASELINE_DOMINANCE
        return if (atBaseline) decayed.copy(intensity = decayed.intensity.coerceAtLeast(1)) else decayed
    }

    // ------------------------------------------------------------------ 提示词

    /** 判定用的 system 提示词：只要一个 JSON 对象。 */
    fun systemPrompt(): String = buildString {
        appendLine("你是情绪状态分析器。输入是「上一轮的情绪」和「最近的对话摘要」，")
        appendLine("判断说话的人此刻的情绪有没有明显变化。只输出一个 JSON 对象，不要解释、不要 markdown：")
        appendLine(
            """{"label":"一个词的此刻心情，例如 累 / 平静 / 烦 / 开心 / 有点委屈 / 期待","""
        )
        appendLine(
            """ "valence":-2 到 2 的整数（负=不悦，0=平静，正=愉快），"""
        )
        appendLine(
            """ "arousal":0 到 5 的整数（0=低能量/困，5=激动），"""
        )
        appendLine(
            """ "dominance":0 到 5 的整数（0=被压住，3=一般，5=掌控），"""
        )
        appendLine(
            """ "intensity":1 到 5 的整数（情绪有多浓），"reason":"一句话，写给将来的自己看"}"""
        )
        appendLine("规则：")
        appendLine("- 只根据对话里真实发生的语气与内容判断，不要编；没有明显变化就原样返回上一轮的值；")
        appendLine(
            "- 单次变化幅度不要超过 2（例如上一轮 valence 是 0，这次不要直接给 -2 或 2）；"
        )
        appendLine("- 标签要短（不超过 6 个字），不要用「开心/难过」这种没有上下文的空话，除非对话就是这样；")
        appendLine("- 不评价对方，不输出任何额外字段。")
    }.trimEnd()

    /**
     * 判定用的 user 提示词：上一轮情绪 + 最近 8 条对话摘要。
     *
     * @param recent 已经按时间顺序排好、并裁过长度的对话摘要（「对方：…」「你：…」）
     */
    fun userPrompt(previous: MoodReading, recent: List<String>): String = buildString {
        appendLine("上一轮的情绪：${previous.label}（强度 ${previous.intensity}/5，")
        appendLine(
            "愉快度 ${previous.valence} / 唤醒度 ${previous.arousal}/5 / 掌控感 ${previous.dominance}/5）"
        )
        if (previous.reason.isNotBlank()) appendLine("上一轮的原因：${previous.reason}")
        appendLine()
        appendLine("最近的对话（新的在最后）：")
        val lines = recent.map { it.trim() }.filter { it.isNotBlank() }.takeLast(RECENT_MESSAGES)
        if (lines.isEmpty()) {
            appendLine("（还没有对话）")
        } else {
            lines.forEach { appendLine("- ${it.take(RECENT_MESSAGE_CHARS)}") }
        }
        appendLine()
        append("现在他的情绪是什么？只输出 JSON。")
    }

    /**
     * 一条对话摘要（`对方：…` / `你：…`）。
     *
     * 放在 domain 而不是数据层：数据层（`data/`）有「不许硬编码中文」的守卫，
     * 这两句是面向模型的提示词、不面向用户，按 `docs/I18N.md` 的约定归在提示词一侧。
     */
    fun recentLine(fromUser: Boolean, content: String): String {
        val who = if (fromUser) "对方：" else "你："
        return who + content.replace('\n', ' ').trim().take(RECENT_MESSAGE_CHARS)
    }

    /** 模型偶尔会套一层 ```json：把它剥掉再解析。 */
    private fun stripFences(text: String): String {
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(text)
        val body = fence?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { text }
        // 前后有解释文字时，只取第一个 { 到最后一个 } 之间的部分。
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        return if (start >= 0 && end > start) body.substring(start, end + 1) else body
    }

    private fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 数字或数字字符串都认（模型很爱写 `"3"`）；认不出返回 null，由调用方沿用上一轮。 */
    private fun JsonObject.int(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        primitive.intOrNull?.let { return it }
        return primitive.contentOrNull?.trim()?.toIntOrNull()
    }
}
