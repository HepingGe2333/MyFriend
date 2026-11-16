package com.yap.app.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 后台路径的「语义边界」守卫。
 *
 * 0.10.0 为了省内存把后台改成只看「最近 40/120 条」，代价是「上次用户说话」这类字段
 * 在窗口里可能根本不存在（最近 40 条全是助手消息时就取不到）。0.10.1 改成精确查询：
 *
 * - 「对方上次说话」走 `lastUserMessage`（全表按时间倒序取第一条 USER）；
 * - 「上次说话时间」走 `lastUserMessageAt`；
 * - 「最近几条用户原话」走 `recentUserMessages(limit)`，而不是先取一堆行再筛。
 *
 * 窗口仍然用于「最近几句」这种本来就只要最近几条的地方——这个守卫保证不会再退回窗口里挑。
 */
class BackgroundWindowContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File("."), File(".."))
        val root = candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录")
        return File(root, relative).readText()
    }

    private val daos get() = source("src/main/java/com/yap/app/data/local/Daos.kt")

    /** Room 的 SQL 注解写在函数声明**上面**，所以往前取一段看。 */
    private fun before(text: String, marker: String, chars: Int = 400): String {
        val index = text.indexOf(marker)
        assertTrue("找不到 $marker", index >= 0)
        return text.substring(maxOf(0, index - chars), index)
    }

    @Test
    fun `DAO 提供精确的上次用户消息查询`() {
        assertTrue(
            "lastUserMessage 必须直接从全表按时间倒序取第一条用户消息",
            daos.contains("suspend fun lastUserMessage(sessionId: String): MessageEntity?")
        )
        val lastUserQuery = before(daos, "suspend fun lastUserMessage(sessionId: String)")
        assertTrue("lastUserMessage 的 SQL 要筛 role = 'USER'", lastUserQuery.contains("role = 'USER'"))
        assertTrue("lastUserMessage 要按时间倒序取第一条", lastUserQuery.contains("ORDER BY createdAt DESC LIMIT 1"))
        assertFalse("lastUserMessage 不能带窗口条件", lastUserQuery.contains("LIMIT :limit"))

        assertTrue(
            "lastUserMessageAt 用 MAX(createdAt) 精确取时间，不受窗口影响",
            daos.contains("suspend fun lastUserMessageAt(sessionId: String): Long?")
        )
        assertTrue(
            "lastUserMessageAt 的 SQL 要用 MAX(createdAt)",
            before(daos, "suspend fun lastUserMessageAt(").contains("MAX(createdAt)")
        )

        assertTrue(
            "recentUserMessages 直接按 role=USER 取 N 条，而不是先取 120 行再筛",
            daos.contains("suspend fun recentUserMessages(sessionId: String, limit: Int)")
        )
        val recentUserQuery = before(daos, "suspend fun recentUserMessages(")
        assertTrue("recentUserMessages 的 SQL 要筛 role = 'USER'", recentUserQuery.contains("role = 'USER'"))
        assertTrue("recentUserMessages 要用 LIMIT :limit", recentUserQuery.contains("LIMIT :limit"))
    }

    @Test
    fun `处境里的上次说话不再从窗口里挑`() {
        val agentLoop = source("src/main/java/com/yap/app/data/repo/AgentLoop.kt")
        assertTrue(
            "AgentLoop 必须用 lastUserMessage 取「对方上次说话」",
            agentLoop.contains("messageDao.lastUserMessage(session.id)")
        )
        assertTrue(
            "AgentLoop 的「你上次说话」也必须走精确查询（latestAssistant）",
            agentLoop.contains("messageDao.latestAssistant(session.id)")
        )
        assertTrue(
            "窗口只留给「最近几句」：仍然要保留 MessageWindows.lastN 的短窗口",
            agentLoop.contains("MessageWindows.lastN(")
        )
    }

    @Test
    fun `闲逛找话题用最近用户原话查询`() {
        val idle = source("src/main/java/com/yap/app/data/repo/IdleBrowsing.kt")
        assertTrue(
            "IdleBrowsing 必须走 recentUserMessages",
            idle.contains("messageDao.recentUserMessages(")
        )
        assertTrue(
            "不再先取 120 行再筛用户消息",
            !idle.contains("MessageWindows.IDLE_LOOKBACK_ROWS")
        )
    }
}
