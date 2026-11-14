package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户气泡「已读」的显示条件（规格 §2.3），以及 0.11.0 的 O(1) 判定口径（审计 P2-10）。 */
class ReadReceiptTest {

    @Test
    fun `看到了且这一轮没有回复 才显示已读`() {
        assertTrue(ReadReceipt.shouldShow(readAt = 100L, hasAssistantAfter = false))
    }

    @Test
    fun `还没看到 不显示`() {
        assertFalse(ReadReceipt.shouldShow(readAt = null, hasAssistantAfter = false))
        assertFalse(ReadReceipt.shouldShow(readAt = 0L, hasAssistantAfter = false))
    }

    @Test
    fun `已经回了 就不再挂已读`() {
        assertFalse(ReadReceipt.shouldShow(readAt = 100L, hasAssistantAfter = true))
    }

    @Test
    fun `一次算好的最后一条助手消息 与逐条扫描等价`() {
        // 窗口里各种形态：普通助手、错误助手、用户消息、同毫秒。
        data class Row(val role: String, val status: String, val createdAt: Long)
        val rows = listOf(
            Row("USER", "COMPLETE", 10L),
            Row("ASSISTANT", "COMPLETE", 12L),
            Row("USER", "COMPLETE", 12L),
            Row("ASSISTANT", "ERROR", 15L),
            Row("USER", "COMPLETE", 20L)
        )
        val lastAssistantAt = ReadReceipt.latestAssistantAt(
            rows.filter { it.role == "ASSISTANT" && it.status != "ERROR" }.map { it.createdAt }
        )
        assertEquals(12L, lastAssistantAt)

        rows.filter { it.role == "USER" }.forEach { user ->
            val scanned = rows.any {
                it.role == "ASSISTANT" && it.status != "ERROR" && it.createdAt > user.createdAt
            }
            assertEquals(
                "第 ${user.createdAt} 条用户消息的已读判定必须与逐条扫描一致",
                ReadReceipt.shouldShow(readAt = user.createdAt, hasAssistantAfter = scanned),
                ReadReceipt.shouldShow(
                    readAt = user.createdAt,
                    userCreatedAt = user.createdAt,
                    lastAssistantAt = lastAssistantAt
                )
            )
        }
    }

    @Test
    fun `没有助手消息时 最后一条时间戳为空`() {
        assertNull(ReadReceipt.latestAssistantAt(emptyList()))
        assertEquals(7L, ReadReceipt.latestAssistantAt(listOf(3L, 7L, 5L)))
    }
}
