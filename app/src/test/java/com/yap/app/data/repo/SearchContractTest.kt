package com.yap.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 检索的**语义边界**守卫（读源码，不跑 Room；和 `ChatPagingContractTest` 同一套做法）。
 *
 * 0.12 的检索契约（规范 `spec/messages-blocks/README.md` §4）：
 *
 * 1. **逐块**：先枚举块，再一条 SQL 读一块；不许再「一条 LIKE 扫全表 + LIMIT 500」；
 * 2. **无上限**：没有候选池常量，`hasMore` 由键集游标决定，界面不再说「只显示最近 N 条」；
 * 3. **参数化**：所有用户输入只进绑定参数（`MessageSearchSql` + `SimpleSQLiteQuery`）；
 * 4. **排序/摘要仍走跨端共享的** `SearchRanking` / `SearchSnippet`；
 * 5. 检索**只读**：不写库、不碰网络。
 */
class SearchContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File("."), File(".."))
        val root = candidates.firstOrNull { File(it, "src/main/java/com/yap/app").isDirectory }
            ?: error("找不到工程根目录：${File(".").absolutePath}")
        return File(root, relative).readText()
    }

    private val chatRepo get() = source("src/main/java/com/yap/app/data/repo/ChatRepository.kt")
    private val daos get() = source("src/main/java/com/yap/app/data/local/Daos.kt")
    private val blockDao get() = source("src/main/java/com/yap/app/data/local/MessageBlockDao.kt")
    private val sql get() = source("src/main/java/com/yap/app/data/local/MessageSearchSql.kt")

    /** 截一段源码（从 marker 开始，到下一个成员声明之前）。 */
    private fun block(text: String, marker: String): String {
        val index = text.indexOf(marker)
        assertTrue("找不到 $marker", index >= 0)
        val rest = text.substring(index + marker.length)
        val end = listOf(
            "\n    suspend fun ",
            "\n    private suspend fun ",
            "\n    private fun ",
            "\n    fun "
        ).filter { rest.indexOf(it) >= 0 }.minOfOrNull { rest.indexOf(it) } ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `检索先枚举块 再一条 SQL 读一块`() {
        assertTrue(
            "枚举块走 MessageSearchSql.blocksQuery（只回 sessionId/blockIndex）",
            chatRepo.contains("MessageSearchSql.blocksQuery(") && sql.contains("fun blocksQuery(")
        )
        assertTrue("块枚举走 blockDao", chatRepo.contains("blockDao.searchBlocks("))
        assertTrue(
            "读一块走 MessageSearchSql.blockScanQuery（blockIndex = ?）",
            chatRepo.contains("MessageSearchSql.blockScanQuery(") &&
                sql.contains("m.sessionId = ? AND m.blockIndex = ?")
        )
        assertTrue("读一块走 messageDao.searchBlockMessages", chatRepo.contains("messageDao.searchBlockMessages("))
        assertTrue("块枚举是 @RawQuery", blockDao.contains("suspend fun searchBlocks("))
        assertTrue("块内读取是 @RawQuery", daos.contains("suspend fun searchBlockMessages("))
    }

    @Test
    fun `逐块扫描的引擎是 BlockSearch 没有候选上限`() {
        assertTrue("遍历/选择在纯逻辑里（可单测）", chatRepo.contains("BlockSearch.page("))
        assertTrue("引擎逐块读", source("src/main/java/com/yap/app/domain/BlockSearch.kt").contains("for (block in source.blocks(filter))"))
        assertFalse("候选上限常量已删除", sql.contains("CANDIDATE_LIMIT"))
        assertFalse("仓库里不再有 500 条候选池", chatRepo.contains("CANDIDATE_LIMIT"))
        assertFalse("旧的 candidateQuery 已退役", sql.contains("fun candidateQuery("))
        assertFalse("旧的 countQuery 已退役（总数在逐块扫描时数出来）", sql.contains("fun countQuery("))
        assertTrue("总数是扫描出来的全部命中", chatRepo.contains("total = page.totalMatches"))
    }

    @Test
    fun `翻页用键集游标 hasMore 由游标决定`() {
        assertTrue("SearchQuery 带游标", chatRepo.contains("cursor: BlockSearch.Cursor? = null"))
        assertTrue("SearchPage 带下一页游标", chatRepo.contains("val nextCursor: BlockSearch.Cursor? = null"))
        assertTrue("hasMore 来自游标扫描结果", chatRepo.contains("hasMore = page.hasMore"))
        assertFalse("不再按 offset 切片", chatRepo.contains(".drop(offset)"))
        assertFalse("不再有 truncated（只显示最近 N 条）", chatRepo.contains("truncated"))
        val engine = source("src/main/java/com/yap/app/domain/BlockSearch.kt")
        assertTrue("游标四元组与排序键一致", engine.contains("(matchedTermCount, relevance, createdAt, id)"))
        assertTrue("下一页只收严格排在游标之后的行", engine.contains("sortsAfter(hit.cursor(), cursor)"))
    }

    @Test
    fun `界面不再显示只显示最近N条 改成已扫描全部历史`() {
        val screen = source("src/main/java/com/yap/app/ui/screens/search/SearchScreen.kt")
        val vm = source("src/main/java/com/yap/app/ui/screens/search/SearchViewModel.kt")
        assertTrue("说明文案改成「已扫描全部历史」", screen.contains("R.string.search_scanned_all_history"))
        assertFalse("旧的「只显示最近 N 条」已删除", screen.contains("R.string.search_truncated"))
        assertFalse("状态里不再有 truncated", vm.contains("truncated"))
        assertTrue(
            "全部扫完才显示说明",
            vm.contains("searched && !hasMore && !loading && hits.isNotEmpty()")
        )
        // 资源本身也要删掉，否则翻译里会留下一条永远不显示的文案。
        val zh = source("src/main/res/values/strings.xml")
        val en = source("src/main/res/values-en/strings.xml")
        assertFalse(zh.contains("search_truncated"))
        assertFalse(en.contains("search_truncated"))
        assertTrue(zh.contains("search_scanned_all_history") && en.contains("search_scanned_all_history"))
    }

    @Test
    fun `检索粗筛是参数化的 RawQuery 而不是拼字符串`() {
        assertTrue(
            "Repository 要把 (sql, args) 交给 SimpleSQLiteQuery，值才会被绑定而不是拼进 SQL",
            chatRepo.contains("SimpleSQLiteQuery(")
        )
        assertTrue(
            "SQL 由 MessageSearchSql 统一生成",
            sql.contains("fun blockScanQuery(") && sql.contains("fun blocksQuery(")
        )
        assertTrue(
            "MessageSearchSql 里必须声明 ESCAPE，% 和 _ 才是字面量",
            sql.contains("ESCAPE '\\\\'")
        )
        assertFalse(
            "旧的 messageDao.search(query) 必须退役：它把用户输入拼进 LIKE，且无法转义 % / _",
            daos.contains("suspend fun search(query: String): List<MessageSearchRow>")
        )
    }

    @Test
    fun `排序与摘要仍然是跨端契约函数`() {
        val engine = source("src/main/java/com/yap/app/domain/BlockSearch.kt")
        assertTrue("命中判定用共享词元规则", engine.contains("SearchRanking.matchedTerms("))
        assertTrue("相关度用共享实现", engine.contains("ChineseTerms.relevance("))
        assertTrue("摘要与高亮必须走 SearchSnippet", chatRepo.contains("SearchSnippet.build("))
        val block = block(chatRepo, "suspend fun searchMessages(")
        assertTrue("过滤条件要同时进 SQL 与内存精排", block.contains("SearchFilter("))
    }

    @Test
    fun `从检索定位到某个块 不再按条数窗口`() {
        val block = block(chatRepo, "suspend fun blockContainingMessage(")
        assertTrue("按消息的 blockIndex 定位", block.contains("target.blockIndex"))
        assertTrue("两侧边界来自块表", block.contains("blocksAround(sessionId, target.blockIndex)"))
        assertFalse("不再需要 COUNT(*) 算下标", block.contains("countBefore("))
        val vm = source("src/main/java/com/yap/app/ui/screens/chat/ChatViewModel.kt")
        assertTrue("聊天页真的用上它", vm.contains("chatRepository.blockContainingMessage(sessionId, target)"))
        assertTrue("定位后换成目标那一块", vm.contains("MessageBlockPaging.onJump("))
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
        assertTrue(
            "检索路径里的 SQL 只通过 MessageSearchSql 生成（不手拼字符串）",
            chatRepo.contains("MessageSearchSql.blocksQuery(") &&
                chatRepo.contains("MessageSearchSql.blockScanQuery(")
        )
    }
}
