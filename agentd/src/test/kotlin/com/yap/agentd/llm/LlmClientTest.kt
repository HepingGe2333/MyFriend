package com.yap.agentd.llm

import com.yap.agentd.TestSupport
import com.yap.agentd.bool
import com.yap.agentd.int
import com.yap.agentd.objects
import com.yap.agentd.str
import com.yap.agentd.llm.FakeLlmServer.Companion.DONE
import com.yap.agentd.llm.FakeLlmServer.Companion.chunk
import com.yap.agentd.llm.FakeLlmServer.Companion.error
import com.yap.agentd.llm.FakeLlmServer.Companion.usage
import com.yap.app.core.json.JsonValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LLM 客户端（JDK `HttpClient` + OpenAI 兼容 SSE）的契约测试。
 *
 * 假服务端是**真的分块流式**（`Transfer-Encoding: chunked`，每帧 flush），所以这里验证的是
 * 「边生成边回调」而不是「最后拼起来的字符串对不对」。请求体也逐字段钉住：
 * `temperature=0.75` / `max_tokens=16384` 是 `spec/api-defaults` 的验收项。
 */
class LlmClientTest {

    private lateinit var server: FakeLlmServer
    private val client = LlmClient()

    private fun config(baseUrl: String = server.baseUrl, key: String? = "sk-test") = LlmConfig(
        baseUrl = baseUrl,
        model = "test-model",
        apiKey = key,
        source = LlmConfig.Source.CLIENT
    )

    private fun start(responder: (FakeLlmServer.Exchange) -> Unit) {
        server = FakeLlmServer(responder)
    }

    @Before
    fun setUp() {
        // 默认 responder：三个文字分片 + usage + [DONE]；需要别的响应时在用例里重新 start()。
        start { exchange ->
            exchange.sse(listOf(chunk("你"), chunk("好"), chunk("呀"), usage(31, 7), DONE))
        }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    @Test
    fun `streams deltas in order and returns upstream usage`() {
        val deltas = mutableListOf<String>()
        val usage = client.stream(config(), listOf(LlmMessage.user("在吗"))) { deltas += it }

        assertEquals(listOf("你", "好", "呀"), deltas)
        assertEquals(31, usage.promptTokens)
        assertEquals(7, usage.completionTokens)
    }

    @Test
    fun `request body carries model messages stream and the spec default params`() {
        client.stream(config(), listOf(LlmMessage.system("你是林深"), LlmMessage.user("在吗"))) { }

        val request = server.onlyRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test", request.header("Authorization"))

        val body = TestSupport.parse(request.body)
        assertEquals("test-model", body.str("model"))
        assertEquals(true, body.bool("stream"))
        assertEquals(0.75, (body.fields["temperature"] as JsonValue.Num).asDouble, 0.0)
        assertEquals(16384, body.int("max_tokens"))
        val messages = body.objects("messages")
        assertEquals(listOf("system", "user"), messages.map { it.str("role") })
        assertEquals(listOf("你是林深", "在吗"), messages.map { it.str("content") })
    }

    @Test
    fun `ignores sse comments event lines and blank lines`() {
        start { exchange ->
            exchange.sse(
                listOf(
                    ": keep-alive\n\n",
                    "event: message\n",
                    chunk("在"),
                    "\n",
                    chunk("的"),
                    DONE
                )
            )
        }
        val deltas = mutableListOf<String>()
        client.stream(config(), listOf(LlmMessage.user("在吗"))) { deltas += it }
        assertEquals(listOf("在", "的"), deltas)
    }

    @Test
    fun `estimates completion tokens when the gateway omits usage`() {
        start { exchange -> exchange.sse(listOf(chunk("今天有点累，先不聊了。"), DONE)) }
        val usage = client.stream(config(), listOf(LlmMessage.user("在吗"))) { }
        assertTrue("缺 usage 时不能把花费记成 0", usage.completionTokens > 0)
    }

    @Test
    fun `complete collects the whole stream and keeps usage`() {
        val completion = client.complete(config(), listOf(LlmMessage.user("在吗")))
        assertEquals("你好呀", completion.text)
        assertEquals(7, completion.usage.completionTokens)
    }

    @Test
    fun `full completion json without sse content type is accepted as one delta`() {
        start { exchange ->
            exchange.json(200, FakeLlmServer.completion("好的，我在。", prompt = 12, completion = 3))
        }
        val deltas = mutableListOf<String>()
        val usage = client.stream(config(), listOf(LlmMessage.user("在吗"))) { deltas += it }
        assertEquals(listOf("好的，我在。"), deltas)
        assertEquals(12, usage.promptTokens)
        assertEquals(3, usage.completionTokens)
    }

    @Test
    fun `http 401 maps to unauthorized without leaking the key`() {
        start { exchange -> exchange.json(401, """{"error":{"message":"invalid api key"}}""") }

        val failure = runCatching { client.stream(config(), listOf(LlmMessage.user("x"))) { } }
            .exceptionOrNull() as LlmException

        assertEquals(LlmErrorKind.UNAUTHORIZED, failure.kind)
        assertEquals("llm_unauthorized", failure.code)
        assertFalse("错误文案不能带 key", failure.userMessage().contains("sk-test"))
        assertTrue(failure.userMessage().contains("401"))
    }

    @Test
    fun `http 429 maps to rate limited`() {
        start { exchange -> exchange.json(429, """{"error":{"message":"rate limited"}}""") }
        val failure = runCatching { client.stream(config(), listOf(LlmMessage.user("x"))) { } }
            .exceptionOrNull() as LlmException
        assertEquals(LlmErrorKind.RATE_LIMITED, failure.kind)
        assertEquals("llm_rate_limited", failure.code)
    }

    @Test
    fun `error frame inside the stream fails the call after the partial text`() {
        start { exchange ->
            exchange.sse(listOf(chunk("先说一半"), error("upstream boom"), DONE))
        }
        val deltas = mutableListOf<String>()
        val failure = runCatching {
            client.stream(config(), listOf(LlmMessage.user("x"))) { deltas += it }
        }.exceptionOrNull() as LlmException

        assertEquals(listOf("先说一半"), deltas)
        assertEquals(LlmErrorKind.UPSTREAM, failure.kind)
        assertEquals("llm_upstream_error", failure.code)
    }

    @Test
    fun `empty stream is reported instead of returning an empty reply`() {
        start { exchange -> exchange.sse(listOf(DONE)) }
        val failure = runCatching { client.stream(config(), listOf(LlmMessage.user("x"))) { } }
            .exceptionOrNull() as LlmException
        assertEquals(LlmErrorKind.EMPTY_REPLY, failure.kind)
        assertEquals("llm_empty_reply", failure.code)
    }

    @Test
    fun `network failure is classified as a network error`() {
        // 先关掉服务端，连接必然失败。
        val dead = FakeLlmServer { it.json(200, "{}") }
        val baseUrl = dead.baseUrl
        dead.close()

        val failure = runCatching {
            client.stream(config(baseUrl = baseUrl), listOf(LlmMessage.user("x"))) { }
        }.exceptionOrNull() as LlmException

        assertEquals(LlmErrorKind.NETWORK, failure.kind)
    }

    @Test
    fun `invalid base url is rejected before any request`() {
        val failure = runCatching {
            client.stream(
                LlmConfig(baseUrl = "file:///etc/passwd", model = "m"),
                listOf(LlmMessage.user("x"))
            ) { }
        }.exceptionOrNull() as LlmException
        assertEquals(LlmErrorKind.INVALID_CONFIG, failure.kind)
    }
}
