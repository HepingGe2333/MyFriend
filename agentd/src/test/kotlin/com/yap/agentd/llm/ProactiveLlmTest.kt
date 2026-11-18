package com.yap.agentd.llm

import com.yap.agentd.AgentdServices
import com.yap.agentd.TestSupport
import com.yap.agentd.ToolsRoutes
import com.yap.agentd.int
import com.yap.agentd.objects
import com.yap.agentd.proactive.PersonaState
import com.yap.agentd.proactive.ProactiveReason
import com.yap.agentd.proactive.ProactiveTextSource
import com.yap.agentd.store.EventType
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.MessageSource
import com.yap.agentd.str
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 主动消息接 LLM（任务书第 2 条）：
 *
 * - 掷中后**真调模型**写正文（假 SSE 服务端分片），正文按分片收集后落库；
 * - 落库走 `source = PROACTIVE` 的消息表并发 `message.created` 事件（客户端事件流能收到）；
 * - LLM 失败/无配置 → 不落库、不回落到固定文案（`generation_failed`）；
 * - 定时器生命周期（start/stop）与 `/v1/proactive/tick` 路由的 fired/messageId。
 */
class ProactiveLlmTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private lateinit var server: FakeLlmServer
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 一段「很久以前」的互动时刻（超过 10 分钟冷却），毫秒。 */
    private val interactionAt = Instant.parse("2026-11-15T06:00:00Z").toEpochMilli()

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-proactive-llm-test")
        store = FileStore.open(root, { interactionAt })
        store.appendMessage(sessionId, MessageRole.USER, "今天加班，先不聊了")
        server = FakeLlmServer { exchange ->
            exchange.sse(
                listOf(
                    FakeLlmServer.chunk("刚忙完，"),
                    FakeLlmServer.chunk("你那边还顺利吗？"),
                    FakeLlmServer.usage(20, 6),
                    FakeLlmServer.DONE
                )
            )
        }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        TestSupport.deleteRecursively(root)
    }

    private fun access(withConfig: Boolean = true): LlmAccess {
        if (withConfig) store.pushLlmConfig(server.baseUrl)
        return llmAccess(store)
    }

    private fun ticker(access: LlmAccess, now: Long) = com.yap.agentd.ProactiveTicker(
        store = store,
        clock = Clock.fixed(Instant.ofEpochMilli(now), zone),
        roll = { 0.0 },
        texts = LlmProactiveTextSource(store, access, Clock.fixed(Instant.ofEpochMilli(now), zone))
    )

    private fun proactiveMessages() = store.latestMessages(sessionId, 100)
        .filter { it.source == MessageSource.PROACTIVE }

    @Test
    fun `fired proactive message is written by the model and emitted as a message created event`() {
        val now = interactionAt + 11 * 60_000L
        val fired = ticker(access(), now).tick(now)

        assertEquals(1, fired.size)
        val messages = proactiveMessages()
        assertEquals(1, messages.size)
        assertEquals("刚忙完，你那边还顺利吗？", messages.single().content)
        assertEquals(MessageSource.PROACTIVE, messages.single().source)

        val events = store.awaitEvents(0, 0, 200).events.filter { it.type == EventType.MESSAGE_CREATED }
        assertTrue("要发 message.created", events.isNotEmpty())
        val payload = events.last().payload
        val message = payload.fields["message"] as com.yap.app.core.json.JsonValue.Obj
        assertEquals(messages.single().id, (message.fields["id"] as com.yap.app.core.json.JsonValue.Str).value)
    }

    @Test
    fun `the proactive request uses the persona prompt and the one line instruction`() {
        val now = interactionAt + 11 * 60_000L
        ticker(access(), now).tick(now)

        val body = TestSupport.parse(server.onlyRequest().body)
        val messages = body.objects("messages")
        assertTrue(messages.first().str("content").contains("# 你是谁"))
        assertTrue(messages.first().str("content").contains("# 现在的情况"))
        assertTrue(messages.first().str("content").contains("只输出这一句话本身"))
        assertTrue("要带一点最近对话", messages.any { it.str("content").contains("今天加班") })
    }

    @Test
    fun `llm failure marks generation_failed and writes nothing`() {
        server.close()
        server = FakeLlmServer { exchange -> exchange.json(500, """{"error":{"message":"boom"}}""") }
        val now = interactionAt + 11 * 60_000L
        val outcome = ticker(access(), now).tickWithDecisions(now)

        assertTrue(outcome.fired.isEmpty())
        assertEquals(ProactiveReason.GENERATION_FAILED, outcome.decisions.single().reason)
        assertTrue(proactiveMessages().isEmpty())
        assertTrue(
            "不该为主动消息发 message.created",
            store.awaitEvents(0, 0, 100).events.none { event ->
                event.type == EventType.MESSAGE_CREATED &&
                    (event.payload.fields["message"] as? com.yap.app.core.json.JsonValue.Obj)
                        ?.fields?.get("source") == com.yap.app.core.json.JsonValue.Str(MessageSource.PROACTIVE)
            }
        )
    }

    @Test
    fun `no llm config skips generation without any request`() {
        val now = interactionAt + 11 * 60_000L
        val outcome = ticker(access(withConfig = false), now).tickWithDecisions(now)

        assertEquals(ProactiveReason.GENERATION_FAILED, outcome.decisions.single().reason)
        assertTrue(proactiveMessages().isEmpty())
        assertTrue("没有配置就不该发请求", server.requests.isEmpty())
    }

    @Test
    fun `scheduler lifecycle can be started and stopped`() {
        val runtime = ticker(access(), interactionAt)
        assertFalse(runtime.isRunning)
        runtime.start()
        assertTrue(runtime.isRunning)
        runtime.stop()
        assertFalse(runtime.isRunning)
    }

    @Test
    fun `tick route returns the fired message id generated by the model`() {
        store.pushLlmConfig(server.baseUrl)
        val access = llmAccess(store)
        val services = AgentdServices(store, llm = access, proactiveRoll = { 0.0 })
        val now = interactionAt + 11 * 60_000L

        val response = ToolsRoutes.handle(services, "/v1/proactive/tick", """{"now":$now}""")

        assertEquals(200, response.status)
        val body = TestSupport.parse(response.body)
        assertEquals(1, body.int("fired"))
        assertEquals(proactiveMessages().single().id, body.objects("decisions").single().str("messageId"))
    }

    @Test
    fun `a text source that returns only whitespace is treated as generation failure`() {
        val now = interactionAt + 11 * 60_000L
        val runtime = com.yap.agentd.ProactiveTicker(
            store = store,
            clock = Clock.fixed(Instant.ofEpochMilli(now), zone),
            roll = { 0.0 },
            texts = ProactiveTextSource { _, _ -> "   " }
        )
        val outcome = runtime.tickWithDecisions(now)
        assertEquals(ProactiveReason.GENERATION_FAILED, outcome.decisions.single().reason)
        assertTrue(proactiveMessages().isEmpty())
    }

    @Test
    fun `persona state passed to the text source identifies the persona`() {
        val captured = mutableListOf<PersonaState>()
        val now = interactionAt + 11 * 60_000L
        val runtime = com.yap.agentd.ProactiveTicker(
            store = store,
            clock = Clock.fixed(Instant.ofEpochMilli(now), zone),
            roll = { 0.0 },
            texts = ProactiveTextSource { persona, _ ->
                captured += persona
                "嗯，我在。"
            }
        )
        val fired = runtime.tick(now)

        assertEquals(1, fired.size)
        assertEquals(listOf(personaId), captured.map { it.personaId })
        assertEquals("嗯，我在。", proactiveMessages().single().content)
    }
}
