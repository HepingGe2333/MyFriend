package com.yap.app.domain

import kotlin.math.exp

/**
 * 「回忆」：他想了多深，就允许翻多旧、翻多杂。
 *
 * 三档不是三个参数，而是三种态度：
 * - [QUICK] 随口想想：只看还在用的记忆，最近 + 重要的优先，翻到一点就够；
 * - [NORMAL] 认真回忆：把沉下去（DORMANT）的记忆也捞回来，再翻最近聊过的内容；
 * - [DEEP] 翻箱倒柜：连归档（ARCHIVED）的都翻，跨全部会话搜聊天记录，还带上他自己上网看到的东西。
 *
 * 本文件是纯领域逻辑：不碰 Room、不碰 Android、不读时钟（`now` 由调用方传入），
 * 所有入口对空数据与坏数据都安全，不会抛异常。
 */

/** 回忆强度。level 是给模型和参数解析用的数字档位。 */
enum class RecallDepth(
    val level: Int,
    val label: String,
    /** 给模型看的内容字数上限：档位越高，翻出来的越啰嗦。 */
    val maxChars: Int,
    /** 最多给多少条命中，避免一次回忆把上下文塞满。 */
    val maxHits: Int,
    /** 时间衰减的半衰期（天）：越深的档位越不嫌旧。 */
    val halfLifeDays: Double,
    /** 是否带上沉下去（DORMANT）的记忆。 */
    val includeDormant: Boolean,
    /** 是否翻已归档（ARCHIVED）的记忆。 */
    val includeArchived: Boolean,
    /** 是否按关键词搜聊天记录。 */
    val includeChatSearch: Boolean,
    /** 是否再按会话扫一遍最近的消息做补充。 */
    val scanRecentChats: Boolean,
    /** 是否带上他独自上网看到的摘要。 */
    val includeBrowsing: Boolean
) {
    QUICK(1, "随口想想", 1200, 6, 21.0, false, false, false, false, false),
    NORMAL(2, "认真回忆", 3000, 12, 60.0, true, false, true, false, true),
    DEEP(3, "翻箱倒柜", 6000, 24, 180.0, true, true, true, true, true);

    /** 这一档能不能看到某种状态的记忆。 */
    fun allows(state: RecallMemoryState): Boolean = when (state) {
        RecallMemoryState.ACTIVE -> true
        RecallMemoryState.DORMANT -> includeDormant
        RecallMemoryState.ARCHIVED -> includeArchived
    }

    companion object {
        /** 数字档位：0 及以下按最浅算，4 及以上按最深算（模型偶尔会给出越界值）。 */
        fun fromLevel(level: Int): RecallDepth = when {
            level <= 1 -> QUICK
            level == 2 -> NORMAL
            else -> DEEP
        }

        /**
         * 宽松解析模型给的 depth：数字（1/2/3，越界自动收拢）、英文（quick/normal/deep…）、
         * 中文别名（随口想想 / 认真回忆 / 翻箱倒柜…）都认；认不出来时退回 [NORMAL]，不抛异常。
         */
        fun parse(raw: String?): RecallDepth {
            val text = raw?.trim()?.lowercase().orEmpty()
            if (text.isEmpty()) return NORMAL
            // 先看数字：容忍 "3"、"第3档"、"level 2" 这类写法
            val numeric = text.filter { it.isDigit() }.toIntOrNull()
            if (numeric != null) return fromLevel(numeric)
            return when {
                text in QUICK_ALIASES -> QUICK
                text in DEEP_ALIASES -> DEEP
                text in NORMAL_ALIASES -> NORMAL
                else -> NORMAL
            }
        }

        private val QUICK_ALIASES = setOf(
            "quick", "q", "fast", "light", "shallow", "simple",
            "随口", "随口想想", "随便想想", "随便", "简单想想", "浅", "浅一点", "浅尝", "快", "快速", "一", "第一档"
        )

        private val NORMAL_ALIASES = setOf(
            "normal", "n", "default", "medium", "mid", "standard",
            "认真", "认真回忆", "回忆", "正常", "普通", "一般", "中", "想一想", "二", "第二档"
        )

        private val DEEP_ALIASES = setOf(
            "deep", "d", "thorough", "full", "max", "deeper",
            "翻箱倒柜", "深入", "深", "最深", "仔细", "仔细想想", "彻底", "挖", "挖一挖", "全部", "都想", "三", "第三档"
        )
    }
}

/** 命中来自哪里：他自己的记忆 / 聊天记录 / 他独自上网看到的。 */
enum class RecallKind(val label: String) {
    MEMORY("记忆"),
    CHAT("聊天"),
    BROWSING("他自己看到的")
}

/** 记忆的生命周期状态（纯领域名字，数据库里存的是同名字符串）。 */
enum class RecallMemoryState { ACTIVE, DORMANT, ARCHIVED }

/**
 * 一条被翻出来的东西。
 *
 * @param kind 来源（记忆 / 聊天 / 他看到的）
 * @param text 给模型看的正文，已经裁剪过
 * @param at 这件事发生（或上次被碰到）的时间，用来算新鲜度与显示日期
 * @param weight 0..1 的「本身有多重要」，与关键词命中、时间衰减一起决定排序
 * @param note 额外说明，例如归档记忆的「这是很久以前的事，他不太确定了」
 * @param source 来源补充，例如聊天所在的会话标题、记忆所在的抽屉
 * @param refId 记忆 id；聊天/浏览为空。仓库据此更新记忆的被使用次数。
 */
data class RecallHit(
    val kind: RecallKind,
    val text: String,
    val at: Long,
    val weight: Double = 0.5,
    val note: String = "",
    val source: String = "",
    val refId: String = "",
    /** 记忆的标签（人 / 事 / 物 / 情绪）；聊天与浏览为空。 */
    val tags: List<String> = emptyList(),
    /** 这件事发生的时间；只有记忆才有，也只有在推断出来时才非空。 */
    val happenedAt: Long? = null,
    /** 是不是倒排索引直接命中的：命中说明「词元真的对上了」，比子串偶然更可信。 */
    val fromIndex: Boolean = false
)

/** 一行记忆的回忆视图：在 [MemoryRow] 之上补上状态与剩余分量。 */
data class RecallMemoryInput(
    val id: String,
    val content: String,
    val drawer: MemoryDrawer = MemoryDrawer.EVENT,
    val importance: Int = 3,
    val pinned: Boolean = false,
    val lastUsedAt: Long = 0L,
    val createdAt: Long = 0L,
    val state: RecallMemoryState = RecallMemoryState.ACTIVE,
    val strength: Double = 1.0,
    val archivedAt: Long? = null,
    /** 结构化标签：给「他妹妹那件事」这类指代多一条线索。 */
    val tags: List<String> = emptyList(),
    /** 这件事发生的时间（不是被记住的时间）；没有就是 null。 */
    val happenedAt: Long? = null
) {
    /** 时间轴上的位置：优先用「上次被用到」，没有就用创建时间。 */
    val at: Long get() = if (lastUsedAt > 0L) lastUsedAt else createdAt

    companion object {
        fun of(
            row: MemoryRow,
            state: RecallMemoryState = RecallMemoryState.ACTIVE,
            strength: Double = 1.0,
            archivedAt: Long? = null
        ): RecallMemoryInput = RecallMemoryInput(
            id = row.id,
            content = row.content,
            drawer = row.drawer,
            importance = row.importance,
            pinned = row.pinned,
            lastUsedAt = row.lastHitAt,
            createdAt = row.createdAt,
            state = state,
            strength = strength,
            archivedAt = archivedAt,
            tags = row.tags,
            happenedAt = row.happenedAt
        )
    }
}

/** 一次回忆的结果。 [isEmpty] 为 true 时 [rendered] 是一句人话（不是错误）。 */
data class RecallResult(
    val hits: List<RecallHit> = emptyList(),
    val rendered: String = "",
    val isEmpty: Boolean = true
)

object Recall {

    // ---------------------------------------------------------------- 权重

    /** 关键词命中的权重：回忆首先是「找对东西」。 */
    private const val KEYWORD_WEIGHT = 2.0

    /** 标签命中：「他妹妹」这类指代，除了字面，还有他当初打的标签。 */
    private const val TAG_WEIGHT = 0.8

    /** 一条记忆最多算几个标签命中。 */
    private const val TAG_MAX_HITS = 2

    /** 时间指代邻近（「去年冬天」）：事件发生时间落在区间里的稍微靠前。 */
    private const val TIME_WEIGHT = 0.9

    /** 时间区间外的软衰减半衰期（天）。 */
    private const val TIME_HINT_HALF_LIFE_DAYS = 120.0

    /** 倒排索引直接命中的小加成：词元对上比子串偶然更可信，但不该盖过真正的相关度。 */
    private const val INDEX_BONUS = 0.25

    /** 新鲜度的权重：不是越新越好，但太旧的东西该往后排。 */
    private const val FRESHNESS_WEIGHT = 0.6

    /** 本身重要度的权重。 */
    private const val QUALITY_WEIGHT = 1.0

    /** 单条命中的正文上限，防止一条长记忆占满整段回忆。 */
    private const val MAX_HIT_TEXT_CHARS = 300

    private const val DAY_MILLIS = 86_400_000L

    /** 归档命中时必须说的那句「他不太确定」。 */
    const val ARCHIVED_NOTE = "这是很久以前的事，他不太确定了"

    // ---------------------------------------------------------------- 记忆 → 命中

    /**
     * 把记忆变成命中：按档位筛状态（QUICK 只看 ACTIVE，NORMAL 加上 DORMANT，DEEP 连 ARCHIVED 都翻），
     * 再按「关键词 + 时间 + 重要度」排序取前 N 条。
     */
    fun memoryHits(
        memories: List<RecallMemoryInput>,
        query: String,
        depth: RecallDepth,
        now: Long,
        indexHits: Set<String> = emptySet()
    ): List<RecallHit> {
        if (memories.isEmpty()) return emptyList()
        val hits = memories
            .asSequence()
            .filter { it.content.isNotBlank() }
            .filter { depth.allows(it.state) }
            .map { it.toHit(fromIndex = it.id in indexHits) }
            .toList()
        return rank(hits, query, depth, now).take(depth.maxHits)
    }

    private fun RecallMemoryInput.toHit(fromIndex: Boolean): RecallHit {
        val safeStrength = strength.coerceIn(0.0, 1.0)
        // 沉下去的记忆还剩下多少分量；归档的只剩一点点，语气上要显得不确定。
        val stateFactor = when (state) {
            RecallMemoryState.ACTIVE -> 0.75 + 0.25 * safeStrength
            RecallMemoryState.DORMANT -> 0.45 + 0.25 * safeStrength
            RecallMemoryState.ARCHIVED -> 0.20 * safeStrength
        }
        val quality = (importance.coerceIn(1, 5) / 5.0) * 0.7 + (if (pinned) 0.3 else 0.0)
        return RecallHit(
            kind = RecallKind.MEMORY,
            text = content.trim().take(MAX_HIT_TEXT_CHARS),
            at = at,
            weight = (quality * stateFactor).coerceIn(0.0, 1.0),
            note = if (state == RecallMemoryState.ARCHIVED) ARCHIVED_NOTE else "",
            source = drawerLabel(drawer),
            refId = id,
            tags = tags,
            happenedAt = happenedAt,
            fromIndex = fromIndex
        )
    }

    /** 抽屉是「他记得的是关于谁的」，比数据库里的 category 更接近人话。 */
    private fun drawerLabel(drawer: MemoryDrawer): String = when (drawer) {
        MemoryDrawer.SELF -> "关于他自己"
        MemoryDrawer.USER -> "关于对方"
        MemoryDrawer.EVENT -> "你们之间的经历"
    }

    // ---------------------------------------------------------------- 聊天 / 浏览 → 命中

    /** 一条聊天记录。 [role] 传 "USER" / "ASSISTANT" 时会补上「谁说的」。 */
    fun chatHit(
        text: String,
        sessionTitle: String,
        at: Long,
        role: String? = null
    ): RecallHit {
        val body = text.trim().take(MAX_HIT_TEXT_CHARS)
        val prefix = when (role?.trim()?.uppercase()) {
            "USER" -> "对方说："
            "ASSISTANT" -> "我说过："
            else -> ""
        }
        val title = sessionTitle.trim().take(30)
        return RecallHit(
            kind = RecallKind.CHAT,
            text = prefix + body,
            at = at,
            weight = 0.45,
            source = if (title.isBlank()) "" else "《$title》"
        )
    }

    /** 他独自上网看到的摘要；缓存里没有时间戳，只能按调用方给的「刚刚」算。 */
    fun browsingHit(digest: String, at: Long): RecallHit = RecallHit(
        kind = RecallKind.BROWSING,
        text = digest.trim().take(MAX_HIT_TEXT_CHARS * 3),
        at = at,
        weight = 0.40
    )

    // ---------------------------------------------------------------- 排序

    /**
     * 综合排序：关键词命中 + 时间衰减 + 重要度/深度加权。
     *
     * 时间衰减的半衰期跟着档位走（QUICK 21 天 / NORMAL 60 天 / DEEP 180 天），
     * 所以「翻箱倒柜」时很久以前的事才排得上来。
     */
    fun rank(
        hits: List<RecallHit>,
        query: String,
        depth: RecallDepth,
        now: Long
    ): List<RecallHit> {
        if (hits.isEmpty()) return emptyList()
        // 时间指代只在这一次查询里解析一次：整批命中共用同一个区间。
        val span = ChineseTerms.timeSpanOf(query, now)
        return hits.sortedWith(
            compareByDescending<RecallHit> { score(it, query, depth, now, span) }
                .thenByDescending { it.at }
                .thenBy { it.text }
        )
    }

    private fun score(
        hit: RecallHit,
        query: String,
        depth: RecallDepth,
        now: Long,
        span: TimeSpan?
    ): Double {
        val keyword = keywordScore(hit.text + " " + hit.source, query)
        val fresh = freshness(hit.at, now, depth.halfLifeDays)
        val tag = tagScore(hit.tags, query)
        val time = timeScore(hit.happenedAt, span)
        var total = keyword * KEYWORD_WEIGHT + fresh * FRESHNESS_WEIGHT +
            hit.weight.coerceIn(0.0, 1.0) * QUALITY_WEIGHT +
            tag * TAG_WEIGHT + time * TIME_WEIGHT
        if (hit.fromIndex) total += INDEX_BONUS
        return total
    }

    /** 标签命中 0..1（最多算 [TAG_MAX_HITS] 个）。 */
    private fun tagScore(tags: List<String>, query: String): Double {
        val hits = ChineseTerms.tagHits(query, tags, TAG_MAX_HITS)
        if (hits <= 0) return 0.0
        return hits.toDouble() / TAG_MAX_HITS
    }

    /** 时间指代邻近 0..1：区间里满分，区间外按半衰期给残值；没有发生时间就不加权。 */
    private fun timeScore(happenedAt: Long?, span: TimeSpan?): Double {
        if (happenedAt == null || happenedAt <= 0L || span == null) return 0.0
        if (span.contains(happenedAt)) return 1.0
        return (exp(-span.distanceDays(happenedAt) / TIME_HINT_HALF_LIFE_DAYS) * 0.5)
            .coerceIn(0.0, 1.0)
    }

    /**
     * 关键词命中率 0..1：整段包含查询算满分；否则按中文二元组 / 英文单词的加权覆盖率与余弦算。
     * 空查询一律 0（此时完全靠新鲜度与重要度排）。
     *
     * 这里和「进上下文」的召回共用同一个 [ChineseTerms]：同一个查询词在两处拆出同一批词元，
     * 否则会出现「上下文里挑中的记忆，回忆工具却翻不到」这种自相矛盾。
     */
    fun keywordScore(text: String, query: String): Double = ChineseTerms.relevance(query, text)

    private fun freshness(at: Long, now: Long, halfLifeDays: Double): Double {
        if (at <= 0L) return 0.0
        val days = (now - at).coerceAtLeast(0L) / DAY_MILLIS.toDouble()
        return exp(-days / halfLifeDays.coerceAtLeast(1.0))
    }

    // ---------------------------------------------------------------- 渲染

    /**
     * 给模型看的中文段落：每条命中都带来源与日期，如「（记忆 · 3 周前）」「（聊天 · 昨天）」，
     * 再按档位裁剪字数。没有命中时返回 [emptyAnswer]，而不是空白。
     */
    fun render(
        hits: List<RecallHit>,
        depth: RecallDepth,
        query: String,
        now: Long = System.currentTimeMillis()
    ): String {
        if (hits.isEmpty()) return emptyAnswer(query, depth)
        val header = if (query.isBlank()) {
            "## 你翻了翻记忆（${depth.label}）"
        } else {
            "## 关于「${query.trim().take(40)}」，你翻了翻记忆（${depth.label}）"
        }
        val tail = when (depth) {
            RecallDepth.QUICK -> "\n只是随口想想：想不起来就直说记不清，别硬圆。"
            RecallDepth.NORMAL -> "\n这些是翻出来的旧印象：自然带进话里就好，不要逐条复述。"
            RecallDepth.DEEP -> "\n翻不到的，就是真的找不回来了：别把旧印象说成确定的事，也别编。"
        }
        val budget = (depth.maxChars - header.length - tail.length - 2).coerceAtLeast(160)
        val body = StringBuilder()
        for (hit in hits) {
            val line = "- （${sourceLabel(hit, now)}）${hit.text.take(MAX_HIT_TEXT_CHARS)}"
            if (body.length + line.length + 1 > budget) break
            body.append(line).append('\n')
        }
        if (body.isEmpty()) {
            // 预算再紧也要给一条：截断正文，而不是整段留空。
            val first = hits.first()
            val label = sourceLabel(first, now)
            val room = (budget - label.length - 4).coerceAtLeast(24)
            body.append("- （").append(label).append("）")
                .append(first.text.take(room)).append('\n')
        }
        return (header + "\n" + body + tail).take(depth.maxChars)
    }

    /** 「记忆 · 3 周前 · 关于对方」，必要时再补一句不确定。 */
    fun sourceLabel(hit: RecallHit, now: Long = System.currentTimeMillis()): String {
        val parts = mutableListOf(hit.kind.label, dateLabel(hit.at, now))
        hit.source.trim().takeIf { it.isNotBlank() }?.let { parts += it.take(30) }
        hit.note.trim().takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /**
     * 中文相对日期：刚刚 / N 分钟前 / 今天 / 昨天 / 前天 / N 天前 / N 周前 / N 个月前 / N 年前。
     * 只做算术，不读日历（跨零点时可能把「昨天凌晨」说成今天，聊天里无所谓）。
     */
    fun dateLabel(at: Long, now: Long = System.currentTimeMillis()): String {
        if (at <= 0L) return "很久以前"
        val delta = (now - at).coerceAtLeast(0L)
        val minutes = delta / 60_000L
        if (minutes < 1L) return "刚刚"
        if (minutes < 60L) return "$minutes 分钟前"
        val days = delta / DAY_MILLIS
        return when {
            days <= 0L -> "今天"
            days == 1L -> "昨天"
            days == 2L -> "前天"
            days < 7L -> "$days 天前"
            days < 30L -> "${days / 7} 周前"
            days < 365L -> "${days / 30} 个月前"
            else -> "${days / 365} 年前"
        }
    }

    /**
     * 什么都没翻到时说的人话。
     * QUICK/NORMAL 只是「想不太起来」；DEEP 是翻遍了也找不到——那意味着可能真的忘了。
     */
    fun emptyAnswer(query: String, depth: RecallDepth): String {
        val topic = if (query.isBlank()) "这件事" else "「${query.trim().take(40)}」"
        return when (depth) {
            RecallDepth.QUICK ->
                "关于$topic，我想不太起来了。你提一句关键词，我再慢慢想。"
            RecallDepth.NORMAL ->
                "关于$topic，我想不太起来了，脑子里没有这段。你要是愿意，可以多给我一点线索。"
            RecallDepth.DEEP ->
                "关于$topic，我翻箱倒柜找了一遍，确实想不起来了——可能早就忘了。" +
                    "有些久远又不重要的事，是真的找不回来；你要是还记得，跟我说说吧。"
        }
    }

    // ---------------------------------------------------------------- 内部：文本归一化
}
