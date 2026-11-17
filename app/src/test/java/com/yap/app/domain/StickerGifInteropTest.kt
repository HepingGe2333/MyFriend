package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Base64

/**
 * 与**外部工具**生成的 GIF 的互通性（另一方向的互通在开发时手工验过：
 * 我们编码出来的文件 Pillow 能读出 3 帧、`loop=0`、`duration=100`、透明像素正确）。
 *
 * 为什么需要这一条：自己的编码器/解码器很容易「自己写、自己读」都通过，
 * 但用户真正导入的表情包是从微信/QQ/浏览器存下来的——那些文件的调色板、disposal、
 * 循环块写法各不相同。这里的样本是 Pillow 生成的 3 帧纯色 GIF（红/绿/蓝、100ms、
 * `disposal=2`、带 NETSCAPE 循环块），正好覆盖「逐帧全画刷 + 全局调色板 + 循环块」。
 */
class StickerGifInteropTest {

    @Test
    fun `外部生成的 GIF 能逐帧解码且颜色与时长都对`() {
        val bytes = Base64.getDecoder().decode(PILLOW_GIF)
        val decoded = StickerGif.decode(bytes)
        assertNotNull("外部 GIF 必须能解出来（解不出来 = 用户导入的表情全挂）", decoded)
        assertEquals(8, decoded!!.width)
        assertEquals(8, decoded.height)
        assertEquals("帧数必须一帧不少", 3, decoded.frames.size)
        decoded.frames.forEach { assertEquals("帧间隔 100ms", 100, it.delayMs) }

        assertEquals("第 1 帧是纯红", 0xFFFF0000.toInt(), decoded.frames[0].argb[0])
        assertEquals("第 2 帧是纯绿", 0xFF00FF00.toInt(), decoded.frames[1].argb[0])
        assertEquals("第 3 帧是纯蓝", 0xFF0000FF.toInt(), decoded.frames[2].argb[0])
        // 每帧都是整幅重画：每个像素都该是当前帧的颜色（disposal=2 不会留下上一帧）。
        decoded.frames.forEachIndexed { index, frame ->
            val expected = listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF)[index].toInt()
            assertEquals("第 ${index + 1} 帧必须是整帧纯色", expected, frame.argb[63])
        }
    }

    @Test
    fun `外部 GIF 再编码后仍是合法 GIF 且帧数不变`() {
        val decoded = StickerGif.decode(Base64.getDecoder().decode(PILLOW_GIF))!!
        val build = StickerPipeline.build(decoded)
        assertNotNull("外部样本也要能过压缩管线", build)
        val again = StickerGif.decode(build!!.bytes)!!
        assertEquals("压过一遍之后帧数仍然一致", 3, again.frames.size)
        assertEquals(8, again.width)
        assertEquals(8, again.height)
    }

    private companion object {
        /** Pillow 生成：3 帧 8×8 纯色（红/绿/蓝），100ms，disposal=2，无限循环。 */
        const val PILLOW_GIF =
            "R0lGODlhCAAIAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQICgAAACwAAAAACAAIAAAIDwABCBxIsKDBgwgTKkwYEAAh+QQICgAAACwAAAAACAAIAIEA/wAAAAAAAAAAAAAIDwABCBxIsKDBgwgTKkwYEAAh+QQICgAAACwAAAAACAAIAIEAAP8AAAAAAAAAAAAIDwABCBxIsKDBgwgTKkwYEAA7"
    }
}
