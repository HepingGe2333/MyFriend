package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * `memorize` 工具（0.15）：会话历史检索、主题段、时间衰减、`<回忆：…>` 渲染与长度上限。
 */
class MemorizeTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun msg(
        text: String,
        fromUser: Boolean = true,
        at: Long = now - day
    ) = Memorize.HistoryMessage(
        sessionId = "s1",
        sessionTitle = "和阿澈",
        fromUser = fromUser,
        content = text,
        at = at
    )

    @Test
    fun `关键词命中并返回原话`() {
        val result = Memorize.recall(
            messages = listOf(
                msg("我下个月要去哈尔滨出差"),
                msg("今天吃了面"),
                msg("那边很冷", fromUser = false)
            ),
            topics = emptyList(),
            query = "哈尔滨 出差",
            now = now
        )
        assertFalse(result.isEmpty)
        assertEquals(1, result.hits.size)
        assertTrue(result.text.startsWith(Memorize.RECALL_PREFIX))
        assertTrue(result.text.contains("哈尔滨"))
        assertTrue(result.text.endsWith(Memorize.RECALL_SUFFIX))
    }

    @Test
    fun `时间衰减让新消息排前面`() {
        val messages = listOf(
            msg("他说怕冷", at = now - 30 * day),
            msg("他说怕冷", fromUser = false, at = now - day)
        )
        val hits = Memorize.rank(messages, emptyList(), query = "怕冷", now = now)
        assertEquals(2, hits.size)
        assertTrue("较新的那条分数更高", hits[0].score > hits[1].score)
    }

    @Test
    fun `主题段参与检索并写在回忆开头`() {
        val topics = listOf(
            MemoryContract.TopicSegment("2026-11-18 晚上", "聊了他换工作，怕裸辞"),
            MemoryContract.TopicSegment("2026-11-10 下午", "聊了周末看房")
        )
        val result = Memorize.recall(
            messages = listOf(msg("还是想换工作")),
            topics = topics,
            query = "换工作",
            now = now
        )
        assertTrue(result.text.contains("2026-11-18 晚上"))
        assertTrue(result.text.contains("换工作"))
        assertEquals(1, result.topics.size)
    }

    @Test
    fun `什么都没命中时给出人话 不编造`() {
        val result = Memorize.recall(listOf(msg("今天吃了面")), emptyList(), "量子力学", now)
        assertTrue(result.isEmpty)
        assertTrue(result.text.contains("想不起来"))
        assertTrue(result.text.contains("不要编"))
        assertTrue(Memorize.empty("量子力学").startsWith(Memorize.RECALL_PREFIX))
    }

    @Test
    fun `空查询安全返回`() {
        val empty = Memorize.recall(listOf(msg("随便")), emptyList(), query = "  ", now = now)
        assertTrue(empty.isEmpty)
        assertEquals(emptyList<Memorize.Hit>(), Memorize.rank(listOf(msg("随便")), emptyList(), "", now))
    }

    @Test
    fun `一次回忆最多四条原话`() {
        val messages = (1..10).map { msg("项目压力很大，第 $it 次说") }
        val result = Memorize.recall(messages, emptyList(), "项目压力", now)
        assertEquals(Memorize.MAX_QUOTES, result.hits.size)
    }

    @Test
    fun `回忆文本有硬长度上限且保留尖括号`() {
        val long = "项目".repeat(400)
        val messages = (1..8).map { msg("$long $it") }
        val result = Memorize.recall(messages, emptyList(), "项目", now)
        assertTrue(result.text.length <= Memorize.MAX_RECALL_CHARS)
        assertTrue(result.text.startsWith(Memorize.RECALL_PREFIX))
        assertTrue(result.text.endsWith(Memorize.RECALL_SUFFIX))
    }

    @Test
    fun `检索线索压成一行并截到三百字`() {
        val query = Memorize.query("  第一行\n第二行  ")
        assertEquals("第一行 第二行", query)
        assertEquals(Memorize.MAX_QUERY_CHARS, Memorize.query("字".repeat(500)).length)
        assertEquals("", Memorize.query(null))
    }

    @Test
    fun `大小写与重复消息不重复占位`() {
        val messages = listOf(
            msg("Project X 的事"),
            msg("Project X 的事"),
            msg("project x 的事", fromUser = false)
        )
        val hits = Memorize.rank(messages, emptyList(), "Project X", now)
        // 用户/助手各一条，重复的用户消息只留一条。
        assertEquals(2, hits.size)
    }

    @Test
    fun `时间词 昨天 三天前 更早用日期`() {
        assertEquals("刚才", Memorize.timeWord(now, now))
        assertEquals("今天", Memorize.timeWord(now - 1_000L, now))
        assertEquals("昨天", Memorize.timeWord(now - day, now))
        assertEquals("3 天前", Memorize.timeWord(now - 3 * day, now))
        val older = Memorize.timeWord(now - 30 * day, now, ZoneId.of("Asia/Shanghai"))
        assertTrue("更早用绝对日期：$older", Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(older))
    }

    @Test
    fun `分词保留中文二元组与英文单词`() {
        val tokens = Memorize.tokensOf("加班 project stress")
        assertTrue(tokens.contains("加班"))
        assertTrue(tokens.contains("project"))
        assertTrue("虚词二元组被过滤", !tokens.contains("的了"))
    }
}
