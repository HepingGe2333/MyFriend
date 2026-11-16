package com.yap.app.domain

import com.yap.app.domain.InterruptedReply.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 停止生成时那一轮该不该落库（审计 P2-8：不要再出现空气泡）。 */
class InterruptedReplyTest {

    @Test
    fun `没有正文也没有工具痕迹 什么都不写`() {
        assertEquals(Outcome.DISCARD, InterruptedReply.decide(visibleText = "", hasToolTraces = false))
        assertEquals(Outcome.DISCARD, InterruptedReply.decide(visibleText = "   \n ", hasToolTraces = false))
    }

    @Test
    fun `只有工具调用时落一条状态消息 而不是空洞气泡`() {
        assertEquals(Outcome.STATUS_ONLY, InterruptedReply.decide(visibleText = "", hasToolTraces = true))
        assertEquals(Outcome.STATUS_ONLY, InterruptedReply.decide(visibleText = "\n", hasToolTraces = true))
    }

    @Test
    fun `有可见正文照常存半截回复`() {
        assertEquals(Outcome.SAVE_TEXT, InterruptedReply.decide(visibleText = "我刚查了", hasToolTraces = true))
        assertEquals(Outcome.SAVE_TEXT, InterruptedReply.decide(visibleText = "嗯", hasToolTraces = false))
    }

    @Test
    fun `界面只在 停止 且正文为空时显示占位`() {
        assertTrue(InterruptedReply.showsStatusPlaceholder(content = "", isStopped = true))
        assertTrue(InterruptedReply.showsStatusPlaceholder(content = null, isStopped = true))
        assertFalse("有正文就显示正文", InterruptedReply.showsStatusPlaceholder(content = "半句话", isStopped = true))
        assertFalse("不是停止状态不显示", InterruptedReply.showsStatusPlaceholder(content = "", isStopped = false))
    }
}
