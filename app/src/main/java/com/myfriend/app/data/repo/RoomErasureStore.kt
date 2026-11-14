package com.myfriend.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.myfriend.app.data.local.AgentIntentDao
import com.myfriend.app.data.local.AgentTickDao
import com.myfriend.app.data.local.ApiConfigDao
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MemoryConsolidationDao
import com.myfriend.app.data.local.MemoryDao
import com.myfriend.app.data.local.MemoryEchoDao
import com.myfriend.app.data.local.MemoryTermDao
import com.myfriend.app.data.local.MemoryVectorDao
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaPendingPatchDao
import com.myfriend.app.data.local.PersonaPresetDao
import com.myfriend.app.data.local.PersonaRevisionDao
import com.myfriend.app.data.local.ProactiveRuleDao
import com.myfriend.app.domain.AttachmentsCodec
import com.myfriend.app.domain.ErasableTable
import com.myfriend.app.domain.ErasureFiles
import com.myfriend.app.domain.ErasureStore
import com.myfriend.app.util.MediaStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ErasureStore] 的 Room 实现：把 [ErasableTable] 映射到具体的 DAO 删除。
 *
 * 这是**唯一**知道「哪张表用哪个 DAO 删」的地方。四条删除路径（清空聊天与记忆 /
 * 删除全部数据 / 删人格 / 备份替换）都只通过 [com.myfriend.app.domain.DataEraser] 调它，
 * 所以以后加一张含用户原文的表，只需要在枚举与这里各补一行。
 *
 * 顺序由 `ErasurePlan` 决定：子表先删、父表后删；`memory_terms` / `memory_versions` /
 * `memory_vectors` 只挂在 `memoryId` 上，必须在 `memories` 之后按孤儿回收。
 */
@Singleton
class RoomErasureStore @Inject constructor(
    private val database: MyFriendDatabase,
    private val messageDao: MessageDao,
    private val sessionDao: ChatSessionDao,
    private val memoryDao: MemoryDao,
    private val memoryTermDao: MemoryTermDao,
    private val memoryVectorDao: MemoryVectorDao,
    private val memoryEchoDao: MemoryEchoDao,
    private val memoryConsolidationDao: MemoryConsolidationDao,
    private val agentIntentDao: AgentIntentDao,
    private val agentTickDao: AgentTickDao,
    private val pendingPatchDao: PersonaPendingPatchDao,
    private val revisionDao: PersonaRevisionDao,
    private val proactiveRuleDao: ProactiveRuleDao,
    private val presetDao: PersonaPresetDao,
    private val personaDao: PersonaDao,
    private val apiConfigDao: ApiConfigDao,
    private val memoryReconsolidator: MemoryReconsolidator
) : ErasureStore {

    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }

    override suspend fun delete(table: ErasableTable, personaId: String?) {
        when (table) {
            ErasableTable.MESSAGES ->
                if (personaId == null) messageDao.deleteAll() else messageDao.deleteByPersona(personaId)

            ErasableTable.SESSIONS ->
                if (personaId == null) sessionDao.deleteAll() else sessionDao.deleteByPersona(personaId)

            ErasableTable.MEMORY_ECHOES ->
                if (personaId == null) memoryEchoDao.deleteAll() else memoryEchoDao.deleteByPersona(personaId)

            ErasableTable.MEMORY_CONSOLIDATIONS ->
                if (personaId == null) {
                    memoryConsolidationDao.deleteAll()
                } else {
                    memoryConsolidationDao.deleteByPersona(personaId)
                }

            ErasableTable.MEMORIES ->
                if (personaId == null) memoryDao.deleteAll() else memoryDao.deleteByPersona(personaId)

            ErasableTable.MEMORY_TERMS ->
                // terms 只挂在 memoryId 上：整表清空直接 deleteAll，按人清则回收孤儿。
                if (personaId == null) memoryTermDao.deleteAll() else memoryTermDao.deleteOrphans()

            ErasableTable.MEMORY_VERSIONS ->
                // 写这张表的是 MemoryReconsolidator，删除入口也在它那里（同一类负责写与删）。
                memoryReconsolidator.purgeVersions(personaId)

            ErasableTable.MEMORY_VECTORS ->
                if (personaId == null) memoryVectorDao.deleteAll() else memoryVectorDao.deleteOrphans()

            ErasableTable.AGENT_TICKS ->
                if (personaId == null) agentTickDao.deleteAll() else agentTickDao.deleteByPersona(personaId)

            ErasableTable.AGENT_INTENTS ->
                if (personaId == null) agentIntentDao.deleteAll() else agentIntentDao.deleteByPersona(personaId)

            ErasableTable.PERSONA_PENDING_PATCHES ->
                if (personaId == null) pendingPatchDao.deleteAll() else pendingPatchDao.deleteByPersona(personaId)

            ErasableTable.PERSONA_REVISIONS ->
                if (personaId == null) revisionDao.deleteByPersonaAll() else revisionDao.deleteByPersona(personaId)

            ErasableTable.PROACTIVE_RULES ->
                if (personaId == null) proactiveRuleDao.deleteAll() else proactiveRuleDao.deleteByPersona(personaId)

            ErasableTable.PERSONA_PRESETS ->
                // 预设库不是「某个人」的派生数据：只在整库清空/替换时清。
                presetDao.deleteAll()

            ErasableTable.PERSONAS ->
                if (personaId == null) personaDao.deleteAll() else personaDao.deleteById(personaId)

            ErasableTable.API_CONFIGS ->
                // 配置从不按人删：Key 是本机的，删人格不该连带删掉服务商配置。
                apiConfigDao.deleteAllApiConfigs()
        }
    }

    override suspend fun attachmentsOfPersona(personaId: String): List<String> =
        runCatching {
            messageDao.getByPersona(personaId)
                .flatMap { message -> AttachmentsCodec.decode(message.attachmentsJson).map { it.uri } }
        }.getOrDefault(emptyList())

    override suspend fun avatarUriOf(personaId: String): String? =
        runCatching { personaDao.getById(personaId)?.avatarUri }.getOrNull()
}

/**
 * [ErasureFiles] 的 Android 实现：所有文件操作都落在 `files/media` 与 `files/avatars`。
 *
 * 具体删除仍由 [MediaStorage] 做，那里有「只删自己目录」的守卫
 * （见 [com.myfriend.app.domain.MediaOwnership]）。
 */
@Singleton
class AndroidErasureFiles @Inject constructor(
    @ApplicationContext private val context: Context
) : ErasureFiles {

    override fun clearChatImages(): Int = MediaStorage.clearChatImages(context)

    override fun clearAllMedia(): Int = MediaStorage.clear(context)

    override fun deleteChatImages(uris: List<String>): Int = MediaStorage.deleteFiles(context, uris)

    override fun deleteAvatar(uri: String?): Int = MediaStorage.deleteAvatar(context, uri)

    override fun clearUnreferencedAvatars(keep: Set<String>): Int =
        MediaStorage.clearUnreferencedAvatars(context, keep)
}
