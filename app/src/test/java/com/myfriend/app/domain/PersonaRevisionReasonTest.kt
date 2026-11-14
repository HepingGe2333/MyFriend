package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本历史 reason 的令牌编解码。
 *
 * 新数据存 `@rev:` 令牌（界面按语言翻译），老库里的中文 reason 解码返回 null、原样显示。
 */
class PersonaRevisionReasonTest {

    @Test
    fun `固定的几种原因都是令牌`() {
        val all = listOf(
            PersonaRevisionReason.CARD_CREATED,
            PersonaRevisionReason.CARD_EDITED,
            PersonaRevisionReason.CARD_BEFORE_ADJUST,
            PersonaRevisionReason.CREATED,
            PersonaRevisionReason.BEFORE_EDIT,
            PersonaRevisionReason.EDITED,
            PersonaRevisionReason.COPIED,
            PersonaRevisionReason.BEFORE_RESTORE,
            PersonaRevisionReason.BEFORE_EVOLUTION,
            PersonaRevisionReason.BASELINE,
            PersonaRevisionReason.IMPORTED
        )
        all.forEach { token ->
            assertTrue("$token 应该是令牌", PersonaRevisionReason.isToken(token))
            assertTrue("$token 里不该有中文", PersonaRevisionReason.decode(token) != null)
        }
    }

    @Test
    fun `恢复版本带版本号`() {
        val label = PersonaRevisionReason.decode(PersonaRevisionReason.restored(7))
        assertEquals(PersonaRevisionReason.Kind.RESTORED, label?.kind)
        assertEquals(7, label?.version)
    }

    @Test
    fun `自我调整与进化带着模型给的摘要`() {
        val adjusted = PersonaRevisionReason.decode(PersonaRevisionReason.selfAdjusted("话少一点了"))
        assertEquals(PersonaRevisionReason.Kind.SELF_ADJUSTED, adjusted?.kind)
        assertEquals("话少一点了", adjusted?.detail)

        val evolved = PersonaRevisionReason.decode(PersonaRevisionReason.evolved("更愿意主动开口"))
        assertEquals(PersonaRevisionReason.Kind.EVOLVED, evolved?.kind)
        assertEquals("更愿意主动开口", evolved?.detail)
    }

    @Test
    fun `摘要里的换行和冒号不会弄坏令牌`() {
        val raw = PersonaRevisionReason.selfAdjusted("第一行\n第二行: 还有冒号")
        val label = PersonaRevisionReason.decode(raw)
        assertEquals(PersonaRevisionReason.Kind.SELF_ADJUSTED, label?.kind)
        assertTrue(label?.detail?.contains('\n') == false)
        assertTrue(label?.detail?.contains(':') == false)
    }

    @Test
    fun `老库里的中文 reason 原样返回`() {
        assertNull(PersonaRevisionReason.decode("创建人格"))
        assertNull(PersonaRevisionReason.decode("恢复至 v3"))
        assertNull(PersonaRevisionReason.decode("自我调整：话少一点了"))
        assertNull(PersonaRevisionReason.decode(null))
        assertNull(PersonaRevisionReason.decode(""))
        assertFalse(PersonaRevisionReason.isToken("创建人格"))
    }

    @Test
    fun `令牌是稳定的`() {
        assertEquals(
            PersonaRevisionReason.selfAdjusted("x"),
            PersonaRevisionReason.selfAdjusted("x")
        )
        assertFalse(PersonaRevisionReason.CREATED.contains("创建"))
    }
}
