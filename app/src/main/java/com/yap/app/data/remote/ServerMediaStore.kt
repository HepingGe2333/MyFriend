package com.yap.app.data.remote

import android.content.Context
import com.yap.app.domain.AttachmentsCodec
import com.yap.app.domain.AudioVideoPolicy
import com.yap.app.domain.BackupAvatarPath
import com.yap.app.domain.MediaHashes
import com.yap.app.domain.MediaOwnership
import com.yap.app.domain.MediaTypes
import com.yap.app.domain.MessageAttachment
import com.yap.app.domain.RenderMime
import com.yap.app.util.MediaStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端媒体的六种用途（与 [ServerMediaKinds] 逐字一致）。
 *
 * 为什么不直接复用 [ServerMediaKinds]：那边是「上传参数的白名单」，这里还要回答
 * 「落到哪个目录、叫什么文件名、下载上限多少」。两件事共用同一批字符串，但映射规则属于
 * 本文件（下载/落盘），所以单独一个对象，避免上传侧被下载细节污染。
 */
object ServerMediaKind {

    const val CHAT_IMAGE = ServerMediaKinds.CHAT_IMAGE
    const val PERSONA_AVATAR = ServerMediaKinds.PERSONA_AVATAR
    const val USER_AVATAR = ServerMediaKinds.USER_AVATAR
    const val STICKER = ServerMediaKinds.STICKER
    const val AUDIO = ServerMediaKinds.AUDIO
    const val VIDEO = ServerMediaKinds.VIDEO

    val all: Set<String> = setOf(CHAT_IMAGE, PERSONA_AVATAR, USER_AVATAR, STICKER, AUDIO, VIDEO)

    /** 服务端附件没给 mime 时的缺省 mime（与 [MessageAttachment] 的默认值一致）。 */
    const val DEFAULT_IMAGE_MIME = "image/jpeg"

    fun isKnown(kind: String?): Boolean = !kind.isNullOrBlank() && kind in all

    /**
     * 既有本地/DB 附件元数据 → 服务端用途。
     *
     * 只认**显式证据**：`kind` 字段，或不是缺省 `image/jpeg` 的 mime。
     * 缺省 mime 只代表「0.12 的老图片」，不构成「这一定是图片」的证据——服务端消息的附件
     * 只有 `/v1/media/<id>`，一条语音在上一轮同步后可能就是按默认 mime 落库的。
     */
    fun fromAttachment(attachment: MessageAttachment?): String? {
        if (attachment == null) return null
        when (attachment.kind) {
            MessageAttachment.KIND_STICKER -> return STICKER
            MessageAttachment.KIND_AUDIO -> return AUDIO
            MessageAttachment.KIND_VIDEO -> return VIDEO
            MessageAttachment.KIND_IMAGE -> return CHAT_IMAGE
        }
        val mime = attachment.mime.trim().lowercase(Locale.US)
        if (mime.isEmpty() || mime == DEFAULT_IMAGE_MIME) return null
        return fromMime(mime)
    }

    /** mime → 用途（GIF 一律当表情：`spec/stickers/README.md` 的约定）。 */
    fun fromMime(mime: String?): String? {
        val value = mime?.trim()?.lowercase(Locale.US).orEmpty()
        if (value.isEmpty()) return null
        return when {
            value == RenderMime.GIF -> STICKER
            value.startsWith("image/") -> CHAT_IMAGE
            value.startsWith("audio/") -> AUDIO
            value.startsWith("video/") -> VIDEO
            value == "application/mp4" -> VIDEO
            else -> null
        }
    }

    /**
     * 文件名扩展名 → mime。
     *
     * 只用于「本机已有附件路径」这一路（`file://.../voice-xxx.m4a`）；服务端引用
     * `/v1/media/<id>` 没有扩展名，返回 null。
     */
    fun fromFileName(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.contains("://")) return null
        return when (value.substringAfterLast('.', "").lowercase(Locale.US)) {
            "jpg", "jpeg" -> DEFAULT_IMAGE_MIME
            "png" -> "image/png"
            "gif" -> RenderMime.GIF
            "webp" -> "image/webp"
            "m4a" -> "audio/m4a"
            "mp3" -> "audio/mpeg"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            else -> null
        }
    }

    /**
     * 用途判定优先级：既有元数据（显式 kind / 具体 mime）→ 字节 magic → 文件名扩展名 → 普通图片。
     *
     * 唯一的例外：元数据说「图片」（多半是缺省 mime），字节却明确是音视频/表情时信字节——
     * 否则一条服务端语音在第一次同步后会永远被当成一张加载不出来的图片。
     */
    fun detect(existing: MessageAttachment?, sniffedMime: String?): String {
        val explicit = fromAttachment(existing)
        val fromBytes = fromMime(sniffedMime)
        val fromName = fromMime(fromFileName(existing?.uri))
        if (explicit == null) return fromBytes ?: fromName ?: CHAT_IMAGE
        if (explicit == CHAT_IMAGE && fromBytes != null && fromBytes != CHAT_IMAGE) return fromBytes
        return explicit
    }

    /**
     * 0.15 严格分类（**下载新字节**专用）：拿不准就返回 null，由调用方拒绝。
     *
     * 与 [detect] 的唯一区别是**不再兜底 CHAT_IMAGE**：服务端引用没有 mime、字节 magic 又认不出时，
     * 旧实现会把它当一张普通图片写进 `files/media`，结果是一条未知格式的媒体永远加载不出来。
     * 现在返回 null → 计一次失败（保留服务端引用，下次还能重试），不猜、不落库。
     *
     * 兼容点保持不变：显式元数据（kind / 具体 mime）优先于字节；`image/jpeg` 这个缺省 mime
     * 不算证据，字节说它是音视频/表情时信字节。
     */
    fun classify(existing: MessageAttachment?, sniffedMime: String?): String? {
        val explicit = fromAttachment(existing)
        val fromBytes = fromMime(sniffedMime)
        val fromName = fromMime(fromFileName(existing?.uri))
        if (explicit == null) return fromBytes ?: fromName
        if (explicit == CHAT_IMAGE && fromBytes != null && fromBytes != CHAT_IMAGE) return fromBytes
        return explicit
    }

    /** 落盘 mime：优先「与最终用途一致」的那份证据，都不一致时按用途给缺省值。 */
    fun mimeFor(kind: String, existingMime: String?, sniffedMime: String?, existingUri: String? = null): String {
        val candidates = listOf(existingMime, sniffedMime, fromFileName(existingUri))
        candidates.forEach { mime ->
            if (mime != null && fromMime(mime) == kind) return mime.trim().lowercase(Locale.US)
        }
        return defaultMime(kind)
    }

    fun defaultMime(kind: String): String = when (kind) {
        STICKER -> RenderMime.GIF
        AUDIO -> "audio/m4a"
        VIDEO -> "video/mp4"
        else -> DEFAULT_IMAGE_MIME
    }

    /** 落盘扩展名。音视频走 [AudioVideoPolicy.extensionFor]（与服务端上传白名单同一套）。 */
    fun extensionOf(kind: String, mime: String?): String {
        val value = mime?.trim()?.lowercase(Locale.US).orEmpty()
        return if (kind == AUDIO || kind == VIDEO) {
            AudioVideoPolicy.extensionFor(value)
        } else {
            when (value) {
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                "image/heic", "image/heif" -> "heic"
                else -> "jpg"
            }
        }
    }

    /**
     * 文件名前缀。
     *
     * 与清理路径逐字对齐：`files/media/chat-*`（清空聊天图片）、`files/avatars/avatar-*`
     * （换头像删旧文件）、`files/audio/voice-*`、`files/video/video-*`（清空语音/视频）、
     * `files/stickers/`（整目录归表情库）。
     */
    fun filePrefix(kind: String): String = when (kind) {
        STICKER -> "sticker-"
        AUDIO -> "voice-"
        VIDEO -> "video-"
        PERSONA_AVATAR, USER_AVATAR -> "avatar-"
        else -> "chat-"
    }

    /**
     * 下载字节上限：图片/头像/表情 2 MiB、音频 8 MiB、视频 64 MiB（复用 [ServerMediaLimits]）。
     *
     * 用途未知时按视频上限一次取回，再按嗅探出的真实用途复检——服务端消息只有引用、
     * 没有 mime，先按 2 MiB 猜会把每一条语音/视频都判成失败（还要再下一次）。
     */
    fun maxBytes(kind: String?): Int = if (kind != null && isKnown(kind)) {
        ServerMediaLimits.maxUploadBytes(kind)
    } else {
        ServerMediaLimits.MAX_VIDEO_BYTES
    }

    /** 落盘附件里要写的 `kind`（普通图片保持 null，与 0.12 数据完全兼容）。 */
    fun attachmentKind(kind: String): String? = when (kind) {
        STICKER -> MessageAttachment.KIND_STICKER
        AUDIO -> MessageAttachment.KIND_AUDIO
        VIDEO -> MessageAttachment.KIND_VIDEO
        else -> null
    }

    /**
     * 一个用途的落盘目标（目录 + 文件名）。
     *
     * 两道守卫都在这里做完：文件名过 [BackupAvatarPath.isSafeName]（拒绝 `/`、`..`、超长），
     * 目标路径过 [MediaOwnership.isWithin]（只写我们自己的媒体目录）。服务端 id 虽然
     * 由 [ServerMediaRef] 校验过字符集，但仍然不直接把 id 拼进绝对路径。
     */
    fun targetFor(
        id: String,
        kind: String,
        mime: String,
        directories: ServerMediaDirectories
    ): ServerMediaTarget? {
        val directory = directories.forKind(kind)
        val fileName = filePrefix(kind) + id + "." + extensionOf(kind, mime)
        if (!BackupAvatarPath.isSafeName(fileName)) return null
        val target = ServerMediaTarget(kind = kind, mime = mime, directory = directory, fileName = fileName)
        if (!MediaOwnership.isWithin(target.file, listOf(directory))) return null
        return target
    }
}

/** 一个附件的落盘目标：用途 + mime + 目录 + 文件名（纯数据，测试可直接断言）。 */
data class ServerMediaTarget(
    val kind: String,
    val mime: String,
    val directory: File,
    val fileName: String
) {
    val file: File get() = File(directory, fileName)
}

/**
 * 五类持久目录（测试传临时目录，生产由 [ServerMediaStore] 从 `MediaStorage` 取）。
 *
 * 头像与用户头像共用 `files/avatars`：两者都是「一格头像」，清理规则也一样。
 */
data class ServerMediaDirectories(
    val chatImages: File,
    val avatars: File,
    val stickers: File,
    val audio: File,
    val video: File
) {
    fun forKind(kind: String): File = when (kind) {
        ServerMediaKind.PERSONA_AVATAR, ServerMediaKind.USER_AVATAR -> avatars
        ServerMediaKind.STICKER -> stickers
        ServerMediaKind.AUDIO -> audio
        ServerMediaKind.VIDEO -> video
        else -> chatImages
    }

    fun all(): List<File> = listOf(chatImages, avatars, stickers, audio, video)
}

/**
 * 服务端返回的字节 magic 嗅探（纯 JVM，纯函数）。
 *
 * `ServerMessage.attachments` 只有 `/v1/media/<id>`，`GET /v1/media/<id>` 也只给字节
 * （没有 Content-Type 可拿）。所以想区分「一张图 / 一段语音 / 一段视频」，只能看字节头。
 *
 * 局限（已写进报告）：`mp4/m4a` 共用 `ftyp` 容器，`isom/mp42` 两种情况都会出现；
 * 这里先按品牌判（`M4A ` / `qt  `），判不出来再看容器里有没有视频轨（`vide` handler），
 * 仍判不出来按视频处理。`moov` 在文件尾的非 faststart 文件（前 64 KiB 没有 handler）会落到默认值。
 */
object ServerMediaSniffer {

    /** 只扫容器头部这么多个字节找 `vide` / `soun` handler。 */
    private const val HEADER_SCAN_BYTES = 64 * 1024

    /** `moov` 在文件尾时，从尾部再扫这么多个字节（≤2 分钟视频的 moov 远小于 2 MiB）。 */
    private const val TAIL_SCAN_BYTES = 2 * 1024 * 1024

    fun mimeOf(bytes: ByteArray?): String? {
        val data = bytes ?: return null
        if (data.isEmpty()) return null
        // JPEG：FF D8 FF（第三字节是 marker 前缀，用来和 MPEG 音频的 FF Ex 区分）。
        if (startsWith(data, 0xFF, 0xD8, 0xFF)) return "image/jpeg"
        if (startsWith(data, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png"
        val head = asciiText(data, 0, 6)
        if (head == "GIF87a" || head == "GIF89a") return RenderMime.GIF
        if (asciiText(data, 0, 4) == "RIFF" && asciiText(data, 8, 4) == "WEBP") return "image/webp"
        if (asciiText(data, 4, 4) == "ftyp") return ftypMime(data)
        if (asciiText(data, 0, 3) == "ID3") return "audio/mpeg"
        if (data.size >= 2) {
            val first = data[0].toInt() and 0xFF
            val second = data[1].toInt() and 0xFF
            // ADTS AAC：FF F1 / FF F9（MPEG-4 容器之外的裸 AAC 流）。
            if (first == 0xFF && (second == 0xF1 || second == 0xF9)) return "audio/aac"
            // MPEG 音频帧同步：FF Ex / FF Fx（layer 位非 00）。
            if (first == 0xFF && (second and 0xE0) == 0xE0) return "audio/mpeg"
        }
        return null
    }

    private fun ftypMime(data: ByteArray): String {
        val brand = asciiText(data, 8, 4).lowercase(Locale.US)
        return when {
            brand.startsWith("m4a") || brand.startsWith("m4b") || brand.startsWith("m4p") -> "audio/m4a"
            brand == "qt  " -> "video/quicktime"
            else -> {
                // isom / mp42 音视频都可能：看容器里有没有视频轨。
                // 录音/录像（MediaRecorder）常常把 `moov` 放在文件尾，只扫头部会漏，
                // 所以头部与尾部各扫一段——否则一条语音会被判成视频（「听不了」）。
                val headStart = minOf(data.size, 12)
                val headEnd = minOf(data.size, headStart + HEADER_SCAN_BYTES)
                val head = data.copyOfRange(headStart, headEnd)
                val tail = data.copyOfRange(maxOf(0, data.size - TAIL_SCAN_BYTES), data.size)
                when {
                    containsAscii(head, "vide") || containsAscii(tail, "vide") -> "video/mp4"
                    containsAscii(head, "soun") || containsAscii(tail, "soun") -> "audio/m4a"
                    else -> "video/mp4"
                }
            }
        }
    }

    private fun asciiText(data: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || length <= 0 || offset + length > data.size) return ""
        return String(data, offset, length, Charsets.US_ASCII)
    }

    private fun startsWith(data: ByteArray, vararg prefix: Int): Boolean {
        if (data.size < prefix.size) return false
        return prefix.indices.all { (data[it].toInt() and 0xFF) == prefix[it] }
    }

    private fun containsAscii(data: ByteArray, token: String): Boolean {
        val needle = token.toByteArray(Charsets.US_ASCII)
        if (needle.isEmpty() || data.size < needle.size) return false
        outer@ for (start in 0..(data.size - needle.size)) {
            for (index in needle.indices) {
                if (data[start + index] != needle[index]) continue@outer
            }
            return true
        }
        return false
    }
}

/**
 * 一个待落盘的服务端附件。
 *
 * @param serverRef 服务端引用（裸 id / `/v1/media/<id>` / 绝对 URL 都认）
 * @param existing 本机上一轮同步留下的附件元数据（用来保留语音/视频/表情的 kind、mime、时长）
 * @param personaId 服务端人格 id（仅供调用方排查/命名，落盘本身不依赖它）
 * @param kind 强制用途（目前只有头像需要：服务端 `avatarUrl` 没有 mime，不能靠嗅探决定目录）
 */
data class ServerAttachmentRef(
    val serverRef: String,
    val existing: MessageAttachment? = null,
    val personaId: String? = null,
    val kind: String? = null,
    /**
     * 已知的服务端内容哈希（来自上传响应 `ServerMedia.sha256` / 服务端备份 `media[].sha256`）。
     *
     * 与下载响应 `ETag`、本机附件记录的 `sha256` 一起参与校验；三者任一非空就对字节重算比对，
     * 不一致 → 该条失败、不落盘、不落库。
     */
    val expectedSha256: String? = null
)

/** 单个附件本地化的结果；[uri] 在失败时仍是服务端引用（界面继续走网络/占位）。 */
data class ServerMediaAttachment(
    val serverMediaId: String,
    val uri: String,
    val mime: String? = null,
    /** 写进 `attachmentsJson` 的 `kind`（STICKER/AUDIO/VIDEO；普通图片为 null）。 */
    val kind: String? = null,
    /** 字节是否真的在本机（false = 下载失败，只保留了服务端引用）。 */
    val local: Boolean = false,
    /** 本机字节的 SHA-256（下载校验通过后记录；旧服务端没给哈希时是本地重算值）。 */
    val sha256: String? = null
)

/** 一批附件的落盘结果：`media` 按规范引用（`/v1/media/<id>`）索引，同 id 只出现一次。 */
data class ServerMediaMaterializeResult(
    val media: Map<String, ServerMediaAttachment> = emptyMap(),
    val downloaded: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    /** 0.15：其中因为 sha256 对不上（而非网络/超限）失败的数量。 */
    val hashMismatches: Int = 0
) {
    /** 按「服务端引用 / 裸 id / 绝对 URL」查本地化结果。 */
    fun attachmentFor(raw: String?): ServerMediaAttachment? = media.attachmentFor(raw)
}

/** 按服务端引用查本地化结果（服务端消息里的引用可能是裸 id，键统一是规范形态）。 */
fun Map<String, ServerMediaAttachment>.attachmentFor(raw: String?): ServerMediaAttachment? {
    if (isEmpty()) return null
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    return this[value]
        ?: ServerMediaRef.canonical(value)?.let { this[it] }
        ?: ServerMediaRef.idOf(value)?.let { this[ServerMediaRef.PREFIX + it] }
}

/**
 * 服务端附件**落盘编排**（纯 JVM：下载器、目录、临时名全部可注入）。
 *
 * 规则（顺序是契约的一部分，单测钉住的重点）：
 *
 * 1. **幂等**：目标文件已在、长度 > 0 就跳过，不再下载；同一 id 在一批里只下一次；
 * 2. **原子落盘**：先写同目录临时文件，再 rename 到目标；失败删临时文件，绝不留半截文件；
 * 3. **失败不抛**：下载失败/超限/写盘失败都只返回「保留服务端引用」的结果并计数，
 *    让上层的事务继续把文字消息落库（一条图片拉不下来不该让整次同步失败）；
 * 4. **取消照常上抛**：`CancellationException` 不被吞掉。
 */
class ServerMediaMaterializer(
    private val directories: ServerMediaDirectories,
    /** 落盘临时文件名的一部分；放在 [download] 之前，让测试可以用末尾 lambda 传下载器。 */
    private val newToken: () -> String = { UUID.randomUUID().toString() },
    /**
     * 下载器：返回字节 + 服务端声明的哈希（`ETag` / 先前的上传响应）。
     * 返回 null = 下载失败。旧测试/旧调用方可以只塞 `ServerMediaDownload(bytes)`。
     */
    private val download: suspend (ref: String, maxBytes: Int) -> ServerMediaDownload?
) {

    suspend fun materialize(refs: List<ServerAttachmentRef>): ServerMediaMaterializeResult {
        if (refs.isEmpty()) return ServerMediaMaterializeResult()
        val media = LinkedHashMap<String, ServerMediaAttachment>(refs.size)
        val seen = HashMap<String, LocalizedItem>(refs.size)
        // 幂等扫描一次目录（不是每个附件扫一遍）：聊天图片目录可能有几千个文件。
        val localIndex = localFileIndex()
        var downloaded = 0
        var skipped = 0
        var failed = 0
        var hashMismatches = 0

        refs.forEach { ref ->
            val raw = ref.serverRef.trim()
            val id = ServerMediaRef.idOf(raw) ?: return@forEach
            val key = ServerMediaRef.PREFIX + id
            // 同一个 id 在一批里出现多次只处理一次（也只为它计一次数）。
            val known = seen[id]
            if (known != null) {
                media[key] = known.attachment
                return@forEach
            }
            val outcome = runCatching { localize(id, raw, ref, localIndex[id]) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    LocalizedItem(failedAttachment(id), LocalizeStatus.FAILED)
                }
            seen[id] = outcome
            media[key] = outcome.attachment
            when (outcome.status) {
                LocalizeStatus.DOWNLOADED -> downloaded++
                LocalizeStatus.SKIPPED -> skipped++
                LocalizeStatus.FAILED -> {
                    failed++
                    if (outcome.hashMismatch) hashMismatches++
                }
            }
        }
        return ServerMediaMaterializeResult(
            media = media,
            downloaded = downloaded,
            skipped = skipped,
            failed = failed,
            hashMismatches = hashMismatches
        )
    }

    /**
     * 单个附件：本地已有 → 校验/跳过；否则下载 → 校验哈希 → 判定类型 → 原子落盘。
     *
     * 0.15 的三道新闸门（顺序是契约的一部分）：
     * 1. **本机记录校验**：附件里记着 `sha256` 而本地文件重算对不上 → 删掉坏副本、重新下载
     *    （下载幂等 + 自我修复）；没有记录（旧数据）→ 直接复用，行为与 0.14 一致。
     * 2. **下载哈希校验**：服务端 `ETag` / `expectedSha256` / 本机记录，三者任一非空就比对；
     *    不一致 → 删临时文件（此处尚未写目标文件）、记失败、**不落库**。
     * 3. **类型判定拒绝猜测**：元数据与字节 magic 都认不出用途时记失败，不再兜底成普通图片。
     */
    private suspend fun localize(
        id: String,
        raw: String,
        ref: ServerAttachmentRef,
        indexedLocalFile: File?
    ): LocalizedItem {
        val existing = ref.existing
        val existingMime = existing?.mime?.trim()?.takeIf { it.isNotEmpty() }
        val forcedKind = ref.kind?.takeIf { ServerMediaKind.isKnown(it) }
        // 期望哈希：调用方明确给的优先，其次附件里记录的（上一轮下载通过校验的那份）。
        val expectedSha = MediaHashes.normalize(ref.expectedSha256)
            ?: MediaHashes.normalize(existing?.sha256)

        // 1. 已经在本机：先按记录的 sha256 复检，坏了就删掉重下。
        val local = indexedLocalFile ?: existingLocalFile(existing)
        if (local != null && (expectedSha == null || matchesLocalFile(local, expectedSha))) {
            val kind = forcedKind ?: ServerMediaKind.detect(existing, null)
            return LocalizedItem(
                attachment = ServerMediaAttachment(
                    serverMediaId = id,
                    uri = fileUri(local),
                    mime = ServerMediaKind.mimeFor(kind, existingMime, null, existing?.uri),
                    kind = ServerMediaKind.attachmentKind(kind),
                    local = true,
                    sha256 = expectedSha ?: hashOfFile(local)
                ),
                status = LocalizeStatus.SKIPPED
            )
        }
        if (local != null) {
            // 记录的哈希与文件对不上：本地副本已损坏。删掉它（归属守卫已在 existingLocalFile /
            // 目录索引里过过），让下面走一次干净下载，避免界面一直渲染一张坏图。
            deleteLocalCopy(local)
        }

        // 2. 下载：用途未知时按最大上限一次取回，再按真实用途复检（见 maxBytes 的注释）。
        val hintKind = forcedKind ?: ServerMediaKind.fromAttachment(existing)
        val response = download(raw, ServerMediaKind.maxBytes(hintKind))
            ?: return LocalizedItem(failedAttachment(id, existingMime, hintKind), LocalizeStatus.FAILED)
        val bytes = response.bytes
        if (bytes.isEmpty()) {
            return LocalizedItem(failedAttachment(id, existingMime, hintKind), LocalizeStatus.FAILED)
        }

        // 2.1 哈希校验：服务端声明的（ETag）与本机期望值都必须与字节一致。
        // 不一致时**还没有写任何文件**（临时文件都不建），所以不存在需要清理的半截副本；
        // 这条按失败计数，保留服务端引用让界面继续走网络/占位，下次同步会重试。
        val computedSha = MediaHashes.sha256Hex(bytes)
        if (!MediaHashes.matches(response.sha256, computedSha) ||
            !MediaHashes.matches(expectedSha, computedSha)
        ) {
            return LocalizedItem(
                attachment = failedAttachment(id, existingMime, hintKind),
                status = LocalizeStatus.FAILED,
                hashMismatch = true
            )
        }

        // 3. 判定用途与 mime（元数据优先，其次字节 magic，再次扩展名）。
        // 0.15：认不出来就拒绝，不再默认成 CHAT_IMAGE（旧行为会把未知格式写成一张坏图）。
        val sniffed = ServerMediaSniffer.mimeOf(bytes)
        val kind = forcedKind ?: ServerMediaKind.classify(existing, sniffed)
            ?: return LocalizedItem(failedAttachment(id, existingMime, hintKind), LocalizeStatus.FAILED)
        val mime = ServerMediaKind.mimeFor(kind, existingMime, sniffed, existing?.uri)
        // 类型声明必须自洽：kind 与 mime 不同族（例如 AUDIO + image/jpeg）也是拒绝。
        if (MediaTypes.rejection(kind, mime) != null) {
            return LocalizedItem(failedAttachment(id, mime, kind), LocalizeStatus.FAILED)
        }
        if (bytes.size > ServerMediaKind.maxBytes(kind)) {
            // 未知用途按 64 MiB 取回，超过真实用途上限（例如 3 MiB 的“图片”）就不落盘。
            return LocalizedItem(failedAttachment(id, mime, kind), LocalizeStatus.FAILED)
        }

        // 4. 原子落盘（目录归属 + 文件名安全守卫都在 targetFor 里）。
        val target = ServerMediaKind.targetFor(id, kind, mime, directories)
            ?: return LocalizedItem(failedAttachment(id, mime, kind), LocalizeStatus.FAILED)
        if (!writeAtomically(target, bytes)) {
            return LocalizedItem(failedAttachment(id, mime, kind), LocalizeStatus.FAILED)
        }
        // 落盘后再复检一次：rename 之后的文件必须与刚校验过的字节等长且同哈希。
        // 失败（磁盘截断/别的进程写坏）就删掉目标文件，绝不让一个坏副本进库。
        if (!fileMatches(target.file, computedSha, bytes.size)) {
            runCatching { target.file.delete() }
            return LocalizedItem(
                attachment = failedAttachment(id, mime, kind),
                status = LocalizeStatus.FAILED,
                hashMismatch = true
            )
        }
        return LocalizedItem(
            attachment = ServerMediaAttachment(
                serverMediaId = id,
                uri = fileUri(target.file),
                mime = mime,
                kind = ServerMediaKind.attachmentKind(kind),
                local = true,
                sha256 = computedSha
            ),
            status = LocalizeStatus.DOWNLOADED
        )
    }

    /** 本地文件是否与记录的哈希一致；读不出来/算不出来一律当不一致。 */
    private fun matchesLocalFile(file: File, expectedSha: String): Boolean {
        val actual = hashOfFile(file) ?: return false
        return MediaHashes.matches(expectedSha, actual)
    }

    private fun hashOfFile(file: File): String? =
        runCatching { MediaHashes.sha256Hex(file.readBytes()) }.getOrNull()

    private fun fileMatches(file: File, expectedSha: String, expectedBytes: Int): Boolean {
        if (!file.isFile || file.length() != expectedBytes.toLong()) return false
        return MediaHashes.matches(expectedSha, hashOfFile(file))
    }

    /** 删除一个本机坏副本：再加一次目录归属守卫，绝不删到媒体目录外面去。 */
    private fun deleteLocalCopy(file: File) {
        if (!MediaOwnership.isWithin(file, directories.all())) return
        runCatching { file.delete() }
    }

    /**
     * 本机媒体 id → 已有文件（一次落盘流程只扫一遍目录）。
     *
     * 按「目录里扫一遍」而不是拼死一个文件名：同一 id 可能由旧版本按别的命名落过盘
     * （播放缓存以前是 `<id>.m4a`），命名变了不该让文件再下一遍。
     */
    private fun localFileIndex(): Map<String, File> {
        val index = HashMap<String, File>()
        directories.all().forEach { directory ->
            val files = runCatching { directory.listFiles() }.getOrNull().orEmpty()
            files.forEach fileLoop@{ file ->
                if (!file.isFile || file.length() <= 0L) return@fileLoop
                val id = idOfFileName(file.name) ?: return@fileLoop
                index.putIfAbsent(id, file)
            }
        }
        return index
    }

    /** 文件名 → 媒体 id（`chat-<id>.jpg`、`voice-<id>.m4a`、旧缓存 `<id>.m4a`）。 */
    private fun idOfFileName(fileName: String): String? {
        if (fileName.length < 2) return null
        // 临时文件不算（写一半的 `.tmp-` 文件长度也可能是正数）。
        if (fileName.contains(".tmp-")) return null
        val stem = fileName.substringBefore('.')
        if (stem.isEmpty()) return null
        FILE_PREFIXES.forEach { prefix ->
            if (stem.startsWith(prefix)) return stem.removePrefix(prefix).takeIf { it.isNotEmpty() }
        }
        return stem
    }

    /** 附件自带的 `file://` 路径：仍要走归属守卫，只认我们自己的媒体目录。 */
    private fun existingLocalFile(existing: MessageAttachment?): File? {
        val raw = existing?.uri?.trim().orEmpty()
        if (raw.isEmpty() || ServerMediaRef.isMediaRef(raw)) return null
        val file = fileOf(raw) ?: return null
        if (!MediaOwnership.isWithin(file, directories.all())) return null
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    /** 写临时文件再 rename；同名目标已存在（并发写）时复用，不留临时文件。 */
    private fun writeAtomically(target: ServerMediaTarget, bytes: ByteArray): Boolean {
        val directory = target.directory
        val ready = runCatching { (directory.isDirectory || directory.mkdirs()) && directory.isDirectory }
            .getOrDefault(false)
        if (!ready) return false
        if (!BackupAvatarPath.isSafeName(target.fileName)) return false
        if (!MediaOwnership.isWithin(target.file, listOf(directory))) return false
        if (target.file.isFile && target.file.length() > 0L) return true
        val temp = File(directory, target.fileName + ".tmp-" + newToken())
        val written = runCatching {
            temp.writeBytes(bytes)
            if (temp.length() != bytes.size.toLong()) return@runCatching false
            if (temp.renameTo(target.file)) return@runCatching true
            target.file.isFile && target.file.length() > 0L
        }.getOrDefault(false)
        runCatching { if (temp.exists()) temp.delete() }
        return written
    }

    /**
     * 失败占位：`uri` 保持服务端引用，界面继续走网络加载/占位。
     *
     * [kind] 是已经判定出的用途（语音/视频/表情），带过去让界面至少知道该显示哪种占位，
     * 而不是把一条语音画成一张加载不出来的图片。
     */
    private fun failedAttachment(
        id: String,
        mime: String? = null,
        kind: String? = null
    ): ServerMediaAttachment =
        ServerMediaAttachment(
            serverMediaId = id,
            uri = ServerMediaRef.PREFIX + id,
            mime = mime,
            kind = kind?.let { ServerMediaKind.attachmentKind(it) },
            local = false
        )

    private data class LocalizedItem(
        val attachment: ServerMediaAttachment,
        val status: LocalizeStatus,
        /** 这次失败是不是「sha256 对不上」（与网络失败分开计数，便于诊断）。 */
        val hashMismatch: Boolean = false
    )

    private enum class LocalizeStatus { DOWNLOADED, SKIPPED, FAILED }

    private companion object {
        /** 我们自己的媒体文件名前缀（与 [ServerMediaKind.filePrefix] 一致）。 */
        val FILE_PREFIXES = listOf("chat-", "avatar-", "sticker-", "voice-", "video-")
    }
}

/**
 * 服务端媒体**落盘**的生产入口（下载 → 持久目录）。
 *
 * 与 [ServerMediaCache]（`cacheDir/av` 播放缓存）的分工：
 * - 这里落的是**持久副本**（`files/media|avatars|stickers|audio|video`），同步/事件拉回消息时调用，
 *   数据库里从此写本地 `file://`，断网、重启、换进程都还在；
 * - 播放缓存只是「MediaPlayer 不能带 Authorization」的临时中转，随时可能被系统清掉。
 *
 * 所有方法都不抛（取消除外）：调用方是同步/事件落库链路，媒体失败不能阻断文字同步。
 */
@Singleton
class ServerMediaStore @Inject constructor(
    @ApplicationContext context: Context,
    private val mode: ServerModeRepository
) {

    private val directories = ServerMediaDirectories(
        chatImages = MediaStorage.mediaDirectory(context),
        avatars = MediaStorage.avatarDirectory(context),
        stickers = MediaStorage.stickerDirectory(context),
        audio = MediaStorage.audioDirectory(context),
        video = MediaStorage.videoDirectory(context)
    )

    /** 生产目录 + 生产下载器（`ServerApi.downloadMedia`，带 Bearer）。 */
    fun materializer(): ServerMediaMaterializer = materializer(directories)

    /** 自定义目录的同一套落盘实现（播放缓存传 `cacheDir/av`）。 */
    fun materializer(directories: ServerMediaDirectories): ServerMediaMaterializer =
        ServerMediaMaterializer(
            directories = directories,
            download = { ref, maxBytes -> downloadOnce(ref, maxBytes) }
        )

    /**
     * 一批服务端附件落盘（同步/事件在**事务之前**调用）。
     *
     * 同一 id 在批内只下一次；失败项保留服务端引用并计数，绝不抛给同步层。
     */
    suspend fun materialize(refs: List<ServerAttachmentRef>): ServerMediaMaterializeResult =
        withContext(Dispatchers.IO) {
            if (refs.isEmpty()) return@withContext ServerMediaMaterializeResult()
            materializer().materialize(refs)
        }

    /**
     * 单条附件「确保本地有文件」，供播放/发送前调用。
     *
     * - 本地文件已存在：原样返回（最多把 `serverMediaId` 归一成规范值）；
     * - uri 是服务端引用、或只有 `serverMediaId` 而本地文件没了：下载后返回 `file://`；
     * - 失败：原样返回（调用方继续走网络或提示），不抛。
     *
     * @param directories 落盘目录；为 null 时用持久目录（同步/发送前）。播放缓存传
     *        `cacheDir/av`，复用同一套「幂等 + 原子落盘」实现，但保持缓存语义。
     */
    suspend fun ensureLocal(
        attachment: MessageAttachment,
        directories: ServerMediaDirectories? = null
    ): MessageAttachment = withContext(Dispatchers.IO) {
        val ref = serverRefOf(attachment) ?: return@withContext attachment
        val materializer = if (directories == null) materializer() else materializer(directories)
        val result = materializer.materialize(
            listOf(ServerAttachmentRef(serverRef = ref, existing = attachment))
        )
        val entry = result.attachmentFor(ref) ?: return@withContext attachment
        if (!entry.local) return@withContext attachment
        attachment.copy(
            uri = entry.uri,
            mime = entry.mime?.takeIf { it.isNotBlank() } ?: attachment.mime,
            kind = entry.kind ?: attachment.kind,
            serverMediaId = entry.serverMediaId,
            // 0.15：把校验过的内容哈希记在附件上，下一轮命中本地文件时可以复检。
            sha256 = MediaHashes.normalize(entry.sha256) ?: attachment.sha256
        )
    }

    /**
     * 人格头像「确保有本地文件」（`ServerSnapshot.personas[].avatarUrl` 可能是 `/v1/media/<id>`）。
     *
     * @return 本机 `file://` 路径；不是媒体引用或落盘失败时返回原值（调用方保留服务端引用，
     *         界面继续走带鉴权的网络加载），不抛。
     */
    suspend fun ensureLocalAvatar(personaId: String?, url: String?): String? =
        withContext(Dispatchers.IO) {
            val raw = url?.trim().orEmpty()
            if (raw.isEmpty()) return@withContext url
            if (!ServerMediaRef.isMediaRef(raw)) return@withContext raw
            val result = materializer().materialize(
                listOf(
                    ServerAttachmentRef(
                        serverRef = raw,
                        personaId = personaId,
                        // 服务端头像没有 mime：靠嗅探只能判成图片，必须强制落到 files/avatars。
                        kind = ServerMediaKind.PERSONA_AVATAR
                    )
                )
            )
            val entry = result.attachmentFor(raw) ?: return@withContext raw
            if (entry.local) entry.uri else raw
        }

    /** 下载一次：字节 + 服务端 `ETag`（= 内容 sha256）。失败返回 null，不抛。 */
    private suspend fun downloadOnce(ref: String, maxBytes: Int): ServerMediaDownload? {
        val api = runCatching { mode.api() }.getOrNull() ?: return null
        return try {
            api.downloadMediaWithMeta(ref, maxBytes)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            null
        }
    }

    private fun serverRefOf(attachment: MessageAttachment): String? =
        attachment.serverMediaId?.trim()?.takeIf { ServerMediaRef.isValidId(it) }
            ?.let { ServerMediaRef.PREFIX + it }
            ?: attachment.uri.trim().takeIf { ServerMediaRef.isMediaRef(it) }
}

/** `file://` 引用（纯 JVM，与 `BackupMediaRestorer` 同一套写法：`file:///path`）。 */
internal fun fileUri(file: File): String = "file://" + file.toURI().toString().removePrefix("file:")

/** `file://` 引用 → File（纯 JVM 版 `MediaStorage.fileOf`）。 */
internal fun fileOf(uriString: String): File? = runCatching {
    val uri = java.net.URI(uriString)
    if (uri.scheme == "file") File(uri) else null
}.getOrNull()

/**
 * 把服务端消息的附件引用整理成「待落盘」清单（纯规则，sink 与测试共用）。
 *
 * @param attachments 服务端 `ServerMessage.attachments`（每项一个 `/v1/media/<id>`）
 * @param localJson 本机同一消息已有的 `attachmentsJson`（按 index 带过来 kind/mime/duration）
 */
fun serverAttachmentRefs(
    attachments: List<String>,
    localJson: String?,
    personaId: String? = null
): List<ServerAttachmentRef> {
    if (attachments.isEmpty()) return emptyList()
    val local = AttachmentsCodec.decode(localJson)
    return attachments.mapIndexedNotNull { index, ref ->
        if (!ServerMediaRef.isMediaRef(ref)) return@mapIndexedNotNull null
        ServerAttachmentRef(
            serverRef = ref,
            existing = local.getOrNull(index),
            personaId = personaId
        )
    }
}
