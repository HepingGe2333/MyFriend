package com.yap.app.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `agentd` 服务端权威 API（`docs/design/server-authoritative.md` §3）的线上 DTO。
 *
 * 设计原则（P1a 传输层只做「原样解析」，不做业务判断）：
 *
 * 1. **默认值齐全**：服务端是「可加字段」的，但已有字段也可能因为版本差异暂时缺失。
 *    解析器开了 `ignoreUnknownKeys`，这里的每个属性都给默认值，一条缺字段的消息不能把整次同步打挂；
 * 2. **时间一律 epoch 毫秒（Long）**，与客户端 Room 侧一致，映射器不需要做单位换算；
 * 3. **不在 DTO 里做 UI 映射**：`ServerChatSync` 负责把 DTO 翻成 UI 能直接用的
 *    [ServerHomeState] / [ServerChatState]，传输层保持与界面无关；
 * 4. 字段名与服务端 `StoreJson` 逐字对应（`serverTime`、`serverCursor`、`lastBlockIndex`…），
 *    服务端读的也是同一份契约，改名要两边一起改。
 */

/** `GET /v1/state?since=&personaId=` 的一次快照。 */
@Serializable
data class StateSnapshot(
    val serverTime: Long = 0,
    val personas: List<ServerPersona> = emptyList(),
    val sessions: List<ServerSession> = emptyList(),
    val messages: List<ServerMessage> = emptyList(),
    val mood: ServerMood? = null,
    val presence: ServerPresence? = null,
    val serverCursor: Long = 0,
    /**
     * 契约外的可加字段：首次同步（`since=0`）只给每个会话的最新一块。
     * 为 true 表示「还有更早的内容」，客户端用分页接口（[ServerApi.messages]）继续翻。
     */
    val hasMoreMessages: Boolean = false
)

/** 人格（P1a 只保留列表页需要的字段，其余字段由服务端可加、客户端忽略）。 */
@Serializable
data class ServerPersona(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String? = null,
    val updatedAt: Long = 0,
    /** 0.14 拉黑：服务端权威的到期时间；null = 未拉黑。 */
    val blockedUntil: Long? = null,
    val blockReason: String? = null,
    /**
     * 0.14.1 拉黑提议（§9 的七个固定字段，字符串形式）。
     *
     * 与服务端 `personas.blockProposalJson` 逐字一致；有未过期提议时才非空。
     * 客户端只把它镜像到本地缓存（本地模式下工具仍由本机执行时也能接着确认）。
     */
    val blockProposalJson: String? = null
)

/** 会话列表 / 快照里的一行会话。 */
@Serializable
data class ServerSession(
    val id: String = "",
    val personaId: String = "",
    val title: String = "",
    val lastMessageAt: Long? = null,
    val unread: Int = 0,
    /** 最新块编号；-1 表示这个会话还没有任何消息（与服务端 `FileStore` 的口径一致）。 */
    val lastBlockIndex: Int = -1,
    val messageCount: Long = 0,
    val updatedAt: Long = 0,
    val createdAt: Long = 0
)

/** 一条消息。与客户端 Room 的 `MessageEntity` 语义相同（块编号 + 角色 + 正文 + 附件）。 */
@Serializable
data class ServerMessage(
    val id: String = "",
    val sessionId: String = "",
    val blockIndex: Int = 0,
    val role: String = "",
    val content: String = "",
    val createdAt: Long = 0,
    val readAt: Long? = null,
    val attachments: List<String> = emptyList(),
    val source: String = "CHAT",
    val clientMessageId: String? = null,
    val replyTo: String? = null,
    /** 0.14：拉黑期间收到的用户消息；服务端也会永久排除在模型上下文之外。 */
    val blocked: Boolean = false,
    /**
     * 0.14.1 §8：服务端决定的可见时刻（毫秒时间戳；没有延迟时等于 [createdAt]）。
     *
     * **只用于协议镜像**：客户端收到 `message.created` 一律直接上屏、历史/同步路径也直接给，
     * 绝不按它自己再延迟一次（延迟由服务端在入队时算好）。
     */
    val visibleAt: Long = 0
)

/** 心情：服务端推进，客户端只展示。 */
@Serializable
data class ServerMood(
    val personaId: String = "",
    val label: String = "",
    val intensity: Int = 0,
    val updatedAt: Long = 0,
    /**
     * 0.13 的多维心情（`spec/mood/README.md`）：服务端 `Mood.toJson` 一直带这三个字段，
     * 老版本服务端没有时按 0/2/3 读出（与客户端 `MoodDimensions.BASELINE_*` 一致）。
     *
     * `/v1/state` 与 `mood.changed` 事件共用这个 DTO，两处都能直接写本地缓存。
     */
    val valence: Int = 0,
    val arousal: Int = 2,
    val dominance: Int = 3,
    val reason: String = ""
)

/** 在场状态：服务端推进，客户端只展示。 */
@Serializable
data class ServerPresence(
    val personaId: String = "",
    val kind: String = "",
    val detail: String = "",
    val updatedAt: Long = 0
)

/** `GET /v1/sessions?personaId=` 的容器（[ServerApi.sessions] 只把列表交给调用方）。 */
@Serializable
data class ServerSessionList(
    val serverTime: Long = 0,
    val sessions: List<ServerSession> = emptyList(),
    val serverCursor: Long = 0
)

/**
 * `POST /v1/sessions/{id}/messages/import` 的一条历史消息（0.12.1 bootstrap 全量推送）。
 *
 * 为什么单独一个 DTO 而不是复用 [ServerMessage]：
 * - 导入端只接受「可写字段」，`sessionId/blockIndex` 由服务端自己分配（块归属以服务端为准）；
 * - 客户端要在**不依赖服务端时钟**的情况下保留原消息时间：`createdAt` 必传、`readAt` 可空；
 * - `id` 是幂等键：服务端按它去重，重试同一批不会产生第二条；`clientMessageId` 是第二道
 *   保险（服务端如果自行分配 id，仍能按它认出「这是同一条导入消息」）。
 */
@Serializable
data class ServerMessageImport(
    val id: String = "",
    val role: String = "",
    val content: String = "",
    val createdAt: Long = 0,
    val readAt: Long? = null,
    val attachments: List<String> = emptyList(),
    val source: String = "IMPORT",
    val clientMessageId: String? = null,
    val replyTo: String? = null
)

/** `POST /v1/sessions/{id}/messages/import` 的响应；计数缺省也能接受（服务端版本差异）。 */
@Serializable
data class ServerMessageImportResult(
    val imported: Int = 0,
    val skipped: Int = 0,
    val sessionId: String = "",
    val session: ServerSession? = null
)

/**
 * `GET /v1/sessions/{id}/messages?beforeBlock=&afterBlock=` 的一页。
 *
 * 一页一块（服务端 `limitBlocks` 只接受 1）：客户端不拼 offset，
 * 前翻传 [beforeBlock]、后翻传 [afterBlock]，服务端按块表找相邻块（允许编号空洞）。
 */
@Serializable
data class ServerMessagePage(
    val sessionId: String = "",
    /** 这一页的块编号；翻过头（没有这一页）时服务端返回 null。 */
    val blockIndex: Int? = null,
    val hasMoreOlder: Boolean = false,
    val hasMoreNewer: Boolean = false,
    val messages: List<ServerMessage> = emptyList()
)

/** `GET /v1/events?since=&timeoutSeconds=&limit=` 的增量事件（长轮询）。 */
@Serializable
data class ServerEvents(
    val serverTime: Long = 0,
    /** 单调游标：客户端下次用它做 `since`。没事件时服务端原样返回，不能因此前移。 */
    val cursor: Long = 0,
    val events: List<ServerEvent> = emptyList()
)

/**
 * 增量事件。
 *
 * [type] 是 `message.created` / `mood.changed` / `presence.changed` 之一（服务端可加），
 * [payload] 保留原始 JSON：P1a 阶段只同步游标与类型，P2 再按类型解码具体内容，
 * 未知类型不应该让客户端丢掉整个批次。
 */
@Serializable
data class ServerEvent(
    val cursor: Long = 0,
    val type: String = "",
    val at: Long = 0,
    val sessionId: String? = null,
    val personaId: String? = null,
    val payload: JsonObject = JsonObject(emptyMap())
)

/** 一次回复的 token 用量（`done` 事件里下发）。 */
@Serializable
data class ServerUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0
)

/**
 * `GET /health` 的健康检查响应。
 *
 * 字段全部可缺省：这个接口的契约只保证「200 + JSON」，服务端版本升级时多/少字段
 * 都不该让「测试连接」失败。真正的失败判定是 HTTP 状态与 `ok`（缺省按 true 处理，
 * 因为老版本 agentd 的 /health 只回 `{"status":"ok"}`）。
 */
@Serializable
data class ServerHealth(
    val ok: Boolean = true,
    val status: String = "",
    val name: String = "",
    val version: String = "",
    val serverTime: Long = 0
) {
    /** 服务端明确自报不健康时才算失败（缺字段 = 老版本，按健康处理）。 */
    val healthy: Boolean get() = ok && !status.equals("error", ignoreCase = true)
}

/** `POST /v1/sessions` 的响应：`{created, session}`（已存在时 `created=false`）。 */
@Serializable
data class ServerSessionCreated(
    val created: Boolean = false,
    val session: ServerSession = ServerSession()
)

/** `POST /v1/sessions/{id}/read` 的响应。 */
@Serializable
data class ServerReadReceipt(
    val sessionId: String = "",
    val unread: Int = 0,
    val readAt: Long = 0,
    val marked: Int = 0,
    val session: ServerSession? = null
)

/**
 * `POST /v1/personas/{id}/activity` 的请求体（`spec/persona-guidance/README.md` §4）。
 *
 * 字段与客户端 `PersonaActivity` 同构；服务端会自己夹取 `minutes`、并校验 `until` 在未来。
 */
@Serializable
data class ServerActivityWrite(
    val kind: String = "OTHER",
    val activity: String = "",
    val minutes: Int? = null,
    val until: Long? = null,
    val availability: String = "BUSY"
)

/** `POST /v1/personas/{id}/activity` 的响应：只取活动；人格本体可能很大，不需要解析。 */
@Serializable
data class ServerActivityReport(
    val activity: ServerActivity? = null
)

/** 服务端归一化之后的活动。 */
@Serializable
data class ServerActivity(
    val kind: String = "",
    val activity: String = "",
    val until: Long = 0,
    val availability: String = ""
)

/** `POST /v1/push/register` 的响应只用来确认；字段缺省也接受。 */
@Serializable
data class ServerPushRegistration(
    val registered: Boolean = true,
    val platform: String = ""
)

/**
 * `POST /v1/backup` 的响应：只带「导入到服务端之后各段有多少条」。
 *
 * 服务端已经完成合并/替换；客户端把这些数字换算成界面摘要，不再用本机备份的条数冒充结果
 * （合并模式下服务端可能保留了本机没有的条目）。
 */
@Serializable
data class ServerBackupImport(
    val mode: String = "",
    val imported: ServerBackupCounts = ServerBackupCounts()
)

/** 服务端导入结果的分段计数。 */
@Serializable
data class ServerBackupCounts(
    val personas: Int = 0,
    val sessions: Int = 0,
    val messages: Int = 0,
    val memories: Int = 0,
    val presets: Int = 0,
    /** 设置段是否成功写入（不是条数）。 */
    val settings: Boolean = false
)

// ---------------------------------------------------------------------------
// 0.13：云端情绪 / 人格进化 / 配置推送
// ---------------------------------------------------------------------------

/**
 * `POST /v1/personas/{id}/mood/evaluate` 的响应。
 *
 * `source` 说明这次结果怎么来的：`llm`（模型判定）、`decay`（失败后按时间衰减）、
 * `unchanged`（没明显变化）。**失败/服务端没配模型也是 200**，所以客户端不能靠状态码判断
 * 「有没有真的判定一次」，只能看 [mood] 与 [source]。
 */
@Serializable
data class ServerMoodEvaluation(
    val personaId: String = "",
    val changed: Boolean = false,
    val source: String = "",
    val mood: ServerMood? = null
)

/** 服务端待审补丁里的一条变更（只用于展示/诊断，真正回放用 [ServerEvolutionPatch.patchJson]）。 */
@Serializable
data class ServerEvolutionChange(
    val field: String = "",
    val op: String = "",
    val value: String = "",
    val reason: String = ""
)

/**
 * 服务端的一条待审进化补丁。
 *
 * [patchJson] 是服务端存的原样补丁（`{"changes":[…]}`），客户端会把它缓存进本地待审表；
 * [summary] 是服务端给的一行摘要，没有时客户端用 [changes] 自己拼。
 */
@Serializable
data class ServerEvolutionPatch(
    val id: String = "",
    val personaId: String = "",
    val sessionId: String? = null,
    val patchJson: String = "",
    val summary: String = "",
    val reason: String = "",
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val changes: List<ServerEvolutionChange> = emptyList()
)

/** `POST /v1/personas/{id}/evolution/evaluate` 的响应；`patch` 在没提议时为 null。 */
@Serializable
data class ServerEvolutionEvaluation(
    val personaId: String = "",
    val proposed: Int = 0,
    val alreadyPending: Boolean = false,
    val summary: String = "",
    val patch: ServerEvolutionPatch? = null
)

/** `GET /v1/personas/{id}/evolution/pending` 的响应。 */
@Serializable
data class ServerEvolutionPending(
    val personaId: String = "",
    val serverTime: Long = 0,
    val patches: List<ServerEvolutionPatch> = emptyList()
)

/**
 * `POST /v1/personas/{id}/evolution/{patchId}/accept` 的响应。
 *
 * [persona] 是服务端合并后的**完整人格**（四字段 + `version`），客户端按它刷新本地缓存；
 * [version] 与 [persona] 里的值一致，取不到时用它兜底。
 */
@Serializable
data class ServerEvolutionAcceptance(
    val personaId: String = "",
    val patchId: String = "",
    val changed: Boolean = false,
    val version: Int = 0,
    val applied: List<ServerEvolutionChange> = emptyList(),
    val persona: JsonObject? = null
)

/** `POST /v1/personas/{id}/evolution/{patchId}/reject` 的响应。 */
@Serializable
data class ServerEvolutionRejection(
    val personaId: String = "",
    val patchId: String = "",
    val rejected: Boolean = false
)

/**
 * `POST /v1/llm-config` 的请求体（只有本机部署的服务端接受）。
 *
 * 这个类型**故意不实现 `toString()` 脱敏**之外的东西：它只在推送那一瞬间存在，
 * 不落盘、不写日志。真正保证密钥不外泄的是「只有回环地址才构造它」这条判定
 * （[ServerLlmConfigPushPolicy]）。
 */
data class ServerLlmConfigWrite(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val provider: String
) {
    override fun toString(): String =
        "ServerLlmConfigWrite(baseUrl=$baseUrl, model=$model, provider=$provider, apiKey=<redacted>)"
}
