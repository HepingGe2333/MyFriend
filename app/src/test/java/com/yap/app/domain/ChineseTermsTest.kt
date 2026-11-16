package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 中文词元与时间指代的纯逻辑单测。
 *
 * 这些东西决定了「他能不能顺着半句话想起一件旧事」，出错不会崩，但会让他显得记性差，
 * 所以拆词、虚词过滤、英文、空串、超长、重复、时间换算都要钉死。
 */
class ChineseTermsTest {

    private val day = 86_400_000L

    /** 用本机时区的「2026-03-15 12:00」当 now：不依赖跑测试的机器当前时间。 */
    private val now = localTime(2026, 2, 15, 12)

    private val sameTerms = setOf("项目", "目压", "压力")

    private fun localTime(year: Int, month0: Int, dayOfMonth: Int, hour: Int): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month0, dayOfMonth, hour, 0, 0)
        return cal.timeInMillis
    }

    // ---------------------------------------------------------------- 1. 二元组拆分

    @Test
    fun `中文按字二元组切分`() {
        val terms = ChineseTerms.terms("项目压力").map { it.term }.toSet()

        assertEquals(sameTerms, terms)
    }

    @Test
    fun `单个汉字也能成为词元`() {
        val terms = ChineseTerms.terms("猫")

        assertEquals(1, terms.size)
        assertEquals("猫", terms.first().term)
        assertEquals(1.0, terms.first().weight, 0.0001)
    }

    @Test
    fun `删除虚词后实词贴在一起`() {
        val terms = ChineseTerms.terms("我觉得这个项目压力很大").map { it.term }

        assertTrue("「项目」应该被保留，实际 $terms", "项目" in terms)
        assertTrue("「压力」应该被保留，实际 $terms", "压力" in terms)
        assertFalse("虚词不该变成词元，实际 $terms", terms.any { it.contains("的") || it.contains("很") })
    }

    @Test
    fun `否定词不会被当成虚词删掉`() {
        val terms = ChineseTerms.terms("不喜欢").map { it.term }

        assertTrue("「喜欢」应该还在，实际 $terms", "喜欢" in terms)
        assertTrue("「不」是语义的一部分，不该被删，实际 $terms", terms.any { it.startsWith("不") })
    }

    @Test
    fun `纯虚词和纯标点没有词元`() {
        assertTrue(ChineseTerms.terms("的了吗呢吧啊").isEmpty())
        assertTrue(ChineseTerms.terms("，。！？、；：").isEmpty())
        assertTrue(ChineseTerms.terms("   ").isEmpty())
        assertTrue(ChineseTerms.terms("").isEmpty())
    }

    // ---------------------------------------------------------------- 2. 英文与混合

    @Test
    fun `英文按小写单词切`() {
        val terms = ChineseTerms.terms("Kotlin Compose Telegram").map { it.term }

        assertEquals(listOf("compose", "kotlin", "telegram"), terms.sorted())
    }

    @Test
    fun `英文虚词被过滤`() {
        val terms = ChineseTerms.terms("the project is good").map { it.term }.toSet()

        assertEquals(setOf("project", "good"), terms)
    }

    @Test
    fun `中英混排各自成词元`() {
        val terms = ChineseTerms.terms("我在杭州做 backend 开发").map { it.term }

        assertTrue("中文实词要留下，实际 $terms", "杭州" in terms)
        assertTrue("英文单词要留下，实际 $terms", "backend" in terms)
    }

    // ---------------------------------------------------------------- 3. 重复与归一化

    @Test
    fun `重复出现的词元权重更高且不超过 1`() {
        val weights = ChineseTerms.weights("项目压力 项目")

        assertEquals(1.0, weights["项目"]!!, 0.0001)
        assertTrue("只出现一次的「目压」应该更轻", weights["目压"]!! < weights["项目"]!!)
        assertTrue(weights.values.all { it in 0.0..1.0 })
    }

    @Test
    fun `超长文本被截断且不抛异常`() {
        val long = "项目压力".repeat(400)

        val terms = ChineseTerms.terms(long)

        assertTrue("词元数应该有上限，实际 ${terms.size}", terms.size <= ChineseTerms.MAX_TERMS_PER_TEXT)
        assertTrue(terms.isNotEmpty())
    }

    @Test
    fun `查询词元去重且有上限`() {
        val longQuery = "项目压力".repeat(50)

        val terms = ChineseTerms.queryTerms(longQuery, max = 5)

        assertEquals(terms.distinct(), terms)
        assertTrue(terms.size <= 5)
    }

    // ---------------------------------------------------------------- 4. 相关度与余弦

    @Test
    fun `加权余弦与相关度的基本性质`() {
        val same = ChineseTerms.cosine(
            ChineseTerms.weights("项目压力"),
            ChineseTerms.weights("项目压力")
        )
        assertEquals(1.0, same, 0.0001)

        assertEquals(
            0.0,
            ChineseTerms.cosine(ChineseTerms.weights("项目压力"), ChineseTerms.weights("宇宙飞船")),
            0.0001
        )
        assertEquals(
            "空向量不该除零",
            0.0,
            ChineseTerms.cosine(emptyMap(), ChineseTerms.weights("项目压力")),
            0.0001
        )
    }

    @Test
    fun `相关度对换一种说法也给出分数`() {
        val exact = ChineseTerms.relevance("项目压力", "项目压力")
        val paraphrase = ChineseTerms.relevance("项目压力", "最近项目压力很大，睡不着")
        val unrelated = ChineseTerms.relevance("项目压力", "对方喜欢在雨天听老歌")

        assertEquals(1.0, exact, 0.0001)
        assertTrue("换个说法也该有分，实际 $paraphrase", paraphrase > 0.3)
        assertEquals(0.0, unrelated, 0.0001)
        assertEquals("空查询一律 0", 0.0, ChineseTerms.relevance("", "项目压力"), 0.0001)
    }

    // ---------------------------------------------------------------- 5. 时间指代

    @Test
    fun `昨天与前天各自是一天`() {
        val yesterday = ChineseTerms.timeSpanOf("昨天", now)
        assertNotNull(yesterday)
        assertTrue("应该包含昨天中午", yesterday!!.contains(now - day))
        assertFalse("不该包含今天", yesterday.contains(now))
        assertEquals("区间应该正好一天", day.toDouble(), (yesterday.to - yesterday.from).toDouble(), 1.0)

        val before = ChineseTerms.timeSpanOf("前天", now)!!
        assertTrue(before.contains(localTime(2026, 2, 13, 15)))
    }

    @Test
    fun `上个月是上一个自然月`() {
        val span = ChineseTerms.timeSpanOf("上个月", now)

        assertNotNull(span)
        assertTrue("应该包含二月", span!!.contains(localTime(2026, 1, 10, 9)))
        assertFalse("不该包含本月", span.contains(localTime(2026, 2, 2, 9)))
    }

    @Test
    fun `去年冬天是跨年的那一段`() {
        val span = ChineseTerms.timeSpanOf("去年冬天", now)

        assertNotNull(span)
        assertTrue("应该包含去年十二月", span!!.contains(localTime(2025, 11, 20, 9)))
        assertTrue("也该包含今年一月", span.contains(localTime(2026, 0, 15, 9)))
        assertFalse("不该包含今年夏天", span.contains(localTime(2026, 6, 15, 9)))
    }

    @Test
    fun `上周三是上一周的周三`() {
        val span = ChineseTerms.timeSpanOf("上周三", now)

        assertNotNull(span)
        assertTrue("应该包含三月四日", span!!.contains(localTime(2026, 2, 4, 15)))
        assertFalse("不该包含本周三", span.contains(localTime(2026, 2, 11, 15)))
    }

    @Test
    fun `数字天数与最近都能解析`() {
        val threeDaysAgo = ChineseTerms.timeSpanOf("三天前", now)!!
        assertTrue(threeDaysAgo.contains(localTime(2026, 2, 12, 9)))

        val recent = ChineseTerms.timeSpanOf("最近这些天", now)!!
        assertTrue(recent.contains(now))
    }

    @Test
    fun `没有时间说法就不猜`() {
        assertNull(ChineseTerms.timeSpanOf("他妹妹", now))
        assertNull(ChineseTerms.timeSpanOf("", now))
        assertNull(ChineseTerms.timeSpanOf("   ", now))
        assertFalse(ChineseTerms.hasTimeHint("项目压力", now))
        assertTrue(ChineseTerms.hasTimeHint("上周三", now))
    }

    @Test
    fun `词元里不残留时间指代`() {
        val stripped = ChineseTerms.stripTimeExpressions("去年冬天他妹妹生病了")

        assertFalse("时间说法该被抹掉，实际 $stripped", stripped.contains("去年"))
        assertFalse(stripped.contains("冬天"))
        assertTrue("正文要留下，实际 $stripped", stripped.contains("妹妹"))

        val terms = ChineseTerms.terms("去年冬天他妹妹生病了").map { it.term }
        assertTrue("「妹妹」应该还能查，实际 $terms", "妹妹" in terms)
    }
}
