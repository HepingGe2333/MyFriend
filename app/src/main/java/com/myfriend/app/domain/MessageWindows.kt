package com.myfriend.app.domain

/**
 * 从「新的在前」的 Room 查询结果里取最近 N 条，返回时间正序。
 *
 * 为什么要这个工具：`getBySession()` 会把整段历史读进列表，只为了 `.takeLast(6)`。
 * 一段聊了几个月的对话可能有几千条消息（每条还带着 attachmentsJson / toolTraceJson），
 * 后台 worker 每次醒来都把它们全查出来、再扔掉 99%，既不划算也让内存峰值跟着历史长度涨。
 *
 * 现在统一改成 `recentBySession(limit)` + 本函数：SQL 只返回需要的行数，
 * 「新→旧」的取窗口/过滤/翻正序这条逻辑在这里只写一次，也能在普通 JVM 单测里钉死。
 *
 * 语义与 `.filter(keep).takeLast(limit)` 完全一致：
 * 先按时间从新到旧过滤，取前 [limit] 条，再翻成从旧到新。
 */
object MessageWindows {

    /** 后台判处境时最多看多少条：足够覆盖「上次说话 + 最近几句」，又不会跟着历史长度涨。 */
    const val WORKER_RECENT_ROWS = 40

    /** 主动唤醒 / 进化提案这类「只取最后 10 句」的场景，SQL 端同样留一点余量。 */
    const val WORKER_HISTORY_ROWS = 40

    /** 记忆抽取只取最近 20 句，SQL 端留 40 条余量。 */
    const val EXTRACTION_ROWS = 40

    /** 闲逛找话题只看最近几条用户消息，SQL 端留 120 条（含助手的回复）。 */
    const val IDLE_LOOKBACK_ROWS = 120

    fun <T> lastN(
        rowsNewestFirst: List<T>,
        limit: Int,
        keep: (T) -> Boolean = { true }
    ): List<T> {
        if (limit <= 0 || rowsNewestFirst.isEmpty()) return emptyList()
        val picked = ArrayList<T>(minOf(limit, rowsNewestFirst.size))
        for (row in rowsNewestFirst) {
            if (!keep(row)) continue
            picked += row
            if (picked.size == limit) break
        }
        picked.reverse()
        return picked
    }
}
