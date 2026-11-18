package com.yap.app.domain

/**
 * 会话级备忘录的规则（`spec/experience/README.md` §6）。
 *
 * 两段摘要都是**可变区域**：短期覆盖最近 5 轮（≤200 字），长期覆盖更早的对话（≤500 字）。
 * 更新节流只认「可见用户消息数」的整数倍（每 5 / 30 条），所以拉黑期间的消息不会推快更新。
 */
object SessionMemoryPolicy {

    const val SHORT_EVERY = 5
    const val LONG_EVERY = 30
    const val SHORT_MAX_CHARS = 200
    const val LONG_MAX_CHARS = 500

    /** 短期覆盖的轮数：一轮 = 一条用户 + 一条助手。 */
    const val SHORT_ROUNDS = 5

    /** 长期摘要最多往前看多少条消息（成本闸，不是产品语义）。 */
    const val LONG_WINDOW_MESSAGES = 60

    /**
     * 这份摘要该不该更新：距上次更新又攒够了 [interval] 条用户消息。
     *
     * 用「条数差」而不是「条数取模」：失败时 upTo 不前进，下次回复会重试；
     * 如果按 count % 5 == 0 判，失败一次就要再等 5 条，甚至永远补不上。
     */
    fun shouldUpdate(currentUserCount: Int, upTo: Int, interval: Int): Boolean {
        if (currentUserCount <= 0) return false
        return currentUserCount - upTo.coerceAtLeast(0) >= interval.coerceAtLeast(1)
    }

    /**
     * 下一次该更新短期的门槛（用户消息数）；返回 null 表示还没到/已经更新过。
     *
     * 用「向下取整的整数倍」而不是 `count == last + 5`：中途 App 被杀、消息补录、
     * 或者从旧版本升级上来时，仍然会在下一次跨过 5 的倍数时更新一次。
     */
    fun nextShortCount(userCount: Int, lastUpdatedCount: Int): Int? =
        userCount.coerceAtLeast(0).takeIf { shouldUpdate(it, lastUpdatedCount, SHORT_EVERY) }

    /** 下一次该更新长期的门槛；语义同上。 */
    fun nextLongCount(userCount: Int, lastUpdatedCount: Int): Int? =
        userCount.coerceAtLeast(0).takeIf { shouldUpdate(it, lastUpdatedCount, LONG_EVERY) }

    /** 摘要长度上限：按字符截断（中文一个字算一个），并去掉首尾空白。 */
    fun truncate(raw: String, maxChars: Int): String {
        val text = raw.trim()
        if (maxChars <= 0) return ""
        return if (text.length <= maxChars) text else text.take(maxChars).trimEnd()
    }

    /**
     * 模型输出可能包着「摘要：」前缀或 Markdown 标题；这里只做最小清理。
     * 清理后为空/过短时返回 null，调用方保留旧摘要。
     */
    fun sanitizeSummary(raw: String, maxChars: Int): String? {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```").removeSuffix("```").trim()
        }
        text = text.removePrefix("摘要：").removePrefix("摘要:").trim()
        // 摘要是一段话，不是多行列表：压平换行，同时把连续空白收成一个空格。
        text = text.replace(Regex("\\s+"), " ").trim()
        if (text.length < 2) return null
        return truncate(text, maxChars).takeIf { it.isNotBlank() }
    }

    /**
     * 拼进系统提示词的「你的备忘录」：短期在前、长期在后。
     *
     * 两段都为空时返回空串（不产生一个空标题）。即使只有长期，也先写短期标题并注明
     * 「还没什么」，避免模型把长期内容误当最近发生的事。
     */
    fun promptBlock(shortSummary: String?, longSummary: String?): String {
        val short = truncate(shortSummary.orEmpty(), SHORT_MAX_CHARS)
        val long = truncate(longSummary.orEmpty(), LONG_MAX_CHARS)
        if (short.isBlank() && long.isBlank()) return ""
        return buildString {
            appendLine("\n\n---\n## 你的备忘录")
            appendLine("这是你自己记下的对话摘要（不是人设，会随时间更新）；回答时自然地用，不要逐条复述。")
            if (short.isNotBlank()) {
                appendLine()
                appendLine("**最近几轮**")
                appendLine("- $short")
            }
            if (long.isNotBlank()) {
                appendLine()
                appendLine("**更早以前**")
                appendLine("- $long")
            }
        }.trimEnd()
    }
}
