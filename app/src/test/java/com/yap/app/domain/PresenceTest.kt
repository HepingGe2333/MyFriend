package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「他此刻在做什么」的纯领域测试。
 *
 * 覆盖：每种状态的边界（刚醒 44/46 分钟、翻东西 14/16 分钟、消息 2/4 分钟、
 * 三小时的整点）、优先级顺序、坏输入（null、负数、超大值），以及「多久以前」的中文说法。
 *
 * 时间全部由参数传入，所以这些测试不依赖真实时钟。
 */
class PresenceTest {

    /** 一个固定的「现在」：2023-11-14 22:13:20 UTC。具体哪天不重要，重要的是前后一致。 */
    private val now = 1_700_000_000_000L

    private fun derive(
        asleepNow: Boolean = false,
        minutesSinceWake: Int? = null,
        minutesSinceLastTick: Int? = null,
        lastTickOutcome: String? = null,
        minutesSinceLastBrowse: Int? = null,
        minutesSinceLastMessage: Int? = null,
        minutesSinceLastAssistantMessage: Int? = null,
        hasPendingIntentSoon: Boolean = false,
        hourOfDay: Int = 12
    ): Presence = PresenceDeriver.derive(
        PresenceInput(
            asleepNow = asleepNow,
            minutesSinceWake = minutesSinceWake,
            minutesSinceLastTick = minutesSinceLastTick,
            lastTickOutcome = lastTickOutcome,
            minutesSinceLastBrowse = minutesSinceLastBrowse,
            minutesSinceLastMessage = minutesSinceLastMessage,
            minutesSinceLastAssistantMessage = minutesSinceLastAssistantMessage,
            hasPendingIntentSoon = hasPendingIntentSoon,
            hourOfDay = hourOfDay
        ),
        now
    )

    // ---------------------------------------------------------------- 睡着

    @Test
    fun `睡着的时候就说他睡着了`() {
        val presence = derive(asleepNow = true)
        assertEquals(PresenceKind.ASLEEP, presence.kind)
        assertEquals("睡着了", presence.text)
        assertTrue(presence.detail.isNotBlank())
        // 不知道他什么时候睡下的，就别硬编一个「…前」。
        assertEquals("睡着了", presence.display(now))
    }

    // ---------------------------------------------------------------- 刚醒

    @Test
    fun `起床四十四分钟还算刚醒`() {
        val presence = derive(minutesSinceWake = 44)
        assertEquals(PresenceKind.JUST_WOKE, presence.kind)
        assertEquals("刚醒", presence.text)
    }

    @Test
    fun `起床四十六分钟就不算刚醒了`() {
        assertEquals(PresenceKind.IDLE, derive(minutesSinceWake = 46).kind)
    }

    @Test
    fun `早上的刚醒和别的时候说法不一样`() {
        val morning = derive(minutesSinceWake = 5, hourOfDay = 7)
        val evening = derive(minutesSinceWake = 5, hourOfDay = 21)
        assertEquals(PresenceKind.JUST_WOKE, morning.kind)
        assertTrue(morning.detail.contains("一早"))
        assertTrue(evening.detail.contains("刚睡醒"))
    }

    // ---------------------------------------------------------------- 在写东西

    @Test
    fun `两分钟内有消息就在写东西`() {
        val presence = derive(minutesSinceLastMessage = 2)
        assertEquals(PresenceKind.WRITING, presence.kind)
        assertEquals("在写东西", presence.text)
    }

    @Test
    fun `四分钟前的消息就不算在写东西`() {
        assertEquals(PresenceKind.IDLE, derive(minutesSinceLastMessage = 4).kind)
    }

    @Test
    fun `他自己刚说完话也在写东西`() {
        val presence = derive(minutesSinceLastAssistantMessage = 1)
        assertEquals(PresenceKind.WRITING, presence.kind)
        // 是他自己说的，措辞该是「刚说完一句」而不是「对方刚说了话」。
        assertTrue(presence.detail.contains("刚说完"))
    }

    // ---------------------------------------------------------------- 在翻东西

    @Test
    fun `十四分钟前翻过东西还在翻`() {
        val presence = derive(minutesSinceLastBrowse = 14)
        assertEquals(PresenceKind.BROWSING, presence.kind)
        assertEquals("在翻东西", presence.text)
    }

    @Test
    fun `十六分钟前翻的就过去了`() {
        assertEquals(PresenceKind.IDLE, derive(minutesSinceLastBrowse = 16).kind)
    }

    @Test
    fun `从留下的那句话里也能看出他刚上过网`() {
        val presence = derive(minutesSinceLastTick = 5, lastTickOutcome = "自己上网看了看")
        assertEquals(PresenceKind.BROWSING, presence.kind)
    }

    // ---------------------------------------------------------------- 在想事情

    @Test
    fun `十四分钟前想过事情还在想`() {
        val presence = derive(minutesSinceLastTick = 14, lastTickOutcome = "什么都没做")
        assertEquals(PresenceKind.THINKING, presence.kind)
        assertEquals("在想事情", presence.text)
    }

    @Test
    fun `十六分钟前想的就不算正在想了`() {
        assertEquals(PresenceKind.IDLE, derive(minutesSinceLastTick = 16, lastTickOutcome = "什么都没做").kind)
    }

    // ---------------------------------------------------------------- 等时机

    @Test
    fun `心里记着事就在等合适的时机`() {
        val presence = derive(hasPendingIntentSoon = true)
        assertEquals(PresenceKind.WAITING, presence.kind)
        assertEquals("过一会儿想找你", presence.text)
        // 界面上不能出现「窗口」这种内部说法。
        assertFalse(presence.text.contains("窗口"))
        assertFalse(presence.detail.contains("窗口"))
    }

    // ---------------------------------------------------------------- 在忙别的

    @Test
    fun `整三小时没动静才叫在忙别的`() {
        assertEquals(PresenceKind.AWAY, derive(minutesSinceLastTick = 180).kind)
    }

    @Test
    fun `差一分钟的三小时还在发呆`() {
        assertEquals(PresenceKind.IDLE, derive(minutesSinceLastTick = 179).kind)
    }

    @Test
    fun `超长时间也是在忙别的`() {
        val presence = derive(minutesSinceLastTick = 600_000)
        assertEquals(PresenceKind.AWAY, presence.kind)
        assertEquals("在忙别的", presence.text)
    }

    // ---------------------------------------------------------------- 优先级

    @Test
    fun `优先级_睡着压过一切`() {
        val presence = derive(
            asleepNow = true,
            minutesSinceWake = 10,
            minutesSinceLastTick = 1,
            minutesSinceLastBrowse = 1,
            minutesSinceLastMessage = 1,
            minutesSinceLastAssistantMessage = 1,
            hasPendingIntentSoon = true
        )
        assertEquals(PresenceKind.ASLEEP, presence.kind)
    }

    @Test
    fun `优先级_刚醒压过在写东西`() {
        val presence = derive(
            minutesSinceWake = 30,
            minutesSinceLastMessage = 1,
            minutesSinceLastAssistantMessage = 1
        )
        assertEquals(PresenceKind.JUST_WOKE, presence.kind)
    }

    @Test
    fun `优先级_在写东西压过翻东西和想事情`() {
        val presence = derive(
            minutesSinceWake = 600,
            minutesSinceLastTick = 3,
            minutesSinceLastBrowse = 3,
            minutesSinceLastMessage = 2,
            hasPendingIntentSoon = true
        )
        assertEquals(PresenceKind.WRITING, presence.kind)
    }

    @Test
    fun `优先级_翻东西压过想事情`() {
        val presence = derive(
            minutesSinceLastTick = 2,
            minutesSinceLastBrowse = 5,
            minutesSinceLastMessage = 10,
            hasPendingIntentSoon = true
        )
        assertEquals(PresenceKind.BROWSING, presence.kind)
    }

    @Test
    fun `优先级_想事情压过等时机`() {
        val presence = derive(
            minutesSinceLastTick = 4,
            minutesSinceLastMessage = 20,
            hasPendingIntentSoon = true
        )
        assertEquals(PresenceKind.THINKING, presence.kind)
    }

    @Test
    fun `优先级_等时机压过在忙别的`() {
        val presence = derive(minutesSinceLastTick = 200, hasPendingIntentSoon = true)
        assertEquals(PresenceKind.WAITING, presence.kind)
    }

    @Test
    fun `优先级_忙别的压过发呆`() {
        val presence = derive(minutesSinceLastTick = 400)
        assertEquals(PresenceKind.AWAY, presence.kind)
    }

    // ---------------------------------------------------------------- 坏输入

    @Test
    fun `没有任何记录的时候就在发呆`() {
        val presence = derive()
        assertEquals(PresenceKind.IDLE, presence.kind)
        assertEquals("在发呆", presence.text)
        assertTrue(presence.isMeaningful())
        // 什么都没发生过：不该编一个「…前」出来。
        assertEquals(0L, presence.sinceAt)
    }

    @Test
    fun `负数分钟当作刚刚`() {
        val presence = derive(minutesSinceLastTick = -5)
        assertEquals(PresenceKind.THINKING, presence.kind)
        assertEquals(now, presence.sinceAt)
    }

    @Test
    fun `深夜发呆的说法不一样`() {
        val presence = derive(hourOfDay = 2)
        assertEquals(PresenceKind.IDLE, presence.kind)
        assertTrue(presence.detail.contains("没睡"))
    }

    // ---------------------------------------------------------------- 界面那一行

    @Test
    fun `界面那一行带上多久以前`() {
        val presence = derive(minutesSinceLastTick = 12)
        assertEquals("在想事情 · 12 分钟前", presence.display(now))
    }

    @Test
    fun `不知道从什么时候开始就只显示那句话`() {
        val presence = derive(asleepNow = true)
        assertEquals("睡着了", presence.display(now))
    }

    @Test
    fun `文案里不出现英文和工程词`() {
        val samples = listOf(
            derive(asleepNow = true),
            derive(minutesSinceWake = 5),
            derive(minutesSinceLastMessage = 1),
            derive(minutesSinceLastBrowse = 3),
            derive(minutesSinceLastTick = 3, lastTickOutcome = "什么都没做"),
            derive(hasPendingIntentSoon = true),
            derive(minutesSinceLastTick = 300),
            derive()
        )
        samples.forEach { presence ->
            val text = presence.text + presence.detail
            assertTrue("不该出现英文枚举名：$text", !Regex("[A-Za-z]").containsMatchIn(text))
            listOf("状态", "心跳", "轮询", "窗口").forEach { word ->
                assertFalse("不该出现工程词「$word」：$text", text.contains(word))
            }
        }
    }

    // ---------------------------------------------------------------- 多久以前

    @Test
    fun `刚刚与分钟`() {
        assertEquals("刚刚", relativeTime(now - 30_000L, now))
        assertEquals("3 分钟前", relativeTime(now - 3 * 60_000L, now))
        assertEquals("59 分钟前", relativeTime(now - 59 * 60_000L, now))
    }

    @Test
    fun `小时与昨天`() {
        assertEquals("1 小时前", relativeTime(now - 61 * 60_000L, now))
        assertEquals("23 小时前", relativeTime(now - 23 * 3_600_000L, now))
        assertEquals("昨天", relativeTime(now - 25 * 3_600_000L, now))
        assertEquals("3 天前", relativeTime(now - 3 * 86_400_000L, now))
    }

    @Test
    fun `上周与更久`() {
        assertEquals("昨天", relativeTime(now - 86_400_000L, now))
        assertEquals("上周", relativeTime(now - 8 * 86_400_000L, now))
        assertEquals("2 周前", relativeTime(now - 20 * 86_400_000L, now))
        assertEquals("2 个月前", relativeTime(now - 60 * 86_400_000L, now))
        assertEquals("很久以前", relativeTime(now - 400 * 86_400_000L, now))
    }

    @Test
    fun `相对时间的坏输入`() {
        // 0 / 负数表示「不知道什么时候」，留给界面自己决定不显示。
        assertEquals("", relativeTime(0L, now))
        assertEquals("", relativeTime(-1L, now))
        // 时钟回拨、时间戳在将来：当作刚刚，不抛异常。
        assertEquals("刚刚", relativeTime(now + 10_000L, now))
    }
}
