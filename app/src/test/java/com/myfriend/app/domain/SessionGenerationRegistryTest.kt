package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话世代号（审计 P1-2 / P1-6 的共同底座）。
 *
 * 语义只有一句：**在途写入开始时记下的世代号，必须等于写回前的世代号**。
 * 清空 / 删除 / 整库替换都会 bump，于是所有旧写入自动作废——比「取消协程」可靠，
 * 因为取消是异步的，而世代比较发生在写库之前、是同步的。
 */
class SessionGenerationRegistryTest {

    @Test
    fun `没 bump 过时是 0 且原世代有效`() {
        val registry = SessionGenerationRegistry()
        assertEquals(0L, registry.current("s"))
        assertTrue(registry.isCurrent("s", 0L))
    }

    @Test
    fun `bump 之后旧世代立即作废`() {
        val registry = SessionGenerationRegistry()
        val start = registry.current("s")
        val next = registry.bump("s")
        assertNotEquals(start, next)
        assertFalse("开始时的世代已经过期：在途写入必须丢弃", registry.isCurrent("s", start))
        assertTrue(registry.isCurrent("s", next))
    }

    @Test
    fun `世代按会话隔离`() {
        val registry = SessionGenerationRegistry()
        registry.bump("A")
        assertTrue("B 没被 bump 过，仍然有效", registry.isCurrent("B", 0L))
        assertFalse(registry.isCurrent("A", 0L))
    }

    @Test
    fun `forget 之后回到 0`() {
        val registry = SessionGenerationRegistry()
        registry.bump("A")
        registry.forget("A")
        assertEquals(0L, registry.current("A"))
        assertTrue(registry.isCurrent("A", 0L))
    }

    @Test
    fun `并发 bump 不丢计数`() {
        val registry = SessionGenerationRegistry()
        val threads = 8
        val perThread = 100
        val start = CountDownLatch(1)
        val errors = AtomicInteger(0)
        val workers = (0 until threads).map {
            Thread {
                start.await()
                repeat(perThread) {
                    val before = registry.current("s")
                    val after = registry.bump("s")
                    if (after <= before) errors.incrementAndGet()
                }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals("每次 bump 都必须严格递增", 0, errors.get())
        assertEquals((threads * perThread).toLong(), registry.current("s"))
    }
}
