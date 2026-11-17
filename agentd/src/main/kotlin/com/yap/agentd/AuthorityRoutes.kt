package com.yap.agentd

import com.yap.agentd.AgentdApi.Response
import com.yap.agentd.proactive.ActivityAvailability
import com.yap.agentd.proactive.ActivityState
import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaUrl
import com.yap.agentd.store.Message
import com.yap.agentd.store.Persona
import com.yap.agentd.store.Session
import com.yap.agentd.store.StateSnapshot
import com.yap.agentd.store.StoreJson
import com.yap.agentd.store.StoreSessionNotFoundException
import com.yap.app.core.json.JsonParseException
import com.yap.app.core.json.JsonValue
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * 服务端权威模式的第一批 HTTP 路由（`docs/design/server-authoritative.md` §3.1/§3.2/§3.3/§3.4）：
 *
 * | 方法 | 路径 | 说明 |
 * | --- | --- | --- |
 * | `GET`  | `/v1/state?since=&personaId=` | 状态快照（增量） |
 * | `GET`  | `/v1/sessions?personaId=` | 会话列表 |
 * | `GET`  | `/v1/sessions/{id}/messages?beforeBlock=&afterBlock=&limitBlocks=1` | 一页一块的消息分页 |
 * | `POST` | `/v1/sessions/{id}/messages` | 发送消息（SSE 流） |
 * | `POST` | `/v1/personas/{id}/activity` | 上报「他此刻在做什么」（M3 客户端的占用时间） |
 * | `GET`  | `/v1/events?since=&timeoutSeconds=&limit=` | 增量事件（长轮询；SSE 下一阶段） |
 *
 * 为什么路由不放进 `AgentdApi`：`AgentdApi` 是**纯函数**（方法/路径/body → Response），而本文件里的
 * 发送消息要**边生成边写 socket**（SSE），还要长轮询（一次请求最多等 25 秒）；这两件事都离不开连接。
 * 所以 `AgentdServer` 把「路径匹配」交给本对象，JSON 路由直接返回 [Response]，流式路由由
 * [AgentdServer] 提供 [SseSink] 实现（`HttpSseSink`）。
 *
 * 错误体沿用 `{"error":{"message":"…中文…","code":"…"}}`（复用 `AgentdApi.errorResponse`），
 * token/限流/请求体上限在 `AgentdServer` 里已经统一处理，这里不重复。
 */
object AuthorityRoutes {

    const val STATE_PATH = "/v1/state"
    const val SESSIONS_PATH = "/v1/sessions"
    const val PERSONAS_PATH = "/v1/personas"
    const val EVENTS_PATH = "/v1/events"
    private const val MESSAGES_SEGMENT = "messages"

    /** `/v1/sessions/{id}/messages/import` 在 [sessionSubPath] 里的样子（`sub` = `messages/import`）。 */
    private const val MESSAGES_IMPORT_SEGMENT = "messages/import"
    private const val ACTIVITY_SEGMENT = "activity"

    /** 长轮询默认等待（秒）；与契约 §3.4 的「最多 25s」一致。 */
    const val DEFAULT_EVENT_WAIT_SECONDS = 25L

    /** 长轮询上限（秒）：客户端传更大也按这个截断。 */
    const val MAX_EVENT_WAIT_SECONDS = 25L

    /** 一次事件查询最多返回多少条。 */
    const val MAX_EVENT_LIMIT = 500

    /** 一页一块：`limitBlocks` 只接受 1（契约 §3.2 没有 offset，也没有多块一页）。 */
    const val ONLY_LIMIT_BLOCKS = 1

    /** 这条路径是否归本对象处理（未知子路径也归，好回统一的 404）。 */
    fun matches(method: String, rawPath: String): Boolean {
        val path = rawPath.substringBefore('?')
        if (path == STATE_PATH || path == EVENTS_PATH || path == SESSIONS_PATH) return true
        if (path.startsWith("$SESSIONS_PATH/") || path.startsWith("$PERSONAS_PATH/")) return true
        return CrudRoutes.matches(path)
    }

    /** `POST /v1/sessions/{id}/messages`：唯一需要流式写响应的路由。 */
    fun isStreaming(method: String, rawPath: String): Boolean {
        if (!method.equals("POST", ignoreCase = true)) return false
        val sub = sessionSubPath(rawPath.substringBefore('?')) ?: return false
        return sub.second == MESSAGES_SEGMENT
    }

    // ------------------------------------------------------------------ JSON 路由

    /**
     * 处理非流式请求。校验失败一律抛 [AgentdApi.ApiFailure]，在这里就地翻成统一错误体
     * （与 `AgentdApi.handle` 的行为一致：调用方只需要看 [Response]）。
     */
    fun handle(services: AgentdServices, method: String, rawPath: String, body: String?): Response {
        // 批量历史消息导入放宽到 8 MiB（其余 JSON 路由仍是 1 MiB）；`AgentdServer` 在
        // readHead 里用同一个常量挡，所以 handler 直调路径与真实 HTTP 路径的上限一致。
        val bodyLimit = if (CrudRoutes.isMessageImportPath(rawPath.substringBefore('?'))) {
            CrudRoutes.MAX_IMPORT_BODY_BYTES
        } else {
            AgentdApi.MAX_BODY_BYTES
        }
        if (body != null && body.toByteArray(StandardCharsets.UTF_8).size > bodyLimit) {
            return AgentdApi.errorResponse(
                413,
                "content_too_large",
                "请求体超过 ${bodyLimit / 1024} KiB 上限"
            )
        }
        return try {
            val store = requireStore(services)
            val verb = method.uppercase()
            val path = rawPath.substringBefore('?')
            when {
                path == STATE_PATH -> {
                    requireGet(verb, STATE_PATH)
                    state(store, rawPath)
                }

                path == EVENTS_PATH -> {
                    requireGet(verb, EVENTS_PATH)
                    events(store, rawPath)
                }

                path == SESSIONS_PATH -> {
                    when (verb) {
                        "GET" -> sessionList(store, rawPath)
                        "POST" -> CrudRoutes.handle(store, method, rawPath, body)
                        else -> throw AgentdApi.ApiFailure(405, "method_not_allowed", "$SESSIONS_PATH 只支持 GET/POST")
                    }
                }

                path == PERSONAS_PATH -> CrudRoutes.handle(store, method, rawPath, body)

                path.startsWith("$PERSONAS_PATH/") -> {
                    if (EvolutionRoutes.matches(path)) {
                        // 人格进化：evaluate / pending / accept / reject（0.13）。
                        EvolutionRoutes.handle(services, method, rawPath, body)
                    } else if (MoodRoutes.matches(path)) {
                        // 多维情绪判定（0.13）。
                        MoodRoutes.handle(services, method, rawPath, body)
                    } else {
                        val sub = personaSubPath(path)
                        if (sub?.second == ACTIVITY_SEGMENT) {
                            requirePost(verb, "$PERSONAS_PATH/{id}/$ACTIVITY_SEGMENT")
                            activity(store, sub.first, body)
                        } else {
                            // DELETE /v1/personas/{id}（没有子路径）、GET/POST /v1/personas/{id}/memories
                            CrudRoutes.handle(store, method, rawPath, body)
                        }
                    }
                }

                path.startsWith("$SESSIONS_PATH/") -> {
                    val sub = sessionSubPath(path)
                    if (sub != null && sub.second == MESSAGES_SEGMENT) {
                        requireGet(verb, "$SESSIONS_PATH/{id}/$MESSAGES_SEGMENT")
                        messagePage(store, sub.first, rawPath)
                    } else if (sub != null && sub.second == MESSAGES_IMPORT_SEGMENT) {
                        requirePost(verb, "$SESSIONS_PATH/{id}/$MESSAGES_SEGMENT/import")
                        CrudRoutes.importMessages(store, sub.first, body)
                    } else {
                        // POST /v1/sessions/{id}/read（未知子路径由 CrudRoutes 回 404）
                        CrudRoutes.handle(store, method, rawPath, body)
                    }
                }

                CrudRoutes.matches(path) -> CrudRoutes.handle(store, method, rawPath, body)

                else -> throw notFound()
            }
        } catch (failure: AgentdApi.ApiFailure) {
            AgentdApi.errorResponse(failure.status, failure.code, failure.message ?: "请求不合法")
        } catch (invalid: JsonParseException) {
            AgentdApi.errorResponse(400, "invalid_json", "请求体不是合法 JSON：${invalid.message.orEmpty()}")
        } catch (missing: StoreSessionNotFoundException) {
            AgentdApi.errorResponse(404, "not_found", "没有这个会话")
        } catch (missing: com.yap.agentd.store.StorePersonaNotFoundException) {
            AgentdApi.errorResponse(404, "not_found", "没有这个人格")
        } catch (missing: com.yap.agentd.store.StoreMemoryNotFoundException) {
            AgentdApi.errorResponse(404, "not_found", "没有这条记忆")
        } catch (missing: com.yap.agentd.store.StorePresetNotFoundException) {
            AgentdApi.errorResponse(404, "not_found", "没有这个预设")
        } catch (invalid: IllegalArgumentException) {
            // 存储层的参数校验（非法 id、非法角色等）是客户端的问题，不是 500。
            AgentdApi.errorResponse(400, "invalid_argument", invalid.message ?: "参数不合法")
        } catch (unexpected: Exception) {
            AgentdApi.unexpectedFailureResponse(unexpected)
        }
    }

    /**
     * 流式发送消息（SSE）。
     *
     * 重要：[sink] 的第一次写入发生在**所有校验之后**（会话存在、body 合法），所以校验失败时
     * 调用方还能回一个普通 JSON 错误；一旦开始写，出错只能用 `event: error`。
     */
    fun stream(
        services: AgentdServices,
        method: String,
        rawPath: String,
        body: String?,
        sink: SseSink
    ) {
        val store = requireStore(services)
        val chat = services.chatService
            ?: throw AgentdApi.ApiFailure(503, "state_unavailable", "服务端权威模式未启用：没有聊天服务")
        if (!method.equals("POST", ignoreCase = true)) {
            throw AgentdApi.ApiFailure(405, "method_not_allowed", "发送消息只支持 POST")
        }
        if (body != null && body.toByteArray(StandardCharsets.UTF_8).size > AgentdApi.MAX_BODY_BYTES) {
            throw AgentdApi.ApiFailure(413, "content_too_large", "请求体超过 ${AgentdApi.MAX_BODY_BYTES / 1024} KiB 上限")
        }
        val sub = sessionSubPath(rawPath.substringBefore('?')) ?: throw notFound()
        if (sub.second != MESSAGES_SEGMENT) throw notFound()
        val sessionId = sub.first
        if (!FileStore.isValidId(sessionId) || store.session(sessionId) == null) {
            throw AgentdApi.ApiFailure(404, "not_found", "没有这个会话")
        }
        val request = parseSendRequest(body)
        chat.send(sessionId, normalizeAttachments(store, request), sink)
    }

    /**
     * 消息附件落地前的归一化（契约 §3.3/§3.6）：客户端可以传裸 `mediaId` 或 `/v1/media/<id>`，
     * 服务端统一存成 `/v1/media/<id>`，并校验媒体确实存在。
     *
     * 不存在时回 **400 `unknown_media`**（不是 404）：错误在请求体里，不是 URL 指向的资源；
     * 而且这一步发生在 SSE 第一帧之前，客户端还能拿到普通 JSON 错误。
     */
    internal fun normalizeAttachments(store: FileStore, request: SendMessageRequest): SendMessageRequest {
        if (request.attachments.isEmpty()) return request
        val normalized = request.attachments.map { raw ->
            val mediaId = MediaUrl.idOf(raw)
                ?: throw AgentdApi.ApiFailure(
                    400,
                    "invalid_argument",
                    "attachments 里必须是媒体 id 或 /v1/media/<id>"
                )
            if (store.media(mediaId) == null) {
                throw AgentdApi.ApiFailure(400, "unknown_media", "附件不存在或已删除")
            }
            MediaUrl.url(mediaId)
        }
        return request.copy(attachments = normalized)
    }

    // ------------------------------------------------------------------ GET /v1/state

    private fun state(store: FileStore, target: String): Response {
        val query = Query.parse(target)
        val since = query.long("since") ?: 0L
        if (since < 0) throw AgentdApi.ApiFailure(400, "invalid_argument", "since 不能是负数")
        val personaId = query.string("personaId")?.trim()?.takeIf { it.isNotEmpty() }
        if (personaId != null) {
            if (!FileStore.isValidId(personaId)) {
                throw AgentdApi.ApiFailure(400, "invalid_argument", "personaId 格式不对")
            }
            if (store.persona(personaId) == null) {
                throw AgentdApi.ApiFailure(404, "not_found", "没有这个人格")
            }
        }
        val snapshot = store.snapshot(since, personaId)
        return Response(200, StoreJson.stringify(snapshotJson(snapshot)))
    }

    private fun snapshotJson(snapshot: StateSnapshot): JsonValue.Obj = StoreJson.obj(
        "serverTime" to StoreJson.num(snapshot.serverTime),
        // 人格多带一格可有可无的 `activity`（结构化活动对象，`null` = 没在忙）；
        // 磁盘上的 `activityJson` 原样保留，客户端爱用哪个用哪个。
        "personas" to StoreJson.arr(snapshot.personas.map { personaJson(it) }),
        "sessions" to StoreJson.arr(snapshot.sessions.map { it.toJson() }),
        "messages" to StoreJson.arr(snapshot.messages.map { it.toJson() }),
        "mood" to (snapshot.mood?.toJson() ?: JsonValue.Null),
        "presence" to (snapshot.presence?.toJson() ?: JsonValue.Null),
        "serverCursor" to StoreJson.num(snapshot.serverCursor),
        // 契约外的可加字段：首次同步只给最新一块，客户端据此知道要不要继续翻分页接口。
        "hasMoreMessages" to StoreJson.bool(snapshot.messagesTruncated)
    )

    // ------------------------------------------------------------------ POST /v1/personas/{id}/activity

    /**
     * 上报「他此刻在做什么」（`spec/persona-guidance/README.md` §4 的 `set_activity`）。
     *
     * 请求体与客户端 `PersonaActivity` 的 JSON 同构，字段全部可选（除了 `activity`）：
     *
     * ```json
     * {"kind":"WORK","activity":"在事务所赶第三版方案","minutes":90,"availability":"BUSY"}
     * {"kind":"WORK","activity":"在事务所赶第三版方案","until":1763200000000,"availability":"LIMITED"}
     * ```
     *
     * 服务端做的归一化（客户端可以不管格式，但别指望服务端接受乱的意义）：
     * - `minutes` 夹到 15–480；既没给 `minutes` 也没给 `until` 时默认 60 分钟；
     * - `until` 接受毫秒时间戳或 ISO-8601，**必须是未来**（过去的时刻由读取方当 FREE，
     *   写进来就是一条立刻失效的活动，静默接受没意义，直接 400 更诚实）；
     * - `kind` 认不出 → `OTHER`；`availability` 认不出 → `BUSY`（保守：不谎称有空）；
     * - `activity` `trim` 后最多 160 字，空串 → 400。
     *
     * 落库走 [FileStore.updateActivity]（同一把写锁 + 原子重写 `personas.json`），
     * 所以「重启后活动还在」和「主动门禁立刻读到」是同一份数据。
     */
    private fun activity(store: FileStore, personaId: String, body: String?): Response {
        if (!FileStore.isValidId(personaId)) throw notFound()
        if (store.persona(personaId) == null) {
            throw AgentdApi.ApiFailure(404, "not_found", "没有这个人格")
        }
        val payload = parseJsonObject(body)
        val activityText = payload.stringField("activity")?.trim().orEmpty()
        if (activityText.isEmpty()) {
            throw AgentdApi.ApiFailure(400, "invalid_argument", "activity 不能为空（用一句他自己的口吻写）")
        }
        val kind = ActivityState.parseKind(payload.stringField("kind"))
        val availability = ActivityAvailability.parse(payload.stringField("availability"))
        val minutes = payload.intField("minutes")
        val explicitUntil = payload.untilField()
        val now = store.serverTime()
        val until = explicitUntil
            ?: now + ActivityState.clampMinutes(minutes ?: ActivityState.DEFAULT_MINUTES) * 60_000L
        if (until <= now) {
            throw AgentdApi.ApiFailure(
                400,
                "invalid_argument",
                "until 必须是未来的时刻（毫秒时间戳或 ISO-8601）"
            )
        }
        val activity = ActivityState(
            kind = kind,
            activity = activityText.take(ActivityState.MAX_ACTIVITY_CHARS),
            until = until,
            availability = availability
        )
        val persona = store.updateActivity(personaId, ActivityState.encode(activity))
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "persona" to personaJson(persona),
                    "activity" to activity.toJson()
                )
            )
        )
    }

    /**
     * 人格的 HTTP 形状 = 磁盘形状 + `activity`（解析后；`null` = 没设过或坏 JSON）。
     *
     * 为什么两个字段都给：`activityJson` 是**原样文本**（客户端可能直接存下来，或原样再上报），
     * `activity` 是**解析后的对象**（`GET /v1/state` 的读者不用再解析一遍）。
     */
    private fun personaJson(persona: Persona): JsonValue.Obj {
        val fields = LinkedHashMap(persona.toJson().fields)
        fields["activity"] = ActivityState.decode(persona.activityJson)?.toJson() ?: JsonValue.Null
        return JsonValue.Obj(fields)
    }

    // ------------------------------------------------------------------ GET /v1/sessions

    private fun sessionList(store: FileStore, target: String): Response {
        val query = Query.parse(target)
        val personaId = query.string("personaId")?.trim()?.takeIf { it.isNotEmpty() }
        if (personaId != null && !FileStore.isValidId(personaId)) {
            throw AgentdApi.ApiFailure(400, "invalid_argument", "personaId 格式不对")
        }
        val sessions = store.sessions()
            .filter { personaId == null || it.personaId == personaId }
            .sortedWith(compareByDescending<Session> { it.lastMessageAt ?: it.createdAt })
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "serverTime" to StoreJson.num(store.serverTime()),
                    "sessions" to StoreJson.arr(sessions.map { it.toJson() }),
                    "serverCursor" to StoreJson.num(store.cursor())
                )
            )
        )
    }

    // ------------------------------------------------------------------ GET …/{id}/messages

    private fun messagePage(store: FileStore, sessionId: String, target: String): Response {
        if (!FileStore.isValidId(sessionId) || store.session(sessionId) == null) throw notFound()
        val query = Query.parse(target)
        val beforeBlock = query.int("beforeBlock")
        val afterBlock = query.int("afterBlock")
        val limitBlocks = query.int("limitBlocks") ?: ONLY_LIMIT_BLOCKS
        if (beforeBlock != null && afterBlock != null) {
            throw AgentdApi.ApiFailure(
                400,
                "invalid_argument",
                "beforeBlock 与 afterBlock 只能给一个（一页一块：前翻 / 后翻各一次）"
            )
        }
        if (beforeBlock != null && beforeBlock < 0) {
            throw AgentdApi.ApiFailure(400, "invalid_argument", "beforeBlock 不能是负数")
        }
        if (afterBlock != null && afterBlock < 0) {
            throw AgentdApi.ApiFailure(400, "invalid_argument", "afterBlock 不能是负数")
        }
        if (limitBlocks != ONLY_LIMIT_BLOCKS) {
            throw AgentdApi.ApiFailure(
                400,
                "unsupported_limit_blocks",
                "一页一块：limitBlocks 只能是 1（更早/更新用 beforeBlock / afterBlock 翻页）"
            )
        }
        val blocks = store.blocks(sessionId)
        val blockIndex: Int? = when {
            beforeBlock != null -> store.previousBlockIndex(sessionId, beforeBlock).takeIf { it >= 0 }
            afterBlock != null -> store.nextBlockIndex(sessionId, afterBlock).takeIf { it >= 0 }
            else -> blocks.lastOrNull()?.index
        }
        val messages: List<Message> = if (blockIndex == null) emptyList() else store.blockMessages(sessionId, blockIndex)
        val hasMoreOlder: Boolean
        val hasMoreNewer: Boolean
        if (blockIndex == null) {
            // 翻过头了（或会话还没有任何消息）：没有这一页，但反方向还有内容。
            hasMoreOlder = blocks.isNotEmpty() && afterBlock != null
            hasMoreNewer = blocks.isNotEmpty() && beforeBlock != null
        } else {
            hasMoreOlder = blocks.any { it.index < blockIndex }
            hasMoreNewer = blocks.any { it.index > blockIndex }
        }
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "sessionId" to StoreJson.str(sessionId),
                    "blockIndex" to (if (blockIndex == null) JsonValue.Null else StoreJson.num(blockIndex)),
                    "hasMoreOlder" to StoreJson.bool(hasMoreOlder),
                    "hasMoreNewer" to StoreJson.bool(hasMoreNewer),
                    "messages" to StoreJson.arr(messages.map { it.toJson() })
                )
            )
        )
    }

    // ------------------------------------------------------------------ GET /v1/events

    private fun events(store: FileStore, target: String): Response {
        val query = Query.parse(target)
        val since = query.long("since") ?: 0L
        if (since < 0) throw AgentdApi.ApiFailure(400, "invalid_argument", "since 不能是负数")
        val timeoutSeconds = (query.long("timeoutSeconds") ?: DEFAULT_EVENT_WAIT_SECONDS)
            .coerceIn(0L, MAX_EVENT_WAIT_SECONDS)
        val limit = (query.int("limit") ?: 200).coerceIn(1, MAX_EVENT_LIMIT)
        val result = store.awaitEvents(since, timeoutSeconds * 1000L, limit)
        // 没有事件时 cursor 原样返回：客户端不能因为一次空轮询就丢掉「还没收到的事件」。
        return Response(
            200,
            StoreJson.stringify(
                StoreJson.obj(
                    "serverTime" to StoreJson.num(store.serverTime()),
                    "cursor" to StoreJson.num(result.cursor),
                    "events" to StoreJson.arr(result.events.map { it.toJson() })
                )
            )
        )
    }

    // ------------------------------------------------------------------ 请求体解析

    /** 解析「必须是一个 JSON 对象」的 body（空 body / 坏 JSON / 非对象都翻成 400）。 */
    private fun parseJsonObject(body: String?): JsonValue.Obj {
        if (body.isNullOrBlank()) throw AgentdApi.ApiFailure(400, "empty_body", "请求体不能为空")
        val root = try {
            com.yap.app.core.json.Json.parse(body)
        } catch (invalid: JsonParseException) {
            throw AgentdApi.ApiFailure(400, "invalid_json", "请求体不是合法 JSON：${invalid.message.orEmpty()}")
        }
        return root as? JsonValue.Obj
            ?: throw AgentdApi.ApiFailure(400, "invalid_body", "请求体必须是一个 JSON 对象")
    }

    /** 可选字符串字段：缺省/null → null；类型不对 → 400（不猜调用方想说什么）。 */
    private fun JsonValue.Obj.stringField(key: String): String? = when (val value = fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Str -> value.value
        else -> throw AgentdApi.ApiFailure(400, "invalid_argument", "字段 $key 必须是字符串")
    }

    /** 可选整数字段：缺省/null → null；不是整数 → 400。 */
    private fun JsonValue.Obj.intField(key: String): Int? = when (val value = fields[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Num -> value.asInt
            ?: throw AgentdApi.ApiFailure(400, "invalid_argument", "字段 $key 必须是整数")

        else -> throw AgentdApi.ApiFailure(400, "invalid_argument", "字段 $key 必须是整数")
    }

    /** `until`：毫秒时间戳或 ISO-8601；缺省/null → null；两种都认不出来 → 400。 */
    private fun JsonValue.Obj.untilField(): Long? = when (val value = fields["until"]) {
        null, JsonValue.Null -> null
        is JsonValue.Num -> value.asLong
            ?: throw AgentdApi.ApiFailure(400, "invalid_argument", "字段 until 必须是整数毫秒时间戳")

        is JsonValue.Str -> {
            val text = value.value.trim()
            text.toLongOrNull()
                ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
                ?: throw AgentdApi.ApiFailure(
                    400,
                    "invalid_argument",
                    "字段 until 必须是整数毫秒时间戳或 ISO-8601（例如 2026-11-16T13:30:00Z）"
                )
        }

        else -> throw AgentdApi.ApiFailure(
            400,
            "invalid_argument",
            "字段 until 必须是整数毫秒时间戳或 ISO-8601 字符串"
        )
    }

    /** 解析 `POST /v1/sessions/{id}/messages` 的 body（字段与限制见 [SendMessageRequest]）。 */
    internal fun parseSendRequest(body: String?): SendMessageRequest {
        if (body.isNullOrBlank()) throw AgentdApi.ApiFailure(400, "empty_body", "请求体不能为空")
        val root = try {
            com.yap.app.core.json.Json.parse(body)
        } catch (invalid: JsonParseException) {
            throw AgentdApi.ApiFailure(400, "invalid_json", "请求体不是合法 JSON：${invalid.message.orEmpty()}")
        }
        val obj = root as? JsonValue.Obj
            ?: throw AgentdApi.ApiFailure(400, "invalid_body", "请求体必须是一个 JSON 对象")
        val clientMessageId = (obj.fields["clientMessageId"] as? JsonValue.Str)?.value
            ?: throw AgentdApi.ApiFailure(400, "missing_field", "缺少字段 clientMessageId（幂等键，字符串）")
        if (clientMessageId.isBlank()) {
            throw AgentdApi.ApiFailure(400, "invalid_argument", "clientMessageId 不能为空")
        }
        if (clientMessageId.length > SendMessageRequest.MAX_CLIENT_MESSAGE_ID_CHARS) {
            throw AgentdApi.ApiFailure(
                400,
                "invalid_argument",
                "clientMessageId 最长 ${SendMessageRequest.MAX_CLIENT_MESSAGE_ID_CHARS} 个字符"
            )
        }
        val content = (obj.fields["content"] as? JsonValue.Str)?.value
            ?: throw AgentdApi.ApiFailure(400, "missing_field", "缺少字段 content（消息正文，字符串）")
        if (content.isBlank()) {
            throw AgentdApi.ApiFailure(400, "invalid_argument", "content 不能为空")
        }
        if (content.length > SendMessageRequest.MAX_CONTENT_CHARS) {
            throw AgentdApi.ApiFailure(
                400,
                "invalid_argument",
                "content 最长 ${SendMessageRequest.MAX_CONTENT_CHARS} 个字符"
            )
        }
        val attachments = when (val raw = obj.fields["attachments"]) {
            null, JsonValue.Null -> emptyList()
            is JsonValue.Arr -> {
                if (raw.items.size > SendMessageRequest.MAX_ATTACHMENTS) {
                    throw AgentdApi.ApiFailure(
                        400,
                        "invalid_argument",
                        "一条消息最多 ${SendMessageRequest.MAX_ATTACHMENTS} 个附件"
                    )
                }
                raw.items.map { item ->
                    val mediaId = (item as? JsonValue.Str)?.value
                        ?: throw AgentdApi.ApiFailure(400, "invalid_argument", "attachments 里必须都是字符串 mediaId")
                    if (mediaId.isBlank() || mediaId.length > 128) {
                        throw AgentdApi.ApiFailure(400, "invalid_argument", "attachments 里的 mediaId 不合法")
                    }
                    mediaId
                }
            }

            else -> throw AgentdApi.ApiFailure(400, "invalid_argument", "字段 attachments 必须是字符串数组")
        }
        val replyTo = when (val raw = obj.fields["replyTo"]) {
            null, JsonValue.Null -> null
            is JsonValue.Str -> raw.value.takeIf { it.isNotBlank() && it.length <= 128 }
                ?: throw AgentdApi.ApiFailure(400, "invalid_argument", "字段 replyTo 不合法")

            else -> throw AgentdApi.ApiFailure(400, "invalid_argument", "字段 replyTo 必须是字符串或 null")
        }
        return SendMessageRequest(
            clientMessageId = clientMessageId,
            content = content,
            attachments = attachments,
            replyTo = replyTo
        )
    }

    // ------------------------------------------------------------------ 小工具

    private fun requireStore(services: AgentdServices): FileStore = services.store
        ?: throw AgentdApi.ApiFailure(
            503,
            "state_unavailable",
            "服务端权威模式未启用：启动时没有配置 --data（数据目录）"
        )

    private fun requireGet(verb: String, path: String) {
        if (verb != "GET") {
            throw AgentdApi.ApiFailure(405, "method_not_allowed", "$path 只支持 GET")
        }
    }

    private fun requirePost(verb: String, path: String) {
        if (verb != "POST") {
            throw AgentdApi.ApiFailure(405, "method_not_allowed", "$path 只支持 POST")
        }
    }

    private fun notFound(): AgentdApi.ApiFailure =
        AgentdApi.ApiFailure(404, "not_found", "没有这个会话/人格或接口")

    /**
     * `/v1/sessions/{id}/<sub>` → `(id, sub)`；路径形状不对返回 null（由调用方回 404）。
     * 这里只做形状解析，id 的合法性另外用 [FileStore.isValidId] 校验（防 `..` 路径穿越）。
     */
    private fun sessionSubPath(path: String): Pair<String, String>? {
        val prefix = "$SESSIONS_PATH/"
        if (!path.startsWith(prefix)) return null
        val rest = path.substring(prefix.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val sessionId = rest.substring(0, slash)
        val sub = rest.substring(slash + 1)
        if (sessionId.isEmpty() || sub.isEmpty()) return null
        return sessionId to sub
    }

    /** `/v1/personas/{id}/<sub>` → `(id, sub)`；形状不对返回 null（由调用方回 404）。 */
    private fun personaSubPath(path: String): Pair<String, String>? {
        val prefix = "$PERSONAS_PATH/"
        if (!path.startsWith(prefix)) return null
        val rest = path.substring(prefix.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val personaId = rest.substring(0, slash)
        val sub = rest.substring(slash + 1)
        if (personaId.isEmpty() || sub.isEmpty()) return null
        return personaId to sub
    }

    /** 查询串（只取第一个值；未知参数忽略；非法百分号转义 → 400）。 */
    private class Query(private val values: Map<String, String>) {

        fun string(key: String): String? = values[key]

        fun long(key: String): Long? {
            val raw = values[key] ?: return null
            // `?since=` / `?beforeBlock=` 这种空值按「没给」处理：契约里参数是可选的，
            // 客户端拼查询串时很容易留一个空值，不该因此 400。
            if (raw.isEmpty()) return null
            return raw.toLongOrNull()
                ?: throw AgentdApi.ApiFailure(400, "invalid_argument", "参数 $key 必须是整数，收到「${echo(raw)}」")
        }

        fun int(key: String): Int? {
            val raw = values[key] ?: return null
            if (raw.isEmpty()) return null
            return raw.toIntOrNull()
                ?: throw AgentdApi.ApiFailure(400, "invalid_argument", "参数 $key 必须是整数，收到「${echo(raw)}」")
        }

        companion object {
            fun parse(target: String): Query {
                val index = target.indexOf('?')
                if (index < 0 || index == target.length - 1) return Query(emptyMap())
                val raw = target.substring(index + 1)
                val values = LinkedHashMap<String, String>()
                for (pair in raw.split('&')) {
                    if (pair.isEmpty()) continue
                    val equals = pair.indexOf('=')
                    val name = decode(if (equals < 0) pair else pair.substring(0, equals))
                    val value = if (equals < 0) "" else decode(pair.substring(equals + 1))
                    if (!values.containsKey(name)) values[name] = value
                }
                return Query(values)
            }

            private fun decode(text: String): String = try {
                URLDecoder.decode(text, StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                throw AgentdApi.ApiFailure(400, "invalid_argument", "查询串里有非法的百分号转义")
            }

            private fun echo(raw: String): String = if (raw.length <= 32) raw else raw.take(32) + "…"
        }
    }
}
