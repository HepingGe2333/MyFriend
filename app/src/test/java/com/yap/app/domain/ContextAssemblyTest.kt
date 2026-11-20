package com.yap.app.domain

import com.yap.app.domain.ExperienceContract.ContextSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.15 上下文装配 v2（`docs/design/context-memory-v2.md` + `spec/context-profile/README.md`）。
 *
 * 钉住三件事：**state_first 的段顺序**、**预算**、**超限裁剪顺序（先压主题、再丢附加块、最后减历史）**。
 * 这些都是纯函数，不依赖数据库与时钟。
 */
class ContextAssemblyTest {

    private fun memory(text: String = "他怕冷，冬天总说手脚冰。") = ContextAssembly.Memo(
        longTerm = text,
        shortTerm = "约好周六下午看房子。",
        topics = listOf(
            MemoryContract.TopicSegment("2026-11-10 晚上", "聊了他换工作的犹豫。"),
            MemoryContract.TopicSegment("2026-11-12 下午", "聊了周末看房。")
        )
    )

    private fun inputs(
        state: String = "# 你此刻的状态\n现在是周五 20:10。",
        guidance: String = "# 引导\n这一轮：只聊此刻，不翻旧账\n- 回应现在这句话\n- 不延伸过去\n- 不要反问。",
        persona: String = "你是阿澈本人。",
        tools: String = "# 你能做什么\n- remember",
        antiAi: String = "# 别像 AI\n- 不要每句话都反问。",
        memo: ContextAssembly.Memo = memory(),
        extras: List<String> = emptyList(),
        language: String = "## 语言\n- 用中文思考和说话。",
        boundaries: String = "# 边界\n- 不解释 AI 身份。",
        practicalChars: Int = ExperienceContract.PRACTICAL_HISTORY_CHARS
    ) = ContextAssembly.Inputs(
        state = state,
        guidance = guidance,
        persona = persona,
        tools = tools,
        antiAi = antiAi,
        memo = memo,
        extras = extras,
        language = language,
        boundaries = boundaries,
        practicalChars = practicalChars
    )

    @Test
    fun `段顺序是 state_first 的状态 引导 人设 工具 反AI 记忆 语言 边界`() {
        val result = ContextAssembly.assemble(inputs())
        val system = result.system
        val order = listOf(
            PromptContract.Section.STATE,
            "# 引导",
            "你是阿澈本人。",
            "# 你能做什么",
            "# 别像 AI",
            MemoryContract.Injection.LONG_TERM_TITLE,
            MemoryContract.Injection.SHORT_TERM_TITLE,
            MemoryContract.Injection.TOPIC_TITLE,
            "## 语言",
            "# 边界"
        ).map { system.indexOf(it) }
        order.forEach { assertTrue("每一段都要出现：$order\n$system", it >= 0) }
        assertEquals("顺序必须固定：$order\n$system", order.sorted(), order)
        // 边界永远是最后一段，后面不能再有段落标题。
        assertTrue(system.trimEnd().endsWith("- 不解释 AI 身份。"))
        assertFalse(system.substringAfter("# 边界").contains("\n# "))
    }

    @Test
    fun `引导紧跟在状态之后 而不是在人设之前之后乱放`() {
        val system = ContextAssembly.assemble(inputs()).system
        val state = system.indexOf(PromptContract.Section.STATE)
        val guidance = system.indexOf("# 引导")
        val persona = system.indexOf("你是阿澈本人。")
        assertTrue("状态要在引导之前", state in 0..<guidance)
        assertTrue("引导要在人设之前（心情之后、话风之前）", guidance in 0..<persona)
    }

    @Test
    fun `段标题统一来自共享契约`() {
        val system = ContextAssembly.assemble(inputs()).system
        assertTrue(system.contains(MemoryContract.Injection.TITLE))
        assertTrue(system.contains(MemoryContract.Injection.LONG_TERM_TITLE))
        assertTrue(system.contains(MemoryContract.Injection.SHORT_TERM_TITLE))
        assertTrue(system.contains(MemoryContract.Injection.TOPIC_TITLE))
        assertTrue(system.contains(PromptContract.Section.STATE))
        // 主题行带时间段。
        assertTrue(system.contains("- 2026-11-10 晚上：聊了他换工作的犹豫。"))
    }

    @Test
    fun `长短期记忆按共享上限截断`() {
        val long = "字".repeat(900)
        val short = "短".repeat(400)
        val result = ContextAssembly.assemble(
            inputs(memo = ContextAssembly.Memo(longTerm = long, shortTerm = short))
        )
        val longBlock = result.blocks.first { it.contains(MemoryContract.Injection.LONG_TERM_TITLE) }
        val shortBlock = result.blocks.first { it.contains(MemoryContract.Injection.SHORT_TERM_TITLE) }
        // 截断后带「…」，且内容不超过 500 / 200（再加一个省略号）。
        assertTrue(longBlock.length <= 500 + 20)
        assertTrue(shortBlock.length <= 200 + 20)
        assertTrue(longBlock.endsWith("…"))
        assertTrue(shortBlock.endsWith("…"))
    }

    @Test
    fun `主题总结最多十二段 每段最多一百二十字`() {
        val topics = (1..20).map {
            MemoryContract.TopicSegment("2026-11-${it.toString().padStart(2, '0')} 晚上", "主".repeat(300))
        }
        val result = ContextAssembly.assemble(inputs(memo = ContextAssembly.Memo(topics = topics)))
        assertTrue("最多 12 段", result.topicSegments.size <= 12)
        result.topicSegments.forEach { assertEquals(121, it.summary.length) } // 120 + 「…」
    }

    @Test
    fun `剩余预算按价格给历史 且合计等于预算`() {
        val result = ContextAssembly.assemble(inputs())
        assertTrue(result.historyCharLimit > 0)
        assertTrue("历史拿剩余预算", result.historyCharLimit < result.plan.historyCharBudget)
        assertTrue(result.plan.isBalanced)
        assertEquals(ExperienceContract.PRACTICAL_HISTORY_CHARS, result.plan.historyCharBudget)
    }

    @Test
    fun `超限时先压主题总结 再减历史`() {
        val topics = (1..12).map {
            MemoryContract.TopicSegment("2026-11-${it.toString().padStart(2, '0')} 晚上", "主".repeat(120))
        }
        // 预算刚好够固定块（状态/引导/人设/工具/反AI/语言/边界）+ 记忆，主题必须被压缩，历史被挤到很小。
        val result = ContextAssembly.assemble(
            inputs(
                persona = "设".repeat(1_000),
                memo = ContextAssembly.Memo(
                    longTerm = "长".repeat(400),
                    shortTerm = "短".repeat(150),
                    topics = topics
                ),
                state = "# 你此刻的状态\n" + "状态".repeat(100),
                practicalChars = 2_000
            )
        )
        assertTrue("主题必须被压缩", result.compressedTopics)
        assertTrue("丢掉了更旧的段", result.droppedTopicSegments > 0)
        assertTrue("历史被挤到很小", result.historyCharLimit in 0..200)
        // 人设与长期记忆不能被丢：那是「他是谁」。
        assertTrue(result.system.contains("设设设"))
        assertTrue(result.system.contains(MemoryContract.Injection.LONG_TERM_TITLE))
        // 状态/引导/边界也不能被丢。
        assertTrue(result.system.contains(PromptContract.Section.STATE))
        assertTrue(result.system.contains("# 引导"))
        assertTrue(result.system.contains("# 边界"))
    }

    @Test
    fun `预算极小也不丢人设 且不抛异常`() {
        val result = ContextAssembly.assemble(inputs(practicalChars = 1))
        assertEquals(0, result.historyCharLimit)
        assertTrue(result.system.isNotBlank())
        assertTrue("人设永远保留", result.system.contains("你是阿澈本人。"))
        assertTrue(result.overflowChars >= 0)
    }

    @Test
    fun `零预算与负数预算都安全`() {
        listOf(0, -100).forEach { chars ->
            val result = ContextAssembly.assemble(inputs(practicalChars = chars))
            assertEquals(0, result.historyCharLimit)
            // 零预算时状态段自身被压到 0 字（它受 CURRENT_STATE 的比例上限约束），
            // 但不受比例约束的引导/人设/边界仍然在——它们是「他是谁」，不能被预算删掉。
            assertTrue(result.system.contains("你是阿澈本人。"))
            assertTrue(result.system.contains("# 引导"))
            assertTrue(result.system.contains("# 边界"))
        }
    }

    @Test
    fun `附加块放在备忘录之后 语言之前 且让位给历史`() {
        val result = ContextAssembly.assemble(
            inputs(extras = listOf("你刚才心里想：算了，不问了。"), practicalChars = 3_000)
        )
        val system = result.system
        assertTrue(system.contains("你刚才心里想"))
        assertTrue(system.indexOf(MemoryContract.Injection.TOPIC_TITLE) < system.indexOf("你刚才心里想"))
        assertTrue("附加块在语言段之前", system.indexOf("你刚才心里想") < system.indexOf("## 语言"))
        // 预算紧时附加块被丢掉，保证人设与历史。
        val tight = ContextAssembly.assemble(
            inputs(
                persona = "设".repeat(1_800),
                extras = listOf("附".repeat(500)),
                practicalChars = 2_200
            )
        )
        assertFalse("附加块不是身份，预算不够就不带", tight.system.contains("附附附"))
    }

    @Test
    fun `空输入得到空 system 而不是一堆空标题`() {
        val result = ContextAssembly.assemble(ContextAssembly.Inputs())
        assertEquals("", result.system)
        assertEquals(0, result.blocks.size)
    }

    @Test
    fun `状态段正文由调用方给 标题由共享契约补`() {
        // 调用方传的是正文（PromptBuilder.currentStateBody），标题不能重复写两遍。
        val system = ContextAssembly.assemble(
            inputs(state = "现在是周五 20:10。")
        ).system
        assertEquals("状态标题只出现一次", 1, Regex(Regex.escape(PromptContract.Section.STATE)).findAll(system).count())
        assertTrue(system.contains(PromptContract.Section.STATE + "\n现在是周五 20:10。"))
    }

    @Test
    fun `历史按预算从最近往前挑 且至少留一条`() {
        val history = listOf("old", "mid", "new")
        val kept = ContextAssembly.trimHistory(history, charLimit = 6) { it.length }
        // 从后往前：「new」3 字 + 「mid」3 字 = 6 刚好；「old」放不下。
        assertEquals(listOf("mid", "new"), kept)
        // 预算 0：至少留最新一条（否则他会对着空气回话）。
        assertEquals(listOf("new"), ContextAssembly.trimHistory(history, 0) { it.length })
        assertEquals(emptyList<String>(), ContextAssembly.trimHistory(emptyList<String>(), 100) { it.length })
    }

    /** 段的枚举顺序仍是共享契约的六段（0.15 只改注入顺序，不改契约本身——agentcore 不归本任务）。 */
    @Test
    fun `段枚举仍在共享契约里`() {
        assertEquals(
            listOf(
                ContextSegment.FULL_PERSONA_AND_TOOLS,
                ContextSegment.LONG_TERM_MEMORY,
                ContextSegment.SHORT_TERM_MEMORY,
                ContextSegment.CURRENT_STATE,
                ContextSegment.TOPIC_SUMMARY,
                ContextSegment.REMAINING_HISTORY
            ),
            ContextAssembly.SEGMENT_ORDER
        )
    }
}
