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
import com.yap.app.domain.MemoryContract

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
 * 一段会话的「他的备忘录」：短期摘要 + 长期摘要 + 对话主题总结
 * （0.15 上下文/记忆 v2，`docs/design/context-memory-v2.md`）。
 *
 * 四个游标都是**该会话里非拉黑用户消息的累计条数**（不是 id）：用累计条数而不是
 * 「最后一条消息 id」，重启之后也不用重新校准。
 *
 * | 缓存 | 什么时候更新 | 上游常量 |
 * | --- | --- | --- |
 * | 短期 | 每 5 条，或模型认为「计划变了」时 | [SHORT_EVERY] = `MemoryContract.Summary.SHORT_UPDATE_EVERY_ROUNDS` |
 * | 长期 | 15–40 条之间由模型决定 | `MemoryContract.Summary.LONG_UPDATE_MIN_ROUNDS` / `_MAX_ROUNDS` |
 * | 主题 | 15–40 条之间，或话题明显切换时 | 同上 + [MemoryContract.Summary.TOPIC_*] |
 *
 * 「检查游标」与「更新游标」分开记：
 * - `shortUserMessages` / `longUserMessages` / `topicUserMessages` 是**上次真正写入**摘要时的条数；
 * - `longCheckedUserMessages` / `topicCheckedUserMessages` 是**上次问过模型**时的条数。
 * 模型说「这次不用更新」时只推进检查游标，所以下一轮检查在 15 条之后、最晚 40 条强制更新——
 * 这就是「15–40 轮由模型决定」的落点。
 *
 * 它们作为**可变区域**注入系统提示词，**不写进人设**：
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
    /** 每 15–40 条由模型决定是否更新，≤500 字，覆盖更早的对话。 */
    val longSummary: String = "",
    val longUpdatedAt: Long = 0L,
    val longUserMessages: Long = 0L,
    /** 上次**询问**模型要不要更新长期摘要时的条数（说「先不更新」时只推进它）。 */
    val longCheckedUserMessages: Long = 0L,
    /** 滚动主题总结：每段 ≤120 字、最多 12 段，带时间段。 */
    val topics: List<MemoryContract.TopicSegment> = emptyList(),
    val topicUpdatedAt: Long = 0L,
    /** 上次**写入**主题总结时的条数。 */
    val topicUserMessages: Long = 0L,
    /** 上次**询问**模型要不要更新主题总结时的条数。 */
    val topicCheckedUserMessages: Long = 0L
) {
    fun hasAnySummary(): Boolean =
        shortSummary.isNotBlank() || longSummary.isNotBlank() || topics.isNotEmpty()

    fun toJson(): JsonValue.Obj = obj(
        "sessionId" to str(sessionId),
        "personaId" to str(personaId),
        "shortSummary" to str(shortSummary),
        "shortUpdatedAt" to num(shortUpdatedAt),
        "shortUserMessages" to num(shortUserMessages),
        "longSummary" to str(longSummary),
        "longUpdatedAt" to num(longUpdatedAt),
        "longUserMessages" to num(longUserMessages),
        "longCheckedUserMessages" to num(longCheckedUserMessages),
        "topics" to topicsJson(topics),
        "topicUpdatedAt" to num(topicUpdatedAt),
        "topicUserMessages" to num(topicUserMessages),
        "topicCheckedUserMessages" to num(topicCheckedUserMessages)
    )

    fun toBackupJson(): JsonValue.Obj = obj(
        "sessionId" to str(sessionId),
        "personaId" to str(personaId),
        "shortSummary" to nullableStr(shortSummary.takeIf { it.isNotBlank() }),
        "shortUpdatedAt" to num(shortUpdatedAt),
        "shortUserMessages" to num(shortUserMessages),
        "longSummary" to nullableStr(longSummary.takeIf { it.isNotBlank() }),
        "longUpdatedAt" to num(longUpdatedAt),
        "longUserMessages" to num(longUserMessages),
        // 0.15 的可加键：客户端 v6 会忽略；服务端自己再导入时要原样带回。
        "longCheckedUserMessages" to num(longCheckedUserMessages),
        "topics" to topicsJson(topics),
        "topicUpdatedAt" to num(topicUpdatedAt),
        "topicUserMessages" to num(topicUserMessages),
        "topicCheckedUserMessages" to num(topicCheckedUserMessages)
    )

    companion object {
        /** 短期摘要的字数上限（`MemoryContract.Summary.SHORT_TERM_MAX_CHARS`）。 */
        const val MAX_SHORT_CHARS = MemoryContract.Summary.SHORT_TERM_MAX_CHARS

        /** 长期摘要的字数上限（`MemoryContract.Summary.LONG_TERM_MAX_CHARS`）。 */
        const val MAX_LONG_CHARS = MemoryContract.Summary.LONG_TERM_MAX_CHARS

        /** 每多少条用户消息更新一次短期摘要。 */
        const val SHORT_EVERY = MemoryContract.Summary.SHORT_UPDATE_EVERY_ROUNDS.toLong()

        /** 长期/主题总结的最小检查间隔（15 条）。 */
        const val LONG_EVERY_MIN = MemoryContract.Summary.LONG_UPDATE_MIN_ROUNDS.toLong()

        /** 长期/主题总结的最晚强制更新间隔（40 条）。 */
        const val LONG_EVERY_MAX = MemoryContract.Summary.LONG_UPDATE_MAX_ROUNDS.toLong()

        /** 主题总结最多几段（`MemoryContract.Summary.TOPIC_MAX_SEGMENTS`）。 */
        const val TOPIC_MAX_SEGMENTS = MemoryContract.Summary.TOPIC_MAX_SEGMENTS

        /** 主题总结每段最多几字。 */
        const val TOPIC_MAX_CHARS = MemoryContract.Summary.TOPIC_SEGMENT_MAX_CHARS

        /**
         * 主题总结 → JSON 数组（`[{"timeLabel":"2026-11-18 晚","summary":"…"}]`）。
         *
         * 空列表也写 `[]`：这样磁盘/备份里的键集稳定，读回来不用猜「没有 topics 键」的旧数据。
         */
        fun topicsJson(topics: List<MemoryContract.TopicSegment>): JsonValue.Arr = JsonValue.Arr(
            topics.mapTo(mutableListOf<JsonValue>()) { topic ->
                obj(
                    "timeLabel" to str(topic.timeLabel.take(32)),
                    "summary" to str(topic.summary.replace(Regex("\\s+"), " ").trim().take(TOPIC_MAX_CHARS))
                )
            }
        )

        /** JSON 数组或「JSON 数组字符串」都认（客户端 Room 列曾把列表存成字符串）。 */
        fun parseTopics(value: JsonValue?, where: String): List<MemoryContract.TopicSegment> {
            val array = when (value) {
                null, JsonValue.Null -> return emptyList()
                is JsonValue.Arr -> value
                is JsonValue.Str -> {
                    val text = value.value.trim()
                    if (text.isEmpty()) return emptyList()
                    (runCatching { com.yap.app.core.json.Json.parse(text) }.getOrNull() as? JsonValue.Arr)
                        ?: return emptyList()
                }

                else -> return emptyList()
            }
            return array.items.mapNotNull { item ->
                val element = item as? JsonValue.Obj ?: return@mapNotNull null
                val summary = (element.fields["summary"] as? JsonValue.Str)?.value
                    ?.replace(Regex("\\s+"), " ")?.trim()?.take(TOPIC_MAX_CHARS)
                    .orEmpty()
                if (summary.isEmpty()) return@mapNotNull null
                val label = (element.fields["timeLabel"] as? JsonValue.Str)?.value?.trim()?.take(32).orEmpty()
                MemoryContract.TopicSegment(timeLabel = label, summary = summary)
            }.takeLast(MemoryContract.Summary.TOPIC_MAX_SEGMENTS)
        }

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
                longUserMessages = value.optLong("longUserMessages", where) ?: 0L,
                longCheckedUserMessages = value.optLong("longCheckedUserMessages", where) ?: 0L,
                topics = parseTopics(value.fields["topics"], where),
                topicUpdatedAt = value.optLong("topicUpdatedAt", where) ?: 0L,
                topicUserMessages = value.optLong("topicUserMessages", where) ?: 0L,
                topicCheckedUserMessages = value.optLong("topicCheckedUserMessages", where) ?: 0L
            )
        }
    }
}

// ---------------------------------------------------------------------- proactive_failures.json

/**
 * 一次**没发出去的主动消息**（0.15「主动消息后台记录」）。
 *
 * 为什么要有这张表：主动消息完全在服务端生成，用户看不到它失败的瞬间。没有记录时，
 * 「他今天怎么一句话没说」只能靠翻 stderr；有了它，运维/诊断面板能回答
 * 「是没掷中、是生成失败、还是落库/投递失败」。
 *
 * 保留策略：只留最近 [KEEP_RECENT] 条（跨人格），按 `at` 从新到旧——它是诊断数据，
 * 不是业务数据，不该无限增长。备份里带上它（服务端专有段），换一台机器还能接着查。
 */
data class ProactiveFailure(
    val id: String,
    val personaId: String,
    /** 出问题时那段会话；没有会话时为 null。 */
    val sessionId: String?,
    val at: Long,
    /** 卡在哪一步：生成正文 / 落库 / 投递 / 推送（见 [Stage]）。 */
    val stage: String,
    /** 稳定错误码（例如 `generation_failed` / `llm_rate_limited`）；日志/客户端按它对照。 */
    val reason: String,
    /** 给人看的一句中文（不含 key、不含上游响应全文）。 */
    val detail: String,
    /** 当时已经生成的草稿（可能为空：生成阶段就失败了）。 */
    val text: String = ""
) {
    /** 四个阶段（code 是稳定字符串，改名字要同步备份/诊断文档）。 */
    object Stage {
        const val GENERATION = "generation"
        const val PERSISTENCE = "persistence"
        const val DELIVERY = "delivery"
        const val PUSH = "push"

        val ALL: List<String> = listOf(GENERATION, PERSISTENCE, DELIVERY, PUSH)
    }

    fun toJson(): JsonValue.Obj = obj(
        "id" to str(id),
        "personaId" to str(personaId),
        "sessionId" to nullableStr(sessionId),
        "at" to num(at),
        "stage" to str(stage),
        "reason" to str(reason),
        "detail" to str(detail),
        "text" to str(text)
    )

    companion object {
        /** 最多留多少条失败记录（诊断用，不需要全史）。 */
        const val KEEP_RECENT = 200

        /** detail 的字数上限（不把上游响应全文写进磁盘）。 */
        const val MAX_DETAIL_CHARS = 300

        fun fromJson(value: JsonValue.Obj, where: String): ProactiveFailure {
            val id = value.reqString("id", where)
            if (!FileStore.isValidId(id)) throw StoreCorruptionException("$where 的 id 不合法")
            val personaId = value.reqString("personaId", where)
            if (!FileStore.isValidId(personaId)) throw StoreCorruptionException("$where 的 personaId 不合法")
            val sessionId = value.optString("sessionId", where)?.takeIf { it.isNotBlank() }
            return ProactiveFailure(
                id = id,
                personaId = personaId,
                sessionId = sessionId,
                at = value.optLong("at", where) ?: 0L,
                stage = value.optString("stage", where).orEmpty().ifBlank { Stage.GENERATION },
                reason = value.optString("reason", where).orEmpty(),
                detail = value.optString("detail", where).orEmpty().take(MAX_DETAIL_CHARS),
                text = value.optString("text", where).orEmpty().take(500)
            )
        }
    }
}
