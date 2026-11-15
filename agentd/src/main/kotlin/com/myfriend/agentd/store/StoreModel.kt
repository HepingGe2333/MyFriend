package com.myfriend.agentd.store

import com.myfriend.agentd.store.StoreJson.nullableInt
import com.myfriend.agentd.store.StoreJson.nullableNum
import com.myfriend.agentd.store.StoreJson.nullableStr
import com.myfriend.agentd.store.StoreJson.num
import com.myfriend.agentd.store.StoreJson.obj
import com.myfriend.agentd.store.StoreJson.optInt
import com.myfriend.agentd.store.StoreJson.optObj
import com.myfriend.agentd.store.StoreJson.optLong
import com.myfriend.agentd.store.StoreJson.optString
import com.myfriend.agentd.store.StoreJson.reqBool
import com.myfriend.agentd.store.StoreJson.reqLong
import com.myfriend.agentd.store.StoreJson.reqString
import com.myfriend.agentd.store.StoreJson.str
import com.myfriend.agentd.store.StoreJson.strArr
import com.myfriend.agentd.store.StoreJson.stringList
import com.myfriend.app.core.json.JsonValue

/**
 * 服务端权威模式的存储模型（字段名就是磁盘 JSON 的键名，**不要**随手改名：数据目录是长期存在的）。
 *
 * 和 `spec/messages-blocks/README.md` 的对应关系：
 * - [Message.blockIndex] / [BlockMeta] 与客户端块表同构，`BLOCK_SIZE = 200`（见 [FileStore.BLOCK_SIZE]）；
 * - 块可以变短、可以为空；空块**删掉**但编号不回收，`block_index.json` 里留下空洞（不重平衡）；
 * - 消息删除是**硬删除**（`spec` 里的约定），没有软删除标记。
 *
 * 契约之外多出来的字段（`source` / `clientMessageId` / `replyTo` / `promptTokens` /
 * `completionTokens` / `messageCount` / `nameEn` / `description` / `isDefault` / `intensity`）
 * 都是**可加**字段：客户端按需读取，服务端需要它们做幂等、用量和主动消息来源区分。
 * 写在 `agentd/README.md` 的「服务端权威模式」一节里。
 */

// ---------------------------------------------------------------------- 角色 / 来源

object MessageRole {
    const val USER = "USER"
    const val ASSISTANT = "ASSISTANT"
    const val SYSTEM = "SYSTEM"

    val ALL = listOf(USER, ASSISTANT, SYSTEM)

    fun isValid(value: String): Boolean = value in ALL
}

object MessageSource {
    /** 用户发消息触发的回复（本阶段唯一会落库的 assistant 来源）。 */
    const val CHAT = "CHAT"

    /** 服务端心跳主动发起的消息（下一阶段；模型先留好字段）。 */
    const val PROACTIVE = "PROACTIVE"

    /** 本地模式的导入/迁移。 */
    const val IMPORT = "IMPORT"
}

object EventType {
    const val MESSAGE_CREATED = "message.created"
    const val MOOD_CHANGED = "mood.changed"
    const val PRESENCE_CHANGED = "presence.changed"

    val ALL = listOf(MESSAGE_CREATED, MOOD_CHANGED, PRESENCE_CHANGED)
}

// ---------------------------------------------------------------------- meta.json

data class StoreMeta(
    val version: Int = FileStore.VERSION,
    val cursor: Long = 0,
    val seeded: Boolean = false
) {
    fun toJson(): JsonValue.Obj = obj(
        "version" to num(version),
        "cursor" to num(cursor),
        "seeded" to StoreJson.bool(seeded)
    )

    companion object {
        fun fromJson(value: JsonValue.Obj, where: String): StoreMeta = StoreMeta(
            version = value.reqLong("version", where).toInt(),
            cursor = value.reqLong("cursor", where),
            seeded = value.reqBool("seeded", where)
        )
    }
}

// ---------------------------------------------------------------------- personas.json

data class Persona(
    val id: String,
    val name: String,
    val nameEn: String,
    val description: String,
    val avatarUrl: String?,
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toJson(): JsonValue.Obj = obj(
        "id" to str(id),
        "name" to str(name),
        "nameEn" to str(nameEn),
        "description" to str(description),
        "avatarUrl" to nullableStr(avatarUrl),
        "isDefault" to StoreJson.bool(isDefault),
        "createdAt" to num(createdAt),
        "updatedAt" to num(updatedAt)
    )

    companion object {
        fun fromJson(value: JsonValue.Obj, where: String): Persona = Persona(
            id = value.reqString("id", where),
            name = value.reqString("name", where),
            nameEn = value.optString("nameEn", where) ?: "",
            description = value.optString("description", where) ?: "",
            avatarUrl = value.optString("avatarUrl", where),
            isDefault = value.fields.containsKey("isDefault") && value.reqBool("isDefault", where),
            createdAt = value.reqLong("createdAt", where),
            updatedAt = value.reqLong("updatedAt", where)
        )
    }
}

// ---------------------------------------------------------------------- sessions.json

data class Session(
    val id: String,
    val personaId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessageAt: Long?,
    /** 最新块的编号；没有任何消息时是 -1（契约里用整数表示「没有块」）。 */
    val lastBlockIndex: Int,
    val unread: Int,
    val messageCount: Long
) {
    fun toJson(): JsonValue.Obj = obj(
        "id" to str(id),
        "personaId" to str(personaId),
        "title" to str(title),
        "createdAt" to num(createdAt),
        "updatedAt" to num(updatedAt),
        "lastMessageAt" to nullableNum(lastMessageAt),
        "lastBlockIndex" to num(lastBlockIndex),
        "unread" to num(unread),
        "messageCount" to num(messageCount)
    )

    companion object {
        fun fromJson(value: JsonValue.Obj, where: String): Session = Session(
            id = value.reqString("id", where),
            personaId = value.reqString("personaId", where),
            title = value.reqString("title", where),
            createdAt = value.reqLong("createdAt", where),
            updatedAt = value.reqLong("updatedAt", where),
            lastMessageAt = value.optLong("lastMessageAt", where),
            lastBlockIndex = value.optInt("lastBlockIndex", where) ?: -1,
            unread = value.optInt("unread", where) ?: 0,
            messageCount = value.optLong("messageCount", where) ?: 0
        )
    }
}

// ---------------------------------------------------------------------- 消息与块

/**
 * 一条消息。
 *
 * `clientMessageId` 只出现在**用户**消息上（幂等键）；`replyTo` 只出现在 assistant 消息上，
 * 指回它回复的用户消息 id（这样重复 POST 能找到已生成的回复，而不必重跑生成器）。
 * `promptTokens` / `completionTokens` 记录那一次生成的用量，用于 `done` 事件重放。
 */
data class Message(
    val id: String,
    val sessionId: String,
    val blockIndex: Int,
    val role: String,
    val content: String,
    val createdAt: Long,
    val readAt: Long?,
    val attachments: List<String>,
    val source: String,
    val clientMessageId: String?,
    val replyTo: String?,
    val promptTokens: Int?,
    val completionTokens: Int?
) {
    fun toJson(): JsonValue.Obj = obj(
        "id" to str(id),
        "sessionId" to str(sessionId),
        "blockIndex" to num(blockIndex),
        "role" to str(role),
        "content" to str(content),
        "createdAt" to num(createdAt),
        "readAt" to nullableNum(readAt),
        "attachments" to strArr(attachments),
        "source" to str(source),
        "clientMessageId" to nullableStr(clientMessageId),
        "replyTo" to nullableStr(replyTo),
        "promptTokens" to nullableInt(promptTokens),
        "completionTokens" to nullableInt(completionTokens)
    )

    companion object {
        fun fromJson(value: JsonValue.Obj, where: String): Message = Message(
            id = value.reqString("id", where),
            sessionId = value.reqString("sessionId", where),
            blockIndex = value.reqLong("blockIndex", where).toInt(),
            role = value.reqString("role", where),
            content = value.reqString("content", where),
            createdAt = value.reqLong("createdAt", where),
            readAt = value.optLong("readAt", where),
            attachments = value.stringList("attachments", where),
            source = value.optString("source", where) ?: MessageSource.CHAT,
            clientMessageId = value.optString("clientMessageId", where),
            replyTo = value.optString("replyTo", where),
            promptTokens = value.optInt("promptTokens", where),
            completionTokens = value.optInt("completionTokens", where)
        )
    }
}

/** 块表的一行，对应 `sessions/<id>/block_index.json` 的 `blocks[]`。 */
data class BlockMeta(
    val index: Int,
    val firstMessageId: String,
    val lastMessageId: String,
    val firstAt: Long,
    val lastAt: Long,
    val count: Int
) {
    fun toJson(): JsonValue.Obj = obj(
        "index" to num(index),
        "firstMessageId" to str(firstMessageId),
        "lastMessageId" to str(lastMessageId),
        "firstAt" to num(firstAt),
        "lastAt" to num(lastAt),
        "count" to num(count)
    )

    companion object {
        fun fromJson(value: JsonValue.Obj, where: String): BlockMeta = BlockMeta(
            index = value.reqLong("index", where).toInt(),
            firstMessageId = value.reqString("firstMessageId", where),
            lastMessageId = value.reqString("lastMessageId", where),
            firstAt = value.reqLong("firstAt", where),
            lastAt = value.reqLong("lastAt", where),
            count = value.reqLong("count", where).toInt()
        )
    }
}

// ---------------------------------------------------------------------- 心情 / 在场

data class Mood(
    val personaId: String,
    val label: String,
    val intensity: Int,
    val updatedAt: Long
) {
    fun toJson(): JsonValue.Obj = obj(
        "personaId" to str(personaId),
        "label" to str(label),
        "intensity" to num(intensity),
        "updatedAt" to num(updatedAt)
    )

    companion object {
        fun fromJson(value: JsonValue.Obj, where: String): Mood = Mood(
            personaId = value.reqString("personaId", where),
            label = value.reqString("label", where),
            intensity = value.optInt("intensity", where) ?: 0,
            updatedAt = value.reqLong("updatedAt", where)
        )
    }
}

data class Presence(
    val personaId: String,
    val kind: String,
    val detail: String,
    val updatedAt: Long
) {
    fun toJson(): JsonValue.Obj = obj(
        "personaId" to str(personaId),
        "kind" to str(kind),
        "detail" to str(detail),
        "updatedAt" to num(updatedAt)
    )

    companion object {
        fun fromJson(value: JsonValue.Obj, where: String): Presence = Presence(
            personaId = value.reqString("personaId", where),
            kind = value.reqString("kind", where),
            detail = value.optString("detail", where) ?: "",
            updatedAt = value.reqLong("updatedAt", where)
        )
    }
}

// ---------------------------------------------------------------------- 用量 / API 配置

data class UsageTotal(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val replies: Long = 0
) {
    fun plus(other: UsageTotal): UsageTotal = UsageTotal(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        replies = replies + other.replies
    )

    fun toJson(): JsonValue.Obj = obj(
        "promptTokens" to num(promptTokens),
        "completionTokens" to num(completionTokens),
        "replies" to num(replies)
    )

    companion object {
        fun fromJson(value: JsonValue.Obj, where: String): UsageTotal = UsageTotal(
            promptTokens = value.optLong("promptTokens", where) ?: 0,
            completionTokens = value.optLong("completionTokens", where) ?: 0,
            replies = value.optLong("replies", where) ?: 0
        )
    }
}

/**
 * `api_configs.json` 里的一条配置：**只有元数据，没有 key**。
 * 明文 key 放在 `secrets.json`（0600，且不参与任何 HTTP 响应，见 [FileStore.apiKey]）。
 */
data class ApiConfig(
    val id: String,
    val name: String,
    val provider: String,
    val baseUrl: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toJson(): JsonValue.Obj = obj(
        "id" to str(id),
        "name" to str(name),
        "provider" to str(provider),
        "baseUrl" to str(baseUrl),
        "model" to str(model),
        "createdAt" to num(createdAt),
        "updatedAt" to num(updatedAt)
    )

    companion object {
        fun fromJson(value: JsonValue.Obj, where: String): ApiConfig = ApiConfig(
            id = value.reqString("id", where),
            name = value.reqString("name", where),
            provider = value.optString("provider", where) ?: "",
            baseUrl = value.optString("baseUrl", where) ?: "",
            model = value.optString("model", where) ?: "",
            createdAt = value.reqLong("createdAt", where),
            updatedAt = value.reqLong("updatedAt", where)
        )
    }
}

// ---------------------------------------------------------------------- events.jsonl

/**
 * 增量事件（`GET /v1/events?since=` 的返回单位）。
 *
 * `cursor` 来自 `meta.cursor`，**单调递增且不回收**；`payload` 是类型相关的对象
 * （`message.created` → `{message:{…}}`、`mood.changed` → `{mood:{…}}`、
 * `presence.changed` → `{presence:{…}}`）。
 */
data class StoredEvent(
    val cursor: Long,
    val type: String,
    val at: Long,
    val sessionId: String?,
    val personaId: String?,
    val payload: JsonValue.Obj
) {
    fun toJson(): JsonValue.Obj = obj(
        "cursor" to num(cursor),
        "type" to str(type),
        "at" to num(at),
        "sessionId" to nullableStr(sessionId),
        "personaId" to nullableStr(personaId),
        "payload" to payload
    )

    companion object {
        fun fromJson(value: JsonValue.Obj, where: String): StoredEvent = StoredEvent(
            cursor = value.reqLong("cursor", where),
            type = value.reqString("type", where),
            at = value.reqLong("at", where),
            sessionId = value.optString("sessionId", where),
            personaId = value.optString("personaId", where),
            payload = value.optObj("payload", where) ?: JsonValue.Obj()
        )
    }
}

// ---------------------------------------------------------------------- state 快照

/** `GET /v1/state` 的一次快照（在存储锁内组装，保证内部一致）。 */
data class StateSnapshot(
    val serverTime: Long,
    val personas: List<Persona>,
    val sessions: List<Session>,
    val messages: List<Message>,
    val mood: Mood?,
    val presence: Presence?,
    val serverCursor: Long,
    /** `since=0` 首次同步只给最新一块；消息被上限截断时这里是 true（客户端继续用分页接口）。 */
    val messagesTruncated: Boolean
)

/** 长轮询的结果：`events` 为空表示等到超时也没事件，此时 `cursor` 原样返回（客户端不要前移）。 */
data class EventWaitResult(
    val events: List<StoredEvent>,
    val cursor: Long
)
