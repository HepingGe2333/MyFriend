package com.yap.agentd.delivery

import java.time.Clock

/**
 * 「客户端在不在前台」的服务端近似（`spec/experience` §8）。
 *
 * 服务端没有推送通道、也不该假装知道 App 的前台状态，所以用最直接的一个信号：
 * **最近一次 `/v1/ 路径` 请求的时刻**。默认窗口 [DEFAULT_WINDOW_MS]（60 秒）内的请求算「前台在线」：
 * - 在线：新 assistant 消息按 `visibleAt` 延迟投递，并先发 `typing.started`（客户端显示「正在输入…」）；
 * - 离线：消息直接可用，不发 typing（客户端重连后同步直接看到，不再补动画）。
 *
 * 时钟可注入：单测不 sleep、不依赖真实时间。
 */
class ClientPresence(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val windowMs: Long = DEFAULT_WINDOW_MS
) {

    @Volatile
    private var lastSeenAt: Long = 0L

    /** 记一次客户端活动（`AgentdServer` 对每个 `/v1/ 路径` 请求调用一次）。 */
    fun markSeen(now: Long = clock.millis()) {
        lastSeenAt = now
    }

    /** 现在算不算「前台在线」；从没连过 = 离线。 */
    fun isOnline(now: Long = clock.millis()): Boolean {
        val seen = lastSeenAt
        return seen > 0L && now - seen <= windowMs
    }

    /** 最近一次活动时刻（0 = 从没有过；测试/日志用）。 */
    fun lastSeenAt(): Long = lastSeenAt

    /** 重置（测试用；生产不需要）。 */
    fun reset() {
        lastSeenAt = 0L
    }

    companion object {
        /** 60 秒内有过请求就算在线（前台长轮询本身会不断刷新它）。 */
        const val DEFAULT_WINDOW_MS: Long = 60_000L
    }
}
