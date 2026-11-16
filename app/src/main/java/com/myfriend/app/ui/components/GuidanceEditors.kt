package com.myfriend.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.myfriend.app.R

/**
 * 话风卡：粘贴聊天记录 → 总结 → 可编辑的 tone / habits / avoid + phrases chips。
 *
 * 世界设定卡在 `WorldPresetEditor.kt`：它是一个真正的可视化编辑器（行式列表 + 下拉 +
 * 时间选择器），比话风卡复杂得多，所以单独一个文件。
 *
 * 「原文」只在这一页的内存里，保存时写库的是总结后的风格数据（规范 §3.3）。
 */
@Composable
fun SpeechStyleCard(
    sampleText: String,
    tone: String,
    phrases: List<String>,
    habitsText: String,
    avoidText: String,
    source: String?,
    summarizing: Boolean,
    onSampleText: (String) -> Unit,
    onSummarize: () -> Unit,
    onClear: () -> Unit,
    onTone: (String) -> Unit,
    onPhrases: (List<String>) -> Unit,
    onHabitsText: (String) -> Unit,
    onAvoidText: (String) -> Unit
) {
    SectionLabel(stringResource(R.string.guidance_speech_section))
    Text(
        stringResource(R.string.guidance_speech_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    Spacer(Modifier.height(10.dp))
    StyledOutlinedField(
        value = sampleText,
        onValueChange = onSampleText,
        label = stringResource(R.string.guidance_speech_sample_label),
        placeholder = stringResource(R.string.guidance_speech_sample_placeholder),
        singleLine = false,
        minLines = 5,
        maxLines = 12
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(
            text = if (summarizing) {
                stringResource(R.string.guidance_speech_summarizing)
            } else {
                stringResource(R.string.guidance_speech_summarize)
            },
            onClick = onSummarize,
            enabled = !summarizing && sampleText.isNotBlank(),
            loading = summarizing,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        SecondaryButton(
            text = stringResource(R.string.guidance_speech_clear),
            onClick = onClear,
            modifier = Modifier.weight(0.5f)
        )
    }
    source?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (it == "heuristic") {
                stringResource(R.string.guidance_speech_source_heuristic)
            } else {
                stringResource(R.string.guidance_speech_source_llm)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(12.dp))
    StyledOutlinedField(
        value = tone,
        onValueChange = { onTone(it.take(120)) },
        label = stringResource(R.string.guidance_speech_tone_label),
        singleLine = false,
        minLines = 1,
        maxLines = 3
    )
    Spacer(Modifier.height(10.dp))
    StyledOutlinedField(
        value = habitsText,
        onValueChange = { onHabitsText(it.take(600)) },
        label = stringResource(R.string.guidance_speech_habits_label),
        placeholder = stringResource(R.string.guidance_speech_list_placeholder),
        singleLine = false,
        minLines = 2,
        maxLines = 5
    )
    Spacer(Modifier.height(10.dp))
    StyledOutlinedField(
        value = avoidText,
        onValueChange = { onAvoidText(it.take(600)) },
        label = stringResource(R.string.guidance_speech_avoid_label),
        placeholder = stringResource(R.string.guidance_speech_list_placeholder),
        singleLine = false,
        minLines = 2,
        maxLines = 5
    )
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.guidance_speech_phrases_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    Spacer(Modifier.height(6.dp))
    if (phrases.isEmpty()) {
        Text(
            stringResource(R.string.guidance_speech_phrases_empty),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(phrases) { phrase ->
                TextPill(
                    text = stringResource(R.string.guidance_speech_phrase_chip, phrase),
                    onClick = { onPhrases(phrases - phrase) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.guidance_speech_phrase_remove_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
    Spacer(Modifier.height(10.dp))
    PhraseAdder(onAdd = { phrase -> onPhrases((phrases + phrase).distinct().take(24)) })
}

/** 一条常用语的输入行：写完按「添加」。 */
@Composable
private fun PhraseAdder(onAdd: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StyledOutlinedField(
            value = draft,
            onValueChange = { draft = it.take(40) },
            label = stringResource(R.string.guidance_speech_phrase_add_label),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        SecondaryButton(
            text = stringResource(R.string.guidance_speech_phrase_add),
            onClick = {
                val trimmed = draft.trim()
                if (trimmed.isNotBlank()) {
                    onAdd(trimmed)
                    draft = ""
                }
            }
        )
    }
}

/** 次级按钮（AI 补全 / 总结 / 清空）：低调一点，不和页面主保存按钮抢。 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    Surface(
        onClick = { if (enabled && !loading) onClick() },
        enabled = enabled && !loading,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
        )
    }
}
