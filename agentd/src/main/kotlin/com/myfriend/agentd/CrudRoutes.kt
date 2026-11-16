package com.myfriend.agentd

import com.myfriend.agentd.AgentdApi.Response
import com.myfriend.agentd.proactive.ActivityState
import com.myfriend.agentd.store.BackupValidationException
import com.myfriend.agentd.store.FileStore
import com.myfriend.agentd.store.Memory
import com.myfriend.agentd.store.Persona
import com.myfriend.agentd.store.Preset
import com.myfriend.agentd.store.PushRegistration
import com.myfriend.agentd.store.Session
import com.myfriend.agentd.store.StoreJson
import com.myfriend.agentd.store.StoreMemoryNotFoundException
import com.myfriend.agentd.store.StorePersonaNotFoundException
import com.myfriend.agentd.store.StorePresetNotFoundException
import com.myfriend.agentd.store.UnsupportedBackupVersionException
import com.myfriend.app.core.json.JsonParseException
import com.myfriend.app.core.json.JsonValue
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * M3–M6 的服务端写路径（`docs/design/server-authoritative.md` §3.6）。
 *
 * 与 [AuthorityRoutes] 的分工：这里全是「一次性 JSON 响应」的 CRUD；SSE 发送、长轮询和
 * 已有的状态/分页仍留在 [AuthorityRoutes]。两边共用 `AgentdServer` 的 token/限流/1 MiB
 * 上限，错误体统一走 [AgentdApi.errorResponse]。
 *
 * 所有写操作都落到 [FileStore] 的同一把进程内写锁里；路由层只做字段校验与 JSON 形状，
 * 不自己拼「读旧值→写新值」的跨锁流程。
 */
object CrudRoutes {

    const val SESSIONS_PATH = "/v1/sessions"
    const val PERSONAS_PATH = "/v1/personas"
    const val MEMORIES_PATH = "/v1/memories"
    const val PRESETS_PATH = "/v1/presets"
    const val SETTINGS_PATH = "/v1/settings"
    const val BACKUP_PATH = "/v1/backup"
    const val PUSH_REGISTER_PATH = "/v1/push/register"

    /** 这个路径是否归本对象处理（未知子路径也归，好回统一 404）。 */
    fun matches(path: String): Boolean =
        path == SESSIONS_PATH ||
            path == PERSONAS_PATH || path.startsWith("$PERSONAS_PATH/") ||
            path == MEMORIES_PATH || path.startsWith("$MEMORIES_PATH/") ||
            path == PRESETS_PATH || path.startsWith("$PRESETS_PATH/") ||
            path == SETTINGS_PATH ||
            path == BACKUP_PATH ||
            path == PUSH_REGISTER_PATH

    /**
     * 处理非流式 CRUD。校验失败抛 [AgentdApi.ApiFailure]；调用方（[AuthorityRoutes.handle]）
     * 负责翻成统一错误体。
     */
    fun handle(store: FileStore, method: String, rawPath: String, body: String?): Response {
        val verb = method.uppercase()
        val path = rawPath.substringBefore('?')
        return when {
            path == SESSIONS_PATH -> {
                requireMethod(verb, "POST", SESSIONS_PATH)
                createSession(store, body)
            }

            path.startsWith("$SESSIONS_PATH/") -> sessionSubRoute(store, verb, path, body)

            path == PERSONAS_PATH -> when (verb) {
                "GET" -> listPersonas(store)
                "POST" -> savePersona(store, body)
                else -> throw methodNotAllowed("$PERSONAS_PATH 只支持 GET/POST")
            }

            path.startsWith("$PERSONAS_PATH/") -> personaSubRoute(store, verb, rawPath, body)

            path == MEMORIES_PATH || path.startsWith("$MEMORIES_PATH/") -> memoriesRoute(store, verb, path, body)

            path == PRESETS_PATH -> when (verb) {
                "GET" -> listPresets(store)
                "POST" -> savePreset(store, body)
                else -> throw methodNotAllowed("$PRESETS_PATH 只支持 GET/POST")
            }

            path.startsWith("$PRESETS_PATH/") -> {
                requireMethod(verb, "DELETE", "$PRESETS_PATH/{id}")
                deletePreset(store, path.removePrefix("$PRESETS_PATH/"))
            }

            path == SETTINGS_PATH -> when (verb) {
                "GET" -> getSettings(store)
                "POST" -> postSettings(store, body)
                else -> throw methodNotAllowed("$SETTINGS_PATH 只支持 GET/POST")
            }

            path == BACKUP_PATH -> when (verb) {
                "GET" -> getBackup(store)
                "POST" -> postBackup(store, body)
                else -> throw methodNotAllowed("$BACKUP_PATH 只支持 GET/POST")
            }

            path == PUSH_REGISTER_PATH -> when (verb) {
                "POST" -> registerPush(store, body)
                "DELETE" -> unregisterPush(store, rawPath, body)
                else -> throw methodNotAllowed("$PUSH_REGISTER_PATH 只支持 POST/DELETE")
            }

            else -> throw notFound("没有这个接口")
        }
    }

    // ------------------------------------------------------------------ 会话

    private fun createSession(store: FileStore, body: String?): Response {
        val request = objectBody(body)
        val personaId = request.requiredString("personaId").trim()
        requireValidId(personaId, "personaId")
        val persona = store.persona(personaId) ?: throw personaNotFound(personaId)
        val title = request.optionalString("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "和${persona.name}的对话"
        if (title.length > 200) {
            throw invalid("title 最长 200 个字符")
        }
        val (created, session) = store.ensureSessionForPersona(personaId, title)
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "created" to StoreJson.bool(created),
                    "session" to session.toJson()
                )
            )
        )
    }

    private fun sessionSubRoute(store: FileStore, verb: String, path: String, body: String?): Response {
        val rest = path.removePrefix("$SESSIONS_PATH/")
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.length - 1) throw notFound("没有这个会话接口")
        val sessionId = rest.substring(0, slash)
        val sub = rest.substring(slash + 1)
        if (sub != "read") throw notFound("没有这个会话接口")
        requireMethod(verb, "POST", "$SESSIONS_PATH/{id}/read")
        requireValidId(sessionId, "sessionId")
        if (store.session(sessionId) == null) throw notFound("没有这个会话")
        val payload = optionalObject(body)
        val upTo = payload?.optionalLong("upTo")
        if (upTo != null && upTo < 0) throw invalid("upTo 不能是负数")
        val result = store.markSessionReadUpTo(sessionId, upTo)
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "sessionId" to StoreJson.str(sessionId),
                    "unread" to StoreJson.num(0),
                    "readAt" to StoreJson.num(result.readAt),
                    "marked" to StoreJson.num(result.marked),
                    "session" to result.session.toJson()
                )
            )
        )
    }

    // ------------------------------------------------------------------ 人格

    private fun listPersonas(store: FileStore): Response = Response(
        200,
        StoreJson.stringify(
            StoreJson.obj(
                "serverTime" to StoreJson.num(store.serverTime()),
                "personas" to StoreJson.arr(store.personas().map { personaJson(it) })
            )
        )
    )

    private fun personaSubRoute(store: FileStore, verb: String, rawPath: String, body: String?): Response {
        val path = rawPath.substringBefore('?')
        val rest = path.removePrefix("$PERSONAS_PATH/")
        val slash = rest.indexOf('/')
        if (slash < 0) {
            requireMethod(verb, "DELETE", "$PERSONAS_PATH/{id}")
            return deletePersona(store, rest)
        }
        if (slash <= 0 || slash == rest.length - 1) throw notFound("没有这个人格接口")
        val personaId = rest.substring(0, slash)
        val sub = rest.substring(slash + 1)
        if (sub == "activity") {
            // 活动上报仍由 AuthorityRoutes 校验/归一化；这里不会走到（见 AuthorityRoutes.handle）。
            throw notFound("没有这个人格接口")
        }
        if (sub != "memories") throw notFound("没有这个人格接口")
        requireValidId(personaId, "personaId")
        return when (verb) {
            "GET" -> listMemories(store, personaId, rawPath)
            "POST" -> saveMemory(store, personaId, body)
            else -> throw methodNotAllowed("$PERSONAS_PATH/{id}/memories 只支持 GET/POST")
        }
    }

    private fun savePersona(store: FileStore, body: String?): Response {
        val request = objectBody(body)
        val requestedId = optionalId(request, "id")
        if (requestedId == null) {
            val persona = store.createPersona(null) { now -> personaFromRequest(request, null, now, creating = true) }
            return Response(200, StoreJson.stringify(StoreJson.obj("persona" to personaJson(persona))))
        }
        requireValidId(requestedId, "id")
        if (store.persona(requestedId) == null) throw personaNotFound(requestedId)
        val persona = store.updatePersona(requestedId) { existing, now ->
            personaFromRequest(request, existing, now, creating = false)
        }
        return Response(200, StoreJson.stringify(StoreJson.obj("persona" to personaJson(persona))))
    }

    private fun deletePersona(store: FileStore, personaId: String): Response {
        requireValidId(personaId, "id")
        if (!store.deletePersona(personaId)) throw personaNotFound(personaId)
        return Response(200, StoreJson.stringify(StoreJson.obj("deleted" to StoreJson.bool(true), "id" to StoreJson.str(personaId))))
    }

    /**
     * 把请求字段拼成 [Persona]。
     *
     * - 缺省字段：更新时保留旧值，新建时用空/默认值；
     * - 除核心列以外的键全部进 `extraFields`（未知字段也原样保留）；
     * - `createdAt/updatedAt` 由存储层管，客户端传了也忽略。
     */
    private fun personaFromRequest(
        request: JsonValue.Obj,
        existing: Persona?,
        now: Long,
        creating: Boolean
    ): Persona {
        val name = if (request.fields.containsKey("name")) {
            request.optionalString("name")?.trim().orEmpty()
        } else {
            existing?.name ?: ""
        }
        if (name.isEmpty()) throw missingOrInvalid("name", creating)
        val nameEn = if (request.fields.containsKey("nameEn")) {
            request.optionalString("nameEn").orEmpty()
        } else {
            existing?.nameEn ?: ""
        }
        val description = if (request.fields.containsKey("description")) {
            request.optionalString("description").orEmpty()
        } else {
            existing?.description ?: ""
        }
        if (description.length > MAX_PERSONA_DESCRIPTION_CHARS) {
            throw invalid("description 最长 $MAX_PERSONA_DESCRIPTION_CHARS 个字符")
        }
        val avatarUrl = if (request.fields.containsKey("avatarUrl")) {
            request.optionalString("avatarUrl")
        } else {
            existing?.avatarUrl
        }
        val isDefault = if (request.fields.containsKey("isDefault")) {
            request.optionalBoolean("isDefault") ?: false
        } else {
            existing?.isDefault ?: false
        }
        val activityJson = if (request.fields.containsKey("activityJson")) {
            request.optionalString("activityJson")?.takeIf { it.isNotBlank() }
        } else {
            existing?.activityJson
        }
        if (activityJson != null && activityJson.length > FileStore.MAX_ACTIVITY_JSON_CHARS) {
            throw invalid("activityJson 最长 ${FileStore.MAX_ACTIVITY_JSON_CHARS} 个字符")
        }
        // 按字段名保留客户端字段（worldJson / speechStyleJson / traits / likes / …）。
        val extras = LinkedHashMap<String, JsonValue>()
        existing?.extraFields?.let { extras.putAll(it) }
        for ((key, value) in request.fields) {
            if (key !in Persona.RESERVED_KEYS) extras[key] = value
        }
        return Persona(
            id = existing?.id ?: "",
            name = name,
            nameEn = nameEn,
            description = description,
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
            isDefault = isDefault,
            createdAt = existing?.createdAt ?: now,
            updatedAt = existing?.updatedAt ?: now,
            activityJson = activityJson,
            extraFields = extras
        )
    }

    // ------------------------------------------------------------------ 记忆

    private fun listMemories(store: FileStore, personaId: String, path: String): Response {
        if (store.persona(personaId) == null) throw personaNotFound(personaId)
        val query = UrlQuery.parse(path)
        val limit = (query.int("limit") ?: DEFAULT_MEMORY_LIMIT).coerceIn(1, MAX_MEMORY_LIMIT)
        val since = query.long("since") ?: 0L
        if (since < 0) throw invalid("since 不能是负数")
        val memories = store.memories(personaId, limit = limit, since = since)
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "personaId" to StoreJson.str(personaId),
                    "memories" to StoreJson.arr(memories.map { it.toJson() })
                )
            )
        )
    }

    private fun saveMemory(store: FileStore, personaId: String, body: String?): Response {
        if (!FileStore.isValidId(personaId)) throw invalid("personaId 格式不对")
        if (store.persona(personaId) == null) throw personaNotFound(personaId)
        val request = objectBody(body)
        val memoryId = optionalId(request, "id")
        if (memoryId != null) requireValidId(memoryId, "id")
        val memory = store.saveMemory(personaId, memoryId) { existing, _ ->
            memoryFromRequest(request, existing)
        }
        return Response(200, StoreJson.stringify(StoreJson.obj("memory" to memory.toJson())))
    }

    private fun memoryFromRequest(request: JsonValue.Obj, existing: Memory?): Memory {
        val content = if (request.fields.containsKey("content")) {
            request.optionalString("content")?.trim().orEmpty()
        } else {
            existing?.content.orEmpty()
        }
        if (content.isEmpty()) throw AgentdApi.ApiFailure(400, "missing_field", "缺少字段 content（字符串）")
        if (content.length > MAX_MEMORY_CONTENT_CHARS) throw invalid("content 最长 $MAX_MEMORY_CONTENT_CHARS 个字符")
        val importance = if (request.fields.containsKey("importance")) {
            request.optionalInt("importance") ?: throw invalid("importance 必须是整数")
        } else {
            existing?.importance ?: 50
        }
        if (importance !in 0..100) throw invalid("importance 必须在 0–100")
        val tags = if (request.fields.containsKey("tags")) {
            request.optionalTags(MAX_MEMORY_TAGS, MAX_MEMORY_TAG_CHARS)
        } else {
            existing?.tags ?: emptyList()
        }
        return Memory(
            id = existing?.id ?: "",
            personaId = existing?.personaId ?: "",
            content = content,
            importance = importance,
            tags = tags,
            createdAt = existing?.createdAt ?: 0L,
            updatedAt = existing?.updatedAt ?: 0L
        )
    }

    private fun memoriesRoute(store: FileStore, verb: String, path: String, body: String?): Response {
        if (path == MEMORIES_PATH) throw notFound("没有这个记忆接口")
        requireMethod(verb, "DELETE", "$MEMORIES_PATH/{id}")
        val memoryId = path.removePrefix("$MEMORIES_PATH/")
        requireValidId(memoryId, "id")
        if (!store.deleteMemory(memoryId)) throw memoryNotFound(memoryId)
        return Response(200, StoreJson.stringify(StoreJson.obj("deleted" to StoreJson.bool(true), "id" to StoreJson.str(memoryId))))
    }

    // ------------------------------------------------------------------ 预设

    private fun listPresets(store: FileStore): Response = Response(
        200,
        StoreJson.stringify(StoreJson.obj("presets" to StoreJson.arr(store.presets().map { it.toJson() })))
    )

    private fun savePreset(store: FileStore, body: String?): Response {
        val request = objectBody(body)
        val presetId = optionalId(request, "id")
        if (presetId == null) {
            val preset = store.savePreset(null) { _, now -> presetFromRequest(request, null, now, creating = true) }
            return Response(200, StoreJson.stringify(StoreJson.obj("preset" to preset.toJson())))
        }
        requireValidId(presetId, "id")
        if (store.preset(presetId) == null) throw presetNotFound(presetId)
        val preset = store.savePreset(presetId) { existing, now ->
            presetFromRequest(request, existing, now, creating = false)
        }
        return Response(200, StoreJson.stringify(StoreJson.obj("preset" to preset.toJson())))
    }

    private fun deletePreset(store: FileStore, presetId: String): Response {
        requireValidId(presetId, "id")
        if (!store.deletePreset(presetId)) throw presetNotFound(presetId)
        return Response(200, StoreJson.stringify(StoreJson.obj("deleted" to StoreJson.bool(true), "id" to StoreJson.str(presetId))))
    }

    private fun presetFromRequest(
        request: JsonValue.Obj,
        existing: Preset?,
        now: Long,
        creating: Boolean
    ): Preset {
        val name = if (request.fields.containsKey("name")) {
            request.optionalString("name")?.trim().orEmpty()
        } else {
            existing?.name ?: ""
        }
        if (name.isEmpty()) throw missingOrInvalid("name", creating)
        val extras = LinkedHashMap<String, JsonValue>()
        existing?.extraFields?.let { extras.putAll(it) }
        for ((key, value) in request.fields) {
            if (key !in Preset.RESERVED_KEYS) extras[key] = value
        }
        return Preset(
            id = existing?.id ?: "",
            name = name,
            description = if (request.fields.containsKey("description")) {
                request.optionalString("description").orEmpty()
            } else {
                existing?.description ?: ""
            },
            prompt = if (request.fields.containsKey("prompt")) {
                request.optionalString("prompt").orEmpty()
            } else {
                existing?.prompt ?: ""
            },
            category = if (request.fields.containsKey("category")) {
                request.optionalString("category").orEmpty()
            } else {
                existing?.category ?: ""
            },
            avatarUri = if (request.fields.containsKey("avatarUri")) {
                request.optionalString("avatarUri")
            } else {
                existing?.avatarUri
            },
            avatarSeed = if (request.fields.containsKey("avatarSeed")) {
                request.optionalInt("avatarSeed") ?: 0
            } else {
                existing?.avatarSeed ?: 0
            },
            createdAt = existing?.createdAt ?: now,
            updatedAt = existing?.updatedAt ?: now,
            worldJson = if (request.fields.containsKey("worldJson")) {
                request.optionalString("worldJson")?.takeIf { it.isNotBlank() }
            } else {
                existing?.worldJson
            },
            speechStyleJson = if (request.fields.containsKey("speechStyleJson")) {
                request.optionalString("speechStyleJson")?.takeIf { it.isNotBlank() }
            } else {
                existing?.speechStyleJson
            },
            extraFields = extras
        )
    }

    // ------------------------------------------------------------------ 设置

    private fun getSettings(store: FileStore): Response = Response(
        200,
        StoreJson.stringify(StoreJson.obj("settings" to store.publicSettings()))
    )

    /**
     * 白名单合并设置。**API Key 不在白名单里**：`apiKey` / `apiKeys` / `secrets` 等键
     * 到这里会被忽略，既不落 `settings.json`，也不会出现在响应/备份里。
     */
    private fun postSettings(store: FileStore, body: String?): Response {
        val request = objectBody(body)
        val values = LinkedHashMap<String, JsonValue>()
        for (key in FileStore.SETTINGS_WHITELIST) {
            if (!request.fields.containsKey(key)) continue
            val value = request.fields.getValue(key)
            validateSetting(key, value, store)
            values[key] = value
        }
        val merged = store.mergeSettings(values)
        return Response(200, StoreJson.stringify(StoreJson.obj("settings" to merged)))
    }

    private fun validateSetting(key: String, value: JsonValue, store: FileStore) {
        when (key) {
            "themeMode" -> {
                val mode = (value as? JsonValue.Str)?.value
                if (mode !in setOf("SYSTEM", "LIGHT", "DARK")) throw invalid("themeMode 只能是 SYSTEM/LIGHT/DARK")
            }
            "bubbleStyle" -> {
                val style = (value as? JsonValue.Str)?.value
                if (style !in setOf("SOFT", "COMPACT", "OUTLINED")) throw invalid("bubbleStyle 只能是 SOFT/COMPACT/OUTLINED")
            }
            "accentColor", "chatBackgroundColor" -> {
                if (value is JsonValue.Null && key == "chatBackgroundColor") return
                if (value !is JsonValue.Num || value.asLong == null) throw invalid("设置 $key 必须是整数")
            }
            "fontScale" -> {
                if (value !is JsonValue.Num || value.asDouble.isNaN()) throw invalid("设置 fontScale 必须是数字")
            }
            "animationsEnabled", "hapticsEnabled", "agentLoopEnabled", "autoTranslatePersona" -> {
                if (value !is JsonValue.Bool) throw invalid("设置 $key 必须是 true/false")
            }
            "activePersonaId" -> {
                if (value is JsonValue.Null) return
                val id = (value as? JsonValue.Str)?.value?.trim()
                    ?: throw invalid("设置 activePersonaId 必须是字符串或 null")
                if (!FileStore.isValidId(id) || store.persona(id) == null) {
                    throw invalid("activePersonaId 指向不存在的人格")
                }
            }
        }
    }

    // ------------------------------------------------------------------ 备份

    private fun getBackup(store: FileStore): Response = Response(
        200,
        StoreJson.stringify(store.exportBackup(AgentdApi.VERSION, store.serverTime()))
    )

    private fun postBackup(store: FileStore, body: String?): Response {
        val request = objectBody(body)
        val mode = request.requiredString("mode").trim()
        if (mode != "merge" && mode != "replace") throw invalid("mode 只能是 merge 或 replace")
        val data = request.fields["data"] as? JsonValue.Obj
            ?: throw AgentdApi.ApiFailure(400, "missing_field", "缺少字段 data（备份对象）")
        val result = try {
            store.importBackup(mode, data, store.serverTime())
        } catch (unsupported: UnsupportedBackupVersionException) {
            throw AgentdApi.ApiFailure(409, "unsupported_backup_version", unsupported.message ?: "备份版本不支持")
        } catch (invalidBackup: BackupValidationException) {
            throw AgentdApi.ApiFailure(400, "invalid_backup", invalidBackup.message ?: "备份内容不合法")
        }
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "mode" to StoreJson.str(result.mode),
                    "imported" to StoreJson.obj(
                        "personas" to StoreJson.num(result.personas),
                        "sessions" to StoreJson.num(result.sessions),
                        "messages" to StoreJson.num(result.messages),
                        "memories" to StoreJson.num(result.memories),
                        "presets" to StoreJson.num(result.presets),
                        "settings" to StoreJson.bool(result.settings)
                    )
                )
            )
        )
    }

    // ------------------------------------------------------------------ 推送

    private fun registerPush(store: FileStore, body: String?): Response {
        val request = objectBody(body)
        val platform = request.requiredString("platform").trim().lowercase()
        if (platform !in PushRegistration.PLATFORMS) throw invalid("platform 只能是 ios 或 android")
        val token = request.requiredString("token").trim()
        if (token.isEmpty() || token.length > MAX_PUSH_TOKEN_CHARS) {
            throw invalid("token 不能为空且最长 $MAX_PUSH_TOKEN_CHARS 个字符")
        }
        val bundleId = (request.optionalString("bundleId") ?: request.optionalString("packageName"))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (bundleId != null && bundleId.length > MAX_BUNDLE_ID_CHARS) {
            throw invalid("bundleId 最长 $MAX_BUNDLE_ID_CHARS 个字符")
        }
        val created = store.registerPushToken(platform, token, bundleId)
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "registered" to StoreJson.bool(true),
                    "created" to StoreJson.bool(created),
                    "platform" to StoreJson.str(platform),
                    "bundleId" to StoreJson.nullableStr(bundleId)
                )
            )
        )
    }

    private fun unregisterPush(store: FileStore, rawPath: String, body: String?): Response {
        val payload = optionalObject(body)
        val query = UrlQuery.parse(rawPath)
        val token = (payload?.optionalString("token") ?: query.string("token"))?.trim()
            ?: throw AgentdApi.ApiFailure(400, "missing_field", "缺少字段 token")
        if (token.isEmpty() || token.length > MAX_PUSH_TOKEN_CHARS) throw invalid("token 不合法")
        val platform = (payload?.optionalString("platform") ?: query.string("platform"))
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        if (platform != null && platform !in PushRegistration.PLATFORMS) {
            throw invalid("platform 只能是 ios 或 android")
        }
        val removed = store.unregisterPushToken(token, platform)
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "unregistered" to StoreJson.bool(true),
                    "removed" to StoreJson.bool(removed)
                )
            )
        )
    }

    // ------------------------------------------------------------------ JSON 形状

    private fun objectBody(body: String?): JsonValue.Obj {
        if (body.isNullOrBlank()) throw AgentdApi.ApiFailure(400, "empty_body", "请求体不能为空")
        val root = try {
            com.myfriend.app.core.json.Json.parse(body)
        } catch (invalidJson: JsonParseException) {
            throw AgentdApi.ApiFailure(400, "invalid_json", "请求体不是合法 JSON：${invalidJson.message.orEmpty()}")
        }
        return root as? JsonValue.Obj
            ?: throw AgentdApi.ApiFailure(400, "invalid_body", "请求体必须是一个 JSON 对象")
    }

    private fun optionalObject(body: String?): JsonValue.Obj? {
        if (body.isNullOrBlank()) return null
        val root = try {
            com.myfriend.app.core.json.Json.parse(body)
        } catch (invalidJson: JsonParseException) {
            throw AgentdApi.ApiFailure(400, "invalid_json", "请求体不是合法 JSON：${invalidJson.message.orEmpty()}")
        }
        return root as? JsonValue.Obj
            ?: throw AgentdApi.ApiFailure(400, "invalid_body", "请求体必须是一个 JSON 对象")
    }

    private fun JsonValue.Obj.requiredString(key: String): String {
        val value = fields[key] ?: throw AgentdApi.ApiFailure(400, "missing_field", "缺少字段 $key（字符串）")
        return (value as? JsonValue.Str)?.value
            ?: throw invalid("字段 $key 必须是字符串")
    }

    private fun JsonValue.Obj.optionalString(key: String): String? = when (val value = fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Str -> value.value
        else -> throw invalid("字段 $key 必须是字符串")
    }

    private fun JsonValue.Obj.optionalBoolean(key: String): Boolean? = when (val value = fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Bool -> value.value
        else -> throw invalid("字段 $key 必须是 true/false")
    }

    private fun JsonValue.Obj.optionalInt(key: String): Int? = when (val value = fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Num -> value.asInt ?: throw invalid("字段 $key 必须是整数")
        else -> throw invalid("字段 $key 必须是整数")
    }

    private fun JsonValue.Obj.optionalLong(key: String): Long? = when (val value = fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Num -> value.asLong ?: throw invalid("字段 $key 必须是整数")
        else -> throw invalid("字段 $key 必须是整数")
    }

    /**
     * 记忆标签：HTTP 契约用字符串数组；同时也接受客户端数据库里的 JSON 字符串
     * （`"[\"天气\"]"`）或逗号分隔字符串。输出统一成数组。
     */
    private fun JsonValue.Obj.optionalTags(maxItems: Int, maxChars: Int): List<String> {
        val value = fields["tags"] ?: return emptyList()
        if (value is JsonValue.Null) return emptyList()
        val items = when (value) {
            is JsonValue.Arr -> value.items
            is JsonValue.Str -> {
                val text = value.value.trim()
                if (text.isEmpty()) {
                    emptyList()
                } else {
                    val parsed = runCatching { com.myfriend.app.core.json.Json.parse(text) }.getOrNull()
                    if (parsed is JsonValue.Arr) {
                        parsed.items
                    } else {
                        text.split(',').map { JsonValue.Str(it.trim()) }.filter { (it as JsonValue.Str).value.isNotEmpty() }
                    }
                }
            }
            else -> throw invalid("字段 tags 必须是字符串数组")
        }
        if (items.size > maxItems) throw invalid("字段 tags 最多 $maxItems 项")
        return items.map { item ->
            val text = (item as? JsonValue.Str)?.value ?: throw invalid("字段 tags 里必须都是字符串")
            if (text.length > maxChars) throw invalid("字段 tags 的每项最长 $maxChars 个字符")
            text
        }
    }

    private fun optionalId(request: JsonValue.Obj, key: String): String? {
        val raw = request.optionalString(key)?.trim()
        return raw?.takeIf { it.isNotEmpty() }
    }

    private fun personaJson(persona: Persona): JsonValue.Obj {
        val fields = LinkedHashMap(persona.toJson().fields)
        fields["activity"] = ActivityState.decode(persona.activityJson)?.toJson() ?: JsonValue.Null
        return JsonValue.Obj(fields)
    }

    // ------------------------------------------------------------------ 错误

    private fun requireMethod(verb: String, expected: String, path: String) {
        if (verb != expected) throw methodNotAllowed("$path 只支持 $expected")
    }

    private fun methodNotAllowed(message: String): AgentdApi.ApiFailure =
        AgentdApi.ApiFailure(405, "method_not_allowed", message)

    private fun invalid(message: String): AgentdApi.ApiFailure =
        AgentdApi.ApiFailure(400, "invalid_argument", message)

    private fun missingOrInvalid(field: String, creating: Boolean): AgentdApi.ApiFailure =
        if (creating) {
            AgentdApi.ApiFailure(400, "missing_field", "缺少字段 $field（$field 不能为空）")
        } else {
            AgentdApi.ApiFailure(400, "invalid_argument", "字段 $field 不能为空")
        }

    private fun requireValidId(id: String, field: String) {
        if (!FileStore.isValidId(id)) throw invalid("$field 格式不对")
    }

    private fun notFound(message: String): AgentdApi.ApiFailure =
        AgentdApi.ApiFailure(404, "not_found", message)

    private fun personaNotFound(id: String): AgentdApi.ApiFailure =
        AgentdApi.ApiFailure(404, "not_found", "没有这个人格：$id")

    private fun memoryNotFound(id: String): AgentdApi.ApiFailure =
        AgentdApi.ApiFailure(404, "not_found", "没有这条记忆：$id")

    private fun presetNotFound(id: String): AgentdApi.ApiFailure =
        AgentdApi.ApiFailure(404, "not_found", "没有这个预设：$id")

    /** 查询串（只取第一个值；非法百分号转义 → 400）。 */
    private class UrlQuery(private val values: Map<String, String>) {
        fun string(key: String): String? = values[key]

        fun long(key: String): Long? {
            val raw = values[key] ?: return null
            if (raw.isEmpty()) return null
            return raw.toLongOrNull() ?: throw invalid("参数 $key 必须是整数")
        }

        fun int(key: String): Int? {
            val raw = values[key] ?: return null
            if (raw.isEmpty()) return null
            return raw.toIntOrNull() ?: throw invalid("参数 $key 必须是整数")
        }

        companion object {
            fun parse(target: String): UrlQuery {
                val index = target.indexOf('?')
                if (index < 0 || index == target.length - 1) return UrlQuery(emptyMap())
                val values = LinkedHashMap<String, String>()
                for (pair in target.substring(index + 1).split('&')) {
                    if (pair.isEmpty()) continue
                    val equals = pair.indexOf('=')
                    val name = decode(if (equals < 0) pair else pair.substring(0, equals))
                    val value = if (equals < 0) "" else decode(pair.substring(equals + 1))
                    if (!values.containsKey(name)) values[name] = value
                }
                return UrlQuery(values)
            }

            private fun decode(text: String): String = try {
                URLDecoder.decode(text, StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                throw invalid("查询串里有非法的百分号转义")
            }
        }
    }

    private const val DEFAULT_MEMORY_LIMIT = 200
    private const val MAX_MEMORY_LIMIT = 500
    private const val MAX_MEMORY_CONTENT_CHARS = 8_000
    private const val MAX_MEMORY_TAGS = 32
    private const val MAX_MEMORY_TAG_CHARS = 64
    private const val MAX_PERSONA_DESCRIPTION_CHARS = 4_000
    private const val MAX_PUSH_TOKEN_CHARS = 4_096
    private const val MAX_BUNDLE_ID_CHARS = 256
}
