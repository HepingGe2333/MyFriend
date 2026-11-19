package com.yap.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SSE（`POST /v1/sessions/{id}/messages`）的单测：解析器逐条钉行为，真 socket 钉「分片到达」
 * 与「取消/超时真的关连接」。
 *
 * 为什么这两条必须用真 socket：假 body 里没有连接，`Call.cancel()` 关不掉任何东西，
 * 「取消协程时关闭连接」这条验收项就测了个寂寞。
 */
class ServerSseTest {

    private lateinit var server: FakeHttpTestServer
    private lateinit var api: ServerApi

    @Before
    fun setUp() {
        server = FakeHttpTestServer()
        api = ServerApi(
            config = ServerConfig(baseUrl = server.baseUrl, token = "test-token", enabled = true),
            debuggable = true
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ------------------------------------------------------------------ 解析器（纯函数）

    @Test
    fun `解析器处理空行 注释 未知字段与未知事件`() {
        val parser = ServerSseParser()
        val events = mutableListOf<ServerChatEvent>()

        listOf(
            ": keepalive",
            "",
            "id: 12",
            "retry: 1000",
            "event: heartbeat",
            "data: {\"n\":1}",
            "",
            "event: delta",
            "data:{\"text\":\"a\"}",
            "",
            "data: {\"text\":\"no event name\"}",
            ""
        ).forEach { events += parser.feed(it) }

        assertEquals("只认 delta；未知事件、id/retry、没有 event 的 data 都忽略", 1, events.size)
        assertEquals(ServerChatEvent.Delta("a"), events.single())
    }

    @Test
    fun `解析器认识 0_14 的 message_created 完整消息`() {
        val parser = ServerSseParser()
        val events = mutableListOf<ServerChatEvent>()
        listOf(
            "event: message.created",
            "data: {\"message\":{\"id\":\"a-1\",\"sessionId\":\"s-1\",\"role\":\"ASSISTANT\",\"content\":\"第一条\",\"createdAt\":42,\"blocked\":false,\"visibleAt\":142}}",
            ""
        ).forEach { events += parser.feed(it) }
        val event = events.single() as ServerChatEvent.MessageCreated
        assertEquals("a-1", event.message.id)
        assertEquals("第一条", event.message.content)
        assertEquals(false, event.message.blocked)
        // 服务端已经按 visibleAt 延迟发过；客户端只解析，不据此再加延迟。
        assertEquals(142L, event.message.visibleAt)
    }

    @Test
    fun `解析器认识 0_14_1 的 typing 事件`() {
        val parser = ServerSseParser()
        val events = mutableListOf<ServerChatEvent>()
        listOf(
            "event: typing.started",
            "data: {\"sessionId\":\"s-1\",\"personaId\":\"p-1\",\"at\":1700000000000}",
            "",
            "event: typing.stopped",
            "data: {\"sessionId\":\"s-1\",\"personaId\":\"p-1\",\"at\":1700000004000}",
            ""
        ).forEach { events += parser.feed(it) }

        assertEquals(
            listOf(
                ServerChatEvent.TypingStarted("s-1", "p-1"),
                ServerChatEvent.TypingStopped("s-1", "p-1")
            ),
            events
        )
    }

    @Test
    fun `typing 事件缺字段时给空串 不抛异常`() {
        val parser = ServerSseParser()
        val events = mutableListOf<ServerChatEvent>()
        listOf("event: typing.started", "data: {}", "").forEach { events += parser.feed(it) }
        assertEquals(listOf(ServerChatEvent.TypingStarted("", "")), events)
    }

    @Test
    fun `解析器按规范拼接多行 data`() {
        val parser = ServerSseParser()
        val events = mutableListOf<ServerChatEvent>()

        listOf(
            "event: delta",
            "data: {\"text\":",
            "data: \"多行\"}",
            ""
        ).forEach { events += parser.feed(it) }

        assertEquals(listOf(ServerChatEvent.Delta("多行")), events)
    }

    @Test
    fun `解析器在流结束没补空行时也能 flush 最后一帧`() {
        val parser = ServerSseParser()

        assertTrue(parser.feed("event: delta").isEmpty())
        assertTrue(parser.feed("data: {\"text\":\"尾帧\"}").isEmpty())

        assertEquals(listOf(ServerChatEvent.Delta("尾帧")), parser.flush())
        assertTrue("flush 之后状态要清干净", parser.flush().isEmpty())
    }

    @Test
    fun `已知事件类型但 data 不是 JSON 时按契约破坏失败`() {
        val parser = ServerSseParser()
        parser.feed("event: delta")

        // dispatch 发生在空行（服务端一帧的结束）：坏 JSON 要在这一行才判得出来。
        val error = assertThrows(ServerApiException::class.java) {
            parser.feed("data: not-json")
            parser.feed("")
        }

        assertEquals("invalid_sse_data", error.code)
    }

    // ------------------------------------------------------------------ 发送：请求形状

    @Test
    fun `sendMessage 的请求形状与事件序列`() = runBlocking {
        val sse = buildString {
            append("event: accepted\r\n")
            append("data: {\"userMessageId\":\"u-1\",\"createdAt\":42}\r\n")
            append("\r\n")
            append(": keepalive\r\n")
            append("\r\n")
            append("event: delta\r\n")
            append("data: {\"text\":\"你\"}\r\n")
            append("\r\n")
            append("event: delta\r\n")
            append("data: {\"text\":\r\n")
            append("data: \"好\"}\r\n")
            append("\r\n")
            append("event: tool\r\n")
            append("data: {\"name\":\"web_search\",\"status\":\"running\",\"summary\":null}\r\n")
            append("\r\n")
            append("event: heartbeat\r\n")
            append("data: {}\r\n")
            append("\r\n")
            append("event: done\r\n")
            append("data: {\"assistantMessageId\":\"a-1\",\"usage\":{\"promptTokens\":12,\"completionTokens\":34}}\r\n")
            append("\r\n")
        }
        server.responder = {
            FakeHttpTestServer.FakeReply.Stream { exchange ->
                // 任意字节边界分片：一次 7 字节，能切在行中间、UTF-8 字符中间、CRLF 中间。
                val bytes = sse.toByteArray(Charsets.UTF_8)
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(offset + 7, bytes.size)
                    exchange.write(bytes.copyOfRange(offset, end))
                    offset = end
                    Thread.sleep(2)
                }
            }
        }

        val events = CopyOnWriteArrayList<ServerChatEvent>()
        api.sendMessage(
            sessionId = "s1",
            clientMessageId = "cm-1",
            content = "你好",
            attachments = listOf("media-1"),
            onEvent = { events += it }
        )

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/sessions/s1/messages", request.path)
        assertEquals("text/event-stream", request.headers["accept"])
        assertEquals("Bearer test-token", request.headers["authorization"])
        assertTrue(request.headers["content-type"].orEmpty().startsWith("application/json"))
        assertTrue(request.body.contains("\"clientMessageId\":\"cm-1\""))
        assertTrue(request.body.contains("\"content\":\"你好\""))
        assertTrue(request.body.contains("\"attachments\":[\"media-1\"]"))

        assertEquals(
            listOf(
                ServerChatEvent.Accepted(userMessageId = "u-1", createdAt = 42),
                ServerChatEvent.Delta("你"),
                ServerChatEvent.Delta("好"),
                ServerChatEvent.Tool(name = "web_search", status = "running", summary = null),
                ServerChatEvent.Done(
                    assistantMessageId = "a-1",
                    usage = ServerUsage(promptTokens = 12, completionTokens = 34)
                )
            ),
            events.toList()
        )
    }

    @Test
    fun `多字节字符被拆到两个 chunk 也不会乱码`() = runBlocking {
        server.responder = {
            FakeHttpTestServer.FakeReply.Stream { exchange ->
                exchange.write("event: accepted\ndata: {\"userMessageId\":\"u-1\",\"createdAt\":1}\n\n")
                val prefix = "event: delta\ndata: {\"text\":\"".toByteArray(Charsets.UTF_8)
                val glyph = "你".toByteArray(Charsets.UTF_8)
                exchange.write(prefix)
                exchange.write(glyph.copyOfRange(0, 1))
                Thread.sleep(5)
                exchange.write(glyph.copyOfRange(1, glyph.size) + "\"}\n\n".toByteArray(Charsets.UTF_8))
                exchange.write("event: done\ndata: {\"assistantMessageId\":\"a-1\",\"usage\":{}}\n\n")
            }
        }

        val events = CopyOnWriteArrayList<ServerChatEvent>()
        api.sendMessage("s1", "cm-1", "hi") { events += it }

        assertEquals(
            listOf(
                ServerChatEvent.Accepted("u-1", 1),
                ServerChatEvent.Delta("你"),
                ServerChatEvent.Done("a-1", ServerUsage())
            ),
            events.toList()
        )
    }

    @Test
    fun `收到 done 后不再读取后续事件`() = runBlocking {
        server.responder = {
            FakeHttpTestServer.FakeReply.Stream { exchange ->
                exchange.write("event: accepted\ndata: {\"userMessageId\":\"u-1\",\"createdAt\":1}\n\n")
                exchange.write("event: done\ndata: {\"assistantMessageId\":\"a-1\",\"usage\":{}}\n\n")
                // done 之后服务端还发了内容：客户端已经收工，不该再回调。
                exchange.write("event: delta\ndata: {\"text\":\"late\"}\n\n")
            }
        }

        val events = CopyOnWriteArrayList<ServerChatEvent>()
        api.sendMessage("s1", "cm-1", "hi") { events += it }

        assertEquals(
            listOf(
                ServerChatEvent.Accepted("u-1", 1),
                ServerChatEvent.Done("a-1", ServerUsage())
            ),
            events.toList()
        )
    }

    @Test
    fun `error 事件作为事件交给调用方而不是抛异常`() = runBlocking {
        server.responder = {
            FakeHttpTestServer.FakeReply.Stream { exchange ->
                exchange.write("event: error\ndata: {\"code\":\"llm_unavailable\",\"message\":\"模型暂时不可用\"}\n\n")
                exchange.write("event: delta\ndata: {\"text\":\"late\"}\n\n")
            }
        }

        val events = CopyOnWriteArrayList<ServerChatEvent>()
        api.sendMessage("s1", "cm-1", "hi") { events += it }

        assertEquals(
            listOf(ServerChatEvent.Error(code = "llm_unavailable", message = "模型暂时不可用")),
            events.toList()
        )
    }

    @Test
    fun `流还没开始就被拒绝时按统一错误体失败`() {
        server.respondJson(
            """{"error":{"message":"没有这个会话","code":"not_found"}}""",
            status = 404
        )
        val events = CopyOnWriteArrayList<ServerChatEvent>()

        val error = assertThrows(ServerApiException::class.java) {
            runBlocking { api.sendMessage("missing", "cm-1", "hi") { events += it } }
        }

        assertEquals("not_found", error.code)
        assertEquals(404, error.status)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `SSE 用 0 读超时 普通请求仍是配置的读超时`() {
        val impatient = ServerApi(
            config = ServerConfig(baseUrl = server.baseUrl, enabled = true),
            debuggable = true,
            readTimeoutMillis = 150
        )

        assertEquals(150L, impatient.requestReadTimeoutMillis)
        assertEquals("SSE 靠 done/取消结束，不能继承 30s 读超时", 0L, impatient.sseReadTimeoutMillis)
    }

    @Test
    fun `慢速流不会因为普通读超时被中断`() = runBlocking {
        val impatient = ServerApi(
            config = ServerConfig(baseUrl = server.baseUrl, enabled = true),
            debuggable = true,
            readTimeoutMillis = 150
        )
        server.responder = {
            FakeHttpTestServer.FakeReply.Stream { exchange ->
                exchange.write("event: accepted\ndata: {\"userMessageId\":\"u-1\",\"createdAt\":1}\n\n")
                // 超过「普通读超时」的停顿：SSE 客户端读超时是 0，所以不该失败。
                Thread.sleep(400)
                exchange.write("event: done\ndata: {\"assistantMessageId\":\"a-1\",\"usage\":{}}\n\n")
            }
        }

        val events = CopyOnWriteArrayList<ServerChatEvent>()
        impatient.sendMessage("s1", "cm-1", "hi") { events += it }

        assertEquals(
            listOf(
                ServerChatEvent.Accepted("u-1", 1),
                ServerChatEvent.Done("a-1", ServerUsage())
            ),
            events.toList()
        )
    }

    // ------------------------------------------------------------------ 取消：必须关连接

    @Test
    fun `取消协程会关闭 SSE 连接`() = runBlocking {
        val acceptedReceived = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        server.responder = {
            FakeHttpTestServer.FakeReply.Stream { exchange ->
                exchange.write("event: accepted\ndata: {\"userMessageId\":\"u-1\",\"createdAt\":1}\n\n")
                // 阻塞在读取上：客户端 cancel 会关 socket，read 立刻返回 -1。
                val read = exchange.input.read(ByteArray(1))
                if (read < 0) peerClosed.countDown()
            }
        }

        val events = CopyOnWriteArrayList<ServerChatEvent>()
        val job = launch(Dispatchers.IO) {
            api.sendMessage("s1", "cm-1", "hi") { event ->
                events += event
                if (event is ServerChatEvent.Accepted) acceptedReceived.countDown()
            }
        }

        assertTrue("要先收到 accepted 再取消", acceptedReceived.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue("取消后服务端应看到连接被关闭", peerClosed.await(5, TimeUnit.SECONDS))
        assertTrue(events.none { it is ServerChatEvent.Done })
    }

    @Test
    fun `服务端提前关闭流时明确报 stream_incomplete 而不是静默成功`() {
        server.responder = {
            FakeHttpTestServer.FakeReply.Stream { exchange ->
                exchange.write("event: accepted\ndata: {\"userMessageId\":\"u-1\",\"createdAt\":1}\n\n")
                // 直接断开：没有 done / error（代理超时、进程被杀都会长这样）。
            }
        }
        val events = CopyOnWriteArrayList<ServerChatEvent>()

        val error = assertThrows(ServerApiException::class.java) {
            runBlocking { api.sendMessage("s1", "cm-1", "hi") { events += it } }
        }

        assertEquals("stream_incomplete", error.code)
        assertEquals(
            "半截流也要把已收到的事件留给调用方（用户消息已经落库了）",
            listOf(ServerChatEvent.Accepted("u-1", 1)),
            events.toList()
        )
    }
}
