package com.yap.app.domain

/**
 * 头像照片落盘之前的「预算」：目标边长、压缩质量、体积上限。
 *
 * 这里刻意只保留纯计算，不 import Bitmap / BitmapFactory：
 * 「一张 4000×3000 的照片该变成多大」这件事必须能在普通 JVM 单测里钉死，
 * 而不是等装到模拟器上、看它到底吃了多少内存才发现算错。
 *
 * 512px 的边长的理由：聊天页最大的头像是 92dp（约 250px @2.7x），
 * 512 已经留了 2 倍余量；再大在屏幕上没有区别，只会让每张头像的解码常驻内存翻倍。
 * 200KB 的理由：JPEG 85 下 512px 的人像通常 40–90KB，200KB 是给噪点多的照片留的余量，
 * 同时保证「相册里随手一张 12MP 原图」不会原样塞进 files/。
 */
object AvatarImagePolicy {

    /** 长边最多这么多像素。 */
    const val MAX_EDGE_PX = 512

    /** 首选 JPEG 质量。 */
    const val JPEG_QUALITY = 85

    /** 落盘体积上限：约 200KB。 */
    const val MAX_BYTES = 200 * 1024

    /** 为了压进 [MAX_BYTES] 允许降到的最低质量；再低脸就开始糊了，不如让用户换一张。 */
    const val MIN_QUALITY = 55

    /** 每轮降多少个质量点。 */
    const val QUALITY_STEP = 10

    /** 像素尺寸（宽 × 高），避免为了返回两个 Int 而引入 Android 的 Size。 */
    data class PixelSize(val width: Int, val height: Int) {
        val longestEdge: Int get() = maxOf(width, height)
    }

    /**
     * 等比缩到长边不超过 [maxEdge]。
     *
     * 比目标小的图**不放大**：放大只会变糊，还白占内存。
     * 非法尺寸返回 0×0，调用方据此报「图片读不出来」。
     */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE_PX): PixelSize {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return PixelSize(0, 0)
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return PixelSize(width, height)
        val scale = maxEdge.toDouble() / longest
        return PixelSize(
            width = (width * scale).toInt().coerceAtLeast(1),
            height = (height * scale).toInt().coerceAtLeast(1)
        )
    }

    /**
     * `BitmapFactory.Options.inSampleSize` 的取值。
     *
     * 目标是「解码出来的长边 >= 目标长边，但不过分大」：先按 1/2、1/4、1/8…
     * 粗采样，再用 [targetSize] 做一次精确缩放。这样 4000px 的原图不会先整张解码
     * 成 48MB 再缩——那一下的峰值就是最容易被系统盯上的时刻。
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE_PX): Int {
        val longest = maxOf(width, height)
        if (longest <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /**
     * 压缩质量阶梯：85、75、65、55。
     *
     * 写入方按顺序尝试，第一次落进 [MAX_BYTES] 就停。
     * 最低只到 [MIN_QUALITY]；仍然超限就换一张图，而不是把质量压到 30 换一张糊脸。
     */
    fun qualityLadder(
        initial: Int = JPEG_QUALITY,
        minimum: Int = MIN_QUALITY,
        step: Int = QUALITY_STEP
    ): List<Int> {
        val start = initial.coerceIn(0, 100)
        val floor = minimum.coerceIn(0, start)
        val safeStep = step.coerceAtLeast(1)
        val result = mutableListOf<Int>()
        var quality = start
        while (quality > floor) {
            result += quality
            quality -= safeStep
        }
        result += floor
        return result.distinct()
    }

    /** 这张图能不能收：非空且不超过上限。 */
    fun fits(byteSize: Long, maxBytes: Int = MAX_BYTES): Boolean =
        byteSize in 1..maxBytes.toLong()
}
