package com.yap.agentd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 令牌桶的单元测试：不启动服务、也不真的等时间（注入单调时钟）。
 *
 * 服务端集成用例（429 / `Retry-After` / `/health` 独立预算）在 `AgentdServerTest` 里；
 * 这里覆盖「不同来源互不影响」「关闭」「有界清理」这类用真实 socket 不好构造的部分
 * （本机尽力而为也只能用一个来源 IP 发请求）。
 */
class RateLimiterTest {

    /** 1 个测试 1 个假时钟：纳秒，单调递增。 */
    private var nowNanos = 0L

    private fun limiter(
        perMinute: Int = 60,
        burst: Int = 2,
        maxTrackedClients: Int = 16
    ): RateLimiter = RateLimiter(perMinute, burst, maxTrackedClients) { nowNanos }

    private fun advance(seconds: Double) {
        nowNanos += (seconds * 1_000_000_000.0).toLong()
    }

    @Test
    fun `burst is allowed then the bucket is empty`() {
        val limiter = limiter(perMinute = 60, burst = 2)
        assertTrue(limiter.tryAcquire("127.0.0.1").allowed)
        assertTrue(limiter.tryAcquire("127.0.0.1").allowed)
        val denied = limiter.tryAcquire("127.0.0.1")
        assertFalse("突发用完之后必须拒绝", denied.allowed)
        assertEquals("按 1 个/秒补充，耗尽后要等 1 秒", 1, denied.retryAfterSeconds)
    }

    @Test
    fun `retry after reflects the configured rate`() {
        // 30 请求/分钟 = 0.5 个/秒：空桶要等 2 秒才补回 1 个。
        val limiter = limiter(perMinute = 30, burst = 1)
        assertTrue(limiter.tryAcquire("a").allowed)
        val denied = limiter.tryAcquire("a")
        assertFalse(denied.allowed)
        assertEquals(2, denied.retryAfterSeconds)
        advance(1.0)
        assertFalse("只过了 1 秒还不够", limiter.tryAcquire("a").allowed)
        advance(1.0)
        assertTrue("过了 2 秒必须放行", limiter.tryAcquire("a").allowed)
    }

    @Test
    fun `refill is capped at the burst size`() {
        val limiter = limiter(perMinute = 60, burst = 3)
        repeat(3) { assertTrue(limiter.tryAcquire("a").allowed) }
        assertFalse(limiter.tryAcquire("a").allowed)
        // 空闲一小时也只攒到 burst 个，不会攒出「无限突发」。
        advance(3600.0)
        repeat(3) { assertTrue(limiter.tryAcquire("a").allowed) }
        assertFalse("攒满之后第 4 个必须被拒绝", limiter.tryAcquire("a").allowed)
    }

    @Test
    fun `different sources do not affect each other`() {
        val limiter = limiter(perMinute = 60, burst = 1)
        assertTrue(limiter.tryAcquire("core|10.0.0.1").allowed)
        assertFalse(limiter.tryAcquire("core|10.0.0.1").allowed)
        assertTrue("另一个来源有自己的桶", limiter.tryAcquire("core|10.0.0.2").allowed)
        assertTrue("同一 IP 的 health 桶也是独立的", limiter.tryAcquire("health|10.0.0.1").allowed)
    }

    @Test
    fun `per minute zero disables the limiter`() {
        val limiter = limiter(perMinute = 0, burst = 1)
        assertFalse(limiter.enabled)
        repeat(100) { assertTrue(limiter.tryAcquire("a").allowed) }
        assertEquals("关闭时不记录任何来源", 0, limiter.trackedClients())
    }

    @Test
    fun `tracked sources are bounded`() {
        val limiter = limiter(perMinute = 1, burst = 1, maxTrackedClients = 2)
        // 扫一遍很多「伪造来源」：map 不能跟着涨。
        for (index in 0 until 100) {
            limiter.tryAcquire("core|10.0.0.$index")
            assertTrue(
                "跟踪的来源数必须 ≤ 上限，实际 ${limiter.trackedClients()}",
                limiter.trackedClients() <= 2
            )
        }
    }

    @Test
    fun `idle full buckets are the first to be evicted`() {
        val limiter = limiter(perMinute = 60, burst = 1, maxTrackedClients = 2)
        // a、b 各用掉 1 个令牌（桶空），c 是新来源。
        assertTrue(limiter.tryAcquire("a").allowed)
        assertTrue(limiter.tryAcquire("b").allowed)
        // 等一小时：a、b 都回满了 —— 它们是「删掉也无所谓」的桶，先被清掉。
        advance(3600.0)
        assertTrue(limiter.tryAcquire("c").allowed)
        assertTrue("上限必须守住，实际 ${limiter.trackedClients()}", limiter.trackedClients() <= 2)
        // a 的桶被清掉后重新出现，按满桶计算：仍然可以正常拿到令牌（不会误封）。
        assertTrue(limiter.tryAcquire("a").allowed)
    }
}
