package com.yap.agentd.search

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 测试用的假服务端：JDK 自带的 `com.sun.net.httpserver.HttpServer`（零第三方依赖，和 `agentd` 一致）。
 *
 * 每条请求都被录下来（方法、路径、查询串、头、body），这样测试能直接钉住「每个 provider 的请求形状」，
 * 而不只是断言「解析结果对」——请求形状错了，真实 provider 一样会失败。
 *
 * 端口用 0（系统分配），不与本机别的进程抢端口；[close] 负责停服务器并关线程池。
 */
internal class FakeHttpServer(
    private val responder: (FakeRequest) -> FakeResponse
) : AutoCloseable {

    data class FakeRequest(
        val method: String,
        val path: String,
        val rawQuery: String,
        val headers: Map<String, String>,
        val body: String
    ) {
        /** 解码后的查询参数（键大小写不敏感；同名只留第一个）。 */
        val query: Map<String, String> by lazy {
            rawQuery.split('&')
                .filter { it.isNotBlank() }
                .associate { pair ->
                    val key = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
                }
        }

        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    data class FakeResponse(
        val status: Int = 200,
        val body: String = "{}",
        val contentType: String = "application/json; charset=utf-8",
        /** 直接给字节（大响应测试用，避免先拼一个 2 MB 的 String）。 */
        val bytes: ByteArray? = null
    )

    val requests = CopyOnWriteArrayList<FakeRequest>()

    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "fake-http-server").apply { isDaemon = true }
    }

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
        this.executor = this@FakeHttpServer.executor
        start()
    }

    /** 直接给 `WebSearchService` 用的 base URL（`http://127.0.0.1:<port>`）。 */
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    /** 唯一一条请求；多于/少于一条都直接失败（比 `requests[0]` 更早暴露「多发了一次请求」）。 */
    fun onlyRequest(): FakeRequest {
        check(requests.size == 1) { "期望 1 条请求，实际 ${requests.size} 条：${requests.map { it.method + " " + it.path }}" }
        return requests.single()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val request = FakeRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                rawQuery = exchange.requestURI.rawQuery.orEmpty(),
                headers = exchange.requestHeaders.entries.associate { it.key to it.value.firstOrNull().orEmpty() },
                body = body
            )
            requests += request
            val response = responder(request)
            val payload = response.bytes ?: response.body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", response.contentType)
            exchange.sendResponseHeaders(response.status, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        } catch (failure: Exception) {
            // 假服务端自己出错时不要静默：把它变成 500，测试断言会看到状态码。
            runCatching {
                val payload = "fake server error: ${failure::class.simpleName}".toByteArray()
                exchange.sendResponseHeaders(500, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        } finally {
            exchange.close()
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
