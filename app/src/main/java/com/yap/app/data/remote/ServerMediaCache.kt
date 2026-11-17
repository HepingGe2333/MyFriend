package com.yap.app.data.remote

import android.content.Context
import com.yap.app.domain.AudioVideoPolicy
import com.yap.app.domain.MessageAttachment
import com.yap.app.util.MediaStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端媒体的**本地播放缓存**（0.13）。
 *
 * 为什么必须落地再播：`MediaPlayer` / `VideoView` 不能带 `Authorization` 头，
 * 而服务端媒体是鉴权的；把令牌拼进 URL 又会把它写进日志/截图/分享。所以：
 * 先用 [ServerApi.downloadMedia]（带 Bearer）把字节取回来，落到 `cacheDir/av`，再本地播放。
 *
 * 缓存按媒体 id 命名、`cacheDir` 随时可能被系统清掉（那正是缓存该有的语义：
 * 下一次播放再下一次，不保证常在）。
 */
@Singleton
class ServerMediaCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mode: ServerModeRepository
) {

    /**
     * 把一条附件解析成本机可播放文件。
     *
     * - 本地 `file://`：直接返回（仍走归属守卫：只认 `files/audio`、`files/video`）；
     * - 服务端 `/v1/media/<id>`：已有缓存就复用，否则下载后原子落盘；
     * - 其它（`content://` 或已失效路径）：返回 null，由界面显示「打不开」。
     */
    suspend fun localFile(attachment: MessageAttachment): File? = withContext(Dispatchers.IO) {
        MediaStorage.voiceVideoFile(context, attachment.uri)?.let { return@withContext it }
        val ref = attachment.uri.trim()
        if (!ServerMediaRef.isMediaRef(ref)) return@withContext null
        val id = ServerMediaRef.idOf(ref) ?: return@withContext null
        val api = mode.api() ?: return@withContext null
        val extension = AudioVideoPolicy.extensionFor(attachment.mime)
        val directory = File(context.cacheDir, CACHE_DIR).apply { runCatching { mkdirs() } }
        val target = File(directory, "$id.$extension")
        if (target.isFile && target.length() > 0L) return@withContext target
        val limit = when {
            attachment.isAudio -> AudioVideoPolicy.AUDIO_MAX_BYTES
            attachment.isVideo -> AudioVideoPolicy.VIDEO_MAX_BYTES
            else -> 0L
        }
        if (limit <= 0L) return@withContext null
        val bytes = runCatching { api.downloadMedia(ref, limit.toInt()) }.getOrNull()
            ?: return@withContext null
        if (bytes.isEmpty() || bytes.size.toLong() > limit) return@withContext null
        val temp = File(directory, "$id.tmp-${UUID.randomUUID()}")
        runCatching {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                temp.delete()
                return@withContext null
            }
        }.getOrElse {
            temp.delete()
            return@withContext null
        }
        target.takeIf { it.isFile && it.length() > 0L }
    }

    private companion object {
        const val CACHE_DIR = "av"
    }
}
