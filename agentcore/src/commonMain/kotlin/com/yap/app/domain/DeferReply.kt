package com.yap.app.domain

/**
 * 模型选择「这一轮先不回」时的时间计划。
 *
 * 规格 §四把边界写死了：minutes 缺省 45，夹在 5–720；reason ≤80 字，只进日志和「他最近在想什么」。
 * 这些数字如果散在 ToolExecutor 里，改一处忘一处就会出现「模型写了 3000 分钟，他三天后才回」
 * 这种用户完全无法理解的沉默。所以收成一个纯函数，单测直接把边界钉住。
 */
object DeferReply {

    /** 工具名（与 ToolCatalog 一致）：模型调用它，就表示这一轮选择不回。 */
    const val TOOL_NAME = "defer_reply"

    const val DEFAULT_MINUTES = ToolArgClamp.MINUTES_DEFAULT
    const val MIN_MINUTES = ToolArgClamp.MINUTES_MIN
    const val MAX_MINUTES = ToolArgClamp.MINUTES_MAX
    const val MAX_REASON_CHARS = 80

    /** 到点后给他留的决策窗口：不是闹钟，到点只是「可以开始想这件事了」。 */
    const val WINDOW_MINUTES = 30

    data class Plan(
        val minutes: Int,
        val reason: String,
        val earliestAt: Long,
        val latestAt: Long
    )

    /**
     * 这一轮是否被模型主动推迟。
     *
     * ChatRepository 靠它决定「不写助手消息」——这个判断必须只看工具名，
     * 不能看模型最后有没有输出文字：模型经常在调用工具的同时继续写正文，
     * 那些字如果落库，就变成「他说了话但界面说已读」，自相矛盾。
     */
    fun wasRequested(toolNames: List<String>): Boolean =
        toolNames.any { it.trim().equals(TOOL_NAME, ignoreCase = true) }

    fun plan(minutes: Int?, reason: String?, now: Long): Plan {
        val safeMinutes = ToolArgClamp.clampDeferMinutes(minutes ?: DEFAULT_MINUTES)
        val cleanReason = reason?.trim()?.take(MAX_REASON_CHARS).orEmpty()
        val earliest = now + safeMinutes * 60_000L
        return Plan(
            minutes = safeMinutes,
            reason = cleanReason,
            earliestAt = earliest,
            latestAt = earliest + WINDOW_MINUTES * 60_000L
        )
    }
}
