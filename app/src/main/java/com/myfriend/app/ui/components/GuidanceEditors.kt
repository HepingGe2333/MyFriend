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
 * 世界设定 / 话风这两张卡的共用界面。
 *
 * 预设编辑器和人格编辑器用的是**同一套控件**：预设里填的东西应用到人格时是复制一份，
 * 之后人格可以再改（规范 §2.2）；如果两边各写一套，迟早会出现「预设能填、人格不能改」
 * 或者「同一个字段在两页的校验不一样」。
 *
 * 日程 / 关系用 JSON 文本框（而不是逐条表单）：这两项结构固定、条目又多，
 * 逐条表单在小屏上会滚很久；JSON 文本框能直接粘贴 AI 补全的结果，也方便高级用户手改。
 * 义务用「一行一条」的纯文本，因为这就是一句话列表。
 */
@Composable
fun WorldPresetCard(
    worldName: String,
    era: String,
    description: String,
    scheduleJson: String,
    relationshipsJson: String,
    obligationsText: String,
    onWorldName: (String) -> Unit,
    onEra: (String) -> Unit,
    onDescription: (String) -> Unit,
    onScheduleJson: (String) -> Unit,
    onRelationshipsJson: (String) -> Unit,
    onObligationsText: (String) -> Unit,
    onAiFill: (() -> Unit)?,
    aiFilling: Boolean,
    aiHint: String?
) {
    SectionLabel(stringResource(R.string.guidance_world_section))
    Text(
        stringResource(R.string.guidance_world_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    Spacer(Modifier.height(10.dp))
    StyledOutlinedField(
        value = worldName,
        onValueChange = { onWorldName(it.take(60)) },
        label = stringResource(R.string.guidance_world_name_label),
        placeholder = stringResource(R.string.guidance_world_name_placeholder),
        singleLine = true
    )
    Spacer(Modifier.height(10.dp))
    StyledOutlinedField(
        value = era,
        onValueChange = { onEra(it.take(120)) },
        label = stringResource(R.string.guidance_world_era_label),
        singleLine = true
    )
    Spacer(Modifier.height(10.dp))
    StyledOutlinedField(
        value = description,
        onValueChange = { onDescription(it.take(600)) },
        label = stringResource(R.string.guidance_world_description_label),
        singleLine = false,
        minLines = 3,
        maxLines = 6
    )
    Spacer(Modifier.height(10.dp))
    StyledOutlinedField(
        value = scheduleJson,
        onValueChange = onScheduleJson,
        label = stringResource(R.string.guidance_world_schedule_label),
        placeholder = stringResource(R.string.guidance_world_schedule_placeholder),
        singleLine = false,
        minLines = 3,
        maxLines = 8
    )
    Spacer(Modifier.height(10.dp))
    StyledOutlinedField(
        value = relationshipsJson,
        onValueChange = onRelationshipsJson,
        label = stringResource(R.string.guidance_world_relationships_label),
        placeholder = stringResource(R.string.guidance_world_relationships_placeholder),
        singleLine = false,
        minLines = 2,
        maxLines = 6
    )
    Spacer(Modifier.height(10.dp))
    StyledOutlinedField(
        value = obligationsText,
        onValueChange = onObligationsText,
        label = stringResource(R.string.guidance_world_obligations_label),
        placeholder = stringResource(R.string.guidance_world_obligations_placeholder),
        singleLine = false,
        minLines = 2,
        maxLines = 6
    )
    if (onAiFill != null) {
        Spacer(Modifier.height(12.dp))
        SecondaryButton(
            text = if (aiFilling) {
                stringResource(R.string.guidance_world_ai_filling)
            } else {
                stringResource(R.string.guidance_world_ai_fill)
            },
            onClick = onAiFill,
            enabled = !aiFilling,
            loading = aiFilling
        )
        aiHint?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 话风卡：粘贴聊天记录 → 总结 → 可编辑的 tone / habits / avoid + phrases chips。
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
