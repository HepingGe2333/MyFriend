package com.yap.app.data.repo

import com.yap.app.data.local.ChatSessionDao
import com.yap.app.data.local.MessageDao
import com.yap.app.data.local.MessageRole
import com.yap.app.data.local.SessionMemoryDao
import com.yap.app.domain.Memorize
import com.yap.app.domain.TopicSummary
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `memorize` 工具的数据侧（0.15）：只在**这段会话历史 + 主题总结**里找旧事。
 *
 * 任务分工：
 * - 检索与渲染规则全在 [Memorize]（纯函数、可单测）；
 * - 这里只做取数（最近 [HISTORY_LIMIT] 条可见消息 + 会话标题 + 主题总结）与
 *   「把这次回忆留给下一轮上下文」的小缓存。
 *
 * 为什么需要 pending 缓存：工具结果本身会回灌给模型（同一轮里它就能用），但设计文档要求
 * 「注入到**下一轮**上下文」。工具执行时把 `<回忆：…>` 存进这里，下一轮装配 system 时取走一次
 * 并清空——既不重复注入，也不会让「他想起来的事」只活在一轮里。
 */
@Singleton
class MemorizeRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val sessionDao: ChatSessionDao,
    private val sessionMemoryDao: SessionMemoryDao
) {

    /** 会话 → 下一轮要注入的 `<回忆：…>`（取一次就清空）。 */
    private val pending = ConcurrentHashMap<String, String>()

    /**
     * 在会话历史里回忆一次。
     *
     * @return 渲染好的 `<回忆：…>`、命中明细与是否为空
     */
    suspend fun recall(
        sessionId: String,
        query: String,
        now: Long = System.currentTimeMillis()
    ): Memorize.Recall {
        val session = runCatching { sessionDao.getById(sessionId) }.getOrNull()
        val rows = runCatching { messageDao.recentBySession(sessionId, HISTORY_LIMIT) }
            .getOrDefault(emptyList())
        val messages = rows.asReversed().mapNotNull { message ->
            if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) return@mapNotNull null
            if (message.content.isBlank()) return@mapNotNull null
            Memorize.HistoryMessage(
                sessionId = sessionId,
                sessionTitle = session?.title.orEmpty(),
                fromUser = message.role == MessageRole.USER,
                content = message.content,
                at = message.createdAt
            )
        }
        val topics = runCatching {
            sessionMemoryDao.get(sessionId)?.topicsJson
        }.getOrNull()?.let { TopicSummary.parse(it) }.orEmpty()
        return Memorize.recall(messages = messages, topics = topics, query = query, now = now)
    }

    /** 把这次回忆记下来，下一轮 system 里注入一次。 */
    fun stashForNextRound(sessionId: String, text: String) {
        if (sessionId.isBlank() || text.isBlank()) return
        pending[sessionId] = text
    }

    /** 取走下一轮要注入的回忆（取一次就清空）。 */
    fun takePending(sessionId: String): String? {
        if (sessionId.isBlank()) return null
        return pending.remove(sessionId)
    }

    /** 清理某个会话的待注入回忆（清空上下文 / 删除会话时调用）。 */
    fun clearPending(sessionId: String) {
        pending.remove(sessionId)
    }

    private companion object {
        /**
         * 上限 600 条：`recentBySession` 走 `createdAt DESC LIMIT`，不扫全表；
         * 600 条足够覆盖几百轮对话，又不至于把一年的历史一次读进内存。
         */
        const val HISTORY_LIMIT = 600
    }
}
