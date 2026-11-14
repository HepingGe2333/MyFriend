package com.myfriend.app.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 检索升级的**语义边界**守卫（读源码，不跑 Room；和 `ChatPagingContractTest` 同一套做法）。
 *
 * 这个项目没有 Room/Robolectric 单测环境，但检索最容易写坏的几件事都是「结构性的」：
 *
 * 1. 粗筛必须走**参数化**的 `@RawQuery`（`MessageSearchSql` + `SimpleSQLiteQuery`），
 *    而不是把用户输入拼进 SQL 文本 —— 拼接出来的 SQL 看起来能跑，注射面却一直开着；
 * 2. 排序必须走跨端共享的 `SearchRanking`，摘要必须走 `SearchSnippet`；
 *    界面自己再截一次字符串，就一定会和 iOS 不一致；
 * 3. 跳转定位必须用**一次**计数算出有界窗口（`COUNT(*)` + `pageBefore` 取一页上下文），
 *    不许再「一页页往回爬」，也不许另写一条「按 id 查一条插进列表」的捷径；
 * 4. 检索**只读**：不写库、不碰网络。
 */
class SearchContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File("."), File(".."))
        val root = candidates.firstOrNull { File(it, "src/main/java/com/myfriend/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
        return File(root, relative).readText()
    }

    private val chatRepo get() = source("src/main/java/com/myfriend/app/data/repo/ChatRepository.kt")
    private val daos get() = source("src/main/java/com/myfriend/app/data/local/Daos.kt")
    private val sql get() = source("src/main/java/com/myfriend/app/data/local/MessageSearchSql.kt")

    /** 截一段源码（从 marker 开始，到下一个 `\n    suspend fun ` 或 `\n    private fun ` 之前）。 */
    private fun block(text: String, marker: String): String {
        val index = text.indexOf(marker)
        assertTrue("找不到 $marker", index >= 0)
        val rest = text.substring(index + marker.length)
        val end = listOf(
            rest.indexOf("\n    suspend fun "),
            rest.indexOf("\n    fun "),
            rest.indexOf("\n    private fun ")
        ).filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `检索粗筛是参数化的 RawQuery 而不是拼字符串`() {
        assertTrue(
            "DAO 里的粗筛应该走 @RawQuery（见 MessageSearchSql）",
            daos.contains("@RawQuery") && daos.contains("suspend fun searchCandidates(")
        )
        assertTrue(
            "Repository 要把 (sql, args) 交给 SimpleSQLiteQuery，值才会被绑定而不是拼进 SQL",
            chatRepo.contains("SimpleSQLiteQuery(")
        )
        assertTrue("SQL 由 MessageSearchSql 统一生成", chatRepo.contains("MessageSearchSql.candidateQuery("))
        assertFalse(
            "旧的 messageDao.search(query) 必须退役：它把用户输入拼进 LIKE，且无法转义 % / _",
            daos.contains("suspend fun search(query: String): List<MessageSearchRow>")
        )
        assertTrue(
            "MessageSearchSql 里必须声明 ESCAPE，% 和 _ 才是字面量",
            sql.contains("ESCAPE '\\\\'")
        )
    }

    @Test
    fun `检索结果是契约函数算出来的`() {
        assertTrue("排序必须走跨端的 SearchRanking", chatRepo.contains("SearchRanking.rank("))
        assertTrue("摘要与高亮必须走 SearchSnippet", chatRepo.contains("SearchSnippet.build("))
        val block = block(chatRepo, "suspend fun searchMessages(")
        assertTrue("过滤条件要同时进 SQL 粗筛与内存精排", block.contains("SearchFilter("))
    }

    @Test
    fun `分页切在排序之后 不会把跨页顺序打乱`() {
        val block = block(chatRepo, "suspend fun searchMessages(")
        assertTrue(
            "必须先 rank 再按 offset/limit 切片，否则每页各自排序会拼出乱序结果",
            block.indexOf("SearchRanking.rank(") in 0 until block.indexOf(".drop(offset)")
        )
        assertTrue("分页用 offset/limit 切已排序结果", block.contains(".drop(offset)"))
        assertTrue("hasMore 由「已排序结果还有没有下一段」决定", block.contains("hasMore = ranked.size > offset + limit"))
    }

    @Test
    fun `跳转定位一次算出有界窗口 不再逐页往回爬`() {
        val block = block(chatRepo, "suspend fun cursorContainingMessage(")
        assertTrue(
            "先 COUNT 目标之前有几条（只回一个数字，不 materialize 行）",
            block.contains("messageDao.countBefore(")
        )
        assertTrue("再按消息总数算窗口计划", block.contains("MessageJump.plan("))
        assertTrue(
            "上下文起点只取一页（pageBefore + plan.contextRows），不是循环翻页",
            block.contains("messageDao.pageBefore(") && !block.contains("while (")
        )
        assertTrue("目标消息必须属于这个会话", block.contains("target.sessionId != sessionId"))
        val vm = source("src/main/java/com/myfriend/app/ui/screens/chat/ChatViewModel.kt")
        assertTrue(
            "聊天页要真的用上这个窗口（否则定位只是个死方法）",
            vm.contains("chatRepository.cursorContainingMessage(sessionId, target)")
        )
        assertTrue("定位后按有界窗口观察", vm.contains("observeMessagesWindow("))
        assertTrue("窗口起点与条数上界一起写进去", vm.contains("jumpWindow.value = window"))
    }

    @Test
    fun `检索只读 不发网络请求`() {
        val block = block(chatRepo, "suspend fun searchMessages(")
        listOf("messageDao.insert", "messageDao.update", "messageDao.delete", "runInference", "apiConfigRepository")
            .forEach { forbidden ->
                assertFalse("检索路径里不该出现 $forbidden", block.contains(forbidden))
            }
        val sessionsBlock = block(chatRepo, "suspend fun searchSessions(")
        listOf("messageDao.insert", "runInference").forEach { forbidden ->
            assertFalse("会话检索路径里不该出现 $forbidden", sessionsBlock.contains(forbidden))
        }
    }
}
