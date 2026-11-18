package com.yap.app.data.remote

import android.content.Context
import com.yap.app.data.local.MessageEntity
import com.yap.app.domain.AttachmentsCodec
import com.yap.app.domain.MessageAttachment
import com.yap.app.util.MediaStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 历史媒体迁移的逐项预算（`spec/sync-consent/README.md` §2）。
 *
 * 图片先压缩再上传（≤800 KiB，和服务端聊天的 1600px/800KiB 同一套策略），
 * 音频/视频不重编码——用户已有的文件直接上传（≤8 MiB / ≤64 MiB，与服务端上限一致）。
 */
data class ServerHistoryMediaLimits(
    /** 压缩后的图片字节上限（`BackupMediaLimits.MAX_CHAT_OUTPUT_BYTES`）。 */
    val imageBytes: Int = 800 * 1024,
    val audioBytes: Int = ServerMediaLimits.MAX_AUDIO_BYTES,
    val videoBytes: Int = ServerMediaLimits.MAX_VIDEO_BYTES,
    /** 表情包不重编码（GIF 一压动画就没了），只挡服务端图片上限。 */
    val stickerBytes: Int = ServerMediaLimits.MAX_UPLOAD_BYTES
)

/** 上传/复用/失败：逐项处理的单个附件结果（失败不阻断其它项）。 */
sealed interface HistoryMediaItemResult {
    /** 这次真的上传了（服务端按 sha256 去重，返回的 id 就是内容哈希）。 */
    data class Uploaded(val uri: String, val serverRef: String, val kind: String) : HistoryMediaItemResult

    /** 本次运行里同一文件已经传过（或本来就是服务端引用），不重复上传。 */
    data class Reused(val uri: String, val serverRef: String, val kind: String) : HistoryMediaItemResult

    /** 读不到/压缩失败/超限/上传失败：附件不进服务端副本，本地保留，结束后报告数量。 */
    data class Failed(val uri: String, val kind: String, val reason: String) : HistoryMediaItemResult
}

/** 历史媒体进度：`第 processed/total 项` + 成功/失败计数（设置页一行字的数据源）。 */
data class HistoryMediaProgress(
    val total: Int = 0,
    val processed: Int = 0,
    val uploaded: Int = 0,
    val failed: Int = 0
) {
    val done: Boolean get() = total > 0 && processed >= total
}

/** 一批消息的媒体处理结果：改写后的消息（只带真正上传成功的附件）+ 计数。 */
data class HistoryMediaBatchResult(
    val messages: List<MessageEntity>,
    val uploaded: Int,
    val failed: Int,
    val skipped: Int,
    val failures: List<HistoryMediaItemResult.Failed> = emptyList()
)

/**
 * 历史媒体逐项上传的**编排**（纯 JVM：上传接口、读文件、压缩器全部注入）。
 *
 * 顺序是契约的一部分：
 * 1. 逐条消息、逐项附件处理（同一文件在一次运行里只传一次）；
 * 2. 图片：读字节 → 压缩到 ≤800 KiB → `POST /v1/media?kind=CHAT_IMAGE`；
 *    音频/视频：读字节 → 校验 8/64 MiB → `POST /v1/media?kind=AUDIO|VIDEO`；
 * 3. 成功的附件把 uri 改写成 `/v1/media/<id>`，再交给 import（**先上传后 import**，
 *    否则服务端导入端会因为附件不存在整批 400）；
 * 4. 失败项只从「服务端副本」里去掉，本地消息不动，计数交给上层提示。
 *
 * 取消：本类不吞 [CancellationException]，直接向上抛；已上传的媒体留在服务端
 * （服务端按 sha256 复用，下次重试同一内容不会产生第二份媒体）。
 */
class ServerHistoryMediaUploader(
    private val api: ServerMediaApi,
    private val readBytes: suspend (String) -> ByteArray?,
    private val compressImage: (ByteArray, String) -> CompressedImage?,
    private val limits: ServerHistoryMediaLimits = ServerHistoryMediaLimits()
) {

    /** 本次运行内的「本地文件 → 服务端引用」缓存：同一张图被多条消息引用时只上传一次。 */
    private val uploadedRefs = mutableMapOf<String, String>()

    /** 附件 → 服务端媒体用途（与服务端 `MediaKind` 逐字一致）。 */
    fun kindOf(attachment: MessageAttachment): String = when {
        isSticker(attachment) -> ServerMediaKinds.STICKER
        attachment.mime.startsWith("audio/", ignoreCase = true) -> ServerMediaKinds.AUDIO
        attachment.mime.startsWith("video/", ignoreCase = true) -> ServerMediaKinds.VIDEO
        else -> ServerMediaKinds.CHAT_IMAGE
    }

    /**
     * 表情包：显式 `kind=STICKER`，或 mime 就是 GIF。
     *
     * 历史表情必须按 `kind=STICKER` 上传且**不重编码**：聊天图片那条链路会转成 JPEG，
     * 把 GIF 的动画压没（与服务端表情的渲染约定也不一致）。
     */
    fun isSticker(attachment: MessageAttachment): Boolean =
        attachment.kind.equals(ServerMediaKinds.STICKER, ignoreCase = true) ||
            attachment.mime.equals("image/gif", ignoreCase = true)

    /**
     * 处理一批消息的附件并返回**可用于 import** 的消息副本。
     *
     * @param progress 本次运行的累计进度（`total` 由上层预扫描给出，`processed` 接着数）
     * @param onProgress 每处理完一项回调一次（进度/取消由上层负责）
     */
    suspend fun prepare(
        messages: List<MessageEntity>,
        personaId: String?,
        progress: HistoryMediaProgress = HistoryMediaProgress(),
        onProgress: suspend (HistoryMediaProgress) -> Unit = {}
    ): HistoryMediaBatchResult {
        var current = progress
        var uploaded = 0
        var failed = 0
        var skipped = 0
        val failures = mutableListOf<HistoryMediaItemResult.Failed>()
        val rewritten = ArrayList<MessageEntity>(messages.size)

        for (message in messages) {
            val attachments = AttachmentsCodec.decode(message.attachmentsJson)
            if (attachments.isEmpty()) {
                rewritten += message
                continue
            }
            val kept = ArrayList<MessageAttachment>(attachments.size)
            var changed = false
            for (attachment in attachments) {
                val uri = attachment.uri.trim()
                if (uri.isEmpty()) {
                    failed++
                    current = current.copy(processed = current.processed + 1, failed = current.failed + 1)
                    failures += HistoryMediaItemResult.Failed(uri, kindOf(attachment), "media_uri_empty")
                    onProgress(current)
                    continue
                }
                // 本来就是服务端引用（换过服务端/上次增量同步拉回来的）：直接保留，不算失败。
                if (ServerMediaRef.isMediaRef(uri)) {
                    kept += attachment
                    skipped++
                    continue
                }
                val cachedRef = uploadedRefs[uri]
                if (cachedRef != null) {
                    kept += attachment.copy(uri = cachedRef)
                    changed = true
                    skipped++
                    current = current.copy(processed = current.processed + 1)
                    onProgress(current)
                    continue
                }
                val outcome = uploadItem(uri, attachment, personaId)
                when (outcome) {
                    is HistoryMediaItemResult.Uploaded -> {
                        uploadedRefs[uri] = outcome.serverRef
                        kept += attachment.copy(uri = outcome.serverRef)
                        changed = true
                        uploaded++
                        current = current.copy(
                            processed = current.processed + 1,
                            uploaded = current.uploaded + 1
                        )
                    }
                    is HistoryMediaItemResult.Reused -> {
                        uploadedRefs[uri] = outcome.serverRef
                        kept += attachment.copy(uri = outcome.serverRef)
                        changed = true
                        skipped++
                        current = current.copy(processed = current.processed + 1)
                    }
                    is HistoryMediaItemResult.Failed -> {
                        // 只从服务端副本里去掉；本地消息保留 file://，用户还能在本地模式看到它。
                        // changed 必须置真：否则这份「服务端副本」还带着 file:// 附件，import 会整批 400。
                        changed = true
                        failed++
                        failures += outcome
                        current = current.copy(
                            processed = current.processed + 1,
                            failed = current.failed + 1
                        )
                    }
                }
                onProgress(current)
            }
            rewritten += if (changed) {
                message.copy(attachmentsJson = AttachmentsCodec.encode(kept))
            } else {
                message
            }
        }
        return HistoryMediaBatchResult(
            messages = rewritten,
            uploaded = uploaded,
            failed = failed,
            skipped = skipped,
            failures = failures
        )
    }

    /** 单项上传：任何失败都变成 [HistoryMediaItemResult.Failed]，不抛出（取消除外）。 */
    private suspend fun uploadItem(
        uri: String,
        attachment: MessageAttachment,
        personaId: String?
    ): HistoryMediaItemResult {
        val mime = attachment.mime.trim().ifEmpty { "image/jpeg" }
        val kind = kindOf(attachment)
        val raw = try {
            readBytes(uri)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            null
        } ?: return HistoryMediaItemResult.Failed(uri, kind, "media_read_failed")

        val bytes: ByteArray
        val uploadMime: String
        when (kind) {
            ServerMediaKinds.AUDIO -> {
                if (raw.size > limits.audioBytes) {
                    return HistoryMediaItemResult.Failed(uri, kind, "media_too_large")
                }
                bytes = raw
                uploadMime = mime
            }
            ServerMediaKinds.VIDEO -> {
                if (raw.size > limits.videoBytes) {
                    return HistoryMediaItemResult.Failed(uri, kind, "media_too_large")
                }
                bytes = raw
                uploadMime = mime
            }
            ServerMediaKinds.STICKER -> {
                if (raw.size > limits.stickerBytes) {
                    return HistoryMediaItemResult.Failed(uri, kind, "media_too_large")
                }
                bytes = raw
                uploadMime = if (mime.startsWith("image/")) mime else "image/gif"
            }
            else -> {
                val compressed = try {
                    compressImage(raw, mime)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    null
                } ?: return HistoryMediaItemResult.Failed(uri, kind, "media_compress_failed")
                if (compressed.bytes.isEmpty()) {
                    return HistoryMediaItemResult.Failed(uri, kind, "media_compress_failed")
                }
                if (compressed.bytes.size > limits.imageBytes ||
                    compressed.bytes.size > ServerMediaLimits.MAX_UPLOAD_BYTES
                ) {
                    return HistoryMediaItemResult.Failed(uri, kind, "media_too_large")
                }
                bytes = compressed.bytes
                uploadMime = compressed.mime
            }
        }

        val media = try {
            api.uploadMedia(bytes = bytes, mime = uploadMime, kind = kind, personaId = personaId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            val reason = (error as? ServerApiException)?.code ?: "media_upload_failed"
            return HistoryMediaItemResult.Failed(uri, kind, reason)
        }
        val ref = ServerMediaRef.canonical(media.url)
            ?: ServerMediaRef.canonical(media.id)
            ?: return HistoryMediaItemResult.Failed(uri, kind, "invalid_response")
        return HistoryMediaItemResult.Uploaded(uri, ref, kind)
    }
}

/**
 * bootstrap 需要的历史媒体能力（默认无实现：单测/无媒体环境直接跳过，不影响文字同步）。
 */
interface ServerHistoryMediaPort {
    suspend fun prepare(
        messages: List<MessageEntity>,
        personaId: String?,
        progress: HistoryMediaProgress,
        onProgress: suspend (HistoryMediaProgress) -> Unit
    ): HistoryMediaBatchResult
}

/**
 * 生产实现：`MediaStorage.ownedFile` 守卫 + 聊天图压缩 + `ServerApi.uploadMedia`。
 *
 * 和 [ServerChatMediaSender] 一样只读 App 自己媒体目录里的文件（`attachmentsJson` 是
 * 「数据即代码」的输入面，不能把数据库/任意沙盒文件当图片上传）。
 */
@Singleton
class ServerHistoryMediaSender @Inject constructor(
    private val mode: ServerModeRepository,
    @ApplicationContext private val context: Context
) : ServerHistoryMediaPort {

    override suspend fun prepare(
        messages: List<MessageEntity>,
        personaId: String?,
        progress: HistoryMediaProgress,
        onProgress: suspend (HistoryMediaProgress) -> Unit
    ): HistoryMediaBatchResult {
        val api = mode.api() ?: throw ServerApiException(
            "server mode is not available",
            code = "server_unavailable"
        )
        val uploader = ServerHistoryMediaUploader(
            api = api,
            readBytes = { uri -> readOwnedBytes(uri) },
            compressImage = { bytes, mime ->
                MediaStorage.compressChatImage(bytes, mime)
                    ?.let { CompressedImage(it.bytes, it.mime) }
            }
        )
        return withContext(Dispatchers.IO) {
            uploader.prepare(messages, personaId, progress, onProgress)
        }
    }

    /**
     * 只读 App 自己的媒体目录（聊天媒体 + 表情目录）里的现有文件；超过视频上限的字节不读进内存。
     *
     * 表情文件在 `files/stickers`（与聊天图不在同一目录）：历史表情迁移也要能读到它，
     * 但两个目录的归属判定都走 [MediaStorage.ownedFile]（canonical path 守卫）。
     */
    private fun readOwnedBytes(uri: String): ByteArray? {
        val file = MediaStorage.ownedFile(uri, MediaStorage.mediaDirectory(context))
            ?: MediaStorage.ownedFile(uri, MediaStorage.stickerDirectory(context))
            ?: return null
        if (file.length() <= 0L || file.length() > ServerMediaLimits.MAX_VIDEO_BYTES) return null
        return runCatching { file.readBytes() }.getOrNull()
    }
}
