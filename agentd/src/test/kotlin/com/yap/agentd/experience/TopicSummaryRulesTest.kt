package com.yap.agentd.experience

import com.yap.app.domain.MemoryContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 对话主题总结的纯规则（0.15 上下文/记忆 v2「主题总结」）。
 *
 * 这些规则会直接进提示词：时间段写错、段数超上限、模型输出解析不出来，
 * 都会让模型把「主题」当成清单或历史。所以格式必须逐条钉住。
 */
class TopicSummaryRulesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `time label uses date plus a Chinese time slot`() {
        // 2026-11-18 19:40 本地（UTC+08:00）= 11:40Z
        assertEquals("2026-11-18 晚", TopicSummaryRules.timeLabel(at("2026-11-18T11:40:00Z"), zone))
        assertEquals("2026-11-18 深夜", TopicSummaryRules.timeLabel(at("2026-11-18T15:30:00Z"), zone))
        assertEquals("2026-11-19 凌晨", TopicSummaryRules.timeLabel(at("2026-11-18T17:30:00Z"), zone))
        assertEquals("2026-11-19 早上", TopicSummaryRules.timeLabel(at("2026-11-18T23:30:00Z"), zone))
        assertEquals("", TopicSummaryRules.timeLabel(0L, zone))
    }

    @Test
    fun `time label of a message list uses the newest message`() {
        assertEquals(
            "2026-11-18 晚",
            TopicSummaryRules.timeLabelOf(
                listOf(at("2026-11-17T11:00:00Z"), at("2026-11-18T11:00:00Z")),
                zone
            )
        )
        assertEquals("（空）", TopicSummaryRules.timeLabelOf(emptyList(), zone, fallback = "（空）"))
    }

    @Test
    fun `parse accepts json pipe and plain summary`() {
        val json = TopicSummaryRules.parse(
            """{"timeLabel":"2026-11-18 晚","summary":"加班与方案改版"}""",
            fallbackLabel = "2026-11-19 早上"
        )!!
        assertEquals("2026-11-18 晚", json.timeLabel)
        assertEquals("加班与方案改版", json.summary)

        val pipe = TopicSummaryRules.parse("2026-11-18 晚｜加班与方案改版", "x")!!
        assertEquals("2026-11-18 晚", pipe.timeLabel)
        assertEquals("加班与方案改版", pipe.summary)

        val plain = TopicSummaryRules.parse("聊了很久的加班", "2026-11-18 深夜")!!
        assertEquals("2026-11-18 深夜", plain.timeLabel)
        assertEquals("聊了很久的加班", plain.summary)
    }

    @Test
    fun `parse rejects empty and skip answers`() {
        assertNull(TopicSummaryRules.parse(null))
        assertNull(TopicSummaryRules.parse("   "))
        assertNull(TopicSummaryRules.parse("SKIP"))
        assertNull(TopicSummaryRules.parse("先不更新"))
        assertTrue(TopicSummaryRules.isSkip("skip"))
        assertFalse(TopicSummaryRules.isSkip("加班"))
    }

    @Test
    fun `parse clips an over long summary instead of rejecting it`() {
        val topic = TopicSummaryRules.parse("X".repeat(500), "")!!
        assertEquals(MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS, topic.summary.length)
    }

    @Test
    fun `merge combines the same time slot and drops the oldest beyond twelve`() {
        val first = MemoryContract.TopicSegment("2026-11-18 晚", "加班")
        val sameSlot = TopicSummaryRules.merge(listOf(first), MemoryContract.TopicSegment("2026-11-18 晚", "方案改版"))
        assertEquals(1, sameSlot.size)
        assertTrue(sameSlot.single().summary.contains("加班"))
        assertTrue(sameSlot.single().summary.contains("方案改版"))

        var topics = emptyList<MemoryContract.TopicSegment>()
        repeat(14) { index ->
            topics = TopicSummaryRules.merge(
                topics,
                MemoryContract.TopicSegment("2026-11-0${(index % 9) + 1} 晚", "话题 $index")
            )
        }
        assertEquals(12, topics.size)
        assertTrue("丢的是最旧的", topics.first().summary.contains("话题 2"))
        assertTrue(topics.last().summary.contains("话题 13"))
    }

    @Test
    fun `topic switch is detected by keyword overlap`() {
        val previous = MemoryContract.TopicSegment("2026-11-18 晚", "加班做方案，周四要给甲方答复")
        val sameTopic = "还在说方案的事，甲方又改了一版，周四还得给答复"
        val newTopic = "今天想聊聊养猫，楼下的流浪猫生了一窝小猫"
        assertFalse("同一个话题不该触发", TopicSummaryRules.shouldSplit(sameTopic, previous))
        assertTrue("换了话题要触发", TopicSummaryRules.shouldSplit(newTopic, previous))
        assertFalse("消息太短不触发（「嗯」「好」不该开新主题）", TopicSummaryRules.shouldSplit("很短", null))
        assertTrue("还没有主题且消息够长 → 值得新起一段", TopicSummaryRules.shouldSplit("a".repeat(60), null))
    }

    @Test
    fun `a short reply never counts as a topic switch`() {
        val previous = MemoryContract.TopicSegment("2026-11-18 晚", "加班做方案")
        assertFalse(TopicSummaryRules.shouldSplit("嗯", previous))
        assertFalse(TopicSummaryRules.shouldSplit("好", previous))
    }
}
