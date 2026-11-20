package com.yap.app.domain

import com.yap.app.domain.ExperienceContract.ContextSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.15 上下文装配 v2（`docs/design/context-memory-v2.md`）。
 *
 * 钉住三件事：**顺序**、**预算**、**超限裁剪顺序（先压主题、再减历史）**。
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
        persona: String = "你是阿澈本人。",
        memo: ContextAssembly.Memo = memory(),
        state: String = "现在是周五 20:10。",
        extras: List<String> = emptyList(),
        practicalChars: Int = ExperienceContract.PRACTICAL_HISTORY_CHARS
    ) = ContextAssembly.Inputs(
        personaAndTools = persona,
        memo = memo,
        currentState = state,
        extras = extras,
        practicalChars = practicalChars
    )

    @Test
    fun `六段按设计文档的顺序出现`() {
        val result = ContextAssembly.assemble(inputs())
        val system = result.system
        val order = listOf(
            "你是阿澈本人。",
            MemoryContract.Injection.LONG_TERM_TITLE,
            MemoryContract.Injection.SHORT_TERM_TITLE,
            PromptContract.Section.STATE,
            MemoryContract.Injection.TOPIC_TITLE
        ).map { system.indexOf(it) }
        order.forEach { assertTrue("每一段都要出现：$order", it >= 0) }
        assertTrue("顺序必须固定：$order", order == order.sorted())
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
        // 预算刚好够人设 + 记忆 + 状态，主题必须被压缩，历史被挤到 0。
        val persona = "设".repeat(1_000)
        val result = ContextAssembly.assemble(
            inputs(
                persona = persona,
                memo = ContextAssembly.Memo(
                    longTerm = "长".repeat(400),
                    shortTerm = "短".repeat(150),
                    topics = topics
                ),
                state = "状态".repeat(100),
                practicalChars = 2_600
            )
        )
        assertTrue("主题必须被压缩", result.compressedTopics)
        assertTrue("丢掉了更旧的段", result.droppedTopicSegments > 0)
        assertTrue("历史被挤到很小", result.historyCharLimit in 0..200)
        // 人设与长期记忆不能被丢：那是「他是谁」。
        assertTrue(result.system.contains(persona.take(100)))
        assertTrue(result.system.contains(MemoryContract.Injection.LONG_TERM_TITLE))
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
            assertTrue(result.system.startsWith("你是阿澈本人。"))
        }
    }

    @Test
    fun `附加块放在主题之后 历史之前 且让位给历史`() {
        val result = ContextAssembly.assemble(
            inputs(extras = listOf("你刚才心里想：算了，不问了。"), practicalChars = 2_000)
        )
        val system = result.system
        assertTrue(system.contains("你刚才心里想"))
        assertTrue(system.indexOf(MemoryContract.Injection.TOPIC_TITLE) < system.indexOf("你刚才心里想"))
        // 预算紧时附加块被丢掉，保证人设与历史。
        val tight = ContextAssembly.assemble(
            inputs(
                persona = "设".repeat(1_800),
                extras = listOf("附".repeat(500)),
                practicalChars = 2_000
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
    fun `历史按预算从最近往前挑 且至少留一条`() {
        val history = listOf("old", "mid", "new")
        val kept = ContextAssembly.trimHistory(history, charLimit = 6) { it.length }
        // 从后往前：「new」3 字 + 「mid」3 字 = 6 刚好；「old」放不下。
        assertEquals(listOf("mid", "new"), kept)
        // 预算 0：至少留最新一条（否则他会对着空气回话）。
        assertEquals(listOf("new"), ContextAssembly.trimHistory(history, 0) { it.length })
        assertEquals(emptyList<String>(), ContextAssembly.trimHistory(emptyList<String>(), 100) { it.length })
    }
}
