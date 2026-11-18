package com.yap.agentd.llm

import com.yap.agentd.ReplyGenerator
import com.yap.agentd.ReplyOutcome
import com.yap.agentd.ReplyRequest
import com.yap.agentd.SseEvent
import com.yap.agentd.Usage
import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.store.FileStore
import com.yap.agentd.tools.ServerToolCatalog
import com.yap.agentd.tools.ToolExecutor
import java.time.Clock

/**
 * 真正的回复生成器：**服务端组提示词 → 调 LLM（带工具回路）→ 流式 emit delta → 返回用量**。
 *
 * 取代 0.12 的 [com.yap.agentd.EchoReplyGenerator]（回显）作为 `Main` 的默认实现。
 * 与 `ChatService` 的分工不变：这里只负责「生成」，落库/幂等/断开继续生成都在 `ChatService`。
 *
 * ### 工具回路（0.13 新增）
 * ```
 * 第 1 轮：messages + tools[] → 模型可能同时给文字和 tool_calls
 *          → 每个 tool_call 真的执行（[ToolExecutor]），emit `tool` 事件
 *          → assistant(tool_calls) + tool(结果) 追加进 messages
 * 第 2 轮：再问一次 …… 最多 [MAX_TOOL_ROUNDS] 轮
 * ```
 * 三条刻意的边界：
 * - **最多 3 轮**：再多只会在「查了又查」上烧钱；第 3 轮之后不再给 tools，逼它用现有信息说话；
 * - **超时停止**：整个回路共享 [deadlineMs]（默认 2 分钟），到点就带着已生成的文字收尾；
 * - **`stay_silent` 立即结束**：这一轮不落 assistant 消息（由 `ChatService` 处理），
 *   也不继续问模型（「不想说话」再问一次只会得到一句敷衍）。
 *
 * ### 与客户端断开的关系
 * [ChatService] 的 `SafeSink` 会吞掉写失败；这里照常执行工具、落库。工具副作用（心情、
 * 活动、记忆、日记）**不依赖客户端连接**——这是「服务端独立运行」的一部分。
 */
class LlmReplyGenerator(
    private val store: FileStore,
    private val llm: LlmAccess,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** 是否把工具表交给模型（测试/降级时可以关掉，只做纯文字回复）。 */
    private val toolEnabled: Boolean = true,
    private val promptBuilder: ChatPromptBuilder = defaultPromptBuilder(clock.zone, toolEnabled),
    private val tools: ToolExecutor = ToolExecutor(store, clock = clock),
    /** 工具回路的硬超时（毫秒）；到点停止继续问模型，用已经拿到的文字收尾。 */
    private val deadlineMs: Long = DEFAULT_DEADLINE_MS
) : ReplyGenerator {

    override fun generate(request: ReplyRequest, emit: (SseEvent) -> Unit): ReplyOutcome {
        // 配置缺失是「可预期的失败」：抛 LlmException(NOT_CONFIGURED)，由 ChatService 回 SSE error。
        val config = llm.config()
        val persona = store.persona(request.personaId)
            ?: throw IllegalStateException("会话引用了不存在的人格：${request.personaId.take(64)}")
        val now = clock.millis()
        val systemMessages = promptBuilder.chatMessages(
            persona = persona,
            mood = store.mood(request.personaId),
            activity = ActivityState.decode(persona.activityJson),
            history = request.history,
            now = now,
            sessionMemory = request.sessionMemory,
            mentalActivity = request.mentalActivity
        )
        val messages = systemMessages.toMutableList()
        val startedAt = now
        val deadline = if (deadlineMs > 0) now + deadlineMs else Long.MAX_VALUE

        var usage = LlmUsage.ZERO
        val traces = ArrayList<ToolExecutor.ToolTrace>()
        val attachments = LinkedHashSet<String>()
        var suppressed = false
        var textSoFar = StringBuilder()
        // 0.14 连发：`send_message` 每调一次收集一条；`OutgoingMessages` 负责「每轮最多 4 条」。
        val outgoing = ToolExecutor.OutgoingMessages()
        val sentMessages = ArrayList<String>()

        var round = 0
        while (round < MAX_TOOL_ROUNDS) {
            round += 1
            if (clock.millis() > deadline) break
            // 最后一轮不再给 tools：让它只能用已经查到的信息说话，而不是无限查下去。
            val useTools = toolEnabled && round < MAX_TOOL_ROUNDS
            val turn = llm.chat(
                config = config,
                messages = messages,
                tools = if (useTools) toolsJson else null
            ) { delta ->
                // 逐段下发：客户端拿到的就是模型真实吐出的内容（不是攒完再切块）。
                textSoFar.append(delta)
                emit(SseEvent.Delta(delta))
            }
            usage = usage.plus(turn.usage)
            if (turn.toolCalls.isEmpty()) break
            messages += LlmMessage.assistant(turn.text, toolCalls = turn.toolCalls)
            var silentThisRound = false
            for (call in turn.toolCalls) {
                emit(SseEvent.Tool(call.name, "running", null))
                val outcome = tools.execute(
                    name = call.name,
                    argumentsJson = call.arguments,
                    context = ToolExecutor.Context(
                        personaId = request.personaId,
                        sessionId = request.sessionId,
                        userMessageId = request.userMessage.id,
                        outgoing = outgoing
                    )
                )
                traces += outcome.trace
                emit(SseEvent.Tool(call.name, "done", outcome.summary))
                messages += LlmMessage.tool(outcome.contentForModel, call.id)
                attachments += outcome.attachments
                outcome.sentMessage?.let { sentMessages += it }
                if (outcome.staySilent) silentThisRound = true
            }
            if (silentThisRound) {
                // 只有「一句话都没说」才算 silent：模型先说了半句又调 `stay_silent` 是自相矛盾的，
                // 这时以已经说出口的话为准（否则客户端看到了 delta、库里却没有对应消息）。
                suppressed = textSoFar.isEmpty()
                break
            }
            if (clock.millis() > deadline) break
        }

        val toolTraceJson = tools.encodeTrace(traces)
        return ReplyOutcome(
            usage = Usage(promptTokens = usage.promptTokens, completionTokens = usage.completionTokens),
            suppressed = suppressed,
            // `stay_silent` 时不带附件、不留文字；其它情况把 sticker 之类的附件带上。
            attachments = if (suppressed) emptyList() else attachments.toList(),
            toolTraceJson = toolTraceJson,
            toolsUsed = traces.map { it.name },
            elapsedMs = clock.millis() - startedAt,
            textLength = textSoFar.length,
            // 用了 `send_message` 就以逐条消息为准（已流出的文字只当「正在输入」的进度）。
            messages = if (suppressed) emptyList() else sentMessages.toList()
        )
    }

    /** tools 数组只序列化一次（每次生成都一样）。 */
    private val toolsJson: String = ServerToolCatalog.toOpenAiToolsJson()

    companion object {
        /** 关掉工具时的「你能做什么」：**诚实地说没有工具**，而不是把 tools[] 藏起来让模型瞎猜。 */
        val NO_TOOLS_CAPABILITIES: String = """
            # 你能做什么
            - 你现在没有可用的工具：不能联网搜索、不能打开链接、不能记事。
            - 对方可能发图片或文件，但你暂时看不到内容，不要假装看见了。
            - 不要假装查过，也不要编造实时信息（新闻、价格、天气）；不知道就说「不清楚」。
        """.trimIndent()

        /** 默认提示词构造器：工具开着就用注册表生成能力段，关掉就用诚实版。 */
        fun defaultPromptBuilder(zone: java.time.ZoneId, toolEnabled: Boolean): ChatPromptBuilder =
            if (toolEnabled) {
                ChatPromptBuilder(zone)
            } else {
                ChatPromptBuilder(zone, capabilities = NO_TOOLS_CAPABILITIES)
            }

        /**
         * 工具回路最多几轮。
         *
         * 3 = 「查一下 → 再确认一下 → 说话」。超过 3 轮基本都是模型在原地打转，
         * 而每一轮都是一次完整的 completion（`max_tokens` 16384），成本肉眼可见。
         */
        const val MAX_TOOL_ROUNDS: Int = 3

        /** 整个工具回路的硬超时：2 分钟（单次请求的超时仍是 5 分钟，见 `LlmClient`）。 */
        const val DEFAULT_DEADLINE_MS: Long = 2 * 60 * 1000L
    }
}
