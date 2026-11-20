package com.yap.app.domain

import java.security.MessageDigest
import java.util.Locale

/**
 * 媒体类型的**唯一口径**（0.15「媒体类型/哈希校验」）。
 *
 * 项目里同时存在三套 kind 名字，历史上各写各的，到了 0.15 必须能互相对上：
 *
 * | 来源 | kind 取值 |
 * | --- | --- |
 * | 附件（`messages.attachmentsJson`） | `IMAGE` / `STICKER` / `AUDIO` / `VIDEO`（旧数据可缺省 = 图片） |
 * | 备份 `media[]` / 服务端 `POST /v1/media` | `PERSONA_AVATAR` / `USER_AVATAR` / `CHAT_IMAGE` / `STICKER` / `AUDIO` / `VIDEO` |
 * | 语义族（本文件） | `IMAGE` / `STICKER` / `AUDIO` / `VIDEO` / `AVATAR` |
 *
 * 为什么要先归一成「族」再做校验：`PERSONA_AVATAR` 与 `USER_AVATAR` 在落盘目录、上限、清理语义上
 * 完全一样，只有「属于谁」不同；把「能不能带这个 mime」按族判断，才不会出现
 * 「头像能收 PNG、用户头像只收 JPEG」这种随实现漂移的规则。
 *
 * **未知类型一律拒绝，不猜**：0.12 之前的代码在拿不准时会把字节当普通图片落盘，
 * 结果是一条语音在界面上变成一张加载不出来的图。0.15 起，DTO 里 kind/mime 不明或互不匹配时
 * 返回 `null`（或结构化拒绝原因），由调用方跳过并计数——宁可少一条，也不要静默错位。
 */
object MediaKinds {

    // ---- 附件 kind（`MessageAttachment.KIND_*` 的同源常量，避免两处各写一份） ----------
    const val IMAGE = "IMAGE"
    const val STICKER = "STICKER"
    const val AUDIO = "AUDIO"
    const val VIDEO = "VIDEO"

    // ---- 备份/服务端细分 kind（与 `BackupMediaKind`、`ServerMediaKinds` 逐字一致） -----
    const val CHAT_IMAGE = "CHAT_IMAGE"
    const val PERSONA_AVATAR = "PERSONA_AVATAR"
    const val USER_AVATAR = "USER_AVATAR"

    /** 头像语义族（备份里的两种头像 kind 都归到这里）。 */
    const val AVATAR = "AVATAR"

    /** `attachmentsJson[].kind` 允许出现的值（**不含** AVATAR：头像不是消息附件）。 */
    val attachmentKinds: Set<String> = setOf(IMAGE, STICKER, AUDIO, VIDEO)

    /** 备份 `media[].kind` 与 `POST /v1/media?kind=` 允许出现的值。 */
    val serverKinds: Set<String> = setOf(CHAT_IMAGE, PERSONA_AVATAR, USER_AVATAR, STICKER, AUDIO, VIDEO)

    /** 语义族。 */
    val families: Set<String> = setOf(IMAGE, STICKER, AUDIO, VIDEO, AVATAR)
}

/** 一次类型校验的结论；`null`（而不是抛异常）表示「这条数据不该落库」。 */
data class MediaDescriptor(
    /** 归一后的 kind（附件用族名，备份/服务端保留细分 kind）。 */
    val kind: String,
    /** 语义族：`IMAGE` / `STICKER` / `AUDIO` / `VIDEO` / `AVATAR`。 */
    val family: String,
    /** 归一后的 mime（小写、去参数、无空白）。 */
    val mime: String
)

/** 类型校验的失败原因（稳定 code，供日志/诊断对比，不参与界面文案）。 */
enum class MediaTypeRejection(val code: String) {
    /** kind 不在任何已知集合里（例如 `GIF`、`FILE`、`AVATAR` 出现在附件里）。 */
    UNKNOWN_KIND("media_unknown_kind"),

    /** 有 kind 但 mime 为空——不能靠扩展名或字节去猜。 */
    MISSING_MIME("media_missing_mime"),

    /** mime 合法，但与声明的 kind 不是同一族（例如 `kind=AUDIO` + `mime=image/jpeg`）。 */
    MIME_MISMATCH("media_mime_mismatch"),

    /** mime 本身不在白名单里（例如 `application/octet-stream`）。 */
    UNSUPPORTED_MIME("media_unsupported_mime")
}

/** 媒体类型的判定与归一（纯函数，客户端/服务端/备份三套输入共用）。 */
object MediaTypes {

    private const val GIF = "image/gif"

    /** kind → 语义族；未知 kind 返回 null（**不要**在这里退回 IMAGE）。 */
    fun familyOfKind(kind: String?): String? {
        val value = kind?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (value.isEmpty()) return null
        return when (value) {
            MediaKinds.IMAGE, MediaKinds.CHAT_IMAGE -> MediaKinds.IMAGE
            MediaKinds.STICKER -> MediaKinds.STICKER
            MediaKinds.AUDIO -> MediaKinds.AUDIO
            MediaKinds.VIDEO -> MediaKinds.VIDEO
            MediaKinds.PERSONA_AVATAR, MediaKinds.USER_AVATAR, MediaKinds.AVATAR -> MediaKinds.AVATAR
            else -> null
        }
    }

    fun isKnownKind(kind: String?): Boolean = familyOfKind(kind) != null

    /**
     * mime 归一：小写、去掉 `; charset=…` 之类的参数、去空白。
     *
     * 空串返回 null——「没有 mime」与「mime 不合法」在调用方看来是同一件事：
     * 都不能拿来判定类型，也都不该落库。
     */
    fun canonicalMime(raw: String?): String? {
        val value = raw?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return value.takeIf { it.isNotEmpty() }
    }

    /** mime → 语义族（**只能**从 mime 判，不读 kind；`image/gif` 按既有约定归 STICKER）。 */
    fun familyOfMime(mime: String?): String? {
        val value = canonicalMime(mime) ?: return null
        return when {
            value == GIF -> MediaKinds.STICKER
            value.startsWith("image/") -> MediaKinds.IMAGE
            value.startsWith("audio/") -> MediaKinds.AUDIO
            value.startsWith("video/") -> MediaKinds.VIDEO
            value == "application/mp4" -> MediaKinds.VIDEO
            else -> null
        }
    }

    /** 这个语义族收哪些 mime。 */
    fun acceptsMime(family: String, mime: String?): Boolean {
        val value = canonicalMime(mime) ?: return false
        return when (family) {
            MediaKinds.IMAGE ->
                value.startsWith("image/") && value != GIF
            MediaKinds.STICKER ->
                value == GIF || value == "image/png" || value == "image/webp" || value == "image/jpeg"
            MediaKinds.AVATAR ->
                value.startsWith("image/") && value != GIF
            MediaKinds.AUDIO -> value.startsWith("audio/")
            MediaKinds.VIDEO -> value.startsWith("video/") || value == "application/mp4"
            else -> false
        }
    }

    /** 语义族的缺省 mime（**只在 kind 已明确、mime 缺失**时用来补齐传输字段）。 */
    fun defaultMime(family: String): String = when (family) {
        MediaKinds.STICKER -> GIF
        MediaKinds.AUDIO -> "audio/m4a"
        MediaKinds.VIDEO -> "video/mp4"
        else -> "image/jpeg"
    }

    /**
     * 严格判定：kind 与 mime 都必须显式、已知、且同族。
     *
     * 用于**新写入/新响应**的 DTO（服务端 `POST /v1/media` 响应、备份 `media[]`）。
     * 任何一项不合格返回 null，调用方必须拒绝（跳过/报错），不能退回「当普通图片」。
     */
    fun descriptor(kind: String?, mime: String?): MediaDescriptor? {
        val family = familyOfKind(kind) ?: return null
        val canonical = canonicalMime(mime) ?: return null
        if (!acceptsMime(family, canonical)) return null
        return MediaDescriptor(
            kind = normalizeKind(kind, family),
            family = family,
            mime = canonical
        )
    }

    /** 严格判定 + 结构化原因（诊断/日志/测试用）。 */
    fun rejection(kind: String?, mime: String?): MediaTypeRejection? {
        val rawKind = kind?.trim().orEmpty()
        val family = familyOfKind(rawKind)
        val canonical = canonicalMime(mime)
        if (family == null) {
            return MediaTypeRejection.UNKNOWN_KIND
        }
        if (canonical == null) {
            return MediaTypeRejection.MISSING_MIME
        }
        // 用「这个族收不收这个 mime」判断，而不是拿 mime 的族去比：
        // AVATAR 收 image/png（mime 族是 IMAGE），这是合法的；只有真正不收才算拒绝。
        if (acceptsMime(family, canonical)) return null
        familyOfMime(canonical) ?: return MediaTypeRejection.UNSUPPORTED_MIME
        // `image/gif` 与 `IMAGE` 的冲突不是「同族不符」，而是「mime 说的是表情」。
        // 两种都算 MIME_MISMATCH：调用方要做的是「拒绝」，不是改判成表情。
        return MediaTypeRejection.MIME_MISMATCH
    }

    /**
     * 附件 DTO 的宽容判定（**读路径**专用）。
     *
     * - 显式 kind：必须是四个附件 kind 之一，mime 同族；mime 缺失时按 kind 补缺省值
     *   （老数据里 `kind=STICKER` 但没写 mime 是真实存在的形状）；
     * - 缺省 kind（0.12 及以前）：按 mime 判断，`image/gif` 仍按表情处理，
     *   但**未知 mime 不猜**——返回 null 让调用方丢掉这条附件。
     *
     * 与 [descriptor] 的区别只有「是否容忍缺省 kind/mime」，这是兼容旧数据的唯一入口。
     */
    fun attachmentDescriptor(kind: String?, mime: String?): MediaDescriptor? {
        val rawKind = kind?.trim()?.takeIf { it.isNotEmpty() }
        val canonical = canonicalMime(mime)
        if (rawKind == null) {
            val family = familyOfMime(canonical) ?: return null
            return MediaDescriptor(
                kind = when (family) {
                    MediaKinds.STICKER -> MediaKinds.STICKER
                    MediaKinds.AUDIO -> MediaKinds.AUDIO
                    MediaKinds.VIDEO -> MediaKinds.VIDEO
                    else -> MediaKinds.IMAGE
                },
                family = family,
                mime = canonical!!
            )
        }
        val family = familyOfKind(rawKind) ?: return null
        if (family !in MediaKinds.attachmentKinds ||
            rawKind.uppercase(Locale.ROOT) == MediaKinds.AVATAR
        ) {
            return null
        }
        var effectiveMime = canonical ?: defaultMime(family)
        if (!acceptsMime(family, effectiveMime) && effectiveMime == "image/jpeg") {
            // `MessageAttachment.mime` 的默认值就是 image/jpeg：只写了 kind、没写 mime 的旧数据
            // 会带着这个默认值。默认值不是证据（与服务端 `fromAttachment` 的规则一致），
            // 按 kind 的缺省 mime 补齐，而不是把一条语音判成坏数据丢掉。
            val fallback = defaultMime(family)
            if (acceptsMime(family, fallback)) effectiveMime = fallback
        }
        if (!acceptsMime(family, effectiveMime)) return null
        return MediaDescriptor(
            kind = normalizeAttachmentKind(rawKind, family),
            family = family,
            mime = effectiveMime
        )
    }

    /**
     * 新写入的附件（`attachmentsJson`）：**一律显式写 kind**，mime 必须有值且与 kind 同族。
     *
     * 返回 null 表示这条附件类型不合法（调用方跳过）；这是「未知类型拒绝而不是猜」的写路径落点。
     */
    fun normalizeForWrite(attachment: MessageAttachment): MessageAttachment? {
        val descriptor = if (attachment.kind == null) {
            // 老调用方只填了 mime：类型由 mime 决定（gif = 表情），但写入时补上显式 kind。
            val family = familyOfMime(attachment.mime) ?: return null
            val mime = canonicalMime(attachment.mime) ?: return null
            MediaDescriptor(
                kind = when (family) {
                    MediaKinds.STICKER -> MediaKinds.STICKER
                    MediaKinds.AUDIO -> MediaKinds.AUDIO
                    MediaKinds.VIDEO -> MediaKinds.VIDEO
                    else -> MediaKinds.IMAGE
                },
                family = family,
                mime = mime
            )
        } else {
            attachmentDescriptor(attachment.kind, attachment.mime) ?: return null
        }
        val sha256 = MediaHashes.normalize(attachment.sha256)
        return attachment.copy(
            kind = descriptor.kind,
            mime = descriptor.mime,
            sha256 = sha256
        )
    }

    private fun normalizeKind(raw: String?, family: String): String {
        val value = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
        // 备份/服务端 kind 原样保留（CHAT_IMAGE 不会被改写成 IMAGE：那是跨端契约）。
        if (value in MediaKinds.serverKinds) return value
        return when (family) {
            MediaKinds.AVATAR -> MediaKinds.AVATAR
            MediaKinds.STICKER -> MediaKinds.STICKER
            MediaKinds.AUDIO -> MediaKinds.AUDIO
            MediaKinds.VIDEO -> MediaKinds.VIDEO
            else -> MediaKinds.IMAGE
        }
    }

    private fun normalizeAttachmentKind(raw: String?, family: String): String {
        val value = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (value in MediaKinds.attachmentKinds) return value
        return when (family) {
            MediaKinds.STICKER -> MediaKinds.STICKER
            MediaKinds.AUDIO -> MediaKinds.AUDIO
            MediaKinds.VIDEO -> MediaKinds.VIDEO
            else -> MediaKinds.IMAGE
        }
    }
}

/**
 * 媒体内容的 SHA-256（0.15「哈希校验」）。
 *
 * 用途只有三个，但每一个都必须一致：
 * 1. **服务端下载校验**：`/v1/media/{id}` 的 `ETag` 就是服务端那条媒体的 sha256；
 *    下载后对**原始字节**重算，不一致说明链路/缓存坏了，删临时文件、记失败、不落库；
 * 2. **备份 `media[].sha256`**（0.15 v7 新增，服务端 `/v1/backup` 早已输出）：
 *    导入时非空就校验，不一致拒绝该条（不整份失败）；
 * 3. **本地记录**：`attachmentsJson[].sha256` 记下这条附件的内容哈希，下次同步命中本地文件时
 *    重算比对——不一致说明本地副本坏了，删掉重下（下载幂等 + 自我修复）。
 *
 * 兼容：**没有 hash 的旧数据一律视为通过**（[normalize] 返回 null 即跳过校验）。
 * 这是「旧数据无 hash 兼容」的落点，不要把它改成「缺 hash 就拒绝」。
 */
object MediaHashes {

    const val HEX_LENGTH: Int = 64

    private const val ALPHABET = "0123456789abcdef"

    /** 原始字节的 SHA-256（小写十六进制）。 */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            hex[index * 2] = ALPHABET[value ushr 4]
            hex[index * 2 + 1] = ALPHABET[value and 0x0F]
        }
        return String(hex)
    }

    /**
     * 归一一个「声明里的哈希」：去空白、去引号、小写。
     *
     * 只接受 64 位十六进制；其它值（空串、`abc`、`sha256:...`）返回 null = 没有可用的哈希，
     * 读取端按「旧数据」跳过校验，而不是拿一个坏哈希把正常媒体判失败。
     */
    fun normalize(raw: String?): String? {
        val value = raw
            ?.trim()
            // HTTP ETag 可能是 `W/"<hex>"`（弱校验）或带引号；两种都剥掉再判。
            ?.removePrefix("W/")
            ?.removePrefix("w/")
            ?.trim()
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if (value.length != HEX_LENGTH) return null
        if (value.any { it !in ALPHABET }) return null
        return value
    }

    /** 期望值缺失（null/空/坏格式）→ 通过；否则必须完全一致。 */
    fun matches(expected: String?, actual: String?): Boolean {
        val wanted = normalize(expected) ?: return true
        val got = normalize(actual) ?: return false
        return wanted == got
    }

    /** 期望值缺失 → 通过；否则对字节重算后比对。 */
    fun matches(expected: String?, bytes: ByteArray): Boolean =
        matches(expected, sha256Hex(bytes))
}
