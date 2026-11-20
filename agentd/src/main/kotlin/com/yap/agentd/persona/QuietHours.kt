package com.yap.agentd.persona

/**
 * 人格的「安静时段」（`set_quiet_hours` 工具写入、`SleepState` 读取）。
 *
 * 为什么单独一个纯函数对象：安静时段最容易写错的就是**跨夜**（23:30–08:00 的窗口要覆盖
 * 00:00–07:59）与时区。服务端能拿到的只有「本地当天第几分钟」（由 `Clock.zone` 换算），
 * 所以这里只做纯分钟运算，边界可以在单测里一条条钉住：
 * - `start < end`：同一天内的窗口（13:00–15:00）；
 * - `start > end`：跨夜窗口（23:30–08:00）；
 * - `start == end`：非法（空窗口和全天窗口只能二选一，客户端 `SleepPolicy` 也把同点折回默认窗口）。
 *
 * 存储形状与客户端 `personas.quietSleepMinutes / quietWakeMinutes` 同义（当天第几分钟，0–1439）。
 * 这正是备份 v6 里那两个可空列，所以工具写入之后，客户端备份/恢复也能原样带走。
 */
data class QuietHours(val startMinutes: Int, val endMinutes: Int) {

    /** 已经归一化到 0..1439 的安静时段（start == end 视为非法，调用方不该构造出来）。 */
    init {
        require(startMinutes in 0 until MINUTES_PER_DAY) { "startMinutes 越界" }
        require(endMinutes in 0 until MINUTES_PER_DAY) { "endMinutes 越界" }
        require(startMinutes != endMinutes) { "安静时段的开始与结束不能相同" }
    }

    /** 当前时刻（本地当天第几分钟）是否在安静时段内。跨夜窗口按 `[start, 24:00) ∪ [0, end)` 判。 */
    fun contains(minuteOfDay: Int): Boolean {
        val minute = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
        return if (startMinutes < endMinutes) {
            minute >= startMinutes && minute < endMinutes
        } else {
            minute >= startMinutes || minute < endMinutes
        }
    }

    /** 时段文案，例如 `23:30–08:00`。 */
    fun text(): String = "${format(startMinutes)}–${format(endMinutes)}"

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        /**
         * 解析时刻：`"23:30"` / `"9:05"` / `"2330"` / `"930"` / `"23点30"` 都认。
         *
         * 对模型宽容一点是必要的：它经常把 `23:30` 写成 `2330` 或 `"23点30"`。
         * 认不出来返回 null（调用方回一条明确的工具失败，而不是猜一个时间）。
         */
        fun parseMinute(raw: String?): Int? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            // 中文写法：23点30 / 23点 / 8点05
            if (text.contains('点')) {
                val left = text.substringBefore('点').trim().toIntOrNull() ?: return null
                val right = text.substringAfter('点').trim().removeSuffix("分").trim()
                val minute = if (right.isEmpty()) 0 else right.toIntOrNull() ?: return null
                return normalize(left, minute)
            }
            if (text.contains(':') || text.contains('：')) {
                val parts = text.split(':', '：')
                if (parts.size != 2) return null
                val hour = parts[0].trim().toIntOrNull() ?: return null
                val minute = parts[1].trim().toIntOrNull() ?: return null
                return normalize(hour, minute)
            }
            val digits = text.toIntOrNull() ?: return null
            return when {
                // 小于 100 只可能是小时（"8" → 08:00）；三位/四位的按 HHmm 读（"930"/"2330"）。
                digits in 0 until 100 -> normalize(digits, 0)
                digits in 100..2359 -> normalize(digits / 100, digits % 100)
                else -> null
            }
        }

        /** 两个时刻构造一个安静时段；任一个认不出来 / 相同 → null（调用方报 400 级别的工具失败）。 */
        fun of(start: String?, end: String?): QuietHours? {
            val startMinute = parseMinute(start) ?: return null
            val endMinute = parseMinute(end) ?: return null
            if (startMinute == endMinute) return null
            return QuietHours(startMinute, endMinute)
        }

        fun format(minuteOfDay: Int): String {
            val minute = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
            return "%02d:%02d".format(minute / 60, minute % 60)
        }

        private fun normalize(hour: Int, minute: Int): Int? {
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }
    }
}
