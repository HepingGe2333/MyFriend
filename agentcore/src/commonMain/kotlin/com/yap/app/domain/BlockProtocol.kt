package com.yap.app.domain

import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue

/**
 * 「AI 主动拉黑」的两轮确认协议（`spec/experience/README.md` §9）。
 *
 * 为什么要有这个文件：拉黑是**模型能改变双方关系**的唯一工具，协议字段名、
 * 七键 JSON、三种返回形状必须两端与服务端逐字一致；此前 Android 的 `BlockPolicy`
 * 与 iOS / agentd 各拼一份字符串，改一个键名就是一个只在真机上才发现的 bug。
 *
 * 这里只做**编解码与形状**，不做「该不该拉黑」的决策（那是调用方/既有 `BlockPolicy` 的事）：
 * - [Proposal]：规范 §9 的 7 个字段；
 * - [encode] / [decode]：同一份字段顺序的 JSON 编解码（解码宽容、编码逐字）；
 * - [pendingJson] / [blockedJson] / [rejectedJson]：回灌给模型的三种返回形状；
 * - [isExpired]：10 分钟提议过期的纯判断。
 *
 * 与既有实现的关系：`app` 的 `BlockPolicy` / `BlockProposal` **保留不动**（迁移由后续 agent 做），
 * 但字段名、状态值、TTL、时长范围以本文件为准。
 */
object BlockProtocol {

    // ------------------------------------------------------------------ 状态常量

    /** 第一次调用：只产生提议，不生效。 */
    const val STATUS_PENDING_CONFIRMATION = "pending_confirmation"

    /** 确认成功：已生效到 `blockedUntil`。 */
    const val STATUS_BLOCKED = "blocked"

    /** 确认不成立：带一个稳定 code，UI 不直接展示英文。 */
    const val STATUS_REJECTED = "rejected"

    /** 三种合法状态（测试与宽容解析共用）。 */
    val STATUSES: List<String> = listOf(STATUS_PENDING_CONFIRMATION, STATUS_BLOCKED, STATUS_REJECTED)

    // ------------------------------------------------------------------ 字段名

    const val FIELD_PROPOSAL_ID = "proposalId"
    const val FIELD_PERSONA_ID = "personaId"
    const val FIELD_HOURS = "hours"
    const val FIELD_REASON = "reason"
    const val FIELD_USER_MESSAGE_ID = "userMessageId"
    const val FIELD_PROPOSED_AT = "proposedAt"
    const val FIELD_EXPIRES_AT = "expiresAt"

    const val FIELD_STATUS = "status"
    const val FIELD_BLOCKED_UNTIL = "blockedUntil"
    const val FIELD_CODE = "code"

    /**
     * 规范 §9 固定的七个键（顺序即编码顺序）。
     *
     * 测试用它钉住「没有多余字段」：多写一个 `sessionId`，服务端与客户端读的就不是同一份协议。
     */
    val PROPOSAL_FIELDS: List<String> = listOf(
        FIELD_PROPOSAL_ID,
        FIELD_PERSONA_ID,
        FIELD_HOURS,
        FIELD_REASON,
        FIELD_USER_MESSAGE_ID,
        FIELD_PROPOSED_AT,
        FIELD_EXPIRES_AT
    )

    /** `pending_confirmation` 返回形状恰好这两个键。 */
    val PENDING_FIELDS: List<String> = listOf(FIELD_STATUS, FIELD_EXPIRES_AT)

    /** `blocked` 返回形状恰好这两个键。 */
    val BLOCKED_FIELDS: List<String> = listOf(FIELD_STATUS, FIELD_BLOCKED_UNTIL)

    /** `rejected` 返回形状恰好这两个键。 */
    val REJECTED_FIELDS: List<String> = listOf(FIELD_STATUS, FIELD_CODE)

    // ------------------------------------------------------------------ 规则常量

    /** 未确认的提议 10 分钟过期（规范 §9）。 */
    const val PROPOSAL_TTL_MS: Long = 10 * 60 * 1000L

    /** 拉黑时长下界：1 小时。 */
    const val MIN_HOURS = 1

    /** 拉黑时长上界：24 小时。 */
    const val MAX_HOURS = 24

    /** 模型没给时长时的默认值（沿用既有实现）。 */
    const val DEFAULT_HOURS = 12

    /** 理由最多 120 字（规范 §9；只给模型/自己看）。 */
    const val MAX_REASON_CHARS = 120

    /**
     * 规范 §9 的七字段提议。
     *
     * @param proposalId 提议的稳定 id（UUID）；过期后重新提议才会换一个
     * @param personaId 谁被拉黑（人格 id）
     * @param hours 提议时长 1..24；确认时以这里的值为准
     * @param reason 为什么想拉黑（≤120 字，可以空）
     * @param userMessageId 提议时那条用户消息的 id；确认必须来自之后的一轮（id 不同）
     * @param proposedAt 第一次调用 `block_user` 的时间（毫秒）
     * @param expiresAt 提议作废时间 = proposedAt + 10 分钟；不会被续期覆盖
     */
    data class Proposal(
        val proposalId: String = "",
        val personaId: String = "",
        val hours: Int = DEFAULT_HOURS,
        val reason: String = "",
        val userMessageId: String = "",
        val proposedAt: Long = 0L,
        val expiresAt: Long = 0L
    )

    /** 把时长夹到 1..24（工具参数与解码共用）。 */
    fun clampHours(hours: Int): Int = hours.coerceIn(MIN_HOURS, MAX_HOURS)

    /** 由第一次提议的时间推出作废时间（10 分钟）。 */
    fun expiresAtFor(proposedAt: Long): Long = proposedAt + PROPOSAL_TTL_MS

    /**
     * 规整一条提议：去首尾空白、时长夹到 1..24、理由截到 120 字。
     *
     * 时间戳原样保留：`proposedAt <= 0` 是坏数据，由调用方按「没有提议」处理，
     * 编解码层不替它编一个现在时间（那会让测试和回放不可重复）。
     */
    fun sanitize(proposal: Proposal): Proposal = proposal.copy(
        proposalId = proposal.proposalId.trim(),
        personaId = proposal.personaId.trim(),
        hours = clampHours(proposal.hours),
        reason = proposal.reason.trim().take(MAX_REASON_CHARS),
        userMessageId = proposal.userMessageId.trim()
    )

    /**
     * 编码成规范 §9 的七键 JSON（字段顺序固定、缺省字段也写出）。
     *
     * 用共享 JSON 序列化器而不是手拼字符串：`reason` 里可能有引号、换行、反斜杠，
     * 手拼会写出坏 JSON；序列化器的转义规则两端一致，字段顺序由 [PROPOSAL_FIELDS] 钉住。
     */
    fun encode(proposal: Proposal): String {
        val fields = LinkedHashMap<String, JsonValue>()
        fields[FIELD_PROPOSAL_ID] = JsonValue.Str(proposal.proposalId)
        fields[FIELD_PERSONA_ID] = JsonValue.Str(proposal.personaId)
        fields[FIELD_HOURS] = JsonValue.Num(proposal.hours.toString())
        fields[FIELD_REASON] = JsonValue.Str(proposal.reason)
        fields[FIELD_USER_MESSAGE_ID] = JsonValue.Str(proposal.userMessageId)
        fields[FIELD_PROPOSED_AT] = JsonValue.Num(proposal.proposedAt.toString())
        fields[FIELD_EXPIRES_AT] = JsonValue.Num(proposal.expiresAt.toString())
        return Json.stringify(JsonValue.Obj(fields))
    }

    /**
     * 解码七键 JSON；不是对象、JSON 坏掉、或没有 `proposalId` 时返回 null。
     *
     * 宽容的地方：多余键忽略；缺省字符串按空串、缺省 `hours` 按 [DEFAULT_HOURS]、
     * 缺省时间戳按 0；`hours` 越界夹到 1..24、`reason` 超长截到 120 字。
     * 这些都不改变合法输入的往返结果，只让坏输入落在一个可执行的值域里。
     */
    fun decode(raw: String?): Proposal? {
        val root = parseObject(raw) ?: return null
        val proposalId = root.stringField(FIELD_PROPOSAL_ID)?.trim().orEmpty()
        if (proposalId.isEmpty()) return null
        val hoursRaw = root.longField(FIELD_HOURS) ?: DEFAULT_HOURS.toLong()
        return Proposal(
            proposalId = proposalId,
            personaId = root.stringField(FIELD_PERSONA_ID).orEmpty().trim(),
            hours = hoursRaw.coerceIn(MIN_HOURS.toLong(), MAX_HOURS.toLong()).toInt(),
            reason = root.stringField(FIELD_REASON).orEmpty().trim().take(MAX_REASON_CHARS),
            userMessageId = root.stringField(FIELD_USER_MESSAGE_ID).orEmpty().trim(),
            proposedAt = root.longField(FIELD_PROPOSED_AT) ?: 0L,
            expiresAt = root.longField(FIELD_EXPIRES_AT) ?: 0L
        )
    }

    // ------------------------------------------------------------------ 返回形状

    /**
     * 回灌给模型的 pending 返回：`{"status":"pending_confirmation","expiresAt":…}`。
     *
     * 不带 `proposalId`：模型不需要（它只能看到这一行），多一个键就多一处两端可能不一致。
     */
    fun pendingJson(expiresAt: Long): String = statusJson(
        status = STATUS_PENDING_CONFIRMATION,
        numberField = FIELD_EXPIRES_AT,
        number = expiresAt
    )

    /** 回灌给模型的 blocked 返回：`{"status":"blocked","blockedUntil":…}`。 */
    fun blockedJson(blockedUntil: Long): String = statusJson(
        status = STATUS_BLOCKED,
        numberField = FIELD_BLOCKED_UNTIL,
        number = blockedUntil
    )

    /** 确认不成立：`{"status":"rejected","code":"…"}`（code 是稳定字符串，UI 不直接展示）。 */
    fun rejectedJson(code: String): String {
        val fields = LinkedHashMap<String, JsonValue>()
        fields[FIELD_STATUS] = JsonValue.Str(STATUS_REJECTED)
        fields[FIELD_CODE] = JsonValue.Str(code.trim())
        return Json.stringify(JsonValue.Obj(fields))
    }

    /** 解析任意一种返回形状里的 `status`；坏 JSON/没有 status 返回 null。 */
    fun statusOf(raw: String?): String? =
        parseObject(raw)?.stringField(FIELD_STATUS)?.trim()?.takeIf { it.isNotEmpty() }

    /** 是不是三种合法状态之一。 */
    fun isKnownStatus(status: String?): Boolean = status != null && status in STATUSES

    /**
     * 提议是否已经作废：`now` 早于 `proposedAt`（时钟倒退）也按过期处理，宁可不生效；
     * 到期即视为解除（读路径不依赖清理任务）。
     */
    fun isExpired(proposal: Proposal, now: Long): Boolean =
        now < proposal.proposedAt || now >= proposal.expiresAt

    // ------------------------------------------------------------------ 内部

    /** 生成 `{"status":…,"<numberField>":<number>}`（固定两键、固定顺序）。 */
    private fun statusJson(status: String, numberField: String, number: Long): String {
        val fields = LinkedHashMap<String, JsonValue>()
        fields[FIELD_STATUS] = JsonValue.Str(status)
        fields[numberField] = JsonValue.Num(number.toString())
        return Json.stringify(JsonValue.Obj(fields))
    }

    private fun parseObject(raw: String?): JsonValue.Obj? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { Json.parse(text) }.getOrNull() as? JsonValue.Obj
    }

    private fun JsonValue.Obj.stringField(key: String): String? =
        (fields[key] as? JsonValue.Str)?.value

    private fun JsonValue.Obj.longField(key: String): Long? =
        (fields[key] as? JsonValue.Num)?.asLong
}
