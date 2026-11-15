package com.myfriend.agentd.proactive

import com.myfriend.app.domain.MoodDrift
import com.myfriend.app.domain.MoodLexicon
import com.myfriend.app.domain.MoodState as CoreMoodState

/**
 * 服务端的心情：会自己淡，也会被事件推一下。
 *
 * 为什么服务端也要有一份：`spec/agent-core` 里心情只影响「措辞、心跳概率、每日上限」，
 * 而这三件事现在都在服务端发生（客户端没有后台进程）。这个类**不落库** ——
 * 存储层的读写由主线接（`store/`），这里只做纯函数：`MoodState + now` → 新的 `MoodState`。
 *
 * 两条规则：
 * 1. **衰减**完全交给 `:agentcore` 的 `MoodDrift.decay`（每小时 -1，6 小时后回到「平静」/1），
 *    这样服务端与客户端看到的心情不会分成两套算法；
 * 2. **事件**只做很少的几件事（[MoodEvent]）：被回复 → 开心、久没人说话 → 想念、
 *    刚主动说完 → 期待、深夜 → 犯困。同一个事件连着发生只让强度 +1（不叠加到 5 以上）。
 *
 * 标签统一走 `MoodLexicon`：认得的词才有 valence/energy，认不出的词按中性处理，
 * 所以「事件驱动的 label」不会因为写错一个字就把心跳概率放大。
 */
data class MoodState(
    /** 一个词的标签（`平静` / `雀跃` / `低落`…）；空串 = 还没有心情。 */
    val label: String = "",
    /** 1..5，越大越浓。 */
    val intensity: Int = 2,
    /** 上次真正变化的时间（毫秒）；衰减按它算。 */
    val updatedAt: Long = 0L
) {

    fun isEmpty(): Boolean = label.isBlank()

    /** 给 `:agentcore` 的判定（心跳概率、心情修正）用的那一份。 */
    fun core(): CoreMoodState = CoreMoodState(
        label = label,
        intensity = intensity.coerceIn(1, 5),
        reason = "",
        updatedAt = updatedAt
    )

    /**
     * 把心情按时间衰减到 [now]（纯函数，不改自己）。
     *
     * 没有心情时直接回 [BASELINE]（平静/1）：`MoodDrift` 对空心情只补标签、不动强度，
     * 那会让「没有心情」变成「强度 2 的平静」，在 `MoodBehavior` 的阈值判断里产生噪音。
     */
    fun decayed(now: Long): MoodState =
        if (isEmpty()) BASELINE else fromCore(MoodDrift.decay(core(), now))

    /**
     * 发生了一件事之后的心情。
     *
     * 同一个标签再次发生只 +1 强度（上限 5）；换了标签就直接变成新标签的基准强度。
     * `updatedAt` 一律记成 [now]：衰减必须从现在重新开始，否则「刚发生的事」会被当成旧心情。
     */
    fun onEvent(event: MoodEvent, now: Long): MoodState {
        val nextIntensity = if (label == event.label) {
            (intensity + 1).coerceIn(1, 5)
        } else {
            event.intensity.coerceIn(1, 5)
        }
        return MoodState(label = event.label, intensity = nextIntensity, updatedAt = now)
    }

    /** 展示/日志用的标签：没有心情时是「平静」。 */
    fun displayLabel(): String = if (isEmpty()) MoodLexicon.BASELINE else label

    companion object {
        /** 基准心情：平静、强度 1。 */
        val BASELINE: MoodState = MoodState(label = MoodLexicon.BASELINE, intensity = 1)

        /** 从 `:agentcore` 的心情值搬回来（衰减结果、或存储层读出来的值）。 */
        fun fromCore(state: CoreMoodState): MoodState = MoodState(
            label = state.label,
            intensity = state.intensity.coerceIn(1, 5),
            updatedAt = state.updatedAt
        )
    }
}

/**
 * 会推心情的那几件事。
 *
 * 强度都刻意不高（2–3）：事件只是「让心情有个方向」，浓不浓靠 `MoodBehavior` 的阈值（≥3）
 * 决定要不要真的改变行为 —— 也就是说，被回复一次不会立刻让他今天更黏。
 */
enum class MoodEvent(val label: String, val intensity: Int) {
    /** 对方刚说话：开心（正面、有点劲）。 */
    USER_REPLIED("开心", 3),

    /** 很久没人说话（超过心跳爬升期）：想念。 */
    LONG_SILENCE("想念", 3),

    /** 他刚主动说过一句：期待（淡淡的正面）。 */
    PROACTIVE_SENT("期待", 2),

    /** 深夜（0–5 点）：犯困（低能量，会让心跳更安静）。 */
    NIGHT("犯困", 2)
}
