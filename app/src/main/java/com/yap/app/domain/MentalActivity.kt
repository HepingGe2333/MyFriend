package com.yap.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 一次心理活动的生成结果（念头 + 三个维度的微调，每维 -1..1）。 */
data class MentalDraft(
    val thought: String,
    val valenceDelta: Int = 0,
    val arousalDelta: Int = 0,
    val dominanceDelta: Int = 0
)

/** 为什么要做这次心理活动；只用于日志/测试，不展示给用户。 */
enum class MentalTrigger {
    /** 距上次心理活动满 3 分钟。 */
    TIME,

    /** 距上次已经聊了 3 轮。 */
    ROUNDS,

    /** 唤醒度 ≥4，心情激动。 */
    AROUSED,

    /** 与上次快照相比，任一维度变化 ≥2。 */
    MOOD_SHIFT
}

object MentalActivity {

    const val MAX_THOUGHT_CHARS = 60
    const val ACTIVE_WINDOW_MS = 10 * 60 * 1000L
    const val MIN_INTERVAL_MS = 3 * 60 * 1000L
    /** 念头注入下一轮的保鲜期：30 分钟，超过就不再把旧念头塞进当前对话。 */
    const val PROMPT_FRESHNESS_MS = 30 * 60 * 1000L
    const val MIN_ROUNDS = 3
    const val AROUSED_THRESHOLD = 4
    const val MOOD_SHIFT_THRESHOLD = 2

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 现在该不该做一次心理活动。
     *
     * @param latestMessageAt 这段会话最近一条消息的时间（用户或助手都算）
     * @param lastActivityAt 上次心理活动时间；0 表示还没做过
     * @param userMessagesSince 上次心理活动以来的用户消息数
     * @param justSent 助手刚发出一轮（调用点明确知道，不靠时间戳猜）
     */
    fun trigger(
        now: Long,
        latestMessageAt: Long?,
        lastActivityAt: Long,
        userMessagesSince: Int,
        justSent: Boolean,
        arousal: Int,
        moodShift: Int
    ): MentalTrigger? {
        val active = justSent || (latestMessageAt != null && now - latestMessageAt <= ACTIVE_WINDOW_MS)
        if (!active) return null
        // 上一次念头就在此刻：不重复写同一秒的念头。
        if (lastActivityAt >= now) return null

        // 激动与明显心情变化不看时间窗，活跃时立刻做一次（规范 §3「必做」）。
        if (arousal >= AROUSED_THRESHOLD) return MentalTrigger.AROUSED
        if (moodShift >= MOOD_SHIFT_THRESHOLD) return MentalTrigger.MOOD_SHIFT

        // 还没做过：活跃窗口里就可以先想一句（与 iOS 的首次行为一致）。
        if (lastActivityAt <= 0L) {
            return if (userMessagesSince >= MIN_ROUNDS) MentalTrigger.ROUNDS else MentalTrigger.TIME
        }
        if (now - lastActivityAt >= MIN_INTERVAL_MS) return MentalTrigger.TIME
        if (userMessagesSince >= MIN_ROUNDS) return MentalTrigger.ROUNDS
        return null
    }

    /**
     * 两次心情快照之间的距离：三维曼哈顿距离（与 iOS `changeDistance` 一致）。
     *
     * 用总距离而不是单维最大差：愉快 +2、唤醒 +2 是一起发生的剧烈变化，
     * 只取 max 会把它当成「只变了一格」。
     */
    fun moodShift(previous: MoodReading?, current: MoodReading?): Int {
        if (previous == null || current == null) return 0
        return kotlin.math.abs(previous.valence - current.valence) +
            kotlin.math.abs(previous.arousal - current.arousal) +
            kotlin.math.abs(previous.dominance - current.dominance)
    }

    /**
     * 解析模型输出。
     *
     * 期望 JSON：`{"thought":"…","valence":0,"arousal":1,"dominance":-1}`；
     * 也容忍代码块、前后闲话、字段名 `valenceDelta`。解析不出 JSON 时把整段当念头，
     * 心情微调按 0 处理——「有念头」比「格式完美」重要，但绝不因为坏格式改心情。
     */
    fun parse(raw: String): MentalDraft? {
        val text = raw.trim().removeCodeFences()
        if (text.isBlank()) return null
        val objectText = firstJsonObject(text)
        if (objectText != null) {
            val obj = runCatching { json.parseToJsonElement(objectText) as? JsonObject }.getOrNull()
            if (obj != null) {
                val thought = obj.string("thought")
                    ?: obj.string("mental")
                    ?: obj.string("content")
                if (!thought.isNullOrBlank()) {
                    return MentalDraft(
                        thought = clampThought(thought),
                        valenceDelta = obj.delta("valence", "valenceDelta"),
                        arousalDelta = obj.delta("arousal", "arousalDelta"),
                        dominanceDelta = obj.delta("dominance", "dominanceDelta")
                    )
                }
            }
        }
        val fallback = clampThought(text)
        if (fallback.isBlank()) return null
        return MentalDraft(thought = fallback)
    }

    fun clampThought(raw: String): String = raw.trim()
        .replace(Regex("\\s+"), " ")
        .take(MAX_THOUGHT_CHARS)
        .trim()

    fun promptBlock(thought: String, at: Long, now: Long = System.currentTimeMillis()): String {
        if (thought.isBlank()) return ""
        if (at > 0L && now - at > PROMPT_FRESHNESS_MS) return ""
        return "你刚才心里想：${clampThought(thought)}"
    }

    private fun String.removeCodeFences(): String =
        replace(Regex("```[a-zA-Z]*"), "").replace("```", "").trim()

    /** 从一段文本里找出第一个平衡的 `{...}`（字符串里的花括号不参与计数）。 */
    private fun firstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.delta(vararg keys: String): Int {
        for (key in keys) {
            val raw = this[key] ?: continue
            val value = (raw as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
            if (value != null) return value.coerceIn(-1, 1)
        }
        return 0
    }
}
