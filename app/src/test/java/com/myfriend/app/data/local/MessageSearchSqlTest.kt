package com.myfriend.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索粗筛 SQL 的两条硬约束：**参数化**与**LIKE 转义**。
 *
 * 为什么这两条值得单独一组测试：
 *
 * 1. 参数化：检索框是唯一一个「用户随手输入、直接进 SQL」的地方。一旦有人图省事写成
 *    字符串拼接，`%`、`'`、`; DROP TABLE` 就都能进去。这里断言「用户输入永远不出现在
 *    SQL 文本里，只出现在绑定参数里」，比肉眼看一遍可靠。
 * 2. 转义：`%` 和 `_` 在 `LIKE` 里是通配符。用户搜「100%」时想找的是字面上的百分号，
 *    不转义就会变成「以 100 开头的所有消息」—— 结果看起来「有结果」，其实全错。
 *    另外 `\` 自己也要转义，否则以 `\` 结尾的输入会把 `ESCAPE` 吃掉。
 *
 * 这组是纯 JVM 单测：`MessageSearchSql` 只产出 (sql, args) 数据，不碰 Room，
 * 所以不需要模拟器（真正跑 SQL 的一致性由用户行为与 androidTest 覆盖）。
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
        val query = MessageSearchSql.candidateQuery("100%", limit = 30)
        // 模式必须以转义后的 \% 形式出现在**参数**里，SQL 文本里没有裸的 %value%。
        assertTrue("模式应进绑定参数：${query.args}", query.args.any { it == "%100\\%%" })
        assertFalse("SQL 里不该出现未转义的用户输入：${query.sql}", query.sql.contains("100%"))
    }

    // ------------------------------------------------------------------ 参数化

    @Test
    fun `用户输入只进绑定参数不进 SQL 文本`() {
        val evil = "'); DROP TABLE messages; --"
        val query = MessageSearchSql.candidateQuery(evil, limit = 10)
        assertFalse("SQL 文本里出现了用户输入，说明是拼接而不是绑定：${query.sql}", query.sql.contains("DROP"))
        assertFalse("SQL 文本里出现了用户输入：${query.sql}", query.sql.contains("evil"))
        assertTrue("恶意输入应该被完整地放进参数里：${query.args}", query.args.contains("%$evil%"))
        // 占位符个数必须和参数个数一致，否则 Room 运行时会抛参数不匹配。
        assertEquals("占位符与参数数量不一致", query.args.size, query.sql.count { it == '?' })
    }

    @Test
    fun `人格与时间过滤走绑定参数`() {
        val query = MessageSearchSql.candidateQuery(
            text = "harbin",
            personaId = "persona-1",
            since = 1_700_000_000_000L,
            until = 1_800_000_000_000L,
            limit = 30
        )
        assertTrue("人格过滤应进 SQL：${query.sql}", query.sql.contains("s.personaId = ?"))
        assertTrue("时间下界应进 SQL：${query.sql}", query.sql.contains("m.createdAt >= ?"))
        assertTrue("时间上界应进 SQL：${query.sql}", query.sql.contains("m.createdAt <= ?"))
        assertEquals("persona-1", query.args[0])
        assertEquals(1_700_000_000_000L, query.args[1])
        assertEquals(1_800_000_000_000L, query.args[2])
        assertEquals("LIMIT 应该是最后一个参数", 30, query.args.last())
    }

    @Test
    fun `空关键词不生成 SQL`() {
        assertEquals("", MessageSearchSql.candidateQuery("   ").sql)
        assertEquals(emptyList<Any?>(), MessageSearchSql.candidateQuery("   ").args)
        assertEquals("", MessageSearchSql.countQuery("").sql)
        assertEquals(emptyList<String>(), MessageSearchSql.likePatterns("  "))
    }

    // ------------------------------------------------------------------ 中文召回

    @Test
    fun `中文词元会额外生成 LIKE 模式`() {
        val patterns = MessageSearchSql.likePatterns("项目压力")
        assertTrue("整句模式必须在：$patterns", patterns.contains("%项目压力%"))
        assertTrue("词元「项目」应该单独有一个模式：$patterns", patterns.contains("%项目%"))
        assertTrue("词元「压力」应该单独有一个模式：$patterns", patterns.contains("%压力%"))
        assertTrue("模式不该重复", patterns.size == patterns.toSet().size)

        val query = MessageSearchSql.candidateQuery("项目压力", limit = 30)
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
            "词元模式最多 ${com.myfriend.app.domain.SearchRanking.MAX_QUERY_TERMS} 个（另加整句 1 个），实际 ${patterns.size}",
            patterns.size <= com.myfriend.app.domain.SearchRanking.MAX_QUERY_TERMS + 1
        )
    }

    @Test
    fun `计数查询与候选查询用同一套过滤`() {
        val count = MessageSearchSql.countQuery("harbin", personaId = "p1", since = 100L)
        assertTrue("计数要 COUNT(*) AS total：${count.sql}", count.sql.contains("COUNT(*) AS total"))
        assertTrue(count.sql.contains("s.personaId = ?"))
        assertTrue(count.sql.contains("m.createdAt >= ?"))
        assertFalse("计数不需要 LIMIT", count.sql.contains("LIMIT"))
        // 同一个入参下，两种查询的过滤参数前缀应该一致。
        val candidates = MessageSearchSql.candidateQuery("harbin", personaId = "p1", since = 100L, limit = 30)
        assertEquals(count.args, candidates.args.take(count.args.size))
    }
}
