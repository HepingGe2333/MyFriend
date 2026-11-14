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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                            ?: error("无法写入文件")
                    }
                }
                snackbar.showSnackbar(result.fold({ "预设已导出" }, { "导出失败：${it.message}" }))
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
                            ?: error("无法读取文件")
                    }
                }
                text.onSuccess { viewModel.import(it) }
                    .onFailure { snackbar.showSnackbar("读取失败：${it.message}") }
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
                Icon(Icons.Outlined.Add, contentDescription = "新建预设", modifier = Modifier.size(26.dp))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppHeader(
                title = "预设库",
                subtitle = "可复用的提示词模板，用来快速生成新人格",
                navigation = { BackNavigation(onBack) },
                actions = {
                    CircleIconButton(
                        Icons.Outlined.FileUpload,
                        "导入预设",
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }
                    )
                    CircleIconButton(
                        Icons.Outlined.FileDownload,
                        "导出全部预设",
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
                                "预设只是模板：用预设创建人格后，人格拥有独立的提示词、记忆与上下文，之后对人格的修改不会影响预设。",
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
                                PresetCategoryChip("全部", state.category == null) { viewModel.setCategory(null) }
                            }
                            items(state.categories) { category ->
                                PresetCategoryChip(category, state.category == category) {
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
                            title = "预设库还是空的",
                            description = "把常用的人格设定存成预设，下次用一键生成新人格；也可以从人格详情里保存为预设。",
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
            title = "删除预设「${preset.name}」？",
            message = "已经用它创建的人格不会受影响，只是以后不能再从这里新建。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                viewModel.delete(preset.id)
                presetToDelete = null
            },
            onDismiss = { presetToDelete = null }
        )
    }
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
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("用这个预设创建人格") },
                            leadingIcon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onCreatePersona()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("编辑预设") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("复制") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDuplicate()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("导出 JSON") },
                            leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onExport()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
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
                TextPill(preset.category)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${preset.prompt.length} 字符提示词",
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
                        "用此预设新建",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
