package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.MessageSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 一次回复生成请求。**提示词构造不在这里**：按契约，`agentd` 不读客户端传来的提示词，
 * 生产实现（下一阶段接 LLM）会在服务端按人格/记忆/心情自己组提示词；这里只把
 * 「会话 + 用户消息 + 最近历史」交给 [ReplyGenerator]。
 */
data class ReplyRequest(
    val sessionId: String,
    val personaId: String,
    val userMessage: Message,
    /** 最近若干条消息（升序，含刚落的用户消息），供生成器参考。 */
    val history: List<Message>
)

/**
 * 可注入的回复生成器。
 *
 * - 接口保持最小：`generate(request) { event -> … }` 同步推送 [SseEvent]，返回本次用量；
 * - 生产实现下一步接 LLM（流式 delta → tool → done），本阶段只有 [EchoReplyGenerator]；
 * - 测试用假实现验证事件顺序、工具事件、客户端断开后继续生成。
 */
fun interface ReplyGenerator {
    fun generate(request: ReplyRequest, emit: (SseEvent) -> Unit): Usage
}

/**
 * 默认生成器：把用户消息回显一句 + 一句固定中文。
 *
 * 用途只有两个：`curl` 冒烟、测试流式链路。**不是**真正的回复质量，也**不调 LLM**。
 * 用量按「字符数」粗略估算（不是分词器）：中文 1 字 ≈ 1 token，注释里写清楚了。
 */
class EchoReplyGenerator(
    private val chunkSize: Int = 16,
    private val fixedLine: String = "收到啦，我在。"
) : ReplyGenerator {

    override fun generate(request: ReplyRequest, emit: (SseEvent) -> Unit): Usage {
        val echoed = request.userMessage.content.trim().let {
            if (it.length <= MAX_ECHO_CHARS) it else it.take(MAX_ECHO_CHARS) + "…"
        }
        val reply = buildString {
            append("你说：「").append(echoed).append("」\n")
            append(fixedLine)
            append("（agentd 回显回复：还没接 LLM，这一条用来验证流式与落库）")
        }
        reply.chunked(chunkSize.coerceAtLeast(1)).forEach { chunk ->
            emit(SseEvent.Delta(chunk))
        }
        var promptChars = 0
        for (message in request.history) promptChars += message.content.length
        return Usage(promptTokens = promptChars, completionTokens = reply.length)
    }

    companion object {
        /** 回显用户消息的上限：不让一条超长消息变成一条超长回复。 */
        const val MAX_ECHO_CHARS = 200
    }
}

/** `POST /v1/sessions/{id}/messages` 解析后的请求体（校验在 `AuthorityRoutes`）。 */
data class SendMessageRequest(
    val clientMessageId: String,
    val content: String,
    val attachments: List<String> = emptyList(),
    val replyTo: String? = null
) {
    companion object {
        /** `clientMessageId` 的长度上限；它同时是幂等键。 */
        const val MAX_CLIENT_MESSAGE_ID_CHARS = 128

        /** 单条消息正文上限（1 MiB 的请求体上限之外，消息本身也有上限）。 */
        const val MAX_CONTENT_CHARS = 65_536

        /** 一条消息最多带几个附件（每项是媒体 id 或 `/v1/media/<id>`，落库统一成后者）。 */
        const val MAX_ATTACHMENTS = 8
    }
}

/**
 * 聊天发送流程：幂等 → 先落用户消息 → 生成 → 落 assistant 消息 → 累加用量。
 *
 * ### 幂等
 * `clientMessageId` 是唯一键（同一会话内）：
 * - 第一次：落用户消息 → `accepted` → 生成 → 落 assistant 消息（`replyTo` 指回用户消息）→ `done`；
 * - 重复（顺序请求）：找到用户消息与已生成的回复 → 直接 `accepted` + `done`，**不再调生成器**；
 * - 并发重复：同一个 key 用一把 `ReentrantLock` 串行化，后到的那个等前一个生成完再重放（也只生成一次）；
 * - 用户消息已落但回复缺失（进程在两步之间被杀）：补生成一次（这时没有别的办法知道上次生成到哪）。
 *
 * ### 客户端断开后继续生成
 * [SafeSink] 会把 `emit` 的任何异常吞掉并记一次日志，生成循环和落库**不会**因为写 socket 失败而中断。
 * 这就是契约里「客户端断开 SSE 时服务端继续生成并落库」的实现方式；HTTP 层的 `HttpSseSink`
 * 也只是「写失败就标记 broken」，不往外抛。
 */
class ChatService(
    private val store: FileStore,
    private val generator: ReplyGenerator,
    private val historyLimit: Int = FileStore.BLOCK_SIZE
) {

    /** 同一 `(sessionId, clientMessageId)` 的并发生成只允许一个；生成完把锁从表里摘掉。 */
    private val inFlight = ConcurrentHashMap<String, ReentrantLock>()

    fun send(sessionId: String, request: SendMessageRequest, sink: SseSink) {
        val safe = SafeSink(sink)
        val key = "$sessionId|${request.clientMessageId}"
        val lock = inFlight.computeIfAbsent(key) { ReentrantLock() }
        lock.lock()
        try {
            val existing = store.findUserMessageByClientMessageId(sessionId, request.clientMessageId)
            if (existing != null) {
                val reply = store.findAssistantReply(sessionId, existing.id)
                safe.emit(SseEvent.Accepted(existing.id, existing.createdAt))
                if (reply != null) {
                    // 幂等重放：不再调生成器，也不产生第二条用户消息。
                    safe.emit(
                        SseEvent.Done(
                            reply.id,
                            Usage(reply.promptTokens ?: 0, reply.completionTokens ?: 0)
                        )
                    )
                    return
                }
                // 用户消息在、回复不在：进程在两步之间被杀过，补生成一次。
                generateAndPersist(sessionId, existing, safe)
                return
            }
            val userMessage = store.appendMessage(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = request.content,
                attachments = request.attachments,
                source = MessageSource.CHAT,
                clientMessageId = request.clientMessageId
            )
            safe.emit(SseEvent.Accepted(userMessage.id, userMessage.createdAt))
            generateAndPersist(sessionId, userMessage, safe)
        } finally {
            // 先在还持锁时判断有没有排队者，再把锁从表里摘掉：并发重复请求会等在同一个锁对象上，
            // 等到的那个看到回复已存在就走重放路径，不会第二次调生成器。
            if (!lock.hasQueuedThreads()) inFlight.remove(key, lock)
            lock.unlock()
        }
    }

    private fun generateAndPersist(sessionId: String, userMessage: Message, sink: SafeSink) {
        val session = store.session(sessionId) ?: return
        val history = store.latestMessages(sessionId, historyLimit)
        val request = ReplyRequest(
            sessionId = sessionId,
            personaId = session.personaId,
            userMessage = userMessage,
            history = history
        )
        val builder = StringBuilder()
        var usage = Usage.ZERO
        var failed = false
        try {
            usage = generator.generate(request) { event ->
                if (event is SseEvent.Delta) builder.append(event.text)
                sink.emit(event)
            }
        } catch (failure: com.yap.agentd.llm.LlmException) {
            // 0.13：LLM 失败/无配置是**可预期的错误**，必须给出明确 code（而不是笼统的
            // generation_failed），也绝不退回 Echo 假装成功。已经生成的部分仍然落库。
            failed = true
            System.err.println(
                "[agentd] 回复生成失败（${failure.code}，已生成的部分仍会落库）：${failure.userMessage()}" +
                    (failure.detail?.let { "｜detail=$it" } ?: "")
            )
            sink.emit(SseEvent.Error(failure.code, failure.userMessage()))
        } catch (failure: Exception) {
            failed = true
            System.err.println(
                "[agentd] 回复生成失败（已生成的部分仍会落库）：" +
                    "${failure::class.qualifiedName}: ${failure.message}"
            )
            sink.emit(SseEvent.Error("generation_failed", "生成回复失败，已保存已经生成的部分"))
        }
        if (failed && builder.isEmpty()) {
            // 一个字都没生成出来：不落一条空 assistant 消息，避免污染历史。
            return
        }
        // 关键点：这里不检查 socket 是否还活着 —— 客户端断开也要落库。
        val assistant = store.appendMessage(
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = builder.toString(),
            source = MessageSource.CHAT,
            replyTo = userMessage.id,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens
        )
        store.addUsage(session.personaId, usage.promptTokens, usage.completionTokens)
        if (!failed) sink.emit(SseEvent.Done(assistant.id, usage))
    }

    /** 写失败只记一次日志，绝不打断生成。 */
    private class SafeSink(private val sink: SseSink) : SseSink {
        private var failures = 0

        override fun emit(event: SseEvent) {
            try {
                sink.emit(event)
            } catch (failure: Exception) {
                failures += 1
                if (failures == 1) {
                    System.err.println(
                        "[agentd] SSE 写出失败，客户端可能已断开；继续生成并落库：" +
                            "${failure::class.qualifiedName}: ${failure.message}"
                    )
                }
            }
        }
    }
}
