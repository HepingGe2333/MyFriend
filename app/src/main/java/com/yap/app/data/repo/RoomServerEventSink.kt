package com.yap.app.data.repo

import androidx.room.withTransaction
import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.YapDatabase
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.remote.ServerAttachmentRef
import com.yap.app.data.remote.ServerEventApplied
import com.yap.app.data.remote.ServerEventChange
import com.yap.app.data.remote.ServerEventSink
import com.yap.app.data.remote.ServerLocalStore
import com.yap.app.data.remote.ServerMediaMaterializeResult
import com.yap.app.data.remote.ServerMediaStore
import com.yap.app.data.remote.ServerMergePlan
import com.yap.app.data.remote.ServerMood
import com.yap.app.data.remote.ServerPresenceStore
import com.yap.app.data.remote.serverAttachmentRefs
import kotlinx.coroutines.CancellationException
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
 *
 * 0.13 起附件同样先落盘（事务前）再写库：实时收到的新消息里的图片/语音/视频要在本地能直接
 * 渲染/播放，而不是等下一次全量同步；落盘失败保留服务端引用，事件本身照常入库。
 */
@Singleton
class RoomServerEventSink @Inject constructor(
    private val database: YapDatabase,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val blockWriter: MessageBlockWriter,
    private val personaDao: PersonaDao,
    private val presenceStore: ServerPresenceStore,
    private val localStore: ServerLocalStore,
    private val mediaStore: ServerMediaStore
) : ServerEventSink {

    override suspend fun apply(change: ServerEventChange): ServerEventApplied = when (change) {
        is ServerEventChange.MessageCreated -> applyMessage(change)
        is ServerEventChange.MoodChanged -> {
            applyMood(change.mood)
            ServerEventApplied()
        }

        is ServerEventChange.PresenceChanged -> {
            presenceStore.save(change.presence)
            ServerEventApplied()
        }

        // typing 是瞬时状态，由 `ServerEventPoller` 直接交给 `ServerTypingState`，不落库。
        is ServerEventChange.TypingStarted, is ServerEventChange.TypingStopped -> ServerEventApplied()

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
        // 媒体在事务外落盘：失败只计数（事件里没有计数字段，保持服务端引用即可）。
        val media = materializeQuietly(
            serverAttachmentRefs(
                attachments = server.attachments,
                localJson = local?.attachmentsJson,
                personaId = session.personaId
            )
        )
        val entity = ServerMergePlan.message(server, session.personaId, local, media.media)
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

    /** 媒体落盘不抛：取消照常上抛，其它异常退化成「这条消息没有本地化结果」。 */
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

    /**
     * `mood.changed` → 本地缓存（0.13：事件 payload 里带完整多维心情）。
     *
     * 与服务端快照走同一个写入口（`setMoodVector`）：只写标签的旧路径会让「事件先到、快照后到」
     * 的瞬间三维变回旧值，提示词读到的语气就和 UI 上的标签对不上。
     */
    private suspend fun applyMood(mood: ServerMood) {
        if (mood.personaId.isBlank()) return
        val persona = personaDao.getById(mood.personaId) ?: return
        if (!ServerMergePlan.shouldOverwrite(persona.moodUpdatedAt, mood.updatedAt)) return
        runCatching {
            personaDao.setMoodVector(
                id = mood.personaId,
                mood = mood.label.ifBlank { persona.mood },
                intensity = mood.intensity.coerceIn(1, 5).takeIf { mood.intensity > 0 } ?: persona.moodIntensity,
                reason = mood.reason.ifBlank { persona.moodReason },
                valence = mood.valence,
                arousal = mood.arousal,
                dominance = mood.dominance,
                now = mood.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
            )
        }
    }
}
