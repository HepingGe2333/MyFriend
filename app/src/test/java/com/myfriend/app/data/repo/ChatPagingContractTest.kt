package com.myfriend.app.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 聊天分页的**语义边界**守卫（读源码，不跑 Room）。
 *
 * 0.12 起分页单位从「60 条滑窗」换成「一块」（规范 `spec/messages-blocks/README.md` §3），
 * 所以这里盯的东西也跟着换：
 *
 * 1. 一页 = 一块：DAO 必须按 `(sessionId, blockIndex)` 读，不许再出现 offset / 条数游标；
 * 2. 首屏只读最新块，向上/向下各加载一块（向下是这次新增的能力，不是「只能跳回最新」）；
 * 3. **已读/未读、免打扰阈值仍然走全表**：分页换单位不该让徽标数字或「他在睡觉」判断变形；
 * 4. 搜索 / 导出 / 历史压缩 / 备份这些「本来就要全量」的路径保持全量，不许被顺手改成窗口。
 */
class ChatPagingContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File("."), File(".."))
        val root = candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
        return File(root, relative).readText()
    }

    private val daos get() = source("src/main/java/com/myfriend/app/data/local/Daos.kt")
    private val blockDao get() = source("src/main/java/com/myfriend/app/data/local/MessageBlockDao.kt")
    private val chatRepo get() = source("src/main/java/com/myfriend/app/data/repo/ChatRepository.kt")
    private val chatVm get() = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatViewModel.kt")
    private val chatScreen get() = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatScreen.kt")

    /** `MessageDao` 那一段源码（避免误伤 `ApiUsageDao.observeSince` 这类同名方法）。 */
    private fun messageDaoBlock(): String {
        val start = daos.indexOf("interface MessageDao {")
        assertTrue("找不到 MessageDao", start >= 0)
        val end = daos.indexOf("interface MemoryDao {", start)
        assertTrue("找不到 MemoryDao", end >= 0)
        return daos.substring(start, end)
    }

    /** 取某个函数声明**之前**的一段（Room 的 SQL 注解写在函数上面）。 */
    private fun before(text: String, marker: String, chars: Int = 700): String {
        val index = text.indexOf(marker)
        assertTrue("找不到 $marker", index >= 0)
        return text.substring(maxOf(0, index - chars), index)
    }

    @Test
    fun `一页等于一块 读一块按 sessionId 加 blockIndex 定位`() {
        assertTrue(
            "MessageDao 要有「读某一块」",
            daos.contains("suspend fun blockOf(sessionId: String, blockIndex: Int)")
        )
        val sql = before(daos, "suspend fun blockOf(")
        assertTrue("读一块的 SQL 必须按 blockIndex 过滤：$sql", sql.contains("blockIndex = :blockIndex"))
        assertTrue("必须按稳定序返回块内消息", sql.contains("ORDER BY createdAt ASC, id ASC"))
    }

    @Test
    fun `分页不再用 offset 也不再用条数游标`() {
        assertFalse("offset 分页已经被区块分页取代", chatRepo.contains("offset = "))
        assertFalse("旧的 observeRecentBySession（首屏 60 条）已退役", daos.contains("fun observeRecentBySession("))
        assertFalse("旧的 pageBefore 游标翻页已退役", daos.contains("fun pageBefore("))
        assertFalse("旧的 observeSince（消息区间观察）已退役", messageDaoBlock().contains("fun observeSince("))
        assertFalse("旧的定位窗口 observeWindow 已退役", daos.contains("fun observeWindow("))
    }

    @Test
    fun `ChatRepository 暴露读某块与读相邻块`() {
        assertTrue(chatRepo.contains("suspend fun blockOf(sessionId: String, blockIndex: Int)"))
        assertTrue(chatRepo.contains("fun observeBlocks(sessionId: String, blockIndices: List<Int>)"))
        assertTrue("上一块/下一块要查块表（编号有空洞）", chatRepo.contains("suspend fun blocksAround("))
        assertTrue(chatRepo.contains("suspend fun lastBlockIndex("))
        assertTrue(chatRepo.contains("observeLastBlockIndex(sessionId: String)"))
        assertTrue(
            "「上一块」用 MAX(blockIndex) < 当前，不能写成 index - 1",
            blockDao.contains("SELECT MAX(blockIndex) FROM message_blocks WHERE sessionId = :sessionId AND blockIndex < :blockIndex")
        )
        assertTrue(
            "「下一块」用 MIN(blockIndex) > 当前",
            blockDao.contains("SELECT MIN(blockIndex) FROM message_blocks WHERE sessionId = :sessionId AND blockIndex > :blockIndex")
        )
    }

    @Test
    fun `首屏只加载最新块`() {
        assertTrue(
            "ViewModel 首屏走区块状态机的 onInitial（最新块）",
            chatVm.contains("MessageBlockPaging.onInitial(last, first)")
        )
        assertTrue(
            "最新块编号从块表观察，会话从空变成有一块时也能重新定位",
            chatVm.contains("chatRepository.observeLastBlockIndex(sessionId)")
        )
        assertTrue(
            "观察的是「已加载的那几块」而不是整段会话",
            chatVm.contains("chatRepository.observeBlocks(sessionId, blocks)")
        )
    }

    @Test
    fun `向上和向下各加载一块`() {
        assertTrue("向上加载一块", chatVm.contains("MessageBlockPaging.beginLoadOlder(window.value)"))
        assertTrue(chatVm.contains("chatRepository.blocksAround(sessionId, it).older"))
        assertTrue("向下加载一块（0.12 新增能力）", chatVm.contains("MessageBlockPaging.beginLoadNewer(window.value)"))
        assertTrue(chatVm.contains("chatRepository.blocksAround(sessionId, it).newer"))

        assertTrue("界面顶部有「加载更早」那一行", chatScreen.contains("LoadEarlierRow("))
        assertTrue("界面底部有「下面还有更新的消息」那一行", chatScreen.contains("LoadNewerRow("))
        assertTrue("底部那一行是加载入口（不是只能回到最新）", chatScreen.contains("onLoadNewer"))
        assertTrue(
            "滚到底自动加载下一块",
            chatScreen.contains("if (state.hasMoreNewer && !state.loadingNewer && isAtBottom) onLoadNewer()")
        )
        assertTrue(
            "文案不再是「回到最新」",
            source("src/main/res/values/strings.xml").contains("chat_load_newer") &&
                !source("src/main/res/values/strings.xml").contains("chat_back_to_latest")
        )
    }

    @Test
    fun `滚动锚定保住位置 并发生在向下加载那块也保留`() {
        assertTrue("加载更早之前记下第一条可见消息的 key 与偏移", chatScreen.contains("restoreAnchor = Anchor(key, info.offset, displayItems.size)"))
        assertTrue("加载完成按 key 找回原来那条消息的下标", chatScreen.contains("if (itemKey(item) == anchor.key)"))
        assertTrue("按记下的偏移滚回去", chatScreen.contains("listState.scrollToItem(index, anchor.offset)"))
        assertTrue(
            "向下加载走同一个「条数变化」的贴底逻辑（已加载块集合只增不减）",
            chatScreen.contains("LaunchedEffect(displayItems.size)")
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
        assertTrue(
            "markUserMessagesRead 的签名不该带上窗口参数",
            daos.contains("suspend fun markUserMessagesRead(sessionId: String, now: Long): Int")
        )
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
        assertTrue("ChatRepository 里不应再有「最近的 N 条」首屏查询", !chatRepo.contains("observeRecentBySession"))
    }

    @Test
    fun `搜索 导出 历史压缩 备份 仍然是全量路径`() {
        assertTrue(
            "搜索要逐块走（不是条数窗口，也不是候选池）",
            chatRepo.contains("messageDao.searchBlockMessages(") &&
                chatRepo.contains("blockDao.searchBlocks(")
        )
        assertFalse(
            "搜索路径里不许退化成整段会话读取",
            chatRepo.substring(
                chatRepo.indexOf("suspend fun searchMessages("),
                chatRepo.indexOf("suspend fun searchSessions(")
            ).contains("messageDao.getBySession(")
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
