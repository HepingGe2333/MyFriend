package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMemoryTest {

    @Test
    fun `短期每五条或计划变化时更新`() {
        assertNull(SessionMemoryPolicy.nextShortCount(4, 0))
        assertEquals(5, SessionMemoryPolicy.nextShortCount(5, 0))
        assertNull(SessionMemoryPolicy.nextShortCount(9, 5))
        assertEquals(12, SessionMemoryPolicy.nextShortCount(12, 5))
        assertTrue(SessionMemoryPolicy.shouldUpdate(6, 0, 5))
        // 计划变化时不等到第 5 条：这一条就要更新。
        assertTrue(SessionMemoryPolicy.shouldUpdateShort(2, 0, planChanged = true))
        assertFalse("没有新消息时计划变化也不触发", SessionMemoryPolicy.shouldUpdateShort(2, 2, planChanged = true))
        assertFalse(SessionMemoryPolicy.shouldUpdateShort(2, 0, planChanged = false))
    }

    @Test
    fun `长期在十五到四十轮之间由模型决定 四十轮强制`() {
        // 15 轮前不问。
        assertEquals(SessionMemoryPolicy.LongDecision.SKIP, SessionMemoryPolicy.longDecision(14, 0))
        // 15 轮起问模型（它可以回 NO_UPDATE）。
        assertEquals(SessionMemoryPolicy.LongDecision.ASK_MODEL, SessionMemoryPolicy.longDecision(15, 0))
        assertEquals(SessionMemoryPolicy.LongDecision.ASK_MODEL, SessionMemoryPolicy.longDecision(39, 0))
        // 40 轮强制更新。
        assertEquals(SessionMemoryPolicy.LongDecision.FORCE, SessionMemoryPolicy.longDecision(40, 0))
        // 上次在 20 轮更新过：再有 15 轮才问，25 轮不问。
        assertEquals(SessionMemoryPolicy.LongDecision.SKIP, SessionMemoryPolicy.longDecision(25, 20))
        assertEquals(SessionMemoryPolicy.LongDecision.ASK_MODEL, SessionMemoryPolicy.longDecision(35, 20))
    }

    @Test
    fun `计划变化的措辞只认已经在商量具体安排的信号`() {
        assertTrue(SessionMemoryPolicy.planChanged(listOf("那明天下午三点吧")))
        assertTrue(SessionMemoryPolicy.planChanged(listOf("我们约好周末去看房子")))
        assertFalse(SessionMemoryPolicy.planChanged(listOf("今天天气不错")))
        assertFalse(SessionMemoryPolicy.planChanged(emptyList()))
    }

    @Test
    fun `摘要长度上限与清洗`() {
        assertEquals(200, SessionMemoryPolicy.truncate("字".repeat(300), 200).length)
        assertEquals(500, SessionMemoryPolicy.truncate("字".repeat(800), 500).length)
        assertEquals("今天聊了面试", SessionMemoryPolicy.sanitizeSummary("摘要：今天聊了面试", 200))
        assertNull(SessionMemoryPolicy.sanitizeSummary(" ", 200))
    }

    @Test
    fun `备忘录用共享标题 长期在前 短期在后 且空时不注入`() {
        assertEquals("", SessionMemoryPolicy.promptBlock(null, null))
        val block = SessionMemoryPolicy.promptBlock("最近很好", "更早的事")
        // 0.15：标题统一来自 MemoryContract.Injection（# 你的备忘录 / ## 长期记忆 / ## 短期记忆）。
        assertTrue(block.contains(MemoryContract.Injection.TITLE))
        assertTrue(block.contains(MemoryContract.Injection.LONG_TERM_TITLE))
        assertTrue(block.contains(MemoryContract.Injection.SHORT_TERM_TITLE))
        assertTrue("长期必须排在短期前面", block.indexOf("更早的事") < block.indexOf("最近很好"))
    }
}
