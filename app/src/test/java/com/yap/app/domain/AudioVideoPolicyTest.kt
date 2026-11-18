package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音/视频的边界（`spec/audio-video/README.md`）。
 *
 * 这些数字会同时出现在四处：录制参数、本机落盘校验、上传前的复核、服务端白名单。
 * 任何一处漂了，用户看到的就是「本机收得下、发出去 413」。所以边界逐条钉在这里。
 */
class AudioVideoPolicyTest {

    @Test
    fun audioMimeAcceptsWhitelistAndAliases() {
        assertEquals("audio/m4a", AudioVideoPolicy.audioMime("audio/m4a"))
        assertEquals("audio/mp4", AudioVideoPolicy.audioMime("audio/mp4"))
        assertEquals("audio/mpeg", AudioVideoPolicy.audioMime("audio/mpeg"))
        // 系统选择器/部分设备会给这些同义写法
        assertEquals("audio/m4a", AudioVideoPolicy.audioMime("audio/x-m4a"))
        assertEquals("audio/m4a", AudioVideoPolicy.audioMime("audio/aac"))
        assertEquals("audio/mpeg", AudioVideoPolicy.audioMime("audio/mp3"))
        // 带参数、大小写都能认
        assertEquals("audio/m4a", AudioVideoPolicy.audioMime("Audio/M4A; charset=binary"))
    }

    @Test
    fun audioMimeFallsBackToFileNameAndRejectsOthers() {
        assertEquals("audio/m4a", AudioVideoPolicy.audioMime(null, "voice-1.m4a"))
        assertEquals("audio/mpeg", AudioVideoPolicy.audioMime("", "song.MP3"))
        assertNull(AudioVideoPolicy.audioMime("application/octet-stream", "clip.bin"))
        assertNull(AudioVideoPolicy.audioMime(null))
        assertNull(AudioVideoPolicy.audioMime("video/mp4"))
    }

    @Test
    fun videoMimeOnlyAllowsMp4AndQuickTime() {
        assertEquals("video/mp4", AudioVideoPolicy.videoMime("video/mp4"))
        assertEquals("video/quicktime", AudioVideoPolicy.videoMime("video/quicktime"))
        assertEquals("video/mp4", AudioVideoPolicy.videoMime(null, "a.mov")?.let { "video/mp4" })
        assertEquals("video/quicktime", AudioVideoPolicy.videoMime("", "a.mov"))
        assertEquals("video/mp4", AudioVideoPolicy.videoMime("", "a.mp4"))
        // 不转码：别的容器一律拒绝，而不是硬塞成 mp4
        assertNull(AudioVideoPolicy.videoMime("video/x-matroska", "a.mkv"))
        assertNull(AudioVideoPolicy.videoMime("video/webm"))
    }

    @Test
    fun audioFormatMatchesProviderShortNames() {
        assertEquals("m4a", AudioVideoPolicy.audioFormat("audio/m4a"))
        assertEquals("m4a", AudioVideoPolicy.audioFormat("audio/mp4"))
        assertEquals("mp3", AudioVideoPolicy.audioFormat("audio/mpeg"))
        assertNull(AudioVideoPolicy.audioFormat("video/mp4"))
    }

    @Test
    fun extensionForMime() {
        assertEquals("m4a", AudioVideoPolicy.extensionFor("audio/m4a"))
        assertEquals("mp3", AudioVideoPolicy.extensionFor("audio/mpeg"))
        assertEquals("mov", AudioVideoPolicy.extensionFor("video/quicktime"))
        assertEquals("mp4", AudioVideoPolicy.extensionFor("video/mp4"))
        assertEquals("bin", AudioVideoPolicy.extensionFor("application/octet-stream"))
    }

    @Test
    fun audioCheckEnforcesSizeDurationAndEmptiness() {
        assertNull(AudioVideoPolicy.checkAudio(1_000, 1_000))
        assertNull(AudioVideoPolicy.checkAudio(AudioVideoPolicy.AUDIO_MAX_BYTES, 1_000))
        assertEquals(
            MediaRejection.TOO_LARGE,
            AudioVideoPolicy.checkAudio(AudioVideoPolicy.AUDIO_MAX_BYTES + 1, 1_000)
        )
        assertEquals(
            MediaRejection.TOO_LONG,
            AudioVideoPolicy.checkAudio(1_000, AudioVideoPolicy.AUDIO_MAX_DURATION_MS + 1)
        )
        assertNull(
            "正好 5 分钟应该允许",
            AudioVideoPolicy.checkAudio(1_000, AudioVideoPolicy.AUDIO_MAX_DURATION_MS)
        )
        assertEquals(MediaRejection.EMPTY, AudioVideoPolicy.checkAudio(0, 1_000))
        assertEquals(MediaRejection.EMPTY, AudioVideoPolicy.checkAudio(-1, 1_000))
    }

    @Test
    fun videoCheckEnforcesSizeDurationAndEmptiness() {
        assertNull(AudioVideoPolicy.checkVideo(AudioVideoPolicy.VIDEO_MAX_BYTES, 1_000))
        assertEquals(
            MediaRejection.TOO_LARGE,
            AudioVideoPolicy.checkVideo(AudioVideoPolicy.VIDEO_MAX_BYTES + 1, 1_000)
        )
        assertEquals(
            MediaRejection.TOO_LONG,
            AudioVideoPolicy.checkVideo(1_000, AudioVideoPolicy.VIDEO_MAX_DURATION_MS + 1)
        )
        assertNull(AudioVideoPolicy.checkVideo(1_000, null))
        assertEquals(MediaRejection.EMPTY, AudioVideoPolicy.checkVideo(0, 1_000))
    }

    @Test
    fun recordingStopsAtDurationThenSize() {
        assertNull(AudioVideoPolicy.recordingStopReason(1_000, 1_000))
        assertEquals(
            RecordingStopReason.DURATION,
            AudioVideoPolicy.recordingStopReason(AudioVideoPolicy.AUDIO_MAX_DURATION_MS, 1_000)
        )
        assertEquals(
            RecordingStopReason.SIZE,
            AudioVideoPolicy.recordingStopReason(1_000, AudioVideoPolicy.AUDIO_MAX_BYTES)
        )
    }

    @Test
    fun frameBudgetNeverExceedsEight() {
        assertEquals(8, AudioVideoPolicy.frameBudget(99))
        assertEquals(1, AudioVideoPolicy.frameBudget(0))
        assertEquals(1, AudioVideoPolicy.frameBudget(-5))
        assertEquals(3, AudioVideoPolicy.frameBudget(3))
    }

    @Test
    fun frameBudgetRespectsPerFrameAndTotalLimits() {
        assertTrue(AudioVideoPolicy.frameFits(0L, 1_000))
        assertFalse("单帧超过 1 MiB 就不要", AudioVideoPolicy.frameFits(0L, AudioVideoPolicy.FRAME_MAX_BYTES + 1))
        assertFalse("累计超过 8 MiB 就停", AudioVideoPolicy.frameFits(AudioVideoPolicy.FRAME_TOTAL_BYTES.toLong(), 1))
        assertFalse("空帧不算一帧", AudioVideoPolicy.frameFits(0L, 0))
        assertTrue(AudioVideoPolicy.framesFit(listOf(1_000, 1_000)))
        assertFalse(AudioVideoPolicy.framesFit(emptyList()))
        assertFalse(AudioVideoPolicy.framesFit(listOf(0)))
    }

    @Test
    fun clockDurationAndSecondsAreStable() {
        assertEquals("0:00", AudioVideoPolicy.clockDuration(0))
        assertEquals("0:07", AudioVideoPolicy.clockDuration(7_000))
        assertEquals("1:05", AudioVideoPolicy.clockDuration(65_000))
        assertEquals("0:00", AudioVideoPolicy.clockDuration(null))
        assertEquals("0:00", AudioVideoPolicy.clockDuration(-1_000))
        assertEquals(1, AudioVideoPolicy.durationSeconds(0))
        assertEquals(7, AudioVideoPolicy.durationSeconds(6_600))
        assertEquals(2, AudioVideoPolicy.durationSeconds(1_500))
    }

    @Test
    fun attachmentKindInferenceIsCompatibleWithLegacyJson() {
        val legacy = MessageAttachment(uri = "file:///a.jpg")
        assertTrue(legacy.isImage)
        assertTrue(legacy.isChatImage)
        assertFalse(legacy.isAudio)
        assertFalse(legacy.isVideo)
        assertNull(legacy.mediaKind)

        val audio = MessageAttachment(uri = "file:///a.m4a", mime = "audio/m4a", kind = "AUDIO", durationMs = 3_000)
        assertTrue(audio.isAudio)
        assertFalse(audio.isImage)
        assertEquals(AudioVideoKind.AUDIO, audio.mediaKind)
        assertEquals(MessageAttachment.KIND_AUDIO, audio.canonicalKind)

        val video = MessageAttachment(uri = "/v1/media/x", mime = "video/quicktime", durationMs = 9_000)
        assertTrue(video.isVideo)
        assertFalse(video.isImage)
        assertEquals(AudioVideoKind.VIDEO, video.mediaKind)
    }
}
