package com.yap.agentd.store

import com.yap.app.core.json.JsonValue
import com.yap.agentd.store.StoreJson.optLong
import com.yap.agentd.store.StoreJson.optString
import com.yap.agentd.store.StoreJson.reqLong
import com.yap.agentd.store.StoreJson.reqString

/**
 * 服务端媒体（图片/头像）的模型与路径规则。
 *
 * 为什么单独一层：
 * - **id 会拼进文件路径**（`data/media/<id>.<ext>`），所以校验必须和存储层共用同一份正则，
 *   不能一边在路由里放 `/`、另一边在 `FileStore` 里拒绝；
 * - **对外的引用形态只有一种**：`/v1/media/<id>`。消息 `attachments`、人格 `avatarUrl`、
 *   预设 `avatarUri` 里允许出现裸 id 或完整 URL，但归一化/引用统计都走这里的
 *   [MediaUrl.idOf] / [MediaUrl.canonical]，避免三处各写一套字符串匹配；
 * - **扩展名只由 mime 决定**，不接受客户端传文件名：`id` 是服务端生成的 UUID，客户端没有
 *   机会用 `../` 或双扩展名做路径穿越/内容嗅探。
 *
 * 媒体和消息/人格/预设**没有外键**：引用是字符串里的约定。删除接口因此要在写锁内扫一遍
 * 引用（[FileStore.deleteMedia]），而不是靠数据库的级联。
 */

/** 上传时允许的用途。与客户端 `AttachmentKind` / 头像上传入口一一对应。 */
object MediaKind {
    const val CHAT_IMAGE = "CHAT_IMAGE"
    const val PERSONA_AVATAR = "PERSONA_AVATAR"
    const val USER_AVATAR = "USER_AVATAR"
    const val STICKER = "STICKER"
    const val AUDIO = "AUDIO"
    const val VIDEO = "VIDEO"

    val ALL: List<String> = listOf(CHAT_IMAGE, PERSONA_AVATAR, USER_AVATAR, STICKER, AUDIO, VIDEO)

    fun isValid(value: String): Boolean = value in ALL
}

/**
 * 允许的媒体类型。
 *
 * 客户端在上传前已经把图片压成 JPEG（头像 ≤400 KB、聊天图 ≤800 KB），服务端仍接受 PNG/WebP：
 * 老客户端/第三方脚本可能直接传原图。**只按 Content-Type 判定**，不嗅探文件头（见 README 风险）。
 */
object MediaMime {
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val WEBP = "image/webp"
    const val GIF = "image/gif"
    const val M4A = "audio/m4a"
    const val AUDIO_MP4 = "audio/mp4"
    const val MPEG = "audio/mpeg"
    const val VIDEO_MP4 = "video/mp4"
    const val QUICKTIME = "video/quicktime"

    val ALL: List<String> = listOf(JPEG, PNG, WEBP, GIF, M4A, AUDIO_MP4, MPEG, VIDEO_MP4, QUICKTIME)

    fun isValid(value: String): Boolean = value in ALL

    /** `image/jpeg; charset=binary` → `image/jpeg`；不认识的类型返回原样的 lower-case 前缀。 */
    fun normalize(contentType: String?): String? =
        contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** 落盘扩展名。未知类型返回 null（调用方应回 415，而不是写一个没有扩展名的文件）。 */
    fun extension(mime: String): String? = when (mime) {
        JPEG -> "jpg"
        PNG -> "png"
        WEBP -> "webp"
        GIF -> "gif"
        M4A -> "m4a"
        AUDIO_MP4 -> "m4a"
        MPEG -> "mp3"
        VIDEO_MP4 -> "mp4"
        QUICKTIME -> "mov"
        else -> null
    }
}

/** `/v1/media/{id}` 的 id 规则与引用归一化。 */
object MediaUrl {
    const val PREFIX = "/v1/media/"

    /** 媒体 id 长度上限；服务端生成的是 36 字符 UUID，留一点余量给测试/未来格式。 */
    const val MAX_ID_CHARS = 64

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,$MAX_ID_CHARS}")

    fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

    fun url(id: String): String = PREFIX + id

    /**
     * 从「裸 id / `/v1/media/<id>` / 绝对 URL 里的 `/v1/media/<id>`」里取出 id。
     *
     * 不合法/不是媒体引用 → null。取路径段时按「id 字符集」截断，所以
     * `/v1/media/abc?x=1`、`https://host/v1/media/abc` 都能解析；同时
     * `/v1/media/abc-def` 不会被错误地当成 `abc`（不会出现前缀误匹配）。
     */
    fun idOf(reference: String?): String? {
        val text = reference?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (isValidId(text)) return text
        val marker = PREFIX
        val index = text.indexOf(marker)
        if (index < 0) return null
        val rest = text.substring(index + marker.length)
        val id = rest.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
        return id.takeIf { isValidId(it) }
    }

    /** 规范化成 `/v1/media/<id>`；不是媒体引用时返回 null。 */
    fun canonical(reference: String?): String? = idOf(reference)?.let { url(it) }
}

/**
 * 一条媒体元数据（`data/media.json` 里的 `media[]` 项 + 内存索引）。
 *
 * [bytes] 是原始字节数（不是 base64 长度），[sha256] 是原始字节的十六进制摘要，
 * 用来做「同内容只落一份」和下载的 `ETag`（`"<sha256>"`，内容是 immutable 的）。
 */
data class MediaItem(
    val id: String,
    val kind: String,
    val personaId: String?,
    val mime: String,
    val bytes: Long,
    val sha256: String,
    val createdAt: Long,
    /**
     * 客户端备份里的**裸文件名**（v6 的 `media[].fileName`，也就是 `personas.avatarUri` /
     * `messages.attachmentsJson[].uri` / `stickers[].fileName` 的关联键）。
     *
     * 为什么服务端要存它：备份是「按文件名关联」的，服务端上传时不知道原名，
     * 但**导入客户端备份时知道**。存下来之后，「导入 → 落库 → 再导出」才能保留原名，
     * 客户端换回来时那些 `uri` 仍然对得上。`null` = 服务端自己收的媒体，导出时
     * 用 `<id>.<ext>` 现造一个（见 [exportFileName]）。
     */
    val fileName: String? = null
) {
    val url: String get() = MediaUrl.url(id)

    /** 导出/关联用的裸文件名；只允许「字母数字._-」，否则退回 `<id>.<ext>`（防路径注入）。 */
    val exportFileName: String
        get() {
            val stored = fileName?.trim().orEmpty()
            if (stored.isNotEmpty() && FILE_NAME_PATTERN.matches(stored)) return stored
            val extension = MediaMime.extension(mime) ?: "bin"
            return "$id.$extension"
        }

    fun toJson(): JsonValue.Obj = StoreJson.obj(
        "id" to StoreJson.str(id),
        "kind" to StoreJson.str(kind),
        "personaId" to StoreJson.nullableStr(personaId),
        "mime" to StoreJson.str(mime),
        "bytes" to StoreJson.num(bytes),
        "sha256" to StoreJson.str(sha256),
        "createdAt" to StoreJson.num(createdAt),
        "fileName" to StoreJson.nullableStr(fileName)
    )

    companion object {
        /** 裸文件名的安全规则（不含目录分隔符、不以点开头）。 */
        val FILE_NAME_PATTERN = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,120}")

        fun fromJson(value: JsonValue.Obj, where: String): MediaItem {
            val id = value.reqString("id", where)
            if (!MediaUrl.isValidId(id)) throw StoreCorruptionException("$where 的 id 不合法")
            val kind = value.reqString("kind", where)
            if (!MediaKind.isValid(kind)) throw StoreCorruptionException("$where 的 kind 不合法：$kind")
            val mime = value.reqString("mime", where)
            if (!MediaMime.isValid(mime)) throw StoreCorruptionException("$where 的 mime 不合法：$mime")
            val personaId = value.optString("personaId", where)?.takeIf { it.isNotBlank() }
            if (personaId != null && !FileStore.isValidId(personaId)) {
                throw StoreCorruptionException("$where 的 personaId 不合法")
            }
            val bytes = value.reqLong("bytes", where)
            if (bytes < 0) throw StoreCorruptionException("$where 的 bytes 不能是负数")
            val sha256 = value.reqString("sha256", where)
            if (sha256.length != 64) throw StoreCorruptionException("$where 的 sha256 不是 SHA-256 十六进制")
            return MediaItem(
                id = id,
                kind = kind,
                personaId = personaId,
                mime = mime,
                bytes = bytes,
                sha256 = sha256,
                createdAt = value.reqLong("createdAt", where),
                fileName = value.optString("fileName", where)?.trim()?.takeIf { it.isNotEmpty() }
            )
        }
    }
}

/** [FileStore.saveMedia] 的结果：[created] = false 表示同 sha256 已存在、复用了旧文件。 */
data class MediaSaveResult(val item: MediaItem, val created: Boolean)

/**
 * [FileStore.deleteMedia] 的结果。
 *
 * [found] 与 [deleted] 分开是因为路由要区分三种情况：404（`found=false`）、
 * 409 被引用（`found=true, deleted=false`）、200 真删了。
 */
data class MediaDeleteResult(val found: Boolean, val deleted: Boolean, val references: Int)
