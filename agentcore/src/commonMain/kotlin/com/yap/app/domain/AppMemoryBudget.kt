package com.yap.app.domain

/**
 * 图片加载器的内存预算。
 *
 * Coil 默认的图片内存缓存是「可用内存的 25%」——在 8GB 的手机上那可能是 1GB 起步的
 * 上限。上限不是实际占用，但它决定了系统压力上来之前缓存愿意替我们攒多少张图；
 * 对一个「头像 + 聊天图片」的应用，这个额度大得没有意义，反而把本来可以还给系统的
 * 内存留在了进程里。这里收成可用内存的 12%，并且最多 48MB：
 *
 * - 12%：和 Android 官方图片库常用的 1/8 量级接近，留出足够缓存头像/缩略图的余量；
 * - 48MB：512px JPEG 头像解码后约 1MB，48MB 够放几十张；再大就是在缓存用户翻过的旧图。
 *
 * 纯计算，单测直接钉住「12% 与 48MB 取小」这条规则，避免以后有人顺手改成 25% 又没人发现。
 */
object AppMemoryBudget {

    /** 图片内存缓存占「可用内存」的比例。 */
    const val IMAGE_MEMORY_CACHE_FRACTION = 0.12

    /** 图片内存缓存硬上限：48MB。 */
    const val IMAGE_MEMORY_CACHE_MAX_BYTES = 48L * 1024 * 1024

    /** 图片磁盘缓存上限：128MB（聊天图片缩略图 + 头像原始文件之外的第二层缓存）。 */
    const val IMAGE_DISK_CACHE_BYTES = 128L * 1024 * 1024

    /** 图片磁盘缓存目录名（App 私有 cacheDir 下）。 */
    const val IMAGE_DISK_CACHE_DIR = "image_cache"

    /**
     * 按可用内存算图片内存缓存上限：`min(available × 12%, 48MB)`。
     *
     * 传入 0 / 负数时返回 0，让 Coil 退化成「不缓存」而不是抛异常。
     */
    fun imageMemoryCacheBytes(availableMemoryBytes: Long): Long {
        if (availableMemoryBytes <= 0L) return 0L
        val proportional = (availableMemoryBytes * IMAGE_MEMORY_CACHE_FRACTION).toLong()
        return minOf(proportional, IMAGE_MEMORY_CACHE_MAX_BYTES)
    }
}
