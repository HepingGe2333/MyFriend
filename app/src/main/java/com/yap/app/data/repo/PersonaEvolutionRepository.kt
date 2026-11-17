package com.yap.app.data.repo

import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPendingPatchDao
import com.yap.app.data.local.PersonaPendingPatchEntity
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.remote.ServerWriteGateway
import com.yap.app.domain.PersonaEvolution
import com.yap.app.domain.PersonaProfile
import com.yap.app.domain.ResolvedApiConfig
import com.yap.app.domain.UsageKind
import com.yap.app.network.AiProviderRegistry
import com.yap.app.network.InferenceParams
import com.yap.app.network.WireMessage
import com.yap.app.data.remote.ServerEvolutionPatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val apiConfigRepository: ApiConfigRepository,
    private val registry: AiProviderRegistry,
    private val settingsRepository: SettingsRepository,
    /** 服务端模式判断 + 接受时要 `POST /v1/personas`；本地模式下完全用不到。 */
    private val serverWrites: ServerWriteGateway,
    /** 接受 / 拒绝一条待审变化（本地写回 or 服务端写回）。 */
    private val acceptance: PersonaEvolutionAcceptance
) : PersonaEvolutionTrigger {

    companion object {
        /** 每多少条用户消息评估一次。 */
        const val USER_MESSAGES_PER_EVAL: Int = 20

        /** 就算没到 20 条，隔多久也回顾一次。 */
        const val EVAL_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

        /** 待审补丁的有效期：7 天后自动丢弃（模型看到的「当时」已经不是现在了）。 */
        const val PENDING_TTL_MS: Long = 7 * 24 * 60 * 60 * 1000L

        /**
         * 服务端待审列表的刷新间隔（0.13）。
         *
         * 待审变化由「20 条消息 / 24 小时」触发，不是实时内容；20 秒足够让「刚评估完」
         * 的补丁在打开人设页时出现，又不会为一张几乎不变的列表持续打请求。
         */
        const val SERVER_PENDING_REFRESH_MILLIS: Long = 20_000L

        /** 服务端没给 reason 时的兜底文案（与服务端 `EvolutionRoutes` 的语义一致）。 */
        const val SERVER_PATCH_REASON: String = "这几轮聊下来，他觉得自己有点变化（需要你点头才会写进人设）。"

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

    // ---------------------------------------------------------------- 触发

    /**
     * 一条用户消息发完（本地模式）或一轮助手回复落库（服务端模式）后调它：
     * 条件到了就异步评估一次，**不等**（聊天不能被它拖慢）。
     *
     * 同一时刻只允许一轮评估：`inFlight` 挡住并发，否则连发两条消息会打出两次模型请求。
     * 两种模式的触发点都走这一个方法、读同一份游标，所以「切模式」不会重复评估。
     */
    override fun ensureAsync(personaId: String, now: Long) {
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
        // 本地模式与服务端模式共用的游标：谁先评估，另一方接着算，不会因为切模式再来一次。
        val samePersona = settings.evolutionPersonaId == personaId
        val cursor = EvolutionCursor(
            personaId = if (samePersona) personaId else null,
            userMessages = if (samePersona) settings.evolutionUserMessageCount else 0,
            lastRunAt = if (samePersona) settings.evolutionLastRunAt else 0L
        ).forPersona(personaId)

        if (!cursor.shouldEvaluate(userMessages, now)) return null

        runCatching { settingsRepository.setEvolutionCursor(personaId, userMessages, now) }
        // 0.13：服务端模式下模型与 Key 都在服务端，改调服务端端点（本地模式这一行以下是原路径）。
        return if (serverWrites.isEnabled()) {
            evaluateOnServer(persona, session.id, now)
        } else {
            evaluate(persona, sessionId = session.id, now = now)
        }
    }

    /**
     * 服务端模式的评估：`POST /v1/personas/{id}/evolution/evaluate`，把返回的补丁缓存进本地待审表。
     *
     * 为什么还要本地缓存：待审卡片的字段（patchJson/summary/reason/createdAt）与 UI 的
     * `PendingChangeUi` 完全同构，缓存一份就能让界面、离线查看、接受时的乐观移除都走同一条路径；
     * 权威列表仍然来自 [observePending]（服务端 `GET .../pending`）。
     *
     * 失败（服务端没配 LLM / 上游错）返回 `written = false`，不抛：聊天主路径不该被打断。
     */
    private suspend fun evaluateOnServer(
        persona: PersonaEntity,
        sessionId: String?,
        now: Long
    ): Evaluation {
        val result = serverWrites.evaluateEvolution(persona.id).getOrNull()
            ?: return Evaluation(proposed = 0, written = false)
        val patch = result.patch ?: return Evaluation(proposed = result.proposed, written = false, summary = result.summary)
        val cached = cacheServerPatch(persona.id, patch, sessionId, now)
        val changes = PersonaEvolution.decodePatch(patch.patchJson)
        return Evaluation(
            proposed = result.proposed.takeIf { it > 0 } ?: changes.size,
            written = cached,
            summary = result.summary.ifBlank { PersonaEvolution.summary(changes) }
        )
    }

    /** 服务端补丁 → 本地待审行（id 用服务端 id，接受/拒绝时原样回传）。返回是否写入成功。 */
    private suspend fun cacheServerPatch(
        personaId: String,
        patch: ServerEvolutionPatch,
        sessionId: String?,
        now: Long
    ): Boolean {
        val id = patch.id.trim()
        if (id.isEmpty() || patch.patchJson.isBlank()) return false
        if (!PersonaEvolution.isEvolutionPatch(patch.patchJson)) return false
        val changes = PersonaEvolution.decodePatch(patch.patchJson)
        return runCatching {
            pendingPatchDao.insert(
                PersonaPendingPatchEntity(
                    id = id,
                    personaId = personaId,
                    sessionId = patch.sessionId ?: sessionId,
                    patchJson = patch.patchJson,
                    summary = patch.summary.takeIf { it.isNotBlank() }
                        ?: PersonaEvolution.summary(changes),
                    reason = patch.reason.ifBlank { SERVER_PATCH_REASON },
                    createdAt = patch.createdAt.takeIf { it > 0L } ?: now
                )
            )
            true
        }.getOrDefault(false)
    }

    /** 真的问一次模型并把结果写进待审表（测试里也可以直接调它）。 */
    suspend fun evaluate(persona: PersonaEntity, sessionId: String?, now: Long): Evaluation {
        val resolved = resolveApiConfig(persona, sessionId)
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

    /** 现在是不是服务端模式：人设页据此决定「接受」的文案（实际写路径在 [acceptance] 里）。 */
    fun isServerMode(): Boolean = serverWrites.isEnabled()

    // ---------------------------------------------------------------- 待审

    /**
     * 人设页「他最近的变化」：只列进化的补丁（老的人设卡补丁有别的入口）。
     *
     * - **本地模式**：直接订阅 Room（0.13 之前的行为一字未改）；
     * - **服务端模式**：权威列表来自 `GET /v1/personas/{id}/evolution/pending`。
     *   首帧立即拉一次，之后每 [SERVER_PENDING_REFRESH_MILLIS] 拉一次（待审列表变化很慢，
     *   不值得为它开事件流）；拉取失败时退回落本地缓存，界面不会突然「全没了」。
     */
    fun observePending(personaId: String): Flow<List<PersonaPendingPatchEntity>> {
        val local = pendingPatchDao.observeByPersona(personaId).map { patches ->
            patches.filter { PersonaEvolution.isEvolutionPatch(it.patchJson) }
        }
        if (!serverWrites.isEnabled()) return local
        return flow {
            while (currentCoroutineContext().isActive) {
                emit(pending(personaId))
                delay(SERVER_PENDING_REFRESH_MILLIS)
            }
        }
    }

    /**
     * 读一次待审列表。
     *
     * 服务端模式：拉 `GET .../pending` → 把结果同步进本地缓存表（补丁行 + 服务端 id）→ 返回缓存行。
     * 失败时返回本地缓存（上一次拉到的），而不是空列表——空列表会让用户以为提议被系统吞了。
     */
    suspend fun pending(personaId: String): List<PersonaPendingPatchEntity> {
        if (!serverWrites.isEnabled()) return localPending(personaId)
        val remote = serverWrites.pendingEvolution(personaId).getOrNull()
            ?: return localPending(personaId)
        val patches = remote.patches.filter { it.id.isNotBlank() && PersonaEvolution.isEvolutionPatch(it.patchJson) }
        runCatching {
            // 以服务端为准：本地那些已经不在服务端列表里的补丁（被别的设备接受/拒绝、过期）清掉。
            // 只在拉取成功时做，失败时保留本地缓存。
            pendingPatchDao.deleteByPersona(personaId)
            patches.forEach { patch ->
                cacheServerPatch(personaId, patch, patch.sessionId, System.currentTimeMillis())
            }
        }
        return localPending(personaId)
    }

    private suspend fun localPending(personaId: String): List<PersonaPendingPatchEntity> =
        runCatching { pendingPatchDao.byPersona(personaId) }.getOrDefault(emptyList())
            .filter { PersonaEvolution.isEvolutionPatch(it.patchJson) }

    /** 一条补丁给用户看的几行：`+嘴硬心软（原因）`。 */
    fun describe(patch: PersonaPendingPatchEntity): List<PersonaEvolution.Change> =
        PersonaEvolution.decodePatch(patch.patchJson)

    /**
     * 接受一条变化：本地模式写回人格的四个字段并 `version + 1`；服务端模式先 `POST /v1/personas`。
     *
     * 只改 `traits/likes/behaviors/quirks` 四个列——名字、描述、提示词、世界、关系一律不碰。
     * 没有真正变化时（去重后为空）不升版本，但仍然把补丁删掉（它已经被处理过了）。
     * 服务端写入失败时本地一行都不写、补丁保留，调用方（人设页）据此提示「没写进去」。
     */
    suspend fun accept(patchId: String, now: Long = System.currentTimeMillis()): Result<PersonaEntity> =
        acceptance.accept(patchId, now)

    /**
     * 拒绝一条变化：只丢掉提议，人设一动不动。
     *
     * 服务端模式先 `POST .../{patchId}/reject`，成功才删本地缓存；失败保留待审并返回 failure
     * （界面提示「没拒绝成」），本地模式仍只有「删本地」这一步。
     */
    suspend fun reject(patchId: String): Result<Unit> = acceptance.reject(patchId)

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

    /**
     * 评估用哪个 provider 配置。
     *
     * 顺序：人格绑定的 → 会话绑定的 →（**仅服务端模式**）本机第一个可用配置。
     *
     * 为什么服务端模式要兜底：服务端没有「让客户端调一次模型」的接口，评估只能复用本机已有的
     * provider 客户端；而服务端模式的人格/会话 id 是从服务端带下来的，本机不一定真有那条配置
     * （bootstrap 另一个设备的人格时尤其如此）。解析失败就继续试下一个，而不是让「本机有 key
     * 却永远不评估」。本地模式仍然只认人格/会话绑定的配置，行为与 0.13 之前一致。
     * 一个都解析不出来时返回 null，这一轮安静跳过（聊天不受影响）。
     */
    private suspend fun resolveApiConfig(
        persona: PersonaEntity,
        sessionId: String?
    ): ResolvedApiConfig? {
        val sessionConfigId = sessionId?.let { id ->
            runCatching { sessionDao.getById(id)?.apiConfigId }.getOrNull()
        }
        val candidates = buildList {
            persona.defaultApiConfigId?.let(::add)
            sessionConfigId?.let(::add)
            if (serverWrites.isEnabled()) firstLocalConfigId()?.let(::add)
        }
        for (configId in candidates.distinct()) {
            runCatching { apiConfigRepository.resolve(configId) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** 本机第一个 API 配置（服务端模式的评估兜底；没有任何配置时 null）。 */
    private suspend fun firstLocalConfigId(): String? =
        runCatching { apiConfigRepository.getAll().firstOrNull()?.id }.getOrNull()

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

/**
 * 人格进化的**共享游标**：上次评估到第几条用户消息、上次评估发生在什么时候。
 *
 * 本地模式与服务端模式读写的是同一份（`SettingsRepository` 里的三个 evolution 键），
 * 所以「本地刚聊满 20 条评估过一次 → 切到服务端 → 下一条回复完成」不会重复评估：
 * 服务端那一次看到的是同一个 `userMessages` 与同一个游标。
 *
 * 游标属于别的人格时按「从没评估过」算（换人之后不该拿上一个人的进度）。
 * 纯数据 + 纯函数，没有 Room/DataStore，`ServerPersonaEvolutionTest` 直接驱动它钉住边界。
 */
data class EvolutionCursor(
    val personaId: String? = null,
    val userMessages: Int = 0,
    val lastRunAt: Long = 0L
) {

    /** 把游标归到 [personaId]：同一个人原样返回，换了人就清零。 */
    fun forPersona(personaId: String): EvolutionCursor =
        if (this.personaId == personaId) this else EvolutionCursor(personaId = personaId)

    /** 这一轮要不要评估（规则本身仍在 [PersonaEvolutionRepository.shouldEvaluate]）。 */
    fun shouldEvaluate(userMessages: Int, now: Long): Boolean =
        PersonaEvolutionRepository.shouldEvaluate(userMessages, this.userMessages, lastRunAt, now)

    /** 评估之后推进游标（调用模型**之前**推进：失败也算这一轮过去了）。 */
    fun advanced(userMessages: Int, now: Long): EvolutionCursor =
        copy(userMessages = userMessages, lastRunAt = now)
}
