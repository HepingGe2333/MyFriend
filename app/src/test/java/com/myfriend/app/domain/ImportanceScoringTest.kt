package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重要性打分器的纯逻辑单测：解析模型返回的打分 JSON、宽松容错、阈值夹取。
 * 正常、缺字段、坏 JSON、越界、merge 多条都在这里钉死。
 */
class ImportanceScoringTest {

    // ---------- 1. parseConsolidation：正常 ----------

    @Test
    fun `正常输出能解析出五种指令`() {
        val raw = """
            {"operations":[
              {"op":"keep","ids":["1","2"],"reason":"这是约定"},
              {"op":"merge","ids":["3","4"],"content":"对方养了两只猫","importance":4,"reason":"同一件事"},
              {"op":"archive","ids":["5"],"reason":"只是近况"},
              {"op":"forget","ids":["6"],"reason":"半年前的一次闲聊"},
              {"op":"raise","ids":["7"],"importance":5,"reason":"重要的事"}
            ],"summary":"整理了一遍"}
        """.trimIndent()
        val plan = ImportanceScoring.parseConsolidation(raw)
        assertFalse(plan.isEmpty)
        assertEquals(5, plan.operations.size)
        assertEquals("整理了一遍", plan.summary)
        assertEquals(MemoryOp.KEEP, plan.operations[0].op)
        assertEquals(listOf("1", "2"), plan.operations[0].ids)
        assertEquals(MemoryOp.MERGE, plan.operations[1].op)
        assertEquals("对方养了两只猫", plan.operations[1].content)
        assertEquals(MemoryOp.ARCHIVE, plan.operations[2].op)
        assertEquals(MemoryOp.FORGET, plan.operations[3].op)
        assertEquals(MemoryOp.RAISE, plan.operations[4].op)
        assertEquals(5, plan.operations[4].importance)
    }

    @Test
    fun `代码块和前后废话都能剥掉`() {
        val raw = """
            好的，我整理完了：
            ```json
            {"operations":[{"op":"keep","ids":[1]}],"summary":"就这些"}
            ```
            以上。
        """.trimIndent()
        val plan = ImportanceScoring.parseConsolidation(raw)
        assertEquals(1, plan.operations.size)
        assertEquals(listOf("1"), plan.operations[0].ids)
        assertEquals("就这些", plan.summary)
    }

    // ---------- 2. parseConsolidation：坏 JSON / 缺字段 ----------

    @Test
    fun `坏 JSON 降级成什么都不做`() {
        listOf("", "   ", "这不是 JSON", "{坏掉的", "null", "[]").forEach { raw ->
            val plan = ImportanceScoring.parseConsolidation(raw)
            assertTrue("「$raw」应该是空计划", plan.isEmpty)
            assertEquals("", plan.summary)
        }
    }

    @Test
    fun `缺少 operations 时只保留 summary`() {
        val plan = ImportanceScoring.parseConsolidation("""{"summary":"这次没动"}""")
        assertTrue(plan.isEmpty)
        assertEquals("这次没动", plan.summary)
    }

    @Test
    fun `缺少 id 或缺 op 的指令被丢掉`() {
        val raw = """
            {"operations":[
              {"op":"keep"},
              {"ids":["1","2"]},
              {"op":"乱写的指令","ids":["3"]},
              {"op":"keep","ids":["4"]}
            ]}
        """.trimIndent()
        val plan = ImportanceScoring.parseConsolidation(raw)
        assertEquals(1, plan.operations.size)
        assertEquals(listOf("4"), plan.operations[0].ids)
    }

    @Test
    fun `merge 只给一个 id 时不算合并`() {
        val plan = ImportanceScoring.parseConsolidation(
            """{"operations":[{"op":"merge","ids":["1"],"content":"并起来"}]}"""
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `merge 的多个 id 能逗号或数组两种写法`() {
        val comma = ImportanceScoring.parseConsolidation(
            """{"operations":[{"op":"merge","ids":"3，4、5","content":"并起来"}]}"""
        )
        assertEquals(
            listOf("3", "4", "5"),
            comma.operations.first().ids
        )
        val array = ImportanceScoring.parseConsolidation(
            """{"operations":[{"op":"merge","ids":["7","8"],"content":"并起来"}]}"""
        )
        assertEquals(listOf("7", "8"), array.operations.first().ids)
    }

    @Test
    fun `raise 没有重要度就丢掉`() {
        assertTrue(
            ImportanceScoring.parseConsolidation(
                """{"operations":[{"op":"raise","ids":["1"]}]}"""
            ).isEmpty
        )
    }

    @Test
    fun `中文指令名也能认出来`() {
        val raw = """
            {"operations":[
              {"op":"保留","ids":["1"]},
              {"op":"合并","ids":["2","3"],"content":"并起来"},
              {"op":"归档","ids":["4"]},
              {"op":"忘记","ids":["5"]},
              {"op":"提权","ids":["6"],"importance":4}
            ]}
        """.trimIndent()
        val ops = ImportanceScoring.parseConsolidation(raw).operations.map { it.op }
        assertEquals(
            listOf(MemoryOp.KEEP, MemoryOp.MERGE, MemoryOp.ARCHIVE, MemoryOp.FORGET, MemoryOp.RAISE),
            ops
        )
    }

    // ---------- 3. parseConsolidation：越界与上限 ----------

    @Test
    fun `越界的重要度会被夹取`() {
        val plan = ImportanceScoring.parseConsolidation(
            """{"operations":[
                 {"op":"raise","ids":["1"],"importance":9},
                 {"op":"raise","ids":["2"],"importance":-4},
                 {"op":"raise","ids":["3"],"importance":"5"}
             ]}"""
        )
        assertEquals(listOf(5, 1, 5), plan.operations.map { it.importance })
    }

    @Test
    fun `编号会去重且单条指令有上限`() {
        val ids = (1..12).joinToString(",") { "\"$it\"" }
        val plan = ImportanceScoring.parseConsolidation(
            """{"operations":[{"op":"keep","ids":[$ids, "1", "2"]}]}"""
        )
        val got = plan.operations.first().ids
        assertEquals(ImportanceScoring.MAX_IDS_PER_OPERATION, got.size)
        assertEquals(got.size, got.distinct().size)
    }

    @Test
    fun `指令条数会被截断且长正文会被截断`() {
        val ops = (1..20).joinToString(",") { """{"op":"keep","ids":["$it"]}""" }
        val plan = ImportanceScoring.parseConsolidation("""{"operations":[$ops]}""")
        assertEquals(ImportanceScoring.MAX_OPERATIONS, plan.operations.size)

        val long = "猫".repeat(2000)
        val merged = ImportanceScoring.parseConsolidation(
            """{"operations":[{"op":"merge","ids":["1","2"],"content":"$long"}]}"""
        ).operations.first()
        assertTrue((merged.content ?: "").length <= 800)
    }

    // ---------- 4. parseSearchScore ----------

    @Test
    fun `正常的打分结果能解析`() {
        val score = ImportanceScoring.parseSearchScore(
            """{"important":true,"importance":4,"takeaway":"对方常去的书店关门了","drawer":"USER"}"""
        )
        assertNotNull(score)
        score!!
        assertTrue(score.important)
        assertEquals(4, score.importance)
        assertEquals("对方常去的书店关门了", score.takeaway)
        assertEquals(MemoryDrawer.USER, score.drawer)
        assertTrue(ImportanceScoring.shouldRemember(score))
    }

    @Test
    fun `缺字段时降级成不记`() {
        val score = ImportanceScoring.parseSearchScore("""{"takeaway":"随便一条"}""")
        assertNotNull(score)
        score!!
        assertFalse(score.important)
        assertEquals(ImportanceScoring.MIN_IMPORTANCE, score.importance)
        assertEquals(MemoryDrawer.USER, score.drawer)
        assertFalse(ImportanceScoring.shouldRemember(score))
    }

    @Test
    fun `只有 important 时重要度按门槛兜底`() {
        val score = ImportanceScoring.parseSearchScore("""{"important":true}""")
        assertNotNull(score)
        assertEquals(ImportanceScoring.REMEMBER_FLOOR, score!!.importance)
        assertTrue(ImportanceScoring.shouldRemember(score))
    }

    @Test
    fun `只有重要度时按重要度推断值不值得记`() {
        val high = ImportanceScoring.parseSearchScore("""{"importance":5}""")
        val low = ImportanceScoring.parseSearchScore("""{"importance":2}""")
        assertTrue(high!!.important)
        assertFalse(low!!.important)
    }

    @Test
    fun `坏 JSON 的打分结果是 null`() {
        assertNull(ImportanceScoring.parseSearchScore(""))
        assertNull(ImportanceScoring.parseSearchScore("我看了下，没什么重要的"))
        assertNull(ImportanceScoring.parseSearchScore("{坏的"))
    }

    @Test
    fun `打分的重要度越界会被夹取`() {
        assertEquals(5, ImportanceScoring.parseSearchScore("""{"importance":9}""")!!.importance)
        assertEquals(1, ImportanceScoring.parseSearchScore("""{"importance":-9}""")!!.importance)
        assertEquals(5, ImportanceScoring.clampImportance(9))
        assertEquals(1, ImportanceScoring.clampImportance(0))
    }

    @Test
    fun `不认识的抽屉回落到关于对方`() {
        val score = ImportanceScoring.parseSearchScore(
            """{"important":true,"importance":4,"drawer":"怪抽屉"}"""
        )
        assertEquals(MemoryDrawer.USER, score!!.drawer)
        val event = ImportanceScoring.parseSearchScore(
            """{"important":true,"importance":4,"drawer":"EVENT"}"""
        )
        assertEquals(MemoryDrawer.EVENT, event!!.drawer)
    }

    @Test
    fun `字符串形式的布尔与代码块都能认`() {
        val score = ImportanceScoring.parseSearchScore(
            """
            ```json
            {"important":"是","importance":"4","takeaway":"一件值得记的事"}
            ```
            """.trimIndent()
        )
        assertNotNull(score)
        assertTrue(score!!.important)
        assertEquals(4, score.importance)
    }

    // ---------- 5. shouldRemember 与提示词 ----------

    @Test
    fun `重要度不够就不记`() {
        assertFalse(
            ImportanceScoring.shouldRemember(
                SearchScore(important = true, importance = 2, takeaway = "x")
            )
        )
        assertFalse(
            ImportanceScoring.shouldRemember(
                SearchScore(important = false, importance = 5, takeaway = "x")
            )
        )
        assertTrue(
            ImportanceScoring.shouldRemember(
                SearchScore(important = true, importance = 3, takeaway = "x")
            )
        )
    }

    @Test
    fun `两个提示词共用同一套判据`() {
        val consolidator = ImportanceScoring.consolidationSystemPrompt("小满")
        assertTrue(consolidator.contains("小满"))
        assertTrue(consolidator.contains("operations"))
        assertTrue(consolidator.contains("值得记"))

        val scorer = ImportanceScoring.searchScoringSystemPrompt("小满")
        assertTrue(scorer.contains("important"))
        assertTrue(scorer.contains("值得记"))
        // 判据是同一份常量，改一处两处一起变。
        assertTrue(consolidator.contains(ImportanceScoring.SHARED_CRITERIA.take(20)))
        assertTrue(scorer.contains(ImportanceScoring.SHARED_CRITERIA.take(20)))
    }

    @Test
    fun `没人设名时提示词也不会空`() {
        val prompt = ImportanceScoring.searchScoringSystemPrompt("   ")
        assertTrue(prompt.contains("他"))
        assertTrue(prompt.contains("drawer"))
    }

    // ---------- 6. tags 与 happenedAt ----------

    @Test
    fun `tags 数组与逗号串都能解析`() {
        assertEquals(
            listOf("妹妹", "失眠"),
            ImportanceScoring.parseTags("""["妹妹","失眠"]""")
        )
        assertEquals(
            listOf("妹妹", "失眠", "杭州"),
            ImportanceScoring.parseTags("妹妹, 失眠、杭州")
        )
        assertEquals(listOf("妹妹"), ImportanceScoring.parseTags("""["妹妹"]"""))
        assertEquals("标签去重", listOf("妹妹"), ImportanceScoring.parseTags("""["妹妹","妹妹"]"""))
    }

    @Test
    fun `tags 的坏数据与上限`() {
        assertEquals(emptyList<String>(), ImportanceScoring.parseTags(null))
        assertEquals(emptyList<String>(), ImportanceScoring.parseTags(""))
        assertEquals(emptyList<String>(), ImportanceScoring.parseTags("null"))
        val many = ImportanceScoring.parseTags("""["一","二","三","四","五","六","七"]""")
        assertEquals("最多 5 个标签", MemoryTags.MAX_TAGS, many.size)
        val longTag = ImportanceScoring.parseTags("""["${"很长".repeat(20)}"]""")
        assertEquals("单个标签会被截断", MemoryTags.MAX_TAG_CHARS, longTag.first().length)
    }

    @Test
    fun `相对时间能换算成毫秒`() {
        val now = 1_760_000_000_000L
        val day = 86_400_000L

        val yesterday = ImportanceScoring.parseRelativeTime("昨天", now)
        assertNotNull(yesterday)
        assertEquals(
            "取的是「昨天」这段时间的中点",
            ChineseTerms.timeSpanOf("昨天", now)!!.center,
            yesterday!!
        )
        assertTrue("应该落在昨天附近", yesterday in (now - 2 * day)..now)

        val winter = ImportanceScoring.parseRelativeTime("去年冬天", now)
        assertNotNull("「去年冬天」也要能换算", winter)
        assertTrue("不该被算成今天", winter!! < now)

        assertNull("没有时间说法就不猜", ImportanceScoring.parseRelativeTime("他妹妹", now))
        assertNull(ImportanceScoring.parseRelativeTime(null, now))
        assertNull(ImportanceScoring.parseRelativeTime("", now))
        assertEquals(
            "毫秒时间戳原样采用",
            1_760_000_000_000L,
            ImportanceScoring.parseRelativeTime("1760000000000", now)!!
        )
        assertNull("年份不是时间戳", ImportanceScoring.parseRelativeTime("2025", now))
    }

    @Test
    fun `整理器提示词说明了时间与标签`() {
        val prompt = ImportanceScoring.consolidationSystemPrompt("小满")

        assertTrue("要讲清标签是什么", prompt.contains("标签"))
        assertTrue("要讲清 happenedAt 的语义", prompt.contains("发生在"))
        assertTrue("要讲清「发生时间 ≠ 记下来的时间」", prompt.contains("发生") && prompt.contains("时间"))
        assertTrue("merge 时必须保留时间与标签", prompt.contains("合并时不许丢掉时间和标签"))
    }

    @Test
    fun `空计划不算有事可做`() {
        assertTrue(ConsolidationPlan().isEmpty)
        assertFalse(
            ConsolidationPlan(operations = listOf(MemoryOperation(MemoryOp.KEEP, listOf("1")))).isEmpty
        )
    }
}
