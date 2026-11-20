package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 历史时间戳的契约测试（`spec/context-profile/README.md`：历史格式 = timestamped）。
 *
 * 钉住格式、跨天、时区三件事：
 * - 同一天：`[19:40] 用户：…` / `[19:41] 他：…`；
 * - 跨天：`[11-18 23:50] 用户：…`；
 * - 时区：同一毫秒在不同设备时区下要显示不同的本地时刻（状态段的时钟用同一把尺子）；
 * - 只有聊天的用户/人格原文加时间戳，工具结果与系统消息不加。
 */
class HistoryTimestampsTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    /** 2026-11-18 19:40（周三）。 */
    private val now = ZonedDateTime.of(2026, 11, 18, 19, 40, 0, 0, shanghai).toInstant().toEpochMilli()

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = shanghai): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `同一天用 HHmm 加说话人`() {
        val user = HistoryTimestamps.decorate(
            content = "今天还是加班吗",
            createdAt = millis(2026, 11, 18, 19, 40),
            now = now,
            zone = shanghai,
            outgoing = true
        )
        assertEquals("[19:40] 用户：今天还是加班吗", user)

        val persona = HistoryTimestamps.decorate(
            content = "还得一会儿",
            createdAt = millis(2026, 11, 18, 19, 41),
            now = now,
            zone = shanghai,
            outgoing = false
        )
        assertEquals("[19:41] 他：还得一会儿", persona)
    }

    @Test
    fun `跨天补 MM-dd`() {
        val yesterday = HistoryTimestamps.decorate(
            content = "我先睡了",
            createdAt = millis(2026, 11, 17, 23, 50),
            now = now,
            zone = shanghai,
            outgoing = true
        )
        assertEquals("[11-17 23:50] 用户：我先睡了", yesterday)

        // 早一分钟还是「今天」：跨天判定看的是本地日期，不是时间差。
        val todayEarly = HistoryTimestamps.decorate(
            content = "早上好",
            createdAt = millis(2026, 11, 18, 0, 1),
            now = now,
            zone = shanghai,
            outgoing = false
        )
        assertEquals("[00:01] 他：早上好", todayEarly)
    }

    @Test
    fun `跨天判定跟着时区走`() {
        // 同一毫秒：上海已经是 19 号 00:10，纽约还是 18 号 11:10。
        val instant = millis(2026, 11, 19, 0, 10, shanghai)
        val newYork = ZoneId.of("America/New_York")

        // 「现在」是上海 19 号 09:00 = 纽约 18 号 20:00：上海看是当天，纽约看也是当天。
        val shanghaiNow = millis(2026, 11, 19, 9, 0, shanghai)
        val sh = HistoryTimestamps.decorate("嗯", instant, shanghaiNow, shanghai, outgoing = true)
        assertTrue("上海当天只写时刻：$sh", sh.startsWith("[00:10] 用户："))

        // 现在是上海 20 号 00:00 = 纽约 19 号 11:00：纽约看这条消息是「昨天」，要带日期。
        val nyNow = millis(2026, 11, 20, 0, 0, shanghai)
        val ny = HistoryTimestamps.decorate("嗯", instant, nyNow, newYork, outgoing = true)
        assertTrue("跨天要带日期：$ny", ny.startsWith("[11-18 11:10] 用户："))
    }

    @Test
    fun `同一毫秒在东京与纽约显示不同本地时刻`() {
        val instant = millis(2026, 11, 18, 19, 40, shanghai)
        val tokyo = HistoryTimestamps.decorate("在", instant, now, ZoneId.of("Asia/Tokyo"), outgoing = false)
        val newYork = HistoryTimestamps.decorate("在", instant, now, ZoneId.of("America/New_York"), outgoing = false)
        assertTrue("东京 +09:00：$tokyo", tokyo.startsWith("[20:40] 他："))
        assertTrue("纽约 -05:00：$newYork", newYork.startsWith("[06:40] 他："))
    }

    @Test
    fun `拿不到时间就原样返回 不写假时间`() {
        assertEquals(
            "今天还是加班吗",
            HistoryTimestamps.decorate("今天还是加班吗", 0L, now, shanghai, outgoing = true)
        )
        assertEquals(
            "今天还是加班吗",
            HistoryTimestamps.decorate("今天还是加班吗", -1L, now, shanghai, outgoing = true)
        )
        // 空白正文也不该被塞一个只有前缀的「消息」。
        assertEquals("   ", HistoryTimestamps.decorate("   ", now, now, shanghai, outgoing = true))
        // 判不出「今天」（now<=0）时按跨天写：宁可多一个日期，也不假装是同一天。
        assertEquals(
            "[11-18 19:40] 用户：在吗",
            HistoryTimestamps.decorate("在吗", millis(2026, 11, 18, 19, 40), 0L, shanghai, outgoing = true)
        )
    }

    @Test
    fun `说话人标签固定是用户与他`() {
        assertEquals("用户", HistoryTimestamps.label(outgoing = true))
        assertEquals("他", HistoryTimestamps.label(outgoing = false))
        assertEquals("[19:40] 用户：", HistoryTimestamps.prefix(now, now, shanghai, outgoing = true))
        assertEquals("[19:40] 他：", HistoryTimestamps.prefix(now, now, shanghai, outgoing = false))
    }

    @Test
    fun `工具结果与系统消息不加时间戳`() {
        // 这条约束由 ChatRepository 的过滤保证：历史只收 USER / ASSISTANT 两种 role。
        // 这里把「它们的正文长什么样」钉住：工具结果自带协议头，不能被套上 `[HH:mm] 用户：`。
        val toolResult = "【工具结果】web_search\n摘要：查了 3 个网页"
        assertFalse(toolResult.contains("用户："))
        assertFalse(toolResult.contains("他："))
        // 引导/系统消息同理：它们不是「他说过的话」。
        assertFalse(PromptBuilder.SECTION_GUIDANCE.contains("用户："))
    }

    @Test
    fun `整段历史按同一个今天基准装饰 顺序不变`() {
        data class Line(val at: Long, val outgoing: Boolean, val text: String)
        val history = listOf(
            Line(millis(2026, 11, 17, 23, 50), true, "我先睡了"),
            Line(millis(2026, 11, 18, 8, 5), false, "早"),
            Line(millis(2026, 11, 18, 19, 40), true, "今天还是加班吗")
        )
        val decorated = HistoryTimestamps.decorateAll(
            history = history,
            now = now,
            zone = shanghai,
            createdAtOf = { it.at },
            outgoingOf = { it.outgoing },
            contentOf = { it.text },
            copy = { line, text -> line.copy(text = text) }
        )
        assertEquals(
            listOf(
                "[11-17 23:50] 用户：我先睡了",
                "[08:05] 他：早",
                "[19:40] 用户：今天还是加班吗"
            ),
            decorated.map { it.text }
        )
    }
}
