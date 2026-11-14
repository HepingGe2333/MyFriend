package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台 worker 的「最近 N 条」窗口：语义必须和 `.filter(keep).takeLast(n)` 完全一致，
 * 只是把「整段历史」换成了 SQL 的 `LIMIT`。
 */
class MessageWindowsTest {

    @Test
    fun `新的在前进 旧的在前出`() {
        val newestFirst = listOf("m5", "m4", "m3", "m2", "m1")
        assertEquals(listOf("m4", "m5"), MessageWindows.lastN(newestFirst, 2))
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), MessageWindows.lastN(newestFirst, 10))
    }

    @Test
    fun `先过滤再取窗口`() {
        val rows = listOf("a1", "u4", "a3", "u2", "system", "u1")
        // 只留 u 开头：新→旧为 u4、u2、u1；取最近 2 条 = u4、u2，再翻成时间正序 u2、u4。
        assertEquals(
            listOf("u2", "u4"),
            MessageWindows.lastN(rows, 2) { it.startsWith("u") }
        )
    }

    @Test
    fun `limit 非正或空输入返回空`() {
        assertTrue(MessageWindows.lastN(listOf("m2", "m1"), 0).isEmpty())
        assertTrue(MessageWindows.lastN(listOf("m2", "m1"), -1).isEmpty())
        assertTrue(MessageWindows.lastN(emptyList<String>(), 3).isEmpty())
    }

    @Test
    fun `过滤后不足 limit 也只返回实际条数`() {
        val rows = listOf("x", "u2", "x", "u1")
        assertEquals(listOf("u1", "u2"), MessageWindows.lastN(rows, 10) { it.startsWith("u") })
    }

    @Test
    fun `窗口常量是有界的小值`() {
        assertTrue(MessageWindows.WORKER_RECENT_ROWS in 10..200)
        assertTrue(MessageWindows.WORKER_HISTORY_ROWS in 10..200)
        assertTrue(MessageWindows.EXTRACTION_ROWS in 10..200)
        assertTrue(MessageWindows.IDLE_LOOKBACK_ROWS in 10..500)
    }
}
