package com.yap.app.domain

/**
 * 拉黑倒计时（0.15 聊天页顶部提示：「你已被拉黑，还剩 X 小时 Y 分」）。
 *
 * 只做纯计算：还剩多久、怎么写成中文的一段时间、什么算「已经到期」。
 * UI 负责每秒刷新（`LaunchedEffect` + `delay(1000)`）与把结果拼进资源字符串。
 *
 * 三条规则：
 * 1. **到期即消失**：`blockedUntil <= now` 一律返回 null，不显示「还剩 0 小时 0 分」；
 * 2. **分钟向上取整**：还剩 59 秒说「不到 1 分钟」，还剩 61 秒说「2 分」——
 *    倒计时只说「还剩多久」，不显示秒，避免每秒跳一个数字；
 * 3. **小时与分钟分开**：`3 小时 12 分`；不足 1 小时只写分钟（`12 分`），
 *    满 1 小时但分钟为 0 只写小时（`3 小时`）。
 */
object BlockCountdown {

    /** 一分钟的毫秒数。 */
    const val MINUTE_MS: Long = 60_000L

    /** 一小时的毫秒数。 */
    const val HOUR_MS: Long = 60 * MINUTE_MS

    /** 倒计时文案的两段（小时/分钟），UI 用资源拼整句。 */
    data class Parts(
        val hours: Int,
        val minutes: Int,
        val remainingMs: Long
    ) {
        /** 是否还有效（`remainingMs > 0`）。 */
        val active: Boolean get() = remainingMs > 0
    }

    /**
     * 还剩多久；已经到期（或时间戳为空/非法）返回 null。
     */
    fun remaining(blockedUntil: Long?, now: Long): Long? {
        if (blockedUntil == null || blockedUntil <= 0L) return null
        val left = blockedUntil - now
        return if (left <= 0L) null else left
    }

    /** 是否仍在拉黑中（到期即解除，与 [BlockPolicy.isBlocked] 同一口径）。 */
    fun isActive(blockedUntil: Long?, now: Long): Boolean = remaining(blockedUntil, now) != null

    /**
     * 把剩余时间拆成小时与分钟（分钟向上取整）。
     *
     * @return 已到期返回 null
     */
    fun parts(blockedUntil: Long?, now: Long): Parts? {
        val left = remaining(blockedUntil, now) ?: return null
        // 向上取整到分钟：还剩 1 秒也是「1 分钟」，不会出现「0 分」这种读起来像已经结束的文案。
        val totalMinutes = (left + MINUTE_MS - 1) / MINUTE_MS
        val hours = (totalMinutes / 60).toInt()
        val minutes = (totalMinutes % 60).toInt()
        return Parts(hours = hours, minutes = minutes, remainingMs = left)
    }

    /**
     * 中文时长：`3 小时 12 分` / `3 小时` / `12 分` / `不到 1 分钟`；已到期返回 null。
     *
     * 注：这里拼的是**时长**，前面那句「你已被拉黑，还剩」由 UI 的资源字符串提供，
     * 这样英文界面可以说「remaining」而不用把整句中文搬进 domain。
     */
    fun duration(blockedUntil: Long?, now: Long): String? {
        val parts = parts(blockedUntil, now) ?: return null
        // 不足一分钟：说「不到 1 分钟」，而不是「1 分」（后者读起来像还能等一会儿）。
        if (parts.remainingMs < MINUTE_MS) return "不到 1 分钟"
        return when {
            parts.hours > 0 && parts.minutes > 0 -> "${parts.hours} 小时 ${parts.minutes} 分"
            parts.hours > 0 -> "${parts.hours} 小时"
            else -> "${parts.minutes} 分"
        }
    }

    /**
     * 实时倒计时是否该在下一秒重新计算：`blockedUntil` 到期的那一秒之后返回 false，
     * UI 据此停止 ticker（比「一直每秒刷新」省电）。
     */
    fun shouldTick(blockedUntil: Long?, now: Long): Boolean = isActive(blockedUntil, now)
}
