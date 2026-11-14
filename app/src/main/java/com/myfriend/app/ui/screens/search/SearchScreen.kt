package com.myfriend.app.ui.screens.search

import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myfriend.app.R
import com.myfriend.app.data.repo.MessageSearchHit
import com.myfriend.app.data.repo.SessionSearchHit
import com.myfriend.app.ui.components.BackNavigation
import com.myfriend.app.ui.components.EmptyState
import com.myfriend.app.ui.components.PersonaAvatar
import com.myfriend.app.ui.components.SectionLabel
import com.myfriend.app.ui.theme.LocalAccentColor
import com.myfriend.app.util.formatShortDate

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(64.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackNavigation(onBack)
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (fieldValue.text.isEmpty()) {
                                Text(
                                    stringResource(R.string.search_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            BasicTextField(
                                value = fieldValue,
                                onValueChange = {
                                    fieldValue = it
                                    viewModel.updateQuery(it.text)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(LocalAccentColor.current),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                            )
                        }
                        if (fieldValue.text.isNotEmpty()) {
                            IconButton(onClick = {
                                fieldValue = TextFieldValue("")
                                viewModel.updateQuery("")
                            }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.search_clear), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
            val hasResults = state.results.sessions.isNotEmpty() || state.results.messages.isNotEmpty()
            when {
                state.query.isBlank() -> {
                    EmptyState(
                        icon = Icons.Outlined.Search,
                        title = stringResource(R.string.search_empty_title),
                        description = stringResource(R.string.search_empty_desc),
                        modifier = Modifier.padding(top = 40.dp)
                    )
                }
                !hasResults -> {
                    EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.search_no_results_title, state.query),
                        description = stringResource(R.string.search_no_results_desc),
                        modifier = Modifier.padding(top = 40.dp)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
                    ) {
                        if (state.results.sessions.isNotEmpty()) {
                            item { SectionLabel(stringResource(R.string.search_section_sessions)) }
                            items(state.results.sessions, key = { "s-${it.sessionId}" }) { hit ->
                                SessionSearchRow(hit, state.query) { onOpenSession(hit.sessionId) }
                            }
                        }
                        if (state.results.messages.isNotEmpty()) {
                            item { SectionLabel(stringResource(R.string.search_section_messages)) }
                            items(state.results.messages, key = { "m-${it.sessionId}-${it.createdAt}-${it.content.hashCode()}" }) { hit ->
                                MessageSearchRow(hit, state.query) {
                                    keyboard?.hide()
                                    onOpenSession(hit.sessionId)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSearchRow(hit: SessionSearchHit, query: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PersonaAvatar(hit.personaAvatarUri, hit.personaAvatarSeed, hit.personaName, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(highlight(hit.title, query), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(
                    R.string.search_session_subtitle,
                    hit.personaName,
                    hit.preview ?: stringResource(R.string.search_no_preview)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (hit.lastMessageAt != null) {
            Text(
                formatShortDate(LocalContext.current, hit.lastMessageAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun MessageSearchRow(hit: MessageSearchHit, query: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        PersonaAvatar(hit.personaAvatarUri, hit.personaAvatarSeed, hit.personaName, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hit.personaName, style = MaterialTheme.typography.labelMedium, color = LocalAccentColor.current)
                Text("  ·  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Text(
                    hit.sessionTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(formatShortDate(LocalContext.current, hit.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                highlight(snippet(hit.content, query), query),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun snippet(content: String, query: String): String {
    val flattened = content.replace('\n', ' ')
    val index = flattened.indexOf(query, ignoreCase = true)
    if (index < 0) return flattened.take(160)
    val start = (index - 50).coerceAtLeast(0)
    val end = (index + query.length + 100).coerceAtMost(flattened.length)
    return (if (start > 0) "…" else "") + flattened.substring(start, end) + (if (end < flattened.length) "…" else "")
}

@Composable
private fun highlight(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    val start = lowerText.indexOf(lowerQuery)
    if (start < 0) return AnnotatedString(text)
    val accent = LocalAccentColor.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        append(text.substring(0, start))
        withStyle(SpanStyle(background = accent.copy(alpha = 0.2f), color = onSurface)) {
            append(text.substring(start, start + query.length))
        }
        append(text.substring(start + query.length))
    }
}
