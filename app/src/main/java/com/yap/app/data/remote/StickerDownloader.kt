package com.yap.app.data.remote

import com.yap.app.domain.WebUrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `sticker` 工具 `make` 的「下载一张公开图片」端口。
 *
 * 抽成接口是为了纯 JVM 单测能塞假下载器（`spec/stickers/README.md` 的验收要求
 * 「工具三条路径（假下载器/假图片）」）；生产实现只有 [HttpStickerDownloader] 一份。
 */
fun interface StickerUrlDownloader {
    /** 成功返回图片字节；被策略拒绝、网络失败、超限都返回 null。 */
    suspend fun download(url: String): ByteArray?
}

/**
 * 生产实现的下载器：**先过 [WebUrlPolicy]，再发请求**，边读边数字节，超 8 MiB 立刻放弃。
 *
 * 安全边界（规范「安全与版权」）：
 * - 只允许公开 http/https：拒 `file:` / `data:` / 内网 / 本机 / userinfo（策略在 `:agentcore`，两端共用）；
 * - ≤8 MiB：不信任 `Content-Length`（可以是假的），按实际读到的字节数截断；
 * - 只接受 `image/` 开头的类型（或没有 Content-Type 时看文件头魔数）：不能把一段 HTML/JSON 当图片塞进管线。
 */
@Singleton
class HttpStickerDownloader @Inject constructor(
    private val okHttp: OkHttpClient
) : StickerUrlDownloader {

    override suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val target = url.trim()
        if (WebUrlPolicy.check(target) != null) return@withContext null
        runCatching {
            val request = Request.Builder()
                .url(target)
                .header("Accept", "image/*")
                .header("User-Agent", USER_AGENT)
                .build()
            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.isNotEmpty() && !contentType.startsWith("image/")) return@runCatching null
                // 声明长度明显超限时连读都不用读；仍按真实字节数兜底（长度可以是假的）。
                response.body?.contentLength()?.let { declared ->
                    if (declared > MAX_BYTES) return@runCatching null
                }
                val stream = response.body?.byteStream() ?: return@runCatching null
                readBounded(stream)
            }
        }.getOrNull()
    }

    private fun readBounded(stream: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream(64 * 1024)
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            total += read
            if (total > MAX_BYTES) return null
            buffer.write(chunk, 0, read)
        }
        val bytes = buffer.toByteArray()
        if (bytes.isEmpty()) return null
        // 没有 Content-Type 时按文件头判断：GIF / PNG / JPEG / WebP 四种。
        if (!looksLikeImage(bytes)) return null
        return bytes
    }

    private companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
        const val USER_AGENT = "YapSticker/1.0"

        fun looksLikeImage(bytes: ByteArray): Boolean {
            if (bytes.size < 12) return false
            val gif = bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()
            val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
            val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
            val webp = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            return gif || png || jpeg || webp
        }
    }
}
