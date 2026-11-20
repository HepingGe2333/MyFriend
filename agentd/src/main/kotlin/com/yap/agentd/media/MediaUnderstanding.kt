package com.yap.agentd.media

import com.yap.agentd.store.FileStore
import com.yap.agentd.store.MediaItem
import com.yap.agentd.store.MediaKind
import com.yap.agentd.store.MediaMime
import com.yap.agentd.store.MediaUrl
import java.nio.file.Path
import java.util.Base64

/**
 * `listen` / `watch` 两个工具的**媒体解析与抽取层**（`spec/audio-video/README.md`）：
 * 工具层只负责把结果拼进模型请求，这里负责「能不能听 / 能不能看 / 为什么不能」。
 *
 * 设计取舍：
 * - **失败不抛异常**，统一返回 [MediaLookup.Failure] 的中文原因：这句话会原样回灌给模型，
 *   由模型决定怎么跟用户解释（「这段视频太大了」比一个 500 更有用）。调用方不需要 try/catch；
 * - **只读**：不写 `FileStore`、不联网；唯一的磁盘活动是 [FfmpegFrameExtractor] 自己的临时目录；
 * - 大小上限按 `spec/audio-video` 的硬约束（音频 8 MB、视频 64 MB）在**读字节之前**先按元数据
 *   拦一次，读完再用实际字节数拦一次（元数据被手工改过时也不会把 64 MB 读进堆里两次）；
 * - 抽帧器可注入（[FrameExtractor]）：单测用假抽取器断言「帧数/JPEG 校验/不可用」，
 *   生产用系统 `ffmpeg`（[FfmpegFrameExtractor]），**没有解码器就明确失败，绝不伪造帧**。
 */

/** 一段能直接发给模型的音频：base64 原始字节 + provider 认的格式名。 */
data class AudioPayload(
    val mediaId: String,
    val base64: String,
    val format: String,   // "m4a" | "mp3" | "wav" | "ogg"
    val mime: String,
    val bytes: Int
)

/** 抽出来的视频关键帧（JPEG 原始字节，按时间顺序）。 */
data class VideoFrames(
    val mediaId: String,
    val jpegs: List<ByteArray>,
    val mime: String
) {
    val frameCount: Int get() = jpegs.size
}

/** 解析结果：要么拿到可直接发模型的媒体，要么是一句明确的中文失败原因（给模型看，不抛异常）。 */
sealed class MediaLookup {
    data class Audio(val payload: AudioPayload) : MediaLookup()
    data class Video(val frames: VideoFrames) : MediaLookup()
    data class Failure(val reason: String) : MediaLookup()
}

/**
 * 视频抽帧器。
 *
 * 为什么是 `fun interface` 但 [available] 带默认实现：Kotlin 的函数式接口**只能有一个抽象方法**，
 * 所以「是否可用」给了默认值 `true`（假抽取器直接写 `FrameExtractor { … }` 就够用），真实实现
 * （[FfmpegFrameExtractor]）覆盖它。工具层两种都按同一签名调用，不影响集成。
 */
fun interface FrameExtractor {
    /** 系统解码器是否可用；不可用时 watch 必须明确失败，绝不能伪造帧。 */
    fun available(): Boolean = true

    /** 从视频字节里抽 ≤maxFrames 帧 JPEG（长边 ≤maxEdge）。失败返回空列表，不抛异常。 */
    fun extract(video: ByteArray, mime: String, maxFrames: Int, maxEdge: Int): List<ByteArray>
}

class MediaUnderstanding(
    private val store: FileStore,
    private val extractor: FrameExtractor = FfmpegFrameExtractor(),
    /**
     * 临时目录根（默认系统临时目录）；测试可注入。
     *
     * 抽帧的工作目录现在由 [FrameExtractor] 自己在系统临时目录下建（`yap-watch-*`，见
     * [FfmpegFrameExtractor]），所以这一层暂时不落盘；保留这个入口是为了让调用方/测试明确
     * 「媒体理解不写数据目录」，将来要把工作目录钉在别处时不必改构造签名。
     */
    @Suppress("unused")
    private val tempRoot: Path? = null
) {

    /**
     * 听一段语音。`mediaId` 为空 → 这段对话里**最近**一条带音频附件的消息。
     *
     * 顺序：解析出 [MediaItem] → mime 必须是音频 → 大小上限 → 读字节（再校验一次大小）→ base64。
     * 任何一步不过都返回明确的中文 [MediaLookup.Failure]。
     */
    fun listen(sessionId: String, mediaId: String?): MediaLookup {
        val explicit = mediaId?.trim()?.takeIf { it.isNotEmpty() }
        val item = if (explicit != null) {
            val id = MediaUrl.idOf(explicit) ?: return MediaLookup.Failure(NO_SUCH_AUDIO)
            store.media(id) ?: return MediaLookup.Failure(NO_SUCH_AUDIO)
        } else {
            latestMedia(sessionId, MediaKind.AUDIO) ?: return MediaLookup.Failure(NO_AUDIO)
        }
        val mime = MediaMime.normalize(item.mime) ?: item.mime
        if (mime !in AUDIO_MIMES) return MediaLookup.Failure(NOT_AUDIO)
        if (item.bytes > MAX_AUDIO_BYTES) return MediaLookup.Failure(AUDIO_TOO_LARGE)
        val bytes = store.mediaBytes(item.id) ?: return MediaLookup.Failure(AUDIO_MISSING)
        // 元数据说「没超」但文件被换过/被手工改过：按真实字节再拦一次，绝不把超限内容塞进请求。
        if (bytes.size > MAX_AUDIO_BYTES) return MediaLookup.Failure(AUDIO_TOO_LARGE)
        return MediaLookup.Audio(
            AudioPayload(
                mediaId = item.id,
                base64 = Base64.getEncoder().encodeToString(bytes),
                format = audioFormat(mime),
                mime = mime,
                bytes = bytes.size
            )
        )
    }

    /**
     * 看一段视频。`mediaId` 为空 → 这段对话里**最近**一条带视频附件的消息。
     *
     * 顺序：解析出 [MediaItem] → mime 必须是视频 → 大小上限 → 读字节 → 解码器是否可用 →
     * 抽帧（截到 [MAX_FRAMES] 帧、丢掉非 JPEG 的帧）。没有解码器时理由是固定的一句
     * [NO_DECODER]，**不会**用空列表冒充成功。
     */
    fun watch(sessionId: String, mediaId: String?): MediaLookup {
        val explicit = mediaId?.trim()?.takeIf { it.isNotEmpty() }
        val item = if (explicit != null) {
            val id = MediaUrl.idOf(explicit) ?: return MediaLookup.Failure(NO_SUCH_VIDEO)
            store.media(id) ?: return MediaLookup.Failure(NO_SUCH_VIDEO)
        } else {
            latestMedia(sessionId, MediaKind.VIDEO) ?: return MediaLookup.Failure(NO_VIDEO)
        }
        val mime = MediaMime.normalize(item.mime) ?: item.mime
        if (mime !in VIDEO_MIMES) return MediaLookup.Failure(NOT_VIDEO)
        if (item.bytes > MAX_VIDEO_BYTES) return MediaLookup.Failure(VIDEO_TOO_LARGE)
        val bytes = store.mediaBytes(item.id) ?: return MediaLookup.Failure(VIDEO_MISSING)
        if (bytes.size > MAX_VIDEO_BYTES) return MediaLookup.Failure(VIDEO_TOO_LARGE)
        if (!extractor.available()) return MediaLookup.Failure(NO_DECODER)
        // 约定里 extract 不抛异常；这里再兜一层，工具层永远只看到 Failure/Video。
        val raw = try {
            extractor.extract(bytes, mime, MAX_FRAMES, MAX_FRAME_EDGE)
        } catch (_: Exception) {
            emptyList()
        }
        val jpegs = raw.asSequence().filter { isJpeg(it) }.take(MAX_FRAMES).toList()
        if (jpegs.isEmpty()) return MediaLookup.Failure(NO_FRAMES)
        return MediaLookup.Video(VideoFrames(mediaId = item.id, jpegs = jpegs, mime = MediaMime.JPEG))
    }

    /**
     * 会话里最近一条「带对应媒体附件」的消息里的媒体。
     *
     * - 只看最近 [RECENT_MESSAGE_LIMIT] 条，且 **includeBlocked = false**：拉黑期间落库的消息
     *   里的媒体不能看/听（`spec/experience` §2），它们的附件在这里根本不会成为候选；
     * - 附件引用允许裸 id 或 `/v1/media/<id>`，统一走 [MediaUrl.idOf]；
     * - 媒体元数据已删（附件指向一个不存在的 id）时跳过、继续往前找（删掉的那份本来也放不出来）。
     */
    private fun latestMedia(sessionId: String, kind: String): MediaItem? {
        val messages = try {
            store.latestMessages(sessionId, RECENT_MESSAGE_LIMIT, includeBlocked = false)
        } catch (_: NoSuchElementException) {
            // 会话不存在：和「会话里没有音频/视频」等价，交给调用方给一句人话。
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        for (message in messages.asReversed()) {
            for (reference in message.attachments.asReversed()) {
                val id = MediaUrl.idOf(reference) ?: continue
                val item = store.media(id) ?: continue
                if (item.kind == kind) return item
            }
        }
        return null
    }

    companion object {
        const val MAX_FRAMES = 8
        const val MAX_FRAME_EDGE = 1024
        const val MAX_AUDIO_BYTES = 8 * 1024 * 1024       // spec/audio-video：音频 ≤8 MB
        const val MAX_VIDEO_BYTES = 64 * 1024 * 1024      // spec/audio-video：视频 ≤64 MB
    }
}

// ---------------------------------------------------------------------- 内部规则

/** 「最近媒体」只扫最近这么多条消息：语音/视频本来就是刚发的，扫全历史没有意义。 */
private const val RECENT_MESSAGE_LIMIT = 50

/** provider 的 `input_audio.format` 只认这几个名字；服务端能收的音频 mime 都落在这里。 */
private val AUDIO_MIMES = setOf(MediaMime.M4A, MediaMime.AUDIO_MP4, MediaMime.MPEG)
private val VIDEO_MIMES = setOf(MediaMime.VIDEO_MP4, MediaMime.QUICKTIME)

// 失败原因：这些字符串会直接回灌给模型，所以都是完整的一句话（不是错误码）。
private const val NO_AUDIO = "这段对话里没有可以听的语音"
private const val NO_SUCH_AUDIO = "没有这个音频（可能已删除）"
private const val NOT_AUDIO = "这不是音频"
private const val AUDIO_TOO_LARGE = "音频太大（上限 8 MB）"
private const val AUDIO_MISSING = "音频文件不见了"

private const val NO_VIDEO = "这段对话里没有可以看的视频"
private const val NO_SUCH_VIDEO = "没有这个视频（可能已删除）"
private const val NOT_VIDEO = "这不是视频"
private const val VIDEO_TOO_LARGE = "视频太大（上限 64 MB）"
private const val VIDEO_MISSING = "视频文件不见了"

/** 工具层/测试逐字断言这一句：没有解码器就说没有，不许拿空帧冒充「看到了」。 */
private const val NO_DECODER = "服务器没有视频解码器，看不了视频"
private const val NO_FRAMES = "没能从这段视频里抽出画面"

/** JPEG 文件头（`FF D8`）；抽帧结果里混进来的非 JPEG 一律丢掉。 */
private fun isJpeg(bytes: ByteArray): Boolean =
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

/** mime → provider 的 `format` 名（未知音频 mime 兜底 `m4a`，与服务端上传默认一致）。 */
private fun audioFormat(mime: String): String = when (mime) {
    MediaMime.M4A, MediaMime.AUDIO_MP4 -> "m4a"
    MediaMime.MPEG -> "mp3"
    else -> "m4a"
}
