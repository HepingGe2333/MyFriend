package com.yap.app.ui.screens.apiconfig

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yap.app.R
import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.ui.components.AppHeader
import com.yap.app.ui.components.BackNavigation
import com.yap.app.ui.components.ConfirmDialog
import com.yap.app.ui.components.EmptyState
import com.yap.app.ui.components.TextPill
import com.yap.app.ui.theme.LocalAccentColor

@Composable
fun ApiListScreen(
    onBack: () -> Unit,
    onAddConfig: () -> Unit,
    onEditConfig: (String) -> Unit,
    viewModel: ApiListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ApiConfigEntity?>(null) }

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
                onClick = onAddConfig,
                containerColor = LocalAccentColor.current,
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.size(58.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.api_list_add_content_desc), modifier = Modifier.size(26.dp))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppHeader(
                title = stringResource(R.string.api_list_title),
                subtitle = stringResource(R.string.api_list_subtitle),
                navigation = { BackNavigation(onBack) }
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp)
            ) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Outlined.Shield, contentDescription = null, tint = LocalAccentColor.current, modifier = Modifier.size(19.dp))
                            Column {
                                Text(stringResource(R.string.api_list_security_title), style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    stringResource(R.string.api_list_security_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (state.configs.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.SettingsEthernet,
                            title = stringResource(R.string.api_list_empty_title),
                            description = stringResource(R.string.api_list_empty_desc),
                            modifier = Modifier.padding(top = 30.dp)
                        )
                    }
                } else {
                    items(state.configs, key = { it.id }) { config ->
                        ApiConfigCard(
                            config = config,
                            testing = state.testingId == config.id,
                            onEdit = { onEditConfig(config.id) },
                            onTest = { viewModel.test(config.id) },
                            onDuplicate = { viewModel.duplicate(config.id) },
                            onDelete = { pendingDelete = config }
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }

    pendingDelete?.let { config ->
        ConfirmDialog(
            title = stringResource(R.string.api_delete_title, config.name),
            message = stringResource(R.string.api_delete_message),
            confirmText = stringResource(R.string.api_delete),
            destructive = true,
            onConfirm = {
                viewModel.delete(config.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun ApiConfigCard(
    config: ApiConfigEntity,
    testing: Boolean,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDuplicate: () -> Unit,
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
                    .clickable(onClick = onEdit)
                    .padding(start = 16.dp, end = 6.dp, top = 15.dp, bottom = 13.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(LocalAccentColor.current.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.SettingsEthernet, contentDescription = null, tint = LocalAccentColor.current, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            config.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(7.dp))
                        TextPill(if (config.provider.name == "OPENAI") stringResource(R.string.api_provider_openai_compatible) else config.provider.name)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        config.model,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        config.baseUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.api_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.api_edit)) }, onClick = {
                            menuOpen = false
                            onEdit()
                        })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.api_duplicate)) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = {
                                menuOpen = false
                                onDuplicate()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.api_delete), color = MaterialTheme.colorScheme.error) },
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
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                Text(
                    if (config.apiKeyCipher.isNullOrBlank()) {
                        stringResource(R.string.api_key_not_set)
                    } else {
                        stringResource(R.string.api_key_stored)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(Modifier.weight(1f))
                if (testing) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp, color = LocalAccentColor.current)
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(R.string.api_testing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onTest)
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp), tint = LocalAccentColor.current)
                        Spacer(Modifier.width(3.dp))
                        Text(stringResource(R.string.api_test_connection), style = MaterialTheme.typography.labelSmall, color = LocalAccentColor.current)
                    }
                }
            }
        }
    }
}
