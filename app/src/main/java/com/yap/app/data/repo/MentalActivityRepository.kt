package com.yap.app.data.repo

import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MentalActivityDao
import com.yap.app.data.local.MentalActivityEntity
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.domain.LlmLane
import com.yap.app.domain.MentalActivity
import com.yap.app.domain.MentalDraft
import com.yap.app.domain.MentalTrigger
import com.yap.app.domain.MoodReading
import com.yap.app.domain.ResolvedApiConfig
import com.yap.app.domain.UsageKind
import com.yap.app.network.AiProviderRegistry
import com.yap.app.network.InferenceParams
import com.yap.app.network.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 心理活动（`spec/experience/README.md` §3）。
 *
 * 活跃窗口里，每 3 分钟或每 3 轮生成一句 ≤60 字的私密念头，并把心情每维微调 ≤1。
 * 念头写入 `mental_activities`（保留 50 条），下一轮由 `ChatRepository.buildContext`
 * 以「你刚才心里想：…」注入。
 *
 * 所有失败都安静地保留旧状态：没有 API、网络失败、模型乱写、解析不出来，都不影响聊天。
 */
@Singleton
class MentalActivityRepository @Inject constructor(
    private val activityDao: MentalActivityDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val personaDao: PersonaDao,
    private val moodRepository: MoodRepository,
    private val apiConfigRepository: ApiConfigRepository,
    private val registry: AiProviderRegistry
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    /** 失败后的短冷却：避免心跳每 30 秒重打一次已经坏掉的配置。 */
    private val lastAttempt = ConcurrentHashMap<String, Long>()

    /** 聊天收尾 / 心跳里调用：不阻塞调用方。 */
    fun ensureAsync(sessionId: String, now: Long = System.currentTimeMillis(), justSent: Boolean = false) {
        if (sessionId.isBlank()) return
        scope.launch {
            runCatching { maybeGenerate(sessionId, now, justSent) }
        }
    }

    /**
     * 真正尝试一次；返回新写入的活动，没做/失败返回 null。
     *
     * @param justSent 这一轮助手刚发出消息（不必等用户再说话，活跃窗口内可以继续想）
     */
    suspend fun maybeGenerate(
        sessionId: String,
        now: Long = System.currentTimeMillis(),
        justSent: Boolean = false
    ): MentalActivityEntity? {
        if (!inFlight.add(sessionId)) return null
        try {
            val session = sessionDao.getById(sessionId) ?: return null
            val persona = personaDao.getById(session.personaId) ?: return null
            // 拉黑期间他不看对方、也不想「对方刚才说了什么」：不做心理活动。
            if (com.yap.app.domain.BlockPolicy.isBlocked(persona.blockedUntil, now)) return null
            val attemptAt = lastAttempt[persona.id]
            if (attemptAt != null && now - attemptAt < FAILURE_COOLDOWN_MS) return null

            val recent = runCatching { messageDao.recentBySession(sessionId, RECENT_ROWS) }
                .getOrDefault(emptyList())
            val latest = recent.firstOrNull()
            val previous = activityDao.latest(persona.id)
            val currentMood = runCatching { moodRepository.reading(persona.id, now) }.getOrNull()
                ?: MoodReading()
            val previousMood = previous?.toMoodReading()
            val shift = MentalActivity.moodShift(previousMood, currentMood)
            val lastAt = previous?.at ?: 0L
            val userMessagesSince = if (lastAt > 0L) {
                runCatching { messageDao.countUserMessagesSince(sessionId, lastAt) }.getOrDefault(0)
            } else {
                runCatching { messageDao.countVisibleUserMessages(sessionId) }.getOrDefault(0)
            }
            val trigger = MentalActivity.trigger(
                now = now,
                latestMessageAt = latest?.createdAt,
                lastActivityAt = lastAt,
                userMessagesSince = userMessagesSince,
                justSent = justSent,
                arousal = currentMood.arousal,
                moodShift = shift
            ) ?: return null

            lastAttempt[persona.id] = now
            val resolved = resolveConfig(persona, session.apiConfigId) ?: return null
            val draft = runCatching {
                val raw = registry.adapterFor(resolved.config.provider).complete(
                    config = resolved.config,
                    apiKey = resolved.apiKey,
                    model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: resolved.config.model,
                    messages = listOf(
                        WireMessage("system", systemPrompt(persona.name)),
                        WireMessage(
                            "user",
                            userPrompt(
                                persona = persona,
                                mood = currentMood,
                                recent = recent,
                                trigger = trigger,
                                userMessagesSince = userMessagesSince
                            )
                        )
                    ),
                    params = InferenceParams(temperature = 0.9, maxOutputTokens = 260),
                    kind = UsageKind.AGENT,
                    lane = LlmLane.BACKGROUND,
                    sessionId = sessionId,
                    personaId = persona.id
                )
                MentalActivity.parse(raw)
            }.getOrNull() ?: return null

            val nextMood = runCatching {
                moodRepository.adjustDimensions(
                    personaId = persona.id,
                    valenceDelta = draft.valenceDelta,
                    arousalDelta = draft.arousalDelta,
                    dominanceDelta = draft.dominanceDelta,
                    now = now
                )
            }.getOrNull() ?: currentMood
            val entity = MentalActivityEntity(
                id = UUID.randomUUID().toString(),
                personaId = persona.id,
                sessionId = sessionId,
                at = now,
                thought = draft.thought,
                moodLabel = nextMood.label,
                moodIntensity = nextMood.intensity,
                moodValence = nextMood.valence,
                moodArousal = nextMood.arousal,
                moodDominance = nextMood.dominance
            )
            activityDao.insert(entity)
            activityDao.trim(persona.id, KEEP)
            return entity
        } finally {
            inFlight.remove(sessionId)
        }
    }

    /** 最近一条可作为「你刚才心里想」的念头；超过活跃窗口就不注入。 */
    suspend fun promptBlock(personaId: String, now: Long = System.currentTimeMillis()): String {
        if (personaId.isBlank()) return ""
        val latest = runCatching { activityDao.latest(personaId) }.getOrNull() ?: return ""
        return MentalActivity.promptBlock(latest.thought, latest.at, now)
    }

    suspend fun latest(personaId: String): MentalActivityEntity? =
        runCatching { activityDao.latest(personaId) }.getOrNull()

    suspend fun deleteBySession(sessionId: String) = activityDao.deleteBySession(sessionId)

    suspend fun deleteByPersona(personaId: String) = activityDao.deleteByPersona(personaId)

    suspend fun deleteAll() = activityDao.deleteAll()

    private suspend fun resolveConfig(persona: PersonaEntity, sessionConfigId: String?): ResolvedApiConfig? {
        val id = sessionConfigId ?: persona.defaultApiConfigId ?: return null
        return runCatching { apiConfigRepository.resolve(id) }.getOrNull()
    }

    private fun systemPrompt(name: String): String = """
        你是「$name」，现在没有和对方说话，只是在心里想事情。
        写一句你此刻私密、具体的念头，不发给任何人。不要写「我在想」这种元叙述，也不要写长。
        只输出 JSON：{"thought":"不超过60字的念头","valence":-1到1的整数,"arousal":-1到1的整数,"dominance":-1到1的整数}
        valence/arousal/dominance 是这次念头让你心情发生的变化量，每个最多 1；没变化就写 0。
    """.trimIndent()

    private fun userPrompt(
        persona: PersonaEntity,
        mood: MoodReading,
        recent: List<MessageEntity>,
        trigger: MentalTrigger,
        userMessagesSince: Int
    ): String = buildString {
        appendLine("你此刻的心情：${mood.label}（强度 ${mood.intensity}/5，愉快 ${mood.valence}，唤醒 ${mood.arousal}/5，掌控 ${mood.dominance}/5）。")
        appendLine("触发：${trigger.name}；距上次念头以来对方说了 $userMessagesSince 条。")
        appendLine("最近几句（新的在最后）：")
        recent.takeLast(6).forEach { message ->
            val who = if (message.role == MessageRole.USER) "对方" else "你"
            val text = message.content.trim().replace('\n', ' ').take(120)
            if (text.isNotEmpty()) appendLine("$who：$text")
        }
        appendLine("想一句此刻真实的念头。可以关于对方、关于你自己、或关于你正在做的事。")
    }.trimEnd()

    private fun MentalActivityEntity.toMoodReading(): MoodReading = MoodReading(
        label = moodLabel.orEmpty(),
        intensity = moodIntensity ?: 2,
        valence = moodValence ?: 0,
        arousal = moodArousal ?: 2,
        dominance = moodDominance ?: 3,
        reason = "",
        updatedAt = at
    )

    private companion object {
        const val KEEP = 50
        const val RECENT_ROWS = 12
        const val FAILURE_COOLDOWN_MS = 60_000L
    }
}
