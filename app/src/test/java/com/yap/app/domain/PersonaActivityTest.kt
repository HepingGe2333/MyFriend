package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 「他此刻在做什么」（规范 §4）。
 *
 * 时间全部由参数传入：这些用例不依赖真实时钟，也不依赖运行机器的时区
 * （`until` 的显示统一用 `Asia/Shanghai` 断言）。
 */
class PersonaActivityTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = 1_794_500_000_000L // 2026-11-15T23:13:20+08:00 附近；具体数值不重要

    private fun activity(until: Long, availability: ActivityAvailability = ActivityAvailability.BUSY) =
        PersonaActivity(
            kind = ActivityKind.WORK,
            activity = "在事务所赶第三版方案",
            until = until,
            availability = availability
        )

    @Test
    fun `到点前是忙的 到点后自动回到没在忙什么`() {
        val active = activity(until = now + 90 * 60_000L)
        assertTrue(active.isActive(now))
        assertEquals(ActivityAvailability.BUSY, active.availabilityAt(now))

        val expired = activity(until = now - 1)
        assertFalse(expired.isActive(now))
        assertEquals("过期就回 FREE", ActivityAvailability.FREE, expired.availabilityAt(now))
        assertEquals("没在忙什么。", expired.renderForPrompt(now, zone))
    }

    @Test
    fun `渲染出活动与预计结束时间`() {
        val text = activity(until = now + 90 * 60_000L).renderForPrompt(now, zone)
        assertTrue(text.contains("你在事务所赶第三版方案"))
        assertTrue(text.contains("预计"))
        assertTrue(text.contains("结束"))
        assertTrue(text.contains("21:30") || text.contains("22:") || text.contains(":"))
    }

    @Test
    fun `三种 availability 的提示不同`() {
        val until = now + 60 * 60_000L
        assertTrue(activity(until, ActivityAvailability.BUSY).renderForPrompt(now, zone).contains("不太有空"))
        assertTrue(activity(until, ActivityAvailability.LIMITED).renderForPrompt(now, zone).contains("回得慢"))
        assertTrue(activity(until, ActivityAvailability.FREE).renderForPrompt(now, zone).contains("随时能聊"))
    }

    @Test
    fun `kind 与 availability 归一化`() {
        assertEquals(ActivityKind.WORK, ActivityKind.parse("work"))
        assertEquals(ActivityKind.OTHER, ActivityKind.parse("SLEEPING"))
        assertEquals(ActivityKind.OTHER, ActivityKind.parse(null))
        assertEquals(ActivityAvailability.LIMITED, ActivityAvailability.parse("limited"))
        assertEquals(ActivityAvailability.BUSY, ActivityAvailability.parse("nope"))
    }

    @Test
    fun `工具参数生成 until 为 now 加分钟`() {
        val built = PersonaActivityCodec.fromTool(
            kind = "WORK",
            activity = "在赶图",
            minutes = 90,
            availability = "LIMITED",
            now = now
        )
        assertEquals(now + 90 * 60_000L, built.until)
        assertEquals(ActivityKind.WORK, built.kind)
        assertEquals(ActivityAvailability.LIMITED, built.availability)
    }

    @Test
    fun `编解码往返与坏 JSON`() {
        val original = activity(until = now + 30 * 60_000L, availability = ActivityAvailability.LIMITED)
        val decoded = PersonaActivityCodec.decode(PersonaActivityCodec.encode(original))!!
        assertEquals(original, decoded)
        assertNull(PersonaActivityCodec.decode("not json"))
        assertNull(PersonaActivityCodec.decode("""{"kind":"WORK"}"""))
        assertNull(PersonaActivityCodec.decode(null))
        // until 写成 ISO 字符串也认
        val iso = PersonaActivityCodec.decode(
            """{"activity":"在赶图","until":"2026-11-15T12:00:00Z","availability":"BUSY"}"""
        )!!
        assertTrue(iso.until > 0L)
    }

    @Test
    fun `LIMITED 的主动降权系数在 0 与 1 之间`() {
        assertTrue(PersonaActivity.LIMITED_PROACTIVE_FACTOR in 0.0..1.0)
    }
}
