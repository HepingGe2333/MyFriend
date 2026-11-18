package com.yap.agentd.experience

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MentalActivity
import com.yap.agentd.store.Session
import java.time.Clock

/**
 * 0.14 体验功能的总入口：把「心理活动 / 会话记忆 / 拉黑到期」接到聊天与心跳上。
 *
 * 两个入口：
 * - [onChatCompleted]：`ChatService` 在 `done` 之后同步调用（每轮聊天一次）；
 * - [onTick]：`ProactiveTicker` 每拍调用（默认 60 秒），**不依赖任何客户端连接**——
 *   客户端关掉、手机锁屏，心理活动与会话记忆照样推进。
 *
 * 三个动作都不抛异常：任何失败只记一行日志（聊天已经成功回完了，不能被后台动作打挂）。
 */
class ExperienceService(
    private val store: FileStore,
    private val mental: MentalActivityService? = null,
    private val memory: SessionMemoryService? = null,
    private val clock: Clock = Clock.systemDefaultZone()
) {

    /** 一轮聊天之后的结果（日志/测试用，不进 HTTP）。 */
    data class ChatOutcome(
        val personaId: String,
        val sessionId: String,
        val blockedExpired: Int = 0,
        val mentalActivity: MentalActivity? = null,
        val memoryShortUpdated: Boolean = false,
        val memoryLongUpdated: Boolean = false
    )

    /** 一拍心跳的结果。 */
    data class TickOutcome(
        val now: Long,
        val blockedExpired: Int = 0,
        val proposalsExpired: Int = 0,
        val mentalActivities: Int = 0,
        val memoriesUpdated: Int = 0
    )

    /**
     * 聊天结束：清理到期拉黑 → 更新会话记忆 → 检查心理活动（激动/变化大时必做）。
     *
     * 顺序有讲究：先清拉黑（后面的读取才是「此刻真实状态」），再做记忆/心理活动。
     */
    fun onChatCompleted(personaId: String, sessionId: String): ChatOutcome {
        val now = clock.millis()
        var outcome = ChatOutcome(personaId = personaId, sessionId = sessionId)
        outcome = outcome.copy(blockedExpired = runCatching { store.expireBlocks(now) }.getOrDefault(0))
        runCatching { store.expireBlockProposals(now) }

        memory?.let { service ->
            val result = runCatching { service.maybeUpdate(sessionId, now) }.getOrNull()
            if (result != null) {
                outcome = outcome.copy(
                    memoryShortUpdated = result.shortUpdated,
                    memoryLongUpdated = result.longUpdated
                )
            }
        }

        mental?.let { service ->
            val activity = runCatching { service.maybeRun(personaId, sessionId, now, force = true) }.getOrNull()
            if (activity != null) outcome = outcome.copy(mentalActivity = activity)
        }
        return outcome
    }

    /**
     * 后台一拍：清到期状态、给「活跃窗口里」的每个人格做心理活动、补会话记忆。
     *
     * 活跃窗口由会话的 `lastMessageAt` 决定（10 分钟）；窗口外的不会打模型。
     */
    fun onTick(now: Long = clock.millis()): TickOutcome {
        var blockedExpired = 0
        var proposalsExpired = 0
        var mentalCount = 0
        var memoriesUpdated = 0
        blockedExpired = runCatching { store.expireBlocks(now) }.getOrDefault(0)
        proposalsExpired = runCatching { store.expireBlockProposals(now) }.getOrDefault(0)

        for (session in sessionsByPersona()) {
            val personaId = session.personaId
            memory?.let { service ->
                val result = runCatching { service.maybeUpdate(session.id, now) }.getOrNull()
                if (result != null && (result.shortUpdated || result.longUpdated)) memoriesUpdated += 1
            }
            mental?.let { service ->
                val activity = runCatching {
                    service.maybeRun(personaId, session.id, now, force = false)
                }.getOrNull()
                if (activity != null) mentalCount += 1
            }
        }
        return TickOutcome(
            now = now,
            blockedExpired = blockedExpired,
            proposalsExpired = proposalsExpired,
            mentalActivities = mentalCount,
            memoriesUpdated = memoriesUpdated
        )
    }

    /** 每个人格最近的会话（心理活动/记忆写在最近活跃的那个会话上）。 */
    private fun sessionsByPersona(): List<Session> = store.sessions()
        .groupBy { it.personaId }
        .values
        .mapNotNull { sessions ->
            sessions.maxByOrNull { it.lastMessageAt ?: it.createdAt }
        }
        .sortedBy { it.personaId }
}
