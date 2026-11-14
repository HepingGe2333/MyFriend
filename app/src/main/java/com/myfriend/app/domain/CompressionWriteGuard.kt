package com.myfriend.app.domain

/**
 * 历史压缩「写回摘要」之前的最后一道判断。
 *
 * 压缩是一次几秒的模型调用：开始读会话与消息，结束才写 `historySummary`。
 * 中间用户可能清空上下文、删掉会话、或者又聊了几句。只在「开始时是什么样、
 * 结束时还是什么样」时才允许落库：
 *
 * - 清空 / 删除 / 整库替换会 bump 世代号（[SessionGenerationRegistry]）→ 世代不一致，丢弃；
 * - 会话被删掉 → 会话不存在，丢弃；
 * - 摘要边界变了（另一次压缩已经写过 / 清空把边界清掉了）→ 丢弃，避免覆盖更新的摘要；
 * - 消息**变少**了 → 说明这段对话被删过（清空 / 删消息），摘要的覆盖区间不再可靠，丢弃。
 *   只是变多（用户又聊了几句）不丢弃：新消息不在这一批摘要的覆盖范围里，
 *   边界没变就仍然连续。
 *
 * 这样既满足「清空之后旧摘要不许回来」，又不会因为「压缩期间用户又发了一句」
 * 而白烧一次调用。纯函数，单测直接覆盖各种结局；压缩器只负责调用它并记日志。
 */
object CompressionWriteGuard {

    enum class Decision {
        /** 期间什么都没变（或只是又多了几条新消息），可以写回。 */
        APPLY,

        /** 期间清空 / 删除 / 整库替换过：这次结果作废，别让旧摘要回来。 */
        DISCARD_STALE_GENERATION,

        /** 会话已经不在了（删对话 / 删人格）。 */
        DISCARD_SESSION_GONE,

        /** 摘要边界已经被别的写入改过（并发压缩 / 清空上下文）。 */
        DISCARD_SUMMARY_CHANGED,

        /** 消息变少了：这段对话被删过，摘要的覆盖区间不再可靠。 */
        DISCARD_MESSAGES_CHANGED
    }

    fun decide(
        startGeneration: Long,
        currentGeneration: Long,
        sessionExists: Boolean,
        startSummaryUpTo: Long?,
        currentSummaryUpTo: Long?,
        startMessageCount: Int,
        currentMessageCount: Int
    ): Decision = when {
        !sessionExists -> Decision.DISCARD_SESSION_GONE
        startGeneration != currentGeneration -> Decision.DISCARD_STALE_GENERATION
        normalized(startSummaryUpTo) != normalized(currentSummaryUpTo) ->
            Decision.DISCARD_SUMMARY_CHANGED
        currentMessageCount < startMessageCount -> Decision.DISCARD_MESSAGES_CHANGED
        else -> Decision.APPLY
    }

    /** 0 与 null 都表示「从来没压过」，比较时统一成 null。 */
    private fun normalized(upTo: Long?): Long? = upTo?.takeIf { it > 0L }
}
