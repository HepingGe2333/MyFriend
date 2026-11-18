package com.yap.agentd.llm

import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.proactive.PersonaState
import com.yap.agentd.proactive.ProactiveTextSource
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Session
import java.time.Clock

/**
 * 主动消息的正文来源：**掷中之后真的调一次 LLM 写一句**（0.13 取代固定文案）。
 *
 * 之前的 `ProactiveTextSource.DEFAULT` 是「忽然想起你，想问问你现在在做什么。」——
 * 链路能跑通，但每条主动消息都一样，用户一眼就看出是假的。现在的输入是
 * 服务端已有的一切：人格（世界/话风/关系）、多维心情、此刻在做什么、最近几条对话，以及现在时间。
 *
 * ### 失败策略（没有固定文案兜底）
 *
 * 没有配置、上游报错、空回复 → 返回**空串**。引擎看到空串会把这一拍记为
 * `generation_failed`（不落库、不发事件），而不是发一条模板消息假装成功。
 * 理由：主动消息的"真实感"全部来自内容；发一条固定文案比不发更伤人设。
 *
 * ### 流式
 *
 * 内部仍用 [LlmClient.stream] 收集——与聊天同一条代码路径（同样的参数、错误分类、usage），
 * 只是没有 SSE 出口可写。生成完把首行清洗成一句（去引号/去换行/截断）。
 */
class LlmProactiveTextSource(
    private val store: FileStore,
    private val llm: LlmAccess,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val promptBuilder: ChatPromptBuilder = ChatPromptBuilder(clock.zone),
    private val maxChars: Int = MAX_CHARS
) : ProactiveTextSource {

    override fun compose(persona: PersonaState, now: Long): String {
        val config = llm.configOrNull()
        if (config == null) {
            System.err.println("[proactive] 没有可用的 LLM 配置，跳过这一条主动消息")
            return ""
        }
        val stored = store.persona(persona.personaId) ?: return ""
        val session = latestSession(persona.personaId)
        val history = session?.let { store.latestMessages(it.id, HISTORY_LIMIT) }.orEmpty()
        val messages = promptBuilder.proactiveMessages(
            persona = stored,
            mood = store.mood(persona.personaId),
            activity = ActivityState.decode(stored.activityJson),
            history = history,
            now = now
        )
        val completion = try {
            llm.complete(config, messages, maxTokens = MAX_OUTPUT_TOKENS)
        } catch (failure: LlmException) {
            // 主动消息失败不该影响心跳：记一行（脱敏），这一拍不开口。
            System.err.println("[proactive] 主动消息生成失败（${failure.code}）：${failure.userMessage()}")
            return ""
        } catch (failure: Exception) {
            System.err.println("[proactive] 主动消息生成异常：${failure::class.simpleName}")
            return ""
        }
        val text = sanitize(completion.text)
        if (text.isBlank()) {
            System.err.println("[proactive] 模型返回了空内容，这一拍不开口")
        }
        return text
    }

    /** 首行、去引号、压空白、截到 [maxChars]；全角引号也去掉（模型最爱套「」）。 */
    private fun sanitize(raw: String): String {
        val firstLine = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val stripped = firstLine
            .removePrefix("「").removeSuffix("」")
            .removePrefix("“").removeSuffix("”")
            .removePrefix("\"").removeSuffix("\"")
            .trim()
        return stripped.replace(Regex("\\s+"), " ").take(maxChars)
    }

    private fun latestSession(personaId: String): Session? =
        store.sessions()
            .filter { it.personaId == personaId }
            .maxByOrNull { it.lastMessageAt ?: it.createdAt }

    companion object {
        /** 一条主动消息的字数上限：太长的开场像客服。 */
        const val MAX_CHARS: Int = 200

        /** 主动消息只给它看最近几条，避免把整段聊天再读一遍。 */
        const val HISTORY_LIMIT: Int = 20

        /** 主动消息要短：输出上限足够写几句，限制主要靠提示词。 */
        const val MAX_OUTPUT_TOKENS: Int = 300
    }
}
