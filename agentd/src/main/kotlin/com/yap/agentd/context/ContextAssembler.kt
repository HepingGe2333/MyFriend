package com.yap.agentd.context

import com.yap.app.domain.ExperienceContract
import com.yap.app.domain.MemoryContract
import com.yap.app.domain.PromptContract

/**
 * 每轮上下文的**装配器**（0.15 上下文/记忆 v2，`docs/design/context-memory-v2.md`）。
 *
 * 段顺序来自 `spec/context-profile/README.md` 选出的 **state_first** profile（Bench v2 搜索：
 * 状态/心情放在人设之前，5 样本 85.3 vs 84.3，硬失败 23.3% vs 26.7%）：
 *
 * | 顺序 | 段 | 预算 |
 * | --- | --- | --- |
 * | 1 | 当前状态（时间/活动/睡眠/心情 + `# 引导`） | 比例预算（引导不裁） |
 * | 2 | 完整设定 + 工具 + 反 AI（身份/世界/话风/关系/你能做什么/别像 AI） | 不裁（人设必须完整） |
 * | 3 | 长期记忆缓存 | ≤500 字 |
 * | 4 | 短期记忆缓存 | ≤200 字 |
 * | 5 | 对话主题总结（每段 ≤120 字、最多 12 段、带时间段） | ≤12×120 字 |
 * | 6 | 心理活动（「你刚才心里想」） | ≤60 字 |
 * | 7 | 边界四条 | 不裁（很短） |
 * | 8 | 剩余预算：最近聊天原文 | 余额全部 |
 *
 * 三条硬规则（本文件是服务端的唯一实现）：
 *
 * 1. **顺序固定、段标题固定**：标题全部来自 `:agentcore` 的共享契约
 *    （[PromptContract.Section] / [MemoryContract.Injection]），服务端不再各写一份；
 * 2. **预算是硬约束**：`system 文本 + 历史原文` 的字符数不超过
 *    [ExperienceContract.practicalPlan] 给出的预算（28,000 字 ≈ 14k token，产品口径；
 *    1M 窗口只是能力上限）。超了先**压主题总结**（从旧到新丢、每段截到 120 字），
 *    再**减少历史条数**；
 * 3. **历史永远从最新往前取**，返回时按时间升序（模型读到的顺序与聊天一致）。
 *
 * 为什么不把历史塞进 system 字符串：历史必须保持 `user` / `assistant` 角色（模型靠角色
 * 区分谁说的），所以 [Result.history] 单独返回，由 `ChatPromptBuilder` 映射成
 * `LlmMessage`；本文件只负责「选哪几条、占多少字符」。
 */
class ContextAssembler(
    /** 这一轮的预算规划；生产用 [ExperienceContract.practicalPlan]，测试可以直接给一个小预算。 */
    private val plan: ExperienceContract.ContextPlan = ExperienceContract.practicalPlan()
) {

    /** 一条要作为聊天历史发出的消息（[role] 是存储层的 `USER` / `ASSISTANT`）。 */
    data class HistoryMessage(val role: String, val content: String) {
        /** 模型能看到的字符数（附件说明由调用方拼进 [content]）。 */
        val chars: Int get() = content.length
    }

    /** 装配输入：各段文本 + 最近历史（升序，最后一条是这一轮的用户消息）。 */
    data class Input(
        /** 完整设定 + 工具 + 反 AI（第 2 段，不裁；`ChatPromptBuilder` 已按 profile 排好段顺序）。 */
        val personaAndTools: String,
        /** 长期记忆摘要（第 3 段，≤500 字）。 */
        val longTerm: String? = null,
        /** 短期记忆摘要（第 4 段，≤200 字）。 */
        val shortTerm: String? = null,
        /** 当前状态：时间/活动/睡眠/心情（第 1 段，可含多条段标题）。 */
        val currentState: String = "",
        /**
         * `# 引导` 段（0.15 profile：g12/g17 二选一，见 `PromptGuidance`）。
         *
         * 单独传而不是拼进 [currentState]：引导很短且**固定不该被裁**，状态段却要按比例预算截断；
         * 拼在一起时状态一长就会把引导截掉，模型就收不到「这一轮怎么站」。
         */
        val guidance: String = "",
        /** 对话主题总结（第 5 段，按时间从旧到新）。 */
        val topics: List<MemoryContract.TopicSegment> = emptyList(),
        /** 「你刚才心里想：…」（第 6 段，主题之后、边界之前）。 */
        val mentalActivity: String? = null,
        /** 边界四条（第 7 段，state_first profile 的最后一段）。 */
        val boundaries: String = "",
        /** 最近聊天原文（升序，已带时间戳/说话人前缀）。 */
        val history: List<HistoryMessage> = emptyList()
    )

    /** 装配结果 + 诊断（测试/日志直接断言这些数字）。 */
    data class Result(
        /** 系统提示词（前五段 + 心理活动；不含历史原文）。 */
        val system: String,
        /** 真正发给模型的历史（按时间升序）。 */
        val history: List<HistoryMessage>,
        /** 这一轮实际用的主题段（可能被压缩过）。 */
        val topics: List<MemoryContract.TopicSegment>,
        /** 剩余历史预算（字符）。 */
        val historyCharBudget: Int,
        /** 历史实际占用字符。 */
        val historyChars: Int,
        /** system + 历史的总字符（硬约束：≤ [ExperienceContract.ContextPlan.historyCharBudget]）。 */
        val totalChars: Int,
        /** 主题被压缩过（丢段/截断）。 */
        val topicsCompressed: Boolean,
        /** 丢掉的主题段数（从旧到新丢）。 */
        val droppedTopics: Int,
        /** 被历史预算挤掉的条数（从旧到新丢）。 */
        val droppedHistory: Int,
        /** 配置/输入层面的提醒（不抛异常：坏配置只该让上下文变短，不该让聊天挂掉）。 */
        val warnings: List<String> = emptyList()
    ) {
        /**
         * 系统提示词里出现的段标题，**按实际出现位置**排序（测试用来钉顺序）。
         *
         * 不做「filter 常量列表」：那样只验证「都在」，验证不了「顺序对」——
         * 段顺序是 profile 的硬约束，必须逐段比位置。
         */
        fun sectionOrder(): List<String> = SECTION_TITLES
            .mapNotNull { title -> system.indexOf(title).takeIf { it >= 0 }?.let { title to it } }
            .sortedBy { it.second }
            .map { it.first }
    }

    /**
     * 装配一轮上下文。**不抛异常**：负数/空输入都退化成「能装多少装多少」。
     */
    fun assemble(input: Input): Result {
        val warnings = ArrayList<String>()
        val budget = plan.historyCharBudget.coerceAtLeast(0)
        if (budget <= 0) warnings += "上下文预算为 0（窗口/产品上限配置异常），这一轮只发历史原文"

        // ---- 第 1 段：当前状态（比例预算；时间/活动/睡眠/心情都在这里）
        // 引导（g12/g17）跟在心情之后：它短且固定，不参与裁剪（宁可状态截短，也不能丢引导）。
        val guidance = input.guidance.trim()
        val stateLimit = plan.charLimitFor(ExperienceContract.ContextSegment.CURRENT_STATE)
        val stateBudget =
            if (guidance.isEmpty()) stateLimit else (stateLimit - guidance.length - SEPARATOR_COST).coerceAtLeast(0)
        val currentState = joinBlocks(clipBlock(input.currentState, stateBudget), guidance)

        // ---- 第 2 段：完整设定 + 工具 + 反 AI（不裁：人设必须完整）
        val persona = input.personaAndTools.trim()
        if (persona.isEmpty()) warnings += "第 2 段（完整设定）为空"

        // ---- 第 3/4 段：长期/短期记忆（取「设计硬顶」与「比例预算」的较小者）
        // 两段合成一个 `# 你的备忘录`（[MemoryContract.Injection.notes] 的顺序：长期在上、短期在下）。
        val memo = MemoryContract.Injection.notes(
            longTerm = clipParagraph(
                input.longTerm,
                minOf(
                    MemoryContract.Summary.LONG_TERM_MAX_CHARS,
                    plan.charLimitFor(ExperienceContract.ContextSegment.LONG_TERM_MEMORY)
                )
            ),
            shortTerm = clipParagraph(
                input.shortTerm,
                minOf(
                    MemoryContract.Summary.SHORT_TERM_MAX_CHARS,
                    plan.charLimitFor(ExperienceContract.ContextSegment.SHORT_TERM_MEMORY)
                )
            )
        )

        // ---- 第 6/7 段：心理活动（≤60 字）+ 边界（都不占比例预算，只占字符）
        val mental = clipParagraph(
            input.mentalActivity?.let { PromptContract.MentalActivity.sanitizeThought(it) },
            PromptContract.MentalActivity.MAX_THOUGHT_CHARS
        )
        val tail = joinBlocks(
            if (mental.isEmpty()) {
                ""
            } else {
                // 标题固定用共享契约（0.14 的「# 你刚才心里想的」多了个「的」，0.15 起统一）。
                PromptContract.MentalActivity.TITLE + "\n" + PromptContract.MentalActivity.LINE_PREFIX + mental
            },
            input.boundaries.trim()
        )

        // ---- 第 1~4 段 + 末尾固定段已经定稿；剩下的给主题与历史
        val head = joinBlocks(currentState, persona, memo)
        val topicLimit = minOf(
            MemoryContract.Summary.TOPIC_MAX_CHARS,
            plan.charLimitFor(ExperienceContract.ContextSegment.TOPIC_SUMMARY)
        )
        // 主题插在 head 与 tail 之间：先用「没有主题」量一次真实长度（含段间空行），再算主题预算。
        val baseLength = renderSystem(head, EMPTY_TOPIC_PLAN, tail).length
        var topicBudget = (budget - baseLength - MIN_HISTORY_CHARS).coerceAtLeast(0)
        var topicPlan = planTopics(input.topics, minOf(topicLimit, topicBudget))
        var system = renderSystem(head, topicPlan, tail)
        // 极端情形（人设极长 / 预算极小）：再压一轮主题，尽量给历史留几条。
        if (topicPlan.segments.isNotEmpty() && system.length + MIN_HISTORY_CHARS > budget) {
            val overflow = system.length + MIN_HISTORY_CHARS - budget
            topicBudget = (topicBudget - overflow - 1).coerceAtLeast(0)
            topicPlan = planTopics(topicPlan.segments, minOf(topicLimit, topicBudget))
            system = renderSystem(head, topicPlan, tail)
            if (topicPlan.segments.isEmpty()) {
                warnings += "系统提示词已占满预算，主题总结被整段省略"
            } else {
                warnings += "预算不足：主题总结被压缩到 ${topicPlan.segments.size} 段"
            }
        }

        // ---- 最后：历史（余额全部；超了从旧到新丢）
        val historyBudget = (budget - system.length).coerceAtLeast(0)
        val selected = takeHistory(input.history, historyBudget)
        val historyChars = selected.history.sumOf { it.chars }
        return Result(
            system = system,
            history = selected.history,
            topics = topicPlan.segments,
            historyCharBudget = plan.historyCharBudget,
            historyChars = historyChars,
            totalChars = system.length + historyChars,
            topicsCompressed = topicPlan.compressed,
            droppedTopics = topicPlan.dropped,
            droppedHistory = selected.dropped,
            warnings = warnings
        )
    }

    // ------------------------------------------------------------------ 段装配

    /**
     * 把若干段拼成系统提示词：空段丢掉、段之间一个空行、末尾不留空行。
     *
     * 顺序就是 profile 的顺序（state_first）：状态 → 人设/工具/反AI → 备忘录 → 主题 → 心理 → 边界。
     */
    private fun joinBlocks(vararg blocks: String): String = blocks
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

    private fun renderSystem(head: String, topics: TopicPlan, tail: String): String = joinBlocks(
        head,
        if (topics.text.isNotBlank()) MemoryContract.Injection.TOPIC_TITLE + "\n" + topics.text else "",
        tail
    )

    // ------------------------------------------------------------------ 主题

    private class TopicPlan(
        val segments: List<MemoryContract.TopicSegment>,
        /** 渲染后的主题行（不含 `## 对话主题` 标题）。 */
        val text: String,
        val compressed: Boolean,
        val dropped: Int
    )

    /**
     * 主题总结的裁剪：先按上限只留**最新**的 12 段，再从旧到新丢到预算内。
     *
     * 为什么从旧到新丢：主题总结按时间从旧到新排列，最近的话题与这一轮最相关；
     * 丢老话题的代价远小于丢「刚刚聊到哪」。
     */
    private fun planTopics(
        topics: List<MemoryContract.TopicSegment>,
        charBudget: Int
    ): TopicPlan {
        // 段对象与文本成对处理：空摘要的段在渲染时会被丢掉，不能让对象列表与行数错位。
        val usable = topics
            .takeLast(MemoryContract.Summary.TOPIC_MAX_SEGMENTS)
            .mapNotNull { topic ->
                val line = MemoryContract.Injection.topicLine(topic) ?: return@mapNotNull null
                topic to line
            }
        if (usable.isEmpty() || charBudget <= 0) {
            return TopicPlan(emptyList(), "", compressed = usable.isNotEmpty(), dropped = usable.size)
        }
        val kept = ArrayList<Pair<MemoryContract.TopicSegment, String>>()
        var used = 0
        for (entry in usable.asReversed()) {
            if (used + entry.second.length + 1 > charBudget) break
            kept.add(entry)
            used += entry.second.length + 1
        }
        val ordered = kept.asReversed()
        val dropped = usable.size - ordered.size
        return TopicPlan(
            segments = ordered.map { it.first },
            text = ordered.joinToString("\n") { it.second },
            compressed = dropped > 0,
            dropped = dropped
        )
    }

    // ------------------------------------------------------------------ 历史

    private class HistoryPlan(val history: List<HistoryMessage>, val dropped: Int)

    /** 从最新往前取，直到预算吃完；返回升序列表（与聊天顺序一致）。 */
    private fun takeHistory(history: List<HistoryMessage>, charBudget: Int): HistoryPlan {
        if (history.isEmpty()) return HistoryPlan(emptyList(), 0)
        if (charBudget <= 0) return HistoryPlan(emptyList(), history.size)
        val kept = ArrayDeque<HistoryMessage>()
        var used = 0
        var processed = 0
        for (message in history.asReversed()) {
            if (message.content.isBlank()) {
                processed += 1
                continue
            }
            val cost = message.chars + HISTORY_SEPARATOR_COST
            if (used + cost > charBudget) {
                // 最新那条再长也要留一点（否则模型看不到这一轮用户说了什么）：
                // 只对「最新一条」做硬截断，其余按预算丢弃。
                if (kept.isEmpty()) {
                    val trimmed = message.content.take((charBudget - used).coerceAtLeast(0))
                    if (trimmed.isNotBlank()) kept.addFirst(message.copy(content = trimmed))
                }
                processed += 1
                break
            }
            used += cost
            kept.addFirst(message)
            processed += 1
        }
        val dropped = (history.size - kept.size).coerceAtLeast(0)
        return HistoryPlan(kept.toList(), dropped)
    }

    // ------------------------------------------------------------------ 小工具

    /** 压成一行（备忘录/主题里的摘要不需要换行；换行会让人误以为是清单）。 */
    private fun flatten(raw: String?): String = raw.orEmpty().replace(Regex("\\s+"), " ").trim()

    /** 截到 [max] 字并补「…」；空/纯空白返回空串。 */
    private fun clipParagraph(raw: String?, max: Int): String {
        val text = flatten(raw)
        if (text.isEmpty() || max <= 0) return ""
        return if (text.length <= max) text else text.take(max).trimEnd() + "…"
    }

    /** 多行块按行截断（状态段里有 `# 标题` + 数据行，整块压成一行会毁掉结构）。 */
    private fun clipBlock(raw: String?, max: Int): String {
        val text = raw.orEmpty().trim()
        if (text.isEmpty() || max <= 0) return ""
        if (text.length <= max) return text
        val out = StringBuilder()
        for (line in text.lines()) {
            if (out.length + line.length + 1 > max) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString().trim()
    }

    companion object {
        /** 给历史至少留多少字（再压主题也要留几条最近的话，否则模型不知道在跟谁说话）。 */
        const val MIN_HISTORY_CHARS: Int = 400

        /** 每条历史之间的分隔开销（`[HH:mm] 用户：` / `[HH:mm] 他：` 前缀 + 换行）。 */
        private const val HISTORY_SEPARATOR_COST: Int = 16

        /** 段与段之间的空行开销（`\n\n`）。 */
        private const val SEPARATOR_COST: Int = 2

        /**
         * `# 引导` 段标题（0.15 profile；`:agentcore` 暂无这条常量，先由服务端持有）。
         *
         * 与 `PromptGuidance.TITLE` 同值：这里不直接引用 llm 包，避免 context → llm 的反向依赖。
         */
        const val GUIDANCE_TITLE: String = "# 引导"

        /** 没有任何主题时的空计划（只用来量「不含主题」的真实长度）。 */
        private val EMPTY_TOPIC_PLAN = TopicPlan(emptyList(), "", compressed = false, dropped = 0)

        /** 段标题（顺序即 state_first profile 里的出现顺序；测试用 `sectionOrder()` 钉住）。 */
        val SECTION_TITLES: List<String> = listOf(
            PromptContract.Section.STATE,
            PromptContract.Section.MOOD,
            GUIDANCE_TITLE,
            PromptContract.Section.IDENTITY,
            PromptContract.Section.WORLD,
            PromptContract.Section.VOICE,
            PromptContract.Section.RELATIONSHIP,
            PromptContract.Section.CAPABILITIES,
            PromptContract.Section.ANTI_AI,
            MemoryContract.Injection.TITLE,
            MemoryContract.Injection.LONG_TERM_TITLE,
            MemoryContract.Injection.SHORT_TERM_TITLE,
            MemoryContract.Injection.TOPIC_TITLE,
            PromptContract.Section.MENTAL_ACTIVITY,
            PromptContract.Section.BOUNDARIES
        )
    }
}
