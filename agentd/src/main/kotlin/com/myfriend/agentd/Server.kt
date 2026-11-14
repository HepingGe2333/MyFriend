package com.myfriend.agentd

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * 把 [AgentdApi] 接到 JDK 自带的 `HttpServer` 上。
 *
 * 这一层只做三件事：读方法/路径/请求体 → 调 [AgentdApi.handle] → 写状态码与 JSON。
 * 所有业务与校验都在 API 层，测试因此可以完全不起端口（另有一条集成测试会真的起）。
 */
object AgentdServer {

    fun start(host: String, port: Int): HttpServer {
        val server = HttpServer.create(InetSocketAddress(host, port), 0)
        server.createContext("/") { exchange -> handleExchange(exchange) }
        server.start()
        return server
    }

    private fun handleExchange(exchange: HttpExchange) {
        try {
            val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val response = AgentdApi.handle(
                method = exchange.requestMethod,
                rawPath = exchange.requestURI.toString(),
                body = body
            )
            val bytes = response.body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (failure: Exception) {
            // 到这一步说明连「回错误 JSON」都失败了（比如客户端提前断开）：
            // 尽力回一个 500，但不再抛出去污染服务器线程。
            val bytes = """{"error":{"message":"服务内部错误","code":"internal_error"}}"""
                .toByteArray(Charsets.UTF_8)
            runCatching {
                exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(500, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        } finally {
            exchange.close()
        }
    }
}
