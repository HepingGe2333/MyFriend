package com.myfriend.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.myfriend.app.R
import com.myfriend.app.domain.Presence
import com.myfriend.app.domain.PresenceKind

/**
 * 「他此刻在做什么」的一行小字：「在想事情 · 12 分钟前」/ "Thinking · 12 min ago"。
 *
 * 只显示、不提供任何入口——这是他自己的状态，用户可以看见，但没法设置、开关或者手动改。
 * 做得很轻：位置留给对话本身。
 *
 * 句子在资源里（`presence_*`），[PresenceKind] 只负责「是哪一种」；
 * 「多久以前」的分档与 `domain/Presence.relativeTime` 一致，只是换成 plurals。
 */
@Composable
fun PresenceText(
    presence: Presence,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
    style: TextStyle = MaterialTheme.typography.labelSmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 1
) {
    if (!presence.isMeaningful()) return
    Text(
        text = presenceLine(presence, now),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun presenceLine(presence: Presence, now: Long): String {
    val label = presenceLabel(presence)
    val ago = presenceAgo(presence.sinceAt, now)
    return if (ago.isBlank()) label else stringResource(R.string.presence_line, label, ago)
}

/** 状态本身。睡着且有作息时顺带说一句几点醒。 */
@Composable
private fun presenceLabel(presence: Presence): String {
    val wake = presence.wakeText?.trim().orEmpty()
    if (presence.kind == PresenceKind.ASLEEP && wake.isNotEmpty()) {
        return stringResource(R.string.presence_asleep_wake, wake)
    }
    return stringResource(
        when (presence.kind) {
            PresenceKind.ASLEEP -> R.string.presence_asleep
            PresenceKind.JUST_WOKE -> R.string.presence_just_woke
            PresenceKind.WRITING -> R.string.presence_writing
            PresenceKind.BROWSING -> R.string.presence_browsing
            PresenceKind.THINKING -> R.string.presence_thinking
            PresenceKind.WAITING -> R.string.presence_waiting
            PresenceKind.AWAY -> R.string.presence_away
            PresenceKind.IDLE -> R.string.presence_idle
        }
    )
}

/**
 * 「多久以前」：刚刚 / 3 分钟前 / 昨天 / 上周 / 很久以前。
 *
 * 分档与 `Presence.relativeTime` 完全一致；0 或未来时间当作「不知道」，整句只留状态。
 */
@Composable
private fun presenceAgo(sinceAt: Long, now: Long): String {
    if (sinceAt <= 0L) return ""
    if (now <= sinceAt) return stringResource(R.string.presence_ago_just_now)
    val delta = now - sinceAt
    val minutes = delta / 60_000L
    if (minutes < 1L) return stringResource(R.string.presence_ago_just_now)
    if (minutes < 60L) {
        return pluralStringResource(R.plurals.presence_ago_minutes, minutes.toInt(), minutes.toInt())
    }
    val hours = delta / 3_600_000L
    if (hours < 24L) {
        return pluralStringResource(R.plurals.presence_ago_hours, hours.toInt(), hours.toInt())
    }
    val days = delta / 86_400_000L
    return when {
        days <= 1L -> stringResource(R.string.presence_ago_yesterday)
        days < 7L -> pluralStringResource(R.plurals.presence_ago_days, days.toInt(), days.toInt())
        days < 14L -> stringResource(R.string.presence_ago_last_week)
        days < 30L -> {
            val weeks = (days / 7).toInt()
            pluralStringResource(R.plurals.presence_ago_weeks, weeks, weeks)
        }
        days < 365L -> {
            val months = (days / 30).toInt()
            pluralStringResource(R.plurals.presence_ago_months, months, months)
        }
        else -> stringResource(R.string.presence_ago_long_ago)
    }
}
