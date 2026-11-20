package com.yap.agentd.store

import com.yap.app.core.json.Json
import com.yap.app.core.json.JsonValue

/**
 * 备份 **v6** 的「服务端状态 → 客户端同形 JSON」映射（`app/src/main/java/com/yap/app/domain/BackupCodec.kt`）。
 *
 * 为什么需要单独一层：服务端的存储形状（`Persona` / `Session` / `Message` / `MediaItem`）
 * 是**内部契约**，它只需要「够用」；而备份文件是**跨端契约**，字段名必须和客户端 v6 逐字一致，
 * 否则 Android/iOS 的 `BackupCodec.decode` 虽然不会崩（它 `ignoreUnknownKeys`），
 * 但会把整段读成默认值——那比读不出来更难查。
 *
 * 三条规则（与客户端 `BackupBundle` 的序列化行为一一对应）：
 * 1. **非空默认值的键总是写**（客户端 `encodeDefaults = true`）：所以 `personas[]` 里
 *    `avatarSeed/prompt/category/version/...` 即使等于默认值也在；
 * 2. **可空字段为 null 时省略**（客户端 `explicitNulls = false`）：`remindAt` / `summary` /
 *    `toolTraceJson` 这些没有就不写，不写 = null；
 * 3. **服务端多出来的键（`nameEn` / `mediaSkipped`）照写**：客户端忽略未知键，但服务端自己
 *    再导入时要靠它们保持原样（`nameEn` 就是典型：不写就丢了）。
 */
internal object BackupV6 {

    const val FORMAT_NAME = FileStore.BACKUP_FORMAT_NAME
    const val VERSION = FileStore.BACKUP_FORMAT_VERSION

    /** 客户端 `BackupSettings` 的固定默认值（服务端没有这条设置时用它补，键集才对齐）。 */
    const val DEFAULT_THEME_MODE = "SYSTEM"
    const val DEFAULT_ACCENT_COLOR = 0xFFC76F4E
    const val DEFAULT_FONT_SCALE = 1.0
    const val DEFAULT_BUBBLE_STYLE = "SOFT"

    /** 客户端 `BackupSettings` 的九个键（顺序同客户端；其它服务端设置键不进备份）。 */
    val SETTINGS_KEYS = listOf(
        "themeMode",
        "accentColor",
        "fontScale",
        "bubbleStyle",
        "animationsEnabled",
        "hapticsEnabled",
        "chatBackgroundColor",
        "agentLoopEnabled",
        "activePersonaId"
    )

    // ------------------------------------------------------------------ personas[]

    /**
     * 人格（客户端 `BackupPersona`）。
     *
     * [mood] 来自 `moods.json`：客户端把「此刻的心情」当成人格的一部分带走，服务端也存在这里，
     * 所以导出时合并回去（导入时再拆开写回 `moods.json`）。
     */
    fun persona(persona: Persona, mood: Mood?, avatarFileName: String?): JsonValue.Obj {
        val extras = persona.extraFields
        val fields = linkedMapOf<String, JsonValue>(
            "id" to StoreJson.str(persona.id),
            "name" to StoreJson.str(persona.name),
            // 服务端专有（客户端忽略）：不写的话服务端自己再导入会丢掉英文名。
            "nameEn" to StoreJson.str(persona.nameEn),
            "description" to StoreJson.str(persona.description),
            "avatarSeed" to StoreJson.num(extraInt(extras, "avatarSeed") ?: 0),
            "prompt" to StoreJson.str(extraString(extras, "prompt")),
            "category" to StoreJson.str(extraString(extras, "category")),
            "version" to StoreJson.num(extraInt(extras, "version") ?: 1),
            "isDefault" to StoreJson.bool(persona.isDefault),
            "createdAt" to StoreJson.num(persona.createdAt),
            "updatedAt" to StoreJson.num(persona.updatedAt),
            "traits" to arrayOfStrings(extras["traits"]),
            "likes" to arrayOfStrings(extras["likes"]),
            "behaviors" to arrayOfStrings(extras["behaviors"]),
            "proactiveLevel" to StoreJson.num(extraInt(extras, "proactiveLevel") ?: 3),
            "memoryRetention" to StoreJson.num(extraInt(extras, "memoryRetention") ?: 3),
            "extractionEvery" to StoreJson.num(extraInt(extras, "extractionEvery") ?: 6),
            "sleepEnabled" to StoreJson.bool(extraBool(extras, "sleepEnabled") ?: true),
            "wakeThreshold" to StoreJson.num(extraInt(extras, "wakeThreshold") ?: 6),
            "dndOnSleep" to StoreJson.bool(extraBool(extras, "dndOnSleep") ?: false)
        )
        // 可空字段：有才写（客户端 explicitNulls = false）。
        putIfNotNull(fields, "avatarUri", avatarFileName ?: persona.avatarUrl?.takeIf { it.isNotBlank() })
        putIfNotNull(fields, "defaultApiConfigId", extraStringOrNull(extras, "defaultApiConfigId"))
        putIfNotNull(fields, "modelOverride", extraStringOrNull(extras, "modelOverride"))
        putIfNotNull(fields, "personaCardJson", extraStringOrNull(extras, "personaCardJson"))
        putIfNotNull(fields, "quietSleepMinutes", extraInt(extras, "quietSleepMinutes")?.let { StoreJson.num(it) })
        putIfNotNull(fields, "quietWakeMinutes", extraInt(extras, "quietWakeMinutes")?.let { StoreJson.num(it) })
        // 心情：服务端的 moods.json 优先，其次人格上残留的客户端列（导入时写进 extraFields）。
        putIfNotNull(
            fields,
            "mood",
            mood?.label?.takeIf { it.isNotBlank() } ?: extraStringOrNull(extras, "mood")
        )
        putIfNotNull(
            fields,
            "moodIntensity",
            mood?.intensity?.let { StoreJson.num(it) } ?: extraInt(extras, "moodIntensity")?.let { StoreJson.num(it) }
        )
        putIfNotNull(
            fields,
            "moodReason",
            mood?.reason?.takeIf { it.isNotBlank() } ?: extraStringOrNull(extras, "moodReason")
        )
        putIfNotNull(
            fields,
            "moodUpdatedAt",
            mood?.updatedAt?.takeIf { it > 0 }?.let { StoreJson.num(it) }
                ?: extraLong(extras, "moodUpdatedAt")?.let { StoreJson.num(it) }
        )
        putIfNotNull(fields, "moodNudgedAt", extraLong(extras, "moodNudgedAt")?.let { StoreJson.num(it) })
        putIfNotNull(fields, "backstory", extraStringOrNull(extras, "backstory"))
        putIfNotNull(fields, "quirks", extraStringOrNull(extras, "quirks"))
        putIfNotNull(fields, "worldJson", extraStringOrNull(extras, "worldJson"))
        putIfNotNull(fields, "speechStyleJson", extraStringOrNull(extras, "speechStyleJson"))
        putIfNotNull(fields, "activityJson", persona.activityJson?.takeIf { it.isNotBlank() })
        // 0.14：拉黑（客户端忽略这两个可加键；服务端自己再导入时要原样带回）。
        putIfNotNull(fields, "blockedUntil", persona.blockedUntil?.takeIf { it > 0 }?.let { StoreJson.num(it) })
        putIfNotNull(fields, "blockReason", persona.blockReason.takeIf { it.isNotBlank() })
        return JsonValue.Obj(fields)
    }

    // ------------------------------------------------------------------ memories[]

    fun memory(memory: Memory): JsonValue.Obj {
        val fields = linkedMapOf<String, JsonValue>(
            "id" to StoreJson.str(memory.id),
            "personaId" to StoreJson.str(memory.personaId),
            "content" to StoreJson.str(memory.content),
            "category" to StoreJson.str("OTHER"),
            "importance" to StoreJson.num(memory.importance),
            "pinned" to StoreJson.bool(false),
            "createdAt" to StoreJson.num(memory.createdAt),
            "updatedAt" to StoreJson.num(memory.updatedAt),
            "lastUsedAt" to StoreJson.num(0),
            "useCount" to StoreJson.num(0),
            "status" to StoreJson.str("ACTIVE"),
            "strength" to StoreJson.num(1.0),
            "driftCount" to StoreJson.num(0)
        )
        // 客户端 `tags` 是 **JSON 字符串**（Room 列的形状），不是数组；导出成数组会让它整条丢掉。
        if (memory.tags.isNotEmpty()) {
            fields["tags"] = StoreJson.str(
                Json.stringify(JsonValue.Arr(memory.tags.map { JsonValue.Str(it) as JsonValue }.toMutableList()))
            )
        }
        return JsonValue.Obj(fields)
    }

    // ------------------------------------------------------------------ sessions[]

    fun session(session: Session): JsonValue.Obj {
        val extras = session.extraFields
        val fields = linkedMapOf<String, JsonValue>(
            "id" to StoreJson.str(session.id),
            "personaId" to StoreJson.str(session.personaId),
            "model" to StoreJson.str(extraString(extras, "model")),
            "title" to StoreJson.str(session.title),
            "unreadCount" to StoreJson.num(session.unread),
            "pinned" to StoreJson.bool(extraBool(extras, "pinned") ?: false),
            "createdAt" to StoreJson.num(session.createdAt),
            "updatedAt" to StoreJson.num(session.updatedAt),
            "lastPersonaVersion" to StoreJson.num(extraInt(extras, "lastPersonaVersion") ?: 0),
            "lastMemoryExtractedCount" to StoreJson.num(extraInt(extras, "lastMemoryExtractedCount") ?: 0)
        )
        putIfNotNull(fields, "apiConfigId", extraStringOrNull(extras, "apiConfigId"))
        putIfNotNull(fields, "lastMessagePreview", extraStringOrNull(extras, "lastMessagePreview"))
        putIfNotNull(fields, "lastMessageAt", session.lastMessageAt?.let { StoreJson.num(it) })
        putIfNotNull(fields, "summary", extraStringOrNull(extras, "summary"))
        putIfNotNull(fields, "summarizedUpTo", extraLong(extras, "summarizedUpTo")?.let { StoreJson.num(it) })
        putIfNotNull(fields, "summaryAt", extraLong(extras, "summaryAt")?.let { StoreJson.num(it) })
        putIfNotNull(
            fields,
            "memoryRetentionOverride",
            extraInt(extras, "memoryRetentionOverride")?.let { StoreJson.num(it) }
        )
        putIfNotNull(
            fields,
            "extractionEveryOverride",
            extraInt(extras, "extractionEveryOverride")?.let { StoreJson.num(it) }
        )
        return JsonValue.Obj(fields)
    }

    // ------------------------------------------------------------------ messages[]

    /**
     * 消息（客户端 `BackupMessage`）。
     *
     * [attachmentsJson] 由调用方用 `mediaIndex` 拼好（裸文件名 + mime），因为流式导出时
     * 每条消息都要查一次媒体表；这里只负责形状。
     */
    fun message(message: Message, personaId: String, attachmentsJson: String?): JsonValue.Obj {
        val fields = linkedMapOf<String, JsonValue>(
            "id" to StoreJson.str(message.id),
            "sessionId" to StoreJson.str(message.sessionId),
            "personaId" to StoreJson.str(personaId),
            "role" to StoreJson.str(message.role),
            "content" to StoreJson.str(message.content),
            "createdAt" to StoreJson.num(message.createdAt),
            "updatedAt" to StoreJson.num(message.createdAt),
            "status" to StoreJson.str("COMPLETE"),
            "edited" to StoreJson.bool(false)
        )
        putIfNotNull(fields, "attachmentsJson", attachmentsJson)
        putIfNotNull(fields, "toolTraceJson", message.toolTraceJson)
        putIfNotNull(fields, "readAt", message.readAt?.let { StoreJson.num(it) })
        // 0.14：拉黑期间落库的消息（客户端忽略未知键；服务端自己再导入时要保留这个永久标记）。
        if (message.blocked) fields["blocked"] = StoreJson.bool(true)
        return JsonValue.Obj(fields)
    }

    // ------------------------------------------------------------------ presets[]

    fun preset(preset: Preset, avatarFileName: String?): JsonValue.Obj {
        val extras = preset.extraFields
        val fields = linkedMapOf<String, JsonValue>(
            "id" to StoreJson.str(preset.id),
            "name" to StoreJson.str(preset.name),
            "description" to StoreJson.str(preset.description),
            "prompt" to StoreJson.str(preset.prompt),
            "category" to StoreJson.str(preset.category),
            "avatarSeed" to StoreJson.num(preset.avatarSeed),
            "createdAt" to StoreJson.num(preset.createdAt),
            "updatedAt" to StoreJson.num(preset.updatedAt)
        )
        putIfNotNull(fields, "avatarUri", avatarFileName ?: preset.avatarUri?.takeIf { it.isNotBlank() })
        putIfNotNull(fields, "worldJson", preset.worldJson ?: extraStringOrNull(extras, "worldJson"))
        putIfNotNull(fields, "speechStyleJson", preset.speechStyleJson ?: extraStringOrNull(extras, "speechStyleJson"))
        return JsonValue.Obj(fields)
    }

    // ------------------------------------------------------------------ apiConfigs[]

    fun apiConfig(config: ApiConfig): JsonValue.Obj {
        val fields = linkedMapOf<String, JsonValue>(
            "id" to StoreJson.str(config.id),
            "name" to StoreJson.str(config.name),
            "provider" to StoreJson.str(config.provider.ifBlank { "OPENAI" }),
            "baseUrl" to StoreJson.str(config.baseUrl),
            "model" to StoreJson.str(config.model),
            "allowInsecureHttp" to StoreJson.bool(false),
            "webToolsEnabled" to StoreJson.bool(false),
            "webToolsBaseUrl" to StoreJson.str(""),
            // 规范默认值：缺字段的老备份读到 MESSAGEAPI（`WebToolsProviders.normalize`）。
            "webToolsProvider" to StoreJson.str("MESSAGEAPI"),
            "createdAt" to StoreJson.num(config.createdAt),
            "updatedAt" to StoreJson.num(config.updatedAt)
        )
        return JsonValue.Obj(fields)
    }

    // ------------------------------------------------------------------ settings

    /** 客户端 `BackupSettings`：九个键**总是**写（缺的用客户端默认值补）。 */
    fun settings(settings: JsonValue.Obj): JsonValue.Obj {
        fun value(key: String): JsonValue? = settings.fields[FileStore.canonicalSettingKey(key)]
        val fields = linkedMapOf<String, JsonValue>(
            "themeMode" to (value("themeMode") ?: StoreJson.str(DEFAULT_THEME_MODE)),
            "accentColor" to (value("accentColor") ?: StoreJson.num(DEFAULT_ACCENT_COLOR.toLong())),
            "fontScale" to (value("fontScale") ?: StoreJson.num(DEFAULT_FONT_SCALE)),
            "bubbleStyle" to (value("bubbleStyle") ?: StoreJson.str(DEFAULT_BUBBLE_STYLE)),
            "animationsEnabled" to (value("animationsEnabled") ?: StoreJson.bool(true)),
            "hapticsEnabled" to (value("hapticsEnabled") ?: StoreJson.bool(true))
        )
        putIfNotNull(fields, "chatBackgroundColor", value("chatBackgroundColor"))
        fields["agentLoopEnabled"] = value("agentLoopEnabled") ?: StoreJson.bool(true)
        putIfNotNull(fields, "activePersonaId", value("activePersonaId"))
        // 服务端白名单里另外几格（`userAvatarUri` / `autoTranslatePersona`）客户端备份结构里没有：
        // 有值就照写——客户端 `ignoreUnknownKeys` 会忽略，但**服务端自己**再导入时还在
        //（用户的头像不能在「导出→导入」一次后丢掉）。
        for (key in FileStore.SETTINGS_WHITELIST) {
            if (key in SETTINGS_KEYS) continue
            // 历史别名（`myAvatarUri`）只作为**读取**入口：写出时只用规范键，否则同一份
            // 备份里会出现两个名字指向同一个值。
            if (FileStore.canonicalSettingKey(key) != key) continue
            putIfNotNull(fields, key, value(key))
        }
        return JsonValue.Obj(fields)
    }

    // ------------------------------------------------------------------ stickers[] / diaryNotes[]

    fun sticker(sticker: Sticker): JsonValue.Obj {
        val fields = linkedMapOf<String, JsonValue>(
            "id" to StoreJson.str(sticker.id),
            "fileName" to StoreJson.str(sticker.file),
            // iOS 端读 `file`：两个都写，任一端都能接住（跨端契约只能加不能改）。
            "file" to StoreJson.str(sticker.file),
            "mime" to StoreJson.str(sticker.mime),
            "width" to StoreJson.num(sticker.width),
            "height" to StoreJson.num(sticker.height),
            "frames" to StoreJson.num(sticker.frames),
            "bytes" to StoreJson.num(sticker.bytes),
            "source" to StoreJson.str(sticker.source),
            "createdBy" to StoreJson.str(sticker.createdBy),
            "createdAt" to StoreJson.num(sticker.createdAt)
        )
        putIfNotNull(fields, "ownerUserId", sticker.ownerUserId)
        putIfNotNull(fields, "personaId", sticker.personaId)
        return JsonValue.Obj(fields)
    }

    fun diaryNote(note: DiaryNote): JsonValue.Obj {
        val fields = linkedMapOf<String, JsonValue>(
            "id" to StoreJson.str(note.id),
            "personaId" to StoreJson.str(note.personaId),
            "content" to StoreJson.str(note.content),
            "createdAt" to StoreJson.num(note.createdAt),
            "updatedAt" to StoreJson.num(note.updatedAt)
        )
        putIfNotNull(fields, "remindAt", note.remindAt?.let { StoreJson.num(it) })
        putIfNotNull(fields, "remindedAt", note.remindedAt?.let { StoreJson.num(it) })
        return JsonValue.Obj(fields)
    }

    fun intent(intent: StoredIntent): JsonValue.Obj {
        val fields = linkedMapOf<String, JsonValue>(
            "id" to StoreJson.str(intent.id),
            "personaId" to StoreJson.str(intent.personaId),
            "kind" to StoreJson.str(intent.kind),
            "earliestAt" to StoreJson.num(intent.earliestAt),
            "latestAt" to StoreJson.num(intent.latestAt),
            "note" to StoreJson.str(intent.note),
            "hint" to StoreJson.str(intent.hint),
            "status" to StoreJson.str(intent.status),
            "createdAt" to StoreJson.num(intent.createdAt)
        )
        putIfNotNull(fields, "sessionId", intent.sessionId)
        putIfNotNull(fields, "sentAt", intent.sentAt?.let { StoreJson.num(it) })
        return JsonValue.Obj(fields)
    }

    // ------------------------------------------------------------------ 0.14：心理活动 / 会话记忆

    fun mentalActivity(activity: MentalActivity): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "id" to StoreJson.str(activity.id),
            "personaId" to StoreJson.str(activity.personaId),
            "sessionId" to StoreJson.str(activity.sessionId),
            "at" to StoreJson.num(activity.at),
            "thought" to StoreJson.str(activity.thought),
            "moodLabel" to StoreJson.str(activity.moodLabel),
            "moodValence" to StoreJson.num(activity.moodValence),
            "moodArousal" to StoreJson.num(activity.moodArousal),
            "moodDominance" to StoreJson.num(activity.moodDominance),
            "moodIntensity" to StoreJson.num(activity.moodIntensity)
        )
    )

    fun sessionMemory(memory: SessionMemory): JsonValue.Obj = memory.toBackupJson()

    // ------------------------------------------------------------------ 小工具

    /** 数组或「JSON 数组字符串」（客户端 Room 列的形状）都归一成字符串数组。 */
    fun arrayOfStrings(value: JsonValue?): JsonValue.Arr {
        val items = when (value) {
            null, JsonValue.Null -> emptyList()
            is JsonValue.Arr -> value.items
            is JsonValue.Str -> {
                val text = value.value.trim()
                if (text.isEmpty()) {
                    emptyList()
                } else {
                    val parsed = runCatching { Json.parse(text) }.getOrNull()
                    (parsed as? JsonValue.Arr)?.items ?: emptyList()
                }
            }

            else -> emptyList()
        }
        return JsonValue.Arr(items.filterIsInstance<JsonValue.Str>().map { it as JsonValue }.toMutableList())
    }

    fun extraStringOrNull(extras: Map<String, JsonValue>, key: String): String? {
        val value = extras[key] ?: return null
        if (value is JsonValue.Null) return null
        val text = (value as? JsonValue.Str)?.value ?: return null
        return text.takeIf { it.isNotBlank() }
    }

    fun extraString(extras: Map<String, JsonValue>, key: String): String =
        extraStringOrNull(extras, key).orEmpty()

    fun extraInt(extras: Map<String, JsonValue>, key: String): Int? {
        val value = extras[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Num)?.asInt
            ?: (value as? JsonValue.Str)?.value?.trim()?.toIntOrNull()
    }

    fun extraLong(extras: Map<String, JsonValue>, key: String): Long? {
        val value = extras[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Num)?.asLong
    }

    fun extraBool(extras: Map<String, JsonValue>, key: String): Boolean? {
        val value = extras[key] ?: return null
        if (value is JsonValue.Null) return null
        return (value as? JsonValue.Bool)?.value
    }

    private fun putIfNotNull(fields: LinkedHashMap<String, JsonValue>, key: String, value: JsonValue?) {
        if (value == null || value is JsonValue.Null) return
        fields[key] = value
    }

    private fun putIfNotNull(fields: LinkedHashMap<String, JsonValue>, key: String, value: String?) {
        if (value == null) return
        fields[key] = StoreJson.str(value)
    }
}
