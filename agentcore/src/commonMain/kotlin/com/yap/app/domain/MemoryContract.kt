package com.yap.app.domain

/**
 * 会话记忆与「主题总结」的**共享纯规则**（0.15 上下文/记忆 v2，见 `docs/design/context-memory-v2.md`）。
 *
 * 为什么要有这个文件：Android 的 `MemoryRepository` / `SessionMemoryRepository` 与 iOS 的
 * `MemoryRepository` / `ChatService` 各自实现了「检索线索怎么取、注入几段、每段多长」，
 * 这些数字（500 / 200 / 120 / 12）和标题（`# 你的备忘录`、`## 长期记忆`…）都是**产品口径**，
 * 两端各写一份就一定会漂。这里只放纯函数与常量：不读库、不读时钟、不碰网络。
 *
 * 三块内容：
 * 1. **检索线索**（[Retrieval]）：这一轮拿什么去检索记忆（最近一句用户消息，≤300 字）；
 * 2. **注入形状**（[Injection]）：`# 你的备忘录` 下长期 / 短期 / 主题总结的标题与上限；
 * 3. **长度门限**（[Summary]）：长期 ≤500 字、短期 ≤200 字、主题每段 ≤120 字、最多 12 段。
 *
 * 与既有实现的关系：本文件**不替换** `HistoryCompression`（滚动摘要）与
 * `app` 里的 `SessionMemoryPolicy`；先新增共享口径，迁移由后续 agent 做。0.15 起注入标题从
 * 旧的 `## 你的备忘录` 改为 `# 你的备忘录` + `## 长期记忆` / `## 短期记忆` / `## 对话主题`
 * （见设计文档的「注入示例」），旧标题保留在 [Injection.LEGACY_TITLE] 供对照与过渡期使用。
 */
object MemoryContract {

    /** 长度门限：全部来自 `docs/design/context-memory-v2.md` 的「固定结构」与「更新节奏」。 */
    object Summary {

        /** 长期记忆缓存最多 500 字（稳定事实、约定、重要事件）。 */
        const val LONG_TERM_MAX_CHARS = 500

        /** 短期记忆缓存最多 200 字（最近几轮定下的计划、待办、情绪线索）。 */
        const val SHORT_TERM_MAX_CHARS = 200

        /** 主题总结里每一段最多 120 字。 */
        const val TOPIC_SEGMENT_MAX_CHARS = 120

        /** 主题总结最多 12 段（再多就按时间从旧到新丢）。 */
        const val TOPIC_MAX_SEGMENTS = 12

        /** 主题总结整段的字符上限：12 × 120。预算规划用它做硬顶。 */
        const val TOPIC_MAX_CHARS = TOPIC_SEGMENT_MAX_CHARS * TOPIC_MAX_SEGMENTS

        /** 短期缓存每 5 轮（或模型认为计划变化时）更新一次。 */
        const val SHORT_UPDATE_EVERY_ROUNDS = 5

        /** 长期缓存 / 主题总结的更新轮次区间：每 15–40 轮由模型决定是否更新。 */
        const val LONG_UPDATE_MIN_ROUNDS = 15
        const val LONG_UPDATE_MAX_ROUNDS = 40
    }

    /**
     * 一条要注入的长期记忆。
     *
     * [drawer] 复用既有的 [MemoryDrawer]（SELF / USER / EVENT）：分组标签按抽屉讲给模型听，
     * 比直接说数据库的 category 更像「他记得的事」。[importance] 越大越靠前；同组内按它降序。
     */
    data class MemoryItem(
        val content: String,
        val drawer: MemoryDrawer = MemoryDrawer.USER,
        val pinned: Boolean = false,
        val importance: Int = 3
    )

    /**
     * 一段「对话主题」总结。
     *
     * @param timeLabel 时间段，例如「2026-11-18 晚」；为空时只写内容
     * @param summary 这一段的 120 字摘要
     */
    data class TopicSegment(
        val timeLabel: String = "",
        val summary: String = ""
    )

    /** 检索线索：这一轮拿什么去 `recall` / 主题检索里找旧事。 */
    object Retrieval {

        /** 检索线索最多 300 字：再长也只是一句用户消息，关键词用不到那么多。 */
        const val MAX_QUERY_CHARS = 300

        /**
         * 取检索线索：最近一句用户消息 → 压成一行 → 截到 [MAX_QUERY_CHARS]。
         *
         * 为什么优先用原话而不是摘要：检索靠的是关键词（人名、地名、约定），
         * 摘要会把它们概括掉；原话里的词才和倒排索引对得上。
         *
         * @param lastUserMessage 最近一条用户消息；空/纯空白时退回 [fallback]
         * @param fallback 没有用户消息时的兜底线索（例如当前主题标题），可以为空
         */
        fun query(lastUserMessage: String?, fallback: String? = null): String {
            val direct = Injection.flatten(lastUserMessage)
            if (direct.isNotEmpty()) return direct.take(MAX_QUERY_CHARS)
            return Injection.flatten(fallback).take(MAX_QUERY_CHARS)
        }
    }

    /** 注入形状：标题、分组标签、最多条数、每条字符上限。 */
    object Injection {

        /** 0.15 的备忘录标题（设计文档「注入示例」）。 */
        const val TITLE = "# 你的备忘录"

        /** 0.14 及以前的标题；保留给过渡期与逐字对照测试。 */
        const val LEGACY_TITLE = "## 你的备忘录"

        /** 0.14 及以前的「长期记忆」标题（`MemoryRepository.formatForPrompt`）。 */
        const val LEGACY_MEMORY_TITLE = "## 你记得的事"

        const val LONG_TERM_TITLE = "## 长期记忆"
        const val SHORT_TERM_TITLE = "## 短期记忆"
        const val TOPIC_TITLE = "## 对话主题"

        /**
         * 一次性注入的长期记忆最多 14 条。
         *
         * 与既有 `4 + 记性(1..5) × 2` 的上界一致：记性最满时 14 条；再多就会把
         * 「最近聊了什么」挤出去——长期记忆是背景，不是主角。
         */
        const val MAX_ITEMS = 14

        /** 单条记忆进提示词只留 300 字（库里的原文不截）。 */
        const val MAX_ITEM_CHARS = 300

        /** 钉住的记忆附一句「对你很重要」，不额外占一行。 */
        const val PINNED_SUFFIX = "（对你很重要）"

        /** 三个抽屉的展示标签（顺序即注入顺序：关于对方 → 他自己 → 共同经历）。 */
        const val DRAWER_USER_LABEL = "关于对方"
        const val DRAWER_SELF_LABEL = "你自己是什么样的人"
        const val DRAWER_EVENT_LABEL = "你们之间的经历与约定"

        /** 抽屉注入顺序（与既有 Android / iOS 一致）。 */
        val DRAWER_ORDER: List<MemoryDrawer> = listOf(
            MemoryDrawer.USER,
            MemoryDrawer.SELF,
            MemoryDrawer.EVENT
        )

        fun drawerLabel(drawer: MemoryDrawer): String = when (drawer) {
            MemoryDrawer.USER -> DRAWER_USER_LABEL
            MemoryDrawer.SELF -> DRAWER_SELF_LABEL
            MemoryDrawer.EVENT -> DRAWER_EVENT_LABEL
        }

        /**
         * 一条记忆的渲染：`- 正文`（超长截到 [MAX_ITEM_CHARS] 并补「…」），
         * 钉住的加 [PINNED_SUFFIX]。空正文返回空串，调用方直接丢掉。
         */
        fun itemLine(item: MemoryItem): String {
            val content = flatten(item.content)
            if (content.isEmpty()) return ""
            val clipped = if (content.length <= MAX_ITEM_CHARS) {
                content
            } else {
                content.take(MAX_ITEM_CHARS) + "…"
            }
            return if (item.pinned) "- $clipped$PINNED_SUFFIX" else "- $clipped"
        }

        /**
         * 长期记忆注入块（`# 你的备忘录` + `## 长期记忆`）。
         *
         * 规则（顺序有意义）：
         * 1. 空正文丢掉；按抽屉顺序分组，组内按 importance 降序（同分保持原顺序）；
         * 2. 全部条数最多 [maxItems]（默认 [MAX_ITEMS]，传更大也夹到 [MAX_ITEMS]）；
         * 3. 没有一条可用时返回空串（不产生空标题）。
         */
        fun memoryInjection(items: List<MemoryItem>, maxItems: Int = MAX_ITEMS): String {
            val limit = maxItems.coerceIn(0, MAX_ITEMS)
            if (limit == 0) return ""
            val usable = items
                .filter { flatten(it.content).isNotEmpty() }
                .take(limit)
            if (usable.isEmpty()) return ""
            return buildString {
                appendLine(TITLE)
                appendLine(LONG_TERM_TITLE)
                for (drawer in DRAWER_ORDER) {
                    val group = usable
                        .filter { it.drawer == drawer }
                        .sortedByDescending { it.importance }
                    if (group.isEmpty()) continue
                    appendLine("**${drawerLabel(drawer)}**")
                    group.forEach { item ->
                        val line = itemLine(item)
                        if (line.isNotEmpty()) appendLine(line)
                    }
                }
            }.trimEnd()
        }

        /**
         * 会话备忘录（短期 / 长期 / 主题总结），标题按 0.15 设计文档「注入示例」。
         *
         * 顺序固定：长期 → 短期 → 主题；空段整段省略；三段全空返回空串。
         * 每段只保留上限内的字符，**不重复截断正文**（调用方已经把摘要压到门限内），
         * 这里再兜一次是为了防止旧数据/模型输出越界。
         *
         * @param longTerm 长期记忆摘要（≤500 字）
         * @param shortTerm 短期记忆摘要（≤200 字）
         * @param topics 主题总结（最多 12 段、每段 ≤120 字；超出的从尾部丢）
         */
        fun notes(
            longTerm: String? = null,
            shortTerm: String? = null,
            topics: List<TopicSegment> = emptyList()
        ): String {
            val long = clipParagraph(longTerm, Summary.LONG_TERM_MAX_CHARS)
            val short = clipParagraph(shortTerm, Summary.SHORT_TERM_MAX_CHARS)
            val topicLines = topics
                .mapNotNull { topicLine(it) }
                .take(Summary.TOPIC_MAX_SEGMENTS)
            if (long.isEmpty() && short.isEmpty() && topicLines.isEmpty()) return ""
            return buildString {
                appendLine(TITLE)
                if (long.isNotEmpty()) {
                    appendLine(LONG_TERM_TITLE)
                    appendLine("- $long")
                }
                if (short.isNotEmpty()) {
                    appendLine(SHORT_TERM_TITLE)
                    appendLine("- $short")
                }
                if (topicLines.isNotEmpty()) {
                    appendLine(TOPIC_TITLE)
                    topicLines.forEach { appendLine(it) }
                }
            }.trimEnd()
        }

        /**
         * 一行主题总结：`- 2026-11-18 晚：加班与方案改版`。
         *
         * 摘要为空返回 null（不写空行）；时间段为空时只写摘要；
         * 摘要超过 [Summary.TOPIC_SEGMENT_MAX_CHARS] 截断并补「…」。
         */
        fun topicLine(topic: TopicSegment): String? {
            val summary = flatten(topic.summary)
            if (summary.isEmpty()) return null
            val clipped = if (summary.length <= Summary.TOPIC_SEGMENT_MAX_CHARS) {
                summary
            } else {
                summary.take(Summary.TOPIC_SEGMENT_MAX_CHARS) + "…"
            }
            val label = flatten(topic.timeLabel)
            return if (label.isEmpty()) "- $clipped" else "- $label：$clipped"
        }

        /** 压成一行（提示词里的摘要不需要换行；换行会让人误以为是清单）。 */
        internal fun flatten(raw: String?): String =
            raw.orEmpty().replace(Regex("\\s+"), " ").trim()

        /** 截断到 [max] 字并补「…」；空/纯空白返回空串。 */
        internal fun clipParagraph(raw: String?, max: Int): String {
            val text = flatten(raw)
            if (text.isEmpty() || max <= 0) return ""
            return if (text.length <= max) text else text.take(max).trimEnd() + "…"
        }
    }

    /**
     * 「主题总结」在预算里占的字符上限（[Summary.TOPIC_MAX_CHARS]）：给
     * `ExperienceContract.plan()` 当硬顶，避免主题总结越长越挤历史正文。
     */
    const val TOPIC_SUMMARY_CHAR_BUDGET: Int = Summary.TOPIC_MAX_CHARS
}
