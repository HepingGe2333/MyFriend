package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话记忆 / 主题总结（0.15 上下文记忆 v2）：检索线索、注入标题、条数、每条字符上限。
 */
class MemoryContractTest {

    // ------------------------------------------------------------------ 设计数值

    @Test
    fun `summary limits match context memory v2`() {
        assertEquals(500, MemoryContract.Summary.LONG_TERM_MAX_CHARS)
        assertEquals(200, MemoryContract.Summary.SHORT_TERM_MAX_CHARS)
        assertEquals(120, MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS)
        assertEquals(12, MemoryContract.Summary.TOPIC_MAX_SEGMENTS)
        assertEquals(1_440, MemoryContract.Summary.TOPIC_MAX_CHARS)
        assertEquals(1_440, MemoryContract.TOPIC_SUMMARY_CHAR_BUDGET)
        assertEquals(5, MemoryContract.Summary.SHORT_UPDATE_EVERY_ROUNDS)
        assertEquals(15, MemoryContract.Summary.LONG_UPDATE_MIN_ROUNDS)
        assertEquals(40, MemoryContract.Summary.LONG_UPDATE_MAX_ROUNDS)
    }

    // ------------------------------------------------------------------ 检索线索

    @Test
    fun `retrieval query flattens whitespace and caps at 300 chars`() {
        assertEquals("你好 世界", MemoryContract.Retrieval.query("  你好\n世界  "))
        val long = "字".repeat(400)
        assertEquals(300, MemoryContract.Retrieval.query(long).length)
        assertEquals(300, long.length - 100)
    }

    @Test
    fun `retrieval query falls back when the message is blank`() {
        assertEquals("主题：加班", MemoryContract.Retrieval.query(null, fallback = "主题：加班"))
        assertEquals("主题：加班", MemoryContract.Retrieval.query("   ", fallback = " 主题：加班 "))
        assertEquals("", MemoryContract.Retrieval.query(null))
        assertEquals("", MemoryContract.Retrieval.query("", fallback = null))
        // 兜底线索也受 300 字上限约束。
        assertEquals(
            300,
            MemoryContract.Retrieval.query(null, fallback = "x".repeat(500)).length
        )
    }

    // ------------------------------------------------------------------ 长期记忆注入

    @Test
    fun `memory injection groups by drawer order and importance`() {
        val block = MemoryContract.Injection.memoryInjection(
            listOf(
                MemoryContract.MemoryItem("u-low", MemoryDrawer.USER, importance = 2),
                MemoryContract.MemoryItem("u-high", MemoryDrawer.USER, importance = 5),
                MemoryContract.MemoryItem("self", MemoryDrawer.SELF),
                MemoryContract.MemoryItem("event", MemoryDrawer.EVENT),
                MemoryContract.MemoryItem("   ", MemoryDrawer.USER)
            )
        )
        assertEquals("# 你的备忘录", block.lineSequence().first())
        assertEquals("## 长期记忆", block.lineSequence().drop(1).first())
        val userAt = block.indexOf("**关于对方**")
        val selfAt = block.indexOf("**你自己是什么样的人**")
        val eventAt = block.indexOf("**你们之间的经历与约定**")
        assertTrue(userAt in 0 until selfAt)
        assertTrue(selfAt in 0 until eventAt)
        // 同组内 importance 大的在前；空内容被丢掉。
        assertTrue(block.indexOf("u-high") < block.indexOf("u-low"))
        assertTrue(!block.contains("   "))
    }

    @Test
    fun `memory injection caps item count and per item chars`() {
        val many = (1..20).map { MemoryContract.MemoryItem("m$it") }
        val block = MemoryContract.Injection.memoryInjection(many)
        assertEquals(
            MemoryContract.Injection.MAX_ITEMS,
            block.lineSequence().count { it.startsWith("- ") }
        )

        val long = MemoryContract.Injection.itemLine(
            MemoryContract.MemoryItem("字".repeat(400))
        )
        // "- " + 300 字 + "…"
        assertEquals(2 + 300 + 1, long.length)
        assertTrue(long.endsWith("…"))
        // 空正文没有行。
        assertEquals("", MemoryContract.Injection.itemLine(MemoryContract.MemoryItem("   ")))
    }

    @Test
    fun `pinned memories carry the suffix`() {
        assertEquals(
            "- 生日是 3 月（对你很重要）",
            MemoryContract.Injection.itemLine(
                MemoryContract.MemoryItem("生日是 3 月", pinned = true)
            )
        )
        assertEquals(14, MemoryContract.Injection.MAX_ITEMS)
        assertEquals(300, MemoryContract.Injection.MAX_ITEM_CHARS)
        assertEquals("（对你很重要）", MemoryContract.Injection.PINNED_SUFFIX)
    }

    @Test
    fun `empty memory list produces no block`() {
        assertEquals("", MemoryContract.Injection.memoryInjection(emptyList()))
        assertEquals(
            "",
            MemoryContract.Injection.memoryInjection(
                listOf(MemoryContract.MemoryItem("  "), MemoryContract.MemoryItem("\n"))
            )
        )
    }

    @Test
    fun `drawer labels match the existing prompt wording`() {
        assertEquals("关于对方", MemoryContract.Injection.drawerLabel(MemoryDrawer.USER))
        assertEquals("你自己是什么样的人", MemoryContract.Injection.drawerLabel(MemoryDrawer.SELF))
        assertEquals("你们之间的经历与约定", MemoryContract.Injection.drawerLabel(MemoryDrawer.EVENT))
        assertEquals(
            listOf(MemoryDrawer.USER, MemoryDrawer.SELF, MemoryDrawer.EVENT),
            MemoryContract.Injection.DRAWER_ORDER
        )
    }

    // ------------------------------------------------------------------ 备忘录（短期/长期/主题）

    @Test
    fun `notes order is long then short then topics`() {
        val block = MemoryContract.Injection.notes(
            longTerm = "长期",
            shortTerm = "短期",
            topics = listOf(MemoryContract.TopicSegment("2026-11-18 晚", "加班与方案改版"))
        )
        assertEquals(
            "# 你的备忘录\n" +
                "## 长期记忆\n- 长期\n" +
                "## 短期记忆\n- 短期\n" +
                "## 对话主题\n- 2026-11-18 晚：加班与方案改版",
            block
        )
        // 0.15 同时保留旧标题常量，供迁移期对照。
        assertEquals("## 你的备忘录", MemoryContract.Injection.LEGACY_TITLE)
        assertEquals("## 你记得的事", MemoryContract.Injection.LEGACY_MEMORY_TITLE)
    }

    @Test
    fun `notes clip long and short summaries to their design limits`() {
        val block = MemoryContract.Injection.notes(
            longTerm = "L".repeat(600),
            shortTerm = "S".repeat(300)
        )
        assertTrue(block.contains("- " + "L".repeat(500) + "…"))
        assertTrue(block.contains("- " + "S".repeat(200) + "…"))
        assertTrue(!block.contains("L".repeat(501)))
        assertTrue(!block.contains("S".repeat(201)))
    }

    @Test
    fun `notes clip topic segments and keep at most twelve`() {
        val topic = MemoryContract.TopicSegment(
            timeLabel = "  2026-11-18 晚 ",
            summary = "字".repeat(200)
        )
        assertEquals(
            "- 2026-11-18 晚：" + "字".repeat(120) + "…",
            MemoryContract.Injection.topicLine(topic)
        )
        // 时间段为空时只写摘要；空摘要整行丢掉。
        assertEquals("- 只有摘要", MemoryContract.Injection.topicLine(MemoryContract.TopicSegment(summary = "只有摘要")))
        assertNull(MemoryContract.Injection.topicLine(MemoryContract.TopicSegment("时间", "  ")))

        val many = (1..20).map { MemoryContract.TopicSegment("t$it", "s$it") }
        val block = MemoryContract.Injection.notes(topics = many)
        assertEquals(
            MemoryContract.Summary.TOPIC_MAX_SEGMENTS,
            block.lineSequence().count { it.startsWith("- ") }
        )
        assertTrue(block.contains("- t12：s12"))
        assertTrue(!block.contains("- t13：s13"))
    }

    @Test
    fun `notes with all empty parts returns empty string`() {
        assertEquals("", MemoryContract.Injection.notes())
        assertEquals("", MemoryContract.Injection.notes(longTerm = " ", shortTerm = "\n"))
        assertEquals(
            "",
            MemoryContract.Injection.notes(topics = listOf(MemoryContract.TopicSegment("t", " ")))
        )
    }
}
