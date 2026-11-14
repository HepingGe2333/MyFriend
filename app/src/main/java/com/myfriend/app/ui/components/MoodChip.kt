package com.myfriend.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myfriend.app.domain.MoodLexicon
import com.myfriend.app.domain.MoodState

/**
 * 他此刻心情的一枚小胶囊：一个点表示浓度，一个词表示内容。
 *
 * 刻意做得很轻——心情是背景信息，不该抢走对话本身。
 */
@Composable
fun MoodChip(
    mood: MoodState,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    if (mood.updatedAt <= 0L && mood.isEmpty()) return
    val word = MoodLexicon.classify(mood.label)
    val tint = when {
        word.valence <= -2 -> Color(0xFFB4736B)
        word.valence < 0 || word.energy <= -2 -> Color(0xFFB08A5A)
        word.valence >= 2 -> Color(0xFF6E9E76)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val intensity = mood.intensity.coerceIn(1, 5)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 五个点，亮起的数量就是强度；一眼看得出「有多浓」。
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(5) { index ->
                Box(
                    Modifier
                        .size(if (intensity >= 4) 4.dp else 3.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = if (index < intensity) 0.9f else 0.22f))
                )
            }
        }
        Text(
            text = mood.label.ifBlank { MoodLexicon.BASELINE },
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 带原因的完整一行，用在「他此刻」这类需要解释的地方。 */
@Composable
fun MoodLine(mood: MoodState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MoodChip(mood)
        if (mood.reason.isNotBlank()) {
            Text(
                text = mood.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
