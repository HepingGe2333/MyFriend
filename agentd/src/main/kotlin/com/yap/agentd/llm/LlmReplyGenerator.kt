package com.yap.agentd.llm

import com.yap.agentd.ReplyGenerator
import com.yap.agentd.ReplyRequest
import com.yap.agentd.SseEvent
import com.yap.agentd.Usage
import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.store.FileStore
import java.time.Clock

/**
 * 真正的回复生成器：**服务端组提示词 → 调 LLM → 流式 emit delta → 返回用量**。
 *
 * 取代 0.12 的 [com.yap.agentd.EchoReplyGenerator]（回显）作为 `Main` 的默认实现。
 * 与 `ChatService` 的分工不变：这里只负责「生成」，落库/幂等/断开继续生成都在 `ChatService`。
 *
 * 三条刻意的行为：
 * 1. **每次请求都重新解析配置**：客户端可能在服务运行期间才推送/更新 API 配置
 *    （本机模式），启动时缓存一份会让「刚在客户端填好 key 却一直报错」；
 * 2. **没有配置/调用失败就抛**：`ChatService` 把 [LlmException] 翻成带明确 `code` 的
 *    SSE `error`，**绝不退回 Echo** 假装成功；
 * 3. **提示词只用服务端自己的状态**：人格、心情、活动、历史全部从 [FileStore] 读，
 *    不接受客户端传来的提示词（契约：客户端只做 UI）。
 *
 * 还没做（诚实记录）：工具回路（`web_search` / `remember` / `defer_reply` / `stay_silent`）。
 * 模型这一轮只输出文字，`# 你能做什么` 里也如实写了「没有工具」。
 */
class LlmReplyGenerator(
    private val store: FileStore,
    private val llm: LlmAccess,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val promptBuilder: ChatPromptBuilder = ChatPromptBuilder(clock.zone)
) : ReplyGenerator {

    override fun generate(request: ReplyRequest, emit: (SseEvent) -> Unit): Usage {
        // 配置缺失是「可预期的失败」：抛 LlmException(NOT_CONFIGURED)，由 ChatService 回 SSE error。
        val config = llm.config()
        val persona = store.persona(request.personaId)
            ?: throw IllegalStateException("会话引用了不存在的人格：${request.personaId.take(64)}")
        val now = clock.millis()
        val messages = promptBuilder.chatMessages(
            persona = persona,
            mood = store.mood(request.personaId),
            activity = ActivityState.decode(persona.activityJson),
            history = request.history,
            now = now
        )
        val usage = llm.stream(config, messages) { delta ->
            // 逐段下发：客户端拿到的就是模型真实吐出的内容（不是攒完再切块）。
            emit(SseEvent.Delta(delta))
        }
        return Usage(promptTokens = usage.promptTokens, completionTokens = usage.completionTokens)
    }
}
