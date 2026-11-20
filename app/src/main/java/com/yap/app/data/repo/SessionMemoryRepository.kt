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
import com.yap.app.domain.TopicSummary
import com.yap.app.domain.UsageKind
import com.yap.app.domain.MemoryContract
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
 * 会话级备忘录（`spec/experience/README.md` §6；0.15 上下文/记忆 v2）。
 *
 * 三段缓存，三种更新节奏：
 *
 * | 段 | 长度 | 更新时机 | 作用 |
 * | --- | --- | --- | --- |
 * | 短期 | ≤200 字 | 每 5 轮，或计划变化 | 最近几轮定下的计划、待办、情绪线索 |
 * | 长期 | ≤500 字 | 每 15–40 轮由模型判断 | 跨越很多轮的稳定事实、约定、重要事件 |
 * | 主题 | ≤12 段 × 120 字 | 每 15–40 轮或话题切换 | 按主题分段的滚动摘要（带时间段） |
 *
 * 三点纪律（与 0.14 一致，0.15 保留）：
 * 1. **不阻塞聊天**：所有更新都在后台 IO 协程里，失败保留旧值；
 * 2. **单飞**：同一会话同一时刻只跑一次；
 * 3. **游标只管「检查到哪」**：模型判断「这次不用更新」也算检查过，否则每发一条消息都会重问。
 *
 * 提示词里的中文只进模型上下文、不直接展示给用户（`ChineseLiteralGuardTest` 白名单已收录本文件）。
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

    /** 一次注入需要的三段（长期 → 短期 → 主题）。 */
    data class Snapshot(
        val longSummary: String = "",
        val shortSummary: String = "",
        val topics: List<MemoryContract.TopicSegment> = emptyList()
    ) {
        val isEmpty: Boolean
            get() = longSummary.isBlank() && shortSummary.isBlank() && topics.isEmpty()
    }

    /** 聊天收尾调用：不阻塞这一轮回复。 */
    fun ensureAsync(sessionId: String, now: Long = System.currentTimeMillis()) {
        if (sessionId.isBlank()) return
        scope.launch { runCatching { maybeUpdate(sessionId, now) } }
    }

    /**
     * 真正尝试更新一次；没到任何一个门槛时直接返回。
     *
     * 三段各自独立：短期失败不影响长期，主题失败保留旧主题。
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
            val recentRows = messageDao.recentBySession(sessionId, SUMMARY_WINDOW)
                .asReversed()
                .filter { (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) && it.content.isNotBlank() }
            val lastUserText = recentRows.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
            val shortTarget = if (
                SessionMemoryPolicy.shouldUpdateShort(
                    userCount = count,
                    lastUpdatedCount = existing.shortUserCount,
                    planChanged = SessionMemoryPolicy.planChanged(
                        recentRows.takeLast(4).map { it.content }
                    )
                )
            ) {
                count
            } else {
                null
            }
            val longDecision = SessionMemoryPolicy.longDecision(count, existing.longUserCount)
            val topicDue = TopicSummary.shouldUpdate(
                userCount = count,
                lastUpdatedCount = existing.topicUserCount,
                topicChanged = TopicSummary.isTopicSwitchMarker(lastUserText)
            )
            if (shortTarget == null && longDecision == SessionMemoryPolicy.LongDecision.SKIP && !topicDue) {
                return existing
            }
            val resolved = resolveConfig(persona, session.apiConfigId) ?: return existing
            if (recentRows.isEmpty()) return existing

            // 先保证行存在：updateShort/updateLong/updateTopics 是 UPDATE，没有行时什么都不会发生。
            if (memoryDao.get(sessionId) == null) memoryDao.upsert(existing)

            var result = memoryDao.get(sessionId) ?: existing
            if (shortTarget != null) {
                val recent = recentRows.takeLast(SessionMemoryPolicy.SHORT_ROUNDS * 2)
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
                } else {
                    // 失败不推进游标：下一条消息会重试（但仍在后台，不影响聊天）。
                    result
                }
            }
            if (longDecision != SessionMemoryPolicy.LongDecision.SKIP) {
                val earlier = earlierRows(recentRows)
                if (earlier.isNotEmpty()) {
                    val summary = runCatching {
                        completeSummary(
                            resolved = resolved,
                            persona = persona,
                            sessionId = sessionId,
                            system = if (longDecision == SessionMemoryPolicy.LongDecision.FORCE) {
                                LONG_SYSTEM
                            } else {
                                LONG_SYSTEM_OPTIONAL
                            },
                            user = buildString {
                                if (!result.longSummary.isNullOrBlank()) {
                                    appendLine("你之前记得的更早的事：")
                                    appendLine(result.longSummary)
                                }
                                appendLine("更早的对话（按时间顺序）：")
                                earlier.forEach { appendLine(line(it)) }
                            }.trimEnd()
                        )
                    }.getOrNull()
                    val noUpdate = summary.isNullOrBlank() || summary.contains(NO_UPDATE_MARKER)
                    if (!noUpdate) {
                        val sanitized = SessionMemoryPolicy.sanitizeSummary(
                            summary.orEmpty(),
                            SessionMemoryPolicy.LONG_MAX_CHARS
                        )
                        if (sanitized != null) {
                            memoryDao.updateLong(sessionId, sanitized, count, now)
                            result = result.copy(longSummary = sanitized, longUserCount = count, longUpdatedAt = now)
                        } else {
                            result = result.copy(longUserCount = count)
                        }
                    } else {
                        // 模型判断「没什么值得长期记的」：游标前进，15 轮后再问。
                        memoryDao.updateLong(sessionId, result.longSummary.orEmpty(), count, now)
                        result = result.copy(longUserCount = count, longUpdatedAt = now)
                    }
                }
            }
            if (topicDue) {
                val previousTopics = TopicSummary.parse(result.topicsJson.orEmpty())
                val recent = recentRows.takeLast(TOPIC_SOURCE_ROWS)
                val raw = runCatching {
                    completeSummary(
                        resolved = resolved,
                        persona = persona,
                        sessionId = sessionId,
                        system = TopicSummary.systemPrompt(),
                        user = TopicSummary.userPrompt(previousTopics, recent.map { line(it) }),
                        maxTokens = TOPIC_MAX_OUTPUT_TOKENS
                    )
                }.getOrNull()
                val parsed = raw?.let { TopicSummary.parse(it) }.orEmpty()
                if (parsed.isNotEmpty()) {
                    val encoded = TopicSummary.encode(parsed)
                    memoryDao.updateTopics(sessionId, encoded, count, now)
                    result = result.copy(
                        topicsJson = encoded,
                        topicUserCount = count,
                        topicUpdatedAt = now
                    )
                } else {
                    // 模型这次没给出可用的主题段：保留旧主题，但游标前进，避免每条消息都重试。
                    memoryDao.updateTopics(sessionId, result.topicsJson.orEmpty(), count, now)
                    result = result.copy(topicUserCount = count, topicUpdatedAt = now)
                }
            }
            return result
        } finally {
            inFlight.remove(sessionId)
        }
    }

    /**
     * 注入系统提示词的「你的备忘录」（0.15 三段，长期 → 短期 → 主题）。
     *
     * 聊天装配走 [com.yap.app.domain.ContextAssembly] 的分段注入；这里给主动思考等
     * 「一次要整块」的调用方一份现成文本。
     */
    suspend fun promptBlock(sessionId: String): String {
        val snapshot = snapshot(sessionId)
        return buildString {
            if (snapshot.longSummary.isNotBlank() || snapshot.shortSummary.isNotBlank() ||
                snapshot.topics.isNotEmpty()
            ) {
                appendLine("\n\n---")
                appendLine(MemoryContract.Injection.TITLE)
                if (snapshot.longSummary.isNotBlank()) {
                    appendLine(MemoryContract.Injection.LONG_TERM_TITLE)
                    appendLine("- ${snapshot.longSummary}")
                }
                if (snapshot.shortSummary.isNotBlank()) {
                    appendLine(MemoryContract.Injection.SHORT_TERM_TITLE)
                    appendLine("- ${snapshot.shortSummary}")
                }
                if (snapshot.topics.isNotEmpty()) {
                    appendLine(MemoryContract.Injection.TOPIC_TITLE)
                    snapshot.topics.forEach { topic ->
                        MemoryContract.Injection.topicLine(topic)?.let { appendLine(it) }
                    }
                }
            }
        }.trimEnd()
    }

    /** 三段缓存快照（供 ContextAssembly 使用；读失败返回空快照）。 */
    suspend fun snapshot(sessionId: String): Snapshot {
        if (sessionId.isBlank()) return Snapshot()
        val memory = runCatching { memoryDao.get(sessionId) }.getOrNull() ?: return Snapshot()
        return Snapshot(
            longSummary = memory.longSummary.orEmpty(),
            shortSummary = memory.shortSummary.orEmpty(),
            topics = TopicSummary.parse(memory.topicsJson.orEmpty())
        )
    }

    suspend fun get(sessionId: String): SessionMemoryEntity? =
        runCatching { memoryDao.get(sessionId) }.getOrNull()

    suspend fun deleteBySession(sessionId: String) = memoryDao.deleteBySession(sessionId)

    suspend fun deleteByPersona(personaId: String) = memoryDao.deleteByPersona(personaId)

    suspend fun deleteAll() = memoryDao.deleteAll()

    /** 更早的对话（不含短期覆盖的最近 5 轮）；最多 [LONG_SOURCE_ROWS] 行。 */
    private fun earlierRows(recentRows: List<MessageEntity>): List<MessageEntity> {
        val recent = recentRows.takeLast(SessionMemoryPolicy.SHORT_ROUNDS * 2)
        val recentIds = recent.map { it.id }.toSet()
        return recentRows.filterNot { it.id in recentIds }.takeLast(LONG_SOURCE_ROWS)
    }

    private suspend fun completeSummary(
        resolved: ResolvedApiConfig,
        persona: PersonaEntity,
        sessionId: String,
        system: String,
        user: String,
        maxTokens: Int = 320
    ): String = registry.adapterFor(resolved.config.provider).complete(
        config = resolved.config,
        apiKey = resolved.apiKey,
        model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: resolved.config.model,
        messages = listOf(WireMessage("system", system), WireMessage("user", user)),
        params = InferenceParams(temperature = 0.3, maxOutputTokens = maxTokens),
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
        const val SUMMARY_WINDOW = 160
        const val LONG_SOURCE_ROWS = 60
        const val TOPIC_SOURCE_ROWS = 60
        const val TOPIC_MAX_OUTPUT_TOKENS = 700

        /** 模型表示「这一段不用更新」时输出的标记（宽松匹配）。 */
        const val NO_UPDATE_MARKER = "NO_UPDATE"

        val SHORT_SYSTEM = """
            你在帮「你自己」整理一份会话备忘录。用第一人称，只写事实与此刻的关系状态，不写建议、不写空话。
            覆盖最近 5 轮对话，压缩成不超过 200 字的一段。只输出摘要正文，不要标题、不要分点。
        """.trimIndent()

        val LONG_SYSTEM = """
            你在帮「你自己」整理一份更早的会话备忘录。用第一人称，保留对以后对话有用的事实、约定、对方的变化。
            和已有的更早备忘合并，不要重复；压缩成不超过 500 字的一段。只输出摘要正文，不要标题、不要分点。
        """.trimIndent()

        /**
         * 15 轮起的「问模型」版本：模型可以先判断值不值得更新。
         *
         * 允许拒绝很重要：不是每 15 轮都有值得长期记的事，硬写一次摘要会把
         * 一次日常寒暄升级成「重要事件」，以后检索时会一直跳出来。
         */
        val LONG_SYSTEM_OPTIONAL = """
            你在帮「你自己」整理一份更早的会话备忘录。用第一人称，保留对以后对话有用的事实、约定、对方的变化。
            先判断：这些内容里有没有值得长期记住的事？没有就只输出 NO_UPDATE，不要硬写。
            有的话，和已有的更早备忘合并，不要重复；压缩成不超过 500 字的一段。只输出摘要正文或 NO_UPDATE。
        """.trimIndent()
    }
}
