package com.myfriend.app.data.repo

import androidx.room.withTransaction
import com.myfriend.app.data.local.MessageBlockDao
import com.myfriend.app.data.local.MessageBlockEntity
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.MessageEntity
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.domain.MessageBlocks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话历史区块（`messages.blockIndex` + `message_blocks`）的**唯一**写入入口。
 *
 * 规范 `spec/messages-blocks/README.md` §2 的三条规则（追加 / 按锚点插入 / 删除）在这里落库，
 * 规则本身是纯函数（[MessageBlocks]），本类只负责「读 DAO → 问规则 → 写 DAO」。
 *
 * ## 为什么必须集中
 *
 * 块元数据是**派生但落库**的数据：漏一条写入路径，就会出现「块里 201 条」「首屏 MAX(blockIndex)
 * 指到一个空块」「检索枚举到一块但读出来是空的」。写过这些路径的入口一共有：
 *
 * | 路径 | 入口 |
 * | --- | --- |
 * | 用户发消息 | `ChatRepository.sendUserMessage` |
 * | 助手回复 / 中断 / 失败 | `ChatRepository.generateAssistant` / `saveInterrupted` / `saveFailed` |
 * | 重新生成（删最后一条） | `ChatRepository.prepareRegeneration` |
 * | 编辑用户消息（锚点插入 + 删后续） | `ChatRepository.editUserMessage` |
 * | 删除单条消息 | `ChatRepository.deleteMessage` |
 * | 清空上下文 / 删除会话 | `ChatRepository.clearContext` / `deleteSession` |
 * | 主动消息（规则型 / 心跳） | `ProactiveRepository` / `AgentLoop` |
 * | 备份导入（替换 / 合并） | `BackupRepository`（插入后整段重建） |
 * | 删人格 / 清空全部 / 删除全部数据 | `RoomErasureStore`（`MESSAGES` 分支连带块表） |
 *
 * 契约测试（`MessageBlockContractTest`）用源码断言守住这份清单：除了本类，谁都不许直接
 * 调 `messageDao.insert` / `deleteById` / `deleteBySession` / `deleteByPersona` / `deleteAll`。
 *
 * ## 事务
 *
 * 每个方法自己保证原子性：已经在事务里就沿用外层事务（`RoomDatabase.inTransaction()`），
 * 否则自己开一个。这样调用方（发消息 + 更新会话预览）仍然是一个事务，块维护不会破坏它。
 */
@Singleton
class MessageBlockWriter @Inject constructor(
    private val database: MyFriendDatabase,
    private val messageDao: MessageDao,
    private val blockDao: MessageBlockDao
) {

    /**
     * 追加一条新消息（规范 §2 Append）：落进最后一块，满了才开新块。
     *
     * 调用方必须保证 [message] 在稳定序 `(createdAt, id)` 里排在最后
     * （发消息/回复都走 `MessageOrder.nextTimestamp`）。
     */
    suspend fun append(message: MessageEntity): MessageEntity = inTransaction {
        val last = blockDao.lastBlock(message.sessionId)
        val placement = MessageBlocks.planAppend(last?.toMeta(), message.ref())
        val positioned = message.copy(blockIndex = placement.blockIndex)
        messageDao.insert(positioned)
        blockDao.upsert(placement.meta.toEntity())
        positioned
    }

    /**
     * 按锚点插入（规范 §2 Insert at an anchor）：块归属跟着锚点走。
     *
     * 编辑用户消息就是「在它自己的位置重新落一条」（id / createdAt 不变），所以锚点块的
     * 编号就是目标块；块可能因此超出一条，规范允许且不重平衡。
     */
    suspend fun insertAtAnchor(message: MessageEntity, anchorBlockIndex: Int): MessageEntity =
        inTransaction {
            val positioned = message.copy(blockIndex = anchorBlockIndex)
            messageDao.insert(positioned)
            val meta = blockDao.get(message.sessionId, anchorBlockIndex)?.let { existing ->
                MessageBlocks.planInsertAtAnchor(existing.toMeta(), positioned.ref()).meta
            } ?: metaFromMessages(message.sessionId, anchorBlockIndex)
            blockDao.upsert(meta.toEntity())
            positioned
        }

    /**
     * 编辑用户消息：先移除旧行，再按锚点规则写回（同 id、同 createdAt、同块）。
     *
     * 这样「编辑」与「删除 + 锚点插入」在块表上的效果完全一致，不会出现两条路径各算一套元数据。
     */
    suspend fun replaceAtAnchor(message: MessageEntity): MessageEntity = inTransaction {
        messageDao.deleteById(message.id)
        refreshBlock(message.sessionId, message.blockIndex)
        // 复用公开的锚点插入入口：编辑与「删除 + 锚点插入」在块表上的效果完全一致。
        insertAtAnchor(message, message.blockIndex)
    }

    /**
     * 服务端同步：写入**服务端已经分好块**的消息，并按块内实际行重算元数据。
     *
     * 与 [append] 的区别：服务端模式下块编号由服务端决定（权威），不能再按本地
     * `planAppend` 重新分配——否则两边对「第几块」的理解会分叉，聊天页前翻会翻错。
     * 这里只做「按给定编号落库 + 让 `message_blocks` 与 `messages` 自洽」。
     *
     * 幂等：`insertAll` 是 REPLACE，同一条消息重复同步只是重写一行；块元数据每次按现有行重算，
     * 因此「同步同一快照两次」不会把块越堆越长。
     *
     * @param messages 同一块内的消息（每条自带的 `blockIndex` 会被 [blockIndex] 覆盖）
     */
    suspend fun applyServerBlock(
        sessionId: String,
        blockIndex: Int,
        messages: List<MessageEntity>
    ) = inTransaction {
        if (messages.isEmpty()) return@inTransaction Unit
        messageDao.insertAll(messages.map { it.copy(sessionId = sessionId, blockIndex = blockIndex) })
        // 服务端的块可能一次补齐多条（首次同步/前翻一页），所以这里不能用「追加」规则，
        // 而是按块内现有行重算首尾与条数（refreshBlock 对增长/缩短都成立）。
        refreshBlock(sessionId, blockIndex)
        Unit
    }

    /** 删除一条消息（规范 §2 Delete）：块会变短；空了就删掉块行并留下编号空洞。 */
    suspend fun deleteMessage(message: MessageEntity) = inTransaction {
        messageDao.deleteById(message.id)
        refreshBlock(message.sessionId, message.blockIndex)
        Unit
    }

    /**
     * 删除某个游标之后的全部消息（编辑重发用）。
     *
     * 先问「会波及哪几块」（只回编号），删完只重算那几块；横跨很多块时也就是几条 UPDATE，
     * 不会为了维护元数据去扫整段会话。
     */
    suspend fun deleteAfterCursor(sessionId: String, createdAt: Long, id: String) = inTransaction {
        val affected = messageDao.blockIndicesAfter(sessionId, createdAt, id)
        messageDao.deleteAfterCursor(sessionId, createdAt, id)
        affected.forEach { blockIndex -> refreshBlock(sessionId, blockIndex) }
        Unit
    }

    /** 清空一段会话的消息（清空上下文 / 删除会话）：块行整段删掉。 */
    suspend fun deleteSession(sessionId: String) = inTransaction {
        messageDao.deleteBySession(sessionId)
        blockDao.deleteBySession(sessionId)
        Unit
    }

    /**
     * 删一个人格的全部消息。
     *
     * **必须在 `chat_sessions` 里这个人的会话还在时调用**（`ErasurePlan` 的删除顺序是
     * `messages` 先于 `sessions`），因为块表上只有 `sessionId`，要靠会话反查 personaId。
     */
    suspend fun deletePersona(personaId: String) = inTransaction {
        messageDao.deleteByPersona(personaId)
        blockDao.deleteByPersona(personaId)
        // 兜底：`messages.personaId` 与 `chat_sessions.personaId` 不一致时（异常导入/手工改库），
        // 上面那条按会话反查会漏，这里按「块里还有没有消息」再回收一次。
        blockDao.deleteEmpty()
        Unit
    }

    /** 整库清空（清空全部数据 / 备份替换前的清空）。 */
    suspend fun deleteAll() = inTransaction {
        messageDao.deleteAll()
        blockDao.deleteAll()
        Unit
    }

    /**
     * 备份导入之后重建块表（规范 §5：块元数据不进备份，导入时按消息顺序重建）。
     *
     * 备份格式仍是 v2，替换/合并都可能在中间插入更早的消息，所以这里不复用「追加」增量维护，
     * 而是对受影响的会话整体重建：顺序 = `(createdAt, id)`，分配 = 追加规则的顺序重放
     * （[MessageBlocks.rebuild] 与迁移回填用的是同一份规则）。
     */
    suspend fun rebuildSessions(sessionIds: Collection<String>) = inTransaction {
        sessionIds.toSet().filter { it.isNotBlank() }.forEach { sessionId ->
            rebuildSessionInside(sessionId)
        }
        // 导入可能带来「会话不存在的历史块」（异常备份）：顺手回收，避免首屏指到空块。
        blockDao.deleteOrphans()
        Unit
    }

    /**
     * 备份导入：批量写入消息，然后**只**重建受影响的会话。
     *
     * 备份里没有块元数据（格式仍是 v2），所以这里统一「先写消息、再按顺序重建」；
     * 合并导入可能在已有历史中间插进更早的消息，重建会把它算进正确的块里。
     */
    suspend fun insertImported(messages: List<MessageEntity>) = inTransaction {
        if (messages.isEmpty()) return@inTransaction Unit
        messageDao.insertAll(messages)
        rebuildSessions(messages.map { it.sessionId }.toSet())
    }

    /** 单个会话重建（备份导入、以及测试里的自洽校验）。 */
    suspend fun rebuildSession(sessionId: String) = inTransaction {
        rebuildSessionInside(sessionId)
        Unit
    }

    // ---------------------------------------------------------------- 内部实现

    private suspend fun <T> inTransaction(block: suspend () -> T): T =
        if (database.inTransaction()) block() else database.withTransaction(block)

    /**
     * 按块里现有的消息重算这一块的元数据；块空了就整行删掉（规范 §2 Delete）。
     */
    private suspend fun refreshBlock(sessionId: String, blockIndex: Int) {
        val existing = blockDao.get(sessionId, blockIndex)
        val first = messageDao.firstInBlock(sessionId, blockIndex)
        val last = messageDao.lastInBlock(sessionId, blockIndex)
        val count = messageDao.countInBlock(sessionId, blockIndex)
        if (existing == null) {
            if (count > 0 && first != null && last != null) {
                blockDao.upsert(
                    MessageBlocks.Meta(
                        sessionId = sessionId,
                        blockIndex = blockIndex,
                        firstMessageId = first.id,
                        lastMessageId = last.id,
                        firstCreatedAt = first.createdAt,
                        lastCreatedAt = last.createdAt,
                        messageCount = count
                    ).toEntity()
                )
            }
            return
        }
        val updated = MessageBlocks.afterDelete(
            meta = existing.toMeta(),
            remainingFirst = first?.ref(),
            remainingLast = last?.ref(),
            remainingCount = count
        )
        if (updated == null) {
            blockDao.delete(sessionId, blockIndex)
        } else {
            blockDao.upsert(updated.toEntity())
        }
    }

    private suspend fun metaFromMessages(sessionId: String, blockIndex: Int): MessageBlocks.Meta {
        val first = messageDao.firstInBlock(sessionId, blockIndex)
            ?: error("empty block $sessionId#$blockIndex must not get metadata")
        val last = messageDao.lastInBlock(sessionId, blockIndex) ?: first
        val count = messageDao.countInBlock(sessionId, blockIndex)
        return MessageBlocks.Meta(
            sessionId = sessionId,
            blockIndex = blockIndex,
            firstMessageId = first.id,
            lastMessageId = last.id,
            firstCreatedAt = first.createdAt,
            lastCreatedAt = last.createdAt,
            messageCount = count
        )
    }

    /**
     * 整段重建：读顺序（只两列 + 当前编号）→ 删旧块行 → 每块一条 UPDATE + 一批块行。
     */
    private suspend fun rebuildSessionInside(sessionId: String) {
        val rows = blockDao.assignmentRows(sessionId)
        blockDao.deleteBySession(sessionId)
        if (rows.isEmpty()) return
        val metas = MessageBlocks.rebuild(
            sessionId = sessionId,
            refs = rows.map { MessageBlocks.Ref(it.id, sessionId, it.createdAt) }
        )
        metas.forEach { meta ->
            messageDao.assignBlockRange(
                sessionId = sessionId,
                blockIndex = meta.blockIndex,
                firstCreatedAt = meta.firstCreatedAt,
                firstId = meta.firstMessageId,
                lastCreatedAt = meta.lastCreatedAt,
                lastId = meta.lastMessageId
            )
        }
        blockDao.upsertAll(metas.map { it.toEntity() })
    }
}

// ---------------------------------------------------------------- 类型转换
//
// 放在文件级（internal）而不是塞进实体：实体要保持「纯 Room 表」，规则类型留在 domain。

internal fun MessageEntity.ref(): MessageBlocks.Ref =
    MessageBlocks.Ref(id = id, sessionId = sessionId, createdAt = createdAt)

internal fun MessageBlockEntity.toMeta(): MessageBlocks.Meta = MessageBlocks.Meta(
    sessionId = sessionId,
    blockIndex = blockIndex,
    firstMessageId = firstMessageId,
    lastMessageId = lastMessageId,
    firstCreatedAt = firstCreatedAt,
    lastCreatedAt = lastCreatedAt,
    messageCount = messageCount
)

internal fun MessageBlocks.Meta.toEntity(): MessageBlockEntity = MessageBlockEntity(
    sessionId = sessionId,
    blockIndex = blockIndex,
    firstMessageId = firstMessageId,
    lastMessageId = lastMessageId,
    firstCreatedAt = firstCreatedAt,
    lastCreatedAt = lastCreatedAt,
    messageCount = messageCount
)
