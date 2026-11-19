package com.yap.agentd.delivery

import com.yap.agentd.store.Mood
import kotlin.math.roundToLong

/**
 * 「打字延迟」的唯一实现（`spec/experience` §5 / §8）。
 *
 * ```
 * visibleAt = now + clamp(0.4 + 字数 × 0.025 + 心情修正, 0.4, 4.0) 秒
 * ```
 *
 * 心情修正（§5「低唤醒/累 → 更慢，高唤醒 → 更快」）：
 * - 唤醒度 ≥ 4（兴奋/激动）：−0.4 秒；
 * - 唤醒度 ≤ 1（很困/很低落）：+0.5 秒；
 * - 其余不加不减；
 * - 不愉快（valence < 0）再 +0.2 秒：心情差的时候打字也会慢一点。
 *
 * 纯函数、不读时钟：客户端与服务端算的是同一件事，两边都可以拿它做单测。
 */
object TypingDelay {

    /** 公式下限/上限（秒）。 */
    const val MIN_SECONDS = 0.4
    const val MAX_SECONDS = 4.0

    /** 固定起手 + 每个字符的增量。 */
    const val BASE_SECONDS = 0.4
    const val SECONDS_PER_CHAR = 0.025

    /** 延迟（秒），夹在 [MIN_SECONDS]..[MAX_SECONDS]。 */
    fun seconds(text: String, mood: Mood?): Double {
        val chars = text.trim().length
        val raw = BASE_SECONDS + chars * SECONDS_PER_CHAR + moodAdjustment(mood)
        return raw.coerceIn(MIN_SECONDS, MAX_SECONDS)
    }

    /** 延迟（毫秒）；至少 [MIN_SECONDS]。 */
    fun millis(text: String, mood: Mood?): Long =
        (seconds(text, mood) * 1000.0).roundToLong().coerceAtLeast((MIN_SECONDS * 1000).roundToLong())

    /** 心情修正（秒）。没有心情记录时按「平静」处理。 */
    fun moodAdjustment(mood: Mood?): Double {
        if (mood == null) return 0.0
        var adjust = when {
            mood.arousal >= 4 -> -0.4
            mood.arousal <= 1 -> 0.5
            else -> 0.0
        }
        if (mood.valence < 0) adjust += 0.2
        return adjust
    }
}

/**
 * 「等到某个时刻」的抽象。
 *
 * 生产是 [REAL]（`Thread.sleep`）；测试注入一个只记录目标并推进假时钟的实现，
 * 所以时序测试既不需要真的等 4 秒、也能断言「第几条在什么时刻发」。
 */
fun interface Sleeper {
    fun sleepUntil(epochMillis: Long)

    companion object {
        /** 真的睡到那个时刻（已经过了就立即返回）。 */
        val REAL: Sleeper = Sleeper { target ->
            val wait = target - System.currentTimeMillis()
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }

        /** 什么都不等（测试/关闭延迟时用）。 */
        val NONE: Sleeper = Sleeper { }
    }
}
