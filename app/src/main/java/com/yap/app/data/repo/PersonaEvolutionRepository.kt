package com.yap.app.data.repo

import androidx.room.withTransaction
import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPendingPatchDao
import com.yap.app.data.local.PersonaPendingPatchEntity
import com.yap.app.data.local.PersonaRevisionEntity
import com.yap.app.data.local.PersonaRevisionDao
import com.yap.app.data.local.YapDatabase
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.domain.DomainErrorCode
import com.yap.app.domain.PersonaEvolution
import com.yap.app.domain.PersonaProfile
import com.yap.app.domain.PersonaRevisionReason
import com.yap.app.domain.UsageKind
import com.yap.app.network.AiProviderRegistry
import com.yap.app.network.InferenceParams
import com.yap.app.network.WireMessage
import com.yap.app.network.userError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 人格进化：每 20 条用户消息或 24 小时回顾一次，产出**待审**的变化
 * （`docs/design/persona-evolution.md` §2）。
 *
 * 三条刻意的边界：
 *
 * 1. **不直接生效**：模型给的变化先落进既有的 `persona_pending_patches`，
 *    用户在人设页「他最近的变化」里接受或拒绝。人格是用户的东西，不能让后台悄悄改。
 * 2. **只碰四个字段**：`traits / likes / behaviors / quirks`（[PersonaEvolution.Field]）。
 *    名字、描述、提示词、世界、关系一律不碰——那些是用户写的。
 * 3. **失败不影响聊天**：评估是一次用完即弃的 completion，没有会话、没有工具；
 *    没有 API 配置、模型出错、解析失败都安静返回，绝不让用户发消息失败。
 *
 * 与 `EvolutionRepository` 的分工：那个仓库是「用户主动点『让他进化』时重写整段提示词」，
 * 这个仓库是「后台按节奏长出小变化」。两者都写版本历史，但入口与粒度不同。
 */
@Singleton
class PersonaEvolutionRepository @Inject constructor(
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val pendingPatchDao: PersonaPendingPatchDao,
    private val revisionDao: PersonaRevisionDao,
    private val apiConfigRepository: ApiConfigRepository,
    private val registry: AiProviderRegistry,
    private val settingsRepository: SettingsRepository,
    private val database: YapDatabase
) {

    companion object {
        /** 每多少条用户消息评估一次。 */
        const val USER_MESSAGES_PER_EVAL: Int = 20

        /** 就算没到 20 条，隔多久也回顾一次。 */
        const val EVAL_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

        /** 待审补丁的有效期：7 天后自动丢弃（模型看到的「当时」已经不是现在了）。 */
        const val PENDING_TTL_MS: Long = 7 * 24 * 60 * 60 * 1000L

        /** 评估请求的输出上限与温度：要稳、要短，不要发挥。 */
        private const val MAX_OUTPUT_TOKENS = 900
        private const val TEMPERATURE = 0.3

        /** 给模型看的「最近对话摘要」最多多少条、每条多长。 */
        private const val RECENT_MESSAGES = 12
        private const val RECENT_MESSAGE_CHARS = 120

        /**
         * 这一轮要不要评估：**攒够 20 条新用户消息**，或**距上次满 24 小时且有新消息**。
         *
         * 纯函数、单独拿出来只为可测：触发条件是「每 20 条或每 24 小时」这件事的唯一实现，
         * 写在仓库内部就只能靠读代码确认（`PersonaEvolutionFlowTest` 逐条钉住边界）。
         */
        fun shouldEvaluate(userMessages: Int, cursor: Int, lastRunAt: Long, now: Long): Boolean {
            val newMessages = userMessages - cursor
            if (newMessages >= USER_MESSAGES_PER_EVAL) return true
            return newMessages > 0 && now - lastRunAt >= EVAL_INTERVAL_MS
        }

        /**
         * 7 天过期的待审补丁 id。
         *
         * 只挑「进化补丁」：旧的人设卡补丁有它自己的处理路径，不该被这里顺带删掉。
         */
        fun expiredPatchIds(patches: List<PersonaPendingPatchEntity>, now: Long): List<String> =
            patches.filter {
                PersonaEvolution.isEvolutionPatch(it.patchJson) && now - it.createdAt > PENDING_TTL_MS
            }.map { it.id }

        /**
         * 把变化应用到人格实体（纯函数）。
         *
         * @return 变化后的人格；变化为空或与现状完全一致时返回 `null`（调用方据此不升版本）
         */
        fun applyTo(
            persona: PersonaEntity,
            changes: List<PersonaEvolution.Change>,
            now: Long
        ): PersonaEntity? {
            val before = PersonaEvolution.Current(
                traits = PersonaProfile.decodeList(persona.traits),
                likes = PersonaProfile.decodeList(persona.likes),
                behaviors = PersonaProfile.decodeList(persona.behaviors),
                quirks = persona.quirks.orEmpty()
            )
            val after = PersonaEvolution.apply(before, changes)
            if (after == before) return null
            return persona.copy(
                traits = PersonaProfile.encodeList(after.traits),
                likes = PersonaProfile.encodeList(after.likes),
                behaviors = PersonaProfile.encodeList(after.behaviors),
                quirks = after.quirks.trim().takeIf { it.isNotBlank() },
                version = persona.version + 1,
                updatedAt = now
            )
        }
    }

    /** 评估结果：这一轮读了几条变化、写没写进待审。 */
    data class Evaluation(
        val proposed: Int,
        val written: Boolean,
        val summary: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)
    private val acceptLock = Mutex()

    // ---------------------------------------------------------------- 触发

    /**
     * 发完一条用户消息后调它：条件到了就异步评估一次，**不等**（聊天不能被它拖慢）。
     *
     * 同一时刻只允许一轮评估：`inFlight` 挡住并发，否则连发两条消息会打出两次模型请求。
     */
    fun ensureAsync(personaId: String, now: Long = System.currentTimeMillis()) {
        if (personaId.isBlank()) return
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                runCatching { maybeEvaluate(personaId, now) }
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * 条件满足才评估：**20 条新用户消息**，或**距上次评估满 24 小时且有新消息**。
     *
     * 游标（上次评估到第几条）与时间存在 DataStore 里，是内部状态、界面上看不到：
     * 重启后接着算，而不是每次冷启动都「刚满 24 小时」。
     *
     * 游标在**调用模型之前**推进：模型失败时这一轮就算过去了，下一轮要再攒 20 条。
     * 这是有意的——进化评估失败不该变成每发一条消息就打一次 API 的重试循环。
     */
    suspend fun maybeEvaluate(personaId: String, now: Long = System.currentTimeMillis()): Evaluation? {
        val persona = runCatching { personaDao.getById(personaId) }.getOrNull() ?: return null
        // 7 天没被处理的提议先丢：模型看到的是当时的对话，过期了就别再挂着烦人。
        runCatching { dropExpired(personaId, now) }

        val settings = runCatching { settingsRepository.settings.first() }.getOrNull() ?: return null
        val session = runCatching { sessionDao.getByPersona(personaId) }.getOrDefault(emptyList())
            .firstOrNull() ?: return null
        val userMessages = runCatching { messageDao.countUserMessages(session.id) }.getOrDefault(0)
        val samePersona = settings.evolutionPersonaId == personaId
        val cursor = if (samePersona) settings.evolutionUserMessageCount else 0
        val lastRunAt = if (samePersona) settings.evolutionLastRunAt else 0L

        if (!shouldEvaluate(userMessages, cursor, lastRunAt, now)) return null

        runCatching { settingsRepository.setEvolutionCursor(personaId, userMessages, now) }
        return evaluate(persona, sessionId = session.id, now = now)
    }

    /** 真的问一次模型并把结果写进待审表（测试里也可以直接调它）。 */
    suspend fun evaluate(persona: PersonaEntity, sessionId: String?, now: Long): Evaluation {
        val configId = persona.defaultApiConfigId ?: sessionId?.let { id ->
            runCatching { sessionDao.getById(id)?.apiConfigId }.getOrNull()
        } ?: return Evaluation(proposed = 0, written = false)
        val resolved = runCatching { apiConfigRepository.resolve(configId) }.getOrNull()
            ?: return Evaluation(proposed = 0, written = false)
        val current = currentOf(persona)
        val recent = recentSummary(sessionId, now)
        val raw = runCatching {
            registry.adapterFor(resolved.config.provider).complete(
                config = resolved.config,
                apiKey = resolved.apiKey,
                model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: resolved.config.model,
                messages = listOf(
                    WireMessage("system", PersonaEvolution.systemPrompt()),
                    WireMessage("user", PersonaEvolution.userPrompt(persona.name, current, recent))
                ),
                params = InferenceParams(temperature = TEMPERATURE, maxOutputTokens = MAX_OUTPUT_TOKENS),
                kind = UsageKind.CONSOLIDATION,
                personaId = persona.id
            )
        }.getOrNull() ?: return Evaluation(proposed = 0, written = false)

        val changes = PersonaEvolution.sanitize(PersonaEvolution.parse(raw), current)
        if (changes.isEmpty()) return Evaluation(proposed = 0, written = false)
        val patchJson = PersonaEvolution.encodePatch(changes)
        val summary = PersonaEvolution.summary(changes)
        // 同一人格已有相似提议时不重复堆（addPendingPatch 按 summary 去重）。
        val id = runCatching {
            pendingEvolutionPatch(persona.id, sessionId, patchJson, summary, now)
        }.getOrNull() ?: return Evaluation(proposed = changes.size, written = false)
        return Evaluation(
            proposed = changes.size,
            written = id.isNotBlank(),
            summary = summary
        )
    }

    /**
     * 写一条进化补丁。
     *
     * 不用 `PersonaCardRepository.addPendingPatch`：那个方法按 summary 去重、语义是「他自己改人设」，
     * 而这里要保证**同一条 patchJson 不重复**（同样的变化在两次评估里出现两次时只留一份）。
     */
    private suspend fun pendingEvolutionPatch(
        personaId: String,
        sessionId: String?,
        patchJson: String,
        summary: String,
        now: Long
    ): String {
        val existing = pendingPatchDao.byPersona(personaId)
            .firstOrNull { PersonaEvolution.isEvolutionPatch(it.patchJson) && it.patchJson == patchJson }
        if (existing != null) return existing.id
        val id = UUID.randomUUID().toString()
        pendingPatchDao.insert(
            PersonaPendingPatchEntity(
                id = id,
                personaId = personaId,
                sessionId = sessionId,
                patchJson = patchJson,
                summary = summary.take(120),
                reason = "这几轮聊下来，他觉得自己有点变化（需要你点头才会写进人设）。",
                createdAt = now
            )
        )
        return id
    }

    // ---------------------------------------------------------------- 待审

    /** 人设页「他最近的变化」：只列进化的补丁（老的人设卡补丁有别的入口）。 */
    fun observePending(personaId: String): Flow<List<PersonaPendingPatchEntity>> =
        pendingPatchDao.observeByPersona(personaId).map { patches ->
            patches.filter { PersonaEvolution.isEvolutionPatch(it.patchJson) }
        }

    suspend fun pending(personaId: String): List<PersonaPendingPatchEntity> =
        runCatching { pendingPatchDao.byPersona(personaId) }.getOrDefault(emptyList())
            .filter { PersonaEvolution.isEvolutionPatch(it.patchJson) }

    /** 一条补丁给用户看的几行：`+嘴硬心软（原因）`。 */
    fun describe(patch: PersonaPendingPatchEntity): List<PersonaEvolution.Change> =
        PersonaEvolution.decodePatch(patch.patchJson)

    /**
     * 接受一条变化：写回人格的四个字段并 `version + 1`，同时记一条版本历史。
     *
     * 只改 `traits/likes/behaviors/quirks` 四个列——名字、描述、提示词、世界、关系一律不碰。
     * 没有真正变化时（去重后为空）不升版本，但仍然把补丁删掉（它已经被处理过了）。
     */
    suspend fun accept(patchId: String, now: Long = System.currentTimeMillis()): Result<PersonaEntity> =
        acceptLock.withLock {
            runCatching {
                val patch = pendingPatchDao.getById(patchId)
                    ?: userError(DomainErrorCode.PENDING_PATCH_NOT_FOUND)
                val persona = personaDao.getById(patch.personaId)
                    ?: userError(DomainErrorCode.PERSONA_NOT_FOUND)
                val changes = PersonaEvolution.decodePatch(patch.patchJson)
                if (!PersonaEvolution.isEvolutionPatch(patch.patchJson) || changes.isEmpty()) {
                    userError(DomainErrorCode.MODEL_UNPARSABLE)
                }
                val updated = applyTo(persona, changes, now)
                if (updated == null) {
                    // 变化已经被别处写进去了（或与现有值完全重复）：只清掉这条待审，不升版本。
                    pendingPatchDao.deleteById(patchId)
                    return@runCatching persona
                }
                database.withTransaction {
                    revisionDao.insert(
                        PersonaRevisionEntity(
                            id = UUID.randomUUID().toString(),
                            personaId = persona.id,
                            version = persona.version,
                            name = persona.name,
                            description = persona.description,
                            avatarUri = persona.avatarUri,
                            avatarSeed = persona.avatarSeed,
                            prompt = persona.prompt,
                            category = persona.category,
                            reason = PersonaRevisionReason.BEFORE_EVOLUTION,
                            createdAt = now
                        )
                    )
                    personaDao.update(updated)
                    revisionDao.insert(
                        PersonaRevisionEntity(
                            id = UUID.randomUUID().toString(),
                            personaId = updated.id,
                            version = updated.version,
                            name = updated.name,
                            description = updated.description,
                            avatarUri = updated.avatarUri,
                            avatarSeed = updated.avatarSeed,
                            prompt = updated.prompt,
                            category = updated.category,
                            reason = PersonaRevisionReason.evolved(PersonaEvolution.summary(changes)),
                            createdAt = now
                        )
                    )
                    pendingPatchDao.deleteById(patchId)
                }
                updated
            }
        }

    /** 拒绝一条变化：只丢掉提议，人设一动不动。 */
    suspend fun reject(patchId: String) {
        runCatching { pendingPatchDao.deleteById(patchId) }
    }

    /**
     * 7 天过期：把超过有效期的待审变化删掉，返回删掉几条。
     *
     * 过期不是「用户拒绝了」，所以不留任何痕迹——模型看到的那段对话已经过去一周，
     * 再挂着只会变成一排永远没人点的按钮。
     */
    suspend fun dropExpired(personaId: String, now: Long = System.currentTimeMillis()): Int {
        val expired = expiredPatchIds(
            runCatching { pendingPatchDao.byPersona(personaId) }.getOrDefault(emptyList()),
            now
        )
        expired.forEach { runCatching { pendingPatchDao.deleteById(it) } }
        return expired.size
    }

    // ---------------------------------------------------------------- 映射

    /** 人格实体 → 进化评估看到的四组值。 */
    fun currentOf(persona: PersonaEntity): PersonaEvolution.Current = PersonaEvolution.Current(
        traits = PersonaProfile.decodeList(persona.traits),
        likes = PersonaProfile.decodeList(persona.likes),
        behaviors = PersonaProfile.decodeList(persona.behaviors),
        quirks = persona.quirks.orEmpty()
    )

    /**
     * 「最近对话摘要」：只给最近 12 条、每条截到 120 字。
     *
     * 规范要求输入是「最近摘要」而不是原始长文本：评估关心的是相处方式的变化，
     * 不是把整段聊天再读一遍（那既慢又容易把一次争吵放大成人格）。
     */
    private suspend fun recentSummary(sessionId: String?, now: Long): String {
        val id = sessionId ?: return ""
        val rows = runCatching { messageDao.recentBySession(id, RECENT_MESSAGES * 2) }.getOrDefault(emptyList())
        val lines = rows.asReversed()
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .filter { it.content.isNotBlank() }
            .takeLast(RECENT_MESSAGES)
            .map { message ->
                val who = if (message.role == MessageRole.USER) "对方" else "他"
                "$who：${message.content.replace('\n', ' ').take(RECENT_MESSAGE_CHARS)}"
            }
        if (lines.isEmpty()) return ""
        return lines.joinToString("\n")
    }
}
