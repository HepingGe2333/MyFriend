package com.yap.app.data.repo

import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.domain.MessageWindows
import com.yap.app.domain.MoodJudgement
import com.yap.app.domain.MoodReading
import com.yap.app.domain.ResolvedApiConfig
import com.yap.app.domain.UsageKind
import com.yap.app.network.AiProviderRegistry
import com.yap.app.network.InferenceParams
import com.yap.app.network.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动情绪判定的执行者（`spec/mood/README.md` §2）。
 *
 * 每 4 条用户消息（或 24 小时 + 至少一条新消息）问一次模型，把结果写进多维心情。
 * 与人格进化（[PersonaEvolutionRepository]）同一套纪律：
 *
 * 1. **不拖慢聊天**：条件满足才在后台起一个协程，用户那条消息照常返回；
 * 2. **单飞**：同一时刻只跑一个判定，连发几条消息不会打一串 API；
 * 3. **游标先推进**：模型失败时这一轮就算过去了，下一轮要再攒 4 条——
 *    否则一次失败会变成「每发一条就打一次 API」的重试循环；
 * 4. **失败/无配置不报错**：按时间衰减（每小时向 0/2/3 靠一步），用户永远看不到这一步。
 *
 * 判定用的提示词与解析规则都在 [MoodJudgement]（纯函数、可单测）；这里只负责
 * 取数据、调模型、落库这三件事。
 */
@Singleton
class MoodJudgementCoordinator @Inject constructor(
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val settingsRepository: SettingsRepository,
    private val apiConfigRepository: ApiConfigRepository,
    private val registry: AiProviderRegistry,
    private val moodRepository: MoodRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同一时刻只允许一个判定在跑；失败也一定释放。 */
    private val inFlight = AtomicBoolean(false)

    /** 聊天发完一条用户消息后调用：条件满足才在后台判定一次。 */
    fun ensureAsync(personaId: String, now: Long = System.currentTimeMillis()) {
        if (personaId.isBlank()) return
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                runCatching { maybeJudge(personaId, now) }
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * 条件满足才判定：**4 条新用户消息**，或**距上次判定满 24 小时且有新消息**。
     *
     * 游标在调用模型之前推进（与进化一致）：判定失败不该变成每条消息一次 API 的重试循环，
     * 时间衰减会在下一次读心情时自然接管。
     *
     * @return 判定并落库后的心情；没到条件 / 没有会话 / 失败时返回 null
     */
    suspend fun maybeJudge(personaId: String, now: Long = System.currentTimeMillis()): MoodReading? {
        val persona = runCatching { personaDao.getById(personaId) }.getOrNull() ?: return null
        val session = runCatching { sessionDao.getByPersona(personaId) }.getOrDefault(emptyList())
            .maxByOrNull { it.lastMessageAt ?: it.createdAt }
            ?: return null
        val settings = runCatching { settingsRepository.settings.first() }.getOrNull() ?: return null
        val userMessages = runCatching { messageDao.countUserMessages(session.id) }.getOrDefault(0)
        // 换人格时游标归零：不能拿上一个人的「第 37 条」来判断这个人的第 1 条。
        val samePersona = settings.moodJudgePersonaId == personaId
        val cursor = if (samePersona) settings.moodJudgeUserMessageCount else 0
        val lastRunAt = if (samePersona) settings.moodJudgeLastRunAt else 0L
        if (!MoodJudgement.shouldJudge(userMessages, cursor, lastRunAt, now)) return null

        runCatching { settingsRepository.setMoodJudgeCursor(personaId, userMessages, now) }
        return judge(persona, sessionId = session.id, now = now)
    }

    /**
     * 真的问一次模型并落库（测试也可以直接调它；不需要满足节流条件）。
     *
     * 没配置 Key / 模型失败 / 返回不是合法 JSON 时都走 [MoodRepository.decayForFailure]：
     * 情绪随时间自然变淡，而不是停在最后一次判定结果上。
     */
    suspend fun judge(persona: PersonaEntity, sessionId: String?, now: Long): MoodReading? {
        val previous = runCatching { moodRepository.reading(persona.id, now) }.getOrNull()
            ?: return null
        val resolved = resolveConfig(persona, sessionId)
            ?: return runCatching { moodRepository.decayForFailure(persona.id, now) }.getOrNull()
        val recent = recentSummary(sessionId)
        val raw = runCatching {
            registry.adapterFor(resolved.config.provider).complete(
                config = resolved.config,
                apiKey = resolved.apiKey,
                model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: resolved.config.model,
                messages = listOf(
                    WireMessage("system", MoodJudgement.systemPrompt()),
                    WireMessage("user", MoodJudgement.userPrompt(previous, recent))
                ),
                params = InferenceParams(
                    temperature = MoodJudgement.TEMPERATURE,
                    maxOutputTokens = MoodJudgement.MAX_OUTPUT_TOKENS
                ),
                // 后台分析：与记忆整理算在同一类用量里，不冒充聊天。
                kind = UsageKind.CONSOLIDATION,
                lane = com.yap.app.domain.LlmLane.BACKGROUND,
                sessionId = sessionId,
                personaId = persona.id
            )
        }.getOrNull().orEmpty()

        val next = MoodJudgement.parse(raw, previous, now)
            ?: return runCatching { moodRepository.decayForFailure(persona.id, now) }.getOrNull()
        return runCatching { moodRepository.setReading(persona.id, next, now) }.getOrNull()
    }

    // ------------------------------------------------------------------ 取数据

    /** 最近 8 条对话摘要（新的在最后）；与新会话 / 空会话都安全。 */
    private suspend fun recentSummary(sessionId: String?): List<String> {
        if (sessionId == null) return emptyList()
        val rows = runCatching {
            messageDao.recentBySession(sessionId, MessageWindows.WORKER_RECENT_ROWS)
        }.getOrDefault(emptyList())
        return MessageWindows.lastN(rows, MessageWindows.WORKER_RECENT_ROWS)
            .filter { (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) && it.content.isNotBlank() }
            .takeLast(MoodJudgement.RECENT_MESSAGES)
            .map { MoodJudgement.recentLine(fromUser = it.role == MessageRole.USER, content = it.content) }
    }

    /**
     * 判定用哪个 API 配置：先用会话绑定的，其次人格默认的。
     *
     * 与聊天一致，避免「聊天能发、判定说没配置」这种自相矛盾。
     */
    private suspend fun resolveConfig(persona: PersonaEntity, sessionId: String?): ResolvedApiConfig? {
        val session = sessionId?.let { runCatching { sessionDao.getById(it) }.getOrNull() }
        val id = session?.apiConfigId ?: persona.defaultApiConfigId ?: return null
        return runCatching { apiConfigRepository.resolve(id) }.getOrNull()
    }
}
