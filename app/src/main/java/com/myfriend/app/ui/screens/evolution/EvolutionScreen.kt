@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.myfriend.app.ui.screens.evolution

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myfriend.app.data.local.PersonaRevisionEntity
import com.myfriend.app.domain.EvolutionProposal
import com.myfriend.app.ui.components.AppHeader
import com.myfriend.app.ui.components.BackNavigation
import com.myfriend.app.ui.components.ConfirmDialog
import com.myfriend.app.ui.components.PersonaAvatar
import com.myfriend.app.ui.components.PrimaryButton
import com.myfriend.app.ui.components.SectionLabel
import com.myfriend.app.ui.components.TextPill
import com.myfriend.app.ui.theme.LocalAccentColor
import com.myfriend.app.util.formatFullDateTime

@Composable
fun EvolutionScreen(
    onBack: () -> Unit,
    viewModel: EvolutionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var viewingPrompt by remember { mutableStateOf<Pair<String, String>?>(null) }
    var acceptingProposal by remember { mutableStateOf<EvolutionProposal?>(null) }
    var restoringRevision by remember { mutableStateOf<PersonaRevisionEntity?>(null) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppHeader(
                title = "人格进化",
                subtitle = "在保留核心的前提下，自然生长",
                navigation = { BackNavigation(onBack) }
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 30.dp)
            ) {
                item {
                    state.persona?.let { persona ->
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PersonaAvatar(
                                        persona.avatarUri,
                                        persona.avatarSeed,
                                        persona.name,
                                        size = 58.dp,
                                        ringColor = LocalAccentColor.current.copy(alpha = 0.5f)
                                    )
                                    Spacer(Modifier.width(13.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(persona.name, style = MaterialTheme.typography.headlineSmall)
                                        Text(
                                            persona.description.ifBlank { persona.category },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    TextPill("v${persona.version}")
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextPill("${persona.prompt.length} 字符")
                                    TextPill("${state.revisions.size} 个保留版本")
                                }
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "人格进化会读取当前提示词、长期记忆和最近的对话，生成一份完整的进化提案。只有你确认后才会应用，旧版本会一直保留。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(14.dp))
                                PrimaryButton(
                                    text = if (state.generating) "正在理解你们的关系…" else "生成进化提案",
                                    onClick = viewModel::generateProposal,
                                    loading = state.generating,
                                    enabled = !state.generating
                                )
                                if (state.generating) {
                                    TextButton(
                                        onClick = viewModel::cancelProposal,
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) { Text("取消生成") }
                                }
                            }
                        }
                    }
                }
                if (state.proposal != null) {
                    item {
                        SectionLabel("进化提案")
                        ProposalCard(
                            proposal = state.proposal!!,
                            accepting = state.accepting,
                            currentPromptChars = state.persona?.prompt?.length ?: 0,
                            onView = { viewingPrompt = "进化后的人格提示词" to state.proposal!!.prompt },
                            onAccept = { acceptingProposal = state.proposal },
                            onDiscard = viewModel::discardProposal
                        )
                    }
                }
                item { SectionLabel("人格保留 · 历史版本") }
                if (state.revisions.isEmpty()) {
                    item {
                        Text(
                            "暂无历史版本。人格首次保存后会自动保留快照。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    items(state.revisions, key = { it.id }) { revision ->
                        RevisionCard(
                            revision = revision,
                            isCurrent = revision.version == state.persona?.version,
                            restoring = state.restoringId == revision.id,
                            onView = { viewingPrompt = "版本 v${revision.version} 提示词" to revision.prompt },
                            onRestore = { restoringRevision = revision }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    viewingPrompt?.let { (title, prompt) ->
        PromptViewerDialog(
            title = title,
            prompt = prompt,
            onCopy = {
                clipboard.setText(AnnotatedString(prompt))
                viewingPrompt = null
            },
            onDismiss = { viewingPrompt = null }
        )
    }

    acceptingProposal?.let { proposal ->
        ConfirmDialog(
            title = "应用这次进化？",
            message = "当前版本 v${state.persona?.version ?: 0} 会被完整保留。应用后会生成新版本，下一轮对话就会用上。",
            confirmText = "应用进化",
            onConfirm = {
                viewModel.acceptProposal(proposal)
                acceptingProposal = null
            },
            onDismiss = { acceptingProposal = null }
        )
    }

    restoringRevision?.let { revision ->
        ConfirmDialog(
            title = "恢复到 v${revision.version}？",
            message = "当前版本会先被保存为历史版本，然后应用 v${revision.version} 的完整人格提示词与资料。",
            confirmText = "恢复",
            onConfirm = {
                viewModel.restoreRevision(revision)
                restoringRevision = null
            },
            onDismiss = { restoringRevision = null }
        )
    }
}

@Composable
private fun ProposalCard(
    proposal: EvolutionProposal,
    accepting: Boolean,
    currentPromptChars: Int,
    onView: () -> Unit,
    onAccept: () -> Unit,
    onDiscard: () -> Unit
) {
    // A proposal that shrinks a substantial prompt is usually a truncated reply,
    // not a real evolution. Surface it before the user replaces the persona core.
    val shrinkWarning = proposal.prompt.length * 2 < currentPromptChars && currentPromptChars >= 400

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = LocalAccentColor.current.copy(alpha = 0.07f),
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalAccentColor.current.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = LocalAccentColor.current, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("进化方向", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(7.dp))
            Text(
                proposal.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))
            Text(
                proposal.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 7,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onView, contentPadding = PaddingValues(horizontal = 2.dp)) {
                Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("查看完整提案")
            }
            if (shrinkWarning) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "这份提案比当前提示词短很多（${proposal.prompt.length} / $currentPromptChars 字符），可能丢失原有的核心设定。建议先查看完整提案。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDiscard, enabled = !accepting) { Text("放弃") }
                PrimaryButton(
                    text = if (accepting) "应用中…" else "接受并保留旧版本",
                    onClick = onAccept,
                    loading = accepting,
                    modifier = Modifier.weight(1f),
                    enabled = !accepting
                )
            }
        }
    }
}

@Composable
private fun RevisionCard(
    revision: PersonaRevisionEntity,
    isCurrent: Boolean,
    restoring: Boolean,
    onView: () -> Unit,
    onRestore: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCurrent) LocalAccentColor.current.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 15.dp, end = 10.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("v${revision.version}", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                if (isCurrent) TextPill("当前", selected = true)
                Spacer(Modifier.weight(1f))
                Text(
                    formatFullDateTime(revision.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                revision.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${revision.name} · ${revision.prompt.length} 字符",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onView, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("查看")
                }
                if (!isCurrent) {
                    TextButton(onClick = onRestore, enabled = !restoring, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        if (restoring) {
                            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                        } else {
                            Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("恢复此版本")
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptViewerDialog(
    title: String,
    prompt: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text("${prompt.length} 字符 · 完整原文", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = "复制全文") }
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 8.dp)
                ) {
                    Text(
                        prompt,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}
