package com.yap.app.domain

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 备份格式的媒体部分：头像、聊天图片、表情、语音、视频真的跟着备份走。
 *
 * v2 及以前，`personas.avatarUri` 只写一个「裸文件名」、`attachmentsJson` 只写路径：
 * 换台手机以后这些引用全都指不到文件，图片等于丢了。v3 在 JSON 里加一个顶层 `media`
 * 数组，把本机**当前还引用得到**的图片二进制用 base64 一起装进去。
 * v6（0.13）把同一套机制扩到表情（GIF）/ 语音 / 视频：`media[].kind` 增加
 * `STICKER` / `AUDIO` / `VIDEO`，二进制仍然只内联一次（表情的元数据在顶层 `stickers[]` 里）。
 *
 * 三条边界写在这里，是为了以后没人能「顺手」放开：
 *
 * 1. **只带本机真的引用到的媒体**：人格头像、用户自己的头像、预设头像、聊天消息里的附件，
 *    以及 `stickers` 表里文件还在本机的表情行。孤儿文件不扫描——备份不是磁盘镜像。
 * 2. **总量与单份都有上限**（[BackupMediaLimits]）：base64 会让体积涨约 1/3，
 *    而备份是一个要在手机上生成、传输、再读回来的 JSON 文件。超限就停，用户会看到
 *    「有 N 份媒体没有随备份带走」——宁可少几份，也不要一份导不回去的备份。
 * 3. **导入时文件名一律重建**：备份里的 `fileName` 只用来做「消息/头像/表情 ↔ 媒体」的关联，
 *    落盘永远是新的 UUID / 内容哈希文件名，并且必须过 [MediaOwnership] 的目录归属守卫。
 *    备份是外部输入，不能让它决定往哪个路径写字节。
 */
object BackupMediaLimits {

    /** 一份备份里所有图片的原始字节总量上限：128 MiB。 */
    const val MAX_MEDIA_BYTES: Long = 128L * 1024 * 1024

    /** 单张图片的原始字节上限：8 MiB。 */
    const val MAX_MEDIA_FILE_BYTES: Int = 8 * 1024 * 1024

    /** 导入压缩后头像的字节上限：400 KiB（`spec/web-search` 之外，0.12 媒体压缩约定）。 */
    const val MAX_AVATAR_OUTPUT_BYTES: Int = 400 * 1024

    /** 导入压缩后聊天图片的字节上限：800 KiB。 */
    const val MAX_CHAT_OUTPUT_BYTES: Int = 800 * 1024

    /** 导入压缩后的长边上限：头像 512、聊天图片 1600。 */
    const val AVATAR_MAX_EDGE: Int = 512
    const val CHAT_MAX_EDGE: Int = 1600

    /** 导入重编码的 JPEG 质量（压不进字节上限时才逐档下调）。 */
    const val JPEG_QUALITY: Int = 85

    fun outputLimit(kind: String): Int =
        if (BackupMediaKind.isAvatar(kind)) MAX_AVATAR_OUTPUT_BYTES else MAX_CHAT_OUTPUT_BYTES

    fun maxEdge(kind: String): Int =
        if (BackupMediaKind.isAvatar(kind)) AVATAR_MAX_EDGE else CHAT_MAX_EDGE

    /**
     * 整份备份文件（含 base64 膨胀与文本）的读入上限：256 MiB。
     *
     * 旧的 64 MB 是按「只有文字」估的；v3 的 128 MiB 图片 base64 之后约 171 MiB，
     * 64 MB 的闸会把一份自己刚导出的备份挡在门外。
     */
    const val MAX_BACKUP_BYTES: Long = 256L * 1024 * 1024

    /** 解析前的字符数上限（与 [MAX_BACKUP_BYTES] 同值；base64 是 ASCII，字符数≈字节数）。 */
    const val MAX_BACKUP_CHARS: Int = 256 * 1024 * 1024

    fun isTooLarge(byteCount: Long): Boolean = byteCount > MAX_BACKUP_BYTES

    /**
     * 按**可用堆**算媒体预算（大库基准的结论：整份 JSON 在内存里，峰值约为媒体原始字节的 5–6 倍）。
     *
     * base64 ≈ ×1.35，JSON 字符串在 JVM 里是 UTF-16（×2），编码/解码时还要一份副本（再 ×2），
     * 实测 500×800KB（335MB 原始）在 192MB 堆上必定 OOM。所以预算取 `可用堆 / 12`，再与
     * [MAX_MEDIA_BYTES] 取小。堆很大时行为与之前一致；小堆机器上宁可少带几张图。
     */
    fun effectiveMediaBudget(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Long =
        minOf(MAX_MEDIA_BYTES, maxHeapBytes / 12).coerceAtLeast(4L * 1024 * 1024)

    /** 导入时整份 JSON 的上限：`min(256MiB, 可用堆/8)`（解析前要按字符读入，UTF-16 占两倍）。 */
    fun effectiveBackupBytes(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Long =
        minOf(MAX_BACKUP_BYTES, maxHeapBytes / 8).coerceAtLeast(8L * 1024 * 1024)

    fun isTooManyChars(charCount: Int): Boolean = charCount > MAX_BACKUP_CHARS

    /** 给文案用的 MB 数（向上取整，避免出现「0MB」）。 */
    fun megabytes(bytes: Long = MAX_BACKUP_BYTES): Long {
        val mb = bytes / (1024 * 1024)
        return if (mb <= 0L) 1L else mb
    }

    /** 单张 base64 文本的长度上限（含 padding）：再长就不可能落在单张预算里。 */
    fun maxBase64Chars(perFileBytes: Int = MAX_MEDIA_FILE_BYTES): Int =
        ((perFileBytes + 2) / 3) * 4
}

/**
 * `media[].kind` 的合法取值。
 *
 * v3 只有三种图片；v6（0.13）加了表情/音频/视频：
 * - [STICKER]：表情包 GIF，字节同时被顶层 `stickers[].fileName` 引用（不重复内联 base64）；
 * - [AUDIO]：语音（`files/audio/voice-*`）；
 * - [VIDEO]：视频（`files/video/video-*`）。
 *
 * 只有头像与聊天图片会走「导入重编码」（[isCompressible]）：GIF 一旦转 JPEG 就不动了，
 * 音频/视频更不能拿 Bitmap 去压。
 */
object BackupMediaKind {
    const val PERSONA_AVATAR = "PERSONA_AVATAR"
    const val USER_AVATAR = "USER_AVATAR"
    const val CHAT_IMAGE = "CHAT_IMAGE"
    const val STICKER = "STICKER"
    const val AUDIO = "AUDIO"
    const val VIDEO = "VIDEO"

    val all = setOf(PERSONA_AVATAR, USER_AVATAR, CHAT_IMAGE, STICKER, AUDIO, VIDEO)

    fun isAvatar(kind: String): Boolean = kind == PERSONA_AVATAR || kind == USER_AVATAR

    /** 导入时是否应该交给 `MediaStorage.compressBackupImage` 重编码。 */
    fun isCompressible(kind: String): Boolean = isAvatar(kind) || kind == CHAT_IMAGE
}

/** v3 媒体相关的提醒：数据层只给计数与种类，句子由 UI 按语言拼。 */
sealed interface BackupMediaWarning {
    /** 导出时超限没带走的图片数。 */
    data class ExportSkipped(val count: Int) : BackupMediaWarning

    /** 导入时没能落盘的图片数（坏 base64 / 不支持的 mime / 文件名不安全 / 目录非法 / 写入失败）。 */
    data class ImportSkipped(val count: Int) : BackupMediaWarning

    /** 导入时因为总量或单张超限而跳过的图片数。 */
    data class ImportOverLimit(val count: Int) : BackupMediaWarning
}

/**
 * 备份用的 base64：标准字母表、带 padding、**不换行**。
 *
 * 为什么不用 `android.util.Base64`：`BackupCodec` 与媒体策略要能在 JVM 单测里直接跑
 * （`app:testDebugUnitTest`），而 `android.util.Base64` 在本地单测里是 stub。
 * 实现按 RFC 4648 §4，解码对空白宽容（导入时允许别人手工排版过），但非法字符一律拒绝。
 */
object BackupBase64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = CharArray(((bytes.size + 2) / 3) * 4)
        var source = 0
        var target = 0
        while (source + 2 < bytes.size) {
            val b0 = bytes[source].toInt() and 0xFF
            val b1 = bytes[source + 1].toInt() and 0xFF
            val b2 = bytes[source + 2].toInt() and 0xFF
            out[target] = ALPHABET[(b0 shr 2) and 0x3F]
            out[target + 1] = ALPHABET[((b0 shl 4) or (b1 shr 4)) and 0x3F]
            out[target + 2] = ALPHABET[((b1 shl 2) or (b2 shr 6)) and 0x3F]
            out[target + 3] = ALPHABET[b2 and 0x3F]
            source += 3
            target += 4
        }
        when (bytes.size - source) {
            1 -> {
                val b0 = bytes[source].toInt() and 0xFF
                out[target] = ALPHABET[(b0 shr 2) and 0x3F]
                out[target + 1] = ALPHABET[(b0 shl 4) and 0x3F]
                out[target + 2] = '='
                out[target + 3] = '='
            }
            2 -> {
                val b0 = bytes[source].toInt() and 0xFF
                val b1 = bytes[source + 1].toInt() and 0xFF
                out[target] = ALPHABET[(b0 shr 2) and 0x3F]
                out[target + 1] = ALPHABET[((b0 shl 4) or (b1 shr 4)) and 0x3F]
                out[target + 2] = ALPHABET[(b1 shl 2) and 0x3F]
                out[target + 3] = '='
            }
        }
        return String(out)
    }

    /** 解不出来返回 null（不抛）。空串解成空字节数组。 */
    fun decode(text: String): ByteArray? {
        val cleaned = StringBuilder(text.length)
        for (char in text) {
            when (char) {
                ' ', '\n', '\r', '\t' -> continue
                else -> cleaned.append(char)
            }
        }
        val value = cleaned.toString()
        if (value.isEmpty()) return ByteArray(0)
        if (value.length % 4 != 0) return null

        var padding = 0
        if (value.endsWith("==")) padding = 2 else if (value.endsWith("=")) padding = 1
        val out = ByteArray(value.length / 4 * 3 - padding)
        var target = 0
        var index = 0
        while (index < value.length) {
            val chunkEnd = index + 4
            val isLast = chunkEnd == value.length
            val c0 = index(value[index]) ?: return null
            val c1 = index(value[index + 1]) ?: return null
            val c0Char = value[index]
            val c1Char = value[index + 1]
            val c2Char = value[index + 2]
            val c3Char = value[index + 3]
            // 前两个字符永远不能是 padding。
            if (c0Char == '=' || c1Char == '=') return null
            val c2 = if (c2Char == '=') 0 else index(c2Char) ?: return null
            val c3 = if (c3Char == '=') 0 else index(c3Char) ?: return null
            // padding 只允许出现在最后四个字符里，而且必须是「尾部」：
            // `xx==` 合法，`xxx=` 合法，`xx=x` / `=xxx` 都是坏数据。
            if ((c2Char == '=' || c3Char == '=') && !isLast) return null
            if (c2Char == '=' && c3Char != '=') return null

            val combined = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            if (target < out.size) out[target++] = ((combined shr 16) and 0xFF).toByte()
            if (target < out.size) out[target++] = ((combined shr 8) and 0xFF).toByte()
            if (target < out.size) out[target++] = (combined and 0xFF).toByte()
            index += 4
        }
        return out
    }

    private fun index(char: Char): Int? {
        val code = char.code
        return when (code) {
            in 'A'.code..'Z'.code -> code - 'A'.code
            in 'a'.code..'z'.code -> code - 'a'.code + 26
            in '0'.code..'9'.code -> code - '0'.code + 52
            '+'.code -> 62
            '/'.code -> 63
            else -> null
        }
    }
}

/**
 * 备份支持的媒体格式。
 *
 * 两套语义，刻意分开：
 * - [supported] / [isSupported] 是 **v3 的图片语义**：只有 JPEG/PNG/WEBP 会送去重编码。
 *   `MediaStorage.compressBackupImage` 只认这三种，GIF 送进去会被当静态图转成 JPEG（动图就没了），
 *   所以「能不能压缩」永远按这个集合判断。
 * - [supports] / [extensionFor] 是 **v6 的 kind-aware 语义**：表情只收图片（含 GIF），
 *   音频/视频只收各自的容器格式。打包与落盘都走这两个函数，避免把 `audio/m4a` 当图片、
 *   或把 GIF 当 JPEG 去压缩。
 */
object BackupMediaFormats {

    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val WEBP = "image/webp"
    const val GIF = "image/gif"

    const val AUDIO_M4A = "audio/m4a"
    const val AUDIO_MP4 = "audio/mp4"
    const val AUDIO_MPEG = "audio/mpeg"

    const val VIDEO_MP4 = "video/mp4"
    const val VIDEO_QUICKTIME = "video/quicktime"

    /** v3/v4 的「可压缩图片」集合：导入重编码只处理这三种。 */
    val supported: Set<String> = setOf(JPEG, PNG, WEBP)

    /** 表情可以带的图片格式（GIF 是主形态；静态表情跨端也可能是 PNG/WEBP/JPEG）。 */
    private val stickerMimes: Set<String> = setOf(GIF, PNG, WEBP, JPEG)

    private val audioMimes: Set<String> = setOf(AUDIO_M4A, AUDIO_MP4, AUDIO_MPEG)

    private val videoMimes: Set<String> = setOf(VIDEO_MP4, VIDEO_QUICKTIME)

    fun isSupported(mime: String): Boolean = mime.trim().lowercase() in supported

    /** kind-aware：这个 kind 能不能带这个 mime。 */
    fun supports(kind: String, mime: String): Boolean {
        val clean = mime.trim().lowercase()
        return when (kind) {
            BackupMediaKind.PERSONA_AVATAR, BackupMediaKind.USER_AVATAR, BackupMediaKind.CHAT_IMAGE ->
                clean in supported
            BackupMediaKind.STICKER -> clean in stickerMimes
            BackupMediaKind.AUDIO -> clean in audioMimes
            BackupMediaKind.VIDEO -> clean in videoMimes
            else -> false
        }
    }

    /** 这个 kind 是否走导入重编码（只有头像与聊天图）。 */
    fun isCompressible(kind: String): Boolean = BackupMediaKind.isCompressible(kind)

    fun extensionOf(mime: String): String? = when (mime.trim().lowercase()) {
        JPEG -> "jpg"
        PNG -> "png"
        WEBP -> "webp"
        GIF -> "gif"
        // 语音落盘统一 .m4a（与 MediaStorage.voice-* 一致），mp4 容器的音频也收成 m4a。
        AUDIO_M4A, AUDIO_MP4 -> "m4a"
        AUDIO_MPEG -> "mp3"
        VIDEO_MP4 -> "mp4"
        VIDEO_QUICKTIME -> "mov"
        else -> null
    }

    fun mimeOfExtension(extension: String): String? = when (extension.trim().lowercase()) {
        "jpg", "jpeg" -> JPEG
        "png" -> PNG
        "webp" -> WEBP
        "gif" -> GIF
        "m4a" -> AUDIO_M4A
        "mp3" -> AUDIO_MPEG
        "mp4" -> VIDEO_MP4
        "mov" -> VIDEO_QUICKTIME
        else -> null
    }

    /** kind-aware：这个 kind 的 mime 应该落成什么扩展名；不支持返回 null。 */
    fun extensionFor(kind: String, mime: String): String? =
        if (supports(kind, mime)) extensionOf(mime) else null

    /** 按文件名后缀猜 mime；猜不到返回 null（调用方跳过这个文件）。 */
    fun mimeOfFile(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isEmpty() || extension == fileName) return null
        return mimeOfExtension(extension)
    }
}

/**
 * 导出时的一个候选图片：已经被解析到真实的、属于 App 的本地文件。
 *
 * `fileName` 是**源文件名**（裸名），它同时会写进 `personas.avatarUri` /
 * `messages.attachmentsJson`，作为导入时「引用 ↔ media」的关联键。
 */
data class BackupMediaCandidate(
    val kind: String,
    val personaId: String?,
    val fileName: String,
    val file: File,
    val mime: String
)

/**
 * 导入时重编码后的一张图（媒体压缩的落点）。
 *
 * 压缩实现放在 `util/MediaStorage`（要 Android Bitmap），domain 只认这个结构，
 * 这样 `BackupMediaRestorer` 的纯 JVM 单测可以传假压缩器，不必拉 Android 图形栈。
 */
data class BackupCompressedImage(
    val bytes: ByteArray,
    val mime: String = "image/jpeg",
    val extension: String = "jpg"
)

/** 打包结果：写进 JSON 的 media 列表 + 因为超限没带走的张数。 */
data class BackupMediaPackResult(
    val media: List<BackupMedia>,
    val skippedByLimit: Int
)

/**
 * 导出时的媒体打包：**只读**文件系统，不写任何东西（单测可直接喂临时目录）。
 *
 * 规则：
 * - 同一张文件（同一路径 + 同一 kind）只装一次；
 * - 单张 > [perFileLimit] 直接跳过并计入超限；
 * - 原始字节累计 > [totalBudget] 就停止收集，剩下的全部计入超限（不写半张图）；
 * - 读不出来 / 文件不在了 → 跳过，但**不计**进「超限」提醒（那不是上限问题）。
 */
object BackupMediaPacker {

    fun pack(
        candidates: List<BackupMediaCandidate>,
        totalBudget: Long = BackupMediaLimits.effectiveMediaBudget(),
        perFileLimit: Int = BackupMediaLimits.MAX_MEDIA_FILE_BYTES
    ): BackupMediaPackResult {
        val packed = mutableListOf<BackupMedia>()
        val seen = mutableSetOf<String>()
        var total = 0L
        var skipped = 0

        val unique = candidates.filter { candidate ->
            val key = candidate.kind + "|" + runCatching { candidate.file.canonicalPath }.getOrDefault(candidate.file.path)
            seen.add(key)
        }

        for ((position, candidate) in unique.withIndex()) {
            val file = candidate.file
            if (!file.isFile || !file.canRead()) continue
            // v6：mime 必须与 kind 匹配（GIF 只能进 STICKER，音频不能进 CHAT_IMAGE）。
            val declared = candidate.mime.trim().lowercase()
            val mime = declared.takeIf { BackupMediaFormats.supports(candidate.kind, it) }
                ?: BackupMediaFormats.mimeOfFile(candidate.fileName)
                    ?.takeIf { BackupMediaFormats.supports(candidate.kind, it) }
                ?: continue
            if (!BackupAvatarPath.isSafeName(candidate.fileName)) continue
            val length = file.length()
            if (length <= 0L) continue
            if (length > perFileLimit) {
                skipped++
                continue
            }
            if (total + length > totalBudget) {
                // 停止收集：剩下的（含当前这张）都没有随备份带走。
                skipped += unique.size - position
                break
            }
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            total += bytes.size
            packed += BackupMedia(
                id = UUID.randomUUID().toString(),
                kind = candidate.kind,
                personaId = candidate.personaId,
                fileName = candidate.fileName,
                mime = mime,
                bytes = bytes.size,
                data = BackupBase64.encode(bytes)
            )
        }
        return BackupMediaPackResult(packed, skipped)
    }
}

/** 导入时落盘的一张图片：新文件 + 它在备份里的源文件名 + 新的 `file://` 地址。 */
data class RestoredBackupMedia(
    val kind: String,
    val fileName: String,
    val file: File,
    val uri: String,
    val mime: String
)

/** 导入媒体阶段的结果：关联表 + 提醒 + 已写文件（失败时要能回滚删掉）。 */
data class BackupMediaRestoreResult(
    val avatarsByFileName: Map<String, RestoredBackupMedia> = emptyMap(),
    val avatarsByPersonaId: Map<String, RestoredBackupMedia> = emptyMap(),
    val chatImagesByFileName: Map<String, RestoredBackupMedia> = emptyMap(),
    val userAvatar: RestoredBackupMedia? = null,
    /** v6：表情/音频/视频的「备份裸文件名 → 本机新文件」关联。 */
    val stickersByFileName: Map<String, RestoredBackupMedia> = emptyMap(),
    val audioByFileName: Map<String, RestoredBackupMedia> = emptyMap(),
    val videoByFileName: Map<String, RestoredBackupMedia> = emptyMap(),
    /**
     * 所有 kind 合并后的关联表（key 是备份里的裸文件名）。
     *
     * 消息附件不区分 kind（图片/表情/语音/视频都可能挂在 `attachmentsJson` 上），
     * 重建附件时按名字查这一张表即可。
     */
    val byFileName: Map<String, RestoredBackupMedia> = emptyMap(),
    val warnings: List<BackupMediaWarning> = emptyList(),
    val writtenFiles: List<File> = emptyList()
)

/**
 * 导入时的媒体落盘：把 `media[].data` 解码、写进本机媒体目录、返回关联表。
 *
 * 安全边界：
 * - 备份里的 `fileName` 只做关联键，**不参与拼路径**；落盘名一律是新 UUID / 内容哈希；
 * - 目标路径必须过 [MediaOwnership.isWithin]（目录归属守卫），目录非法就整类跳过；
 * - 坏 base64 / 与 kind 不匹配的 mime / 字节数与声明不符 / 超限：跳过并计数，绝不抛。
 *
 * v6 起按 kind 分发到五个目录：
 * 头像 `files/avatars`、聊天图 `files/media`、表情 `files/stickers`、
 * 语音 `files/audio`、视频 `files/video`。只有头像与聊天图会交给 [compressor] 重编码。
 * `stickerDir` / `audioDir` / `videoDir` 传 null 表示这次调用不管这类媒体
 * （旧的纯图片调用方不必改签名）：落到这些 kind 的条目按「跳过」计数。
 *
 * 返回的 [BackupMediaRestoreResult.writtenFiles] 用于「媒体写完、落库失败」时回滚，
 * 避免留下一批没人引用的文件。
 */
object BackupMediaRestorer {

    fun restore(
        entries: List<BackupMedia>,
        avatarDir: File,
        chatDir: File,
        /** v6：表情目录（`MediaStorage.stickerDirectory`）；null = 这类跳过。 */
        stickerDir: File? = null,
        /** v6：语音目录（`MediaStorage.audioDirectory`）；null = 这类跳过。 */
        audioDir: File? = null,
        /** v6：视频目录（`MediaStorage.videoDirectory`）；null = 这类跳过。 */
        videoDir: File? = null,
        totalBudget: Long = BackupMediaLimits.effectiveMediaBudget(),
        perFileLimit: Int = BackupMediaLimits.MAX_MEDIA_FILE_BYTES,
        /**
         * 落盘前的重编码（生产路径传 `MediaStorage::compressBackupImage`）。
         * null 表示原样落盘——只给纯逻辑测试/基准用，正式导入不应为 null。
         */
        compressor: ((bytes: ByteArray, mime: String, kind: String) -> BackupCompressedImage?)? = null,
        newName: (prefix: String, extension: String, bytes: ByteArray) -> String = ::defaultNewName
    ): BackupMediaRestoreResult {
        if (entries.isEmpty()) return BackupMediaRestoreResult()

        val avatarReady = ensureDirectory(avatarDir)
        val chatReady = ensureDirectory(chatDir)
        val stickerReady = stickerDir != null && ensureDirectory(stickerDir)
        val audioReady = audioDir != null && ensureDirectory(audioDir)
        val videoReady = videoDir != null && ensureDirectory(videoDir)
        val avatarsByFileName = linkedMapOf<String, RestoredBackupMedia>()
        val avatarsByPersonaId = linkedMapOf<String, RestoredBackupMedia>()
        val chatImages = linkedMapOf<String, RestoredBackupMedia>()
        val stickers = linkedMapOf<String, RestoredBackupMedia>()
        val audios = linkedMapOf<String, RestoredBackupMedia>()
        val videos = linkedMapOf<String, RestoredBackupMedia>()
        val byFileName = linkedMapOf<String, RestoredBackupMedia>()
        val written = mutableListOf<File>()
        var userAvatar: RestoredBackupMedia? = null
        var invalid = 0
        var overLimit = 0
        var total = 0L

        entries.forEach { entry ->
            val kind = entry.kind.trim()
            if (kind !in BackupMediaKind.all) {
                invalid++
                return@forEach
            }
            // 备份里的源文件名只用来关联：不合法就整条拒绝（绝对路径 / ../ 一律不认）。
            if (!BackupAvatarPath.isSafeName(entry.fileName)) {
                invalid++
                return@forEach
            }
            val mime = entry.mime.trim().lowercase()
            if (!BackupMediaFormats.supports(kind, mime)) {
                invalid++
                return@forEach
            }
            val extension = BackupMediaFormats.extensionFor(kind, mime) ?: run { invalid++; return@forEach }
            if (entry.bytes < 0 || entry.bytes > perFileLimit ||
                entry.data.length > BackupMediaLimits.maxBase64Chars(perFileLimit)
            ) {
                overLimit++
                return@forEach
            }
            val bytes = BackupBase64.decode(entry.data)
            if (bytes == null || bytes.isEmpty() || bytes.size != entry.bytes) {
                invalid++
                return@forEach
            }
            if (bytes.size > perFileLimit || total + bytes.size > totalBudget) {
                overLimit++
                return@forEach
            }
            // 总量按**原始**字节计（压缩前），避免「压缩后变小」成为绕过 128 MiB 预算的口子。
            total += bytes.size

            // v4 媒体压缩：只有头像与聊天图重编码（v6：GIF/音频/视频原样落盘）。
            // 压不出来（坏图 / 压不进字节上限）就跳过并计数，绝不让导入整体失败。
            val compressible = BackupMediaKind.isCompressible(kind)
            val compressed = if (compressor != null && compressible) {
                runCatching { compressor(bytes, mime, kind) }.getOrNull()
            } else {
                null
            }
            if (compressor != null && compressible && compressed == null) {
                invalid++
                return@forEach
            }
            val outputBytes = compressed?.bytes ?: bytes
            if (outputBytes.isEmpty() || outputBytes.size > perFileLimit) {
                invalid++
                return@forEach
            }
            if (compressed != null && outputBytes.size > BackupMediaLimits.outputLimit(kind)) {
                invalid++
                return@forEach
            }
            val outputMime = (compressed?.mime ?: mime).trim().lowercase()
            if (!BackupMediaFormats.supports(kind, outputMime)) {
                invalid++
                return@forEach
            }
            val outputExtension = compressed?.extension?.trim()?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?: BackupMediaFormats.extensionFor(kind, outputMime)
                ?: run { invalid++; return@forEach }

            val dir: File?
            val prefix: String
            val ready: Boolean
            when (kind) {
                BackupMediaKind.PERSONA_AVATAR, BackupMediaKind.USER_AVATAR -> {
                    dir = avatarDir
                    prefix = AvatarPrefix
                    ready = avatarReady
                }
                BackupMediaKind.CHAT_IMAGE -> {
                    dir = chatDir
                    prefix = ChatPrefix
                    ready = chatReady
                }
                BackupMediaKind.STICKER -> {
                    dir = stickerDir
                    prefix = StickerPrefix
                    ready = stickerReady
                }
                BackupMediaKind.AUDIO -> {
                    dir = audioDir
                    prefix = AudioPrefix
                    ready = audioReady
                }
                else -> {
                    dir = videoDir
                    prefix = VideoPrefix
                    ready = videoReady
                }
            }
            if (dir == null || !ready) {
                invalid++
                return@forEach
            }
            val target = File(dir, newName(prefix, outputExtension, outputBytes))
            // 目录归属守卫：写之前确认目标确实落在我们自己的媒体目录里。
            if (!BackupAvatarPath.isSafeName(target.name) || !MediaOwnership.isWithin(target, listOf(dir))) {
                invalid++
                return@forEach
            }
            // 内容寻址去重：同名（= 同一份内容）已经在磁盘上就不再写第二份。
            // 聊天图片用纯内容名（chat-<sha16>.jpg），头像仍然带 UUID（见 defaultNewName 的注释）。
            var created = false
            if (target.exists()) {
                val reusable = target.isFile && runCatching { target.length() == outputBytes.size.toLong() }.getOrDefault(false)
                if (!reusable) {
                    invalid++
                    return@forEach
                }
            } else {
                val writtenOk = runCatching {
                    target.writeBytes(outputBytes)
                    target.isFile && target.length() == outputBytes.size.toLong()
                }.getOrDefault(false)
                if (!writtenOk) {
                    runCatching { target.delete() }
                    invalid++
                    return@forEach
                }
                created = true
                written += target
            }
            val restored = RestoredBackupMedia(
                kind = kind,
                fileName = entry.fileName,
                file = target,
                uri = fileUri(target),
                mime = outputMime
            )
            fun rejectDuplicate() {
                if (created) {
                    written -= target
                    runCatching { target.delete() }
                }
                invalid++
            }
            // 同一个源文件名出现多次时以第一条为准（文件内容不同的「同名」是坏数据）。
            if (byFileName.putIfAbsent(restored.fileName, restored) != null) {
                rejectDuplicate()
                return@forEach
            }
            when (kind) {
                BackupMediaKind.PERSONA_AVATAR -> {
                    if (avatarsByFileName.putIfAbsent(restored.fileName, restored) == null) {
                        val personaId = entry.personaId?.trim().orEmpty()
                        if (personaId.isNotEmpty()) avatarsByPersonaId.putIfAbsent(personaId, restored)
                    } else {
                        byFileName.remove(restored.fileName)
                        rejectDuplicate()
                    }
                }
                BackupMediaKind.USER_AVATAR -> {
                    if (userAvatar == null) {
                        userAvatar = restored
                    } else {
                        byFileName.remove(restored.fileName)
                        rejectDuplicate()
                    }
                }
                BackupMediaKind.CHAT_IMAGE -> {
                    if (chatImages.putIfAbsent(restored.fileName, restored) != null) {
                        byFileName.remove(restored.fileName)
                        rejectDuplicate()
                    }
                }
                BackupMediaKind.STICKER -> {
                    if (stickers.putIfAbsent(restored.fileName, restored) != null) {
                        byFileName.remove(restored.fileName)
                        rejectDuplicate()
                    }
                }
                BackupMediaKind.AUDIO -> {
                    if (audios.putIfAbsent(restored.fileName, restored) != null) {
                        byFileName.remove(restored.fileName)
                        rejectDuplicate()
                    }
                }
                else -> {
                    if (videos.putIfAbsent(restored.fileName, restored) != null) {
                        byFileName.remove(restored.fileName)
                        rejectDuplicate()
                    }
                }
            }
        }

        val warnings = buildList {
            if (invalid > 0) add(BackupMediaWarning.ImportSkipped(invalid))
            if (overLimit > 0) add(BackupMediaWarning.ImportOverLimit(overLimit))
        }
        return BackupMediaRestoreResult(
            avatarsByFileName = avatarsByFileName,
            avatarsByPersonaId = avatarsByPersonaId,
            chatImagesByFileName = chatImages,
            userAvatar = userAvatar,
            stickersByFileName = stickers,
            audioByFileName = audios,
            videoByFileName = videos,
            byFileName = byFileName,
            warnings = warnings,
            writtenFiles = written
        )
    }

    /** 头像目录里的文件前缀（与 `MediaStorage.persistAvatar` 保持一致）。 */
    const val AvatarPrefix = "avatar-"

    /** 聊天图片前缀（`MediaStorage.clearChatImages` 只清这一类，所以恢复时也必须用它）。 */
    const val ChatPrefix = "chat-"

    /**
     * 表情文件前缀（v6）。
     *
     * `StickerRepository` 自己落盘用 `<uuid>.gif`，清理走「整目录」或「按行删文件」，
     * 所以备份恢复用内容寻址的 `sticker-<sha16>.gif` 也不会被别的清理路径误伤；
     * 前缀只为「人肉一眼看出来这是备份恢复的表情」与将来按前缀清理留一条路。
     */
    const val StickerPrefix = "sticker-"

    /**
     * 语音前缀（v6）：必须与 `MediaStorage` 的 `voice-` 一致。
     *
     * `MediaStorage.clearVoiceVideoExceptIfUnreferenced` 只清 `voice-*` / `video-*`，
     * 恢复时换个前缀，那批文件就永远回收不掉。
     */
    const val AudioPrefix = "voice-"

    /** 视频前缀（v6）：与 `MediaStorage` 的 `video-` 一致（同上）。 */
    const val VideoPrefix = "video-"

    /**
     * `File.toURI()` 给的是 `file:/path`（单斜杠），而 App 其它地方（`Uri.fromFile`）写的是
     * `file:///path`。统一成后者，免得同一个头像在库里出现两种写法的 `file://` 引用。
     */
    private fun fileUri(file: File): String = "file://" + file.toURI().toString().removePrefix("file:")

    private fun ensureDirectory(dir: File): Boolean =
        runCatching { (dir.isDirectory || dir.mkdirs()) && dir.isDirectory }.getOrDefault(false)

    /**
     * 落盘文件名。
     *
     * - 聊天图片：`chat-<sha256前16>.jpg` —— 纯内容寻址，同一张图重复导入/重复选择只留一份。
     *   名字里没有 UUID，所以 `clearChatImages()` 的 `chat-*` 语义不变。
     * - 头像：`avatar-<sha256前16>-<uuid>.jpg` —— 头像有「换头像时删旧文件」的生命周期
     *   （`MediaStorage.deleteAvatar`），两张人格共用一份文件会在改其中一个时把另一个删坏；
     *   所以头像只把哈希当索引前缀，仍然保持一文一文件。
     * - 表情/语音/视频（v6）：`sticker-<sha16>.<ext>` / `voice-<sha16>.<ext>` /
     *   `video-<sha16>.<ext>` —— 纯内容寻址：同一张表情被十条消息引用、同一条语音被服务端
     *   回推两次，落盘都只留一份；前缀与 `MediaStorage` 的清理语义对齐。
     */
    internal fun defaultNewName(prefix: String, extension: String, bytes: ByteArray): String {
        val hash = sha256Hex(bytes).take(16)
        return if (prefix == AvatarPrefix) {
            "$prefix$hash-${UUID.randomUUID()}.$extension"
        } else {
            "$prefix$hash.$extension"
        }
    }

    /** 内容哈希（SHA-256 十六进制小写）。导出去重与落盘命名共用。 */
    internal fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = CharArray(digest.size * 2)
        val alphabet = "0123456789abcdef"
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            hex[index * 2] = alphabet[value ushr 4]
            hex[index * 2 + 1] = alphabet[value and 0x0F]
        }
        return String(hex)
    }
}

/**
 * `messages.attachmentsJson` 的跨端转换。
 *
 * 导出（v3 起）：把每条的 `uri` 从设备路径换成**裸文件名**（换机后路径必然失效；裸名用来在
 * `media` 里找到同一份媒体）。文件不存在 / 名字不安全 / 格式与 kind 不匹配的附件直接丢掉。
 * v6 起图片/表情/语音/视频都走同一条路：`kind` 与 `durationMs` 原样保留，只有 `uri` 变裸名。
 *
 * 导入：只有能在 `media`（同名 fileName）里找到的附件才重建成本机新路径；
 * 找不到的丢掉并计数——这正是 v1/v2 备份「图片没有跟着备份走，只剩文字」的老行为。
 */
object BackupAttachmentsPortable {

    /** 导入一条消息的附件结果：[json] 为 null 表示没有（可用的）附件了。 */
    data class Restored(val json: String?, val dropped: Int)

    /**
     * 导出：`allowedNames` 是本次真的装进 media 的所有文件名（五种 kind 都算）。
     *
     * 旧代码只允许聊天图片；v6 的语音/视频/表情同样要能被消息引用，否则换台手机
     * 消息里的「那条语音」就只剩一个播放不出来的气泡。
     */
    fun portable(raw: String?, allowedNames: Set<String>): String? {
        val attachments = AttachmentsCodec.decode(raw)
        if (attachments.isEmpty() || allowedNames.isEmpty()) return null
        val portable = attachments.mapNotNull { attachment ->
            val name = BackupAvatarPath.portable(attachment.uri) ?: return@mapNotNull null
            if (name !in allowedNames) return@mapNotNull null
            val kind = portableKindOf(attachment)
            if (BackupMediaFormats.supports(kind, attachment.mime)) {
                attachment.copy(uri = name)
            } else {
                // mime 字段可能是历史遗留的奇怪值：按文件名再认一次，认出来就用文件后缀的 mime。
                val derived = BackupMediaFormats.mimeOfFile(name) ?: return@mapNotNull null
                if (!BackupMediaFormats.supports(kind, derived)) return@mapNotNull null
                attachment.copy(uri = name, mime = derived)
            }
        }
        return AttachmentsCodec.encode(portable)
    }

    /**
     * 导入：按 `media` 里恢复出来的文件重建本机路径与 mime。
     *
     * `byFileName` 是 [BackupMediaRestoreResult.byFileName]（五种 kind 合并后的表）。
     * `kind` / `durationMs` 由 [MessageAttachment.copy] 原样保留——语音的时长丢了，
     * 界面上那条气泡就不知道该画多长。
     */
    fun restore(
        raw: String?,
        byFileName: Map<String, RestoredBackupMedia>
    ): Restored {
        val attachments = AttachmentsCodec.decode(raw)
        if (attachments.isEmpty()) return Restored(null, 0)
        var dropped = 0
        val rebuilt = attachments.mapNotNull { attachment ->
            // 附件 uri 是裸文件名；v1/v2 里的设备路径一律不认（换机后本来也指不到文件）。
            val bare = attachment.uri.trim()
            if (bare.contains('/') || bare.contains('\\') || bare.contains("..")) {
                dropped++
                return@mapNotNull null
            }
            val media = byFileName[bare] ?: run { dropped++; return@mapNotNull null }
            attachment.copy(uri = media.uri, mime = media.mime)
        }
        return Restored(AttachmentsCodec.encode(rebuilt), dropped)
    }

    /** 附件应该落进 media 的哪个 kind（顺序有意义：表情也是图片）。 */
    private fun portableKindOf(attachment: MessageAttachment): String = when {
        attachment.isSticker -> BackupMediaKind.STICKER
        attachment.isAudio -> BackupMediaKind.AUDIO
        attachment.isVideo -> BackupMediaKind.VIDEO
        else -> BackupMediaKind.CHAT_IMAGE
    }
}

