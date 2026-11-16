package com.yap.app.ui.components

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yap.app.R
import com.yap.app.domain.WorldEditorIssue
import com.yap.app.domain.WorldEditorState
import com.yap.app.domain.WorldPreset
import com.yap.app.domain.WorldRelationship
import com.yap.app.domain.WorldScheduleEntry

/**
 * 世界设定卡（`spec/persona-guidance/README.md` §2.3）：**可视化编辑器**，不是 JSON 文本框。
 *
 * 规范明确要求：日程与关系是行式列表（下拉 + 时间选择器 + 单行文本），义务一行一条，
 * 其他背景是带字数提示的多行文本框。用户永远不需要知道「JSON 数组」是什么。
 *
 * 状态与全部增删改/校验逻辑在 `domain/WorldEditorState`（纯 JVM，可单测）；这里只画，
 * 并把用户动作折算成新的 state 交回去（`onChange`）。所以这一页的每一处交互都能在
 * 状态层被测到，即使 Compose 渲染测试在这台机器上跑不了。
 *
 * 两处刻意的取舍：
 * 1. 时间用系统 `TimePickerDialog`：它是一个成熟的无障碍控件，比自绘滚轮更不容易出错；
 * 2. 校验问题不弹对话框，而是把行标红并在保存时提示「第几行错在哪」——用户改一行就好，
 *    不用在对话框和表单之间来回找。
 */
@Composable
fun WorldPresetCard(
    state: WorldEditorState,
    onChange: (WorldEditorState) -> Unit,
    onAiFill: (() -> Unit)?,
    aiFilling: Boolean,
    aiHint: String?,
    issues: List<WorldEditorIssue> = emptyList(),
    onUseExample: (() -> Unit)? = null
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
        value = state.worldName,
        onValueChange = { onChange(state.copy(worldName = it.take(60))) },
        label = stringResource(R.string.guidance_world_name_label),
        placeholder = stringResource(R.string.guidance_world_name_placeholder),
        singleLine = true
    )
    Spacer(Modifier.height(10.dp))
    StyledOutlinedField(
        value = state.era,
        onValueChange = { onChange(state.copy(era = it.take(120))) },
        label = stringResource(R.string.guidance_world_era_label),
        singleLine = true
    )
    Spacer(Modifier.height(10.dp))
    StyledOutlinedField(
        value = state.description,
        onValueChange = { onChange(state.copy(description = it.take(600))) },
        label = stringResource(R.string.guidance_world_description_label),
        singleLine = false,
        minLines = 3,
        maxLines = 6
    )

    if (state.isEmpty() && onUseExample != null) {
        Spacer(Modifier.height(12.dp))
        ExampleHint(onUseExample)
    }

    ScheduleEditor(state, issues, onChange)
    RelationshipEditor(state, issues, onChange)
    ObligationEditor(state, onChange)
    NotesEditor(state, onChange)

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

/** 空状态的一条示例：用户可以一键填上，再逐项改成自己的。 */
@Composable
private fun ExampleHint(onUseExample: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.guidance_world_example_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = stringResource(R.string.guidance_world_example_fill),
                onClick = onUseExample
            )
        }
    }
}

// ---------------------------------------------------------------------- 日程

@Composable
private fun ScheduleEditor(
    state: WorldEditorState,
    issues: List<WorldEditorIssue>,
    onChange: (WorldEditorState) -> Unit
) {
    EditorSectionHeader(
        title = stringResource(R.string.guidance_world_schedule_title),
        hint = stringResource(R.string.guidance_world_schedule_hint)
    )
    state.schedule.forEachIndexed { index, entry ->
        val badFields = issues.filter { it.rowIndex == index }.map { it.field }.toSet()
        ScheduleRow(
            entry = entry,
            showErrors = badFields.isNotEmpty(),
            onChange = { next -> onChange(state.updateSchedule(index) { next }) },
            onMoveUp = { onChange(state.moveSchedule(index, -1)) },
            onMoveDown = { onChange(state.moveSchedule(index, 1)) },
            onDelete = { onChange(state.removeSchedule(index)) }
        )
        Spacer(Modifier.height(8.dp))
    }
    AddRowButton(
        text = stringResource(R.string.guidance_world_add_schedule),
        onClick = { onChange(state.addSchedule()) }
    )
}

@Composable
private fun ScheduleRow(
    entry: WorldScheduleEntry,
    showErrors: Boolean,
    onChange: (WorldScheduleEntry) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    EditorRowCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DropdownField(
                value = entry.day,
                options = WorldEditorState.DAY_OPTIONS,
                label = stringResource(R.string.guidance_world_day_label),
                modifier = Modifier.weight(1f),
                onSelect = { onChange(entry.copy(day = it)) }
            )
            Spacer(Modifier.width(8.dp))
            TimeField(
                value = entry.start,
                label = stringResource(R.string.guidance_world_start_label),
                onPick = { onChange(entry.copy(start = it)) }
            )
            Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TimeField(
                value = entry.end,
                label = stringResource(R.string.guidance_world_end_label),
                onPick = { onChange(entry.copy(end = it)) }
            )
        }
        if (entry.crossesMidnight) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.guidance_world_next_day),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StyledOutlinedField(
                value = entry.activity,
                onValueChange = { onChange(entry.copy(activity = it.take(120))) },
                label = stringResource(R.string.guidance_world_activity_label),
                placeholder = stringResource(R.string.guidance_world_activity_placeholder),
                singleLine = true,
                isError = showErrors,
                modifier = Modifier.weight(1f)
            )
            MoveButtons(onMoveUp = onMoveUp, onMoveDown = onMoveDown, onDelete = onDelete)
        }
    }
}

// ------------------------------------------------------------------ 人际关系

@Composable
private fun RelationshipEditor(
    state: WorldEditorState,
    issues: List<WorldEditorIssue>,
    onChange: (WorldEditorState) -> Unit
) {
    EditorSectionHeader(
        title = stringResource(R.string.guidance_world_relationships_title),
        hint = stringResource(R.string.guidance_world_relationships_hint)
    )
    state.relationships.forEachIndexed { index, relation ->
        val badFields = issues.filter { it.rowIndex == index }.map { it.field }.toSet()
        RelationshipRow(
            relation = relation,
            showErrors = badFields.isNotEmpty(),
            onChange = { next -> onChange(state.updateRelationship(index) { next }) },
            onMoveUp = { onChange(state.moveRelationship(index, -1)) },
            onMoveDown = { onChange(state.moveRelationship(index, 1)) },
            onDelete = { onChange(state.removeRelationship(index)) }
        )
        Spacer(Modifier.height(8.dp))
    }
    AddRowButton(
        text = stringResource(R.string.guidance_world_add_relationship),
        onClick = { onChange(state.addRelationship()) }
    )
}

@Composable
private fun RelationshipRow(
    relation: WorldRelationship,
    showErrors: Boolean,
    onChange: (WorldRelationship) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    // 「自定义」是一个下拉项，但它背后是同一份自由文本：不在常用列表里的值原样进输入框，
    // 换回常用值也不丢东西（规范 §2.3：下拉 + 自定义）。
    val isCustom = relation.relation.isNotBlank() &&
        relation.relation !in WorldEditorState.RELATION_OPTIONS
    val customLabel = stringResource(R.string.guidance_world_relationship_custom)
    EditorRowCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StyledOutlinedField(
                value = relation.name,
                onValueChange = { onChange(relation.copy(name = it.take(30))) },
                label = stringResource(R.string.guidance_world_relationship_name_label),
                singleLine = true,
                isError = showErrors,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            DropdownField(
                value = if (isCustom) {
                    customLabel
                } else {
                    relation.relation.ifBlank {
                        stringResource(R.string.guidance_world_relationship_relation_label)
                    }
                },
                options = WorldEditorState.RELATION_OPTIONS + customLabel,
                label = stringResource(R.string.guidance_world_relationship_relation_label),
                modifier = Modifier.weight(0.9f),
                onSelect = { picked ->
                    onChange(relation.copy(relation = if (picked == customLabel) "" else picked))
                }
            )
        }
        if (isCustom) {
            Spacer(Modifier.height(6.dp))
            StyledOutlinedField(
                value = relation.relation,
                onValueChange = { onChange(relation.copy(relation = it.take(30))) },
                label = stringResource(R.string.guidance_world_relationship_custom_label),
                singleLine = true
            )
        }
        Spacer(Modifier.height(6.dp))
        StyledOutlinedField(
            value = relation.note,
            onValueChange = { onChange(relation.copy(note = it.take(120))) },
            label = stringResource(R.string.guidance_world_relationship_note_label),
            singleLine = true,
            isError = showErrors
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.guidance_world_intimacy_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            IntimacyPicker(
                value = relation.intimacy,
                onChange = { onChange(relation.copy(intimacy = it)) }
            )
            Spacer(Modifier.weight(1f))
            MoveButtons(onMoveUp = onMoveUp, onMoveDown = onMoveDown, onDelete = onDelete)
        }
    }
}

/** 可选亲密度 1–5：不填就是「没写」，不给自己加一个假的默认值。 */
@Composable
private fun IntimacyPicker(value: Int?, onChange: (Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextPill(
            text = stringResource(R.string.guidance_world_intimacy_none),
            selected = value == null,
            onClick = { onChange(null) }
        )
        (1..5).forEach { level ->
            TextPill(
                text = level.toString(),
                selected = value == level,
                onClick = { onChange(level) }
            )
        }
    }
}

// ---------------------------------------------------------------------- 义务

@Composable
private fun ObligationEditor(
    state: WorldEditorState,
    onChange: (WorldEditorState) -> Unit
) {
    EditorSectionHeader(
        title = stringResource(R.string.guidance_world_obligations_title),
        hint = stringResource(R.string.guidance_world_obligations_hint)
    )
    state.obligations.forEachIndexed { index, text ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StyledOutlinedField(
                value = text,
                onValueChange = { onChange(state.updateObligation(index, it.take(120))) },
                label = stringResource(R.string.guidance_world_obligation_label),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onChange(state.removeObligation(index)) }) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.guidance_world_delete_row),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    AddRowButton(
        text = stringResource(R.string.guidance_world_add_obligation),
        onClick = { onChange(state.addObligation()) }
    )
}

// ------------------------------------------------------------------ 其他背景

@Composable
private fun NotesEditor(
    state: WorldEditorState,
    onChange: (WorldEditorState) -> Unit
) {
    EditorSectionHeader(
        title = stringResource(R.string.guidance_world_notes_title),
        hint = stringResource(R.string.guidance_world_notes_hint)
    )
    StyledOutlinedField(
        value = state.notes,
        onValueChange = { onChange(state.withNotes(it)) },
        label = stringResource(R.string.guidance_world_notes_label),
        singleLine = false,
        minLines = 3,
        maxLines = 8
    )
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.guidance_world_notes_truncated),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            pluralStringResource(
                R.plurals.guidance_world_notes_counter,
                state.notes.length,
                state.notes.length,
                WorldPreset.MAX_NOTES_CHARS
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------- 零件

@Composable
private fun EditorSectionHeader(title: String, hint: String) {
    Spacer(Modifier.height(18.dp))
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    Text(
        hint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun EditorRowCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            0.8.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp), content = content)
    }
}

@Composable
private fun AddRowButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MoveButtons(onMoveUp: () -> Unit, onMoveDown: () -> Unit, onDelete: () -> Unit) {
    IconButton(onClick = onMoveUp, modifier = Modifier.size(34.dp)) {
        Icon(
            Icons.Outlined.KeyboardArrowUp,
            contentDescription = stringResource(R.string.guidance_world_move_up),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    IconButton(onClick = onMoveDown, modifier = Modifier.size(34.dp)) {
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            contentDescription = stringResource(R.string.guidance_world_move_down),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
        Icon(
            Icons.Outlined.DeleteOutline,
            contentDescription = stringResource(R.string.guidance_world_delete_row),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 下拉：点击展开一列选项；不选中任何东西就不改（不会因为误触把值清空）。 */
@Composable
private fun DropdownField(
    value: String,
    options: List<String>,
    label: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

/**
 * 时间选择器：显示 `HH:mm`，点一下弹系统时间选择器。
 *
 * 值本身仍然允许是任何字符串（历史数据/手改的 JSON 里可能不是规范格式），
 * 坏值照原样显示，保存时由状态层校验报错——而不是在这里悄悄改成 00:00。
 */
@Composable
private fun TimeField(value: String, label: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val initialMinutes = WorldPreset.minuteOfDay(value) ?: (9 * 60)
    Surface(
        onClick = {
            TimePickerDialog(
                context,
                { _, hour, minute -> onPick("%02d:%02d".format(hour, minute)) },
                initialMinutes / 60,
                initialMinutes % 60,
                true
            ).show()
        },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value.ifBlank { "--:--" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
