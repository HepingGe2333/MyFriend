package com.yap.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索 SQL 的硬约束：**参数化**、**LIKE 转义**、**一条 SQL 只读一块**。
 *
 * 0.12 起不再有「全表 LIKE + LIMIT 500」的候选查询，两条 SQL 各管一件事：
 *
 * - [MessageSearchSql.blocksQuery]：枚举块（只回 `(sessionId, blockIndex)`）；
 * - [MessageSearchSql.blockScanQuery]：读一块（`sessionId = ? AND blockIndex = ?`）。
 *
 * 这组是纯 JVM 单测：`MessageSearchSql` 只产出 (sql, args) 数据，不碰 Room。
 */
class MessageSearchSqlTest {

    // ------------------------------------------------------------------ 转义

    @Test
    fun `百分号被当作普通字符`() {
        assertEquals("100\\%", MessageSearchSql.escapeLike("100%"))
        assertEquals("%100\\%%", MessageSearchSql.likePattern("100%"))
    }

    @Test
    fun `下划线与反斜杠也转义`() {
        assertEquals("a\\_b", MessageSearchSql.escapeLike("a_b"))
        // 反斜杠自己先转义，否则 ESCAPE '\' 会把后一个字符吃掉。
        assertEquals("a\\\\b", MessageSearchSql.escapeLike("a\\b"))
        assertEquals("%a\\\\b%", MessageSearchSql.likePattern("a\\b"))
    }

    @Test
    fun `普通中文与字母原样保留`() {
        assertEquals("哈尔滨", MessageSearchSql.escapeLike("哈尔滨"))
        assertEquals("%harbin%", MessageSearchSql.likePattern("harbin"))
    }

    @Test
    fun `用户输入里的百分号不会变成通配符`() {
        val query = MessageSearchSql.blockScanQuery("100%", sessionId = "s1", blockIndex = 3)
        // 模式必须以转义后的 \% 形式出现在**参数**里，SQL 文本里没有裸的 %value%。
        assertTrue("模式应进绑定参数：${query.args}", query.args.any { it == "%100\\%%" })
        assertFalse("SQL 里不该出现未转义的用户输入：${query.sql}", query.sql.contains("100%"))
    }

    // ------------------------------------------------------------------ 参数化

    @Test
    fun `用户输入只进绑定参数不进 SQL 文本`() {
        val evil = "'); DROP TABLE messages; --"
        val query = MessageSearchSql.blockScanQuery(evil, sessionId = "s1", blockIndex = 0)
        assertFalse("SQL 文本里出现了用户输入，说明是拼接而不是绑定：${query.sql}", query.sql.contains("DROP"))
        assertFalse("SQL 文本里出现了用户输入：${query.sql}", query.sql.contains("evil"))
        assertTrue("恶意输入应该被完整地放进参数里：${query.args}", query.args.contains("%$evil%"))
        // 占位符个数必须和参数个数一致，否则 Room 运行时会抛参数不匹配。
        assertEquals("占位符与参数数量不一致", query.args.size, query.sql.count { it == '?' })
    }

    @Test
    fun `读一块的 SQL 只读一块 且按稳定序返回`() {
        val query = MessageSearchSql.blockScanQuery("harbin", sessionId = "s-1", blockIndex = 7)

        assertTrue("必须按会话定位", query.sql.contains("m.sessionId = ?"))
        assertTrue("必须按块定位", query.sql.contains("m.blockIndex = ?"))
        assertTrue("块内顺序稳定", query.sql.contains("ORDER BY m.createdAt ASC, m.id ASC"))
        assertFalse("一块就是一页，不需要 LIMIT（块上限由 BLOCK_SIZE 保证）", query.sql.contains("LIMIT"))
        assertEquals("会话参数在前", "s-1", query.args[0])
        assertEquals("块编号第二", 7, query.args[1])
    }

    @Test
    fun `时间过滤也走绑定参数`() {
        val query = MessageSearchSql.blockScanQuery(
            text = "harbin",
            sessionId = "s1",
            blockIndex = 0,
            since = 1_700_000_000_000L,
            until = 1_800_000_000_000L
        )
        assertTrue("时间下界应进 SQL：${query.sql}", query.sql.contains("m.createdAt >= ?"))
        assertTrue("时间上界应进 SQL：${query.sql}", query.sql.contains("m.createdAt <= ?"))
        assertTrue(query.args.contains(1_700_000_000_000L))
        assertTrue(query.args.contains(1_800_000_000_000L))
    }

    @Test
    fun `空关键词不生成 SQL`() {
        assertEquals("", MessageSearchSql.blockScanQuery("   ", sessionId = "s1", blockIndex = 0).sql)
        assertEquals(emptyList<Any?>(), MessageSearchSql.blockScanQuery("   ", sessionId = "s1", blockIndex = 0).args)
        assertEquals("", MessageSearchSql.recentCandidatesQuery("", limit = 10).sql)
        assertEquals(emptyList<String>(), MessageSearchSql.likePatterns("  "))
    }

    // ------------------------------------------------------------------ 枚举块

    @Test
    fun `枚举块只回定位信息 不做候选截断`() {
        val query = MessageSearchSql.blocksQuery()

        assertTrue("只选两列：${query.sql}", query.sql.contains("b.sessionId AS sessionId") && query.sql.contains("b.blockIndex AS blockIndex"))
        assertTrue("按会话+块升序", query.sql.contains("ORDER BY b.sessionId ASC, b.blockIndex ASC"))
        assertFalse("枚举块没有候选上限", query.sql.contains("LIMIT"))
        assertTrue("没有任何过滤时不带参数", query.args.isEmpty())
        assertEquals("占位符与参数数量一致", query.args.size, query.sql.count { it == '?' })
    }

    @Test
    fun `块枚举的人格与时间过滤走绑定参数`() {
        val query = MessageSearchSql.blocksQuery(
            personaId = "persona-1",
            since = 1_700_000_000_000L,
            until = 1_800_000_000_000L
        )

        assertTrue("人格过滤走 chat_sessions", query.sql.contains("s.personaId = ?"))
        assertTrue("块整体早于下界就跳过", query.sql.contains("b.lastCreatedAt >= ?"))
        assertTrue("块整体晚于上界就跳过", query.sql.contains("b.firstCreatedAt <= ?"))
        assertEquals("persona-1", query.args[0])
        assertEquals(1_700_000_000_000L, query.args[1])
        assertEquals(1_800_000_000_000L, query.args[2])
        assertEquals("占位符与参数数量一致", query.args.size, query.sql.count { it == '?' })
    }

    // ------------------------------------------------------------------ 中文召回 / 回忆

    @Test
    fun `中文词元会额外生成 LIKE 模式`() {
        val patterns = MessageSearchSql.likePatterns("项目压力")
        assertTrue("整句模式必须在：$patterns", patterns.contains("%项目压力%"))
        assertTrue("词元「项目」应该单独有一个模式：$patterns", patterns.contains("%项目%"))
        assertTrue("词元「压力」应该单独有一个模式：$patterns", patterns.contains("%压力%"))
        assertTrue("模式不该重复", patterns.size == patterns.toSet().size)

        val query = MessageSearchSql.blockScanQuery("项目压力", sessionId = "s1", blockIndex = 0)
        assertTrue("词元越多 LIKE 越多：${query.sql}", query.sql.count { it == '?' } >= 3)
        assertTrue(
            "必须声明 ESCAPE，转义后的 \\% 才是字面百分号：${query.sql}",
            query.sql.contains("ESCAPE '\\'")
        )
    }

    @Test
    fun `词元数量有上限 不会把 SQL 撑爆`() {
        val longQuery = "项目压力 工作 加班 失眠 焦虑 明天 天气 咖啡 猫 电影 音乐 旅行 哈尔滨 外套 雪"
        val patterns = MessageSearchSql.likePatterns(longQuery)
        assertTrue(
            "词元模式最多 ${com.yap.app.domain.SearchRanking.MAX_QUERY_TERMS} 个（另加整句 1 个），实际 ${patterns.size}",
            patterns.size <= com.yap.app.domain.SearchRanking.MAX_QUERY_TERMS + 1
        )
    }

    @Test
    fun `回忆用的有界查询与检索用的逐块查询是两条 SQL`() {
        val recall = MessageSearchSql.recentCandidatesQuery("harbin", personaId = "p1", limit = 20)
        assertTrue("回忆是启发式召回，保留 LIMIT", recall.sql.contains("LIMIT ?"))
        assertTrue("最近的在前", recall.sql.contains("ORDER BY m.createdAt DESC"))
        assertEquals("LIMIT 是最后一个绑定参数", 20, recall.args.last())
        assertFalse(
            "检索契约的逐块查询不能带 LIMIT（会静默少一条命中）",
            MessageSearchSql.blockScanQuery("harbin", "s1", 0).sql.contains("LIMIT")
        )
    }
}
