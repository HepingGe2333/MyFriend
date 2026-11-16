package com.yap.app.domain

/**
 * 平台时间能力的最小 expect/actual 面。
 *
 * commonMain 里的决策（睡眠窗口、心情的「深夜折扣」、中文时间指代）都需要「现在是几点」，
 * 但「墙钟」和「本地时区」是平台能力：JVM 是 `System.currentTimeMillis` / `TimeZone`，
 * iOS 是 `NSDate` / `NSTimeZone`。这里只把这两个事实暴露成两个函数，其余全部用纯 Kotlin
 * 的日历算术实现（见 [LocalTimeMath]），保证两端算出来的结果一致、且能在单测里用固定的
 * `now` 钉死。
 */
internal expect fun nowMillis(): Long

/**
 * [epochMillis] 这一刻在设备本地时区的 UTC 偏移（毫秒）。
 *
 * 东八区是 +28_800_000。必须按「指定时刻」取而不是取当前偏移：跨夏令时的历史日期要算对
 * （中国大陆 1991 年后没有夏令时，但这个函数不该假设这一点）。
 */
internal expect fun localUtcOffsetMillis(epochMillis: Long): Int

/** 本地日历上的一个日期（只用于内部换算，不对外暴露）。 */
internal data class CivilDate(val year: Int, val month: Int, val day: Int)

/** 向下取整除法（Kotlin 的 `/` 是截断除法，负数会算错）。 */
internal fun floorDivLong(a: Long, b: Long): Long {
    val quotient = a / b
    return if (a % b != 0L && (a < 0L) != (b < 0L)) quotient - 1 else quotient
}

/** 向下取整取模（结果与除数同号）。 */
internal fun floorModLong(a: Long, b: Long): Long = a - floorDivLong(a, b) * b

/**
 * 本地时区的日历算术，纯 Kotlin 实现（不依赖 `java.time` / `NSCalendar`）。
 *
 * 用 Howard Hinnant 的 civil-from-days / days-from-civil 算法：把「本地日期」和
 * 「epoch day（1970-01-01 = 0）」互转，中间只跟 [localUtcOffsetMillis] 打交道。
 * 这样 `ChineseTerms` 的「昨天/上个月/去年冬天」和 `MoodBehavior` 的「深夜」都能进 commonMain，
 * 而不是被 `java.util.Calendar` 绑在 JVM 上。
 */
internal object LocalTimeMath {

    const val DAY_MILLIS: Long = 86_400_000L
    private const val HOUR_MILLIS: Long = 3_600_000L

    /** 这一刻在本地时区是第几天（1970-01-01 本地 = 0）。 */
    fun localEpochDay(epochMillis: Long): Long =
        floorDivLong(epochMillis + localUtcOffsetMillis(epochMillis), DAY_MILLIS)

    /** 这一刻在本地时区是几点（0..23）。 */
    fun localHourOfDay(epochMillis: Long): Int {
        val local = epochMillis + localUtcOffsetMillis(epochMillis)
        return (floorModLong(local, DAY_MILLIS) / HOUR_MILLIS).toInt()
    }

    /** 本地日期 [epochDay] 的 00:00 对应的 epoch millis。 */
    fun startOfLocalDay(epochDay: Long): Long {
        val utcMidnight = epochDay * DAY_MILLIS
        // 先用这一天中间的偏移做估计，再用「估计出来的本地午夜」的偏移校正一次。
        // 有夏令时的时区在切换日会差一小时，两次迭代足够收敛（Calendar 也只按这一刻的规则算）。
        var candidate = utcMidnight - localUtcOffsetMillis(utcMidnight + DAY_MILLIS / 2)
        val offsetAtCandidate = localUtcOffsetMillis(candidate)
        val offsetAtUtcMidnight = utcMidnight - candidate
        if (offsetAtCandidate.toLong() != offsetAtUtcMidnight) {
            candidate = utcMidnight - offsetAtCandidate
        }
        return candidate
    }

    /** 从本地日期换算 epoch day（月份 1..12，日 1..31）。 */
    fun epochDayFromCivil(year: Int, month: Int, day: Int): Long {
        var y = year.toLong()
        val m = month.toLong()
        val d = day.toLong()
        if (m <= 2L) y -= 1L
        val era = floorDivLong(y, 400L)
        val yoe = y - era * 400L
        val adjustedMonth = if (m > 2L) m - 3L else m + 9L
        val doy = (153L * adjustedMonth + 2L) / 5L + d - 1L
        val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
        return era * 146_097L + doe - 719_468L
    }

    /** epoch day → 本地日期。 */
    fun civilFromEpochDay(epochDay: Long): CivilDate {
        val z = epochDay + 719_468L
        val era = floorDivLong(z, 146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
        var year = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val day = doy - (153L * mp + 2L) / 5L + 1L
        val month = mp + if (mp < 10L) 3L else -9L
        if (month <= 2L) year += 1L
        return CivilDate(year.toInt(), month.toInt(), day.toInt())
    }

    /** 本地日期 (year, month0) 的 1 号 00:00；month0 可以越界（11 + 3 = 14 → 次年 2 月）。 */
    fun monthStart(year: Int, month0: Int): Long {
        val normalizedYear = year + floorDivLong(month0.toLong(), 12L).toInt()
        val normalizedMonth = (floorModLong(month0.toLong(), 12L) + 1).toInt()
        return startOfLocalDay(epochDayFromCivil(normalizedYear, normalizedMonth, 1))
    }

    /** 本地日期往前/往后挪 [offsetDays] 天后的 00:00。 */
    fun dayStart(epochMillis: Long, offsetDays: Int): Long =
        startOfLocalDay(localEpochDay(epochMillis) + offsetDays)

    /** 本地「周一 00:00」（中国人说「上周三」时按周一起算的那一周）。 */
    fun weekStart(epochMillis: Long): Long {
        val epochDay = localEpochDay(epochMillis)
        // 1970-01-01 是周四：epochDay + 3 对 7 取模就是「离最近一个周一过了几天」。
        val mondayOffset = floorModLong(epochDay + 3L, 7L)
        return startOfLocalDay(epochDay - mondayOffset)
    }
}
