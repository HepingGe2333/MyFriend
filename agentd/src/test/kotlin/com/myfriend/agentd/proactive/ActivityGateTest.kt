package com.myfriend.agentd.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 「占用时间」门禁（`spec/persona-guidance/README.md` §4）在主动引擎里的规则：
 *
 * - **BUSY**：这一拍连骰子都不掷，`reason = busy`，`scheduledAt` 给到活动结束；
 * - **LIMITED**：概率 ×[ActivityState.LIMITED_PROACTIVE_FACTOR]（0.35），没掷中时
 *   `reason = limited`（与「纯运气没中」的 `not_this_tick` 分开）；
 * - **过期**：`until <= now` 自动回到 FREE，不靠定时任务清理；
 * - **坏 JSON / 没设过**：当成没有活动，不能把主动消息永久掐死。
 *
 * 时间与随机数全部注入（`Clock.fixed` + `roll`），所以「忙到 21:30 正好结束」这种边界可以精确钉住。
 */
class ActivityGateTest {

    private val utc = ZoneOffset.UTC
    private val minute = 60_000L
    private val hour = 60 * minute

    /** 本地下午 2 点（不在深夜折扣、不在睡眠窗口）。 */
    private val afternoon = localMillis(2026, 11, 15, 14, 0)

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun engineAt(now: Long, roll: Double = 0.0): ProactiveEngine = ProactiveEngine(
        clock = Clock.fixed(Instant.ofEpochMilli(now), utc),
        roll = { roll }
    )

    private fun persona(
        activity: ActivityState? = null,
        lastInteractionAt: Long = afternoon - hour,
        count: Int = 0
    ): PersonaState = PersonaState(
        personaId = "persona-1",
        displayName = "林深",
        sleepEnabled = false,
        lastInteractionAt = lastInteractionAt,
        proactiveCountToday = count,
        activity = activity
    )

    private fun busy(until: Long, text: String = "在事务所赶第三版方案") = ActivityState(
        kind = "WORK",
        activity = text,
        until = until,
        availability = ActivityAvailability.BUSY
    )

    private fun limited(until: Long, text: String = "在事务所赶图") = ActivityState(
        kind = "WORK",
        activity = text,
        until = until,
        availability = ActivityAvailability.LIMITED
    )

    // ------------------------------------------------------------------ BUSY

    @Test
    fun `busy activity blocks the tick without even rolling`() {
        val until = afternoon + 30 * minute
        // 随机源一旦被调用就炸：BUSY 必须在掷骰子之前返回，否则这条测试会直接失败。
        val engine = ProactiveEngine(
            clock = Clock.fixed(Instant.ofEpochMilli(afternoon), utc),
            roll = { error("BUSY 时不该掷骰子") }
        )

        val decision = engine.evaluate(persona(activity = busy(until)), LevelPolicy(level = 3), afternoon)

        assertFalse(decision.shouldFire)
        assertEquals(ProactiveReason.BUSY, decision.reason)
        assertEquals("busy", decision.reason.code)
        assertNull(decision.pending)
        assertEquals("概率必须是 0（没走到掷骰子那一步）", 0.0, decision.probability, 0.0)
        assertEquals("这一拍不开口，下一次看是活动结束时刻", until, decision.scheduledAt)
    }

    @Test
    fun `busy activity makes impulse probability zero`() {
        val engine = engineAt(afternoon)
        val free = engine.impulseProbability(persona(), LevelPolicy(level = 3), afternoon)
        val blocked = engine.impulseProbability(
            persona(activity = busy(afternoon + hour)),
            LevelPolicy(level = 3),
            afternoon
        )

        assertTrue("对照组得有一个正概率，否则这条测试没意义：$free", free > 0.0)
        assertEquals(0.0, blocked, 0.0)
    }

    @Test
    fun `busy activity with no free slot left reports the daily cap numbers`() {
        val decision = engineAt(afternoon).evaluate(
            persona(activity = busy(afternoon + hour), count = 3),
            LevelPolicy(level = 3),
            afternoon
        )
        assertEquals(ProactiveReason.BUSY, decision.reason)
        assertEquals(3, decision.dailyCap)
        assertEquals(3, decision.sentToday)
    }

    // ------------------------------------------------------------------ LIMITED

    @Test
    fun `limited activity lowers the probability to 35 percent`() {
        val engine = engineAt(afternoon)
        val free = engine.impulseProbability(persona(), LevelPolicy(level = 3), afternoon)
        val reduced = engine.impulseProbability(
            persona(activity = limited(afternoon + hour)),
            LevelPolicy(level = 3),
            afternoon
        )

        assertTrue("对照组得有一个正概率：$free", free > 0.0)
        assertEquals(
            "LIMITED 的唯一效果就是 ×0.35（默认一拍一分钟，换算后相等）",
            free * ActivityState.LIMITED_PROACTIVE_FACTOR,
            reduced,
            1e-12
        )
        assertEquals(0.35, ActivityState.LIMITED_PROACTIVE_FACTOR, 0.0)
    }

    @Test
    fun `limited activity that misses the roll says limited instead of not this tick`() {
        val decision = engineAt(afternoon, roll = 0.99).evaluate(
            persona(activity = limited(afternoon + hour)),
            LevelPolicy(level = 3),
            afternoon
        )

        assertFalse(decision.shouldFire)
        assertEquals(ProactiveReason.LIMITED, decision.reason)
        assertEquals("limited", decision.reason.code)
        assertTrue("概率必须是降权之后的那个数：${decision.probability}", decision.probability < 0.9)
    }

    @Test
    fun `limited activity can still fire when the roll is lucky`() {
        val decision = engineAt(afternoon, roll = 0.0).evaluate(
            persona(activity = limited(afternoon + hour)),
            LevelPolicy(level = 3),
            afternoon
        )
        assertTrue(decision.shouldFire)
        assertEquals(ProactiveReason.FIRED, decision.reason)
    }

    @Test
    fun `free activity keeps the normal probability`() {
        val engine = engineAt(afternoon, roll = 0.99)
        val plain = engine.evaluate(persona(), LevelPolicy(level = 3), afternoon)
        val withFree = engine.evaluate(
            persona(
                activity = ActivityState(
                    activity = "在事务所画图",
                    until = afternoon + hour,
                    availability = ActivityAvailability.FREE
                )
            ),
            LevelPolicy(level = 3),
            afternoon
        )

        assertEquals(plain.reason, withFree.reason)
        assertEquals(plain.probability, withFree.probability, 0.0)
    }

    // ------------------------------------------------------------------ 过期

    @Test
    fun `expired activity is free again without any cleanup task`() {
        val until = afternoon - minute // 一分钟前就结束了
        val decision = engineAt(afternoon, roll = 0.0).evaluate(
            persona(activity = busy(until)),
            LevelPolicy(level = 3),
            afternoon
        )

        assertTrue("过期的 BUSY 不该继续压住主动消息", decision.shouldFire)
        assertEquals(ProactiveReason.FIRED, decision.reason)
    }

    @Test
    fun `activity ending exactly now counts as free`() {
        // until == now 视为已结束（`until > now` 才算还在忙）。
        val decision = engineAt(afternoon, roll = 0.0).evaluate(
            persona(activity = busy(afternoon)),
            LevelPolicy(level = 3),
            afternoon
        )
        assertTrue(decision.shouldFire)
        assertEquals(ActivityAvailability.FREE, busy(afternoon).availabilityAt(afternoon))
    }

    @Test
    fun `activity without text or without deadline is free`() {
        assertEquals(
            ActivityAvailability.FREE,
            ActivityState(activity = "", until = afternoon + hour, availability = ActivityAvailability.BUSY)
                .availabilityAt(afternoon)
        )
        assertEquals(
            ActivityAvailability.FREE,
            busy(until = 0L).availabilityAt(afternoon)
        )
    }

    @Test
    fun `no activity at all is free`() {
        val decision = engineAt(afternoon, roll = 0.0).evaluate(persona(activity = null), LevelPolicy(level = 3), afternoon)
        assertTrue(decision.shouldFire)
        assertEquals(ProactiveReason.FIRED, decision.reason)
    }

    // ------------------------------------------------------------------ 解析

    @Test
    fun `decode reads the client shaped json`() {
        val decoded = ActivityState.decode(
            """{"kind":"work","activity":" 在事务所赶图 ","until":1763200000000,"availability":"limited"}"""
        ) ?: error("这段 JSON 应该能解析出来")
        assertEquals("WORK", decoded.kind)
        assertEquals("在事务所赶图", decoded.activity)
        assertEquals(1763200000000L, decoded.until)
        assertEquals(ActivityAvailability.LIMITED, decoded.availability)
    }

    @Test
    fun `decode accepts an iso until and unknown enums`() {
        val decoded = ActivityState.decode(
            """{"kind":"不认识的","activity":"开会","until":"2026-11-16T13:30:00Z","availability":"开会中"}"""
        ) ?: error("这段 JSON 应该能解析出来")
        assertEquals(ActivityState.KIND_OTHER, decoded.kind)
        assertEquals("认不出的 availability 按 BUSY（不谎称有空）", ActivityAvailability.BUSY, decoded.availability)
        assertEquals(Instant.parse("2026-11-16T13:30:00Z").toEpochMilli(), decoded.until)
    }

    @Test
    fun `decode treats broken or empty json as no activity`() {
        assertNull(ActivityState.decode(null))
        assertNull(ActivityState.decode("  "))
        assertNull(ActivityState.decode("not json"))
        assertNull(ActivityState.decode("[]"))
        assertNull(ActivityState.decode("""{"kind":"WORK","activity":"   "}"""))
    }

    @Test
    fun `clampMinutes keeps the spec range`() {
        assertEquals(15, ActivityState.clampMinutes(1))
        assertEquals(15, ActivityState.clampMinutes(-30))
        assertEquals(480, ActivityState.clampMinutes(9999))
        assertEquals(90, ActivityState.clampMinutes(90))
        assertEquals(15, ActivityState.MIN_MINUTES)
        assertEquals(480, ActivityState.MAX_MINUTES)
    }

    @Test
    fun `fromMinutes applies the clamp and keeps the text under the limit`() {
        val activity = ActivityState.fromMinutes(
            kind = "study",
            activity = " 在图书馆复习 " + "题".repeat(400),
            minutes = 5,
            availability = ActivityAvailability.BUSY,
            now = afternoon
        )
        assertEquals("STUDY", activity.kind)
        assertEquals(afternoon + ActivityState.MIN_MINUTES * minute, activity.until)
        assertEquals(ActivityState.MAX_ACTIVITY_CHARS, activity.activity.length)
        assertTrue(activity.activity.startsWith("在图书馆复习"))
    }

    @Test
    fun `encoded json round trips through decode`() {
        val activity = limited(afternoon + 90 * minute)
        val decoded = ActivityState.decode(ActivityState.encode(activity))
        assertEquals(activity, decoded)
    }

    // ------------------------------------------------------------------ 解析：availability 枚举

    @Test
    fun `availability parsing is case insensitive and defaults to busy`() {
        assertEquals(ActivityAvailability.BUSY, ActivityAvailability.parse("busy"))
        assertEquals(ActivityAvailability.LIMITED, ActivityAvailability.parse(" Limited "))
        assertEquals(ActivityAvailability.FREE, ActivityAvailability.parse("free"))
        assertEquals(ActivityAvailability.BUSY, ActivityAvailability.parse(null))
        assertEquals(ActivityAvailability.BUSY, ActivityAvailability.parse("开会中"))
    }
}
