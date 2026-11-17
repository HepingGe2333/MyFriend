package com.yap.agentd.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 测试用「假模型服务端」：JDK 自带 `com.sun.net.httpserver`，零第三方依赖。
 *
 * 与 `search/FakeHttpServer` 的区别只有一个但很关键：**能真的分块流式写**。
 * `LlmClient` 的验收点是「边生成边下发」，如果假服务端一次把整段响应塞回去，
 * 测试就只能验证"最后拿到什么"，验证不了"第一段是什么时候到的"。
 *
 * 用法：
 * ```kotlin
 * FakeLlmServer { exchange ->
 *     exchange.sse(listOf(chunk("你"), chunk("好"), done), delayMs = 5)
 * }
 * ```
 * 端口用 0（系统分配）；每条请求的方法/路径/头/body 都记下来，测试可以直接钉请求形状。
 */
internal class FakeLlmServer(
    private val responder: (Exchange) -> Unit
) : AutoCloseable {

    data class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    val requests = CopyOnWriteArrayList<RecordedRequest>()

    private val executor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "fake-llm-server").apply { isDaemon = true }
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
        this.executor = this@FakeLlmServer.executor
        start()
    }

    /** OpenAI 兼容根地址：`http://127.0.0.1:<port>/v1`。 */
    val baseUrl: String = "http://127.0.0.1:${server.address.port}/v1"

    fun onlyRequest(): RecordedRequest {
        check(requests.size == 1) {
            "期望 1 条请求，实际 ${requests.size} 条：${requests.map { it.method + " " + it.path }}"
        }
        return requests.single()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val request = RecordedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                headers = exchange.requestHeaders.entries.associate { it.key to it.value.firstOrNull().orEmpty() },
                body = body
            )
            requests += request
            responder(Exchange(exchange, request))
        } catch (failure: Exception) {
            runCatching {
                val payload = "fake llm server error: ${failure::class.simpleName}".toByteArray()
                exchange.sendResponseHeaders(500, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        } finally {
            runCatching { exchange.close() }
        }
    }

    class Exchange(
        private val exchange: HttpExchange,
        val request: RecordedRequest
    ) {

        /** 真分块 SSE：`Transfer-Encoding: chunked`，每帧 flush 一次（可选间隔）。 */
        fun sse(frames: List<String>, delayMs: Long = 0L) {
            exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, 0)
            val output = exchange.responseBody
            for (frame in frames) {
                if (delayMs > 0) Thread.sleep(delayMs)
                output.write(frame.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
            output.close()
        }

        /** 一次性 JSON（非流式/错误响应）。 */
        fun json(status: Int, body: String) {
            val payload = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        fun text(status: Int, body: String, contentType: String) {
            val payload = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        /** 一个文字分片。 */
        fun chunk(text: String): String =
            """data: {"choices":[{"delta":{"content":${jsonString(text)}}}]}""" + "\n\n"

        /** 带 usage 的收尾分片（`choices` 为空，OpenAI 的 include_usage 形状）。 */
        fun usage(prompt: Int, completion: Int): String =
            """data: {"choices":[],"usage":{"prompt_tokens":$prompt,"completion_tokens":$completion}}""" + "\n\n"

        /** 流内的错误分片。 */
        fun error(message: String): String =
            """data: {"error":{"message":${jsonString(message)}}}""" + "\n\n"

        val DONE: String = "data: [DONE]\n\n"

        /** 完整（非流式）completion 响应体。 */
        fun completion(text: String, prompt: Int = 11, completion: Int = 22): String =
            """{"choices":[{"message":{"role":"assistant","content":${jsonString(text)}}}],""" +
                """"usage":{"prompt_tokens":$prompt,"completion_tokens":$completion}}"""

        private fun jsonString(text: String): String = buildString {
            append('"')
            text.forEach { ch ->
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }
    }
}

/** 测试里用的一段固定模型输出（合法 JSON）。 */
internal fun changesJson(vararg changes: String): String =
    """{"changes":[${changes.joinToString(",")}]}"""

internal fun changeJson(
    field: String,
    op: String,
    value: String,
    confidence: Double = 0.9,
    reason: String = "测试"
): String = """{"field":"$field","op":"$op","value":"$value","reason":"$reason","confidence":$confidence}"""
