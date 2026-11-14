package com.myfriend.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知 id 分配规则的纯逻辑单测。
 *
 * 这里的重点不是「算得好看」，而是**两条消息不能顶掉对方**，
 * 以及会话通知和常驻通知不能落进同一个 id——通知互相覆盖是用户看不见的错误，
 * 只会表现为「他没回我」。
 */
class NotificationIdsTest {

    /** 常驻「他还在」通知的 id（AgentNotifier.NOTIFICATION_ID），与聊天区段必须分开。 */
    private val agentAliveId = 0x1F2E3D

    @Test
    fun `会话通知 id 落在自己的区段里且不碰常驻通知`() {
        val sessionId = "6f9c2a1e-3b7d-4c8e-9a10-2b4d6f8a0c12"
        val id = NotificationIds.chatId(sessionId)
        assertTrue("会话通知 id 应落在聊天区段，实际 $id", id in NotificationIds.CHAT_ID_BASE until NotificationIds.CHAT_ID_BASE + NotificationIds.ID_SPAN)
        assertTrue(
            "会话通知 id 不能撞上摘要区段，实际 $id",
            id < NotificationIds.CHAT_SUMMARY_ID_BASE
        )
        assertNotEquals("会话通知不能和常驻通知共用一个 id", agentAliveId, id)
    }

    @Test
    fun `摘要通知 id 落在摘要区段并避开常驻通知`() {
        val personaId = "a8b0c1d2-1111-4222-8333-944455556666"
        val id = NotificationIds.chatSummaryId(personaId)
        assertTrue(
            "摘要通知 id 应落在摘要区段，实际 $id",
            id in NotificationIds.CHAT_SUMMARY_ID_BASE until NotificationIds.CHAT_SUMMARY_ID_BASE + NotificationIds.ID_SPAN
        )
        assertTrue("摘要通知 id 不能是负数", id > 0)
        assertNotEquals("摘要通知不能和常驻通知共用一个 id", agentAliveId, id)
    }

    @Test
    fun `同一个会话永远得到同一个 id`() {
        val sessionId = "2b9e7f00-5c3a-4d61-b8f2-0e1d3c5a7b90"
        assertEquals(
            "同一个 sessionId 两次计算必须一致，否则通知会重复出两条",
            NotificationIds.chatId(sessionId),
            NotificationIds.chatId(sessionId)
        )
        assertEquals(
            "同一个 personaId 两次计算必须一致，否则会堆出多条摘要",
            NotificationIds.chatSummaryId("persona-1"),
            NotificationIds.chatSummaryId("persona-1")
        )
    }

    @Test
    fun `一堆真实形态的会话 id 互不碰撞`() {
        // 固定一批 UUID 形态的 id：算法是确定性的，这个断言本身也是确定性的，不是抽样碰运气。
        val sessionIds = (1..300).map { "00000000-0000-4000-8000-%012d".format(it) }
        val ids = sessionIds.map { NotificationIds.chatId(it) }
        assertEquals("会话通知 id 出现碰撞，后来的消息会覆盖先前的", sessionIds.size, ids.toSet().size)
    }

    @Test
    fun `不同 persona 的摘要 id 也互不碰撞`() {
        val personas = (1..300).map { "persona-%03d".format(it) }
        val ids = personas.map { NotificationIds.chatSummaryId(it) }
        assertEquals("摘要通知 id 出现碰撞", personas.size, ids.toSet().size)
    }

    @Test
    fun `相邻的会话 id 不会被挤到相邻的桶里`() {
        // 旧实现用 hashCode，形如 uuid-1 / uuid-2 的 id 常常挨在一起。
        val a = NotificationIds.bucket("6f9c2a1e-3b7d-4c8e-9a10-2b4d6f8a0c12")
        val b = NotificationIds.bucket("6f9c2a1e-3b7d-4c8e-9a10-2b4d6f8a0c13")
        assertNotEquals("只差一个字符的两个 id 落在同一个桶，说明 hash 分布太差", a, b)
        assertTrue("桶下标必须在区间内", a in 0 until NotificationIds.ID_SPAN)
        assertTrue("桶下标必须在区间内", b in 0 until NotificationIds.ID_SPAN)
    }

    @Test
    fun `分组名只由 personaId 决定`() {
        assertEquals("chat-p1", NotificationIds.chatGroup("p1"))
        assertNotEquals(
            "不同 persona 不能被折进同一组，否则两条摘要会互相顶掉",
            NotificationIds.chatGroup("p1"),
            NotificationIds.chatGroup("p2")
        )
    }
}
