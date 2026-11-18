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
    /** 最近若干条消息（升序，含刚落的用户消息，**已排除拉黑期间的消息**），供生成器参考。 */
    val history: List<Message>,
    /** 0.14：会话级「他的备忘录」（短期/长期摘要）；注入系统提示词的可变区域。 */
    val sessionMemory: com.yap.agentd.store.SessionMemory? = null,
    /** 0.14：最近一次心理活动（「你刚才心里想：…」）；过旧的不再注入。 */
    val mentalActivity: com.yap.agentd.store.MentalActivity? = null
)

/**
 * 一次生成的产物（`ReplyGenerator` 的返回）。
 *
 * 为什么不再只返回 [Usage]：工具回路带来了三件「生成之外」的事，必须交给 [ChatService] 处理：
 * - [suppressed]（`stay_silent`）：**不落 assistant 消息**，只标已读；
 * - [attachments]（`sticker.send`）：assistant 消息要带上的媒体引用；
 * - [toolTraceJson]：这一轮做过什么（客户端同形，落进消息表）。
 *
 * 其余字段（[toolsUsed] / [elapsedMs] / [textLength]）只用于日志与测试断言，不进协议。
 */
data class ReplyOutcome(
    val usage: Usage,
    val suppressed: Boolean = false,
    val attachments: List<String> = emptyList(),
    val toolTraceJson: String? = null,
    val toolsUsed: List<String> = emptyList(),
    val elapsedMs: Long = 0L,
    val textLength: Int = 0,
    /**
     * 0.14：这一轮真正要逐条发出的消息（`send_message` 工具收集来的，按顺序）。
     *
     * 空列表 = 生成器没有用连发，由 `ChatService` 把已生成的文字按空行拆成 1–3 条。
     */
    val messages: List<String> = emptyList()
)

/**
 * 可注入的回复生成器。
 *
 * - 接口保持最小：`generate(request) { event -> … }` 同步推送 [SseEvent]，返回 [ReplyOutcome]；
 * - 生产实现是 `llm/LlmReplyGenerator`（组提示词 → 流式 delta → 工具回路 → done）；
 * - 测试用假实现验证事件顺序、工具事件、客户端断开后继续生成。
 */
fun interface ReplyGenerator {
    fun generate(request: ReplyRequest, emit: (SseEvent) -> Unit): ReplyOutcome
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

    override fun generate(request: ReplyRequest, emit: (SseEvent) -> Unit): ReplyOutcome {
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
        return ReplyOutcome(
            usage = Usage(promptTokens = promptChars, completionTokens = reply.length),
            textLength = reply.length
        )
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
    private val historyLimit: Int = FileStore.BLOCK_SIZE,
    /**
     * 每条聊天结束后的自动动作（0.13：每 4 条用户消息判心情、每 20 条评估进化）。
     *
     * 契约要求「不依赖客户端连接」：这里在**反手回完 done 之后**同步跑一次（生产把它丢进
     * `AgentdServices` 的单线程执行器，见 `AutoTriggers`），失败只记日志，绝不影响聊天本身。
     */
    private val autoTriggers: com.yap.agentd.automation.AutoTriggers? = null,
    /**
     * 0.14 的体验功能：每条聊天后记一轮「他说了几轮」、按 5/30 条更新会话记忆、
     * 在心情激动/变化大时补一次心理活动。失败同样只记日志。
     */
    private val experience: com.yap.agentd.experience.ExperienceService? = null
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
                val replies = store.findAssistantReplies(sessionId, existing.id)
                safe.emit(SseEvent.Accepted(existing.id, existing.createdAt))
                if (replies.isNotEmpty()) {
                    // 幂等重放：不再调生成器，也不产生第二条用户消息。
                    // 0.14 连发的一轮有几条回复就逐条重放 message.created，最后用一个 done 收尾
                    // （assistantMessageId = 最后一条，usage 取自它）。
                    if (replies.size > 1) replies.forEach { safe.emit(SseEvent.MessageCreated(it)) }
                    val last = replies.last()
                    safe.emit(
                        SseEvent.Done(
                            last.id,
                            Usage(last.promptTokens ?: 0, last.completionTokens ?: 0)
                        )
                    )
                    return
                }
                // 用户消息在、回复不在：进程在两步之间被杀过，补生成一次。
                generateAndPersist(sessionId, existing, safe)
                return
            }
            val session = store.session(sessionId) ?: return
            // 0.14 拉黑：被拉黑期间用户仍可发；消息落库并永久标记 blocked，**不生成回复**。
            val blockedNow = store.persona(session.personaId)?.isBlockedAt(store.serverTime()) == true
            val userMessage = store.appendMessage(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = request.content,
                attachments = request.attachments,
                source = MessageSource.CHAT,
                clientMessageId = request.clientMessageId,
                blocked = blockedNow
            )
            safe.emit(SseEvent.Accepted(userMessage.id, userMessage.createdAt))
            if (blockedNow) {
                // 「对方已将你拉黑」：只把会话标已读，不产生 assistant 消息，也不跑任何自动判定。
                store.markSessionRead(sessionId)
                safe.emit(SseEvent.Done("", Usage.ZERO, silent = true))
                return
            }
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
        val now = store.serverTime()
        // 模型上下文**永久排除**拉黑期间的消息（解除后也不可见）：includeBlocked = false。
        val history = store.latestMessages(sessionId, historyLimit, includeBlocked = false)
        val request = ReplyRequest(
            sessionId = sessionId,
            personaId = session.personaId,
            userMessage = userMessage,
            history = history,
            sessionMemory = store.sessionMemory(sessionId),
            mentalActivity = store.latestMentalActivity(session.personaId)
                ?.takeIf { now - it.at <= MENTAL_ACTIVITY_CONTEXT_MS }
        )
        val builder = StringBuilder()
        var outcome = ReplyOutcome(Usage.ZERO)
        var failed = false
        try {
            outcome = generator.generate(request) { event ->
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
        val usage = outcome.usage
        val replyTexts = when {
            outcome.suppressed -> emptyList()
            outcome.messages.isNotEmpty() -> outcome.messages
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                // 连发是「每轮最多 4 条」，比纯文字按空行拆的 1–3 条宽一条。
                .take(com.yap.agentd.tools.ToolExecutor.MAX_MESSAGES_PER_TURN)
            else -> splitIntoMessages(builder.toString())
        }
        if (outcome.suppressed) {
            // `stay_silent`（spec/mood §4）：这一轮**没有 assistant 消息**，只把会话标已读；
            // 客户端 UI 不显示任何占位气泡，只看到「已读」。用量照记（模型确实被调用了）。
            if (usage.promptTokens > 0 || usage.completionTokens > 0) {
                store.addUsage(session.personaId, usage.promptTokens, usage.completionTokens)
            }
            store.markSessionRead(sessionId)
            sink.emit(SseEvent.Done("", usage, silent = true))
            afterChat(session.personaId, sessionId)
            return
        }
        if (failed && replyTexts.isEmpty()) {
            // 一个字都没生成出来：不落一条空 assistant 消息，避免污染历史。
            afterChat(session.personaId, sessionId)
            return
        }
        if (replyTexts.isEmpty() && outcome.attachments.isEmpty()) {
            // 工具回路把 3 轮都用完了、模型一个字都没说（只调工具）：同样不落空消息，
            // 用一条 `silent` 的 done 收尾（客户端只会看到「已读」，和 stay_silent 同一形状）。
            store.addUsage(session.personaId, usage.promptTokens, usage.completionTokens)
            store.markSessionRead(sessionId)
            sink.emit(SseEvent.Done("", usage, silent = true))
            afterChat(session.personaId, sessionId)
            return
        }
        // 有附件但没有文字（sticker.send）：仍然落一条带附件的空正文消息（与 0.13 一致）。
        val textsToPersist = if (replyTexts.isEmpty()) listOf("") else replyTexts
        val multiMessage = outcome.messages.isNotEmpty() || textsToPersist.size > 1
        // 关键点：这里不检查 socket 是否还活着 —— 客户端断开也要落库。
        val persisted = ArrayList<Message>(textsToPersist.size)
        for ((index, text) in textsToPersist.withIndex()) {
            val isLast = index == textsToPersist.lastIndex
            persisted += store.appendMessage(
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = text,
                attachments = if (isLast) outcome.attachments else emptyList(),
                source = MessageSource.CHAT,
                replyTo = userMessage.id,
                // 用量记在最后一条：`done` 与幂等重放都读它。
                promptTokens = if (isLast) usage.promptTokens else null,
                completionTokens = if (isLast) usage.completionTokens else null,
                toolTraceJson = if (isLast) outcome.toolTraceJson else null
            )
        }
        store.addUsage(session.personaId, usage.promptTokens, usage.completionTokens)
        // 真的说了一句 → 连续沉默清零（`stay_silent` 的「不超过 2 轮」由它兜底）。
        store.recordAssistantReply(session.personaId)
        if (!failed) {
            // 0.14 连发：逐条 `message.created`（带完整消息 DTO），最后只发一个 `done`。
            // 单条回复保持旧形状（只有 delta + done），老客户端不用改也能正常显示。
            if (multiMessage) persisted.forEach { sink.emit(SseEvent.MessageCreated(it)) }
            sink.emit(SseEvent.Done(persisted.last().id, usage))
        }
        afterChat(session.personaId, sessionId)
    }

    /**
     * 不走工具的回复按**空行**拆成 1–3 条短消息（`spec/experience/README.md` §4）。
     *
     * 只有一个段落就原样一条；超过 3 段时把多出来的并进最后一条——宁可最后一条长一点，
     * 也不要丢掉内容。
     */
    internal fun splitIntoMessages(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parts = trimmed.split(EMPTY_LINE).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) return listOf(trimmed)
        if (parts.size <= MAX_REPLY_MESSAGES) return parts
        return parts.take(MAX_REPLY_MESSAGES - 1) +
            parts.drop(MAX_REPLY_MESSAGES - 1).joinToString("\n\n")
    }

    /**
     * 聊天结束后的自动动作（0.13 / 0.14）。**在任何情况下都不抛**：
     * 情绪判定失败要按时间衰减、进化评估失败只丢一条日志，聊天本身已经成功回完了。
     */
    private fun afterChat(personaId: String, sessionId: String) {
        val triggers = autoTriggers
        if (triggers != null) {
            runCatching { triggers.onChatCompleted(personaId) }
                .onFailure { failure ->
                    System.err.println("[agentd] 自动判定触发失败：${failure::class.simpleName}: ${failure.message}")
                }
        }
        val experienceService = experience ?: return
        runCatching { experienceService.onChatCompleted(personaId, sessionId) }
            .onFailure { failure ->
                System.err.println("[agentd] 体验功能触发失败：${failure::class.simpleName}: ${failure.message}")
            }
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

    companion object {
        /** 一轮最多拆成几条（规范 §4：1–3 条）。 */
        const val MAX_REPLY_MESSAGES = 3

        /** 心理活动注入下一轮的时效：太久以前的念头不再提（半小时）。 */
        const val MENTAL_ACTIVITY_CONTEXT_MS: Long = 30 * 60 * 1000L

        /** 空行（一个或多个，允许行内空白）。 */
        private val EMPTY_LINE = Regex("\\n[ \\t]*\\n")
    }
}
