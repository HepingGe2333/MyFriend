package com.yap.agentd.experience

import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmException
import com.yap.agentd.llm.LlmMessage
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.SessionMemory
import com.yap.app.domain.MemoryContract
import java.time.Clock

/**
 * 会话级「他的备忘录」v2：短期摘要 + 长期摘要 + 对话主题总结
 * （0.15 上下文/记忆 v2，`docs/design/context-memory-v2.md`）。
 *
 * ### 更新节奏（数字全部来自 `:agentcore` 的 [MemoryContract.Summary]）
 *
 * | 缓存 | 什么时候更新 |
 * | --- | --- |
 * | 短期（≤200 字） | 每 5 条用户消息，或模型认为「计划变化了」（[maybeUpdate] 的 `planChanged`） |
 * | 长期（≤500 字） | 15–40 条之间**由模型决定**：15 条起每次检查都问一次，模型可以答 `SKIP`；满 40 条强制更新 |
 * | 主题（每段 ≤120 字、最多 12 段） | 同上，另外「话题明显切换」（[TopicSummaryRules.shouldSplit]）时立即检查 |
 *
 * ### 为什么「检查游标」和「更新游标」分开
 * 模型说「这次不用更新」时如果推进更新游标，下一次更新就要再等 15 条；如果完全不推进，
 * 每来一条消息都要问一次模型（每条消息多一次 completion）。所以[SessionMemory] 里两套游标：
 * - `*UserMessages` = 上次**真正写入**摘要时的条数；
 * - `*CheckedUserMessages` = 上次**问过模型**时的条数（`SKIP` 时只推进它）。
 * 结果就是「15–40 条之间更新一次」，且每条消息最多问一次模型。
 *
 * ### 三条边界（与 0.14 一致）
 * 1. **失败保留旧摘要**：模型失败/返回空/超长截断为空都算失败，游标不推进，下一轮再试；
 * 2. **被拉黑的消息永远不进摘要**（它们本来就不进模型上下文）；
 * 3. 摘要是**可变区域**，由 `ChatPromptBuilder` 经 `ContextAssembler` 注入，不写进人设。
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
        val longUpdated: Boolean = false,
        val topicUpdated: Boolean = false,
        /** 长期/主题这次「问过模型，但模型说先不更新」。 */
        val longSkipped: Boolean = false,
        val topicSkipped: Boolean = false
    )

    /**
     * 每条聊天/每拍心跳都可以调；只有攒够条数（或话题切换）才会真的打模型。
     *
     * @param planChanged 模型自己觉得「刚才定下的计划变了」时传 true：短期摘要立刻更新
     *   （设计文档「短期缓存：每 5 轮或模型认为计划变化时更新」；谁来判断由调用方决定，
     *   服务端不猜模型的心思）
     */
    fun maybeUpdate(sessionId: String, now: Long = clock.millis(), planChanged: Boolean = false): Outcome {
        val session = store.session(sessionId) ?: return Outcome(null, 0)
        val current = store.countUserMessagesInSession(sessionId, includeBlocked = false)
        var updated = store.sessionMemory(sessionId)
            ?: SessionMemory(sessionId = sessionId, personaId = session.personaId)
        val original = updated
        var shortUpdated = false
        var longUpdated = false
        var topicUpdated = false
        var longSkipped = false
        var topicSkipped = false
        val personaName = store.persona(session.personaId)?.name.orEmpty()

        // ---- 短期：每 5 条，或「计划变了」立刻更新（必须有新消息才值得问模型）
        val newForShort = current - updated.shortUserMessages
        if (newForShort > 0 && (planChanged || newForShort >= SessionMemory.SHORT_EVERY)) {
            val recent = store.latestMessages(sessionId, SHORT_CONTEXT_MESSAGES, includeBlocked = false)
            val summary = summarize(
                kind = Kind.SHORT,
                personaName = personaName,
                previous = updated.shortSummary,
                messages = recent
            )
            if (summary != null && !summary.skipped) {
                updated = updated.copy(
                    shortSummary = summary.text,
                    shortUpdatedAt = now,
                    shortUserMessages = current
                )
                shortUpdated = true
            }
        }

        // ---- 长期：15 条起检查、40 条强制；模型可以答「先不更新」
        val sinceLongUpdate = current - updated.longUserMessages
        val sinceLongCheck = current - updated.longCheckedUserMessages
        // 两个游标分别管「多久没更新」和「多久没问过」：跳过之后下一次检查在 15 条之后，
        // 但距上次真正更新满 40 条时强制更新（设计文档的 15–40 区间）。
        val longCheckDue = sinceLongCheck >= LONG_EVERY_MIN || sinceLongUpdate >= LONG_EVERY_MAX
        if (longCheckDue) {
            val forced = sinceLongUpdate >= LONG_EVERY_MAX
            val earlier = store.latestMessages(sessionId, LONG_CONTEXT_MESSAGES, includeBlocked = false)
            val summary = summarize(
                kind = Kind.LONG,
                personaName = personaName,
                previous = updated.longSummary,
                messages = earlier,
                allowSkip = !forced
            )
            when {
                summary == null -> Unit // 失败：游标不推进，下一轮再试
                summary.skipped -> {
                    // 模型决定「先不更新」：只推进检查游标（下一轮检查在 15 条之后，最晚 40 条强制）。
                    updated = updated.copy(longCheckedUserMessages = current)
                    longSkipped = true
                }

                else -> {
                    updated = updated.copy(
                        longSummary = summary.text,
                        longUpdatedAt = now,
                        longUserMessages = current,
                        longCheckedUserMessages = current
                    )
                    longUpdated = true
                }
            }
        }

        // ---- 主题：15 条起检查 / 话题明显切换时立即检查 / 40 条强制
        val sinceTopicUpdate = current - updated.topicUserMessages
        val sinceTopicCheck = current - updated.topicCheckedUserMessages
        val recentMessages = store.latestMessages(sessionId, TOPIC_CONTEXT_MESSAGES, includeBlocked = false)
        val recentText = recentMessages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .joinToString("\n") { it.content }
        // 「话题明显切换」只在**已经有主题**之后才算：第一条主题要等 15 条的第一次检查
        // （否则每条新消息都会因为「还没有主题」而要求一次模型调用）。
        val topicSwitch = updated.topics.isNotEmpty() &&
            TopicSummaryRules.shouldSplit(recentText, updated.topics.lastOrNull())
        val topicCheckDue = current > updated.topicUserMessages &&
            (topicSwitch || sinceTopicCheck >= LONG_EVERY_MIN || sinceTopicUpdate >= LONG_EVERY_MAX) &&
            current != updated.topicCheckedUserMessages
        if (topicCheckDue) {
            val forced = sinceTopicUpdate >= LONG_EVERY_MAX
            val fallbackLabel = TopicSummaryRules.timeLabelOf(
                recentMessages.map { it.createdAt },
                clock.zone
            )
            val segment = summarizeTopic(
                personaName = personaName,
                previous = updated.topics,
                messages = recentMessages,
                fallbackLabel = fallbackLabel,
                allowSkip = !forced
            )
            when {
                segment == SKIPPED -> {
                    updated = updated.copy(topicCheckedUserMessages = current)
                    topicSkipped = true
                }

                segment != null -> {
                    updated = updated.copy(
                        topics = TopicSummaryRules.merge(updated.topics, segment),
                        topicUpdatedAt = now,
                        topicUserMessages = current,
                        topicCheckedUserMessages = current
                    )
                    topicUpdated = true
                }

                else -> Unit
            }
        }

        if (updated != original) store.saveSessionMemory(updated)
        return Outcome(
            memory = if (updated != original) updated else original,
            userMessages = current,
            shortUpdated = shortUpdated,
            longUpdated = longUpdated,
            topicUpdated = topicUpdated,
            longSkipped = longSkipped,
            topicSkipped = topicSkipped
        )
    }

    // ------------------------------------------------------------------ 生成

    private enum class Kind(val maxChars: Int, val maxTokens: Int) {
        SHORT(SessionMemory.MAX_SHORT_CHARS, 400),
        LONG(SessionMemory.MAX_LONG_CHARS, 700)
    }

    /** 一段摘要的结果：[skipped] = 模型明确说「这次不用更新」。 */
    private data class Summary(val text: String, val skipped: Boolean)

    /** 生成一段摘要；失败/空/坏内容返回 null（调用方保留旧摘要）。 */
    private fun summarize(
        kind: Kind,
        personaName: String,
        previous: String,
        messages: List<Message>,
        allowSkip: Boolean = false
    ): Summary? {
        val transcript = transcript(messages)
        if (transcript.isBlank()) return null
        val config = llm.configOrNull() ?: return null
        val prompt = listOf(
            LlmMessage.system(systemPrompt(kind, allowSkip)),
            LlmMessage.user(userPrompt(kind, personaName, previous, transcript, allowSkip))
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
        if (allowSkip && (TopicSummaryRules.isSkip(cleaned) || cleaned.equals(SKIP, ignoreCase = true))) {
            return Summary(cleaned, skipped = true)
        }
        // 超长不算失败，截断即可（保留开头的事实，不让一段废话把整条记忆顶掉）。
        return Summary(cleaned.take(kind.maxChars), skipped = false)
    }

    /** 生成一段「对话主题」；`SKIPPED` 表示模型说先不更新，`null` 表示失败。 */
    private fun summarizeTopic(
        personaName: String,
        previous: List<MemoryContract.TopicSegment>,
        messages: List<Message>,
        fallbackLabel: String,
        allowSkip: Boolean
    ): MemoryContract.TopicSegment? {
        val transcript = transcript(messages)
        if (transcript.isBlank()) return null
        val config = llm.configOrNull() ?: return null
        val prompt = listOf(
            LlmMessage.system(topicSystemPrompt(allowSkip)),
            LlmMessage.user(topicUserPrompt(personaName, previous, transcript, fallbackLabel, allowSkip))
        )
        val raw = try {
            llm.complete(config, prompt, temperature = TEMPERATURE, maxTokens = TOPIC_MAX_TOKENS).text
        } catch (failure: LlmException) {
            System.err.println("[agentd] 主题总结生成失败（${failure.code}），保留旧主题：${failure.userMessage()}")
            return null
        } catch (failure: Exception) {
            System.err.println("[agentd] 主题总结生成异常，保留旧主题：${failure::class.simpleName}: ${failure.message}")
            return null
        }
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return null
        if (allowSkip && TopicSummaryRules.isSkip(cleaned)) return SKIPPED
        val segment = TopicSummaryRules.parse(cleaned, fallbackLabel) ?: return null
        if (allowSkip && TopicSummaryRules.isSkip(segment.summary)) return SKIPPED
        return segment
    }

    internal fun systemPrompt(kind: String): String = when (kind) {
        "SHORT" -> systemPrompt(Kind.SHORT, allowSkip = false)
        "LONG" -> systemPrompt(Kind.LONG, allowSkip = false)
        else -> systemPrompt(Kind.SHORT, allowSkip = false)
    }

    private fun systemPrompt(kind: Kind, allowSkip: Boolean): String {
        val skipLine = if (allowSkip) {
            "\n- 如果最近这些对话里没有新的「稳定事实 / 约定 / 重要事件」，只输出 SKIP，不要为了交差而重写。"
        } else {
            ""
        }
        return when (kind) {
            Kind.SHORT -> """
                你负责把「最近几轮聊天」压成一段第一人称的备忘，给将来的自己看。
                只输出这段备忘本身：不要解释、不要 markdown、不要换行。
                要求：
                - 最多 ${SessionMemory.MAX_SHORT_CHARS} 字；
                - 只留事实、约定、对方在意的事、你们刚聊到哪、**接下来有什么计划**；
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
                - 不要写最近几轮的细节（那是短期备忘的事），不要编造。$skipLine
            """.trimIndent()
        }
    }

    private fun topicSystemPrompt(allowSkip: Boolean): String {
        val skipLine = if (allowSkip) {
            "\n- 如果最近这些对话还属于上一段主题（没有明显换话题），只输出 SKIP。"
        } else {
            ""
        }
        return """
            你负责维护一段「对话主题」的滚动摘要：每段一个时间段 + 最多 ${SessionMemory.TOPIC_MAX_CHARS} 字的概括。
            只输出一个 JSON 对象，不要解释、不要 markdown 代码块：
            {"timeLabel":"2026-11-18 晚","summary":"加班与方案改版，他答应周四前给答复"}
            要求：
            - timeLabel 用「日期 + 时段」，时段是 凌晨/早上/上午/中午/下午/晚/深夜；
            - summary 只写这一段聊了什么（谁在意什么、约定了什么），不写评价、不复述原话；
            - 和上一段是同一个话题时，概括成「仍在继续的那件事」，不要重复细节。$skipLine
        """.trimIndent()
    }

    private fun userPrompt(
        kind: Kind,
        personaName: String,
        previous: String,
        transcript: String,
        allowSkip: Boolean
    ): String {
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
            "把上面这些（含旧备忘）合并成一段 ≤${SessionMemory.MAX_LONG_CHARS} 字的第一人称长备忘，" +
                "只输出备忘本身。" + if (allowSkip) "没有值得留下的新事就只输出 SKIP。" else ""
        }
        return lines.joinToString("\n")
    }

    private fun topicUserPrompt(
        personaName: String,
        previous: List<MemoryContract.TopicSegment>,
        transcript: String,
        fallbackLabel: String,
        allowSkip: Boolean
    ): String {
        val previousText = if (previous.isEmpty()) {
            "（还没有）"
        } else {
            previous.joinToString("\n") { topic ->
                "- ${topic.timeLabel.ifBlank { "（没写时间）" }}：${topic.summary}"
            }
        }
        val lines = mutableListOf<String>()
        lines += "【最近这些对话】"
        lines += "你是：${personaName.ifBlank { "他" }}"
        lines += "现在大约：$fallbackLabel"
        lines += ""
        lines += "## 已有的主题（从旧到新）"
        lines += previousText
        lines += ""
        lines += "## 对话"
        lines += transcript
        lines += ""
        lines += "如果这段对话属于新话题，输出一个 {timeLabel,summary} 的 JSON 对象；" +
            "如果还是上一段主题，就只输出 SKIP。" + if (allowSkip) "" else "（这次必须给出时间段。）"
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

        /** 主题总结看最近多少条（主题是「最近聊了什么」，不需要整段历史）。 */
        const val TOPIC_CONTEXT_MESSAGES: Int = 30

        /** 主题总结的短输出（一个 JSON 对象，400 tokens 足够）。 */
        const val TOPIC_MAX_TOKENS: Int = 400

        const val MESSAGE_CHARS: Int = 160

        /** 模型决定「先不更新」时的固定输出。 */
        const val SKIP: String = "SKIP"

        /** 长期/主题的最小检查间隔（15 条）。 */
        val LONG_EVERY_MIN: Long get() = SessionMemory.LONG_EVERY_MIN

        /** 长期/主题的最晚强制更新间隔（40 条）。 */
        val LONG_EVERY_MAX: Long get() = SessionMemory.LONG_EVERY_MAX

        /** 模型说「先不更新」的哨兵（`summarizeTopic` 的返回值之一）。 */
        private val SKIPPED = MemoryContract.TopicSegment(timeLabel = "", summary = "")
    }
}
