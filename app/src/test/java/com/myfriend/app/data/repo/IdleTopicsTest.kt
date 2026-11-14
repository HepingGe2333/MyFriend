package com.myfriend.app.data.repo

import com.myfriend.app.domain.PersonaCard
import com.myfriend.app.domain.PersonaIdentity
import com.myfriend.app.domain.PersonaLife
import com.myfriend.app.network.WebSearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「空闲自浏览」纯逻辑单测：挑话题、摘结果、写记忆句、判断该不该出去看看。
 * 这里不联网、不碰 Room、不碰 Android，只测 [IdleTopics]。
 */
class IdleTopicsTest {

    private val now = 1_760_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun card(
        role: String = "",
        caresAbout: String = "",
        doing: String = "",
        values: List<String> = emptyList()
    ) = PersonaCard(
        identity = PersonaIdentity(role = role),
        life = PersonaLife(doing = doing, caresAbout = caresAbout),
        values = values
    )

    private fun hit(
        title: String,
        url: String,
        summary: String = "",
        site: String? = null
    ) = WebSearchHit(title = title, url = url, summary = summary, siteName = site)

    /** 一个信息量正常的人设卡：话题池足够大，方便验证轮换。 */
    private fun richCard() = card(
        role = "图书管理员",
        caresAbout = "AI 编程、独立游戏",
        doing = "在准备考试",
        values = listOf("好奇心", "耐心")
    )

    // ---------- 1. 话题挑选：兜底、稳定、轮换 ----------

    @Test
    fun emptyPersonaFallsBackToNeutralQueries() {
        val queries = IdleTopics.queriesFor(PersonaCard(), emptyList(), daySeed = 42)

        assertTrue("兜底应该有 2~3 条，实际：$queries", queries.size in 2..3)
        assertTrue("没人设时应该有「今天的重要新闻」，实际：$queries", "今天的重要新闻" in queries)
        assertTrue("没人设时应该有「科技新闻」，实际：$queries", "科技新闻" in queries)
        assertTrue("每条都不能为空，实际：$queries", queries.all { it.isNotBlank() })
    }

    @Test
    fun sameDaySeedIsStable() {
        val first = IdleTopics.queriesFor(richCard(), listOf("最近在学吉他"), daySeed = 137)
        val second = IdleTopics.queriesFor(richCard(), listOf("最近在学吉他"), daySeed = 137)

        assertEquals("同一个 daySeed 必须完全稳定", first, second)
    }

    @Test
    fun differentDaySeedRotatesTopics() {
        val monday = IdleTopics.queriesFor(richCard(), emptyList(), daySeed = 1)
        val tuesday = IdleTopics.queriesFor(richCard(), emptyList(), daySeed = 2)

        assertNotEquals("换一天应该换主题", monday, tuesday)
        assertNotEquals("主话题应该跟着换", monday.first(), tuesday.first())
    }

    @Test
    fun queriesComeFromPersonaAndStayWithinLimit() {
        val queries = IdleTopics.queriesFor(
            card = card(
                role = "后端工程师",
                caresAbout = "AI 编程、独立游戏",
                doing = "在写的这个项目已经拖了三个月还没收尾",
                values = listOf("诚实")
            ),
            recentUserTexts = listOf("最近在学吉他，手指头都是茧"),
            daySeed = 3
        )

        assertTrue("话题应该来自人设和对话：$queries", queries.any { it.contains("最新") })
        assertTrue("每条搜索词都不能超过 30 字，实际：$queries", queries.all { it.length <= 30 })
        assertTrue("每条都不能为空", queries.all { it.isNotBlank() })
        assertTrue("2~3 条，实际：${queries.size}", queries.size in 2..3)
        assertEquals("不能有重复话题", queries.size, queries.distinct().size)
    }

    @Test
    fun personaRoleBecomesIndustryNews() {
        // 只有身份/职业时，话题应该围绕它来。
        val queries = IdleTopics.queriesFor(card(role = "图书管理员"), emptyList(), daySeed = 0)

        assertTrue("身份/职业要变成「行业新闻」，实际：$queries", "图书管理员 行业新闻" in queries)
        assertTrue("不够 2 条时用中性话题补位，实际：$queries", queries.size >= 2)
    }

    @Test
    fun personaWithoutAnyClueStillGetsTwoToThreeQueries() {
        // 只有一句很长、抽不出关键词的「在做什么」，也要有兜底话题。
        val queries = IdleTopics.queriesFor(
            card = card(doing = "最近一直在琢磨怎么把咖啡煮得更好喝，试了很多种豆子"),
            recentUserTexts = emptyList(),
            daySeed = 9
        )

        assertTrue("至少 2 条，实际：$queries", queries.size >= 2)
        assertTrue("每条 ≤ 30 字，实际：$queries", queries.all { it.length <= 30 })
    }

    @Test
    fun userTextIsOnlyTurnedIntoShortKeywords() {
        val queries = IdleTopics.queriesFor(
            card = card(role = "编辑"),
            recentUserTexts = listOf(
                "我今天去医院看了一个很特别的医生，结果比想象中好一些",
                "顺便说一句我的电话是13800138000",
                "最近在学吉他"
            ),
            daySeed = 5
        )

        assertTrue("整句对话不能被塞进搜索词，实际：$queries", queries.none { it.contains("医院") })
        assertTrue("电话号码不能被搜，实际：$queries", queries.none { it.contains("13800138000") })
        assertTrue("短关键词可以进话题，实际：$queries", queries.any { it.contains("学吉他") })
    }

    // ---------- 2. 搜索结果摘要 ----------

    @Test
    fun digestDeduplicatesByUrlAndCapsLineLength() {
        val digest = IdleTopics.digestFor(
            listOf(
                hit("AI 编程又有新进展", "https://a.example/1", "模型更会写代码了", "某科技站"),
                hit("AI 编程又有新进展（转载）", "https://a.example/1", "同一条新闻", "转载站"),
                hit("很长的标题".repeat(40), "https://b.example/2", "很长的摘要".repeat(120), "站".repeat(30))
            )
        )

        val lines = digest.lines().filter { it.isNotBlank() }
        assertEquals("同一个 url 只留一条，实际：$lines", 2, lines.size)
        assertTrue("每条摘要 ≤ 120 字，实际：${lines.map { it.length }}", lines.all { it.length <= 120 })
        assertTrue("格式应该是「标题 — 摘要（站点）」，实际：$digest", lines.first().contains(" — "))
        assertTrue("要带上站点，实际：$digest", lines.first().contains("（某科技站）"))
    }

    @Test
    fun digestRespectsLimitAndEmptyInput() {
        val hits = (1..8).map { index -> hit("第 $index 条新闻", "https://n.example/$index") }

        assertEquals("没有结果时摘要为空", "", IdleTopics.digestFor(emptyList()))
        assertEquals("limit 为 0 时摘要为空", "", IdleTopics.digestFor(hits, limit = 0))
        assertEquals("最多 3 条", 3, IdleTopics.digestFor(hits, limit = 3).lines().size)
        assertTrue("默认最多 5 条", IdleTopics.digestFor(hits).lines().size <= 5)
    }

    // ---------- 3. 记忆句 ----------

    @Test
    fun memoryLineKeepsOneToThreePointsAndFitsLimit() {
        val digest = (1..10).joinToString("\n") { index -> "第 $index 条要点".repeat(30) }

        val line = IdleTopics.memoryLineFor(digest)

        assertTrue("记忆句 ≤ 400 字，实际：${line.length}", line.length <= 400)
        assertTrue("要写成「近况」口吻，实际：$line", line.startsWith("他今天自己上网看了几条资讯"))
        assertTrue("至少留 1 条要点，实际：$line", line.contains("1. "))
        assertTrue("最多留 3 条要点，实际：$line", !line.contains("4. "))
    }

    @Test
    fun memoryLineForEmptyDigestIsStillSafe() {
        val line = IdleTopics.memoryLineFor("")

        assertTrue("空摘要也要给一句话", line.isNotBlank())
        assertTrue("仍然 ≤ 400 字", line.length <= 400)
    }

    // ---------- 4. 该不该出去看看（边界） ----------

    @Test
    fun isIdleRequiresMoreThanSixHours() {
        assertFalse("正好 6 小时还不算「一个人待着」", IdleTopics.isIdle(now - 6 * hour, now))
        assertTrue("超过 6 小时才算", IdleTopics.isIdle(now - 6 * hour - 1, now))
        assertTrue("7 小时当然算", IdleTopics.isIdle(now - 7 * hour, now))
        assertFalse("刚聊过不算", IdleTopics.isIdle(now, now))
        assertTrue("完全没有活动记录时算", IdleTopics.isIdle(0L, now))
    }

    @Test
    fun shouldBrowseRequiresThreeHoursGap() {
        assertTrue("从没浏览过就可以去", IdleTopics.shouldBrowse(0L, now))
        assertFalse("刚浏览过不行", IdleTopics.shouldBrowse(now - hour, now))
        assertTrue("刚好满 3 小时可以再去", IdleTopics.shouldBrowse(now - 3 * hour, now))
        assertFalse("差 1 毫秒也不行", IdleTopics.shouldBrowse(now - 3 * hour + 1, now))
        assertTrue("4 小时前浏览过，可以再去", IdleTopics.shouldBrowse(now - 4 * hour, now))
        assertFalse("时钟回拨（未来时间）不能放开限制", IdleTopics.shouldBrowse(now + hour, now))
    }

    // ---------- 5. daySeed ----------

    @Test
    fun daySeedChangesOnlyAfterMidnight() {
        val zone = ZoneId.of("Asia/Shanghai")
        val morning = ZonedDateTime.of(2026, 3, 4, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val night = morning + 10 * hour
        val nextDay = morning + day

        assertEquals("同一天任何时候种子都一样", IdleTopics.daySeedOf(morning, zone), IdleTopics.daySeedOf(night, zone))
        assertNotEquals("跨天就要换种子", IdleTopics.daySeedOf(morning, zone), IdleTopics.daySeedOf(nextDay, zone))
        assertEquals("3 月 4 日是这一年第 63 天", 63, IdleTopics.daySeedOf(morning, zone))
    }
}
