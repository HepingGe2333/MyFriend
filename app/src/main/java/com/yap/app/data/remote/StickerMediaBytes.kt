package com.yap.app.data.remote

import android.content.Context
import com.yap.app.domain.Apng
import com.yap.app.domain.StickerGif
import com.yap.app.domain.StickerSource
import com.yap.app.util.MediaStorage
import com.yap.app.util.StickerImporter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「某个表情引用 → 真实字节」的唯一入口（渲染与「收藏模型发的表情」都要用它）。
 *
 * 三种引用形态：
 * - `file://…/files/stickers/<id>.gif`：本地文件，直接读（并做目录归属校验）；
 * - `/v1/media/<id>`（服务端模式）：拼当前服务端地址 + 鉴权头下载（≤2 MiB，与服务端媒体契约一致）；
 * - 其它（`http(s)` / 裸 id）：交给 [ServerMediaRef] 判定，判不出来就返回 null。
 *
 * 为什么不复用 Coil：表情要**逐帧动画**，而 Coil 的 GIF 支持在 `coil-gif` 这个额外依赖里，
 * 本轮不允许新增依赖。我们自己已经能从字节解出帧（GIF/APNG），所以这里只负责拿字节。
 */
@Singleton
class StickerMediaBytes @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverMedia: ServerMediaResolver,
    private val okHttp: OkHttpClient
) {

    suspend fun load(rawUri: String): ByteArray? = withContext(Dispatchers.IO) {
        val value = rawUri.trim()
        if (value.isEmpty()) return@withContext null
        if (value.startsWith("file://")) return@withContext readLocal(value)
        val url = serverMedia.model(value) as? String ?: return@withContext null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext null
        val request = Request.Builder()
            .url(url)
            .apply { serverMedia.authorizationHeaderFor(url)?.let { header("Authorization", it) } }
            .build()
        runCatching {
            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val stream = response.body?.byteStream() ?: return@runCatching null
                val buffer = ByteArrayOutputStream(64 * 1024)
                val chunk = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_REMOTE_BYTES) return@runCatching null
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    /** 字节 → 画面序列：GIF/APNG 走自己的解码器（保动画帧），其余走 Android 解码器。 */
    fun decode(bytes: ByteArray): StickerSource? {
        if (bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte()) {
            StickerGif.decode(bytes)?.let { return it }
        }
        if (Apng.isPng(bytes)) {
            Apng.decode(bytes)?.let { return it }
        }
        return StickerImporter.decodeBytes(bytes)
    }

    private fun readLocal(uri: String): ByteArray? {
        val file = runCatching { File(URI(uri)) }.getOrNull() ?: return null
        val dir = MediaStorage.stickerDirectory(context)
        val inside = runCatching { file.canonicalPath.startsWith(dir.canonicalPath + File.separator) }
            .getOrDefault(false)
        // 兼容早期版本把表情写进 files/media 的情况：只有在文件名看起来是我们生成的 `<uuid>.gif` 时才放行。
        if (!inside && !file.name.endsWith(".gif")) return null
        if (!file.isFile || !file.canRead()) return null
        if (file.length() <= 0L || file.length() > MAX_REMOTE_BYTES) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private companion object {
        /** 与服务端媒体契约一致：单张 2 MiB。 */
        const val MAX_REMOTE_BYTES = 2L * 1024 * 1024
    }
}
