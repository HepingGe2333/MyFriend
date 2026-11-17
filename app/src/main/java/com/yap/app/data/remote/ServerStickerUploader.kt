package com.yap.app.data.remote

import com.yap.app.domain.MessageAttachment
import com.yap.app.domain.MediaOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 服务端模式下的**表情包上传**（`spec/stickers/README.md`「服务端模式存服务端媒体」）。
 *
 * 单独一个文件而不是塞进 `ServerChatMediaSender`：聊天图片那条链路要做「JPEG 重编码压缩」，
 * 而表情包**已经在自己的管线里压到 ≤512 KB 的 GIF** 了，再压一次只会把动画压没。
 * 两条上传的唯一共同点是「都走 `POST /v1/media`」，其余语义（kind、是否重编码、上限）都不同。
 *
 * 硬约束与聊天图片一致：
 * - **只读 App 自己表情目录里的文件**（附件路径是「数据即代码」的输入面）；
 * - 上传前再挡一次字节上限（压缩器是外部实现，不能只信它）；
 * - 任何一张失败整条消息失败：不能默默少发一张表情。
 */
class ServerStickerUploader(
    private val api: ServerMediaApi,
    private val readBytes: suspend (String) -> ByteArray?,
    private val maxUploadBytes: Int = ServerMediaLimits.MAX_UPLOAD_BYTES
) {

    suspend fun uploadStickers(attachments: List<MessageAttachment>, personaId: String?): Result<List<String>> =
        runCatching {
            attachments.map { attachment ->
                val raw = readBytes(attachment.uri) ?: throw ServerApiException(
                    "sticker is not readable",
                    code = "media_read_failed"
                )
                if (raw.isEmpty()) throw ServerApiException("sticker is empty", code = "media_read_failed")
                if (raw.size > maxUploadBytes) {
                    throw ServerApiException(
                        "sticker exceeds the $maxUploadBytes byte upload limit",
                        code = "media_too_large"
                    )
                }
                val media = api.uploadMedia(
                    bytes = raw,
                    mime = STICKER_MIME,
                    kind = KIND_STICKER,
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

    companion object {
        /** 与服务端 `MediaKind.STICKER` 逐字一致（服务端已支持，见 `agentd` 的 `MediaRoutes`）。 */
        const val KIND_STICKER = "STICKER"
        const val STICKER_MIME = "image/gif"
    }
}

/**
 * 表情文件归属守卫：只允许读 `files/stickers` 里的文件。
 *
 * 与 [ServerChatMediaSender] 里的同名逻辑分开写是有意的：两个目录的归属语义不同
 * （表情目录故意不在 `MediaStorage.ownedRoots` 里），共用一份会让人以为它们是同一个东西。
 */
object StickerFileReader {

    /** 单张上限 8 MiB：与服务端媒体的「读入上限」一致（表情本身 ≤512 KB，这里只是兜底）。 */
    private const val MAX_READ_BYTES = 8L * 1024 * 1024

    suspend fun read(uri: String, stickerDirectory: java.io.File): ByteArray? = withContext(Dispatchers.IO) {
        if (!uri.startsWith("file://")) return@withContext null
        val file = runCatching { java.io.File(java.net.URI(uri)) }.getOrNull() ?: return@withContext null
        if (!MediaOwnership.isWithin(file, listOf(stickerDirectory))) return@withContext null
        if (!file.isFile || !file.canRead()) return@withContext null
        if (file.length() <= 0L || file.length() > MAX_READ_BYTES) return@withContext null
        runCatching { file.readBytes() }.getOrNull()
    }
}
