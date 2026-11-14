package com.myfriend.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.myfriend.app.R
import com.myfriend.app.domain.AvatarImagePolicy
import com.myfriend.app.domain.BackupAvatarPath
import com.myfriend.app.domain.ImageContextBudget
import com.myfriend.app.domain.MediaOwnership
import com.myfriend.app.domain.MessageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Copies user-picked media into app-private storage so avatars/backgrounds survive URI grant changes. */
object MediaStorage {
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val MAX_IMAGE_DIMENSION = 1568
    private const val MAX_CHAT_IMAGE_BYTES = 4 * 1024 * 1024

    /** 头像文件目录：files/avatars，和聊天图片的 files/media 分开，清理时互不牵连。 */
    private const val AVATAR_DIR = "avatars"

    /** 聊天图片 / 聊天背景 / 其它聊天媒体所在的目录。 */
    private const val MEDIA_DIR = "media"

    /** `persistChatImage` 落盘时的文件名前缀；「清空聊天」只删这一类。 */
    private const val CHAT_IMAGE_PREFIX = "chat-"

    private const val TAG = "MediaStorage"

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
                error(context.getString(R.string.media_error_bad_size))
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
                error(context.getString(R.string.media_error_too_big_to_compress))
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
        val bounds = readImageBounds(context, source) ?: error(context.getString(R.string.media_error_unreadable))
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
        } ?: error(context.getString(R.string.media_error_unreadable))
    }

    suspend fun persistImage(context: Context, source: Uri, kind: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, MEDIA_DIR).apply { mkdirs() }
            val target = File(dir, "$kind-${UUID.randomUUID()}.img")
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output ->
                    val copied = input.copyTo(output)
                    if (copied <= 0L) error(context.getString(R.string.media_error_empty))
                    if (copied > MAX_BYTES) {
                        target.delete()
                        error(context.getString(R.string.media_error_over_20mb))
                    }
                }
            } ?: error(context.getString(R.string.media_error_unreadable))
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
                val dir = File(context.filesDir, MEDIA_DIR).apply { mkdirs() }
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
                    error(context.getString(R.string.media_error_process_failed))
                }
                MessageAttachment(
                    uri = Uri.fromFile(target).toString(),
                    mime = if (usePng) "image/png" else "image/jpeg",
                    width = 0,
                    height = 0
                )
            }
        }

    /**
     * 把一张聊天附件转成上下文里能直接用的 data URL（审计 P2-9）。
     *
     * 与旧 `toDataUrl` 的区别：
     * - **在 IO 上做**：解码/压缩/读文件都是阻塞活，以前是在调用方上下文里直接 `readBytes()`；
     * - **按目标尺寸重编码**：长边压到 [ImageContextBudget.TARGET_EDGE_PX]，PNG 也转 JPEG
     *   （PNG 截图不压缩，一张就能到 4MB，是 OOM 的主要来源）；
     * - **单张字节上限**：按 [ImageContextBudget.JPEG_QUALITY_LADDER] 逐档降质量，
     *   压不到 [ImageContextBudget.MAX_ENCODED_BYTES] 就放弃这一张（返回 null）。
     *
     * 返回 null 表示「这张图这次带不上」，调用方跳过即可，不该让整轮回复失败。
     */
    suspend fun toContextDataUrl(attachment: MessageAttachment): String? = withContext(Dispatchers.IO) {
        runCatching {
            val file = fileOf(attachment.uri) ?: return@runCatching null
            if (!file.isFile || !file.canRead()) return@runCatching null
            val decoded = decodeForContext(file) ?: return@runCatching null
            val flattened = flattenForJpeg(decoded)
            try {
                val bytes = encodeBounded(flattened) ?: return@runCatching null
                "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            } finally {
                if (flattened !== decoded) flattened.recycle()
                decoded.recycle()
            }
        }.getOrNull()
    }

    /**
     * 备份里的头像值 → 本机可用的 `file://` 路径（安全审计 P2-5）。
     *
     * 备份里只写文件名（新备份）或老备份里的绝对路径，两者都归一成文件名，
     * 再看本机 `files/avatars/<文件名>` 在不在：在就恢复成本机路径，不在就返回 null，
     * 由导入方清空字段并记一条警告。
     */
    fun resolveImportedAvatar(context: Context, raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val name = BackupAvatarPath.fileName(value) ?: return null
        val dir = File(context.filesDir, AVATAR_DIR)
        val file = File(dir, name)
        if (!MediaOwnership.isWithin(file, listOf(dir))) return null
        if (!file.isFile) return null
        return Uri.fromFile(file).toString()
    }

    fun fileOf(uriString: String): File? = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") uri.path?.let { File(it) } else null
    }.getOrNull()

    /**
     * 删一批聊天附件文件。
     *
     * **只删 App 自己媒体目录里的**（`files/media` 或 `files/avatars`）：
     * 路径来自数据库的 `attachmentsJson`，属于「数据即代码」的输入面，上游一旦被污染，
     * 一个 `file:///data/user/0/<pkg>/databases/myfriend.db` 或 `../../shared_prefs/x.xml`
     * 就会被当成聊天图片删掉。头像早已这么守卫（[deleteAvatar]），附件 0.10.2 起补齐。
     *
     * @return 真的删掉的文件数（被拒绝的路径不计数）
     */
    fun deleteAttachments(context: Context, items: List<MessageAttachment>): Int =
        deleteFiles(context, items.map { it.uri })

    fun deleteFiles(context: Context, uris: List<String>): Int {
        if (uris.isEmpty()) return 0
        val roots = ownedRoots(context)
        var deleted = 0
        uris.forEach { raw ->
            val file = fileOf(raw) ?: return@forEach
            if (!MediaOwnership.isWithin(file, roots)) {
                // 拒绝并留痕：这条路径不是我们自己的文件，删它就是越界。
                Log.w(TAG, "refusing to delete a file outside app media dirs: ${file.path}")
                return@forEach
            }
            if (runCatching { file.delete() }.getOrDefault(false)) deleted++
        }
        return deleted
    }

    /**
     * 只删聊天图片（`files/media/chat-*`），不动聊天背景（`background-*.img`）与头像。
     *
     * 「清空聊天与记忆」要清掉聊天图片文件，但不该顺手把用户设的聊天背景删掉——
     * 那是设置项，不是聊天内容。
     */
    fun clearChatImages(context: Context): Int {
        val dir = File(context.filesDir, MEDIA_DIR)
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return 0
        return files.count { file ->
            file.isFile && file.name.startsWith(CHAT_IMAGE_PREFIX) &&
                runCatching { file.delete() }.getOrDefault(false)
        }
    }

    private fun ownedRoots(context: Context): List<File> = listOf(
        File(context.filesDir, MEDIA_DIR),
        File(context.filesDir, AVATAR_DIR)
    )

    /**
     * 删掉一张 App 自己管的头像文件。
     *
     * 只删 `files/avatars/` 里的文件：用户选的 `content://` 原图、或别处借来的路径一律不碰，
     * 免得「换头像」把人相册里的照片删了。传 null / 非 file:// 路径时什么都不做。
     */
    fun deleteAvatar(context: Context, uriString: String?): Int {
        val raw = uriString?.trim().orEmpty()
        if (raw.isEmpty()) return 0
        val file = fileOf(raw) ?: return 0
        val dir = File(context.filesDir, AVATAR_DIR)
        if (!MediaOwnership.isWithin(file, listOf(dir))) return 0
        return if (runCatching { file.delete() }.getOrDefault(false)) 1 else 0
    }

    /**
     * 删掉 `files/avatars/` 里没有被 [keepUris] 引用的头像文件。
     *
     * 用 canonical path 比较（与 [deleteAvatar] 同一套归属语义）：路径写法的差异
     * （相对段、符号链接）不会让「本来还在用的头像」被误删。
     */
    fun clearUnreferencedAvatars(context: Context, keepUris: Set<String>): Int {
        val dir = File(context.filesDir, AVATAR_DIR)
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return 0
        val keepPaths = keepUris.mapNotNull { raw ->
            fileOf(raw)?.let { file -> runCatching { file.canonicalPath }.getOrNull() }
        }.toSet()
        return files.count { file ->
            if (!file.isFile) return@count false
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@count false
            if (canonical in keepPaths) return@count false
            runCatching { file.delete() }.getOrDefault(false)
        }
    }

    /**
     * `file://` 指向的文件是否已经不在了。
     *
     * 备份导入用它判断「这行头像路径失效了没有」：`content://` 与空值一律当作「无法判断」，
     * 返回 false，不动它。
     */
    fun isMissingLocalFile(uriString: String?): Boolean {
        val raw = uriString?.trim().orEmpty()
        if (raw.isEmpty() || !raw.startsWith("file://")) return false
        val file = fileOf(raw) ?: return false
        return !file.exists()
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
            ?: error(context.getString(R.string.media_error_unreadable))
    }

    private fun writeBitmap(bitmap: Bitmap, target: File, usePng: Boolean, quality: Int): Long {
        val format = if (usePng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        target.outputStream().use { output -> bitmap.compress(format, quality, output) }
        return target.length()
    }

    /**
     * 上下文用图的解码：长边压到 [ImageContextBudget.TARGET_EDGE_PX]。
     *
     * API 28+ 用 ImageDecoder 直接按目标尺寸解码（不会先解一张原图再缩）；旧版本按
     * `inSampleSize` 粗采样。返回 null 表示这张图读不出来（文件坏了 / 不是图片）。
     */
    private fun decodeForContext(file: File): Bitmap? {
        val targetEdge = ImageContextBudget.TARGET_EDGE_PX
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    decoder.isMutableRequired = false
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > targetEdge) {
                        val scale = targetEdge.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            }.getOrNull()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        while (longest / sample > targetEdge * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull()
    }

    /** JPEG 没有透明通道：带 alpha 的图先垫一层白底，免得截图/贴纸变成全黑。 */
    private fun flattenForJpeg(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return out
    }

    /** 按质量阶梯压到单张字节预算以内；压不进去返回 null（这张就不带了）。 */
    private fun encodeBounded(bitmap: Bitmap): ByteArray? {
        val buffer = ByteArrayOutputStream()
        for (quality in ImageContextBudget.JPEG_QUALITY_LADDER) {
            buffer.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            if (buffer.size() in 1..ImageContextBudget.MAX_ENCODED_BYTES) return buffer.toByteArray()
        }
        return null
    }

    /**
     * 「删除全部数据」：媒体目录整棵删掉（聊天图片、聊天背景、头像），返回删掉的文件数。
     *
     * 只有真正「全删」才该用它；「清空聊天与记忆」用 [clearChatImages]，否则会连
     * 聊天背景与头像一起删掉——那两样是设置/人格数据，不在「聊天记录」的语义里。
     */
    fun clear(context: Context): Int {
        var deleted = 0
        ownedRoots(context).forEach { dir ->
            deleted += runCatching { dir.walkTopDown().count { it.isFile } }.getOrDefault(0)
            runCatching { dir.deleteRecursively() }
        }
        return deleted
    }
}
