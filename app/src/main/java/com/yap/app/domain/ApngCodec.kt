package com.yap.app.domain

import java.util.zip.Inflater

/**
 * APNG 读取器（纯 JVM、零第三方依赖）。
 *
 * 为什么自己写：`spec/stickers/README.md` 要求「APNG 也走这条管线」，而 Android 的 `ImageDecoder`
 * 只能把动图交给 `AnimatedImageDrawable` 播——**没有任何公开 API 能逐帧取位图**。
 * APNG 的每一帧本来就是「一段 zlib 压缩的 PNG 扫描行」，用 `java.util.zip.Inflater` 就能解，
 * 所以这里直接按 PNG/APNG 规范解帧，导入时和 GIF 走同一个出口。
 *
 * 支持范围（够用即止，超出范围返回 null，让调用方退回 `ImageDecoder` 的静态首帧）：
 * - 8 bit、colorType 0/2/3/4/6（灰度/RGB/调色板/灰度+alpha/RGBA）；
 * - 非隔行扫描（`interlace = 0`）——表情包几乎不会是 Adam7；
 * - `dispose_op` 0/1/2、`blend_op` 0/1；
 * - 「默认图像是不是第 0 帧」两种写法都认（`fcTL` 在 `IDAT` 之前 / 之后）。
 *
 * 帧时长取 `delay_num / delay_den`（分母 0 按 100 处理，与规范一致）。
 */
object Apng {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /** 是不是 APNG：PNG 签名 + 第一块图像数据之前出现 `acTL`。只看块头，不解码。 */
    fun isApng(bytes: ByteArray): Boolean {
        if (!hasPngSignature(bytes)) return false
        var position = 8
        while (position + 8 <= bytes.size) {
            val length = readInt(bytes, position)
            if (length < 0 || position + 12 + length > bytes.size) return false
            val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
            if (type == "acTL") return true
            if (type == "IDAT") return false
            position += 12 + length
        }
        return false
    }

    fun isPng(bytes: ByteArray): Boolean = hasPngSignature(bytes)

    fun decode(bytes: ByteArray): StickerSource? = runCatching { ApngReader(bytes).read() }.getOrNull()

    private fun hasPngSignature(bytes: ByteArray): Boolean {
        if (bytes.size < SIGNATURE.size) return false
        return SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private class FrameControl(
        val width: Int,
        val height: Int,
        val xOffset: Int,
        val yOffset: Int,
        val delayMs: Int,
        val disposeOp: Int,
        val blendOp: Int
    )

    private class ApngReader(private val bytes: ByteArray) {

        private var width = 0
        private var height = 0
        private var bitDepth = 8
        private var colorType = 6
        private var palette: IntArray = IntArray(0)
        private var paletteAlpha: IntArray = IntArray(0)

        fun read(): StickerSource? {
            if (!hasPngSignature(bytes)) return null
            val header = readHeader() ?: return null
            val rawFrames = collectFrames()
            if (rawFrames.isEmpty()) {
                // 静态 PNG：没有动画，就把默认图像当唯一一帧。
                val pixels = inflate(header.idat.toByteArray(), width, height) ?: return null
                return StickerSource(
                    width = width,
                    height = height,
                    frames = listOf(StickerFrame(pixels, StickerPipeline.normalizeDelay(100)))
                )
            }

            val canvas = IntArray(width * height)
            val frames = mutableListOf<StickerFrame>()
            rawFrames.forEach { frame ->
                val overlay = inflate(frame.data, frame.control.width, frame.control.height)
                    ?: return@forEach
                drawFrame(canvas, overlay, frame.control)
                frames += StickerFrame(canvas.copyOf(), StickerPipeline.normalizeDelay(frame.control.delayMs))
                when (frame.control.disposeOp) {
                    1 -> clearRect(canvas, frame.control)
                    2 -> Unit // PREVIOUS：这里不去恢复上一帧（表情包不会用到，代价不值得）
                }
            }
            if (frames.isEmpty()) return null
            return StickerSource(width, height, frames)
        }

        // ------------------------------------------------------------ 头部

        private class Header(val idat: java.io.ByteArrayOutputStream, val firstFrameControl: FrameControl?)

        /**
         * 第一遍：读 IHDR / PLTE / tRNS / IDAT，并分辨「默认图像是不是第 0 帧」。
         *
         * 规范：如果第一个 `fcTL` 出现在 `IDAT` **之前**，默认图像（IDAT）就是动画的第 0 帧；
         * 否则默认图像只是不支持动画的播放器的静态兜底，动画从透明画布开始。
         */
        private fun readHeader(): Header? {
            var position = 8
            var firstControl: FrameControl? = null
            var sawImageData = false
            val idat = java.io.ByteArrayOutputStream()
            while (position + 8 <= bytes.size) {
                val length = readInt(bytes, position)
                if (length < 0 || position + 12 + length > bytes.size) return null
                val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
                val start = position + 8
                when (type) {
                    "IHDR" -> {
                        if (length < 13) return null
                        width = readInt(bytes, start)
                        height = readInt(bytes, start + 4)
                        bitDepth = bytes[start + 8].toInt() and 0xFF
                        colorType = bytes[start + 9].toInt() and 0xFF
                        val interlace = bytes[start + 12].toInt() and 0xFF
                        if (width <= 0 || height <= 0 || bitDepth != 8 || interlace != 0) return null
                        if (colorType !in intArrayOf(0, 2, 3, 4, 6)) return null
                    }

                    "PLTE" -> {
                        val count = length / 3
                        palette = IntArray(count)
                        for (i in 0 until count) {
                            val r = bytes[start + i * 3].toInt() and 0xFF
                            val g = bytes[start + i * 3 + 1].toInt() and 0xFF
                            val b = bytes[start + i * 3 + 2].toInt() and 0xFF
                            palette[i] = (r shl 16) or (g shl 8) or b
                        }
                    }

                    "tRNS" -> paletteAlpha = IntArray(length) { bytes[start + it].toInt() and 0xFF }

                    "fcTL" -> {
                        if (!sawImageData && firstControl == null) firstControl = parseFrameControl(start, length)
                    }

                    "IDAT" -> {
                        sawImageData = true
                        idat.write(bytes, start, length)
                    }

                    "IEND" -> return Header(idat, firstControl)
                }
                position += 12 + length
            }
            return Header(idat, firstControl)
        }

        // ------------------------------------------------------------ 帧数据

        private class RawFrame(val control: FrameControl, val data: ByteArray)

        /**
         * 第二遍：把「帧控制 + 压缩数据」配对。
         *
         * 必须整段拿到一帧的数据才能 inflate（同一帧可能被拆成多个 `fdAT`），
         * 所以这里只收集、不解码。
         */
        private fun collectFrames(): List<RawFrame> {
            val result = mutableListOf<RawFrame>()
            var position = 8
            var control: FrameControl? = null
            var buffer = java.io.ByteArrayOutputStream()
            var sawImageData = false
            var firstControl: FrameControl? = null
            var idat = java.io.ByteArrayOutputStream()

            fun flush() {
                val current = control
                if (current != null && buffer.size() > 0) {
                    result += RawFrame(current, buffer.toByteArray())
                }
                buffer = java.io.ByteArrayOutputStream()
            }

            while (position + 8 <= bytes.size) {
                val length = readInt(bytes, position)
                if (length < 0 || position + 12 + length > bytes.size) break
                val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
                val start = position + 8
                when (type) {
                    "fcTL" -> {
                        if (!sawImageData) {
                            // 默认图像就是第 0 帧：它的控制块在这里，数据在 IDAT。
                            if (firstControl == null) firstControl = parseFrameControl(start, length)
                        } else if (length >= 26) {
                            flush()
                            control = parseFrameControl(start, length)
                        }
                    }

                    "IDAT" -> {
                        sawImageData = true
                        idat.write(bytes, start, length)
                    }

                    "fdAT" -> {
                        // 前 4 字节是序列号，帧数据从第 5 字节开始。
                        if (length >= 4 && control != null) buffer.write(bytes, start + 4, length - 4)
                    }

                    "IEND" -> {
                        flush()
                        val frames = mutableListOf<RawFrame>()
                        val head = firstControl
                        if (head != null && idat.size() > 0) frames += RawFrame(head, idat.toByteArray())
                        frames += result
                        return frames
                    }
                }
                position += 12 + length
            }
            flush()
            val frames = mutableListOf<RawFrame>()
            val head = firstControl
            if (head != null && idat.size() > 0) frames += RawFrame(head, idat.toByteArray())
            frames += result
            return frames
        }

        private fun parseFrameControl(start: Int, length: Int): FrameControl? {
            if (length < 26) return null
            val delayNum = readShort(bytes, start + 20)
            val delayDen = readShort(bytes, start + 22)
            return FrameControl(
                width = readInt(bytes, start + 4),
                height = readInt(bytes, start + 8),
                xOffset = readInt(bytes, start + 12),
                yOffset = readInt(bytes, start + 16),
                delayMs = delayMs(delayNum, delayDen),
                disposeOp = bytes[start + 24].toInt() and 0xFF,
                blendOp = bytes[start + 25].toInt() and 0xFF
            )
        }

        // ------------------------------------------------------------ 合成

        private fun drawFrame(canvas: IntArray, overlay: IntArray, control: FrameControl) {
            val frameWidth = control.width
            val frameHeight = control.height
            for (y in 0 until frameHeight) {
                val targetY = control.yOffset + y
                if (targetY < 0 || targetY >= height) continue
                for (x in 0 until frameWidth) {
                    val targetX = control.xOffset + x
                    if (targetX < 0 || targetX >= width) continue
                    val src = overlay.getOrElse(y * frameWidth + x) { 0 }
                    val alpha = (src ushr 24) and 0xFF
                    val index = targetY * width + targetX
                    if (control.blendOp == 0) {
                        // SOURCE：整块覆盖（透明也覆盖）
                        canvas[index] = if (alpha == 0) 0 else src
                    } else if (alpha != 0) {
                        // OVER：按 alpha 合成
                        val dst = canvas[index]
                        canvas[index] = if (alpha == 0xFF) src else blendPixel(dst, src, alpha)
                    }
                }
            }
        }

        private fun blendPixel(dst: Int, src: Int, alpha: Int): Int {
            if (alpha == 0xFF) return src
            val a = alpha.toLong()
            val inv = 255L - a
            val dstAlpha = (dst ushr 24) and 0xFF
            val outA = a + dstAlpha * inv / 255L
            val r = (((src shr 16) and 0xFF) * a + ((dst shr 16) and 0xFF) * inv) / 255L
            val g = (((src shr 8) and 0xFF) * a + ((dst shr 8) and 0xFF) * inv) / 255L
            val b = ((src and 0xFF) * a + (dst and 0xFF) * inv) / 255L
            return ((outA.toInt() and 0xFF) shl 24) or
                ((r.toInt() and 0xFF) shl 16) or
                ((g.toInt() and 0xFF) shl 8) or
                (b.toInt() and 0xFF)
        }

        private fun clearRect(canvas: IntArray, control: FrameControl) {
            for (y in 0 until control.height) {
                val targetY = control.yOffset + y
                if (targetY < 0 || targetY >= height) continue
                for (x in 0 until control.width) {
                    val targetX = control.xOffset + x
                    if (targetX < 0 || targetX >= width) continue
                    canvas[targetY * width + targetX] = 0
                }
            }
        }

        // ------------------------------------------------------------ 解压 + 反滤波

        /** zlib 解压 + 反滤波 → ARGB 数组。失败返回 null。 */
        private fun inflate(data: ByteArray, frameWidth: Int, frameHeight: Int): IntArray? {
            if (data.isEmpty() || frameWidth <= 0 || frameHeight <= 0) return null
            val channels = channelsPerPixel(colorType) ?: return null
            val stride = frameWidth * channels
            val expected = (stride + 1) * frameHeight
            val raw = ByteArray(expected)
            val inflater = Inflater()
            try {
                inflater.setInput(data)
                var offset = 0
                while (offset < expected) {
                    val read = inflater.inflate(raw, offset, expected - offset)
                    if (read == 0) {
                        if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break
                    }
                    offset += read
                }
                if (offset < expected) return null
            } catch (_: Exception) {
                return null
            } finally {
                inflater.end()
            }

            val pixels = IntArray(frameWidth * frameHeight)
            var previous = IntArray(stride)
            var current = IntArray(stride)
            var position = 0
            for (y in 0 until frameHeight) {
                val filter = raw[position++].toInt() and 0xFF
                for (i in 0 until stride) {
                    val value = raw[position + i].toInt() and 0xFF
                    val left = if (i >= channels) current[i - channels] else 0
                    val up = previous[i]
                    val upLeft = if (i >= channels) previous[i - channels] else 0
                    current[i] = when (filter) {
                        0 -> value
                        1 -> (value + left) and 0xFF
                        2 -> (value + up) and 0xFF
                        3 -> (value + ((left + up) / 2)) and 0xFF
                        4 -> (value + paeth(left, up, upLeft)) and 0xFF
                        else -> return null
                    }
                }
                position += stride
                for (x in 0 until frameWidth) {
                    pixels[y * frameWidth + x] = pixelAt(current, x)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return pixels
        }

        private fun channelsPerPixel(type: Int): Int? = when (type) {
            0 -> 1
            2 -> 3
            3 -> 1
            4 -> 2
            6 -> 4
            else -> null
        }

        private fun pixelAt(row: IntArray, x: Int): Int = when (colorType) {
            0 -> {
                val v = row[x]
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }

            2 -> {
                val i = x * 3
                (0xFF shl 24) or (row[i] shl 16) or (row[i + 1] shl 8) or row[i + 2]
            }

            3 -> {
                val index = row[x]
                val rgb = palette.getOrElse(index) { 0 }
                val alpha = paletteAlpha.getOrElse(index) { 255 }
                (alpha shl 24) or rgb
            }

            4 -> {
                val i = x * 2
                (row[i + 1] shl 24) or (row[i] shl 16) or (row[i] shl 8) or row[i]
            }

            else -> {
                val i = x * 4
                (row[i + 3] shl 24) or (row[i] shl 16) or (row[i + 1] shl 8) or row[i + 2]
            }
        }

        private fun paeth(left: Int, up: Int, upLeft: Int): Int {
            val p = left + up - upLeft
            val pa = kotlin.math.abs(p - left)
            val pb = kotlin.math.abs(p - up)
            val pc = kotlin.math.abs(p - upLeft)
            return when {
                pa <= pb && pa <= pc -> left
                pb <= pc -> up
                else -> upLeft
            }
        }

        private fun delayMs(delayNum: Int, delayDen: Int): Int {
            val denominator = if (delayDen == 0) 100 else delayDen
            val millis = delayNum * 1000 / denominator
            return if (millis <= 0) 100 else millis
        }

        private fun hasPngSignature(bytes: ByteArray): Boolean {
            if (bytes.size < SIGNATURE.size) return false
            return SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }
        }
    }
}
