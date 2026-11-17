package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送前的语音/视频复核（0.13，任务 5）。
 *
 * 选片时已经查过一次，但「文件在选完到发送之间被换掉/损坏」是真会发生的；
 * 这里钉住四条边界：2 分钟 / 64 MiB / 5 分钟 / 8 MiB，以及「读不到时长只按大小挡」。
 */
class MediaSendGuardTest {

    private fun audio(size: Long = 1024, durationMs: Long? = 10_000) =
        MediaSendGuard.Candidate(AudioVideoKind.AUDIO, size, durationMs)

    private fun video(size: Long = 2048, durationMs: Long? = 10_000) =
        MediaSendGuard.Candidate(AudioVideoKind.VIDEO, size, durationMs)

    @Test
    fun `正常语音视频通过`() {
        assertNull(MediaSendGuard.firstRejection(listOf(audio(), video())))
        assertNull(MediaSendGuard.firstRejection(emptyList()))
    }

    @Test
    fun `视频超过两分钟被拒绝`() {
        val rejection = MediaSendGuard.firstRejection(
            listOf(video(durationMs = AudioVideoPolicy.VIDEO_MAX_DURATION_MS + 1))
        )
        assertEquals(MediaRejection.TOO_LONG, rejection?.reason)
        assertTrue(!rejection!!.candidate.isAudio)
    }

    @Test
    fun `视频超过六十四兆被拒绝`() {
        val rejection = MediaSendGuard.firstRejection(
            listOf(video(size = AudioVideoPolicy.VIDEO_MAX_BYTES + 1))
        )
        assertEquals(MediaRejection.TOO_LARGE, rejection?.reason)
    }

    @Test
    fun `语音超过五分钟被拒绝`() {
        val rejection = MediaSendGuard.firstRejection(
            listOf(audio(durationMs = AudioVideoPolicy.AUDIO_MAX_DURATION_MS + 1))
        )
        assertEquals(MediaRejection.TOO_LONG, rejection?.reason)
        assertTrue(rejection!!.candidate.isAudio)
    }

    @Test
    fun `语音超过八兆被拒绝`() {
        val rejection = MediaSendGuard.firstRejection(
            listOf(audio(size = AudioVideoPolicy.AUDIO_MAX_BYTES + 1))
        )
        assertEquals(MediaRejection.TOO_LARGE, rejection?.reason)
    }

    @Test
    fun `刚好等于上限时通过`() {
        assertNull(
            MediaSendGuard.firstRejection(
                listOf(
                    video(size = AudioVideoPolicy.VIDEO_MAX_BYTES, durationMs = AudioVideoPolicy.VIDEO_MAX_DURATION_MS),
                    audio(size = AudioVideoPolicy.AUDIO_MAX_BYTES, durationMs = AudioVideoPolicy.AUDIO_MAX_DURATION_MS)
                )
            )
        )
    }

    @Test
    fun `读不到时长时只按大小挡`() {
        assertNull(MediaSendGuard.firstRejection(listOf(video(durationMs = null))))
        assertEquals(
            MediaRejection.TOO_LARGE,
            MediaSendGuard.firstRejection(listOf(video(size = AudioVideoPolicy.VIDEO_MAX_BYTES + 1, durationMs = null)))?.reason
        )
    }

    @Test
    fun `空文件按不可读处理`() {
        val rejection = MediaSendGuard.firstRejection(listOf(audio(size = 0)))
        assertEquals(MediaRejection.EMPTY, rejection?.reason)
        assertTrue(MediaSendGuard.isUnreadable(rejection!!.reason))
        assertTrue(!MediaSendGuard.isUnreadable(MediaRejection.TOO_LARGE))
    }

    @Test
    fun `返回第一条超限项而不是全部`() {
        val rejection = MediaSendGuard.firstRejection(
            listOf(
                audio(durationMs = AudioVideoPolicy.AUDIO_MAX_DURATION_MS + 1),
                video(durationMs = AudioVideoPolicy.VIDEO_MAX_DURATION_MS + 1)
            )
        )
        assertTrue(rejection!!.candidate.isAudio)
    }
}
