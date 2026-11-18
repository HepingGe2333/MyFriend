package com.yap.app.domain

/**
 * 语音/视频播放的**纯状态机**（`spec/audio-video/README.md`「气泡」）。
 *
 * 真正的播放器是 `android.media.MediaPlayer` / `VideoView`，它们只能跑在设备上；
 * 但「点一下应该开始还是暂停」「播完了按钮该回到三角形」「进度条怎么算」
 * 这些容易出错的地方全是纯逻辑，放在这里就能被 JVM 单测钉住。
 *
 * 约定：
 * - 一条媒体在界面上同一时刻只有一个状态（列表里滚动不会重置它）；
 * - [PlaybackStatus.COMPLETED] 之后再点等于重播（先 seek 到 0 再 start）；
 * - 出错（文件没了/服务器 401）落到 [PlaybackStatus.FAILED]，界面显示一句「打不开」，
 *   **不要**自动重试——那会让用户以为按钮坏了。
 */
enum class PlaybackStatus {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    COMPLETED,
    FAILED
}

/** 播放器上可能发生的事件（由 UI/MediaPlayer 回调发出）。 */
enum class PlaybackEvent {
    PREPARE,
    START,
    PAUSE,
    RESUME,
    COMPLETE,
    FAIL,
    RESET
}

object Playback {

    /** 状态迁移；任何「不该发生」的事件都保持原状态，不崩溃。 */
    fun next(current: PlaybackStatus, event: PlaybackEvent): PlaybackStatus = when (event) {
        PlaybackEvent.PREPARE -> when (current) {
            PlaybackStatus.PLAYING -> current
            else -> PlaybackStatus.PREPARING
        }
        PlaybackEvent.START -> PlaybackStatus.PLAYING
        PlaybackEvent.PAUSE ->
            if (current == PlaybackStatus.PLAYING) PlaybackStatus.PAUSED else current
        PlaybackEvent.RESUME -> when (current) {
            PlaybackStatus.PAUSED, PlaybackStatus.COMPLETED, PlaybackStatus.FAILED ->
                PlaybackStatus.PLAYING
            else -> current
        }
        PlaybackEvent.COMPLETE ->
            if (current == PlaybackStatus.PLAYING || current == PlaybackStatus.PREPARING) {
                PlaybackStatus.COMPLETED
            } else {
                current
            }
        PlaybackEvent.FAIL -> PlaybackStatus.FAILED
        PlaybackEvent.RESET -> PlaybackStatus.IDLE
    }

    /**
     * 点一下播放按钮会发生什么。
     *
     * - 正在播 → 暂停；
     * - 暂停/播完/失败 → 继续（失败时由调用方重新 prepare，这里只表达「想听」）；
     * - 还在准备 → 取消（回到 IDLE，交给界面收起进度）；
     * - 空闲 → 准备。
     */
    fun toggle(current: PlaybackStatus): PlaybackEvent = when (current) {
        PlaybackStatus.PLAYING -> PlaybackEvent.PAUSE
        PlaybackStatus.PAUSED, PlaybackStatus.COMPLETED, PlaybackStatus.FAILED -> PlaybackEvent.RESUME
        PlaybackStatus.PREPARING -> PlaybackEvent.RESET
        PlaybackStatus.IDLE -> PlaybackEvent.PREPARE
    }

    /** 按钮上该画「暂停」还是「播放」。 */
    fun showsPause(status: PlaybackStatus): Boolean = status == PlaybackStatus.PLAYING

    /** 播放中（含准备中）才算「正在占用一个播放器」：切到别的媒体前要把它停掉。 */
    fun isActive(status: PlaybackStatus): Boolean =
        status == PlaybackStatus.PLAYING || status == PlaybackStatus.PREPARING

    /** 进度 0..1；时长未知/为 0 时返回 0（界面画一条不动的底线，而不是除零）。 */
    fun progress(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L || positionMs <= 0L) return 0f
        return (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 气泡上的时间标签：没开始播时显示总时长，播起来显示「已播 / 总长」。
     *
     * 用 [AudioVideoPolicy.clockDuration]，与语音气泡、通知里的写法一致。
     */
    fun clockLabel(positionMs: Long, durationMs: Long, status: PlaybackStatus): String =
        if (status == PlaybackStatus.PLAYING || status == PlaybackStatus.PAUSED) {
            "${AudioVideoPolicy.clockDuration(positionMs)} / ${AudioVideoPolicy.clockDuration(durationMs)}"
        } else {
            AudioVideoPolicy.clockDuration(durationMs)
        }
}
