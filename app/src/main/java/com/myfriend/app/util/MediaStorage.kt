package com.myfriend.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.myfriend.app.domain.AvatarImagePolicy
import com.myfriend.app.domain.MessageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Copies user-picked media into app-private storage so avatars/backgrounds survive URI grant changes. */
object MediaStorage {
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val MAX_IMAGE_DIMENSION = 1568
    private const val MAX_CHAT_IMAGE_BYTES = 4 * 1024 * 1024

    /** 头像文件目录：files/avatars，和聊天图片的 files/media 分开，清理时互不牵连。 */
    private const val AVATAR_DIR = "avatars"

    /**
     * 把相册里选的头像采样、缩放、压缩后存进 App 私有目录，返回 `file://` 路径。
     *
     * 与 [persistImage]（原样复制，聊天背景仍在用）的区别：
     * - **采样解码**：先读宽高算 inSampleSize / ImageDecoder 目标尺寸，绝不让一张 12MP
     *   原图整张进内存；
     * - **长边 ≤512、JPEG 85、落盘 ≤200KB**：头像在屏幕上最大也就 250px，存原图只会让
     *   每次冷启动、每次列表滚动都把一张几千万像素的位图解出来常驻；
     * - **永远落到 files/avatars**：系统相册的 URI 授权随时可能失效，换手机也不会跟过来。
     *
     * 纯策略部分在 [AvatarImagePolicy]，那部分有单测。
     */
    suspend fun persistAvatar(context: Context, source: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, AVATAR_DIR).apply { mkdirs() }
            val decoded = decodeAvatarBitmap(context, source)
            val target = AvatarImagePolicy.targetSize(decoded.width, decoded.height)
            if (target.width <= 0 || target.height <= 0) {
                decoded.recycle()
                error("图片尺寸异常，换一张试试")
            }
            val scaled = if (decoded.width == target.width && decoded.height == target.height) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, target.width, target.height, true).also { out ->
                    if (out !== decoded) decoded.recycle()
                }
            }
            val targetFile = File(dir, "avatar-${UUID.randomUUID()}.jpg")
            var written = 0L
            for (quality in AvatarImagePolicy.qualityLadder()) {
                targetFile.outputStream().use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
                }
                written = targetFile.length()
                if (AvatarImagePolicy.fits(written)) break
            }
            scaled.recycle()
            if (!AvatarImagePolicy.fits(written)) {
                targetFile.delete()
                error("这张图片压不进 200KB，换一张试试")
            }
            Uri.fromFile(targetFile).toString()
        }
    }

    /** 读原图尺寸，不解码像素。读不到返回 null。 */
    private fun readImageBounds(context: Context, source: Uri): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return options.outWidth to options.outHeight
    }

    /**
     * 按 [AvatarImagePolicy] 的预算解码头像。
     *
     * API 28+ 用 ImageDecoder：它会顺带把 EXIF 方向摆正，并且能直接按目标尺寸解码
     * （不像 BitmapFactory 那样先解一张大的再缩）。旧版本退回 inSampleSize + 精确缩放。
     */
    private fun decodeAvatarBitmap(context: Context, source: Uri): Bitmap {
        val bounds = readImageBounds(context, source) ?: error("无法读取图片")
        val target = AvatarImagePolicy.targetSize(bounds.first, bounds.second)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && target.width > 0) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, _, _ ->
                decoder.isMutableRequired = false
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(target.width, target.height)
            }
        }
        val sample = AvatarImagePolicy.sampleSize(bounds.first, bounds.second)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("无法读取图片")
    }

    suspend fun persistImage(context: Context, source: Uri, kind: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "media").apply { mkdirs() }
            val target = File(dir, "$kind-${UUID.randomUUID()}.img")
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output ->
                    val copied = input.copyTo(output)
                    if (copied <= 0L) error("图片为空")
                    if (copied > MAX_BYTES) {
                        target.delete()
                        error("图片超过 20 MB，请选择更小的图片")
                    }
                }
            } ?: error("无法读取图片")
            Uri.fromFile(target).toString()
        }
    }

    /**
     * 把相册图片缩放后复制到私有目录，返回可长期引用的附件。
     * 视觉模型对图片尺寸敏感，长边限制在 1568px 可以在清晰度与请求体积之间取平衡。
     */
    suspend fun persistChatImage(context: Context, source: Uri): Result<MessageAttachment> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, "media").apply { mkdirs() }
                val bitmap = decodeScaled(context, source)
                val sourceMime = context.contentResolver.getType(source).orEmpty()
                val usePng = sourceMime == "image/png"
                val target = File(dir, "chat-${UUID.randomUUID()}.${if (usePng) "png" else "jpg"}")
                var quality = if (usePng) 100 else 88
                var written = writeBitmap(bitmap, target, usePng, quality)
                while (written > MAX_CHAT_IMAGE_BYTES && quality > 55 && !usePng) {
                    quality -= 12
                    written = writeBitmap(bitmap, target, usePng, quality)
                }
                bitmap.recycle()
                if (written <= 0L) {
                    target.delete()
                    error("图片处理失败")
                }
                MessageAttachment(
                    uri = Uri.fromFile(target).toString(),
                    mime = if (usePng) "image/png" else "image/jpeg",
                    width = 0,
                    height = 0
                )
            }
        }

    /** 转成 OpenAI 兼容接口可直接使用的 data URL。 */
    fun toDataUrl(attachment: MessageAttachment): String? {
        val file = fileOf(attachment.uri) ?: return null
        if (!file.exists() || !file.canRead()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:${attachment.mime};base64,$encoded"
    }

    fun fileOf(uriString: String): File? = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") uri.path?.let { File(it) } else null
    }.getOrNull()

    fun deleteFiles(items: List<MessageAttachment>) {
        items.forEach { attachment -> fileOf(attachment.uri)?.delete() }
    }

    private fun decodeScaled(context: Context, source: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, info, _ ->
                decoder.isMutableRequired = false
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val longest = maxOf(width, height)
                if (longest > MAX_IMAGE_DIMENSION) {
                    val scale = MAX_IMAGE_DIMENSION.toFloat() / longest
                    decoder.setTargetSize(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > MAX_IMAGE_DIMENSION * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("无法读取图片")
    }

    private fun writeBitmap(bitmap: Bitmap, target: File, usePng: Boolean, quality: Int): Long {
        val format = if (usePng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        target.outputStream().use { output -> bitmap.compress(format, quality, output) }
        return target.length()
    }

    fun clear(context: Context) {
        File(context.filesDir, "media").deleteRecursively()
        // 头像和聊天图片一样是这台手机上的文件；「清空所有数据」之后不能留下孤儿文件。
        File(context.filesDir, AVATAR_DIR).deleteRecursively()
    }
}
