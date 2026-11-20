package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 0.15 对话主题总结（`docs/design/context-memory-v2.md`）：触发、解析、滚动、上限。
 */
class TopicSummaryTest {

    @Test
    fun `十五轮起问模型 话题切换立即更新`() {
        assertFalse(TopicSummary.shouldUpdate(userCount = 14, lastUpdatedCount = 0))
        assertTrue(TopicSummary.shouldUpdate(userCount = 15, lastUpdatedCount = 0))
        assertTrue("话题切换不等到 15 轮", TopicSummary.shouldUpdate(3, 1, topicChanged = true))
        assertFalse("没有新消息不重复滚", TopicSummary.shouldUpdate(1, 1, topicChanged = true))
        assertFalse(TopicSummary.shouldUpdate(0, 0))
    }

    @Test
    fun `四十轮到达必须更新`() {
        assertFalse(TopicSummary.isForced(39, 0))
        assertTrue(TopicSummary.isForced(40, 0))
        assertTrue(TopicSummary.isForced(60, 20))
    }

    @Test
    fun `话题切换只认明确说法`() {
        assertTrue(TopicSummary.isTopicSwitchMarker("对了，你上次说的那家店"))
        assertTrue(TopicSummary.isTopicSwitchMarker("换个话题吧"))
        assertTrue(TopicSummary.isTopicSwitchMarker("说点别的"))
        assertFalse(TopicSummary.isTopicSwitchMarker("今天很好"))
        assertFalse(TopicSummary.isTopicSwitchMarker(""))
        assertFalse(TopicSummary.isTopicSwitchMarker(null))
    }

    @Test
    fun `解析模型给的 JSON 数组`() {
        val parsed = TopicSummary.parse(
            """
            ```json
            [{"time":"2026-11-18 晚上","summary":"他换工作在犹豫，怕裸辞。"},
             {"time":"2026-11-19 下午","summary":"约了周末看房。"}]
            ```
            """.trimIndent()
        )
        assertEquals(2, parsed.size)
        assertEquals("2026-11-18 晚上", parsed[0].timeLabel)
        assertEquals("他换工作在犹豫，怕裸辞。", parsed[0].summary)
    }

    @Test
    fun `也认包装形状与单对象`() {
        val wrapped = TopicSummary.parse("""{"topics":[{"time":"11-18","summary":"聊了工作"}]}""")
        assertEquals(1, wrapped.size)
        val single = TopicSummary.parse("""{"time":"11-18","summary":"聊了工作"}""")
        assertEquals(1, single.size)
    }

    @Test
    fun `坏 JSON 返回空列表 不清空旧总结`() {
        assertTrue(TopicSummary.parse("").isEmpty())
        assertTrue(TopicSummary.parse("这不是 JSON").isEmpty())
        assertTrue(TopicSummary.parse("[{\"time\":\"x\"}]").isEmpty())
    }

    @Test
    fun `每段截到一百二十字 最多十二段 超出丢最旧`() {
        val many = (1..20).map { MemoryContract.TopicSegment("t$it", "字".repeat(200)) }
        val clean = TopicSummary.sanitize(many)
        assertEquals(12, clean.size)
        assertEquals("最后 12 段：从 t9 开始", "t9", clean.first().timeLabel)
        assertEquals(121, clean.first().summary.length)
        // 空段丢掉。
        assertEquals(1, TopicSummary.sanitize(listOf(MemoryContract.TopicSegment("", "  "), MemoryContract.TopicSegment("x", "ok"))).size)
    }

    @Test
    fun `同一时段并进最后一段 不同时段追加`() {
        val first = TopicSummary.append(emptyList(), "2026-11-18 晚上", "聊了工作")
        assertEquals(1, first.size)
        val merged = TopicSummary.append(first, "2026-11-18 晚上", "又聊了房子")
        assertEquals(1, merged.size)
        assertTrue(merged[0].summary.contains("聊了工作"))
        assertTrue(merged[0].summary.contains("又聊了房子"))
        val appended = TopicSummary.append(merged, "2026-11-19 下午", "聊了看房")
        assertEquals(2, appended.size)
    }

    @Test
    fun `时间段标签是可长期保存的绝对时间`() {
        val zone = ZoneId.of("Asia/Shanghai")
        // 2026-11-18 20:30 CST = 2026-11-18 12:30 UTC
        val at = 1_795_005_000_000L
        val label = TopicSummary.timeLabel(at, zone)
        assertTrue("带日期：$label", label.startsWith("2026-11-"))
        assertTrue("带时段：$label", label.endsWith("晚上") || label.endsWith("下午"))
        assertEquals("", TopicSummary.timeLabel(0L, zone))
    }

    @Test
    fun `编码与解析往返`() {
        val segments = listOf(
            MemoryContract.TopicSegment("2026-11-18 晚上", "他说\"不想干了\"，又改口"),
            MemoryContract.TopicSegment("", "第二段")
        )
        val encoded = TopicSummary.encode(segments)
        val decoded = TopicSummary.parse(encoded)
        assertEquals(2, decoded.size)
        assertEquals("他说\"不想干了\"，又改口", decoded[0].summary)
        assertEquals("第二段", decoded[1].summary)
    }

    @Test
    fun `提示词里带已有主题与新对话`() {
        val prompt = TopicSummary.userPrompt(
            previous = listOf(MemoryContract.TopicSegment("2026-11-18 晚上", "聊了工作")),
            conversation = listOf("对方：还是想换工作", "你：那就先投简历")
        )
        assertTrue(prompt.contains("2026-11-18 晚上：聊了工作"))
        assertTrue(prompt.contains("对方：还是想换工作"))
        assertTrue(prompt.contains("只输出 JSON 数组"))
        assertNotNull(TopicSummary.systemPrompt())
        assertTrue(TopicSummary.systemPrompt().contains("120 字"))
    }
}
