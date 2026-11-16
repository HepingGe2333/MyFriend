package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回忆的三档强度：该翻多深、怎么排、翻不到时怎么说人话。
 * 全部是纯函数，不碰数据库，时间由测试固定传入。
 */
class RecallTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L
    private val hour = 3_600_000L

    // ---------------------------------------------------------------- 造数据

    private fun memory(
        id: String,
        content: String,
        state: RecallMemoryState = RecallMemoryState.ACTIVE,
        importance: Int = 3,
        lastUsedAt: Long = now,
        pinned: Boolean = false,
        drawer: MemoryDrawer = MemoryDrawer.USER,
        strength: Double = 1.0,
        tags: List<String> = emptyList(),
        happenedAt: Long? = null
    ): RecallMemoryInput = RecallMemoryInput(
        id = id,
        content = content,
        drawer = drawer,
        importance = importance,
        pinned = pinned,
        lastUsedAt = lastUsedAt,
        createdAt = lastUsedAt,
        state = state,
        strength = strength,
        tags = tags,
        happenedAt = happenedAt
    )

    // ---------------------------------------------------------------- 三档取数差异

    @Test
    fun quickDepthOnlySeesActiveMemories() {
        val inputs = listOf(
            memory("active", "对方怕冷，冬天总是手脚冰凉"),
            memory("dormant", "对方喜欢在雨天听老歌", state = RecallMemoryState.DORMANT),
            memory("archived", "很久以前一起看过海", state = RecallMemoryState.ARCHIVED)
        )

        val quick = Recall.memoryHits(inputs, "对方", RecallDepth.QUICK, now)

        assertEquals(listOf("active"), quick.map { it.refId })
    }

    @Test
    fun normalDepthAddsDormantButNotArchived() {
        val inputs = listOf(
            memory("active", "对方怕冷，冬天总是手脚冰凉"),
            memory("dormant", "对方喜欢在雨天听老歌", state = RecallMemoryState.DORMANT),
            memory("archived", "很久以前一起看过海", state = RecallMemoryState.ARCHIVED)
        )

        val normal = Recall.memoryHits(inputs, "对方", RecallDepth.NORMAL, now)

        assertEquals(listOf("active", "dormant"), normal.map { it.refId }.sorted())
    }

    @Test
    fun deepDepthFindsArchivedMemoriesWithAnUncertainNote() {
        val inputs = listOf(
            memory("active", "对方怕冷，冬天总是手脚冰凉"),
            memory("dormant", "对方喜欢在雨天听老歌", state = RecallMemoryState.DORMANT),
            memory("archived", "很久以前对方提过想去看海", state = RecallMemoryState.ARCHIVED)
        )

        val deep = Recall.memoryHits(inputs, "对方", RecallDepth.DEEP, now)

        assertEquals(3, deep.size)
        val archived = deep.single { it.refId == "archived" }
        assertTrue("归档命中要说「不太确定」", archived.note.contains("不太确定"))
        val rendered = Recall.render(deep, RecallDepth.DEEP, "对方", now)
        assertTrue(rendered.contains("不太确定"))
    }

    @Test
    fun depthFlagsMatchProductSemantics() {
        // QUICK：只看还在用的记忆，不碰聊天与浏览
        assertFalse(RecallDepth.QUICK.includeDormant)
        assertFalse(RecallDepth.QUICK.includeArchived)
        assertFalse(RecallDepth.QUICK.includeChatSearch)
        assertFalse(RecallDepth.QUICK.includeBrowsing)
        // NORMAL：加上沉下去的记忆与最近聊天、他看到的
        assertTrue(RecallDepth.NORMAL.includeDormant)
        assertFalse(RecallDepth.NORMAL.includeArchived)
        assertTrue(RecallDepth.NORMAL.includeChatSearch)
        assertTrue(RecallDepth.NORMAL.includeBrowsing)
        assertFalse(RecallDepth.NORMAL.scanRecentChats)
        // DEEP：连归档都翻，还按会话扫最近的聊天
        assertTrue(RecallDepth.DEEP.includeArchived)
        assertTrue(RecallDepth.DEEP.scanRecentChats)
        assertTrue(RecallDepth.DEEP.includeBrowsing)
        // 越深给的字数越多
        assertTrue(RecallDepth.QUICK.maxChars < RecallDepth.NORMAL.maxChars)
        assertTrue(RecallDepth.NORMAL.maxChars < RecallDepth.DEEP.maxChars)
        assertTrue(RecallDepth.QUICK.level < RecallDepth.NORMAL.level)
        assertTrue(RecallDepth.NORMAL.level < RecallDepth.DEEP.level)
    }

    // ---------------------------------------------------------------- 排序

    @Test
    fun keywordMatchOutranksNewerUnrelatedContent() {
        val hits = listOf(
            RecallHit(kind = RecallKind.CHAT, text = "今天天气不错", at = now, weight = 1.0),
            RecallHit(kind = RecallKind.MEMORY, text = "对方养了一只叫团子的猫", at = now - 40 * day, weight = 0.2)
        )

        val ranked = Recall.rank(hits, "团子", RecallDepth.NORMAL, now)

        assertEquals("对方养了一只叫团子的猫", ranked.first().text)
    }

    @Test
    fun keywordScoreHandlesBlankQueryAndPartialMatch() {
        assertEquals(0.0, Recall.keywordScore("随便一段话", ""), 0.0001)
        assertEquals(0.0, Recall.keywordScore("随便一段话", "   "), 0.0001)
        assertEquals(1.0, Recall.keywordScore("对方怕冷", "怕冷"), 0.0001)
        assertEquals(0.0, Recall.keywordScore("对方怕冷", "宇宙飞船"), 0.0001)
        assertTrue(Recall.keywordScore("对方喜欢在雨天听老歌", "雨天 老歌") > 0.0)
    }

    @Test
    fun newerHitWinsWhenEverythingElseIsEqual() {
        val hits = listOf(
            RecallHit(kind = RecallKind.MEMORY, text = "很早以前的事", at = now - 200 * day, weight = 0.5),
            RecallHit(kind = RecallKind.MEMORY, text = "最近的事", at = now - day, weight = 0.5)
        )

        assertEquals("最近的事", Recall.rank(hits, "", RecallDepth.QUICK, now).first().text)
    }

    @Test
    fun deeperDepthToleratesOldButImportantContent() {
        val oldImportant = RecallHit(kind = RecallKind.MEMORY, text = "最早的那次约定", at = now - 300 * day, weight = 1.0)
        val freshLight = RecallHit(kind = RecallKind.CHAT, text = "刚才的闲聊", at = now, weight = 0.45)
        val hits = listOf(oldImportant, freshLight)

        // 随口想想时更信「最近」的；翻箱倒柜时，很久以前但很重要的排得上来
        assertEquals("刚才的闲聊", Recall.rank(hits, "", RecallDepth.QUICK, now).first().text)
        assertEquals("最早的那次约定", Recall.rank(hits, "", RecallDepth.DEEP, now).first().text)
    }

    @Test
    fun pinnedAndImportantMemoryRanksFirst() {
        val inputs = listOf(
            memory("plain", "对方喜欢猫", importance = 2, lastUsedAt = now - 30 * day),
            memory("pinned", "对方说养猫是这辈子最对的决定", importance = 5, pinned = true, lastUsedAt = now - 30 * day)
        )

        val hits = Recall.memoryHits(inputs, "猫", RecallDepth.QUICK, now)

        assertEquals("pinned", hits.first().refId)
    }

    @Test
    fun blankQueryFallsBackToImportanceAndFreshness() {
        val inputs = listOf(
            memory("old-important", "很久以前的重要约定", importance = 5, lastUsedAt = now - 30 * day),
            memory("recent-light", "最近随口说的一句话", importance = 1, lastUsedAt = now)
        )

        val hits = Recall.memoryHits(inputs, "", RecallDepth.QUICK, now)

        assertEquals(2, hits.size)
        assertEquals("old-important", hits.first().refId)
    }

    @Test
    fun archivedMemoryComesBackFaint() {
        val active = Recall.memoryHits(
            listOf(memory("a", "对方怕冷", importance = 4)),
            "", RecallDepth.QUICK, now
        ).single()
        val archived = Recall.memoryHits(
            listOf(memory("z", "很久以前对方提过怕冷", state = RecallMemoryState.ARCHIVED, importance = 4)),
            "", RecallDepth.DEEP, now
        ).single()

        assertTrue("归档的应该更轻", archived.weight < active.weight)
        assertTrue(archived.note.contains("不太确定"))
    }

    // ---------------------------------------------------------------- 渲染

    @Test
    fun renderLabelsEachSourceWithRelativeDate() {
        val memoryHit = Recall.memoryHits(
            listOf(memory("m1", "对方怕冷", lastUsedAt = now - 21 * day)),
            "", RecallDepth.QUICK, now
        ).single()
        val chatHit = Recall.chatHit("上次说的那家店很好吃", "关于猫的那次聊天", now - day, "USER")
        val browsingHit = Recall.browsingHit("看到一条关于降噪耳机的评测", now - 2 * hour)

        val rendered = Recall.render(listOf(memoryHit, chatHit, browsingHit), RecallDepth.NORMAL, "店", now)

        assertTrue(rendered.contains("（记忆 · 3 周前 · 关于对方）"))
        assertTrue(rendered.contains("（聊天 · 昨天 · 《关于猫的那次聊天》"))
        assertTrue(rendered.contains("（他自己看到的 · 今天）"))
        assertTrue(rendered.contains("关于「店」"))
    }

    @Test
    fun renderTrimsContentByDepthBudget() {
        val hits = (1..60).map { index ->
            RecallHit(
                kind = RecallKind.MEMORY,
                text = "第${index}条：" + "很久以前的事".repeat(20),
                at = now - index * day,
                weight = 0.5
            )
        }

        val quick = Recall.render(hits, RecallDepth.QUICK, "事", now)
        val deep = Recall.render(hits, RecallDepth.DEEP, "事", now)

        assertTrue("QUICK 不该超过 ${RecallDepth.QUICK.maxChars} 字", quick.length <= RecallDepth.QUICK.maxChars)
        assertTrue("DEEP 不该超过 ${RecallDepth.DEEP.maxChars} 字", deep.length <= RecallDepth.DEEP.maxChars)
        assertTrue("越深翻出来的越多", deep.length > quick.length)
        assertTrue(quick.contains("随口想想"))
    }

    @Test
    fun emptyAnswerDiffersByDepthAndAdmitsRealForgetting() {
        val quick = Recall.emptyAnswer("猫", RecallDepth.QUICK)
        val normal = Recall.emptyAnswer("猫", RecallDepth.NORMAL)
        val deep = Recall.emptyAnswer("猫", RecallDepth.DEEP)

        assertTrue(quick.contains("想不太起来"))
        assertTrue(normal.contains("想不太起来"))
        assertTrue(deep.contains("确实想不起来"))
        assertTrue(deep.contains("可能早就忘了"))
        assertFalse("DEEP 不该只说「想不太起来」", deep.contains("想不太起来"))
        assertTrue(deep.contains("猫"))
        // 空查询也要能说人话
        assertTrue(Recall.emptyAnswer("", RecallDepth.QUICK).contains("这件事"))
    }

    @Test
    fun renderFallsBackToEmptyAnswerWhenNothingFound() {
        assertEquals(
            Recall.emptyAnswer("猫", RecallDepth.DEEP),
            Recall.render(emptyList(), RecallDepth.DEEP, "猫", now)
        )
    }

    // ---------------------------------------------------------------- 参数解析

    @Test
    fun parseDepthAcceptsNumbersEnglishAndChineseAliases() {
        assertEquals(RecallDepth.QUICK, RecallDepth.parse("1"))
        assertEquals(RecallDepth.NORMAL, RecallDepth.parse("2"))
        assertEquals(RecallDepth.DEEP, RecallDepth.parse("3"))
        assertEquals(RecallDepth.QUICK, RecallDepth.parse("quick"))
        assertEquals(RecallDepth.QUICK, RecallDepth.parse("QUICK"))
        assertEquals(RecallDepth.NORMAL, RecallDepth.parse("normal"))
        assertEquals(RecallDepth.DEEP, RecallDepth.parse("Deep"))
        assertEquals(RecallDepth.QUICK, RecallDepth.parse("随口想想"))
        assertEquals(RecallDepth.NORMAL, RecallDepth.parse("认真回忆"))
        assertEquals(RecallDepth.DEEP, RecallDepth.parse("翻箱倒柜"))
        assertEquals(RecallDepth.QUICK, RecallDepth.parse("第一档"))
        assertEquals(RecallDepth.NORMAL, RecallDepth.parse("第二档"))
        assertEquals(RecallDepth.DEEP, RecallDepth.parse("第三档"))
        // 认不出来时退回「认真回忆」，不抛异常
        assertEquals(RecallDepth.NORMAL, RecallDepth.parse(null))
        assertEquals(RecallDepth.NORMAL, RecallDepth.parse("   "))
        assertEquals(RecallDepth.NORMAL, RecallDepth.parse("看不懂的写法"))
    }

    @Test
    fun parseDepthClampsOutOfRangeLevels() {
        assertEquals(RecallDepth.QUICK, RecallDepth.parse("0"))
        assertEquals(RecallDepth.QUICK, RecallDepth.parse("-1"))
        assertEquals(RecallDepth.DEEP, RecallDepth.parse("9"))
        assertEquals(RecallDepth.DEEP, RecallDepth.parse("第3档"))
        assertEquals(RecallDepth.QUICK, RecallDepth.fromLevel(-7))
        assertEquals(RecallDepth.DEEP, RecallDepth.fromLevel(42))
    }

    @Test
    fun dateLabelCoversChineseRanges() {
        assertEquals("很久以前", Recall.dateLabel(0L, now))
        assertEquals("刚刚", Recall.dateLabel(now, now))
        assertEquals("5 分钟前", Recall.dateLabel(now - 5 * 60_000L, now))
        assertEquals("今天", Recall.dateLabel(now - 2 * hour, now))
        assertEquals("昨天", Recall.dateLabel(now - day, now))
        assertEquals("前天", Recall.dateLabel(now - 2 * day, now))
        assertEquals("5 天前", Recall.dateLabel(now - 5 * day, now))
        assertEquals("3 周前", Recall.dateLabel(now - 21 * day, now))
        assertEquals("2 个月前", Recall.dateLabel(now - 65 * day, now))
        assertEquals("2 年前", Recall.dateLabel(now - 800 * day, now))
    }

    // ---------------------------------------------------------------- 聊天 / 浏览命中

    @Test
    fun chatHitCarriesSessionTitleAndSpeaker() {
        val user = Recall.chatHit("上次说的那家店很好吃", "关于猫的那次聊天", now - day, "USER")
        assertEquals(RecallKind.CHAT, user.kind)
        assertTrue(user.text.startsWith("对方说："))
        assertEquals("《关于猫的那次聊天》", user.source)
        assertTrue(RecallKind.CHAT.label in Recall.sourceLabel(user, now))

        val assistant = Recall.chatHit("我也记得那家店", "关于猫的那次聊天", now - day, "assistant")
        assertTrue(assistant.text.startsWith("我说过："))

        val unknown = Recall.chatHit("没有角色的旧消息", "旧会话", now - day)
        assertEquals("没有角色的旧消息", unknown.text)
    }

    @Test
    fun browsingHitKeepsDigestAndKind() {
        val hit = Recall.browsingHit("  看到一条关于降噪耳机的评测  ", now)

        assertEquals(RecallKind.BROWSING, hit.kind)
        assertEquals("看到一条关于降噪耳机的评测", hit.text)
        assertTrue(hit.weight in 0.0..1.0)
        assertEquals("", hit.source)
        assertTrue(Recall.sourceLabel(hit, now).startsWith("他自己看到的"))
    }

    // ---------------------------------------------------------------- 坏数据

    // ---------------------------------------------------------------- 倒排索引 / 标签 / 时间指代

    @Test
    fun invertedIndexHitWinsWhenEverythingElseIsEqual() {
        val first = memory("first", "对方喜欢在雨天听老歌")
        val second = memory("second", "对方喜欢在雨天听老歌")

        val hits = Recall.memoryHits(
            listOf(first, second),
            "老歌",
            RecallDepth.QUICK,
            now,
            indexHits = setOf("second")
        )

        assertEquals("词元真的对上比子串偶然更可信", "second", hits.first().refId)
    }

    @Test
    fun tagHitLiftsTheMemoryTaggedForIt() {
        val tagged = memory("tagged", "对方最近睡不好", tags = listOf("妹妹", "失眠"))
        val plain = memory("plain", "对方最近睡不好")

        val hits = Recall.memoryHits(listOf(plain, tagged), "我妹妹最近怎么样", RecallDepth.QUICK, now)

        assertEquals("标签命中应该靠前", "tagged", hits.first().refId)
    }

    @Test
    fun happenedAtNearTimeHintWins() {
        // now 是 2023-11 附近：去年冬天 = 2022 年 12 月到 2023 年 3 月。
        val inside = memory("inside", "那次旅行挺开心的", happenedAt = now - 300 * day)
        val outside = memory("outside", "那次旅行挺开心的", happenedAt = now - 20 * day)

        val hits = Recall.memoryHits(listOf(outside, inside), "去年冬天那次旅行", RecallDepth.QUICK, now)

        assertEquals("发生时间落在时间指代里的应该靠前", "inside", hits.first().refId)
    }

    @Test
    fun recallKeywordScoreSharesTheSameTermsAsContextSelection() {
        assertEquals(1.0, Recall.keywordScore("项目压力", "项目压力"), 0.0001)
        assertTrue("换一种说法也该有分", Recall.keywordScore("他最近项目压得睡不着", "项目压力") > 0.0)
        assertEquals(0.0, Recall.keywordScore("对方喜欢在雨天听老歌", "项目压力"), 0.0001)
    }

    @Test
    fun badDataNeverThrows() {
        assertTrue(Recall.memoryHits(emptyList(), "猫", RecallDepth.DEEP, now).isEmpty())
        assertTrue(Recall.memoryHits(listOf(memory("blank", "   ")), "猫", RecallDepth.DEEP, now).isEmpty())
        assertTrue(Recall.rank(emptyList(), "猫", RecallDepth.QUICK, now).isEmpty())
        assertEquals("很久以前", Recall.dateLabel(-5L, now))
        assertTrue(Recall.keywordScore("", "") == 0.0)
        // 一条正文为空的聊天记录不该炸，只是没有内容
        assertEquals(RecallKind.CHAT, Recall.chatHit("  ", "会话", now).kind)
    }
}
