package com.myfriend.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.myfriend.app.domain.Presence

/**
 * 「他此刻在做什么」的一行小字：「在想事情 · 12 分钟前」。
 *
 * 只显示、不提供任何入口——这是他自己的状态，用户可以看见，但没法设置、开关或者手动改。
 * 做得很轻：位置留给对话本身。
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
        text = presence.display(now),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}
