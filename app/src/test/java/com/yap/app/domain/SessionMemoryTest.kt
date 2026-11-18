package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMemoryTest {

    @Test
    fun `短期每五条 长期每三十条更新且不重复`() {
        assertNull(SessionMemoryPolicy.nextShortCount(4, 0))
        assertEquals(5, SessionMemoryPolicy.nextShortCount(5, 0))
        assertNull(SessionMemoryPolicy.nextShortCount(9, 5))
        assertEquals(12, SessionMemoryPolicy.nextShortCount(12, 5))
        assertTrue(SessionMemoryPolicy.shouldUpdate(6, 0, 5))
        assertNull(SessionMemoryPolicy.nextLongCount(29, 0))
        assertEquals(30, SessionMemoryPolicy.nextLongCount(30, 0))
        assertNull(SessionMemoryPolicy.nextLongCount(31, 30))
    }

    @Test
    fun `摘要长度上限与清洗`() {
        assertEquals(200, SessionMemoryPolicy.truncate("字".repeat(300), 200).length)
        assertEquals(500, SessionMemoryPolicy.truncate("字".repeat(800), 500).length)
        assertEquals("今天聊了面试", SessionMemoryPolicy.sanitizeSummary("摘要：今天聊了面试", 200))
        assertNull(SessionMemoryPolicy.sanitizeSummary(" ", 200))
    }

    @Test
    fun `备忘录短期在前 长期在后 且空时不注入`() {
        assertEquals("", SessionMemoryPolicy.promptBlock(null, null))
        val block = SessionMemoryPolicy.promptBlock("最近很好", "更早的事")
        assertTrue(block.contains("## 你的备忘录"))
        assertTrue("短期必须排在长期前面", block.indexOf("最近很好") < block.indexOf("更早的事"))
    }
}
