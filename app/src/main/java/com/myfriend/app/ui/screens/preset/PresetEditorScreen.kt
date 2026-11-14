package com.myfriend.app.ui.screens.preset

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myfriend.app.R
import com.myfriend.app.ui.components.AppHeader
import com.myfriend.app.ui.components.BackNavigation
import com.myfriend.app.ui.components.PersonaAvatar
import com.myfriend.app.ui.components.PrimaryButton
import com.myfriend.app.ui.components.SectionLabel
import com.myfriend.app.ui.components.StyledOutlinedField
import com.myfriend.app.ui.components.TextPill
import com.myfriend.app.ui.theme.avatarPalette

// 分类是落库的数据值，界面显示时通过 presetCategoryLabel() 映射，常量保持原样。
private val categoryOptions = listOf("日常聊天", "角色扮演", "学习", "编程", "创意写作", "通用")

@Composable
fun PresetEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: PresetEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is PresetEditorEvent.Saved -> onSaved()
                is PresetEditorEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AppHeader(
            title = if (state.isNew) stringResource(R.string.preset_new) else stringResource(R.string.preset_edit),
            subtitle = stringResource(R.string.preset_editor_subtitle),
            navigation = { BackNavigation(onBack) }
        )
        if (!state.loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.preset_editor_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PersonaAvatar(
                    avatarUri = null,
                    seed = state.form.avatarSeed,
                    name = state.form.name.ifBlank { stringResource(R.string.preset_avatar_initial) },
                    size = 82.dp
                )
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed((0 until 12).toList()) { index, seed ->
                    val palette = avatarPalette(seed)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(palette.first, palette.second)))
                            .then(
                                if (state.form.avatarSeed == seed) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), CircleShape)
                                } else Modifier
                            )
                            .clickable { viewModel.update { it.copy(avatarSeed = seed) } },
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.form.avatarSeed == seed) {
                            androidx.compose.material3.Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            StyledOutlinedField(
                value = state.form.name,
                onValueChange = { value -> viewModel.update { it.copy(name = value.take(60)) } },
                label = stringResource(R.string.preset_editor_name_label),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            StyledOutlinedField(
                value = state.form.description,
                onValueChange = { value -> viewModel.update { it.copy(description = value.take(300)) } },
                label = stringResource(R.string.preset_editor_description_label),
                singleLine = false,
                minLines = 2,
                maxLines = 3
            )
            Spacer(Modifier.height(16.dp))
            SectionLabel(stringResource(R.string.preset_editor_category_section))
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categoryOptions) { option ->
                    TextPill(
                        text = presetCategoryLabel(option),
                        selected = state.form.category == option,
                        onClick = { viewModel.update { it.copy(category = option) } }
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(
                    stringResource(R.string.preset_editor_prompt_section),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    pluralStringResource(
                        R.plurals.preset_editor_prompt_chars,
                        state.form.prompt.length,
                        state.form.prompt.length
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                StyledOutlinedField(
                    value = state.form.prompt,
                    onValueChange = { value -> viewModel.update { it.copy(prompt = value.take(120_000)) } },
                    label = stringResource(R.string.preset_editor_prompt_label),
                    singleLine = false,
                    minLines = 10,
                    maxLines = 24,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.preset_editor_prompt_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                PrimaryButton(
                    text = if (state.saving) {
                        stringResource(R.string.preset_editor_saving)
                    } else {
                        stringResource(R.string.preset_editor_save)
                    },
                    enabled = !state.saving && state.form.name.isNotBlank() && state.form.prompt.isNotBlank(),
                    loading = state.saving,
                    onClick = viewModel::save
                )
            }
        }
        SnackbarHost(snackbar)
    }
}
