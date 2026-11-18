package com.yap.agentd.experience

import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmException
import com.yap.agentd.llm.LlmMessage
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.SessionMemory
import java.time.Clock

/**
 * 会话级「他的备忘录」：短期摘要 + 长期摘要（`spec/experience/README.md` §6）。
 *
 * 节流不是定时器，而是**用户消息条数**：
 * - 每 5 条非拉黑用户消息更新 `shortSummary`（≤200 字，覆盖最近几轮）；
 * - 每 30 条更新 `longSummary`（≤500 字，覆盖更早的对话）。
 *
 * 三条边界：
 * 1. **失败保留旧摘要**：模型失败/返回空/超长被截断为空都视为失败，游标不推进，
 *    下一条消息还会再试（不会阻塞回复）；
 * 2. **被拉黑的消息永远不进摘要**：它们本来就不该进模型上下文；
 * 3. 摘要是**可变区域**（`ChatPromptBuilder` 里单独成段），不写进人设。
 */
class SessionMemoryService(
    private val store: FileStore,
    private val llm: LlmAccess,
    private val clock: Clock = Clock.systemDefaultZone()
) {

    /** 一次节流检查的结果（日志/测试断言用）。 */
    data class Outcome(
        val memory: SessionMemory?,
        val userMessages: Long,
        val shortUpdated: Boolean = false,
        val longUpdated: Boolean = false
    )

    /** 每条聊天/每拍心跳都可以调；只有攒够条数才会真的打模型。 */
    fun maybeUpdate(sessionId: String, now: Long = clock.millis()): Outcome {
        val session = store.session(sessionId) ?: return Outcome(null, 0)
        val current = store.countUserMessagesInSession(sessionId, includeBlocked = false)
        val existing = store.sessionMemory(sessionId)
            ?: SessionMemory(sessionId = sessionId, personaId = session.personaId)
        var updated = existing
        var shortUpdated = false
        var longUpdated = false

        if (current - existing.shortUserMessages >= SessionMemory.SHORT_EVERY) {
            val recent = store.latestMessages(sessionId, SHORT_CONTEXT_MESSAGES, includeBlocked = false)
            val summary = summarize(
                kind = Kind.SHORT,
                personaName = store.persona(session.personaId)?.name.orEmpty(),
                previous = existing.shortSummary,
                messages = recent
            )
            if (summary != null) {
                updated = updated.copy(
                    shortSummary = summary,
                    shortUpdatedAt = now,
                    shortUserMessages = current
                )
                shortUpdated = true
            }
        }

        if (current - existing.longUserMessages >= SessionMemory.LONG_EVERY) {
            val earlier = store.latestMessages(sessionId, LONG_CONTEXT_MESSAGES, includeBlocked = false)
            val summary = summarize(
                kind = Kind.LONG,
                personaName = store.persona(session.personaId)?.name.orEmpty(),
                previous = existing.longSummary,
                messages = earlier
            )
            if (summary != null) {
                updated = updated.copy(
                    longSummary = summary,
                    longUpdatedAt = now,
                    longUserMessages = current
                )
                longUpdated = true
            }
        }

        if (shortUpdated || longUpdated) store.saveSessionMemory(updated)
        return Outcome(
            memory = if (shortUpdated || longUpdated) updated else existing,
            userMessages = current,
            shortUpdated = shortUpdated,
            longUpdated = longUpdated
        )
    }

    // ------------------------------------------------------------------ 生成

    private enum class Kind(val maxChars: Int, val maxTokens: Int) {
        SHORT(SessionMemory.MAX_SHORT_CHARS, 400),
        LONG(SessionMemory.MAX_LONG_CHARS, 700)
    }

    /** 生成一段摘要；失败/空/坏内容返回 null（调用方保留旧摘要）。 */
    private fun summarize(kind: Kind, personaName: String, previous: String, messages: List<Message>): String? {
        val transcript = transcript(messages)
        if (transcript.isBlank()) return null
        val config = llm.configOrNull() ?: return null
        val prompt = listOf(
            LlmMessage.system(systemPrompt(kind)),
            LlmMessage.user(userPrompt(kind, personaName, previous, transcript))
        )
        val raw = try {
            llm.complete(config, prompt, temperature = TEMPERATURE, maxTokens = kind.maxTokens).text
        } catch (failure: LlmException) {
            System.err.println("[agentd] 会话记忆生成失败（${failure.code}），保留旧摘要：${failure.userMessage()}")
            return null
        } catch (failure: Exception) {
            System.err.println("[agentd] 会话记忆生成异常，保留旧摘要：${failure::class.simpleName}: ${failure.message}")
            return null
        }
        val cleaned = raw.trim()
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return null
        // 超长不算失败，截断即可（保留开头的事实，不让一段废话把整条记忆顶掉）。
        return cleaned.take(kind.maxChars)
    }

    internal fun systemPrompt(kind: String): String = when (kind) {
        "SHORT" -> systemPrompt(Kind.SHORT)
        "LONG" -> systemPrompt(Kind.LONG)
        else -> systemPrompt(Kind.SHORT)
    }

    private fun systemPrompt(kind: Kind): String = when (kind) {
        Kind.SHORT -> """
            你负责把「最近几轮聊天」压成一段第一人称的备忘，给将来的自己看。
            只输出这段备忘本身：不要解释、不要 markdown、不要换行。
            要求：
            - 最多 ${SessionMemory.MAX_SHORT_CHARS} 字；
            - 只留事实、约定、对方在意的事、你们刚聊到哪；
            - 不要寒暄、不要复述原话、不要编造没发生的事；
            - 写成「你提过…」「我们约好…」这样的第一人称短句。
        """.trimIndent()

        Kind.LONG -> """
            你负责把「更早之前的对话」压成一段第一人称的长备忘，给将来的自己看。
            只输出这段备忘本身：不要解释、不要 markdown、不要换行。
            要求：
            - 最多 ${SessionMemory.MAX_LONG_CHARS} 字；
            - 覆盖更早的事：对方的重要经历、长期偏好、你们的约定、关系的变化；
            - 和旧的长备忘合并（旧备忘里仍然成立的事要留下，过时/被推翻的可以丢）；
            - 不要写最近几轮的细节（那是短期备忘的事），不要编造。
        """.trimIndent()
    }

    private fun userPrompt(kind: Kind, personaName: String, previous: String, transcript: String): String {
        val lines = mutableListOf<String>()
        lines += if (kind == Kind.SHORT) "【最近几轮聊天】" else "【更早之前的聊天】"
        lines += "你是：${personaName.ifBlank { "他" }}"
        lines += ""
        lines += "## 旧的备忘"
        lines += previous.ifBlank { "（还没有）" }
        lines += ""
        lines += "## 对话"
        lines += transcript
        lines += ""
        lines += if (kind == Kind.SHORT) {
            "把上面这些压成一段 ≤${SessionMemory.MAX_SHORT_CHARS} 字的第一人称备忘，只输出备忘本身。"
        } else {
            "把上面这些（含旧备忘）合并成一段 ≤${SessionMemory.MAX_LONG_CHARS} 字的第一人称长备忘，只输出备忘本身。"
        }
        return lines.joinToString("\n")
    }

    private fun transcript(messages: List<Message>): String = messages
        .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        .filter { it.content.isNotBlank() }
        .joinToString("\n") { message ->
            val who = if (message.role == MessageRole.USER) "对方" else "他"
            "$who：${message.content.replace('\n', ' ').take(MESSAGE_CHARS)}"
        }

    companion object {
        /** 摘要生成要稳：低温度。 */
        const val TEMPERATURE: Double = 0.3

        /** 短期摘要看最近多少条消息。 */
        const val SHORT_CONTEXT_MESSAGES: Int = 12

        /** 长期摘要看多少条（足够覆盖早前的内容，又不至于把一次请求撑爆）。 */
        const val LONG_CONTEXT_MESSAGES: Int = 60

        const val MESSAGE_CHARS: Int = 160
    }
}
