package com.myfriend.app.data.local

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

    @Query("UPDATE personas SET defaultApiConfigId = NULL WHERE defaultApiConfigId = :configId")
    suspend fun unbindApiConfig(configId: String)

    /** 记下此刻的心情；不清空 updatedAt，免得打乱列表顺序。 */
    @Query("UPDATE personas SET mood = :mood, moodIntensity = :intensity, moodReason = :reason, moodUpdatedAt = :now WHERE id = :id")
    suspend fun setMood(id: String, mood: String?, intensity: Int?, reason: String?, now: Long)

    /** 睡觉前要不要开免打扰：由模型的 dnd / quiet_hours 动作写，用户没有入口。 */
    @Query("UPDATE personas SET dndOnSleep = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setDndOnSleep(id: String, enabled: Boolean, now: Long)

    @Query("UPDATE personas SET moodNudgedAt = :now WHERE id = :id")
    suspend fun markMoodNudged(id: String, now: Long)

    /** 心情随时间淡下去，只改词和强度。 */
    @Query("UPDATE personas SET mood = :mood, moodIntensity = :intensity WHERE id = :id")
    suspend fun fadeMood(id: String, mood: String?, intensity: Int?)

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
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    /**
     * 聊天页首屏：只观察最近 [limit] 条（新的在前，调用方翻正序）。
     *
     * Room 的可观察查询会跟着 `messages` 表的变化重跑，所以「新消息追加 / 单条编辑 / 单条删除」
     * 都会自动反映到这个窗口里，不需要界面自己拼。
     */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecentBySession(sessionId: String, limit: Int): Flow<List<MessageEntity>>

    /**
     * 聊天页「已经加载到的最早一条之后」的整个区间。
     *
     * 上滑加载更早时，游标往前挪，这个查询自然就多带回一段；因为它是可观察的，
     * 窗口内任何一条消息被编辑/删除都会跟着更新，不会留下过期的旧行。
     */
    @Query(
        """
        SELECT * FROM messages WHERE sessionId = :sessionId
        AND (createdAt > :sinceCreatedAt OR (createdAt = :sinceCreatedAt AND id >= :sinceId))
        ORDER BY createdAt ASC, id ASC
        """
    )
    fun observeSince(
        sessionId: String,
        sinceCreatedAt: Long,
        sinceId: String
    ): Flow<List<MessageEntity>>

    /**
     * 往前翻一页：比 `(beforeCreatedAt, beforeId)` 更早的最近 [limit] 条（新的在前）。
     *
     * 用 `(createdAt, id)` 双列游标而不是只看时间：导入的备份、极端情况下同一毫秒写入的
     * 两条消息不会因为时间相等而被跳过或重复。
     */
    @Query(
        """
        SELECT * FROM messages WHERE sessionId = :sessionId
        AND (createdAt < :beforeCreatedAt OR (createdAt = :beforeCreatedAt AND id < :beforeId))
        ORDER BY createdAt DESC, id DESC LIMIT :limit
        """
    )
    suspend fun pageBefore(
        sessionId: String,
        beforeCreatedAt: Long,
        beforeId: String,
        limit: Int
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getBySession(sessionId: String): List<MessageEntity>

    /** 只取最近 N 条：长会话不再把整段历史读进内存。 */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recentBySession(sessionId: String, limit: Int): List<MessageEntity>

    /** 压缩用：取比某个时间点更早的消息（按时间正序），只拿需要被压进摘要的那一段。 */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND createdAt <= :before ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun olderThan(sessionId: String, before: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latestInSession(sessionId: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countInSession(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND role = 'USER'")
    suspend fun countUserMessages(sessionId: String): Int

    /**
     * 用户消息第一次被模型「看到」时写已读。
     *
     * 只填 readAt 为空的那几条：重复生成、重试都不会把时间改成「刚刚」，
     * 否则界面上的「已读」会随着每一次重新生成往后跳。
     */
    @Query("UPDATE messages SET readAt = :now WHERE sessionId = :sessionId AND role = 'USER' AND readAt IS NULL")
    suspend fun markUserMessagesRead(sessionId: String, now: Long): Int

    /** 睡眠唤醒阈值：最近 30 分钟内这个人格发了几条（含刚刚这一条）。 */
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND role = 'USER' AND createdAt >= :since")
    suspend fun countUserMessagesSince(sessionId: String, since: Long): Int

    /** 某条用户消息之后有没有他真正说出口的话；界面靠它决定还要不要显示「已读」。 */
    @Query("""
        SELECT * FROM messages WHERE sessionId = :sessionId AND role = 'ASSISTANT'
        AND status != 'ERROR' AND createdAt > :after ORDER BY createdAt ASC LIMIT 1
    """)
    suspend fun firstAssistantAfter(sessionId: String, after: Long): MessageEntity?

    /** 这个人设说了多少句话（跨会话），用来判断是不是该引导一下心情了。 */
    @Query("""
        SELECT COUNT(*) FROM messages m
        INNER JOIN chat_sessions s ON s.id = m.sessionId
        WHERE s.personaId = :personaId AND m.role = 'ASSISTANT'
        AND m.status != 'ERROR' AND m.createdAt > :after
    """)
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
    @Query("""
        SELECT * FROM messages WHERE sessionId = :sessionId AND role = 'USER'
        AND content != '' ORDER BY createdAt DESC LIMIT 1
    """)
    suspend fun lastUserMessage(sessionId: String): MessageEntity?

    /** 「对方上次说话」的时间戳；没有说过话时返回 null。 */
    @Query("SELECT MAX(createdAt) FROM messages WHERE sessionId = :sessionId AND role = 'USER'")
    suspend fun lastUserMessageAt(sessionId: String): Long?

    /** 最近几条用户原话（闲逛找话题用）：按时间倒序，只要用户消息，不扫整段历史。 */
    @Query("""
        SELECT * FROM messages WHERE sessionId = :sessionId AND role = 'USER'
        AND content != '' ORDER BY createdAt DESC LIMIT :limit
    """)
    suspend fun recentUserMessages(sessionId: String, limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE messages SET content = :content, updatedAt = :now, edited = 1 WHERE id = :id")
    suspend fun updateContent(id: String, content: String, now: Long)

    @Query("UPDATE messages SET status = :status, errorMessage = :error, content = :content, updatedAt = :now WHERE id = :id")
    suspend fun finish(id: String, status: MessageStatus, error: String?, content: String, now: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND createdAt > :after")
    suspend fun deleteAfter(sessionId: String, after: Long)

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
     * 检索粗筛：SQL 由 [MessageSearchSql] 在运行时拼出来（人格 / 时间过滤 + 若干个 LIKE 词元 +
     * LIMIT），所有值走绑定参数，用户输入永远不进 SQL 文本。
     *
     * 为什么用 `@RawQuery` 而不是 `@Query`：关键词要按 [com.myfriend.app.domain.ChineseTerms]
     * 切出**不定个数**的词元，每个词元一个 `LIKE`，注解里的静态 SQL 表达不了；手拼字符串又
     * 会把用户输入拼进 SQL（注入风险），`@RawQuery` + [androidx.sqlite.db.SimpleSQLiteQuery]
     * 既能动态拼装又能保持参数化。排序与展示顺序由 `SearchRanking` 在内存里重新算。
     */
    @RawQuery
    suspend fun searchCandidates(query: SupportSQLiteQuery): List<MessageSearchRow>

    /** 与 [searchCandidates] 同一套过滤条件的命中总数（`COUNT(*) AS total`）。 */
    @RawQuery
    suspend fun countSearchHits(query: SupportSQLiteQuery): List<SearchCountRow>
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
