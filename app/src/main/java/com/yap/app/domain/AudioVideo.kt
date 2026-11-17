package com.yap.app.domain

import java.util.Locale

/**
 * 语音 / 视频输入的**纯策略**（`spec/audio-video/README.md`）。
 *
 * 这里不碰 Android：录制参数、大小与时长上限、mime 归一等全部是纯函数，
 * JVM 单测可以直接钉住「多大的音频算超限」「哪些 mime 认」这些边界。
 * 真正调用 `MediaRecorder` / `MediaMetadataRetriever` 的实现分别在
 * `util/VoiceRecorder` 与 `util/VideoFrames`（两者都很薄，只负责把字节交出来）。
 */
enum class AudioVideoKind {
    AUDIO,
    VIDEO
}

/** 一次发送前的检查结论：`null` 表示通过，否则是拒绝原因。 */
enum class MediaRejection {
    EMPTY,
    TOO_LARGE,
    TOO_LONG,
    UNSUPPORTED_FORMAT
}

/** 录音到上限时的自动停止原因（UI 用一句话解释为什么自己停了）。 */
enum class RecordingStopReason {
    DURATION,
    SIZE
}

/**
 * 语音 / 视频的 mime、上限与录制参数。
 *
 * 所有数字都与 `spec/audio-video/README.md` 和 `agentd` 的服务端白名单一致；
 * 两处一旦分叉，会出现「本机收得下、服务端 413」这种最难解释的失败。
 */
object AudioVideoPolicy {

    // ---------------------------------------------------------------- 大小与时长

    /** 音频单文件上限：8 MiB。 */
    const val AUDIO_MAX_BYTES: Long = 8L * 1024 * 1024

    /** 视频单文件上限：64 MiB（不转码，超了就拒绝并说清楚）。 */
    const val VIDEO_MAX_BYTES: Long = 64L * 1024 * 1024

    /** 一条语音最长 5 分钟，到点自动停。 */
    const val AUDIO_MAX_DURATION_MS: Long = 5L * 60 * 1000

    /** 选的视频最长 2 分钟（不做转码，所以只能拒绝超长的那条）。 */
    const val VIDEO_MAX_DURATION_MS: Long = 2L * 60 * 1000

    // ---------------------------------------------------------------- 录制参数

    /** 采样率 16 kHz：语音够清楚，体积只有 44.1 kHz 的三分之一。 */
    const val AUDIO_SAMPLE_RATE = 16_000

    /** 单声道：人声不需要立体声，也省一半体积。 */
    const val AUDIO_CHANNELS = 1

    /** AAC 64 kbps：16 kHz 单声道在这个码率下听感已经接近原声。 */
    const val AUDIO_BIT_RATE = 64_000

    /** 录出来的容器是 MPEG-4，扩展名 `.m4a`。 */
    const val AUDIO_CONTAINER_EXTENSION = "m4a"

    /** 太短的误触不发送（例如手指刚碰到按钮就抬起）。 */
    const val MIN_RECORDING_MS: Long = 800

    // ---------------------------------------------------------------- 视频抽帧

    /** 一轮最多带 8 帧给模型看。 */
    const val MAX_VIDEO_FRAMES = 8

    /** 每帧长边 ≤1024px。 */
    const val FRAME_MAX_EDGE_PX = 1024

    /** 一帧的 JPEG 体积上限（8 帧合计还要再受 [FRAME_TOTAL_BYTES] 约束）。 */
    const val FRAME_MAX_BYTES = 1024 * 1024

    /** 所有帧加起来的字节上限：8 MiB。 */
    const val FRAME_TOTAL_BYTES = 8 * 1024 * 1024

    // ---------------------------------------------------------------- mime

    /** 认可的音频 mime（与 `agentd` 的媒体白名单逐字一致）。 */
    val AUDIO_MIMES: Set<String> = setOf("audio/m4a", "audio/mp4", "audio/mpeg")

    /** 认可的 mime 之外还容忍的几个同义写法（安卓相册/系统选择器会给这些）。 */
    private val AUDIO_ALIASES: Map<String, String> = mapOf(
        "audio/aac" to "audio/m4a",
        "audio/x-m4a" to "audio/m4a",
        "audio/mp3" to "audio/mpeg",
        "audio/x-mp3" to "audio/mpeg"
    )

    /** 认可的 mime 之外还容忍的同义写法（QuickTime 的 macOS 写法）。 */
    private val VIDEO_ALIASES: Map<String, String> = mapOf(
        "video/x-m4v" to "video/mp4"
    )

    /** 认可的 mime 之外还容忍的同义写法（QuickTime 的 macOS 写法）。 */
    val VIDEO_MIMES: Set<String> = setOf("video/mp4", "video/quicktime")

    /**
     * 把声明 mime / 文件名 / 前缀都认一遍，归一到白名单里的那三种音频 mime。
     *
     * 认不出返回 null：宁可让调用方拒绝，也不要把一个 `application/octet-stream`
     * 硬塞成音频发给服务端。
     */
    fun audioMime(declared: String?, fileName: String? = null): String? {
        canonical(declared, AUDIO_ALIASES, AUDIO_MIMES)?.let { return it }
        val lower = fileName?.lowercase(Locale.US).orEmpty()
        return when {
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> "audio/m4a"
            lower.endsWith(".mp4") -> "audio/mp4"
            lower.endsWith(".mp3") -> "audio/mpeg"
            else -> null
        }
    }

    /** 视频 mime 归一：只认 mp4 / quicktime，其它一律 null（不转码，也不假装认得）。 */
    fun videoMime(declared: String?, fileName: String? = null): String? {
        canonical(declared, VIDEO_ALIASES, VIDEO_MIMES)?.let { return it }
        val lower = fileName?.lowercase(Locale.US).orEmpty()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mov") -> "video/quicktime"
            else -> null
        }
    }

    fun isAudioMime(mime: String?): Boolean = audioMime(mime) != null

    fun isVideoMime(mime: String?): Boolean = videoMime(mime) != null

    /**
     * 给 provider 的 `input_audio.format`：OpenAI 兼容接口只认 `wav` / `mp3` / `m4a` 这几种短名。
     *
     * m4a 容器（AAC）在两家实现里都叫 `m4a`；`audio/mp4` 也是同一个容器。
     */
    fun audioFormat(mime: String?): String? = when (audioMime(mime)) {
        "audio/mpeg" -> "mp3"
        "audio/m4a", "audio/mp4" -> "m4a"
        else -> null
    }

    /** 落盘文件名用的扩展名。 */
    fun extensionFor(mime: String?): String = when (audioMime(mime)) {
        "audio/mpeg" -> "mp3"
        "audio/m4a", "audio/mp4" -> "m4a"
        else -> videoMime(mime)?.let { if (it == "video/quicktime") "mov" else "mp4" } ?: "bin"
    }

    // ---------------------------------------------------------------- 检查

    /** 发一条语音前的检查：读不到字节算空，超 8 MiB / 超 5 分钟都拒绝。 */
    fun checkAudio(sizeBytes: Long, durationMs: Long?): MediaRejection? = when {
        sizeBytes <= 0L -> MediaRejection.EMPTY
        sizeBytes > AUDIO_MAX_BYTES -> MediaRejection.TOO_LARGE
        durationMs != null && durationMs > AUDIO_MAX_DURATION_MS -> MediaRejection.TOO_LONG
        else -> null
    }

    /** 发一条视频前的检查：超 64 MiB / 超 2 分钟都拒绝（不转码，所以不裁剪）。 */
    fun checkVideo(sizeBytes: Long, durationMs: Long?): MediaRejection? = when {
        sizeBytes <= 0L -> MediaRejection.EMPTY
        sizeBytes > VIDEO_MAX_BYTES -> MediaRejection.TOO_LARGE
        durationMs != null && durationMs > VIDEO_MAX_DURATION_MS -> MediaRejection.TOO_LONG
        else -> null
    }

    /** 录音过程中是否该自动停：先看时长（5 分钟），再看体积（8 MiB）。 */
    fun recordingStopReason(elapsedMs: Long, sizeBytes: Long): RecordingStopReason? = when {
        elapsedMs >= AUDIO_MAX_DURATION_MS -> RecordingStopReason.DURATION
        sizeBytes >= AUDIO_MAX_BYTES -> RecordingStopReason.SIZE
        else -> null
    }

    /** 帧数上限：算上「时长为 0 / 文件坏」的边界，永远不超过 [MAX_VIDEO_FRAMES]。 */
    fun frameBudget(requested: Int = MAX_VIDEO_FRAMES): Int = requested.coerceIn(1, MAX_VIDEO_FRAMES)

    /** 一帧能不能进这一轮：单帧与总量都受限。 */
    fun frameFits(alreadyBytes: Long, frameBytes: Int): Boolean {
        if (frameBytes <= 0) return false
        if (frameBytes > FRAME_MAX_BYTES) return false
        return alreadyBytes + frameBytes <= FRAME_TOTAL_BYTES
    }

    /** 累计帧字节是否还在总量内。 */
    fun framesFit(sizes: List<Int>): Boolean {
        if (sizes.isEmpty()) return false
        if (sizes.any { it <= 0 || it > FRAME_MAX_BYTES }) return false
        return sizes.sumOf { it.toLong() } <= FRAME_TOTAL_BYTES
    }

    // ---------------------------------------------------------------- 显示

    /**
     * 时长的短写法：`0:07` / `1:23`。
     *
     * 不是本地化文案（数字与冒号在中英文里相同），所以放在 domain 里；
     * 「12 秒前」这类带语言的句子走 `R.string.*`。
     */
    fun clockDuration(durationMs: Long?): String {
        val total = ((durationMs ?: 0L).coerceAtLeast(0L)) / 1000
        val minutes = total / 60
        val seconds = total % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    /** 秒数（四舍五入到整秒，至少 1 秒）：提示词与气泡副标题都用它。 */
    fun durationSeconds(durationMs: Long?): Int {
        val ms = (durationMs ?: 0L).coerceAtLeast(0L)
        return ((ms + 500) / 1000).toInt().coerceAtLeast(1)
    }

    private fun canonical(
        declared: String?,
        aliases: Map<String, String>,
        allowed: Set<String>
    ): String? {
        val value = declared?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
        if (value.isEmpty()) return null
        aliases[value]?.let { return it }
        return value.takeIf { it in allowed }
    }
}
