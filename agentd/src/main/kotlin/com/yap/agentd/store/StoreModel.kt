package com.yap.agentd.store

import com.yap.agentd.store.StoreJson.nullableInt
import com.yap.agentd.store.StoreJson.nullableNum
import com.yap.agentd.store.StoreJson.nullableStr
import com.yap.agentd.store.StoreJson.num
import com.yap.agentd.store.StoreJson.obj
import com.yap.agentd.store.StoreJson.optInt
import com.yap.agentd.store.StoreJson.optObj
import com.yap.agentd.store.StoreJson.optLong
import com.yap.agentd.store.StoreJson.optString
import com.yap.agentd.store.StoreJson.reqBool
import com.yap.agentd.store.StoreJson.reqLong
import com.yap.agentd.store.StoreJson.reqString
import com.yap.agentd.store.StoreJson.str
import com.yap.agentd.store.StoreJson.strArr
import com.yap.agentd.store.StoreJson.stringList
import com.yap.app.core.json.JsonValue

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

/**
 * 不进入任何响应/备份的字段名（客户端 schema 里没有这些；有人手工塞进来也不能被带走）。
 *
 * 未知字段要保留，但「未知」不等于「可以夹带密钥」：`apiKey` / `*Cipher` /
 * `authorization` 这类名字在导出时一律丢弃。真正的服务端密钥本来只在 `secrets.json`。
 */
internal fun isSecretLikeFieldName(key: String): Boolean {
    val lower = key.lowercase()
    return lower == "authorization" ||
        lower == "apikey" ||
        lower == "api_key" ||
        lower == "apikeys" ||
        lower == "secret" ||
        lower == "secrets" ||
        lower.contains("apikey") ||
        lower.endsWith("cipher") ||
        lower.contains("secret")
}

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
    val updatedAt: Long,
    /**
     * 他此刻在做什么（`spec/persona-guidance/README.md` §4 的「占用时间」）；`null` = 没设过。
     *
     * 存的是**原样 JSON 文本**（结构同客户端 `PersonaActivity`：`kind` / `activity` /
     * `until` / `availability`），与客户端 `personas.activityJson` 同名字段一一对应，
     * 这样备份/M3 上报都不必再做一层映射。为什么服务端不存结构化对象：
     * 过期判断只需要 `until`，读的人（主动门禁、`GET /v1/state`）各取所需，
     * 而存原样文本能让「客户端写什么、服务端就回什么」，字段演进不必同时改两边的模型。
     *
     * 到点自动失效**不靠定时任务**：读取方用 `ActivityState.availabilityAt(now)` 把过期的
     * 活动当成 `FREE`（`README.md` §4「到 until 自动失效（读取时判断）」）。
     */
    val activityJson: String? = null,
    /**
     * 客户端 schema 里、服务端没有专门建模的字段（`worldJson` / `speechStyleJson` /
     * `traits` / `likes` / `behaviors` / `backstory` / `quirks` / `translationJson` /
     * `translationLang` / `personaCardJson` …）。
     *
     * HTTP 写路径按字段名原样收下、原样回给客户端：服务端不需要理解每一格的语义，但
     * **不能丢**（客户端下一次同步/备份依赖它们）。核心列（id/name/…）不在这里，
     * 避免和上面的 `copy` 产生两份真相。
     */
    val extraFields: Map<String, JsonValue> = emptyMap()
) {
    fun toJson(): JsonValue.Obj {
        val fields = linkedMapOf(
            "id" to str(id),
            "name" to str(name),
            "nameEn" to str(nameEn),
            "description" to str(description),
            "avatarUrl" to nullableStr(avatarUrl),
            "isDefault" to StoreJson.bool(isDefault),
            "createdAt" to num(createdAt),
            "updatedAt" to num(updatedAt),
            "activityJson" to nullableStr(activityJson)
        )
        // 保留客户端字段的原始 JSON 形状，未知字段也一样（“未知字段保留原文”），
        // 但密钥形状的字段不出口（见 isSecretLikeFieldName）。
        for ((key, value) in extraFields) {
            if (key !in RESERVED_KEYS && !isSecretLikeFieldName(key)) fields[key] = value
        }
        return JsonValue.Obj(fields)
    }

    companion object {
        val RESERVED_KEYS: Set<String> = setOf(
            "id", "name", "nameEn", "description", "avatarUrl", "isDefault",
            "createdAt", "updatedAt", "activityJson"
        )

        fun fromJson(value: JsonValue.Obj, where: String): Persona = Persona(
            id = value.reqString("id", where),
            name = value.reqString("name", where),
            nameEn = value.optString("nameEn", where) ?: "",
            description = value.optString("description", where) ?: "",
            avatarUrl = value.optString("avatarUrl", where),
            isDefault = value.fields.containsKey("isDefault") && value.reqBool("isDefault", where),
            createdAt = value.reqLong("createdAt", where),
            updatedAt = value.reqLong("updatedAt", where),
            // 可加字段：老数据目录没有这一格（null），不需要版本号 +1。
            activityJson = value.optString("activityJson", where)?.takeIf { it.isNotBlank() },
            extraFields = value.fields.filterKeys { it !in RESERVED_KEYS }
        )
    }
}

// ---------------------------------------------------------------------- proactive_state.json

/**
 * 每个「人格」的主动开口记录（服务端权威模式 §3.4）。
 *
 * 这三格以前只活在 [com.yap.agentd.proactive.ProactiveScheduler] 的进程内 overlay 里，
 * 重启就归零：当日次数 / 上次开口时间一起丢，于是服务重启后可能重复开口或超出每日上限。
 * 现在落到独立文件 `proactive_state.json`（不改 `personas.json` 的既有契约）：
 *
 * - [lastProactiveAt]：上次真正落库的主动消息时刻（毫秒）；`0` = 从没发过；
 * - [proactiveCountToday]：**该自然日**已经开口的次数；
 * - [proactiveCountDate]：上面那个计数属于哪个本地自然日（`yyyy-MM-dd`）。
 *
 * 跨日归零不靠定时任务：读取时发现 [proactiveCountDate] 不是今天，就把计数当成 0；
 * 下一次真正开口时写成 1。`lastProactiveAt` **不随日期清空** —— 冷却期是「距离上次开口多久」，
 * 跨过零点也该继续生效。
 */
data class ProactiveState(
    val personaId: String,
    val lastProactiveAt: Long = 0L,
    val proactiveCountToday: Int = 0,
    val proactiveCountDate: String = ""
) {
    fun toJson(): JsonValue.Obj = obj(
        "personaId" to str(personaId),
        "lastProactiveAt" to num(lastProactiveAt),
        "proactiveCountToday" to num(proactiveCountToday),
        "proactiveCountDate" to str(proactiveCountDate)
    )

    companion object {
        fun fromJson(value: JsonValue.Obj, where: String): ProactiveState = ProactiveState(
            personaId = value.reqString("personaId", where),
            lastProactiveAt = value.optLong("lastProactiveAt", where) ?: 0L,
            proactiveCountToday = value.optInt("proactiveCountToday", where) ?: 0,
            proactiveCountDate = value.optString("proactiveCountDate", where) ?: ""
        )
    }
}

// ---------------------------------------------------------------------- memories.json

/**
 * 一条长期记忆（`memories.json`）。
 *
 * 字段名与客户端 `memories` / 备份 v5 的 `memories[]` 同义。服务端这一阶段只做
 * 「CRUD + 备份搬运」，不做抽取/衰减/整理——那些仍属于后续阶段。
 *
 * `tags` 在客户端数据库里是 JSON 字符串，但 HTTP/备份契约用数组；服务端内部统一成
 * `List<String>`，读写时两边都兼容（见 `FileStore` 的导入解析）。
 */
data class Memory(
    val id: String,
    val personaId: String,
    val content: String,
    val importance: Int = 50,
    val tags: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toJson(): JsonValue.Obj = obj(
        "id" to str(id),
        "personaId" to str(personaId),
        "content" to str(content),
        "importance" to num(importance),
        "tags" to strArr(tags),
        "createdAt" to num(createdAt),
        "updatedAt" to num(updatedAt)
    )

    companion object {
        fun fromJson(value: JsonValue.Obj, where: String): Memory = Memory(
            id = value.reqString("id", where),
            personaId = value.reqString("personaId", where),
            content = value.reqString("content", where),
            importance = value.optInt("importance", where) ?: 50,
            tags = value.stringList("tags", where),
            createdAt = value.reqLong("createdAt", where),
            updatedAt = value.reqLong("updatedAt", where)
        )
    }
}

// ---------------------------------------------------------------------- presets.json

/**
 * 人格预设（`presets.json`）。客户端 `persona_presets` 的跨端形态。
 *
 * `worldJson` / `speechStyleJson` 按原始文本存（与人格上的同名字段一致）；
 * 未知字段走 [extraFields] 原样保留。
 */
data class Preset(
    val id: String,
    val name: String,
    val description: String = "",
    val prompt: String = "",
    val category: String = "",
    val avatarUri: String? = null,
    val avatarSeed: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val worldJson: String? = null,
    val speechStyleJson: String? = null,
    val extraFields: Map<String, JsonValue> = emptyMap()
) {
    fun toJson(): JsonValue.Obj {
        val fields = linkedMapOf(
            "id" to str(id),
            "name" to str(name),
            "description" to str(description),
            "prompt" to str(prompt),
            "category" to str(category),
            "avatarUri" to nullableStr(avatarUri),
            "avatarSeed" to num(avatarSeed),
            "createdAt" to num(createdAt),
            "updatedAt" to num(updatedAt),
            "worldJson" to nullableStr(worldJson),
            "speechStyleJson" to nullableStr(speechStyleJson)
        )
        for ((key, value) in extraFields) {
            if (key !in RESERVED_KEYS && !isSecretLikeFieldName(key)) fields[key] = value
        }
        return JsonValue.Obj(fields)
    }

    companion object {
        val RESERVED_KEYS: Set<String> = setOf(
            "id", "name", "description", "prompt", "category", "avatarUri", "avatarSeed",
            "createdAt", "updatedAt", "worldJson", "speechStyleJson"
        )

        fun fromJson(value: JsonValue.Obj, where: String): Preset = Preset(
            id = value.reqString("id", where),
            name = value.reqString("name", where),
            description = value.optString("description", where) ?: "",
            prompt = value.optString("prompt", where) ?: "",
            category = value.optString("category", where) ?: "",
            avatarUri = value.optString("avatarUri", where),
            avatarSeed = value.optInt("avatarSeed", where) ?: 0,
            createdAt = value.reqLong("createdAt", where),
            updatedAt = value.reqLong("updatedAt", where),
            worldJson = value.optString("worldJson", where)?.takeIf { it.isNotBlank() },
            speechStyleJson = value.optString("speechStyleJson", where)?.takeIf { it.isNotBlank() },
            extraFields = value.fields.filterKeys { it !in RESERVED_KEYS }
        )
    }
}

// ---------------------------------------------------------------------- push_tokens.json

/** 一条推送注册（服务端只存 token，真正的 APNs/FCM 发送由部署侧实现 [PushSender]）。 */
data class PushRegistration(
    val platform: String,
    val token: String,
    val bundleId: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toJson(): JsonValue.Obj = obj(
        "platform" to str(platform),
        "token" to str(token),
        "bundleId" to nullableStr(bundleId),
        "createdAt" to num(createdAt),
        "updatedAt" to num(updatedAt)
    )

    companion object {
        const val PLATFORM_IOS = "ios"
        const val PLATFORM_ANDROID = "android"
        val PLATFORMS = listOf(PLATFORM_IOS, PLATFORM_ANDROID)

        fun fromJson(value: JsonValue.Obj, where: String): PushRegistration = PushRegistration(
            platform = value.reqString("platform", where),
            token = value.reqString("token", where),
            bundleId = value.optString("bundleId", where),
            createdAt = value.reqLong("createdAt", where),
            updatedAt = value.reqLong("updatedAt", where)
        )
    }
}

/** 备份导入的结果计数（路由回给客户端；方便确认 merge/replace 实际写了什么）。 */
data class BackupImportResult(
    val mode: String,
    val personas: Int,
    val sessions: Int,
    val messages: Int,
    val memories: Int,
    val presets: Int,
    val settings: Boolean,
    val media: Int = 0,
    /** v6：导入的表情行数。 */
    val stickers: Int = 0,
    /** v6：导入的日记条数。 */
    val diaryNotes: Int = 0
)

/** 备份里的 version 不受支持（路由翻成 409）。 */
class UnsupportedBackupVersionException(val version: Int) :
    IllegalArgumentException("备份格式 v$version 不受支持（本服务读 v1–v${FileStore.BACKUP_FORMAT_VERSION}）")

/** 备份内容结构/引用不合法（路由翻成 400；导入前校验，不写盘）。 */
class BackupValidationException(message: String) : IllegalArgumentException(message)

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
    val messageCount: Long,
    /**
     * 客户端会话列里服务端没有专门建模的字段（`apiConfigId` / `model` / `pinned` /
     * `summary` / `summarizedUpTo` / `summaryAt` / `memoryRetentionOverride` /
     * `extractionEveryOverride` / `lastPersonaVersion` / …）。
     *
     * 为什么保留：备份 v6 的 `sessions[]` 是**客户端形状**，这些字段必须在
     * 「导入 → 落库 → 再导出」这条链路上原样活下来（否则换端后长会话的滚动摘要、
     * 置顶、模型覆盖全丢了，而且不会报错）。
     */
    val extraFields: Map<String, JsonValue> = emptyMap()
) {
    fun toJson(): JsonValue.Obj {
        val fields = linkedMapOf(
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
        for ((key, value) in extraFields) {
            if (key !in RESERVED_KEYS && !isSecretLikeFieldName(key)) fields[key] = value
        }
        return JsonValue.Obj(fields)
    }

    companion object {
        val RESERVED_KEYS: Set<String> = setOf(
            "id", "personaId", "title", "createdAt", "updatedAt", "lastMessageAt",
            "lastBlockIndex", "unread", "unreadCount", "messageCount"
        )

        fun fromJson(value: JsonValue.Obj, where: String): Session = Session(
            id = value.reqString("id", where),
            personaId = value.reqString("personaId", where),
            title = value.reqString("title", where),
            createdAt = value.reqLong("createdAt", where),
            updatedAt = value.reqLong("updatedAt", where),
            lastMessageAt = value.optLong("lastMessageAt", where),
            lastBlockIndex = value.optInt("lastBlockIndex", where) ?: -1,
            // 兼容客户端备份的 `unreadCount`（v6 的 sessions[] 用这个名字）。
            unread = value.optInt("unread", where) ?: value.optInt("unreadCount", where) ?: 0,
            messageCount = value.optLong("messageCount", where) ?: 0,
            extraFields = value.fields.filterKeys { it !in RESERVED_KEYS }
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
    val completionTokens: Int?,
    /**
     * 这一轮工具回路的痕迹（`spec`/客户端 `messages.toolTraceJson` 的服务端对应物）。
     *
     * 存**客户端同形的 JSON 文本**（`[{"name":"web_search","status":"done","summary":"…"}]`），
     * 所以备份 v6 的 `messages[].toolTraceJson` 可以原样来回搬，客户端「他当时查了两个网页」
     * 那张卡片不用再翻译一次。`null` = 这一轮没有工具调用（绝大多数消息）。
     */
    val toolTraceJson: String? = null
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
        "completionTokens" to nullableInt(completionTokens),
        // 可加字段：老数据目录没有这一格（null），不升存储版本号。
        "toolTraceJson" to nullableStr(toolTraceJson)
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
            completionTokens = value.optInt("completionTokens", where),
            toolTraceJson = value.optString("toolTraceJson", where)?.takeIf { it.isNotBlank() }
        )
    }
}

// ---------------------------------------------------------------------- 批量历史消息导入

/**
 * `POST /v1/sessions/{id}/messages/import` 里的一条消息（**客户端自带 id**）。
 *
 * 与 [Message] 的区别：没有 `sessionId`（URL 里给）、没有 `blockIndex`（服务端按
 * `createdAt` 切块）、没有 token 用量（历史消息本来就没有）。
 *
 * `readAt` 缺省/null = 未读：批量导入**不会**把它算进会话的 `unread`（历史消息不该在客户端
 * 切到远程后突然显示一堆未读），但会原样存下请求给的 `readAt`。
 */
data class ImportMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val readAt: Long?,
    /** 已由路由层归一化成 `/v1/media/<id>` 并校验过存在。 */
    val attachments: List<String> = emptyList(),
    val source: String = MessageSource.IMPORT,
    val clientMessageId: String? = null,
    val replyTo: String? = null
)

/**
 * 一次批量导入的结果。
 *
 * - [imported] / [skipped]：真正写入的条数 / 因为 id 已存在而跳过的条数；
 * - [firstBlock] / [lastBlock]：本次新写入消息覆盖到的块编号区间（`imported == 0` 时都是 null）；
 * - [messageCount]：导入后该会话的总消息数（客户端用来对账，不用再拉一次列表）。
 */
data class MessageImportResult(
    val imported: Int,
    val skipped: Int,
    val firstBlock: Int?,
    val lastBlock: Int?,
    val messageCount: Long
)

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

/**
 * 多维心情（`spec/mood/README.md` §1，schema v17）。
 *
 * 四格一起表达「一个词 + 三个维度」：
 * - [label] 给人看的短词（累/平静/烦/开心…）；
 * - [valence] 愉快度 -2..2（负=不悦，正=愉快）；
 * - [arousal] 唤醒度 0..5（0=低能量/困，5=激动）；
 * - [dominance] 掌控感 0..5（0=被压住，5=掌控）；
 * - [intensity] 强度 1..5（既有列）；
 * - [reason] 为什么（模型内部/调试用，不直接展示）。
 *
 * 旧数据只有 `label/intensity`：读盘时按规范 §1 的迁移规则补维度
 * （valence 由 label 映射，arousal 由 intensity 推，dominance=3）。`moods.json` 不升版本。
 */
data class Mood(
    val personaId: String,
    val label: String,
    val intensity: Int,
    val updatedAt: Long,
    val valence: Int = 0,
    val arousal: Int = 2,
    val dominance: Int = 3,
    val reason: String = ""
) {
    fun toJson(): JsonValue.Obj = obj(
        "personaId" to str(personaId),
        "label" to str(label),
        "intensity" to num(intensity),
        "updatedAt" to num(updatedAt),
        // 0.13 可加字段：老数据目录没有它们，读的时候按迁移规则补齐。
        "valence" to num(valence),
        "arousal" to num(arousal),
        "dominance" to num(dominance),
        "reason" to str(reason)
    )

    companion object {
        /** 心情原因的字符上限（只进提示词/调试，不给人看长文）。 */
        const val MAX_REASON_CHARS = 200

        /** 旧数据迁移：label → valence（累/烦 → -1，平静 → 0，开心 → 1……由词表给出）。 */
        fun defaultValence(label: String): Int =
            com.yap.app.domain.MoodLexicon.classify(label).valence.coerceIn(-2, 2)

        /** 旧数据迁移：arousal 由 intensity 推（强度越高越"有劲"，区间 0..5）。 */
        fun defaultArousal(intensity: Int): Int = intensity.coerceIn(0, 5)

        /** 旧数据迁移：dominance 默认 3（中间值，不猜）。 */
        fun defaultDominance(): Int = 3

        fun fromJson(value: JsonValue.Obj, where: String): Mood {
            val label = value.reqString("label", where)
            val intensity = value.optInt("intensity", where) ?: 0
            return Mood(
                personaId = value.reqString("personaId", where),
                label = label,
                intensity = intensity,
                updatedAt = value.reqLong("updatedAt", where),
                valence = value.optInt("valence", where) ?: defaultValence(label),
                arousal = value.optInt("arousal", where) ?: defaultArousal(intensity),
                dominance = value.optInt("dominance", where) ?: defaultDominance(),
                reason = value.optString("reason", where).orEmpty()
            )
        }
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

/** `POST /v1/sessions/{id}/read` 的存储结果。 */
data class SessionReadResult(
    val marked: Int,
    val readAt: Long,
    val session: Session
)

/** 人格不存在（路由翻成 404，不是 500）。 */
class StorePersonaNotFoundException(val personaId: String) :
    NoSuchElementException("没有这个人格：$personaId")

/** 记忆不存在 / 不属于 URL 里的人格（路由翻成 404）。 */
class StoreMemoryNotFoundException(val memoryId: String) :
    NoSuchElementException("没有这条记忆：$memoryId")

/** 预设不存在（路由翻成 404）。 */
class StorePresetNotFoundException(val presetId: String) :
    NoSuchElementException("没有这个预设：$presetId")

/** 日记不存在 / 不属于 URL 里的人格（路由翻成 404）。 */
class StoreDiaryNoteNotFoundException(val noteId: String) :
    NoSuchElementException("没有这条日记：$noteId")

// ---------------------------------------------------------------------- pending_patches.json

/**
 * 一条**待审**的人格进化补丁（`spec`/客户端 `persona_pending_patches` 的服务端对应物）。
 *
 * 服务端不自动改人格：模型给出的变化先落这里，用户/客户端调 accept 才写进 `personas.json`，
 * reject 就丢掉。字段形状与客户端一致（`patchJson` 是 `{"changes":[…]}` 的文本），
 * 多出来的 [expiresAt] 是服务端为了「7 天后自动丢弃」显式算好的（不靠读的时候再猜 TTL）。
 *
 * @param sessionId 评估时读的会话（诊断用；会话删了也不影响补丁本身）
 * @param summary 给人看的一行（例如「性格 +嘴硬心软」）
 * @param reason 为什么要改（固定中文，不参与规则）
 */
data class PendingPatch(
    val id: String,
    val personaId: String,
    val sessionId: String?,
    val patchJson: String,
    val summary: String,
    val reason: String,
    val createdAt: Long,
    val expiresAt: Long
) {
    fun toJson(): JsonValue.Obj = obj(
        "id" to str(id),
        "personaId" to str(personaId),
        "sessionId" to nullableStr(sessionId),
        "patchJson" to str(patchJson),
        "summary" to str(summary),
        "reason" to str(reason),
        "createdAt" to num(createdAt),
        "expiresAt" to num(expiresAt)
    )

    companion object {
        fun fromJson(value: JsonValue.Obj, where: String): PendingPatch = PendingPatch(
            id = value.reqString("id", where),
            personaId = value.reqString("personaId", where),
            sessionId = value.optString("sessionId", where),
            patchJson = value.reqString("patchJson", where),
            summary = value.optString("summary", where).orEmpty(),
            reason = value.optString("reason", where).orEmpty(),
            createdAt = value.reqLong("createdAt", where),
            expiresAt = value.optLong("expiresAt", where) ?: value.reqLong("createdAt", where)
        )
    }
}
