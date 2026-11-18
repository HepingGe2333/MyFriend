package com.yap.app.data.remote

import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MemoryCategory
import com.yap.app.data.local.MemoryEntity
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.MessageStatus
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPresetEntity
import com.yap.app.data.prefs.AppSettings
import com.yap.app.domain.AttachmentsCodec
import com.yap.app.domain.MessageAttachment
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.13「切换云端同意 + 历史媒体 + 切回本地下载」单测用的假实现。
 *
 * 与 `ServerBootstrapTest` 里的假实现刻意分开：那边钉 0.12.1 的四象限/幂等，这边要覆盖
 * 「附件逐项上传」「NEW_ONLY 不推历史」「下载条数校验」这些新分支，混在一起会让两边都难读。
 */
internal class ConsentFakeRemote : ServerBootstrapRemote {
    val personas = mutableListOf<JsonObject>()
    val sessions = mutableListOf<ServerSession>()
    val presets = mutableListOf<JsonObject>()
    val settings = mutableMapOf<String, String>()
    val memories = mutableMapOf<String, MutableList<JsonObject>>()
    val blocks = mutableMapOf<String, MutableMap<Int, MutableList<ServerMessage>>>()

    var presetsSupported = true
    var memoriesSupported = true
    val importCalls = mutableListOf<Pair<String, List<ServerMessageImport>>>()
    val createdPersonas = mutableListOf<JsonObject>()
    val createdSessions = mutableListOf<String>()
    var pushedSettings: JsonObject? = null
    var pageBeforeCalls = 0
    var createdSessionCount = 0
    /** 观察「先上传后 import」的顺序（测试里往同一个事件列表里追加）。 */
    var onImport: ((String) -> Unit)? = null
    private val personaSeq = AtomicInteger(0)

    override suspend fun state(): StateSnapshot = StateSnapshot(
        serverTime = 1_000,
        personas = personas.map { ServerBootstrapMapping.personaSummary(it) },
        sessions = sessions.toList(),
        serverCursor = 7,
        messages = sessions.flatMap { latestPage(it.id).messages }
    )

    override suspend fun personas(): List<JsonObject> = personas.toList()

    override suspend fun sessions(): List<ServerSession> = sessions.toList()

    override suspend fun settings(): JsonObject = buildJsonObject {
        settings.forEach { (key, value) -> put(key, value) }
    }

    override suspend fun presets(): List<JsonObject>? =
        if (presetsSupported) presets.toList() else null

    override suspend fun memories(personaId: String): List<JsonObject>? =
        if (memoriesSupported) memories[personaId]?.toList().orEmpty() else null

    override suspend fun createPersona(payload: JsonObject): JsonObject {
        createdPersonas += payload
        val id = "srv-persona-${personaSeq.incrementAndGet()}"
        val stored = JsonObject(
            payload + mapOf("id" to JsonPrimitive(id), "updatedAt" to JsonPrimitive(2_000))
        )
        personas += stored
        return stored
    }

    override suspend fun createSession(personaId: String, title: String?): ServerSession {
        sessions.firstOrNull { it.personaId == personaId }?.let { return it }
        val session = ServerSession(
            id = "srv-session-${++createdSessionCount}",
            personaId = personaId,
            title = title ?: "chat",
            updatedAt = 2_000,
            createdAt = 2_000
        )
        sessions += session
        createdSessions += session.id
        return session
    }

    override suspend fun importMessages(
        sessionId: String,
        messages: List<ServerMessageImport>
    ): Int {
        onImport?.invoke(sessionId)
        importCalls += sessionId to messages
        val block = blocks.getOrPut(sessionId) { mutableMapOf() }.getOrPut(0) { mutableListOf() }
        var imported = 0
        messages.forEach { message ->
            if (block.none { it.id == message.id }) {
                block += ServerMessage(
                    id = message.id,
                    sessionId = sessionId,
                    blockIndex = 0,
                    role = message.role,
                    content = message.content,
                    createdAt = message.createdAt,
                    readAt = message.readAt,
                    attachments = message.attachments
                )
                imported++
            }
        }
        sessions.replaceAll {
            if (it.id == sessionId) {
                it.copy(messageCount = it.messageCount + imported, lastBlockIndex = 0)
            } else {
                it
            }
        }
        return imported
    }

    override suspend fun latestPage(sessionId: String): ServerMessagePage {
        val blocks = blocks[sessionId].orEmpty()
        val newest = blocks.keys.maxOrNull() ?: return ServerMessagePage(sessionId = sessionId)
        return page(sessionId, newest)
    }

    override suspend fun pageBefore(sessionId: String, beforeBlock: Int): ServerMessagePage {
        pageBeforeCalls++
        val blocks = blocks[sessionId].orEmpty()
        val previous = blocks.keys.filter { it < beforeBlock }.maxOrNull()
            ?: return ServerMessagePage(sessionId = sessionId)
        return page(sessionId, previous)
    }

    override suspend fun savePreset(payload: JsonObject): JsonObject {
        val id = payload["id"]?.let { (it as? JsonPrimitive)?.content }
            ?: "srv-preset-${presets.size + 1}"
        val stored = JsonObject(
            payload + mapOf("id" to JsonPrimitive(id), "updatedAt" to JsonPrimitive(2_000))
        )
        val index = presets.indexOfFirst { (it["id"] as? JsonPrimitive)?.content == id }
        if (index >= 0) presets[index] = stored else presets += stored
        return stored
    }

    override suspend fun saveMemory(personaId: String, payload: JsonObject): JsonObject {
        val list = memories.getOrPut(personaId) { mutableListOf() }
        val id = payload["id"]?.let { (it as? JsonPrimitive)?.content }
            ?: "srv-memory-${list.size + 1}-$personaId"
        val stored = JsonObject(
            payload + mapOf("id" to JsonPrimitive(id), "updatedAt" to JsonPrimitive(2_000))
        )
        val index = list.indexOfFirst { (it["id"] as? JsonPrimitive)?.content == id }
        if (index >= 0) list[index] = stored else list += stored
        return stored
    }

    override suspend fun pushSettings(values: JsonObject) {
        pushedSettings = values
        values.forEach { (key, value) ->
            (value as? JsonPrimitive)?.content?.let { settings[key] = it }
        }
    }

    fun seedBlock(sessionId: String, blockIndex: Int, messages: List<ServerMessage>) {
        blocks.getOrPut(sessionId) { mutableMapOf() }[blockIndex] = messages.toMutableList()
        sessions.replaceAll {
            if (it.id == sessionId) {
                it.copy(
                    lastBlockIndex = maxOf(it.lastBlockIndex, blockIndex),
                    messageCount = it.messageCount + messages.size
                )
            } else {
                it
            }
        }
    }

    private fun page(sessionId: String, blockIndex: Int): ServerMessagePage {
        val blocks = blocks[sessionId].orEmpty()
        return ServerMessagePage(
            sessionId = sessionId,
            blockIndex = blockIndex,
            hasMoreOlder = blocks.keys.any { it < blockIndex },
            messages = blocks[blockIndex].orEmpty()
        )
    }
}

/** 内存版 [ServerBootstrapLocal]：附件计数/逐会话条数都按真实数据算。 */
internal class ConsentFakeLocal(
    private val store: TestServerLocalStore
) : ServerBootstrapLocal {
    val personas = linkedMapOf<String, PersonaEntity>()
    val presets = linkedMapOf<String, PersonaPresetEntity>()
    val memories = mutableListOf<MemoryEntity>()
    val sessions = linkedMapOf<String, ChatSessionEntity>()
    val messages = mutableMapOf<String, MutableList<MessageEntity>>()
    var settings = AppSettings()
    var appliedSettings: JsonObject? = null
    var stateCursor = 0L
    var eventCursor = 0L

    /** 模拟「下载时写库失败」：条数校验必须因此失败（开关保持开启）。 */
    var dropIncomingPages = false

    override suspend fun counts(): ServerBootstrapCounts = ServerBootstrapCounts(
        localPersonas = personas.size,
        localSessions = sessions.size,
        localMessages = messages.values.sumOf { it.size }
    )

    override suspend fun personas(): List<PersonaEntity> = personas.values.toList()

    override suspend fun presets(): List<PersonaPresetEntity> = presets.values.toList()

    override suspend fun memories(personaId: String?): List<MemoryEntity> =
        if (personaId == null) memories.toList() else memories.filter { it.personaId == personaId }

    override suspend fun sessions(): List<ChatSessionEntity> = sessions.values.toList()

    override suspend fun messages(localSessionId: String): List<MessageEntity> =
        messages[localSessionId].orEmpty().toList()

    override suspend fun settings(): AppSettings = settings

    override suspend fun applySnapshot(snapshot: StateSnapshot) {
        snapshot.personas.forEach { summary ->
            if (summary.id.isBlank()) return@forEach
            val localId = store.localPersonaId(summary.id) ?: summary.id
            if (personas[localId] == null) {
                personas[localId] = PersonaEntity(
                    id = localId,
                    name = summary.name,
                    description = "",
                    avatarUri = summary.avatarUrl,
                    avatarSeed = 0,
                    prompt = "",
                    category = ServerMergePlan.DEFAULT_SERVER_PERSONA_CATEGORY,
                    version = 1,
                    defaultApiConfigId = null,
                    modelOverride = null,
                    isDefault = false,
                    createdAt = summary.updatedAt,
                    updatedAt = summary.updatedAt
                )
            }
            store.setPersonaMapping(summary.id, localId)
        }
        snapshot.sessions.forEach { server ->
            val localId = store.localSessionId(server.id)
                ?: sessions.values
                    .firstOrNull {
                        it.personaId == (store.localPersonaId(server.personaId) ?: server.personaId)
                    }
                    ?.id
                ?: server.id
            if (sessions[localId] == null) {
                sessions[localId] = ChatSessionEntity(
                    id = localId,
                    personaId = store.localPersonaId(server.personaId) ?: server.personaId,
                    apiConfigId = null,
                    model = "",
                    title = server.title,
                    lastMessagePreview = null,
                    lastMessageAt = server.lastMessageAt,
                    unreadCount = server.unread,
                    pinned = false,
                    createdAt = server.createdAt,
                    updatedAt = server.updatedAt,
                    lastPersonaVersion = 1,
                    lastMemoryExtractedCount = 0
                )
            }
            store.setSessionMapping(server.id, localId)
        }
    }

    override suspend fun applyPage(page: ServerMessagePage, personaId: String?) {
        if (page.messages.isEmpty() || dropIncomingPages) return
        val localSessionId = store.localSessionId(page.sessionId) ?: page.sessionId
        val list = messages.getOrPut(localSessionId) { mutableListOf() }
        page.messages.forEach { server ->
            val entity = ServerMergePlan
                .message(server, personaId.orEmpty(), list.firstOrNull { it.id == server.id })
                .copy(sessionId = localSessionId)
            list.removeAll { it.id == entity.id }
            list += entity
        }
    }

    override suspend fun applyPersonas(personas: List<PersonaEntity>) {
        personas.forEach { persona ->
            val local = this.personas[persona.id]
            if (local == null || persona.updatedAt >= local.updatedAt) {
                this.personas[persona.id] = persona
            }
        }
    }

    override suspend fun applyPreset(preset: PersonaPresetEntity) {
        presets[preset.id] = preset
    }

    override suspend fun replacePresetId(localId: String, preset: PersonaPresetEntity) {
        presets.remove(localId)
        presets[preset.id] = preset
    }

    override suspend fun applyMemory(memory: MemoryEntity) {
        memories.removeAll { it.id == memory.id }
        memories += memory
    }

    override suspend fun applySettings(values: JsonObject) {
        appliedSettings = values
    }

    override suspend fun applyUserAvatar(serverRef: String?, localRef: String?) {
        settings = settings.copy(userAvatarUri = serverRef, userAvatarLocalUri = localRef)
    }

    override suspend fun setSyncCursors(stateCursor: Long, eventCursor: Long) {
        this.stateCursor = stateCursor
        this.eventCursor = eventCursor
    }

    override suspend fun attachmentTotal(): Int = messages.values.sumOf { list ->
        list.sumOf { message ->
            AttachmentsCodec.decode(message.attachmentsJson).count { !ServerMediaRef.isMediaRef(it.uri) }
        }
    }

    override suspend fun messageCount(localSessionId: String): Int = messages[localSessionId]?.size ?: 0

    fun addMessage(sessionId: String, message: MessageEntity) {
        messages.getOrPut(sessionId) { mutableListOf() } += message
        if (sessions[sessionId] == null) {
            sessions[sessionId] = ChatSessionEntity(
                id = sessionId,
                personaId = message.personaId,
                apiConfigId = null,
                model = "m",
                title = sessionId,
                lastMessagePreview = null,
                lastMessageAt = message.createdAt,
                unreadCount = 0,
                pinned = false,
                createdAt = message.createdAt,
                updatedAt = message.createdAt,
                lastPersonaVersion = 1,
                lastMemoryExtractedCount = 0
            )
        }
    }
}

/** 内存版开关控制（不写密钥存储）。 */
internal class ConsentFakeMode(
    override var enabled: Boolean = false,
    override var usable: Boolean = true,
    override var configuredUrl: String = "http://server.test"
) : ServerModeControl {
    var setEnabledFailure: Boolean = false
    val enabledCalls = mutableListOf<Boolean>()

    override fun setEnabled(enabled: Boolean): Result<Unit> {
        enabledCalls += enabled
        if (setEnabledFailure) return Result.failure(IllegalStateException("keystore unavailable"))
        this.enabled = enabled
        return Result.success(Unit)
    }
}

/** 内存版同步 runner：记录调用，结果/delays 可注入。 */
internal class ConsentFakeRunner : ServerBootstrapRunner {
    var bootstrapOutcome: ServerBootstrapOutcome = ServerBootstrapOutcome(
        success = true,
        mode = ServerBootstrapMode.PUSH,
        messages = 3,
        mediaTotal = 2,
        mediaUploaded = 2
    )
    var downloadOutcome: ServerFullDownloadOutcome =
        ServerFullDownloadOutcome(success = true, downloadedMessages = 4, localMessages = 4, serverMessages = 4)
    var onBootstrap: (suspend (String) -> Unit)? = null
    var lastOutcomeValue: ServerBootstrapOutcome? = null
    val bootstrapCalls = mutableListOf<String>()
    val downloadCalls = mutableListOf<String>()

    override suspend fun runBootstrap(baseUrl: String): ServerBootstrapOutcome {
        bootstrapCalls += baseUrl
        onBootstrap?.invoke(baseUrl)
        // 与生产一致：skip 的那次没有真实计数，保留上一轮的结果。
        if (!bootstrapOutcome.skipped) lastOutcomeValue = bootstrapOutcome
        return bootstrapOutcome
    }

    override suspend fun runFullDownload(baseUrl: String): ServerFullDownloadOutcome {
        downloadCalls += baseUrl
        onBootstrap?.invoke(baseUrl)
        return downloadOutcome
    }

    override fun latestOutcome(baseUrl: String): ServerBootstrapOutcome? = lastOutcomeValue
}

internal fun consentPersona(id: String, name: String = id, updatedAt: Long = 100) = PersonaEntity(
    id = id,
    name = name,
    description = "",
    avatarUri = null,
    avatarSeed = 0,
    prompt = "prompt",
    category = "日常聊天",
    version = 1,
    defaultApiConfigId = null,
    modelOverride = null,
    isDefault = false,
    createdAt = 10,
    updatedAt = updatedAt
)

internal fun consentSession(id: String, personaId: String) = ChatSessionEntity(
    id = id,
    personaId = personaId,
    apiConfigId = null,
    model = "m",
    title = "和${personaId}的对话",
    lastMessagePreview = null,
    lastMessageAt = 100,
    unreadCount = 0,
    pinned = false,
    createdAt = 10,
    updatedAt = 100,
    lastPersonaVersion = 1,
    lastMemoryExtractedCount = 0
)

internal fun consentMessage(
    id: String,
    sessionId: String,
    personaId: String,
    attachments: List<MessageAttachment> = emptyList(),
    content: String = "content-$id",
    createdAt: Long = 100
) = MessageEntity(
    id = id,
    sessionId = sessionId,
    personaId = personaId,
    role = MessageRole.USER,
    content = content,
    createdAt = createdAt,
    updatedAt = createdAt,
    status = MessageStatus.COMPLETE,
    errorMessage = null,
    model = null,
    edited = false,
    attachmentsJson = AttachmentsCodec.encode(attachments),
    toolTraceJson = null,
    readAt = null,
    blockIndex = 0
)

internal fun consentMemory(id: String, personaId: String, content: String) = MemoryEntity(
    id = id,
    personaId = personaId,
    content = content,
    category = MemoryCategory.FACT,
    importance = 3,
    pinned = false,
    sourceSessionId = null,
    createdAt = 10,
    updatedAt = 20,
    lastUsedAt = 0,
    useCount = 1
)
