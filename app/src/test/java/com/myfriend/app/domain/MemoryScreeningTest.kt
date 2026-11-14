package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryScreeningTest {

    private val now = 1_760_000_000_000L
    private val day = 86_400_000L

    private fun candidate(
        content: String,
        drawer: MemoryDrawer = MemoryDrawer.USER,
        importance: Int = 3,
        confidence: Float = 0.8f
    ) = MemoryCandidate(
        content = content,
        drawer = drawer,
        importance = importance,
        sourceQuote = content,
        categoryName = "FACT",
        confidence = confidence
    )

    private fun row(
        id: String,
        content: String,
        drawer: MemoryDrawer = MemoryDrawer.USER,
        importance: Int = 3,
        pinned: Boolean = false,
        hits: Int = 1,
        lastHitAt: Long = now,
        createdAt: Long = now,
        tags: List<String> = emptyList(),
        happenedAt: Long? = null
    ) = MemoryRow(
        id = id,
        content = content,
        drawer = drawer,
        importance = importance,
        pinned = pinned,
        hits = hits,
        lastHitAt = lastHitAt,
        createdAt = createdAt,
        tags = tags,
        happenedAt = happenedAt
    )

    // ---------- 1. 第一次 Echo，第二次 Promote ----------

    @Test
    fun firstMentionOnlyLeavesAnEcho() {
        val decision = MemoryScreening.decide(
            candidate("对方周末喜欢去公园散步"),
            emptyList(),
            emptyList(),
            now
        )

        assertTrue("第一次提到只留回声，实际：$decision", decision is MemoryDecision.Echo)
    }

    @Test
    fun secondMentionOfTheSameThingPromotes() {
        val first = candidate("对方周末喜欢去公园散步")
        val second = candidate("他周末喜欢去公园散步")

        val decision = MemoryScreening.decide(second, emptyList(), listOf(first), now)

        assertTrue("换个说法第二次提到应落库，实际：$decision", decision is MemoryDecision.Promote)
        val promoted = decision as MemoryDecision.Promote
        assertNull("没有可合并的已有记忆时 mergeIntoId 为空", promoted.mergeIntoId)
        assertEquals(second.content, promoted.candidate.content)
    }

    @Test
    fun differentTopicStaysEcho() {
        val echo = candidate("对方家里养了一只布偶猫")
        val other = candidate("对方在互联网公司做后端开发")

        assertTrue(MemoryScreening.decide(other, emptyList(), listOf(echo), now) is MemoryDecision.Echo)
    }

    @Test
    fun promotesIntoRelatedExistingMemoryWhenOneIsClose() {
        val existing = row("m1", "对方喜欢在周末去公园散步")
        val echo = candidate("对方周末喜欢去公园散步")

        val decision = MemoryScreening.decide(
            candidate("他周末喜欢去公园散步"),
            listOf(existing),
            listOf(echo),
            now
        )

        assertTrue(
            "与已有记忆只是相近但不到加固阈值，应 Promote，实际：$decision",
            decision is MemoryDecision.Promote
        )
        assertEquals("m1", (decision as MemoryDecision.Promote).mergeIntoId)
    }

    // ---------- 2. 高度相似 → Reinforce ----------

    @Test
    fun similarExistingMemoryIsReinforcedWithoutAppending() {
        val existing = row("m1", "对方喜欢在周末去公园散步")

        val decision = MemoryScreening.decide(
            candidate("对方喜欢周末去公园散步"),
            listOf(existing),
            emptyList(),
            now
        )

        assertTrue("高度相似应加固，实际：$decision", decision is MemoryDecision.Reinforce)
        val reinforced = decision as MemoryDecision.Reinforce
        assertEquals("m1", reinforced.id)
        assertNull("只是换个说法、没有新信息时不该追加", reinforced.appendedText)
    }

    @Test
    fun reinforceAppendsOnlyTheNewSentence() {
        val existing = row("m1", "对方喜欢在周末去公园散步")

        val decision = MemoryScreening.decide(
            candidate("对方喜欢在周末去公园散步，还养了一只叫咪咪的橘猫"),
            listOf(existing),
            emptyList(),
            now
        )

        assertTrue("应加固，实际：$decision", decision is MemoryDecision.Reinforce)
        val appended = (decision as MemoryDecision.Reinforce).appendedText
        assertTrue(
            "有新信息时应返回追加句，实际：$appended",
            appended != null && appended.contains("橘猫")
        )
        assertFalse("不该把原句再抄一遍，实际：$appended", appended!!.contains("公园"))
    }

    // ---------- 3. 一次性情绪 → Reject ----------

    @Test
    fun oneOffEmotionIsRejected() {
        val emotion = candidate("今天好累啊，一整天都没缓过来", importance = 2)

        val decision = MemoryScreening.decide(emotion, emptyList(), emptyList(), now)

        assertTrue("一次性情绪应丢弃，实际：$decision", decision is MemoryDecision.Reject)
        // 短情绪同样丢弃（先被长度规则拦下）
        val tooShort = MemoryScreening.decide(
            candidate("今天好累啊", importance = 2),
            emptyList(),
            emptyList(),
            now
        )
        assertTrue(tooShort is MemoryDecision.Reject)
    }

    // ---------- 4. 寒暄、过短、过长、低置信度 → Reject ----------

    @Test
    fun smallTalkAndBadCandidatesAreRejected() {
        val rejects = listOf(
            candidate("在吗？晚安"),
            candidate("你好呀"),
            candidate("嗯嗯"),
            candidate("对方喜欢在周末去公园散步", confidence = 0.2f),
            candidate("对方" + "喜欢".repeat(160))
        )

        rejects.forEach {
            val decision = MemoryScreening.decide(it, emptyList(), emptyList(), now)
            assertTrue("应该被丢弃：${it.content}", decision is MemoryDecision.Reject)
        }
    }

    @Test
    fun pureSmallTalkIsRejectedEvenWhenLongEnough() {
        val decision = MemoryScreening.decide(
            candidate("你好你好，在吗在吗"),
            emptyList(),
            emptyList(),
            now
        )

        assertTrue("纯寒暄应丢弃，实际：$decision", decision is MemoryDecision.Reject)
        val reason = (decision as MemoryDecision.Reject).reason
        assertTrue("应命中寒暄规则，实际：$reason", reason.contains("寒暄"))
    }

    // ---------- 5. 约定类内容的重要度保底 ----------

    @Test
    fun recurringAppointmentPromotesWithHighImportance() {
        val echo = candidate("每周三晚上一起看电影", MemoryDrawer.EVENT, importance = 3)
        val second = candidate("我们说好每周三晚上一起看电影", MemoryDrawer.EVENT, importance = 3)

        val decision = MemoryScreening.decide(second, emptyList(), listOf(echo), now)

        assertTrue("约定类第二次提到应落库，实际：$decision", decision is MemoryDecision.Promote)
        val promoted = (decision as MemoryDecision.Promote).candidate
        assertTrue("约定类重要度至少 4，实际：${promoted.importance}", promoted.importance >= 4)
    }

    // ---------- 6. 召回排序 ----------

    @Test
    fun pinnedMemoryRanksFirst() {
        val pinned = row(
            "pinned",
            "对方喜欢在周末去公园散步",
            importance = 2,
            pinned = true,
            lastHitAt = now - day
        )
        val fresh = row("fresh", "对方在互联网公司做后端开发", importance = 5, lastHitAt = now)

        val ranked = MemoryScreening.rankForRecall(listOf(fresh, pinned), "", now)

        assertEquals("pinned", ranked.first().id)
    }

    @Test
    fun keywordHitsRankBeforeUnrelatedMemories() {
        val hit = row("hit", "每周三晚上一起看电影", importance = 3)
        val miss = row("miss", "对方在互联网公司做后端开发", importance = 3)

        val ranked = MemoryScreening.rankForRecall(listOf(miss, hit), "电影", now)

        assertEquals(listOf("hit", "miss"), ranked.map { it.id })
    }

    @Test
    fun olderMemoriesRankLower() {
        val fresh = row("fresh", "对方喜欢在周末去公园散步", lastHitAt = now)
        val stale = row("stale", "对方喜欢在周末去公园散步", lastHitAt = now - 90 * day)

        val ranked = MemoryScreening.rankForRecall(listOf(stale, fresh), "", now)

        assertEquals(listOf("fresh", "stale"), ranked.map { it.id })
    }

    // ---------- 7. 预算挑选 ----------

    @Test
    fun selectionRespectsBudgetAndKeepsOrder() {
        val a = row("a", "每周三晚上一起看电影", importance = 5)
        val b = row("b", "对方喜欢在周末去公园散步", importance = 4)
        val c = row("c", "对方在互联网公司做后端开发", importance = 3)
        val all = listOf(c, a, b)
        val budget = a.content.length + b.content.length

        val selected = MemoryScreening.selectWithinBudget(all, "", now, budget)

        assertEquals(listOf("a", "b"), selected.map { it.id })
        assertTrue("总长度不能超预算", selected.sumOf { it.content.length } <= budget)
        val rankedIds = MemoryScreening.rankForRecall(all, "", now).map { it.id }
        val pickedIds = selected.map { it.id }.toSet()
        assertEquals(
            "挑选结果必须保持排序顺序",
            selected.map { it.id },
            rankedIds.filter { it in pickedIds }
        )
    }

    // ---------- 8. 空输入与基础工具函数 ----------

    @Test
    fun emptyInputIsSafe() {
        assertTrue(MemoryScreening.rankForRecall(emptyList(), "", now).isEmpty())
        val single = listOf(row("a", "对方喜欢在周末去公园散步"))
        assertEquals(1, MemoryScreening.rankForRecall(single, "", now).size)
        assertTrue(MemoryScreening.selectWithinBudget(emptyList(), "电影", now, 100).isEmpty())
        assertTrue(MemoryScreening.selectWithinBudget(single, "电影", now, 0).isEmpty())
        assertEquals("", MemoryScreening.normalize("   "))
        assertEquals(0.0, MemoryScreening.similarity("", "对方喜欢在周末去公园散步"), 0.0001)
        assertTrue(MemoryScreening.decide(candidate(""), emptyList(), emptyList(), now) is MemoryDecision.Reject)
    }

    @Test
    fun mapsCategoryNamesToDrawers() {
        assertEquals(MemoryDrawer.USER, MemoryScreening.drawerOf("FACT"))
        assertEquals(MemoryDrawer.USER, MemoryScreening.drawerOf("PREFERENCE"))
        assertEquals(MemoryDrawer.USER, MemoryScreening.drawerOf("OTHER"))
        assertEquals(MemoryDrawer.EVENT, MemoryScreening.drawerOf("EVENT"))
        assertEquals(MemoryDrawer.EVENT, MemoryScreening.drawerOf("RELATIONSHIP"))
        assertEquals(MemoryDrawer.SELF, MemoryScreening.drawerOf("SELF"))
        assertEquals(MemoryDrawer.SELF, MemoryScreening.drawerOf("TRAIT"))
        assertEquals(MemoryDrawer.USER, MemoryScreening.drawerOf(""))
    }

    // ---------- 9. 本地重排：词元余弦 ----------

    @Test
    fun relevantMemoryBeatsMoreImportantUnrelatedOne() {
        val relevant = row("relevant", "项目压力大到睡不着", importance = 2)
        val important = row("important", "对方喜欢在周末打游戏", importance = 5)

        val ranked = MemoryScreening.rerankForRecall(listOf(important, relevant), "项目压力", now)

        assertEquals("找对东西比「重要」更优先", "relevant", ranked.first().id)
    }

    @Test
    fun paraphraseStillMatchesWithoutLiteralQuery() {
        val paraphrase = row("paraphrase", "他最近项目压得睡不着", importance = 2)
        val other = row("other", "对方喜欢养猫", importance = 2)

        val ranked = MemoryScreening.rerankForRecall(listOf(other, paraphrase), "项目压力", now)

        assertEquals("换一种说法也该排上来", "paraphrase", ranked.first().id)
    }

    // ---------- 10. 标签与时间指代 ----------

    @Test
    fun tagHitLiftsTheMemoryTaggedForIt() {
        val tagged = row("tagged", "对方最近睡不好", tags = listOf("妹妹", "失眠"))
        val plain = row("plain", "对方最近睡不好")

        val ranked = MemoryScreening.rerankForRecall(listOf(plain, tagged), "我妹妹最近怎么样", now)

        assertEquals("标签命中的应该靠前", "tagged", ranked.first().id)
        assertEquals("两个标签都命中算满分", 1.0, MemoryScreening.tagAffinity("妹妹失眠了", listOf("妹妹", "失眠")), 0.0001)
        assertEquals("无关查询不给标签分", 0.0, MemoryScreening.tagAffinity("今天天气不错", listOf("妹妹")), 0.0001)
    }

    @Test
    fun happenedAtNearTimeHintWins() {
        val inside = row("inside", "那次旅行挺开心的", happenedAt = 1_740_000_000_000L)
        val outside = row("outside", "那次旅行挺开心的", happenedAt = now - 20 * day)

        val ranked = MemoryScreening.rerankForRecall(listOf(outside, inside), "去年冬天那次旅行", now)

        assertEquals("发生时间落在时间指代里的应该靠前", "inside", ranked.first().id)
    }

    @Test
    fun timeAffinityIsSafeWithoutTimeHintOrTimestamp() {
        val span = ChineseTerms.timeSpanOf("昨天", now)

        assertEquals(0.0, MemoryScreening.timeAffinity(null, now), 0.0001)
        assertEquals(0.0, MemoryScreening.timeAffinity(span, null), 0.0001)
        assertEquals(0.0, MemoryScreening.timeAffinity(span, 0L), 0.0001)
        // 昨天那一段就是「今天零点往前一天」，所以「今天这个时刻减一天」一定落在里面。
        assertEquals(1.0, MemoryScreening.timeAffinity(span, now - day), 0.0001)
    }

    // ---------- 11. tags 字段的 JSON 编解码 ----------

    @Test
    fun tagsRoundTripAndBadDataNeverThrows() {
        val encoded = MemoryTags.encode(listOf("妹妹", "妹妹", " ", "失眠"))

        assertEquals(listOf("妹妹", "失眠"), MemoryTags.decode(encoded))
        assertEquals(emptyList<String>(), MemoryTags.decode(null))
        assertEquals(emptyList<String>(), MemoryTags.decode(""))
        assertEquals(emptyList<String>(), MemoryTags.decode("不是 JSON"))
        assertEquals(emptyList<String>(), MemoryTags.decode("""{"不是":"数组"}"""))
        assertEquals(5, MemoryTags.sanitize(listOf("一", "二", "三", "四", "五", "六")).size)
        assertEquals("单个标签超长会被截断", MemoryTags.MAX_TAG_CHARS, MemoryTags.sanitize(listOf("很长".repeat(20))).first().length)
        assertNull("没有标签时写 null", MemoryTags.encode(emptyList()))
    }

    @Test
    fun normalizeDropsNoiseBeforeComparing() {
        assertEquals(
            "对方喜欢在周末去公园散步",
            MemoryScreening.normalize("  我觉得，对方喜欢在周末去公园散步！ ")
        )
        assertEquals(MemoryScreening.normalize("今天好累"), MemoryScreening.normalize("好累。"))
        assertEquals(
            1.0,
            MemoryScreening.similarity("对方喜欢在周末去公园散步", "对方喜欢在周末去公园散步"),
            0.0001
        )
        assertTrue(MemoryScreening.similarity("对方喜欢猫", "对方喜欢狗") < 0.6)
    }
}
