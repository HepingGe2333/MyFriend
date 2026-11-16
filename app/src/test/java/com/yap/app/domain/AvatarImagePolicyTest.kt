package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 头像落盘预算：长边、采样、压缩质量阶梯、体积上限。
 *
 * 这些数字是「内存上限」的第一道闸：一张 12MP 的相册图如果原样解开再缩，
 * 峰值是几十 MB；而按这套预算采样后，解码和落盘都只有几百 KB。
 */
class AvatarImagePolicyTest {

    @Test
    fun `横图与竖图都按长边缩到 512`() {
        assertEquals(
            AvatarImagePolicy.PixelSize(512, 384),
            AvatarImagePolicy.targetSize(4000, 3000)
        )
        assertEquals(
            AvatarImagePolicy.PixelSize(384, 512),
            AvatarImagePolicy.targetSize(3000, 4000)
        )
        // 正方形
        assertEquals(
            AvatarImagePolicy.PixelSize(512, 512),
            AvatarImagePolicy.targetSize(2000, 2000)
        )
        // 极端长图：短边至少留 1px
        assertEquals(
            AvatarImagePolicy.PixelSize(1, 512),
            AvatarImagePolicy.targetSize(2, 10_000)
        )
    }

    @Test
    fun `小图不放大`() {
        assertEquals(
            AvatarImagePolicy.PixelSize(200, 150),
            AvatarImagePolicy.targetSize(200, 150)
        )
        // 刚好等于上限也不动
        assertEquals(
            AvatarImagePolicy.PixelSize(512, 512),
            AvatarImagePolicy.targetSize(512, 512)
        )
    }

    @Test
    fun `非法尺寸返回 0 让调用方报错`() {
        assertEquals(AvatarImagePolicy.PixelSize(0, 0), AvatarImagePolicy.targetSize(0, 100))
        assertEquals(AvatarImagePolicy.PixelSize(0, 0), AvatarImagePolicy.targetSize(100, -1))
        assertEquals(AvatarImagePolicy.PixelSize(0, 0), AvatarImagePolicy.targetSize(100, 100, maxEdge = 0))
    }

    @Test
    fun `inSampleSize 取 2 的幂且解码结果不小于目标`() {
        // 4000 长边：/2=2000、/4=1000 都 >=512，/8=500 <512 → 采样 4（解出 1000 再精确缩）。
        assertEquals(4, AvatarImagePolicy.sampleSize(4000, 3000))
        // 512 本身不用再粗采样
        assertEquals(1, AvatarImagePolicy.sampleSize(512, 400))
        // 800：/2=400 <512 → 1（解出 800 再精确缩到 512）
        assertEquals(1, AvatarImagePolicy.sampleSize(800, 600))
        // 2048：/2=1024>=512 →2；/4=512>=512 →4；/8=256<512 → 4
        assertEquals(4, AvatarImagePolicy.sampleSize(2048, 1536))
        assertEquals(1, AvatarImagePolicy.sampleSize(0, 0))
    }

    @Test
    fun `采样后的解码尺寸不会小于目标边长`() {
        listOf(4000, 3000, 2048, 1024, 800, 513).forEach { edge ->
            val sample = AvatarImagePolicy.sampleSize(edge, edge)
            val decoded = edge / sample
            assertTrue("edge=$edge sample=$sample decoded=$decoded", decoded >= 512)
        }
    }

    @Test
    fun `质量阶梯从 85 降到 55 每档 10`() {
        assertEquals(listOf(85, 75, 65, 55), AvatarImagePolicy.qualityLadder())
    }

    @Test
    fun `体积上限是 200KB 且空文件不算通过`() {
        assertTrue(AvatarImagePolicy.fits(1))
        assertTrue(AvatarImagePolicy.fits(AvatarImagePolicy.MAX_BYTES.toLong()))
        assertFalse(AvatarImagePolicy.fits(0))
        assertFalse(AvatarImagePolicy.fits(AvatarImagePolicy.MAX_BYTES + 1L))
    }

    @Test
    fun `预算常量就是规格里的 512 85 200KB`() {
        assertEquals(512, AvatarImagePolicy.MAX_EDGE_PX)
        assertEquals(85, AvatarImagePolicy.JPEG_QUALITY)
        assertEquals(200 * 1024, AvatarImagePolicy.MAX_BYTES)
    }
}
