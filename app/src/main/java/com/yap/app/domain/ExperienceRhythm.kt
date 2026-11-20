package com.yap.app.domain

import kotlin.math.roundToLong

/**
 * 0.14 的「像人」节奏（`spec/experience/README.md` §4 / §5）。
 *
 * 这里只做纯计算：首条消息的「正在输入」时长、连发之间的停顿、以及不带工具时按空行拆条。
 * 真正的 delay 由仓库层执行；UI 不自己算，测试也不依赖真实时间。
 */
object ExperienceRhythm {

    /** 首条 / 每条发送前的思考时长上下界（规范 §5）。 */
    const val TYPING_MIN_MS = 400L
    const val TYPING_MAX_MS = 4_000L
    const val TYPING_BASE_MS = 400L
    const val TYPING_PER_CHAR_MS = 25L
    /** 120 字之后不再变慢（再长的消息也不该等 10 秒）。 */
    const val TYPING_LENGTH_CAP = 120

    /** 连发之间的停顿（规范 §4）：0.8–2.5 秒。 */
    const val GAP_MIN_MS = 800L
    const val GAP_MAX_MS = 2_500L
    const val GAP_BASE_MS = 800L
    const val GAP_SPAN_MS = 1_700L

    /** 单条 `send_message` 的字数上限（规范 §4）。 */
    const val MAX_SEND_MESSAGE_CHARS = 120

    /** 助手一轮最多发几条（规范 §4）。 */
    const val MAX_MESSAGES_PER_ROUND = 4

    /** 不带工具的回复最多拆成几条（规范 §4）。 */
    const val MAX_SPLIT_MESSAGES = 3

    /**
     * `clamp(0.4 + 字数×0.025 + 心情修正, 0.4, 4.0)` 秒。
     *
     * 心情修正只影响「等多久」，不改内容：低唤醒更慢、高唤醒更快；「累/困」这类低能量标签
     * 再慢一点。修正量故意很小（±0.45s），免得让用户觉得延迟是随机的。
     */
    fun typingDelayMillis(
        charCount: Int,
        arousal: Int? = null,
        lowEnergy: Boolean = false,
        random: () -> Float = { 0.5f }
    ): Long {
        // 与 iOS `TypingDelay.seconds` 同一公式；random 仅为兼容旧调用，默认不抖动。
        val chars = charCount.coerceIn(0, TYPING_LENGTH_CAP)
        val correctionMs = moodCorrectionMs(arousal, lowEnergy)
        val raw = TYPING_BASE_MS + chars * TYPING_PER_CHAR_MS + correctionMs
        val shaken = raw * (1f + (random() - 0.5f) * 2f * 0.06f)
        return shaken.roundToLong().coerceIn(TYPING_MIN_MS, TYPING_MAX_MS)
    }

    /**
     * 连发之间的停顿：同样按长度与心情，但落在 0.8–2.5 秒这个更窄的区间。
     *
     * 这里与 [typingDelayMillis] 分开，是因为语义不同：首条是「他看到了、在想」，
     * 连发是「他还在打字，下一句马上出来」。连发太长会像卡住，太短又不像人在敲。
     */
    fun sendGapMillis(
        charCount: Int,
        arousal: Int? = null,
        lowEnergy: Boolean = false,
        random: () -> Float = { 0.5f }
    ): Long {
        val chars = charCount.coerceIn(1, TYPING_LENGTH_CAP)
        val base = GAP_BASE_MS + GAP_SPAN_MS * (chars.toDouble() / TYPING_LENGTH_CAP)
        val adjusted = base + moodCorrectionMs(arousal, lowEnergy) * 0.6
        val shaken = adjusted * (1f + (random() - 0.5f) * 2f * 0.10f)
        return shaken.roundToLong().coerceIn(GAP_MIN_MS, GAP_MAX_MS)
    }

    /** 心情修正（毫秒）：低唤醒/累 → 更慢，高唤醒 → 更快；总幅度 ≤ 0.5 秒。 */
    private fun moodCorrectionMs(arousal: Int?, lowEnergy: Boolean): Long {
        var adjustment = if (arousal == null) 0.0 else (2.5 - arousal.coerceIn(0, 5)) * 150.0
        if (lowEnergy) adjustment += 250.0
        return adjustment.coerceIn(-500.0, 500.0).roundToLong()
    }

    /**
     * 不带 `send_message` 的回复按空行拆成 1–3 条。
     *
     * 超过 3 段时把开头几段并进第 1 条（用空行保留原意），绝不丢内容；空文本返回空列表。
     * 单段内的换行保留，因为那可能是一首诗/一段引用，不该被当成「两条消息」。
     */
    fun splitPlainReply(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parts = trimmed.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        if (parts.size <= MAX_SPLIT_MESSAGES) return parts
        // 段落太多时把开头几段并进第一条——他先说的话都在第一条里。
        val head = parts.take(parts.size - MAX_SPLIT_MESSAGES + 1).joinToString("\n\n")
        return listOf(head) + parts.takeLast(MAX_SPLIT_MESSAGES - 1)
    }

    /**
     * `send_message` 的正文规整：去首尾空白、把连续空行压成一个换行，并截到 120 字。
     *
     * 截断而不是报错：模型已经决定「发这一句」，因为超了 3 个字整条丢掉会让对话断掉；
     * 截断后回灌给模型一句「已按 120 字截断」，它下一次会自己写短。
     */
    fun clampSendText(raw: String): String {
        val text = raw.trim().replace(Regex("\\n{3,}"), "\n\n")
        if (text.length <= MAX_SEND_MESSAGE_CHARS) return text
        return text.take(MAX_SEND_MESSAGE_CHARS).trimEnd()
    }
}
