package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * defer_reply（规格 §四）的边界：默认 45 分钟、夹 5–720、reason ≤80 字、窗口从到点再留半小时。
 *
 * 真正「不写助手消息、用户消息已读、生成一条 intent」由 ChatRepository + ToolExecutor 完成；
 * 这里钉住它们共用的那份时间计划，避免模型写一个荒唐的分钟数就让他沉默三天。
 */
class DeferReplyTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `没写 minutes 时默认 45 分钟`() {
        val plan = DeferReply.plan(minutes = null, reason = null, now = now)
        assertEquals(45, plan.minutes)
        assertEquals(now + 45 * 60_000L, plan.earliestAt)
        assertEquals(now + (45 + DeferReply.WINDOW_MINUTES) * 60_000L, plan.latestAt)
    }

    @Test
    fun `太少和太多都会被夹回 5 到 720`() {
        assertEquals(5, DeferReply.plan(minutes = 1, reason = null, now = now).minutes)
        assertEquals(5, DeferReply.plan(minutes = 0, reason = null, now = now).minutes)
        assertEquals(720, DeferReply.plan(minutes = 5000, reason = null, now = now).minutes)
        assertEquals(5, DeferReply.plan(minutes = -10, reason = null, now = now).minutes)
        assertEquals(90, DeferReply.plan(minutes = 90, reason = null, now = now).minutes)
    }

    @Test
    fun `reason 只留 80 字并去掉首尾空白`() {
        val long = "  " + "很".repeat(200) + "  "
        val plan = DeferReply.plan(minutes = 30, reason = long, now = now)
        assertEquals(80, plan.reason.length)
        assertTrue(!plan.reason.startsWith(" "))
    }

    @Test
    fun `模型调用了 defer_reply 才算这一轮不回`() {
        assertTrue(DeferReply.wasRequested(listOf("defer_reply")))
        assertTrue(DeferReply.wasRequested(listOf("think", " defer_reply ")))
        assertTrue(DeferReply.wasRequested(listOf("DEFER_REPLY")))
        assertTrue(!DeferReply.wasRequested(listOf("think", "web_search")))
        assertTrue(!DeferReply.wasRequested(emptyList()))
    }

    @Test
    fun `early 一定在 now 之后 窗口一定非空`() {
        val plan = DeferReply.plan(minutes = 5, reason = "现在不想说话", now = now)
        assertTrue(plan.earliestAt > now)
        assertTrue(plan.latestAt > plan.earliestAt)
    }
}
