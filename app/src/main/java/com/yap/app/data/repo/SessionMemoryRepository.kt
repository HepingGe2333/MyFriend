package com.yap.app.data.repo

import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.SessionMemoryDao
import com.yap.app.data.local.SessionMemoryEntity
import com.yap.app.domain.LlmLane
import com.yap.app.domain.ResolvedApiConfig
import com.yap.app.domain.SessionMemoryPolicy
import com.yap.app.domain.UsageKind
import com.yap.app.network.AiProviderRegistry
import com.yap.app.network.InferenceParams
import com.yap.app.network.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话级备忘录（`spec/experience/README.md` §6）。
 *
 * 更新是异步的、节流的、失败保留旧值：短摘要每 5 条可见用户消息更新一次，
 * 长摘要每 30 条更新一次。所有模型调用都走后台 lane，不阻塞聊天。
 */
@Singleton
class SessionMemoryRepository @Inject constructor(
    private val memoryDao: SessionMemoryDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val personaDao: PersonaDao,
    private val apiConfigRepository: ApiConfigRepository,
    private val registry: AiProviderRegistry
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 聊天收尾调用：不阻塞这一轮回复。 */
    fun ensureAsync(sessionId: String, now: Long = System.currentTimeMillis()) {
        if (sessionId.isBlank()) return
        scope.launch { runCatching { maybeUpdate(sessionId, now) } }
    }

    /**
     * 真正尝试更新一次；没有跨过 5 / 30 的门槛时直接返回。
     *
     * 两个更新各自独立：短期失败不影响长期，长期失败也不回滚短期。
     */
    suspend fun maybeUpdate(sessionId: String, now: Long = System.currentTimeMillis()): SessionMemoryEntity? {
        if (!inFlight.add(sessionId)) return null
        try {
            val session = sessionDao.getById(sessionId) ?: return null
            val persona = personaDao.getById(session.personaId) ?: return null
            if (com.yap.app.domain.BlockPolicy.isBlocked(persona.blockedUntil, now)) return null
            val count = messageDao.countVisibleUserMessages(sessionId)
            val existing = memoryDao.get(sessionId) ?: SessionMemoryEntity(
                sessionId = sessionId,
                personaId = persona.id,
                updatedAt = now
            )
            val shortTarget = SessionMemoryPolicy.nextShortCount(count, existing.shortUserCount)
            val longTarget = SessionMemoryPolicy.nextLongCount(count, existing.longUserCount)
            if (shortTarget == null && longTarget == null) return existing
            val resolved = resolveConfig(persona, session.apiConfigId) ?: return existing
            val visible = messageDao.recentBySession(sessionId, SUMMARY_WINDOW)
                .asReversed()
                .filter { (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) && it.content.isNotBlank() }
            if (visible.isEmpty()) return existing

            // 先保证行存在：updateShort/updateLong 是 UPDATE，没有行时什么都不会发生。
            if (memoryDao.get(sessionId) == null) memoryDao.upsert(existing)

            var result = memoryDao.get(sessionId) ?: existing
            if (shortTarget != null) {
                val recent = visible.takeLast(SessionMemoryPolicy.SHORT_ROUNDS * 2)
                val summary = runCatching {
                    completeSummary(
                        resolved = resolved,
                        persona = persona,
                        sessionId = sessionId,
                        system = SHORT_SYSTEM,
                        user = buildString {
                            appendLine("对方最近说的话（按时间顺序）：")
                            recent.forEach { appendLine(line(it)) }
                        }.trimEnd()
                    )
                }.getOrNull()?.let { SessionMemoryPolicy.sanitizeSummary(it, SessionMemoryPolicy.SHORT_MAX_CHARS) }
                if (summary != null) {
                    memoryDao.updateShort(sessionId, summary, shortTarget, now)
                    result = result.copy(shortSummary = summary, shortUserCount = shortTarget, shortUpdatedAt = now)
                }
            }
            if (longTarget != null) {
                val recent = visible.takeLast(SessionMemoryPolicy.SHORT_ROUNDS * 2)
                val recentIds = recent.map { it.id }.toSet()
                val earlier = visible.filterNot { it.id in recentIds }.takeLast(LONG_SOURCE_ROWS)
                if (earlier.isNotEmpty()) {
                    val summary = runCatching {
                        completeSummary(
                            resolved = resolved,
                            persona = persona,
                            sessionId = sessionId,
                            system = LONG_SYSTEM,
                            user = buildString {
                                if (!result.longSummary.isNullOrBlank()) {
                                    appendLine("你之前记得的更早的事：")
                                    appendLine(result.longSummary)
                                }
                                appendLine("更早的对话（按时间顺序）：")
                                earlier.forEach { appendLine(line(it)) }
                            }.trimEnd()
                        )
                    }.getOrNull()?.let { SessionMemoryPolicy.sanitizeSummary(it, SessionMemoryPolicy.LONG_MAX_CHARS) }
                    if (summary != null) {
                        memoryDao.updateLong(sessionId, summary, longTarget, now)
                        result = result.copy(longSummary = summary, longUserCount = longTarget, longUpdatedAt = now)
                    }
                }
            }
            return result
        } finally {
            inFlight.remove(sessionId)
        }
    }

    /** 注入系统提示词的「你的备忘录」；两段都为空时返回空串。 */
    suspend fun promptBlock(sessionId: String): String {
        if (sessionId.isBlank()) return ""
        val memory = runCatching { memoryDao.get(sessionId) }.getOrNull() ?: return ""
        return SessionMemoryPolicy.promptBlock(memory.shortSummary, memory.longSummary)
    }

    suspend fun get(sessionId: String): SessionMemoryEntity? =
        runCatching { memoryDao.get(sessionId) }.getOrNull()

    suspend fun deleteBySession(sessionId: String) = memoryDao.deleteBySession(sessionId)

    suspend fun deleteByPersona(personaId: String) = memoryDao.deleteByPersona(personaId)

    suspend fun deleteAll() = memoryDao.deleteAll()

    private suspend fun completeSummary(
        resolved: ResolvedApiConfig,
        persona: PersonaEntity,
        sessionId: String,
        system: String,
        user: String
    ): String = registry.adapterFor(resolved.config.provider).complete(
        config = resolved.config,
        apiKey = resolved.apiKey,
        model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: resolved.config.model,
        messages = listOf(WireMessage("system", system), WireMessage("user", user)),
        params = InferenceParams(temperature = 0.3, maxOutputTokens = 320),
        kind = UsageKind.HISTORY,
        lane = LlmLane.BACKGROUND,
        sessionId = sessionId,
        personaId = persona.id
    )

    private fun line(message: MessageEntity): String {
        val who = if (message.role == MessageRole.USER) "对方" else "你"
        return "$who：${message.content.trim().replace('\n', ' ').take(160)}"
    }

    private suspend fun resolveConfig(persona: PersonaEntity, sessionConfigId: String?): ResolvedApiConfig? {
        val id = sessionConfigId ?: persona.defaultApiConfigId ?: return null
        return runCatching { apiConfigRepository.resolve(id) }.getOrNull()
    }

    private companion object {
        const val SUMMARY_WINDOW = 120
        const val LONG_SOURCE_ROWS = 60
        val SHORT_SYSTEM = """
            你在帮「你自己」整理一份会话备忘录。用第一人称，只写事实与此刻的关系状态，不写建议、不写空话。
            覆盖最近 5 轮对话，压缩成不超过 200 字的一段。只输出摘要正文，不要标题、不要分点。
        """.trimIndent()
        val LONG_SYSTEM = """
            你在帮「你自己」整理一份更早的会话备忘录。用第一人称，保留对以后对话有用的事实、约定、对方的变化。
            和已有的更早备忘合并，不要重复；压缩成不超过 500 字的一段。只输出摘要正文，不要标题、不要分点。
        """.trimIndent()
    }
}
