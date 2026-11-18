package com.yap.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.yap.app.domain.AudioVideoPolicy
import com.yap.app.domain.RecordingStopReason
import java.io.File
import java.util.UUID

/**
 * 按住说话的录音器（0.13）：`MediaRecorder` + AAC 单声道 16 kHz，落成 `.m4a`。
 *
 * 参数来自 [AudioVideoPolicy]（不是这里另写一套）：采样率 16 kHz、单声道、64 kbps，
 * 与规格 `spec/audio-video/README.md` 的「AAC 单声道 16 kHz、≤5 分钟、≤8 MB」逐条对齐。
 *
 * 两道上限都在**录制过程中**生效（`setMaxDuration` / `setMaxFileSize`）：
 * 到点由系统停，[onAutoStop] 通知界面「该把这段发出去了」，而不是等 5 分钟后再发现文件太大。
 *
 * 落盘用的是同目录 `.tmp` 文件：录制中途 App 被杀，目录里只会多一个 `.tmp`，
 * 不会出现一条「看起来能播、实际是半截」的语音。真正改名与校验在
 * `MediaStorage.persistRecordedAudio` 里（原子改名 + 8 MiB / 5 分钟复核）。
 *
 * 线程约定：`start/stop/cancel` 都在主线程调用（MediaRecorder 的常规用法）；
 * [onAutoStop] 可能在系统回调线程上触发，接收方自己切回主线程。
 */
class VoiceRecorder(private val context: Context) {

    /** 一次录制的结果（临时文件，尚未改名成正式语音）。 */
    data class Clip(
        val tempFile: File,
        val durationMs: Long,
        val mime: String = "audio/m4a",
        val stopReason: RecordingStopReason? = null
    )

    /** 到时长/体积上限被系统自动停：界面据此直接发送，不必再让用户松手。 */
    var onAutoStop: (() -> Unit)? = null

    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L
    private var stopReason: RecordingStopReason? = null

    /** 正在录（且真的拿到过输入）。 */
    val isRecording: Boolean get() = recorder != null && startedAt > 0L

    /** 已经录了多久（毫秒）：界面上的计时胶囊用它。 */
    fun elapsedMs(): Long =
        if (startedAt <= 0L) 0L else (SystemClock.elapsedRealtime() - startedAt)

    /**
     * 开始录音。
     *
     * 权限由界面层申请（`ActivityResultContracts.RequestPermission`），拿到才调这里：
     * 所以这里显式 `@SuppressLint("MissingPermission")`——不是"没检查"，是"在调用方检查"。
     *
     * 返回 false 表示这次开不了（没权限 / 麦克风被别的 App 占着 / 目录写不进去），
     * 已经清理干净，调用方只需要提示一句，不用再收尾。
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recorder != null) cancel()
        val directory = MediaStorage.audioDirectory(context)
        if (!directory.isDirectory) return false
        val temp = File(directory, "voice-${UUID.randomUUID()}.tmp")
        val created = createRecorder() ?: return false
        return try {
            created.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(AudioVideoPolicy.AUDIO_SAMPLE_RATE)
                setAudioChannels(AudioVideoPolicy.AUDIO_CHANNELS)
                setAudioEncodingBitRate(AudioVideoPolicy.AUDIO_BIT_RATE)
                setOutputFile(temp.absolutePath)
                setMaxDuration(AudioVideoPolicy.AUDIO_MAX_DURATION_MS.toInt())
                setMaxFileSize(AudioVideoPolicy.AUDIO_MAX_BYTES)
                setOnInfoListener { _, what, _ ->
                    val reason = when (what) {
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> RecordingStopReason.DURATION
                        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> RecordingStopReason.SIZE
                        else -> null
                    } ?: return@setOnInfoListener
                    stopReason = reason
                    onAutoStop?.invoke()
                }
                prepare()
                start()
            }
            recorder = created
            target = temp
            startedAt = SystemClock.elapsedRealtime()
            stopReason = null
            true
        } catch (_: Exception) {
            runCatching { created.reset() }
            runCatching { created.release() }
            temp.delete()
            recorder = null
            target = null
            startedAt = 0L
            false
        }
    }

    /**
     * 停止并交出临时文件。
     *
     * 已经被系统自动停过（到 5 分钟/8 MB）时 `stop()` 会抛 `IllegalStateException`——
     * 这是正常路径，不是错误：吞掉它，照样把文件与时长交出去。
     */
    fun stop(): Clip? {
        val active = recorder ?: return null
        val file = target
        val duration = elapsedMs().coerceAtLeast(0L)
        val reason = stopReason
        recorder = null
        target = null
        startedAt = 0L
        stopReason = null
        runCatching { active.stop() }
        runCatching { active.reset() }
        runCatching { active.release() }
        if (file == null || !file.isFile || file.length() <= 0L) {
            file?.delete()
            return null
        }
        return Clip(tempFile = file, durationMs = duration, stopReason = reason)
    }

    /** 放弃这一段（用户滑开/切页面）：文件删掉，什么也不留下。 */
    fun cancel() {
        val active = recorder
        recorder = null
        target?.delete()
        target = null
        startedAt = 0L
        stopReason = null
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.reset() }
            runCatching { active.release() }
        }
    }

    private fun createRecorder(): MediaRecorder? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    } catch (_: Exception) {
        null
    }
}
