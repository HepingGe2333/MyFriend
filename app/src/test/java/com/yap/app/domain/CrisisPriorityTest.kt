package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 危机优先于沉默 / 延后（0.15）的纯领域测试。
 *
 * 钉住四件事：
 * 1. 危机轮里 `stay_silent` 与 `defer_reply` 被拦下（**执行之前**拦，副作用一个都不发生）；
 * 2. 危机轮必须有一段可见正文：模型说了就用模型的，一个字没说才用本地安全提示；
 * 3. 非危机轮完全不受影响（沉默/延后原样走，空正文仍然是空正文）；
 * 4. 「什么算危机」与 [SafetyGuard] 是同一条规则，不在两处各写一份。
 *
 * 真正的接线（不落「已读不回」卡片、标已读、不写 defer 念头、不造第 5 个气泡）在
 * [CrisisPriorityContractTest] 里扫源码确认——生成流程要 Room 与流式模型，JVM 单测跑不起来。
 */
class CrisisPriorityTest {

    /** 与 `R.string.safety_crisis_note` 的中文一致。 */
    private val note = "（先别一个人待着，身边有人吗？可以打 120。）"

    // ---------------------------------------------------------------- 拦下「不回」的工具

    @Test
    fun `危机轮拦下 stay_silent 与 defer_reply`() {
        assertTrue(CrisisPriority.blocksTool(isCrisis = true, toolName = "stay_silent"))
        assertTrue(CrisisPriority.blocksTool(isCrisis = true, toolName = "defer_reply"))
        assertTrue("大小写与空白都容忍", CrisisPriority.blocksTool(true, "  STAY_SILENT "))
        assertTrue(CrisisPriority.blocksTool(true, "DEFER_REPLY"))
    }

    @Test
    fun `危机轮不拦别的工具`() {
        listOf("send_message", "remember", "sticker", "search", "listen", "watch").forEach { tool ->
            assertFalse("$tool 不该被危机规则拦下", CrisisPriority.blocksTool(true, tool))
        }
    }

    @Test
    fun `非危机轮沉默与延后行为不变`() {
        assertFalse(
            "非危机时 stay_silent 必须原样执行",
            CrisisPriority.blocksTool(isCrisis = false, toolName = "stay_silent")
        )
        assertFalse(
            "非危机时 defer_reply 必须原样执行",
            CrisisPriority.blocksTool(isCrisis = false, toolName = "defer_reply")
        )
    }

    // ---------------------------------------------------------------- 这一轮的正文

    @Test
    fun `危机轮模型写了正文就用模型的正文`() {
        val body = "我在，别一个人待着。"
        assertEquals(body, CrisisPriority.replyBody(isCrisis = true, modelBody = body, note = note))
    }

    @Test
    fun `危机轮没有正文时用安全提示作为唯一正文`() {
        assertEquals(note, CrisisPriority.replyBody(true, "", note))
        assertEquals("只有空白也算没写", note, CrisisPriority.replyBody(true, "  \n\t ", note))
    }

    @Test
    fun `非危机轮绝不凭空造正文`() {
        assertEquals("", CrisisPriority.replyBody(isCrisis = false, modelBody = "", note = note))
        assertEquals("嗯", CrisisPriority.replyBody(false, "嗯", note))
    }

    @Test
    fun `提示句为空时不硬造正文`() {
        assertEquals("", CrisisPriority.replyBody(true, "", "   "))
        assertEquals("", CrisisPriority.replyBody(true, "", ""))
    }

    // ---------------------------------------------------------------- 判定规则只有一份

    @Test
    fun `危机判定沿用 SafetyGuard 的同一条规则`() {
        listOf(
            "我真的不想活了",
            "我想自杀",
            "我查过安眠药",
            "我觉得我消失比较好",
            "I want to kill myself"
        ).forEach { text ->
            assertTrue("「$text」应当判为危机轮", CrisisPriority.isCrisisTurn(text))
        }
        listOf(
            "想死你了",
            "今天累得要死",
            "晚安，明天见",
            "",
            "   "
        ).forEach { text ->
            assertFalse("「$text」不该判为危机轮", CrisisPriority.isCrisisTurn(text))
        }
    }

    // ---------------------------------------------------------------- 回灌给模型的说明

    @Test
    fun `被拦下时告诉模型不能沉默 也不要提工具`() {
        val text = CrisisPriority.blockedModelText()
        assertTrue("要说明这一轮不能沉默/晚点说", text.contains("不能沉默"))
        assertTrue("要求用他自己的语气回一句", text.contains("回一句"))
        assertTrue("不要向用户暴露工具", text.contains("不要提这个工具"))
    }
}
