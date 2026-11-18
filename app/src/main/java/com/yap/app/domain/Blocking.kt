package com.yap.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 拉黑的两轮确认规则（`spec/experience/README.md` §2）。
 *
 * 全部是纯函数：不碰数据库、不读时钟。仓库只负责把 [BlockProposal] 原样存进
 * `personas.blockProposalJson`，把 [BlockPlan.Apply] 写进 `personas.blockedUntil`。
 */
@Serializable
data class BlockProposal(
    /** 第一次调用 `block_user` 的时间。 */
    val at: Long,
    /** 提议的时长（1–24 小时）。 */
    val hours: Int,
    /** 提议时对方已经有多少条用户消息；确认时必须比它多，保证中间真的隔了一轮。 */
    val afterUserCount: Int,
    val reason: String = ""
)

sealed interface BlockPlan {
    /** 第一次（或重复）调用：只返回 pending_confirmation，不生效。 */
    data class Pending(val proposal: BlockProposal, val renewed: Boolean) : BlockPlan

    /** 条件都满足：生效到 [until]。 */
    data class Apply(val until: Long, val reason: String) : BlockPlan

    /** 确认不成立；[code] 是给模型/测试看的稳定字符串，UI 不直接展示英文。 */
    data class Reject(val code: String) : BlockPlan
}

object BlockPolicy {

    const val PROPOSAL_TTL_MS = 10 * 60 * 1000L
    const val MIN_HOURS = 1
    const val MAX_HOURS = 24
    const val DEFAULT_HOURS = 12
    const val MAX_REASON_CHARS = 120

    const val REJECT_NO_PROPOSAL = "no_pending_proposal"
    const val REJECT_EXPIRED = "proposal_expired"
    const val REJECT_NEED_MORE_MESSAGES = "need_more_user_messages"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(proposal: BlockProposal): String = json.encodeToString(BlockProposal.serializer(), proposal)

    fun decode(raw: String?): BlockProposal? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { json.decodeFromString(BlockProposal.serializer(), text) }.getOrNull()
    }

    fun isExpired(proposal: BlockProposal, now: Long): Boolean =
        now < proposal.at || now - proposal.at >= PROPOSAL_TTL_MS

    /**
     * 一次 `block_user` 调用该怎么走。
     *
     * `confirm=false` 永远只产生/续用提议；`confirm=true` 必须同时满足：
     * 有未过期提议 + 现在的用户消息数严格大于提议时的数量（中间至少一条新用户消息）。
     * 有效期从第一次提议算，续用不会把 10 分钟重新计时（否则可以无限拖）。
     */
    fun plan(
        existing: BlockProposal?,
        confirm: Boolean,
        hours: Int,
        reason: String?,
        now: Long,
        userMessageCount: Int
    ): BlockPlan {
        val safeHours = hours.coerceIn(MIN_HOURS, MAX_HOURS)
        val safeReason = reason?.trim()?.take(MAX_REASON_CHARS).orEmpty()
        if (!confirm) {
            if (existing != null && !isExpired(existing, now)) {
                return BlockPlan.Pending(existing, renewed = false)
            }
            return BlockPlan.Pending(
                BlockProposal(
                    at = now,
                    hours = safeHours,
                    afterUserCount = userMessageCount.coerceAtLeast(0),
                    reason = safeReason
                ),
                renewed = true
            )
        }
        if (existing == null) return BlockPlan.Reject(REJECT_NO_PROPOSAL)
        if (isExpired(existing, now)) return BlockPlan.Reject(REJECT_EXPIRED)
        if (userMessageCount <= existing.afterUserCount) {
            return BlockPlan.Reject(REJECT_NEED_MORE_MESSAGES)
        }
        val until = now + existing.hours.coerceIn(MIN_HOURS, MAX_HOURS) * 60L * 60L * 1000L
        return BlockPlan.Apply(until = until, reason = existing.reason)
    }

    /** 当前是否处于拉黑中；到期即 false（读路径不依赖清理任务）。 */
    fun isBlocked(blockedUntil: Long?, now: Long): Boolean =
        blockedUntil != null && blockedUntil > now

    /** 给模型看的一句「现在还在拉黑期」；不写责怪对方的话。 */
    fun promptNote(until: Long, reason: String?, now: Long): String {
        if (!isBlocked(until, now)) return ""
        val minutesLeft = ((until - now) / 60_000L).coerceAtLeast(0L)
        val hours = ((minutesLeft + 59L) / 60L).coerceAtLeast(1L)
        val trimmed = reason?.trim().orEmpty()
        val reasonText = if (trimmed.isEmpty()) "" else "（你当时想的是：$trimmed）"
        return "你现在不想理对方$reasonText，大约还有 $hours 小时。" +
            "他发来的消息你都收得到，但这段时间不回也没关系；想提前缓过来也可以照常回。"
    }

    /** 界面是否要在一条用户消息旁显示「被拉黑」（红色感叹号）。 */
    fun showsBlockedIndicator(roleIsUser: Boolean, blocked: Boolean): Boolean =
        roleIsUser && blocked
}
