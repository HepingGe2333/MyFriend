package com.yap.app.util

import kotlin.math.roundToLong

/**
 * 「他不是机器人」的时间感。
 *
 * 一条消息发出去到第一个字出现之间，故意留一段读消息的时间：按字数估算，
 * 夹在 0.5–4.5 秒之间并加一点抖动。1.9 删掉了「读消息节奏」的设置项之后，
 * 这个系数固定为自然速度（1.0）；想不想回、多久回，交给模型的 defer_reply 与睡眠判定。
 * 图片比文字更难「看」，所以每张图片额外加一点时间。
 */
object HumanTiming {

    const val BASE_MS = 450L
    const val PER_CHAR_MS = 50L
    const val PER_IMAGE_MS = 600L
    const val MIN_MS = 500L
    const val MAX_MS = 4_500L

    /** 编辑重发时他已经知道上下文，只需要很短的反应时间。 */
    const val REGENERATE_MS = 380L

    /**
     * @param textLength 这条消息的字符数
     * @param images 这条消息的图片数
     * @param paceFactor 0 表示立刻回复；1 为自然；更大的值更慢
     * @param jitter 0.15 表示 ±15% 的随机抖动，测试里传 0
     */
    fun readDelayMillis(
        textLength: Int,
        images: Int,
        paceFactor: Float,
        jitter: Float = 0.15f,
        random: () -> Float = { Math.random().toFloat() }
    ): Long {
        if (paceFactor <= 0f) return 0L
        val raw = BASE_MS + textLength.coerceAtLeast(0) * PER_CHAR_MS + images.coerceAtLeast(0) * PER_IMAGE_MS
        val clamped = raw.coerceIn(MIN_MS, MAX_MS).toFloat()
        val shaken = clamped * (1f + (random() * 2f - 1f) * jitter.coerceIn(0f, 1f))
        return (shaken * paceFactor).roundToLong().coerceAtLeast(0L)
    }
}
