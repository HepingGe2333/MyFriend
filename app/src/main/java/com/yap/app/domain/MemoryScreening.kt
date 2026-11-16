package com.yap.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

// `MemoryDrawer` 已经搬到同包的 `MemoryDrawer.kt`：它是纯枚举，不该跟着本文件一起
// 依赖 kotlinx-serialization（`agentd/` 要零依赖复用纯领域代码）。这里不再重复声明。

/** 一行已有记忆的最小视图（不要依赖 Room 实体，便于单测） */
data class MemoryRow(
    val id: String,
    val content: String,
    val drawer: MemoryDrawer,
    val importance: Int,        // 1..5
    val pinned: Boolean = false,
    val hits: Int = 1,          // 被提到过几次
    val lastHitAt: Long = 0L,
    val createdAt: Long = 0L,
    /** 结构化标签（人 / 事 / 物 / 情绪）：查「他妹妹」时能对上「妹妹」这个标签。 */
    val tags: List<String> = emptyList(),
    /** 这件事**发生**的时间（不是被记住的时间）；推断不出来就是 null，此时不加权。 */
    val happenedAt: Long? = null
)

/**
 * `tags` 字段的 JSON 编解码：数据库里存的是 JSON 数组，如 `["妹妹","失眠"]`。
 *
 * 坏数据（不是数组、空串、null、超长）一律当「没有标签」——
 * 标签只是给他自己查记忆用的辅助，不能因为一条脏 JSON 让整次召回失败。
 */
object MemoryTags {
    /** 一条记忆最多几个标签：多了就不叫标签，叫关键词堆。 */
    const val MAX_TAGS = 5

    /** 单个标签最多几个字：标签是「妹妹」「失眠」，不是一句话。 */
    const val MAX_TAG_CHARS = 12

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun decode(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return emptyList()
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return sanitize(
            array.mapNotNull { element ->
                runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
            }
        )
    }

    /** 落库用：没有标签时写 null（不写空数组，省得每次读还要判空一次）。 */
    fun encode(tags: List<String>): String? {
        val clean = sanitize(tags)
        if (clean.isEmpty()) return null
        return JsonArray(clean.map { JsonPrimitive(it) }).toString()
    }

    /** 清洗：去空白、去重、截长度与条数；不认识的字符原样保留（人名地名什么都有）。 */
    fun sanitize(tags: List<String>): List<String> {
        if (tags.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>(tags.size)
        for (raw in tags) {
            val tag = raw.trim().replace('\n', ' ').trim()
            if (tag.isEmpty() || tag == "null") continue
            out += tag.take(MAX_TAG_CHARS)
            if (out.size >= MAX_TAGS) break
        }
        return out.toList()
    }
}

/** 模型抽取出来的一条候选记忆 */
data class MemoryCandidate(
    val content: String,
    val drawer: MemoryDrawer,
    val importance: Int,
    val sourceQuote: String = "",   // 触发它的原话片段
    val categoryName: String = "",  // 模型给的原始 category，如 FACT/PREFERENCE/EVENT/RELATIONSHIP/OTHER
    val confidence: Float = 0.6f,
    /** 模型给的短标签（人 / 事 / 物 / 情绪），最多 5 个；给不出来就是空。 */
    val tags: List<String> = emptyList(),
    /** 这件事发生的时间（由「昨天/上周三/去年冬天」换算出的毫秒）；换算不出来是 null。 */
    val happenedAt: Long? = null
)

sealed interface MemoryDecision {
    /** 第一次提到：先只留一个「回声」，不下沉为长期记忆 */
    data class Echo(val candidate: MemoryCandidate) : MemoryDecision

    /** 第二次提到同一件事：正式落库 */
    data class Promote(val candidate: MemoryCandidate, val mergeIntoId: String?) : MemoryDecision

    /** 已有记忆再被提到：加固（hits+1、可能升重要度），可选补充内容 */
    data class Reinforce(val id: String, val appendedText: String?) : MemoryDecision

    /** 丢弃（一次性的情绪、太含糊、和人格无关） */
    data class Reject(val reason: String) : MemoryDecision
}

/**
 * 记忆筛查：把「模型一次抽一堆」变成「同一件事出现两次才落库」。
 *
 * 全部是纯函数，不碰 Room、不碰 Android、不读时钟（`now` 由调用方传入），方便单测。
 * 所有入口对空列表与空字符串安全，不会抛异常。
 */
object MemoryScreening {

    // ---------- 判定阈值 ----------

    /** 少于 8 字通常没有可长期复用的信息（"今天好累啊""在吗"之类） */
    private const val MIN_CONTENT_LENGTH = 8

    /** 超过 300 字更像一段叙述而不是一条记忆，交给摘要而不是原样落库 */
    private const val MAX_CONTENT_LENGTH = 300

    /** 模型自己都不确定的信息不落库，避免噪声污染人格 */
    private const val MIN_CONFIDENCE = 0.35f

    /** 与已有记忆相似度到这个程度，视为「同一件事又说了」→ 加固 */
    private const val REINFORCE_SIMILARITY = 0.72

    /**
     * 与抽屉里的回声相似度到这个程度，视为「第二次提到」→ 正式落库。
     * 比加固阈值低：回声只是临时线索，宁可多认一次重复，也不要丢掉真正重要的事。
     */
    private const val SAME_TOPIC_SIMILARITY = 0.60

    /** 新内容要带来至少这么多字的新信息，才值得追加到已有记忆后面 */
    private const val NEW_INFO_CHARS = 6

    /** 一个分句与已有记忆的词元重合度低于此值，才算「新信息」 */
    private const val FRAGMENT_OVERLAP_LIMIT = 0.60

    /** 归一化后不足 8 字的短句信息量太少，容易误判成同一件事，按比例衰减 */
    private const val SHORT_TEXT_LENGTH = 8.0

    /** 约定、身份、家人这类信息，只要出现就至少 4 分，避免被时间冲淡 */
    private const val IMPORTANCE_FLOOR_FOR_COMMITMENT = 4

    // ---------- 召回权重 ----------
    //
    // 下面这些数字是他「想事的习惯」，不是设置项：用户看不到、也调不了。
    // 它们只在同一个 query 下决定几条记忆谁先谁后，不改变「记不记得住」本身。

    /** 钉住的记忆先摆出来：用户亲手钉的，本来就该排在前面。 */
    private const val PINNED_BONUS = 2.0

    /** 和 query 的词元重合度（加权余弦 + 覆盖率）：回忆首先是「找对东西」。 */
    private const val RELEVANCE_WEIGHT = 3.0

    /** 这条记忆本身有多重要（1..5 压到 0..1）。 */
    private const val IMPORTANCE_WEIGHT = 1.0

    /** 新鲜度：不是越新越好，但太旧的东西该往后排。 */
    private const val FRESHNESS_WEIGHT = 0.8
    private const val FRESHNESS_HALF_LIFE_DAYS = 30.0
    private const val MILLIS_PER_DAY = 86_400_000.0

    /** 标签命中的加成：query 里出现人名/主题词时，带这个标签的记忆稍微靠前。 */
    private const val TAG_WEIGHT = 1.2

    /** 一条记忆最多算几个标签命中（再多也不加分，避免标签堆得多的记忆占便宜）。 */
    private const val TAG_MAX_HITS = 2

    /** 时间指代（「去年冬天」）落在 happenedAt 附近的加成。 */
    private const val TIME_WEIGHT = 1.0

    /**
     * 向量相似度的上限加成：最多 0.8。
     *
     * 为什么是 0.8：它是「字面近似的语义」（见 [LocalEmbedding]），能补「换个说法字面前缀对不上」的漏，
     * 但也可能整段跑偏。这个上限比新鲜度（0.8，同量级）、时间指代（1.0）、重要度（1.0）、
     * 钉住（2.0）都不高，所以它**只能在同一档相关度的记忆之间微调先后**，
     * 不可能把一条明显不重要、时间对不上的记忆顶到一条被钉住的重要记忆前面。
     * 换句话说：向量帮他「想起来」，不替他决定「什么更要紧」。
     */
    private const val VECTOR_WEIGHT = 0.8

    /** 时间区间的软边界：区间外按 120 天半衰期衰减，一年外几乎不加权。 */
    private const val TIME_HINT_HALF_LIFE_DAYS = 120.0

    /**
     * 抽屉只当背景色：聊到某个话题时，关于对方的事实最容易用上，共同的经历次之，
     * 「他自己是什么样的人」更像底色。权重差得很少，盖不过命中本身。
     */
    private val DRAWER_WEIGHT: Map<MemoryDrawer, Double> = mapOf(
        MemoryDrawer.USER to 1.0,
        MemoryDrawer.EVENT to 0.96,
        MemoryDrawer.SELF to 0.92
    )

    // ---------- 词表 ----------

    /** 不携带信息的口头前缀，比较相似度前先剥掉（"我觉得""今天""其实"…） */
    private val NO_INFO_PREFIXES = listOf(
        "我觉得", "我感觉", "说实话", "说真的", "其实", "反正",
        "然后", "就是", "大概", "好像", "不过", "而且",
        "今天", "昨天", "刚才", "现在", "刚刚", "这会儿"
    )

    /** 归一化时要清掉的标点（全角半角都算） */
    private val PUNCTUATION: Set<Char> =
        "，。！？、；：,.!?;:'\"“”‘’（）()【】[]…—～·".toSet()

    /** 纯寒暄候选，剥干净后没剩几个字就丢弃 */
    private val GREETINGS = listOf(
        "你好", "您好", "在吗", "在不在", "嗨", "哈喽", "早安", "晚安",
        "早上好", "中午好", "晚上好", "拜拜", "再见", "睡了吗", "吃了吗",
        "吃饭了吗", "哈哈", "嘿嘿", "呵呵", "嗯嗯", "好的", "收到",
        "谢谢", "没事", "辛苦了"
    )

    private val LATIN_GREETINGS = setOf("hi", "hello", "ok", "bye", "goodnight")

    /** 时效词：把「今天/现在」这类词和情绪词一起出现，说明是当下的情绪 */
    private val TIME_WORD_REGEX = Regex(
        "今天|昨天|刚才|刚刚|现在|今晚|这会儿|这两天|最近|突然|忽然|此刻|这天"
    )

    /** 情绪词：一次性情绪的典型表达 */
    private val EMOTION_WORD_REGEX = Regex(
        "好累|很累|累死|累了|难受|难过|开心|高兴|烦|无聊|困|emo|啊啊|郁闷|焦虑|崩溃|孤独|委屈|生气|想哭|睡不着|失眠|丧"
    )

    /** 按标点切句，用来判断新内容里哪一句是真正的新信息 */
    private val FRAGMENT_SEPARATOR = Regex("[，。；！？、,;!?\\n\\r]+")

    /** 值得给重要度保底的信息类型：约定、身份、家人 */
    private val IMPORTANT_KEYWORDS = listOf(
        "约定", "答应", "说好", "约好", "承诺", "纪念日", "生日", "名字", "叫",
        "职业", "工作", "家人", "妈妈", "爸爸", "父母", "家乡", "过敏", "忌口",
        "每周", "每天", "每年", "每次"
    )

    // ---------- 抽屉映射 ----------

    /**
     * 把模型给的 category 名映射到抽屉。
     * 兼容旧枚举（FACT/PREFERENCE/EVENT/RELATIONSHIP/OTHER）与中英文自定义标签。
     */
    fun drawerOf(categoryName: String): MemoryDrawer {
        val key = categoryName.trim().uppercase()
        if (key.isEmpty()) return MemoryDrawer.USER
        return when {
            key.contains("SELF") || key.contains("PERSONA") || key.contains("TRAIT") ||
                key.contains("HABIT") || key.contains("STYLE") || key.contains("CHARACTER") ||
                key.contains("自我") || key.contains("人格") || key.contains("性格") ->
                MemoryDrawer.SELF

            key.contains("EVENT") || key.contains("EXPERIENCE") || key.contains("PROMISE") ||
                key.contains("AGREEMENT") || key.contains("PLAN") || key.contains("RELATIONSHIP") ||
                key.contains("MEMORY") || key.contains("约定") || key.contains("经历") ||
                key.contains("关系") ->
                MemoryDrawer.EVENT

            else -> MemoryDrawer.USER
        }
    }

    // ---------- 归一化与相似度 ----------

    /** 归一化：去空白、统一全半角标点、去「我觉得/今天」这类无信息前缀，便于比较 */
    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        val lowered = buildString(text.length) {
            for (ch in text) {
                val code = ch.code
                when {
                    // 全角 ASCII（！到 ～）转半角
                    code in 0xFF01..0xFF5E -> append((code - 0xFEE0).toChar())
                    ch == '\u3000' -> append(' ')
                    else -> append(ch)
                }
            }
        }.lowercase()
        val compact = buildString(lowered.length) {
            for (ch in lowered) {
                if (ch.isWhitespace() || ch in PUNCTUATION) continue
                append(ch)
            }
        }
        var result = compact
        // 反复剥前缀："我觉得今天其实…" 要全部剥掉，但要给正文至少留 2 个字
        var changed = true
        while (changed && result.length > 2) {
            changed = false
            for (prefix in NO_INFO_PREFIXES) {
                if (result.length - prefix.length >= 2 && result.startsWith(prefix)) {
                    result = result.substring(prefix.length)
                    changed = true
                    break
                }
            }
        }
        return result
    }

    /** 0..1 的相似度：词元 Jaccard + 包含关系加权，中文按字 bigram 切词 */
    fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0

        val ta = tokenize(na)
        val tb = tokenize(nb)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0

        val inter = ta.count { it in tb }.toDouble()
        val jaccard = inter / (ta.size + tb.size - inter)
        val containment = inter / min(ta.size, tb.size).toDouble()

        // 同一件事换说法时字面重合往往很高，bigram 之外再补一层字符包含度
        val charsA = na.toSet()
        val charsB = nb.toSet()
        val charInter = charsA.count { it in charsB }.toDouble()
        val charContainment = charInter / min(charsA.size, charsB.size).toDouble()

        var score = 0.40 * jaccard + 0.35 * containment + 0.25 * charContainment

        val minLength = min(na.length, nb.length)
        // 短句（"对方喜欢猫" vs "对方喜欢狗"）词元太少，容易虚高，按长度衰减
        if (minLength < SHORT_TEXT_LENGTH) {
            score *= minLength / SHORT_TEXT_LENGTH
        }
        // 一方完整包含另一方（"…去公园散步" ⊂ "…去公园散步，还养了猫"）几乎总是同一件事
        if (minLength >= 5 && (na.contains(nb) || nb.contains(na))) {
            val longer = max(na.length, nb.length)
            score = max(score, 0.75 + 0.25 * minLength / longer)
        }
        return score.coerceIn(0.0, 1.0)
    }

    // ---------- 判定 ----------

    /**
     * @param echoes 之前只被提到过一次的候选（按抽屉存放）
     */
    fun decide(
        candidate: MemoryCandidate,
        existing: List<MemoryRow>,
        echoes: List<MemoryCandidate>,
        now: Long
    ): MemoryDecision {
        val content = candidate.content.trim()
        rejectReason(candidate, content)?.let { return MemoryDecision.Reject(it) }

        // 1) 已有记忆高度相似 → 加固，不新增
        val bestExisting = bestExistingMatch(content, candidate.drawer, existing)
        if (bestExisting != null && bestExisting.second >= REINFORCE_SIMILARITY) {
            val appended = appendedSentence(content, bestExisting.first.content)
            return MemoryDecision.Reinforce(bestExisting.first.id, appended)
        }

        // 2) 与该抽屉的回声相似 → 第二次提到，正式落库
        val bestEcho = bestEchoMatch(candidate, content, echoes)
        if (bestEcho != null && bestEcho.second >= SAME_TOPIC_SIMILARITY) {
            val mergeIntoId = bestExisting
                ?.takeIf { it.second >= SAME_TOPIC_SIMILARITY && it.first.drawer == candidate.drawer }
                ?.first?.id
            // 两次出现取更大的重要度；约定/身份类信息再兜底到 4
            val importance = maxOf(
                candidate.importance,
                bestEcho.first.importance,
                commitmentFloor(content, bestEcho.first.content)
            ).coerceIn(1, 5)
            return MemoryDecision.Promote(candidate.copy(content = content, importance = importance), mergeIntoId)
        }

        // 3) 第一次提到 → 只留回声（now 仅用于调用方记录时间，判定本身不需要）
        return MemoryDecision.Echo(candidate.copy(content = content))
    }

    /**
     * 召回排序（本地重排）：pinned / 词元重合度 / 向量相似度 / 标签命中 / 时间指代 / 重要度 / 新鲜度 / 抽屉背景色。
     *
     * 这一步**不查库**：查库只负责给候选（倒排索引命中 ∪ 向量命中 ∪ LIKE ∪ pinned/重要度兜底），
     * 排序全在内存里算。所以索引没建好、query 为空、向量表空着、数据库出问题，排序本身依旧成立——
     * 最坏也只是候选少一点、少一路信号，不会比旧的全表 LIKE 更差。
     *
     * @param vectorScores 记忆 id → 向量余弦（0..1）。空 map 等于「这条链路没有向量可用」，排序退化成纯词元。
     *   只在**同一批候选**里加一点分，不可能盖过 pin/重要度/时间（见 [VECTOR_WEIGHT]）。
     */
    fun rerankForRecall(
        memories: List<MemoryRow>,
        query: String,
        now: Long,
        vectorScores: Map<String, Double> = emptyMap()
    ): List<MemoryRow> {
        if (memories.isEmpty()) return emptyList()
        val span = ChineseTerms.timeSpanOf(query, now)
        return memories
            .map { it to recallScore(it, query, now, span, vectorScores) }
            .sortedWith(
                compareByDescending<Pair<MemoryRow, Double>> { it.second }
                    .thenByDescending { it.first.lastHitAt }
                    .thenByDescending { it.first.createdAt }
                    .thenBy { it.first.id }
            )
            .map { it.first }
    }

    /** 旧名字，保留给既有调用点：语义已经是新的本地重排。 */
    fun rankForRecall(
        memories: List<MemoryRow>,
        query: String,
        now: Long,
        vectorScores: Map<String, Double> = emptyMap()
    ): List<MemoryRow> = rerankForRecall(memories, query, now, vectorScores)

    /** 按字符预算挑选，返回按分数降序的前 N 条 */
    fun selectWithinBudget(
        memories: List<MemoryRow>,
        query: String,
        now: Long,
        maxChars: Int,
        vectorScores: Map<String, Double> = emptyMap()
    ): List<MemoryRow> {
        if (memories.isEmpty() || maxChars <= 0) return emptyList()
        val result = mutableListOf<MemoryRow>()
        var used = 0
        for (memory in rerankForRecall(memories, query, now, vectorScores)) {
            val cost = memory.content.length
            // 超预算的这条跳过，后面的短记忆仍可能塞得下（顺序不变）
            if (used + cost > maxChars) continue
            result += memory
            used += cost
        }
        return result
    }

    // ---------- 内部：拒绝规则 ----------

    private fun rejectReason(candidate: MemoryCandidate, content: String): String? {
        if (content.isBlank()) return "内容为空"
        if (content.length < MIN_CONTENT_LENGTH) return "内容太短，没有可长期复用的信息"
        if (content.length > MAX_CONTENT_LENGTH) return "内容太长，不像一条记忆"
        if (candidate.confidence < MIN_CONFIDENCE) return "模型自己也不确定，先不留"
        if (isSmallTalk(content)) return "只是寒暄，没有长期价值"
        if (isOneOffEmotion(content, candidate.importance)) return "一次性的情绪，不必长期记住"
        return null
    }

    /** 全是寒暄：把问候语逐个剥掉后只剩语气词 */
    private fun isSmallTalk(content: String): Boolean {
        val text = normalize(content)
        if (text.isEmpty()) return true
        if (text in LATIN_GREETINGS) return true
        var rest = text
        var changed = true
        while (changed) {
            changed = false
            for (greeting in GREETINGS) {
                if (rest.contains(greeting)) {
                    rest = rest.replace(greeting, "")
                    changed = true
                }
            }
        }
        return rest.length <= 2
    }

    /** 一次性情绪：带时效词 + 情绪词，且模型给的长期重要度本来就不高 */
    private fun isOneOffEmotion(content: String, importance: Int): Boolean {
        if (importance > 2) return false
        val text = content.lowercase()
        return TIME_WORD_REGEX.containsMatchIn(text) && EMOTION_WORD_REGEX.containsMatchIn(text)
    }

    // ---------- 内部：匹配与追加 ----------

    private fun bestExistingMatch(
        content: String,
        drawer: MemoryDrawer,
        existing: List<MemoryRow>
    ): Pair<MemoryRow, Double>? {
        if (existing.isEmpty()) return null
        return existing
            .map { it to similarity(content, it.content) }
            .filter { it.second > 0.0 }
            .maxWithOrNull(
                compareBy<Pair<MemoryRow, Double>> { it.second }
                    .thenBy { if (it.first.drawer == drawer) 1 else 0 }
                    .thenBy { it.first.importance }
            )
    }

    private fun bestEchoMatch(
        candidate: MemoryCandidate,
        content: String,
        echoes: List<MemoryCandidate>
    ): Pair<MemoryCandidate, Double>? {
        // echoes 是平铺列表，只跟同一个抽屉比，避免"关于你"和"共同经历"串味
        val sameDrawer = echoes.filter { it.drawer == candidate.drawer && it.content.isNotBlank() }
        if (sameDrawer.isEmpty()) return null
        return sameDrawer
            .map { it to similarity(content, it.content) }
            .filter { it.second > 0.0 }
            .maxWithOrNull(compareBy<Pair<MemoryCandidate, Double>> { it.second }.thenBy { it.first.importance })
    }

    /**
     * 判断新内容里是否有值得追加的句子。
     * 按标点切句，逐句与已有记忆比对词元重合度：重合度低的分句才是新信息。
     * 累加起来不足 6 字就返回 null（只是换个说法，不该把记忆越写越长）。
     */
    private fun appendedSentence(candidateContent: String, existingContent: String): String? {
        val existingNorm = normalize(existingContent)
        if (existingNorm.isEmpty()) return null
        val existingTokens = tokenize(existingNorm)
        if (existingTokens.isEmpty()) return null

        val fresh = mutableListOf<String>()
        var freshChars = 0
        for (fragment in candidateContent.split(FRAGMENT_SEPARATOR)) {
            val trimmed = fragment.trim()
            if (trimmed.isEmpty()) continue
            val fragmentNorm = normalize(trimmed)
            if (fragmentNorm.isEmpty()) continue
            val fragmentTokens = tokenize(fragmentNorm)
            if (fragmentTokens.isEmpty()) continue
            val overlap = fragmentTokens.count { it in existingTokens }.toDouble() / fragmentTokens.size
            if (overlap >= FRAGMENT_OVERLAP_LIMIT) continue
            fresh += trimmed
            freshChars += fragmentNorm.length
        }

        if (fresh.isEmpty() || freshChars < NEW_INFO_CHARS) return null
        val sentence = fresh.joinToString("，").trim().trimEnd('。', '，', '；', '！', '？', '、', ',', ';', '!', '?')
        if (sentence.isBlank()) return null
        return "$sentence。"
    }

    /** 约定 / 身份 / 家人类关键词出现时，重要度至少 4 */
    private fun commitmentFloor(vararg texts: String): Int {
        val raw = texts.joinToString(" ").lowercase()
        if (raw.isBlank()) return 0
        return if (IMPORTANT_KEYWORDS.any { raw.contains(it) }) IMPORTANCE_FLOOR_FOR_COMMITMENT else 0
    }

    // ---------- 内部：召回打分 ----------

    private fun recallScore(
        memory: MemoryRow,
        query: String,
        now: Long,
        span: TimeSpan?,
        vectorScores: Map<String, Double>
    ): Double {
        val pinned = if (memory.pinned) PINNED_BONUS else 0.0
        val relevance = ChineseTerms.relevance(query, memory.content)
        // 向量分是「换个说法也像」的一路补充：和词元重合度叠加，但有自己的上限。
        val vector = (vectorScores[memory.id] ?: 0.0).let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.0 }
        val importance = memory.importance.coerceIn(1, 5) / 5.0
        val fresh = freshness(memory, now)
        val tag = tagAffinity(query, memory.tags)
        val time = timeAffinity(span, memory.happenedAt)
        val drawer = DRAWER_WEIGHT[memory.drawer] ?: 1.0
        val score = pinned +
            relevance * RELEVANCE_WEIGHT +
            vector * VECTOR_WEIGHT +
            importance * IMPORTANCE_WEIGHT +
            fresh * FRESHNESS_WEIGHT +
            tag * TAG_WEIGHT +
            time * TIME_WEIGHT
        return score * drawer
    }

    /**
     * 标签命中 0..1：query 里出现这个标签（「妹妹」），或标签的词元被 query 覆盖。
     *
     * 标签只用来**加分**，不用来筛选：结构是给人格查记忆用的辅助，
     * 不能把「他记得的事」变成一条结构化查询的结果。
     */
    fun tagAffinity(query: String, tags: List<String>): Double {
        val hits = ChineseTerms.tagHits(query, tags, TAG_MAX_HITS)
        if (hits <= 0) return 0.0
        return hits.toDouble() / TAG_MAX_HITS
    }

    /**
     * 时间指代邻近 0..1：落在区间里算满分，区间外按半衰期给残值。
     *
     * 没有 happenedAt 就一律 0，**不拿 createdAt 顶替**：那是「什么时候记住的」，
     * 不是「什么时候发生的」。宁可少加一次权，也不要把「刚记下来的往事」当成「最近发生的事」。
     */
    fun timeAffinity(span: TimeSpan?, happenedAt: Long?): Double {
        if (span == null || happenedAt == null || happenedAt <= 0L) return 0.0
        if (span.contains(happenedAt)) return 1.0
        val days = span.distanceDays(happenedAt)
        return (exp(-days / TIME_HINT_HALF_LIFE_DAYS) * 0.5).coerceIn(0.0, 1.0)
    }

    /** 新鲜度 0..1：以 30 天为半衰期，越久没被提到越低；没有时间戳时按最旧处理 */
    private fun freshness(memory: MemoryRow, now: Long): Double {
        val last = if (memory.lastHitAt > 0L) memory.lastHitAt else memory.createdAt
        if (last <= 0L) return 0.0
        val days = (now - last).coerceAtLeast(0L) / MILLIS_PER_DAY
        return exp(-days / FRESHNESS_HALF_LIFE_DAYS)
    }

    // ---------- 内部：切词 ----------

    /**
     * 词元集合：中文按相邻两个字（bigram）切，英文/数字按整词切。
     * 中文不做分词，bigram 在短文本上足够稳，也不会因为分词错误崩掉。
     */
    private fun tokenize(normalized: String): Set<String> {
        if (normalized.isEmpty()) return emptySet()
        val tokens = LinkedHashSet<String>()
        val cjk = StringBuilder()
        val latin = StringBuilder()

        fun flushCjk() {
            if (cjk.isEmpty()) return
            if (cjk.length == 1) {
                tokens += "c:$cjk"
            } else {
                for (i in 0 until cjk.length - 1) tokens += cjk.substring(i, i + 2)
            }
            cjk.setLength(0)
        }

        fun flushLatin() {
            if (latin.isEmpty()) return
            tokens += "w:$latin"
            latin.setLength(0)
        }

        for (ch in normalized) {
            when {
                isCjk(ch) -> {
                    flushLatin()
                    cjk.append(ch)
                }

                ch.isLetterOrDigit() -> {
                    flushCjk()
                    latin.append(ch)
                }

                else -> {
                    flushCjk()
                    flushLatin()
                }
            }
        }
        flushCjk()
        flushLatin()
        return tokens
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF ||
            code in 0x3040..0x30FF || code in 0xAC00..0xD7AF ||
            code in 0xF900..0xFAFF
    }
}
