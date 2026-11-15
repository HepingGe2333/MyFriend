package com.myfriend.app.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐块检索（规范 `spec/messages-blocks/README.md` §4）的单测。
 *
 * 这个文件专门回答四件事，也正是这次改造要证明的东西：
 *
 * 1. **跨块命中**：第 1 块和第 90 块里的命中都要出现（旧实现在候选池打满时会漏掉更早的）；
 * 2. **无上限**：构造 600 条命中，翻页能一条不漏地翻完（旧的 500 上限会截断）；
 * 3. **能过滤**：人格 / 时间的过滤在**枚举块**和**逐行判定**两层都生效（跨块也一样）；
 * 4. **逐块读**：假数据源记录每次 `messages()` 调用——每次只拿一个块键，
 *    所以「一条 SQL 只读一块」不是口头承诺。
 */
class BlockSearchTest {

    // ------------------------------------------------------------------ 假数据源

    private class FakeSource(
        private val blocks: List<Pair<BlockSearch.BlockKey, List<Pair<RankInput, String>>>>,
        /** 过滤时它「应该」返回哪些块：生产实现由 SQL 决定，这里模拟 SQL 的过滤结果。 */
        private val onlyBlocks: Set<BlockSearch.BlockKey> = blocks.map { it.first }.toSet()
    ) : BlockSearch.BlockScanSource<String> {

        val reads = mutableListOf<BlockSearch.BlockKey>()
        var maxRowsPerRead = 0
        var blockCalls = 0

        override suspend fun blocks(filter: SearchFilter): List<BlockSearch.BlockKey> {
            blockCalls++
            return onlyBlocks.toList().sortedWith(compareBy({ it.sessionId }, { it.blockIndex }))
        }

        override suspend fun messages(block: BlockSearch.BlockKey): List<Pair<RankInput, String>> {
            reads += block
            val rows = blocks.firstOrNull { it.first == block }?.second ?: emptyList()
            maxRowsPerRead = maxOf(maxRowsPerRead, rows.size)
            return rows
        }
    }

    private fun row(
        id: String,
        content: String,
        createdAt: Long,
        persona: String? = "p1",
        session: String = "s1"
    ): Pair<RankInput, String> = RankInput(id, content, createdAt, persona) to session

    /** 造一段「每块 200 条、共 [blockCount] 块」的历史，正文都命中「晚安」。 */
    private fun history(blockCount: Int, perBlock: Int = 200, session: String = "s1"): FakeSource {
        val blocks = (0 until blockCount).map { blockIndex ->
            val rows = (0 until perBlock).map { offset ->
                val position = blockIndex * perBlock + offset
                row(
                    id = "m$position",
                    content = "第 $position 条：晚安，早点睡",
                    createdAt = 1_000L + position,
                    session = session
                )
            }
            BlockSearch.BlockKey(session, blockIndex) to rows
        }
        return FakeSource(blocks)
    }

    // ------------------------------------------------------------------ 跨块

    @Test
    fun `第 1 块和第 90 块都命中时 两条都在结果里（旧实现会漏）`() = runTest {
        val blocks = (0 until 91).map { blockIndex ->
            val rows = (0 until 200).map { offset ->
                val position = blockIndex * 200 + offset
                val content = if (position == 5 || position == 18_005) {
                    "我们聊聊项目压力吧（第 $position 条）"
                } else {
                    "今天天气不错（第 $position 条）"
                }
                row(id = "m$position", content = content, createdAt = 1_000L + position)
            }
            BlockSearch.BlockKey("s1", blockIndex) to rows
        }
        val source = FakeSource(blocks)

        val page = BlockSearch.page(source, query = "项目压力", limit = 30)

        assertEquals("两条跨块命中都要出现", 2, page.hits.size)
        assertEquals(setOf("m5", "m18005"), page.hits.map { it.input.id }.toSet())
        assertEquals("总数也要是 2（不受候选池影响）", 2, page.totalMatches)
        assertFalse(page.hasMore)
        assertEquals("一共扫了 91 块", 91, page.scannedBlocks)
    }

    // ------------------------------------------------------------------ 无上限

    @Test
    fun `600 条命中可以一页一页翻完 一条不漏（旧实现的 500 上限会截断）`() = runTest {
        val source = history(blockCount = 3, perBlock = 200) // 600 条命中

        val pageSize = 50
        val collected = mutableListOf<String>()
        var cursor: BlockSearch.Cursor? = null
        var pages = 0
        while (true) {
            val page = BlockSearch.page(source, query = "晚安", limit = pageSize, cursor = cursor)
            pages++
            assertTrue("每页不超过 pageSize", page.hits.size <= pageSize)
            assertEquals("总数始终是全部命中（600）", 600, page.totalMatches)
            collected += page.hits.map { it.input.id }
            if (!page.hasMore) {
                assertNull(page.nextCursor)
                break
            }
            cursor = page.nextCursor
            assertTrue("翻页必须能继续", cursor != null)
            if (pages > 100) error("翻页没有收敛")
        }

        assertEquals("必须能翻到全部 600 条", 600, collected.size)
        assertEquals("不能重复", collected.size, collected.toSet().size)
        // 同一 createdAt 顺序下按时间倒序：最早的一条（m0）应该在最后一页。
        assertEquals("m0", collected.last())
        assertEquals(12, pages)
    }

    @Test
    fun `候选上限 500 的场景里第 501 条之后也能取到`() = runTest {
        val source = history(blockCount = 4) // 800 条命中

        // 一直翻到第 520 条附近，验证「更早的那 300 条」不是永远取不到。
        var cursor: BlockSearch.Cursor? = null
        val ids = mutableListOf<String>()
        repeat(11) {
            val page = BlockSearch.page(source, query = "晚安", limit = 50, cursor = cursor)
            ids += page.hits.map { it.input.id }
            cursor = page.nextCursor
        }
        assertTrue("已取到 550 条（> 旧上限 500）", ids.size >= 550)
        assertEquals("这 550 条互不重复", ids.size, ids.toSet().size)
        assertTrue("更早的命中（第 500 条之后）必须能取到", ids.last() != "m0")
    }

    // ------------------------------------------------------------------ 过滤

    @Test
    fun `人格过滤跨块生效 别人的消息不会露出来`() = runTest {
        val blocks = listOf(
            BlockSearch.BlockKey("s1", 0) to (0 until 200).map { index ->
                row("a$index", "晚安 a$index", 1_000L + index, persona = "p1", session = "s1")
            },
            BlockSearch.BlockKey("s2", 0) to (0 until 200).map { index ->
                row("b$index", "晚安 b$index", 1_000L + index, persona = "p2", session = "s2")
            }
        )
        // SQL 层只枚举 p1 的块（生产实现靠 chat_sessions join 做到这一点）
        val source = FakeSource(blocks, onlyBlocks = setOf(BlockSearch.BlockKey("s1", 0)))

        val page = BlockSearch.page(source, query = "晚安", limit = 30, filter = SearchFilter(personaId = "p1"))

        assertTrue("只出现 p1 的消息", page.hits.all { it.input.personaId == "p1" })
        assertEquals(200, page.totalMatches)
        assertEquals("只读 p1 的那一块", listOf(BlockSearch.BlockKey("s1", 0)), source.reads)
    }

    @Test
    fun `人格过滤在逐行判定层再守一次 SQL 改坏了也不会漏`() = runTest {
        // 数据源故意把两块都返回（模拟 SQL 过滤失效），引擎自己必须丢掉别人的行。
        val blocks = listOf(
            BlockSearch.BlockKey("s1", 0) to listOf(row("a1", "晚安 a1", 10, persona = "p1")),
            BlockSearch.BlockKey("s2", 0) to listOf(row("b1", "晚安 b1", 20, persona = "p2"))
        )
        val source = FakeSource(blocks, onlyBlocks = blocks.map { it.first }.toSet())

        val page = BlockSearch.page(source, query = "晚安", limit = 30, filter = SearchFilter(personaId = "p1"))

        assertEquals(listOf("a1"), page.hits.map { it.input.id })
        assertEquals(1, page.totalMatches)
    }

    @Test
    fun `时间过滤跨块生效`() = runTest {
        val blocks = (0 until 3).map { blockIndex ->
            BlockSearch.BlockKey("s1", blockIndex) to (0 until 200).map { offset ->
                val position = blockIndex * 200 + offset
                row("m$position", "晚安 $position", 1_000L + position)
            }
        }
        val source = FakeSource(blocks)

        val page = BlockSearch.page(
            source,
            query = "晚安",
            limit = 50,
            filter = SearchFilter(since = 1_000L + 500)
        )

        assertEquals("since 之下的 500 条被过滤掉", 100, page.totalMatches)
        assertTrue(page.hits.all { it.input.createdAt >= 1_500L })
    }

    // ------------------------------------------------------------------ 逐块读 / 内存上界

    @Test
    fun `每次 messages 调用只拿一个块键 一条 SQL 只读一块`() = runTest {
        val source = history(blockCount = 3)

        BlockSearch.page(source, query = "晚安", limit = 30)

        assertEquals("枚举一次", 1, source.blockCalls)
        assertEquals("三块就三次读", 3, source.reads.size)
        assertEquals("每次读的都是不同的块", 3, source.reads.toSet().size)
        assertTrue("单次读取不超过一块（200 条）", source.maxRowsPerRead <= MessageBlocks.BLOCK_SIZE)
    }

    @Test
    fun `一页只保留 pageSize 加一条 frontier 不会把整段历史攒在内存里`() = runTest {
        val source = history(blockCount = 20) // 4000 条命中

        val page = BlockSearch.page(source, query = "晚安", limit = 30)

        assertEquals("返回的只有一页", 30, page.hits.size)
        assertTrue(page.hasMore)
        assertEquals("但总数仍然是全部命中", 4_000, page.totalMatches)
        assertEquals("扫过 20 块", 20, page.scannedBlocks)
    }

    // ------------------------------------------------------------------ 游标稳定性

    @Test
    fun `翻页期间新增的消息不会造成重复或乱序`() = runTest {
        val blockKey = BlockSearch.BlockKey("s1", 0)
        val rows = ArrayList<Pair<RankInput, String>>()
        (0 until 200).forEach { index ->
            rows += row("m$index", "晚安 $index", 1_000L + index)
        }
        val source = FakeSource(listOf(blockKey to rows))

        val first = BlockSearch.page(source, query = "晚安", limit = 30)
        assertEquals("m199", first.hits.first().input.id)
        val cursor = first.nextCursor!!

        // 翻页途中来了三条新消息（时间更晚，所以排序更靠前）。
        rows += row("new-1", "晚安 新来的 1", 9_000L)
        rows += row("new-2", "晚安 新来的 2", 9_001L)
        rows += row("new-3", "晚安 新来的 3", 9_002L)

        val second = BlockSearch.page(source, query = "晚安", limit = 30, cursor = cursor)

        val overlap = first.hits.map { it.input.id }.intersect(second.hits.map { it.input.id }.toSet())
        assertTrue("第二页不能重复第一页的行：$overlap", overlap.isEmpty())
        assertTrue(
            "第二页仍然严格排在游标之后",
            second.hits.all { BlockSearch.sortsAfter(it.cursor(), cursor) }
        )
        assertTrue(
            "新消息不会被塞进已翻过的页里",
            second.hits.none { it.input.id.startsWith("new-") }
        )
    }

    @Test
    fun `游标比较与 SearchRanking 的排序键完全一致`() = runTest {
        val rows = listOf(
            row("a", "项目压力 项目压力", 100),
            row("b", "项目压力", 300),
            row("c", "项目", 200),
            row("d", "压力很大", 400)
        )
        val source = FakeSource(listOf(BlockSearch.BlockKey("s1", 0) to rows))

        val engine = BlockSearch.page(source, query = "项目压力", limit = 10)
        val contract = SearchRanking.rank(
            inputs = rows.map { it.first },
            query = "项目压力"
        )

        assertEquals(
            "引擎顺序必须与跨端契约 SearchRanking.rank 一致",
            contract.map { it.id },
            engine.hits.map { it.input.id }
        )
    }

    @Test
    fun `同分同时间时按 id 升序 保证跨设备稳定`() = runTest {
        val rows = listOf(
            row("z", "晚安", 100),
            row("a", "晚安", 100),
            row("m", "晚安", 100)
        )
        val source = FakeSource(listOf(BlockSearch.BlockKey("s1", 0) to rows))

        val page = BlockSearch.page(source, query = "晚安", limit = 10)

        assertEquals(listOf("a", "m", "z"), page.hits.map { it.input.id })
    }

    // ------------------------------------------------------------------ 空输入

    @Test
    fun `空关键词不扫库`() = runTest {
        val source = history(blockCount = 3)

        val page = BlockSearch.page(source, query = "   ", limit = 30)

        assertTrue(page.hits.isEmpty())
        assertEquals(0, page.scannedBlocks)
        assertTrue("一次块枚举都不该发生", source.reads.isEmpty())
        assertEquals(0, source.blockCalls)
    }

    @Test
    fun `没有块时返回空页而不是崩`() = runTest {
        val page = BlockSearch.page(FakeSource(emptyList()), query = "晚安", limit = 30)

        assertTrue(page.hits.isEmpty())
        assertFalse(page.hasMore)
        assertEquals(0, page.totalMatches)
    }

    @Test
    fun `空块不会让扫描中断`() = runTest {
        val blocks = listOf(
            BlockSearch.BlockKey("s1", 0) to emptyList(),
            BlockSearch.BlockKey("s1", 1) to listOf(row("m1", "晚安", 10))
        )
        val page = BlockSearch.page(FakeSource(blocks), query = "晚安", limit = 10)

        assertEquals(listOf("m1"), page.hits.map { it.input.id })
        assertEquals(2, page.scannedBlocks)
    }
}
