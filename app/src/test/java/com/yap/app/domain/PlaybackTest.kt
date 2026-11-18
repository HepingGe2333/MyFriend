package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放状态机（气泡上的播放/暂停按钮）。
 *
 * `MediaPlayer` 只能在设备上跑，但「点一下是播还是停」「播完按钮怎么回位」
 * 这些最容易出错的地方都能在这里钉住。
 */
class PlaybackTest {

    @Test
    fun idlePressPrepares() {
        assertEquals(PlaybackEvent.PREPARE, Playback.toggle(PlaybackStatus.IDLE))
        assertEquals(PlaybackStatus.PREPARING, Playback.next(PlaybackStatus.IDLE, PlaybackEvent.PREPARE))
    }

    @Test
    fun playingPressPausesAndPausedPressResumes() {
        assertEquals(PlaybackEvent.PAUSE, Playback.toggle(PlaybackStatus.PLAYING))
        assertEquals(PlaybackStatus.PAUSED, Playback.next(PlaybackStatus.PLAYING, PlaybackEvent.PAUSE))
        assertEquals(PlaybackEvent.RESUME, Playback.toggle(PlaybackStatus.PAUSED))
        assertEquals(PlaybackStatus.PLAYING, Playback.next(PlaybackStatus.PAUSED, PlaybackEvent.RESUME))
    }

    @Test
    fun completedPressReplays() {
        assertEquals(PlaybackEvent.RESUME, Playback.toggle(PlaybackStatus.COMPLETED))
        assertEquals(PlaybackStatus.PLAYING, Playback.next(PlaybackStatus.COMPLETED, PlaybackEvent.RESUME))
    }

    @Test
    fun preparingPressCancels() {
        assertEquals(PlaybackEvent.RESET, Playback.toggle(PlaybackStatus.PREPARING))
        assertEquals(PlaybackStatus.IDLE, Playback.next(PlaybackStatus.PREPARING, PlaybackEvent.RESET))
    }

    @Test
    fun failedPressTriesAgain() {
        assertEquals(PlaybackEvent.RESUME, Playback.toggle(PlaybackStatus.FAILED))
        assertEquals(PlaybackStatus.PLAYING, Playback.next(PlaybackStatus.FAILED, PlaybackEvent.RESUME))
    }

    @Test
    fun completionOnlyFromActiveStates() {
        assertEquals(PlaybackStatus.COMPLETED, Playback.next(PlaybackStatus.PLAYING, PlaybackEvent.COMPLETE))
        assertEquals(PlaybackStatus.COMPLETED, Playback.next(PlaybackStatus.PREPARING, PlaybackEvent.COMPLETE))
        assertEquals("没在播时收到 complete 不该改状态", PlaybackStatus.IDLE, Playback.next(PlaybackStatus.IDLE, PlaybackEvent.COMPLETE))
    }

    @Test
    fun pauseOnIdleKeepsState() {
        assertEquals(PlaybackStatus.IDLE, Playback.next(PlaybackStatus.IDLE, PlaybackEvent.PAUSE))
        assertEquals(PlaybackStatus.COMPLETED, Playback.next(PlaybackStatus.COMPLETED, PlaybackEvent.PAUSE))
    }

    @Test
    fun prepareWhilePlayingIsIgnored() {
        assertEquals(
            PlaybackStatus.PLAYING,
            Playback.next(PlaybackStatus.PLAYING, PlaybackEvent.PREPARE)
        )
    }

    @Test
    fun failureIsTerminalUntilReset() {
        assertEquals(PlaybackStatus.FAILED, Playback.next(PlaybackStatus.PLAYING, PlaybackEvent.FAIL))
        assertEquals(PlaybackStatus.FAILED, Playback.next(PlaybackStatus.FAILED, PlaybackEvent.PAUSE))
        assertEquals(PlaybackStatus.IDLE, Playback.next(PlaybackStatus.FAILED, PlaybackEvent.RESET))
    }

    @Test
    fun buttonStateMatchesExpectation() {
        assertTrue(Playback.showsPause(PlaybackStatus.PLAYING))
        assertFalse(Playback.showsPause(PlaybackStatus.PAUSED))
        assertFalse(Playback.showsPause(PlaybackStatus.IDLE))
        assertTrue(Playback.isActive(PlaybackStatus.PLAYING))
        assertTrue(Playback.isActive(PlaybackStatus.PREPARING))
        assertFalse(Playback.isActive(PlaybackStatus.PAUSED))
    }

    @Test
    fun progressIsClampedAndSafe() {
        assertEquals(0f, Playback.progress(0, 10_000), 0f)
        assertEquals(0.5f, Playback.progress(5_000, 10_000), 0.0001f)
        assertEquals(1f, Playback.progress(20_000, 10_000), 0f)
        assertEquals("时长为 0 不能除零", 0f, Playback.progress(1_000, 0), 0f)
        assertEquals(0f, Playback.progress(-1, 10_000), 0f)
    }

    @Test
    fun clockLabelShowsTotalBeforePlaybackAndProgressWhilePlaying() {
        assertEquals("0:12", Playback.clockLabel(0, 12_000, PlaybackStatus.IDLE))
        assertEquals("0:12", Playback.clockLabel(0, 12_000, PlaybackStatus.COMPLETED))
        assertEquals("0:05 / 0:12", Playback.clockLabel(5_000, 12_000, PlaybackStatus.PLAYING))
        assertEquals("0:05 / 0:12", Playback.clockLabel(5_000, 12_000, PlaybackStatus.PAUSED))
    }
}
