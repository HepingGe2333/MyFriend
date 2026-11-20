package com.yap.agentd

import com.yap.agentd.delivery.ClientPresence
import com.yap.agentd.delivery.MessageDelivery
import com.yap.agentd.delivery.Sleeper
import com.yap.agentd.proactive.ProactiveReason
import com.yap.agentd.proactive.ProactiveTextSource
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.MessageSource
import com.yap.agentd.store.ProactiveFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 0.15「主动消息后台记录」：主动消息在**服务端**生成、落库、写 `message.created` 事件与队列记录，
 * 不依赖客户端在线；失败的尝试留在 `proactive_failures.json`。
 *
 * 钉住三件事：
 * 1. **离线也生成**：客户端从没连过（`presence` 离线），主动消息照样落库、照样有事件；
 * 2. **重启后可见**：重新打开数据目录，消息与事件都还在（客户端下次同步就能看到）；
 * 3. **失败有记录**：模型没写出正文时不回落模板，而是写一条 `stage=generation` 的失败记录。
 */
class ProactiveBackgroundTest {

    private lateinit var root: Path
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-15 14:00（本地）。 */
    private val afternoon = Instant.parse("2026-11-15T06:00:00Z").toEpochMilli()
    private val interactionAt = afternoon - 6 * 60 * 60_000L

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-proactive-background")
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun openStore(): FileStore = FileStore.open(root, { interactionAt })

    private fun clock(): Clock = Clock.fixed(Instant.ofEpochMilli(afternoon), zone)

    private fun ticker(
        store: FileStore,
        texts: ProactiveTextSource,
        presence: ClientPresence = ClientPresence(clock())
    ): ProactiveTicker = ProactiveTicker(
        store = store,
        clock = clock(),
        roll = { 0.0 },
        texts = texts,
        delivery = MessageDelivery(
            store = store,
            clock = clock(),
            presence = presence,
            delayEnabled = true,
            sleeper = Sleeper.NONE
        )
    )

    private fun seedInteraction(store: FileStore) {
        store.appendMessage(FileStore.SEED_SESSION_ID, MessageRole.USER, "在吗")
    }

    private fun proactiveMessages(store: FileStore) =
        store.latestMessages(FileStore.SEED_SESSION_ID, 100)
            .filter { it.source == MessageSource.PROACTIVE }

    @Test
    fun `a proactive message is generated and persisted while the client is offline`() {
        val store = openStore()
        seedInteraction(store)
        val ticker = ticker(store, ProactiveTextSource { _, _ -> "刚路过那家店，想起你。" })

        val fired = ticker.tick(afternoon)

        assertEquals("离线也要开口", 1, fired.size)
        val message = proactiveMessages(store).single()
        assertEquals("刚路过那家店，想起你。", message.content)
        assertTrue("队列记录要存在过（投递层入口）", Files.isRegularFile(root.resolve(FileStore.MESSAGE_QUEUE_FILE)))
        assertTrue("队列不能堆积（离线立即投递并完成）", store.pendingDeliveries().isEmpty())

        val events = store.eventsSince(0, 200)
        assertTrue(
            "必须写 message.created（客户端启动/重连后同步可见）",
            events.any { it.type == "message.created" }
        )
        assertTrue(
            "事件里带的是那条主动消息",
            events.any { event ->
                event.type == "message.created" &&
                    com.yap.agentd.store.StoreJson.stringify(event.payload).contains(message.id)
            }
        )
    }

    @Test
    fun `the proactive message and its event survive a restart`() {
        val first = openStore()
        seedInteraction(first)
        val fired = ticker(first, ProactiveTextSource { _, _ -> "今天风挺大的，你那边冷不冷。" }).tick(afternoon)
        assertTrue(fired.isNotEmpty())
        val messageId = fired.single().messageId

        val reopened = openStore()
        val restored = proactiveMessages(reopened).single()
        assertEquals("重启后消息还在（客户端下次同步就能看到）", messageId, restored.id)
        assertEquals("今天风挺大的，你那边冷不冷。", restored.content)
        assertTrue(
            "重启后事件还能从 events.jsonl 读回来",
            reopened.eventsSince(0, 200).any { it.type == "message.created" }
        )
    }

    @Test
    fun `a generation failure leaves a record instead of a template message`() {
        val store = openStore()
        seedInteraction(store)
        val ticker = ticker(store, ProactiveTextSource { _, _ -> "" })

        val outcome = ticker.tickWithDecisions(afternoon)

        assertTrue("没有生成出正文就不发", outcome.fired.isEmpty())
        assertTrue(
            "判定里要说明为什么没开口",
            outcome.decisions.any { it.reason == ProactiveReason.GENERATION_FAILED }
        )
        assertTrue("不能回落成模板消息", proactiveMessages(store).isEmpty())
        val failures = store.proactiveFailures()
        assertEquals(1, failures.size)
        assertEquals(ProactiveFailure.Stage.GENERATION, failures.single().stage)
        assertEquals("generation_failed", failures.single().reason)
        assertTrue(Files.isRegularFile(root.resolve(FileStore.PROACTIVE_FAILURES_FILE)))

        val reopened = openStore()
        assertEquals("失败记录也要落盘", 1, reopened.proactiveFailures().size)
    }

    @Test
    fun `failure records are capped and the newest survive`() {
        val store = openStore()
        repeat(ProactiveFailure.KEEP_RECENT + 25) { index ->
            store.recordProactiveFailure(
                ProactiveFailure(
                    id = "failure-$index",
                    personaId = FileStore.SEED_PERSONA_1_ID,
                    sessionId = FileStore.SEED_SESSION_ID,
                    at = afternoon + index,
                    stage = ProactiveFailure.Stage.PUSH,
                    reason = "push_failed",
                    detail = "第 $index 次失败"
                )
            )
        }
        val all = store.proactiveFailures()
        assertEquals(ProactiveFailure.KEEP_RECENT, all.size)
        assertEquals("留下的是最新的", "第 ${ProactiveFailure.KEEP_RECENT + 24} 次失败", all.first().detail)
        assertFalse(all.any { it.at == afternoon })
    }
}
