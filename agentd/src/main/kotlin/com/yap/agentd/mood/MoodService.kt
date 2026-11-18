package com.yap.agentd.mood

import com.yap.agentd.llm.ChatPromptBuilder
import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmException
import com.yap.agentd.llm.LlmMessage
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.Mood
import com.yap.agentd.store.Persona
import com.yap.agentd.store.StorePersonaNotFoundException
import java.time.Clock

/**
 * 服务端情绪判定（`POST /v1/personas/{id}/mood/evaluate`，规则见 `spec/mood/README.md` §2）。
 *
 * 输入：「上一轮心情 + 最近 8 条对话」；输出：模型的 JSON（label/valence/arousal/dominance/
 * intensity/reason），再经 [MoodRules] 套范围、限制单次幅度（±2）、判断「算不算明显变化」。
 *
 * 三个关键行为：
 * 1. **没有明显变化就不写**：不改库、不发 `mood.changed`，返回现状（`changed=false`）；
 * 2. **失败/无配置按时间衰减**：每小时向 0/2/3 靠一步，**不报错**（规范 §2 的最后一条）
 *    ——端点仍然回 200，响应里的 `source` 说明是 `llm` 还是 `decay`；
 * 3. 判定结果落库时带 `reason`，并作为下一轮提示词的一部分（`ChatPromptBuilder` 读 `moods.json`）。
 *
 * 还没做（诚实记录）：规范里的「每 4 条用户消息 / 24h 自动跑一次」目前没有挂到聊天链路上，
 * 只有显式调用这个端点（以及未来定时任务）会判定。规则纯函数 [shouldEvaluate] 已经写好、有测试，
 * 接线留给下一轮——自动判定意味着每条聊天消息之后都要打一次模型，成本需要先商量。
 */
class MoodService(
    private val store: FileStore,
    private val llm: LlmAccess,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val promptBuilder: ChatPromptBuilder = ChatPromptBuilder(clock.zone)
) {

    /** 判定结果：[source] 说明这次是真的问了模型，还是失败后按时间衰减。 */
    data class Outcome(
        val mood: Mood?,
        val values: MoodRules.Values,
        val changed: Boolean,
        val source: Source,
        val proposed: MoodRules.Values? = null
    )

    enum class Source(val wireValue: String) {
        /** 模型判定并写库（或判定为「没明显变化」）。 */
        LLM("llm"),

        /** 没有配置/调用失败：按时间衰减（可能什么都没变）。 */
        DECAY("decay"),

        /** 没有配置且不需要衰减（或没有明显变化）：维持现状。 */
        UNCHANGED("unchanged")
    }

    fun evaluate(personaId: String, now: Long = clock.millis()): Outcome {
        val persona = store.persona(personaId) ?: throw StorePersonaNotFoundException(personaId)
        val stored = store.mood(personaId)
        val previous = stored?.let { MoodRules.Values.from(it) } ?: MoodRules.Values.none()
        val config = llm.configOrNull()
        if (config == null) {
            return decayOutcome(personaId, stored, previous, now)
        }
        val messages = listOf(
            LlmMessage.system(systemPrompt()),
            LlmMessage.user(userPrompt(persona, stored, previous, recentMessages(personaId), now))
        )
        val raw = try {
            llm.complete(config, messages, temperature = EVALUATION_TEMPERATURE, maxTokens = MAX_OUTPUT_TOKENS).text
        } catch (failure: LlmException) {
            // 规范 §2：失败/无配置按时间衰减，不报错。日志里留一行（不含 key）。
            System.err.println("[mood] 情绪判定失败（${failure.code}），改用时间衰减：${failure.userMessage()}")
            return decayOutcome(personaId, stored, previous, now)
        }
        val parsed = MoodRules.parse(raw) ?: return decayOutcome(personaId, stored, previous, now)
        val limited = MoodRules.limit(previous, parsed)
        if (!MoodRules.isNoticeable(previous, limited)) {
            return Outcome(stored, previous, changed = false, source = Source.LLM, proposed = limited)
        }
        val mood = store.setMood(
            personaId = personaId,
            label = limited.label,
            intensity = limited.intensity,
            valence = limited.valence,
            arousal = limited.arousal,
            dominance = limited.dominance,
            reason = limited.reason
        )
        return Outcome(mood, limited, changed = true, source = Source.LLM, proposed = limited)
    }

    /** 失败回落：按「距上次更新过了几个小时」向 0/2/3 靠；没有记录时写入基准心情。 */
    private fun decayOutcome(
        personaId: String,
        stored: Mood?,
        previous: MoodRules.Values,
        now: Long
    ): Outcome {
        if (stored == null) {
            // 从来没有心情：写一条基准，保证 `/v1/state` 与提示词都有多维字段。
            val baseline = MoodRules.Values.BASELINE.copy(intensity = 2)
            val mood = store.setMood(
                personaId = personaId,
                label = baseline.label,
                intensity = baseline.intensity,
                valence = baseline.valence,
                arousal = baseline.arousal,
                dominance = baseline.dominance,
                reason = ""
            )
            return Outcome(mood, baseline, changed = true, source = Source.DECAY)
        }
        val decayed = MoodRules.decay(previous, stored.updatedAt, now)
        if (!MoodRules.isNoticeable(previous, decayed)) {
            return Outcome(stored, previous, changed = false, source = Source.UNCHANGED)
        }
        val mood = store.setMood(
            personaId = personaId,
            label = decayed.label,
            intensity = decayed.intensity,
            valence = decayed.valence,
            arousal = decayed.arousal,
            dominance = decayed.dominance,
            reason = stored.reason
        )
        return Outcome(mood, decayed, changed = true, source = Source.DECAY)
    }

    // ------------------------------------------------------------------ 提示词

    /** 判定用 system 提示词：钉死 JSON 形状与三个维度的含义（规范 §2）。 */
    fun systemPrompt(): String = """
        你是「情绪判定器」。你会看到某个 AI 联系人上一轮的心情，以及最近 8 条对话摘要。
        判断他此刻的心情，只输出一个 JSON 对象，不要解释、不要客套、不要 markdown 代码块。

        输出格式（字段名必须完全一样，数值都是整数）：
        {"label":"有点累","valence":-1,"arousal":2,"dominance":3,"intensity":3,"reason":"连续加班，话变少了"}

        维度含义：
        - label：一个短词（中文，最多 6 字），例如 累 / 平静 / 烦 / 开心 / 雀跃 / 低落 / 想念；
        - valence：愉快度 -2..2（负=不悦，正=愉快）；
        - arousal：唤醒度 0..5（0=低能量/困，5=激动）；
        - dominance：掌控感 0..5（0=被压住，5=掌控）；
        - intensity：强度 1..5；
        - reason：一句话，最多 30 字，只给自己看。

        规则：
        - 单次变化幅度不要超过 2：一次对话不该让心情从谷底跳到顶。
        - 没有明显变化就沿用上一轮的值（不要为了输出而编造波动）。
        - 不要因为一句话就大幅波动；拿不准就给中间值。
    """.trimIndent()

    fun userPrompt(
        persona: Persona,
        stored: Mood?,
        previous: MoodRules.Values,
        recent: List<Message>,
        now: Long
    ): String {
        val lines = mutableListOf<String>()
        lines += "【情绪判定】"
        lines += "联系人：" + persona.name.ifBlank { "他" }
        promptBuilder.clockText(now)?.let { lines += "现在：$it" }
        lines += ""
        lines += "## 上一轮心情"
        lines += if (stored == null) {
            "（没有记录；按平静处理）"
        } else {
            "${previous.label}（强度 ${previous.intensity}/5），愉快度 ${previous.valence} / " +
                "唤醒度 ${previous.arousal}/5 / 掌控感 ${previous.dominance}/5。"
        }
        lines += ""
        lines += "## 最近对话"
        lines += recentSummary(recent).ifBlank { "（最近没有对话）" }
        lines += ""
        lines += """请按 system 说明的格式只输出一个 JSON 对象。"""
        return lines.joinToString("\n")
    }

    /** 最近 8 条（升序）折成「对方：… / 他：…」，每条截 120 字（与客户端进化摘要同一规则）。 */
    fun recentSummary(recent: List<Message>): String = recent
        .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        .filter { it.content.isNotBlank() }
        .takeLast(RECENT_MESSAGES)
        .joinToString("\n") { message ->
            val who = if (message.role == MessageRole.USER) "对方" else "他"
            "$who：${message.content.replace('\n', ' ').take(RECENT_MESSAGE_CHARS)}"
        }

    private fun recentMessages(personaId: String): List<Message> {
        val session = store.sessions()
            .filter { it.personaId == personaId }
            .maxByOrNull { it.lastMessageAt ?: it.createdAt } ?: return emptyList()
        return store.latestMessages(session.id, RECENT_MESSAGES * 2, includeBlocked = false)
    }

    companion object {
        /** 每多少条用户消息自动判定一次（规范 §2）；接线是下一轮的事。 */
        const val USER_MESSAGES_PER_EVAL: Int = 4

        /** 就算没到 4 条，隔多久也判定一次。 */
        const val EVAL_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

        /** 判定请求要稳：低温度、短输出。 */
        const val EVALUATION_TEMPERATURE: Double = 0.3

        const val MAX_OUTPUT_TOKENS: Int = 400

        const val RECENT_MESSAGES: Int = 8
        const val RECENT_MESSAGE_CHARS: Int = 120

        /**
         * 要不要自动判定：**攒够 4 条新用户消息**，或**距上次满 24 小时且有新消息**。
         *
         * 纯函数（与客户端 `PersonaEvolutionRepository.shouldEvaluate` 同构），
         * 目前只被测试使用；自动触发接上之后走同一份规则。
         */
        fun shouldEvaluate(userMessages: Int, cursor: Int, lastRunAt: Long, now: Long): Boolean {
            val newMessages = userMessages - cursor
            if (newMessages >= USER_MESSAGES_PER_EVAL) return true
            return newMessages > 0 && now - lastRunAt >= EVAL_INTERVAL_MS
        }
    }
}
