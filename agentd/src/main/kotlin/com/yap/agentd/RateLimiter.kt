package com.yap.agentd

import kotlin.math.ceil

/**
 * 按来源的**令牌桶**限流（0.12.0）。
 *
 * 为什么要在 HTTP 层做：`agentd` 的 4 个固定 worker 保证了「慢连接打不瘫 `/health`」，
 * 但没有配额——一个脚本可以一直占着 worker 和 CPU。这里给每个来源一个独立的桶，
 * 超限直接回 429，别的来源完全不受影响（不需要全局锁，也不需要因为限流而阻塞其他 IP）。
 *
 * 语义（与常见的 `rate`/`burst` 一致）：
 * - 桶容量 = [burst]（默认 30）：空闲时最多攒这么多，一次性突发不会被误杀；
 * - 补充速率 = [perMinute] / 60 个/秒（默认 60 请求/分钟 = 1 个/秒）；
 * - 每次请求取 1 个令牌，取不到就回 429，并给出「最快多少秒后可以重试」；
 * - [perMinute] = 0 表示关闭限流（**0 = 关**，README 与 `--help` 都这么写）。
 *
 * 状态是「每个来源一个 (令牌数, 最后填充时间)」，用 `LinkedHashMap(accessOrder = true)`：
 * - 访问顺序天然给出「最久没动过的来源」，超过 [maxTrackedClients] 时先删**已经回满的桶**
 *   （删掉与保留等价，下次请求照样从满桶开始），仍然超限就按访问顺序淘汰最旧的；
 * - 因此 map 的大小有硬上限（默认 4096 个来源），扫描大量伪造来源不会把堆吃光，
 *   也不会因为清理而每次请求都做一次全表扫描。
 *
 * 时间源可注入（[clock]，纳秒）：测试里不用真的等一分钟。
 */
class RateLimiter(
    val perMinute: Int = DEFAULT_PER_MINUTE,
    val burst: Int = DEFAULT_BURST,
    private val maxTrackedClients: Int = DEFAULT_MAX_TRACKED_CLIENTS,
    private val clock: () -> Long = System::nanoTime
) {

    init {
        require(perMinute >= 0) { "perMinute 不能是负数：$perMinute" }
        require(burst >= 1) { "burst 至少是 1：$burst" }
        require(maxTrackedClients >= 1) { "maxTrackedClients 至少是 1：$maxTrackedClients" }
    }

    /** `perMinute = 0` 即关闭：`tryAcquire` 恒放行，也不记录任何来源。 */
    val enabled: Boolean get() = perMinute > 0

    /** 桶容量 = burst。 */
    val capacity: Double get() = burst.toDouble()

    /** 每秒补充多少令牌（0 表示关闭）。 */
    val refillPerSecond: Double get() = perMinute / 60.0

    private val refillPerNano: Double = refillPerSecond / 1_000_000_000.0

    /** 一个来源的桶：剩余令牌（带小数，便于按时间平滑补充）+ 上次填充的单调时间。 */
    private class Bucket(var tokens: Double, var lastRefillNanos: Long)

    // accessOrder = true：get / put 会把条目挪到队尾，队首就是「最久没访问」的那个。
    private val buckets = LinkedHashMap<String, Bucket>(64, 0.75f, true)
    private val lock = Any()

    /** 一次限流判定。拒绝时 [retryAfterSeconds] 至少为 1（HTTP `Retry-After` 不允许 0）。 */
    data class Decision(val allowed: Boolean, val retryAfterSeconds: Int = 0)

    /**
     * 尝试为一个来源取一个令牌。
     *
     * @param client 来源标识（服务端传 `<scope>|<ip>`；单测可以直接用任意字符串）。
     */
    fun tryAcquire(client: String): Decision {
        if (!enabled) return ALLOWED
        val now = clock()
        synchronized(lock) {
            val bucket = buckets[client] ?: Bucket(capacity, now).also { buckets[client] = it }
            // prune 放在插入之后：本次来源刚被访问（队尾），清理只会从队首淘汰别人，
            // 调用返回时 map 大小一定 ≤ maxTrackedClients。
            prune(now)
            // 惰性填充：只有这个来源再次出现时才补，不需要后台线程/定时器。
            val elapsed = now - bucket.lastRefillNanos
            if (elapsed > 0) {
                bucket.tokens = minOf(capacity, bucket.tokens + elapsed * refillPerNano)
                bucket.lastRefillNanos = now
            }
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                return ALLOWED
            }
            val seconds = ceil((1.0 - bucket.tokens) / refillPerSecond).toInt().coerceAtLeast(1)
            return Decision(allowed = false, retryAfterSeconds = seconds)
        }
    }

    /** 当前跟踪了多少个来源（测试用；生产里就是「内存占用」的度量）。 */
    internal fun trackedClients(): Int = synchronized(lock) { buckets.size }

    /**
     * 有界清理：只在超过上限时动手，摊到每次请求的代价是 O(超出量)。
     */
    private fun prune(now: Long) {
        if (buckets.size <= maxTrackedClients) return
        // 第一轮：删掉「已经回满」的桶。它们的下一位访问者本来就会得到一个满桶，
        // 所以这是无损清理，优先做它可以让活跃来源一个都不丢。
        val iterator = buckets.entries.iterator()
        while (buckets.size > maxTrackedClients && iterator.hasNext()) {
            val bucket = iterator.next().value
            val refilled = bucket.tokens + (now - bucket.lastRefillNanos).coerceAtLeast(0L) * refillPerNano
            if (refilled >= capacity) iterator.remove()
        }
        // 第二轮：还是超就按访问顺序淘汰最久没来的来源（硬上限，绝不无限增长）。
        while (buckets.size > maxTrackedClients) {
            val oldest = buckets.keys.firstOrNull() ?: break
            buckets.remove(oldest)
        }
    }

    companion object {
        /** 默认 60 请求/分钟（= 1 个/秒）。 */
        const val DEFAULT_PER_MINUTE = 60

        /** 默认突发 30：空闲时攒下的令牌最多 30 个。 */
        const val DEFAULT_BURST = 30

        /** 最多跟踪多少个来源；超过后按「回满优先、最久未访问其次」清理。 */
        const val DEFAULT_MAX_TRACKED_CLIENTS = 4096

        private val ALLOWED = Decision(allowed = true)
    }
}
