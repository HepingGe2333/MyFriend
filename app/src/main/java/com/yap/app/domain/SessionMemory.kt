package com.yap.app.domain

/**
 * 会话级备忘录的规则（`spec/experience/README.md` §6；0.15 上下文/记忆 v2）。
 *
 * 三段摘要都是**可变区域**，不写进人设：
 * - 短期：最近几轮定下的计划、待办、情绪线索（≤200 字），每 5 轮**或计划变化时**更新；
 * - 长期：跨越很多轮的稳定事实、约定、重要事件（≤500 字），每 15–40 轮由模型判断要不要更新；
 * - 主题总结：按主题分段的滚动摘要（每段 ≤120 字、最多 12 段，带时间段），
 *   每 15–40 轮或话题明显切换时滚动更新。
 *
 * 0.15 的变化（与 0.14 §6 对齐后仍保留的差异）：
 * 1. 长期不再是「每 30 条硬更新」，而是 15 轮起**问模型**、40 轮**强制**更新一次；
 * 2. 短期多一个「计划变化」触发（[planChanged]）：用户刚说「那就改成…」时，
 *    不该等到第 5 条消息才记住；
 * 3. 注入标题改用共享契约 [MemoryContract.Injection] 的 `# 你的备忘录` + `## 长期记忆` /
 *    `## 短期记忆` / `## 对话主题`，不再各写一份「你的备忘录」。
 *
 * 更新节流只认「可见用户消息数」的整数倍，所以拉黑期间的消息不会推快更新。
 */
object SessionMemoryPolicy {

    const val SHORT_EVERY = 5

    /** 0.14 的长期更新间隔；0.15 改为区间 [LONG_UPDATE_MIN_ROUNDS]..[LONG_UPDATE_MAX_ROUNDS]。 */
    const val LONG_EVERY = 30
    const val SHORT_MAX_CHARS = MemoryContract.Summary.SHORT_TERM_MAX_CHARS
    const val LONG_MAX_CHARS = MemoryContract.Summary.LONG_TERM_MAX_CHARS

    /** 长期更新轮次区间（0.15）：到 15 轮问模型，到 40 轮强制。 */
    const val LONG_UPDATE_MIN_ROUNDS = MemoryContract.Summary.LONG_UPDATE_MIN_ROUNDS
    const val LONG_UPDATE_MAX_ROUNDS = MemoryContract.Summary.LONG_UPDATE_MAX_ROUNDS

    /** 短期覆盖的轮数：一轮 = 一条用户 + 一条助手。 */
    const val SHORT_ROUNDS = 5

    /** 长期摘要最多往前看多少条消息（成本闸，不是产品语义）。 */
    const val LONG_WINDOW_MESSAGES = 60

    /** 长期更新决策：不更新 / 问模型 / 必须更新。 */
    enum class LongDecision {
        SKIP,
        ASK_MODEL,
        FORCE
    }

    /**
     * 长期与主题总结该不该更新。
     *
     * @param userCount 当前可见用户消息数
     * @param lastUpdatedCount 上次更新时的用户消息数
     * @param intervalMin 下限（默认 15）
     * @param intervalMax 上限（默认 40）
     */
    fun longDecision(
        userCount: Int,
        lastUpdatedCount: Int,
        intervalMin: Int = LONG_UPDATE_MIN_ROUNDS,
        intervalMax: Int = LONG_UPDATE_MAX_ROUNDS
    ): LongDecision {
        val total = userCount.coerceAtLeast(0)
        val last = lastUpdatedCount.coerceAtLeast(0)
        val fresh = (total - last).coerceAtLeast(0)
        val min = intervalMin.coerceAtLeast(1)
        val max = intervalMax.coerceAtLeast(min)
        return when {
            total <= 0 -> LongDecision.SKIP
            fresh >= max -> LongDecision.FORCE
            fresh >= min -> LongDecision.ASK_MODEL
            else -> LongDecision.SKIP
        }
    }

    /**
     * 短期摘要该不该更新：攒够 5 条用户消息，**或**模型/启发式认为计划变了。
     */
    fun shouldUpdateShort(userCount: Int, lastUpdatedCount: Int, planChanged: Boolean = false): Boolean {
        if (planChanged) return userCount.coerceAtLeast(0) > lastUpdatedCount.coerceAtLeast(0)
        return shouldUpdate(userCount, lastUpdatedCount, SHORT_EVERY)
    }

    /**
     * 这份摘要该不该更新：距上次更新又攒够了 [interval] 条用户消息。
     *
     * 用「条数差」而不是「条数取模」：失败时 upTo 不前进，下次回复会重试；
     * 如果按 count % 5 == 0 判，失败一次就要再等 5 条，甚至永远补不上。
     */
    fun shouldUpdate(currentUserCount: Int, upTo: Int, interval: Int): Boolean {
        if (currentUserCount <= 0) return false
        return currentUserCount - upTo.coerceAtLeast(0) >= interval.coerceAtLeast(1)
    }

    /**
     * 下一次该更新短期的门槛（用户消息数）；返回 null 表示还没到/已经更新过。
     *
     * 用「向下取整的整数倍」而不是 `count == last + 5`：中途 App 被杀、消息补录、
     * 或者从旧版本升级上来时，仍然会在下一次跨过 5 的倍数时更新一次。
     */
    fun nextShortCount(userCount: Int, lastUpdatedCount: Int): Int? =
        userCount.coerceAtLeast(0).takeIf { shouldUpdate(it, lastUpdatedCount, SHORT_EVERY) }

    /** 下一次该更新长期的门槛；语义同上（0.15 保留给旧调用方）。 */
    fun nextLongCount(userCount: Int, lastUpdatedCount: Int): Int? =
        userCount.coerceAtLeast(0).takeIf {
            longDecision(it, lastUpdatedCount) != LongDecision.SKIP
        }

    /**
     * 「计划变了」的轻量判断（0.15）：短摘要不等 5 轮也要更新。
     *
     * 只认很明确的「约定/改主意」措辞——它触发一次**额外的模型调用**，误判的代价是钱和延迟，
     * 所以宁可漏判（最多等 5 轮）也不要一看到「改」就重算。
     *
     * @param recentTexts 最近这一轮的用户/助手原话（新的在最后），通常只传 2–4 条
     */
    fun planChanged(recentTexts: List<String>): Boolean {
        if (recentTexts.isEmpty()) return false
        val text = recentTexts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return false
        return PLAN_CHANGE_MARKERS.any { text.contains(it) }
    }

    /** 计划变化的措辞（顺序即优先级，全部是「已经在商量具体安排」的信号）。 */
    private val PLAN_CHANGE_MARKERS = listOf(
        "那就", "改成", "改到", "约好", "说好", "定在", "计划", "安排", "决定",
        "明天", "后天", "下周", "周末", "几点", "别忘了", "记得"
    )

    /** 摘要长度上限：按字符截断（中文一个字算一个），并去掉首尾空白。 */
    fun truncate(raw: String, maxChars: Int): String {
        val text = raw.trim()
        if (maxChars <= 0) return ""
        return if (text.length <= maxChars) text else text.take(maxChars).trimEnd()
    }

    /**
     * 模型输出可能包着「摘要：」前缀或 Markdown 标题；这里只做最小清理。
     * 清理后为空/过短时返回 null，调用方保留旧摘要。
     */
    fun sanitizeSummary(raw: String, maxChars: Int): String? {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```").removeSuffix("```").trim()
        }
        text = text.removePrefix("摘要：").removePrefix("摘要:").trim()
        // 摘要是一段话，不是多行列表：压平换行，同时把连续空白收成一个空格。
        text = text.replace(Regex("\\s+"), " ").trim()
        if (text.length < 2) return null
        return truncate(text, maxChars).takeIf { it.isNotBlank() }
    }

    /**
     * 拼进系统提示词的「你的备忘录」（0.15 标题；顺序：长期 → 短期）。
     *
     * 两段都为空时返回空串（不产生一个空标题）。聊天装配走 [ContextAssembly] 的分段注入，
     * 这个函数留给主动思考/预览这类「一次要整块」的调用方；标题两处共用同一份常量。
     */
    fun promptBlock(shortSummary: String?, longSummary: String?): String {
        val short = truncate(shortSummary.orEmpty(), SHORT_MAX_CHARS)
        val long = truncate(longSummary.orEmpty(), LONG_MAX_CHARS)
        if (short.isBlank() && long.isBlank()) return ""
        return buildString {
            appendLine(MemoryContract.Injection.TITLE)
            if (long.isNotBlank()) {
                appendLine(MemoryContract.Injection.LONG_TERM_TITLE)
                appendLine("- $long")
            }
            if (short.isNotBlank()) {
                appendLine(MemoryContract.Injection.SHORT_TERM_TITLE)
                appendLine("- $short")
            }
        }.trimEnd()
    }
}
