package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一拍思考的非重入闸（审计 P2-4）。
 *
 * 三条入口（前台服务心跳 / WorkManager 兜底 / 主动规则 Worker）都会直接调 `AgentLoop.tick`，
 * 而 tick 里是「先查今天说够没 → 再发」的 check-then-act。没有互斥时两条并发 tick
 * 都能通过检查，用户收到两条几乎一样的消息。
 */
class AgentTickLockTest {

    @Test
    fun `同一时刻只有一个持有者`() {
        val lock = AgentTickLock()
        assertTrue(lock.tryEnter())
        assertTrue(lock.isHeld)
        assertFalse("第二拍必须被挡掉", lock.tryEnter())
        lock.exit()
        assertFalse(lock.isHeld)
        assertTrue("释放之后下一拍可以进", lock.tryEnter())
        lock.exit()
    }

    @Test
    fun `拿不到锁时直接跳过 不排队`() {
        val lock = AgentTickLock()
        assertTrue(lock.tryEnter())
        assertEquals("拿不到锁返回 null，调用方按「没发生」收场", null, lock.withLock { "ran" })
        lock.exit()
        assertEquals("ran", lock.withLock { "ran" })
    }

    @Test
    fun `代码块抛异常时锁一定释放`() {
        val lock = AgentTickLock()
        runCatching { lock.withLock<Unit> { error("boom") } }
        assertFalse("失败不能让思考永远停摆", lock.isHeld)
        assertTrue(lock.tryEnter())
        lock.exit()
    }

    @Test
    fun `重复释放是幂等的`() {
        val lock = AgentTickLock()
        lock.exit()
        lock.exit()
        assertTrue(lock.tryEnter())
    }

    @Test
    fun `两个线程并发只有一个人能进`() {
        val lock = AgentTickLock()
        val start = CountDownLatch(1)
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val workers = (0 until 8).map {
            Thread {
                start.await()
                repeat(200) {
                    if (lock.tryEnter()) {
                        val now = inside.incrementAndGet()
                        maxInside.getAndUpdate { previous -> maxOf(previous, now) }
                        inside.decrementAndGet()
                        lock.exit()
                    }
                }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals("任何时刻最多一个 tick 在跑", 1, maxInside.get())
    }

    @Test
    fun `并发下 withLock 的返回值与 null 互斥`() {
        val lock = AgentTickLock()
        val start = CountDownLatch(1)
        val ran = AtomicInteger(0)
        val workers = (0 until 4).map {
            Thread {
                start.await()
                repeat(100) {
                    val result = lock.withLock {
                        ran.incrementAndGet()
                        true
                    }
                    if (result != null) assertTrue(result)
                }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }
        assertTrue("至少跑过一些", ran.get() > 0)
        assertFalse("全部结束后锁必须是自由的", lock.isHeld)
    }
}
