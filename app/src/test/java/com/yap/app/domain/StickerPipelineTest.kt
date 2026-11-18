package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 表情包压缩管线的单元测试（纯 JVM）。
 *
 * `spec/stickers/README.md` 的硬预算：**长边 ≤320、帧数 ≤60、时长 ≤6 s、≤20 fps、
 * 调色板 ≤256、字节 ≤512 KB**。这六条里前五条是「规整」，最后一条是「必要时降级」，
 * 所以测试也分成两组：
 *
 * 1. `normalize` 的规整结果（尺寸/帧数/时长/帧率）；
 * 2. `build` 的最终产出（一定 ≤512 KB、帧数/尺寸的降级顺序、极端输入返回 null）。
 */
class StickerPipelineTest {

    private fun solidFrame(width: Int, height: Int, color: Int, delayMs: Int = 100) =
        StickerFrame(IntArray(width * height) { color }, delayMs)

    @Test
    fun `大图会被等比缩到长边 320`() {
        val source = StickerSource(
            width = 900,
            height = 600,
            frames = listOf(solidFrame(900, 600, 0xFF336699.toInt()))
        )
        val normalized = StickerPipeline.normalize(source)!!
        assertEquals(320, normalized.width)
        assertEquals("宽高比不能变（900:600 → 320:213）", 213, normalized.height)
    }

    @Test
    fun `小图不会被放大`() {
        val source = StickerSource(80, 60, listOf(solidFrame(80, 60, 0xFF112233.toInt())))
        val normalized = StickerPipeline.normalize(source)!!
        assertEquals(80, normalized.width)
        assertEquals(60, normalized.height)
    }

    @Test
    fun `帧率被夹到 20 fps`() {
        // 1000ms / 5ms / 0ms 三种输入：夹完之后都不许短于 50ms。
        val source = StickerSource(
            width = 32,
            height = 32,
            frames = listOf(
                solidFrame(32, 32, 0xFFFF0000.toInt(), delayMs = 1000),
                solidFrame(32, 32, 0xFF00FF00.toInt(), delayMs = 5),
                solidFrame(32, 32, 0xFF0000FF.toInt(), delayMs = 0)
            )
        )
        val normalized = StickerPipeline.normalize(source)!!
        assertTrue(
            "每帧停留必须 ≥50ms（20 fps 上限）：${normalized.frames.map { it.delayMs }}",
            normalized.frames.all { it.delayMs >= StickerLimits.MIN_DELAY_MS }
        )
    }

    @Test
    fun `帧数超 60 时均匀抽帧且总时长不变`() {
        val frames = (0 until 150).map { index ->
            solidFrame(16, 16, 0xFF000000.toInt() or (index * 1000), delayMs = 50)
        }
        val source = StickerSource(16, 16, frames)
        val normalized = StickerPipeline.normalize(source)!!
        assertTrue("帧数必须 ≤60：${normalized.frames.size}", normalized.frames.size <= StickerLimits.MAX_FRAMES)
        assertEquals("抽帧不能把动作变快（总时长应保持 7500 → 截到 6000）", 6000, normalized.durationMs)
    }

    @Test
    fun `总时长超 6 秒时从尾部截断`() {
        val frames = (0 until 20).map { index ->
            solidFrame(16, 16, 0xFF000000.toInt() or (index * 100), delayMs = 500)
        }
        val normalized = StickerPipeline.normalize(StickerSource(16, 16, frames))!!
        assertTrue("总时长必须 ≤6000ms：${normalized.durationMs}", normalized.durationMs <= StickerLimits.MAX_DURATION_MS)
        assertTrue("截断不能变成 0 帧", normalized.frames.isNotEmpty())
    }

    @Test
    fun `静态图产出单帧 GIF`() {
        val source = StickerSource(120, 90, listOf(solidFrame(120, 90, 0xFFABCDEF.toInt())))
        val build = StickerPipeline.build(source)!!
        assertEquals(1, build.frames)
        assertEquals(120, build.width)
        assertEquals(90, build.height)
        assertTrue(build.byteCount in 1..StickerLimits.MAX_BYTES)

        val decoded = StickerGif.decode(build.bytes)!!
        assertEquals("落盘的东西必须真的是 GIF", 1, decoded.frames.size)
        assertEquals(0xFFABCDEF.toInt(), decoded.frames[0].argb[0])
    }

    @Test
    fun `透明底的图能通过管线且透明还在`() {
        val width = 64
        val height = 64
        val pixels = IntArray(width * height) { index ->
            if (index % 3 == 0) 0x00000000 else 0xFFFF6600.toInt()
        }
        val build = StickerPipeline.build(StickerSource(width, height, listOf(StickerFrame(pixels, 100))))!!
        val decoded = StickerGif.decode(build.bytes)!!
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        val transparentCount = decoded.frames[0].argb.count { (it ushr 24) and 0xFF == 0 }
        assertTrue("透明像素不该被压成不透明（实际还剩 $transparentCount 个）", transparentCount > 0)
    }

    @Test
    fun `多帧输入的帧数在往返后保持一致`() {
        val frames = (0 until 8).map { index ->
            solidFrame(48, 48, 0xFF000000.toInt() or ((index * 30) shl 16), delayMs = 60)
        }
        val build = StickerPipeline.build(StickerSource(48, 48, frames))!!
        assertEquals("帧数一致（没有触发降级）", 8, build.frames)
        val decoded = StickerGif.decode(build.bytes)!!
        assertEquals("解码回来的帧数必须一致", 8, decoded.frames.size)
        assertEquals("总时长一致（8 帧 × 60ms）", build.durationMs, decoded.durationMs)
    }

    /**
     * 最坏情况：320×320 的随机噪声多帧。
     *
     * 随机噪声是 LZW 压不动的东西，几乎必然超过 512 KB，于是必须走降级阶梯。
     * 这条用例钉住「不管多糟，最终输出一定 ≤512 KB」以及「尺寸/帧数仍然合法」。
     */
    @Test
    fun `噪声大图一定被压进 512KB 以内`() {
        val random = Random(11)
        val width = 320
        val height = 320
        val frames = (0 until 10).map {
            StickerFrame(IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0x1000000) }, 50)
        }
        val build = StickerPipeline.build(StickerSource(width, height, frames))
        assertNotNull("噪声图也必须能产出（降级阶梯够用）", build)
        assertTrue("字节上限 512KB 是硬约束：实际 ${build!!.byteCount}", build.byteCount <= StickerLimits.MAX_BYTES)
        assertTrue("长边仍 ≤320", maxOf(build.width, build.height) <= StickerLimits.MAX_EDGE)
        assertTrue("帧数仍 ≤60", build.frames <= StickerLimits.MAX_FRAMES)
        assertTrue("调色板 ≤256", build.colors <= StickerLimits.MAX_COLORS)
        // 真的能解回来（不是写了一段垃圾）。
        assertNotNull(StickerGif.decode(build.bytes))
    }

    @Test
    fun `降级阶梯是从轻到重的固定顺序`() {
        val ladder = StickerPipeline.degradeLadder()
        assertEquals("第一档必须是「什么都不降」", StickerLimits.MAX_COLORS, ladder.first().colors)
        assertEquals(1.0, ladder.first().scale, 0.0)
        assertEquals(1, ladder.first().frameStep)
        assertTrue("阶梯必须单调收紧（否则会来回抖动）", ladder.size >= 6)
        for (i in 1 until ladder.size) {
            val previous = ladder[i - 1]
            val current = ladder[i]
            assertTrue(
                "第 $i 档不能比上一档更宽松：$current vs $previous",
                current.colors <= previous.colors || current.scale < previous.scale || current.frameStep > previous.frameStep
            )
        }
        assertTrue("最后一档必须够狠（8 色 / 0.2 倍 / 6 帧）", ladder.last().colors <= 16 && ladder.last().scale <= 0.25)
    }

    @Test
    fun `坏输入返回 null 而不是崩`() {
        assertEquals(null, StickerPipeline.normalize(StickerSource(0, 10, emptyList())))
        assertEquals(null, StickerPipeline.normalize(StickerSource(10, 10, emptyList())))
        // 帧像素数与宽高不匹配
        assertEquals(
            null,
            StickerPipeline.normalize(StickerSource(10, 10, listOf(StickerFrame(IntArray(5), 100))))
        )
        assertEquals(null, StickerPipeline.build(StickerSource(0, 0, emptyList())))
    }

    @Test
    fun `缩小的采样不会把透明像素算成颜色`() {
        // 4×4 里一半全透明、一半纯红，缩到 2×2：透明那一半不该被红“污染”成不透明。
        val source = IntArray(16) { index ->
            val x = index % 4
            if (x < 2) 0x00000000 else 0xFFFF0000.toInt()
        }
        val scaled = StickerPipeline.scaleArgb(source, 4, 4, 2, 2)
        val leftTop = scaled[0]
        val rightTop = scaled[1]
        assertEquals("透明区域必须保持 alpha=0", 0, (leftTop ushr 24) and 0xFF)
        assertEquals("不透明区域必须保持红色", 0xFFFF0000.toInt(), rightTop)
    }
}
