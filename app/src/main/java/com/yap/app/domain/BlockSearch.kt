package com.yap.app.domain

import java.util.PriorityQueue

/**
 * 会话历史的「逐块扫描 + 键集游标」检索（规范：`spec/messages-blocks/README.md` §4）。
 *
 * ## 为什么不能再有候选上限
 *
 * 0.10.2 的实现是「一条 `LIKE` 全表粗筛 + `LIMIT 500` + 内存精排」；命中超过 500 条时，
 * 更早的那些**永远不会出现**（界面写「只显示最近 N 条」）。在几个月的长历史里，
 * 搜一个常见词（「晚安」「今天」）命中上千条是正常的，于是「搜不到自己记得的那句话」
 * 变成了一个解释不清的缺陷。
 *
 * 现在改成：**枚举块 → 一块一块读 → 命中就参与排序**，不设候选池，也不需要 offset。
 *
 * ## 游标为什么是 (matchedTermCount, relevance, createdAt, id)
 *
 * 这和 [SearchRanking] 的排序键完全相同。翻下一页时重新走一遍块（磁盘顺序读），
 * 只保留「严格排在游标之后」的行，再用一个**固定大小的堆**取这一页最靠前的 `limit + 1` 条：
 *
 * - 结果集完整：不会因为候选池上限漏掉任何一条；
 * - 顺序稳定：翻页期间新增消息只会被排除（它排在游标之前）或落到后面，不会重复、不会乱序；
 * - 峰值内存 ≈ 一块 + 一页 + frontier（堆里最多 `limit + 1` 条），与会话总长度无关。
 *
 * ## 这个文件的边界
 *
 * 只有纯逻辑（游标比较 + 遍历/选择），数据源是 [BlockScanSource]，生产实现是 Room，
 * 单测用假实现就能断言「每次只读一块」「>500 命中能翻完」「过滤跨块」。
 */
object BlockSearch {

    /**
     * 检索游标：与排序键一一对应。
     *
     * 数值类型刻意与 [SearchRanking] 内部一致（`matchedTermCount: Int`、`relevance: Double`），
     * 这样「游标之后」的判断和 `SearchRanking.rank` 的 `sortedWith` 是同一套比较。
     */
    data class Cursor(
        val matchedTermCount: Int,
        val relevance: Double,
        val createdAt: Long,
        val messageId: String
    )

    /** 一页里的命中：排序键 + 数据源自己的行（生产实现里就是展示用的投影行）。 */
    data class Hit<T>(
        val input: RankInput,
        val payload: T,
        val matchedTermCount: Int,
        val relevance: Double
    ) {
        fun cursor(): Cursor = Cursor(matchedTermCount, relevance, input.createdAt, input.id)
    }

    /** 一页的检索结果。 */
    data class Page<T>(
        val hits: List<Hit<T>>,
        /** 下一页的游标；没有下一页时为 null。 */
        val nextCursor: Cursor?,
        /** 游标之后还能取到下一批（由「堆里还剩不下一页的量」决定，不是由总数决定）。 */
        val hasMore: Boolean,
        /** 这次扫描一共命中多少条（**不受页大小、不受 500 上限影响**，界面用它显示「共 N 条」）。 */
        val totalMatches: Int,
        /** 这次扫描读了多少块（用于证明「逐块」而不是「一把梭」）。 */
        val scannedBlocks: Int
    )

    /** 一块的定位键。 */
    data class BlockKey(val sessionId: String, val blockIndex: Int)

    /**
     * 检索的数据源：生产实现每条 SQL 只读一块；单测用假实现断言调用形状。
     */
    interface BlockScanSource<T> {
        /** 枚举（已按人格/时间过滤的）会话的块，按 `(sessionId, blockIndex)` 升序。 */
        suspend fun blocks(filter: SearchFilter): List<BlockKey>

        /**
         * 读**一块**的消息（一条 SQL 只读一块）。
         *
         * 返回 `RankInput + 展示用 payload`；块内行按 `(createdAt, id)` 升序即可，
         * 排序由本引擎负责（不依赖 SQL 的 ORDER BY 是否正确）。
         */
        suspend fun messages(block: BlockKey): List<Pair<RankInput, T>>
    }

    /**
     * 排序比较：**负数表示 [a] 排在 [b] 前面**。
     *
     * 与 `SearchRanking.rank` 的 `compareByDescending(terms).thenByDescending(relevance)
     * .thenByDescending(createdAt).thenBy(id)` 逐项对应，所以两边永远不会给出不同顺序。
     */
    fun compare(a: Cursor, b: Cursor): Int {
        if (a.matchedTermCount != b.matchedTermCount) {
            return b.matchedTermCount.compareTo(a.matchedTermCount)
        }
        if (a.relevance != b.relevance) {
            return b.relevance.compareTo(a.relevance)
        }
        if (a.createdAt != b.createdAt) {
            return b.createdAt.compareTo(a.createdAt)
        }
        return a.messageId.compareTo(b.messageId)
    }

    /** 这一行是否严格排在游标之后（下一页只收它）。 */
    fun sortsAfter(hit: Cursor, cursor: Cursor): Boolean = compare(hit, cursor) > 0

    /**
     * 取一页。
     *
     * @param query 已 trim 的关键词（空串返回空页，不扫库）
     * @param limit 一页多少条
     * @param cursor 上一页最后一条的游标；首页传 null
     */
    suspend fun <T> page(
        source: BlockScanSource<T>,
        query: String,
        filter: SearchFilter = SearchFilter(),
        limit: Int = 30,
        cursor: Cursor? = null
    ): Page<T> {
        val text = query.trim()
        val safeLimit = limit.coerceIn(1, 200)
        if (text.isEmpty()) return Page(emptyList(), null, hasMore = false, totalMatches = 0, scannedBlocks = 0)

        // 最差的那条留在堆顶：堆里最多 safeLimit + 1 条（多出来那一条只用来判断「还有没有下一页」）。
        val keep = safeLimit + 1
        val heap = PriorityQueue<Hit<T>>(keep + 1) { left, right -> compare(right.cursor(), left.cursor()) }
        var total = 0
        var scanned = 0

        for (block in source.blocks(filter)) {
            scanned++
            // 一块读完就丢掉：循环变量里只留这一块的行，堆里只有页大小。
            val rows = source.messages(block)
            for ((input, payload) in rows) {
                // 过滤在命中判定**之前**做：SQL 已经过滤过一次，这里再守一遍，
                // 免得将来 SQL 改坏了把别人的消息露出来。
                if (!filter.accepts(input)) continue
                val terms = SearchRanking.matchedTerms(text, input.content)
                val contains = input.content.indexOf(text, ignoreCase = true) >= 0
                if (terms <= 0 && !contains) continue
                total++
                val hit = Hit(
                    input = input,
                    payload = payload,
                    matchedTermCount = terms,
                    relevance = ChineseTerms.relevance(text, input.content)
                )
                if (cursor != null && !sortsAfter(hit.cursor(), cursor)) continue
                heap.add(hit)
                if (heap.size > keep) heap.poll()
            }
        }

        val ordered = heap.toList().sortedWith { left, right -> compare(left.cursor(), right.cursor()) }
        val hasMore = ordered.size > safeLimit
        val hits = if (hasMore) ordered.take(safeLimit) else ordered
        return Page(
            hits = hits,
            nextCursor = if (hasMore) hits.lastOrNull()?.cursor() else null,
            hasMore = hasMore,
            totalMatches = total,
            scannedBlocks = scanned
        )
    }
}
