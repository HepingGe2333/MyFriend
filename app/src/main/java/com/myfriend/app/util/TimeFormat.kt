package com.myfriend.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val dayFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.getDefault())
private val fullFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.getDefault())

fun formatClock(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(clockFormatter)

fun formatShortDate(timestamp: Long): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> formatClock(timestamp)
        date == today.minusDays(1) -> "昨天"
        date.year == today.year -> date.format(dayFormatter)
        else -> "${date.year}/${date.monthValue}/${date.dayOfMonth}"
    }
}

fun formatFullDateTime(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(fullFormatter)

fun dayLabel(timestamp: Long): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> "今天"
        date == today.minusDays(1) -> "昨天"
        date.year == today.year -> date.format(dayFormatter)
        else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
}

fun isSameDay(first: Long, second: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(first).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(second).atZone(zone).toLocalDate()
}
