package com.myfriend.app.data.repo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 聊天分页的**语义边界**守卫（读源码，不跑 Room）。
 *
 * 为什么用源码断言而不是 DAO 单测：本项目没有 Room/Robolectric 单测环境，
 * 但这次分页最容易写坏的不是 SQL 本身，而是三条「不能被窗口影响」的语义：
 *
 * 1. 首屏窗口必须有 `LIMIT`，往前翻必须用 `createdAt` 游标；
 * 2. **已读/未读必须走全表**（`unreadCount` 是会话列；`markUserMessagesRead` / `countUserMessagesSince`
 *    都不能带窗口条件），否则长会话的徽标数字会变小、免打扰判定会变形；
 * 3. 搜索 / 导出 / 历史压缩 / 备份这些「本来就要全量」的路径**保持全量**，不许被顺手改成窗口。
 */
class ChatPagingContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File("."), File(".."))
        val root = candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
        return File(root, relative).readText()
    }

    private val daos get() = source("src/main/java/com/myfriend/app/data/local/Daos.kt")
    private val chatRepo get() = source("src/main/java/com/myfriend/app/data/repo/ChatRepository.kt")

    /** 取某个函数声明**之前**的一段（Room 的 SQL 注解写在函数上面）。 */
    private fun before(text: String, marker: String, chars: Int = 500): String {
        val index = text.indexOf(marker)
        assertTrue("找不到 $marker", index >= 0)
        return text.substring(maxOf(0, index - chars), index)
    }

    @Test
    fun `首屏窗口只取最近一页`() {
        assertTrue(
            "observeRecentBySession 必须带 LIMIT :limit，否则还是整段会话进内存",
            daos.contains("fun observeRecentBySession(sessionId: String, limit: Int)")
        )
        assertTrue(
            "observeRecentBySession 的 SQL 里没有 LIMIT",
            before(daos, "fun observeRecentBySession(").contains("LIMIT :limit")
        )
    }

    @Test
    fun `往前翻页用 createdAt 游标`() {
        assertTrue(
            "pageBefore 必须用 createdAt 游标（且带 id 兜同一毫秒）",
            daos.contains("fun pageBefore(") &&
                daos.contains("createdAt < :beforeCreatedAt") &&
                daos.contains("createdAt = :beforeCreatedAt AND id < :beforeId")
        )
        assertTrue(
            "已加载区间用 observeSince 观察，编辑/删除才会跟着更新",
            daos.contains("fun observeSince(") && daos.contains("createdAt > :sinceCreatedAt")
        )
    }

    @Test
    fun `已读回执仍然覆盖全表的未读用户消息`() {
        assertTrue(
            "markUserMessagesRead 不能被窗口化：已读是一条 UPDATE，应当覆盖会话里所有未读用户消息",
            daos.contains(
                "UPDATE messages SET readAt = :now WHERE sessionId = :sessionId " +
                    "AND role = 'USER' AND readAt IS NULL"
            )
        )
        assertTrue("markUserMessagesRead 的签名不该带上窗口参数", daos.contains("suspend fun markUserMessagesRead(sessionId: String, now: Long): Int"))
    }

    @Test
    fun `未读计数与免打扰判定走全表 不看窗口`() {
        assertTrue(
            "未读数是 chat_sessions.unreadCount 列（随消息写入 +1 / 打开会话清零），与窗口无关",
            daos.contains("UPDATE chat_sessions SET unreadCount = unreadCount + 1 WHERE id = :id") &&
                daos.contains("UPDATE chat_sessions SET unreadCount = 0 WHERE id = :id")
        )
        val wakeThresholdQuery = before(daos, "suspend fun countUserMessagesSince(")
        assertTrue("免打扰的唤醒阈值必须 COUNT 全表", wakeThresholdQuery.contains("COUNT(*)"))
        assertTrue(
            "免打扰的计数不能被加 LIMIT（那会让「连着几条叫醒」判定随窗口截断）",
            !wakeThresholdQuery.contains("LIMIT")
        )
    }

    @Test
    fun `聊天页不再观察整段会话`() {
        assertTrue(
            "ChatRepository 里不应再出现整段观察（全部消息进 Flow）",
            !chatRepo.contains("messageDao.observeBySession")
        )
        assertTrue(
            "聊天页首屏走 observeLatestMessages",
            chatRepo.contains("fun observeLatestMessages(")
        )
        assertTrue(
            "加载更早走 messagesBefore（带 limit）",
            chatRepo.contains("fun messagesBefore(") &&
                chatRepo.contains("messageDao.pageBefore(")
        )
    }

    @Test
    fun `搜索 导出 历史压缩 备份 仍然是全量路径`() {
        assertTrue(
            "搜索仍应走 DAO 的全表粗筛（0.10.2 起是 searchCandidates + 内存精排，不是窗口）",
            chatRepo.contains("messageDao.searchCandidates(")
        )
        assertTrue("导出仍应取整段会话", chatRepo.contains("messageDao.getBySession(sessionId)"))
        val compressor = source("src/main/java/com/myfriend/app/data/repo/HistoryCompressor.kt")
        assertTrue(
            "历史压缩仍然按时间区间读（olderThan），不要被顺手改成窗口",
            compressor.contains("messageDao.olderThan(")
        )
        val backup = source("src/main/java/com/myfriend/app/data/repo/BackupRepository.kt")
        assertTrue("备份仍然逐会话读全部消息", backup.contains("messageDao.getBySession(session.id)"))
    }
}
