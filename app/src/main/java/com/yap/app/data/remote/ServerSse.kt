package com.yap.app.data.remote

import com.yap.app.network.ApiException
import com.yap.app.network.SseLines
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.BufferedSource

/**
 * `POST /v1/sessions/{id}/messages` 的流式事件（契约 §3.3）。
 *
 * 顺序：`accepted` → `delta`* → `tool`*（可选）→ `done`；任何阶段出错可以用 `error` 结束。
 * 未知事件类型（服务端未来新增的）由解析器**静默忽略**，不让新字段/新事件把客户端的整次发送打挂。
 */
sealed class ServerChatEvent {

    /** 用户消息已落库（幂等重复请求也发它，`userMessageId` 是同一条）。 */
    data class Accepted(val userMessageId: String, val createdAt: Long) : ServerChatEvent()

    /** 一段回复增量；拼接顺序就是显示顺序。 */
    data class Delta(val text: String) : ServerChatEvent()

    /** 工具调用进度（服务端只转发，客户端只展示）。 */
    data class Tool(val name: String, val status: String, val summary: String?) : ServerChatEvent()

    /** assistant 消息已落库；[assistantMessageId] 是服务端权威 id。 */
    data class Done(val assistantMessageId: String, val usage: ServerUsage) : ServerChatEvent()

    /** 服务端在流里报错；`done` 不再发。 */
    data class Error(val code: String, val message: String) : ServerChatEvent()
}

/**
 * 逐行 SSE 解析器（纯 Kotlin，无 IO，可单测）。
 *
 * 支持的 SSE 细节（都是真实实现里最容易出错的几条）：
 *
 * - **空行**才是一帧的结束：`event:`/`data:` 是「攒着」的，空行时才 dispatch；
 * - **多行 `data:`** 按规范用 `\n` 拼接（服务端可能把一个 JSON 对象拆成几行发）；
 * - 冒号开头的**注释行**（`: keepalive`）忽略——代理 / 服务端常靠它保活；
 * - `field` 与 `value` 之间**最多去掉一个前导空格**（`data:{"a":1}` 与 `data: {"a":1}` 等价）；
 * - `id:` / `retry:` / 其它未知字段忽略；
 * - `\r\n` 由调用方（[readEvents] 的读取循环）在喂行前剥掉尾部的 `\r`；
 * - 未知的**事件类型**忽略；已知事件类型但 data 不是合法 JSON → 抛
 *   [ServerApiException]（`code = "invalid_sse_data"`），因为这属于契约破坏，不该静默吞掉。
 *
 * 为什么不用 `okhttp3.sse.EventSources`：那是 `okhttp-sse` 依赖，本项目没有也不打算为
 * 这一个接口引进来；解析只有几十行，且可以在纯 JVM 里逐条钉住分片/多行/注释行为。
 */
class ServerSseParser(private val json: Json = ServerApi.DEFAULT_JSON) {

    private val data = StringBuilder()
    private var dataStarted = false
    private var eventName: String? = null

    /**
     * 喂一行（不含行尾 `\n`，尾部 `\r` 应已被剥掉）。
     *
     * @return 这一行导致的完整事件（通常是 0 个；空行会 dispatch 出 0..1 个）。
     */
    fun feed(line: String): List<ServerChatEvent> {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return emptyList()

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        // SSE 规范：冒号后只去掉**一个**前导空格，其它空格属于 data 本身。
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (dataStarted) data.append('\n')
                data.append(value)
                dataStarted = true
                if (data.length > MAX_EVENT_DATA_CHARS) {
                    throw ServerApiException("an SSE event payload is too large", code = "response_too_large")
                }
            }
            // `id` / `retry` / 未知字段：P1a 不需要，忽略。
            else -> Unit
        }
        return emptyList()
    }

    /** 流结束时把「没有以空行收尾」的最后一帧也 dispatch 出去（服务端正常会补空行，这里兜底）。 */
    fun flush(): List<ServerChatEvent> = dispatch()

    private fun dispatch(): List<ServerChatEvent> {
        val name = eventName
        val payload = if (dataStarted) data.toString() else null
        eventName = null
        data.setLength(0)
        dataStarted = false
        if (name == null || payload == null) return emptyList()
        return listOfNotNull(decode(name, payload))
    }

    private fun decode(name: String, payload: String): ServerChatEvent? = when (name) {
        "accepted" -> {
            val data = objectOf(name, payload)
            ServerChatEvent.Accepted(
                userMessageId = data.string("userMessageId"),
                createdAt = data.long("createdAt")
            )
        }

        "delta" -> ServerChatEvent.Delta(objectOf(name, payload).string("text"))

        "tool" -> {
            val data = objectOf(name, payload)
            ServerChatEvent.Tool(
                name = data.string("name"),
                status = data.string("status"),
                summary = data.stringOrNull("summary")
            )
        }

        "done" -> {
            val data = objectOf(name, payload)
            val usage = data["usage"] as? JsonObject
            ServerChatEvent.Done(
                assistantMessageId = data.string("assistantMessageId"),
                usage = ServerUsage(
                    promptTokens = usage?.get("promptTokens")?.jsonPrimitive?.longOrNull ?: 0,
                    completionTokens = usage?.get("completionTokens")?.jsonPrimitive?.longOrNull ?: 0
                )
            )
        }

        "error" -> {
            val data = objectOf(name, payload)
            ServerChatEvent.Error(code = data.string("code"), message = data.string("message"))
        }

        // 服务端新增的事件类型：忽略，但游标/连接照常继续读。
        else -> null
    }

    private fun objectOf(name: String, payload: String): JsonObject = try {
        json.parseToJsonElement(payload).jsonObject
    } catch (invalid: Exception) {
        throw ServerApiException(
            "invalid SSE data for event '$name'",
            code = "invalid_sse_data",
            cause = invalid
        )
    }

    private fun JsonObject.string(name: String): String = stringOrNull(name).orEmpty()

    private fun JsonObject.stringOrNull(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull ?: 0

    companion object {
        /** 单个 SSE 事件 data 的上限（工具摘要等可能不小，但 1 MiB 已经是异常量级）。 */
        const val MAX_EVENT_DATA_CHARS = 1024 * 1024

        /**
         * 从 OkHttp 的流式响应体读事件。
         *
         * 不在这里 `use` 响应：调用方（[ServerApi.sendMessage]）已经用 `response.use` 包住了，
         * 读完/取消/异常都会走到 close —— **取消协程时必须关连接**，否则 0 读超时的 SSE
         * 会永远挂着一个 socket。
         */
        fun readEvents(
            source: BufferedSource,
            onEvent: (ServerChatEvent) -> Unit,
            parser: ServerSseParser = ServerSseParser()
        ) {
            while (true) {
                val line = try {
                    SseLines.readLine(source)
                } catch (tooLong: ApiException) {
                    throw ServerApiException(
                        "an SSE line exceeds the size limit",
                        code = "response_too_large",
                        cause = tooLong
                    )
                }
                if (line == null) break
                val events = parser.feed(line.removeSuffix("\r"))
                for (event in events) {
                    onEvent(event)
                    if (event is ServerChatEvent.Done || event is ServerChatEvent.Error) return
                }
            }
            parser.flush().forEach(onEvent)
        }
    }
}
