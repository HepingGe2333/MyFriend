@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.myfriend.app.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.ui.components.AppHeader
import com.myfriend.app.ui.components.CircleIconButton
import com.myfriend.app.ui.components.ConfirmDialog
import com.myfriend.app.ui.components.EmptyState
import com.myfriend.app.ui.components.PersonaAvatar
import com.myfriend.app.ui.theme.LocalAccentColor
import com.myfriend.app.util.formatShortDate

/**
 * 首页 = 联系人列表（规格 §七）。
 *
 * 一行一个联系人：头像、名字、最后一句预览、时间、未读数。
 * 没有「最近对话」与「新建对话」：一个人只有一段会话，点进去就是继续。
 * 右上「管理」进人格管理，`+` 是新建人格（联系人）；长按联系人删除（二次确认）。
 */
@Composable
fun HomeScreen(
    onOpenChat: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPersonas: () -> Unit,
    onOpenApiConfigs: () -> Unit,
    onCreatePersona: () -> Unit,
    onOpenMemory: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    var pendingDelete by remember { mutableStateOf<ContactItem?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.OpenChat -> onOpenChat(event.sessionId)
                is HomeEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.padding(bottom = 92.dp)) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCreatePersona()
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = LocalAccentColor.current,
                contentColor = Color.White,
                modifier = Modifier
                    .padding(end = 4.dp, bottom = 8.dp)
                    .size(58.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "新建人格", modifier = Modifier.size(26.dp))
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(LocalAccentColor.current.copy(alpha = 0.10f), Color.Transparent),
                            radius = 700f
                        )
                    )
            )
            Column(Modifier.fillMaxSize().padding(padding)) {
                AppHeader(
                    title = "MyFriend",
                    subtitle = "他就在这儿，点一个名字说话",
                    brand = true,
                    actions = {
                        CircleIconButton(Icons.Outlined.Search, "搜索", onOpenSearch)
                        CircleIconButton(Icons.Rounded.Settings, "设置", onOpenSettings)
                    }
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .combinedClickable(onClick = onOpenPersonas, onLongClick = null)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.PersonOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "管理",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (state.contacts.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.PersonOutline,
                        title = "从一个名字开始",
                        description = "新建一个联系人。他会是独立的一个人：有自己的性格、记性和作息。",
                        modifier = Modifier.padding(top = 28.dp),
                        action = {
                            com.myfriend.app.ui.components.PrimaryButton(
                                text = "新建人格",
                                onClick = onCreatePersona,
                                modifier = Modifier.width(230.dp)
                            )
                        }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 116.dp)
                    ) {
                        items(state.contacts, key = { it.persona.id }) { item ->
                            ContactRow(
                                item = item,
                                loading = state.switchingPersonaId == item.persona.id,
                                onClick = { viewModel.openContact(item.persona.id) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    pendingDelete = item
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        ConfirmDialog(
            title = "删除「${item.persona.name}」？",
            message = "他的消息、记忆、念头和规则都会从这台手机上永久删除，此操作无法撤销。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                viewModel.deleteContact(item.persona.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    item: ContactItem,
    loading: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val persona = item.persona
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            PersonaAvatar(persona.avatarUri, persona.avatarSeed, persona.name, size = 54.dp)
            if (loading) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                }
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = persona.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatShortDate(item.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(19.dp)
                            .clip(CircleShape)
                            .background(LocalAccentColor.current),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (item.unread > 9) "9+" else item.unread.toString(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
