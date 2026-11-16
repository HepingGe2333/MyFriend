package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.Message
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 真实 `ServerSocket` 上的集成测试（端口 0 = 随机分配）：
 * - 新路由真的能通过 HTTP 访问（JSON + SSE）；
 * - SSE 是 `text/event-stream`、没有 `Content-Length`、帧顺序 accepted → delta → done；
 * - **客户端提前断开，服务端仍然生成并落库**（契约 §3.3 的关键行为）；
 * - token 与限流对新路由同样生效；
 * - `/v1/events` 长轮询在别的连接写入后立刻返回。
 */
class AuthorityServerTest {

    private lateinit var root: Path
    private lateinit var store: FileStore
    private val clock = TestSupport.FakeClock(1_000L)
    private val client = HttpClient.newHttpClient()
    private val servers = mutableListOf<AgentdServer.StartedServer>()

    private val sessionId: String get() = FileStore.SEED_SESSION_ID

    @Before
    fun setUp() {
        root = TestSupport.tempDir("agentd-server-test")
        clock.now = 1_000L
        store = FileStore.open(root, clock.reader)
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
        TestSupport.deleteRecursively(root)
    }

    private fun start(
        generator: ReplyGenerator = EchoReplyGenerator(),
        token: String? = null,
        rateLimiter: RateLimiter = RateLimiter()
    ): AgentdServer.StartedServer {
        val server = AgentdServer.start(
            "127.0.0.1",
            0,
            token,
            rateLimiter,
            AgentdServices(store, generator)
        )
        servers += server
        return server
    }

    private fun get(port: Int, path: String, token: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET()
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(
        port: Int,
        path: String,
        body: String,
        token: String? = null,
        accept: String? = null
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (accept != null) builder.header("Accept", accept)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    // ------------------------------------------------------------------ 基本链路

    @Test
    fun `real server serves state and streams a chat reply over sse`() {
        val server = start()

        val state = get(server.port, "/v1/state")
        assertEquals(200, state.statusCode())
        assertTrue(
            "state 是 JSON：${state.headers().firstValue("Content-Type").orElse("")}",
            state.headers().firstValue("Content-Type").orElse("").contains("application/json")
        )
        val stateJson = TestSupport.parse(state.body())
        assertEquals(3, stateJson.objects("personas").size)
        assertEquals(1, stateJson.objects("sessions").size)

        val chat = post(
            server.port,
            "/v1/sessions/$sessionId/messages",
            """{"clientMessageId":"http-1","content":"在吗","attachments":[]}""",
            accept = "text/event-stream"
        )
        assertEquals("SSE 响应：${chat.body()}", 200, chat.statusCode())
        assertTrue(
            chat.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")
        )
        assertTrue(
            "SSE 不写 Content-Length（靠关闭连接表示结束）",
            chat.headers().firstValue("Content-Length").isEmpty
        )

        val body = chat.body()
        val accepted = body.indexOf("event: accepted")
        val delta = body.indexOf("event: delta")
        val done = body.indexOf("event: done")
        assertTrue("帧顺序必须是 accepted → delta → done：\n$body", accepted >= 0)
        assertTrue("缺少 delta：\n$body", delta > accepted)
        assertTrue("缺少 done：\n$body", done > delta)
        assertTrue(body.contains("\"userMessageId\""))
        assertTrue(body.contains("\"assistantMessageId\""))
        assertTrue(body.contains("收到啦，我在。"))

        // 落库之后，另一个连接通过 /v1/state 也能看到
        val after = TestSupport.parse(get(server.port, "/v1/state").body())
        assertEquals(2, after.objects("messages").size)
        assertEquals(1, after.objects("sessions").single().int("unread"))
    }

    /**
     * 契约 §3.3 的关键行为：客户端读到一半就断开，服务端**继续生成并落库**。
     *
     * 用原始 socket：发完请求读一小段就 `close()`（相当于 App 被杀）。生成器故意慢，
     * 好让断开发生在生成中途；断言 assistant 消息最终落库、并且新连接能读到。
     */
    @Test
    fun `a client that disconnects mid stream still gets the reply persisted`() {
        val slowGenerator = ReplyGenerator { _, emit ->
            repeat(200) { chunk ->
                emit(SseEvent.Delta("块$chunk"))
                Thread.sleep(3)
            }
            Usage(promptTokens = 10, completionTokens = 20)
        }
        val server = start(slowGenerator)
        val payload = """{"clientMessageId":"gone-1","content":"你慢慢说，我先走了"}"""
        val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)

        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 10_000
            val request = buildString {
                append("POST /v1/sessions/$sessionId/messages HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Content-Type: application/json\r\n")
                append("Accept: text/event-stream\r\n")
                append("Content-Length: ${payloadBytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.ISO_8859_1))
                write(payloadBytes)
                flush()
            }
            // 读到 accepted 就断开（模拟客户端提前退出）
            socket.getInputStream().read(ByteArray(128))
        }

        // 服务端没有「客户端走了就中止」的分支：assistant 消息必须最终出现。
        // 用量是在落库之后累加的，所以等到「消息在 + 用量在」再断言，避免和最后一步抢跑。
        val deadline = System.currentTimeMillis() + 15_000
        var assistant: Message? = null
        while (System.currentTimeMillis() < deadline) {
            assistant = store.blockMessages(sessionId, 0).lastOrNull { it.role == MessageRole.ASSISTANT }
            if (assistant != null && store.usage(FileStore.SEED_PERSONA_1_ID).replies == 1L) break
            Thread.sleep(50)
        }
        assertNotNull("客户端断开后服务端必须继续生成并落库", assistant)
        assertTrue("已经生成的内容要保存下来：${assistant?.content?.length}", (assistant?.content?.length ?: 0) > 50)
        assertEquals(1L, store.usage(FileStore.SEED_PERSONA_1_ID).replies)

        // 新连接也能通过 HTTP 读到（客户端下次同步即可）
        val state = TestSupport.parse(get(server.port, "/v1/state").body())
        assertEquals(2, state.objects("messages").size)
    }

    // ------------------------------------------------------------------ 鉴权 / 限流

    @Test
    fun `shared token also guards the authoritative routes`() {
        val token = "test-token-0123456789"
        val server = start(token = token)

        assertEquals(401, get(server.port, "/v1/state").statusCode())
        assertEquals(401, get(server.port, "/v1/events?since=0").statusCode())
        assertEquals(
            401,
            post(
                server.port,
                "/v1/sessions/$sessionId/messages",
                """{"clientMessageId":"t-1","content":"x"}"""
            ).statusCode()
        )

        assertEquals(200, get(server.port, "/v1/state", token).statusCode())
        val chat = post(
            server.port,
            "/v1/sessions/$sessionId/messages",
            """{"clientMessageId":"t-2","content":"带 token"}""",
            token = token
        )
        assertEquals(200, chat.statusCode())
        assertTrue(chat.body().contains("event: done"))
    }

    @Test
    fun `rate limiting also applies to the authoritative routes`() {
        val server = start(rateLimiter = RateLimiter(perMinute = 6, burst = 2))
        repeat(2) { assertEquals(200, get(server.port, "/v1/state").statusCode()) }
        val limited = get(server.port, "/v1/state")
        assertEquals(429, limited.statusCode())
        assertTrue(limited.body().contains("rate_limited"))
        assertTrue(
            "429 要带 Retry-After",
            (limited.headers().firstValue("Retry-After").orElse("").toIntOrNull() ?: 0) >= 1
        )
    }

    // ------------------------------------------------------------------ 长轮询 / 错误

    @Test
    fun `events long poll over http returns as soon as a message is appended`() {
        val server = start()
        val since = store.cursor()
        val pending = CompletableFuture.supplyAsync {
            get(server.port, "/v1/events?since=$since&timeoutSeconds=5")
        }
        Thread.sleep(300)
        store.appendMessage(sessionId, MessageRole.USER, "醒醒（长轮询）")

        val response = pending.get(8, TimeUnit.SECONDS)
        assertEquals(200, response.statusCode())
        val json = TestSupport.parse(response.body())
        val events = json.objects("events")
        assertEquals(1, events.size)
        assertEquals("message.created", events.single().str("type"))
        assertEquals(store.cursor(), json.long("cursor"))
    }

    @Test
    fun `sse validation errors come back as plain json before the stream starts`() {
        val server = start()
        val unknown = post(
            server.port,
            "/v1/sessions/does-not-exist/messages",
            """{"clientMessageId":"n-1","content":"x"}"""
        )
        assertEquals(404, unknown.statusCode())
        assertTrue(
            "校验失败时还没有开始 SSE：${unknown.headers().firstValue("Content-Type").orElse("")}",
            unknown.headers().firstValue("Content-Type").orElse("").contains("application/json")
        )
        assertTrue(unknown.body().contains("not_found"))

        val invalid = post(
            server.port,
            "/v1/sessions/$sessionId/messages",
            """{"content":"没有幂等键"}"""
        )
        assertEquals(400, invalid.statusCode())
        assertTrue(invalid.body().contains("missing_field"))
        assertTrue("校验失败不能落消息", store.blockMessages(sessionId, 0).isEmpty())
    }
}
