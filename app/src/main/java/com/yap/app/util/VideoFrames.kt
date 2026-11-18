package com.yap.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.yap.app.domain.AudioVideoPolicy
import com.yap.app.domain.FrameCandidate
import com.yap.app.domain.VideoFramePlan
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * 视频解帧（0.13）：`MediaMetadataRetriever` 的薄封装。
 *
 * 策略全在 [VideoFramePlan]（纯逻辑、有单测）；这里只做三件事：
 * 1. 按规划好的时间点取帧；
 * 2. 把每帧缩到长边 ≤1024px 并压成 JPEG（质量阶梯 + 单帧/总量预算）；
 * 3. **永远回收 Bitmap**：8 帧 1080p 原图就是 70MB，不回收必然 OOM。
 *
 * 失败一律返回空列表/ null：抽不到帧只该让模型「看得少」，不该让整轮聊天失败。
 */
object VideoFramesExtractor {

    /** 一帧 JPEG（时间点 + 字节）。 */
    data class JpegFrame(val atMs: Long, val jpeg: ByteArray)

    /** 取视频时长（毫秒）；读不到返回 null（调用方只按大小挡）。 */
    fun durationMs(file: File): Long? {
        if (!file.isFile) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 系统选择器给的 `content://` 视频时长（毫秒）；读不到返回 null。
     *
     * 选视频时先读它：超过 2 分钟直接拒绝，不必先把几百 MB 复制进沙盒才发现超限。
     */
    fun durationMs(context: Context, uri: Uri): Long? = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } finally {
            runCatching { retriever.release() }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 视频首帧（气泡缩略图用）。
     *
     * 长边 ≤512px：缩略图最大也就 248dp，解一张 4K 原图会让列表滚动一顿一顿的。
     * 调用方负责 recycle。
     */
    fun poster(file: File, maxEdge: Int = 512): Bitmap? {
        if (!file.isFile) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val raw = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            scaleDown(raw, maxEdge)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 抽关键帧：场景变化优先，取不到就均匀采样；最多 [maxFrames] 帧。
     *
     * 两阶段：
     * - 阶段一（便宜）：均匀取候选帧，缩到 16×16 算灰度签名，相邻帧差异 = 场景分数；
     * - 阶段二（贵）：只对选中的时间点重新取帧、缩放、压 JPEG。
     *
     * 阶段一先 recycle 掉候选帧，阶段二再取一次——用一次多余的解码换「不会同时持有 24 张原图」。
     */
    fun extract(file: File, maxFrames: Int = AudioVideoPolicy.MAX_VIDEO_FRAMES): List<JpegFrame> {
        if (!file.isFile) return emptyList()
        val limit = AudioVideoPolicy.frameBudget(maxFrames)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L) ?: 0L
            val candidates = scoreCandidates(retriever, duration)
            val chosen = if (candidates.isEmpty()) {
                VideoFramePlan.fallbackSingle()
            } else {
                VideoFramePlan.select(candidates, duration, limit)
            }
            encodeChosen(retriever, chosen)
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ---------------------------------------------------------------- 实现

    private fun scoreCandidates(
        retriever: MediaMetadataRetriever,
        durationMs: Long
    ): List<FrameCandidate> {
        val times = if (durationMs > 0L) {
            VideoFramePlan.candidateTimes(durationMs)
        } else {
            listOf(0L)
        }
        val candidates = mutableListOf<FrameCandidate>()
        var previous: IntArray? = null
        times.forEach { atMs ->
            val frame = runCatching {
                retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull() ?: return@forEach
            try {
                val signature = lumaSignature(frame)
                candidates += FrameCandidate(
                    atMs = atMs,
                    sceneScore = VideoFramePlan.sceneScore(previous, signature)
                )
                previous = signature
            } finally {
                frame.recycle()
            }
        }
        return candidates
    }

    private fun encodeChosen(
        retriever: MediaMetadataRetriever,
        chosen: List<FrameCandidate>
    ): List<JpegFrame> {
        val result = mutableListOf<JpegFrame>()
        var totalBytes = 0L
        chosen.forEach { candidate ->
            val raw = runCatching {
                retriever.getFrameAtTime(candidate.atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull() ?: return@forEach
            val scaled = try {
                scaleDown(raw, AudioVideoPolicy.FRAME_MAX_EDGE_PX)
            } catch (_: Exception) {
                raw.recycle()
                null
            } ?: return@forEach
            try {
                val jpeg = encodeBounded(scaled, totalBytes) ?: return@forEach
                result += JpegFrame(candidate.atMs, jpeg)
                totalBytes += jpeg.size.toLong()
            } finally {
                if (scaled !== raw) scaled.recycle()
                raw.recycle()
            }
        }
        return result
    }

    /** 缩到长边 ≤[maxEdge]；本来就够小就返回原图（调用方判断是否同一个对象再回收）。 */
    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= 0) return bitmap
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /**
     * 16×16 灰度签名：先缩到 16×16 再取像素，代价与原始分辨率无关。
     *
     * 视频帧可能有 alpha（理论上没有），统一垫白底再取亮度，避免透明区域算成黑色。
     */
    private fun lumaSignature(bitmap: Bitmap): IntArray {
        val small = Bitmap.createBitmap(SIGNATURE_EDGE, SIGNATURE_EDGE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(small)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(
                bitmap,
                null,
                android.graphics.Rect(0, 0, SIGNATURE_EDGE, SIGNATURE_EDGE),
                null
            )
            val pixels = IntArray(SIGNATURE_EDGE * SIGNATURE_EDGE)
            small.getPixels(pixels, 0, SIGNATURE_EDGE, 0, 0, SIGNATURE_EDGE, SIGNATURE_EDGE)
            return IntArray(pixels.size) { index ->
                val color = pixels[index]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                (r * 299 + g * 587 + b * 114) / 1000
            }
        } finally {
            small.recycle()
        }
    }

    /** 按质量阶梯压到「单帧 ≤1 MiB、累计 ≤8 MiB」以内；压不进去就放弃这一帧。 */
    private fun encodeBounded(bitmap: Bitmap, alreadyBytes: Long): ByteArray? {
        val buffer = ByteArrayOutputStream()
        for (quality in QUALITY_LADDER) {
            buffer.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            val size = buffer.size()
            if (AudioVideoPolicy.frameFits(alreadyBytes, size)) return buffer.toByteArray()
        }
        return null
    }

    private const val SIGNATURE_EDGE = 16

    private val QUALITY_LADDER = intArrayOf(85, 78, 70, 62, 54, 46, 38)
}
