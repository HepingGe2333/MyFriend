package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * GIF89a 编解码器的单元测试（纯 JVM，不需要设备）。
 *
 * 这里盯的是「自己写的 LZW / 调色板到底对不对」，因为这一类 bug 在界面上只会表现为
 * 「表情包花掉了」，没有任何报错。四类断言：
 *
 * 1. **结构**：文件头是 `GIF89a`、结束符是 `0x3B`、多帧带 NETSCAPE 循环块；
 * 2. **往返**：解码出来的帧数/尺寸/停留时长与编码输入一致（调色板允许有误差，帧数不允许）；
 * 3. **颜色**：颜色数不超预算时是**无损**的（纯色、透明、少量颜色的表情必须原样回来）；
 * 4. **边界**：随机噪声（LZW 码表一定会涨到 512/1024/2048 以上）也能正确往返——
 *    这一条专门防「码表涨位宽时编解码不同步」那类只在 500 个字典项以后才花屏的 bug。
 */
class StickerGifTest {

    private fun solid(width: Int, height: Int, color: Int): IntArray = IntArray(width * height) { color }

    private fun frames(width: Int, height: Int, colors: List<Int>): StickerSource = StickerSource(
        width = width,
        height = height,
        frames = colors.mapIndexed { index, color -> StickerFrame(solid(width, height, color), 60 + index * 10) }
    )

    @Test
    fun `单帧纯色往返是无损的`() {
        val source = frames(12, 9, listOf(0xFF3366CC.toInt()))
        val gif = StickerGif.encode(source)
        assertEquals("必须是 GIF89a", "GIF89a", String(gif, 0, 6, Charsets.US_ASCII))
        assertEquals("必须以 trailer 0x3B 结尾", 0x3B, gif.last().toInt() and 0xFF)
        assertTrue("只有一帧时不该写 NETSCAPE 循环块", !String(gif, Charsets.ISO_8859_1).contains("NETSCAPE"))

        val decoded = StickerGif.decode(gif)!!
        assertEquals(12, decoded.width)
        assertEquals(9, decoded.height)
        assertEquals(1, decoded.frames.size)
        decoded.frames[0].argb.forEach { pixel ->
            assertEquals("颜色必须是原值（调色板够用时不允许有误差）", 0xFF3366CC.toInt(), pixel)
        }
    }

    @Test
    fun `多帧往返帧数与时长都对得上`() {
        val source = frames(10, 10, listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
        val gif = StickerGif.encode(source)
        assertTrue("多帧必须带 NETSCAPE 循环块", String(gif, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))

        val decoded = StickerGif.decode(gif)!!
        assertEquals("帧数必须一致（抽帧是压缩管线的事，编解码器不许丢帧）", 3, decoded.frames.size)
        assertEquals(
            listOf(60, 70, 80),
            decoded.frames.map { it.delayMs },
            // GIF 的时长精度是 10ms，这里给的输入本来就是 10ms 的整数倍。
        )
        assertEquals(0xFFFF0000.toInt(), decoded.frames[0].argb[0])
        assertEquals(0xFF00FF00.toInt(), decoded.frames[1].argb[0])
        assertEquals(0xFF0000FF.toInt(), decoded.frames[2].argb[0])
    }

    @Test
    fun `透明像素回来仍然是透明`() {
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { index ->
            if (index % 2 == 0) 0x00000000 else 0xFF202020.toInt()
        }
        val source = StickerSource(width, height, listOf(StickerFrame(pixels, 100)))
        val decoded = StickerGif.decode(StickerGif.encode(source))!!
        assertEquals(1, decoded.frames.size)
        val out = decoded.frames[0].argb
        assertEquals("透明白像素必须保持 alpha=0", 0, (out[0] ushr 24) and 0xFF)
        assertEquals("不透明像素必须保持不透明", 0xFF, (out[1] ushr 24) and 0xFF)
        assertEquals(0x202020, out[1] and 0xFFFFFF)
    }

    @Test
    fun `调色板预算被遵守`() {
        // 一片彩色渐变（远多于 16 色），编码时限制到 16 色。
        val width = 32
        val height = 32
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            0xFF000000.toInt() or ((x * 8) shl 16) or ((y * 8) shl 8) or ((x * y) and 0xFF)
        }
        val source = StickerSource(width, height, listOf(StickerFrame(pixels, 100)))
        val decoded = StickerGif.decode(StickerGif.encode(source, maxColors = 16))!!
        val distinct = decoded.frames[0].argb.map { it and 0xFFFFFF }.toSet()
        assertTrue("调色板必须真的被限制在 16 色内：实际 ${distinct.size}", distinct.size <= 16)
    }

    @Test
    fun `随机噪声也能正确往返 覆盖 LZW 码表涨位宽`() {
        // 64×64 的随机噪声足够让字典冲到 4096 项上限，正好压住「涨位宽不同步」的坑。
        val random = Random(42)
        val width = 64
        val height = 64
        val pixels = IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
        val source = StickerSource(width, height, listOf(StickerFrame(pixels, 50)))

        val gif = StickerGif.encode(source)
        val decoded = StickerGif.decode(gif)!!
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        assertEquals(1, decoded.frames.size)

        // 调色板只有 256 色，逐像素相等不可能；但「大面积同色」必须对得上：
        // 统计解码结果与原图的平均色差，超过阈值就说明 LZW 错位/颜色错乱。
        var totalDiff = 0L
        var compared = 0
        for (i in pixels.indices) {
            val a = pixels[i]
            val b = decoded.frames[0].argb[i]
            totalDiff += kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)).toLong()
            totalDiff += kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)).toLong()
            totalDiff += kotlin.math.abs((a and 0xFF) - (b and 0xFF)).toLong()
            compared += 3
        }
        val average = totalDiff.toDouble() / compared
        assertTrue("随机噪声的平均通道误差应该很小（实际 $average）", average < 24.0)
    }

    @Test
    fun `大图与多帧都在能力范围内`() {
        val random = Random(7)
        val width = 320
        val height = 240
        val source = StickerSource(
            width = width,
            height = height,
            frames = (0 until 4).map { frameIndex ->
                val pixels = IntArray(width * height) { index ->
                    val x = index % width
                    val y = index / width
                    // 有结构的大图（横向渐变 + 几个色块），比纯噪声更接近真实表情包。
                    if (x % 64 < 8) 0xFF000000.toInt() or (frameIndex * 0x202020)
                    else 0xFF000000.toInt() or ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8)
                }
                StickerFrame(pixels, 50)
            }
        )
        val gif = StickerGif.encode(source)
        val decoded = StickerGif.decode(gif)
        assertNotNull("320×240 的四帧必须能解回来", decoded)
        assertEquals(4, decoded!!.frames.size)
        assertEquals(320, decoded.width)
        assertEquals(240, decoded.height)
        // 顺带记录真实体积：这个数字是「导入一张表情包大概多大」的经验值。
        assertTrue("四帧 320×240 应该在 512KB 以内（实际 ${gif.size} 字节）", gif.size < 512 * 1024)
    }

    @Test
    fun `不是 GIF 的字节返回 null 而不是抛异常`() {
        assertNull(StickerGif.decode(ByteArray(0)))
        assertNull(StickerGif.decode("not a gif at all".toByteArray()))
        // 头部正确但内容被截断：可以解出 0 帧，返回 null 也行，就是不许崩。
        val truncated = StickerGif.encode(frames(8, 8, listOf(0xFF123456.toInt()))).copyOf(20)
        runCatching { StickerGif.decode(truncated) }.let { result ->
            assertTrue("截断数据只允许「返回 null」或「返回空」，不许抛异常", result.isSuccess)
        }
    }
}
