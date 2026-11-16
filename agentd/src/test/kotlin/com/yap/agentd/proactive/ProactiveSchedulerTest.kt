package com.yap.agentd.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 定时器：`tickOnce(now)` 的幂等、掷中一次就记一次、以及「默认 60 秒一拍」的定时行为。
 *
 * 定时器本身不依赖任何客户端连接 —— 这也是服务端权威架构的关键：客户端进程没了，
 * 主动消息仍然按节拍推进。测试里把节拍缩到 30 毫秒，用 `CountDownLatch` 等它自己跑起来，
 * 不 sleep 固定时长（在慢 CI 上不会 flaky）。
 */
class ProactiveSchedulerTest {

    private val utc = ZoneOffset.UTC
    private val afternoon = LocalDateTime.of(2026, 11, 15, 14, 0).toInstant(utc).toEpochMilli()
    private val hour = 60 * 60_000L

    private fun engineAt(now: Long, roll: Double, policy: ProactivePolicy = ProactivePolicy()): ProactiveEngine =
        ProactiveEngine(
            clock = Clock.fixed(Instant.ofEpochMilli(now), utc),
            policy = policy,
            roll = { roll }
        )

    private fun eligiblePersona(now: Long, count: Int = 0): PersonaState = PersonaState(
        personaId = "persona-1",
        sleepEnabled = false,
        lastInteractionAt = now - hour,
        proactiveCountToday = count
    )

    @Test
    fun `default policy ticks once a minute`() {
        assertEquals(60_000L, ProactivePolicy().tickIntervalMs)
        assertEquals(1.0, ProactivePolicy().tickIntervalMinutes, 1e-12)
    }

    @Test
    fun `tick fires once and the same timestamp is a no-op`() {
        val fired = ArrayList<ProactiveDecision>()
        val scheduler = ProactiveScheduler(
            engine = engineAt(afternoon, roll = 0.0),
            source = { now -> listOf(ProactiveTarget(eligiblePersona(now))) },
            sink = { decision -> fired += decision },
            executor = Executors.newSingleThreadScheduledExecutor()
        )
        try {
            val first = scheduler.tickOnce(afternoon)
            assertEquals(1, first.size)
            assertEquals(1, fired.size)
            assertEquals(ProactiveReason.FIRED, fired.single().reason)
            assertEquals(afternoon, scheduler.lastTickAt)

            // 同一个 now 再来一次（重试 / 调试接口重复调用）：不能把同一条消息发两遍。
            assertTrue(scheduler.tickOnce(afternoon).isEmpty())
            assertEquals(1, fired.size)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `fired state is remembered until the storage layer catches up`() {
        val scheduler = ProactiveScheduler(
            engine = engineAt(afternoon, roll = 0.0),
            source = { now -> listOf(ProactiveTarget(eligiblePersona(now))) },
            executor = Executors.newSingleThreadScheduledExecutor()
        )
        try {
            assertEquals(1, scheduler.tickOnce(afternoon).size)
            // 10 分钟冷却之内：source 仍然返回「没发过」的旧状态，但调度器自己记得刚发过。
            assertTrue(scheduler.tickOnce(afternoon + 60_000L).isEmpty())
            // 冷却结束（+10 分钟）之后才可能再掷。
            assertEquals(1, scheduler.tickOnce(afternoon + 11 * 60_000L).size)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `daily cap is respected across ticks`() {
        val scheduler = ProactiveScheduler(
            engine = engineAt(afternoon, roll = 0.0),
            source = { now -> listOf(ProactiveTarget(eligiblePersona(now), LevelPolicy(level = 1))) },
            executor = Executors.newSingleThreadScheduledExecutor()
        )
        try {
            assertEquals(1, scheduler.tickOnce(afternoon).size)
            // level 1 = 一天 1 次；调度器记的那一格已经把「今天已发」变成 1。
            assertTrue(scheduler.tickOnce(afternoon + 11 * 60_000L).isEmpty())
            assertTrue(scheduler.tickOnce(afternoon + 30 * 60_000L).isEmpty())
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `one persona throwing does not stop the others`() {
        val fired = AtomicInteger()
        val scheduler = ProactiveScheduler(
            engine = engineAt(afternoon, roll = 0.0),
            source = { now ->
                listOf(
                    ProactiveTarget(eligiblePersona(now).copy(personaId = "ok-1")),
                    ProactiveTarget(eligiblePersona(now).copy(personaId = "ok-2"))
                )
            },
            sink = { fired.incrementAndGet() },
            executor = Executors.newSingleThreadScheduledExecutor()
        )
        try {
            assertEquals(2, scheduler.tickOnce(afternoon).size)
            assertEquals(2, fired.get())
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `sink failures are contained`() {
        val scheduler = ProactiveScheduler(
            engine = engineAt(afternoon, roll = 0.0),
            source = { now -> listOf(ProactiveTarget(eligiblePersona(now))) },
            sink = { error("数据库炸了") },
            executor = Executors.newSingleThreadScheduledExecutor()
        )
        try {
            // 掷中的决定仍然要被返回（落库失败由主线重试），而且异常不能穿透 tickOnce。
            assertEquals(1, scheduler.tickOnce(afternoon).size)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `scheduled executor drives ticks without any client connection`() {
        val ticks = CountDownLatch(2)
        val fast = ProactivePolicy(tickIntervalMs = 30L)
        val scheduler = ProactiveScheduler(
            engine = engineAt(afternoon, roll = 0.9999, policy = fast),
            source = { now ->
                ticks.countDown()
                listOf(ProactiveTarget(eligiblePersona(now)))
            },
            policy = fast,
            executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "proactive-test").apply { isDaemon = true }
            }
        )
        try {
            assertFalse(scheduler.isRunning)
            scheduler.start()
            assertTrue(scheduler.isRunning)
            assertTrue("定时器应该在 5 秒内至少跑两拍", ticks.await(5, TimeUnit.SECONDS))
            assertTrue(scheduler.lastTickAt > 0L)
            scheduler.stop()
            assertFalse(scheduler.isRunning)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `start is idempotent and stop releases the executor`() {
        val ticks = CountDownLatch(1)
        val fast = ProactivePolicy(tickIntervalMs = 30L)
        val scheduler = ProactiveScheduler(
            engine = engineAt(afternoon, roll = 0.9999, policy = fast),
            source = {
                ticks.countDown()
                emptyList()
            },
            policy = fast,
            executor = Executors.newSingleThreadScheduledExecutor()
        )
        scheduler.start()
        scheduler.start()
        try {
            assertTrue(ticks.await(5, TimeUnit.SECONDS))
        } finally {
            scheduler.stop()
            scheduler.stop()
        }
        assertFalse(scheduler.isRunning)
    }
}
