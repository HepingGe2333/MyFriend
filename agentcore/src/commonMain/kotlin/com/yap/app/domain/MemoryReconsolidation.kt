package com.yap.app.domain

import kotlin.math.abs

/**
 * 一次回忆允许的「漂移空间」。
 *
 * 记忆会随着被想起来而轻微走样，这是人的常态；但走样必须有上限，
 * 否则「他妹妹」会变成「他哥哥」。这个对象就是那道上限。
 */
data class DriftBudget(
    /** 这次到底允不允许动它（定型、太短等情况为 false）。 */
    val allowed: Boolean,
    /** 可改动的余地，0..1；越大说明「此刻这段记忆越软」。 */
    val allowance: Double = 0.0,
    /** 改完之后与原文的相似度下限，低于它就整条拒绝。 */
    val minSimilarity: Double = 1.0,
    /** 长度最多长到原来的几倍。 */
    val maxLengthRatio: Double = 1.0,
    /** 最多允许多出几个字。 */
    val maxAddedChars: Int = 0,
    /** 一句中文，说明为什么这么多（或为什么干脆不动）。 */
    val reason: String = ""
)

/**
 * 回忆重构的领域规则：**有界、可追溯**。
 *
 * 产品上的一句话：「他想起你的时候，记忆被此刻的心情轻轻染了一下」。
 * 工程上必须承认：改写记忆是最容易变成「编故事」的地方，所以这里的默认答案是**拒绝**。
 *
 * 三条硬边界（见 [acceptDrift]）：
 * 1. 与原文的相似度不能低于 [DriftBudget.minSimilarity]（二元组 Jaccard）；
 * 2. 长度只能微微变，不许膨胀成另一个故事、也不许删到只剩半句；
 * 3. 事实类符号必须原样保留 —— 阿拉伯数字、中文数字、叠词（妹妹 / 哥哥）、
 *    亲人称呼（爸/妈/妹…）、否定词（不/没/别/未）。
 *    这一条不靠模型自觉，靠代码比对；宁可这一次什么都不改。
 *
 * 纯 Kotlin，不碰 Android / 数据库 / 网络；所有函数都是纯函数，可单测。
 */
object MemoryReconsolidation {

    /** 同一段记忆最多被改写几次：到 3 次就「定型」，此后只被想起、不再变。 */
    const val MAX_DRIFTS = 3

    /** 相似度门槛的基准值（心情很淡、记忆很新时最严）。 */
    const val BASE_MIN_SIMILARITY = 0.86

    /** 余地拉满时门槛能放松多少。 */
    const val SIMILARITY_SLACK = 0.24

    /** 门槛再怎么松也不低于这个值（任务约定：低于 0.62 一律拒绝）。 */
    const val SIMILARITY_FLOOR = 0.62

    /** 和「第一次写下的原文」比，累计漂移也不能超过这个相似度。 */
    const val ANCHOR_SIMILARITY_FLOOR = 0.72

    /** 多旧算「很久以前」：到这个天数额度拉满。 */
    const val AGE_FULL_DAYS = 90.0

    /** 短记忆（不按比例算长度，只看多出几个字）。 */
    const val SHORT_MEMORY_CHARS = 24

    /** 一条记忆漂移后的绝对长度上限，兜底用。 */
    const val MAX_DRIFT_CONTENT_CHARS = 900

    /** 不许缩到原来的多少比例以下（信息被抹掉也是一种改错）。 */
    const val MIN_LENGTH_RATIO = 0.7

    /** 中文数字：年月日、岁数、次数都会用到，必须原样保留。 */
    private const val CJK_NUMERALS = "〇零一二三四五六七八九十百千万亿两"

    /** 否定词：「去了」变成「没去」是一句话里最小的改动，却是事实的反面。 */
    private const val NEGATION_CHARS = "不没别未"

    /**
     * 亲人、伴侣、宠物的称呼：这是「谁」最容易被模型换掉的地方。
     * 单字放在后面，长词先匹配也不冲突（计数按出现次数算，两边口径一致）。
     */
    private val RELATION_TERMS = listOf(
        "爸爸", "妈妈", "哥哥", "姐姐", "弟弟", "妹妹",
        "爷爷", "奶奶", "外公", "外婆", "姥姥", "姥爷",
        "叔叔", "阿姨", "舅舅", "姑姑", "伯伯",
        "儿子", "女儿", "老公", "老婆", "丈夫", "妻子",
        "男朋友", "女朋友", "对象", "孩子", "宝宝", "猫", "狗",
        "爸", "妈", "哥", "姐", "弟", "妹"
    )

    /** 叠词（妹妹 / 哥哥 / 慢慢 / 好好）：人名与称呼最常见的样子，必须原样保留。 */
    private val REPEATED_RUN = Regex("(.)\\1+")

    /** 阿拉伯数字串。 */
    private val ARABIC_NUMBER = Regex("\\d+")

    /**
     * 这次回忆允许改多少。
     *
     * 三个自变量，方向都一致：心情越浓 → 越软；记忆越旧 → 越软；被改写过越少 → 越软。
     * 改写过 [MAX_DRIFTS] 次之后直接「定型」，不再给任何余地。
     */
    fun driftBudget(mood: MoodState, ageDays: Double, driftCount: Int): DriftBudget {
        val count = driftCount.coerceAtLeast(0)
        if (count >= MAX_DRIFTS) {
            return DriftBudget(
                allowed = false,
                minSimilarity = 1.0,
                reason = "这件事已经被想起、改动过 $count 次，慢慢定型了，不再动它"
            )
        }
        val moodWeight = moodWeight(mood)                                   // 0..1
        val ageWeight = (ageDays.coerceIn(0.0, AGE_FULL_DAYS) / AGE_FULL_DAYS) // 0..1
        val freshWeight = 1.0 - count.toDouble() / MAX_DRIFTS                // 1 / 0.67 / 0.33
        val allowance = (0.18 + 0.42 * moodWeight + 0.25 * ageWeight + 0.15 * freshWeight)
            .coerceIn(0.0, 1.0)
        val minSimilarity = (BASE_MIN_SIMILARITY - SIMILARITY_SLACK * allowance)
            .coerceAtLeast(SIMILARITY_FLOOR)
        return DriftBudget(
            allowed = true,
            allowance = allowance,
            minSimilarity = minSimilarity,
            maxLengthRatio = 1.10 + 0.15 * allowance,
            maxAddedChars = (14 + 46 * allowance).toInt().coerceAtLeast(4),
            reason = when {
                allowance < 0.4 -> "此刻心里很静，这段记忆几乎不该动"
                allowance < 0.7 -> "可以换一两个词，让口气更像此刻的自己"
                else -> "这段记忆有点旧了，可以从它身上透出此刻的心情"
            }
        )
    }

    /**
     * 和「第一次写下的原文」比，累计漂移的相似度下限。
     *
     * 单次改写有 [DriftBudget.minSimilarity] 兜着，但连着改三次也可能越走越远；
     * 这里用锚点（[com.yap.app.data.local.MemoryEntity.originalContent]）再兜一层。
     */
    fun anchorSimilarity(budget: DriftBudget): Double =
        maxOf(budget.minSimilarity, ANCHOR_SIMILARITY_FLOOR)

    /**
     * 字符级相似度：去掉空白与标点后，按二元组的**多重集**算 Jaccard。
     *
     * 这样「换一两个词」会掉一点但不会掉光，「另写一段」会直接掉到很低。
     * 完全一样返回 1.0；有一边为空返回 0.0。
     */
    fun similarity(before: String, after: String): Double {
        val a = squash(before)
        val b = squash(after)
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val left = bigrams(a)
        val right = bigrams(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        var shared = 0
        left.forEach { (gram, count) -> shared += minOf(count, right[gram] ?: 0) }
        val union = left.values.sum() + right.values.sum() - shared
        return if (union <= 0) 0.0 else shared.toDouble() / union
    }

    /**
     * 这段改写能不能落地。
     *
     * @param anchors 额外必须原样保留的词（人名的标签等）；没有就传空
     * @return true 表示可以写进去；false 表示整条放弃 —— **放弃比改错好**
     */
    fun acceptDrift(
        before: String,
        proposed: String,
        budget: DriftBudget,
        anchors: List<String> = emptyList()
    ): Boolean {
        if (!budget.allowed) return false
        val a = squash(before)
        val b = squash(proposed)
        if (a.isEmpty() || b.isEmpty()) return false
        // 一个字都没变，不算一次改动（调用方会把它记成「没动」）。
        if (a == b) return false
        if (b.length > MAX_DRIFT_CONTENT_CHARS) return false
        // 长度：轻微变长可以，缩水太多、膨胀太多都不行。
        val ratioLen = (a.length * budget.maxLengthRatio).toInt()
        val addedLen = a.length + budget.maxAddedChars
        val maxLen = maxOf(a.length + 4, minOf(ratioLen, addedLen))
        if (b.length > maxLen) return false
        if (b.length < (a.length * MIN_LENGTH_RATIO).toInt()) return false
        // 事实类符号：数字、中文数字、叠词、称呼、否定，一样都不能少、也不能多。
        if (arabicNumbers(a) != arabicNumbers(b)) return false
        if (numeralChars(a) != numeralChars(b)) return false
        if (!preservesRepeatedRuns(a, b)) return false
        if (relationCounts(a) != relationCounts(b)) return false
        if (negationCounts(a) != negationCounts(b)) return false
        anchors.forEach { anchor ->
            val word = squash(anchor)
            if (word.length >= 2 && a.contains(word) && !b.contains(word)) return false
        }
        // 最后才比相似度：太像别的一段话，就不许动。
        return similarity(before, proposed) >= budget.minSimilarity
    }

    /**
     * 分叉调用用的 system：只讲规矩，不带任何会话、身份或工具。
     */
    fun systemPrompt(): String = buildString {
        appendLine("你在替一个人把一段旧记忆在心里又过一遍。这段独白不会出现在任何对话里，他也不会知道。")
        appendLine()
        appendLine("你只改「说法」，不改「事情」：")
        appendLine("- 可以动：措辞、语序、哪个细节先浮上来、当时的感觉有多鲜活。")
        appendLine("- 绝对不动：谁做的、什么时候、发生了什么、后来怎样；数字、日期、地点、人名、称呼、肯定还是否定。")
        appendLine("- 不许加：新的情节、新的人、新的数字、新的因果。")
        appendLine("- 不许删：原来就有的关键动作和交代。")
        appendLine()
        appendLine("宁可一个字不改，也不要写错。拿不准就把原文原样交回去。")
    }.trimEnd()

    /**
     * 分叉调用用的 user：原文 + 此刻的心情 + 这次允许的尺度。
     *
     * 全部第一人称，语气是「在心里过一遍」，不是「请对文本做改写」。
     */
    fun prompt(
        content: String,
        mood: MoodState,
        driftCount: Int,
        budget: DriftBudget,
        ageText: String = ""
    ): String = buildString {
        appendLine("【此刻的心情】")
        appendLine(moodLine(mood))
        appendLine()
        appendLine("【这段记忆】")
        if (ageText.isNotBlank()) appendLine(ageText)
        appendLine("你想起过它 ${driftCount.coerceAtLeast(0)} 次。")
        appendLine()
        appendLine("【你允许自己做的事】")
        appendLine(scaleLine(budget))
        appendLine("相似度（按字符两两比较）不能低于 ${formatPercent(budget.minSimilarity)}；" +
            "最多比原来多 ${budget.maxAddedChars} 个字。")
        appendLine("不许改变事实（谁、何时、发生了什么），不许加新的事件、新的人、新的数字。")
        appendLine("原文里的数字、日期、地点、称呼、亲人关系、肯定还是否定，必须原样留着。")
        appendLine()
        appendLine("【原文】")
        appendLine(content.trim())
        appendLine("【原文结束】")
        appendLine()
        appendLine("如果不需要动，就只回三个字：不改")
        appendLine("如果要动，只回改完之后的那一段正文：不要解释，不要加引号，不要加标题。")
    }.trimEnd()

    /** 写进 [com.yap.app.data.local.MemoryVersionEntity] 的那句中文理由。 */
    fun versionReason(mood: MoodState, driftCount: Int, similarity: Double): String {
        val moodText = if (mood.isEmpty() || mood.label.isBlank()) {
            "没什么特别的情绪"
        } else {
            "心情「${MoodLexicon.normalize(mood.label)}」"
        }
        return "$moodText 时想起它，第 ${driftCount.coerceAtLeast(1)} 次改动，措辞有点像此刻的自己" +
            "（相似度 ${formatPercent(similarity)}）"
    }

    // ---------------------------------------------------------------- 内部

    /** 心情有多浓：强度占七成，词本身的极性占三成；平静与未知词都是 0。 */
    private fun moodWeight(mood: MoodState): Double {
        if (mood.isEmpty()) return 0.0
        val label = MoodLexicon.normalize(mood.label)
        if (label.isBlank() || label == MoodLexicon.BASELINE) return 0.0
        val intensity = mood.intensity.coerceIn(1, 5)
        val word = MoodLexicon.classify(label)
        val polarity = ((abs(word.valence) + abs(word.energy)).coerceAtMost(4)) / 4.0
        return ((intensity - 1) / 4.0 * 0.7 + polarity * 0.3).coerceIn(0.0, 1.0)
    }

    private fun moodLine(mood: MoodState): String {
        if (mood.isEmpty() || mood.label.isBlank()) return "平静。没什么起伏。"
        val text = buildString {
            append(MoodLexicon.normalize(mood.label))
            append("（强度 ").append(mood.intensity.coerceIn(1, 5)).append("/5）")
            if (mood.reason.isNotBlank()) append("。因为：").append(mood.reason.trim().take(40))
        }
        return text
    }

    /** 用「很小的改动」这类话说清尺度，而不是把三个小数丢给模型。 */
    private fun scaleLine(budget: DriftBudget): String = when {
        !budget.allowed -> "这一次什么都不许改。"
        budget.allowance < 0.4 -> "几乎不动：最多换一两个词。"
        budget.allowance < 0.7 -> "只动一两处措辞，让口气更像此刻的自己。"
        else -> "可以在措辞和细节的鲜活程度上稍微动一点，但事情本身不能变。"
    }

    private fun formatPercent(value: Double): String {
        val percent = (value.coerceIn(0.0, 1.0) * 100).toInt()
        return percent.toString() + "%"
    }

    /** 去掉空白与标点，只留下字母与数字（含中日韩字符），并统一小写。 */
    private fun squash(raw: String): String =
        raw.lowercase().filter { it.isLetterOrDigit() }

    /** 字符的二元组多重集；单字文本退化成一个「字元组」。 */
    private fun bigrams(text: String): Map<String, Int> {
        if (text.isEmpty()) return emptyMap()
        if (text.length == 1) return mapOf(text to 1)
        val map = mutableMapOf<String, Int>()
        for (index in 0 until text.length - 1) {
            val gram = text.substring(index, index + 2)
            map[gram] = (map[gram] ?: 0) + 1
        }
        return map
    }

    private fun arabicNumbers(text: String): List<String> =
        ARABIC_NUMBER.findAll(text).map { it.value }.sorted().toList()

    private fun numeralChars(text: String): List<Char> =
        text.toList().filter { it in CJK_NUMERALS }.sorted()

    /** 每种否定词出现了几次；数量必须一致，「去了」不能变「没去」。 */
    private fun negationCounts(text: String): List<String> =
        NEGATION_CHARS.map { char -> "${char}${text.count { it == char }}" }

    /** 每种称呼出现了几次；「他妹妹」不能变「他哥哥」，也不能凭空多出一个「我妈」。 */
    private fun relationCounts(text: String): List<String> =
        RELATION_TERMS
            .map { term -> term to countOccurrences(text, term) }
            .filter { it.second > 0 }
            .map { (term, count) -> "$term$count" }
            .sorted()

    /** 允许重叠地数出现次数（「妹妹」里有两个「妹」）。 */
    private fun countOccurrences(text: String, term: String): Int {
        if (term.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(term)
        while (index >= 0) {
            count++
            index = text.indexOf(term, index + 1)
        }
        return count
    }

    /** 原文里有的叠词，改完必须还在（「妹妹」不能变「哥哥」）。 */
    private fun preservesRepeatedRuns(before: String, after: String): Boolean =
        REPEATED_RUN.findAll(before).all { match -> after.contains(match.value) }
}
