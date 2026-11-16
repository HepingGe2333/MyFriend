package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落库之后「标不标未读、弹不弹通知」的唯一判定（审计 P1-1 的后果、P2-5）。
 *
 * 三条产物路径（聊天回复 / 常驻 agent / 规则型主动消息）共用这套语义：
 * 会话正开着时消息已经在屏幕上，**不弹通知**；App 不在前台时即使会话开着也要弹
 * （按 Home 退到桌面时聊天页还挂在栈上，只判断 openSession 会漏掉通知）。
 */
class ChatNotificationPolicyTest {

    @Test
    fun `会话开着时标已读且不弹通知`() {
        val delivery = ChatNotificationPolicy.delivery(
            openSessionId = "A",
            sessionId = "A",
            appInForeground = true
        )
        assertFalse("正在读的会话不该 +1 未读", delivery.markUnread)
        assertFalse("正在读的消息不该再弹一条系统通知", delivery.notify)
    }

    @Test
    fun `会话没开着时要未读与通知`() {
        val delivery = ChatNotificationPolicy.delivery(
            openSessionId = null,
            sessionId = "A",
            appInForeground = true
        )
        assertTrue(delivery.markUnread)
        assertTrue(delivery.notify)
    }

    @Test
    fun `开着别的会话时这一段要未读与通知`() {
        val delivery = ChatNotificationPolicy.delivery(
            openSessionId = "B",
            sessionId = "A",
            appInForeground = true
        )
        assertTrue(delivery.markUnread)
        assertTrue(delivery.notify)
    }

    @Test
    fun `App 不在前台时 即使会话开着也要通知`() {
        // 按 Home 退到桌面：聊天页仍在栈上，openSession 还是 A，但用户看不到。
        assertTrue(
            ChatNotificationPolicy.shouldNotify(
                openSessionId = "A",
                sessionId = "A",
                appInForeground = false
            )
        )
    }

    @Test
    fun `已读判定只看会话 不看前台`() {
        assertFalse(ChatNotificationPolicy.shouldMarkUnread("A", "A"))
        assertTrue(ChatNotificationPolicy.shouldMarkUnread("B", "A"))
        assertTrue(ChatNotificationPolicy.shouldMarkUnread(null, "A"))
    }

    @Test
    fun `P1-1 回归：openSession 被清成 null 会让正在读的会话收到通知`() {
        // 修复前：旧 VM 的 onCleared 把 openSession 设成 null → shouldNotify 为 true。
        val bug = ChatNotificationPolicy.delivery(
            openSessionId = null,
            sessionId = "A",
            appInForeground = true
        )
        assertTrue("这就是 P1-1 的现场，所以 compare-and-clear 必须成立", bug.notify)

        // 修复后：compare-and-clear 保住了 A → 不通知。
        val fixed = ChatNotificationPolicy.delivery(
            openSessionId = "A",
            sessionId = "A",
            appInForeground = true
        )
        assertFalse(fixed.notify)
    }
}
