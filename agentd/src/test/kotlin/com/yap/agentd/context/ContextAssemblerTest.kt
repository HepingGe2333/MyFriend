package com.yap.agentd.context

import com.yap.app.domain.ExperienceContract
import com.yap.app.domain.MemoryContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文装配 v2 + 0.15 上下文 profile（`docs/design/context-memory-v2.md`、
 * `spec/context-profile/README.md`）的纯规则测试。
 *
 * 钉住四件事：
 * 1. **顺序固定（state_first）**：当前状态（含 `# 引导`）→ 人设 → 工具 → 反 AI → 备忘录
 *    → 主题 → 心理活动 → 边界；
 * 2. **预算是硬约束**：system + 历史总字符 ≤ `practicalPlan().historyCharBudget`（默认 28,000）；
 * 3. **超限先压主题再减历史**：主题从旧到新丢，历史永远从最新往前取；
 * 4. **段标题来自共享契约**：`# 你的备忘录` / `## 长期记忆` / `## 短期记忆` / `## 对话主题`。
 */
class ContextAssemblerTest {

    private fun persona(): String = listOf(
        "# 你是谁",
        "- 林深",
        "",
        "# 你所在的世界",
        "- 临港新城",
        "",
        "# 你怎么说话",
        "- 短句",
        "",
        "# 你们的关系",
        "- 恋人",
        "",
        "# 你能做什么",
        "- `remember`：记下来",
        "",
        "# 别像 AI",
        "- 不要每句话都反问。"
    ).joinToString("\n")

    private fun boundaries(): String = listOf(
        "# 边界",
        "- 不编造"
    ).joinToString("\n")

    private fun history(count: Int, chars: Int = 50): List<ContextAssembler.HistoryMessage> =
        (1..count).map { index ->
            ContextAssembler.HistoryMessage(
                role = if (index % 2 == 0) "ASSISTANT" else "USER",
                content = "第 $index 条消息" + "啊".repeat(chars)
            )
        }

    @Test
    fun `sections follow the fixed v2 order`() {
        val assembler = ContextAssembler(ExperienceContract.practicalPlan())
        val result = assembler.assemble(
            ContextAssembler.Input(
                personaAndTools = persona(),
                longTerm = "你们认识三年了。",
                shortTerm = "下周要去哈尔滨。",
                currentState = "# 你此刻的状态\n现在是 19:40。\n\n# 你的心情\n心情：有点累（强度 3/5）。",
                guidance = "# 引导\n这一轮：有点防备，不轻易示弱\n- 用短句挡一下\n- 被问急了才多露半句\n- 不要掏心掏肺。",
                topics = listOf(MemoryContract.TopicSegment("2026-11-18 晚", "加班与方案改版")),
                mentalActivity = "刚才那句话有点戳到我",
                boundaries = boundaries(),
                history = history(4)
            )
        )
        assertEquals(
            listOf(
                "# 你此刻的状态",
                "# 你的心情",
                "# 引导",
                "# 你是谁",
                "# 你所在的世界",
                "# 你怎么说话",
                "# 你们的关系",
                "# 你能做什么",
                "# 别像 AI",
                "# 你的备忘录",
                "## 长期记忆",
                "## 短期记忆",
                "## 对话主题",
                "# 你刚才心里想",
                "# 边界"
            ),
            result.sectionOrder()
        )
        assertTrue("状态段在最前", result.system.startsWith("# 你此刻的状态"))
        assertTrue("边界是最后一段", result.system.trimEnd().endsWith("- 不编造"))
        // 历史按时间升序（最后一条是这一轮的用户消息）。
        assertEquals(4, result.history.size)
        assertTrue(result.history.last().content.startsWith("第 4 条消息"))
        assertTrue(result.history.first().content.startsWith("第 1 条消息"))
    }

    @Test
    fun `the guidance section survives even when the state block is clipped`() {
        val plan = ExperienceContract.planForBudget(4_000)
        val assembler = ContextAssembler(plan)
        val guidance = "# 引导\n这一轮：只聊此刻，不翻旧账\n- 回应现在这句话\n- 不延伸过去\n- 不要反问。"
        val result = assembler.assemble(
            ContextAssembler.Input(
                personaAndTools = persona(),
                currentState = "# 你此刻的状态\n" + "状态".repeat(2000),
                guidance = guidance,
                boundaries = boundaries(),
                history = history(4)
            )
        )
        assertTrue("引导不能被状态段的裁剪吃掉", result.system.contains(guidance))
        assertTrue("引导紧跟心情/状态之后、人设之前", result.sectionOrder().take(2) == listOf("# 你此刻的状态", "# 引导"))
    }

    @Test
    fun `the default plan keeps everything within the practical budget`() {
        val plan = ExperienceContract.practicalPlan()
        val assembler = ContextAssembler(plan)
        val result = assembler.assemble(
            ContextAssembler.Input(
                personaAndTools = persona(),
                longTerm = "长".repeat(900),
                shortTerm = "短".repeat(400),
                currentState = "# 你此刻的状态\n" + "状态".repeat(2000),
                topics = (1..12).map { MemoryContract.TopicSegment("2026-11-1$it 晚", "主题".repeat(100)) },
                mentalActivity = "念头".repeat(40),
                history = history(120, chars = 300)
            )
        )
        assertTrue("总字符必须 ≤ 产品预算：${result.totalChars}", result.totalChars <= plan.historyCharBudget)
        assertTrue("长期记忆截到 500 字", result.system.contains("长".repeat(500)))
        assertTrue("短期记忆截到 200 字", result.system.contains("短".repeat(200)))
        assertTrue("主题每段截到 120 字（+省略号）", result.system.contains("主题".repeat(60) + "…"))
    }

    @Test
    fun `overflowing topics are compressed before history is dropped`() {
        // 4,000 字预算：人设 ~150、主题 12×120 ≈ 1,440、还要留至少 400 给历史。
        val plan = ExperienceContract.planForBudget(4_000)
        val assembler = ContextAssembler(plan)
        val topics = (1..12).map { index ->
            MemoryContract.TopicSegment("2026-11-1$index 晚", "第 $index 段主题" + "。".repeat(110))
        }
        val result = assembler.assemble(
            ContextAssembler.Input(
                personaAndTools = persona(),
                topics = topics,
                history = history(30, chars = 120)
            )
        )
        assertTrue("主题被压缩过：${result.topics.size} 段", result.topicsCompressed)
        assertTrue("压缩从旧到新丢（保留的是最新的几段）", result.topics.last().summary.contains("第 12 段主题"))
        assertTrue("主题压缩之后仍然要留历史", result.history.isNotEmpty())
        assertTrue("历史从最新往前取", result.history.last().content.startsWith("第 30 条消息"))
        assertTrue("总字符 ≤ 预算", result.totalChars <= plan.historyCharBudget)
    }

    @Test
    fun `a tiny budget still keeps the newest message`() {
        val plan = ExperienceContract.planForBudget(200)
        val assembler = ContextAssembler(plan)
        val result = assembler.assemble(
            ContextAssembler.Input(
                personaAndTools = "# 你是谁\n- 林深",
                history = history(10, chars = 100)
            )
        )
        assertEquals("只留最新一条（按预算截断）", 1, result.history.size)
        assertTrue(result.history.single().content.startsWith("第 10 条消息"))
        assertTrue(result.totalChars <= plan.historyCharBudget)
    }

    @Test
    fun `long term memory is clipped to the shared cap`() {
        val assembler = ContextAssembler(ExperienceContract.practicalPlan())
        val result = assembler.assemble(
            ContextAssembler.Input(
                personaAndTools = "# 你是谁\n- 林深",
                longTerm = "记".repeat(MemoryContract.Summary.LONG_TERM_MAX_CHARS + 100)
            )
        )
        assertTrue(result.system.contains("记".repeat(MemoryContract.Summary.LONG_TERM_MAX_CHARS)))
        assertTrue(result.system.contains("…"))
    }
}
