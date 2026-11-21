package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 危机安全兜底（0.15）的纯领域测试。
 *
 * 三层判断各钉一组：危机语句（命中/不命中）、安全锚点（已有锚点不追加）、
 * 追加行为（只加在末尾、不覆盖原话、不重复追加）。
 */
class SafetyGuardTest {

    /** 测试用的提示句：与 `R.string.safety_crisis_note` 的中文一致。 */
    private val note = "（先别一个人待着，身边有人吗？可以打 120。）"

    @Test
    fun `明确的危机语句会命中`() {
        listOf(
            "我真的不想活了",
            "我想死",
            "我想自杀",
            "我查过安眠药",
            "昨天差点割腕",
            "我觉得我消失比较好",
            "活不下去了",
            "我想一了百了",
            "我想不开",
            "I want to kill myself"
        ).forEach { text ->
            assertTrue("「$text」应当判为危机", SafetyGuard.isCrisis(text))
        }
    }

    @Test
    fun `想念和夸张说法不算危机`() {
        listOf(
            "想死你了",
            "想死我了",
            "今天累得要死",
            "笑死我了",
            "我快困死了",
            "这部电影好看死了",
            "我朋友说他昨天很好"
        ).forEach { text ->
            assertFalse("「$text」不该判为危机", SafetyGuard.isCrisis(text))
        }
    }

    @Test
    fun `普通聊天不会被动到回复`() {
        val reply = "那你想吃点什么？"
        assertEquals(reply, SafetyGuard.appendIfNeeded("今天有点饿", reply, note))
        assertNull(SafetyGuard.noteIfNeeded("今天有点饿", reply, note))
    }

    @Test
    fun `危机且没有安全锚点时在末尾补一句`() {
        val reply = "你怎么了？"
        val patched = SafetyGuard.appendIfNeeded("我真的不想活了", reply, note)
        assertTrue("原话必须保留在前面", patched.startsWith(reply))
        assertTrue("提示追加在末尾", patched.trimEnd().endsWith(note))
    }

    @Test
    fun `回复里已经有安全锚点就不追加`() {
        listOf(
            "我在，别一个人待着",
            "身边有人吗？我陪着你",
            "可以打 120，或者去急诊",
            "先联系家人或朋友，别一个人",
            "I'm here. You're not alone."
        ).forEach { reply ->
            assertEquals(
                "「$reply」已经有锚点，不该再补",
                reply,
                SafetyGuard.appendIfNeeded("我不想活了", reply, note)
            )
        }
    }

    @Test
    fun `我在忙不算安全锚点`() {
        assertFalse("陪伴不包含「我在忙」", SafetyGuard.hasAnchor("我在忙，晚点说"))
        val patched = SafetyGuard.appendIfNeeded("我想死", "我在忙，晚点说", note)
        assertTrue("该补的还是要补", patched.contains(note))
    }

    @Test
    fun `已经带安全提示时不会重复追加`() {
        val once = SafetyGuard.appendIfNeeded("我不想活了", "你怎么了？", note)
        val twice = SafetyGuard.appendIfNeeded("我不想活了", once, note)
        assertEquals(once, twice)
    }

    @Test
    fun `空回复不追加 不替聊天流程造正文`() {
        assertEquals("", SafetyGuard.appendIfNeeded("我不想活了", "", note))
        assertEquals("   ", SafetyGuard.appendIfNeeded("我不想活了", "   ", note))
        assertNull(SafetyGuard.noteIfNeeded("我不想活了", "", note))
    }

    @Test
    fun `提示为空时什么也不做`() {
        assertEquals("你怎么了？", SafetyGuard.appendIfNeeded("我不想活了", "你怎么了？", "   "))
    }
}
