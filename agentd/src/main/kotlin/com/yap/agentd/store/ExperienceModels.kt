package com.yap.agentd.store

import com.yap.agentd.store.StoreJson.nullableStr
import com.yap.agentd.store.StoreJson.num
import com.yap.agentd.store.StoreJson.obj
import com.yap.agentd.store.StoreJson.optLong
import com.yap.agentd.store.StoreJson.optString
import com.yap.agentd.store.StoreJson.reqLong
import com.yap.agentd.store.StoreJson.reqString
import com.yap.agentd.store.StoreJson.str
import com.yap.app.core.json.JsonValue

/**
 * 0.14 体验功能的三块服务端状态：**拉黑提案 / 心理活动 / 会话记忆**。
 *
 * 与 `FeatureModels.kt` 同样的取舍：它们都是「服务端自己跑、客户端只同步」的状态，
 * 键名就是磁盘契约（`block_proposals.json` / `mental_activities.json` / `session_memory.json`），
 * 改名 = 老数据读不出来。
 */

// ---------------------------------------------------------------------- block_proposals.json

/**
 * 一次**还没生效**的拉黑提议（`block_user` 的两轮确认）。
 *
 * `spec/experience/README.md` §9 把 `blockProposalJson` 的字段定死了：
 * `proposalId / personaId / hours / reason / userMessageId / proposedAt / expiresAt`。
 * 这七个键就是**两端与服务端共用的 wire 形状**（`GET /v1/state` 的
 * `personas[].blockProposalJson` 原样是这段 JSON 的字符串），因此：
 * - 不再有 `sessionId`（一人格一会话，personaId 就够定位；服务端不再需要它做确认判断）；
 * - 不再有 `afterUserCount`（确认规则就是「`userMessageId` 不同」= 之后的一轮）。
 *
 * 10 分钟过期：`expiresAt = proposedAt + TTL_MS`，超时的提议直接丢弃，下一次调用又回到
 * 「第一次」。**未过期时再次提议不续期**：返回同一条（同 proposalId/proposedAt/expiresAt），
 * 否则模型可以一直拖着不确认、把提议无限延长。
 */
data class BlockProposal(
    /** 提议 id；同一条提议在过期前重复提议不会变。 */
    val proposalId: String,
    val personaId: String,
    /** 提议时那条用户消息的 id（用来判断「是不是之后的一轮」）。 */
    val userMessageId: String,
    /** 拉黑时长（小时），1–24。 */
    val hours: Int,
    val reason: String,
    val proposedAt: Long
) {
    /** 规范 §9 的 `expiresAt`：10 分钟后作废；由 [proposedAt] 派生，不单独存。 */
    val expiresAt: Long get() = proposedAt + TTL_MS

    /**
     * `expiresAt` 是**作废时刻**：`now >= expiresAt` 即过期（与客户端 `now < expiresAt` 有效一致）。
     * 时钟回拨（`now < proposedAt`）不当作过期，避免把刚建的提议误删。
     */
    fun isExpired(now: Long): Boolean = now >= expiresAt

    /** 规范 §9 的固定 7 字段（客户端 `blockProposalJson` 同一份）。 */
    fun toJson(): JsonValue.Obj = obj(
        "proposalId" to str(proposalId),
        "personaId" to str(personaId),
        "hours" to num(hours),
        "reason" to str(reason),
        "userMessageId" to str(userMessageId),
        "proposedAt" to num(proposedAt),
        "expiresAt" to num(expiresAt)
    )

    companion object {
        /** 未确认的提议多久后作废（规范 §9：10 分钟）。 */
        const val TTL_MS: Long = 10 * 60 * 1000L

        /** 拉黑时长的取值范围（规范 §2：1–24 小时）。 */
        const val MIN_HOURS = 1
        const val MAX_HOURS = 24

        /** `reason` 的字数上限（与客户端 `BlockPolicy.MAX_REASON_CHARS` 一致）。 */
        const val MAX_REASON_CHARS = 120

        fun fromJson(value: JsonValue.Obj, where: String): BlockProposal = BlockProposal(
            // 老的 block_proposals.json 没有 proposalId：读回时补一个稳定的 id（磁盘上是被
            // 覆盖重写的，不影响 wire 契约；真的丢了就当成一条新提议也无伤大雅）。
            proposalId = value.optString("proposalId", where)
                ?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
            personaId = value.reqString("personaId", where),
            userMessageId = value.optString("userMessageId", where).orEmpty(),
            hours = (value.optLong("hours", where) ?: MIN_HOURS.toLong()).toInt()
                .coerceIn(MIN_HOURS, MAX_HOURS),
            reason = value.optString("reason", where).orEmpty().take(MAX_REASON_CHARS),
            proposedAt = value.optLong("proposedAt", where) ?: 0L
        )
    }
}

// ---------------------------------------------------------------------- mental_activities.json

/**
 * 一次「心理活动」：他私下的一个念头 + 那一刻的心情快照（`spec/experience/README.md` §3）。
 *
 * 念头**不发出去**，只写进这里、并在下一轮提示词里以「你刚才心里想：…」注入。
 * [moodLabel] / 四个维度是**做完微调之后**的快照，这样「念头怎么改变了心情」有据可查。
 */
data class MentalActivity(
    val id: String,
    val personaId: String,
    val sessionId: String,
    val at: Long,
    /** 私密念头（≤60 字）。 */
    val thought: String,
    val moodLabel: String,
    val moodValence: Int,
    val moodArousal: Int,
    val moodDominance: Int,
    val moodIntensity: Int
) {
    fun toJson(): JsonValue.Obj = obj(
        "id" to str(id),
        "personaId" to str(personaId),
        "sessionId" to str(sessionId),
        "at" to num(at),
        "thought" to str(thought),
        "moodLabel" to str(moodLabel),
        "moodValence" to num(moodValence),
        "moodArousal" to num(moodArousal),
        "moodDominance" to num(moodDominance),
        "moodIntensity" to num(moodIntensity)
    )

    companion object {
        /** 念头字数上限（规范 §3：≤60 字）。 */
        const val MAX_THOUGHT_CHARS = 60

        /** 每个人格保留最近的多少条心理活动（规范 §3）。 */
        const val KEEP_RECENT = 50

        fun fromJson(value: JsonValue.Obj, where: String): MentalActivity {
            val id = value.reqString("id", where)
            if (!FileStore.isValidId(id)) throw StoreCorruptionException("$where 的 id 不合法")
            val personaId = value.reqString("personaId", where)
            if (!FileStore.isValidId(personaId)) throw StoreCorruptionException("$where 的 personaId 不合法")
            val at = value.optLong("at", where) ?: 0L
            return MentalActivity(
                id = id,
                personaId = personaId,
                sessionId = value.optString("sessionId", where).orEmpty(),
                at = at,
                thought = value.optString("thought", where).orEmpty().take(MAX_THOUGHT_CHARS),
                moodLabel = value.optString("moodLabel", where).orEmpty(),
                moodValence = (value.optLong("moodValence", where) ?: 0L).toInt(),
                moodArousal = (value.optLong("moodArousal", where) ?: 2L).toInt(),
                moodDominance = (value.optLong("moodDominance", where) ?: 3L).toInt(),
                moodIntensity = (value.optLong("moodIntensity", where) ?: 2L).toInt()
            )
        }
    }
}

// ---------------------------------------------------------------------- session_memory.json

/**
 * 一段会话的「他的备忘录」：短期摘要 + 长期摘要（`spec/experience/README.md` §6）。
 *
 * 两个游标都是**该会话里非拉黑用户消息的累计条数**（不是 id）：`current - shortUserMessages >= 5`
 * 就更新短期、`>= 30` 就更新长期。用累计条数而不是「最后一条消息 id」，重启后也不用重新校准。
 *
 * 它们作为**可变区域**注入系统提示词（短期在前、长期在后），**不写进人设**：
 * 人设是「他是谁」，备忘录是「他最近记住了什么」。
 */
data class SessionMemory(
    val sessionId: String,
    val personaId: String,
    /** 每 5 条用户消息更新一次，≤200 字，覆盖最近几轮。 */
    val shortSummary: String = "",
    val shortUpdatedAt: Long = 0L,
    /** 上次更新短期摘要时该会话的用户消息条数。 */
    val shortUserMessages: Long = 0L,
    /** 每 30 条用户消息更新一次，≤500 字，覆盖更早的对话。 */
    val longSummary: String = "",
    val longUpdatedAt: Long = 0L,
    val longUserMessages: Long = 0L
) {
    fun hasAnySummary(): Boolean = shortSummary.isNotBlank() || longSummary.isNotBlank()

    fun toJson(): JsonValue.Obj = obj(
        "sessionId" to str(sessionId),
        "personaId" to str(personaId),
        "shortSummary" to str(shortSummary),
        "shortUpdatedAt" to num(shortUpdatedAt),
        "shortUserMessages" to num(shortUserMessages),
        "longSummary" to str(longSummary),
        "longUpdatedAt" to num(longUpdatedAt),
        "longUserMessages" to num(longUserMessages)
    )

    fun toBackupJson(): JsonValue.Obj = obj(
        "sessionId" to str(sessionId),
        "personaId" to str(personaId),
        "shortSummary" to nullableStr(shortSummary.takeIf { it.isNotBlank() }),
        "shortUpdatedAt" to num(shortUpdatedAt),
        "shortUserMessages" to num(shortUserMessages),
        "longSummary" to nullableStr(longSummary.takeIf { it.isNotBlank() }),
        "longUpdatedAt" to num(longUpdatedAt),
        "longUserMessages" to num(longUserMessages)
    )

    companion object {
        /** 短期摘要的字数上限（规范 §6）。 */
        const val MAX_SHORT_CHARS = 200

        /** 长期摘要的字数上限（规范 §6）。 */
        const val MAX_LONG_CHARS = 500

        /** 每多少条用户消息更新一次短期摘要。 */
        const val SHORT_EVERY = 5L

        /** 每多少条用户消息更新一次长期摘要。 */
        const val LONG_EVERY = 30L

        fun fromJson(value: JsonValue.Obj, where: String): SessionMemory {
            val sessionId = value.reqString("sessionId", where)
            if (!FileStore.isValidId(sessionId)) throw StoreCorruptionException("$where 的 sessionId 不合法")
            val personaId = value.reqString("personaId", where)
            if (!FileStore.isValidId(personaId)) throw StoreCorruptionException("$where 的 personaId 不合法")
            return SessionMemory(
                sessionId = sessionId,
                personaId = personaId,
                shortSummary = value.optString("shortSummary", where).orEmpty().take(MAX_SHORT_CHARS),
                shortUpdatedAt = value.optLong("shortUpdatedAt", where) ?: 0L,
                shortUserMessages = value.optLong("shortUserMessages", where) ?: 0L,
                longSummary = value.optString("longSummary", where).orEmpty().take(MAX_LONG_CHARS),
                longUpdatedAt = value.optLong("longUpdatedAt", where) ?: 0L,
                longUserMessages = value.optLong("longUserMessages", where) ?: 0L
            )
        }
    }
}
