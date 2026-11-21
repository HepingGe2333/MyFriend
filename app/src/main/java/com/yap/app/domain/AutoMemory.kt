package com.yap.app.domain

/**
 * 高置信自动记忆（0.15）。
 *
 * 为什么需要它：`remember` 是模型自己决定调的，但「我对花生过敏」「我生日是 3 月 5 日」
 * 这类话一旦漏记，下一次他就会忘。提示词里那句「只在正文说『记住了』不算」只能提高概率，
 * 不能保证；这里用**保守的规则**兜底：用户把长期事实说得很明确时，这一轮他又没调用
 * `remember`，就在回复落库后自动写一条 `USER` 记忆。
 *
 * 三条纪律：
 * 1. **只认明确的长期事实**：过敏/忌口、生日、纪念日、说定的约定。闲聊、情绪、临时安排一律不记；
 * 2. **只认第一人称**：「我朋友对花生过敏」「他生日是……」不是「我」的事，不自动记；
 * 3. **宁可不记也别记错**：规则全部是纯函数（不碰 Android、不碰数据库），命中后也只取原句，
 *    不做推断、不补全日期、不把「不能吃太辣」改写成别的意思。
 *
 * 落库由 [com.yap.app.data.repo.MemoryRepository.addAutoMemory] 负责：内容取原句关键片段、
 * importance 4–5、抽屉 `USER`、标签带 [TAG]（记忆抽屉据此显示「自动记下」）。
 * 这里只回答三个问题：该不该记（[detect] / [detectThisTurn]）、是不是已经记过（[isDuplicate]）。
 */
object AutoMemory {

    /**
     * 自动记忆的标签：写进 `memories.tags`，记忆抽屉据此标「自动记下」，日志也用它区分来源。
     *
     * 用小写英文而不是中文：它是**落库值**（会进备份、跨端同步），不是界面文案。
     */
    const val TAG: String = "auto"

    /** 一条自动记忆最多保留原句的多少个字：够看清是什么事，又不至于把整段独白抄进去。 */
    const val MAX_CONTENT_CHARS: Int = 80

    /**
     * 允许自动记的几类长期事实。
     *
     * [importance] 固定 4–5：过敏/生日/纪念日记得越牢越好，约定略低一档。
     * [tag] 与 [TAG] 一起落进 `tags`，方便以后按类型筛（例如「他忌讳什么」）。
     */
    enum class Kind(val tag: String, val importance: Int) {
        ALLERGY("allergy", 5),
        BIRTHDAY("birthday", 5),
        ANNIVERSARY("anniversary", 5),
        PROMISE("promise", 4)
    }

    /** 一次命中：记什么、为什么记、写多重要。 */
    data class Hit(
        val kind: Kind,
        val content: String,
        val tags: List<String> = listOf(TAG, kind.tag)
    ) {
        val importance: Int get() = kind.importance
    }

    /**
     * 规则表：**顺序就是优先级**（过敏 > 生日 > 纪念日 > 约定）。
     *
     * 写成 `(我|本人|咱)(?!朋友|妈|爸|…)` 是为了挡住「我朋友不能吃辣」这种第三人称；
     * `不能吃` 本身很宽，但只有和第一人称出现在同一句里才算「我」的忌口。
     */
    private val RULES: List<Pair<Kind, Regex>> = listOf(
        Kind.ALLERGY to Regex(
            "(我|本人|咱)(?!朋友|朋|妈|爸|哥|姐|弟|妹|家|同事|同学|领导|老师|他|她)" +
                "[^。！？!?\\n]{0,12}(过敏|忌口|不能吃|吃不了|不能喝|不能碰)"
        ),
        Kind.BIRTHDAY to Regex("(我的?生日|我生日|(?:^|[，。！？!?,.;；\\s]|我)生日(?:是|在))"),
        Kind.ANNIVERSARY to Regex("(我们的?纪念日|纪念日(?:是|在|快到了|到了))"),
        Kind.PROMISE to Regex("(重要约定|我们的约定|我答应过你|我答应你|我们说好|我们说定|约好以后)")
    )

    /** 否定说法：命中规则但语义相反时不算（「我不过敏」「我没忌口」）。 */
    private val NEGATIONS = listOf("不过敏", "没过敏", "没有过敏", "不忌口", "没忌口", "不用忌口")

    /**
     * 这句话里有没有值得自动记的长期事实。
     *
     * 返回的 [Hit.content] 是**包含命中的那一句原话**（去多余空白、截到 [MAX_CONTENT_CHARS] 字），
     * 不是模型改写过的总结：宁可留着「对了，我花生过敏」这样的口气，也不要编出更准确的说法。
     */
    fun detect(userMessage: String): Hit? {
        val text = userMessage.trim()
        if (text.isEmpty()) return null
        for ((kind, regex) in RULES) {
            val match = regex.find(text) ?: continue
            // 「我不过敏」这种命中是反的：直接跳过这条规则，继续看下一条。
            if (NEGATIONS.any { match.value.contains(it) }) continue
            val sentence = sentenceAround(text, match.range.first) ?: continue
            if (sentence.isBlank()) continue
            return Hit(kind = kind, content = clipAroundMatch(sentence, match.value))
        }
        return null
    }

    /**
     * 这一轮该不该按规则自动记。
     *
     * 模型已经调用过 `remember` 时返回 null：同一件事不写两条（人已经记了，规则不再兜底）。
     */
    fun detectThisTurn(userMessage: String, rememberCalled: Boolean): Hit? =
        if (rememberCalled) null else detect(userMessage)

    /**
     * 这条内容是不是已经记过了（同一句话/同一件事被再次说出来时不再写第二条）。
     *
     * 判据只有一条：去掉空白与标点后，两条内容互相包含。**不**做模糊相似度：
     * 自动记忆宁可漏掉一次「换个说法的同一件事」，也不要在抽屉里堆两条几乎一样的记忆。
     */
    fun isDuplicate(existing: List<String>, content: String): Boolean {
        val target = normalize(content)
        if (target.length < 3) return false
        return existing.any { raw ->
            val other = normalize(raw)
            other.length >= 3 && (other.contains(target) || target.contains(other))
        }
    }

    /** 记忆抽屉里这条记忆是不是自动写的（tags 里带 [TAG]）。 */
    fun isAuto(tags: List<String>): Boolean = tags.any { it.trim().equals(TAG, ignoreCase = true) }

    /**
     * 太长的句子截一段，但**保证命中的那几个字在片段里**。
     *
     * 直接 `take(80)` 会把「我对花生过敏」这种放在句子末尾的事实切掉——记住一句没有重点的话，
     * 比不记更糟。所以以命中位置为中心取窗口。
     */
    private fun clipAroundMatch(sentence: String, needle: String): String {
        if (sentence.length <= MAX_CONTENT_CHARS) return sentence
        val at = sentence.indexOf(needle).coerceAtLeast(0)
        val latestStart = (sentence.length - MAX_CONTENT_CHARS).coerceAtLeast(0)
        val startAt = (at - MAX_CONTENT_CHARS / 3).coerceIn(0, latestStart)
        return sentence.substring(startAt, (startAt + MAX_CONTENT_CHARS).coerceAtMost(sentence.length))
    }

    /** 取命中位置所在的那一句：按中文/英文句末标点与换行切，句子太长时由 [clipAroundMatch] 截断。 */
    private fun sentenceAround(text: String, index: Int): String? {
        if (index !in text.indices) return null
        var start = index
        while (start > 0 && !SENTENCE_BREAK.contains(text[start - 1])) start--
        var end = index
        while (end < text.length && !SENTENCE_BREAK.contains(text[end])) end++
        return text.substring(start, end).trim().replace(Regex("\\s+"), " ")
    }

    /** 归一化：去空白与常见中英文标点，只留下实际内容用于比较。 */
    private fun normalize(text: String): String = text.filterNot { char ->
        char.isWhitespace() || char in PUNCTUATION
    }

    private val SENTENCE_BREAK: Set<Char> = setOf('。', '！', '？', '!', '?', '；', ';', '\n', '\r')

    private val PUNCTUATION: Set<Char> = setOf(
        '，', '。', '！', '？', '!', '?', ',', '.', '；', ';', '：', ':', '、', '…', '—', '·',
        '（', '）', '(', ')', '“', '”', '‘', '’', '"', '\'', '「', '」', '《', '》'
    )
}
