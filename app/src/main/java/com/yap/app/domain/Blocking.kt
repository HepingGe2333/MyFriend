package com.yap.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * 拉黑的两轮确认规则（`spec/experience/README.md` §9）。
 *
 * 全部是纯函数：不碰数据库、不读时钟。仓库只负责把 [BlockProposal] 原样存进
 * `personas.blockProposalJson`，把 [BlockPlan.Apply] 写进 `personas.blockedUntil`。
 *
 * 0.14.1 起字段名与语义**与服务端 agentd 的 `blockProposalJson` 逐字一致**（§9）：
 * `proposalId/personaId/hours/reason/userMessageId/proposedAt/expiresAt`。
 * 没有 `sessionId`（一人格一会话，personaId 足以定位），也没有旧的 `afterUserCount`
 * ——「是不是之后的一轮」改用 `userMessageId` 判断（同一轮里连着调两次，id 相同）。
 */
@Serializable
data class BlockProposal(
    /** 提议的稳定 id（UUID）；过期后重新提议才会换一个。 */
    val proposalId: String,
    /** 谁被拉黑（人格 id）；工具执行时由调用方填入。 */
    val personaId: String = "",
    /** 提议的时长（1–24 小时）。确认时以这里的值为准，confirm 那次再传的 hours 不参与。 */
    val hours: Int,
    /** 为什么想拉黑（≤120 字，可以空）。只给模型/自己看。 */
    val reason: String = "",
    /** 提议时那条用户消息的 id；确认时必须来自之后的一轮（id 不同）。 */
    val userMessageId: String = "",
    /** 第一次调用 `block_user` 的时间。 */
    val proposedAt: Long,
    /** 提议作废时间 = proposedAt + 10 分钟；不会被续期覆盖。 */
    val expiresAt: Long
)

sealed interface BlockPlan {
    /** 第一次（或重复）调用：只返回 pending_confirmation，不生效。 */
    data class Pending(val proposal: BlockProposal, val renewed: Boolean) : BlockPlan

    /** 条件都满足：生效到 [until]（时长来自提议里的 [hours]）。 */
    data class Apply(val until: Long, val reason: String, val hours: Int) : BlockPlan

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
    const val REJECT_ALREADY_BLOCKED = "already_blocked"

    /** 规范 §9 固定的七个键（顺序即编码顺序）；测试用它钉住「没有多余字段」。 */
    val PROPOSAL_FIELDS = listOf(
        "proposalId",
        "personaId",
        "hours",
        "reason",
        "userMessageId",
        "proposedAt",
        "expiresAt"
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // 空字符串也是契约的一部分（服务端读的是同一份 7 键 JSON），不能被省略。
        encodeDefaults = true
    }

    fun encode(proposal: BlockProposal): String =
        json.encodeToString(BlockProposal.serializer(), proposal)

    /**
     * 宽松解码：认新 7 键，也认 0.14.0 写进库里的旧形状
     * `{at, hours, afterUserCount, reason}`（老库升级上来还有未过期的提议）。
     *
     * 旧提议没有 proposalId/userMessageId：补一个随机 proposalId、`userMessageId=""`。
     * 这不会让「同一轮确认」漏过去——确认时本轮 id 一定非空，与非空 id 比较即不同；
     * 但旧数据没有 personaId，调用方会在 [plan] 里用当前人格补上。
     */
    fun decode(raw: String?): BlockProposal? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val element = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        runCatching { json.decodeFromJsonElement(BlockProposal.serializer(), element) }.getOrNull()
            ?.let { return it }
        return decodeLegacy(element)
    }

    private fun decodeLegacy(element: JsonObject): BlockProposal? {
        fun text(key: String): String? = runCatching { element[key]?.jsonPrimitive?.content }.getOrNull()
        fun number(key: String): Long? = runCatching { element[key]?.jsonPrimitive?.longOrNull }.getOrNull()
        val proposedAt = number("proposedAt") ?: number("at") ?: return null
        val expiresAt = number("expiresAt") ?: (proposedAt + PROPOSAL_TTL_MS)
        val hours = (number("hours") ?: DEFAULT_HOURS.toLong()).toInt().coerceIn(MIN_HOURS, MAX_HOURS)
        return BlockProposal(
            proposalId = text("proposalId")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            personaId = text("personaId").orEmpty(),
            hours = hours,
            reason = text("reason").orEmpty().take(MAX_REASON_CHARS),
            userMessageId = text("userMessageId").orEmpty(),
            proposedAt = proposedAt,
            expiresAt = expiresAt
        )
    }

    /** 提议是否已经作废；时钟倒退（now < proposedAt）也按过期处理，宁可不生效。 */
    fun isExpired(proposal: BlockProposal, now: Long): Boolean =
        now < proposal.proposedAt || now >= proposal.expiresAt

    /**
     * 一次 `block_user` 调用该怎么走。
     *
     * `confirm=false` 永远只产生/沿用提议；未过期时返回**同一条**（proposalId/proposedAt/
     * expiresAt 都不变，与 §9「不续期」一致）。`confirm=true` 必须同时满足：
     * 有未过期提议 + 提议属于同一个人格 + 本轮 `userMessageId` 与提议里的不同
     * （即之后的一轮、中间至少一条新用户消息）。有效期从第一次提议算。
     */
    fun plan(
        existing: BlockProposal?,
        confirm: Boolean,
        hours: Int,
        reason: String?,
        now: Long,
        personaId: String,
        userMessageId: String
    ): BlockPlan {
        val safeHours = hours.coerceIn(MIN_HOURS, MAX_HOURS)
        val safeReason = reason?.trim()?.take(MAX_REASON_CHARS).orEmpty()
        val safeUserMessageId = userMessageId.trim()
        if (!confirm) {
            if (existing != null &&
                !isExpired(existing, now) &&
                (existing.personaId.isBlank() || existing.personaId == personaId)
            ) {
                // 旧形状没有 personaId：补上，但 keep proposalId / 时间不动。
                val normalized = if (existing.personaId.isBlank()) existing.copy(personaId = personaId) else existing
                return BlockPlan.Pending(normalized, renewed = false)
            }
            return BlockPlan.Pending(
                BlockProposal(
                    proposalId = UUID.randomUUID().toString(),
                    personaId = personaId,
                    hours = safeHours,
                    reason = safeReason,
                    userMessageId = safeUserMessageId,
                    proposedAt = now,
                    expiresAt = now + PROPOSAL_TTL_MS
                ),
                renewed = true
            )
        }
        if (existing == null) return BlockPlan.Reject(REJECT_NO_PROPOSAL)
        if (existing.personaId.isNotBlank() && existing.personaId != personaId) {
            return BlockPlan.Reject(REJECT_NO_PROPOSAL)
        }
        if (isExpired(existing, now)) return BlockPlan.Reject(REJECT_EXPIRED)
        // 没有新的用户消息（本轮 id 为空或与提议时相同）→ 还在同一轮，不能确认。
        if (safeUserMessageId.isBlank() || safeUserMessageId == existing.userMessageId) {
            return BlockPlan.Reject(REJECT_NEED_MORE_MESSAGES)
        }
        val until = now + existing.hours.coerceIn(MIN_HOURS, MAX_HOURS) * 60L * 60L * 1000L
        return BlockPlan.Apply(
            until = until,
            reason = existing.reason,
            hours = existing.hours.coerceIn(MIN_HOURS, MAX_HOURS)
        )
    }

    /** 当前是否处于拉黑中；到期即 false（读路径不依赖清理任务）。 */
    fun isBlocked(blockedUntil: Long?, now: Long): Boolean =
        blockedUntil != null && blockedUntil > now

    /**
     * 回灌给模型的 pending 文本（§9 原样 JSON，别无多余键）。
     *
     * 用字符串拼而不是再进一次序列化器：字段顺序/格式就是契约的一部分，测试逐字比对。
     */
    fun pendingJson(proposal: BlockProposal): String =
        """{"status":"pending_confirmation","expiresAt":${proposal.expiresAt}}"""

    /** 回灌给模型的 blocked 文本（§9 原样 JSON）。 */
    fun blockedJson(blockedUntil: Long): String =
        """{"status":"blocked","blockedUntil":$blockedUntil}"""

    /** 确认不成立时的稳定返回（服务端同形；UI 不直接展示英文 code）。 */
    fun rejectedJson(code: String): String =
        """{"status":"rejected","code":"$code"}"""

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
