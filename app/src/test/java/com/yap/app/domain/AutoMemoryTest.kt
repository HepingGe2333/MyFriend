package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高置信自动记忆（0.15）的纯领域测试。
 *
 * 自动记忆只在「用户把长期事实说得很明确」时兜底，所以测试的重点不是「能记多少」，
 * 而是**不该记的千万别记**：第三人称、否定说法、普通闲聊都不能触发。
 *
 * 「模型已经调用 remember 就不重复写」发生在聊天流程里（`ChatRepository`），
 * 由 `AndroidGuardsContractTest` 扫源码钉住；这里钉规则本身与去重。
 */
class AutoMemoryTest {

    @Test
    fun `明确说自己过敏会被记住`() {
        val hit = AutoMemory.detect("对了，我对花生过敏，吃了会起疹子")
        assertNotNull("过敏是必须兜底的长期事实", hit)
        assertEquals(AutoMemory.Kind.ALLERGY, hit!!.kind)
        assertTrue("内容取原句片段", hit.content.contains("花生过敏"))
        assertTrue("importance 要在 4–5", hit.importance in 4..5)
        assertTrue("记忆抽屉据此标「自动记下」", hit.tags.contains(AutoMemory.TAG))
        assertTrue("标签带上类型，方便按「他忌讳什么」筛", hit.tags.contains(AutoMemory.Kind.ALLERGY.tag))
    }

    @Test
    fun `忌口和不能吃也算`() {
        assertEquals(AutoMemory.Kind.ALLERGY, AutoMemory.detect("我忌口，海鲜不能吃")?.kind)
        assertEquals(AutoMemory.Kind.ALLERGY, AutoMemory.detect("我不能吃辣")?.kind)
        assertEquals(AutoMemory.Kind.ALLERGY, AutoMemory.detect("我芒果过敏")?.kind)
    }

    @Test
    fun `生日 纪念日 约定会被记住`() {
        assertEquals(AutoMemory.Kind.BIRTHDAY, AutoMemory.detect("我的生日是三月五日")?.kind)
        assertEquals(AutoMemory.Kind.BIRTHDAY, AutoMemory.detect("我生日快到了，下周")?.kind)
        assertEquals("省略主语但句首就是生日，也是在说自己", AutoMemory.Kind.BIRTHDAY, AutoMemory.detect("生日是三月五号")?.kind)
        assertEquals(AutoMemory.Kind.ANNIVERSARY, AutoMemory.detect("我们的纪念日是五月二十号")?.kind)
        assertEquals(AutoMemory.Kind.ANNIVERSARY, AutoMemory.detect("纪念日是五月二十号")?.kind)
        assertEquals(AutoMemory.Kind.PROMISE, AutoMemory.detect("我答应你明年陪你去海边")?.kind)
        assertEquals(AutoMemory.Kind.PROMISE, AutoMemory.detect("我们说好了一直用这个暗号")?.kind)
    }

    @Test
    fun `正常闲聊不触发自动记忆`() {
        assertNull("累不是长期事实", AutoMemory.detect("今天好累"))
        assertNull("别人的生日不是「我」的事", AutoMemory.detect("他生日是三月五号"))
        assertNull("朋友忌口不是「我」忌口", AutoMemory.detect("我朋友不能吃辣"))
        assertNull("问句不是事实陈述", AutoMemory.detect("你生日是什么时候？"))
        assertNull("没有第一人称", AutoMemory.detect("不能吃太多"))
        assertNull("空消息", AutoMemory.detect("   "))
    }

    @Test
    fun `否定说法不触发`() {
        assertNull("我不过敏 = 没有这个事实", AutoMemory.detect("我不过敏"))
        assertNull(AutoMemory.detect("我没忌口，什么都能吃"))
    }

    @Test
    fun `很长的句子也会把命中的关键片段留下来`() {
        val long = "今天天气挺好的然后我想跟你说一件很重要的事情".repeat(4) + "我对花生过敏，吃了会起疹子"
        val hit = AutoMemory.detect(long)
        assertNotNull(hit)
        assertTrue("截断时不能把命中的「过敏」切掉", hit!!.content.contains("过敏"))
        assertTrue("片段要短到能进抽屉", hit.content.length <= AutoMemory.MAX_CONTENT_CHARS)
    }

    @Test
    fun `模型已经调用 remember 时不重复写`() {
        val message = "我对花生过敏"
        assertNull("模型记过就不再兜底", AutoMemory.detectThisTurn(message, rememberCalled = true))
        assertNotNull(
            "模型没记时才由规则兜底",
            AutoMemory.detectThisTurn(message, rememberCalled = false)
        )
    }

    @Test
    fun `同一件事再说一次算重复 不会写第二条`() {
        val existing = listOf("我对花生过敏")
        assertTrue(AutoMemory.isDuplicate(existing, "我对花生过敏，花生酱也不行"))
        assertTrue("标点/空白不同也算同一句", AutoMemory.isDuplicate(existing, "我对花生过敏！"))
        assertFalse("不同的事不算重复", AutoMemory.isDuplicate(existing, "我的生日是三月五日"))
        assertFalse("空内容不算重复", AutoMemory.isDuplicate(existing, "  "))
    }

    @Test
    fun `自动标签只认 auto`() {
        assertTrue(AutoMemory.isAuto(listOf("auto")))
        assertTrue(AutoMemory.isAuto(listOf("allergy", " AUTO ")))
        assertFalse(AutoMemory.isAuto(listOf("allergy")))
        assertFalse(AutoMemory.isAuto(emptyList()))
    }
}
