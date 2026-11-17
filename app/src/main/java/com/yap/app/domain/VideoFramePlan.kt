package com.yap.app.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 视频抽帧的**纯规划**：抽哪几个时间点、最多几帧、怎么把「场景变化优先」和
 * 「取不到就均匀采样」这两条规则落成可单测的函数。
 *
 * 真正的解码在 `util/VideoFrames`（`MediaMetadataRetriever`），它只负责：
 * 1. 按 [candidateTimes] 取候选帧；
 * 2. 给每帧算一个 [lumaSignature]（16×16 灰度），相邻帧的签名差就是「场景变化」分数；
 * 3. 把候选交给 [select]；
 * 4. 对选中的时间点重新取帧、缩到 ≤1024px、压成 JPEG。
 *
 * 这样「关键帧策略」不依赖 Android，JVM 单测能直接覆盖：场景变化大的帧优先、
 * 帧数永远 ≤8、时长为 0/负数的坏视频不会崩。
 */
data class FrameCandidate(
    /** 帧所在的时间点（毫秒）。 */
    val atMs: Long,
    /** 与前一帧的差异（0 表示完全一样，越大越像「换了个镜头」）。 */
    val sceneScore: Double = 0.0
)

object VideoFramePlan {

    /** 候选点数量：比最终帧数多，才有「挑」的余地。 */
    const val CANDIDATE_COUNT = 24

    /**
     * 均匀候选时间点（毫秒），跳过开头结尾各 5%，避免黑场/片尾。
     *
     * 时长 <=0 或太短时返回空列表（N=1 时也补一个中点，画面至少有一帧可看）。
     */
    fun candidateTimes(durationMs: Long, count: Int = CANDIDATE_COUNT): List<Long> {
        if (durationMs <= 0L || count <= 0) return emptyList()
        val start = (durationMs * 0.05).roundToLong().coerceAtLeast(0L)
        val end = (durationMs * 0.95).roundToLong().coerceAtMost(durationMs - 1)
        if (end <= start) return listOf((durationMs / 2).coerceAtLeast(0L))
        val span = end - start
        return (0 until count).map { index ->
            start + span * index / (count - 1).coerceAtLeast(1)
        }.distinct()
    }

    /**
     * 从候选里挑最多 [maxFrames] 帧。
     *
     * 规则（按优先级）：
     * 1. 先按 [FrameCandidate.sceneScore] 从大到小挑，且两帧至少间隔
     *    `duration / (maxFrames * 3)`，避免 8 帧全落在同一个镜头里；
     * 2. 分数都不高（整段没换镜头）时，用均匀采样把剩下的名额补齐；
     * 3. 输出按时间正序——模型看到的 8 张图要能顺着时间读，否则会更迷惑。
     *
     * 永远返回 ≤ maxFrames（也 ≤ [AudioVideoPolicy.MAX_VIDEO_FRAMES]）个元素。
     */
    fun select(
        candidates: List<FrameCandidate>,
        durationMs: Long,
        maxFrames: Int = AudioVideoPolicy.MAX_VIDEO_FRAMES
    ): List<FrameCandidate> {
        val limit = AudioVideoPolicy.frameBudget(maxFrames)
        val usable = candidates
            .filter { it.atMs >= 0L && (durationMs <= 0L || it.atMs <= durationMs) }
            .distinctBy { it.atMs }
            .sortedBy { it.atMs }
        if (usable.isEmpty()) return emptyList()
        if (usable.size <= limit) return usable
        val minGap = if (durationMs > 0L) {
            (durationMs / (limit * 3).coerceAtLeast(1)).coerceAtLeast(1L)
        } else {
            0L
        }
        val picked = mutableListOf<FrameCandidate>()
        // 1. 场景变化优先：分数高的先占位，但要有间隔。
        usable.sortedWith(
            compareByDescending<FrameCandidate> { it.sceneScore }.thenBy { it.atMs }
        ).forEach { candidate ->
            if (picked.size >= limit) return@forEach
            val tooClose = picked.any { existing -> abs(existing.atMs - candidate.atMs) < minGap }
            if (!tooClose) picked += candidate
        }
        // 2. 名额没占满：按均匀顺序补齐（用候选自身的时间顺序近似均匀）。
        if (picked.size < limit) {
            val step = usable.size.toDouble() / limit
            for (index in 0 until limit) {
                if (picked.size >= limit) break
                val candidate = usable[(index * step).toInt().coerceIn(0, usable.lastIndex)]
                if (picked.none { it.atMs == candidate.atMs }) picked += candidate
            }
        }
        // 3. 补到「至少 1 帧」：极端情况下候选都用不了，退回第一帧。
        if (picked.isEmpty()) picked += usable.first()
        return picked.sortedBy { it.atMs }.take(limit)
    }

    /** 时长为 0 / 负数时的兜底：只取一帧（0ms），不去猜。 */
    fun fallbackSingle(): List<FrameCandidate> = listOf(FrameCandidate(atMs = 0L))

    /**
     * 两帧灰度签名的差异分数：平均绝对差 / 255，落在 0..1。
     *
     * 长度不一致（坏数据）返回 0，不抛异常——抽帧失败只该少一帧，不该让整轮聊天失败。
     */
    fun sceneScore(previous: IntArray?, current: IntArray): Double {
        if (previous == null || previous.isEmpty() || previous.size != current.size) return 0.0
        var sum = 0L
        for (index in current.indices) {
            sum += abs(current[index].coerceIn(0, 255) - previous[index].coerceIn(0, 255)).toLong()
        }
        return (sum.toDouble() / current.size) / 255.0
    }

    /**
     * 给模型看的那一行：「视频：5 帧，时长 37 秒」。
     *
     * 这句话会写进这一轮 user 消息的正文里（`spec/audio-video/README.md`「模型侧」），
     * 让模型知道自己拿到的是抽帧而不是完整视频。
     */
    fun caption(frames: Int, durationMs: Long): String {
        val seconds = AudioVideoPolicy.durationSeconds(durationMs)
        return "视频：${frames} 帧，时长 $seconds 秒"
    }
}
