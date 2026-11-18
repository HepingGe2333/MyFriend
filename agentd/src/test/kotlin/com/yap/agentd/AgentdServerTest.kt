package com.yap.agentd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * 集成测试：真的起一个 `HttpServer`（端口 0 = 系统分配的随机端口），用 JDK `HttpClient` / 原始 socket 请求。
 *
 * 覆盖「handler 层之外」的那一小段：字节读写、Content-Type、状态码透传，以及 0.11.1 的三条网络加固：
 * - 半开连接不阻塞 `/health`，并且自己会在读超时后拿到 408（审计 P1-1）；
 * - 声明超过 1 MiB 的 body 直接 413，不读进内存（审计 P2-1）；
 * - 可选共享 token：缺失/错误 401，正确时行为不变。
 *
 * 端口不写死，避免和本机别的进程/并行测试抢 8788。
 */
class AgentdServerTest {

    private val client = HttpClient.newHttpClient()

    private fun get(port: Int, path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )

    private fun post(port: Int, path: String, body: String, token: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `real server answers health and core endpoints`() {
        val server = AgentdServer.start("127.0.0.1", 0)
        try {
            val port = server.port

            val health = get(port, "/health")
            assertEquals(200, health.statusCode())
            assertTrue(health.headers().firstValue("Content-Type").orElse("").contains("application/json"))
            assertEquals(
                """{"ok":true,"service":"yap-agentd","version":"0.13.0"}""",
                health.body()
            )

            val defer = post(
                port,
                "/v1/core/defer-plan",
                """{"minutes":90,"reason":"现在不想说话","now":1000}"""
            )
            assertEquals(200, defer.statusCode())
            assertTrue(defer.body().contains(""""earliestAt":5401000"""))
            assertTrue(defer.body().contains(""""minutes":90"""))

            val missing = get(port, "/v1/core/nope")
            assertEquals(404, missing.statusCode())
            assertTrue(missing.body().contains("not_found"))

            val broken = post(port, "/v1/core/defer-plan", """{"now":""")
            assertEquals(400, broken.statusCode())
            assertTrue(broken.body().contains("invalid_json"))
        } finally {
            server.stop()
        }
    }

    /**
     * 审计 P1-1 的原始复现：声明 `Content-Length: 1000` 却只发半截 body。
     *
     * 断言两件事：① 半开连接还在时 `/health` 仍然立刻 200（修复前会等到它断开才处理）；
     * ② 半开连接在 `READ_TIMEOUT_MS` 后收到 408 并被关闭（不会永久占住 worker）。
     */
    @Test
    fun `half open body does not block health and answers 408`() {
        val server = AgentdServer.start("127.0.0.1", 0)
        try {
            val port = server.port
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = (AgentdServer.READ_TIMEOUT_MS + 10_000L).toInt()
                val out = socket.getOutputStream()
                out.write(
                    (
                        "POST /v1/core/tool-args HTTP/1.1\r\n" +
                            "Host: 127.0.0.1\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: 1000\r\n\r\n" +
                            "{\"tool\":"
                        ).toByteArray(StandardCharsets.US_ASCII)
                )
                out.flush()

                // 半开连接还在时，/health 必须仍然很快可用（池里还有空闲 worker）。
                val startedAt = System.nanoTime()
                val health = get(port, "/health")
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                assertEquals(200, health.statusCode())
                assertTrue("半开连接卡住了 /health：${elapsedMs}ms", elapsedMs < 2_000)

                // 半开请求本身在读超时后收到 408，然后连接被关闭（readAll 能读到 EOF）。
                val response = readAll(socket.getInputStream())
                assertTrue("期望 408，实际：${response.take(160)}", response.startsWith("HTTP/1.1 408"))
                assertTrue(response.contains("request_timeout"))
            }

            // 408 之后 worker 必须已经释放：下一条请求仍然立刻可用。
            assertEquals(200, get(port, "/health").statusCode())
        } finally {
            server.stop()
        }
    }

    /** chunked body 在 `HttpExchange` 上只能阻塞读、无法自己计时，所以显式拒绝（411）。 */
    @Test
    fun `chunked bodies are rejected with 411`() {
        val server = AgentdServer.start("127.0.0.1", 0)
        try {
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 15_000
                socket.getOutputStream().apply {
                    write(
                        (
                            "POST /v1/core/tool-args HTTP/1.1\r\n" +
                                "Host: 127.0.0.1\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Transfer-Encoding: chunked\r\n\r\n" +
                                "5\r\nhello\r\n0\r\n\r\n"
                            ).toByteArray(StandardCharsets.US_ASCII)
                    )
                    flush()
                }
                val response = readAll(socket.getInputStream())
                assertTrue("期望 411，实际：${response.take(160)}", response.startsWith("HTTP/1.1 411"))
                assertTrue(response.contains("length_required"))
            }
        } finally {
            server.stop()
        }
    }

    /** 声明超过 1 MiB 的 body：不必真的发完，头部就能判定 413。 */
    @Test
    fun `declared oversized body is rejected with 413`() {
        val server = AgentdServer.start("127.0.0.1", 0)
        try {
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 15_000
                socket.getOutputStream().apply {
                    write(
                        (
                            "POST /v1/core/tool-args HTTP/1.1\r\n" +
                                "Host: 127.0.0.1\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${AgentdApi.MAX_BODY_BYTES + 1}\r\n\r\n"
                            ).toByteArray(StandardCharsets.US_ASCII)
                    )
                    flush()
                }
                val response = readAll(socket.getInputStream())
                assertTrue("期望 413，实际：${response.take(160)}", response.startsWith("HTTP/1.1 413"))
                assertTrue(response.contains("content_too_large"))
            }
        } finally {
            server.stop()
        }
    }

    /** 设置共享 token 后：所有请求（含 `/health`）缺 token / 错 token 都 401，带对了行为不变。 */
    @Test
    fun `shared token guards every request`() {
        val token = "test-token-0123456789"
        val server = AgentdServer.start("127.0.0.1", 0, token)
        try {
            val port = server.port

            assertEquals(401, get(port, "/health").statusCode())
            assertEquals(401, post(port, "/v1/core/defer-plan", """{"now":0}""").statusCode())

            val wrong = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health"))
                    .header("Authorization", "Bearer wrong-token")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(401, wrong.statusCode())
            assertTrue(wrong.body().contains("unauthorized"))

            val authorized = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health"))
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, authorized.statusCode())

            val defer = post(port, "/v1/core/defer-plan", """{"now":0}""", token)
            assertEquals(200, defer.statusCode())
            assertTrue(defer.body().contains(""""minutes":45"""))
        } finally {
            server.stop()
        }
    }

    // ------------------------------------------------------------------ 0.12.0 限流

    /** 连续请求打空令牌桶：第 3 个必须 429，且带 Retry-After（单位秒、≥ 1）。 */
    @Test
    fun `rate limited core requests answer 429 with Retry-After`() {
        val server = AgentdServer.start(
            "127.0.0.1", 0,
            rateLimiter = RateLimiter(perMinute = 6, burst = 2)
        )
        try {
            val port = server.port
            repeat(2) {
                assertEquals(200, post(port, "/v1/core/defer-plan", """{"now":0}""").statusCode())
            }
            val limited = post(port, "/v1/core/defer-plan", """{"now":0}""")
            assertEquals(429, limited.statusCode())
            assertTrue("错误码要稳定：${limited.body()}", limited.body().contains("rate_limited"))
            val retryAfter = limited.headers().firstValue("Retry-After").orElse("")
            assertTrue(
                "429 必须带 Retry-After（≥1 秒），实际：$retryAfter",
                (retryAfter.toIntOrNull() ?: 0) >= 1
            )
        } finally {
            server.stop()
        }
    }

    /**
     * `/health` 单独计一个桶：业务流量打满配额时探针仍然能拿到 200。
     *
     * 这是有意的取舍（见 README「限流」）：反代后面所有请求折叠成同一个 IP，
     * 共用桶会让「配额用尽」在运维眼里变成「服务挂了」。
     */
    @Test
    fun `health has its own rate limit bucket`() {
        val server = AgentdServer.start(
            "127.0.0.1", 0,
            rateLimiter = RateLimiter(perMinute = 6, burst = 2)
        )
        try {
            val port = server.port
            repeat(2) {
                assertEquals(200, post(port, "/v1/core/defer-plan", """{"now":0}""").statusCode())
            }
            assertEquals(
                "业务桶先打空",
                429,
                post(port, "/v1/core/defer-plan", """{"now":0}""").statusCode()
            )
            // /health 有自己的桶（burst=2）：两次 200，第三次 429 —— 它不是无限额度的旁路。
            assertEquals(200, get(port, "/health").statusCode())
            assertEquals(200, get(port, "/health").statusCode())
            val limited = get(port, "/health")
            assertEquals(429, limited.statusCode())
            assertTrue(
                "Retry-After 至少 1 秒",
                (limited.headers().firstValue("Retry-After").orElse("").toIntOrNull() ?: 0) >= 1
            )
        } finally {
            server.stop()
        }
    }

    /** `--rate-limit 0`（`RateLimiter(perMinute = 0)`）：怎么打都不限流。 */
    @Test
    fun `rate limiting can be disabled`() {
        val server = AgentdServer.start(
            "127.0.0.1", 0,
            rateLimiter = RateLimiter(perMinute = 0, burst = 1)
        )
        try {
            val port = server.port
            repeat(10) { index ->
                val response = post(port, "/v1/core/defer-plan", """{"now":$index}""")
                assertEquals("第 $index 个请求被限流了", 200, response.statusCode())
            }
            repeat(10) { assertEquals(200, get(port, "/health").statusCode()) }
        } finally {
            server.stop()
        }
    }

    /**
     * 读到 EOF 就返回；如果对端在写完之后迟迟不关连接（例如 413/408 路径上的排空逻辑），
     * 超时也把已收到的内容当成完整响应返回 —— 这些用例断言的是状态行与错误码，不是连接关闭时机。
     * 这样在并行构建、JVM 被 CPU 抢占时不会出现「响应其实已经到了、却因为等 EOF 超时而失败」。
     */
    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: java.net.SocketTimeoutException) {
                break
            }
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }
}
