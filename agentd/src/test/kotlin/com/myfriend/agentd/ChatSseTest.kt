package com.myfriend.agentd

import com.myfriend.agentd.store.EventType
import com.myfriend.agentd.store.FileStore
import com.myfriend.agentd.store.Message
import com.myfriend.agentd.store.MessageRole
import com.myfriend.agentd.store.MessageSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 发送消息（SSE）的 handler 级测试：事件顺序、幂等（顺序 + 并发）、断开后仍落库、用量累加。
 *
 * 这里用一个「记录型 sink」代替 socket；真实 socket 的断开/EOF 行为在 `AuthorityServerTest` 里。
 */
class ChatSseTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)

    private val sessionId: String get() = FileStore.SEED_SESSION_ID
    private val personaId: String get() = FileStore.SEED_PERSONA_1_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-chat-test")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        TestSupport.deleteRecursively(root)
    }

    private class RecordingSink : SseSink {
        val events = mutableListOf<SseEvent>()

        override fun emit(event: SseEvent) {
            events += event
        }

        fun types(): List<String> = events.map { it.type }

        inline fun <reified T : SseEvent> firstOf(): T = events.filterIsInstance<T>().first()
    }

    private fun services(generator: ReplyGenerator = EchoReplyGenerator()) = AgentdServices(store, generator)

    private fun send(
        services: AgentdServices,
        body: String,
        sessionId: String = this.sessionId
    ): RecordingSink {
        val sink = RecordingSink()
        AuthorityRoutes.stream(services, "POST", "/v1/sessions/$sessionId/messages", body, sink)
        return sink
    }

    private fun userMessage(): Message = store.blockMessages(sessionId, 0).first { it.role == MessageRole.USER }

    private fun assistantMessage(): Message = store.blockMessages(sessionId, 0).last { it.role == MessageRole.ASSISTANT }

    // ------------------------------------------------------------------ 事件顺序与落库

    @Test
    fun `sse emits accepted deltas and done and persists the assistant message`() {
        val sink = send(services(), """{"clientMessageId":"c-1","content":"你好呀"}""")

        assertEquals("accepted", sink.types().first())
        assertEquals("done", sink.types().last())
        assertTrue("回显是分块推送的", sink.types().count { it == "delta" } > 1)
        assertFalse("本阶段没有 tool 事件", sink.types().contains("tool"))

        val accepted = sink.firstOf<SseEvent.Accepted>()
        val done = sink.firstOf<SseEvent.Done>()
        assertTrue("用量由生成器提供", done.usage.completionTokens > 0)

        val messages = store.blockMessages(sessionId, 0)
        assertEquals(2, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(accepted.userMessageId, messages[0].id)
        assertEquals(done.assistantMessageId, messages[1].id)
        assertTrue("回显要带上用户说的话", messages[1].content.contains("你好呀"))
        assertTrue("还有一句固定中文", messages[1].content.contains("收到啦，我在。"))
        assertEquals(MessageSource.CHAT, messages[1].source)
        assertEquals("assistant 指回用户消息", messages[0].id, messages[1].replyTo)

        val session = store.session(sessionId)!!
        assertEquals(2L, session.messageCount)
        assertEquals("未读只算 assistant", 1, session.unread)
        assertEquals(1L, store.usage(personaId).replies)
        assertEquals(done.usage.promptTokens, messages[1].promptTokens)
        assertEquals(done.usage.completionTokens, messages[1].completionTokens)
    }

    @Test
    fun `tool events from the generator are forwarded unchanged`() {
        val generator = ReplyGenerator { _, emit ->
            emit(SseEvent.Delta("想一下…"))
            emit(SseEvent.Tool("web_search", "running", "正在搜索"))
            emit(SseEvent.Tool("web_search", "done", "3 条结果"))
            emit(SseEvent.Delta("结论如下"))
            Usage(promptTokens = 7, completionTokens = 9)
        }
        val sink = send(services(generator), """{"clientMessageId":"c-tool","content":"搜一下"}""")

        assertEquals(
            listOf("accepted", "delta", "tool", "tool", "delta", "done"),
            sink.types()
        )
        val done = sink.firstOf<SseEvent.Done>()
        assertEquals(Usage(7, 9), done.usage)
        val assistant = assistantMessage()
        assertEquals("想一下…结论如下", assistant.content)
        assertEquals(7, assistant.promptTokens)
        assertEquals(9, assistant.completionTokens)
        assertEquals(7L, store.usage(personaId).promptTokens)
        assertEquals(9L, store.usage(personaId).completionTokens)
    }

    @Test
    fun `messages emit message created events for both roles`() {
        send(services(), """{"clientMessageId":"c-events","content":"记录一下"}""")

        val created = store.eventsSince(0).filter { it.type == EventType.MESSAGE_CREATED }
        assertEquals(2, created.size)
        assertEquals(MessageRole.USER, created[0].payload.obj("message").str("role"))
        assertEquals(MessageRole.ASSISTANT, created[1].payload.obj("message").str("role"))
        assertTrue(created[0].cursor < created[1].cursor)
        assertEquals(sessionId, created[0].sessionId)
        assertEquals(personaId, created[0].personaId)
    }

    @Test
    fun `attachments are persisted on the user message as media urls`() {
        // 0.12.0 起附件必须是已存在的媒体：客户端可传裸 id 或 /v1/media/<id>，落库统一成后者。
        val first = store.saveMedia("CHAT_IMAGE", null, "image/jpeg", byteArrayOf(1, 2, 3)).item
        val second = store.saveMedia("CHAT_IMAGE", null, "image/jpeg", byteArrayOf(4, 5, 6)).item
        send(
            services(),
            """{"clientMessageId":"c-attach","content":"看图","attachments":["${first.id}","/v1/media/${second.id}"]}"""
        )
        assertEquals(listOf(first.url, second.url), userMessage().attachments)
    }

    @Test
    fun `unknown media attachment is rejected before the stream starts`() {
        val failure = try {
            send(services(), """{"clientMessageId":"c-bad-media","content":"看图","attachments":["no-such-media"]}""")
            null
        } catch (failure: AgentdApi.ApiFailure) {
            failure
        }
        assertTrue("必须拒绝不存在的媒体", failure != null)
        assertEquals(400, failure!!.status)
        assertEquals("unknown_media", failure.code)
        assertTrue("不能落库半条消息", store.blockMessages(sessionId, 0).isEmpty())
    }

    // ------------------------------------------------------------------ 幂等

    @Test
    fun `duplicate clientMessageId replays accepted and done without generating again`() {
        val generated = AtomicInteger()
        val generator = ReplyGenerator { _, emit ->
            generated.incrementAndGet()
            emit(SseEvent.Delta("只生成一次"))
            Usage(1, 1)
        }
        val authoritative = services(generator)
        val body = """{"clientMessageId":"same-id","content":"重复发送"}"""

        val first = send(authoritative, body)
        val second = send(authoritative, body)

        assertEquals("生成只发生一次", 1, generated.get())
        assertEquals(listOf("accepted", "done"), second.types())
        assertEquals(first.firstOf<SseEvent.Accepted>().userMessageId, second.firstOf<SseEvent.Accepted>().userMessageId)
        assertEquals(first.firstOf<SseEvent.Done>(), second.firstOf<SseEvent.Done>())
        assertEquals("只有一条用户消息 + 一条回复", 2, store.blockMessages(sessionId, 0).size)
        assertEquals(2L, store.session(sessionId)!!.messageCount)
    }

    @Test
    fun `concurrent duplicates still generate exactly once`() {
        val generated = AtomicInteger()
        val generator = ReplyGenerator { _, emit ->
            generated.incrementAndGet()
            Thread.sleep(80)
            emit(SseEvent.Delta("慢一点"))
            Usage(2, 3)
        }
        val authoritative = services(generator)
        val body = """{"clientMessageId":"race-id","content":"同时发两次"}"""
        val start = CountDownLatch(1)
        val sinkA = RecordingSink()
        val sinkB = RecordingSink()
        val threadA = Thread {
            start.await()
            AuthorityRoutes.stream(authoritative, "POST", "/v1/sessions/$sessionId/messages", body, sinkA)
        }
        val threadB = Thread {
            start.await()
            AuthorityRoutes.stream(authoritative, "POST", "/v1/sessions/$sessionId/messages", body, sinkB)
        }
        threadA.start()
        threadB.start()
        start.countDown()
        threadA.join(10_000)
        threadB.join(10_000)

        assertEquals("并发的重复请求也只能生成一次", 1, generated.get())
        assertEquals(2, store.blockMessages(sessionId, 0).size)
        assertEquals("accepted", sinkA.types().first())
        assertEquals("accepted", sinkB.types().first())
        assertEquals("done", sinkA.types().last())
        assertEquals("done", sinkB.types().last())
    }

    @Test
    fun `a user message without a reply is completed on the next request`() {
        // 模拟「用户消息落库后、回复落库前进程被杀」
        val orphan = store.appendMessage(
            sessionId,
            MessageRole.USER,
            "半路死了",
            clientMessageId = "orphan-id"
        )
        val sink = send(services(), """{"clientMessageId":"orphan-id","content":"半路死了"}""")

        assertEquals(orphan.id, sink.firstOf<SseEvent.Accepted>().userMessageId)
        assertEquals("done", sink.types().last())
        assertEquals(1, store.blockMessages(sessionId, 0).count { it.role == MessageRole.USER })
        assertEquals(1, store.blockMessages(sessionId, 0).count { it.role == MessageRole.ASSISTANT })
    }

    // ------------------------------------------------------------------ 断开 / 错误

    @Test
    fun `a dead sink does not stop generation or persistence`() {
        var written = 0
        val deadSink = SseSink {
            written += 1
            throw IOException("客户端已经走了")
        }

        // 不抛异常：写失败被吞掉，生成继续。
        AuthorityRoutes.stream(
            services(),
            "POST",
            "/v1/sessions/$sessionId/messages",
            """{"clientMessageId":"dead-sink","content":"你继续"}""",
            deadSink
        )

        assertTrue("至少尝试写过（accepted）", written >= 1)
        val messages = store.blockMessages(sessionId, 0)
        assertEquals(2, messages.size)
        assertEquals(MessageRole.ASSISTANT, messages.last().role)
        assertTrue(messages.last().content.isNotEmpty())
        assertEquals(1L, store.usage(personaId).replies)
        assertEquals(1, store.session(sessionId)!!.unread)
    }

    @Test
    fun `validation happens before the stream starts`() {
        fun failureFor(body: String?, sessionId: String = this.sessionId): AgentdApi.ApiFailure {
            val sink = RecordingSink()
            val failure = runCatching {
                AuthorityRoutes.stream(
                    services(),
                    "POST",
                    "/v1/sessions/$sessionId/messages",
                    body,
                    sink
                )
            }.exceptionOrNull()
            assertTrue("应该在写任何事件前失败：$failure", failure is AgentdApi.ApiFailure)
            assertTrue("失败时不能已经写出事件", sink.events.isEmpty())
            return failure as AgentdApi.ApiFailure
        }

        assertEquals(400, failureFor(null).status)
        assertEquals(400, failureFor("""{"content":"没有幂等键"}""").status)
        assertEquals(400, failureFor("""{"clientMessageId":"  ","content":"空 id"}""").status)
        assertEquals(400, failureFor("""{"clientMessageId":"c","content":"   "}""").status)
        assertEquals(400, failureFor("""{"clientMessageId":"c"}""").status)
        assertEquals(400, failureFor("""{"clientMessageId":"c","content":"x","attachments":"nope"}""").status)
        assertEquals(404, failureFor("""{"clientMessageId":"c","content":"x"}""", sessionId = "nope").status)
        assertEquals(
            "路径穿越 id 也是 404",
            404,
            failureFor("""{"clientMessageId":"c","content":"x"}""", sessionId = "..").status
        )
        assertTrue("校验失败不能落任何消息", store.blockMessages(sessionId, 0).isEmpty())
    }
}
