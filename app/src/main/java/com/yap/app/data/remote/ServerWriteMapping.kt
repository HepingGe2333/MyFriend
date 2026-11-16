package com.yap.app.data.remote

import com.yap.app.data.local.MemoryEntity
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.local.PersonaPresetEntity
import com.yap.app.data.prefs.AppSettings
import com.yap.app.domain.MemoryTags
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 本地实体 ⇄ 服务端 JSON 的**唯一映射点**（M5 写路径）。
 *
 * 为什么单独一个文件而不是散在各仓储里：
 * - 服务端的字段名与本地 Room 列名并不逐字相同（`avatarUrl` vs `avatarUri`、重要度 0–100 vs 1–5），
 *   映射错一次就会出现「保存成功但头像没了 / 重要度变成 1」；
 * - 纯函数、不碰 Android：JVM 单测可以直接断言请求体形状与响应落缓存的结果；
 * - **未知字段不猜**：服务端保留的额外字段（worldJson / speechStyleJson / 人设卡…）由客户端
 *   按自己的实体写回，不从响应里反向覆盖本地没发出去的东西。
 */
object ServerPersonaWrite {

    /**
     * `POST /v1/personas` 的请求体。
     *
     * 服务端只把 `id/name/nameEn/description/avatarUrl/isDefault/createdAt/updatedAt/activityJson`
     * 当核心列，其余字段原样进 `extraFields`。所以这里把本地的完整人格都带上（prompt、世界、
     * 话风、作息、心情…），服务端不需要理解语义，但不能丢。
     *
     * @param avatarUrl 已上传头像的 `/v1/media/<id>`；null 表示没有头像 / 用户清空了头像
     *        （更新时显式写 null，服务端才会把旧头像清掉）
     * @param creating 新建时不带 id（服务端分配），更新时带 id
     */
    fun payload(
        persona: PersonaEntity,
        avatarUrl: String?,
        creating: Boolean,
        includeAvatar: Boolean = true
    ): JsonObject = buildJsonObject {
        if (!creating) put("id", persona.id)
        put("name", persona.name)
        put("description", persona.description)
        // 更新时头像没变就**省略** avatarUrl：让服务端保留原值，而不是被一次普通保存清空。
        // 用户真的清空头像时 includeAvatar=true 且 avatarUrl=null，这时才写 JsonNull。
        if (includeAvatar) put("avatarUrl", avatarUrl?.let(::JsonPrimitive) ?: JsonNull)
        put("isDefault", persona.isDefault)
        // 服务端核心列以外的字段：按本地列名原样带走，服务端 unknown 字段会原样保留。
        put("avatarSeed", persona.avatarSeed)
        put("prompt", persona.prompt)
        put("category", persona.category)
        put("version", persona.version)
        persona.defaultApiConfigId?.let { put("defaultApiConfigId", it) }
        persona.modelOverride?.let { put("modelOverride", it) }
        persona.personaCardJson?.let { put("personaCardJson", it) }
        persona.quietSleepMinutes?.let { put("quietSleepMinutes", it) }
        persona.quietWakeMinutes?.let { put("quietWakeMinutes", it) }
        persona.mood?.let { put("mood", it) }
        persona.moodIntensity?.let { put("moodIntensity", it) }
        persona.moodReason?.let { put("moodReason", it) }
        persona.moodUpdatedAt?.let { put("moodUpdatedAt", it) }
        persona.moodNudgedAt?.let { put("moodNudgedAt", it) }
        put("traits", persona.traits)
        put("likes", persona.likes)
        put("behaviors", persona.behaviors)
        persona.backstory?.let { put("backstory", it) }
        persona.quirks?.let { put("quirks", it) }
        put("proactiveLevel", persona.proactiveLevel)
        put("memoryRetention", persona.memoryRetention)
        put("extractionEvery", persona.extractionEvery)
        put("sleepEnabled", persona.sleepEnabled)
        put("wakeThreshold", persona.wakeThreshold)
        put("dndOnSleep", persona.dndOnSleep)
        persona.worldJson?.let { put("worldJson", it) }
        persona.speechStyleJson?.let { put("speechStyleJson", it) }
        persona.activityJson?.let { put("activityJson", it) }
        persona.translationJson?.let { put("translationJson", it) }
        persona.translationLang?.let { put("translationLang", it) }
    }

    /**
     * 服务端响应 → 要写回 Room 的实体。
     *
     * 以 [prepared]（客户端刚构造、还没落库的那一行）为底：服务端只负责分配 id 与时间戳；
     * 本地那些服务端不理解的字段（心情、翻译缓存、人设卡…）不能因为响应里没有就被清空。
     */
    fun localEntity(server: JsonObject, prepared: PersonaEntity): PersonaEntity {
        val id = server.string("id")?.takeIf { it.isNotBlank() } ?: prepared.id
        return prepared.copy(
            id = id,
            name = server.string("name")?.takeIf { it.isNotBlank() } ?: prepared.name,
            description = server.string("description") ?: prepared.description,
            avatarUri = prepared.avatarUri
                ?: ServerMediaRef.canonical(server.string("avatarUrl")),
            isDefault = server.bool("isDefault") ?: prepared.isDefault,
            createdAt = server.long("createdAt")?.takeIf { it > 0L } ?: prepared.createdAt,
            updatedAt = server.long("updatedAt")?.takeIf { it > 0L } ?: prepared.updatedAt
        )
    }
}

/**
 * 记忆的服务端映射。
 *
 * 服务端记忆只存 `content / importance(0–100) / tags`（`agentd/store/StoreModel.kt`），
 * 抽屉、分类、pinned、strength 都是客户端字段：写服务端时不丢本地缓存，但别的设备看不到
 * 抽屉分类——这是当前契约的已知边界，不能假装它存在。
 */
object ServerMemoryWrite {

    fun payload(memory: MemoryEntity, creating: Boolean): JsonObject = buildJsonObject {
        if (!creating) put("id", memory.id)
        put("content", memory.content)
        put("importance", importanceToServer(memory.importance))
        val tags = MemoryTags.decode(memory.tags)
        if (tags.isNotEmpty()) put("tags", kotlinx.serialization.json.JsonArray(tags.map(::JsonPrimitive)))
    }

    /** 本地 1–5 → 服务端 0–100；`3` 对应服务端默认的 `50`。 */
    fun importanceToServer(local: Int): Int = (local.coerceIn(1, 5) * 20).coerceIn(0, 100)

    /** 服务端 0–100 → 本地 1–5（四舍五入，0 归到 1，100 归到 5）。 */
    fun importanceFromServer(server: Int): Int = ((server + 10) / 20).coerceIn(1, 5)

    fun localEntity(server: JsonObject, prepared: MemoryEntity): MemoryEntity = prepared.copy(
        id = server.string("id")?.takeIf { it.isNotBlank() } ?: prepared.id,
        content = server.string("content")?.takeIf { it.isNotBlank() } ?: prepared.content,
        importance = server.int("importance")?.let(::importanceFromServer) ?: prepared.importance,
        createdAt = server.long("createdAt")?.takeIf { it > 0L } ?: prepared.createdAt,
        updatedAt = server.long("updatedAt")?.takeIf { it > 0L } ?: prepared.updatedAt
    )
}

/** 预设的服务端映射（服务端字段与本地列名基本同构，头像同样是媒体引用）。 */
object ServerPresetWrite {

    fun payload(
        preset: PersonaPresetEntity,
        avatarRef: String?,
        creating: Boolean,
        includeAvatar: Boolean = true
    ): JsonObject = buildJsonObject {
        if (!creating) put("id", preset.id)
        put("name", preset.name)
        put("description", preset.description)
        put("prompt", preset.prompt)
        put("category", preset.category)
        if (includeAvatar) put("avatarUri", avatarRef?.let(::JsonPrimitive) ?: JsonNull)
        put("avatarSeed", preset.avatarSeed)
        preset.worldJson?.let { put("worldJson", it) }
        preset.speechStyleJson?.let { put("speechStyleJson", it) }
    }

    fun localEntity(server: JsonObject, prepared: PersonaPresetEntity): PersonaPresetEntity = prepared.copy(
        id = server.string("id")?.takeIf { it.isNotBlank() } ?: prepared.id,
        name = server.string("name")?.takeIf { it.isNotBlank() } ?: prepared.name,
        description = server.string("description") ?: prepared.description,
        prompt = server.string("prompt")?.takeIf { it.isNotBlank() } ?: prepared.prompt,
        category = server.string("category") ?: prepared.category,
        avatarUri = prepared.avatarUri ?: ServerMediaRef.canonical(server.string("avatarUri")),
        createdAt = server.long("createdAt")?.takeIf { it > 0L } ?: prepared.createdAt,
        updatedAt = server.long("updatedAt")?.takeIf { it > 0L } ?: prepared.updatedAt
    )
}

/**
 * 设置的白名单映射（服务端 `FileStore.SETTINGS_WHITELIST`）。
 *
 * **只发这十项**：API Key、自定义请求头、服务地址这些绝不能因为「顺手带一份设置」离开设备；
 * 服务端即使收到密钥字段也会忽略，但客户端更不该发。
 */
object ServerSettingsWrite {

    /** 与服务端白名单逐字一致的键；新增设置只能加在这里（单测钉住长度与内容）。 */
    val WHITELIST: Set<String> = linkedSetOf(
        "themeMode",
        "accentColor",
        "fontScale",
        "bubbleStyle",
        "animationsEnabled",
        "hapticsEnabled",
        "chatBackgroundColor",
        "agentLoopEnabled",
        "activePersonaId",
        "autoTranslatePersona"
    )

    fun payload(settings: AppSettings): JsonObject = buildJsonObject {
        put("themeMode", settings.themeMode.name)
        put("accentColor", settings.accentColor)
        put("fontScale", settings.fontScale.toDouble())
        put("bubbleStyle", settings.bubbleStyle.name)
        put("animationsEnabled", settings.animationsEnabled)
        put("hapticsEnabled", settings.hapticsEnabled)
        put("chatBackgroundColor", settings.chatBackgroundColor?.let(::JsonPrimitive) ?: JsonNull)
        put("agentLoopEnabled", settings.agentLoopEnabled)
        put("activePersonaId", settings.activePersonaId?.let(::JsonPrimitive) ?: JsonNull)
        put("autoTranslatePersona", settings.autoTranslatePersona)
    }
}

// --------------------------------------------------------------------------- JSON 小工具
//
// 服务端响应是动态对象（字段可增可缺），这里只做「宽松取值 + 缺省回落」，
// 不引一层额外的反序列化模型：写路径真正依赖的字段就这几个。

internal fun JsonObject.string(key: String): String? =
    runCatching { get(key)?.let { element -> (element as? JsonPrimitive)?.contentOrNull } }.getOrNull()

internal fun JsonObject.long(key: String): Long? =
    runCatching { get(key)?.let { (it as? JsonPrimitive)?.longOrNull } }.getOrNull()

internal fun JsonObject.int(key: String): Int? =
    runCatching { get(key)?.let { (it as? JsonPrimitive)?.intOrNull } }.getOrNull()

internal fun JsonObject.bool(key: String): Boolean? =
    runCatching { get(key)?.let { (it as? JsonPrimitive)?.booleanOrNull } }.getOrNull()

internal fun JsonObject.child(key: String): JsonObject? =
    runCatching { get(key)?.let { it as? JsonObject } }.getOrNull()
