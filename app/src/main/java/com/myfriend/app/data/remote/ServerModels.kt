package com.myfriend.app.data.remote

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
    val updatedAt: Long = 0
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
    val replyTo: String? = null
)

/** 心情：服务端推进，客户端只展示。 */
@Serializable
data class ServerMood(
    val personaId: String = "",
    val label: String = "",
    val intensity: Int = 0,
    val updatedAt: Long = 0
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
