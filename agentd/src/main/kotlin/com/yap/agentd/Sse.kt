package com.yap.agentd

import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import com.yap.agentd.store.StoreJson

/**
 * SSE 事件（`docs/design/server-authoritative.md` §3.3 的契约）。
 *
 * 只在**一次流式回复**里出现，顺序是：
 * `accepted`（用户消息已落库）→ `delta`（0..n）→ `tool`（0..n，可选）→ `done`；
 * 任何阶段出错都可以用 `error` 结束（`done` 不再发）。
 */
sealed class SseEvent {

    /** SSE 的 `event:` 名字（也是契约里的字段值）。 */
    abstract val type: String

    /** `data:` 里的 JSON 对象。 */
    abstract fun data(): JsonValue.Obj

    /** 一条完整的 SSE 帧（`event: …\ndata: …\n\n`）。 */
    fun frame(): String = "event: $type\ndata: ${Json.stringify(data())}\n\n"

    /** 用户消息已落库（幂等重复请求也发它，`userMessageId` 是同一条）。 */
    data class Accepted(val userMessageId: String, val createdAt: Long) : SseEvent() {
        override val type: String get() = "accepted"
        override fun data(): JsonValue.Obj = StoreJson.obj(
            "userMessageId" to StoreJson.str(userMessageId),
            "createdAt" to StoreJson.num(createdAt)
        )
    }

    data class Delta(val text: String) : SseEvent() {
        override val type: String get() = "delta"
        override fun data(): JsonValue.Obj = StoreJson.obj("text" to StoreJson.str(text))
    }

    /** 工具调用进度（本阶段生成器可以发，服务端只转发不解释）。 */
    data class Tool(val toolName: String, val status: String, val summary: String?) : SseEvent() {
        override val type: String get() = "tool"
        override fun data(): JsonValue.Obj = StoreJson.obj(
            "name" to StoreJson.str(toolName),
            "status" to StoreJson.str(status),
            "summary" to StoreJson.nullableStr(summary)
        )
    }

    /**
     * assistant 消息已落库；`usage` 是这一次生成的 token 数。
     *
     * [silent] 只在 `stay_silent`（这一轮只已读不回）时为 true，此时 [assistantMessageId] 是空串、
     * 库里**没有** assistant 消息。为了不破坏老客户端的解析，这个键只在 true 时出现
     * （`event: done` 的其余字段一个都没变）。
     */
    data class Done(
        val assistantMessageId: String,
        val usage: Usage,
        val silent: Boolean = false
    ) : SseEvent() {
        override val type: String get() = "done"
        override fun data(): JsonValue.Obj {
            val fields = linkedMapOf<String, JsonValue>(
                "assistantMessageId" to StoreJson.str(assistantMessageId),
                "usage" to usage.toJson()
            )
            if (silent) fields["silent"] = StoreJson.bool(true)
            return JsonValue.Obj(fields)
        }
    }

    /**
     * 一条 assistant 消息已经落库（0.14 连发：一轮可能有好几条，按发送顺序逐条下发）。
     *
     * data 与 `GET /v1/events` 的 `message.created` payload 同形：`{"message": {…}}`。
     * 老客户端把未知事件类型静默忽略，新客户端以它为准渲染多条气泡；`delta` 仍保留，
     * 只用于「正在输入…」与单条回复的旧渲染路径。
     */
    data class MessageCreated(val message: com.yap.agentd.store.Message) : SseEvent() {
        override val type: String get() = "message.created"
        override fun data(): JsonValue.Obj = StoreJson.obj("message" to message.toJson())
    }

    /**
     * 0.14.1（`spec/experience` §8）：服务端开始「打字」——一轮的第一条 assistant 消息入队时发一次。
     *
     * 与 `GET /v1/events` 的 `typing.started` 事件**同名同字段**；客户端只在前台收到它时显示
     * 「正在输入…」，离线不补。
     */
    data class TypingStarted(val sessionId: String, val personaId: String, val at: Long) : SseEvent() {
        override val type: String get() = "typing.started"
        override fun data(): JsonValue.Obj = StoreJson.obj(
            "sessionId" to StoreJson.str(sessionId),
            "personaId" to StoreJson.str(personaId),
            "at" to StoreJson.num(at)
        )
    }

    /** 0.14.1：这一轮的消息都发完了（每轮一次 `typing.started` / 一次 `typing.stopped`）。 */
    data class TypingStopped(val sessionId: String, val personaId: String, val at: Long) : SseEvent() {
        override val type: String get() = "typing.stopped"
        override fun data(): JsonValue.Obj = StoreJson.obj(
            "sessionId" to StoreJson.str(sessionId),
            "personaId" to StoreJson.str(personaId),
            "at" to StoreJson.num(at)
        )
    }

    data class Error(val code: String, val message: String) : SseEvent() {
        override val type: String get() = "error"
        override fun data(): JsonValue.Obj = StoreJson.obj(
            "code" to StoreJson.str(code),
            "message" to StoreJson.str(message)
        )
    }
}

/** 一次回复的 token 用量（由 [ReplyGenerator] 提供；服务端只累加，不猜）。 */
data class Usage(val promptTokens: Int = 0, val completionTokens: Int = 0) {
    fun toJson(): JsonValue.Obj = StoreJson.obj(
        "promptTokens" to StoreJson.num(promptTokens.coerceAtLeast(0)),
        "completionTokens" to StoreJson.num(completionTokens.coerceAtLeast(0))
    )

    companion object {
        val ZERO = Usage()
    }
}

/**
 * 事件出口。HTTP 层用 `AgentdServer.HttpSseSink` 写 socket；测试里直接收集。
 *
 * **写失败不能影响生成**：这是「客户端断开后服务端继续生成并落库」的关键（§3.3）。
 * 所以 [ChatService] 会把任何 `emit` 抛出的异常吞掉并只记一次日志（见 `SafeSink`）。
 */
fun interface SseSink {
    fun emit(event: SseEvent)
}
