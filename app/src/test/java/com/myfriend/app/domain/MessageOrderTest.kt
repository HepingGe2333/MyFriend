package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内消息的稳定顺序（审计 P2-7）：
 * 时间戳严格递增，排序/删除一律按 `(createdAt, id)`。
 */
class MessageOrderTest {

    @Test
    fun `没有历史消息时直接用 now`() {
        assertEquals(1_000L, MessageOrder.nextTimestamp(null, 1_000L))
    }

    @Test
    fun `同一毫秒连写两条时后一条严格更大`() {
        val first = MessageOrder.nextTimestamp(null, 5_000L)
        val second = MessageOrder.nextTimestamp(first, 5_000L)
        assertEquals(5_000L, first)
        assertEquals(5_001L, second)
        assertTrue(MessageOrder.isAfter(second, "b", first, "a"))
    }

    @Test
    fun `时钟回拨也能兜住`() {
        assertEquals(9_001L, MessageOrder.nextTimestamp(9_000L, 1_000L))
    }

    @Test
    fun `同一毫秒按 id 决出先后 不再随机`() {
        assertTrue(MessageOrder.isAfter(100L, "b", 100L, "a"))
        assertFalse(MessageOrder.isAfter(100L, "a", 100L, "b"))
        assertEquals(0, MessageOrder.compare(100L, "a", 100L, "a"))
        assertTrue(MessageOrder.isAfter(101L, "a", 100L, "z"))
    }

    @Test
    fun `删除之后的语义就是稳定序更大`() {
        // 编辑 user 消息 (100, "m")：同毫秒的助手回复 (100, "z") 也算「之后」，必须一起删。
        assertTrue(MessageOrder.isAfter(100L, "z", 100L, "m"))
        // 被编辑的这条自己不算「之后」。
        assertFalse(MessageOrder.isAfter(100L, "m", 100L, "m"))
    }
}
