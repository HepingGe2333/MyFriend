package com.yap.app.domain

/**
 * 「点发送之前再拦一次」的语音/视频校验（0.13）。
 *
 * 为什么选视频/录音时已经查过，这里还要查：
 * - 选完视频到点发送之间，文件可能被别的路径替换/损坏（安全扫描、清理工具、备份导入）；
 * - 服务端模式与本地模式共用同一个 `send()`，服务端上传前虽然也会再校验一次，
 *   但**本机就不该把注定失败的请求发出去**（64 MiB 的 body 传一半再被 413 是最糟的体验）。
 *
 * 这里只做纯计算：size/duration 由调用方用现有元数据读取（`File.length()` +
 * `VideoFramesExtractor.durationMs`）给出。上限与判据全部复用 [AudioVideoPolicy]，
 * 绝不在这里写第二套数字。
 */
object MediaSendGuard {

    /** 一个待发送的音视频附件（只保留判据需要的字段）。 */
    data class Candidate(
        val kind: AudioVideoKind,
        val sizeBytes: Long,
        /** 读不到时传 null：只按大小挡（与选片时的行为一致）。 */
        val durationMs: Long?
    ) {
        /** 给人看的类型（调用方据此挑文案）。 */
        val isAudio: Boolean get() = kind == AudioVideoKind.AUDIO
    }

    data class Rejection(val candidate: Candidate, val reason: MediaRejection)

    /** 逐条检查，返回**第一条**超限的附件；全部通过返回 null。 */
    fun firstRejection(candidates: List<Candidate>): Rejection? {
        for (candidate in candidates) {
            val reason = when (candidate.kind) {
                AudioVideoKind.AUDIO -> AudioVideoPolicy.checkAudio(candidate.sizeBytes, candidate.durationMs)
                AudioVideoKind.VIDEO -> AudioVideoPolicy.checkVideo(candidate.sizeBytes, candidate.durationMs)
            }
            if (reason != null) return Rejection(candidate, reason)
        }
        return null
    }

    /** 这个拒绝要不要当成「空/读不到」而不是「超限」（前者说明文件坏了）。 */
    fun isUnreadable(reason: MediaRejection): Boolean =
        reason == MediaRejection.EMPTY || reason == MediaRejection.UNSUPPORTED_FORMAT
}
