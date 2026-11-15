package com.myfriend.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.myfriend.app.data.local.AgentIntentDao
import com.myfriend.app.data.local.AgentTickDao
import com.myfriend.app.data.local.ApiConfigDao
import com.myfriend.app.data.local.ApiUsageDao
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MemoryConsolidationDao
import com.myfriend.app.data.local.MemoryDao
import com.myfriend.app.data.local.MemoryEchoDao
import com.myfriend.app.data.local.MemoryTermDao
import com.myfriend.app.data.local.MemoryVectorDao
import com.myfriend.app.data.local.MessageBlockDao
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
import kotlinx.coroutines.runBlocking
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
    private val blockDao: MessageBlockDao,
    /** 块表没有自己的擦除枚举：它跟着 `messages` 一起删（同一个分支里）。 */
    private val blockWriter: MessageBlockWriter,
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
    private val apiUsageDao: ApiUsageDao,
    private val memoryReconsolidator: MemoryReconsolidator
) : ErasureStore {

    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }

    override suspend fun delete(table: ErasableTable, personaId: String?) {
        when (table) {
            ErasableTable.MESSAGES -> {
                // v11：`message_blocks` 是 messages 的派生元数据（用户可读的原文只有 messages），
                // 所以它不单独进 `ErasableTable`（那份清单在 :agentcore），而是**跟着 messages
                // 在同一个分支里删**。按人删时块表要靠 chat_sessions 反查 personaId，
                // 因此必须在 SESSIONS 之前执行（ErasurePlan 的顺序正好如此）。
                if (personaId == null) blockWriter.deleteAll() else blockWriter.deletePersona(personaId)
            }

            ErasableTable.SESSIONS -> {
                if (personaId == null) sessionDao.deleteAll() else sessionDao.deleteByPersona(personaId)
                // 兜底：会话已经不存在、块行还在（异常导入/中断的事务）时回收孤儿块。
                blockDao.deleteOrphans()
            }

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

            ErasableTable.API_USAGE ->
                // 流水按人删：sessionId 是「按人看用量」的依据，personaId 也为空的历史行
                // 只会在整库清空时一起走。这里只支持整库/按人两种口径，与其它表一致。
                if (personaId == null) apiUsageDao.deleteAll() else apiUsageDao.deleteByPersona(personaId)

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
 * ## 0.12：引用守卫
 *
 * 具体删除由 [MediaStorage] 的**带守卫**版本完成（归属 + 引用）；`DataEraser` 保证这些方法
 * 都在**数据库事务提交之后**才被调用，正好满足「先删行、再查引用」的顺序。
 *
 * `ErasureFiles` 是 `:agentcore` 里的非挂起接口（两端共用，本轮不改它），所以这里用
 * `runBlocking` 把挂起的引用查询接进同步方法：阻塞点是用户显式发起的
 * 「清空聊天与记忆 / 删一个人 / 备份替换」，一次操作只查一批；比起「删掉用户另一段对话里的图」
 * 这点阻塞是划算的。（Room 的挂起查询会切到查询线程，不会因为调用线程是主线程而死锁。）
 *
 * `clearAllMedia` 是「删除全部数据」专用（整棵媒体目录），此刻所有引用它的行都已经删掉，
 * 保持整目录删除的语义即可。
 */
@Singleton
class AndroidErasureFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaReferences: MediaReferences
) : ErasureFiles {

    override fun clearChatImages(): Int = runBlocking {
        MediaStorage.clearChatImagesExceptIfUnreferenced(context, emptySet()) { names ->
            mediaReferences.referencedNames(names)
        }
    }

    override fun clearAllMedia(): Int = MediaStorage.clear(context)

    override fun deleteChatImages(uris: List<String>): Int = runBlocking {
        MediaStorage.deleteFilesIfUnreferenced(context, uris) { names ->
            mediaReferences.referencedNames(names)
        }
    }

    override fun deleteAvatar(uri: String?): Int = runBlocking {
        MediaStorage.deleteIfUnreferenced(context, uri) { names ->
            mediaReferences.referencedNames(names)
        }
    }

    override fun clearUnreferencedAvatars(keep: Set<String>): Int = runBlocking {
        MediaStorage.clearUnreferencedAvatarsIfUnreferenced(context, keep) { names ->
            mediaReferences.referencedNames(names)
        }
    }
}
