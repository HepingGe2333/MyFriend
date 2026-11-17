package com.yap.app.data.repo

import androidx.room.withTransaction
import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.ChatSessionEntity
import com.yap.app.data.local.MemoryDao
import com.yap.app.data.local.MemoryEntity
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageEntity
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPresetDao
import com.yap.app.data.local.PersonaPresetEntity
import com.yap.app.data.local.YapDatabase
import com.yap.app.data.prefs.AppSettings
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.remote.ServerBootstrapCounts
import com.yap.app.data.remote.ServerBootstrapLocal
import com.yap.app.data.remote.ServerLocalStore
import com.yap.app.data.remote.ServerMediaRef
import com.yap.app.data.remote.ServerMessagePage
import com.yap.app.data.remote.ServerStateSink
import com.yap.app.data.remote.StateSnapshot
import com.yap.app.domain.AttachmentsCodec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * bootstrap 的本地读写实现（Room + DataStore）。
 *
 * 只做「取/放」：四象限判定、分批、冲突口径、id 映射都在 [com.yap.app.data.remote.ServerBootstrapManager]。
 * 这样规则可以在纯 JVM 单测里用内存假实现跑，这里只负责 SQL。
 *
 * 几个刻意的选择：
 * - **消息按会话懒读**：大库上 `messages()` 全量物化会直接 OOM；bootstrap 逐个会话搬；
 * - **人格/预设/记忆用 REPLACE**：调用方已按 `updatedAt` 决定「谁赢」；
 * - **预设换 id 在同一事务里**：先删旧行再插新行，中间不存在「两个副本」的瞬间。
 */
@Singleton
class RoomServerBootstrapLocal @Inject constructor(
    private val database: YapDatabase,
    private val personaDao: PersonaDao,
    private val presetDao: PersonaPresetDao,
    private val memoryDao: MemoryDao,
    private val sessionDao: ChatSessionDao,
    private val messageDao: MessageDao,
    private val settingsRepository: SettingsRepository,
    private val stateSink: ServerStateSink,
    private val localStore: ServerLocalStore
) : ServerBootstrapLocal {

    override suspend fun counts(): ServerBootstrapCounts = ServerBootstrapCounts(
        localPersonas = personaDao.count(),
        localSessions = sessionDao.getAll().size,
        localMessages = messageDao.countAll()
    )

    override suspend fun personas(): List<PersonaEntity> = personaDao.getAll()

    override suspend fun presets(): List<PersonaPresetEntity> = presetDao.getAll()

    override suspend fun memories(personaId: String?): List<MemoryEntity> =
        if (personaId.isNullOrBlank()) {
            // bootstrap 不会在「全部记忆」上调用它；给一个空列表比全表扫描安全。
            emptyList()
        } else {
            memoryDao.getAllForPersona(personaId)
        }

    override suspend fun sessions(): List<ChatSessionEntity> = sessionDao.getAll()

    override suspend fun messages(localSessionId: String): List<MessageEntity> =
        messageDao.getBySession(localSessionId)

    override suspend fun settings(): AppSettings = settingsRepository.settings.first()

    override suspend fun applySnapshot(snapshot: StateSnapshot) {
        stateSink.applySnapshot(snapshot)
    }

    override suspend fun applyPage(page: ServerMessagePage, personaId: String?) {
        stateSink.applyPage(page, personaId)
    }

    override suspend fun applyPersonas(personas: List<PersonaEntity>) {
        if (personas.isEmpty()) return
        database.withTransaction {
            personas.forEach { persona ->
                if (persona.id.isBlank()) return@forEach
                val local = personaDao.getById(persona.id)
                // 调用方已按 updatedAt 决定方向；这里只避免「服务端旧数据把本地新行写回去」。
                if (local == null) {
                    personaDao.insert(persona)
                } else if (persona.updatedAt >= local.updatedAt) {
                    personaDao.update(persona)
                }
            }
        }
    }

    override suspend fun applyPreset(preset: PersonaPresetEntity) {
        if (preset.id.isBlank()) return
        presetDao.insert(preset)
    }

    override suspend fun replacePresetId(localId: String, preset: PersonaPresetEntity) {
        if (localId.isBlank() || preset.id.isBlank() || localId == preset.id) {
            applyPreset(preset)
            return
        }
        database.withTransaction {
            presetDao.deleteById(localId)
            presetDao.insert(preset)
        }
    }

    override suspend fun applyMemory(memory: MemoryEntity) {
        if (memory.id.isBlank()) return
        memoryDao.insert(memory)
    }

    override suspend fun applySettings(values: JsonObject) {
        settingsRepository.applyServerSettings(values)
    }

    override suspend fun applyUserAvatar(serverRef: String?, localRef: String?) {
        settingsRepository.setUserAvatar(serverRef, localRef)
    }

    /**
     * 历史媒体迁移的进度分母：本地附件里「还是本地文件」的条数。
     *
     * 已经是 `/v1/media/<id>` 的附件不需要再上传，不能算进分母，否则进度条永远差一截。
     * 这里只解析窄列 JSON，不把整条消息读进内存；孤儿会话的附件也会被算进来
     * （宁可分母略大，也不为它再扫一遍会话映射）。
     */
    override suspend fun attachmentTotal(): Int =
        messageDao.allAttachmentJson().sumOf { json ->
            AttachmentsCodec.decode(json).count { !ServerMediaRef.isMediaRef(it.uri) }
        }

    override suspend fun messageCount(localSessionId: String): Int =
        if (localSessionId.isBlank()) -1 else messageDao.countInSession(localSessionId)

    override suspend fun setSyncCursors(stateCursor: Long, eventCursor: Long) {
        if (eventCursor > 0L) localStore.setCursor(eventCursor)
        if (stateCursor > 0L) localStore.setStateCursor(stateCursor)
    }
}
