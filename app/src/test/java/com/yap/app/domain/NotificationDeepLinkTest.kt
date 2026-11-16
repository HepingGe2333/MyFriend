package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 通知深链落点校验的测试（安全 P2-7 / 审计 P2-6）。
 *
 * `MainActivity` 是 `exported="true"` 的 LAUNCHER Activity，任意应用都能
 * `startActivity` 带 `extra_session_id`。以前它被直接交给导航，于是「不存在的 id → 空白会话页」、
 * 「别人的会话 → 被强推过去 + markRead + 撤通知」都成立。
 *
 * 这里钉住纯校验函数，再用源码断言钉住接线（MainActivity 必须经过 ChatRepository 的查询，
 * 不能直接信 extra；NavHost 只拿校验后的值）。
 */
class NotificationDeepLinkTest {

    @Test
    fun `不存在或空白的会话 id 直接丢弃`() {
        assertNull(NotificationDeepLink.sessionIdToOpen(null, "p1", "p1"))
        assertNull(NotificationDeepLink.sessionIdToOpen("", "p1", "p1"))
        assertNull(NotificationDeepLink.sessionIdToOpen("   ", "p1", "p1"))
        assertNull("会话不存在（查不到所属人格）就不能打开", NotificationDeepLink.sessionIdToOpen("s1", null, "p1"))
        assertNull(NotificationDeepLink.sessionIdToOpen("s1", "", "p1"))
    }

    @Test
    fun `会话属于当前 activePersona 才允许打开`() {
        assertEquals("s1", NotificationDeepLink.sessionIdToOpen(" s1 ", "p1", "p1"))
        assertNull("别人的会话不许被深链打开", NotificationDeepLink.sessionIdToOpen("s1", "p2", "p1"))
        assertNull("没有 activePersona 时不认任何深链", NotificationDeepLink.sessionIdToOpen("s1", "p1", null))
        assertNull(NotificationDeepLink.sessionIdToOpen("s1", "p1", "  "))
    }

    // ------------------------------------------------------------------ 接线

    @Test
    fun `MainActivity 不再直接信任 extra`() {
        val activity = source("src/main/java/com/yap/app/MainActivity.kt")
        assertEquals(
            "MainActivity 必须先问仓库「这个会话能不能打开」",
            1,
            Regex("""chatRepository\.resolveNotificationSession\(""").findAll(activity).count()
        )
        assertEquals(
            "onCreate / onNewIntent 都走 handleDeepLink(intent)（两处入口都要校验）",
            2,
            Regex("""handleDeepLink\(intent\)""").findAll(activity).count()
        )
        assertNull(
            "不能再直接把 getStringExtra 的结果写进 pendingSessionId",
            Regex("""pendingSessionId = intent\??\.getStringExtra""").find(activity)
        )
    }

    @Test
    fun `仓库校验真的查了会话与 activePersona`() {
        val repo = source("src/main/java/com/yap/app/data/repo/ChatRepository.kt")
        val start = repo.indexOf("suspend fun resolveNotificationSession(")
        assertTrue("ChatRepository 要有 resolveNotificationSession", start >= 0)
        val body = repo.substring(start, minOf(repo.length, start + 900))
        assertTrue("要查会话是否存在", body.contains("sessionDao.getById(candidate)"))
        assertTrue("要查当前 activePersona", body.contains("settings.first().activePersonaId"))
        assertTrue("判定走纯函数（同一套规则可单测）", body.contains("NotificationDeepLink.sessionIdToOpen("))
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }

    private fun projectRoot(): File {
        val candidates = listOf(File("."), File(".."))
        return candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到 app 模块目录：${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(projectRoot(), relative).readText()
}
