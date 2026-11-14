package com.myfriend.app.domain

/**
 * 他此刻的心情。
 *
 * 心情不是装饰：它常驻在提示词里影响语气，也会随时间自己淡下去；
 * 他说过几句话之后，系统会轻轻问他一句「现在我的心情变得……」，让他自己更新。
 */
data class MoodState(
    /** 一个词的标签，如「平静」「有点累」「雀跃」 */
    val label: String = "",
    /** 1..5，越大越浓 */
    val intensity: Int = 2,
    /** 为什么会这样，一句话 */
    val reason: String = "",
    /** 上次真正变化的时间 */
    val updatedAt: Long = 0L
) {
    fun isEmpty(): Boolean = label.isBlank()

    /** 给界面用的一句：「雀跃（4/5）· 因为刚聊到想去的地方」 */
    fun display(): String = if (isEmpty()) {
        MoodLexicon.BASELINE
    } else {
        buildString {
            append(label)
            append("（").append(intensity).append("/5）")
            if (reason.isNotBlank()) append(" · ").append(reason)
        }
    }
}

/** 词表与温和的极性判断，只用来做展示和自动回落，不参与模型决策。 */
object MoodLexicon {

    const val BASELINE = "平静"

    /** 极性：-2 很负面 … 2 很正面；energy：-2 很累 … 2 很有劲。 */
    data class MoodWord(val label: String, val valence: Int, val energy: Int)

    private val KNOWN = listOf(
        MoodWord("平静", 0, 0),
        MoodWord("放松", 1, -1),
        MoodWord("温柔", 1, 0),
        MoodWord("雀跃", 2, 2),
        MoodWord("开心", 2, 1),
        MoodWord("兴奋", 2, 2),
        MoodWord("期待", 1, 1),
        MoodWord("想念", 0, -1),
        MoodWord("有点累", -1, -2),
        MoodWord("疲惫", -1, -2),
        MoodWord("犯困", -1, -2),
        MoodWord("低落", -2, -1),
        MoodWord("难过", -2, -1),
        MoodWord("心烦", -2, 1),
        MoodWord("不安", -1, 1),
        MoodWord("有点委屈", -2, 0),
        MoodWord("愧疚", -1, 0)
    )

    private val BY_LABEL = KNOWN.associateBy { it.label }

    /** 未知词按中性处理，不猜。 */
    fun classify(label: String): MoodWord =
        BY_LABEL[normalize(label)] ?: MoodWord(normalize(label), 0, 0)

    /** 去掉空白与标点，限制长度；空串回落到基准心情。 */
    fun normalize(label: String): String {
        val trimmed = label.trim().replace(Regex("\\s+"), "")
        if (trimmed.isEmpty()) return BASELINE
        return trimmed.take(12)
    }

    /** 是不是「累/困」这类需要他少说话的心情。 */
    fun isLowEnergy(label: String): Boolean = classify(label).energy <= -2

    /** 是不是比较负面、需要被照顾的心情。 */
    fun isNegative(label: String): Boolean = classify(label).valence <= -2
}

/**
 * 心情自己会淡。
 *
 * 人的情绪本来就会回落：强度每小时掉一点，几个小时后回到基准；
 * 只有真的被再次触发时才会重新变浓（由模型通过 set_mood 或周期性引导完成）。
 */
object MoodDrift {

    /** 超过这个时间没有更新，就认为情绪已经散了。 */
    const val FADE_AFTER_MS = 6 * 60 * 60 * 1000L

    /** 每小时掉的强度。 */
    private const val DECAY_PER_HOUR = 1

    fun decay(state: MoodState, now: Long): MoodState {
        if (state.isEmpty()) return state.copy(label = MoodLexicon.BASELINE)
        val age = (now - state.updatedAt).coerceAtLeast(0L)
        if (age >= FADE_AFTER_MS) {
            return MoodState(
                label = MoodLexicon.BASELINE,
                intensity = 1,
                reason = "",
                updatedAt = state.updatedAt
            )
        }
        val hours = (age / 3_600_000L).toInt()
        if (hours <= 0) return state
        val faded = (state.intensity - hours * DECAY_PER_HOUR).coerceAtLeast(1)
        if (faded == state.intensity) return state
        return state.copy(
            intensity = faded,
            reason = if (faded <= 1) "" else state.reason,
            label = if (faded <= 1 && MoodLexicon.isNegative(state.label)) MoodLexicon.BASELINE else state.label
        )
    }

    /** 说过几句话之后该不该回头看一眼自己的心情。 */
    fun shouldReflect(
        state: MoodState,
        spokenSinceUpdate: Int,
        now: Long,
        threshold: Int = 3,
        lastTouchAt: Long = state.updatedAt
    ): Boolean {
        if (spokenSinceUpdate >= threshold) return true
        val age = (now - lastTouchAt).coerceAtLeast(0L)
        // 很久没动过心情、又聊了好一会儿，也该看看了。
        return age >= 4 * 3_600_000L && spokenSinceUpdate >= 1
    }
}

object MoodPrompt {

    /** 常驻在系统提示里的一句。 */
    fun line(state: MoodState): String {
        if (state.isEmpty()) return "\n\n## 你现在的心情\n${MoodLexicon.BASELINE}。没有特别的起伏。"
        val word = MoodLexicon.classify(state.label)
        val intensityText = when (state.intensity) {
            1 -> "很淡"
            2 -> "淡淡的"
            3 -> "明显"
            4 -> "很浓"
            else -> "满脑子都是"
        }
        return buildString {
            append("\n\n## 你现在的心情\n")
            append("${state.label}（强度 ${state.intensity}/5，$intensityText）")
            if (state.reason.isNotBlank()) append("。原因：${state.reason}")
            append("。")
            append(
                when {
                    word.valence <= -2 -> "这种心情会让你说话慢一点、少一点，但不要卖惨，也别反复解释情绪。"
                    word.energy <= -2 -> "这种心情会让你回得更短、更懒，不要硬撑热情。"
                    word.valence >= 2 -> "这种心情会让你话多一点、语气轻一点，但别油。"
                    else -> "语气自然就好，不必特意表现。"
                }
            )
        }
    }

    /**
     * 周期性引导词：说过几句话之后，让他回头看看心情变没变。
     *
     * 刻意写成第一人称的自言自语，不说「请更新情绪状态」这种机械指令。
     */
    fun reflectionNudge(state: MoodState, spokenSinceUpdate: Int): String = buildString {
        append("\n\n## 心里那句话\n")
        append("说了 $spokenSinceUpdate 句话，停一下，问自己一句：现在我的心情变得……？")
        if (state.isEmpty()) {
            append("如果和${MoodLexicon.BASELINE}不一样了，调用 set_mood 记下来。")
        } else {
            append("刚才还是「${state.label}」，现在呢？真的变了就调用 set_mood 更新，没变就别动。")
        }
        append("对方不需要知道你记了心情，语气里自然带出来就行。")
    }
}

/**
 * 被叫醒时的心情恢复。
 *
 * 规格里写得很具体：「强度 -1，若为负面词则改『平静』」。
 * 为什么要有这一条：他是被对方连着几条消息吵醒的，如果醒来还是睡前那股低落/烦躁，
 * 对方会以为自己打扰了他——那比不回复更糟。恢复不是「变开心」，只是「不再带着起床气」。
 *
 * 纯函数：不读库、不写库，方便单测把边界（1 级、负面词、基准心情）逐条钉住。
 */
object MoodRecovery {

    /** 醒来时留在「他最近在想什么」里的一句，给界面看。 */
    const val WAKE_REASON = "被你叫醒了，缓了缓"

    fun afterWake(state: MoodState, now: Long = System.currentTimeMillis()): MoodState {
        val label = if (state.isEmpty() || MoodLexicon.isNegative(state.label)) {
            MoodLexicon.BASELINE
        } else {
            state.label
        }
        val intensity = (state.intensity - 1).coerceAtLeast(1)
        return MoodState(
            label = label,
            intensity = intensity,
            reason = WAKE_REASON,
            updatedAt = now
        )
    }
}
