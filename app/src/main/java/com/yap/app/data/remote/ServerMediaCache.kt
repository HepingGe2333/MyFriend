package com.yap.app.data.remote

import android.content.Context
import com.yap.app.domain.MediaOwnership
import com.yap.app.domain.MessageAttachment
import com.yap.app.util.MediaStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端媒体的**本地播放缓存**（0.13）。
 *
 * 为什么必须落地再播：`MediaPlayer` / `VideoView` 不能带 `Authorization` 头，
 * 而服务端媒体是鉴权的；把令牌拼进 URL 又会把它写进日志/截图/分享。所以：
 * 先用 [ServerApi.downloadMedia]（带 Bearer）把字节取回来，落到 `cacheDir/av`，再本地播放。
 *
 * 与持久副本的分工（[ServerMediaStore]）：正常同步/事件拉回消息时，附件已经落到
 * `files/audio|video` 并写进数据库，这里只服务「数据库里仍是服务端引用」的兜底播放
 * （离线导入、落盘失败的占位、只有 `serverMediaId` 的旧数据）。
 *
 * 缓存按媒体 id 命名、`cacheDir` 随时可能被系统清掉（那正是缓存该有的语义：
 * 下一次播放再下一次，不保证常在）。落盘编排复用 [ServerMediaStore.ensureLocal]
 * （同一条「幂等 + 临时文件 + rename」实现），只是目录换成缓存目录——播放路径不该再写
 * 一份下载/写盘逻辑。
 */
@Singleton
class ServerMediaCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStore: ServerMediaStore
) {

    /**
     * 把一条附件解析成本机可播放文件。
     *
     * - 本地 `file://`：直接返回（仍走归属守卫：只认 `files/audio`、`files/video`）；
     * - 服务端 `/v1/media/<id>`（或只有 `serverMediaId`）：已有缓存就复用，否则下载后原子落盘；
     * - 非语音/视频、`content://` 或已失效路径：返回 null，由界面显示「打不开」。
     */
    suspend fun localFile(attachment: MessageAttachment): File? = withContext(Dispatchers.IO) {
        MediaStorage.voiceVideoFile(context, attachment.uri)?.let { return@withContext it }
        if (!attachment.isAudioOrVideo) return@withContext null
        val directory = File(context.cacheDir, CACHE_DIR).apply { runCatching { mkdirs() } }
        // 音频/视频都落同一个缓存目录（播放路径只关心「能交给 MediaPlayer 的文件」）。
        val directories = ServerMediaDirectories(
            chatImages = directory,
            avatars = directory,
            stickers = directory,
            audio = directory,
            video = directory
        )
        val ensured = mediaStore.ensureLocal(attachment, directories)
        // 没本地化成功时 uri 原样返回（仍是服务端引用/失效路径）：播放层按「打不开」处理。
        if (ensured.uri == attachment.uri) return@withContext null
        val file = fileOf(ensured.uri) ?: return@withContext null
        file.takeIf {
            it.isFile && it.length() > 0L && MediaOwnership.isWithin(it, listOf(directory))
        }
    }

    private companion object {
        const val CACHE_DIR = "av"
    }
}
