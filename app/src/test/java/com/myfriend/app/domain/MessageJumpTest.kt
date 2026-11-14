package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「跳到很久以前那条消息」的读取上界（审计 P2-13）。
 *
 * 老实现是「从最新一页往回翻，每翻一页窗口重查一次」= O(页数²) 行读取。
 * 现在先算两个 COUNT，再按页对齐取一个**有界**窗口：这里钉住「上界」与「目标必在窗口内」。
 */
class MessageJumpTest {

    private val page = MessagePaging.PAGE_SIZE

    @Test
    fun `目标在很旧的位置时 读取条数仍有上界`() {
        // 一万条消息，目标是第 100 条（很旧）。
        val plan = MessageJump.plan(total = 10_000, beforeTarget = 100, pageSize = page)
        assertFalse(plan.targetInLatestPage)
        assertEquals("窗口最多四页", MessageJump.maxWindowRows(page), plan.windowRows)
        assertTrue(
            "一次定位读 ${plan.windowRows} 条，必须远小于全量 ${10_000}",
            plan.windowRows <= MessageJump.maxWindowRows(page) && plan.windowRows < 10_000
        )
        // 目标一定落在窗口里：起点 = 100 - 一页上下文。
        val start = 100 - plan.contextRows
        assertTrue("目标下标 ${100} 不在窗口 [$start, ${start + plan.windowRows})", start <= 100 && 100 < start + plan.windowRows)
        assertTrue("上面还有更早的", plan.hasMoreOlder)
        assertTrue("下面还有更新的", plan.hasMoreNewer)
    }

    @Test
    fun `会话很短时窗口就是全部 且不报有更新的`() {
        val plan = MessageJump.plan(total = 90, beforeTarget = 10, pageSize = page)
        assertFalse(plan.targetInLatestPage)
        assertEquals("目标之前只有 10 条：上下文就是这 10 条", 10, plan.contextRows)
        assertEquals("窗口从会话第一条开始，一共 90 条", 90, plan.windowRows)
        assertFalse(plan.hasMoreOlder)
        assertFalse(plan.hasMoreNewer)
    }

    @Test
    fun `目标就在首屏窗口里时不需要挪窗口`() {
        val plan = MessageJump.plan(total = 500, beforeTarget = 480, pageSize = page)
        assertTrue("最近一页里的消息不用跳", plan.targetInLatestPage)
        assertFalse(plan.hasMoreNewer)
        assertTrue(plan.hasMoreOlder)
    }

    @Test
    fun `目标是第一条时上下文为零`() {
        val plan = MessageJump.plan(total = 5000, beforeTarget = 0, pageSize = page)
        assertFalse(plan.targetInLatestPage)
        assertEquals(0, plan.contextRows)
        assertFalse(plan.hasMoreOlder)
        assertEquals(MessageJump.maxWindowRows(page), plan.windowRows)
        assertTrue(plan.hasMoreNewer)
    }

    @Test
    fun `入参越界也不会算出负窗口`() {
        val plan = MessageJump.plan(total = -5, beforeTarget = -3, pageSize = 0)
        assertTrue(plan.windowRows >= 0)
        assertTrue(plan.contextRows >= 0)
        val weird = MessageJump.plan(total = 100, beforeTarget = 999, pageSize = page)
        assertTrue(weird.windowRows >= 0 && weird.windowRows <= 100)
    }

    @Test
    fun `窗口上界与分页常量挂钩`() {
        assertEquals(MessageJump.MAX_WINDOW_PAGES * page, MessageJump.maxWindowRows())
        assertTrue("上界不该超过四页", MessageJump.MAX_WINDOW_PAGES == 4)
    }
}
