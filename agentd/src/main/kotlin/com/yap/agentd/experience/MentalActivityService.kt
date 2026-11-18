package com.yap.agentd.experience

import com.yap.agentd.llm.ChatPromptBuilder
import com.yap.agentd.llm.LlmAccess
import com.yap.agentd.llm.LlmException
import com.yap.agentd.llm.LlmMessage
import com.yap.agentd.mood.MoodRules
import com.yap.agentd.store.AutoState
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MentalActivity
import com.yap.agentd.store.Message
import com.yap.agentd.store.MessageRole
import com.yap.agentd.store.Session
import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue
import java.time.Clock
import java.util.UUID

/**
 * 心理活动：**他私下的一个念头** + 据此微调心情（`spec/experience/README.md` §3）。
 *
 * 三条边界：
 * 1. 念头**绝不发给用户**：只写 `mental_activities.json`，下一轮以「你刚才心里想：…」注入提示词；
 * 2. 心情微调每一维最多 1 格（[MentalActivityRules.MAX_MOOD_STEP]）：念头是「心里动了一下」，
 *    不是一次完整的情绪判定，不该让心情跳两格；
 * 3. 失败/没配置就什么都不写：心理活动是锦上添花，不能影响聊天（也不会推进游标，
 *    下一拍还会再试）。
 */
class MentalActivityService(
    private val store: FileStore,
    private val llm: LlmAccess,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val promptBuilder: ChatPromptBuilder = ChatPromptBuilder(clock.zone)
) {

    /**
     * 按规则决定要不要做一次；不满足条件返回 `null`。
     *
     * @param sessionId 发生心理活动的会话（用于快照里的「会话」）
     * @param force 聊天刚结束时的强制检查（仍然受活跃窗口与规则约束）
     */
    fun maybeRun(
        personaId: String,
        sessionId: String,
        now: Long = clock.millis(),
        force: Boolean = false
    ): MentalActivity? {
        val session = store.session(sessionId) ?: return null
        val previous = store.latestMentalActivity(personaId)
        val mood = store.mood(personaId)
        val state = store.autoState(personaId)
        val mandatory = force && MentalActivityRules.isMandatory(mood, previous)
        val shouldRun = MentalActivityRules.shouldRun(
            now = now,
            lastMessageAt = session.lastMessageAt,
            lastMentalAt = state.mentalEvaluatedAt,
            assistantReplies = state.assistantReplies,
            mentalCursor = state.mentalCursor,
            mandatory = mandatory
        )
        if (!shouldRun) return null
        return runNow(personaId, session, mood, previous, now)
    }

    /** 立刻做一次（测试/调试）；失败返回 null 且不写任何东西。 */
    fun runNow(
        personaId: String,
        session: Session,
        mood: com.yap.agentd.store.Mood?,
        previous: MentalActivity?,
        now: Long
    ): MentalActivity? {
        val config = llm.configOrNull() ?: return null
        val persona = store.persona(personaId) ?: return null
        val recent = store.latestMessages(session.id, RECENT_MESSAGES * 2, includeBlocked = false)
        val messages = listOf(
            LlmMessage.system(systemPrompt()),
            LlmMessage.user(userPrompt(persona.name, mood, previous, recent, now))
        )
        val raw = try {
            llm.complete(config, messages, temperature = TEMPERATURE, maxTokens = MAX_OUTPUT_TOKENS).text
        } catch (failure: LlmException) {
            // 后台路径：失败只记一行（不含 key），不推进游标，下一拍再试。
            System.err.println("[agentd] 心理活动生成失败（${failure.code}）：${failure.userMessage()}")
            return null
        } catch (failure: Exception) {
            System.err.println("[agentd] 心理活动生成异常：${failure::class.simpleName}: ${failure.message}")
            return null
        }
        val thought = parseThought(raw)?.takeIf { it.isNotBlank() } ?: return null
        if (thought.length > MentalActivity.MAX_THOUGHT_CHARS) {
            System.err.println("[agentd] 心理活动念头超长（${thought.length} 字），丢弃这次")
            return null
        }
        // 模型可以只给念头、不给心情（那就是「心里动了一下，但心情没变」）。
        val proposed = if (hasMoodFields(raw)) MoodRules.parse(raw) else null
        val adjusted = MentalActivityRules.adjust(mood, proposed)
        val changed = mood == null || MoodRules.isNoticeable(MoodRules.Values.from(mood), adjusted)
        val currentMood = if (changed) {
            store.setMood(
                personaId = personaId,
                label = adjusted.label,
                intensity = adjusted.intensity,
                valence = adjusted.valence,
                arousal = adjusted.arousal,
                dominance = adjusted.dominance,
                reason = thought.take(200)
            )
        } else {
            mood
        }
        val activity = store.saveMentalActivity(
            MentalActivity(
                id = UUID.randomUUID().toString(),
                personaId = personaId,
                sessionId = session.id,
                at = now,
                thought = thought,
                moodLabel = currentMood?.label.orEmpty(),
                moodValence = currentMood?.valence ?: adjusted.valence,
                moodArousal = currentMood?.arousal ?: adjusted.arousal,
                moodDominance = currentMood?.dominance ?: adjusted.dominance,
                moodIntensity = currentMood?.intensity ?: adjusted.intensity
            )
        )
        store.recordMentalActivity(personaId, now)
        return activity
    }

    // ------------------------------------------------------------------ 提示词

    /** 内心独白生成器的 system 提示词：钉死 JSON 形状、字数与「不发给对方」。 */
    fun systemPrompt(): String = """
        你负责生成某个人**私下的一个念头**（内心独白）。这不是回复，不会发给对方。
        只输出一个 JSON 对象，不要解释、不要 markdown 代码块、不要引号包裹整段。

        输出格式（字段名必须完全一样）：
        {"thought":"刚看到那句话，心里有点软","label":"有点想他","valence":1,"arousal":2,"dominance":3,"intensity":3}

        规则：
        - thought：一句第一人称的私密念头，最多 60 字；像自己心里掠过的一句话，不要复述对方原话，不要写成抒情散文。
        - 心情四维（label/valence/arousal/dominance/intensity）：**相对现在最多各变 1 格**；没有变化就照抄现在的数值。
        - 不要编造事实（没发生的事、没见过的人）；不要提「心理活动」「系统」这类词。
    """.trimIndent()

    /** 用户消息：现在的心情 + 最近几轮对话。 */
    fun userPrompt(
        name: String,
        mood: com.yap.agentd.store.Mood?,
        previous: MentalActivity?,
        recent: List<Message>,
        now: Long
    ): String {
        val lines = mutableListOf<String>()
        lines += "【现在】"
        lines += "你是：${name.ifBlank { "他" }}"
        promptBuilder.clockText(now)?.let { lines += "时间：$it" }
        lines += "现在的心情：${mood?.label ?: "（没有记录，按平静处理）"}" +
            (mood?.let { "（强度 ${it.intensity}/5，愉快度 ${it.valence} / 唤醒度 ${it.arousal}/5 / 掌控感 ${it.dominance}/5）" } ?: "")
        previous?.let { lines += "上一次你心里想的是：「${it.thought}」" }
        lines += ""
        lines += "## 最近几轮"
        lines += recentSummary(recent).ifBlank { "（最近没有说话）" }
        lines += ""
        lines += "只输出一个 JSON 对象。"
        return lines.joinToString("\n")
    }

    private fun recentSummary(recent: List<Message>): String = recent
        .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        .filter { it.content.isNotBlank() }
        .takeLast(RECENT_MESSAGES)
        .joinToString("\n") { message ->
            val who = if (message.role == MessageRole.USER) "对方" else "他"
            "$who：${message.content.replace('\n', ' ').take(RECENT_MESSAGE_CHARS)}"
        }

    // ------------------------------------------------------------------ 解析

    /** 宽松取 `thought`：容忍代码块/前后解释；取不到返回 null。 */
    internal fun parseThought(raw: String): String? {
        val obj = parseObject(raw) ?: return null
        return (obj.fields["thought"] as? JsonValue.Str)?.value?.trim()?.replace('\n', ' ')
    }

    private fun hasMoodFields(raw: String): Boolean {
        val obj = parseObject(raw) ?: return false
        return listOf("label", "valence", "arousal", "dominance", "intensity").any { obj.fields.containsKey(it) }
    }

    private fun parseObject(raw: String): JsonValue.Obj? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val candidates = listOfNotNull(
            text,
            if (start >= 0 && end > start) text.substring(start, end + 1) else null
        ).distinct()
        for (candidate in candidates) {
            val parsed = runCatching { Json.parse(candidate) }.getOrNull()
            if (parsed is JsonValue.Obj) return parsed
        }
        return null
    }

    companion object {
        /** 心理活动生成要稳：低温度、短输出。 */
        const val TEMPERATURE: Double = 0.4

        const val MAX_OUTPUT_TOKENS: Int = 300

        /** 提示词里带最近几轮对话。 */
        const val RECENT_MESSAGES: Int = 8

        const val RECENT_MESSAGE_CHARS: Int = 120

        /** 只在活跃窗口里做（与 [MentalActivityRules] 一致；便于测试/文档引用）。 */
        val activeWindowMs: Long get() = MentalActivityRules.ACTIVE_WINDOW_MS

        /** 供 `ExperienceService` 判断「这个会话刚落过消息」。 */
        fun autoStateOf(store: FileStore, personaId: String): AutoState = store.autoState(personaId)
    }
}
