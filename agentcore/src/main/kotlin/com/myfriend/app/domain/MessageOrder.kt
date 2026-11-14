package com.myfriend.app.domain

/**
 * 消息在会话内的稳定顺序：`(createdAt, id)`（审计 P2-7）。
 *
 * ## 两个坑
 *
 * 1. **同一毫秒**：`createdAt` 由「读最新一条 + now」推算，两条路径并发写入（用户发送、
 *    后台主动消息 `AgentLoop.deliver`）完全可能落到同一毫秒。SQL 里排序是
 *    `ORDER BY createdAt, id`，同一毫秒的两条靠 id 决定先后——而 id 是随机 UUID，
 *    于是「谁先谁后」变成随机的，对话读起来错位。
 * 2. **`deleteAfter` 只删严格更晚**：编辑一条用户消息会删掉它之后的所有回复。
 *    如果助手回复和这条用户消息同一毫秒，`createdAt > :after` 就漏掉了它，
 *    编辑重发后旧回复还留在列表里。
 *
 * 现在的约定：会话内时间戳**严格递增**（[nextTimestamp]），删除/排序一律用
 * [compare] 给出的复合序，不再只看时间。
 */
object MessageOrder {

    /**
     * 下一条消息的时间戳：取 `max(now, 最新一条 + 1)`。
     *
     * 这样即使同一毫秒里连写两条，后一条也一定严格大于前一条；时钟回拨同样兜住。
     */
    fun nextTimestamp(latestCreatedAt: Long?, now: Long): Long {
        if (latestCreatedAt == null) return now
        return if (latestCreatedAt >= now) latestCreatedAt + 1 else now
    }

    /** 稳定序比较：负数表示 [first] 更早。 */
    fun compare(
        firstCreatedAt: Long,
        firstId: String,
        secondCreatedAt: Long,
        secondId: String
    ): Int {
        val byTime = firstCreatedAt.compareTo(secondCreatedAt)
        return if (byTime != 0) byTime else firstId.compareTo(secondId)
    }

    /** [first] 是否严格晚于 [second]（按稳定序）。 */
    fun isAfter(firstCreatedAt: Long, firstId: String, secondCreatedAt: Long, secondId: String): Boolean =
        compare(firstCreatedAt, firstId, secondCreatedAt, secondId) > 0
}
