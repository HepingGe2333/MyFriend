package com.yap.app.data.remote

import com.yap.app.BuildConfig
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 服务端权威 API 的调用失败。
 *
 * 三个字段就是界面/日志需要的全部信息：
 * - [code]：服务端错误体里的 `error.code`（如 `not_found`）或客户端自己的分类
 *   （`timeout` / `io_error` / `invalid_response` / `response_too_large` / `server_unavailable`）；
 * - [status]：HTTP 状态码（网络层失败为 null）；
 * - `message`：服务端文案或一句技术描述。**不含 token**。
 *
 * 继承 [IOException] 而不是 `Exception`：P1b 的仓储层可以继续用「网络错误」这一档
 * 统一做离线/重试分支，和现有 `ApiException` 的调用习惯不冲突。
 */
class ServerApiException(
    message: String,
    val code: String? = null,
    val status: Int? = null,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * `agentd` 服务端权威 API 的客户端（P1a：只做传输，不接 UI）。
 *
 * 覆盖契约 §3.1–§3.4 的五个接口：
 *
 * | 方法 | 接口 |
 * | --- | --- |
 * | [state] | `GET /v1/state?since=&personaId=` |
 * | [sessions] | `GET /v1/sessions?personaId=` |
 * | [messages] | `GET /v1/sessions/{id}/messages?beforeBlock=&afterBlock=` |
 * | [sendMessage] | `POST /v1/sessions/{id}/messages`（SSE） |
 * | [events] | `GET /v1/events?since=&timeoutSeconds=&limit=` |
 *
 * ## 超时
 *
 * 连接 10s、读 30s（[connectTimeoutMillis] / [readTimeoutMillis]，可注入以便单测）。
 * **SSE 例外**：`sendMessage` 用 `readTimeout(0)` 的派生客户端——一次回复可能几十秒不打一个字节
 * （模型慢/服务端排队），读超时会把正常的长回复误判成失败；这条链路的结束条件只有
 * 「服务端 `done`/`error`」或「调用方取消协程」，取消时 [OkHttp Call.cancel] 会关掉 socket。
 *
 * ## 响应体上限
 *
 * 非流式响应 2 MiB（[MAX_RESPONSE_BYTES]）：状态快照再大也不该整份打进堆里爆掉。
 * 先看 `Content-Length` 挡「声明就超大」，读的时候再累计挡「不声明/撒谎」的。
 *
 * ## 线程模型 / 取消
 *
 * 所有请求用 `Call.enqueue` 发、在 OkHttp 的回调线程上解析；[sendMessage] 的 `onEvent`
 * **不是在主线程调用的**（调用方负责切回自己的 dispatcher）。
 * 协程被取消时 `invokeOnCancellation` 立即 `call.cancel()`，连接随之关闭——这是
 * 「客户端断开 SSE 时服务端继续生成并落库」契约的客户端侧保证。
 *
 * ## 安全
 *
 * - 令牌只进 `Authorization: Bearer` 头，不进日志、不进异常文案、不进 [ServerConfig.toString]；
 * - 关闭重定向：307/308 会**保留方法与请求体**跟到跨域地址，令牌与聊天正文就有被转发的风险，
 *   服务端权威模式不需要重定向（与 `RemoteAgentCoreClient` 的处理一致）；
 * - 地址合法性（https / 调试期局域网 http）由 [ServerConfig] 在构造时判定一次。
 */
class ServerApi(
    private val config: ServerConfig,
    private val debuggable: Boolean = BuildConfig.DEBUG,
    httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = DEFAULT_JSON,
    connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS
) : ServerMediaApi {

    /** 服务端根地址（去掉尾部 `/v1`，避免拼出 `/v1/v1/state`）；配置不可用时为 null。 */
    private val root: HttpUrl? =
        if (config.rejection(debuggable) == null) rootOf(config.validatedUrl(debuggable)) else null

    private val client: OkHttpClient = httpClient.newBuilder()
        // 服务端权威模式没有「重定向到另一台机器」的用法；跟随重定向会把 Authorization 与正文
        // 带去 Host 头之外的目标（OkHttp 会跨域保留请求头）。
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    /** SSE 专用：读超时 0（无限），靠 `done` 或取消结束。 */
    private val sseClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** 配置齐不齐（地址合法且打开）；P1b 的 DI 用它决定要不要构造本对象。 */
    val isUsable: Boolean get() = root != null

    // 下面两个只给单测断言「SSE 用的是 0 读超时」用，不参与业务。
    internal val requestReadTimeoutMillis: Long get() = client.readTimeoutMillis.toLong()
    internal val sseReadTimeoutMillis: Long get() = sseClient.readTimeoutMillis.toLong()

    // ------------------------------------------------------------------ 契约 §3.1 状态快照

    /**
     * 拉一次状态快照。
     *
     * @param since 增量游标（epoch 毫秒）；`0` = 首次全量（每个会话只给最新一块）
     * @param personaId 只看某个人格；null = 全部
     */
    suspend fun state(since: Long = 0L, personaId: String? = null): StateSnapshot =
        requestJson<StateSnapshot>(
            requestBuilder(
                url("v1", "state") { builder ->
                    builder.addQueryParameter("since", since.toString())
                    personaId?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("personaId", it) }
                }
            ).get().build()
        )

    // ------------------------------------------------------------------ 契约 §3.1 会话列表

    /** 会话列表（服务端已按 `lastMessageAt` 倒序排好）。 */
    suspend fun sessions(personaId: String? = null): List<ServerSession> =
        requestJson<ServerSessionList>(
            requestBuilder(
                url("v1", "sessions") { builder ->
                    personaId?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("personaId", it) }
                }
            ).get().build()
        ).sessions

    // ------------------------------------------------------------------ 契约 §3.2 消息分页

    /**
     * 取一页消息（一页一块）。
     *
     * [beforeBlock] 与 [afterBlock] 互斥（服务端也这么判）：前翻传前者，后翻传后者；
     * 两个都不传 = 最新一块。签名里没有 `limitBlocks`：契约只接受 1，客户端不给，
     * 让服务端默认值生效，少一个「客户端传了 7、服务端 400」的坑。
     */
    suspend fun messages(
        sessionId: String,
        beforeBlock: Int? = null,
        afterBlock: Int? = null
    ): ServerMessagePage {
        if (beforeBlock != null && afterBlock != null) {
            throw ServerApiException(
                "beforeBlock and afterBlock are mutually exclusive",
                code = "invalid_argument"
            )
        }
        return requestJson(
            requestBuilder(
                url("v1", "sessions", sessionId, "messages") { builder ->
                    beforeBlock?.let { builder.addQueryParameter("beforeBlock", it.toString()) }
                    afterBlock?.let { builder.addQueryParameter("afterBlock", it.toString()) }
                }
            ).get().build()
        )
    }

    // ------------------------------------------------------------------ 0.12.1 批量导入历史消息

    /**
     * `POST /v1/sessions/{id}/messages/import`：把本机历史消息批量搬进服务端（bootstrap 用）。
     *
     * 契约要点（与 `agentd` 并行开发中的导入端点一致）：
     * - **单批最多 [MAX_IMPORT_MESSAGES] 条**：超了直接在客户端失败，不发出去等服务端 400；
     * - **幂等**：每条带 `id`，重试同一批不会产生重复消息；
     * - `createdAt/readAt/role/attachments` 原样保留，服务端按自己的块规则分配 `blockIndex`。
     *
     * 返回的计数只用于进度显示；服务端多给/少给字段都不影响「这批处理完了」这个事实。
     */
    suspend fun importMessages(
        sessionId: String,
        messages: List<ServerMessageImport>
    ): ServerMessageImportResult {
        if (messages.isEmpty()) return ServerMessageImportResult(sessionId = sessionId)
        if (messages.size > MAX_IMPORT_MESSAGES) {
            throw ServerApiException(
                "import batch exceeds the $MAX_IMPORT_MESSAGES message limit",
                code = "invalid_argument"
            )
        }
        // 逐字段手写而不是 `json.encodeToJsonElement`：序列化器默认省略「等于默认值」的字段，
        // `source=IMPORT` 会被省掉、服务端可能落成 CHAT；导入契约要求这几列一定带上。
        return postJson(
            url("v1", "sessions", sessionId, "messages", "import"),
            buildJsonObject {
                put(
                    "messages",
                    buildJsonArray {
                        messages.forEach { message ->
                            add(
                                buildJsonObject {
                                    if (message.id.isNotBlank()) put("id", message.id)
                                    put("role", message.role)
                                    put("content", message.content)
                                    put("createdAt", message.createdAt)
                                    message.readAt?.let { put("readAt", it) }
                                    if (message.attachments.isNotEmpty()) {
                                        put(
                                            "attachments",
                                            buildJsonArray {
                                                message.attachments.forEach { add(it) }
                                            }
                                        )
                                    }
                                    put("source", message.source)
                                    message.clientMessageId?.let { put("clientMessageId", it) }
                                    message.replyTo?.let { put("replyTo", it) }
                                }
                            )
                        }
                    }
                )
            }
        )
    }

    // ------------------------------------------------------------------ 契约 §3.4 增量事件

    /**
     * 长轮询增量事件。
     *
     * @param since 上次拿到的游标
     * @param timeoutSeconds 最多等多久（服务端上限 25s，这里先截断，免得服务端等待时间
     *        超过 30s 读超时导致「等到一半自己超时」）
     */
    suspend fun events(since: Long = 0L, timeoutSeconds: Long? = null, limit: Int? = null): ServerEvents =
        requestJson<ServerEvents>(
            requestBuilder(
                url("v1", "events") { builder ->
                    builder.addQueryParameter("since", since.toString())
                    timeoutSeconds?.let {
                        builder.addQueryParameter(
                            "timeoutSeconds",
                            it.coerceIn(0L, MAX_EVENT_WAIT_SECONDS).toString()
                        )
                    }
                    limit?.let { builder.addQueryParameter("limit", it.coerceAtLeast(1).toString()) }
                }
            ).get().build()
        )

    // ------------------------------------------------------------------ 契约 §3.3 发送（SSE）

    /**
     * 发一条消息并流式接收回复事件。
     *
     * 返回时机：收到 `done` / `error`，或服务端关闭流（异常时抛 [ServerApiException]）。
     * 注意：**返回不等于失败**——即使用户取消，服务端仍会继续生成并落库（契约 §3.3），
     * 下次同步就能拿到。
     *
     * @param clientMessageId 幂等键（由调用方生成并持久化在待发送队列里，重发不产生第二条消息）
     * @param onEvent 事件回调；**在 OkHttp 的回调线程调用**，不要在里面直接改 UI 状态
     */
    suspend fun sendMessage(
        sessionId: String,
        clientMessageId: String,
        content: String,
        attachments: List<String> = emptyList(),
        replyTo: String? = null,
        onEvent: (ServerChatEvent) -> Unit
    ) {
        val payload = buildJsonObject {
            put("clientMessageId", clientMessageId)
            put("content", content)
            if (attachments.isNotEmpty()) {
                put("attachments", buildJsonArray { attachments.forEach { add(it) } })
            }
            replyTo?.takeIf { it.isNotBlank() }?.let { put("replyTo", it) }
        }.toString()
        val request = requestBuilder(url("v1", "sessions", sessionId, "messages"))
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        execute(sseClient.newCall(request)) { response ->
            if (!response.isSuccessful) throw errorFrom(response.code, readBoundedText(response))
            val body = response.body
                ?: throw ServerApiException("server returned an empty event stream", code = "invalid_response")
            var finished = false
            ServerSseParser.readEvents(
                source = body.source(),
                onEvent = { event ->
                    onEvent(event)
                    if (event is ServerChatEvent.Done || event is ServerChatEvent.Error) finished = true
                },
                parser = ServerSseParser(json)
            )
            // 服务端正常会以 done / error 收尾；流被提前掐断（代理超时、进程被杀）时必须让调用方
            // 知道「这一条回复不完整」，而不是当成成功——P1b 靠这个决定是否重试/重新同步。
            if (!finished) {
                throw ServerApiException(
                    "server closed the event stream before done",
                    code = "stream_incomplete"
                )
            }
        }
    }

    // ------------------------------------------------------------------ 健康检查（设置页「测试连接」）

    /**
     * `GET /health`：设置页「测试连接」用的最小请求。
     *
     * 为什么不用 `/v1/state`：状态快照可能很大（首次同步每个会话一块），而「地址/令牌对不对」
     * 只需要一个不会读库的端点。agentd 的 `/health` 与 `/v1/…` 走同一套 token 校验
     * （`Main.kt`：设了 token 后所有请求都要 Bearer），所以能验证认证，又不会拉回整份状态。
     */
    suspend fun health(): ServerHealth = requestJson(
        requestBuilder(url("health")).get().build()
    )

    // ------------------------------------------------------------------ 写路径（M3 最小闭环）

    /**
     * `POST /v1/sessions`：一人格一会话；已存在时服务端返回既有会话（`created=false`）。
     *
     * 成功后调用方负责把 [ServerSessionCreated.session] 落进本地缓存（服务端是唯一真相源）。
     */
    suspend fun createSession(personaId: String, title: String? = null): ServerSessionCreated =
        postJson(
            url("v1", "sessions"),
            buildJsonObject {
                put("personaId", personaId)
                title?.trim()?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
            }
        )

    /**
     * `POST /v1/sessions/{id}/read`：标已读到 [upTo]（null = 全部）。
     *
     * 服务端返回 `{unread:0, readAt, marked}`；失败由调用方决定是重试还是只记日志
     * （读回执不该挡住用户看消息）。
     */
    suspend fun markRead(sessionId: String, upTo: Long? = null): ServerReadReceipt =
        postJson(
            url("v1", "sessions", sessionId, "read"),
            buildJsonObject { upTo?.takeIf { it > 0 }?.let { put("upTo", it) } }
        )

    /**
     * `POST /v1/personas/{id}/activity`：把 `set_activity` 的结果同步给服务端。
     *
     * 服务端会自己夹取 `minutes` / 校验 `until`；客户端只负责把本地已经归一化的活动原样上报。
     */
    suspend fun reportActivity(personaId: String, activity: ServerActivityWrite): ServerActivityReport =
        postJson(
            url("v1", "personas", personaId, "activity"),
            buildJsonObject {
                put("kind", activity.kind)
                put("activity", activity.activity)
                activity.minutes?.let { put("minutes", it) }
                activity.until?.let { put("until", it) }
                put("availability", activity.availability)
            }
        )

    /**
     * `POST /v1/push/register`（M6 脚手架）：服务端保存 token，有凭据时才有真实推送。
     *
     * 本阶段客户端不主动调用；先留方法，等推送凭据/协议落地时接线，避免那时候再改传输层。
     */
    suspend fun registerPush(platform: String, token: String): ServerPushRegistration =
        postJson(
            url("v1", "push", "register"),
            buildJsonObject {
                put("platform", platform)
                put("token", token)
            }
        )

    // ------------------------------------------------------------------ M5：媒体

    /**
     * `POST /v1/media?mime=&kind=&personaId=`：**原始字节**请求体，不是 multipart。
     *
     * 客户端上传前已经把图片压成 JPEG（聊天图 ≤800 KB、头像 ≤400 KB），服务端仍按
     * [ServerMediaLimits.maxUploadBytes]（图片 2 MiB / 音频 8 MiB / 视频 64 MiB）兜底：
     * 压缩器是可替换实现，不能只信它。超限在这里就失败，不把超大 body 发出去再等服务端 413。
     */
    override suspend fun uploadMedia(
        bytes: ByteArray,
        mime: String,
        kind: String,
        personaId: String?
    ): ServerMedia {
        if (bytes.isEmpty()) {
            throw ServerApiException("media body is empty", code = "media_empty")
        }
        val limit = ServerMediaLimits.maxUploadBytes(kind)
        if (bytes.size > limit) {
            throw ServerApiException(
                "media body exceeds the $limit byte upload limit",
                code = "media_too_large"
            )
        }
        val type = mime.trim().takeIf { it.isNotEmpty() } ?: "application/octet-stream"
        val request = requestBuilder(
            url("v1", "media") { builder ->
                builder.addQueryParameter("mime", type)
                builder.addQueryParameter("kind", kind)
                personaId?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("personaId", it) }
            }
        )
            .post(bytes.toRequestBody(type.toMediaType()))
            .build()
        val response = execute(client.newCall(request)) { result ->
            val body = readBoundedText(result)
            if (!result.isSuccessful) throw errorFrom(result.code, body)
            body
        }
        val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull()
            ?: throw invalidResponse(200, IllegalArgumentException("media response is not a JSON object"))
        // 服务端可能回 `{media:{...}}`，也可能直接把条目放根上；两种都接受。
        val item = root["media"]?.jsonObject ?: root
        val media = runCatching { json.decodeFromJsonElement<ServerMedia>(item) }.getOrElse {
            throw invalidResponse(200, it as? Exception ?: IllegalArgumentException(it.message))
        }
        if (media.id.isBlank()) {
            throw ServerApiException(
                "server returned a media entry without id",
                code = "invalid_response"
            )
        }
        return media.copy(url = media.url.ifBlank { ServerMediaRef.canonical(media.id).orEmpty() })
    }

    /**
     * `GET /v1/media/{id}`：下载原始字节（备份/缓存导出/音视频播放用；图片渲染走 Coil 的磁盘缓存）。
     *
     * @param maxBytes 下载上限：图片默认 2 MiB；语音/视频播放缓存传 8 / 64 MiB（0.13）
     */
    suspend fun downloadMedia(
        mediaRef: String,
        maxBytes: Int = ServerMediaLimits.MAX_DOWNLOAD_BYTES
    ): ByteArray {
        val id = ServerMediaRef.idOf(mediaRef)
            ?: throw ServerApiException("not a media reference", code = "invalid_argument")
        val request = requestBuilder(url("v1", "media", id)).get().build()
        return execute(client.newCall(request)) { response ->
            if (!response.isSuccessful) throw errorFrom(response.code, readBoundedText(response))
            val body = response.body
                ?: throw ServerApiException("server returned an empty media body", code = "invalid_response")
            val declared = body.contentLength()
            if (declared > maxBytes) {
                throw ServerApiException("media exceeds the download limit", code = "response_too_large")
            }
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        throw ServerApiException("media exceeds the download limit", code = "response_too_large")
                    }
                    output.write(chunk, 0, read)
                }
            }
            output.toByteArray()
        }
    }

    // ------------------------------------------------------------------ M5：CRUD（§3.6）

    /** `POST /v1/personas`：新建（无 id）或更新（带 id）。返回 `{persona:{…}}` 根对象。 */
    suspend fun savePersona(payload: JsonObject): JsonObject = postJsonObject(url("v1", "personas"), payload)

    /** `DELETE /v1/personas/{id}`：连带会话/消息/记忆。404 由调用方决定是否当「已经没了」。 */
    suspend fun deletePersona(personaId: String) = delete(url("v1", "personas", personaId))

    /** `POST /v1/personas/{id}/memories`：新建（无 id）或更新（带 id）。 */
    suspend fun saveMemory(personaId: String, payload: JsonObject): JsonObject =
        postJsonObject(url("v1", "personas", personaId, "memories"), payload)

    /** `DELETE /v1/memories/{id}`。 */
    suspend fun deleteMemory(memoryId: String) = delete(url("v1", "memories", memoryId))

    /** `POST /v1/presets`：新建/更新预设。 */
    suspend fun savePreset(payload: JsonObject): JsonObject = postJsonObject(url("v1", "presets"), payload)

    /** `DELETE /v1/presets/{id}`。 */
    suspend fun deletePreset(presetId: String) = delete(url("v1", "presets", presetId))

    /** `GET /v1/personas`：完整人格列表（含客户端扩展字段，服务端原样回传）。 */
    suspend fun personas(): List<JsonObject> =
        requestObjectList(url("v1", "personas"), "personas")

    /** `GET /v1/presets`：全部预设（bootstrap 全量拉取用）。 */
    suspend fun presets(): List<JsonObject> =
        requestObjectList(url("v1", "presets"), "presets")

    /**
     * `GET /v1/personas/{id}/memories?limit=&since=`：一个记忆分页。
     *
     * `since` 是 `updatedAt` 游标（服务端过滤 `updatedAt > since`），bootstrap 用它翻完全部记忆：
     * 服务端单页上限 500，`limit` 超过会被夹取，所以客户端自己按 [MEMORY_PAGE_LIMIT] 翻页。
     */
    suspend fun memories(
        personaId: String,
        limit: Int = MEMORY_PAGE_LIMIT,
        since: Long = 0L
    ): List<JsonObject> =
        requestObjectList(
            url("v1", "personas", personaId, "memories") { builder ->
                builder.addQueryParameter("limit", limit.coerceIn(1, MEMORY_PAGE_LIMIT).toString())
                if (since > 0L) builder.addQueryParameter("since", since.toString())
            },
            "memories"
        )

    /** `GET /v1/settings`：服务端公开设置（不含 API Key）。 */
    suspend fun settings(): JsonObject =
        requestJson(requestBuilder(url("v1", "settings")).get().build())


    /** `POST /v1/settings`：白名单合并；请求里出现密钥类字段会被服务端忽略。 */
    suspend fun postSettings(values: JsonObject): JsonObject =
        postJsonObject(url("v1", "settings"), values)

    /**
     * `GET /v1/backup`：服务端导出 v5 备份 JSON。
     *
     * 返回**原始文本**而不是反序列化成 [ServerBackupImport]：这份 JSON 要直接写进用户选的文件，
     * 客户端不能先解析再重新编码（字段顺序/未知字段都该原样保留）。
     */
    suspend fun exportBackup(): String {
        val request = requestBuilder(url("v1", "backup")).get().build()
        return execute(client.newCall(request)) { response ->
            // 备份是唯一可能超过普通 2 MiB 响应上限的 JSON（聊天越多越大）；
            // 单独的 64 MiB 上限仍然挡得住「服务端错误地回了一个几 GB 的东西」。
            val body = readBoundedText(response, MAX_BACKUP_RESPONSE_BYTES)
            if (!response.isSuccessful) throw errorFrom(response.code, body)
            body
        }
    }

    /**
     * `POST /v1/backup`：导入 `{mode:"merge"|"replace", data:<备份对象>}`。
     *
     * `data` 是**对象**而不是字符串：服务端校验的是结构；把 JSON 编码成字符串再塞进去，
     * 服务端要么拒收、要么得再解一次（两边多一层转义）。
     */
    suspend fun importBackup(mode: String, data: JsonObject): ServerBackupImport = postJson(
        url("v1", "backup"),
        buildJsonObject {
            put("mode", mode)
            put("data", data)
        }
    )

    // ------------------------------------------------------------------ 0.13：云端情绪 / 人格进化 / 配置推送

    /**
     * `POST /v1/personas/{id}/mood/evaluate`：让**服务端**用自己的模型判定一次多维情绪。
     *
     * 与服务端「没配模型也不报错」的约定一致：端点仍回 200，`source` 说明是
     * `llm` / `decay` / `unchanged`。客户端只在 [ServerMoodEvaluation.mood] 有值时写本地缓存。
     */
    suspend fun evaluateMood(personaId: String): ServerMoodEvaluation =
        postJson(
            url("v1", "personas", personaId, "mood", "evaluate"),
            buildJsonObject { }
        )

    /**
     * `POST /v1/personas/{id}/evolution/evaluate`：让服务端评估一次人格进化并写服务端待审表。
     *
     * 返回体里的 `patch` 是**服务端分配 id** 的补丁（客户端把它缓存到本地待审表，
     * 接受/拒绝时用这个 id 调服务端端点）。
     */
    suspend fun evaluateEvolution(personaId: String): ServerEvolutionEvaluation =
        postJson(
            url("v1", "personas", personaId, "evolution", "evaluate"),
            buildJsonObject { }
        )

    /** `GET /v1/personas/{id}/evolution/pending`：服务端待审补丁（服务端已顺手清掉过期项）。 */
    suspend fun pendingEvolution(personaId: String): ServerEvolutionPending =
        requestJson(
            requestBuilder(url("v1", "personas", personaId, "evolution", "pending")).get().build()
        )

    /** `POST /v1/personas/{id}/evolution/{patchId}/accept`：服务端合并四字段 + `version + 1`。 */
    suspend fun acceptEvolution(personaId: String, patchId: String): ServerEvolutionAcceptance =
        postJson(
            url("v1", "personas", personaId, "evolution", patchId, "accept"),
            buildJsonObject { }
        )

    /** `POST /v1/personas/{id}/evolution/{patchId}/reject`：只丢补丁，人格不动。 */
    suspend fun rejectEvolution(personaId: String, patchId: String): ServerEvolutionRejection =
        postJson(
            url("v1", "personas", personaId, "evolution", patchId, "reject"),
            buildJsonObject { }
        )

    /**
     * `POST /v1/llm-config`（**仅本机部署**）：把客户端已经配好的模型配置推给服务端。
     *
     * 远程部署由服务端回 403 `remote_config_disallowed`；客户端**不重试、不提示**，
     * 只记一条日志（推送是让本机服务端「不用再配一遍」的便利，不是聊天链路的一部分）。
     */
    suspend fun pushLlmConfig(payload: ServerLlmConfigWrite): JsonObject =
        postJsonObject(
            url("v1", "llm-config"),
            buildJsonObject {
                put("baseUrl", payload.baseUrl)
                put("model", payload.model)
                put("apiKey", payload.apiKey)
                put("provider", payload.provider)
            }
        )

    // ------------------------------------------------------------------ 内部：请求构造 / 执行 / 解析

    private fun requestBuilder(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        config.authorizationHeader()?.let { builder.header("Authorization", it) }
        return builder
    }

    /** 协议路径：`<root>/v1/...`；root 已去掉尾部 `/v1`。 */
    private fun url(vararg segments: String, query: (HttpUrl.Builder) -> Unit = {}): HttpUrl {
        val base = root ?: throw serverUnavailable()
        val builder = base.newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        builder.encodedQuery(null)
        query(builder)
        return builder.build()
    }

    private suspend inline fun <reified T> requestJson(request: Request): T {
        var status = 0
        val text = execute(client.newCall(request)) { response ->
            val body = readBoundedText(response)
            if (!response.isSuccessful) throw errorFrom(response.code, body)
            status = response.code
            body
        }
        return try {
            json.decodeFromString<T>(text)
        } catch (invalid: SerializationException) {
            throw invalidResponse(status, invalid)
        } catch (invalid: IllegalArgumentException) {
            // kotlinx.serialization 对「类型不符」也可能抛 IAE（比如把字符串当数字）。
            throw invalidResponse(status, invalid)
        }
    }

    /** `POST` + JSON 请求体 → 反序列化响应；写路径共用这一条，超时/错误映射与 GET 一致。 */
    private suspend inline fun <reified T> postJson(url: HttpUrl, payload: kotlinx.serialization.json.JsonObject): T =
        requestJson(
            requestBuilder(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )

    /**
     * GET 一个「对象数组」端点：服务端可能回 `{ "<key>": [...] }`，也可能直接回裸数组。
     *
     * 为什么不反序列化成强类型：人格/预设/记忆的服务端字段会随版本增加（`extraFields`
     * 就是为这个存在的），bootstrap 需要把**原始对象**交给映射层逐字段挑，不能先丢掉没建模的键。
     */
    private suspend fun requestObjectList(url: HttpUrl, key: String): List<JsonObject> {
        var status = 0
        val text = execute(client.newCall(requestBuilder(url).get().build())) { response ->
            val body = readBoundedText(response)
            if (!response.isSuccessful) throw errorFrom(response.code, body)
            status = response.code
            body
        }
        val element = try {
            json.parseToJsonElement(text)
        } catch (invalid: SerializationException) {
            throw invalidResponse(status, invalid)
        }
        val array: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> element[key] as? JsonArray ?: JsonArray(emptyList())
            else -> throw invalidResponse(status, IllegalArgumentException("expected a JSON array"))
        }
        val items = ArrayList<JsonObject>(array.size)
        array.forEach { item ->
            val obj = item as? JsonObject
            if (obj == null) {
                throw invalidResponse(status, IllegalArgumentException("expected JSON objects"))
            }
            items += obj
        }
        return items
    }

    /** 写路径返回的是动态对象（人格/记忆/预设的字段会随版本增加）：保留 [JsonObject] 原样。 */
    private suspend fun postJsonObject(url: HttpUrl, payload: JsonObject): JsonObject =
        postJson(url, payload)

    /** `DELETE`：成功只关心状态码，响应体只用于错误映射。 */
    private suspend fun delete(url: HttpUrl) {
        val request = requestBuilder(url).delete().build()
        execute(client.newCall(request)) { response ->
            val body = readBoundedText(response)
            if (!response.isSuccessful) throw errorFrom(response.code, body)
        }
    }

    private fun invalidResponse(status: Int, cause: Exception): ServerApiException =
        ServerApiException(
            "server returned a response that does not match the contract",
            code = "invalid_response",
            status = status,
            cause = cause
        )

    /**
     * 把一次 OkHttp 调用桥接成 suspend：回调线程上解析，协程取消时立刻 `call.cancel()`。
     *
     * 为什么不用 `withContext(Dispatchers.IO) { call.execute() }`：那样取消协程不会打断
     * 已经在阻塞读的线程（SSE 读超时又设为 0），只能等下一次读返回才发现「已经没人要了」。
     * 用 `invokeOnCancellation` + `cancel()` 才能保证「取消 ⇒ 关连接」。
     */
    private suspend fun <T> execute(call: Call, parse: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                // 取消必然要关连接：SSE 的读阻塞只有 cancel 能打断。
                runCatching { call.cancel() }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, failure: IOException) {
                    continuation.resumeFailureSafely(mapFailure(failure))
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        // 协程已经取消：不解析，直接关掉响应体（= 关连接）。
                        runCatching { response.close() }
                        return
                    }
                    val result = try {
                        response.use(parse)
                    } catch (failure: Exception) {
                        continuation.resumeFailureSafely(failure)
                        return
                    }
                    continuation.resumeSafely(result)
                }
            })
        }

    /** 读响应体，超过 [MAX_RESPONSE_BYTES] 直接按失败处理（连接随后被 `response.use` 关掉）。 */
    private fun readBoundedText(
        response: Response,
        maxBytes: Long = MAX_RESPONSE_BYTES
    ): String {
        val body = response.body ?: return ""
        val declared = body.contentLength()
        if (declared > maxBytes) {
            throw ServerApiException(
                "server response exceeds the $maxBytes byte limit",
                code = "response_too_large",
                status = response.code
            )
        }
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0L
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw ServerApiException(
                        "server response exceeds the $maxBytes byte limit",
                        code = "response_too_large",
                        status = response.code
                    )
                }
                output.write(chunk, 0, read)
            }
        }
        return output.toString("UTF-8")
    }

    /**
     * 非 2xx → 统一错误体 `{"error":{"message":"…","code":"…"}}`。
     *
     * 服务端文案原样带上（P1b 的 UI 由 `ui/` 决定要不要显示、怎么本地化），
     * 解析不出来时退化成 `http_<status>`，不把整份响应体塞进异常（可能有几 MB）。
     */
    private fun errorFrom(status: Int, text: String): ServerApiException {
        val error = runCatching {
            json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
        }.getOrNull()
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull
        return ServerApiException(
            message ?: "server returned HTTP $status",
            code = code ?: "http_$status",
            status = status
        )
    }

    private fun mapFailure(failure: IOException): ServerApiException = when (failure) {
        is ServerApiException -> failure
        is SocketTimeoutException -> ServerApiException(
            "server request timed out",
            code = "timeout",
            cause = failure
        )
        else -> ServerApiException(
            failure.message ?: "network error",
            code = "io_error",
            cause = failure
        )
    }

    /**
     * 安全 resume：取消与 OkHttp 回调可能同时发生（用户退出聊天页 vs 服务端刚发完 `done`），
     * 对已取消的 continuation 直接 `resume` 会抛 `IllegalStateException`——那是在 OkHttp 的
     * 回调线程上，没人 catch，会直接崩进程。这里把「已经没人要这个结果」当成正常情况丢掉。
     */
    private fun <T> CancellableContinuation<T>.resumeSafely(value: T) {
        if (!isActive) return
        try {
            resumeWith(Result.success(value))
        } catch (alreadyResumed: IllegalStateException) {
            // 结果没人要了：取消路径已经处理过，忽略。
        }
    }

    private fun <T> CancellableContinuation<T>.resumeFailureSafely(failure: Throwable) {
        if (!isActive) return
        try {
            resumeWith(Result.failure(failure))
        } catch (alreadyResumed: IllegalStateException) {
            // 同上：取消与回调竞争。
        }
    }

    private fun serverUnavailable(): ServerApiException =
        ServerApiException(
            "server transport is not usable: ${config.rejection(debuggable)}",
            code = "server_unavailable"
        )

    companion object {

        /** 契约 §3.4：服务端长轮询上限 25 秒（客户端也按这个截断）。 */
        const val MAX_EVENT_WAIT_SECONDS = 25L

        /** 连接超时：局域网/本机通常 < 50ms，10s 已经是「服务没起」的量级。 */
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L

        /** 普通请求的读超时。 */
        const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000L

        /** 非流式响应体上限：2 MiB。 */
        const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024

        /** `POST /v1/sessions/{id}/messages/import` 单批上限（服务端契约）。 */
        const val MAX_IMPORT_MESSAGES = 200

        /** `GET /v1/personas/{id}/memories` 的单页上限（服务端 `MAX_MEMORY_LIMIT`）。 */
        const val MEMORY_PAGE_LIMIT = 500

        /** 备份导出专用上限：64 MiB（聊天越多备份越大，普通 JSON 仍按 2 MiB）。 */
        const val MAX_BACKUP_RESPONSE_BYTES = 64L * 1024 * 1024

        /** 服务端可能新增字段：忽略未知键 + 宽松解析（数字字符串也能收）。 */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 服务端根地址：去掉尾部 `/v1`，保留反向代理前缀（`https://host/mf`）。
         *
         * 为什么允许前缀：`agentd/README.md` 建议对外用反向代理 + TLS，反代很可能挂在子路径上；
         * 客户端不应该因为「用户填了 `/v1` 结尾」就拼出 `/v1/v1/state`。
         */
        private fun rootOf(base: HttpUrl): HttpUrl {
            val segments = base.pathSegments.filter { it.isNotEmpty() }
            val kept = if (segments.isNotEmpty() && segments.last() == "v1") segments.dropLast(1) else segments
            val builder = base.newBuilder().encodedPath("/")
            kept.forEach { builder.addPathSegment(it) }
            return builder.encodedQuery(null).fragment(null).build()
        }

        /** 构造 [ServerApi] 的地址检查（不建连接）：方便 DI 在装配前先判一次。 */
        fun isBaseUrlUsable(baseUrl: String, debuggable: Boolean = BuildConfig.DEBUG): Boolean =
            ServerConfig(baseUrl = baseUrl, enabled = baseUrl.isNotBlank()).isUsable(debuggable)

        /** 只做语法归一化，供测试检查「填的地址会不会拼成预期路径」。 */
        internal fun normalizedBaseUrl(baseUrl: String): String? =
            baseUrl.trim().toHttpUrlOrNull()?.let { rootOf(it).toString().trimEnd('/') }
    }
}
