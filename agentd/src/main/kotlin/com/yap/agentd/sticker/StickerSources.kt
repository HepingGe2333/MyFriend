package com.yap.agentd.sticker

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaUrl
import com.yap.app.domain.WebUrlPolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * `sticker.make` 的素材来源：已有的表情（`/v1/media` 里的字节）与公开图片链接。
 *
 * 为什么单独一层：合成器 [StickerComposer] 是纯函数（只吃字节），下载/查库这些**副作用**
 * 放这里，工具层只做「取素材 → 合成 → 存媒体」的编排。
 *
 * 安全边界（`spec/stickers` §安全与版权）：
 * - 只允许 http(s)，且必须过 `:agentcore` 的 [WebUrlPolicy]（拒绝本机/内网/带 userinfo/非法转义）；
 * - 单张 ≤ [MAX_DOWNLOAD_BYTES]（8 MiB）：先看 `Content-Length`，没有就边读边数；
 * - 不跟随跨协议跳转（JDK 默认 NORMAL 只跟同协议）；不把响应体写进日志；
 * - 来源只用于日志，不写进表情行（`source = WEB`）。
 */
object StickerSources {

    /** 下载上限：8 MiB（与 `spec/stickers` 的「≤8 MiB」一致）。 */
    const val MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024

    private val defaultHttp: HttpClient by lazy {
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /**
     * 从「我的表情」里取一张当底/叠图。
     *
     * 找不到（行不存在 / 媒体文件丢了）返回 null，由工具层回一条明确的失败；
     * mime 取媒体行里的值（表情只可能是 GIF/PNG/WebP，合成器不认识 WebP 会报 unsupported_image）。
     */
    fun fromSticker(store: FileStore, stickerId: String): StickerInput? {
        val id = MediaUrl.idOf(stickerId) ?: return null
        val sticker = store.sticker(id) ?: return null
        val mediaId = store.stickerMediaId(sticker.id) ?: return null
        val bytes = store.mediaBytes(mediaId) ?: return null
        val mime = store.media(mediaId)?.mime ?: sticker.mime
        return StickerInput(bytes, mime)
    }

    /**
     * 下载一张公开图片当底图。
     *
     * 失败抛 [StickerComposeException]（code 稳定、message 中文），工具层原样回灌给模型。
     */
    fun fromUrl(url: String, http: HttpClient = defaultHttp): StickerInput {
        val target = url.trim()
        if (target.isEmpty()) throw StickerComposeException("invalid_url", "没有图片链接")
        val rejection = WebUrlPolicy.check(target)
        if (rejection != null) {
            throw StickerComposeException(
                "url_rejected",
                when (rejection) {
                    WebUrlPolicy.Rejection.EMPTY -> "没有图片链接"
                    WebUrlPolicy.Rejection.UNSUPPORTED_SCHEME -> "只支持 http(s) 图片链接"
                    WebUrlPolicy.Rejection.MISSING_HOST -> "链接缺少可用的主机名（或带了账号密码）"
                    WebUrlPolicy.Rejection.LOCAL_HOST -> "不能下载本机/内网地址"
                }
            )
        }
        val request = try {
            HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "image/*")
                .GET()
                .build()
        } catch (invalid: IllegalArgumentException) {
            throw StickerComposeException("url_rejected", "链接不合法")
        }
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (failure: Exception) {
            throw StickerComposeException("download_failed", "图片下载失败（${failure::class.simpleName}）")
        }
        response.body().use { body ->
            if (response.statusCode() !in 200..299) {
                throw StickerComposeException("download_failed", "图片下载失败（HTTP ${response.statusCode()}）")
            }
            response.headers().firstValue("content-length").orElse("").toLongOrNull()?.let { declared ->
                if (declared > MAX_DOWNLOAD_BYTES) {
                    throw StickerComposeException("image_too_large", "图片太大（上限 8 MiB）")
                }
            }
            val bytes = readLimited(body, MAX_DOWNLOAD_BYTES)
            if (bytes.size > MAX_DOWNLOAD_BYTES) {
                throw StickerComposeException("image_too_large", "图片太大（上限 8 MiB）")
            }
            val contentType = response.headers().firstValue("content-type").orElse("")
                .substringBefore(';').trim().lowercase()
            val mime = when {
                contentType == "image/gif" || contentType == "image/jpeg" || contentType == "image/png" -> contentType
                // 有些图床回 application/octet-stream：按文件头再认一次（不认识就交给合成器报错）。
                isGif(bytes) -> "image/gif"
                isJpeg(bytes) -> "image/jpeg"
                isPng(bytes) -> "image/png"
                else -> contentType.ifBlank { "application/octet-stream" }
            }
            return StickerInput(bytes, mime)
        }
    }

    /** 读最多 [limit] + 1 字节（多读一个用来判断「超了」），不把超大响应整个读进堆。 */
    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream(minOf(limit, 256 * 1024))
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) return ByteArray(limit + 1)
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a" ||
            bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) == "GIF87a"

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            (bytes[0].toInt() and 0xFF) == 0x89 &&
            bytes[1].toInt() == 0x50 && bytes[2].toInt() == 0x4E && bytes[3].toInt() == 0x47
}
