package com.yap.app.data.remote

import android.content.Context
import com.yap.app.domain.MediaOwnership
import com.yap.app.domain.MessageAttachment
import com.yap.app.util.MediaStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端模式的图片上传入口：把「本地私有的图片文件」变成服务端媒体引用。
 *
 * 两条硬规矩：
 * 1. **只读 App 自己媒体目录里的文件**。附件路径来自数据库/Room，是「数据即代码」的输入面；
 *    如果哪天被污染成 `file:///data/.../databases/yap.db`，这里不能把数据库当图片传上云。
 *    归属判定复用 [MediaOwnership.isWithin]（与删除/备份同一套 canonical path 语义）。
 * 2. **先压缩再上传**：聊天图/头像都走 `MediaStorage.compress*`，服务端收到的字节一定已经
 *    落在 1600px/800KB、512px/400KB 的预算里；上传编排还会再做一次 2 MiB 硬上限检查。
 *
 * 读取/压缩/上传三段都可注入（见 [ServerChatMediaUploader]）；这里只是生产实现。
 */
@Singleton
class ServerChatMediaSender @Inject constructor(
    private val mode: ServerModeRepository,
    @ApplicationContext private val context: Context
) {

    private val compressor = ChatImageCompressor { bytes, mime ->
        MediaStorage.compressChatImage(bytes, mime)?.let { compressed ->
            CompressedImage(compressed.bytes, compressed.mime)
        }
    }

    /** 把一条消息里的图片全部上传；任何一张失败整条消息失败（不返回半截附件）。 */
    suspend fun uploadChatImages(
        attachments: List<MessageAttachment>,
        personaId: String?
    ): Result<List<String>> {
        if (attachments.isEmpty()) return Result.success(emptyList())
        val api = mode.api() ?: return Result.failure(
            ServerApiException("server mode is not available", code = "server_unavailable")
        )
        val uploader = ServerChatMediaUploader(
            api = api,
            compressor = compressor,
            readBytes = { uri -> readOwned(uri, mediaDirectory()) }
        )
        return uploader.uploadImages(attachments, personaId)
    }

    /**
     * 上传人格头像；没有头像/本地文件不在时返回 `null`（表示「服务端那边也没有头像」）。
     *
     * 头像上传失败必须让人格保存失败：静默丢掉头像会让用户以为换好了，下次打开又变回旧的。
     */
    suspend fun uploadPersonaAvatar(avatarUri: String?, personaId: String?): Result<String?> {
        val value = avatarUri?.trim().orEmpty()
        if (value.isEmpty()) return Result.success(null)
        val api = mode.api() ?: return Result.failure(
            ServerApiException("server mode is not available", code = "server_unavailable")
        )
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readOwned(value, MediaStorage.avatarDirectory(context))
                    ?: return@runCatching null
                val compressed = MediaStorage.compressAvatarImage(bytes, "image/jpeg")
                    ?: throw ServerApiException(
                        "avatar could not be compressed for upload",
                        code = "media_compress_failed"
                    )
                val media = api.uploadMedia(
                    bytes = compressed.bytes,
                    mime = compressed.mime,
                    kind = ServerMediaKinds.PERSONA_AVATAR,
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

    /** 只读指定目录下、真实存在的文件；单张上限与备份一致（8 MiB），防止异常大文件进堆。 */
    private fun readOwned(uri: String, directory: File): ByteArray? {
        if (!uri.startsWith("file://")) return null
        val file = runCatching { File(URI(uri)) }.getOrNull() ?: return null
        if (!MediaOwnership.isWithin(file, listOf(directory))) return null
        if (!file.isFile || !file.canRead()) return null
        if (file.length() <= 0L || file.length() > MAX_READ_BYTES) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun mediaDirectory(): File = MediaStorage.mediaDirectory(context)

    private companion object {
        const val MAX_READ_BYTES = 8L * 1024 * 1024
    }
}
