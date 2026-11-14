package com.myfriend.app.domain

/**
 * 每段会话一个「世代号」：后台写入（历史压缩、生成收尾）在开始时记下世代，
 * 写回前再比一次，不一致就丢弃这次结果。
 *
 * ## 为什么需要它
 *
 * 长会话回复完之后，[com.myfriend.app.data.repo.HistoryCompressor] 会在后台跑一次
 * 模型调用（数秒），结束时把摘要写回 `chat_sessions.historySummary`。如果用户在这几秒里
 * 点了「清空上下文」或「删除这段对话」，清空事务先提交、压缩后提交，于是
 * 「消息已经删了、旧摘要又回来了」——下次开聊时那段被删掉的对话还会被当成
 * 「更早之前你记得的」注入 system prompt。
 *
 * 生成收尾（`saveInterrupted` / `saveFailed`）同理：`NonCancellable` 的落库可能
 * 排在删除事务之后，把半截回复写进已经删掉的会话。
 *
 * 世代号是这类「后台任务 vs 用户动作」竞态的统一闸门：清空 / 删除 / 替换时 `bump`，
 * 所有在途写入在自己的世代号过期后一律丢弃。比「取消协程」更可靠——取消是异步的，
 * 而世代比较是同步的、发生在写库之前。
 *
 * 纯 Kotlin（不碰 Android / 协程）：并发语义在 JVM 单测里直接验。
 */
class SessionGenerationRegistry {

    private val lock = Any()
    private val generations = HashMap<String, Long>()

    /** 当前世代号；从没 bump 过时是 0。 */
    fun current(sessionId: String): Long = synchronized(lock) {
        generations[sessionId] ?: 0L
    }

    /** 让这段会话的所有在途写入作废，返回新世代号。 */
    fun bump(sessionId: String): Long = synchronized(lock) {
        val next = (generations[sessionId] ?: 0L) + 1
        generations[sessionId] = next
        next
    }

    /**
     * 这次写入还有效吗。
     *
     * 语义是「开始时的世代必须等于现在的世代」：只要中间发生过清空 / 删除 / 替换，
     * 在途写入就一律作废。会话被删掉后再次新建时会拿到新的 sessionId，
     * 不会与旧世代撞上。
     */
    fun isCurrent(sessionId: String, generation: Long): Boolean =
        current(sessionId) == generation

    /** 会话彻底不存在了：把它的世代记录一并清掉，避免这张表跟着 id 无限增长。 */
    fun forget(sessionId: String) {
        synchronized(lock) { generations.remove(sessionId) }
    }
}
