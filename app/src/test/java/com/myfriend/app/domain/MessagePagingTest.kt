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
    fun `首屏之后窗口条数变化不再影响 hasMore`() {
        // 已经加载过更早的一页，观察结果变成「整个已加载区间」：条数从 60 变 90 不代表还有更早的。
        val loaded = MessagePaging.State(initialized = true, hasMoreOlder = false)
        assertEquals(loaded, MessagePaging.onFirstWindow(loaded, 90))
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
