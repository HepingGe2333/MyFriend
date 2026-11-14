package com.myfriend.app.ui.screens.preset

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myfriend.app.ui.common.ErrorText
import com.myfriend.app.R
import com.myfriend.app.data.local.PersonaPresetEntity
import com.myfriend.app.domain.ExportFile
import com.myfriend.app.ui.components.AppHeader
import com.myfriend.app.ui.components.BackNavigation
import com.myfriend.app.ui.components.CircleIconButton
import com.myfriend.app.ui.components.ConfirmDialog
import com.myfriend.app.ui.components.EmptyState
import com.myfriend.app.ui.components.PersonaAvatar
import com.myfriend.app.ui.components.TextPill
import com.myfriend.app.ui.theme.LocalAccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PresetListScreen(
    onBack: () -> Unit,
    onEditPreset: (String?) -> Unit,
    onPersonaCreated: (String) -> Unit,
    viewModel: PresetListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pendingExport by remember { mutableStateOf<ExportFile?>(null) }
    var presetToDelete by remember { mutableStateOf<PersonaPresetEntity?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val file = pendingExport
        if (uri != null && file != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(file.content.toByteArray()) }
                            ?: error(context.getString(R.string.preset_write_unavailable))
                    }
                }
                snackbar.showSnackbar(
                    result.fold(
                        { context.getString(R.string.preset_export_done) },
                        { context.getString(R.string.preset_export_error_detail, ErrorText.of(context, it)) }
                    )
                )
            }
        }
        pendingExport = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error(context.getString(R.string.preset_read_unavailable))
                    }
                }
                text.onSuccess { viewModel.import(it) }
                    .onFailure {
                        snackbar.showSnackbar(
                            context.getString(R.string.preset_read_error_detail, ErrorText.of(context, it))
                        )
                    }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is PresetListEvent.ExportReady -> {
                    pendingExport = event.file
                    exportLauncher.launch(event.file.fileName)
                }
                is PresetListEvent.OpenEditor -> onEditPreset(event.presetId)
                is PresetListEvent.PersonaCreated -> onPersonaCreated(event.personaId)
            }
        }
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.newPreset() },
                containerColor = LocalAccentColor.current,
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.size(58.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.preset_new), modifier = Modifier.size(26.dp))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppHeader(
                title = stringResource(R.string.preset_list_title),
                subtitle = stringResource(R.string.preset_list_subtitle),
                navigation = { BackNavigation(onBack) },
                actions = {
                    CircleIconButton(
                        Icons.Outlined.FileUpload,
                        stringResource(R.string.preset_import),
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }
                    )
                    CircleIconButton(
                        Icons.Outlined.FileDownload,
                        stringResource(R.string.preset_export_all),
                        onClick = { viewModel.exportAll() }
                    )
                }
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp)
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = LocalAccentColor.current.copy(alpha = 0.075f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Outlined.Bookmarks,
                                contentDescription = null,
                                tint = LocalAccentColor.current,
                                modifier = Modifier.size(19.dp)
                            )
                            Text(
                                stringResource(R.string.preset_info_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (state.categories.isNotEmpty()) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                PresetCategoryChip(
                                    stringResource(R.string.preset_category_all),
                                    state.category == null
                                ) { viewModel.setCategory(null) }
                            }
                            items(state.categories) { category ->
                                PresetCategoryChip(presetCategoryLabel(category), state.category == category) {
                                    viewModel.setCategory(if (state.category == category) null else category)
                                }
                            }
                        }
                    }
                } else {
                    item { Spacer(Modifier.height(10.dp)) }
                }
                if (state.filtered.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.Bookmarks,
                            title = stringResource(R.string.preset_empty_title),
                            description = stringResource(R.string.preset_empty_desc),
                            modifier = Modifier.padding(top = 26.dp)
                        )
                    }
                } else {
                    items(state.filtered, key = { it.id }) { preset ->
                        PresetCard(
                            preset = preset,
                            onCreatePersona = { viewModel.createPersona(preset.id) },
                            onEdit = { viewModel.editPreset(preset.id) },
                            onDuplicate = { viewModel.duplicate(preset.id) },
                            onExport = { viewModel.exportPreset(preset.id) },
                            onDelete = { presetToDelete = preset }
                        )
                    }
                }
            }
        }
    }

    presetToDelete?.let { preset ->
        ConfirmDialog(
            title = stringResource(R.string.preset_delete_title, preset.name),
            message = stringResource(R.string.preset_delete_message),
            confirmText = stringResource(R.string.preset_delete),
            destructive = true,
            onConfirm = {
                viewModel.delete(preset.id)
                presetToDelete = null
            },
            onDismiss = { presetToDelete = null }
        )
    }
}

/**
 * 预设分类是**落库的数据值**（见 `PresetRepository` 与 `PresetForm.category` 的默认值），
 * 所以常量本身永远保持中文原样，只在界面上做一次显示映射；用户自定义的分类原样显示。
 */
@Composable
internal fun presetCategoryLabel(category: String): String = when (category) {
    "日常聊天" -> stringResource(R.string.preset_category_daily_chat)
    "角色扮演" -> stringResource(R.string.preset_category_roleplay)
    "学习" -> stringResource(R.string.preset_category_study)
    "编程" -> stringResource(R.string.preset_category_coding)
    "创意写作" -> stringResource(R.string.preset_category_writing)
    "通用" -> stringResource(R.string.preset_category_general)
    else -> category
}

@Composable
private fun PresetCategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccentColor.current
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            0.8.dp,
            if (selected) accent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun PresetCard(
    preset: PersonaPresetEntity,
    onCreatePersona: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val accent = LocalAccentColor.current
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clickable(onClick = onEdit)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PersonaAvatar(
                    avatarUri = preset.avatarUri,
                    seed = preset.avatarSeed,
                    name = preset.name,
                    size = 44.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        preset.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (preset.description.isNotBlank()) {
                        Text(
                            preset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.preset_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.preset_create_persona)) },
                            leadingIcon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onCreatePersona()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.preset_edit)) },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.preset_duplicate)) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDuplicate()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.preset_export_json)) },
                            leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onExport()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.preset_delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextPill(presetCategoryLabel(preset.category))
                Spacer(Modifier.width(8.dp))
                Text(
                    pluralStringResource(
                        R.plurals.preset_prompt_chars,
                        preset.prompt.length,
                        preset.prompt.length
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = accent.copy(alpha = 0.14f),
                    modifier = Modifier.clickable(onClick = onCreatePersona)
                ) {
                    Text(
                        stringResource(R.string.preset_create_persona_short),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
