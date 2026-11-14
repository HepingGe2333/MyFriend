package com.myfriend.app.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 心情落到「行为」上的那一份分量。
 *
 * 心情原本只影响措辞（见 [MoodPrompt]）。但一个人不高兴的时候，不只是说话变短：
 * 他也会想得慢一点、今天愿意主动开口的次数少一点、懒得刷网页。这里把这些「倾向」
 * 算成几个克制的数，交给后台循环和聊天提示词去用。
 *
 * 刻意**不**做成参数表：这里没有开关、没有滑杆、没有「心情强度」的手动入口。
 * 用户看到的只会是「今天他话少」「今晚他好像不太想说话」。
 *
 * 纯函数、不碰 Android / 数据库 / 网络，全部可单测。
 */
data class MoodTuning(
    /** 下次再想一次的间隔乘数：>1 想得更少，<1 更勤快。 */
    val tickIntervalMultiplier: Double = 1.0,
    /** 今天主动开口的次数上限增减（-1..+1）；基准心情是 0。 */
    val dailyMessageCapDelta: Int = 0,
    /** 想不想上网看看：<0.5 就懒得去（0.2..1.5）。 */
    val browseBias: Double = 1.0,
    /** 想不想把自己看到的东西讲给对方听：<0.5 就自己收着（0.2..1.5）。 */
    val shareBias: Double = 1.0,
    /** 一句话交给聊天提示词，很短；基准心情是空串（不写）。 */
    val replyLengthHint: String = "",
    /** 给他自己看的一句内部说明（进 tick 的 summary），基准心情是空串。 */
    val note: String = ""
) {
    /** 心情没把行为改动分毫。 */
    fun isNeutral(): Boolean = this == MoodTuning()
}

/**
 * [MoodState] → [MoodTuning] 的换算表。
 *
 * 原则：宁可少动，也别夸张。基准心情（平静 / 空 / 未知词）完全不做事，
 * 只有真的「有点浓」的心情才动区间的一头。
 */
object MoodBehavior {

    /** 一天里主动开口的下限与上限（心情只能在 [MIN_DAILY_CAP, MAX_DAILY_CAP] 里挪一格）。 */
    const val MIN_DAILY_CAP = 1
    const val MAX_DAILY_CAP = 5

    /** 思考间隔的夹取范围（分钟）：和 AgentLoop 原有的 1..60 分钟一致。 */
    const val MIN_DELAY_MINUTES = 1
    const val MAX_DELAY_MINUTES = 60

    /** 乘数的夹取范围：再低落也不会懒到不动，再雀跃也不会连轴转。 */
    const val MIN_TICK_MULTIPLIER = 0.8
    const val MAX_TICK_MULTIPLIER = 1.6

    /** 低于这个数就不想上网 / 不想主动分享。 */
    const val BROWSE_THRESHOLD = 0.5
    const val SHARE_THRESHOLD = 0.5

    /** 深夜（0..5 点）所有人都会更安静一点。 */
    private const val NIGHT_START_HOUR = 0
    private const val NIGHT_END_HOUR = 5

    /**
     * 心情 → 行为倾向。
     *
     * @param mood 当前心情（已经过时间衰减）
     * @param now 用来判断是不是深夜；只影响「更安静一点」这一档
     */
    fun from(mood: MoodState, now: Long = System.currentTimeMillis()): MoodTuning {
        val label = if (mood.isEmpty()) "" else MoodLexicon.normalize(mood.label)
        val intensity = if (mood.isEmpty()) 1 else mood.intensity.coerceIn(1, 5)
        val word = MoodLexicon.classify(label)

        val base = when {
            // 平静 / 空 / 未知词：不做事。心情不该被硬解释成行为。
            label.isBlank() || label == MoodLexicon.BASELINE -> MoodTuning()

            // 没劲、累、困：想得少、说得少、也不想去网上逛。
            word.energy <= -2 -> MoodTuning(
                tickIntervalMultiplier = 1.2 + 0.1 * (intensity - 1),   // 1.2 ~ 1.6
                dailyMessageCapDelta = -1,
                browseBias = 0.35,
                shareBias = 0.30,
                replyLengthHint = "今天说话短一点，能一句说完就别拖成两句。",
                note = "有点没劲，今天想得少、也说得少"
            )

            // 低落、难过、委屈、心烦：慢一点、少一点，但别卖惨。
            word.valence <= -2 -> MoodTuning(
                tickIntervalMultiplier = 1.3 + 0.1 * (intensity - 1),   // 1.3 ~ 1.7（会被夹到 1.6）
                dailyMessageCapDelta = -1,
                browseBias = 0.40,
                shareBias = 0.35,
                replyLengthHint = "今天话少一点、慢一点，不用硬撑热情。",
                note = "心情低落，今天不太想说话"
            )

            // 想念（valence 0 / energy -1）、不安这类轻负面：只稍微收一点，不值得写进提示词。
            word.valence < 0 || word.energy < 0 -> MoodTuning(
                tickIntervalMultiplier = 1.1,
                browseBias = 0.8,
                shareBias = 0.9
            )

            // 雀跃、开心、兴奋：稍微勤快一点，也更想看、更想说。
            word.valence >= 2 -> MoodTuning(
                tickIntervalMultiplier = if (intensity >= 3) 0.85 else 0.92,
                dailyMessageCapDelta = if (intensity >= 3) 1 else 0,
                browseBias = 1.25,
                shareBias = 1.35,
                replyLengthHint = "今天心情好，话可以多一点，语气轻快些。",
                note = "心情很好，今天话多一点，也想看看外面"
            )

            // 温柔、放松、期待：淡淡的正面，只让节奏轻一点点。
            word.valence > 0 -> MoodTuning(
                tickIntervalMultiplier = 0.95,
                browseBias = 1.1,
                shareBias = 1.15
            )

            // 未知词按中性处理：不猜他到底什么心情，也就不改行为。
            else -> MoodTuning()
        }
        return clamp(applyNight(base, now))
    }

    /** 今夜的这一份安静：谁都睡得更沉，但不是「心情变了」。 */
    private fun applyNight(tuning: MoodTuning, now: Long): MoodTuning {
        if (!isNight(now)) return tuning
        val note = tuning.note.takeIf { it.isNotBlank() }?.plus("，夜里更安静")
        return tuning.copy(
            tickIntervalMultiplier = tuning.tickIntervalMultiplier * 1.15,
            browseBias = tuning.browseBias * 0.7,
            shareBias = tuning.shareBias * 0.7,
            note = note.orEmpty()
        )
    }

    /** 把三处系数夹回各自的安全区间，免得某天出现一个极端心情把循环拖住。 */
    private fun clamp(tuning: MoodTuning): MoodTuning = tuning.copy(
        tickIntervalMultiplier = tuning.tickIntervalMultiplier
            .coerceIn(MIN_TICK_MULTIPLIER, MAX_TICK_MULTIPLIER),
        dailyMessageCapDelta = tuning.dailyMessageCapDelta.coerceIn(-1, 1),
        browseBias = tuning.browseBias.coerceIn(0.2, 1.5),
        shareBias = tuning.shareBias.coerceIn(0.2, 1.5)
    )

    /**
     * 下一次思考的间隔（分钟）。
     *
     * @param baseMinutes 他本来会用的间隔（[1, 60] 之外的输入先夹进来）
     */
    fun adjustDelayMinutes(baseMinutes: Int, tuning: MoodTuning): Int {
        val base = baseMinutes.coerceIn(MIN_DELAY_MINUTES, MAX_DELAY_MINUTES)
        val scaled = (base * tuning.tickIntervalMultiplier).roundToInt()
        return scaled.coerceIn(MIN_DELAY_MINUTES, MAX_DELAY_MINUTES)
    }

    /**
     * 今天主动开口的上限。
     *
     * [baseCap] <= 0 表示用户把主动消息关掉了 —— 心情不能替他打开。
     */
    fun effectiveDailyCap(baseCap: Int, tuning: MoodTuning): Int {
        if (baseCap <= 0) return 0
        return (baseCap + tuning.dailyMessageCapDelta).coerceIn(MIN_DAILY_CAP, MAX_DAILY_CAP)
    }

    /** 今天还想不想自己上网看看。 */
    fun wantsToBrowse(tuning: MoodTuning): Boolean = tuning.browseBias >= BROWSE_THRESHOLD

    /** 今天还想不想把看到的东西主动讲给他听。 */
    fun wantsToShare(tuning: MoodTuning): Boolean = tuning.shareBias >= SHARE_THRESHOLD

    /**
     * 拼进聊天系统提示词的那一行（很短，不污染人格设定）。
     *
     * 基准心情返回空串：不加任何东西。话多话少的硬逻辑不在这里，这里只给语气一个分寸。
     */
    fun promptLine(mood: MoodState, now: Long = System.currentTimeMillis()): String {
        val hint = from(mood, now).replyLengthHint
        if (hint.isBlank()) return ""
        return "\n\n$hint"
    }

    private fun isNight(now: Long): Boolean {
        val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
        return hour in NIGHT_START_HOUR..NIGHT_END_HOUR
    }
}
