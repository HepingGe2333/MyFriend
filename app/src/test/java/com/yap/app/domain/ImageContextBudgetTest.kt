package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片上下文的双重限制（审计 P2-9）。
 *
 * 这一条修的是「12 张 4MB 原图 → base64 → 拼 JSON → OOM」：先钉住数量上限，
 * 再钉住单图尺寸/字节与整体字符串预算。
 */
class ImageContextBudgetTest {

    private fun candidate(id: String, isUser: Boolean = true, hasImages: Boolean = true) =
        ImageContextBudget.Candidate(messageId = id, isUser = isUser, hasImages = hasImages)

    @Test
    fun `只带最近两条带图消息`() {
        val owners = ImageContextBudget.ownersWithImages(
            listOf(
                candidate("m1"),
                candidate("m2"),
                candidate("m3"),
                candidate("m4"),
                candidate("m5")
            )
        )
        assertEquals(setOf("m4", "m5"), owners)
        assertEquals(2, ImageContextBudget.MAX_IMAGE_MESSAGES_IN_CONTEXT)
    }

    @Test
    fun `助手消息与没有附件的消息不占名额`() {
        val owners = ImageContextBudget.ownersWithImages(
            listOf(
                candidate("a1", isUser = false),
                candidate("u1"),
                candidate("u2", hasImages = false),
                candidate("a2", isUser = false),
                candidate("u3")
            )
        )
        assertEquals(setOf("u1", "u3"), owners)
    }

    @Test
    fun `空列表返回空集合`() {
        assertTrue(ImageContextBudget.ownersWithImages(emptyList()).isEmpty())
    }

    @Test
    fun `单条消息最多四张图`() {
        assertEquals(4, ImageContextBudget.imagesForMessage(9))
        assertEquals(2, ImageContextBudget.imagesForMessage(2))
        assertEquals(0, ImageContextBudget.imagesForMessage(-3))
        assertEquals(4, ImageContextBudget.MAX_IMAGES_PER_MESSAGE)
    }

    @Test
    fun `一共最多八张图 且最坏情况仍在字符串预算内`() {
        assertEquals(8, ImageContextBudget.MAX_IMAGES_IN_CONTEXT)
        val worst = ImageContextBudget.worstCaseEncodedChars()
        assertTrue(
            "最坏情况 $worst 字符超了上下文预算 ${ImageContextBudget.MAX_CONTEXT_ENCODED_CHARS}",
            worst <= ImageContextBudget.MAX_CONTEXT_ENCODED_CHARS
        )
    }

    @Test
    fun `发送前按目标尺寸重编码 单张字节数与长边都有上限`() {
        assertTrue("长边要压到 1024 以内", ImageContextBudget.TARGET_EDGE_PX <= 1024)
        assertTrue("单张要压到 320KB 以内", ImageContextBudget.MAX_ENCODED_BYTES <= 320 * 1024)
        assertTrue("质量阶梯必须递减", ImageContextBudget.JPEG_QUALITY_LADDER.zipWithNext().all { (a, b) -> a > b })
        assertTrue("最低质量不能太低（否则截图看不清）", ImageContextBudget.JPEG_QUALITY_LADDER.last() >= 45)
    }

    // ------------------------------------------------------------------ 审计 P2-10（0.11.1）：目标尺寸

    @Test
    fun `长图按长边缩到目标尺寸`() {
        // API 26–27 的 BitmapFactory 只能粗采样（4000 → 2000），必须再缩到 1024。
        assertEquals(1024 to 768, ImageContextBudget.scaledSize(4000, 3000))
        assertEquals(768 to 1024, ImageContextBudget.scaledSize(3000, 4000))
        assertEquals(1024 to 1024, ImageContextBudget.scaledSize(4096, 4096))
        assertEquals(1024 to 1, ImageContextBudget.scaledSize(100_000, 1))
    }

    @Test
    fun `已经够小的图不缩`() {
        assertNull("长边本来就 ≤1024，不该再缩一次", ImageContextBudget.scaledSize(1024, 768))
        assertNull(ImageContextBudget.scaledSize(300, 200))
        assertNull("宽高相等也要看长边", ImageContextBudget.scaledSize(1024, 1024))
    }

    @Test
    fun `非法尺寸返回 null 而不是除零`() {
        assertNull(ImageContextBudget.scaledSize(0, 100))
        assertNull(ImageContextBudget.scaledSize(-5, 100))
        assertNull(ImageContextBudget.scaledSize(100, 100, targetEdge = 0))
    }

    @Test
    fun `整体字符预算会挡住超出的那一张`() {
        val used = ImageContextBudget.MAX_CONTEXT_ENCODED_CHARS - 10
        val small = "data:image/jpeg;base64,AAAA"
        assertFalse("塞不下就得跳过", ImageContextBudget.acceptsEncoded(used, small))
        assertTrue(ImageContextBudget.acceptsEncoded(0, small))
        assertFalse("解不出来（null）不占预算也不塞", ImageContextBudget.acceptsEncoded(0, null))
    }
}
