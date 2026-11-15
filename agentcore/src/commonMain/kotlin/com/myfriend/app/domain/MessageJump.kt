package com.myfriend.app.domain

/**
 * 「从检索结果跳到很久以前的那条消息」要读多少条（审计 P2-13）。
 *
 * ## 老做法为什么是 O(n²)
 *
 * 以前 `cursorContainingMessage` 从最新一页开始用 `pageBefore` 一页页往回翻（最多 200 页 = 12000 条），
 * 每翻一页都把聊天页的窗口起点往前挪一次；而窗口是「从起点到最新」的连续区间，
 * 于是第 k 页要重读 60×k 行，合计约 120 万行读取/分配——这正是 0.10.1 引入分页要避免的峰值，
 * 只是换了一个入口。
 *
 * ## 现在的做法
 *
 * 1. 先只问两个数字：这段会话一共几条（`COUNT(*)`）、目标之前有几条（`COUNT(*) WHERE (createdAt,id) < 目标`）。
 *    这一步不 materialize 任何行；
 * 2. 窗口起点对齐到「目标所在页的前 [CONTEXT_PAGES] 页」，窗口长度固定不超过
 *    [MAX_WINDOW_PAGES] 页 —— 于是**一次定位最多读 [MAX_WINDOW_PAGES] × 每页条数行**；
 * 3. 目标之后还有更新的消息时，聊天页底部给一个「更新的消息」入口，回到最新一屏即可，
 *    不需要把「目标 → 最新」整段读进内存。
 *
 * 这里只有算术，单测直接钉住「窗口长度有上界」「目标一定在窗口里」「目标就在首屏时不用挪窗口」。
 */
object MessageJump {

    /** 目标之上带几页上下文：一页足够看清「这段话在讲什么」。 */
    const val CONTEXT_PAGES = 1

    /**
     * 一次定位的窗口最多几页（默认每页 60 条 → 240 条）。
     *
     * 这是**读取条数的上界**，不是「能看多少历史」：往上还能继续翻页，往下回到最新一屏即可。
     */
    const val MAX_WINDOW_PAGES = 4

    /**
     * 一次定位的读取计划。
     *
     * @param targetInLatestPage 目标已经在首屏窗口里：不用挪窗口（返回 null 让调用方保持现状）
     * @param contextRows 目标之上带多少条上下文（用于算窗口起点）
     * @param windowRows 这次窗口最多读多少条（`≤ MAX_WINDOW_PAGES × pageSize`）
     * @param hasMoreOlder 窗口起点之前还有更早的消息（界面上「加载更早」该亮）
     * @param hasMoreNewer 窗口末尾之后还有更新的消息（界面上「更新的消息」该亮）
     */
    data class Plan(
        val targetInLatestPage: Boolean,
        val contextRows: Int,
        val windowRows: Int,
        val hasMoreOlder: Boolean,
        val hasMoreNewer: Boolean
    )

    /**
     * 算出一次定位的窗口计划。
     *
     * @param total 会话里的消息总数（`COUNT(*)`）
     * @param beforeTarget 稳定序 `(createdAt, id)` 下严格早于目标的消息数
     * @param pageSize 一页多少条（与聊天页分页一致）
     */
    fun plan(total: Int, beforeTarget: Int, pageSize: Int = MessagePaging.PAGE_SIZE): Plan {
        val safePage = if (pageSize <= 0) MessagePaging.PAGE_SIZE else pageSize
        val safeTotal = total.coerceAtLeast(0)
        val before = beforeTarget.coerceIn(0, safeTotal)

        // 首屏窗口是「最近一页」：目标落在这一页里就不需要动游标。
        val latestPageStart = (safeTotal - safePage).coerceAtLeast(0)
        if (safeTotal > 0 && before >= latestPageStart) {
            return Plan(
                targetInLatestPage = true,
                contextRows = 0,
                windowRows = safeTotal - latestPageStart,
                hasMoreOlder = latestPageStart > 0,
                hasMoreNewer = false
            )
        }

        val contextRows = minOf(CONTEXT_PAGES * safePage, before)
        val startIndex = before - contextRows
        val windowRows = minOf(safeTotal - startIndex, MAX_WINDOW_PAGES * safePage)
        return Plan(
            targetInLatestPage = false,
            contextRows = contextRows,
            windowRows = windowRows,
            hasMoreOlder = startIndex > 0,
            hasMoreNewer = startIndex + windowRows < safeTotal
        )
    }

    /** 窗口读取条数的上界（给单测与报告一个明确数字）。 */
    fun maxWindowRows(pageSize: Int = MessagePaging.PAGE_SIZE): Int =
        MAX_WINDOW_PAGES * (if (pageSize <= 0) MessagePaging.PAGE_SIZE else pageSize)
}
