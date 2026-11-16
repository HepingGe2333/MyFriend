package com.myfriend.app.data.remote

import com.myfriend.app.data.local.ChatSessionEntity
import com.myfriend.app.data.local.MessageEntity
import com.myfriend.app.data.local.MessageRole
import com.myfriend.app.data.local.MessageStatus
import com.myfriend.app.data.local.PersonaEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 同步要用的三个服务端读接口；抽接口让 `ServerSyncManager` 能纯 JVM 测。 */
interface ServerStateSource {
    suspend fun state(since: Long): StateSnapshot
    suspend fun latestPage(sessionId: String): ServerMessagePage
}

/** 一次快照/一页消息落库之后的计数（日志与测试断言用）。 */
data class ServerSyncApplyResult(
    val personas: Int = 0,
    val sessions: Int = 0,
    val messages: Int = 0,
    val moods: Int = 0,
    val presence: Int = 0
) {
    operator fun plus(other: ServerSyncApplyResult): ServerSyncApplyResult = ServerSyncApplyResult(
        personas = personas + other.personas,
        sessions = sessions + other.sessions,
        messages = messages + other.messages,
        moods = moods + other.moods,
        presence = presence + other.presence
    )
}

/**
 * 快照/分页的落库入口。
 *
 * 生产实现是 `RoomServerSyncSink`（单事务写 Room）；测试用内存假实现，
 * 这样「增量合并、重复消息幂等、服务端更新覆盖本地」可以先在纯 JVM 里钉住规则，
 * 而不是只能在模拟器上验证。
 */
interface ServerStateSink {
    suspend fun applySnapshot(snapshot: StateSnapshot): ServerSyncApplyResult
    suspend fun applyPage(page: ServerMessagePage, personaId: String? = null): ServerSyncApplyResult
}

/**
 * 一次同步的结果：成功/失败、走到哪个游标、试了几次。
 *
 * [cursor] 是下一次 `/v1/state?since=` 用的**时间戳游标**；[eventCursor] 是
 * `/v1/events?since=` 用的单调事件游标（两者语义不同，见 [ServerLocalStore.stateCursor]）。
 */
data class ServerSyncOutcome(
    val success: Boolean,
    val cursor: Long,
    val attempts: Int,
    val applied: ServerSyncApplyResult = ServerSyncApplyResult(),
    val eventCursor: Long = 0L,
    val error: Throwable? = null
)

/**
 * 状态同步（M4 基础）：`state(since=lastCursor)` → 事务内落 Room → 前进游标。
 *
 * 规则（`docs/design/server-authoritative.md` §3.1 / §6）：
 *
 * 1. **首次 `since=0`**：服务端只给每个会话的最新一块；随后按会话调分页接口补齐这一块
 *    （老消息由聊天页继续按块前翻，不在启动时全量拉）；
 * 2. **游标只进不退**：写入 [ServerLocalStore.setCursor] 的是 `max(旧游标, serverCursor)`；
 *    空轮询/服务端重启都不能把指针拨回去，否则会重复处理旧事件；
 * 3. **失败静默重试**：指数退避、最多 [MAX_ATTEMPTS] 次；仍失败就安静返回 failure，
 *    由下一次前台/启动再试（服务端不可达不该让 App 弹错）；
 * 4. **幂等**：消息按 id upsert，重复同步同一块不会变成两条（幂等由 sink 保证，
 *    这里只负责「同一份快照可能被送进去」）。
 */
class ServerSyncManager(
    private val source: ServerStateSource,
    private val sink: ServerStateSink,
    private val localStore: ServerLocalStore,
    private val retryDelaysMillis: List<Long> = DEFAULT_RETRY_DELAYS,
    private val delayFn: suspend (Long) -> Unit = { delay(it) }
) {

    private val mutex = Mutex()

    /**
     * 同步一次。
     *
     * 并发调用会串行化：启动与回前台可能同时触发，没必要把同一份快照拉两遍。
     */
    suspend fun syncNow(): ServerSyncOutcome = mutex.withLock { syncWithRetry() }

    private suspend fun syncWithRetry(): ServerSyncOutcome {
        var lastError: Throwable? = null
        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            attempts++
            try {
                val result = syncOnce()
                return ServerSyncOutcome(
                    success = true,
                    cursor = result.nextStateCursor,
                    attempts = attempts,
                    applied = result.applied,
                    eventCursor = result.eventCursor
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                lastError = error
                if (attempts >= MAX_ATTEMPTS) break
                val wait = retryDelaysMillis.getOrElse(attempts - 1) { DEFAULT_RETRY_DELAYS.last() }
                if (wait > 0) delayFn(wait)
            }
        }
        return ServerSyncOutcome(
            success = false,
            cursor = runCatching { localStore.stateCursor() }.getOrDefault(0L),
            eventCursor = runCatching { localStore.cursor() }.getOrDefault(0L),
            attempts = attempts,
            error = lastError
        )
    }

    private data class SyncResult(
        val applied: ServerSyncApplyResult,
        val nextStateCursor: Long,
        val eventCursor: Long
    )

    private suspend fun syncOnce(): SyncResult {
        // `/v1/state` 的 since 是时间戳；`/v1/events` 的游标是另一套单调计数，不能混用。
        val stateCursor = localStore.stateCursor()
        val snapshot = source.state(stateCursor)
        var applied = sink.applySnapshot(snapshot)

        // 首次同步：快照每个会话只给最新一块。补齐这一块，聊天页打开时首屏就不用等网络。
        if (stateCursor == 0L) {
            snapshot.sessions
                .filter { it.lastBlockIndex >= 0 }
                .forEach { session ->
                    val page = runCatching { source.latestPage(session.id) }.getOrNull() ?: return@forEach
                    applied += sink.applyPage(page, session.personaId)
                }
        }

        // 事件游标：给 `/v1/events` 用（只进不退）。
        val eventCursor = maxOf(localStore.cursor(), snapshot.serverCursor)
        localStore.setCursor(eventCursor)

        // 状态游标：取快照里所有「比上次新」的时间戳的最大值。agentd 的 `/v1/state`
        // 按 `updatedAt > since` / `createdAt > since` 过滤，传事件游标会退化成全量同步。
        val nextStateCursor = maxOf(
            stateCursor,
            snapshot.serverTime,
            snapshot.personas.maxOfOrNull { it.updatedAt } ?: 0L,
            snapshot.sessions.maxOfOrNull { it.updatedAt } ?: 0L,
            snapshot.messages.maxOfOrNull { it.createdAt } ?: 0L
        )
        localStore.setStateCursor(nextStateCursor)
        return SyncResult(applied = applied, nextStateCursor = nextStateCursor, eventCursor = eventCursor)
    }

    companion object {
        /** 失败最多试 3 次（含第一次）。 */
        const val MAX_ATTEMPTS = 3

        /** 指数退避：0.5s、1s；第 3 次失败就交给下一次前台同步。 */
        val DEFAULT_RETRY_DELAYS = listOf(500L, 1_000L)
    }
}

/**
 * 服务端 DTO → 本地实体/更新的纯规则（无 Room、无 Android）。
 *
 * 冲突口径：**服务端 `updatedAt` 更新则覆盖本地；否则保留本地**（§6）。消息没有独立的
 * `updatedAt`，按 id upsert：服务端是唯一真相源，同一 id 的内容以服务端为准；本地未发送的
 * 草稿不在 `messages` 表里（它在待发队列/输入框），所以不会被同步覆盖。
 */
object ServerMergePlan {

    /** 服务端更新时间严格更新才算「新」。相等时保留本地，避免每次同步都无意义地重写。 */
    fun shouldOverwrite(localUpdatedAt: Long?, serverUpdatedAt: Long): Boolean =
        localUpdatedAt == null || serverUpdatedAt > localUpdatedAt

    /**
     * 人设合并。
     *
     * 服务端快照只带 `id/name/avatarUrl/updatedAt`，**不能**用一份精简 DTO 覆盖本地完整人设：
     * 那会把世界预设、话风、翻译缓存、心情全部清空。所以只更新服务端真正拥有的两个展示字段
     * （名字、更新时间），本地多出来的字段原样保留。
     */
    fun persona(local: PersonaEntity?, server: ServerPersona, now: Long = System.currentTimeMillis()): PersonaEntity {
        if (local != null && !shouldOverwrite(local.updatedAt, server.updatedAt)) return local
        if (local != null) {
            return local.copy(
                name = server.name.ifBlank { local.name },
                // 本机没有头像文件、服务端有一个媒体引用时补上：换设备/服务端新建的人格
                // 不该在首页是一块没有脸的色块。本机已有头像时保留本地文件（离线也能看）。
                avatarUri = local.avatarUri?.takeIf { it.isNotBlank() } ?: server.avatarUrl,
                updatedAt = maxOf(local.updatedAt, server.updatedAt)
            )
        }
        return PersonaEntity(
            id = server.id,
            name = server.name,
            description = "",
            avatarUri = server.avatarUrl,
            avatarSeed = server.id.hashCode(),
            prompt = "",
            category = DEFAULT_SERVER_PERSONA_CATEGORY,
            version = 1,
            defaultApiConfigId = null,
            modelOverride = null,
            isDefault = false,
            createdAt = now,
            updatedAt = server.updatedAt.takeIf { it > 0 } ?: now
        )
    }

    /**
     * 会话合并。
     *
     * 本地字段（绑定的 API 配置、模型、置顶、摘要进度…）在服务端模式下没有意义，但也不能
     * 被快照清掉：切回本地模式时要能继续用。所以合并时以本地行为底、只覆盖服务端拥有的列。
     */
    fun session(
        local: ChatSessionEntity?,
        server: ServerSession,
        preview: String? = null,
        now: Long = System.currentTimeMillis()
    ): ChatSessionEntity {
        if (local != null && !shouldOverwrite(local.updatedAt, server.updatedAt)) return local
        val base = local ?: ChatSessionEntity(
            id = server.id,
            personaId = server.personaId,
            apiConfigId = null,
            model = "",
            title = server.title,
            lastMessagePreview = null,
            lastMessageAt = null,
            unreadCount = 0,
            pinned = false,
            createdAt = server.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = server.updatedAt.takeIf { it > 0 } ?: now,
            lastPersonaVersion = 1,
            lastMemoryExtractedCount = 0
        )
        return base.copy(
            personaId = server.personaId.ifBlank { base.personaId },
            title = server.title.ifBlank { base.title },
            lastMessagePreview = preview ?: base.lastMessagePreview,
            lastMessageAt = server.lastMessageAt ?: base.lastMessageAt,
            unreadCount = server.unread.coerceAtLeast(0),
            updatedAt = maxOf(base.updatedAt, server.updatedAt)
        )
    }

    /** 服务端消息 → 本地实体；本地已有同 id 时以服务端内容为准（含已读时间）。 */
    fun message(server: ServerMessage, personaId: String, local: MessageEntity?): MessageEntity {
        val createdAt = server.createdAt.takeIf { it > 0 } ?: local?.createdAt ?: System.currentTimeMillis()
        return MessageEntity(
            id = server.id,
            sessionId = server.sessionId,
            personaId = personaId.ifBlank { local?.personaId.orEmpty() },
            role = when (ServerMessageRole.from(server.role)) {
                ServerMessageRole.USER -> MessageRole.USER
                ServerMessageRole.ASSISTANT -> MessageRole.ASSISTANT
                ServerMessageRole.SYSTEM -> MessageRole.SYSTEM
                // 未知角色落成 SYSTEM：既不冒充用户的话，也不会在界面里被当成他的人设回复。
                ServerMessageRole.UNKNOWN -> MessageRole.SYSTEM
            },
            content = server.content,
            createdAt = createdAt,
            updatedAt = local?.updatedAt ?: createdAt,
            status = MessageStatus.COMPLETE,
            errorMessage = null,
            model = local?.model,
            edited = local?.edited ?: false,
            // 媒体文件本身还没同步（M3 只做文本缓存）：保留 id，界面加载失败时显示占位，
            // 而不是把「他发过一张图」这件事悄悄丢掉。
            attachmentsJson = attachmentsJson(server.attachments) ?: local?.attachmentsJson,
            toolTraceJson = local?.toolTraceJson,
            readAt = server.readAt ?: local?.readAt,
            blockIndex = server.blockIndex.coerceAtLeast(0)
        )
    }

    /** 预览：单行、最多 [ServerChatSync.PREVIEW_MAX_CHARS] 字（与首页映射同一口径）。 */
    fun previewOf(content: String): String {
        val singleLine = content.replace('\n', ' ').replace('\r', ' ').trim()
        return if (singleLine.length <= ServerChatSync.PREVIEW_MAX_CHARS) {
            singleLine
        } else {
            singleLine.take(ServerChatSync.PREVIEW_MAX_CHARS) + "…"
        }
    }

    private fun attachmentsJson(attachments: List<String>): String? {
        if (attachments.isEmpty()) return null
        return JsonArray(
            attachments.map { id -> buildJsonObject { put("uri", JsonPrimitive(id)) } }
        ).toString()
    }

    /**
     * 本地人设表里服务端新建人格的默认分类。
     *
     * 用内置分类而不是空串：人格列表按分类筛选时，空串会掉进「未分类」那个假分类里。
     */
    const val DEFAULT_SERVER_PERSONA_CATEGORY = "日常聊天"
}

/** [ServerApi] → [ServerStateSource] 的适配器（传输层不反向依赖同步层）。 */
class ServerApiStateSource(private val api: ServerApi) : ServerStateSource {
    override suspend fun state(since: Long): StateSnapshot = api.state(since)

    override suspend fun latestPage(sessionId: String): ServerMessagePage = api.messages(sessionId)
}
