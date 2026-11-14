package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天页分页状态机的边界。
 *
 * 分页本身靠 SQL `LIMIT`（真机/DAO 层跑），这里把「什么时候还能再要一页」的逻辑
 * 单独钉住：首页 60、不足 60、恰好 60、加载到底、并发触发只加载一次。
 */
class MessagePagingTest {

    private val page = MessagePaging.PAGE_SIZE

    @Test
    fun `一页是 60 条`() {
        assertEquals(60, page)
        assertEquals(60, MessagePaging.pageSize)
    }

    @Test
    fun `首屏恰好一页 认为还有更早的`() {
        val state = MessagePaging.onFirstWindow(MessagePaging.State(), page)
        assertTrue(state.initialized)
        assertTrue(state.hasMoreOlder)
        assertTrue(state.canLoadOlder)
    }

    @Test
    fun `首屏不足一页 就是到底了`() {
        val state = MessagePaging.onFirstWindow(MessagePaging.State(), page - 1)
        assertFalse("只有 59 条说明整段会话都在这了", state.hasMoreOlder)
        assertFalse(state.canLoadOlder)
    }

    @Test
    fun `空会话首屏直接到底`() {
        val state = MessagePaging.onFirstWindow(MessagePaging.State(), 0)
        assertFalse(state.hasMoreOlder)
        assertTrue(state.initialized)
    }

    @Test
    fun `滑窗时首屏的 hasMoreOlder 每次都会重算`() {
        // 场景：会话原来只有 30 条（打开时判「到底了」），聊到 90 条之后最早的 30 条滑出了 60 条窗口。
        // 如果只在第一次算，用户就永远看不到「上面还有更早的消息」（审计 P2-14）。
        val first = MessagePaging.onFirstWindow(MessagePaging.State(), 30)
        assertFalse(first.hasMoreOlder)
        val slid = MessagePaging.onFirstWindow(first, page)
        assertTrue("窗口滑满一页就必须提示上面还有更早的", slid.hasMoreOlder)
        assertTrue(slid.canLoadOlder)
    }

    @Test
    fun `定位到旧消息后窗口有上界 并给出更新的入口`() {
        val window = MessagePaging.Window(
            start = MessagePaging.Cursor(1_000L, "a"),
            rows = MessageJump.maxWindowRows(),
            hasMoreOlder = true,
            hasMoreNewer = true
        )
        val state = MessagePaging.onJumped(MessagePaging.State(), window)
        assertTrue(state.initialized)
        assertTrue("上面还有更早的", state.hasMoreOlder)
        assertTrue("下面还有更新的", state.hasMoreNewer)
        assertFalse(state.loadingOlder)
    }

    @Test
    fun `回到最新一屏时状态整体复位`() {
        val state = MessagePaging.onJumped(
            MessagePaging.State(),
            MessagePaging.Window(MessagePaging.Cursor(1L, "a"), 240, hasMoreOlder = true, hasMoreNewer = true)
        )
        val latest = MessagePaging.onLatest(state)
        assertEquals(MessagePaging.State(), latest)
        assertFalse(latest.hasMoreNewer)
    }

    @Test
    fun `加载更早取满一页 继续保留还有更早`() {
        val (loading, started) = MessagePaging.beginLoadOlder(MessagePaging.State())
        assertTrue(started)
        assertTrue(loading.loadingOlder)
        assertFalse("加载中不允许再次触发", loading.canLoadOlder)

        val done = MessagePaging.onOlderPage(loading, page)
        assertFalse(done.loadingOlder)
        assertTrue(done.hasMoreOlder)
    }

    @Test
    fun `加载更早不足一页 就是最早一条了`() {
        val state = MessagePaging.State()
        val done = MessagePaging.onOlderPage(state, page - 1)
        assertFalse(done.hasMoreOlder)
        assertFalse(done.canLoadOlder)
    }

    @Test
    fun `恰好一页之后再来一页空 说明刚好到底`() {
        // 一共 120 条：首屏 60 → 还有更早；再要一页拿到 60 → 仍认为可能还有；再要一页拿到 0 → 到底。
        var state = MessagePaging.onFirstWindow(MessagePaging.State(), page)
        assertTrue(state.hasMoreOlder)

        state = MessagePaging.onOlderPage(state, page)
        assertTrue(state.hasMoreOlder)

        state = MessagePaging.onOlderPage(state, 0)
        assertFalse("拿到 0 条就是最早一条了", state.hasMoreOlder)
    }

    @Test
    fun `加载中重复触发只算一次`() {
        val first = MessagePaging.beginLoadOlder(MessagePaging.State())
        assertTrue(first.second)

        // 快速滚顶会连着触发：第二次开始必须被挡掉，否则同一页会被查两遍、插两遍。
        val second = MessagePaging.beginLoadOlder(first.first)
        assertFalse(second.second)
        assertEquals(first.first, second.first)

        // 第三、第四次也一样。
        assertFalse(MessagePaging.beginLoadOlder(second.first).second)
    }

    @Test
    fun `到底之后不再触发查询`() {
        val done = MessagePaging.State(initialized = true, hasMoreOlder = false)
        val (state, started) = MessagePaging.beginLoadOlder(done)
        assertFalse(started)
        assertFalse(state.loadingOlder)
    }

    @Test
    fun `查询失败收掉转圈 但不把失败当成到底`() {
        val (loading, _) = MessagePaging.beginLoadOlder(MessagePaging.State())
        val failed = MessagePaging.onLoadFailed(loading)
        assertFalse(failed.loadingOlder)
        assertTrue("失败了还能再试", failed.canLoadOlder)
    }

    // ------------------------------------------------------------------ 定位窗口的「更新的消息」（审计 P1-1）

    @Test
    fun `窗口末尾之后还有消息 就亮更新的入口`() {
        val end = MessagePaging.Cursor(createdAt = 100L, id = "m100")
        val latest = MessagePaging.Cursor(createdAt = 200L, id = "m200")
        assertTrue(MessagePaging.isNewer(latest, end))
        val state = MessagePaging.onWindowRows(MessagePaging.State(initialized = true), latest, end)
        assertTrue("后台/心跳写入也要让「回到最新」入口重新出现", state.hasMoreNewer)
    }

    @Test
    fun `窗口已经到最新 就不亮入口`() {
        val latest = MessagePaging.Cursor(createdAt = 200L, id = "m200")
        val state = MessagePaging.onWindowRows(MessagePaging.State(), latest, latest)
        assertFalse(state.hasMoreNewer)
        assertFalse(MessagePaging.isNewer(latest, latest))
    }

    @Test
    fun `同一毫秒也要按稳定序比较`() {
        val end = MessagePaging.Cursor(createdAt = 100L, id = "b")
        val newer = MessagePaging.Cursor(createdAt = 100L, id = "c")
        val older = MessagePaging.Cursor(createdAt = 100L, id = "a")
        assertTrue("同毫秒时 id 更大的更晚", MessagePaging.isNewer(newer, end))
        assertFalse("同毫秒时 id 更小的更早", MessagePaging.isNewer(older, end))
    }

    @Test
    fun `没有消息时不算更新`() {
        assertFalse(MessagePaging.isNewer(null, MessagePaging.Cursor(1L, "a")))
        assertFalse(MessagePaging.isNewer(MessagePaging.Cursor(1L, "a"), null))
    }

    @Test
    fun `定位时计划说没有更新的 但新消息到达后会重算`() {
        // 审计 P1-1 的真实场景：200 条会话跳到第 100 条，窗口 40..199、hasMoreNewer=false
        //（MessageJump.plan 的计划值）。之后来一条新消息，界面必须重新出现「回到最新」。
        val plan = MessageJump.plan(total = 200, beforeTarget = 100, pageSize = page)
        assertFalse("计划值：定位那一刻窗口确实到末尾", plan.hasMoreNewer)
        val jumped = MessagePaging.onJumped(
            MessagePaging.State(),
            MessagePaging.Window(
                start = MessagePaging.Cursor(40L, "m40"),
                rows = plan.windowRows,
                hasMoreOlder = plan.hasMoreOlder,
                hasMoreNewer = plan.hasMoreNewer
            )
        )
        assertFalse(jumped.hasMoreNewer)
        val afterNewMessage = MessagePaging.onWindowRows(
            jumped,
            latest = MessagePaging.Cursor(201L, "m201"),
            windowEnd = MessagePaging.Cursor(199L, "m199")
        )
        assertTrue("来了新消息之后必须能回到最新", afterNewMessage.hasMoreNewer)
        assertEquals("hasMoreOlder 不该被这次重算改掉", jumped.hasMoreOlder, afterNewMessage.hasMoreOlder)
    }

    @Test
    fun `游标取已加载区间里最早的一条`() {
        data class Row(val at: Long, val id: String)
        val rows = listOf(Row(10, "a"), Row(20, "b"), Row(30, "c"))
        assertEquals(
            MessagePaging.Cursor(10, "a"),
            MessagePaging.oldestCursor(rows, { it.at }, { it.id })
        )
        assertNull(MessagePaging.oldestCursor(emptyList<Row>(), { it.at }, { it.id }))
    }
}
