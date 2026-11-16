package com.yap.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KMP 改造（0.12.0）后**必须继续在 commonMain 成立**的决策与工具函数。
 *
 * 这些用例只依赖 Kotlin 标准库：JVM 上由 `:agentcore:jvmTest` 执行，同一个文件也会被
 * `iosSimulatorArm64Test` 编译（iOS 侧若将来接 CI，跑的是同一批断言）。
 *
 * 重点覆盖那些为了进 commonMain 而重写的实现：
 * - [VectorMath] 的定点编码（原来是 `java.nio.ByteBuffer`，现在手写小端字节序）；
 * - [VectorMath.topK] 的部分选择（原来是 `java.util.PriorityQueue`，现在是手写小顶堆）；
 * - [SleepPolicy] 的 `%02d` 文本（原来用 `String.format`）；
 * - [ChineseTerms] 的日历算术（原来是 `java.util.Calendar`，见 jvmTest 的逐日对照测试）。
 */
class KmpCommonDecisionsTest {

    // ------------------------------------------------------------------ VectorMath：编码

    @Test
    fun `vector encoding is little endian float32`() {
        val bytes = VectorMath.encode(floatArrayOf(1.0f, -2.5f))
        assertEquals(8, bytes.size)
        // 1.0f = 0x3F800000 → 小端：00 00 80 3F；-2.5f = 0xC0200000 → 00 00 20 C0。
        assertEquals(
            listOf<Byte>(0, 0, 0x80.toByte(), 0x3F),
            bytes.copyOfRange(0, 4).toList()
        )
        assertEquals(
            listOf<Byte>(0, 0, 0x20, 0xC0.toByte()),
            bytes.copyOfRange(4, 8).toList()
        )
    }

    @Test
    fun `non finite values are written as zero`() {
        val dirty = VectorMath.encode(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -1.5f))
        val clean = VectorMath.encode(floatArrayOf(0f, 0f, -1.5f))
        assertEquals(clean.toList(), dirty.toList())
    }

    @Test
    fun `decode round trips and rejects wrong lengths`() {
        val original = floatArrayOf(0f, 1f, -3.25f, 0.5f)
        val decoded = VectorMath.decode(VectorMath.encode(original), original.size)
        assertEquals(original.toList(), decoded?.toList())
        assertNull(VectorMath.decode(VectorMath.encode(original), original.size + 1))
        assertNull(VectorMath.decode(ByteArray(0), 1))
        assertNull(VectorMath.decode(ByteArray(4), 0))
    }

    // ------------------------------------------------------------------ VectorMath：topK

    @Test
    fun `topK keeps the highest scores in descending order`() {
        val scores = mapOf("a" to 1.0, "b" to 3.0, "c" to 2.0, "d" to 4.0)
        val result = VectorMath.topK(scores.keys, limit = 2) { scores.getValue(it) }
        assertEquals(listOf("d", "b"), result)
    }

    @Test
    fun `topK keeps the first of equal scores and drops non finite`() {
        val result = VectorMath.topK(listOf("x", "y", "z", "nan"), limit = 2) {
            if (it == "nan") Double.NaN else 1.0
        }
        // 同分先到先留；NaN 直接丢掉。
        assertEquals(listOf("x", "y"), result)
        assertEquals(emptyList(), VectorMath.topK(listOf("x"), limit = 0) { 1.0 })
    }

    @Test
    fun `topK returns fewer items than limit when candidates are fewer`() {
        assertEquals(listOf("only"), VectorMath.topK(listOf("only"), limit = 10) { 0.5 })
    }

    // ------------------------------------------------------------------ SleepPolicy

    @Test
    fun `wake clock text pads both halves`() {
        assertEquals("08:00", SleepPolicy.wakeClockText(true, null, null))
        assertEquals("07:05", SleepPolicy.wakeClockText(true, 23 * 60, 7 * 60 + 5))
        assertNull(SleepPolicy.wakeClockText(false, null, null))
    }

    @Test
    fun `sleep decision follows dnd and wake threshold`() {
        val asleep = SleepPolicy.evaluate(
            sleepEnabled = true,
            sleepMinutes = null,
            wakeMinutes = null,
            minuteOfDay = 23 * 60 + 45,
            dndOnSleep = false,
            recentUserMessages = 0,
            wakeThreshold = 3
        )
        assertTrue(asleep.asleep)
        assertFalse(asleep.shouldReply)
        assertTrue(asleep.marksRead)

        val woken = SleepPolicy.evaluate(
            sleepEnabled = true,
            sleepMinutes = null,
            wakeMinutes = null,
            minuteOfDay = 23 * 60 + 45,
            dndOnSleep = false,
            recentUserMessages = 3,
            wakeThreshold = 3
        )
        assertTrue(woken.woken)
        assertTrue(woken.shouldReply)

        val dnd = SleepPolicy.evaluate(
            sleepEnabled = true,
            sleepMinutes = null,
            wakeMinutes = null,
            minuteOfDay = 23 * 60 + 45,
            dndOnSleep = true,
            recentUserMessages = 99,
            wakeThreshold = 3
        )
        assertFalse(dnd.shouldReply)
        assertFalse(dnd.marksRead)
    }

    // ------------------------------------------------------------------ DeferReply / ToolArgClamp

    @Test
    fun `defer plan clamps minutes and truncates reason`() {
        val plan = DeferReply.plan(minutes = 9999, reason = "x".repeat(100), now = 1_000L)
        assertEquals(ToolArgClamp.MINUTES_MAX, plan.minutes)
        assertEquals(1_000L + ToolArgClamp.MINUTES_MAX * 60_000L, plan.earliestAt)
        assertEquals(DeferReply.WINDOW_MINUTES * 60_000L, plan.latestAt - plan.earliestAt)
        assertEquals(DeferReply.MAX_REASON_CHARS, plan.reason.length)

        val default = DeferReply.plan(minutes = null, reason = null, now = 0L)
        assertEquals(DeferReply.DEFAULT_MINUTES, default.minutes)
        assertEquals("", default.reason)
    }

    @Test
    fun `proactive level normalizes out of range input`() {
        assertEquals(1, ProactiveLevel.dailyCap(-3))
        assertEquals(5, ProactiveLevel.dailyCap(42))
        assertEquals(3, ProactiveLevel.dailyCap(3))
        assertEquals(0.70, ProactiveLevel.heartbeatWeight(0))
        assertEquals(1.30, ProactiveLevel.heartbeatWeight(9))
    }

    // ------------------------------------------------------------------ ChineseTerms：时间指代

    @Test
    fun `chinese time hints resolve to a span containing today`() {
        val now = 1_700_000_000_000L // 2023-11-14T22:13:20Z 附近，任何时区都落在同一天
        val today = ChineseTerms.timeSpanOf("今天", now)
        assertTrue(today != null && today.contains(now))
        assertTrue(ChineseTerms.hasTimeHint("上周三", now))
        assertTrue(ChineseTerms.hasTimeHint("去年冬天", now))
        assertTrue(ChineseTerms.hasTimeHint("3天前", now))
        assertNull(ChineseTerms.timeSpanOf("随便聊聊", now))
        assertNull(ChineseTerms.timeSpanOf("   ", now))
    }
}
