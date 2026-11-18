package com.yap.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY isDefault DESC, updatedAt DESC")
    fun observeAll(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas WHERE id = :id")
    fun observeById(id: String): Flow<PersonaEntity?>

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun getById(id: String): PersonaEntity?

    @Query("SELECT * FROM personas ORDER BY isDefault DESC, updatedAt DESC")
    suspend fun getAll(): List<PersonaEntity>

    @Query("SELECT COUNT(*) FROM personas")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(persona: PersonaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(personas: List<PersonaEntity>)

    @Update
    suspend fun update(persona: PersonaEntity)

    @Query("UPDATE personas SET isDefault = 0")
    suspend fun clearDefaults()

    @Query("UPDATE personas SET isDefault = 1, updatedAt = :now WHERE id = :id")
    suspend fun markDefault(id: String, now: Long)

    @Query("DELETE FROM personas WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 写入 / 清空人格提示词的翻译缓存（schema v14）。
     *
     * 刻意不改 `updatedAt`：翻译是后台按需做的事，不是用户改人格；
     * 让它搅乱首页排序（或让「最近改过」的含义变味）是错的。
     */
    @Query("UPDATE personas SET translationJson = :json, translationLang = :lang WHERE id = :id")
    suspend fun setTranslation(id: String, json: String?, lang: String?)

    @Query("UPDATE personas SET defaultApiConfigId = NULL WHERE defaultApiConfigId = :configId")
    suspend fun unbindApiConfig(configId: String)

    /** 记下此刻的心情；不清空 updatedAt，免得打乱列表顺序。 */
    @Query("UPDATE personas SET mood = :mood, moodIntensity = :intensity, moodReason = :reason, moodUpdatedAt = :now WHERE id = :id")
    suspend fun setMood(id: String, mood: String?, intensity: Int?, reason: String?, now: Long)

    /**
     * 多维心情写入（schema v17）：标签 / 强度 / 三观维度一起落库。
     *
     * 单独一条而不是改 [setMood] 的签名：老调用方（服务端同步、唤醒恢复）只知道标签与强度，
     * 它们不该被逼着猜维度；需要写维度的路径（`set_mood`、自动判定）走这一条。
     */
    @Query(
        "UPDATE personas SET mood = :mood, moodIntensity = :intensity, moodReason = :reason, " +
            "moodValence = :valence, moodArousal = :arousal, moodDominance = :dominance, " +
            "moodUpdatedAt = :now WHERE id = :id"
    )
    suspend fun setMoodVector(
        id: String,
        mood: String?,
        intensity: Int?,
        reason: String?,
        valence: Int?,
        arousal: Int?,
        dominance: Int?,
        now: Long
    )

    /** 睡觉前要不要开免打扰：由模型的 dnd / quiet_hours 动作写，用户没有入口。 */
    @Query("UPDATE personas SET dndOnSleep = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setDndOnSleep(id: String, enabled: Boolean, now: Long)

    @Query("UPDATE personas SET moodNudgedAt = :now WHERE id = :id")
    suspend fun markMoodNudged(id: String, now: Long)

    /**
     * 一次性迁移用：把这些人的 `sleepEnabled` 关掉（0.12 续 7）。
     *
     * 为什么按 id 批量而不是写一条大 `WHERE`：筛选条件（`SleepDefaultsMigration`）是纯函数、
     * 有单测；这里只负责把「确定要改的那几条」写下去，条件不会在 SQL 与 Kotlin 里各写一遍。
     * 不碰 `updatedAt`：迁移不是用户改人格，不该打乱首页排序。
     */
    @Query("UPDATE personas SET sleepEnabled = 0 WHERE id IN (:ids)")
    suspend fun disableSleepFor(ids: List<String>): Int

    /** 心情随时间淡下去：词、强度与三个维度一起衰减（维度由 v17 起才有）。 */
    @Query(
        "UPDATE personas SET mood = :mood, moodIntensity = :intensity, " +
            "moodValence = :valence, moodArousal = :arousal, moodDominance = :dominance WHERE id = :id"
    )
    suspend fun fadeMood(
        id: String,
        mood: String?,
        intensity: Int?,
        valence: Int?,
        arousal: Int?,
        dominance: Int?
    )

    /** 到点自动解除拉黑（schema v18）。返回值只用于日志/测试，不影响调用方。 */
    @Query(
        "UPDATE personas SET blockedUntil = NULL, blockReason = NULL " +
            "WHERE blockedUntil IS NOT NULL AND blockedUntil <= :now"
    )
    suspend fun clearExpiredBlocks(now: Long): Int

    /** 写入 / 清空还没生效的拉黑提议（JSON；10 分钟由读取方判断）。 */
    @Query("UPDATE personas SET blockProposalJson = :json WHERE id = :id")
    suspend fun setBlockProposal(id: String, json: String?)

    /** 确认拉黑：写到期时间与原因，同时清掉提议（一次提议只能确认一次）。 */
    @Query(
        "UPDATE personas SET blockedUntil = :until, blockReason = :reason, blockProposalJson = NULL " +
            "WHERE id = :id"
    )
    suspend fun applyBlock(id: String, until: Long, reason: String?): Int

    @Query("DELETE FROM personas")
    suspend fun deleteAll()
}

@Dao
interface PersonaRevisionDao {
    @Query("SELECT * FROM persona_revisions WHERE personaId = :personaId ORDER BY version DESC")
    fun observeByPersona(personaId: String): Flow<List<PersonaRevisionEntity>>

    @Query("SELECT * FROM persona_revisions WHERE personaId = :personaId ORDER BY version DESC")
    suspend fun getByPersona(personaId: String): List<PersonaRevisionEntity>

    @Query("SELECT * FROM persona_revisions WHERE id = :id")
    suspend fun getById(id: String): PersonaRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(revision: PersonaRevisionEntity)

    @Query("SELECT COUNT(*) FROM persona_revisions WHERE personaId = :personaId")
    suspend fun countByPersona(personaId: String): Int

    @Query("DELETE FROM persona_revisions WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM persona_revisions")
    suspend fun deleteByPersonaAll()
}

@Dao
interface ApiConfigDao {
    @Query("SELECT * FROM api_configs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ApiConfigEntity>>

    @Query("SELECT * FROM api_configs ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ApiConfigEntity>

    @Query("SELECT * FROM api_configs WHERE id = :id")
    fun observeById(id: String): Flow<ApiConfigEntity?>

    @Query("SELECT * FROM api_configs WHERE id = :id")
    suspend fun getById(id: String): ApiConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: ApiConfigEntity)

    @Update
    suspend fun update(config: ApiConfigEntity)

    @Query("DELETE FROM api_configs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM api_configs")
    suspend fun deleteAllApiConfigs()
}

@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY pinned DESC, COALESCE(lastMessageAt, createdAt) DESC")
    fun observeAll(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE personaId = :personaId ORDER BY pinned DESC, COALESCE(lastMessageAt, createdAt) DESC")
    fun observeByPersona(personaId: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    fun observeById(id: String): Flow<ChatSessionEntity?>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getById(id: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE personaId = :personaId ORDER BY COALESCE(lastMessageAt, createdAt) DESC LIMIT 1")
    suspend fun latestForPersona(personaId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE personaId = :personaId")
    suspend fun getByPersona(personaId: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions")
    suspend fun getAll(): List<ChatSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ChatSessionEntity)

    /**
     * 幂等插入：撞上 `index_chat_sessions_personaId_unique` 时**什么都不做**并返回 -1。
     *
     * 一人一段的唯一索引下，「先查后插」在两个调用方并发时必然有一方撞索引：
     * REPLACE 会把对方刚建的那一段整行删掉再插（消息就成了孤儿），而默认的 ABORT 会抛
     * `SQLiteConstraintException`，于是首页点联系人直接报错（审计 P2-6）。
     * 这里用 IGNORE：撞了就说明别人已经建好了，调用方回读一行即可。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(session: ChatSessionEntity): Long

    @Update
    suspend fun update(session: ChatSessionEntity)

    @Query("UPDATE chat_sessions SET unreadCount = 0 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE chat_sessions SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long)

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE chat_sessions SET lastMessagePreview = :preview, lastMessageAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun updatePreview(id: String, preview: String?, at: Long)

    @Query("UPDATE chat_sessions SET lastMessagePreview = :preview, lastMessageAt = :at, updatedAt = :now WHERE id = :id")
    suspend fun updatePreview(id: String, preview: String?, at: Long?, now: Long)

    @Query("UPDATE chat_sessions SET unreadCount = unreadCount + 1 WHERE id = :id")
    suspend fun incrementUnread(id: String)

    @Query("UPDATE chat_sessions SET apiConfigId = :configId, model = :model, updatedAt = :now WHERE id = :id")
    suspend fun bindConfig(id: String, configId: String, model: String, now: Long)

    @Query("UPDATE chat_sessions SET apiConfigId = :configId, updatedAt = :now WHERE personaId = :personaId AND (apiConfigId IS NULL OR apiConfigId = :oldConfigId)")
    suspend fun fillMissingConfigForPersona(personaId: String, configId: String, oldConfigId: String?, now: Long)

    @Query("UPDATE chat_sessions SET apiConfigId = NULL, updatedAt = :now WHERE apiConfigId = :configId")
    suspend fun detachConfig(configId: String, now: Long)

    @Query("UPDATE chat_sessions SET apiConfigId = :configId, model = :model, updatedAt = :now WHERE id = :id")
    suspend fun rebindConfig(id: String, configId: String, model: String, now: Long)

    @Query("UPDATE chat_sessions SET lastMemoryExtractedCount = :count, updatedAt = :now WHERE id = :id")
    suspend fun setMemoryExtractedCount(id: String, count: Int, now: Long)

    /** 历史压缩：只写摘要，不动其它字段（列表页的预览与排序不受影响）。 */
    @Query("""
        UPDATE chat_sessions SET historySummary = :summary, historySummarizedUpTo = :upTo,
        historySummaryAt = :now WHERE id = :id
    """)
    suspend fun setHistorySummary(id: String, summary: String?, upTo: Long?, now: Long)

    @Query("UPDATE chat_sessions SET historySummary = NULL, historySummarizedUpTo = NULL, historySummaryAt = NULL WHERE id = :id")
    suspend fun clearHistorySummary(id: String)

    /** 有摘要的会话：启动时做一次自检，清掉旧版本写坏的那些。 */
    @Query("SELECT * FROM chat_sessions WHERE historySummary IS NOT NULL AND TRIM(historySummary) != ''")
    suspend fun withHistorySummary(): List<ChatSessionEntity>

    @Query("UPDATE chat_sessions SET lastPersonaVersion = :version, updatedAt = :now WHERE personaId = :personaId")
    suspend fun syncPersonaVersionOnSessions(personaId: String, version: Int, now: Long)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM chat_sessions WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {

    // ---------------------------------------------------------------- 区块分页（v11）

    /**
     * 「读某一块」（规范 §3：一页 = 一块）。
     *
     * `(sessionId, blockIndex, createdAt)` 上有索引，所以这条查询读多少行**只取决于这一块**
     * （≤ `MessageBlocks.BLOCK_SIZE` 条，删除之后可能更少），与会话总长度无关。
     */
    @Query(
        """
        SELECT * FROM messages WHERE sessionId = :sessionId AND blockIndex = :blockIndex
        ORDER BY createdAt ASC, id ASC
        """
    )
    suspend fun blockOf(sessionId: String, blockIndex: Int): List<MessageEntity>

    /**
     * 聊天页观察「已加载的那几块」。
     *
     * 用可观察查询（而不是一次性快照）是必须的：块内任何一条被编辑/删除、块里追加了新消息，
     * 列表都要跟着更新。块编号变化时由 ViewModel 重新订阅（`flatMapLatest`）。
     */
    @Query(
        """
        SELECT * FROM messages WHERE sessionId = :sessionId AND blockIndex IN (:blockIndices)
        ORDER BY blockIndex ASC, createdAt ASC, id ASC
        """
    )
    fun observeBlocks(sessionId: String, blockIndices: List<Int>): Flow<List<MessageEntity>>

    /** 块内第一条：删除之后重算 `first…` 用。 */
    @Query(
        """
        SELECT * FROM messages WHERE sessionId = :sessionId AND blockIndex = :blockIndex
        ORDER BY createdAt ASC, id ASC LIMIT 1
        """
    )
    suspend fun firstInBlock(sessionId: String, blockIndex: Int): MessageEntity?

    /** 块内最后一条：删除之后重算 `last…` 用。 */
    @Query(
        """
        SELECT * FROM messages WHERE sessionId = :sessionId AND blockIndex = :blockIndex
        ORDER BY createdAt DESC, id DESC LIMIT 1
        """
    )
    suspend fun lastInBlock(sessionId: String, blockIndex: Int): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND blockIndex = :blockIndex")
    suspend fun countInBlock(sessionId: String, blockIndex: Int): Int

    /**
     * 一次删除会波及哪几块：`deleteAfterCursor` 之前先问一遍，删完只需重算这几块。
     *
     * 只回编号，不 materialize 行；结果集大小 = 被删消息横跨的块数。
     */
    @Query(
        """
        SELECT DISTINCT blockIndex FROM messages WHERE sessionId = :sessionId
        AND (createdAt > :createdAt OR (createdAt = :createdAt AND id > :id))
        """
    )
    suspend fun blockIndicesAfter(sessionId: String, createdAt: Long, id: String): List<Int>

    @Query("UPDATE messages SET blockIndex = :blockIndex WHERE id = :id")
    suspend fun setBlockIndex(id: String, blockIndex: Int)

    /**
     * 只改附件 JSON（0.13 服务端模式）。
     *
     * 服务端消息只回附件 id，不带 mime/kind/duration；上传方知道这些元数据，
     * 在消息落库后补写一次，否则「语音」在服务端模式里会渲染成一张加载不出来的图片。
     */
    @Query("UPDATE messages SET attachmentsJson = :json WHERE id = :id")
    suspend fun updateAttachments(id: String, json: String?)

    /**
     * 把一段稳定序区间整体改成同一块（导入/重建时用：一块一条 UPDATE，而不是一条消息一条）。
     *
     * 区间两端都是**闭区间**，用 `(createdAt, id)` 双列比较，和排序键完全一致，
     * 所以「哪些消息属于哪一块」不会因为同一毫秒而错位。
     */
    @Query(
        """
        UPDATE messages SET blockIndex = :blockIndex WHERE sessionId = :sessionId
        AND (createdAt > :firstCreatedAt OR (createdAt = :firstCreatedAt AND id >= :firstId))
        AND (createdAt < :lastCreatedAt OR (createdAt = :lastCreatedAt AND id <= :lastId))
        """
    )
    suspend fun assignBlockRange(
        sessionId: String,
        blockIndex: Int,
        firstCreatedAt: Long,
        firstId: String,
        lastCreatedAt: Long,
        lastId: String
    ): Int

    // ---------------------------------------------------------------- 读取

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getBySession(sessionId: String): List<MessageEntity>

    /** 只取最近 N 条：装配模型上下文用，长会话不再把整段历史读进内存。 */
    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND blocked = 0 " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    suspend fun recentBySession(sessionId: String, limit: Int): List<MessageEntity>

    /**
     * 最近 N 条（含拉黑期间的消息）。
     *
     * 只给「用户可见的历史 / 媒体列表」这一类界面兜底路径用；模型上下文一律走
     * [recentBySession]，避免拉黑内容在解除后重新进入提示词。
     */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recentIncludingBlockedBySession(sessionId: String, limit: Int): List<MessageEntity>

    /** 压缩用：取比某个时间点更早的消息（按时间正序），只拿需要被压进摘要的那一段。 */
    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND blocked = 0 AND createdAt <= :before " +
            "ORDER BY createdAt ASC, id ASC LIMIT :limit"
    )
    suspend fun olderThan(sessionId: String, before: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latestInSession(sessionId: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countInSession(sessionId: String): Int

    /** 模型可见的条数（不含拉黑期间收到的消息）；抽记忆/压缩的间隔判断用。 */
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND blocked = 0")
    suspend fun countVisibleInSession(sessionId: String): Int

    /**
     * 全库消息条数（bootstrap 四象限判定用）。
     *
     * 只回一个数字：判断「本地有没有聊天历史」不需要把消息物化出来，大库上这仍是一条索引扫描。
     */
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countAll(): Int

    /**
     * 0.13 历史媒体迁移的进度分母：只取「带附件」消息的 `attachmentsJson` 窄列。
     *
     * 不物化整行消息：几十万条纯文本消息不会进内存，返回的只是附件 JSON 片段。
     */
    @Query("SELECT attachmentsJson FROM messages WHERE attachmentsJson IS NOT NULL AND attachmentsJson != ''")
    suspend fun allAttachmentJson(): List<String>

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND role = 'USER'")
    suspend fun countUserMessages(sessionId: String): Int

    /** 节流用：不把拉黑期间的消息算进摘要/记忆的进度（它们永远不会被模型看到）。 */
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND role = 'USER' AND blocked = 0")
    suspend fun countVisibleUserMessages(sessionId: String): Int

    /**
     * 用户消息第一次被模型「看到」时写已读。
     *
     * 只填 readAt 为空的那几条：重复生成、重试都不会把时间改成「刚刚」，
     * 否则界面上的「已读」会随着每一次重新生成往后跳。
     */
    @Query("UPDATE messages SET readAt = :now WHERE sessionId = :sessionId AND role = 'USER' AND readAt IS NULL AND blocked = 0")
    suspend fun markUserMessagesRead(sessionId: String, now: Long): Int

    /** 睡眠唤醒阈值：最近 30 分钟内这个人格发了几条（含刚刚这一条；拉黑消息不算）。 */
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND role = 'USER' AND blocked = 0 AND createdAt >= :since")
    suspend fun countUserMessagesSince(sessionId: String, since: Long): Int

    /** 某条用户消息之后有没有他真正说出口的话；界面靠它决定还要不要显示「已读」。 */
    @Query(
        """
        SELECT * FROM messages WHERE sessionId = :sessionId AND role = 'ASSISTANT'
        AND status != 'ERROR' AND createdAt > :after ORDER BY createdAt ASC LIMIT 1
        """
    )
    suspend fun firstAssistantAfter(sessionId: String, after: Long): MessageEntity?

    /** 这个人设说了多少句话（跨会话），用来判断是不是该引导一下心情了。 */
    @Query(
        """
        SELECT COUNT(*) FROM messages m
        INNER JOIN chat_sessions s ON s.id = m.sessionId
        WHERE s.personaId = :personaId AND m.role = 'ASSISTANT'
        AND m.status != 'ERROR' AND m.createdAt > :after
        """
    )
    suspend fun countAssistantSince(personaId: String, after: Long): Int

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND role = 'ASSISTANT' AND status != 'ERROR' ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestAssistant(sessionId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND role = 'USER' ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestUser(sessionId: String): MessageEntity?

    /**
     * 「对方上次说的话」——精确查询，不靠窗口。
     *
     * 后台处境里的这句以前是从「最近 40 条」里挑最后一个 USER 推出来的：只要最近 40 条
     * 恰好全是助手消息（长回复、连续主动消息），就会出现「明明说过话却显示没说过」。
     * 这里直接从全表按时间倒序取第一条用户消息，结果与历史长度无关。
     */
    @Query(
        """
        SELECT * FROM messages WHERE sessionId = :sessionId AND role = 'USER'
        AND content != '' AND blocked = 0 ORDER BY createdAt DESC LIMIT 1
        """
    )
    suspend fun lastUserMessage(sessionId: String): MessageEntity?

    /** 「对方上次说话」的时间戳；没有说过话时返回 null。 */
    @Query("SELECT MAX(createdAt) FROM messages WHERE sessionId = :sessionId AND role = 'USER' AND blocked = 0")
    suspend fun lastUserMessageAt(sessionId: String): Long?

    /** 最近几条用户原话（闲逛找话题用）：按时间倒序，只要用户消息，不扫整段历史。 */
    @Query(
        """
        SELECT * FROM messages WHERE sessionId = :sessionId AND role = 'USER'
        AND content != '' AND blocked = 0 ORDER BY createdAt DESC LIMIT :limit
        """
    )
    suspend fun recentUserMessages(sessionId: String, limit: Int): List<MessageEntity>

    // ---------------------------------------------------------------- 写入
    //
    // 注意：**所有**会改变「会话里有哪些消息」的写入都必须经由
    // `com.yap.app.data.repo.MessageBlockWriter`，由它同步维护 `message_blocks`
    // 与 `messages.blockIndex`。直接调这里的 insert/delete 会绕过块维护。

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    /** 备份导入的批量插入（块编号随后由 `MessageBlockWriter` 整段重建）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE messages SET content = :content, updatedAt = :now, edited = 1 WHERE id = :id")
    suspend fun updateContent(id: String, content: String, now: Long)

    @Query("UPDATE messages SET status = :status, errorMessage = :error, content = :content, updatedAt = :now WHERE id = :id")
    suspend fun finish(id: String, status: MessageStatus, error: String?, content: String, now: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 删掉这条消息**之后**的所有消息（按稳定序 `(createdAt, id)`）。
     *
     * 以前是 `createdAt > :after`：另一条路径（后台主动消息）刚好落在同一毫秒时，
     * 那条实际更晚的助手回复不会被删，编辑重发后旧回复还留在列表里（审计 P2-7）。
     * 现在同毫秒按 id 决出先后：被编辑的这条自己不算「之后」，所以不会被删。
     */
    @Query(
        """
        DELETE FROM messages WHERE sessionId = :sessionId
        AND (createdAt > :createdAt OR (createdAt = :createdAt AND id > :id))
        """
    )
    suspend fun deleteAfterCursor(sessionId: String, createdAt: Long, id: String)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM messages WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    /**
     * 删人格时先取出他要删的消息：附件图片文件要在事务**之前**拿到路径，
     * 消息删完就查不到了。0.10.2 起「删一个人」也会删他的聊天图片。
     */
    @Query("SELECT * FROM messages WHERE personaId = :personaId ORDER BY createdAt ASC, id ASC")
    suspend fun getByPersona(personaId: String): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /**
     * 读**一块**里的消息（检索用；SQL 由 [MessageSearchSql] 在运行时拼出来）。
     *
     * 每条 SQL 只读一块，所以一次检索的峰值内存 ≈ 一块 + 一页 + frontier，与会话总长度无关
     * （规范 §4）。`@RawQuery` 的理由与旧实现相同：关键词要切出不定个数的词元，每个词元一个
     * `LIKE`，注解里的静态 SQL 表达不了；而手拼字符串会把用户输入拼进 SQL（注入风险），
     * `@RawQuery` + `SimpleSQLiteQuery` 既能动态拼装又能保持参数化。
     */
    @RawQuery
    suspend fun searchBlockMessages(query: SupportSQLiteQuery): List<MessageSearchRow>

    /**
     * 后台回忆用的「最近若干条命中」粗筛（[MessageSearchSql.recentCandidatesQuery]）。
     *
     * 与检索页的逐块扫描分开：回忆是启发式、有界、不翻页；检索页是无上限、逐块、键集游标。
     * 两条路共用的只有词元与 LIKE 转义。
     */
    @RawQuery
    suspend fun searchRecentCandidates(query: SupportSQLiteQuery): List<MessageSearchRow>
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE personaId = :personaId ORDER BY pinned DESC, importance DESC, updatedAt DESC")
    fun observeByPersona(personaId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE personaId = :personaId ORDER BY pinned DESC, importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun getForPersona(personaId: String, limit: Int): List<MemoryEntity>

    /** 进上下文的只有还在用的那些：归档过的永远不进。 */
    @Query("SELECT * FROM memories WHERE personaId = :personaId AND status != 'ARCHIVED' ORDER BY pinned DESC, importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun getForPrompt(personaId: String, limit: Int): List<MemoryEntity>

    /** 整理与回忆都要看到全部（含归档）。 */
    @Query("SELECT * FROM memories WHERE personaId = :personaId ORDER BY pinned DESC, importance DESC, updatedAt DESC")
    suspend fun getAllForPersona(personaId: String): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memories WHERE personaId = :personaId AND status = :status")
    suspend fun countByStatus(personaId: String, status: String): Int

    /** 回忆工具用：按关键词在记忆正文里找（大小写不敏感，中文按 LIKE 处理）。 */
    @Query("""
        SELECT * FROM memories WHERE personaId = :personaId AND content LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY pinned DESC, importance DESC, updatedAt DESC LIMIT :limit
    """)
    suspend fun searchForPersona(personaId: String, query: String, limit: Int): List<MemoryEntity>

    @Query("UPDATE memories SET status = :status, strength = :strength, archivedAt = :archivedAt, updatedAt = :now, consolidatedAt = :now WHERE id = :id")
    suspend fun setLifecycle(id: String, status: String, strength: Double, archivedAt: Long?, now: Long)

    @Query("UPDATE memories SET importance = :importance, strength = :strength, content = :content, updatedAt = :now, consolidatedAt = :now WHERE id = :id")
    suspend fun applyConsolidation(id: String, importance: Int, strength: Double, content: String, now: Long)

    @Query("UPDATE memories SET strength = :strength, updatedAt = :now, consolidatedAt = :now WHERE id = :id")
    suspend fun touchStrength(id: String, strength: Double, now: Long)

    /** 倒排索引候选：命中这些二元组的记忆（按命中的词数排序由上层做）。 */
    @Query("SELECT DISTINCT memoryId FROM memory_terms WHERE term IN (:terms) LIMIT :limit")
    suspend fun memoryIdsByTerms(terms: List<String>, limit: Int = 200): List<String>

    @Query("SELECT * FROM memories WHERE personaId = :personaId AND happenedAt IS NOT NULL ORDER BY happenedAt DESC LIMIT :limit")
    suspend fun withTimeHint(personaId: String, limit: Int = 50): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE personaId = :personaId AND tags IS NOT NULL AND tags LIKE '%' || :tag || '%' ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun byTag(personaId: String, tag: String, limit: Int = 50): List<MemoryEntity>

    /** 回忆重构：改正文，同时留下降次数与锚点（原文只在第一次写）。 */
    @Query("""
        UPDATE memories SET content = :content,
        originalContent = COALESCE(originalContent, :beforeContent),
        driftCount = driftCount + 1, updatedAt = :now, lastUsedAt = :now,
        useCount = useCount + 1 WHERE id = :id
    """)
    suspend fun applyDrift(id: String, content: String, beforeContent: String, now: Long)

    @Query("UPDATE memories SET lastUsedAt = :now, useCount = useCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun markUsed(id: String, now: Long)

    @Query("SELECT COUNT(*) FROM memories WHERE personaId = :personaId AND driftCount > 0")
    suspend fun countDrifted(personaId: String): Int

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<MemoryEntity>

    /**
     * 还没有向量的记忆 id（补建用）。
     *
     * 只挑真的能算出向量的行（正文非空白），否则「一条空记忆」会让补建永远补不完。
     * 用 `NOT EXISTS` 而不是 `LEFT JOIN … IS NULL`：`memory_vectors` 上有 memoryId 索引，走一遍就够。
     */
    @Query(
        """
        SELECT m.id FROM memories m
        WHERE m.personaId = :personaId
          AND m.content IS NOT NULL AND TRIM(m.content) != ''
          AND NOT EXISTS (
              SELECT 1 FROM memory_vectors v
              WHERE v.memoryId = m.id AND v.provider = :provider AND v.dim = :dim
          )
        ORDER BY m.pinned DESC, m.importance DESC, m.updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun idsMissingVectors(personaId: String, provider: String, dim: Int, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM memories WHERE personaId = :personaId")
    suspend fun countForPersona(personaId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<MemoryEntity>)

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("UPDATE memories SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM memories WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}

@Dao
interface PersonaPresetDao {
    @Query("SELECT * FROM persona_presets ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PersonaPresetEntity>>

    @Query("SELECT * FROM persona_presets ORDER BY updatedAt DESC")
    suspend fun getAll(): List<PersonaPresetEntity>

    @Query("SELECT * FROM persona_presets WHERE id = :id")
    fun observeById(id: String): Flow<PersonaPresetEntity?>

    @Query("SELECT * FROM persona_presets WHERE id = :id")
    suspend fun getById(id: String): PersonaPresetEntity?

    @Query("SELECT COUNT(*) FROM persona_presets")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: PersonaPresetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(presets: List<PersonaPresetEntity>)

    @Update
    suspend fun update(preset: PersonaPresetEntity)

    @Query("DELETE FROM persona_presets WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM persona_presets")
    suspend fun deleteAll()
}

@Dao
interface ProactiveRuleDao {
    @Query("SELECT * FROM proactive_rules ORDER BY enabled DESC, nextTriggerAt ASC")
    fun observeAll(): Flow<List<ProactiveRuleEntity>>

    @Query("SELECT * FROM proactive_rules WHERE personaId = :personaId ORDER BY enabled DESC, nextTriggerAt ASC")
    fun observeByPersona(personaId: String): Flow<List<ProactiveRuleEntity>>

    @Query("SELECT * FROM proactive_rules WHERE id = :id")
    fun observeById(id: String): Flow<ProactiveRuleEntity?>

    @Query("SELECT * FROM proactive_rules WHERE id = :id")
    suspend fun getById(id: String): ProactiveRuleEntity?

    @Query("SELECT * FROM proactive_rules WHERE enabled = 1 AND nextTriggerAt <= :now ORDER BY nextTriggerAt ASC")
    suspend fun dueAt(now: Long): List<ProactiveRuleEntity>

    @Query("SELECT * FROM proactive_rules WHERE enabled = 1 ORDER BY nextTriggerAt ASC")
    suspend fun enabledRules(): List<ProactiveRuleEntity>

    @Query("SELECT MIN(nextTriggerAt) FROM proactive_rules WHERE enabled = 1")
    suspend fun nextTriggerAt(): Long?

    /** pending 只表示「他刚安排、你还没看到」，规则本身已经生效。 */
    @Query("SELECT * FROM proactive_rules WHERE personaId = :personaId AND pending = 1 ORDER BY createdAt DESC")
    suspend fun pendingForPersona(personaId: String): List<ProactiveRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: ProactiveRuleEntity)

    @Update
    suspend fun update(rule: ProactiveRuleEntity)

    @Query("UPDATE proactive_rules SET enabled = :enabled, pending = 0, nextTriggerAt = :nextTriggerAt, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, nextTriggerAt: Long, now: Long)

    @Query("UPDATE proactive_rules SET nextTriggerAt = :nextTriggerAt, lastTriggeredAt = :at, lastError = :error, updatedAt = :now WHERE id = :id")
    suspend fun markTriggered(id: String, nextTriggerAt: Long, at: Long, error: String?, now: Long)

    @Query("DELETE FROM proactive_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM proactive_rules WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM proactive_rules")
    suspend fun deleteAll()
}

@Dao
interface MemoryEchoDao {
    @Query("SELECT * FROM memory_echoes WHERE personaId = :personaId AND drawer = :drawer ORDER BY lastSeenAt DESC")
    suspend fun byPersonaAndDrawer(personaId: String, drawer: String): List<MemoryEchoEntity>

    @Query("SELECT * FROM memory_echoes WHERE personaId = :personaId ORDER BY lastSeenAt DESC")
    suspend fun byPersona(personaId: String): List<MemoryEchoEntity>

    @Query("SELECT COUNT(*) FROM memory_echoes WHERE personaId = :personaId")
    suspend fun countForPersona(personaId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(echo: MemoryEchoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(echoes: List<MemoryEchoEntity>)

    @Query("DELETE FROM memory_echoes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM memory_echoes WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM memory_echoes WHERE personaId = :personaId AND createdAt < :before")
    suspend fun deleteOlderThan(personaId: String, before: Long)

    @Query("DELETE FROM memory_echoes")
    suspend fun deleteAll()
}

@Dao
interface PersonaPendingPatchDao {
    @Query("SELECT * FROM persona_pending_patches WHERE personaId = :personaId ORDER BY createdAt DESC")
    fun observeByPersona(personaId: String): kotlinx.coroutines.flow.Flow<List<PersonaPendingPatchEntity>>

    @Query("SELECT * FROM persona_pending_patches WHERE personaId = :personaId ORDER BY createdAt DESC")
    suspend fun byPersona(personaId: String): List<PersonaPendingPatchEntity>

    @Query("SELECT * FROM persona_pending_patches WHERE id = :id")
    suspend fun getById(id: String): PersonaPendingPatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(patch: PersonaPendingPatchEntity)

    @Query("DELETE FROM persona_pending_patches WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM persona_pending_patches WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM persona_pending_patches")
    suspend fun deleteAll()
}


@Dao
interface AgentIntentDao {

    @Query("SELECT * FROM agent_intents WHERE personaId = :personaId AND status = 'PENDING' ORDER BY earliestAt ASC")
    suspend fun pendingForPersona(personaId: String): List<AgentIntentEntity>

    /** 窗口已经到了（或过期）的意向：包括「窗口已开」和「窗口已过但还没发」。 */
    @Query("SELECT * FROM agent_intents WHERE status = 'PENDING' AND earliestAt <= :now ORDER BY earliestAt ASC")
    suspend fun dueAt(now: Long): List<AgentIntentEntity>

    /** 所有还挂着、且写了备注的念头：用来收掉重复的那些。 */
    @Query("SELECT * FROM agent_intents WHERE status = 'PENDING' AND note != ''")
    suspend fun pendingWithNote(): List<AgentIntentEntity>

    @Query("SELECT * FROM agent_intents WHERE personaId = :personaId ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(personaId: String, limit: Int = 20): kotlinx.coroutines.flow.Flow<List<AgentIntentEntity>>

    @Query("SELECT * FROM agent_intents WHERE id = :id")
    suspend fun getById(id: String): AgentIntentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(intent: AgentIntentEntity)

    @Update
    suspend fun update(intent: AgentIntentEntity)

    @Query("UPDATE agent_intents SET status = :status, sentAt = :sentAt, lastError = :error WHERE id = :id")
    suspend fun mark(id: String, status: AgentIntentStatus, sentAt: Long?, error: String?)

    @Query("UPDATE agent_intents SET status = 'EXPIRED' WHERE status = 'PENDING' AND latestAt < :now")
    suspend fun expireOlderThan(now: Long): Int

    @Query("SELECT COUNT(*) FROM agent_intents WHERE personaId = :personaId AND status = 'PENDING'")
    suspend fun pendingCount(personaId: String): Int

    @Query("DELETE FROM agent_intents WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM agent_intents")
    suspend fun deleteAll()
}

@Dao
interface AgentTickDao {

    @Query("SELECT * FROM agent_ticks WHERE personaId = :personaId ORDER BY at DESC LIMIT :limit")
    fun observeRecent(personaId: String, limit: Int = 30): kotlinx.coroutines.flow.Flow<List<AgentTickEntity>>

    @Query("SELECT * FROM agent_ticks ORDER BY at DESC LIMIT :limit")
    fun observeLatest(limit: Int = 30): kotlinx.coroutines.flow.Flow<List<AgentTickEntity>>

    @Query("SELECT * FROM agent_ticks WHERE personaId = :personaId ORDER BY at DESC LIMIT 1")
    suspend fun latest(personaId: String): AgentTickEntity?

    /** 所有人格里最近一次思考的时间，用来防止服务被反复拉起就连着想好几次。 */
    @Query("SELECT MAX(at) FROM agent_ticks")
    suspend fun lastTickAt(): Long?

    /** 最近一次「真的开口了」：用来算两条主动消息之间的最小间隔。 */
    @Query("SELECT MAX(at) FROM agent_ticks WHERE personaId = :personaId AND messageId IS NOT NULL")
    suspend fun lastSpokenAt(personaId: String): Long?

    /** 一天之内他说了几次——防止变成推送机器。 */
    @Query("SELECT COUNT(*) FROM agent_ticks WHERE personaId = :personaId AND messageId IS NOT NULL AND at >= :since")
    suspend fun spokenSince(personaId: String, since: Long): Int

    @Query("SELECT COUNT(*) FROM agent_ticks WHERE personaId = :personaId AND at >= :since")
    suspend fun tickCountSince(personaId: String, since: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tick: AgentTickEntity)

    @Query("DELETE FROM agent_ticks WHERE at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM agent_ticks WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM agent_ticks")
    suspend fun deleteAll()
}

@Dao
interface MemoryConsolidationDao {
    @Query("SELECT * FROM memory_consolidations WHERE personaId = :personaId ORDER BY at DESC LIMIT :limit")
    fun observeRecent(personaId: String, limit: Int = 10): Flow<List<MemoryConsolidationEntity>>

    @Query("SELECT * FROM memory_consolidations ORDER BY at DESC LIMIT :limit")
    fun observeLatest(limit: Int = 10): Flow<List<MemoryConsolidationEntity>>

    @Query("SELECT * FROM memory_consolidations WHERE personaId = :personaId ORDER BY at DESC LIMIT 1")
    suspend fun latestForPersona(personaId: String): MemoryConsolidationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: MemoryConsolidationEntity)

    @Query("DELETE FROM memory_consolidations WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM memory_consolidations")
    suspend fun deleteAll()
}

@Dao
interface MemoryTermDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(terms: List<MemoryTermEntity>)

    @Query("DELETE FROM memory_terms WHERE memoryId = :memoryId")
    suspend fun deleteByMemory(memoryId: String)

    @Query("DELETE FROM memory_terms")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM memory_terms")
    suspend fun count(): Int

    /** 记忆被整批删掉时（清空会话、删人格、整库替换）回收指向已不存在记忆的词元。 */
    @Query("DELETE FROM memory_terms WHERE memoryId NOT IN (SELECT id FROM memories)")
    suspend fun deleteOrphans(): Int
}

@Dao
interface MemoryVersionDao {
    @Query("SELECT * FROM memory_versions WHERE memoryId = :memoryId ORDER BY at DESC LIMIT :limit")
    fun observeByMemory(memoryId: String, limit: Int = 20): Flow<List<MemoryVersionEntity>>

    @Query("SELECT * FROM memory_versions WHERE memoryId = :memoryId ORDER BY at DESC LIMIT 1")
    suspend fun latest(memoryId: String): MemoryVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(version: MemoryVersionEntity)

    @Query("DELETE FROM memory_versions WHERE memoryId = :memoryId")
    suspend fun deleteByMemory(memoryId: String)

    /**
     * 记忆被整批删掉之后回收版本行。
     *
     * `memory_versions` 只挂在 `memoryId` 上（没有外键），
     * `MemoryReconsolidator` 每次「回忆漂移」都写一条——0.10.2 之前它**只增不减**，
     * 删人格/清空数据都清不干净。这里与 term/vector 用同一条孤儿回收语义。
     */
    @Query("DELETE FROM memory_versions WHERE memoryId NOT IN (SELECT id FROM memories)")
    suspend fun deleteOrphans(): Int

    @Query("DELETE FROM memory_versions")
    suspend fun deleteAll()
}

@Dao
interface ApiUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(usage: ApiUsageEntity)

    @Query("SELECT * FROM api_usage WHERE at >= :since ORDER BY at DESC LIMIT :limit")
    fun observeSince(since: Long, limit: Int = 200): Flow<List<ApiUsageEntity>>

    @Query("SELECT * FROM api_usage WHERE at >= :since ORDER BY at DESC")
    suspend fun since(since: Long): List<ApiUsageEntity>

    @Query("SELECT COUNT(*) FROM api_usage WHERE at >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT COALESCE(SUM(costMicroUsd), 0) FROM api_usage WHERE at >= :since")
    suspend fun costSince(since: Long): Long

    /**
     * 按会话（= 按人）汇总本月用量。
     *
     * 会话删掉以后留下来的历史行 sessionId 还在，只是 JOIN 不到人格；界面把它们归到「未知联系人」，
     * 这样「按人看」的总数不会因为删掉一个人就凭空少一截。
     */
    @Query("""
        SELECT u.sessionId AS sessionId, p.name AS personaName, COUNT(*) AS calls,
               COALESCE(SUM(u.promptTokens), 0) AS promptTokens,
               COALESCE(SUM(u.completionTokens), 0) AS completionTokens
        FROM api_usage u
        LEFT JOIN chat_sessions s ON s.id = u.sessionId
        LEFT JOIN personas p ON p.id = s.personaId
        WHERE u.at >= :since
        GROUP BY u.sessionId
        ORDER BY (COALESCE(SUM(u.promptTokens), 0) + COALESCE(SUM(u.completionTokens), 0)) DESC
        LIMIT :limit
    """)
    fun observeBySession(since: Long, limit: Int = 10): Flow<List<UsageBySessionRow>>

    @Query("SELECT COALESCE(SUM(promptTokens), 0) FROM api_usage WHERE at >= :since")
    suspend fun promptTokensSince(since: Long): Long

    @Query("SELECT COALESCE(SUM(completionTokens), 0) FROM api_usage WHERE at >= :since")
    suspend fun completionTokensSince(since: Long): Long

    @Query("DELETE FROM api_usage WHERE at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM api_usage")
    suspend fun deleteAll()

    /**
     * 按人清 token 流水。
     *
     * 当前没有任何删除路径按人清它（删人格刻意保留历史，界面归到「未知联系人」，
     * 见 [observeBySession]）；这个方法让 `RoomErasureStore` 的两种口径（整库/按人）
     * 都能实现，而不是留下一个「只能整表删」的特例。
     */
    @Query("DELETE FROM api_usage WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)
}

@Dao
interface MemoryVectorDao {
    @Query("SELECT * FROM memory_vectors WHERE provider = :provider")
    suspend fun allForProvider(provider: String): List<MemoryVectorEntity>

    @Query("SELECT * FROM memory_vectors WHERE memoryId IN (:ids) AND provider = :provider")
    suspend fun forMemories(ids: List<String>, provider: String): List<MemoryVectorEntity>

    @Query("SELECT COUNT(*) FROM memory_vectors WHERE provider = :provider")
    suspend fun countForProvider(provider: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vectors: List<MemoryVectorEntity>)

    @Query("DELETE FROM memory_vectors WHERE memoryId = :memoryId")
    suspend fun deleteByMemory(memoryId: String)

    @Query("DELETE FROM memory_vectors WHERE memoryId NOT IN (SELECT id FROM memories)")
    suspend fun deleteOrphans(): Int

    @Query("DELETE FROM memory_vectors WHERE provider != :provider")
    suspend fun deleteOtherProviders(provider: String): Int

    @Query("DELETE FROM memory_vectors")
    suspend fun deleteAll()
}

/**
 * 日记板（`spec/diary/README.md`）。
 *
 * 只做四件事：写、按时间倒序翻、按 id 删、把到点的提醒**原子地**领走。
 * 领提醒用 `UPDATE ... WHERE remindedAt IS NULL` 并对返回行数判定：
 * 心跳与主动 tick 可能同时跑，靠这条带条件的更新保证「只触发一次」，
 * 而不是先查后写在两个协程之间留一个窗口。
 */
@Dao
interface DiaryNoteDao {

    @Query("SELECT * FROM diary_notes WHERE personaId = :personaId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(personaId: String, limit: Int): List<DiaryNoteEntity>

    @Query("SELECT * FROM diary_notes WHERE personaId = :personaId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(personaId: String, limit: Int): kotlinx.coroutines.flow.Flow<List<DiaryNoteEntity>>

    @Query("SELECT * FROM diary_notes WHERE personaId = :personaId")
    suspend fun allForPersona(personaId: String): List<DiaryNoteEntity>

    @Query("SELECT * FROM diary_notes WHERE id = :id")
    suspend fun getById(id: String): DiaryNoteEntity?

    @Query("SELECT COUNT(*) FROM diary_notes WHERE personaId = :personaId")
    suspend fun count(personaId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: DiaryNoteEntity)

    /** 按 id + 人格删（跨人格隔离：别人的笔记 id 删不掉）。返回真删掉的行数。 */
    @Query("DELETE FROM diary_notes WHERE id = :id AND personaId = :personaId")
    suspend fun delete(id: String, personaId: String): Int

    /** 到点且还没提醒过的笔记（整库口径：他的提醒不因为当前打开的是哪个人格而消失）。 */
    @Query(
        "SELECT * FROM diary_notes WHERE remindAt IS NOT NULL AND remindedAt IS NULL " +
            "AND remindAt <= :now ORDER BY remindAt ASC LIMIT :limit"
    )
    suspend fun dueReminders(now: Long, limit: Int): List<DiaryNoteEntity>

    /** 原子占位：只有 `remindedAt IS NULL` 时才写，返回 1 表示这一拍抢到了这条提醒。 */
    @Query("UPDATE diary_notes SET remindedAt = :at, updatedAt = :at WHERE id = :id AND remindedAt IS NULL")
    suspend fun markReminded(id: String, at: Long): Int

    @Query("DELETE FROM diary_notes WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM diary_notes")
    suspend fun deleteAll()
}

/**
 * 表情包的读写口（`spec/stickers/SCHEMA.md`）。
 *
 * 列表只有两种口径，和 `spec/stickers/README.md` 的「最近 30 个 + 全部」一一对应：
 * 「最近」按 `createdAt` 倒序，`createdAt` 相同时用 `id` 兜底（同一毫秒导入两张不会跳序）；
 * 「全部」同序但不限量。`personaId` 只作为「属于哪个人格」的弱归属，不参与过滤——
 * 用户换人格时自己的表情不该消失。
 */
@Dao
interface StickerDao {

    @Query("SELECT * FROM stickers ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): kotlinx.coroutines.flow.Flow<List<StickerEntity>>

    @Query("SELECT * FROM stickers ORDER BY createdAt DESC, id DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<StickerEntity>>

    @Query("SELECT * FROM stickers ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<StickerEntity>

    @Query("SELECT * FROM stickers WHERE id = :id")
    suspend fun getById(id: String): StickerEntity?

    /** 同一个文件是否已经有表情行（「收藏」要复用同一份文件，不能存成两份）。 */
    @Query("SELECT * FROM stickers WHERE file = :file LIMIT 1")
    suspend fun findByFile(file: String): StickerEntity?

    @Query("SELECT COUNT(*) FROM stickers")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM stickers WHERE personaId = :personaId")
    suspend fun countForPersona(personaId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sticker: StickerEntity): Long

    /**
     * 服务端缓存刷新用的「有就覆盖」写入（0.13）。
     *
     * 为什么不能只用 [insert]（IGNORE）：服务端那一行可能先是「字节没落盘、file 指向
     * `/v1/media/<id>`」，下一轮下载成功后要用本地文件路径覆盖同一 id；IGNORE 会让它永远
     * 停在服务端引用上。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sticker: StickerEntity): Long

    @Query("UPDATE stickers SET personaId = :personaId WHERE id = :id")
    suspend fun setPersona(id: String, personaId: String?)

    @Query("DELETE FROM stickers WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM stickers WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM stickers")
    suspend fun deleteAll()

    @Query("SELECT file FROM stickers")
    suspend fun allFiles(): List<String>
}

/**
 * 心理活动的读写口（schema v18）。
 *
 * 只保留最近 50 条：`trim` 删除「不在最近 keep 条 id 里」的行，写入后调用一次。
 * 用 id 子查询而不是 `at < cutoff`，是因为同一毫秒可能写入多条（测试/导入），
 * 按排序键 id 兜底才能稳定保留恰好 50 条。
 */
@Dao
interface MentalActivityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(activity: MentalActivityEntity)

    @Query("SELECT * FROM mental_activities WHERE personaId = :personaId ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recent(personaId: String, limit: Int): List<MentalActivityEntity>

    @Query("SELECT * FROM mental_activities WHERE personaId = :personaId ORDER BY at DESC, id DESC LIMIT 1")
    suspend fun latest(personaId: String): MentalActivityEntity?

    @Query("SELECT * FROM mental_activities WHERE sessionId = :sessionId ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recentForSession(sessionId: String, limit: Int): List<MentalActivityEntity>

    @Query("SELECT COUNT(*) FROM mental_activities WHERE personaId = :personaId")
    suspend fun count(personaId: String): Int

    @Query(
        "DELETE FROM mental_activities WHERE personaId = :personaId AND id NOT IN " +
            "(SELECT id FROM mental_activities WHERE personaId = :personaId ORDER BY at DESC, id DESC LIMIT :keep)"
    )
    suspend fun trim(personaId: String, keep: Int)

    @Query("DELETE FROM mental_activities WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM mental_activities WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM mental_activities")
    suspend fun deleteAll()
}

/**
 * 会话级「小本子」的读写口（schema v18）。
 *
 * 一个会话一行；更新时走 REPLACE。短期/长期各自的用户消息计数是节流凭据：
 * 只有跨过下一个 5 / 30 的整数倍才真正调用模型，失败时整行不写，旧摘要自然保留。
 */
@Dao
interface SessionMemoryDao {
    @Query("SELECT * FROM session_memory WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): SessionMemoryEntity?

    @Query("SELECT * FROM session_memory WHERE personaId = :personaId")
    suspend fun byPersona(personaId: String): List<SessionMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: SessionMemoryEntity)

    @Query("UPDATE session_memory SET shortSummary = :shortSummary, shortUserCount = :count, shortUpdatedAt = :at, updatedAt = :at WHERE sessionId = :sessionId")
    suspend fun updateShort(sessionId: String, shortSummary: String, count: Int, at: Long)

    @Query("UPDATE session_memory SET longSummary = :longSummary, longUserCount = :count, longUpdatedAt = :at, updatedAt = :at WHERE sessionId = :sessionId")
    suspend fun updateLong(sessionId: String, longSummary: String, count: Int, at: Long)

    @Query("DELETE FROM session_memory WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM session_memory WHERE personaId = :personaId")
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM session_memory")
    suspend fun deleteAll()
}
