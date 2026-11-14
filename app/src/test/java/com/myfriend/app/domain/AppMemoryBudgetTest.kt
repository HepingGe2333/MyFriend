package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coil 图片内存缓存上限：`min(可用内存 × 12%, 48MB)`。
 *
 * 这条规则以前根本不存在：Coil 默认吃可用内存的 25% 当缓存上限，
 * 在大内存机器上等于几百 MB 的额度。单测把 12% 和 48MB 两道闸钉住。
 */
class AppMemoryBudgetTest {

    @Test
    fun `小内存设备按 12% 算`() {
        // 100MB 可用 × 12% = 12MB，低于 48MB 上限。
        assertEquals(
            12L * 1024 * 1024,
            AppMemoryBudget.imageMemoryCacheBytes(100L * 1024 * 1024)
        )
        // 512MB × 12% ≈ 61MB，会被 48MB 上限截住。
        assertEquals(
            AppMemoryBudget.IMAGE_MEMORY_CACHE_MAX_BYTES,
            AppMemoryBudget.imageMemoryCacheBytes(512L * 1024 * 1024)
        )
    }

    @Test
    fun `大内存设备封顶 48MB`() {
        assertEquals(
            48L * 1024 * 1024,
            AppMemoryBudget.imageMemoryCacheBytes(8L * 1024 * 1024 * 1024)
        )
        assertEquals(48L * 1024 * 1024, AppMemoryBudget.IMAGE_MEMORY_CACHE_MAX_BYTES)
    }

    @Test
    fun `12% 与 48MB 的边界`() {
        // 刚好 400MB × 12% = 48MB：不超上限。
        assertEquals(48L * 1024 * 1024, AppMemoryBudget.imageMemoryCacheBytes(400L * 1024 * 1024))
        // 比 400MB 多 1MB：仍封顶 48MB。
        assertEquals(48L * 1024 * 1024, AppMemoryBudget.imageMemoryCacheBytes(401L * 1024 * 1024))
    }

    @Test
    fun `拿不到可用内存时退回 0`() {
        assertEquals(0L, AppMemoryBudget.imageMemoryCacheBytes(0))
        assertEquals(0L, AppMemoryBudget.imageMemoryCacheBytes(-1))
    }

    @Test
    fun `磁盘缓存与比例常量符合规格`() {
        assertEquals(0.12, AppMemoryBudget.IMAGE_MEMORY_CACHE_FRACTION, 0.0)
        assertEquals(128L * 1024 * 1024, AppMemoryBudget.IMAGE_DISK_CACHE_BYTES)
    }
}
