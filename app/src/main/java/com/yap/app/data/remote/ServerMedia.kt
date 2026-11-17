package com.yap.app.data.remote

import com.yap.app.domain.AudioVideoKind
import com.yap.app.domain.AudioVideoPolicy
import com.yap.app.domain.MediaRejection
import com.yap.app.domain.MessageAttachment
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `POST /v1/media` 返回的一条媒体（服务端 `MediaItem.toJson` 的同构 DTO）。
 *
 * 字段全部有默认值：服务端以后新增字段、或某个版本少给一个 `sha256`，都不该让「图已经传上去了」
 * 变成一次解析失败。客户端真正依赖的只有 [id]（拼 attachments）与 [url]（拼下载地址）。
 */
@Serializable
data class ServerMedia(
    val id: String = "",
    /** 服务端规范形态：`/v1/media/<id>`；缺省时客户端用 [id] 自己拼。 */
    val url: String = "",
    val kind: String = "",
    val personaId: String? = null,
    val mime: String = "",
    val bytes: Long = 0,
    val sha256: String = "",
    val createdAt: Long = 0,
    /** 同内容已存在时服务端会复用旧条目；缺省按「新建」处理。 */
    val created: Boolean = true
)

/**
 * 客户端可以上传的媒体用途（与 `agentd` 的 `MediaKind` 逐字一致）。
 *
 * 服务端会校验这三个值；写成枚举/常量而不是随手字符串，是为了上传参数只有一处真源。
 */
object ServerMediaKinds {
    const val CHAT_IMAGE = "CHAT_IMAGE"
    const val PERSONA_AVATAR = "PERSONA_AVATAR"
    const val USER_AVATAR = "USER_AVATAR"

    /** 0.13：历史媒体迁移要带上表情/语音/视频（服务端白名单逐字一致）。 */
    const val STICKER = "STICKER"
    const val AUDIO = "AUDIO"
    const val VIDEO = "VIDEO"
}

/** 上传/下载的字节硬上限（契约：图片单张 2 MiB；音频 8 MiB、视频 64 MiB）。 */
object ServerMediaLimits {
    const val MAX_UPLOAD_BYTES: Int = 2 * 1024 * 1024

    /** 0.13：语音/视频放宽（与 `agentd` 的 `MediaRoutes.maxBytes` 同一套值）。 */
    const val MAX_AUDIO_BYTES: Int = 8 * 1024 * 1024
    const val MAX_VIDEO_BYTES: Int = 64 * 1024 * 1024

    /** 按用途取上传上限：图片/头像/表情 2 MiB，音频 8 MiB，视频 64 MiB。 */
    fun maxUploadBytes(kind: String): Int = when (kind) {
        ServerMediaKinds.AUDIO -> MAX_AUDIO_BYTES
        ServerMediaKinds.VIDEO -> MAX_VIDEO_BYTES
        else -> MAX_UPLOAD_BYTES
    }

    /** 下载暂按图片上限挡：全量下载只搬 `/v1/media/<id>` 引用，不取音频/视频字节。 */
    const val MAX_DOWNLOAD_BYTES: Int = 2 * 1024 * 1024
}

/** 传输层最小面：编排层依赖接口，纯 JVM 单测可以注入假实现（断言顺序/字节）。 */
interface ServerMediaApi {
    suspend fun uploadMedia(
        bytes: ByteArray,
        mime: String,
        kind: String,
        personaId: String? = null
    ): ServerMedia
}

/** 一张压缩后的聊天图（或头像）：字节 + 重编码后的 mime。 */
data class CompressedImage(val bytes: ByteArray, val mime: String) {
    // ByteArray 的 data class equals 是引用比较；测试里按内容断言更直观。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CompressedImage) return false
        return bytes.contentEquals(other.bytes) && mime == other.mime
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mime.hashCode()
}

/** 压缩器接口：生产实现走 `MediaStorage.compressChatImage`（Android Bitmap），单测注入假压缩器。 */
fun interface ChatImageCompressor {
    fun compress(bytes: ByteArray, mime: String): CompressedImage?
}

/**
 * 带图消息的「先压缩、再上传」编排。
 *
 * 顺序是契约的一部分（也是单测钉住的重点）：
 * 1. 读附件字节（只读 App 私有目录里的文件）；
 * 2. 压缩（长边 ≤1600、JPEG、≤800 KB）；
 * 3. **再检查一次**上传上限（压缩器是外部实现，不能只信它）；
 * 4. `POST /v1/media`，把返回的 `/v1/media/<id>` 放进消息 `attachments`。
 *
 * 任何一步失败都整体失败、**不返回半截附件列表**：一条消息里三张图只传上去两张，
 * 用户看到的是「少了一张」而没有任何解释。失败原因用 [ServerApiException] 的结构化 code。
 */
class ServerChatMediaUploader(
    private val api: ServerMediaApi,
    private val compressor: ChatImageCompressor,
    private val readBytes: suspend (String) -> ByteArray?,
    private val maxUploadBytes: Int = ServerMediaLimits.MAX_UPLOAD_BYTES
) {

    suspend fun uploadImages(
        attachments: List<MessageAttachment>,
        personaId: String?
    ): Result<List<String>> = runCatching {
        attachments.map { attachment ->
            val raw = readBytes(attachment.uri) ?: throw ServerApiException(
                "chat attachment is not readable",
                code = "media_read_failed"
            )
            val compressed = compressor.compress(raw, attachment.mime) ?: throw ServerApiException(
                "chat attachment could not be compressed",
                code = "media_compress_failed"
            )
            if (compressed.bytes.isEmpty()) {
                throw ServerApiException("compressed image is empty", code = "media_compress_failed")
            }
            if (compressed.bytes.size > maxUploadBytes) {
                throw ServerApiException(
                    "compressed image exceeds the $maxUploadBytes byte upload limit",
                    code = "media_too_large"
                )
            }
            val media = api.uploadMedia(
                bytes = compressed.bytes,
                mime = compressed.mime,
                kind = ServerMediaKinds.CHAT_IMAGE,
                personaId = personaId
            )
            // 服务端可能只回 id、也可能回 `/v1/media/<id>` 或绝对 URL；统一落成规范形态。
            ServerMediaRef.canonical(media.url)
                ?: ServerMediaRef.canonical(media.id)
                ?: throw ServerApiException(
                    "server returned a media entry without a usable id",
                    code = "invalid_response"
                )
        }
    }
}

/**
 * 语音 / 视频的上传编排（0.13，纯 JVM：API 与读文件都注入）。
 *
 * 与图片不同，音视频**不压缩、不转码**：`spec/audio-video/README.md` 明确「不做转码」。
 * 所以这里的顺序是「读字节 → 按 8 / 64 MiB 与时长上限再挡一次 → `POST /v1/media?kind=AUDIO|VIDEO`」。
 * 上传前再挡一次是必须的：读文件与压缩器都是外部实现，不能只信调用方。
 *
 * 任何一项失败整条消息失败（不返回半截附件）：一条「语音 + 视频」的消息只传上去一半，
 * 用户看到的是「少了一段」而没有任何解释。
 */
class ServerAudioVideoUploader(
    private val api: ServerMediaApi,
    private val readBytes: suspend (MessageAttachment) -> ByteArray?
) {

    /** 逐项上传（保持原顺序），返回服务端引用 `/v1/media/<id>`。 */
    suspend fun upload(
        attachments: List<MessageAttachment>,
        personaId: String?
    ): Result<List<String>> = runCatching {
        attachments.filter { it.isAudioOrVideo }.map { attachment ->
            val kind = if (attachment.isAudio) AudioVideoKind.AUDIO else AudioVideoKind.VIDEO
            val bytes = readBytes(attachment) ?: throw ServerApiException(
                "audio/video attachment is not readable",
                code = "media_read_failed"
            )
            val rejection = when (kind) {
                AudioVideoKind.AUDIO ->
                    AudioVideoPolicy.checkAudio(bytes.size.toLong(), attachment.durationMs)
                AudioVideoKind.VIDEO ->
                    AudioVideoPolicy.checkVideo(bytes.size.toLong(), attachment.durationMs)
            }
            if (rejection != null) {
                throw ServerApiException(
                    when (rejection) {
                        MediaRejection.TOO_LARGE -> "media_too_large"
                        MediaRejection.TOO_LONG -> "media_too_long"
                        else -> "media_empty"
                    },
                    code = when (rejection) {
                        MediaRejection.TOO_LARGE -> "media_too_large"
                        MediaRejection.TOO_LONG -> "media_too_long"
                        else -> "media_empty"
                    }
                )
            }
            val media = api.uploadMedia(
                bytes = bytes,
                mime = attachment.mime,
                kind = if (kind == AudioVideoKind.AUDIO) ServerMediaKinds.AUDIO else ServerMediaKinds.VIDEO,
                personaId = personaId
            )
            ServerMediaRef.canonical(media.url)
                ?: ServerMediaRef.canonical(media.id)
                ?: throw ServerApiException(
                    "server returned a media entry without a usable id",
                    code = "invalid_response"
                )
        }
    }
}

/**
 * 服务端媒体引用的**唯一解析规则**（`agentd` 的 `MediaUrl` 是同一套语义）。
 *
 * 客户端会在三个地方遇到同一个引用：`attachments`（服务端消息）、人格 `avatarUrl`、
 * 预设 `avatarUri`。它们可能是裸 id、`/v1/media/<id>`、或完整 URL；渲染前都要归一成
 * 「能交给 Coil 的绝对地址」，否则同一个 id 在三个页面会出现三种加载结果。
 *
 * 安全边界：只有当目标地址与当前服务端**同源且路径在 `/v1/media/` 下**时，
 * 才允许带 `Authorization`——聊天图片可能是服务端返回的外部 URL，令牌绝不能送给别的域名。
 */
object ServerMediaRef {

    const val PREFIX = "/v1/media/"

    /** 服务端 id 的字符集（与 `MediaUrl.isValidId` 一致）。 */
    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

    fun isValidId(id: String?): Boolean = !id.isNullOrBlank() && ID_PATTERN.matches(id)

    /** 是不是一个「服务端媒体引用」：裸 id / `/v1/media/<id>` / 指向该路径的绝对 URL。 */
    fun isMediaRef(raw: String?): Boolean {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return false
        if (isValidId(value)) return true
        if (!value.contains(PREFIX)) return false
        return idOf(value) != null
    }

    /** 从任意形态里取出媒体 id；不是媒体引用返回 null。 */
    fun idOf(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (isValidId(value)) return value
        val index = value.indexOf(PREFIX)
        if (index < 0) return null
        val rest = value.substring(index + PREFIX.length)
        val id = rest.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
        return id.takeIf { isValidId(it) }
    }

    /** 规范形态：`/v1/media/<id>`；不是媒体引用返回 null。 */
    fun canonical(raw: String?): String? = idOf(raw)?.let { PREFIX + it }

    /**
     * 把引用解析成本次运行可以直接加载的绝对地址。
     *
     * @param config 当前服务端配置；不可用（关闭/地址不合法）时返回 null——界面回落到
     *        本地文件或占位图，而不是拼一个 `http:///v1/media/...`。
     */
    fun resolve(raw: String?, config: ServerConfig): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        val id = idOf(value) ?: return null
        val root = rootOf(config) ?: return null
        return root.trimEnd('/') + PREFIX + id
    }

    /**
     * 给一个图片地址挑 `Authorization` 头。
     *
     * 只有「同源服务端 + `/v1/media/` 路径」才返回 Bearer：Coil 请求里会带这个头，
     * 一旦把外部图片 URL 也当服务端媒体，令牌就跟着图片请求发出去了。
     */
    fun authorizationHeader(url: String?, config: ServerConfig): String? {
        val header = config.authorizationHeader() ?: return null
        val target = url?.trim()?.toHttpUrlOrNull() ?: return null
        val root = rootOf(config)?.toHttpUrlOrNull() ?: return null
        if (target.scheme != root.scheme) return null
        if (!target.host.equals(root.host, ignoreCase = true)) return null
        if (target.port != root.port) return null
        val rootPath = root.encodedPath.trimEnd('/')
        val targetPath = target.encodedPath
        if (rootPath.isNotEmpty() && !targetPath.startsWith("$rootPath$PREFIX")) return null
        if (rootPath.isEmpty() && !targetPath.startsWith(PREFIX)) return null
        return header
    }

    /**
     * 服务端根地址（保留反代前缀、去掉尾部 `/v1`）。
     *
     * 与 [ServerApi.normalizedBaseUrl] 共用语义；这里返回 null 表示配置当前不可用。
     */
    fun rootOf(config: ServerConfig): String? {
        if (!config.isUsable()) return null
        return ServerApi.normalizedBaseUrl(config.baseUrl)
    }
}

/**
 * 渲染层用的「媒体引用 → 可加载模型」解析器。
 *
 * - `file://` → [File]（本地模式与「刚发出去还没同步回来」的图片）；
 * - `content://` → 原样交给 Coil（相册 URI 仍由系统解析）；
 * - 服务端引用 → 绝对 URL（鉴权头由 [request] 单独加）；
 * - 其它字符串原样返回（兼容旧数据）。
 *
 * 服务端配置变化时 [state] 会通知 Compose 重组；不要在别处缓存 `mode.currentConfig()`。
 */
@Singleton
class ServerMediaResolver @Inject constructor(
    private val mode: ServerModeRepository
) {
    val state = mode.state

    /** 当前能不能把服务端引用渲染出来（服务端模式关闭时回落本地/占位）。 */
    fun isServerMode(): Boolean = mode.isEnabled()

    fun model(raw: String?): Any? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when {
            value.startsWith("file://") -> runCatching { File(URI(value)) }.getOrNull() ?: value
            value.startsWith("content://") -> value
            value.startsWith("http://") || value.startsWith("https://") -> value
            // 服务端引用在服务端模式关闭/地址不合法时解析不出来：返回 null 让界面回落占位，
            // 而不是把 `/v1/media/id` 当成本地路径交给 Coil。
            ServerMediaRef.isMediaRef(value) -> ServerMediaRef.resolve(value, mode.currentConfig())
            else -> value
        }
    }

    /** 这个模型加载时是否需要携带服务端鉴权头。 */
    fun authorizationHeaderFor(model: Any?): String? {
        val url = when (model) {
            is String -> model.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            else -> null
        } ?: return null
        return ServerMediaRef.authorizationHeader(url, mode.currentConfig())
    }
}
