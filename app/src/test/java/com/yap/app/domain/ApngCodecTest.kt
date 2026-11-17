package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * APNG 读取器（`spec/stickers/README.md`：WebP 动图 / APNG 也走同一条管线）。
 *
 * Android 的 `ImageDecoder` 只能「播」动图，没有逐帧取位图的公开 API，所以 APNG 自己解。
 * 样本是 Pillow 生成的真实 APNG（4 帧 6×6 纯色、100ms、无限循环、`fcTL` 在 `IDAT` 之前），
 * 正好覆盖「默认图像就是第 0 帧」这种最常见也最容易接错的写法。
 */
class ApngCodecTest {

    @Test
    fun `能认出 APNG 与静态 PNG`() {
        val apng = Base64.getDecoder().decode(PILLOW_APNG)
        val png = Base64.getDecoder().decode(STATIC_PNG)
        assertTrue(Apng.isPng(apng))
        assertTrue("acTL 在第一块 IDAT 之前 = APNG", Apng.isApng(apng))
        assertTrue(Apng.isPng(png))
        assertFalse("普通 PNG 不是 APNG", Apng.isApng(png))
        assertFalse(Apng.isPng("GIF89a".toByteArray()))
        assertFalse(Apng.isApng(ByteArray(0)))
    }

    @Test
    fun `APNG 逐帧解码 帧数与颜色都对`() {
        val decoded = Apng.decode(Base64.getDecoder().decode(PILLOW_APNG))
        assertNotNull("APNG 必须能解出来", decoded)
        assertEquals(6, decoded!!.width)
        assertEquals(6, decoded.height)
        assertEquals("帧数必须一帧不少", 4, decoded.frames.size)
        decoded.frames.forEach { assertEquals("帧间隔 100ms", 100, it.delayMs) }
        assertEquals(0xFFFF0000.toInt(), decoded.frames[0].argb[0])
        assertEquals(0xFF00FF00.toInt(), decoded.frames[1].argb[0])
        assertEquals(0xFF0000FF.toInt(), decoded.frames[2].argb[0])
        assertEquals(0xFFFFFF00.toInt(), decoded.frames[3].argb[0])
    }

    @Test
    fun `静态 PNG 当单帧用`() {
        val decoded = Apng.decode(Base64.getDecoder().decode(STATIC_PNG))
        assertNotNull(decoded)
        assertEquals(4, decoded!!.width)
        assertEquals(4, decoded.height)
        assertEquals(1, decoded.frames.size)
        assertEquals("颜色必须与源文件一致（10,20,30）", 0xFF0A141E.toInt(), decoded.frames[0].argb[0])
    }

    @Test
    fun `APNG 能过压缩管线且仍然是动画`() {
        val decoded = Apng.decode(Base64.getDecoder().decode(PILLOW_APNG))!!
        val build = StickerPipeline.build(decoded)
        assertNotNull(build)
        assertEquals("四帧 6×6 不会被降级", 4, build!!.frames)
        val roundTrip = StickerGif.decode(build.bytes)!!
        assertEquals("转成 GIF 之后还是 4 帧", 4, roundTrip.frames.size)
        assertEquals(0xFF00FF00.toInt(), roundTrip.frames[1].argb[0])
    }

    @Test
    fun `坏数据返回 null 而不是抛异常`() {
        assertEquals(null, Apng.decode(ByteArray(0)))
        assertEquals(null, Apng.decode("not a png".toByteArray()))
        // 只有签名、没有 IHDR：不能崩。
        val signatureOnly = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(null, Apng.decode(signatureOnly))
    }

    private companion object {
        /** Pillow 生成：4 帧 6×6（红/绿/蓝/黄），100ms，无限循环。 */
        const val PILLOW_APNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAYAAAAGCAYAAADgzO9IAAAACGFjVEwAAAAEAAAAAHzNZtAAAAAaZmNUTAAAAAAAAAAGAAAABgAAAAAAAAAAAAEACgAAi0C0gQAAABVJREFUeJxj/M/A8J8BC2DCJkgvCQAqAgIK8FmdowAAABpmY1RMAAAAAQAAAAYAAAAGAAAAAAAAAAAAAQAKAAAQM15VAAAAGWZkQVQAAAACeJxjZPjP8J8BC2DCJkgvCQApAwIKFzTzuAAAABpmY1RMAAAAAwAAAAYAAAAGAAAAAAAAAAAAAQAKAAD9pY28AAAAGWZkQVQAAAAEeJxjZGD4/58BC2DCJkgvCQAoBAIKy5K72AAAABpmY1RMAAAABQAAAAYAAAAGAAAAAAAAAAAAAQAKAAAQb//GAAAAGWZkQVQAAAAGeJxj/P+f4T8DFsCETZBeEgC9bgMJzYSSBQAAAABJRU5ErkJggg=="

        /** Pillow 生成的 4×4 纯色 PNG（RGB 10,20,30）。 */
        const val STATIC_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAE0lEQVR4nGPkEpFjgAEmOAsvBwAMqABEMm0NowAAAABJRU5ErkJggg=="
    }
}
