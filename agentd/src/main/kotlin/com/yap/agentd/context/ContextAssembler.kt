package com.yap.agentd.context

import com.yap.app.domain.ExperienceContract
import com.yap.app.domain.MemoryContract
import com.yap.app.domain.PromptContract

/**
 * 每轮上下文的**装配器**（0.15 上下文/记忆 v2，`docs/design/context-memory-v2.md`）。
 *
 * 设计文档把一轮上下文钉成六段固定顺序：
 *
 * | 顺序 | 段 | 预算 |
 * | --- | --- | --- |
 * | 1 | 完整设定 + 工具（身份/世界/话风/关系/边界/你能做什么） | 不裁（人设必须完整） |
 * | 2 | 长期记忆缓存 | ≤500 字 |
 * | 3 | 短期记忆缓存 | ≤200 字 |
 * | 4 | 当前状态（时间/活动/睡眠/心情直觉标签 + 三维） | 比例预算 |
 * | 5 | 对话主题总结（每段 ≤120 字、最多 12 段、带时间段） | ≤12×120 字 |
 * | 6 | 剩余预算：最近聊天原文 | 余额全部 |
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

    /** 装配输入：前五段文本 + 最近历史（升序，最后一条是这一轮的用户消息）。 */
    data class Input(
        /** 完整设定 + 工具清单（第 1 段，不裁）。 */
        val personaAndTools: String,
        /** 长期记忆摘要（第 2 段，≤500 字）。 */
        val longTerm: String? = null,
        /** 短期记忆摘要（第 3 段，≤200 字）。 */
        val shortTerm: String? = null,
        /** 当前状态：时间/活动/睡眠/心情（第 4 段，可含多条段标题）。 */
        val currentState: String = "",
        /** 对话主题总结（第 5 段，按时间从旧到新）。 */
        val topics: List<MemoryContract.TopicSegment> = emptyList(),
        /** 「你刚才心里想：…」（0.14 心理活动；放在主题之后、历史之前）。 */
        val mentalActivity: String? = null,
        /** 最近聊天原文（升序）。 */
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
        /** 系统提示词里出现了哪些段标题（测试用来钉顺序）。 */
        fun sectionOrder(): List<String> = SECTION_TITLES.filter { system.contains(it) }
    }

    /**
     * 装配一轮上下文。**不抛异常**：负数/空输入都退化成「能装多少装多少」。
     */
    fun assemble(input: Input): Result {
        val warnings = ArrayList<String>()
        val budget = plan.historyCharBudget.coerceAtLeast(0)
        if (budget <= 0) warnings += "上下文预算为 0（窗口/产品上限配置异常），这一轮只发历史原文"

        val persona = input.personaAndTools.trim()
        if (persona.isEmpty()) warnings += "第 1 段（完整设定）为空"

        // ---- 第 2/3 段：长期/短期记忆（取「设计硬顶」与「比例预算」的较小者）
        val longTerm = clipParagraph(
            input.longTerm,
            minOf(
                MemoryContract.Summary.LONG_TERM_MAX_CHARS,
                plan.charLimitFor(ExperienceContract.ContextSegment.LONG_TERM_MEMORY)
            )
        )
        val shortTerm = clipParagraph(
            input.shortTerm,
            minOf(
                MemoryContract.Summary.SHORT_TERM_MAX_CHARS,
                plan.charLimitFor(ExperienceContract.ContextSegment.SHORT_TERM_MEMORY)
            )
        )

        // ---- 第 4 段：当前状态（比例预算；时间/活动/心情都在这里）
        val currentState = clipBlock(
            input.currentState,
            plan.charLimitFor(ExperienceContract.ContextSegment.CURRENT_STATE)
        )

        // ---- 心理活动：「你刚才心里想」最多 60 字，不单独占比例（很小）
        val mental = clipParagraph(
            input.mentalActivity?.let { PromptContract.MentalActivity.sanitizeThought(it) },
            PromptContract.MentalActivity.MAX_THOUGHT_CHARS
        )

        // ---- 第 1~4 段已经定稿；剩下的给主题与历史
        val head = buildHead(persona, longTerm, shortTerm, currentState, mental)
        val topicLimit = minOf(
            MemoryContract.Summary.TOPIC_MAX_CHARS,
            plan.charLimitFor(ExperienceContract.ContextSegment.TOPIC_SUMMARY)
        )
        var topicBudget = (budget - head.length - MIN_HISTORY_CHARS).coerceAtLeast(0)
        var topicPlan = planTopics(input.topics, minOf(topicLimit, topicBudget))
        var system = renderSystem(head, topicPlan)
        // 极端情形（人设极长 / 预算极小）：再压一轮主题，尽量给历史留几条。
        if (topicPlan.segments.isNotEmpty() && system.length + MIN_HISTORY_CHARS > budget) {
            val overflow = system.length + MIN_HISTORY_CHARS - budget
            topicBudget = (topicBudget - overflow - 1).coerceAtLeast(0)
            topicPlan = planTopics(topicPlan.segments, minOf(topicLimit, topicBudget))
            system = renderSystem(head, topicPlan)
            if (topicPlan.segments.isEmpty()) {
                warnings += "系统提示词已占满预算，主题总结被整段省略"
            } else {
                warnings += "预算不足：主题总结被压缩到 ${topicPlan.segments.size} 段"
            }
        }

        // ---- 第 6 段：历史（余额全部；超了从旧到新丢）
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

    // ------------------------------------------------------------------ 前四段

    /**
     * 第 1~4 段的文本。
     *
     * 长期 / 短期合成一个 `# 你的备忘录` 段（[MemoryContract.Injection.notes] 的顺序：
     * 长期在上、短期在下），这样「备忘录」只有一处标题，模型不会以为是两件事。
     */
    private fun buildHead(
        persona: String,
        longTerm: String,
        shortTerm: String,
        currentState: String,
        mentalActivity: String
    ): String = buildString {
        if (persona.isNotEmpty()) append(persona)
        val memo = MemoryContract.Injection.notes(longTerm = longTerm, shortTerm = shortTerm)
        if (memo.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append(memo)
        }
        if (currentState.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append(currentState)
        }
        if (mentalActivity.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            // 标题固定用共享契约（0.14 的「# 你刚才心里想的」多了个「的」，0.15 起统一）。
            append(PromptContract.MentalActivity.TITLE)
            append("\n")
            append(PromptContract.MentalActivity.LINE_PREFIX)
            append(mentalActivity)
        }
    }

    private fun renderSystem(head: String, topics: TopicPlan): String = buildString {
        if (head.isNotEmpty()) append(head)
        if (topics.text.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(MemoryContract.Injection.TOPIC_TITLE)
            append("\n")
            append(topics.text)
        }
    }

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

        /** 每条历史之间的分隔开销（`对方：` / `他：` 前缀 + 换行）。 */
        private const val HISTORY_SEPARATOR_COST: Int = 4

        /** 段标题（顺序即 v2 里的出现顺序；测试用 `sectionOrder()` 钉住）。 */
        val SECTION_TITLES: List<String> = listOf(
            PromptContract.Section.IDENTITY,
            PromptContract.Section.WORLD,
            PromptContract.Section.VOICE,
            PromptContract.Section.RELATIONSHIP,
            PromptContract.Section.CAPABILITIES,
            PromptContract.Section.BOUNDARIES,
            MemoryContract.Injection.TITLE,
            MemoryContract.Injection.LONG_TERM_TITLE,
            MemoryContract.Injection.SHORT_TERM_TITLE,
            PromptContract.Section.STATE,
            PromptContract.Section.MOOD,
            MemoryContract.Injection.TOPIC_TITLE,
            PromptContract.Section.MENTAL_ACTIVITY
        )
    }
}
