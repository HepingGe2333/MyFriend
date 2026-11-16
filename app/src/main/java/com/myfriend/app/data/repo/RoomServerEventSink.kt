package com.myfriend.app.data.repo

import androidx.room.withTransaction
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MessageRole
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.remote.ServerEventApplied
import com.myfriend.app.data.remote.ServerEventChange
import com.myfriend.app.data.remote.ServerEventSink
import com.myfriend.app.data.remote.ServerLocalStore
import com.myfriend.app.data.remote.ServerMergePlan
import com.myfriend.app.data.remote.ServerPresenceStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 增量事件 → Room 缓存（M4）。
 *
 * 与同步快照的分工：快照负责「打开 App 时补齐」，事件负责「开着 App 时立刻出现」。
 * 两条路都写同一批表，所以新主动消息不会出现「通知弹了、聊天页没有」。
 *
 * `message.created` 的判定：
 * - 只缓存服务端**已有会话**的消息（会话不存在说明快照还没到，下一轮同步会补齐）；
 * - assistant 的新消息要更新预览、未读数并触发通知；user（另一台设备发的）只落库；
 * - 通知文案不在这里拼：这里给 [ServerEventApplied] 的字段，由通知层决定前台/当前会话是否打扰。
 */
@Singleton
class RoomServerEventSink @Inject constructor(
    private val database: MyFriendDatabase,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val blockWriter: MessageBlockWriter,
    private val personaDao: PersonaDao,
    private val presenceStore: ServerPresenceStore,
    private val localStore: ServerLocalStore
) : ServerEventSink {

    override suspend fun apply(change: ServerEventChange): ServerEventApplied = when (change) {
        is ServerEventChange.MessageCreated -> applyMessage(change)
        is ServerEventChange.MoodChanged -> {
            applyMood(change.mood.personaId, change.mood.label, change.mood.intensity, change.mood.updatedAt)
            ServerEventApplied()
        }

        is ServerEventChange.PresenceChanged -> {
            presenceStore.save(change.presence)
            ServerEventApplied()
        }

        is ServerEventChange.Unknown -> ServerEventApplied()
    }

    private suspend fun applyMessage(change: ServerEventChange.MessageCreated): ServerEventApplied {
        val server = change.message
        if (server.id.isBlank() || server.sessionId.isBlank()) return ServerEventApplied()
        // 事件里的 sessionId 是服务端 id；本地缓存可能复用同一人格的本机会话（id 不同）。
        val localSessionId = localStore.localSessionId(server.sessionId) ?: server.sessionId
        val session = sessionDao.getById(localSessionId) ?: return ServerEventApplied()
        val blockIndex = server.blockIndex.coerceAtLeast(0)
        val local = messageDao.getById(server.id)
        val entity = ServerMergePlan.message(server, session.personaId, local)
            .copy(sessionId = session.id)

        database.withTransaction {
            blockWriter.applyServerBlock(session.id, blockIndex, listOf(entity))
            val lastAt = session.lastMessageAt ?: 0L
            if (entity.createdAt > lastAt) {
                sessionDao.updatePreview(
                    id = session.id,
                    preview = ServerMergePlan.previewOf(entity.content),
                    at = entity.createdAt
                )
            }
            if (entity.role == MessageRole.ASSISTANT) {
                sessionDao.incrementUnread(session.id)
            }
        }

        return if (entity.role == MessageRole.ASSISTANT) {
            ServerEventApplied(
                notifySessionId = session.id,
                notifyPersonaId = session.personaId,
                notifyText = entity.content
            )
        } else {
            ServerEventApplied()
        }
    }

    private suspend fun applyMood(personaId: String, label: String, intensity: Int, updatedAt: Long) {
        if (personaId.isBlank()) return
        val persona = personaDao.getById(personaId) ?: return
        if (!ServerMergePlan.shouldOverwrite(persona.moodUpdatedAt, updatedAt)) return
        runCatching {
            personaDao.setMood(
                id = personaId,
                mood = label.ifBlank { persona.mood },
                intensity = intensity.coerceIn(1, 5).takeIf { intensity > 0 } ?: persona.moodIntensity,
                reason = persona.moodReason,
                now = updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
            )
        }
    }
}
