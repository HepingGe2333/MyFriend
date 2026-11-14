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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.myfriend.app.R
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
            text = moodLabelText(mood.label),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 心情的那个词。
 *
 * 词表是固定的（[MoodLexicon]），所以常见的那些都能说成对方的语言；
 * 万一模型自己造了个词，就原样显示——不猜、不硬翻。
 */
@Composable
private fun moodLabelText(label: String): String {
    val word = MoodLexicon.normalize(label)
    val res = when (word) {
        "平静" -> R.string.mood_label_calm
        "放松" -> R.string.mood_label_relaxed
        "温柔" -> R.string.mood_label_gentle
        "雀跃" -> R.string.mood_label_elated
        "开心" -> R.string.mood_label_happy
        "兴奋" -> R.string.mood_label_excited
        "期待" -> R.string.mood_label_looking_forward
        "想念" -> R.string.mood_label_missing_you
        "有点累" -> R.string.mood_label_a_little_tired
        "疲惫" -> R.string.mood_label_worn_out
        "犯困" -> R.string.mood_label_sleepy
        "低落" -> R.string.mood_label_low
        "难过" -> R.string.mood_label_sad
        "心烦" -> R.string.mood_label_restless
        "不安" -> R.string.mood_label_uneasy
        "有点委屈" -> R.string.mood_label_a_little_hurt
        "愧疚" -> R.string.mood_label_guilty
        else -> null
    }
    return if (res != null) stringResource(res) else word
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
