package com.yap.agentd.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * [StickerComposer] 的行为钉在这里：`spec/stickers` 的四条硬上限（长边 ≤320、帧数 ≤60、
 * 总时长 ≤6 s、字节 ≤512 KB）、逐帧合成、叠图叠字真的落到像素上、错误码稳定。
 *
 * 测试素材全部**现场生成**（PNG/JPEG 走 `ImageIO`，GIF 走 [StickerGif.encode]），
 * 仓库里不放二进制 fixture：fixture 的字节数、帧率一改就得重新生成，反而更难维护。
 */
class StickerComposerTest {

    // ------------------------------------------------------------ 1. 静态底 + 文字

    @Test
    fun staticPngWithCenteredTextProducesSingleFrameGif() {
        val baseBytes = png(400, 300) { g, w, h ->
            g.color = Color(0x2E, 0x5A, 0x88)
            g.fillRect(0, 0, w, h)
        }
        val base = StickerInput(baseBytes, "image/png")

        val result = StickerComposer.compose(base, overlay = null, text = "测试一下", position = "CENTER")

        assertEquals("静态图必须是单帧", 1, result.frames)
        assertTrue(max(result.width, result.height) <= StickerComposer.MAX_EDGE)
        assertTrue(result.gif.size <= StickerComposer.MAX_BYTES)

        val decoded = StickerGif.decode(result.gif)
        assertNotNull("输出必须能被自己的解码器读回来", decoded)
        decoded!!
        assertEquals(result.width, decoded.width)
        assertEquals(result.height, decoded.height)
        assertEquals(result.frames, decoded.frames.size)
        // 400x300 缩到长边 320 → 320x240
        assertEquals(320, decoded.width)
        assertEquals(240, decoded.height)

        // 文字必须真的画上去：同一张底图不叠字时，中间区域应当明显不同。
        val noText = StickerComposer.compose(base, overlay = null, text = null, position = "CENTER")
        val plain = StickerGif.decode(noText.gif)!!
        assertTrue("叠字后像素应当有变化", changedPixels(plain.frames[0].argb, decoded.frames[0].argb) > 100)
    }

    // ------------------------------------------------------------ 2. JPEG 底 + 静态贴纸

    @Test
    fun jpegBaseWithStaticOverlayPastesPixelsAtBottom() {
        val baseBytes = jpeg(320, 240) { g, w, h ->
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
        }
        val overlayBytes = png(120, 120) { g, w, h ->
            g.color = Color(0xC0, 0x28, 0x28)
            g.fillRect(0, 0, w, h)
        }
        val base = StickerInput(baseBytes, "image/jpeg")

        val baseOnly = StickerComposer.compose(base, overlay = null, text = null, position = "BOTTOM")
        val composed = StickerComposer.compose(
            base,
            StickerInput(overlayBytes, "image/png"),
            text = null,
            position = "BOTTOM"
        )

        val plain = StickerGif.decode(baseOnly.gif)!!
        val withOverlay = StickerGif.decode(composed.gif)!!
        assertEquals(plain.width, withOverlay.width)
        assertEquals(plain.height, withOverlay.height)

        // 贴纸在 BOTTOM：底部 40% 的行里应当有大片像素被改掉。
        val bandTop = withOverlay.height * 6 / 10
        var changed = 0
        var total = 0
        for (y in bandTop until withOverlay.height) {
            for (x in 0 until withOverlay.width) {
                total++
                val before = plain.frames[0].argb[y * plain.width + x]
                val after = withOverlay.frames[0].argb[y * withOverlay.width + x]
                if (colorDistance(before, after) > 40) changed++
            }
        }
        assertTrue("底部应当被贴纸覆盖（changed=$changed/$total）", changed > total / 5)
    }

    // ------------------------------------------------------------ 3. 5 帧动图底 + 文字

    @Test
    fun fiveFrameGifKeepsFramesAndPositiveDelays() {
        val frames = (0 until 5).map { index ->
            solidImage(160, 160, Color(20 + 40 * index, 120, 200 - 30 * index))
        }
        val input = StickerInput(animatedGif(frames, delayMs = 120), "image/gif")

        val result = StickerComposer.compose(input, overlay = null, text = "动图", position = "TOP")

        assertEquals("5 帧输入不该被抽帧", 5, result.frames)
        assertEquals("总时长 = 5 x 120ms", 600L, result.durationMs)
        assertTrue(max(result.width, result.height) <= StickerComposer.MAX_EDGE)
        assertTrue(result.gif.size <= StickerComposer.MAX_BYTES)

        val decoded = StickerGif.decode(result.gif)!!
        assertEquals(5, decoded.frames.size)
        assertEquals(result.width, decoded.width)
        assertEquals(result.height, decoded.height)
        decoded.frames.forEachIndexed { index, frame ->
            assertTrue("第 $index 帧 delay 必须 > 0", frame.delayMs > 0)
        }
        assertTrue(decoded.durationMs <= StickerComposer.MAX_DURATION_MS)
    }

    // ------------------------------------------------------------ 4. 内置空白底

    @Test
    fun blankBaseRenders320SquareSingleFrame() {
        val result = StickerComposer.compose(base = null, overlay = null, text = "哈哈哈", position = "BOTTOM")

        assertEquals(StickerComposer.BLANK_SIZE, result.width)
        assertEquals(StickerComposer.BLANK_SIZE, result.height)
        assertEquals(1, result.frames)
        assertTrue(result.gif.size <= StickerComposer.MAX_BYTES)

        val decoded = StickerGif.decode(result.gif)
        assertNotNull(decoded)
        decoded!!
        assertEquals(320, decoded.width)
        assertEquals(320, decoded.height)
        assertEquals(1, decoded.frames.size)
    }

    // ------------------------------------------------------------ 5. 透明底

    @Test
    fun transparentPngBaseKeepsAlphaInOutput() {
        // 带 alpha 的 PNG 基底：四角透明、中间一个红圆。输出 GIF 必须把「透明」也带过去
        // （`StickerGif` 用索引 0 + disposal=2 表示透明），否则表情贴到聊天背景上会变成黑块。
        val image = BufferedImage(320, 320, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )
            g.color = Color(0xD0, 0x30, 0x30)
            g.fillOval(60, 60, 200, 200)
        } finally {
            g.dispose()
        }
        val bytes = encodeImage(image, "png")

        val result = StickerComposer.compose(StickerInput(bytes, "image/png"), null, "透明", "BOTTOM")
        assertEquals(320, result.width)
        assertEquals(320, result.height)

        val decoded = StickerGif.decode(result.gif)!!
        val argb = decoded.frames[0].argb
        assertEquals("左上角应当仍然是透明的", 0, argb[0] ushr 24)
        assertEquals("右下角应当仍然是透明的", 0, argb[argb.size - 1] ushr 24)
        assertTrue("中间的圆必须还在", argb[160 * 320 + 160] ushr 24 != 0)
    }

    // ------------------------------------------------------------ 6. 61 帧 → ≤60 帧

    @Test
    fun sixtyOneFrameAnimationIsThinnedToAtMostSixty() {
        val frames = (0 until 61).map { index ->
            solidImage(80, 80, Color((index * 4) % 256, 80, 160))
        }
        val input = StickerInput(animatedGif(frames, delayMs = 80), "image/gif")

        val result = StickerComposer.compose(input, overlay = null, text = "多帧", position = "CENTER")

        assertTrue("帧数必须 ≤60（实际 ${result.frames}）", result.frames <= StickerComposer.MAX_FRAMES)
        assertTrue("抽帧后仍应是动图（实际 ${result.frames}）", result.frames > 1)
        // 抽帧要把被跳过帧的时长累加起来，总时长不能缩水（61 x 80ms = 4880ms）。
        assertEquals(4880L, result.durationMs)
        assertTrue(result.gif.size <= StickerComposer.MAX_BYTES)

        val decoded = StickerGif.decode(result.gif)!!
        assertEquals(result.frames, decoded.frames.size)
        assertTrue(decoded.durationMs <= StickerComposer.MAX_DURATION_MS)
    }

    // ------------------------------------------------------------ 7. 大图自动缩边

    @Test
    fun largePngIsScaledIntoMaxEdge() {
        val bytes = png(1200, 900) { g, w, h ->
            g.paint = GradientPaint(0f, 0f, Color(30, 40, 200), w.toFloat(), h.toFloat(), Color(240, 200, 40))
            g.fillRect(0, 0, w, h)
        }

        val result = StickerComposer.compose(StickerInput(bytes, "image/png"), null, "大图", "CENTER")

        assertEquals("1200x900 → 长边 320", 320, result.width)
        assertEquals("等比缩放", 240, result.height)
        assertTrue(result.gif.size <= StickerComposer.MAX_BYTES)

        val decoded = StickerGif.decode(result.gif)!!
        assertEquals(320, decoded.width)
        assertEquals(240, decoded.height)
    }

    // ------------------------------------------------------------ 8. 错误码

    @Test
    fun invalidInputsMapToStableErrorCodes() {
        // 坏字节：mime 说是 PNG，内容不是。
        assertComposeCode("invalid_image") {
            StickerComposer.compose(StickerInput(ByteArray(64) { 0x7F }, "image/png"), null, null)
        }
        // 不支持的 mime：即使字节是合法 PNG 也要拒。
        val validPng = png(32, 32) { g, w, h ->
            g.color = Color.BLUE
            g.fillRect(0, 0, w, h)
        }
        assertComposeCode("unsupported_image") {
            StickerComposer.compose(StickerInput(validPng, "image/webp"), null, null)
        }
        // 超过 8 MiB。
        assertComposeCode("image_too_large") {
            StickerComposer.compose(StickerInput(ByteArray(StickerComposer.MAX_INPUT_BYTES + 1), "image/png"), null, null)
        }
        // 什么素材都没有。
        assertComposeCode("empty_request") {
            StickerComposer.compose(null, null, "   ", "CENTER")
        }
        // GIF mime 但字节不是 GIF。
        assertComposeCode("invalid_image") {
            StickerComposer.compose(StickerInput(validPng, "image/gif"), null, null)
        }
    }

    // ------------------------------------------------------------ 9. 噪声图降级

    @Test
    fun noisyAnimationIsCompressedOrFailsLoudly() {
        // 最难压的输入形态：16 帧、每帧 320x320、像素在空间上**完全随机**，LZW 压不动它
        // （首轮 320 px / 256 色实测约 620 KB，必然超 512 KB，于是必须真的走降级阶梯）。
        //
        // 颜色数故意只用 8 种而不是「每像素真随机色」：`StickerGif.encode` 的
        // RGB→调色板映射对「几万种互不相同的颜色」会退化成全表探测（编码器自身的性能特性，
        // 实测 320x320 八帧全彩噪声要 7 s+）。8 种底色的图在双线性缩放后混合色也有限，
        // 既能压出「压不动」的体量，又不会把用例拖成十几秒——这里要钉的是**字节上限与降级链**。
        val random = Random(20240521L)
        val palette = IntArray(8) { random.nextInt() or 0xFF000000.toInt() }
        val frames = (0 until 16).map {
            val argb = IntArray(320 * 320) { palette[random.nextInt(palette.size)] }
            StickerFrame(argb, 100)
        }
        val input = StickerInput(StickerGif.encode(StickerSource(320, 320, frames)), "image/gif")

        val outcome = runCatching { StickerComposer.compose(input, null, "噪声", "CENTER") }
        val result = outcome.getOrNull()
        if (result != null) {
            assertTrue("超限返回：${result.gif.size} 字节", result.gif.size <= StickerComposer.MAX_BYTES)
            assertTrue(max(result.width, result.height) <= StickerComposer.MAX_EDGE)
            assertTrue(result.frames <= StickerComposer.MAX_FRAMES)
            assertTrue(result.durationMs <= StickerComposer.MAX_DURATION_MS)
            assertNotNull("降级后的输出也必须能解回来", StickerGif.decode(result.gif))
        } else {
            // 允许压不下来，但只能是明确的 too_large，不能是「静默返回一张超限的表情」。
            val error = outcome.exceptionOrNull()
            assertTrue(
                "只允许 too_large，实际是 $error",
                error is StickerComposeException && error.code == "too_large"
            )
        }
    }

    // ------------------------------------------------------------ 10. 长动图截到 6s

    @Test
    fun longAnimationIsTrimmedToSixSeconds() {
        val frames = (0 until 20).map { index ->
            solidImage(120, 120, Color(20 * index % 256, 60, 180))
        }
        // 每帧 500 ms → 原本 10 s，必须被砍到 ≤6 s。
        val input = StickerInput(animatedGif(frames, delayMs = 500), "image/gif")

        val result = StickerComposer.compose(input, null, "长", "BOTTOM")

        assertTrue("总时长必须 ≤6000ms（实际 ${result.durationMs}）", result.durationMs <= StickerComposer.MAX_DURATION_MS)
        assertTrue("必须真的砍帧（实际 ${result.frames} 帧）", result.frames < 20)
        val decoded = StickerGif.decode(result.gif)!!
        assertEquals(result.frames, decoded.frames.size)
        assertTrue(decoded.durationMs <= StickerComposer.MAX_DURATION_MS)
    }

    // ------------------------------------------------------------ 素材生成

    private fun solidImage(width: Int, height: Int, color: Color): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = color
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun png(width: Int, height: Int, paint: (Graphics2D, Int, Int) -> Unit): ByteArray =
        encodeImage(drawImage(width, height, paint), "png")

    private fun jpeg(width: Int, height: Int, paint: (Graphics2D, Int, Int) -> Unit): ByteArray =
        encodeImage(drawImage(width, height, paint), "jpg")

    private fun drawImage(width: Int, height: Int, paint: (Graphics2D, Int, Int) -> Unit): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            paint(g, width, height)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun encodeImage(image: BufferedImage, format: String): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue("ImageIO 没有 $format 写入器", ImageIO.write(image, format, out))
        return out.toByteArray()
    }

    /** 把若干 BufferedImage 编成动画 GIF（走被测的 [StickerGif.encode]，两端同一份算法）。 */
    private fun animatedGif(images: List<BufferedImage>, delayMs: Int): ByteArray {
        val width = images.first().width
        val height = images.first().height
        val frames = images.map { image ->
            val argb = IntArray(width * height)
            image.getRGB(0, 0, width, height, argb, 0, width)
            StickerFrame(argb, delayMs)
        }
        return StickerGif.encode(StickerSource(width, height, frames))
    }

    // ------------------------------------------------------------ 断言工具

    private fun assertComposeCode(expected: String, block: () -> Unit) {
        try {
            block()
            fail("应当抛 StickerComposeException($expected)")
        } catch (error: StickerComposeException) {
            assertEquals(expected, error.code)
        }
    }

    /** 两帧之间「肉眼可见不同」的像素数（通道最大差 >50 才算）。 */
    private fun changedPixels(before: IntArray, after: IntArray): Int {
        var changed = 0
        for (index in before.indices) {
            if (colorDistance(before[index], after[index]) > 50) changed++
        }
        return changed
    }

    private fun colorDistance(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db))
    }
}
