package com.yap.agentd.sticker

import java.io.ByteArrayOutputStream

/**
 * 表情包的 **GIF89a 编解码器**（纯 JVM、零第三方依赖）。
 * 0.14.1：从 app/src/main/java/com/yap/app/domain/StickerGif.kt 逐字移植（只改 package），两端共用同一份 GIF89a 编解码算法。
 *
 * 为什么自己写：`spec/stickers/README.md` 要求「输出 GIF89a、调色板 ≤256 色、自己写 LZW/调色板编码器」。
 * Android 的 `Bitmap.compress` 没有 GIF 编码器（只有 JPEG/PNG/WebP），系统也没有公开的动画 GIF 写入口；
 * 引第三方库又与「本轮不新增依赖」冲突。所以这里实现最小的 GIF89a 写入器 + 一个够用的读取器
 * （读取器只服务三件事：导入兜底、`overlayStickerId` 叠图、单元测试的「解码-编码往返」）。
 *
 * 编码策略（有意取简单的一档）：
 * - **整段一张全局调色板**（不做逐帧局部调色板）：表情包大多是纯色/线条/大字，逐帧调色板省不了多少字节，
 *   却要多一份「每帧调色板不同」的解析分支；
 * - **有透明像素时**预留索引 0 给透明色，其余 255 色交给中位切分（median cut）量化；
 * - 逐帧按 `disposal = 2`（有透明）或 `1`（全不透明）擦除，帧之间不会留下残影；
 * - LZW 每帧独立编码，`minCodeSize = max(2, 调色板位数)`，码表满 4096 就重发 clear code。
 *
 * 性能：调色板直方图**按步长采样**（最多约 6.5 万个样本），映射走一张固定大小的哈希缓存，
 * 所以 320×320×60 帧（约 610 万像素）也不会把内存/时间拖爆——这是「导一张表情包要等几秒」的那几秒。
 */
data class StickerFrame(val argb: IntArray, val delayMs: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StickerFrame) return false
        return delayMs == other.delayMs && argb.contentEquals(other.argb)
    }

    override fun hashCode(): Int = 31 * argb.contentHashCode() + delayMs
}

/** 一段可编码的画面序列：整个序列共用宽高，每帧带自己的停留时长（毫秒）。 */
data class StickerSource(val width: Int, val height: Int, val frames: List<StickerFrame>) {
    val isAnimated: Boolean get() = frames.size > 1
    val durationMs: Int get() = frames.sumOf { it.delayMs }
    val pixelCount: Int get() = width * height
}

object StickerGif {

    /** GIF 的硬上限：8 bit 调色板。 */
    const val MAX_COLORS = 256

    /** alpha 低于它就算全透明（GIF 只有「全透/全不透」两档，没有半透明）。 */
    private const val ALPHA_CUTOFF = 0x80

    /** 直方图最多采这么多像素：再多也不会让 256 色更准，只会更慢。 */
    private const val HISTOGRAM_SAMPLES = 65_536

    /** 一帧的停留时长写进 GIF 时以 1/100 秒为单位；0 会被很多渲染器当成 100ms，所以至少给 2。 */
    private const val MIN_DELAY_CENTIS = 2

    /**
     * 编码成 GIF89a 字节。
     *
     * @param maxColors 调色板上限（含透明色）；超出会被夹到 2..256。降色阶就是靠它。
     * @throws IllegalArgumentException 宽高非法 / 没有帧 / 帧像素数与宽高不符。
     */
    fun encode(source: StickerSource, maxColors: Int = MAX_COLORS): ByteArray {
        require(source.width > 0 && source.height > 0) { "sticker size must be positive" }
        require(source.frames.isNotEmpty()) { "sticker needs at least one frame" }
        val pixels = source.pixelCount
        source.frames.forEachIndexed { index, frame ->
            require(frame.argb.size == pixels) { "frame $index has ${frame.argb.size} pixels, expected $pixels" }
        }

        val transparent = source.frames.any { frame -> frame.argb.any { alphaOf(it) < ALPHA_CUTOFF } }
        val colorBudget = ((if (transparent) maxColors - 1 else maxColors)).coerceIn(2, MAX_COLORS)
        val palette = StickerPalette.build(source.frames, colorBudget)
        // 透明色占索引 0；调色板整体后移一位。
        val offset = if (transparent) 1 else 0
        val transparentIndex = if (transparent) 0 else -1
        // GIF 的全局调色板必须是 2 的幂、且至少 2 项（规范下限）：纯色图只有 1 个颜色时也要补齐，
        // 否则读者会按 packed 里声明的 2 项去读、只读到 1 项，后面整段错位。
        val tableEntries = 1 shl tableSizeBits(palette.size + offset)
        val mapper = ColorMapper(palette, offset, transparentIndex)

        val out = ByteArrayOutputStream(64 * 1024)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeLogicalScreenDescriptor(out, source.width, source.height, tableEntries, transparentIndex)
        writeGlobalColorTable(out, palette, offset, tableEntries)
        if (source.frames.size > 1) writeLoopExtension(out)

        val disposal = if (transparent) 2 else 1
        val indices = IntArray(pixels)
        source.frames.forEach { frame ->
            writeGraphicControlExtension(out, frame.delayMs, disposal, transparentIndex)
            writeImageDescriptor(out, source.width, source.height)
            for (i in 0 until pixels) indices[i] = mapper.index(frame.argb[i])
            LzwEncoder.write(out, indices, minCodeSizeFor(tableEntries))
        }
        out.write(0x3B) // trailer
        return out.toByteArray()
    }

    /**
     * 解码成 [StickerSource]；不是 GIF / 数据截断时返回 null（绝不抛异常）。
     *
     * 支持：全局/局部调色板、透明色、`disposal` 0/1/2/3、交错扫描、NETSCAPE 循环扩展（忽略循环次数）。
     */
    fun decode(bytes: ByteArray): StickerSource? = runCatching { GifReader(bytes).read() }.getOrNull()

    // ------------------------------------------------------------ 文件头/块

    private fun writeLogicalScreenDescriptor(
        out: ByteArrayOutputStream,
        width: Int,
        height: Int,
        tableEntries: Int,
        backgroundIndex: Int
    ) {
        writeShort(out, width)
        writeShort(out, height)
        val bits = tableSizeBits(tableEntries)
        // 0x80 = 有全局调色板；0x70 = 每通道 8 bit；低三位 = 调色板大小 2^(n+1)
        out.write(0x80 or 0x70 or (bits - 1))
        out.write(if (backgroundIndex >= 0) backgroundIndex else 0)
        out.write(0) // 像素宽高比：0 = 未指定
    }

    private fun writeGlobalColorTable(
        out: ByteArrayOutputStream,
        palette: IntArray,
        offset: Int,
        tableSize: Int
    ) {
        // 透明槽位（offset=1 时）写黑，读到 transparentIndex 的像素不会用它。
        if (offset == 1) {
            out.write(0)
            out.write(0)
            out.write(0)
        }
        palette.forEach { rgb ->
            out.write((rgb shr 16) and 0xFF)
            out.write((rgb shr 8) and 0xFF)
            out.write(rgb and 0xFF)
        }
        // 补齐到 2 的幂：多出来的表项写 0。
        var written = palette.size + offset
        while (written < tableSize) {
            out.write(0)
            out.write(0)
            out.write(0)
            written++
        }
    }

    private fun writeLoopExtension(out: ByteArrayOutputStream) {
        out.write(0x21)
        out.write(0xFF)
        out.write(0x0B)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(0x03)
        out.write(0x01)
        writeShort(out, 0) // 0 = 无限循环
        out.write(0)
    }

    private fun writeGraphicControlExtension(
        out: ByteArrayOutputStream,
        delayMs: Int,
        disposal: Int,
        transparentIndex: Int
    ) {
        val centis = (delayMs / 10).coerceAtLeast(MIN_DELAY_CENTIS).coerceAtMost(65_535)
        out.write(0x21)
        out.write(0xF9)
        out.write(0x04)
        val packed = ((disposal and 0x07) shl 2) or (if (transparentIndex >= 0) 1 else 0)
        out.write(packed)
        writeShort(out, centis)
        out.write(if (transparentIndex >= 0) transparentIndex else 0)
        out.write(0)
    }

    private fun writeImageDescriptor(out: ByteArrayOutputStream, width: Int, height: Int) {
        out.write(0x2C)
        writeShort(out, 0)
        writeShort(out, 0)
        writeShort(out, width)
        writeShort(out, height)
        out.write(0) // 无局部调色板、非交错
    }

    /** LZW 的最小码长：调色板位数，但 GIF 规定不得小于 2。 */
    private fun minCodeSizeFor(tableSize: Int): Int = tableSizeBits(tableSize).coerceAtLeast(2)

    private fun tableSizeBits(tableSize: Int): Int {
        var bits = 1
        while ((1 shl bits) < tableSize.coerceAtLeast(2)) bits++
        return bits
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    private fun alphaOf(argb: Int): Int = (argb ushr 24) and 0xFF

    // ------------------------------------------------------------ 调色板 + 映射

    /**
     * 调色板构建：颜色数不超预算时**原样保留**每个颜色（纯色/文字表情不会有任何色差），
     * 超了才走中位切分。
     */
    private object StickerPalette {

        fun build(frames: List<StickerFrame>, budget: Int): IntArray {
            val histogram = histogramOf(frames)
            if (histogram.isEmpty()) return intArrayOf(0)
            if (histogram.size <= budget) {
                // 按亮度排序，让相邻索引的颜色也相邻（LZW 更容易命中，纯属锦上添花）。
                return histogram.keys.sortedBy { luminance(it) }.toIntArray()
            }
            return medianCut(histogram, budget)
        }

        /**
         * 采样统计颜色出现次数。
         *
         * 用步长采样而不是全量：610 万像素全量收集会得到几十万个不同颜色，
         * 而 256 色调色板本来就要丢掉它们中的绝大多数。
         */
        private fun histogramOf(frames: List<StickerFrame>): Map<Int, Int> {
            val totalPixels = frames.size.toLong() * (frames.first().argb.size.toLong())
            val stride = (totalPixels / HISTOGRAM_SAMPLES).coerceAtLeast(1L).toInt()
            val counts = HashMap<Int, Int>(1024)
            frames.forEach { frame ->
                var index = 0
                while (index < frame.argb.size) {
                    val argb = frame.argb[index]
                    if (alphaOf(argb) >= ALPHA_CUTOFF) {
                        val rgb = argb and 0x00FFFFFF
                        counts[rgb] = (counts[rgb] ?: 0) + 1
                    }
                    index += stride
                }
            }
            return counts
        }

        private class Entry(val rgb: Int, val count: Int)

        private class Box(val start: Int, val end: Int) {
            val size: Int get() = end - start
        }

        private fun medianCut(histogram: Map<Int, Int>, budget: Int): IntArray {
            val entries = histogram.entries
                .map { Entry(it.key, it.value) }
                .toMutableList()
            val boxes = mutableListOf(Box(0, entries.size))
            while (boxes.size < budget) {
                val target = boxes.filter { it.size > 1 }.maxByOrNull { spread(entries, it) } ?: break
                val split = splitBox(entries, target)
                boxes.remove(target)
                boxes.add(Box(target.start, split))
                boxes.add(Box(split, target.end))
            }
            return boxes.map { averageOf(entries, it) }.toIntArray()
        }

        /** 一个盒子里「最长的那个通道」的取值范围 × 像素数：切分优先级。 */
        private fun spread(entries: MutableList<Entry>, box: Box): Long {
            var minR = 255
            var maxR = 0
            var minG = 255
            var maxG = 0
            var minB = 255
            var maxB = 0
            var weight = 0L
            for (i in box.start until box.end) {
                val rgb = entries[i].rgb
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (b < minB) minB = b
                if (b > maxB) maxB = b
                weight += entries[i].count
            }
            val spread = maxOf(maxR - minR, maxG - minG, maxB - minB)
            return spread.toLong() * weight.coerceAtLeast(1L)
        }

        /** 沿最大跨度通道排序，再按「累计像素数过半」的位置切开。 */
        private fun splitBox(entries: MutableList<Entry>, box: Box): Int {
            var minR = 255
            var maxR = 0
            var minG = 255
            var maxG = 0
            var minB = 255
            var maxB = 0
            var weight = 0L
            for (i in box.start until box.end) {
                val rgb = entries[i].rgb
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (b < minB) minB = b
                if (b > maxB) maxB = b
                weight += entries[i].count
            }
            val shift = when {
                maxR - minR >= maxG - minG && maxR - minR >= maxB - minB -> 16
                maxG - minG >= maxB - minB -> 8
                else -> 0
            }
            entries.subList(box.start, box.end).sortBy { (it.rgb shr shift) and 0xFF }
            val half = weight / 2
            var acc = 0L
            for (i in box.start until box.end - 1) {
                acc += entries[i].count
                if (acc >= half) return (i + 1).coerceIn(box.start + 1, box.end - 1)
            }
            return (box.start + box.end) / 2
        }

        /** 盒子的代表色：按出现次数加权平均。 */
        private fun averageOf(entries: MutableList<Entry>, box: Box): Int {
            var r = 0L
            var g = 0L
            var b = 0L
            var weight = 0L
            for (i in box.start until box.end) {
                val entry = entries[i]
                val count = entry.count.toLong()
                r += ((entry.rgb shr 16) and 0xFF).toLong() * count
                g += ((entry.rgb shr 8) and 0xFF).toLong() * count
                b += (entry.rgb and 0xFF).toLong() * count
                weight += count
            }
            if (weight <= 0L) return 0
            return (((r / weight).toInt() and 0xFF) shl 16) or
                (((g / weight).toInt() and 0xFF) shl 8) or
                ((b / weight).toInt() and 0xFF)
        }

        private fun luminance(rgb: Int): Int {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return 299 * r + 587 * g + 114 * b
        }
    }

    /**
     * RGB → 调色板索引的映射。
     *
     * 缓存是**固定大小的开放寻址表**（键 = 24 位 RGB，值 = 索引 + 1，0 表示空）：
     * 逐像素走 HashMap 会把 610 万次装箱压在这一步上，而这里只对「没见过的颜色」做 256 次线性最近搜索。
     */
    private class ColorMapper(
        private val palette: IntArray,
        private val offset: Int,
        private val transparentIndex: Int
    ) {
        private val cacheBits = 14
        private val cacheKeys = IntArray(1 shl cacheBits) { -1 }
        private val cacheValues = IntArray(1 shl cacheBits)
        private val mask = (1 shl cacheBits) - 1

        fun index(argb: Int): Int {
            if (transparentIndex >= 0 && ((argb ushr 24) and 0xFF) < ALPHA_CUTOFF) return transparentIndex
            val rgb = argb and 0x00FFFFFF
            var slot = (rgb xor (rgb ushr 12)) and mask
            var probes = 0
            while (probes <= mask) {
                val key = cacheKeys[slot]
                if (key == rgb) return cacheValues[slot] + offset
                if (key == -1) {
                    val resolved = nearest(rgb)
                    cacheKeys[slot] = rgb
                    cacheValues[slot] = resolved
                    return resolved + offset
                }
                slot = (slot + 1) and mask
                probes++
            }
            return nearest(rgb) + offset
        }

        private fun nearest(rgb: Int): Int {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            var best = 0
            var bestDistance = Int.MAX_VALUE
            for (i in palette.indices) {
                val pr = (palette[i] shr 16) and 0xFF
                val pg = (palette[i] shr 8) and 0xFF
                val pb = palette[i] and 0xFF
                val dr = r - pr
                val dg = g - pg
                val db = b - pb
                // 人眼对绿更敏感：2/4/3 的加权是常见的一档。
                val distance = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                    if (distance == 0) break
                }
            }
            return best
        }
    }

    // ------------------------------------------------------------ LZW

    /**
     * GIF 的变长 LZW。
     *
     * 字典用一张 `(前缀 << 8) | 当前字节` → code 的定长表（4096×256 = 4MB IntArray）：
     * 每帧重填一遍虽然要 memset 一次 4MB，但比 600 万次 HashMap 查找快一个量级，
     * 而且这张表只在编码期间存在，编码结束就可回收。
     */
    private class LzwEncoder(
        private val out: ByteArrayOutputStream,
        private val minCodeSize: Int
    ) {
        private val clearCode = 1 shl minCodeSize
        private val endCode = clearCode + 1
        private var codeSize = minCodeSize + 1
        private var nextCode = endCode + 1
        private var dictionary = IntArray(DICTIONARY_SIZE) { -1 }
        private var bitBuffer = 0L
        private var bitCount = 0
        private val block = ByteArray(255)
        private var blockLength = 0

        fun encode(indices: IntArray) {
            writeCode(clearCode)
            var prefix = indices[0]
            for (i in 1 until indices.size) {
                val value = indices[i]
                val key = (prefix shl 8) or value
                val existing = dictionary[key]
                if (existing >= 0) {
                    prefix = existing
                    continue
                }
                writeCode(prefix)
                if (nextCode < MAX_CODE) {
                    dictionary[key] = nextCode++
                    // 码表长过当前位宽能表示的范围就加一位（12 位封顶）。
                    //
                    // 这里的比较是「>」，不是「>=」：解码器永远比编码器**少一格**（它要等下一个码
                    // 才知道这一条的后缀），所以编码器必须晚一格切位宽，两边才在同一个位置换宽度。
                    // 写反了的表现是「小图正常、一过 500 来个字典项就整帧花掉」。
                    if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    writeCode(clearCode)
                    dictionary = IntArray(DICTIONARY_SIZE) { -1 }
                    codeSize = minCodeSize + 1
                    nextCode = endCode + 1
                }
                prefix = value
            }
            writeCode(prefix)
            writeCode(endCode)
            flushBits()
            flushBlock()
            out.write(0) // 结束块
        }

        private fun writeCode(code: Int) {
            bitBuffer = bitBuffer or (code.toLong() shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                pushByte((bitBuffer and 0xFF).toInt())
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        private fun flushBits() {
            while (bitCount > 0) {
                pushByte((bitBuffer and 0xFF).toInt())
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
            bitCount = 0
        }

        private fun pushByte(value: Int) {
            block[blockLength++] = value.toByte()
            if (blockLength == 255) flushBlock()
        }

        private fun flushBlock() {
            if (blockLength == 0) return
            out.write(blockLength)
            out.write(block, 0, blockLength)
            blockLength = 0
        }

        companion object {
            private const val MAX_CODE = 4096
            private const val DICTIONARY_SIZE = 4096 * 256

            fun write(out: ByteArrayOutputStream, indices: IntArray, minCodeSize: Int) {
                out.write(minCodeSize)
                if (indices.isEmpty()) {
                    out.write(0)
                    return
                }
                LzwEncoder(out, minCodeSize).encode(indices)
            }
        }
    }

    // ------------------------------------------------------------ 读取

    /**
     * GIF89a 读取器。
     *
     * 只做「够用」的实现：不吃应用扩展块（除 GCE）、不理会循环次数、不支持自由裁切，
     * 但支持交错扫描与三种 disposal——因为要读的可能是用户从别处存下来的 GIF，
     * 而不只是我们自己写出去的那一种。
     */
    private class GifReader(private val bytes: ByteArray) {

        private var position = 0
        private var screenWidth = 0
        private var screenHeight = 0
        private var globalPalette: IntArray = IntArray(0)
        private var transparentIndex = -1

        fun read(): StickerSource? {
            if (bytes.size < 13) return null
            if (readAscii(6) != "GIF89a" && !isGif87()) return null
            screenWidth = readShort()
            screenHeight = readShort()
            if (screenWidth <= 0 || screenHeight <= 0) return null
            val packed = readByte()
            position++ // 背景色索引
            position++ // 像素宽高比
            if (packed and 0x80 != 0) {
                val size = 1 shl ((packed and 0x07) + 1)
                globalPalette = readPalette(size)
            }

            val frames = mutableListOf<StickerFrame>()
            var canvas = IntArray(screenWidth * screenHeight)
            var frameDelay = 100
            var disposal = 0
            var frameTransparent = -1

            while (position < bytes.size) {
                when (val block = readByte()) {
                    0x21 -> {
                        val label = readByte()
                        if (label == 0xF9) {
                            val size = readByte()
                            val flags = readByte()
                            disposal = (flags shr 2) and 0x07
                            val delayCentis = readShort()
                            val tIndex = readByte()
                            frameTransparent = if (flags and 0x01 != 0) tIndex else -1
                            frameDelay = (delayCentis.coerceAtLeast(MIN_DELAY_CENTIS) * 10)
                            // 跳过剩下的子块（GCE 只有一个终止 0，但别的块不一定）
                            repeat((size - 4).coerceAtLeast(0)) { position++ }
                            skipSubBlocks()
                        } else {
                            skipSubBlocks()
                        }
                    }

                    0x2C -> {
                        val left = readShort()
                        val top = readShort()
                        val width = readShort()
                        val height = readShort()
                        if (width <= 0 || height <= 0) return null
                        val imagePacked = readByte()
                        val localPalette = if (imagePacked and 0x80 != 0) {
                            readPalette(1 shl ((imagePacked and 0x07) + 1))
                        } else {
                            globalPalette
                        }
                        val interlaced = imagePacked and 0x40 != 0
                        val minCodeSize = readByte()
                        val indices = readLzw(minCodeSize, width * height)
                        val palette = localPalette
                        if (palette.isEmpty()) return null
                        val restore = if (disposal == 3) canvas.copyOf() else null
                        drawFrame(
                            canvas = canvas,
                            indices = indices,
                            palette = palette,
                            transparentIndex = frameTransparent,
                            left = left,
                            top = top,
                            width = width,
                            height = height,
                            interlaced = interlaced
                        )
                        frames += StickerFrame(canvas.copyOf(), frameDelay)
                        when (disposal) {
                            2 -> clearRect(canvas, left, top, width, height)
                            3 -> restore?.let { canvas = it }
                        }
                        frameDelay = 100
                        disposal = 0
                        frameTransparent = -1
                    }

                    0x3B -> return finish(frames)
                    else -> {
                        // 未知块：无法安全跳过，直接收尾（已经解出来的帧仍然可用）。
                        if (frames.isEmpty()) return null
                        return finish(frames)
                    }
                }
            }
            return finish(frames)
        }

        private fun finish(frames: List<StickerFrame>): StickerSource? {
            if (frames.isEmpty()) return null
            return StickerSource(screenWidth, screenHeight, frames)
        }

        private fun isGif87(): Boolean {
            if (bytes.size < 6) return false
            val header = String(bytes, 0, 6, Charsets.US_ASCII)
            return header == "GIF87a"
        }

        private fun drawFrame(
            canvas: IntArray,
            indices: IntArray,
            palette: IntArray,
            transparentIndex: Int,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            interlaced: Boolean
        ) {
            val rows = if (interlaced) interlaceRows(height) else IntArray(height) { it }
            var cursor = 0
            rows.forEach { y ->
                val targetY = top + y
                if (targetY < 0 || targetY >= screenHeight) {
                    cursor += width
                    return@forEach
                }
                val rowStart = targetY * screenWidth
                for (x in 0 until width) {
                    val index = indices[cursor++]
                    if (index == transparentIndex) continue
                    val targetX = left + x
                    if (targetX < 0 || targetX >= screenWidth) continue
                    val rgb = palette.getOrElse(index) { 0 }
                    canvas[rowStart + targetX] = 0xFF000000.toInt() or rgb
                }
            }
        }

        private fun interlaceRows(height: Int): IntArray {
            val rows = IntArray(height)
            var cursor = 0
            for (start in intArrayOf(0, 4)) {
                var y = start
                while (y < height) { rows[cursor++] = y; y += 8 }
            }
            for (start in intArrayOf(2, 1)) {
                var y = start
                while (y < height) { rows[cursor++] = y; y += 4 }
            }
            return rows
        }

        private fun clearRect(canvas: IntArray, left: Int, top: Int, width: Int, height: Int) {
            for (y in top until (top + height).coerceAtMost(screenHeight)) {
                if (y < 0) continue
                val rowStart = y * screenWidth
                for (x in left until (left + width).coerceAtMost(screenWidth)) {
                    if (x < 0) continue
                    canvas[rowStart + x] = 0
                }
            }
        }

        private fun readLzw(minCodeSize: Int, expected: Int): IntArray {
            val output = IntArray(expected)
            var outputCount = 0
            val clearCode = 1 shl minCodeSize
            val endCode = clearCode + 1
            var codeSize = minCodeSize + 1
            var nextCode = endCode + 1
            val stack = IntArray(4096)
            val dictionaryPrefix = IntArray(4096) { -1 }
            val dictionarySuffix = IntArray(4096)
            var previous = -1
            var bitBuffer = 0L
            var bitCount = 0
            var blockRemaining = 0
            var finished = false

            fun readByteOrMinusOne(): Int {
                if (blockRemaining == 0) {
                    if (position >= bytes.size) return -1
                    blockRemaining = bytes[position++].toInt() and 0xFF
                    if (blockRemaining == 0) return -1
                }
                if (position >= bytes.size) return -1
                blockRemaining--
                return bytes[position++].toInt() and 0xFF
            }

            while (!finished && outputCount < expected) {
                while (bitCount < codeSize) {
                    val byte = readByteOrMinusOne()
                    if (byte < 0) { finished = true; break }
                    bitBuffer = bitBuffer or (byte.toLong() shl bitCount)
                    bitCount += 8
                }
                if (finished) break
                val code = (bitBuffer and ((1L shl codeSize) - 1)).toInt()
                bitBuffer = bitBuffer ushr codeSize
                bitCount -= codeSize

                when {
                    code == clearCode -> {
                        codeSize = minCodeSize + 1
                        nextCode = endCode + 1
                        previous = -1
                    }

                    code == endCode -> finished = true

                    code < nextCode -> {
                        val length = expand(code, clearCode, dictionaryPrefix, dictionarySuffix, stack)
                        for (i in 0 until length) {
                            if (outputCount < expected) output[outputCount++] = stack[i]
                        }
                        if (previous >= 0 && nextCode < 4096) {
                            dictionaryPrefix[nextCode] = previous
                            dictionarySuffix[nextCode] = stack[0]
                            nextCode++
                            // 与编码器的「> 2^codeSize」配对的另一边：解码器永远落后一格。
                            if (nextCode >= (1 shl codeSize) && codeSize < 12) codeSize++
                        }
                        previous = code
                    }

                    code == nextCode && previous >= 0 -> {
                        val length = expand(previous, clearCode, dictionaryPrefix, dictionarySuffix, stack)
                        val first = stack[0]
                        if (outputCount < expected) output[outputCount++] = first
                        for (i in 0 until length) {
                            if (outputCount < expected) output[outputCount++] = stack[i]
                        }
                        if (nextCode < 4096) {
                            dictionaryPrefix[nextCode] = previous
                            dictionarySuffix[nextCode] = first
                            nextCode++
                            if (nextCode >= (1 shl codeSize) && codeSize < 12) codeSize++
                        }
                        previous = code
                    }

                    else -> finished = true
                }
            }
            // 把剩下的子块读干净，避免影响后面的帧。
            while (true) {
                if (blockRemaining > 0) {
                    position += blockRemaining
                    blockRemaining = 0
                }
                if (position >= bytes.size) break
                val length = bytes[position++].toInt() and 0xFF
                if (length == 0) break
                position += length
            }
            if (outputCount >= expected) return output
            return output.copyOf(outputCount)
        }

        /** 把 code 对应的字节串展开进 buffer，返回长度（buffer[0] 是这段的第一字节）。 */
        private fun expand(
            code: Int,
            clearCode: Int,
            dictionaryPrefix: IntArray,
            dictionarySuffix: IntArray,
            buffer: IntArray
        ): Int {
            var current = code
            var length = 0
            while (current >= 0 && length < buffer.size) {
                if (current < clearCode) {
                    buffer[length++] = current
                    break
                }
                buffer[length++] = dictionarySuffix[current]
                current = dictionaryPrefix[current]
            }
            // 反转成「从第一字节到最后一字节」
            var i = 0
            var j = length - 1
            while (i < j) {
                val tmp = buffer[i]
                buffer[i] = buffer[j]
                buffer[j] = tmp
                i++
                j--
            }
            return length
        }

        private fun skipSubBlocks() {
            while (position < bytes.size) {
                val length = bytes[position++].toInt() and 0xFF
                if (length == 0) return
                position += length
            }
        }

        private fun readPalette(size: Int): IntArray {
            val palette = IntArray(size)
            for (i in 0 until size) {
                val r = readByte()
                val g = readByte()
                val b = readByte()
                palette[i] = (r shl 16) or (g shl 8) or b
            }
            return palette
        }

        private fun readAscii(count: Int): String {
            if (position + count > bytes.size) return ""
            val text = String(bytes, position, count, Charsets.US_ASCII)
            position += count
            return text
        }

        private fun readByte(): Int {
            if (position >= bytes.size) return 0
            return bytes[position++].toInt() and 0xFF
        }

        private fun readShort(): Int {
            val low = readByte()
            val high = readByte()
            return low or (high shl 8)
        }

        private companion object {
            const val MIN_DELAY_CENTIS = 2
        }
    }
}
