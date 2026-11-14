package com.myfriend.agentd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * 集成测试：真的起一个 `HttpServer`（端口 0 = 系统分配的随机端口），用 JDK `HttpClient` 请求。
 *
 * 只验证「handler 层之外」的那一小段：字节读写、Content-Type、状态码透传。
 * 端口不写死，避免和本机别的进程/并行测试抢 8788。
 */
class AgentdServerTest {

    private val client = HttpClient.newHttpClient()

    private fun get(port: Int, path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )

    private fun post(port: Int, path: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

    @Test
    fun `real server answers health and core endpoints`() {
        val server = AgentdServer.start("127.0.0.1", 0)
        try {
            val port = server.address.port

            val health = get(port, "/health")
            assertEquals(200, health.statusCode())
            assertTrue(health.headers().firstValue("Content-Type").orElse("").contains("application/json"))
            assertEquals(
                """{"ok":true,"service":"myfriend-agentd","version":"0.10.0"}""",
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
            server.stop(0)
        }
    }
}
