package com.myfriend.app.data.repo

import com.myfriend.app.data.local.ProactiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveProtocolTest {

    @Test
    fun extractsDailyProposalAndStripsBlock() {
        val raw = """
            好呀，那我们就这样约定。

            每天到了那个时间，我会先来找你。

            ```myfriend-wake
            {"kind":"daily","time":"22:30","note":"每晚睡前问候","hint":"轻声问对方今天过得怎么样"}
            ```
        """.trimIndent()

        val (text, proposal) = ProactiveProtocol.extract(raw)

        assertEquals("好呀，那我们就这样约定。\n\n每天到了那个时间，我会先来找你。", text)
        val daily = requireNotNull(proposal) { "应当解析出 daily 提案" }
        assertEquals(ProactiveKind.DAILY, daily.kind)
        assertEquals(22 * 60 + 30, daily.timeOfDayMinutes)
        assertEquals("每晚睡前问候", daily.note)
        assertEquals("轻声问对方今天过得怎么样", daily.hint)
        assertTrue(!text.contains("myfriend-wake"))
    }

    @Test
    fun extractsIntervalProposal() {
        val raw = """
            那我每隔三小时来陪你一下。

            ```myfriend-wake
            {"kind":"interval","intervalHours":3,"note":"每隔三小时来陪你"}
            ```
        """.trimIndent()

        val (_, proposal) = ProactiveProtocol.extract(raw)

        val interval = requireNotNull(proposal) { "应当解析出 interval 提案" }
        assertEquals(ProactiveKind.INTERVAL, interval.kind)
        assertEquals(180, interval.intervalMinutes)
        assertNull(interval.timeOfDayMinutes)
        assertEquals("每隔三小时来陪你", interval.note)
        // 没写说明/指引时不再由协议层拼中文：仓储层会按界面语言补
        // `R.string.proactive_default_note_*` / `proactive_default_hint`（见 ProactiveRepository）。
        assertEquals("", interval.hint)
        assertEquals("", ProactiveProtocol.parse("""{"kind":"interval","intervalHours":3}""")!!.note)
    }

    @Test
    fun keepsPlainReplyUntouched() {
        val raw = "我在。\n\n你刚才说：「你好」。"
        val (text, proposal) = ProactiveProtocol.extract(raw)

        assertEquals(raw, text)
        assertNull(proposal)
    }

    @Test
    fun dropsInvalidBlockButKeepsText() {
        val raw = """
            我想陪你。

            ```myfriend-wake
            {"kind":"daily","time":"25:99"}
            ```
        """.trimIndent()

        val (text, proposal) = ProactiveProtocol.extract(raw)

        assertNull(proposal)
        assertEquals("我想陪你。", text)
    }

    @Test
    fun toleratesFullWidthColonAndSpaces() {
        val payload = """{"kind":"daily","time":" 07：05 "}"""
        val proposal = ProactiveProtocol.parse(payload)

        val daily = requireNotNull(proposal)
        assertEquals(7 * 60 + 5, daily.timeOfDayMinutes)
        // 说明由仓储层按当前语言生成（`R.string.proactive_default_note_daily`），协议层留空。
        assertEquals("", daily.note)
    }

    @Test
    fun rejectsOutOfRangeInterval() {
        assertNull(ProactiveProtocol.parse("""{"kind":"interval","intervalHours":0}"""))
        assertNull(ProactiveProtocol.parse("""{"kind":"interval","intervalHours":25}"""))
        assertNull(ProactiveProtocol.parse("""{"kind":"unknown"}"""))
        assertNull(ProactiveProtocol.parse("不是 JSON"))
    }

    @Test
    fun formatsTimeOfDayAroundMidnight() {
        assertEquals("00:00", ProactiveProtocol.formatTimeOfDay(0))
        assertEquals("22:30", ProactiveProtocol.formatTimeOfDay(22 * 60 + 30))
        assertEquals("23:59", ProactiveProtocol.formatTimeOfDay(1440 - 1))
    }

    @Test
    fun capabilityPromptMentionsProtocolBlock() {
        assertTrue(ProactiveProtocol.CAPABILITY_PROMPT.contains("myfriend-wake"))
        assertTrue(ProactiveProtocol.CAPABILITY_PROMPT.contains("intervalHours"))
    }
}
