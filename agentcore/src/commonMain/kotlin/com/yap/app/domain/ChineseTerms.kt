package com.yap.app.domain

import kotlin.math.sqrt

/**
 * 一个检索词元与它的分量（0..1）。同一段文本里，权重按出现次数归一化：
 * 出现最多的那个词元是 1.0，其余按比例；只出现一次的词元拿到 1/最高次数。
 *
 * 为什么不是 TF-IDF：语料就是他自己那几百条记忆，IDF 会被几条长记忆带偏；
 * 次数归一化足够让「反复提到的词」稍微更重要，又不至于让一条长记忆压过一切。
 */
data class TermWeight(val term: String, val weight: Double)

/**
 * 一段时间范围（左闭右开），用来理解「昨天」「上个月」「去年冬天」这类时间指代。
 *
 * 只做日历算术，不读系统时钟外的任何状态：同一个 `now` 永远算出同一个区间，方便单测。
 */
data class TimeSpan(val from: Long, val to: Long) {
    /** 区间的中点：模型只说「去年冬天」时，用它当作「那件事发生的时间」的近似。 */
    val center: Long get() = from + (to - from) / 2

    fun contains(at: Long): Boolean = at >= from && at < to

    /** 距离这个区间有多少天（落在区间内是 0）。 */
    fun distanceDays(at: Long): Double {
        val millis = when {
            at < from -> from - at
            at >= to -> at - to
            else -> 0L
        }
        return millis.toDouble() / DAY_MILLIS
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000.0
    }
}

/**
 * 中文检索词元：把一句人话拆成「能拿去查倒排索引」的碎片。
 *
 * ## 为什么是字二元组
 *
 * 中文没有空格，词库又不该塞进 App（几个 G 的分词模型不现实）。字二元组是检索里最划算的折中：
 * 「项目压力」→ 项目 / 目压 / 压力，这样「项目压得我睡不着」也能被「项目压力」找到。
 * 分词会错，二元组只会多出一点噪声，不会整句查不到。
 *
 * ## 为什么先删虚词再切
 *
 * 「我养了猫」如果只按原顺序切，会得到 我养 / 养了 / 了猫 —— 三个都带着虚词，反而查不到「猫」。
 * 所以先把「的了吗呢」这类不携带信息的字去掉，让实词贴在一起：「我养了猫」→「养猫」→ 养猫。
 * 代价是「难过/困难」都会变成「难」：对着他自己的记忆够用，而且查询和记忆走同一个函数，不会错配。
 *
 * ## 约定
 *
 * - 纯 Kotlin：不碰 Room、不碰 Android、不读时钟（`now` 由调用方传入）；
 * - 空串、纯标点、超长文本、怪字符一律安全返回空列表，绝不抛异常；
 * - 用户可见的地方看不到这里的任何常量和权重：这是他自己的记性，不是给用户调参的面板。
 */
object ChineseTerms {

    /** 一次查询最多取多少个词元（喂给 SQL 的 IN 列表要克制）。 */
    const val MAX_QUERY_TERMS = 24

    /** 一条记忆最多写多少个索引词元：正文最长几百字，再多的都是低频噪声。 */
    const val MAX_TERMS_PER_TEXT = 160

    /** 单个汉字（删掉虚词后只剩一个字）本身就模糊，权重给低一点。 */
    private const val SINGLE_CHAR_WEIGHT = 0.7

    /** 加权覆盖率与余弦的配比：覆盖率治「长记忆被长度拖低分」，余弦治「短记忆虚高」。 */
    private const val WEIGHT_COVERAGE = 0.6
    private const val WEIGHT_COSINE = 0.4

    private const val DAY_MILLIS = 86_400_000L

    // ---------------------------------------------------------------- 切词

    /**
     * 把文本切成 (词元, 权重) 列表，按权重降序。
     * 空串 / 纯标点 / 全是虚词时返回空列表。
     */
    fun terms(text: String): List<TermWeight> {
        val counted = countedTerms(text)
        if (counted.isEmpty()) return emptyList()
        val max = counted.values.maxOrNull() ?: return emptyList()
        if (max <= 0.0) return emptyList()
        return counted.entries
            .asSequence()
            .map { TermWeight(it.key, (it.value / max).coerceIn(0.0, 1.0)) }
            .sortedWith(compareByDescending<TermWeight> { it.weight }.thenBy { it.term })
            .take(MAX_TERMS_PER_TEXT)
            .toList()
    }

    /** 词元 → 权重，用于余弦。同 [terms]。 */
    fun weights(text: String): Map<String, Double> {
        val list = terms(text)
        if (list.isEmpty()) return emptyMap()
        return LinkedHashMap<String, Double>(list.size).apply {
            list.forEach { put(it.term, it.weight) }
        }
    }

    /**
     * 拿去做倒排索引查询的词元（不含权重、已去重）。
     *
     * 取权重最高的前 [max] 个：查询里的虚词和长尾词不该把 IN 列表撑爆。
     */
    fun queryTerms(text: String, max: Int = MAX_QUERY_TERMS): List<String> {
        if (max <= 0) return emptyList()
        return terms(text)
            .take(max)
            .map { it.term }
            .distinct()
    }

    /**
     * 「这段正文有多相关」的 0..1 分：加权覆盖率为主、余弦为辅。
     *
     * 整段包含查询时直接给 1.0 —— 中文换个说法（「在杭州工作」/「杭州工作」）时二元组可能漏，
     * 但字面包含是确定的。空查询、空正文一律 0（此时排序完全交给重要度与新鲜度）。
     */
    fun relevance(query: String, document: String): Double {
        val q = normalize(query)
        if (q.isEmpty()) return 0.0
        val d = normalize(document)
        if (d.isEmpty()) return 0.0
        if (d.contains(q)) return 1.0

        val qWeights = weights(q)
        if (qWeights.isEmpty()) return 0.0
        val dWeights = weights(d)
        if (dWeights.isEmpty()) return 0.0

        var matched = 0.0
        var total = 0.0
        qWeights.forEach { (term, weight) ->
            total += weight
            val hit = dWeights[term] ?: return@forEach
            matched += weight * hit
        }
        if (total <= 0.0 || matched <= 0.0) return 0.0
        val coverage = (matched / total).coerceIn(0.0, 1.0)
        val cosine = cosine(qWeights, dWeights)
        return (WEIGHT_COVERAGE * coverage + WEIGHT_COSINE * cosine).coerceIn(0.0, 1.0)
    }

    /**
     * query 里命中了几个标签（最多 [max] 个，够了就不再数）。
     *
     * 标签是「妹妹」「失眠」这样的短词，判断靠词元重叠而不是子串乱猜：
     * 「妹妹」命中 query「我妹妹最近失眠」，但「妹」不会因为 query 里有「姐妹」就算命中。
     */
    fun tagHits(query: String, tags: List<String>, max: Int = 2): Int {
        if (max <= 0 || query.isBlank() || tags.isEmpty()) return 0
        val queryNorm = normalize(query)
        if (queryNorm.isEmpty()) return 0
        val queryWeights = weights(queryNorm)
        var hits = 0
        tags.forEach { tag ->
            val tagNorm = normalize(tag)
            if (tagNorm.isEmpty()) return@forEach
            val matched = queryNorm.contains(tagNorm) ||
                terms(tagNorm).any { it.term in queryWeights }
            if (matched) hits++
            if (hits >= max) return hits
        }
        return hits
    }

    /** 加权余弦：两段文本的词元向量夹角。任一为空返回 0，不会除零。 */
    fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (shorter, longer) = if (a.size <= b.size) a to b else b to a
        var dot = 0.0
        shorter.forEach { (term, weight) ->
            val other = longer[term] ?: return@forEach
            dot += weight * other
        }
        if (dot <= 0.0) return 0.0
        val normA = sqrt(a.values.sumOf { it * it })
        val normB = sqrt(b.values.sumOf { it * it })
        if (normA <= 0.0 || normB <= 0.0) return 0.0
        return (dot / (normA * normB)).coerceIn(0.0, 1.0)
    }

    // ---------------------------------------------------------------- 归一化

    /**
     * 归一化：全角转半角、转小写、标点与空白换成空格、去掉「去年冬天」这类时间指代。
     *
     * 时间指代单独处理：它由 [timeSpanOf] 换成真实时间区间去加权，
     * 留在词元里只会让「去年」这种词污染命中。
     */
    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        val halfWidth = buildString(text.length) {
            for (ch in text) {
                when {
                    ch.code in 0xFF01..0xFF5E -> append((ch.code - 0xFEE0).toChar())
                    ch == '\u3000' -> append(' ')
                    else -> append(ch)
                }
            }
        }
        val spaced = buildString(halfWidth.length) {
            for (ch in halfWidth.lowercase()) {
                append(if (ch.isWhitespace() || ch in PUNCTUATION) ' ' else ch)
            }
        }
        return stripTimeExpressions(spaced).replace(Regex("\\s+"), " ").trim()
    }

    /**
     * 去掉「昨天」「上周三」「上个月」「去年冬天」这类相对时间说法。
     * 单独暴露出来是为了单测能把规则钉死；正文里真正的「冬天」（怕冷）不受影响，
     * 只删带相对参照的说法（去年/今年/上/上个…）。
     */
    fun stripTimeExpressions(text: String): String {
        if (text.isBlank()) return text
        var result = text
        STRIP_PATTERNS.forEach { regex ->
            result = regex.replace(result, " ")
        }
        return result
    }

    // ---------------------------------------------------------------- 内部：切词实现

    /** 词元 → 次数权重。查重与归一化都在 [terms] 里做，这里只管数。 */
    private fun countedTerms(text: String): Map<String, Double> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyMap()
        val counts = LinkedHashMap<String, Double>(32)

        fun add(term: String, weight: Double) {
            if (term.isEmpty()) return
            counts[term] = (counts[term] ?: 0.0) + weight
        }

        val cjk = StringBuilder()
        val latin = StringBuilder()

        fun flushCjk() {
            if (cjk.isEmpty()) return
            // 先删虚词，让实词贴在一起：「我养了猫」→「养猫」。
            val content = buildString(cjk.length) {
                cjk.forEach { ch -> if (ch !in STOP_CHARS) append(ch) }
            }
            cjk.setLength(0)
            if (content.isEmpty()) return
            if (content.length == 1) {
                val single = content.toString()
                if (single !in STOP_BIGRAMS) add(single, SINGLE_CHAR_WEIGHT)
                return
            }
            for (i in 0 until content.length - 1) {
                val bigram = content.substring(i, i + 2)
                if (bigram in STOP_BIGRAMS) continue
                add(bigram, 1.0)
            }
        }

        fun flushLatin() {
            if (latin.isEmpty()) return
            val word = latin.toString()
            latin.setLength(0)
            if (word.length < 2 && word.toIntOrNull() == null) return
            if (word in LATIN_STOP_WORDS) return
            add(word, 1.0)
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
        return counts
    }

    // ---------------------------------------------------------------- 时间指代

    /**
     * 把查询里的时间指代解析成一段时间区间；解析不出来返回 null（= 不加权，不猜）。
     *
     * 支持的写法：今天 / 昨天 / 前天 / 大前天 / 上周三 / 上周 / 这周 / 上个月 / 这个月 /
     * 去年 / 今年 / 前年 / 去年冬天（春夏秋冬同理）/ N 天前 / N 周前 / N 个月前 / N 年前 /
     * 最近 / 这两天 / 这些天。
     */
    fun timeSpanOf(text: String, now: Long): TimeSpan? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        numericSpanOf(raw, now)?.let { return it }
        yearSeasonSpanOf(raw, now)?.let { return it }
        if (LAST_MONTH.containsMatchIn(raw)) return monthSpan(now, -1)
        if (THIS_MONTH.containsMatchIn(raw)) return monthSpan(now, 0)
        weekdaySpanOf(raw, now)?.let { return it }
        if (LAST_WEEK.containsMatchIn(raw)) return weekSpan(now, -1)
        if (THIS_WEEK.containsMatchIn(raw)) return weekSpan(now, 0)
        dayWordSpanOf(raw, now)?.let { return it }
        if (RECENT_WORDS.containsMatchIn(raw)) {
            return TimeSpan(dayStart(now, -7), dayStart(now, 1))
        }
        seasonWordOf(raw)?.let { return seasonSpanIncumbent(it, now) }
        return null
    }

    /** 查询里有没有可解析的时间指代。 */
    fun hasTimeHint(text: String, now: Long): Boolean = timeSpanOf(text, now) != null

    private fun numericSpanOf(text: String, now: Long): TimeSpan? {
        val match = NUMERIC_TIME.find(text) ?: return null
        val count = chineseNumber(match.groupValues[1]) ?: return null
        if (count <= 0) return null
        val unit = match.groupValues[2]
        return when {
            unit.endsWith("天") || unit.endsWith("日") -> dayWordSpan(now, -count)
            unit.contains("周") || unit.contains("星期") -> {
                val end = dayStart(now, -(count * 7 - 1))
                TimeSpan(dayStart(now, -(count * 7 + 6)), end)
            }

            unit.endsWith("月") -> monthSpan(now, -count)
            else -> yearSpan(now, -count)
        }
    }

    private fun yearSeasonSpanOf(text: String, now: Long): TimeSpan? {
        val match = YEAR_WITH_SEASON.find(text) ?: return null
        val offset = when (match.groupValues[1]) {
            "去年" -> -1
            "前年" -> -2
            else -> 0
        }
        val season = seasonWordOf(match.groupValues[2])
            ?: return yearSpan(now, offset)
        val anchorYear = LocalTimeMath.civilFromEpochDay(LocalTimeMath.localEpochDay(now)).year + offset
        // 「去年冬天」= 去年 12 月到今年 2 月：冬天是跨年的，锚点取开始下雪的那一年。
        return seasonSpan(anchorYear, season)
    }

    private fun weekdaySpanOf(text: String, now: Long): TimeSpan? {
        val match = WEEKDAY_IN_WEEK.find(text) ?: return null
        val marker = match.groupValues[1]
        val weekdayChar = match.groupValues[3]
        val index = WEEKDAY_INDEX[weekdayChar] ?: return null
        val weekOffset = if (marker == "上") -1 else 0
        val monday = weekStart(now) + weekOffset * 7 * DAY_MILLIS
        val start = monday + (index - 1) * DAY_MILLIS
        return TimeSpan(start, start + DAY_MILLIS)
    }

    private fun dayWordSpanOf(text: String, now: Long): TimeSpan? {
        DAY_WORDS.forEach { (word, offset) ->
            if (text.contains(word)) return dayWordSpan(now, offset)
        }
        return null
    }

    private fun dayWordSpan(now: Long, offsetDays: Int): TimeSpan =
        TimeSpan(dayStart(now, offsetDays), dayStart(now, offsetDays + 1))

    private fun weekSpan(now: Long, weekOffset: Int): TimeSpan {
        val start = weekStart(now) + weekOffset * 7 * DAY_MILLIS
        return TimeSpan(start, start + 7 * DAY_MILLIS)
    }

    private fun monthSpan(now: Long, monthOffset: Int): TimeSpan {
        val today = LocalTimeMath.civilFromEpochDay(LocalTimeMath.localEpochDay(now))
        val month = today.month - 1 + monthOffset
        return TimeSpan(monthStart(today.year, month), monthStart(today.year, month + 1))
    }

    private fun yearSpan(now: Long, yearOffset: Int): TimeSpan {
        val year = LocalTimeMath.civilFromEpochDay(LocalTimeMath.localEpochDay(now)).year + yearOffset
        return TimeSpan(monthStart(year, 0), monthStart(year + 1, 0))
    }

    private fun seasonSpan(anchorYear: Int, season: String): TimeSpan {
        val startMonth = when (season) {
            "春" -> 2
            "夏" -> 5
            "秋" -> 8
            else -> 11
        }
        val start = monthStart(anchorYear, startMonth)
        // monthStart 自己会跨年（11 + 3 = 14 → 次年 2 月），这里不能再加一年，否则冬天会变成两年长。
        val end = monthStart(anchorYear, startMonth + 3)
        return TimeSpan(start, end)
    }

    /**
     * 只说了「冬天」时的最近一次：12 月算今年的冬天，1、2 月还在这个冬天里，
     * 3 月到 11 月说的都是刚过去的那个冬天。
     */
    private fun seasonSpanIncumbent(season: String, now: Long): TimeSpan {
        val today = LocalTimeMath.civilFromEpochDay(LocalTimeMath.localEpochDay(now))
        val year = today.year
        val month = today.month
        val anchorYear = when {
            month >= 12 -> year
            month <= 2 -> year - 1
            else -> if (season == "冬") year - 1 else year
        }
        return seasonSpan(anchorYear, season)
    }

    private fun seasonWordOf(raw: String): String? {
        val match = SEASON_WORDS.find(raw) ?: return null
        val word = match.value
        return when (word.first()) {
            '春' -> "春"
            '夏' -> "夏"
            '秋' -> "秋"
            else -> "冬"
        }
    }

    private fun dayStart(now: Long, offsetDays: Int): Long =
        LocalTimeMath.dayStart(now, offsetDays)

    /** 周一 00:00（中国人说「上周三」时按周一起算的那一周）。 */
    private fun weekStart(now: Long): Long = LocalTimeMath.weekStart(now)

    private fun monthStart(year: Int, month0: Int): Long = LocalTimeMath.monthStart(year, month0)

    /** 「三」「十二」「两」这类中文数字（够用到 99，再大没有意义）。 */
    private fun chineseNumber(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        text.toIntOrNull()?.let { return it }
        val digits = mapOf(
            '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (text.length == 1) {
            if (text[0] == '十') return 10
            return digits[text[0]]
        }
        val tenIndex = text.indexOf('十')
        if (tenIndex < 0) return null
        val tens = if (tenIndex == 0) 1 else digits[text[0]] ?: return null
        val ones = if (tenIndex == text.length - 1) 0 else digits[text[tenIndex + 1]] ?: return null
        return tens * 10 + ones
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF ||
            code in 0x3040..0x30FF || code in 0xAC00..0xD7AF ||
            code in 0xF900..0xFAFF
    }

    // ---------------------------------------------------------------- 词表

    /** 不携带信息的标点（全角半角都算）。 */
    private val PUNCTUATION: Set<Char> =
        "，。！？、；：,.!?;:'\"“”‘’（）()【】[]{}…—～·-~`<>《》「」/\\|@#$%^&*+=_".toSet()

    /**
     * 虚词表：代词、助词、语气词、程度副词。
     *
     * 刻意**不**删「不、没、有、和、说、想、要、会、上、下」：它们要么改变意思（不喜欢 ≠ 喜欢），
     * 要么常是实词的一部分（家里、说话）。只删纯语法字，宁可留噪声也不要改语义。
     */
    private val STOP_CHARS: Set<Char> =
        "的了着过地得是我你他她它咱您们就也都很太又再还才而且并或吗呢吧啊呀哦嗯嘛之其个这那在于与觉".toSet()

    /** 整词噪声：删掉虚词后仍会剩下的口水词二元组。 */
    private val STOP_BIGRAMS: Set<String> = setOf(
        "什么", "怎么", "这个", "那个", "这些", "那些", "一下", "一点",
        "我们", "你们", "他们", "自己", "现在", "刚才", "然后", "就是",
        "因为", "所以", "如果", "但是", "可能", "应该", "知道", "觉得",
        "时候", "这样", "那样", "一个", "有点", "其实", "反正"
    )

    private val LATIN_STOP_WORDS: Set<String> = setOf(
        "the", "a", "an", "of", "to", "in", "on", "at", "and", "or", "is", "are",
        "was", "were", "be", "been", "am", "i", "you", "he", "she", "it", "we",
        "they", "me", "my", "your", "his", "her", "its", "our", "their", "this",
        "that", "these", "those", "for", "with", "but", "not", "no", "do", "does",
        "did", "so", "if", "then", "than", "as", "by", "from", "have", "has", "had",
        "will", "would", "can", "could", "should", "about", "there", "here"
    )

    /** 「N 天前 / N 周前 / N 个月前 / N 年前」。 */
    private val NUMERIC_TIME =
        Regex("(\\d{1,3}|[一二两三四五六七八九十]{1,3})\\s*(天|日|周|个?星期|个?月|年)前")

    /** 「去年冬天 / 今年的春天 / 前年秋天」。 */
    private val YEAR_WITH_SEASON = Regex("(去年|今年|前年)(?:的)?(春天|夏天|秋天|冬天|春季|夏季|秋季|冬季)?")

    private val LAST_MONTH = Regex("上个?月")
    private val THIS_MONTH = Regex("这个?月|本月|当月")
    private val LAST_WEEK = Regex("上个?星期|上周|上星期")
    private val THIS_WEEK = Regex("这周|本周|这个?星期|这星期")

    /** 「上周三 / 这周五 / 星期三」，group1 是「上/这/本」，group3 是星期几。 */
    private val WEEKDAY_IN_WEEK =
        Regex("(?:(上|这|本)(?:个)?(?:星期|周)|(星期|周))([一二三四五六日天])")

    /** 相对天数：先长后短，避免「大前天」被「前天」抢先。 */
    private val DAY_WORDS: List<Pair<String, Int>> = listOf(
        "大前天" to -3,
        "前天" to -2,
        "昨天" to -1,
        "今天" to 0
    )

    private val RECENT_WORDS = Regex("最近|这两天|这些天|这几天|前几天|前段时间")

    private val SEASON_WORDS = Regex("春天|夏天|秋天|冬天|春季|夏季|秋季|冬季")

    private val WEEKDAY_INDEX: Map<String, Int> = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4,
        "五" to 5, "六" to 6, "日" to 7, "天" to 7
    )

    /** 从正文里抹掉的时间说法：只删带相对参照的，正文里的「冬天怕冷」不受影响。 */
    private val STRIP_PATTERNS: List<Regex> = listOf(
        NUMERIC_TIME,
        Regex("(去年|今年|前年)(?:的)?(?:春天|夏天|秋天|冬天|春季|夏季|秋季|冬季)?"),
        Regex("上个?星期[一二三四五六日天]?"),
        Regex("(上|这|本)(?:个)?(?:星期|周)[一二三四五六日天]?"),
        Regex("上个?月|这个?月|本月|当月"),
        Regex("大前天|前天|昨天|今天|明日|明天|刚刚|刚才|现在"),
        Regex("最近|这两天|这些天|这几天|前几天|前段时间")
    )
}
