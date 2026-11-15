package com.myfriend.agentd.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 睡眠状态的推导：**直接跑 `spec/agent-core/cases/sleep-decision.json` 的全部用例**。
 *
 * 这是本次改动里最重要的一条「不发明规则」的证据：服务端的 [SleepState] 只是
 * `SleepPolicy.evaluate` 的薄封装（加上「最后互动」与「下一次醒来」两个服务端字段），
 * 所以契约里的 16 条边界（默认窗口、睡点/醒点、跨午夜、免打扰、唤醒阈值、睡醒同点、分钟数取模…）
 * 必须原样通过。
 *
 * 找不到仓库根目录时用 `assumeTrue` 跳过（等价 iOS 的 XCTSkip）：`agentd` 被单独拷出去构建时，
 * 没有 `spec/` 就没有契约可读，跳过比误报红更诚实。
 */
class SleepStateTest {

    private val utc = ZoneOffset.UTC

    // ------------------------------------------------------------------ 契约用例

    @Test
    fun `sleep decision cases match sleep state`() {
        SpecCases.forEachCase("sleep-decision") { _, input, expect ->
            val state = SleepState.derive(
                sleepEnabled = input.bool("sleepEnabled") ?: false,
                sleepMinutes = input.int("sleepMinutes"),
                wakeMinutes = input.int("wakeMinutes"),
                dndOnSleep = input.bool("dndOnSleep") ?: false,
                minuteOfDay = input.int("nowMinutes") ?: 0,
                recentUserMessages = input.int("userMessagesInWindow") ?: 0,
                wakeThreshold = input.int("wakeThreshold") ?: 6,
                lastInteractionAt = 0L,
                now = 0L,
                zone = utc
            )
            assertEquals("asleep", expect.bool("asleep"), state.asleep)
            assertEquals("dnd", expect.bool("dnd"), state.dnd)
            assertEquals("woken", expect.bool("woken"), state.woken)
            assertEquals("shouldReply", expect.bool("shouldReply"), state.shouldReply)
            assertEquals("marksRead", expect.bool("marksRead"), state.marksRead)
        }
    }

    // ------------------------------------------------------------------ 服务端特有：最后互动

    @Test
    fun `recent interaction counts as one message inside the wake window`() {
        val now = 1_000_000_000_000L
        assertEquals(1, SleepState.recentUserMessages(now - 60_000L, now))
        assertEquals(1, SleepState.recentUserMessages(now - 29 * 60_000L, now))
        assertEquals(0, SleepState.recentUserMessages(now - 31 * 60_000L, now))
        assertEquals(0, SleepState.recentUserMessages(0L, now))
        // 时钟回拨（最后互动在「未来」）不能算成「刚说过话」。
        assertEquals(0, SleepState.recentUserMessages(now + 60_000L, now))
    }

    @Test
    fun `one recent message wakes a threshold one persona but not a threshold six one`() {
        val now = instantAt(2026, 11, 15, 3, 0)
        val lastInteraction = now - 5 * 60_000L
        val easy = SleepState.derive(
            PersonaState(personaId = "a", wakeThreshold = 1, lastInteractionAt = lastInteraction),
            now,
            utc
        )
        assertTrue("阈值 1：一条消息就该被叫醒", easy.woken)
        assertTrue(easy.shouldReply)

        val deep = SleepState.derive(
            PersonaState(personaId = "b", wakeThreshold = 6, lastInteractionAt = lastInteraction),
            now,
            utc
        )
        assertFalse("阈值 6：一条消息不够", deep.woken)
        assertFalse(deep.shouldReply)
    }

    @Test
    fun `old interaction does not count as a message`() {
        val now = instantAt(2026, 11, 15, 3, 0)
        val state = SleepState.derive(
            PersonaState(personaId = "a", wakeThreshold = 1, lastInteractionAt = now - 3 * 60 * 60_000L),
            now,
            utc
        )
        assertFalse(state.woken)
        assertTrue(state.asleep)
    }

    // ------------------------------------------------------------------ 服务端特有：下一次醒来

    @Test
    fun `next awake at is today wake time when still before it`() {
        val now = instantAt(2026, 11, 15, 3, 0)
        val state = SleepState.derive(PersonaState(personaId = "a"), now, utc)
        assertEquals(instantAt(2026, 11, 15, 8, 0), state.nextAwakeAt)
        assertEquals(5 * 60, state.minutesUntilWake)
    }

    @Test
    fun `next awake at rolls to tomorrow when now is after wake time`() {
        val now = instantAt(2026, 11, 15, 23, 45)
        val state = SleepState.derive(PersonaState(personaId = "a"), now, utc)
        assertTrue(state.asleep)
        assertEquals(instantAt(2026, 11, 16, 8, 0), state.nextAwakeAt)
    }

    @Test
    fun `next awake at respects a custom cross midnight window`() {
        val now = instantAt(2026, 11, 15, 3, 0)
        val state = SleepState.derive(
            PersonaState(personaId = "a", sleepMinutes = 22 * 60, wakeMinutes = 7 * 60),
            now,
            utc
        )
        assertTrue(state.asleep)
        assertEquals(instantAt(2026, 11, 15, 7, 0), state.nextAwakeAt)
        assertEquals("22:00–07:00", state.windowText)
    }

    @Test
    fun `default window text and sleep clock`() {
        val state = SleepState.derive(PersonaState(personaId = "a"), 0L, utc)
        assertEquals("23:30–08:00", state.windowText)
        assertEquals("23:30", SleepState.clock(23 * 60 + 30))
        assertEquals("07:05", SleepState.clock(7 * 60 + 5))
        assertEquals("00:00", SleepState.clock(0))
        // 脏分钟数也先取模，不会拼出 24:xx。
        assertEquals("00:10", SleepState.clock(24 * 60 + 10))
    }

    // ------------------------------------------------------------------ 时区

    @Test
    fun `minute of day uses the local zone`() {
        val now = Instant.parse("2026-11-15T19:00:00Z").toEpochMilli() // 上海 11-16 03:00
        val shanghai = ZoneId.of("Asia/Shanghai")
        assertEquals(3 * 60, SleepState.minuteOfDay(now, shanghai))
        assertEquals(3, SleepState.hourOfDay(now, shanghai))
        assertEquals(19 * 60, SleepState.minuteOfDay(now, utc))

        val state = SleepState.derive(PersonaState(personaId = "a"), now, shanghai)
        assertTrue("上海凌晨 3 点，默认作息下他睡着", state.asleep)
        assertEquals(Instant.parse("2026-11-16T00:00:00Z").toEpochMilli(), state.nextAwakeAt)
    }

    @Test
    fun `next local midnight is the next day start`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-11-15T19:00:00Z").toEpochMilli() // 上海 11-16 03:00
        assertEquals(Instant.parse("2026-11-16T16:00:00Z").toEpochMilli(), SleepState.nextLocalMidnight(now, zone))
    }

    // ------------------------------------------------------------------ 边界

    @Test
    fun `sleep start minute is asleep and wake minute is awake`() {
        val window = PersonaState(personaId = "a", sleepMinutes = 23 * 60 + 30, wakeMinutes = 8 * 60)
        assertTrue(SleepState.derive(window, instantAt(2026, 11, 15, 23, 30), utc).asleep)
        assertFalse(SleepState.derive(window, instantAt(2026, 11, 15, 8, 0), utc).asleep)
        assertFalse(SleepState.derive(window, instantAt(2026, 11, 15, 7, 59), utc).awake)
    }

    @Test
    fun `sleep disabled is always awake`() {
        val state = SleepState.derive(
            PersonaState(personaId = "a", sleepEnabled = false),
            instantAt(2026, 11, 15, 3, 0),
            utc
        )
        assertFalse(state.asleep)
        assertTrue(state.awake)
        assertTrue(state.shouldReply)
        assertTrue(state.marksRead)
        assertEquals(0, state.minutesUntilWake)
    }

    @Test
    fun `dnd never marks read`() {
        val state = SleepState.derive(
            PersonaState(personaId = "a", dndOnSleep = true, wakeThreshold = 1, lastInteractionAt = 0L),
            instantAt(2026, 11, 15, 3, 0),
            utc
        )
        assertTrue(state.asleep)
        assertTrue(state.dnd)
        assertFalse(state.marksRead)
        assertFalse(state.shouldReply)
    }

    private fun instantAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.time.LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()
}
