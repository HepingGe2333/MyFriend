package com.yap.app.domain

/**
 * 多维心情（`spec/mood/README.md` §1）。
 *
 * 一个词说不清一个人此刻的状态：「累」和「烦」可以都是强度 3，但一个能量低、一个能量高；
 * 「开心」也可能只是轻松，也可能是被压着一件事时的强颜欢笑。所以除了给人看的标签之外，
 * 再加三个维度给模型看：
 *
 * | 维度 | 范围 | 含义 |
 * | --- | --- | --- |
 * | [valence] | -2..2 | 愉快度（负=不悦，正=愉快） |
 * | [arousal] | 0..5 | 唤醒度（0=低能量/困，5=激动） |
 * | [dominance] | 0..5 | 掌控感（0=被压住，5=掌控） |
 *
 * 这个类只做**纯计算**：规整取值、旧数据映射、变化幅度限制、时间衰减、行为提示文案。
 * 读库写库在 [com.yap.app.data.repo.MoodRepository]，让模型判定在
 * [com.yap.app.data.repo.MoodJudgementCoordinator]；两边共用这里的规则，不各写一套。
 *
 * 为什么不能直接复用 `MoodLexicon.MoodWord`：那张表是「一个词 → 两个极值」的展示用词表，
 * 值是 -2..2 的粗极性；而这里的 arousal/dominance 是模型每一轮都要读、要拿来决定语气的
 * 连续量，且必须能表达「同一标签、不同维度」的组合。
 */
data class MoodReading(
    /** 给人看的标签（累/平静/烦/开心…）；空串表示还没读到心情。 */
    val label: String = "",
    /** 强度 1..5（既有列 `moodIntensity`）。 */
    val intensity: Int = 2,
    val valence: Int = 0,
    val arousal: Int = 2,
    val dominance: Int = 3,
    /** 为什么（模型内部用，不直接展示）。 */
    val reason: String = "",
    /** 上次真正变化的时间；时间衰减按它算。 */
    val updatedAt: Long = 0L
) {
    fun isEmpty(): Boolean = label.isBlank()

    /** 转成旧的三元组（界面、心跳曲线、agentcore 的既有接口都还只认它）。 */
    fun toMoodState(): MoodState = MoodState(
        label = label,
        intensity = intensity,
        reason = reason,
        updatedAt = updatedAt
    )

    /** 界面上的一句话：「有点累（3/5）· 今天加班」——与 [MoodState.display] 保持一致。 */
    fun display(): String = toMoodState().display()

    companion object {
        fun of(state: MoodState): MoodReading = MoodDimensions.fromLegacy(
            label = state.label,
            intensity = state.intensity,
            reason = state.reason,
            updatedAt = state.updatedAt
        )
    }
}

object MoodDimensions {

    // ---- 取值范围（规范 §1 的表格，夹取时的唯一真源） -------------------------
    const val VALENCE_MIN = -2
    const val VALENCE_MAX = 2
    const val AROUSAL_MIN = 0
    const val AROUSAL_MAX = 5
    const val DOMINANCE_MIN = 0
    const val DOMINANCE_MAX = 5

    /** 判定结果相对上一轮的**单次**最大变化幅度（规范 §2「单次变化幅度 ≤2」）。 */
    const val MAX_CHANGE = 2

    /**
     * 基准心情的三个维度：平静 = 0（不悦也不愉快）/ 2（不高不低）/ 3（谈不上掌控也谈不上被压住）。
     *
     * 「随时间衰减」和「判定失败」都往这里靠：不是变开心，也不是变难过，只是慢慢回到没什么起伏。
     */
    const val BASELINE_VALENCE = 0
    const val BASELINE_AROUSAL = 2
    const val BASELINE_DOMINANCE = 3

    /** 一小时的毫秒数：衰减按小时走一步。 */
    const val HOUR_MS = 60 * 60 * 1000L

    /**
     * 旧数据的标签 → 愉快度。
     *
     * 规范 §2 的迁移规则：「valence 由 label 映射（累/烦→-1，平静→0，开心→1）」。
     * 词表里有些词本身是 ±2（低落、雀跃），但那是**看不出强度时的粗映射**，
     * 直接取 ±2 会把「一句旧标签」放大成很强烈的情绪；所以这里夹到 -1..1，
     * 让老库升级后的第一轮提示词只是「略偏」而不是「大起大落」，之后由自动判定纠正。
     */
    fun valenceForLabel(raw: String): Int {
        val label = MoodLexicon.normalize(raw)
        if (label == MoodLexicon.BASELINE) return BASELINE_VALENCE
        val known = MoodLexicon.classify(label).valence
        if (known != 0) return known.coerceIn(-1, 1)
        // 词表之外的常见单字/口语词：模型与用户都可能写「累」「烦」「高兴」，
        // 而 MoodLexicon 只收了「有点累 / 心烦」这类带修饰的写法。
        // 这里只给方向，不给强度——旧数据本来就只有一个标签。
        if (NEGATIVE_HINTS.any { label.contains(it) }) return -1
        if (POSITIVE_HINTS.any { label.contains(it) }) return 1
        return BASELINE_VALENCE
    }

    /** 负面口语词（只用于「词表里不认识」时的方向判断，不参与任何强度计算）。 */
    private val NEGATIVE_HINTS = listOf("累", "烦", "困", "难受", "低落", "委屈", "不安", "愧疚", "难过", "丧")

    /** 正面口语词。 */
    private val POSITIVE_HINTS = listOf("开心", "高兴", "快乐", "雀跃", "兴奋", "期待", "轻松", "放松", "温柔", "甜")

    /** 旧的 label/intensity → 多维心情（schema v17 迁移时的映射，读库时也用它兜底）。 */
    fun fromLegacy(
        label: String,
        intensity: Int,
        reason: String = "",
        updatedAt: Long = 0L
    ): MoodReading {
        val word = MoodLexicon.normalize(label)
        val strength = intensity.coerceIn(1, 5)
        if (word == MoodLexicon.BASELINE && label.isBlank()) {
            return MoodReading(
                label = MoodLexicon.BASELINE,
                intensity = strength,
                valence = BASELINE_VALENCE,
                arousal = BASELINE_AROUSAL,
                dominance = BASELINE_DOMINANCE,
                reason = reason,
                updatedAt = updatedAt
            )
        }
        return MoodReading(
            label = word,
            intensity = strength,
            // intensity 5 = 满脑子都是，必定高唤醒；intensity 1 = 淡淡的，通常低唤醒。
            // 「平静」是例外：强度 1 的平静不是「困/没劲」，而是基准的 2。
            valence = valenceForLabel(word),
            arousal = if (word == MoodLexicon.BASELINE) BASELINE_AROUSAL
            else strength.coerceIn(AROUSAL_MIN, AROUSAL_MAX),
            dominance = BASELINE_DOMINANCE,
            reason = reason,
            updatedAt = updatedAt
        )
    }

    /** 把任意来源的值规整到合法范围（模型给 7、-9、空词都兜住）。 */
    fun normalize(
        label: String,
        intensity: Int,
        valence: Int,
        arousal: Int,
        dominance: Int,
        reason: String = "",
        updatedAt: Long = 0L
    ): MoodReading = MoodReading(
        label = MoodLexicon.normalize(label),
        intensity = intensity.coerceIn(1, 5),
        valence = valence.coerceIn(VALENCE_MIN, VALENCE_MAX),
        arousal = arousal.coerceIn(AROUSAL_MIN, AROUSAL_MAX),
        dominance = dominance.coerceIn(DOMINANCE_MIN, DOMINANCE_MAX),
        reason = reason.trim().take(60),
        updatedAt = updatedAt
    )

    /**
     * 限制一次判定的变化幅度：每个维度最多动 [MAX_CHANGE]。
     *
     * 为什么需要它：模型偶尔会把「有点烦」直接写成 valence -2、arousal 5，下一轮语气突然
     * 崩掉会让人以为「他怎么了」。规范 §2 要求单次变化 ≤2，就是让情绪像人一样是连续变化的。
     */
    fun limitChange(previous: MoodReading, next: MoodReading, maxDelta: Int = MAX_CHANGE): MoodReading {
        val limit = maxDelta.coerceAtLeast(0)
        fun step(from: Int, to: Int, min: Int, max: Int): Int =
            to.coerceIn(from - limit, from + limit).coerceIn(min, max)
        return next.copy(
            valence = step(previous.valence, next.valence, VALENCE_MIN, VALENCE_MAX),
            arousal = step(previous.arousal, next.arousal, AROUSAL_MIN, AROUSAL_MAX),
            dominance = step(previous.dominance, next.dominance, DOMINANCE_MIN, DOMINANCE_MAX),
            intensity = step(previous.intensity, next.intensity, 1, 5)
        )
    }

    /**
     * 「没有明显变化就不写」（规范 §2）。
     *
     * 判定每 4 轮就跑一次，但大多数时候他的心情其实没变；这时写库只会白改 `moodUpdatedAt`，
     * 顺带把时间衰减的起点往后推（等于情绪永远不淡）。所以只在标签或任一维度真的不同才算变化。
     * `updatedAt` 不参与比较——那是记账，不是情绪。
     */
    fun isMeaningfulChange(previous: MoodReading, next: MoodReading): Boolean =
        previous.label != next.label ||
            previous.intensity != next.intensity ||
            previous.valence != next.valence ||
            previous.arousal != next.arousal ||
            previous.dominance != next.dominance

    /**
     * 时间衰减：每小时向基准（0 / 2 / 3）靠一步（规范 §2「失败/无配置」的兜底）。
     *
     * 为什么用「一步一步靠」而不是线性插值到基准：情绪回落是人能感觉到的台阶感——
     * 一小时后没那么烦了，两小时后基本平静；如果一路连续插值，任何时刻的值都只是
     * 「上次情绪的一个比例」，模型读到的语气会显得黏糊、不果断。
     *
     * @param hours 已经过去的小时数（负值/0 原样返回；由调用方用 `now - updatedAt` 算）
     */
    fun decay(reading: MoodReading, hours: Long): MoodReading {
        if (hours <= 0L) return reading
        var valence = reading.valence
        var arousal = reading.arousal
        var dominance = reading.dominance
        repeat(hours.coerceAtMost(24L).toInt()) {
            valence = toward(valence, BASELINE_VALENCE)
            arousal = toward(arousal, BASELINE_AROUSAL)
            dominance = toward(dominance, BASELINE_DOMINANCE)
        }
        return reading.copy(valence = valence, arousal = arousal, dominance = dominance)
    }

    /** 从 `now` 到 `updatedAt` 之间过了几个小时（至少 0）。 */
    fun elapsedHours(updatedAt: Long, now: Long): Long =
        ((now - updatedAt).coerceAtLeast(0L)) / HOUR_MS

    /** 往目标走一步（不越过）。 */
    private fun toward(value: Int, target: Int): Int = when {
        value > target -> value - 1
        value < target -> value + 1
        else -> value
    }

    /**
     * 按维度生成 1–2 句行为提示（规范 §3 的 `{按维度生成的 1-2 句行为提示}`）。
     *
     * 只取最该影响语气的两条，按「能量 → 掌控 → 愉快」排序：累的时候先短下来，
     * 被压住的时候先被动一点，最后才轮到「开心可以多说一句」。
     * 这是固定中文（进提示词，不翻译），只在这里写一份，两端如果要对齐也只看这一处。
     */
    fun behaviorHints(reading: MoodReading): List<String> {
        val hints = collectHints(reading)
        // 完全没有可说的维度（平静、既有维度都在基准）也要给一句：提示词里不能出现空的行为提示。
        return hints.ifEmpty { listOf("没什么特别的起伏，语气自然就好。") }
    }

    private fun collectHints(reading: MoodReading): List<String> = buildList {
        if (reading.arousal <= 1) {
            add("能量很低：回复更短、更慢，少主动开新话题，也别硬撑热情。")
        } else if (reading.arousal >= 4) {
            add("有点上头：语气容易急，别连发，也别追问。")
        }
        if (reading.dominance <= 1) {
            add("没什么掌控感：更被动一点，多顺着对方说，少下判断、少替对方安排。")
        } else if (reading.dominance >= 4) {
            add("比较有把握：可以说得笃定一点，但别指挥对方。")
        }
        if (reading.valence <= -1) {
            add("不太愉快：话里可以带一点情绪，但不要卖惨，也别反复解释自己。")
        } else if (reading.valence >= 1) {
            add("心情不错：可以多说一句，语气轻一点，但别油。")
        }
    }.distinct().take(2)

    /**
     * 「你此刻的状态」里的多维心情段（`spec/mood/README.md` §3 的逐项落地）。
     *
     * 规范给的是一个带 `# 你的心情` 标题的段；聊天的系统提示词里这一段并进
     * `# 你此刻的状态`（`spec/persona-guidance/README.md` §1 要求状态段里带心情），
     * 所以这里只产出**正文行**，标题由外层段落提供，避免出现第二个同级标题。
     */
    fun promptLines(reading: MoodReading): List<String> {
        val hints = behaviorHints(reading).joinToString(" ")
        return buildList {
            add(
                "心情：${reading.label}（强度 ${reading.intensity}/5）。" +
                    "愉快度 ${reading.valence} / 唤醒度 ${reading.arousal}/5 / 掌控感 ${reading.dominance}/5。"
            )
            if (reading.reason.isNotBlank()) add("原因：${reading.reason.trim()}")
            add("- 这会影响你怎么说：$hints")
            add("- 不要直接报数值，也不要说「我的情绪是…」；用说话方式体现。")
        }
    }
}
