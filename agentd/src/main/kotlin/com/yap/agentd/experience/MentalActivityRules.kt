package com.yap.agentd.experience

import com.yap.agentd.mood.MoodRules
import com.yap.agentd.store.MentalActivity
import com.yap.agentd.store.Mood

/**
 * 心理活动的**规则层**（`spec/experience/README.md` §3）：纯函数、可逐条钉住。
 *
 * 规范数字：
 * - 活跃窗口：最近一条消息在 10 分钟内（或助手刚发出消息）；
 * - 活跃时每 3 分钟或每 3 轮做一次；
 * - **必做**：心情激动（唤醒度 ≥4）或心情变化 ≥2；
 * - 据此微调心情：每一维单次最多动 1 格。
 *
 * 为什么把「触发」单独拆出来：它决定了后台定时器会不会在用户没说话时反复打模型，
 * 也决定了「激动时必做」能不能被测试钉死，而不是埋在一个大 service 的 if 里。
 */
object MentalActivityRules {

    /** 活跃窗口：最近一条消息到这为止（10 分钟）。 */
    const val ACTIVE_WINDOW_MS: Long = 10 * 60 * 1000L

    /** 活跃窗口里的定时触发间隔（3 分钟）。 */
    const val INTERVAL_MS: Long = 3 * 60 * 1000L

    /** 活跃窗口里的轮次触发（每 3 轮）。 */
    const val TURNS: Long = 3L

    /** 唤醒度到这个值就算「激动」，必须做一次心理活动。 */
    const val MANDATORY_AROUSAL: Int = 4

    /** 任意一维变化达到这个幅度就算「变化大」，必须做一次。 */
    const val MANDATORY_MOOD_DELTA: Int = 2

    /** 一次心理活动对心情的微调上限（每一维最多 1 格）。 */
    const val MAX_MOOD_STEP: Int = 1

    /** 活跃窗口：`lastMessageAt` 在 [now] 之前 10 分钟内（或就在此刻之后一点，时钟漂移）。 */
    fun isActiveWindow(lastMessageAt: Long?, now: Long): Boolean {
        if (lastMessageAt == null || lastMessageAt <= 0) return false
        return now - lastMessageAt <= ACTIVE_WINDOW_MS
    }

    /** 当前心情是否「激动」：唤醒度 ≥4。没有心情记录时不算。 */
    fun isAroused(mood: Mood?): Boolean = (mood?.arousal ?: 0) >= MANDATORY_AROUSAL

    /**
     * 相对「上一次心理活动时的心情快照」变化了多少（取各维绝对差的最大值）。
     *
     * 没有快照（第一次做）时返回 0：第一次不该因为「跟基线不同」就算「变化大」。
     */
    fun moodDelta(previous: MentalActivity?, mood: Mood?): Int {
        if (previous == null || mood == null) return 0
        return maxOf(
            kotlin.math.abs(mood.valence - previous.moodValence),
            kotlin.math.abs(mood.arousal - previous.moodArousal),
            kotlin.math.abs(mood.dominance - previous.moodDominance),
            kotlin.math.abs(mood.intensity - previous.moodIntensity)
        )
    }

    /** 「激动 / 变化 ≥2」：满足任意一条就必须做。 */
    fun isMandatory(mood: Mood?, previous: MentalActivity?): Boolean =
        isAroused(mood) || moodDelta(previous, mood) >= MANDATORY_MOOD_DELTA

    /**
     * 这一拍要不要做心理活动。
     *
     * @param lastMessageAt 该人格最近一条消息的时间（活跃窗口）
     * @param lastMentalAt 上次心理活动的时刻；`0` = 还没做过（用 [lastMessageAt] 当基准）
     * @param assistantReplies 累计发出的 assistant 消息条数
     * @param mentalCursor 上次做心理活动时的 [assistantReplies]
     * @param mandatory 「激动 / 变化 ≥2」时必做（不受间隔/轮次限制，但仍要求活跃窗口）
     */
    fun shouldRun(
        now: Long,
        lastMessageAt: Long?,
        lastMentalAt: Long,
        assistantReplies: Long,
        mentalCursor: Long,
        mandatory: Boolean
    ): Boolean {
        if (!isActiveWindow(lastMessageAt, now)) return false
        if (mandatory) return true
        if (assistantReplies - mentalCursor >= TURNS) return true
        val reference = if (lastMentalAt > 0) lastMentalAt else (lastMessageAt ?: now)
        return now - reference >= INTERVAL_MS
    }

    /**
     * 把模型给的心情套到「每维最多动 1 格」的约束里。
     *
     * @param previous 当前心情（`null` = 没有记录，按 [MoodRules.Values.none]）
     * @param proposed 模型给的心情（可能缺维度，`MoodRules.parse` 已经补过默认值）
     */
    fun adjust(previous: Mood?, proposed: MoodRules.Values?): MoodRules.Values {
        val base = previous?.let { MoodRules.Values.from(it) } ?: MoodRules.Values.none()
        if (proposed == null) return base
        fun step(current: Int, wanted: Int, min: Int, max: Int): Int {
            val delta = (wanted - current).coerceIn(-MAX_MOOD_STEP, MAX_MOOD_STEP)
            return (current + delta).coerceIn(min, max)
        }
        return base.copy(
            label = proposed.label.trim().take(MoodRules.MAX_LABEL_CHARS).ifBlank { base.label },
            valence = step(base.valence, proposed.valence.coerceIn(-2, 2), -2, 2),
            arousal = step(base.arousal, proposed.arousal.coerceIn(0, 5), 0, 5),
            dominance = step(base.dominance, proposed.dominance.coerceIn(0, 5), 0, 5),
            intensity = step(base.intensity, proposed.intensity.coerceIn(1, 5), 1, 5)
        )
    }
}
