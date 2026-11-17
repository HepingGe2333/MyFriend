package com.yap.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import com.yap.app.domain.Apng
import com.yap.app.domain.StickerFrame
import com.yap.app.domain.StickerLimits
import com.yap.app.domain.StickerPosition
import com.yap.app.domain.StickerSource
import com.yap.app.domain.StickerGif
import com.yap.app.domain.StickerPipeline
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 表情包的**输入解码**与**叠字/叠图**（Android 侧）。
 *
 * 分派顺序是有讲究的：
 * 1. **GIF** 走自己的解码器（`StickerGif.decode`）：全 API 可用，且动画帧一帧不少；
 * 2. **APNG** 走自己的解码器（`Apng.decode`）：`ImageDecoder` 只能把动图交给
 *    `AnimatedImageDrawable` 播，**没有公开 API 逐帧取位图**，所以 APNG 只能自己解；
 * 3. 其余（JPEG/PNG/WebP，含静态 WebP）走 `ImageDecoder`：它会顺带摆正 EXIF 方向、
 *    能直接按目标尺寸解码（不会先解一张 12MP 原图再缩）。
 *
 * 已知取舍：**动图 WebP** 目前只能取到首帧（同样是「没有逐帧公开 API」），
 * 会退化成单帧 GIF；这一点在报告里明确列出，不假装支持。
 */
object StickerImporter {

    /** 单张输入上限：和相册导入一致（20 MiB），超出直接拒，不进堆。 */
    const val MAX_INPUT_BYTES = 20L * 1024 * 1024

    /** 从 `content://` / `file://` 读字节再解码；读不到返回 null。 */
    suspend fun decodeUri(context: Context, uri: Uri): StickerSource? {
        val bytes = readBytes(context, uri) ?: return null
        return decodeBytes(bytes)
    }

    fun readBytes(context: Context, uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input -> readBounded(input) }
    }.getOrNull()

    private fun readBounded(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream(64 * 1024)
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read <= 0) break
            total += read
            if (total > MAX_INPUT_BYTES) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** 字节 → 画面序列；认不出/读不出来返回 null。 */
    fun decodeBytes(bytes: ByteArray): StickerSource? {
        if (bytes.isEmpty()) return null
        if (isGif(bytes)) {
            StickerGif.decode(bytes)?.let { return it }
        }
        if (Apng.isPng(bytes)) {
            // 静态 PNG 与 APNG 都走这里：APNG 会把每一帧都解出来，静态 PNG 得到单帧。
            Apng.decode(bytes)?.let { return it }
        }
        val bitmap = decodeBitmap(bytes) ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            StickerSource(
                width = bitmap.width,
                height = bitmap.height,
                frames = listOf(StickerFrame(pixels, StickerLimits.STATIC_DELAY_MS))
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()

    /**
     * 位图解码：长边直接解到 ≤320（`ImageDecoder` 的缩放质量比「先解原图再缩」好得多）。
     */
    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        val edge = StickerLimits.MAX_EDGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                ImageDecoderSupport.decodeScaled(bytes, edge)
            }.getOrNull()
        }
        // API 26–27：没有 ImageDecoder，退回 inSampleSize 粗采样 + 精确缩放。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > edge) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
            ?: return null
        val target = if (maxOf(decoded.width, decoded.height) > edge) {
            val scale = edge.toDouble() / maxOf(decoded.width, decoded.height)
            val width = (decoded.width * scale).toInt().coerceAtLeast(1)
            val height = (decoded.height * scale).toInt().coerceAtLeast(1)
            runCatching { Bitmap.createScaledBitmap(decoded, width, height, true) }.getOrNull()
        } else {
            null
        }
        if (target != null && target !== decoded) decoded.recycle()
        return target ?: decoded
    }

    // ------------------------------------------------------------ 叠字 / 叠图

    /** 「Meme 底」：默认一块 320×320 的白底（模型只给 text 时用）。 */
    fun blankSource(width: Int = 320, height: Int = 320, color: Int = 0xFFFFFFFF.toInt()): StickerSource =
        StickerSource(width, height, listOf(StickerFrame(IntArray(width * height) { color }, StickerLimits.STATIC_DELAY_MS)))

    /**
     * 叠字：白字黑描边，按 [position] 放上/中/下。
     *
     * 字号按短边取（`短边 × 0.16`，最少 12px），超宽自动换行、最多 4 行把字号往下压——
     * 表情包上的字必须大，但也不能溢出画框。
     */
    fun renderText(source: StickerSource, text: String, position: StickerPosition): StickerSource {
        val content = text.trim()
        if (content.isEmpty()) return source
        return StickerSource(
            width = source.width,
            height = source.height,
            frames = source.frames.map { frame ->
                StickerFrame(drawTextOnFrame(source.width, source.height, frame.argb, content, position), frame.delayMs)
            }
        )
    }

    private fun drawTextOnFrame(
        width: Int,
        height: Int,
        argb: IntArray,
        text: String,
        position: StickerPosition
    ): IntArray {
        val bitmap = runCatching { Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888) }.getOrNull()
            ?: return argb
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return argb
        val canvas = Canvas(mutable)
        val shortEdge = minOf(width, height)
        var textSize = (shortEdge * 0.16f).coerceAtLeast(12f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        var lines = wrapText(text, paint, textSize, width * 0.88f)
        var attempts = 0
        while (lines.size > 4 && textSize > 10f && attempts < 8) {
            textSize *= 0.82f
            lines = wrapText(text, paint, textSize, width * 0.88f)
            attempts++
        }

        val lineHeight = textSize * 1.15f
        val blockHeight = lineHeight * lines.size
        val margin = height * 0.08f
        val firstBaseline = when (position) {
            StickerPosition.TOP -> margin + textSize
            StickerPosition.CENTER -> (height - blockHeight) / 2f + textSize
            StickerPosition.BOTTOM -> height - margin - blockHeight + textSize
        }

        paint.textSize = textSize
        lines.forEachIndexed { index, line ->
            val baseline = firstBaseline + index * lineHeight
            // 先描边后填字：白字在任何底色上都看得清。
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (textSize * 0.14f).coerceAtLeast(2f)
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = 0xFF000000.toInt()
            canvas.drawText(line, width / 2f, baseline, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawText(line, width / 2f, baseline, paint)
        }

        val out = IntArray(width * height)
        mutable.getPixels(out, 0, width, 0, 0, width, height)
        mutable.recycle()
        if (mutable !== bitmap) bitmap.recycle()
        return out
    }

    private fun wrapText(text: String, paint: Paint, textSize: Float, maxWidth: Float): List<String> {
        paint.textSize = textSize
        val lines = mutableListOf<String>()
        text.split('\n').forEach { paragraph ->
            if (paragraph.isEmpty()) {
                lines += ""
                return@forEach
            }
            var current = StringBuilder()
            paragraph.forEach { char ->
                val candidate = current.toString() + char
                if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                    lines += current.toString()
                    current = StringBuilder().append(char)
                } else {
                    current = StringBuilder(candidate)
                }
            }
            if (current.isNotEmpty()) lines += current.toString()
        }
        return lines.take(6).ifEmpty { listOf(text) }
    }

    /**
     * 叠图：把另一张表情按 [position] 贴到画面上。
     *
     * 叠图会缩到「基底短边的 55%」（上/下）或 70%（居中）—— 规则固定，这样同一组素材
     * 每次得到同样的结果；帧数取两者较大值，静态的那一方按自己的唯一帧重复。
     */
    fun renderOverlay(source: StickerSource, overlay: StickerSource, position: StickerPosition): StickerSource {
        val boxFraction = if (position == StickerPosition.CENTER) 0.70f else 0.55f
        val boxEdge = (minOf(source.width, source.height) * boxFraction).toInt().coerceAtLeast(8)
        val overlayScale = minOf(
            boxEdge.toDouble() / maxOf(overlay.width, overlay.height).coerceAtLeast(1),
            1.0
        )
        val drawWidth = (overlay.width * overlayScale).toInt().coerceAtLeast(1)
        val drawHeight = (overlay.height * overlayScale).toInt().coerceAtLeast(1)
        val left = (source.width - drawWidth) / 2
        val margin = (source.height * 0.06f).toInt()
        val top = when (position) {
            StickerPosition.TOP -> margin
            StickerPosition.CENTER -> (source.height - drawHeight) / 2
            StickerPosition.BOTTOM -> source.height - drawHeight - margin
        }.coerceAtLeast(0)

        val frameCount = maxOf(source.frames.size, overlay.frames.size)
        val frames = (0 until frameCount).map { index ->
            val base = source.frames[if (source.frames.size == 1) 0 else index % source.frames.size]
            val patch = overlay.frames[if (overlay.frames.size == 1) 0 else index % overlay.frames.size]
            val delay = if (source.frames.size > 1) base.delayMs else patch.delayMs
            StickerFrame(
                argb = drawOverlayOnFrame(
                    canvasWidth = source.width,
                    canvasHeight = source.height,
                    argb = base.argb,
                    patchWidth = overlay.width,
                    patchHeight = overlay.height,
                    patch = patch.argb,
                    left = left,
                    top = top,
                    targetWidth = drawWidth,
                    targetHeight = drawHeight
                ),
                delayMs = StickerPipeline.normalizeDelay(delay)
            )
        }
        return StickerSource(source.width, source.height, frames)
    }

    private fun drawOverlayOnFrame(
        canvasWidth: Int,
        canvasHeight: Int,
        argb: IntArray,
        patchWidth: Int,
        patchHeight: Int,
        patch: IntArray,
        left: Int,
        top: Int,
        targetWidth: Int,
        targetHeight: Int
    ): IntArray {
        val base = runCatching { Bitmap.createBitmap(argb, canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888) }
            .getOrNull() ?: return argb
        val mutable = base.copy(Bitmap.Config.ARGB_8888, true) ?: return argb
        val patchBitmap = runCatching {
            Bitmap.createBitmap(patch, patchWidth, patchHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return argb
        val canvas = Canvas(mutable)
        val destination = Rect(left, top, left + targetWidth, top + targetHeight)
        canvas.drawBitmap(patchBitmap, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
        val out = IntArray(canvasWidth * canvasHeight)
        mutable.getPixels(out, 0, canvasWidth, 0, 0, canvasWidth, canvasHeight)
        patchBitmap.recycle()
        mutable.recycle()
        if (mutable !== base) base.recycle()
        return out
    }
}

/** `ImageDecoder` 的调用被单独包一层：`@RequiresApi(28)` 的类和 API 26 的构建目标要分得清。 */
private object ImageDecoderSupport {

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    fun decodeScaled(bytes: ByteArray, maxEdge: Int): Bitmap {
        val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.isMutableRequired = false
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxEdge && longest > 0) {
                val scale = maxEdge.toDouble() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
    }
}
