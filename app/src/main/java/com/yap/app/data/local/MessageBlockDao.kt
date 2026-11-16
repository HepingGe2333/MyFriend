package com.yap.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * 一块的元数据（规范 `spec/messages-blocks/README.md` §1）。
 *
 * 存在的意义是「不扫消息表就能定位一页」：编号、条数、首尾消息与时间都在这里，
 * 于是聊天页首屏只要 `MAX(blockIndex)` + 一条按块取消息的查询，检索只要按块枚举。
 *
 * ## 它是派生数据
 *
 * 每一行都能由 `messages` 算出来（见 [com.yap.app.domain.MessageBlocks]），
 * 所以**不进备份**（备份格式仍是 v2）：导入之后用追加规则重建即可。
 * 反过来，任何写消息的路径都必须同步维护它，否则会出现「块里有 210 条」这种
 * 只会在用户手机上才看得见的错——落库入口统一收在 `MessageBlockWriter`。
 */
@Entity(
    tableName = "message_blocks",
    primaryKeys = ["sessionId", "blockIndex"],
    indices = [Index(value = ["sessionId", "blockIndex"])]
)
data class MessageBlockEntity(
    val sessionId: String,
    val blockIndex: Int,
    val firstMessageId: String,
    val lastMessageId: String,
    val firstCreatedAt: Long,
    val lastCreatedAt: Long,
    val messageCount: Int
)

/**
 * 检索枚举块时回来的一行（只用来定位，不读消息正文）。
 *
 * 时间范围也一起带回来，是为了让「按时间过滤」在 SQL 里就能跳过整块，
 * 而不是把块读进来再逐条丢。
 */
data class SearchBlockRow(
    val sessionId: String,
    val blockIndex: Int
)

/** 重建块表时需要的一行：消息顺序 + 当前的块编号（用于只更新真的变了的行）。 */
data class MessageBlockAssignmentRow(
    val id: String,
    val createdAt: Long,
    val blockIndex: Int
)

@Dao
interface MessageBlockDao {

    @Query("SELECT * FROM message_blocks WHERE sessionId = :sessionId AND blockIndex = :blockIndex")
    suspend fun get(sessionId: String, blockIndex: Int): MessageBlockEntity?

    /** 追加规则要看的那一块：编号最大的那一块。 */
    @Query("SELECT * FROM message_blocks WHERE sessionId = :sessionId ORDER BY blockIndex DESC LIMIT 1")
    suspend fun lastBlock(sessionId: String): MessageBlockEntity?

    @Query("SELECT MAX(blockIndex) FROM message_blocks WHERE sessionId = :sessionId")
    suspend fun lastBlockIndex(sessionId: String): Int?

    @Query("SELECT MIN(blockIndex) FROM message_blocks WHERE sessionId = :sessionId")
    suspend fun firstBlockIndex(sessionId: String): Int?

    /**
     * 「上一块」不是 `newest - 1`：块删空之后编号会留缺口（规范 §1）。
     */
    @Query("SELECT MAX(blockIndex) FROM message_blocks WHERE sessionId = :sessionId AND blockIndex < :blockIndex")
    suspend fun blockBefore(sessionId: String, blockIndex: Int): Int?

    @Query("SELECT MIN(blockIndex) FROM message_blocks WHERE sessionId = :sessionId AND blockIndex > :blockIndex")
    suspend fun blockAfter(sessionId: String, blockIndex: Int): Int?

    /**
     * 块表的可观察版本：聊天页靠它知道「会话从空变成有一块」「最新块被删了」。
     *
     * 返回 `List<Int>` 而不是可空的标量：Room 对 `Flow<Int?>` 的标量映射在空表时行为不直观，
     * 用「最多一行的列表」最省心。
     */
    @Query("SELECT blockIndex FROM message_blocks WHERE sessionId = :sessionId ORDER BY blockIndex DESC LIMIT 1")
    fun observeLastBlock(sessionId: String): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(block: MessageBlockEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(blocks: List<MessageBlockEntity>)

    @Query("DELETE FROM message_blocks WHERE sessionId = :sessionId AND blockIndex = :blockIndex")
    suspend fun delete(sessionId: String, blockIndex: Int)

    @Query("DELETE FROM message_blocks WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /**
     * 删一个人格时清掉他的块：`message_blocks` 上只有 sessionId，所以按会话反查。
     *
     * 必须在 `chat_sessions` 被删**之前**调用（删除清单里 `messages` 排在 `sessions` 前面）。
     */
    @Query(
        """
        DELETE FROM message_blocks WHERE sessionId IN (
            SELECT id FROM chat_sessions WHERE personaId = :personaId
        )
        """
    )
    suspend fun deleteByPersona(personaId: String)

    @Query("DELETE FROM message_blocks")
    suspend fun deleteAll()

    /**
     * 回收孤儿块：会话已经不存在（或整表被替换）时留下的行。
     *
     * 迁移的最后一步与备份「替换」后的兜底都用它——块表坏了不影响聊天，
     * 但会让首屏 `MAX(blockIndex)` 指到一个没有消息的块，表现是「打开会话是空的」。
     */
    @Query("DELETE FROM message_blocks WHERE sessionId NOT IN (SELECT id FROM chat_sessions)")
    suspend fun deleteOrphans(): Int

    /**
     * 回收「块里一条消息都没有」的行。
     *
     * 只在**批量删除**（删人格 / 导入重建）之后调：单条删除由 [MessageBlockWriter] 逐块刷新，
     * 不需要全表扫。它守的是一种数据漂移：`messages.personaId` 与 `chat_sessions.personaId`
     * 万一不一致（异常导入、手工改库），按会话反查会漏掉几行块——那几行会让首屏指到一个空块。
     */
    @Query(
        """
        DELETE FROM message_blocks WHERE NOT EXISTS (
            SELECT 1 FROM messages m
            WHERE m.sessionId = message_blocks.sessionId AND m.blockIndex = message_blocks.blockIndex
        )
        """
    )
    suspend fun deleteEmpty(): Int

    @Query("SELECT COUNT(*) FROM message_blocks WHERE sessionId = :sessionId")
    suspend fun countInSession(sessionId: String): Int

    /** 检索用：一次枚举（可过滤人格/时间的）块，按 `(sessionId, blockIndex)` 升序。 */
    @RawQuery
    suspend fun searchBlocks(query: SupportSQLiteQuery): List<SearchBlockRow>

    /** 重建块表用：整段会话的消息顺序（只取三列，不 materialize 正文）。 */
    @Query("SELECT id, createdAt, blockIndex FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun assignmentRows(sessionId: String): List<MessageBlockAssignmentRow>
}
