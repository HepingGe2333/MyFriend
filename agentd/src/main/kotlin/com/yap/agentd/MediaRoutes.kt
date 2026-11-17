package com.yap.agentd

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaKind
import com.yap.agentd.store.MediaMime
import com.yap.agentd.store.MediaUrl
import com.yap.agentd.store.StoreJson
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 服务端媒体路由（`docs/design/server-authoritative.md` §3.6 / §7 的 M5 剩余项）。
 *
 * | 方法 | 路径 | 说明 |
 * | --- | --- | --- |
 * | `POST` | `/v1/media?kind=&mime=&personaId=` | **原始字节** body；201 新建 / 200 同 sha256 复用 |
 * | `GET` | `/v1/media/{id}` | 原始字节 + Content-Type + 强缓存 + ETag（If-None-Match → 304） |
 * | `DELETE` | `/v1/media/{id}?force=1` | 被引用时 409；`force` 强制删文件与元数据 |
 *
 * 为什么和 [AuthorityRoutes] 分开：那套路由的输入输出都是 **String**（JSON），而媒体上传要读
 * 原始字节、下载要写原始字节。`AgentdServer` 在解码成 UTF-8 字符串**之前**把媒体路径分流到这里，
 * 所以二进制不会被 `String(bytes, UTF_8)` 破坏。媒体路由同样在 `AgentdServer` 的 token/限流之后，
 * 只有请求体上限不同（媒体 4 MiB，其余 1 MiB）。
 *
 * 校验顺序刻意是「方法 → 类型 → 用途 → persona → 大小 → 落盘」：任何一步失败都不写文件；
 * 唯一的写操作在 [FileStore.saveMedia] 里，原子写 + 同锁去重。
 */
object MediaRoutes {

    const val PATH = "/v1/media"

    /** 单文件上限（原始字节）：图片/表情 2 MiB、音频 8 MiB、视频 64 MiB（按 kind 区分）。 */
    const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024
    const val MAX_AUDIO_BYTES = 8 * 1024 * 1024
    const val MAX_VIDEO_BYTES = 64 * 1024 * 1024

    fun maxBytes(kind: String): Int = when (kind) {
        com.yap.agentd.store.MediaKind.AUDIO -> MAX_AUDIO_BYTES
        com.yap.agentd.store.MediaKind.VIDEO -> MAX_VIDEO_BYTES
        else -> MAX_UPLOAD_BYTES
    }

    /**
     * 本路由的请求体上限：文件上限 2 MiB 的两倍，给「HTTP 层先读进内存、handler 再按文件大小拒绝」
     * 留出判定空间；其它路由保持 [AgentdApi.MAX_BODY_BYTES]。
     */
    const val MAX_REQUEST_BODY_BYTES = 66 * 1024 * 1024

    /** 内容不会变（id 由内容 sha256 去重），可以放心长缓存。 */
    const val CACHE_CONTROL = "private, max-age=31536000, immutable"

    /** `AgentdServer` 的分流结果：JSON 错误/元数据，或原始字节 + 响应头。 */
    sealed class MediaResult {
        data class Json(val response: AgentdApi.Response) : MediaResult()

        /** [contentType] = null 只用于 304（没有响应体，也就不该带 Content-Type）。 */
        data class Binary(
            val status: Int,
            val bytes: ByteArray,
            val contentType: String?,
            val headers: Map<String, String>
        ) : MediaResult()
    }

    /** 这条路径是否归媒体路由处理（未知子路径也归，好回统一 404）。 */
    fun matches(rawPath: String): Boolean {
        val path = rawPath.substringBefore('?')
        return path == PATH || path.startsWith("$PATH/")
    }

    /**
     * 该路径的请求体上限。
     *
     * 只有上传路径（`path == PATH`）放宽到 4 MiB；`GET`/`DELETE /v1/media/{id}` 仍按 1 MiB 读头，
     * 它们本来就没有 body。放宽本身不是漏洞：路由层仍按 2 MiB 拒绝，4 MiB 只是让「超限」在
     * handler 里得到更准确的错误文案。
     */
    fun bodyLimit(rawPath: String): Int {
        val path = rawPath.substringBefore('?')
        return if (path == PATH) MAX_REQUEST_BODY_BYTES else AgentdApi.MAX_BODY_BYTES
    }

    /**
     * 处理一条媒体请求。
     *
     * @param headers 请求头（键已 lower-case，和 `AgentdServer` 的解析结果一致）
     * @param body 原始字节（**不是** UTF-8 解码后的字符串）
     */
    fun handle(
        services: AgentdServices,
        method: String,
        rawPath: String,
        headers: Map<String, String>,
        body: ByteArray
    ): MediaResult {
        return try {
            val store = services.store ?: throw AgentdApi.ApiFailure(
                503,
                "state_unavailable",
                "服务端权威模式未启用：启动时没有配置 --data（数据目录）"
            )
            val verb = method.uppercase()
            val path = rawPath.substringBefore('?')
            if (path == PATH) {
                if (verb != "POST") {
                    throw AgentdApi.ApiFailure(405, "method_not_allowed", "$PATH 只支持 POST")
                }
                upload(store, rawPath, headers, body)
            } else {
                val id = path.removePrefix("$PATH/")
                // 形状不对（多级路径、`..`、空 id）一律 404：不给探测文件系统的机会。
                if (!MediaUrl.isValidId(id)) throw notFound()
                when (verb) {
                    "GET" -> download(store, id, headers)
                    "DELETE" -> remove(store, id, rawPath)
                    else -> throw AgentdApi.ApiFailure(
                        405,
                        "method_not_allowed",
                        "$PATH/{id} 只支持 GET/DELETE"
                    )
                }
            }
        } catch (failure: AgentdApi.ApiFailure) {
            MediaResult.Json(
                AgentdApi.errorResponse(failure.status, failure.code, failure.message ?: "请求不合法")
            )
        } catch (invalid: IllegalArgumentException) {
            MediaResult.Json(AgentdApi.errorResponse(400, "invalid_argument", invalid.message ?: "参数不合法"))
        } catch (unexpected: Exception) {
            MediaResult.Json(AgentdApi.unexpectedFailureResponse(unexpected))
        }
    }

    // ------------------------------------------------------------------ 上传

    private fun upload(
        store: FileStore,
        target: String,
        headers: Map<String, String>,
        body: ByteArray
    ): MediaResult {
        val contentType = MediaMime.normalize(headers["content-type"])
        if (contentType == null || !MediaMime.isValid(contentType)) {
            throw AgentdApi.ApiFailure(
                415,
                "unsupported_media_type",
                "Content-Type 必须是 image/jpeg、image/png 或 image/webp"
            )
        }
        val query = MediaQuery.parse(target)
        val mimeParam = query.string("mime")?.let { MediaMime.normalize(it) }
        if (mimeParam != null) {
            if (!MediaMime.isValid(mimeParam)) {
                throw AgentdApi.ApiFailure(
                    415,
                    "unsupported_media_type",
                    "mime 参数必须是 image/jpeg、image/png 或 image/webp"
                )
            }
            if (mimeParam != contentType) {
                throw AgentdApi.ApiFailure(
                    400,
                    "mime_mismatch",
                    "mime 参数与 Content-Type 不一致"
                )
            }
        }
        val kind = query.string("kind")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw AgentdApi.ApiFailure(
                400,
                "missing_field",
                "缺少查询参数 kind（CHAT_IMAGE / PERSONA_AVATAR / USER_AVATAR）"
            )
        if (!MediaKind.isValid(kind)) {
            throw AgentdApi.ApiFailure(
                400,
                "invalid_argument",
                "kind 只能是 CHAT_IMAGE / PERSONA_AVATAR / USER_AVATAR"
            )
        }
        val personaId = query.string("personaId")?.trim()?.takeIf { it.isNotEmpty() }
        if (personaId != null) {
            if (!FileStore.isValidId(personaId)) {
                throw AgentdApi.ApiFailure(400, "invalid_argument", "personaId 格式不对")
            }
            if (store.persona(personaId) == null) {
                throw AgentdApi.ApiFailure(404, "not_found", "没有这个人格")
            }
        }
        if (body.isEmpty()) throw AgentdApi.ApiFailure(400, "empty_body", "请求体不能为空（要发原始图片字节）")
        if (body.size > maxBytes(kind)) {
            throw AgentdApi.ApiFailure(
                413,
                "content_too_large",
                "单张图片超过 ${MAX_UPLOAD_BYTES / 1024} KiB 上限"
            )
        }

        val saved = store.saveMedia(kind, personaId, contentType, body)
        val status = if (saved.created) 201 else 200
        return MediaResult.Json(
            AgentdApi.Response(
                status,
                StoreJson.stringify(
                    StoreJson.obj(
                        "id" to StoreJson.str(saved.item.id),
                        "kind" to StoreJson.str(saved.item.kind),
                        "mime" to StoreJson.str(saved.item.mime),
                        "bytes" to StoreJson.num(saved.item.bytes),
                        "sha256" to StoreJson.str(saved.item.sha256),
                        "url" to StoreJson.str(saved.item.url)
                    )
                )
            )
        )
    }

    // ------------------------------------------------------------------ 下载

    private fun download(store: FileStore, id: String, headers: Map<String, String>): MediaResult {
        val item = store.media(id) ?: throw notFound()
        val bytes = store.mediaBytes(id) ?: throw notFound()
        val etag = "\"${item.sha256}\""
        if (matchesIfNoneMatch(headers["if-none-match"], item.sha256)) {
            // 304 没有 body；Cache-Control/ETag 仍要带，客户端才知道本地副本还能继续用。
            return MediaResult.Binary(
                status = 304,
                bytes = ByteArray(0),
                contentType = null,
                headers = mapOf(
                    "ETag" to etag,
                    "Cache-Control" to CACHE_CONTROL
                )
            )
        }
        return MediaResult.Binary(
            status = 200,
            bytes = bytes,
            contentType = item.mime,
            headers = mapOf(
                "ETag" to etag,
                "Cache-Control" to CACHE_CONTROL,
                // 图片是按 Content-Type 下发的，禁止浏览器再嗅探成别的类型。
                "X-Content-Type-Options" to "nosniff"
            )
        )
    }

    // ------------------------------------------------------------------ 删除

    private fun remove(store: FileStore, id: String, target: String): MediaResult {
        val force = parseForce(MediaQuery.parse(target).string("force"))
        val result = store.deleteMedia(id, force)
        if (!result.found) throw notFound()
        if (!result.deleted) {
            // 409 的引用数放在顶层（错误体之外）：客户端可以直接展示「还有 3 条消息在用」。
            val body = StoreJson.stringify(
                StoreJson.obj(
                    "id" to StoreJson.str(id),
                    "references" to StoreJson.num(result.references),
                    "error" to StoreJson.obj(
                        "message" to StoreJson.str(
                            "媒体还被 ${result.references} 处引用；加 ?force=1 强制删除"
                        ),
                        "code" to StoreJson.str("media_in_use")
                    )
                )
            )
            return MediaResult.Json(AgentdApi.Response(409, body))
        }
        return MediaResult.Json(
            AgentdApi.Response(
                200,
                StoreJson.stringify(
                    StoreJson.obj(
                        "id" to StoreJson.str(id),
                        "deleted" to StoreJson.bool(true),
                        "references" to StoreJson.num(result.references)
                    )
                )
            )
        )
    }

    // ------------------------------------------------------------------ 小工具

    private fun notFound(): AgentdApi.ApiFailure =
        AgentdApi.ApiFailure(404, "not_found", "没有这个媒体")

    /** 允许 `?force=1/true/yes`；明确给了不认识的写法 → 400（不猜用户意图）。 */
    private fun parseForce(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return when (raw.trim().lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> throw AgentdApi.ApiFailure(400, "invalid_argument", "force 只能是 1 或 0")
        }
    }

    /**
     * `If-None-Match`：支持逗号分隔多个值、弱校验前缀 `W/`、带引号/不带引号，以及 `*`。
     * ETag 用 sha256（内容寻址），所以「相同内容」等价于「同一表示」。
     */
    private fun matchesIfNoneMatch(header: String?, sha256: String): Boolean {
        if (header.isNullOrBlank()) return false
        return header.split(',').any { raw ->
            val token = raw.trim()
            if (token == "*") return@any true
            val unquoted = token.removePrefix("W/").trim().removeSurrounding("\"")
            unquoted.equals(sha256, ignoreCase = true)
        }
    }

    /** 查询串（只取第一个值；非法百分号转义 → 400）。媒体参数只有字符串，不需要数字解析。 */
    private class MediaQuery(private val values: Map<String, String>) {
        fun string(key: String): String? = values[key]

        companion object {
            fun parse(target: String): MediaQuery {
                val index = target.indexOf('?')
                if (index < 0 || index == target.length - 1) return MediaQuery(emptyMap())
                val values = LinkedHashMap<String, String>()
                for (pair in target.substring(index + 1).split('&')) {
                    if (pair.isEmpty()) continue
                    val equals = pair.indexOf('=')
                    val name = decode(if (equals < 0) pair else pair.substring(0, equals))
                    val value = if (equals < 0) "" else decode(pair.substring(equals + 1))
                    if (!values.containsKey(name)) values[name] = value
                }
                return MediaQuery(values)
            }

            private fun decode(text: String): String = try {
                URLDecoder.decode(text, StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                throw AgentdApi.ApiFailure(400, "invalid_argument", "查询串里有非法的百分号转义")
            }
        }
    }
}
