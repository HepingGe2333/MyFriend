package com.yap.app.domain

/**
 * 表情包的**压缩参数与策略**（纯 JVM，可单测）。
 *
 * 参数来自 `spec/stickers/README.md`：单文件 ≤512 KB、长边 ≤320 px、帧数 ≤60、总时长 ≤6 s、
 * 帧率 ≤20 fps、调色板 ≤256 色。这里只做「把任意输入压进这组预算」，不碰 Android 的解码与 Canvas：
 *
 * 1. [normalize]：等比缩到长边 ≤320 → 每帧停留 ≥50 ms（= 20 fps 上限）→ 帧数 ≤60（均匀抽帧，
 *    被抽掉的时长并进保留帧，动作快慢不变）→ 总时长 ≤6 s（超了从尾部截断）；
 * 2. [build]：编码一次，超 512 KB 就按「降色 → 降尺寸 → 减帧」的固定阶梯重来，直到压进预算；
 *    阶梯走完还压不进（极端噪声图）才返回 null，调用方报「这张做不成表情」。
 */
object StickerLimits {
    const val MAX_EDGE = 320
    const val MAX_FPS = 20
    const val MAX_FRAMES = 60
    const val MAX_DURATION_MS = 6_000
    const val MAX_COLORS = 256
    const val MAX_BYTES = 512 * 1024
    /** 20 fps 对应的每帧最短停留。 */
    const val MIN_DELAY_MS = 1_000 / MAX_FPS
    /** 静态图导入时给的停留时长（单帧 GIF 也会带一个 delay，很多渲染器对 0 有歧义）。 */
    const val STATIC_DELAY_MS = 100
    /** 降尺寸的最后一道底线：再小就没有表情的意思了。 */
    const val MIN_EDGE = 64
}

/** 一次成功压缩的结果：字节 + 落库要记的元数据。 */
class StickerBuild(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val frames: Int,
    val colors: Int,
    val durationMs: Int
) {
    val byteCount: Int get() = bytes.size
}

object StickerPipeline {

    /**
     * 把任意来源的画面序列规整进规范的形状。
     *
     * 返回 null 表示输入不可用（宽高非正、没有帧、帧像素数不匹配）。
     */
    fun normalize(
        source: StickerSource,
        maxEdge: Int = StickerLimits.MAX_EDGE,
        maxFrames: Int = StickerLimits.MAX_FRAMES,
        maxDurationMs: Int = StickerLimits.MAX_DURATION_MS,
        minDelayMs: Int = StickerLimits.MIN_DELAY_MS
    ): StickerSource? {
        if (source.width <= 0 || source.height <= 0 || source.frames.isEmpty()) return null
        val expected = source.width * source.height
        if (source.frames.any { it.argb.size != expected }) return null

        // 1. 等比缩放到长边上限（小图不放大：放大只会让字节变大、看着更糊）。
        val longest = maxOf(source.width, source.height)
        val resized = if (longest > maxEdge) {
            val scale = maxEdge.toDouble() / longest
            val width = (source.width * scale).toInt().coerceAtLeast(1)
            val height = (source.height * scale).toInt().coerceAtLeast(1)
            StickerSource(width, height, source.frames.map { StickerFrame(scaleArgb(it.argb, source.width, source.height, width, height), it.delayMs) })
        } else {
            source
        }

        // 2. 每帧停留夹到 [minDelayMs, ...]：0/负值按静态图的默认停留处理。
        var frames = resized.frames.map { frame ->
            val delay = if (frame.delayMs <= 0) StickerLimits.STATIC_DELAY_MS else frame.delayMs
            StickerFrame(frame.argb, delay.coerceAtLeast(minDelayMs))
        }

        // 3. 帧数上限：均匀抽帧，被抽掉的帧时长并进保留帧（不然动画会变快）。
        if (frames.size > maxFrames) {
            frames = decimate(frames, (frames.size + maxFrames - 1) / maxFrames)
        }

        // 4. 总时长上限：从尾部截断（表情包的头一两秒才是重点）。
        val total = frames.sumOf { it.delayMs }
        if (total > maxDurationMs) {
            val truncated = mutableListOf<StickerFrame>()
            var acc = 0
            for (frame in frames) {
                if (acc + frame.delayMs > maxDurationMs) break
                truncated += frame
                acc += frame.delayMs
            }
            frames = truncated.ifEmpty { listOf(frames.first()) }
        }

        return StickerSource(resized.width, resized.height, frames)
    }

    /**
     * 规整 + 编码 + 逐级降级，直到落进 [StickerLimits.MAX_BYTES]。
     *
     * 降级阶梯是固定的（不随输入变化），这样「同一张图两次导入得到同样的字节」——
     * 用户不会遇到「刚才还行、再导一次就超限」这种说不清的事。
     */
    fun build(source: StickerSource): StickerBuild? {
        val base = normalize(source) ?: return null
        val attempts = degradeLadder()
        attempts.forEach { attempt ->
            val candidate = applyAttempt(base, attempt) ?: return@forEach
            val bytes = runCatching { StickerGif.encode(candidate, attempt.colors) }.getOrNull() ?: return@forEach
            if (bytes.size <= StickerLimits.MAX_BYTES) {
                return StickerBuild(
                    bytes = bytes,
                    width = candidate.width,
                    height = candidate.height,
                    frames = candidate.frames.size,
                    colors = attempt.colors,
                    durationMs = candidate.durationMs
                )
            }
        }
        return null
    }

    /** 只编码一次（测试与「够小就不要再降」的快路径用）。 */
    fun encodeOnce(source: StickerSource, colors: Int = StickerLimits.MAX_COLORS): ByteArray? =
        runCatching { StickerGif.encode(source, colors) }.getOrNull()

    /**
     * 单帧停留时长的归一：0/负数按静态图默认值处理，再夹到 20 fps 对应的最短停留。
     *
     * 解码器（GIF / APNG）拿到的时长五花八门，统一走这里，免得每处各写一遍夹取。
     */
    fun normalizeDelay(delayMs: Int): Int {
        val raw = if (delayMs <= 0) StickerLimits.STATIC_DELAY_MS else delayMs
        return raw.coerceAtLeast(StickerLimits.MIN_DELAY_MS)
    }

    /** 降级阶梯：颜色 → 尺寸 → 减帧，逐步收紧；最后几档已经是「能看就行」。 */
    internal data class Attempt(val colors: Int, val scale: Double, val frameStep: Int)

    internal fun degradeLadder(): List<Attempt> = listOf(
        Attempt(StickerLimits.MAX_COLORS, 1.0, 1),
        Attempt(128, 1.0, 1),
        Attempt(64, 1.0, 1),
        Attempt(32, 1.0, 1),
        Attempt(128, 0.8, 1),
        Attempt(64, 0.8, 1),
        Attempt(64, 0.6, 1),
        Attempt(128, 0.8, 2),
        Attempt(64, 0.8, 2),
        Attempt(32, 0.6, 2),
        Attempt(32, 0.45, 2),
        Attempt(32, 0.35, 3),
        Attempt(16, 0.25, 4),
        Attempt(8, 0.2, 6)
    )

    /** 应用一档降级；尺寸会夹在 [StickerLimits.MIN_EDGE] 之上（除非原本就更小）。 */
    internal fun applyAttempt(base: StickerSource, attempt: Attempt): StickerSource? {
        var source = base
        if (attempt.scale < 1.0) {
            val longest = maxOf(base.width, base.height)
            val targetLongest = (longest * attempt.scale).toInt()
                .coerceAtLeast(minOf(StickerLimits.MIN_EDGE, longest))
                .coerceAtMost(longest)
            if (targetLongest < longest) {
                val scale = targetLongest.toDouble() / longest
                val width = (base.width * scale).toInt().coerceAtLeast(1)
                val height = (base.height * scale).toInt().coerceAtLeast(1)
                source = StickerSource(
                    width = width,
                    height = height,
                    frames = base.frames.map {
                        StickerFrame(scaleArgb(it.argb, base.width, base.height, width, height), it.delayMs)
                    }
                )
            }
        }
        if (attempt.frameStep > 1 && source.frames.size > 1) {
            val kept = decimate(source.frames, attempt.frameStep)
            if (kept.isEmpty()) return null
            source = StickerSource(source.width, source.height, kept)
        }
        return source.takeIf { it.width > 0 && it.height > 0 && it.frames.isNotEmpty() }
    }

    /**
     * 每 [step] 帧留一帧，并把**被抽掉那一帧的时长并进前一个保留帧**。
     *
     * 顺序很关键：如果把时长并给后一帧，最后一组会因为「后面没有保留帧」而丢掉时长，
     * 于是 150 帧 × 50 ms 抽成 50 帧后总时长会悄悄少 100 ms（动画快了一点点，且很难查）。
     */
    internal fun decimate(frames: List<StickerFrame>, step: Int): List<StickerFrame> {
        if (step <= 1 || frames.size <= 1) return frames
        val kept = mutableListOf<StickerFrame>()
        var index = 0
        while (index < frames.size) {
            var delay = 0
            val head = frames[index]
            // 这一组里所有帧的时长加起来，喂给组内第一帧。
            var cursor = index
            while (cursor < frames.size && cursor < index + step) {
                delay += frames[cursor].delayMs
                cursor++
            }
            kept += StickerFrame(head.argb, delay)
            index += step
        }
        return kept
    }

    /**
     * 盒式（面积平均）缩小。
     *
     * 不用最近邻：表情包缩到 320 以下以后，最近邻会把细线（描边、手指、字）抽成锯齿，
     * 而盒式平均在同等代价下明显更稳。透明像素不参与颜色平均，但会稀释 alpha。
     */
    internal fun scaleArgb(
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): IntArray {
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) return source.copyOf()
        val target = IntArray(targetWidth * targetHeight)
        for (ty in 0 until targetHeight) {
            val y0 = ty * sourceHeight / targetHeight
            val y1 = ((ty + 1) * sourceHeight / targetHeight).coerceAtLeast(y0 + 1).coerceAtMost(sourceHeight)
            for (tx in 0 until targetWidth) {
                val x0 = tx * sourceWidth / targetWidth
                val x1 = ((tx + 1) * sourceWidth / targetWidth).coerceAtLeast(x0 + 1).coerceAtMost(sourceWidth)
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                var opaque = 0L
                for (y in y0 until y1) {
                    val row = y * sourceWidth
                    for (x in x0 until x1) {
                        val argb = source[row + x]
                        val alpha = (argb ushr 24) and 0xFF
                        a += alpha
                        if (alpha >= 0x80) {
                            r += (argb shr 16) and 0xFF
                            g += (argb shr 8) and 0xFF
                            b += argb and 0xFF
                            opaque++
                        }
                    }
                }
                val count = ((y1 - y0) * (x1 - x0)).coerceAtLeast(1)
                val alpha = (a / count).toInt()
                target[ty * targetWidth + tx] = if (opaque == 0L) {
                    0
                } else {
                    ((alpha and 0xFF) shl 24) or
                        (((r / opaque).toInt() and 0xFF) shl 16) or
                        (((g / opaque).toInt() and 0xFF) shl 8) or
                        ((b / opaque).toInt() and 0xFF)
                }
            }
        }
        return target
    }
}
