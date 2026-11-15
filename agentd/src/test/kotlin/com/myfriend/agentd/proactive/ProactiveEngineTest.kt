package com.myfriend.agentd.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 主动引擎的规则测试：睡眠/免打扰、每日上限、冷却与间隔、被回复后重置、概率与随机数。
 *
 * 时间全部用 `Clock.fixed(...)` 或显式 `now` 传入（`spec/agent-core/README.md` 的要求：
 * 测试不读系统时钟），所以「23:30 正好入睡」「刚聊完 5 分钟」这类边界可以精确构造。
 *
 * 随机数用注入的 `roll` 固定：`0.0` = 一定掷中，`0.9999` = 一定不中。这样「该不该开口」
 * 与「掷骰子结果」两件事可以分开断言，不会出现「这次测试碰巧中了」。
 */
class ProactiveEngineTest {

    private val utc = ZoneOffset.UTC
    private val minute = 60_000L
    private val hour = 60 * minute

    // 本地下午 2 点（不在深夜折扣、不在睡眠窗口）。
    private val afternoon = localMillis(2026, 11, 15, 14, 0)

    private fun engineAt(
        now: Long,
        roll: Double = 0.0,
        policy: ProactivePolicy = ProactivePolicy(),
        texts: ProactiveTextSource = ProactiveTextSource.DEFAULT
    ): ProactiveEngine = ProactiveEngine(
        clock = Clock.fixed(Instant.ofEpochMilli(now), utc),
        policy = policy,
        texts = texts,
        roll = { roll }
    )

    private fun persona(
        lastInteractionAt: Long = afternoon - hour,
        lastProactiveAt: Long = 0L,
        count: Int = 0,
        mood: MoodState = MoodState(),
        sleepEnabled: Boolean = true,
        sleepMinutes: Int? = null,
        wakeMinutes: Int? = null,
        dndOnSleep: Boolean = false,
        wakeThreshold: Int = 6,
        enabled: Boolean = true
    ): PersonaState = PersonaState(
        personaId = "persona-1",
        displayName = "小雨",
        enabled = enabled,
        sleepEnabled = sleepEnabled,
        sleepMinutes = sleepMinutes,
        wakeMinutes = wakeMinutes,
        dndOnSleep = dndOnSleep,
        wakeThreshold = wakeThreshold,
        lastInteractionAt = lastInteractionAt,
        lastProactiveAt = lastProactiveAt,
        proactiveCountToday = count,
        mood = mood
    )

    // ------------------------------------------------------------------ 正常开口

    @Test
    fun `fires when eligible and produces a pending row`() {
        val decision = engineAt(afternoon, roll = 0.0).evaluate(persona(), LevelPolicy(level = 3), afternoon)

        assertTrue(decision.shouldFire)
        assertEquals(ProactiveReason.FIRED, decision.reason)
        assertEquals("persona-1", decision.targetPersonaId)
        assertEquals(afternoon, decision.scheduledAt)

        val pending = decision.pending
        assertNotNull(pending)
        requireNotNull(pending)
        assertTrue(pending.text.isNotBlank())
        assertEquals("persona-1", pending.personaId)
        assertEquals(afternoon, pending.createdAt)
    }

    @Test
    fun `injected text source writes the pending text`() {
        val texts = ProactiveTextSource { persona, now -> "想问 ${persona.displayName}：现在忙吗（$now）" }
        val decision = engineAt(afternoon, roll = 0.0, texts = texts).evaluate(persona(), LevelPolicy(), afternoon)
        assertEquals("想问 小雨：现在忙吗（$afternoon）", decision.pending?.text)
    }

    @Test
    fun `blank text falls back to the default line instead of an empty message`() {
        val texts = ProactiveTextSource { _, _ -> "   " }
        val decision = engineAt(afternoon, roll = 0.0, texts = texts).evaluate(persona(), LevelPolicy(), afternoon)
        assertTrue(decision.pending?.text?.isNotBlank() == true)
    }

    // ------------------------------------------------------------------ 睡眠与免打扰

    @Test
    fun `asleep inside the default window does not fire and schedules the wake time`() {
        val night = localMillis(2026, 11, 15, 3, 0)
        val decision = engineAt(night, roll = 0.0).evaluate(
            persona(lastInteractionAt = night - hour),
            LevelPolicy(),
            night
        )
        assertFalse(decision.shouldFire)
        assertEquals(ProactiveReason.ASLEEP, decision.reason)
        assertNull(decision.pending)
        assertEquals(localMillis(2026, 11, 15, 8, 0), decision.scheduledAt)
    }

    @Test
    fun `dnd inside the sleep window is reported as dnd`() {
        val night = localMillis(2026, 11, 15, 3, 0)
        val decision = engineAt(night, roll = 0.0).evaluate(
            persona(lastInteractionAt = night - hour, dndOnSleep = true),
            LevelPolicy(),
            night
        )
        assertFalse(decision.shouldFire)
        assertEquals(ProactiveReason.DND, decision.reason)
        assertEquals(localMillis(2026, 11, 15, 8, 0), decision.scheduledAt)
    }

    @Test
    fun `sleep disabled fires even at three in the morning`() {
        val night = localMillis(2026, 11, 15, 3, 0)
        val decision = engineAt(night, roll = 0.0).evaluate(
            persona(lastInteractionAt = night - hour, sleepEnabled = false),
            LevelPolicy(),
            night
        )
        assertTrue(decision.shouldFire)
        assertEquals(ProactiveReason.FIRED, decision.reason)
    }

    @Test
    fun `a recent message can wake him out of the sleep window`() {
        // 阈值 1：一条消息就被叫醒 —— 与 SleepPolicy 的语义一致（服务端从「最后互动」推出 1 条）。
        val night = localMillis(2026, 11, 15, 3, 0)
        val decision = engineAt(night, roll = 0.0).evaluate(
            persona(lastInteractionAt = night - 5 * minute, wakeThreshold = 1),
            LevelPolicy(),
            night
        )
        // 被叫醒后仍然受「刚聊完先安静一会儿」约束：这一拍不掷骰子，但不是 ASLEEP。
        assertEquals(ProactiveReason.RECENT_INTERACTION, decision.reason)
    }

    // ------------------------------------------------------------------ 冷却 / 间隔

    @Test
    fun `no baseline means the first tick only records the start`() {
        val decision = engineAt(afternoon, roll = 0.0).evaluate(
            persona(lastInteractionAt = 0L, lastProactiveAt = 0L),
            LevelPolicy(),
            afternoon
        )
        assertFalse(decision.shouldFire)
        assertEquals(ProactiveReason.NO_BASELINE, decision.reason)
        assertEquals(afternoon + 60_000L, decision.scheduledAt)
    }

    @Test
    fun `just talked means no dice at all`() {
        val decision = engineAt(afternoon, roll = 0.0).evaluate(
            persona(lastInteractionAt = afternoon - 5 * minute),
            LevelPolicy(),
            afternoon
        )
        assertFalse(decision.shouldFire)
        assertEquals(ProactiveReason.RECENT_INTERACTION, decision.reason)
        // 冷却结束 = 最后互动 + 10 分钟。
        assertEquals(afternoon - 5 * minute + 10 * minute, decision.scheduledAt)
    }

    @Test
    fun `reply resets the clock even if he spoke proactively very recently`() {
        // 上次主动在两小时前（本该很可能再开口），但用户两分钟前刚回复：以最近互动为准。
        val decision = engineAt(afternoon, roll = 0.0).evaluate(
            persona(lastInteractionAt = afternoon - 2 * minute, lastProactiveAt = afternoon - 2 * hour),
            LevelPolicy(),
            afternoon
        )
        assertEquals(ProactiveReason.RECENT_INTERACTION, decision.reason)
        assertFalse(decision.shouldFire)
    }

    @Test
    fun `proactive time also resets the clock`() {
        val decision = engineAt(afternoon, roll = 0.0).evaluate(
            persona(lastInteractionAt = afternoon - 5 * hour, lastProactiveAt = afternoon - 3 * minute),
            LevelPolicy(),
            afternoon
        )
        assertEquals(ProactiveReason.RECENT_INTERACTION, decision.reason)
    }

    @Test
    fun `cool down boundary at ten minutes starts rolling`() {
        val exactlyTen = engineAt(afternoon, roll = 0.9999).evaluate(
            persona(lastInteractionAt = afternoon - 10 * minute),
            LevelPolicy(),
            afternoon
        )
        assertEquals(ProactiveReason.NOT_THIS_TICK, exactlyTen.reason)
        assertTrue("冷却结束后应该真的算了概率", exactlyTen.probability > 0.0)
    }

    // ------------------------------------------------------------------ 每日上限

    @Test
    fun `daily cap stops firing and waits for the next local midnight`() {
        val decision = engineAt(afternoon, roll = 0.0).evaluate(
            persona(count = 3),
            LevelPolicy(level = 3),
            afternoon
        )
        assertFalse(decision.shouldFire)
        assertEquals(ProactiveReason.DAILY_CAP, decision.reason)
        assertEquals(3, decision.dailyCap)
        assertEquals(3, decision.sentToday)
        assertEquals(localMillis(2026, 11, 16, 0, 0), decision.scheduledAt)
    }

    @Test
    fun `happy mood raises the cap by one`() {
        val happy = MoodState(label = "雀跃", intensity = 4)
        // 平时（level 3）说 3 次就够；今天心情好，第 4 次仍然可以开口。
        val capped = engineAt(afternoon, roll = 0.0).evaluate(persona(count = 3), LevelPolicy(level = 3), afternoon)
        assertEquals(ProactiveReason.DAILY_CAP, capped.reason)

        val cheerful = engineAt(afternoon, roll = 0.0).evaluate(
            persona(count = 3, mood = happy),
            LevelPolicy(level = 3),
            afternoon
        )
        assertTrue(cheerful.shouldFire)
        assertEquals(4, cheerful.dailyCap)
    }

    @Test
    fun `low mood lowers the cap by one`() {
        val decision = engineAt(afternoon, roll = 0.0).evaluate(
            persona(count = 2, mood = MoodState(label = "低落", intensity = 4)),
            LevelPolicy(level = 3),
            afternoon
        )
        assertEquals(2, decision.dailyCap)
        assertEquals(ProactiveReason.DAILY_CAP, decision.reason)
    }

    @Test
    fun `daily cap override of zero disables today`() {
        val decision = engineAt(afternoon, roll = 0.0).evaluate(
            persona(count = 0),
            LevelPolicy(level = 5, dailyCapOverride = 0),
            afternoon
        )
        assertEquals(ProactiveReason.DAILY_CAP, decision.reason)
        assertEquals(0, decision.dailyCap)
    }

    @Test
    fun `disabled persona or level never fires`() {
        val byPersona = engineAt(afternoon, roll = 0.0).evaluate(persona(enabled = false), LevelPolicy(), afternoon)
        assertEquals(ProactiveReason.DISABLED, byPersona.reason)
        val byLevel = engineAt(afternoon, roll = 0.0).evaluate(persona(), LevelPolicy(enabled = false), afternoon)
        assertEquals(ProactiveReason.DISABLED, byLevel.reason)
    }

    @Test
    fun `level one persona still gets one proactive message a day`() {
        val decision = engineAt(afternoon, roll = 0.0).evaluate(persona(count = 0), LevelPolicy(level = 1), afternoon)
        assertTrue(decision.shouldFire)
        assertEquals(1, decision.dailyCap)
    }

    // ------------------------------------------------------------------ 概率

    @Test
    fun `random roll above the probability does not fire`() {
        val decision = engineAt(afternoon, roll = 0.9999).evaluate(persona(), LevelPolicy(level = 3), afternoon)
        assertFalse(decision.shouldFire)
        assertEquals(ProactiveReason.NOT_THIS_TICK, decision.reason)
        assertEquals(afternoon + 60_000L, decision.scheduledAt)
        assertTrue(decision.roll in 0.0..1.0)
        assertNull(decision.pending)
    }

    @Test
    fun `night halves the probability`() {
        val engine = ProactiveEngine(clock = Clock.fixed(Instant.ofEpochMilli(afternoon), utc), roll = { 0.9999 })
        val dayProbability = engine.impulseProbability(persona(lastInteractionAt = afternoon - hour), LevelPolicy(), afternoon)

        val night = localMillis(2026, 11, 15, 3, 0)
        val nightEngine = ProactiveEngine(clock = Clock.fixed(Instant.ofEpochMilli(night), utc), roll = { 0.9999 })
        val nightProbability = nightEngine.impulseProbability(
            persona(lastInteractionAt = night - hour, sleepEnabled = false),
            LevelPolicy(),
            night
        )

        assertEquals(dayProbability / 2, nightProbability, 1e-9)
    }

    @Test
    fun `higher proactive level raises the probability`() {
        val engine = ProactiveEngine(clock = Clock.fixed(Instant.ofEpochMilli(afternoon), utc), roll = { 0.9999 })
        val low = engine.impulseProbability(persona(lastInteractionAt = afternoon - hour), LevelPolicy(level = 1), afternoon)
        val high = engine.impulseProbability(persona(lastInteractionAt = afternoon - hour), LevelPolicy(level = 5), afternoon)
        assertTrue("主动程度 5 应该比 1 更想开口（$high vs $low）", high > low)
        assertEquals(low / 0.70 * 1.30, high, 1e-9)
    }

    @Test
    fun `concentrated positive mood raises the probability`() {
        val engine = ProactiveEngine(clock = Clock.fixed(Instant.ofEpochMilli(afternoon), utc), roll = { 0.9999 })
        val calm = engine.impulseProbability(persona(lastInteractionAt = afternoon - hour), LevelPolicy(), afternoon)
        val happy = engine.impulseProbability(
            persona(lastInteractionAt = afternoon - hour, mood = MoodState(label = "雀跃", intensity = 4)),
            LevelPolicy(),
            afternoon
        )
        assertTrue("心情浓（强度 ≥3）应该放大冲动：$happy vs $calm", happy > calm)
    }

    @Test
    fun `faint mood does not change the probability`() {
        val engine = ProactiveEngine(clock = Clock.fixed(Instant.ofEpochMilli(afternoon), utc), roll = { 0.9999 })
        val calm = engine.impulseProbability(persona(lastInteractionAt = afternoon - hour), LevelPolicy(), afternoon)
        val faint = engine.impulseProbability(
            persona(lastInteractionAt = afternoon - hour, mood = MoodState(label = "雀跃", intensity = 2)),
            LevelPolicy(),
            afternoon
        )
        assertEquals(calm, faint, 1e-9)
    }

    @Test
    fun `long silence converges to the ceiling and never exceeds it`() {
        val engine = ProactiveEngine(clock = Clock.fixed(Instant.ofEpochMilli(afternoon), utc), roll = { 0.9999 })
        val threeHours = engine.impulseProbability(persona(lastInteractionAt = afternoon - 3 * hour), LevelPolicy(), afternoon)
        val tenHours = engine.impulseProbability(persona(lastInteractionAt = afternoon - 10 * hour), LevelPolicy(), afternoon)
        assertEquals(0.85, threeHours, 1e-9)
        assertEquals(threeHours, tenHours, 1e-9)
    }

    @Test
    fun `probability is deterministic for the same inputs`() {
        val first = engineAt(afternoon, roll = 0.3).evaluate(persona(), LevelPolicy(), afternoon)
        val second = engineAt(afternoon, roll = 0.3).evaluate(persona(), LevelPolicy(), afternoon)
        assertEquals(first, second)
    }

    @Test
    fun `bad rolls are sanitized instead of silently disabling the engine`() {
        // NaN / 负数 / 正无穷都不是 [0,1) 里的骰子：按 0 处理（该开口就要开口），
        // 否则一次坏随机数就会让这个进程从此不再主动。
        listOf(Double.NaN, -1.0, Double.POSITIVE_INFINITY).forEach { bad ->
            val decision = engineAt(afternoon, roll = bad).evaluate(persona(), LevelPolicy(), afternoon)
            assertTrue("roll=$bad 应该被收进 [0,1)：${decision.reason}", decision.shouldFire)
        }
        // 1.0 是「永远落不进去」的合法语义（`Heartbeat.shouldFire` 的约定），保持不触发。
        val one = engineAt(afternoon, roll = 1.0).evaluate(persona(), LevelPolicy(), afternoon)
        assertEquals(ProactiveReason.NOT_THIS_TICK, one.reason)
    }

    // ------------------------------------------------------------------ 心跳契约

    @Test
    fun `heartbeat contract cases match the engine probability`() {
        SpecCases.forEachCase("heartbeat") { _, input, expect ->
            val hour = input.int("hourOfDay") ?: 12
            val minutes = input.int("minutesSinceReset") ?: 0
            val now = localMillis(2026, 1, 1, hour, 0)
            val mood = input.obj("mood")?.let { mood ->
                MoodState(
                    label = mood.str("label").orEmpty(),
                    intensity = mood.int("intensity") ?: 2
                )
            } ?: MoodState()
            val perBeat = input.obj("perBeat")
            val intervalMs = perBeat?.let { beat ->
                val intervalMinutes = beat.double("intervalMinutes") ?: 0.5
                (intervalMinutes * 60_000.0).toLong()
            } ?: 60_000L
            val engine = ProactiveEngine(
                clock = Clock.fixed(Instant.ofEpochMilli(now), utc),
                policy = ProactivePolicy(tickIntervalMs = intervalMs),
                roll = { 0.0 }
            )
            val state = persona(
                lastInteractionAt = now - minutes * minute,
                mood = mood,
                sleepEnabled = false
            )
            val probability = engine.impulseProbability(state, LevelPolicy(level = 3), now)
            // 用例里的 `probability` 是「每分钟」的概率；带 `perBeat` 的用例把换算后的本拍概率
            // 单独放在 `perBeatProbability` 里。engine 的 impulseProbability 返回的都是本拍值。
            if (perBeat == null) {
                val expected = requireNotNull(expect.double("probability")) { "用例缺少 probability" }
                assertEquals("probability", expected, probability, 1e-9)
            } else {
                val expected = requireNotNull(expect.double("perBeatProbability")) { "用例缺少 perBeatProbability" }
                assertEquals("perBeatProbability", expected, probability, 1e-9)
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(utc).toEpochMilli()
}
