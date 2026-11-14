package com.myfriend.app.util

import android.content.Context
import com.myfriend.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时间与日期的说法。
 *
 * 界面上不再手写「昨天」「M月d日」这类中文后缀：模式串和「今天/昨天」都在资源里
 * （`common_clock_pattern` / `common_month_day_pattern` / `common_today` …），
 * 中文取「今天 04:10」「5月1日」，英文取「Today, 4:10 AM」「May 1」。
 * 语言跟着系统走，见 `values-en/`。
 */
private fun pattern(context: Context, resId: Int): String = context.getString(resId)

fun formatClock(context: Context, timestamp: Long): String {
    val formatter = DateTimeFormatter.ofPattern(pattern(context, R.string.common_clock_pattern), Locale.getDefault())
    return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(formatter)
}

fun formatShortDate(context: Context, timestamp: Long): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> formatClock(context, timestamp)
        date == today.minusDays(1) -> context.getString(R.string.common_yesterday)
        date.year == today.year -> date.format(
            DateTimeFormatter.ofPattern(pattern(context, R.string.common_month_day_pattern), Locale.getDefault())
        )
        else -> date.format(
            DateTimeFormatter.ofPattern(pattern(context, R.string.common_year_month_day_pattern), Locale.getDefault())
        )
    }
}

fun formatFullDateTime(context: Context, timestamp: Long): String {
    val formatter = DateTimeFormatter.ofPattern(pattern(context, R.string.common_datetime_full_pattern), Locale.getDefault())
    return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(formatter)
}

fun dayLabel(context: Context, timestamp: Long): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> context.getString(R.string.common_today)
        date == today.minusDays(1) -> context.getString(R.string.common_yesterday)
        date.year == today.year -> date.format(
            DateTimeFormatter.ofPattern(pattern(context, R.string.common_month_day_pattern), Locale.getDefault())
        )
        else -> date.format(
            DateTimeFormatter.ofPattern(pattern(context, R.string.common_year_month_day_pattern), Locale.getDefault())
        )
    }
}

fun isSameDay(first: Long, second: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(first).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(second).atZone(zone).toLocalDate()
}
