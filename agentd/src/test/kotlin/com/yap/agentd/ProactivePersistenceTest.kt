package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.MessageSource
import com.yap.agentd.store.ProactiveState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 主动计数落盘（0.12 遗留缺陷 1）。
 *
 * 以前「今天说到了几次 / 上次什么时候开口」只活在 `ProactiveScheduler` 的进程内 overlay：
 * - 重启后 `ToolsRoutes` 又从 store 构造 `proactiveCountToday = 0`、`lastProactiveAt = null`，
 *   于是当天可能重复开口、甚至突破每日上限；
 * - 跨过本地零点不会把持久化计数清掉（因为根本没有持久化计数）。
 *
 * 这里用真临时目录 + `proactive_state.json` 钉住四件事：
 * 1. 重启后计数与上次主动时间都还在；
 * 2. 同一天第二次 tick 被冷却/每日上限挡住；
 * 3. 跨本地零点自动从 1 重新计数；
 * 4. 两个 tick 并发时只有一个能落库（store 锁内复查，不只靠调度器的进程内去重）。
 */
class ProactivePersistenceTest {

    private lateinit var root: Path
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-15 14:00（本地），秒级整数，方便手算。 */
    private val afternoon = Instant.parse("2026-11-15T06:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-proactive-state-test")
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun localDate(epochMillis: Long): String =
        java.time.LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zone).toString()

    private fun openStore(interactionAt: Long): FileStore = FileStore.open(root, { interactionAt })

    private fun ticker(store: FileStore, now: Long, roll: () -> Double = { 0.0 }): ProactiveTicker =
        ProactiveTicker(
            store = store,
            clock = Clock.fixed(Instant.ofEpochMilli(now), zone),
            roll = roll
        )

    /** 先造一条很久以前的用户消息，让「最近互动」过冷却期；返回会话 id。 */
    private fun seedInteraction(store: FileStore, at: Long): String {
        store.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.USER, "在吗")
        return FileStore.SEED_SESSION_ID
    }

    private fun proactiveMessages(store: FileStore, sessionId: String) =
        store.latestMessages(sessionId, 100).filter { it.source == MessageSource.PROACTIVE }

    @Test
    fun `proactive count and last fired time survive a restart`() {
        val interactionAt = afternoon - 6 * 60 * 60_000L
        val first = openStore(interactionAt)
        val sessionId = seedInteraction(first, interactionAt)
        val today = localDate(afternoon)

        // 直接写 5 次（超过任何主动程度上限），重启后只验证「读得回来」，不依赖上限的具体数值。
        repeat(5) { index ->
            first.recordProactiveFired(
                personaId = FileStore.SEED_PERSONA_1_ID,
                at = afternoon + index,
                today = today
            )
        }
        val persisted = first.proactiveState(FileStore.SEED_PERSONA_1_ID, today)
        assertEquals(5, persisted.proactiveCountToday)
        assertEquals(today, persisted.proactiveCountDate)
        assertEquals(afternoon + 4, persisted.lastProactiveAt)
        assertTrue("主动状态要真的落盘", Files.isRegularFile(root.resolve(FileStore.PROACTIVE_STATE_FILE)))

        val reopened = openStore(interactionAt)
        val restored = reopened.proactiveState(FileStore.SEED_PERSONA_1_ID, today)
        assertEquals("重启后当天计数不能归零", 5, restored.proactiveCountToday)
        assertEquals("重启后上次主动时间不能丢", afternoon + 4, restored.lastProactiveAt)
        assertEquals(today, restored.proactiveCountDate)

        // 重启后的第一拍：计数已经满了，不该再开口。
        val fired = ticker(reopened, afternoon + 11 * 60_000L).tick(afternoon + 11 * 60_000L)
        assertTrue("计数已满时重启后仍必须被每日上限挡住", fired.isEmpty())
        assertEquals(0, proactiveMessages(reopened, sessionId).size)
        assertEquals(5, reopened.proactiveState(FileStore.SEED_PERSONA_1_ID, today).proactiveCountToday)
    }

    @Test
    fun `same day second tick is blocked by cooldown and the daily cap`() {
        val interactionAt = afternoon - 6 * 60 * 60_000L
        val store = openStore(interactionAt)
        val sessionId = seedInteraction(store, interactionAt)
        val ticker = ticker(store, afternoon)

        assertEquals(1, ticker.tick(afternoon).size)
        // 10 分钟冷却之内：第二次 tick 不能开口（不是概率低，是根本不掷）。
        assertTrue(ticker.tick(afternoon + 60_000L).isEmpty())
        assertEquals(1, proactiveMessages(store, sessionId).size)

        // 冷却过后每次 +11 分钟继续跑：总数最终停在上限，不会无限增长。
        var fired = 1
        repeat(6) { index ->
            val now = afternoon + (index + 2) * 11 * 60_000L
            if (ticker.tick(now).isNotEmpty()) fired++
        }
        val state = store.proactiveState(FileStore.SEED_PERSONA_1_ID, localDate(afternoon))
        assertEquals("落库条数必须等于持久化计数", fired, state.proactiveCountToday)
        assertEquals(fired, proactiveMessages(store, sessionId).size)
        assertTrue("默认主动程度上限不该让同一天发到 7 条", fired < 7)

        // 上限已到：同一天再跑一拍也不会多一条。
        assertTrue(ticker.tick(afternoon + 3 * 60 * 60_000L).isEmpty())
        assertEquals(fired, proactiveMessages(store, sessionId).size)
    }

    @Test
    fun `count resets after local midnight but last fired time keeps the cooldown`() {
        // 23:50 已经说满 5 次；20 分钟后跨到第二天 00:10。
        val dayOne = Instant.parse("2026-11-15T15:50:00Z").toEpochMilli() // 23:50 +08:00
        val dayTwo = Instant.parse("2026-11-15T16:10:00Z").toEpochMilli() // 00:10 +08:00
        val interactionAt = dayOne - 6 * 60 * 60_000L
        val store = openStore(interactionAt)
        val sessionId = seedInteraction(store, interactionAt)

        repeat(5) { store.recordProactiveFired(FileStore.SEED_PERSONA_1_ID, dayOne, localDate(dayOne)) }
        assertEquals("2026-11-15", localDate(dayOne))
        assertEquals(5, store.proactiveState(FileStore.SEED_PERSONA_1_ID, localDate(dayOne)).proactiveCountToday)

        // 跨日：读出来就是 0，不需要等清理任务；上次主动时间保留（冷却期继续生效）。
        val dayTwoState = store.proactiveState(FileStore.SEED_PERSONA_1_ID, localDate(dayTwo))
        assertEquals("跨本地零点计数自动归零", 0, dayTwoState.proactiveCountToday)
        assertEquals("2026-11-16", dayTwoState.proactiveCountDate)
        assertEquals(dayOne, dayTwoState.lastProactiveAt)

        // 00:10 距 23:50 已有 20 分钟（> 10 分钟冷却），新的一天可以重新开口。
        val ticker = ticker(store, dayTwo)
        assertEquals(1, ticker.tick(dayTwo).size)
        val after = store.proactiveState(FileStore.SEED_PERSONA_1_ID, localDate(dayTwo))
        assertEquals(1, after.proactiveCountToday)
        assertEquals("2026-11-16", after.proactiveCountDate)
        assertEquals(dayTwo, after.lastProactiveAt)
        assertEquals(1, proactiveMessages(store, sessionId).size)
    }

    @Test
    fun `two concurrent tickers only write one proactive message`() {
        val interactionAt = afternoon - 6 * 60 * 60_000L
        val store = openStore(interactionAt)
        val sessionId = seedInteraction(store, interactionAt)

        // 两个 ticker 各自有独立的 lastTickAt（模拟两个路由实例/定时器同时跑），
        // 去重不能只靠某个对象的进程内状态，必须由 store 在锁内复查。
        val first = ticker(store, afternoon)
        val second = ticker(store, afternoon)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(2) { index ->
                pool.execute {
                    start.await()
                    if (index == 0) first.tick(afternoon) else second.tick(afternoon)
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("两个 tick 应该在 5 秒内跑完", done.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        val messages = proactiveMessages(store, sessionId)
        assertEquals("并发两个 tick 只能发一条", 1, messages.size)
        assertEquals(
            1,
            store.proactiveState(FileStore.SEED_PERSONA_1_ID, localDate(afternoon)).proactiveCountToday
        )
    }

    @Test
    fun `proactive state file is optional for old data directories`() {
        val first = openStore(afternoon - 6 * 60 * 60_000L)
        assertNotNull(first.proactiveState(FileStore.SEED_PERSONA_1_ID, localDate(afternoon)))
        // v1 数据目录没有这个文件也能正常打开（按「从没主动过」处理）。
        Files.deleteIfExists(root.resolve(FileStore.PROACTIVE_STATE_FILE))
        val reopened = openStore(afternoon - 6 * 60 * 60_000L)
        val state: ProactiveState = reopened.proactiveState(FileStore.SEED_PERSONA_1_ID, localDate(afternoon))
        assertEquals(0, state.proactiveCountToday)
        assertEquals(0L, state.lastProactiveAt)
    }
}
