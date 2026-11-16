package com.yap.app.data.remote

import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MemoryEntity
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPresetEntity
import com.yap.app.data.prefs.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一次性 bootstrap 的两端数据量（只用于四象限判定与进度展示）。
 *
 * 消息数用「所有会话的 `messageCount` 之和」而不是快照里 `messages.size`：`/v1/state?since=0`
 * 每个会话只给最新一块，拿它判断「服务端有没有历史」会把几十万条聊天误判成「几乎空库」。
 */
data class ServerBootstrapCounts(
    val localPersonas: Int = 0,
    val localSessions: Int = 0,
    val localMessages: Int = 0,
    val serverPersonas: Int = 0,
    val serverSessions: Int = 0,
    val serverMessages: Long = 0
) {
    /** 本地没有任何聊天数据（人格/会话/消息都空）。 */
    val localEmpty: Boolean
        get() = localPersonas == 0 && localSessions == 0 && localMessages == 0

    /** 服务端没有任何聊天数据。 */
    val serverEmpty: Boolean
        get() = serverPersonas == 0 && serverSessions == 0 && serverMessages == 0L
}

/** bootstrap 的搬运方向。 */
enum class ServerBootstrapMode {
    /** 两端都空：什么都不用搬，只写标记。 */
    NOOP,

    /** 服务端空、本地有：全量推送到服务端。 */
    PUSH,

    /** 服务端有、本地空（新客户端）：全量拉取到 Room。 */
    PULL,

    /** 两端都有：本地独有推送、服务端独有拉取、同 id 冲突以服务端/新时间为准。 */
    MERGE
}

/** 纯函数：四象限判定（单测直接钉住，不依赖网络/Room）。 */
object ServerBootstrapDecision {
    fun decide(counts: ServerBootstrapCounts): ServerBootstrapMode = when {
        counts.serverEmpty && counts.localEmpty -> ServerBootstrapMode.NOOP
        counts.serverEmpty -> ServerBootstrapMode.PUSH
        counts.localEmpty -> ServerBootstrapMode.PULL
        else -> ServerBootstrapMode.MERGE
    }
}

/** 设置页那一行进度文字对应的阶段（UI 负责枚举 → 资源文案，数据层不出现中文）。 */
enum class ServerBootstrapPhase {
    IDLE,
    INSPECTING,
    PUSHING,
    PULLING,
    MERGING,
    COMPLETED,
    ALREADY_DONE,
    FAILED
}

/** bootstrap 的可观察进度（设置页一行字；计数用于「已搬多少」）。 */
data class ServerBootstrapProgress(
    val phase: ServerBootstrapPhase = ServerBootstrapPhase.IDLE,
    val mode: ServerBootstrapMode? = null,
    val personas: Int = 0,
    val sessions: Int = 0,
    val messages: Int = 0,
    /** 失败时的技术原因（English，已去掉地址与令牌）；成功/进行中为 null。 */
    val error: String? = null
) {
    val running: Boolean
        get() = phase == ServerBootstrapPhase.INSPECTING ||
            phase == ServerBootstrapPhase.PUSHING ||
            phase == ServerBootstrapPhase.PULLING ||
            phase == ServerBootstrapPhase.MERGING
}

/** 一次 bootstrap 的结果。 */
data class ServerBootstrapOutcome(
    val success: Boolean,
    val skipped: Boolean = false,
    val mode: ServerBootstrapMode? = null,
    val counts: ServerBootstrapCounts = ServerBootstrapCounts(),
    val personas: Int = 0,
    val sessions: Int = 0,
    val messages: Int = 0,
    val attempts: Int = 0,
    val error: Throwable? = null
)

/**
 * bootstrap 的**本地读写面**（生产实现是 Room；测试用内存假实现）。
 *
 * 读：计数 + 实体列表（消息按会话懒读，避免大库一次性物化）；
 * 写：拉回来的数据落 Room、映射关系落 DataStore（[ServerLocalStore] 由编排层单独持有）。
 */
interface ServerBootstrapLocal {

    suspend fun counts(): ServerBootstrapCounts = ServerBootstrapCounts()

    suspend fun personas(): List<PersonaEntity> = emptyList()

    suspend fun presets(): List<PersonaPresetEntity> = emptyList()

    /** 某个人格的记忆；`personaId = null` 表示全部。 */
    suspend fun memories(personaId: String? = null): List<MemoryEntity> = emptyList()

    suspend fun sessions(): List<ChatSessionEntity> = emptyList()

    /** 某个本地会话的全部消息（按 createdAt/id 升序由编排层负责）。 */
    suspend fun messages(localSessionId: String): List<MessageEntity> = emptyList()

    suspend fun settings(): AppSettings = AppSettings()

    /** 把服务端的人格/会话落进 Room（复用 [ServerStateSink.applySnapshot] 的映射规则）。 */
    suspend fun applySnapshot(snapshot: StateSnapshot) = Unit

    /** 把一页消息落进 Room（复用 [ServerStateSink.applyPage]）。 */
    suspend fun applyPage(page: ServerMessagePage, personaId: String?) = Unit

    /** 拉回的人格（完整字段）——调用方已完成 id 映射与冲突判定。 */
    suspend fun applyPersonas(personas: List<PersonaEntity>) = Unit

    /** 拉回的一个预设。 */
    suspend fun applyPreset(preset: PersonaPresetEntity) = Unit

    /** 推送预设后服务端分配了新 id：把本地这一行换成服务端 id（预设无子表，换主键安全）。 */
    suspend fun replacePresetId(localId: String, preset: PersonaPresetEntity) = Unit

    /** 拉回的一条记忆。 */
    suspend fun applyMemory(memory: MemoryEntity) = Unit

    /** 拉回的服务端设置（白名单键 → 本地 DataStore）。 */
    suspend fun applySettings(values: JsonObject) = Unit

    /**
     * bootstrap 把本地「我的头像」迁到服务端之后，把本地引用换成 `/v1/media/<id>` 并保留
     * 本机缓存副本（离线显示）。只写本地，不触发镜像（推送由 bootstrap 统一做）。
     */
    suspend fun applyUserAvatar(serverRef: String?, localRef: String?) = Unit

    /** bootstrap 完成后把增量同步游标推进到这次快照的位置（避免下一次又把全量拉一遍）。 */
    suspend fun setSyncCursors(stateCursor: Long, eventCursor: Long) = Unit
}

/**
 * 一次性 bootstrap 的**状态机**（0.12.1）。
 *
 * 触发条件：`server_bootstrap_done:<baseUrl>` 不存在（开启服务端模式 / 换地址后的第一次连接）。
 * 完成条件：整轮搬运成功后才写标记；失败保留标记为空，下一次前台/启动重试。
 *
 * ## 为什么失败可以重试而不会重复导入
 *
 * 每一类实体都有稳定幂等键：
 * - 人格/记忆：服务端分配新 id，客户端**每成功一条就写一条 id 映射**；重试时先查映射/远端列表；
 * - 会话：`POST /v1/sessions` 本身按 persona 幂等（已存在返回既有会话）；
 * - 消息：`messages/import` 按消息 `id` 幂等，重试同一批不会多出消息；
 * - 预设：成功一条就把本地行换成服务端 id，重试时远端列表里已有它；
 * - 设置：白名单合并，重复写同值。
 *
 * ## 冲突口径
 *
 * 同 id/同映射的实体：服务端 `updatedAt` **大于或等于**本地就采用服务端（并把服务端实体写回
 * 本地缓存）；只有本地严格更新时才把本地推上去。没有 `updatedAt` 的设置对象以服务端为准。
 */
class ServerBootstrapManager(
    private val local: ServerBootstrapLocal,
    private val remote: ServerBootstrapRemote,
    private val store: ServerLocalStore,
    /**
     * 推送人格/预设头像：本地文件 → `POST /v1/media` → `/v1/media/<id>`。
     *
     * 默认实现返回 null（测试/无媒体环境）：头像丢了也比整轮 bootstrap 卡住好；生产装配
     * 注入 `ServerChatMediaSender.uploadPersonaAvatar`，失败会抛给重试。
     */
    private val uploadAvatar: suspend (String?, String?) -> String? = { _, _ -> null },
    /** 上传「我的头像」（`USER_AVATAR`）：本地模式切到服务端时把已有的本地头像迁上去。 */
    private val uploadUserAvatar: suspend (String?) -> String? = { null },
    private val retryDelaysMillis: List<Long> = DEFAULT_RETRY_DELAYS,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private val mutex = Mutex()

    private val _progress = MutableStateFlow(ServerBootstrapProgress())
    val progress: StateFlow<ServerBootstrapProgress> = _progress.asStateFlow()

    /**
     * 需要时执行一次 bootstrap；已经做过（同一 baseUrl）直接跳过。
     *
     * 并发调用串行化：启动与回前台可能同时触发，第一次没做完时第二次不应并发再跑一轮。
     */
    suspend fun runIfNeeded(baseUrl: String): ServerBootstrapOutcome = mutex.withLock {
        val key = normalize(baseUrl)
        if (key.isEmpty()) {
            return ServerBootstrapOutcome(
                success = false,
                error = ServerApiException("server base url is empty", code = "server_unavailable")
            )
        }
        if (store.bootstrapDone(key)) {
            _progress.value = ServerBootstrapProgress(phase = ServerBootstrapPhase.ALREADY_DONE)
            return ServerBootstrapOutcome(success = true, skipped = true)
        }

        var lastError: Throwable? = null
        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            attempts++
            try {
                _progress.value = ServerBootstrapProgress(phase = ServerBootstrapPhase.INSPECTING)
                val result = runOnce()
                store.markBootstrapDone(key)
                _progress.value = ServerBootstrapProgress(
                    phase = ServerBootstrapPhase.COMPLETED,
                    mode = result.mode,
                    personas = result.personas,
                    sessions = result.sessions,
                    messages = result.messages
                )
                return ServerBootstrapOutcome(
                    success = true,
                    mode = result.mode,
                    counts = result.counts,
                    personas = result.personas,
                    sessions = result.sessions,
                    messages = result.messages,
                    attempts = attempts
                )
            } catch (cancel: CancellationException) {
                // 用户关掉服务端模式/退后台会取消这一轮：进度清零，下次重新开始时不要停在「进行中」。
                _progress.value = ServerBootstrapProgress(phase = ServerBootstrapPhase.IDLE)
                throw cancel
            } catch (error: Throwable) {
                lastError = error
                if (attempts >= MAX_ATTEMPTS) break
                val wait = retryDelaysMillis.getOrElse(attempts - 1) { DEFAULT_RETRY_DELAYS.last() }
                if (wait > 0) delayFn(wait)
            }
        }
        _progress.value = ServerBootstrapProgress(
            phase = ServerBootstrapPhase.FAILED,
            error = describe(lastError)
        )
        return ServerBootstrapOutcome(
            success = false,
            attempts = attempts,
            error = lastError
        )
    }

    // ------------------------------------------------------------------ 一轮

    private data class BootstrapResult(
        val mode: ServerBootstrapMode,
        val counts: ServerBootstrapCounts,
        val personas: Int,
        val sessions: Int,
        val messages: Int
    )

    private data class Tally(
        var personas: Int = 0,
        var sessions: Int = 0,
        var messages: Int = 0
    )

    private suspend fun runOnce(): BootstrapResult {
        val localCounts = local.counts()
        // 三个读请求的顺序固定：先 state（拿游标/时钟），再完整人格列表与会话列表。
        val state = remote.state()
        val remotePersonas = remote.personas()
        val remoteSessions = remote.sessions()
        val counts = ServerBootstrapCounts(
            localPersonas = localCounts.localPersonas,
            localSessions = localCounts.localSessions,
            localMessages = localCounts.localMessages,
            serverPersonas = remotePersonas.size.coerceAtLeast(state.personas.size),
            serverSessions = remoteSessions.size.coerceAtLeast(state.sessions.size),
            // messageCount 是服务端全量条数；快照里的 messages 只有最新一块。
            serverMessages = remoteSessions.sumOf { it.messageCount }
                .coerceAtLeast(state.messages.size.toLong())
        )
        val mode = ServerBootstrapDecision.decide(counts)
        return when (mode) {
            ServerBootstrapMode.NOOP -> BootstrapResult(mode, counts, 0, 0, 0)
            ServerBootstrapMode.PUSH -> {
                _progress.value = ServerBootstrapProgress(
                    phase = ServerBootstrapPhase.PUSHING,
                    mode = mode
                )
                val tally = pushAll(remotePersonas)
                BootstrapResult(mode, counts, tally.personas, tally.sessions, tally.messages)
            }
            ServerBootstrapMode.PULL -> {
                _progress.value = ServerBootstrapProgress(
                    phase = ServerBootstrapPhase.PULLING,
                    mode = mode
                )
                val tally = pullAll(state, remotePersonas, remoteSessions)
                BootstrapResult(mode, counts, tally.personas, tally.sessions, tally.messages)
            }
            ServerBootstrapMode.MERGE -> {
                _progress.value = ServerBootstrapProgress(
                    phase = ServerBootstrapPhase.MERGING,
                    mode = mode
                )
                val tally = merge(state, remotePersonas, remoteSessions)
                BootstrapResult(mode, counts, tally.personas, tally.sessions, tally.messages)
            }
        }
    }

    // ------------------------------------------------------------------ 全量推送

    private suspend fun pushAll(remotePersonas: List<JsonObject>): Tally {
        val tally = Tally()
        val remotePersonaIds = remotePersonas.mapNotNull { it.string("id")?.takeIf(String::isNotBlank) }.toSet()
        val personaServerIds = mutableMapOf<String, String>()

        // 人格：先建映射，后面会话/记忆都要用。
        local.personas().forEach { persona ->
            val mapped = store.serverPersonaId(persona.id)
            // 映射可能指向已被清空/重置的服务端：只有远端确实还有它时才当作已推送。
            val existing = mapped?.takeIf { it in remotePersonaIds }
                ?: persona.id.takeIf { it in remotePersonaIds }
            if (existing != null) {
                personaServerIds[persona.id] = existing
                store.setPersonaMapping(existing, persona.id)
                return@forEach
            }
            val server = remote.createPersona(
                ServerPersonaWrite.payload(
                    persona = persona,
                    avatarUrl = avatarRefFor(persona.avatarUri, null),
                    creating = true
                )
            )
            val serverId = server.string("id")?.takeIf { it.isNotBlank() }
                ?: throw ServerBootstrapFailure("persona response is missing id")
            personaServerIds[persona.id] = serverId
            store.setPersonaMapping(serverId, persona.id)
            tally.personas++
            publish(ServerBootstrapPhase.PUSHING, ServerBootstrapMode.PUSH, tally)
        }

        // 预设：服务端不支持预设端点时静默跳过（不阻断聊天数据搬运）。
        val remotePresets = remote.presets()
        if (remotePresets != null) {
            val remotePresetIds = remotePresets.mapNotNull { it.string("id") }.toSet()
            local.presets().forEach { preset ->
                if (preset.id in remotePresetIds) return@forEach
                val response = remote.savePreset(
                    ServerPresetWrite.payload(
                        preset = preset,
                        avatarRef = avatarRefFor(preset.avatarUri, null),
                        creating = true
                    )
                )
                val serverPreset = ServerBootstrapMapping.presetEntity(response, preset)
                if (serverPreset.id.isNotBlank() && serverPreset.id != preset.id) {
                    local.replacePresetId(preset.id, serverPreset)
                }
                tally.personas++ // 预设与人格共用一个「实体」计数，比另开一个 UI 字段更简洁
            }
        }

        // 记忆：按人格推送，先拿远端列表避免重试时重复创建。
        local.personas().forEach { persona ->
            val serverPersonaId = personaServerIds[persona.id] ?: return@forEach
            val remoteMemories = remote.memories(serverPersonaId).orEmpty()
            val remoteIds = remoteMemories.mapNotNull { it.string("id") }.toSet()
            val remoteContents = remoteMemories.mapNotNull { it.string("content") }.toSet()
            local.memories(persona.id).forEach { memory ->
                val mapped = store.serverMemoryId(memory.id)
                if (mapped != null && mapped in remoteIds) return@forEach
                if (mapped == null && memory.content in remoteContents) {
                    val found = remoteMemories.firstOrNull { it.string("content") == memory.content }
                    found?.string("id")?.let { store.setMemoryMapping(it, memory.id) }
                    return@forEach
                }
                val server = remote.saveMemory(
                    serverPersonaId,
                    ServerMemoryWrite.payload(memory, creating = true)
                )
                val serverId = server.string("id")?.takeIf { it.isNotBlank() }
                    ?: throw ServerBootstrapFailure("memory response is missing id")
                store.setMemoryMapping(serverId, memory.id)
            }
        }

        // 会话 + 消息：每个本地会话建服务端会话，再按 200 条/批导入。
        val serverPersonaIdOfLocal = mutableMapOf<String, String>()
        local.personas().forEach { persona ->
            val mapped = store.serverPersonaId(persona.id)
            if (mapped != null) serverPersonaIdOfLocal[persona.id] = mapped
        }
        local.sessions().forEach { session ->
            // 会话必须挂在「已经推到服务端的那个 id」上；本地孤儿会话（persona 行不存在）
            // 不能拿本机 UUID 硬建，否则服务端 404 会让整次 bootstrap 永远重试。
            val serverPersonaId = serverPersonaIdOfLocal[session.personaId] ?: return@forEach
            if (serverPersonaId.isBlank()) return@forEach
            val serverSession = remote.createSession(
                serverPersonaId,
                ServerBootstrapMapping.sessionTitle(session)
            )
            val serverSessionId = serverSession.id.takeIf { it.isNotBlank() }
                ?: throw ServerBootstrapFailure("session response is missing id")
            store.setSessionMapping(serverSessionId, session.id)
            tally.sessions++
            pushMessages(serverSessionId, local.messages(session.id), tally)
            publish(ServerBootstrapPhase.PUSHING, ServerBootstrapMode.PUSH, tally)
        }

        // 设置：最后推（前面任何一步失败都不该让服务端先看到「一台空机器」的偏好）。
        remote.pushSettings(settingsPayloadForPush())
        return tally
    }

    private suspend fun pushMessages(
        serverSessionId: String,
        messages: List<MessageEntity>,
        tally: Tally
    ) {
        ServerBootstrapMapping.sortMessages(messages)
            .chunked(ServerApi.MAX_IMPORT_MESSAGES)
            .forEach { batch ->
                importWithAttachmentFallback(serverSessionId, batch)
                tally.messages += batch.size
                publish(ServerBootstrapPhase.PUSHING, ServerBootstrapMode.PUSH, tally)
            }
    }

    /**
     * 导入一批消息；如果服务端因为附件不存在整批 400，就**去掉附件重发一次**。
     *
     * 为什么需要这层兜底：换服务端后，本地消息里的 `/v1/media/<id>` 是旧服务端的媒体，
     * 新服务端导入端会校验附件存在并整批拒绝。聊天文字比图片重要：宁可这一批图片附件
     * 不带过去，也不能让整次 bootstrap 永远失败（媒体历史迁移不在本版范围）。
     */
    private suspend fun importWithAttachmentFallback(
        serverSessionId: String,
        batch: List<MessageEntity>
    ) {
        val payload = ServerBootstrapMapping.importPayload(batch)
        try {
            remote.importMessages(serverSessionId, payload)
        } catch (error: ServerApiException) {
            val stripped = payload.map { message ->
                if (message.attachments.isEmpty()) message else message.copy(attachments = emptyList())
            }
            val hasAttachments = stripped != payload
            val attachmentProblem = error.code == "unknown_media" ||
                error.code == "invalid_argument" ||
                error.status == 400
            if (!hasAttachments || !attachmentProblem) throw error
            remote.importMessages(serverSessionId, stripped)
        }
    }

    // ------------------------------------------------------------------ 全量拉取

    private suspend fun pullAll(
        state: StateSnapshot,
        remotePersonas: List<JsonObject>,
        remoteSessions: List<ServerSession>
    ): Tally {
        val tally = Tally()
        val localPersonas = local.personas().associateBy { it.id }
        val summaries = mutableListOf<ServerPersona>()

        val entities = remotePersonas.mapNotNull { raw ->
            val serverId = raw.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val localId = store.localPersonaId(serverId) ?: serverId
            val existing = localPersonas[localId]
            val entity = ServerBootstrapMapping.personaEntity(raw, existing, now())
            store.setPersonaMapping(serverId, entity.id)
            summaries += ServerBootstrapMapping.personaSummary(raw, serverId)
            entity
        }
        // 先写完整人格（含提示词/世界/话风），再用快照补会话（快照映射器只认 name/avatar）。
        local.applyPersonas(entities)
        tally.personas += entities.size
        publish(ServerBootstrapPhase.PULLING, ServerBootstrapMode.PULL, tally)

        local.applySnapshot(StateSnapshot(personas = summaries, sessions = remoteSessions))
        remoteSessions.forEach { session ->
            val localPersonaId = store.localPersonaId(session.personaId) ?: session.personaId
            pullPages(session.id, localPersonaId, ServerBootstrapMode.PULL, tally)
            tally.sessions++
        }

        // 预设
        val remotePresets = remote.presets()
        if (remotePresets != null) {
            val localPresets = local.presets().associateBy { it.id }
            remotePresets.forEach { raw ->
                val serverId = raw.string("id")?.takeIf { it.isNotBlank() } ?: return@forEach
                val entity = ServerBootstrapMapping.presetEntity(raw, localPresets[serverId], now())
                local.applyPreset(entity)
            }
        }

        // 记忆（按人格翻完分页）
        remotePersonas.forEach { raw ->
            val serverPersonaId = raw.string("id")?.takeIf { it.isNotBlank() } ?: return@forEach
            val localPersonaId = store.localPersonaId(serverPersonaId) ?: serverPersonaId
            val remoteMemories = remote.memories(serverPersonaId) ?: return@forEach
            val localMemories = local.memories(localPersonaId).associateBy { it.id }
            remoteMemories.forEach { memoryRaw ->
                val serverMemoryId = memoryRaw.string("id")?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val localId = store.localMemoryId(serverMemoryId) ?: serverMemoryId
                val entity = ServerBootstrapMapping.memoryEntity(
                    memoryRaw,
                    localPersonaId,
                    localMemories[localId],
                    now()
                )
                local.applyMemory(entity)
                store.setMemoryMapping(serverMemoryId, entity.id)
            }
        }

        // 设置：服务端有就用服务端的（新客户端的本机偏好是默认值，不该反向覆盖）。
        val settings = remote.settings()
        if (settings.isNotEmpty()) local.applySettings(localizeSettings(settings))

        advanceCursors(state, ServerBootstrapMode.PULL)
        return tally
    }

    /**
     * 逐会话从最新块往前翻完所有块（保持服务端块语义：一页一块，编号空洞允许）。
     *
     * @return 这一轮见过的服务端消息 id（merge 用它算「本地独有」）
     */
    private suspend fun pullPages(
        serverSessionId: String,
        localPersonaId: String?,
        mode: ServerBootstrapMode,
        tally: Tally
    ): Set<String> {
        val seen = mutableSetOf<String>()
        var page = remote.latestPage(serverSessionId)
        var guard = 0
        while (guard++ < MAX_BLOCKS_PER_SESSION) {
            val blockIndex = page.blockIndex
            if (page.messages.isNotEmpty()) {
                local.applyPage(page, localPersonaId)
                page.messages.forEach { message ->
                    if (message.id.isNotBlank()) seen += message.id
                }
                tally.messages += page.messages.size
                publish(
                    if (mode == ServerBootstrapMode.MERGE) ServerBootstrapPhase.MERGING else ServerBootstrapPhase.PULLING,
                    mode,
                    tally
                )
            }
            if (!page.hasMoreOlder || blockIndex == null || blockIndex <= 0) break
            val before = remote.pageBefore(serverSessionId, blockIndex)
            if (before.messages.isEmpty() && before.blockIndex == null) break
            page = before
        }
        return seen
    }

    // ------------------------------------------------------------------ 合并

    private suspend fun merge(
        state: StateSnapshot,
        remotePersonas: List<JsonObject>,
        remoteSessions: List<ServerSession>
    ): Tally {
        val tally = Tally()
        val localPersonas = local.personas().associateBy { it.id }
        val remotePersonaById = remotePersonas
            .mapNotNull { raw -> raw.string("id")?.takeIf { it.isNotBlank() }?.let { it to raw } }
            .toMap()

        // ---- 人格：服务端独有拉取；本地独有推送；同 id/同映射按 updatedAt ----
        remotePersonaById.forEach { (serverId, raw) ->
            val localId = store.localPersonaId(serverId) ?: serverId
            val existing = localPersonas[localId]
            val serverUpdatedAt = raw.long("updatedAt") ?: 0L
            if (existing == null) {
                val entity = ServerBootstrapMapping.personaEntity(raw, null, now())
                local.applyPersonas(listOf(entity))
                store.setPersonaMapping(serverId, entity.id)
                tally.personas++
            } else {
                store.setPersonaMapping(serverId, existing.id)
                if (serverUpdatedAt >= existing.updatedAt) {
                    local.applyPersonas(
                        listOf(ServerBootstrapMapping.personaEntity(raw, existing, now()))
                    )
                    tally.personas++
                } else {
                    // 本地严格更新：用服务端 id 更新服务端，服务端时间戳由它自己写，不回写本地 id。
                    remote.updatePersona(
                        ServerPersonaWrite.payload(
                            persona = existing.copy(id = serverId),
                            avatarUrl = avatarRefFor(existing.avatarUri, serverId),
                            creating = false,
                            includeAvatar = true
                        )
                    )
                    tally.personas++
                }
            }
        }
        val remotePersonaIds = remotePersonaById.keys
        localPersonas.values.forEach { persona ->
            val mapped = store.serverPersonaId(persona.id)
            if (mapped != null && mapped in remotePersonaIds) return@forEach
            if (persona.id in remotePersonaIds) {
                store.setPersonaMapping(persona.id, persona.id)
                return@forEach
            }
            val server = remote.createPersona(
                ServerPersonaWrite.payload(
                    persona = persona,
                    avatarUrl = avatarRefFor(persona.avatarUri, null),
                    creating = true
                )
            )
            val serverId = server.string("id")?.takeIf { it.isNotBlank() }
                ?: throw ServerBootstrapFailure("persona response is missing id")
            store.setPersonaMapping(serverId, persona.id)
            tally.personas++
        }
        publish(ServerBootstrapPhase.MERGING, ServerBootstrapMode.MERGE, tally)

        // ---- 会话：先让快照把服务端会话映射进本地，再逐会话拉全量、补推本地独有消息 ----
        val summaries = remotePersonaById.map { (serverId, raw) ->
            ServerBootstrapMapping.personaSummary(raw, serverId)
        }
        local.applySnapshot(StateSnapshot(personas = summaries, sessions = remoteSessions))
        // 快照之后本地会话已经齐了：只读一次，避免「每个服务端会话都全表查一遍」。
        val localSessions = local.sessions()

        val handledLocalSessions = mutableSetOf<String>()
        remoteSessions.forEach { session ->
            val localPersonaId = store.localPersonaId(session.personaId) ?: session.personaId
            val localSessionId = store.localSessionId(session.id)
                ?: localSessions.firstOrNull { it.id == session.id }?.id
                ?: localSessions.firstOrNull { it.personaId == localPersonaId }?.id
            val serverIds = pullPages(session.id, localPersonaId, ServerBootstrapMode.MERGE, tally)
            tally.sessions++
            if (localSessionId == null) return@forEach
            handledLocalSessions += localSessionId
            val localOnly = ServerBootstrapMapping.sortMessages(local.messages(localSessionId))
                .filter { it.id !in serverIds }
            importInChunks(session.id, localOnly, tally)
            publish(ServerBootstrapPhase.MERGING, ServerBootstrapMode.MERGE, tally)
        }

        // 本地独有会话（服务端没有对应 persona 的会话）：建服务端会话并全量推送。
        localSessions.forEach { session ->
            if (session.id in handledLocalSessions) return@forEach
            val mappedSession = store.serverSessionId(session.id)
            if (mappedSession != null && remoteSessions.any { it.id == mappedSession }) return@forEach
            val serverPersonaId = store.serverPersonaId(session.personaId)
                ?: session.personaId.takeIf { it in remotePersonaIds }
                ?: return@forEach
            if (serverPersonaId.isBlank()) return@forEach
            val created = remote.createSession(
                serverPersonaId,
                ServerBootstrapMapping.sessionTitle(session)
            )
            val serverSessionId = created.id.takeIf { it.isNotBlank() }
                ?: throw ServerBootstrapFailure("session response is missing id")
            store.setSessionMapping(serverSessionId, session.id)
            tally.sessions++
            pushMessages(serverSessionId, local.messages(session.id), tally)
            publish(ServerBootstrapPhase.MERGING, ServerBootstrapMode.MERGE, tally)
        }

        // ---- 预设 ----
        val remotePresets = remote.presets()
        if (remotePresets != null) {
            val localPresets = local.presets().associateBy { it.id }
            val remotePresetById = remotePresets
                .mapNotNull { raw -> raw.string("id")?.takeIf { it.isNotBlank() }?.let { it to raw } }
                .toMap()
            remotePresetById.forEach { (serverId, raw) ->
                val existing = localPresets[serverId]
                val serverUpdatedAt = raw.long("updatedAt") ?: 0L
                when {
                    existing == null -> {
                        local.applyPreset(ServerBootstrapMapping.presetEntity(raw, null, now()))
                        tally.personas++
                    }
                    serverUpdatedAt >= existing.updatedAt -> {
                        local.applyPreset(ServerBootstrapMapping.presetEntity(raw, existing, now()))
                        tally.personas++
                    }
                    else -> {
                        remote.savePreset(
                            ServerPresetWrite.payload(
                                preset = existing,
                                avatarRef = avatarRefFor(existing.avatarUri, null),
                                creating = false
                            )
                        )
                        tally.personas++
                    }
                }
            }
            localPresets.values.forEach { preset ->
                if (preset.id in remotePresetById) return@forEach
                val response = remote.savePreset(
                    ServerPresetWrite.payload(
                        preset = preset,
                        avatarRef = avatarRefFor(preset.avatarUri, null),
                        creating = true
                    )
                )
                val serverPreset = ServerBootstrapMapping.presetEntity(response, preset)
                if (serverPreset.id.isNotBlank() && serverPreset.id != preset.id) {
                    local.replacePresetId(preset.id, serverPreset)
                }
                tally.personas++
            }
        }

        // ---- 记忆（按服务端人格）：远端独有拉取、本地独有推送、同 id 按 updatedAt ----
        remotePersonaById.forEach { (serverPersonaId, _) ->
            val localPersonaId = store.localPersonaId(serverPersonaId) ?: serverPersonaId
            val remoteMemories = remote.memories(serverPersonaId) ?: return@forEach
            val remoteById = remoteMemories
                .mapNotNull { raw -> raw.string("id")?.takeIf { it.isNotBlank() }?.let { it to raw } }
                .toMap()
            val localMemories = local.memories(localPersonaId)
            val consumed = mutableSetOf<String>()

            localMemories.forEach { memory ->
                val mapped = store.serverMemoryId(memory.id)
                val remoteEntry = remoteById.entries.firstOrNull { (serverId, _) ->
                    serverId == mapped || serverId == memory.id
                }
                if (remoteEntry == null) {
                    val server = remote.saveMemory(
                        serverPersonaId,
                        ServerMemoryWrite.payload(memory, creating = true)
                    )
                    val serverId = server.string("id")?.takeIf { it.isNotBlank() }
                        ?: throw ServerBootstrapFailure("memory response is missing id")
                    store.setMemoryMapping(serverId, memory.id)
                    tally.messages++ // 记忆计入 messages 进度：都是「条数」，不新开 UI 字段
                    return@forEach
                }
                val (serverId, raw) = remoteEntry
                consumed += serverId
                store.setMemoryMapping(serverId, memory.id)
                val serverUpdatedAt = raw.long("updatedAt") ?: 0L
                if (serverUpdatedAt >= memory.updatedAt) {
                    local.applyMemory(
                        ServerBootstrapMapping.memoryEntity(raw, localPersonaId, memory, now())
                    )
                } else {
                    remote.saveMemory(
                        serverPersonaId,
                        ServerMemoryWrite.payload(
                            memory.copy(id = serverId),
                            creating = false
                        )
                    )
                }
                tally.messages++
            }

            remoteById.forEach { (serverId, raw) ->
                if (serverId in consumed) return@forEach
                val localId = store.localMemoryId(serverId) ?: serverId
                val existing = localMemories.firstOrNull { it.id == localId }
                val entity = ServerBootstrapMapping.memoryEntity(raw, localPersonaId, existing, now())
                local.applyMemory(entity)
                store.setMemoryMapping(serverId, entity.id)
                tally.messages++
            }
        }

        // ---- 设置：服务端有设置就以服务端为准，否则把本地推上去 ----
        val settings = remote.settings()
        if (settings.isNotEmpty()) {
            local.applySettings(localizeSettings(settings))
        } else {
            remote.pushSettings(settingsPayloadForPush())
        }

        advanceCursors(state, ServerBootstrapMode.MERGE)
        return tally
    }

    private suspend fun importInChunks(
        serverSessionId: String,
        messages: List<MessageEntity>,
        tally: Tally
    ) {
        messages.chunked(ServerApi.MAX_IMPORT_MESSAGES).forEach { batch ->
            importWithAttachmentFallback(serverSessionId, batch)
            tally.messages += batch.size
        }
    }

    // ------------------------------------------------------------------ 内部

    private suspend fun advanceCursors(state: StateSnapshot, mode: ServerBootstrapMode) {
        val personaMax = state.personas.maxOfOrNull { it.updatedAt } ?: 0L
        val sessionMax = state.sessions.maxOfOrNull { it.updatedAt } ?: 0L
        val messageMax = state.messages.maxOfOrNull { it.createdAt } ?: 0L
        val stateCursor = maxOf(state.serverTime, personaMax, sessionMax, messageMax)
        // 推送/合并里本地推上去的实体服务端会写成 now：下一次增量同步会再拉回一次（幂等），
        // 这里只把游标推进到快照时刻，避免「自己刚推的东西」永远重拉。
        local.setSyncCursors(stateCursor = stateCursor, eventCursor = state.serverCursor)
        publish(
            if (mode == ServerBootstrapMode.PULL) ServerBootstrapPhase.PULLING else ServerBootstrapPhase.MERGING,
            mode,
            Tally()
        )
    }

    /**
     * 推送设置前把「本机文件形式的我的头像」迁到服务端。
     *
     * 场景：用户先在本地模式用了几个月头像，再打开服务端模式。直接 push 会把
     * `file:///…` 发给服务端，另一台设备拿到一个不存在的路径。这里先 `USER_AVATAR` 上传，
     * 成功才把设置换成本地引用，并把本机缓存副本留在 DataStore。
     */
    private suspend fun settingsPayloadForPush(): JsonObject {
        var settings = local.settings()
        // activePersonaId 在服务端有外键校验：本地主键先换服务端 id（bootstrap 推送后两者不同）。
        // 这一轮已经为每个本地人格写过映射；查不到就说明是孤儿人格，宁可清空也不能让设置 400。
        settings = settings.copy(
            activePersonaId = settings.activePersonaId?.let { localId ->
                store.serverPersonaId(localId)
            }
        )
        // 优先用本机缓存副本重传一次：换了一台服务端时，旧的 `/v1/media/<id>` 在新服务端
        // 并不存在；同一台服务端上重传同内容会由服务端按 sha256 去重，成本只是一次请求。
        val localFile = settings.userAvatarLocalUri?.trim()
            ?.takeIf { it.startsWith("file://") }
            ?: settings.userAvatarUri?.trim()?.takeIf { it.startsWith("file://") }
        if (localFile != null) {
            val ref = uploadUserAvatar(localFile)
            if (ref != null) {
                local.applyUserAvatar(ref, localFile)
                settings = settings.copy(userAvatarUri = ref, userAvatarLocalUri = localFile)
            } else if (!ServerMediaRef.isMediaRef(settings.userAvatarUri)) {
                // 上传失败且手里没有可用的服务端引用：宁可不给服务端头像，也不要把
                // `file:///data/...` 发给别的设备（那边一定打不开）。
                settings = settings.copy(userAvatarUri = null)
            }
        }
        return ServerSettingsWrite.payload(settings)
    }

    private suspend fun avatarRefFor(raw: String?, personaId: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (ServerMediaRef.isMediaRef(value)) return ServerMediaRef.canonical(value) ?: value
        if (!value.startsWith("file://")) return value
        return uploadAvatar(value, personaId)
    }

    private fun publish(phase: ServerBootstrapPhase, mode: ServerBootstrapMode, tally: Tally) {
        val current = _progress.value
        _progress.value = current.copy(
            phase = phase,
            mode = mode,
            personas = tally.personas.coerceAtLeast(if (current.mode == mode) current.personas else 0),
            sessions = tally.sessions.coerceAtLeast(if (current.mode == mode) current.sessions else 0),
            messages = tally.messages.coerceAtLeast(if (current.mode == mode) current.messages else 0)
        )
    }

    /**
     * 服务端设置里的 `activePersonaId` 指向服务端人格；本地主键可能是客户端 UUID
     * （bootstrap 推送过的本机人格），写回 DataStore 前要换成本地 id，否则「当前人格」丢失。
     */
    private suspend fun localizeSettings(values: JsonObject): JsonObject {
        val serverPersonaId = values.string("activePersonaId")?.takeIf { it.isNotBlank() } ?: return values
        val localPersonaId = store.localPersonaId(serverPersonaId) ?: return values
        if (localPersonaId == serverPersonaId) return values
        return JsonObject(values + ("activePersonaId" to JsonPrimitive(localPersonaId)))
    }

    private fun normalize(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    /** 失败原因只保留「哪一类」：不带地址、令牌、聊天正文。 */
    private fun describe(error: Throwable?): String? {
        if (error == null) return null
        val code = (error as? ServerApiException)?.code
        return code ?: error.javaClass.simpleName
    }

    private class ServerBootstrapFailure(message: String) : IllegalStateException(message)

    private companion object {
        /** 失败最多试 3 次（含第一次）；仍失败就留给下一次前台/启动。 */
        const val MAX_ATTEMPTS = 3

        /** 指数退避：1s、2s（bootstrap 比增量同步重，退避长一点）。 */
        val DEFAULT_RETRY_DELAYS = listOf(1_000L, 2_000L)

        /** 单会话最多翻多少块（防服务端 `hasMoreOlder` 永远为 true）。 */
        const val MAX_BLOCKS_PER_SESSION = 100_000
    }
}
