package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽帧策略（`spec/audio-video/README.md`「watch」）。
 *
 * 只测纯规划：场景变化优先、最多 8 帧、时长为 0/负数的坏视频不崩、输出按时间正序。
 * 真正的解码（`MediaMetadataRetriever`）在设备上，JVM 测不到——那一层的风险写在报告里。
 */
class VideoFramePlanTest {

    @Test
    fun candidateTimesSkipHeadAndTail() {
        val times = VideoFramePlan.candidateTimes(60_000)
        assertTrue(times.isNotEmpty())
        assertEquals("第一个候选不能是 0（片头常常是黑场）", false, times.first() == 0L)
        assertTrue("最后一个候选不能是视频末尾", times.last() < 60_000)
        assertEquals("候选点去重", times.size, times.distinct().size)
    }

    @Test
    fun candidateTimesHandleDegenerateDurations() {
        assertTrue(VideoFramePlan.candidateTimes(0).isEmpty())
        assertTrue(VideoFramePlan.candidateTimes(-1).isEmpty())
        // 极短视频：仍然给出候选（都在 0..duration 内），不返回空
        val short = VideoFramePlan.candidateTimes(100, count = 4)
        assertTrue(short.isNotEmpty())
        assertTrue(short.all { it in 0L..100L })
        // 时长为 0 时没有候选（真取不到帧，由 fallbackSingle 兜底）
        assertEquals(listOf(0L), VideoFramePlan.candidateTimes(1, count = 4).take(1))
    }

    @Test
    fun selectPrefersSceneChanges() {
        val candidates = (0 until 10).map { index ->
            FrameCandidate(atMs = index * 1_000L, sceneScore = if (index == 7) 0.9 else 0.01)
        }
        val chosen = VideoFramePlan.select(candidates, durationMs = 10_000, maxFrames = 4)
        assertTrue("场景变化最大的那一帧必须入选", chosen.any { it.atMs == 7_000L })
        assertTrue("最多 4 帧", chosen.size <= 4)
        assertEquals("输出按时间正序", chosen.map { it.atMs }.sorted(), chosen.map { it.atMs })
    }

    @Test
    fun selectNeverExceedsEightFrames() {
        val candidates = (0 until 100).map { FrameCandidate(it * 100L, it.toDouble()) }
        val chosen = VideoFramePlan.select(candidates, durationMs = 10_000, maxFrames = 32)
        assertTrue(chosen.size <= AudioVideoPolicy.MAX_VIDEO_FRAMES)
        assertTrue(chosen.isNotEmpty())
    }

    @Test
    fun selectFillsWithUniformSamplesWhenNothingChanges() {
        val candidates = (0 until 24).map { FrameCandidate(atMs = it * 1_000L, sceneScore = 0.0) }
        val chosen = VideoFramePlan.select(candidates, durationMs = 23_000, maxFrames = 8)
        assertEquals(8, chosen.size)
        assertEquals("时间正序", chosen.map { it.atMs }.sorted(), chosen.map { it.atMs })
    }

    @Test
    fun selectKeepsShortInputAsIs() {
        val candidates = listOf(FrameCandidate(0, 0.0), FrameCandidate(1_000, 0.1))
        assertEquals(candidates, VideoFramePlan.select(candidates, durationMs = 2_000, maxFrames = 8))
    }

    @Test
    fun selectDropsOutOfRangeAndDuplicates() {
        val candidates = listOf(
            FrameCandidate(-5, 1.0),
            FrameCandidate(1_000, 0.1),
            FrameCandidate(1_000, 0.2),
            FrameCandidate(99_000, 0.3)
        )
        val chosen = VideoFramePlan.select(candidates, durationMs = 10_000, maxFrames = 8)
        assertEquals(listOf(1_000L), chosen.map { it.atMs })
    }

    @Test
    fun selectHandlesEmptyInput() {
        assertTrue(VideoFramePlan.select(emptyList(), durationMs = 5_000).isEmpty())
        assertEquals(listOf(FrameCandidate(0L)), VideoFramePlan.fallbackSingle())
    }

    @Test
    fun sceneScoreIsBoundedAndSafe() {
        assertEquals(0.0, VideoFramePlan.sceneScore(null, IntArray(4) { 0 }), 0.0)
        assertEquals(0.0, VideoFramePlan.sceneScore(IntArray(4) { 0 }, IntArray(4) { 0 }), 0.0)
        assertEquals(1.0, VideoFramePlan.sceneScore(IntArray(4) { 0 }, IntArray(4) { 255 }), 0.0)
        // 长度不一致（坏数据）不崩，按 0 处理
        assertEquals(0.0, VideoFramePlan.sceneScore(IntArray(2) { 0 }, IntArray(4) { 255 }), 0.0)
    }

    @Test
    fun captionMatchesTheSpecWording() {
        assertEquals("视频：5 帧，时长 37 秒", VideoFramePlan.caption(5, 37_000))
        assertEquals("视频：1 帧，时长 1 秒", VideoFramePlan.caption(1, 200))
    }
}
