package com.myfriend.app.data.repo

import androidx.room.withTransaction
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.remote.ServerMergePlan
import com.myfriend.app.data.remote.ServerLocalStore
import com.myfriend.app.data.remote.ServerMessagePage
import com.myfriend.app.data.remote.ServerPresence
import com.myfriend.app.data.remote.ServerPresenceStore
import com.myfriend.app.data.remote.ServerStateSink
import com.myfriend.app.data.remote.StateSnapshot
import com.myfriend.app.data.remote.ServerSyncApplyResult
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
 * 4. **幂等**：人格/会话按 id upsert 并做「updatedAt 新才覆盖」；消息按 id REPLACE。
 *
 * 这不是「把服务端数据搬成本地数据」：聊天页仍然读 Room，所以本地模式的代码路径一行没改，
 * 只是服务端模式把 Room 当缓存来填。
 */
@Singleton
class RoomServerStateSink @Inject constructor(
    private val database: MyFriendDatabase,
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val blockWriter: MessageBlockWriter,
    private val presenceStore: ServerPresenceStore,
    private val localStore: ServerLocalStore
) : ServerStateSink {

    override suspend fun applySnapshot(snapshot: StateSnapshot): ServerSyncApplyResult {
        val sessionsById = snapshot.sessions.associateBy { it.id }
        val previewBySession = snapshot.messages
            .groupBy { it.sessionId }
            .mapValues { (_, messages) ->
                messages.maxWithOrNull(compareBy({ it.blockIndex }, { it.createdAt }, { it.id }))
                    ?.let { ServerMergePlan.previewOf(it.content) }
            }

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
                val local = personaDao.getById(server.id)
                val merged = ServerMergePlan.persona(local, server)
                if (local == null) {
                    personaDao.insert(merged)
                    personas++
                } else if (merged != local) {
                    personaDao.update(merged)
                    personas++
                }
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
                        ServerMergePlan.message(server, personaId, localById[server.id])
                            .copy(sessionId = localSessionId)
                    }
                    blockWriter.applyServerBlock(localSessionId, blockIndex, entities)
                    messages += entities.size
                }

            // ---- 心情：服务端推进；本地更晚的心情不被旧快照覆盖 ----
            snapshot.mood?.takeIf { it.personaId.isNotBlank() }?.let { mood ->
                val local = personaDao.getById(mood.personaId) ?: return@let
                if (ServerMergePlan.shouldOverwrite(local.moodUpdatedAt, mood.updatedAt)) {
                    personaDao.setMood(
                        id = mood.personaId,
                        mood = mood.label.ifBlank { local.mood },
                        intensity = mood.intensity.coerceIn(1, 5).takeIf { mood.intensity > 0 } ?: local.moodIntensity,
                        reason = local.moodReason,
                        now = mood.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                    moods++
                }
            }
        }

        var presence = 0
        snapshot.presence?.takeIf { it.personaId.isNotBlank() }?.let {
            presenceStore.save(it)
            presence = 1
        }

        return ServerSyncApplyResult(
            personas = personas,
            sessions = sessions,
            messages = messages,
            moods = moods,
            presence = presence
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
        var count = 0
        database.withTransaction {
            messages
                .groupBy { it.blockIndex.coerceAtLeast(0) }
                .forEach { (blockIndex, serverMessages) ->
                    val localById = messageDao.blockOf(localSessionId, blockIndex).associateBy { it.id }
                    val entities = serverMessages.map { server ->
                        ServerMergePlan.message(server, resolvedPersona, localById[server.id])
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
        return ServerSyncApplyResult(messages = count, sessions = 1)
    }

    /**
     * 找到服务端会话在本地的那一行：
     * 1. 同 id（服务端模式里新建的缓存）；
     * 2. 之前记过映射的本地会话；
     * 3. 同一人格的本机会话（一人一段唯一索引下只能复用它，并把映射记下来）。
     */
    private suspend fun findLocalSession(server: com.myfriend.app.data.remote.ServerSession) =
        sessionDao.getById(server.id)
            ?: localStore.localSessionId(server.id)?.let { sessionDao.getById(it) }
            ?: sessionDao.latestForPersona(server.personaId)
}
