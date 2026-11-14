package com.myfriend.app.domain

/**
 * 「删干净」到底删哪些表。
 *
 * 0.10.2 之前，四条删除路径各自手写一遍清单，漏得各不相同：
 *
 * - `wipeAllData` 不删 `memory_versions`（记忆旧正文，只增不减）、`memory_echoes`
 *   （候选记忆的**原话**）、`memory_vectors`、`memory_consolidations`（整理摘要正文）、
 *   `agent_ticks`（他的思考留痕）、`agent_intents`、`persona_pending_patches`；
 * - `clearAllChatData` 连记忆派生表与 `files/media` 都不碰；
 * - `PersonaRepository.delete` 不清 `memory_versions` 与聊天图片文件；
 * - 备份「替换」不清 `agent_ticks` / `memory_vectors` / pending patches。
 *
 * 现在四条路径共享这张表：哪条路径清哪些表写在 [ErasurePlan.tables] 里，
 * 由 [DataEraser] 统一执行，测试用假 store 断言「操作后这些表为空」。
 *
 * 顺序有意义：**先子表、后父表**；`memory_terms` / `memory_versions` / `memory_vectors`
 * 只挂在 `memoryId` 上（没有外键），所以必须排在 `memories` 之后做孤儿回收。
 */
enum class ErasableTable(val tableName: String) {
    MESSAGES("messages"),
    SESSIONS("chat_sessions"),
    MEMORY_ECHOES("memory_echoes"),
    MEMORY_CONSOLIDATIONS("memory_consolidations"),
    MEMORIES("memories"),
    MEMORY_TERMS("memory_terms"),
    MEMORY_VERSIONS("memory_versions"),
    MEMORY_VECTORS("memory_vectors"),
    AGENT_TICKS("agent_ticks"),
    AGENT_INTENTS("agent_intents"),
    PERSONA_PENDING_PATCHES("persona_pending_patches"),
    PERSONA_REVISIONS("persona_revisions"),
    PROACTIVE_RULES("proactive_rules"),
    PERSONA_PRESETS("persona_presets"),
    PERSONAS("personas"),
    API_CONFIGS("api_configs")
}

/** 四条会删用户内容的路径。 */
enum class ErasurePath {
    /** 设置页「清空全部聊天与记忆」：人格、API 配置保留。 */
    CLEAR_CHATS,

    /** 设置页「删除全部本地数据」。 */
    WIPE_ALL,

    /** 首页删除一个联系人（按人格清）。 */
    DELETE_PERSONA,

    /** 备份「替换」导入：先清空本机，再照备份写回（API 配置从不删）。 */
    BACKUP_REPLACE
}

object ErasurePlan {

    /**
     * 每条路径要清的表（已按「子表 → 父表」排好）。
     *
     * 与 iOS `DataReset` 对齐后的当前口径：
     * - `CLEAR_CHATS`：聊天 + 记忆 + 所有记忆派生正文（回声/整理/版本/向量）+ 聊天图片文件；
     * - `WIPE_ALL`：上面全部 + 思考留痕/念头/待确认补丁/人格版本历史/主动节奏/人格预设/人格/API 配置
     *   + 全部媒体文件；
     * - `DELETE_PERSONA`：只动这个人的行（派生表按孤儿回收），并删他的聊天图片与头像文件；
     * - `BACKUP_REPLACE`：除 API 配置外的全部数据（含主动节奏与人格预设）+ 聊天图片文件。
     *
     * @see ErasableTable 的 KDoc 说明为什么 `API_CONFIGS` 永不参与「替换」。
     */
    fun tables(path: ErasurePath): List<ErasableTable> = when (path) {
        ErasurePath.CLEAR_CHATS -> listOf(
            ErasableTable.MESSAGES,
            ErasableTable.SESSIONS,
            ErasableTable.MEMORY_ECHOES,
            ErasableTable.MEMORY_CONSOLIDATIONS,
            ErasableTable.MEMORIES,
            ErasableTable.MEMORY_TERMS,
            ErasableTable.MEMORY_VERSIONS,
            ErasableTable.MEMORY_VECTORS
        )

        ErasurePath.WIPE_ALL -> listOf(
            ErasableTable.MESSAGES,
            ErasableTable.SESSIONS,
            ErasableTable.MEMORY_ECHOES,
            ErasableTable.MEMORY_CONSOLIDATIONS,
            ErasableTable.MEMORIES,
            ErasableTable.MEMORY_TERMS,
            ErasableTable.MEMORY_VERSIONS,
            ErasableTable.MEMORY_VECTORS,
            ErasableTable.AGENT_TICKS,
            ErasableTable.AGENT_INTENTS,
            ErasableTable.PERSONA_PENDING_PATCHES,
            ErasableTable.PERSONA_REVISIONS,
            ErasableTable.PROACTIVE_RULES,
            ErasableTable.PERSONA_PRESETS,
            ErasableTable.PERSONAS,
            ErasableTable.API_CONFIGS
        )

        ErasurePath.DELETE_PERSONA -> listOf(
            ErasableTable.MESSAGES,
            ErasableTable.SESSIONS,
            ErasableTable.MEMORY_ECHOES,
            ErasableTable.MEMORY_CONSOLIDATIONS,
            ErasableTable.MEMORIES,
            ErasableTable.MEMORY_TERMS,
            ErasableTable.MEMORY_VERSIONS,
            ErasableTable.MEMORY_VECTORS,
            ErasableTable.AGENT_TICKS,
            ErasableTable.AGENT_INTENTS,
            ErasableTable.PERSONA_PENDING_PATCHES,
            ErasableTable.PERSONA_REVISIONS,
            ErasableTable.PROACTIVE_RULES,
            ErasableTable.PERSONAS
        )

        ErasurePath.BACKUP_REPLACE -> listOf(
            ErasableTable.MESSAGES,
            ErasableTable.SESSIONS,
            ErasableTable.MEMORY_ECHOES,
            ErasableTable.MEMORY_CONSOLIDATIONS,
            ErasableTable.MEMORIES,
            ErasableTable.MEMORY_TERMS,
            ErasableTable.MEMORY_VERSIONS,
            ErasableTable.MEMORY_VECTORS,
            ErasableTable.AGENT_TICKS,
            ErasableTable.AGENT_INTENTS,
            ErasableTable.PERSONA_PENDING_PATCHES,
            ErasableTable.PERSONA_REVISIONS,
            ErasableTable.PROACTIVE_RULES,
            ErasableTable.PERSONA_PRESETS,
            ErasableTable.PERSONAS
        )
    }

    /** 这条路径是不是「只清一个人」。 */
    fun personaScoped(path: ErasurePath): Boolean = path == ErasurePath.DELETE_PERSONA
}

/**
 * 真正执行删除的接口。
 *
 * 生产实现直接打 Room DAO（`RoomErasureStore`）；单测用内存假实现，
 * 这样「操作后这张表空了」可以在纯 JVM 里断言，不需要 Robolectric。
 */
interface ErasureStore {

    /** 把一次删除收进一个数据库事务（测试里直接执行）。 */
    suspend fun <T> inTransaction(block: suspend () -> T): T

    /** 删除 [table]；[personaId] 为 null 时清空整表，否则只清这个人的行。 */
    suspend fun delete(table: ErasableTable, personaId: String?)

    /** 这个人的消息带的所有附件路径（删之前先取出来，事务里不做文件 IO）。 */
    suspend fun attachmentsOfPersona(personaId: String): List<String>

    /** 这个人格的头像路径（没有为 null）。 */
    suspend fun avatarUriOf(personaId: String): String?
}

/**
 * 文件清理接口。
 *
 * 拆成接口是为了能注入临时目录：契约测试要断言「媒体目录被清」，
 * 而不是在黑盒里猜 `filesDir` 长什么样。
 */
interface ErasureFiles {

    /** 只删聊天图片（`files/media/chat-*`），不动聊天背景图与头像。 */
    fun clearChatImages(): Int

    /** 删掉整个媒体目录（聊天图片、聊天背景、头像）——只有「删除全部数据」该用。 */
    fun clearAllMedia(): Int

    /** 按路径删聊天图片（删一个人的时候）。 */
    fun deleteChatImages(uris: List<String>): Int

    /** 按路径删头像（只删 `files/avatars/` 里的）。 */
    fun deleteAvatar(uri: String?): Int

    /**
     * 删掉 `files/avatars/` 里没有被 [keep] 引用的文件。
     *
     * 备份「替换」会丢掉旧人格，但备份里的 `avatarUri` 可能仍指向本机同一批文件；
     * 全删目录会把「刚接回来」的头像一起删掉，全不删又会留下旧头像。
     * 所以按「备份里引用到的 + 用户自己的头像」保留，其余当孤儿回收。
     */
    fun clearUnreferencedAvatars(keep: Set<String>): Int
}

/** 一次清理的结果，给日志和测试对账用。 */
data class ErasureReport(
    val path: ErasurePath,
    val tables: List<ErasableTable>,
    val personaId: String? = null,
    val filesCleared: Int = 0
)

/**
 * 四条删除路径的唯一实现。
 *
 * 设计要点：
 * 1. **清单只有一份**（[ErasurePlan]），加一张含用户原文的表只需要改那里；
 * 2. 表删除在一个事务里（生产实现是 Room `withTransaction`），文件删除在事务之外；
 * 3. 不抛「部分失败」：删不掉就整次失败，界面照实报错——隐私承诺不能假装成功。
 *
 * 这个类不依赖 Android/Hilt（因此住在 `:agentcore`，Android 与 `agentd` 共用同一份实现），
 * 由 DI 模块用 [ErasureStore] + [ErasureFiles] 组装。
 */
class DataEraser(
    private val store: ErasureStore,
    private val files: ErasureFiles
) {

    /** 「清空全部聊天与记忆」：人格与 API 配置保留，记忆派生表与聊天图片一起清。 */
    suspend fun clearChatsAndMemories(): ErasureReport =
        erase(ErasurePath.CLEAR_CHATS, personaId = null) {
            files.clearChatImages()
        }

    /** 「删除全部本地数据」：整库 + 全部媒体文件。 */
    suspend fun wipeAllData(): ErasureReport =
        erase(ErasurePath.WIPE_ALL, personaId = null) {
            files.clearAllMedia()
        }

    /**
     * 删除一个联系人（独立调用版，会自己开事务）：这个人的行 + 他的聊天图片 + 他的头像。
     *
     * 附件与头像路径会在删除之前取出来：消息删掉之后就查不到了。
     */
    suspend fun deletePersona(personaId: String): ErasureReport =
        store.inTransaction {
            deletePersonaInsideTransaction(personaId)
        }

    /**
     * 备份「替换」导入之前清空本机。
     *
     * 只清聊天图片：备份不携带附件（导入时已经按 warning 丢弃），旧图片留着纯属残留；
     * 头像与聊天背景不动——备份里的 `avatarUri` 可能就指向本机 `files/avatars` 里
     * 仍然有效的文件，先清目录会把「刚接回来」的头像一起删掉。
     *
     * **必须在调用方已有的事务里执行**（见 [_replaceForBackup]）：备份导入是
     * 「清空 + 写回」一个事务，清空再单独开事务会破坏原子性。
     */
    suspend fun replaceForBackupInsideTransaction(keepAvatarUris: Set<String> = emptySet()): ErasureReport =
        _replaceForBackup(keepAvatarUris)

    /**
     * 独立调用版（会自己开事务）。
     *
     * 给不便把整段逻辑放进一个事务的调用方用；备份导入走
     * [replaceForBackupInsideTransaction]。
     */
    suspend fun replaceForBackup(keepAvatarUris: Set<String> = emptySet()): ErasureReport =
        erase(ErasurePath.BACKUP_REPLACE, personaId = null) {
            files.clearChatImages() + files.clearUnreferencedAvatars(keepAvatarUris)
        }

    /**
     * 删一个联系人，**在调用方已有的事务里**执行（PersonaRepository 还要接着重置默认人格 /
     * activePersonaId，这些必须与删除同属一个事务）。
     */
    suspend fun deletePersonaInsideTransaction(personaId: String): ErasureReport {
        val attachments = store.attachmentsOfPersona(personaId)
        val avatar = store.avatarUriOf(personaId)
        return _deletePersona(personaId, attachments, avatar)
    }

    private suspend fun _replaceForBackup(keepAvatarUris: Set<String>): ErasureReport {
        val tables = ErasurePlan.tables(ErasurePath.BACKUP_REPLACE)
        tables.forEach { table -> store.delete(table, personaId = null) }
        val filesCleared = files.clearChatImages() + files.clearUnreferencedAvatars(keepAvatarUris)
        return ErasureReport(ErasurePath.BACKUP_REPLACE, tables, null, filesCleared)
    }

    private suspend fun _deletePersona(
        personaId: String,
        attachments: List<String>,
        avatar: String?
    ): ErasureReport {
        val tables = ErasurePlan.tables(ErasurePath.DELETE_PERSONA)
        tables.forEach { table -> store.delete(table, personaId) }
        val filesCleared = files.deleteChatImages(attachments) + files.deleteAvatar(avatar)
        return ErasureReport(ErasurePath.DELETE_PERSONA, tables, personaId, filesCleared)
    }

    private suspend fun erase(
        path: ErasurePath,
        personaId: String?,
        deleteFiles: (() -> Int)? = null
    ): ErasureReport {
        val tables = ErasurePlan.tables(path)
        store.inTransaction {
            tables.forEach { table -> store.delete(table, personaId) }
        }
        val filesCleared = deleteFiles?.invoke() ?: 0
        return ErasureReport(path, tables, personaId, filesCleared)
    }
}
