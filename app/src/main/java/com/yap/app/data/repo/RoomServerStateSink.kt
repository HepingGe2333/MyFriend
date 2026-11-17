package com.yap.app.data.repo

import androidx.room.withTransaction
import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.YapDatabase
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.remote.ServerAttachmentRef
import com.yap.app.data.remote.ServerMergePlan
import com.yap.app.data.remote.ServerLocalStore
import com.yap.app.data.remote.ServerMediaMaterializeResult
import com.yap.app.data.remote.ServerMediaRef
import com.yap.app.data.remote.ServerMediaStore
import com.yap.app.data.remote.ServerMessagePage
import com.yap.app.data.remote.ServerPersona
import com.yap.app.data.remote.ServerPresence
import com.yap.app.data.remote.ServerPresenceStore
import com.yap.app.data.remote.ServerStateSink
import com.yap.app.data.remote.StateSnapshot
import com.yap.app.data.remote.ServerSyncApplyResult
import com.yap.app.data.remote.serverAttachmentRefs
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端快照/分页 → Room 缓存（M4）。
 *
 * 设计取舍：
 *
 * 1. **一个事务**：快照里人格/会话/消息/心情要么一起可见，要么都不可见。否则首页可能
 *    出现「会话有了、点进去消息还没有」的瞬间，用户会以为聊天记录丢了；
 * 2. **块编号以服务端为准**：消息落库走 [MessageBlockWriter.applyServerBlock]，
 *    不再按本地 200 条规则重新分配——两端对「第几块」必须一致，否则前翻会错位；
 * 3. **presence 不进 Room**：它是易失展示缓存，写 DataStore（[ServerPresenceStore]），
 *    且不占 Room 事务（DataStore 与 Room 是两个独立的写者，塞在同一个事务里没有意义）；
 * 4. **幂等**：人格/会话按 id upsert 并做「updatedAt 新才覆盖」；消息按 id REPLACE；
 * 5. **媒体先落盘再落库**（0.13）：附件在事务**之前**用 [ServerMediaStore.materialize] 落到
 *    `files/media|avatars|stickers|audio|video`，事务里写本地 `file://`。下载失败只计数并
 *    保留服务端引用——一条图片拉不下来，不该让整批文字消息不同步。
 *
 * 这不是「把服务端数据搬成本地数据」：聊天页仍然读 Room，所以本地模式的代码路径一行没改，
 * 只是服务端模式把 Room 当缓存来填。
 */
@Singleton
class RoomServerStateSink @Inject constructor(
    private val database: YapDatabase,
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val blockWriter: MessageBlockWriter,
    private val presenceStore: ServerPresenceStore,
    private val localStore: ServerLocalStore,
    private val mediaStore: ServerMediaStore
) : ServerStateSink {

    override suspend fun applySnapshot(snapshot: StateSnapshot): ServerSyncApplyResult {
        val sessionsById = snapshot.sessions.associateBy { it.id }
        val previewBySession = snapshot.messages
            .groupBy { it.sessionId }
            .mapValues { (_, messages) ->
                messages.maxWithOrNull(compareBy({ it.blockIndex }, { it.createdAt }, { it.id }))
                    ?.let { ServerMergePlan.previewOf(it.content) }
            }

        // 媒体在事务外落盘：网络/写盘都可能慢，不能占着 Room 的写事务。
        val media = materializeQuietly(snapshotMediaRefs(snapshot))
        val localAvatars = personaAvatarsQuietly(snapshot.personas)

        var personas = 0
        var sessions = 0
        var messages = 0
        var moods = 0
        // 服务端会话 id → 本地缓存会话 id（见 ServerLocalStore 的说明：本地一人一段是唯一索引）。
        val localIds = mutableMapOf<String, String>()

        database.withTransaction {
            // ---- 人格：服务端只拥有 name/updatedAt，其余本地字段原样保留 ----
            snapshot.personas.forEach { server ->
                if (server.id.isBlank()) return@forEach
                // bootstrap 推送过的人格：本地主键是客户端 UUID，服务端是它分配的 id。
                // 不先查映射就会在本地多插一个「同名的他」（会话/消息又会挂到新行上）。
                val mappedLocalId = localStore.localPersonaId(server.id)
                val local = personaDao.getById(mappedLocalId ?: server.id)
                val merged = ServerMergePlan.persona(
                    local = local,
                    server = server,
                    localAvatarUri = localAvatars[server.id]
                ).let { entity ->
                    if (local != null) entity.copy(id = local.id) else entity
                }
                if (local == null) {
                    personaDao.insert(merged)
                    personas++
                } else if (merged != local) {
                    personaDao.update(merged)
                    personas++
                }
                if (merged.id != server.id) localStore.setPersonaMapping(server.id, merged.id)
            }

            // ---- 会话：服务端拥有 title/未读/最后消息时间 ----
            snapshot.sessions.forEach { server ->
                if (server.id.isBlank()) return@forEach
                val local = findLocalSession(server)
                val merged = ServerMergePlan.session(
                    local = local,
                    server = server,
                    preview = previewBySession[server.id]
                )
                if (local == null) {
                    sessionDao.insert(merged)
                    sessions++
                } else if (merged != local) {
                    sessionDao.update(merged)
                    sessions++
                }
                localIds[server.id] = merged.id
                if (merged.id != server.id) localStore.setSessionMapping(server.id, merged.id)
            }

            // ---- 消息：按 (本地会话 id, blockIndex) 分组写，块元数据由 writer 重算 ----
            snapshot.messages
                .filter { it.id.isNotBlank() && it.sessionId.isNotBlank() }
                .groupBy { message ->
                    (localIds[message.sessionId]
                        ?: localStore.localSessionId(message.sessionId)
                        ?: message.sessionId) to message.blockIndex.coerceAtLeast(0)
                }
                .forEach { (key, serverMessages) ->
                    val (localSessionId, blockIndex) = key
                    val cachedSession = sessionDao.getById(localSessionId) ?: return@forEach
                    val personaId = cachedSession.personaId
                    val localById = messageDao.blockOf(localSessionId, blockIndex).associateBy { it.id }
                    val entities = serverMessages.map { server ->
                        ServerMergePlan.message(server, personaId, localById[server.id], media.media)
                            .copy(sessionId = localSessionId)
                    }
                    blockWriter.applyServerBlock(localSessionId, blockIndex, entities)
                    messages += entities.size
                }

            // ---- 心情：服务端推进；本地更晚的心情不被旧快照覆盖 ----
            snapshot.mood?.takeIf { it.personaId.isNotBlank() }?.let { mood ->
                val localPersonaId = localPersonaFor(mood.personaId)
                val local = personaDao.getById(localPersonaId) ?: return@let
                if (ServerMergePlan.shouldOverwrite(local.moodUpdatedAt, mood.updatedAt)) {
                    // 0.13：/v1/state 的 mood 带三维（valence/arousal/dominance），一起写进本地缓存；
                    // 提示词与离线 UI 读的是同一组列，不能只同步标签。
                    personaDao.setMoodVector(
                        id = localPersonaId,
                        mood = mood.label.ifBlank { local.mood },
                        intensity = mood.intensity.coerceIn(1, 5).takeIf { mood.intensity > 0 } ?: local.moodIntensity,
                        reason = mood.reason.ifBlank { local.moodReason },
                        valence = mood.valence,
                        arousal = mood.arousal,
                        dominance = mood.dominance,
                        now = mood.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                    moods++
                }
            }
        }

        var presence = 0
        snapshot.presence?.takeIf { it.personaId.isNotBlank() }?.let { state ->
            presenceStore.save(state.copy(personaId = localPersonaFor(state.personaId)))
            presence = 1
        }

        return ServerSyncApplyResult(
            personas = personas,
            sessions = sessions,
            messages = messages,
            moods = moods,
            presence = presence,
            mediaDownloaded = media.downloaded,
            mediaFailed = media.failed,
            mediaSkipped = media.skipped
        )
    }

    override suspend fun applyPage(page: ServerMessagePage, personaId: String?): ServerSyncApplyResult {
        if (page.sessionId.isBlank()) return ServerSyncApplyResult()
        val localSessionId = sessionDao.getById(page.sessionId)?.id
            ?: localStore.localSessionId(page.sessionId)
            ?: page.sessionId
        val cachedSession = sessionDao.getById(localSessionId) ?: return ServerSyncApplyResult()
        val messages = page.messages.filter { it.id.isNotBlank() }
        if (messages.isEmpty()) return ServerSyncApplyResult()
        val resolvedPersona = personaId?.takeIf { it.isNotBlank() } ?: cachedSession.personaId

        // 媒体先落盘（事务外）：同一批里同 id 只下一次；失败只计数，不阻断文字消息。
        val localByBlock = messages
            .groupBy { it.blockIndex.coerceAtLeast(0) }
            .mapValues { (blockIndex, _) ->
                messageDao.blockOf(localSessionId, blockIndex).associateBy { it.id }
            }
        val media = materializeQuietly(
            messages.flatMap { server ->
                serverAttachmentRefs(
                    attachments = server.attachments,
                    localJson = localByBlock[server.blockIndex.coerceAtLeast(0)]
                        ?.get(server.id)?.attachmentsJson,
                    personaId = resolvedPersona
                )
            }
        )

        var count = 0
        database.withTransaction {
            messages
                .groupBy { it.blockIndex.coerceAtLeast(0) }
                .forEach { (blockIndex, serverMessages) ->
                    val localById = localByBlock[blockIndex].orEmpty()
                    val entities = serverMessages.map { server ->
                        ServerMergePlan.message(server, resolvedPersona, localById[server.id], media.media)
                            .copy(sessionId = localSessionId)
                    }
                    blockWriter.applyServerBlock(localSessionId, blockIndex, entities)
                    count += entities.size
                }
            // 分页翻到的是新块时，顺手把会话预览推到最新消息（会话列表用的是它）。
            val newest = messages.maxWithOrNull(compareBy({ it.blockIndex }, { it.createdAt }, { it.id }))
            if (newest != null) {
                val session = sessionDao.getById(localSessionId)
                val currentLast = session?.lastMessageAt ?: 0L
                if (newest.createdAt > currentLast) {
                    sessionDao.updatePreview(
                        id = localSessionId,
                        preview = ServerMergePlan.previewOf(newest.content),
                        at = newest.createdAt
                    )
                }
            }
        }
        return ServerSyncApplyResult(
            messages = count,
            sessions = 1,
            mediaDownloaded = media.downloaded,
            mediaFailed = media.failed,
            mediaSkipped = media.skipped
        )
    }

    /**
     * 快照里所有服务端消息的附件 → 待落盘清单。
     *
     * 本地会话 id 在事务外先解析一遍（只用于找到「本机已有附件元数据」，找不到就是空，
     * 靠字节 magic 判定即可）；事务里仍按自己的映射重新查一次，两者不一致也不影响落盘：
     * 落盘结果按服务端引用索引，与本地会话 id 无关。
     */
    private suspend fun snapshotMediaRefs(snapshot: StateSnapshot): List<ServerAttachmentRef> {
        val refs = ArrayList<ServerAttachmentRef>()
        snapshot.messages
            .filter { it.id.isNotBlank() && it.sessionId.isNotBlank() && it.attachments.isNotEmpty() }
            .groupBy { it.sessionId }
            .forEach { (serverSessionId, sessionMessages) ->
                val localSessionId = resolveLocalSessionId(serverSessionId)
                val personaId = snapshot.sessions.firstOrNull { it.id == serverSessionId }?.personaId.orEmpty()
                sessionMessages
                    .groupBy { it.blockIndex.coerceAtLeast(0) }
                    .forEach { (blockIndex, blockMessages) ->
                        val localById = messageDao.blockOf(localSessionId, blockIndex).associateBy { it.id }
                        blockMessages.forEach { server ->
                            refs += serverAttachmentRefs(
                                attachments = server.attachments,
                                localJson = localById[server.id]?.attachmentsJson,
                                personaId = personaId
                            )
                        }
                    }
            }
        return refs
    }

    /**
     * 人格头像落盘（`avatarUrl` 是 `/v1/media/<id>` 时）。
     *
     * 本机已经有真实头像文件就不下（离线也能看，也避免每次同步都问服务端要同一张图）；
     * 返回「服务端人格 id → 本地 `file://`」，失败的人格不出现（保留服务端引用）。
     */
    private suspend fun personaAvatarsQuietly(personas: List<ServerPersona>): Map<String, String> {
        if (personas.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        personas.forEach { server ->
            try {
                val url = server.avatarUrl?.trim().orEmpty()
                if (url.isEmpty() || !ServerMediaRef.isMediaRef(url)) return@forEach
                val localId = localStore.localPersonaId(server.id) ?: server.id
                val current = personaDao.getById(localId)?.avatarUri
                // 本地已是真文件（file:// 且不是服务端引用）：保留，不下载。
                if (!current.isNullOrBlank() && !ServerMediaRef.isMediaRef(current)) return@forEach
                val localUri = mediaStore.ensureLocalAvatar(server.id, url) ?: return@forEach
                if (localUri.startsWith("file://")) out[server.id] = localUri
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // 头像落盘失败：保留服务端引用，界面继续走带鉴权的网络加载。
            }
        }
        return out
    }

    /** 媒体落盘不抛：取消照常上抛，其它异常都退化成「这批没有本地化结果」。 */
    private suspend fun materializeQuietly(refs: List<ServerAttachmentRef>): ServerMediaMaterializeResult {
        if (refs.isEmpty()) return ServerMediaMaterializeResult()
        return try {
            mediaStore.materialize(refs)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            ServerMediaMaterializeResult()
        }
    }

    /** 服务端会话 id → 本机会话 id（事务外的近似解析，只用于查本机已有附件元数据）。 */
    private suspend fun resolveLocalSessionId(serverSessionId: String): String =
        sessionDao.getById(serverSessionId)?.id
            ?: localStore.localSessionId(serverSessionId)?.let { mapped -> sessionDao.getById(mapped)?.id ?: mapped }
            ?: serverSessionId

    /**
     * 找到服务端会话在本地的那一行：
     * 1. 同 id（服务端模式里新建的缓存）；
     * 2. 之前记过映射的本地会话；
     * 3. 同一人格的本机会话（一人一段唯一索引下只能复用它，并把映射记下来）。
     */
    private suspend fun findLocalSession(server: com.yap.app.data.remote.ServerSession) =
        sessionDao.getById(server.id)
            ?: localStore.localSessionId(server.id)?.let { sessionDao.getById(it) }
            ?: sessionDao.latestForPersona(localPersonaFor(server.personaId))

    /** 服务端人格 id → 本地人格 id（bootstrap 推送后两者可能不同）。 */
    private suspend fun localPersonaFor(serverPersonaId: String): String =
        localStore.localPersonaId(serverPersonaId) ?: serverPersonaId
}
