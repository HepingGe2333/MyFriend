package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已读不回」（`stay_silent`，`spec/mood/README.md` §4）的纯领域测试。
 *
 * 这里只钉住两件最容易写错的事：**连续几轮算太多**、**和 defer_reply 的语义不能混**。
 * 「不落 assistant 消息、只标已读」那部分在 `StaySilentContractTest` 里扫源码确认，
 * 因为真正的写入发生在 ChatRepository 的生成流程里（JVM 单测没有 Room）。
 */
class StaySilentTest {

    @Test
    fun `连续两轮之内允许沉默`() {
        assertTrue(StaySilent.allowed(0))
        assertTrue(StaySilent.allowed(1))
    }

    @Test
    fun `第三轮必须回一句`() {
        assertFalse(StaySilent.allowed(2))
        assertFalse("脏数据也不该放开限制", StaySilent.allowed(9))
    }

    @Test
    fun `计数封顶 不会越涨越大`() {
        assertEquals(1, StaySilent.nextStreak(0))
        assertEquals(2, StaySilent.nextStreak(1))
        assertEquals(2, StaySilent.nextStreak(2))
        assertEquals(2, StaySilent.nextStreak(99))
        assertEquals(1, StaySilent.nextStreak(-3))
    }

    @Test
    fun `允许沉默时明确要求什么都不要说`() {
        val text = StaySilent.allowedModelText("在忙")
        assertTrue(text.contains("不要再输出任何文字"))
        assertTrue(text.contains("已读"))
        assertTrue(text.contains("在忙"))
    }

    @Test
    fun `拒绝沉默时要求这一轮说一句 而不是当失败重试`() {
        val text = StaySilent.deniedModelText()
        assertTrue(text.contains("不能再沉默"))
        assertTrue(text.contains("回一句"))
        assertTrue("不要让它反复重试同一个工具", text.contains("不要解释"))
    }

    @Test
    fun `摘要只在模型侧使用`() {
        assertEquals("这一轮不回", StaySilent.summary(""))
        assertTrue(StaySilent.summary("太累了").startsWith("这一轮不回："))
    }

    // ------------------------------------------------------------- 工具协议

    @Test
    fun `stay_silent 已注册 且参数里只有可选的 reason`() {
        val spec = ToolCatalog.byName(ToolCatalog.STAY_SILENT)
        assertNotNull("工具注册表里必须有 stay_silent", spec)
        assertTrue("规范 §4：无参数或 reason 可选", spec!!.parametersJson.contains("\"reason\""))
        assertFalse(
            "reason 不是必填：模型不该因为没写理由就不能沉默",
            spec.parametersJson.contains("\"required\"")
        )
        assertTrue("工具描述要写清什么时候用", spec.description.contains("已读"))
        assertTrue("工具描述要写清什么时候不该用", spec.description.contains("不要用它逃避"))
    }

    @Test
    fun `工具描述把 stay_silent 与 defer_reply 区分开`() {
        val stay = ToolCatalog.byName(ToolCatalog.STAY_SILENT)!!
        val defer = ToolCatalog.byName(ToolCatalog.DEFER_REPLY)!!
        assertTrue("stay_silent 必须解释和 defer 的区别", stay.contrast.contains("defer_reply"))
        assertTrue("defer_reply 的语义不变：过一会儿再回 + 留念头", defer.description.contains("过一会儿"))
        assertFarNotEqual(stay.description, defer.description)

        // 「你能做什么」段由注册表生成：两个工具都要出现，且各自的区别说明都带着。
        val prompt = ToolCatalog.capabilityPrompt()
        assertTrue(prompt.contains(ToolCatalog.STAY_SILENT))
        assertTrue(prompt.contains(ToolCatalog.DEFER_REPLY))
        assertTrue(prompt.contains("和 defer_reply 的区别"))
    }

    @Test
    fun `原生工具 JSON 里也有 stay_silent`() {
        val json = ToolCatalog.toOpenAiToolsJson()
        assertTrue(json.contains("\"name\":\"stay_silent\""))
        assertTrue(json.contains("\"name\":\"defer_reply\""))
    }

    private fun assertFarNotEqual(a: String, b: String) {
        assertFalse("两个工具的描述不能是同一句", a == b)
    }
}
