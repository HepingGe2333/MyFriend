package com.myfriend.app.ui.screens.persona

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonOutline
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
import com.myfriend.app.data.local.ApiConfigEntity
import com.myfriend.app.data.local.PersonaEntity
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
fun PersonaListScreen(
    onBack: () -> Unit,
    onOpenPersona: (String) -> Unit,
    onCreatePersona: () -> Unit,
    onOpenPresets: () -> Unit,
    viewModel: PersonaListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pendingExport by remember { mutableStateOf<ExportFile?>(null) }
    var personaToDelete by remember { mutableStateOf<PersonaEntity?>(null) }

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
                snackbar.showSnackbar(result.fold({ "人格预设已导出" }, { "导出失败：${it.message}" }))
            }
        }
        pendingExport = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("无法读取文件")
                    }
                }
                result.onSuccess { viewModel.import(it) }
                    .onFailure { snackbar.showSnackbar("读取失败：${it.message}") }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is PersonaListEvent.ExportReady -> {
                    pendingExport = event.file
                    exportLauncher.launch(event.file.fileName)
                }
                is PersonaListEvent.OpenEditor -> onOpenPersona(event.personaId)
                PersonaListEvent.CreateNew -> onCreatePersona()
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
                onClick = onCreatePersona,
                containerColor = LocalAccentColor.current,
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.size(58.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "创建人格", modifier = Modifier.size(26.dp))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppHeader(
                title = "人格",
                subtitle = "每个人格拥有独立的提示词、记忆与上下文",
                navigation = { BackNavigation(onBack) },
                actions = {
                    CircleIconButton(
                        Icons.Outlined.Bookmarks,
                        "预设库",
                        onClick = onOpenPresets
                    )
                    CircleIconButton(
                        Icons.Outlined.FileUpload,
                        "导入人格",
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }
                    )
                    CircleIconButton(
                        Icons.Outlined.FileDownload,
                        "导出全部人格",
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
                                Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = LocalAccentColor.current,
                                modifier = Modifier.size(19.dp)
                            )
                            Text(
                                "人格提示词会完整保存。每次对话都以 system 消息把完整人格注入，不需要单独的同步步骤。",
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
                                CategoryChip("全部", state.category == null) { viewModel.setCategory(null) }
                            }
                            items(state.categories) { category ->
                                CategoryChip(category, state.category == category) {
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
                            icon = Icons.Outlined.PersonOutline,
                            title = "没有找到人格",
                            description = if (state.personas.isEmpty()) "创建一个人格，写下它的性格与说话方式。" else "这个分类下还没有人格。",
                            modifier = Modifier.padding(top = 30.dp)
                        )
                    }
                } else {
                    items(state.filtered, key = { it.id }) { persona ->
                        val config = state.configs.firstOrNull { it.id == persona.defaultApiConfigId }
                        PersonaCard(
                            persona = persona,
                            config = config,
                            onOpen = { onOpenPersona(persona.id) },
                            onSetDefault = { viewModel.setDefault(persona.id) },
                            onDuplicate = { viewModel.duplicate(persona.id) },
                            onExport = { viewModel.exportPersona(persona.id) },
                            onSaveAsPreset = { viewModel.saveAsPreset(persona.id) },
                            onDelete = { personaToDelete = persona }
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }

    personaToDelete?.let { persona ->
        ConfirmDialog(
            title = "删除「${persona.name}」？",
            message = "你们的那段会话、聊天记录、长期记忆与历史版本都会被永久删除。API 配置会被保留。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                viewModel.delete(persona.id)
                personaToDelete = null
            },
            onDismiss = { personaToDelete = null }
        )
    }
}

@Composable
private fun CategoryChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) LocalAccentColor.current.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) LocalAccentColor.current.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun PersonaCard(
    persona: PersonaEntity,
    config: ApiConfigEntity?,
    onOpen: () -> Unit,
    onSetDefault: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onSaveAsPreset: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(start = 15.dp, end = 6.dp, top = 14.dp, bottom = 12.dp)
            ) {
                PersonaAvatar(
                    persona.avatarUri,
                    persona.avatarSeed,
                    persona.name,
                    size = 54.dp,
                    ringColor = if (persona.isDefault) LocalAccentColor.current.copy(alpha = 0.55f) else null
                )
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            persona.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (persona.isDefault) {
                            Spacer(Modifier.width(7.dp))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = LocalAccentColor.current.copy(alpha = 0.13f)
                            ) {
                                Text(
                                    "默认",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalAccentColor.current,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        persona.description.ifBlank { "尚未填写简介" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(9.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextPill(persona.category.ifBlank { "未分类" })
                        Text(
                            text = config?.let { "${it.name} · ${it.model}" } ?: "未绑定 API",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (config == null) MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("编辑人格") }, onClick = {
                            menuOpen = false
                            onOpen()
                        })
                        if (!persona.isDefault) {
                            DropdownMenuItem(text = { Text("设为默认") }, onClick = {
                                menuOpen = false
                                onSetDefault()
                            })
                        }
                        DropdownMenuItem(
                            text = { Text("复制") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = {
                                menuOpen = false
                                onDuplicate()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("保存为预设") },
                            leadingIcon = { Icon(Icons.Outlined.Bookmarks, null) },
                            onClick = {
                                menuOpen = false
                                onSaveAsPreset()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("导出 JSON") },
                            leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                            onClick = {
                                menuOpen = false
                                onExport()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${persona.prompt.length} 字符提示词",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "版本 v${persona.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
    }
}
