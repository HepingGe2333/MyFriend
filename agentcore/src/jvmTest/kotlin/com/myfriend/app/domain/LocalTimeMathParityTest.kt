package com.myfriend.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * commonMain 的日历算术（[LocalTimeMath]，为替代 `java.util.Calendar` 而写）与 JDK 的逐点对照。
 *
 * 为什么值得单独钉：`ChineseTerms` 的「昨天 / 上个月 / 去年冬天」和 `MoodBehavior` 的「深夜」
 * 都从 `Calendar` 换成了纯 Kotlin 实现。这类换算错一小时、错一天不会崩，只会让
 * 「他记错时间」——是最难在设备上发现的那种 bug。所以这里直接拿 JDK 当参考实现：
 * 默认时区 + 有夏令时的时区（跨 DST 的那一天最容易错）都逐点比一遍。
 */
class LocalTimeMathParityTest {

    private fun <T> withDefaultZone(zone: TimeZone, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(zone)
        try {
            return block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun calendarAt(now: Long): Calendar = Calendar.getInstance().apply {
        timeInMillis = now
    }

    private fun calendarStartOfDay(now: Long): Long = calendarAt(now).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun assertMatchesJdk(now: Long, label: String) {
        val cal = calendarAt(now)
        val epochDay = LocalTimeMath.localEpochDay(now)
        val civil = LocalTimeMath.civilFromEpochDay(epochDay)

        assertEquals("$label: year", cal.get(Calendar.YEAR), civil.year)
        assertEquals("$label: month", cal.get(Calendar.MONTH) + 1, civil.month)
        assertEquals("$label: day", cal.get(Calendar.DAY_OF_MONTH), civil.day)
        assertEquals("$label: hour", cal.get(Calendar.HOUR_OF_DAY), LocalTimeMath.localHourOfDay(now))
        assertEquals("$label: day start", calendarStartOfDay(now), LocalTimeMath.startOfLocalDay(epochDay))
        assertEquals("$label: day + 0", calendarStartOfDay(now), LocalTimeMath.dayStart(now, 0))
        assertEquals(
            "$label: day + 1",
            calendarStartOfDay(now).let { start ->
                calendarAt(start).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            },
            LocalTimeMath.dayStart(now, 1)
        )

        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val mondayOffset = (dayOfWeek + 5) % 7
        assertEquals(
            "$label: week start",
            calendarStartOfDay(now).let { start ->
                calendarAt(start).apply { add(Calendar.DAY_OF_MONTH, -mondayOffset) }.timeInMillis
            },
            LocalTimeMath.weekStart(now)
        )
    }

    private fun assertMonthStartMatchesJdk(year: Int, month0: Int, label: String) {
        val expected = Calendar.getInstance().apply {
            clear()
            set(year, month0, 1, 0, 0, 0)
        }.timeInMillis
        assertEquals("$label: month start", expected, LocalTimeMath.monthStart(year, month0))
    }

    @Test
    fun `calendar arithmetic matches jdk in the default zone`() {
        // 2023-01-01 到 2025-12-31，每 13 小时取一个点（覆盖每个时段；不锁死机器时区）。
        var now = 1_672_531_200_000L // 2023-01-01T00:00:00Z
        val end = 1_767_225_600_000L // 2026-01-01T00:00:00Z
        var index = 0
        while (now < end) {
            assertMatchesJdk(now, "default#$index")
            now += 13 * 3_600_000L
            index++
        }
        for (year in 2023..2025) {
            for (month0 in 0..11) assertMonthStartMatchesJdk(year, month0, "$year-$month0")
        }
    }

    @Test
    fun `calendar arithmetic survives dst transitions`() {
        // 美国东部：2024-03-10 02:00 进入夏令时、2024-11-03 02:00 退出。
        // 逐个半小时扫过切换点前后，最容易暴露「按固定 UTC 偏移算本地日」的错误。
        withDefaultZone(TimeZone.getTimeZone("America/New_York")) {
            var now = 1_709_980_800_000L // 2024-03-09T12:00:00Z
            val end = 1_711_166_400_000L // 2024-03-24T00:00:00Z
            var index = 0
            while (now < end) {
                assertMatchesJdk(now, "dst-spring#$index")
                now += 30 * 60_000L
                index++
            }
            for (month0 in 0..11) assertMonthStartMatchesJdk(2024, month0, "dst-2024-$month0")
        }

        withDefaultZone(TimeZone.getTimeZone("America/New_York")) {
            var now = 1_730_404_800_000L // 2024-11-01T12:00:00Z
            val end = 1_731_614_400_000L // 2024-11-15T00:00:00Z
            var index = 0
            while (now < end) {
                assertMatchesJdk(now, "dst-autumn#$index")
                now += 30 * 60_000L
                index++
            }
        }
    }
}
