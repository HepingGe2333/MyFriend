package com.yap.agentd

import com.yap.agentd.llm.FakeLlmServer
import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmClient
import com.yap.agentd.llm.LlmConfigResolver
import com.yap.agentd.llm.LlmReplyGenerator
import com.yap.agentd.llm.llmAccess
import com.yap.agentd.llm.pushLlmConfig
import com.yap.agentd.store.EventType
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import com.yap.agentd.tools.ServerToolCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Path

/**
 * 0.14 连发（`spec/experience/README.md` §4）：一轮多条短消息 + 逐条 `message.created`。
 *
 * 钉住四件事：
 * 1. 每条消息是**独立**的 assistant 消息（独立 id / 独立 `message.created` 事件 / 共用 `replyTo`）；
 * 2. SSE 顺序：`accepted → message.created* → done`，**只有一个 done**（assistantMessageId=最后一条）；
 * 3. `send_message` 每轮最多 4 条、每条 ≤120 字；第 5 条失败且不落库；
 * 4. 不走工具的回复按空行拆成 1–3 条（超过 3 条并进最后一条，不丢内容）。
 */
class MultiSendTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)

    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID
    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-multisend")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private fun services(generator: ReplyGenerator): AgentdServices = AgentdServices(store, generator)

    private fun send(services: AgentdServices, id: String = "m-1", content: String = "在吗"): RecordingSink {
        val sink = RecordingSink()
        AuthorityRoutes.stream(
            services,
            "POST",
            "/v1/sessions/$sessionId/messages",
            """{"clientMessageId":"$id","content":"$content"}""",
            sink
        )
        return sink
    }

    private fun assistantMessages(): List<Message> =
        store.blockMessages(sessionId, 0).filter { it.role == MessageRole.ASSISTANT }

    // ------------------------------------------------------------------ 生成器直接给多条

    @Test
    fun `multiple generated messages produce one created event each and a single done`() {
        val generator = ReplyGenerator { _, emit ->
            emit(SseEvent.Delta("正在输入…"))
            ReplyOutcome(Usage(5, 6), messages = listOf("第一句", "第二句", "第三句"))
        }
        val sink = send(services(generator))

        assertEquals(
            listOf("accepted", "delta", "message.created", "message.created", "message.created", "done"),
            sink.types()
        )
        val created = sink.events.filterIsInstance<SseEvent.MessageCreated>()
        assertEquals(listOf("第一句", "第二句", "第三句"), created.map { it.message.content })
        created.forEach { assertEquals(MessageRole.ASSISTANT, it.message.role) }
        created.forEach { assertFalse(it.message.blocked) }

        val done = sink.firstOf<SseEvent.Done>()
        assertEquals("done 指最后一条", created.last().message.id, done.assistantMessageId)
        assertEquals(Usage(5, 6), done.usage)

        val assistants = assistantMessages()
        assertEquals(3, assistants.size)
        assertEquals(listOf("第一句", "第二句", "第三句"), assistants.map { it.content })
        assertEquals("三条共用同一条用户消息", 1, assistants.mapNotNull { it.replyTo }.distinct().size)
        assertEquals(3L, store.session(sessionId)!!.messageCount - 1)
        assertEquals("用量记在最后一条", 5, assistants.last().promptTokens)
        assertEquals("只有最后一条带用量", null, assistants.first().promptTokens)
        // 事件流里也是三条独立的 message.created（客户端下一次同步能看到）。
        val events = store.eventsSince(0).filter { it.type == EventType.MESSAGE_CREATED }
        assertEquals(4, events.size)
        assertEquals(3, events.count { it.payload.obj("message").str("role") == MessageRole.ASSISTANT })
    }

    @Test
    fun `generated messages are capped at four per turn`() {
        val generator = ReplyGenerator { _, _ ->
            ReplyOutcome(Usage.ZERO, messages = listOf("一", "二", "三", "四", "五", "六"))
        }
        val sink = send(services(generator))
        assertEquals("每轮最多 4 条（连发上限）", 4, sink.events.count { it is SseEvent.MessageCreated })
        assertEquals(4, assistantMessages().size)
    }

    // ------------------------------------------------------------------ send_message 工具

    @Test
    fun `send_message emits one message per tool call and enforces four per turn`() {
        var round = 0
        FakeLlmServer { exchange ->
            round += 1
            if (round == 1) {
                // 第 1 轮：模型连发 5 条（第 5 条必须被拒绝）。
                val calls = (0 until 5).flatMap { index ->
                    listOf(
                        FakeLlmServer.toolCall(
                            index,
                            id = "call_$index",
                            name = ServerToolCatalog.SEND_MESSAGE,
                            arguments = """{"text":"第 ${index + 1} 条"}"""
                        )
                    )
                }
                exchange.sse(calls + listOf(FakeLlmServer.usage(30, 10), FakeLlmServer.DONE))
            } else {
                exchange.sse(listOf(FakeLlmServer.chunk("（已经说完了）"), FakeLlmServer.DONE))
            }
        }.use { server ->
            store.pushLlmConfig(server.baseUrl)
            val llm = LlmAccess(LlmClient(), LlmConfigResolver(store, loopback = true))
            val service = ChatService(store, LlmReplyGenerator(store, llm, clock = clock.asClock()))
            val sink = RecordingSink()
            service.send(
                sessionId,
                SendMessageRequest(clientMessageId = "multi-1", content = "说几句"),
                sink
            )

            val assistants = assistantMessages()
            assertEquals("第 5 条不落库", 4, assistants.size)
            assertEquals(listOf("第 1 条", "第 2 条", "第 3 条", "第 4 条"), assistants.map { it.content })
            val created = sink.events.filterIsInstance<SseEvent.MessageCreated>()
            assertEquals(4, created.size)
            assertEquals(assistants.map { it.id }, created.map { it.message.id })
            assertEquals("done 只发一次", 1, sink.events.count { it is SseEvent.Done })
            assertEquals(assistants.last().id, sink.firstOf<SseEvent.Done>().assistantMessageId)
            // 工具结果回灌：第 5 条是一次失败（ok=false），模型下一轮能看到。
            val trace = assistants.last().toolTraceJson.orEmpty()
            assertTrue(trace.contains(ServerToolCatalog.SEND_MESSAGE))
        }
    }

    @Test
    fun `send_message truncates a too long text to 120 chars`() {
        var round = 0
        FakeLlmServer { exchange ->
            round += 1
            if (round == 1) {
                exchange.sse(
                    listOf(
                        FakeLlmServer.toolCall(
                            0,
                            id = "call_long",
                            name = ServerToolCatalog.SEND_MESSAGE,
                            arguments = """{"text":"${"长".repeat(200)}"}"""
                        ),
                        FakeLlmServer.DONE
                    )
                )
            } else {
                exchange.sse(listOf(FakeLlmServer.chunk("好。"), FakeLlmServer.DONE))
            }
        }.use { server ->
            store.pushLlmConfig(server.baseUrl)
            val llm = LlmAccess(LlmClient(), LlmConfigResolver(store, loopback = true))
            val service = ChatService(store, LlmReplyGenerator(store, llm, clock = clock.asClock()))
            service.send(
                sessionId,
                SendMessageRequest(clientMessageId = "long-1", content = "说一句长的"),
                RecordingSink()
            )
            val assistants = assistantMessages()
            assertEquals(1, assistants.size)
            assertEquals(120, assistants.single().content.length)
        }
    }

    // ------------------------------------------------------------------ 不用工具的回复

    @Test
    fun `plain text replies are split by blank lines into one to three messages`() {
        val generator = ReplyGenerator { _, emit ->
            emit(SseEvent.Delta("第一段\n\n第二段\n\n第三段\n\n第四段"))
            ReplyOutcome(Usage(1, 1))
        }
        val sink = send(services(generator))

        val created = sink.events.filterIsInstance<SseEvent.MessageCreated>()
        assertEquals(3, created.size)
        assertEquals("第一段", created[0].message.content)
        assertEquals("第二段", created[1].message.content)
        assertTrue("多出来的并进最后一条", created[2].message.content.contains("第三段"))
        assertTrue(created[2].message.content.contains("第四段"))
        assertEquals(3, assistantMessages().size)
    }

    @Test
    fun `a single paragraph stays one message and keeps the old SSE shape`() {
        val generator = ReplyGenerator { _, emit ->
            emit(SseEvent.Delta("只有一段话，没有空行。"))
            ReplyOutcome(Usage(1, 1))
        }
        val sink = send(services(generator))

        assertEquals(listOf("accepted", "delta", "done"), sink.types())
        assertEquals(1, assistantMessages().size)
        assertEquals("只有一段话，没有空行。", assistantMessages().single().content)
    }

    @Test
    fun `no placeholder messages are ever created`() {
        // stay_silent：一条 assistant 消息都不能落，也不能有「…」占位。
        val generator = ReplyGenerator { _, _ -> ReplyOutcome(Usage(1, 1), suppressed = true) }
        val sink = send(services(generator))
        assertEquals(0, assistantMessages().size)
        assertTrue(sink.firstOf<SseEvent.Done>().silent)
        assertTrue(sink.events.none { it is SseEvent.MessageCreated })

        // 空文字（工具用完了但没说话）：同样不落空消息。
        val empty = ReplyGenerator { _, _ -> ReplyOutcome(Usage(1, 1)) }
        val sink2 = send(services(empty), id = "m-2")
        assertEquals(0, assistantMessages().size)
        assertTrue(sink2.firstOf<SseEvent.Done>().silent)
    }

    // ------------------------------------------------------------------ 幂等重放

    @Test
    fun `replaying a multi message turn resends every created event and one done`() {
        val generator = ReplyGenerator { _, _ -> ReplyOutcome(Usage(2, 3), messages = listOf("甲", "乙")) }
        val authoritative = services(generator)
        val first = send(authoritative, id = "multi-replay")
        val second = send(authoritative, id = "multi-replay")

        assertEquals(2, second.events.count { it is SseEvent.MessageCreated })
        assertEquals(1, second.events.count { it is SseEvent.Done })
        assertEquals(
            first.events.filterIsInstance<SseEvent.MessageCreated>().map { it.message.id },
            second.events.filterIsInstance<SseEvent.MessageCreated>().map { it.message.id }
        )
        assertEquals("重放不生成新的消息", 2, assistantMessages().size)
    }
}
