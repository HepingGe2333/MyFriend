package com.yap.app.ui.screens.search

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yap.app.R
import com.yap.app.data.repo.SearchHit
import com.yap.app.data.repo.SessionSearchHit
import com.yap.app.ui.components.BackNavigation
import com.yap.app.ui.components.EmptyState
import com.yap.app.ui.components.PersonaAvatar
import com.yap.app.ui.theme.LocalAccentColor
import com.yap.app.util.formatShortDate

/**
 * 检索页。
 *
 * ## 布局（从上到下）
 *
 * 1. 搜索框（沿用原来的位置与样式，从首页搜索图标进来点开就能打字）；
 * 2. 两个过滤器：人格（全部 / 某个人）与时间（全部 / 最近 7 天 / 最近 30 天）。
 *    刻意用两个下拉胶囊而不是一长排 chip：人格可能有十几个，排成一行会把搜索框挤没；
 * 3. 分段：消息 / 会话。找「那句话」和找「那段对话」是两种意图，混在一列里会互相挤占；
 * 4. 结果列表：消息按摘要 + 高亮显示（高亮来自 `SearchSnippet`，不是界面自己截的），
 *    滚到底自动加载下一页（每页 30 条）。
 *
 * ## 点一条消息
 *
 * 会带着 `messageId` 打开对应会话，聊天页会一页页往前翻直到那条消息进入窗口，
 * 然后滚到它、给它约 2 秒的高亮。返回检索页时查询词、过滤条件、滚动位置都还在
 * （ViewModel 跟随返回栈条目存活，列表状态是 `rememberLazyListState` 的可保存状态）。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenMessage: (sessionId: String, messageId: String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.query))
    }

    // 只在这一页**第一次**打开时自动聚焦并弹键盘。
    // 从结果点进会话再回来时，查询词和滚动位置都还在（ViewModel + rememberLazyListState），
    // 这时再弹一次键盘会把用户刚才看的位置顶走，所以用 rememberSaveable 记住「已经聚焦过」。
    var autoFocused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoFocused) return@LaunchedEffect
        autoFocused = true
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // 输入框与 ViewModel 的单向同步：返回检索页时（ViewModel 还活着）把上次的查询词填回去。
    LaunchedEffect(state.query) {
        if (state.query != fieldValue.text) fieldValue = TextFieldValue(state.query)
    }

    // 滚到底自动加载下一页。是否真的发查询由 ViewModel 的状态位决定，这里只负责「该不该问」。
    LaunchedEffect(listState, state.tab, state.hits.size, state.hasMore, state.loadingMore) {
        if (state.tab != SearchTab.MESSAGES || !state.hasMore || state.loadingMore) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= state.hits.size - 2) viewModel.loadMore()
            }
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
            SearchField(
                fieldValue = fieldValue,
                focusRequester = focusRequester,
                onValueChange = {
                    fieldValue = it
                    viewModel.updateQuery(it.text)
                },
                onClear = {
                    fieldValue = TextFieldValue("")
                    viewModel.updateQuery("")
                },
                onBack = onBack,
                onSearch = { focusManager.clearFocus() }
            )

            if (state.query.isNotBlank()) {
                FilterBar(state = state, viewModel = viewModel)
            }

            when {
                state.query.isBlank() -> EmptyState(
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.search_empty_title),
                    description = stringResource(R.string.search_empty_desc),
                    modifier = Modifier.padding(top = 32.dp)
                )

                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                }

                state.failed -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.search_error_title),
                    description = stringResource(R.string.search_error_desc),
                    modifier = Modifier.padding(top = 32.dp),
                    action = {
                        TextButton(onClick = viewModel::retry) {
                            Text(stringResource(R.string.search_retry), color = LocalAccentColor.current)
                        }
                    }
                )

                !state.hasAnyResult -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.search_no_results_title, state.query.trim()),
                    description = stringResource(
                        if (state.personaId != null || state.timeFilter != SearchTimeFilter.ALL) {
                            R.string.search_no_results_filtered_desc
                        } else {
                            R.string.search_no_results_desc
                        }
                    ),
                    modifier = Modifier.padding(top = 32.dp)
                )

                state.tab == SearchTab.SESSIONS -> SessionResults(
                    state = state,
                    onOpenSession = { sessionId ->
                        keyboard?.hide()
                        onOpenSession(sessionId)
                    }
                )

                else -> MessageResults(
                    state = state,
                    listState = listState,
                    onOpenMessage = { hit ->
                        keyboard?.hide()
                        onOpenMessage(hit.sessionId, hit.messageId)
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- 搜索框

@Composable
private fun SearchField(
    fieldValue: TextFieldValue,
    focusRequester: FocusRequester,
    onValueChange: (TextFieldValue) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit
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
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(LocalAccentColor.current),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() })
                    )
                }
                if (fieldValue.text.isNotEmpty()) {
                    IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.search_clear),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}

// ---------------------------------------------------------------------- 过滤器 / 分段

@Composable
private fun FilterBar(state: SearchUiState, viewModel: SearchViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 两个芯片平分一行：英文的人格名 / 「Last 30 days」比中文长，
            // 用 weight 让它们按可用宽度收缩（芯片内是单行省略），不会把整行挤出去。
            PersonaFilterChip(state, viewModel, Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            TimeFilterChip(state, viewModel, Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.height(10.dp))
        TabSwitch(
            state = state,
            onSelect = viewModel::setTab
        )
    }
}

@Composable
private fun FilterChipSurface(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = LocalAccentColor.current
    Surface(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (active) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (active) accent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PersonaFilterChip(
    state: SearchUiState,
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val accent = LocalAccentColor.current
    Box(modifier) {
        FilterChipSurface(
            label = state.activePersona?.name ?: stringResource(R.string.search_filter_persona_all),
            active = state.personaId != null,
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.search_filter_persona_all),
                        color = if (state.personaId == null) accent else MaterialTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    expanded = false
                    viewModel.setPersona(null)
                }
            )
            state.personas.forEach { persona ->
                DropdownMenuItem(
                    text = {
                        Text(
                            persona.name,
                            color = if (state.personaId == persona.id) accent else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = {
                        PersonaAvatar(persona.avatarUri, persona.avatarSeed, persona.name, size = 22.dp)
                    },
                    onClick = {
                        expanded = false
                        viewModel.setPersona(persona.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun TimeFilterChip(
    state: SearchUiState,
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val accent = LocalAccentColor.current
    Box(modifier) {
        FilterChipSurface(
            label = timeFilterLabel(state.timeFilter),
            active = state.timeFilter != SearchTimeFilter.ALL,
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SearchTimeFilter.entries.forEach { filter ->
                DropdownMenuItem(
                    text = {
                        Text(
                            timeFilterLabel(filter),
                            color = if (state.timeFilter == filter) accent else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        expanded = false
                        viewModel.setTimeFilter(filter)
                    }
                )
            }
        }
    }
}

@Composable
private fun timeFilterLabel(filter: SearchTimeFilter): String = when (filter) {
    SearchTimeFilter.ALL -> stringResource(R.string.search_time_all)
    SearchTimeFilter.LAST_7_DAYS -> stringResource(R.string.search_time_7d)
    SearchTimeFilter.LAST_30_DAYS -> stringResource(R.string.search_time_30d)
}

/**
 * 分段控件：消息 / 会话。
 *
 * 用两段的自绘胶囊而不是 `TabRow`：这里只有两段、而且要和上面的过滤胶囊在同一行视觉层级里，
 * `TabRow` 自带的指示条在只有两项时会显得过重。
 */
@Composable
private fun TabSwitch(state: SearchUiState, onSelect: (SearchTab) -> Unit) {
    val accent = LocalAccentColor.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.fillMaxWidth().padding(3.dp)) {
            TabSwitchItem(
                label = stringResource(R.string.search_tab_messages),
                count = if (state.tab == SearchTab.MESSAGES) state.total else null,
                selected = state.tab == SearchTab.MESSAGES,
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(SearchTab.MESSAGES) }
            )
            TabSwitchItem(
                label = stringResource(R.string.search_tab_sessions),
                count = if (state.tab == SearchTab.SESSIONS) state.total else null,
                selected = state.tab == SearchTab.SESSIONS,
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(SearchTab.SESSIONS) }
            )
        }
    }
}

@Composable
private fun TabSwitchItem(
    label: String,
    count: Int?,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) accent.copy(alpha = 0.13f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count != null && count > 0) stringResource(R.string.search_tab_with_count, label, count) else label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------- 结果列表

@Composable
private fun MessageResults(
    state: SearchUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenMessage: (SearchHit) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)
    ) {
        items(state.hits, key = { it.messageId }) { hit ->
            MessageHitRow(hit) { onOpenMessage(hit) }
        }
        if (state.loadingMore) {
            item(key = "loading-more") {
                Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            }
        }
        if (state.scannedAllHistory) {
            // 0.12：检索改成「逐块扫描 + 键集游标无上限」之后不再有「只显示最近 N 条」这回事。
            // 翻到最后一页时说明「已扫描全部历史」，让用户确信没有更早的命中被悄悄丢掉。
            item(key = "scanned-all") {
                Text(
                    stringResource(R.string.search_scanned_all_history, state.total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SessionResults(state: SearchUiState, onOpenSession: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)
    ) {
        items(state.sessions, key = { it.sessionId }) { hit ->
            SessionHitRow(hit, state.query.trim()) { onOpenSession(hit.sessionId) }
        }
    }
}

@Composable
private fun SessionHitRow(hit: SessionSearchHit, query: String, onClick: () -> Unit) {
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
            Text(
                highlightText(hit.title, query),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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

/**
 * 一条消息命中：人格名 · 会话名 · 时间，下面是带高亮的摘要。
 *
 * 摘要与高亮区间都来自 `SearchSnippet`（跨端契约），这里只做「区间 → SpanStyle」的渲染，
 * 不再自己截断字符串：以前界面自己 `indexOf` + `substring`，中文和大小写混合时偏移经常错位。
 */
@Composable
private fun MessageHitRow(hit: SearchHit, onClick: () -> Unit) {
    val accent = LocalAccentColor.current
    val highlightColor = MaterialTheme.colorScheme.onSurface
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
                Text(hit.personaName, style = MaterialTheme.typography.labelMedium, color = accent)
                Text(
                    "  ·  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    hit.sessionTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    formatShortDate(LocalContext.current, hit.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = annotatedSnippet(hit, accent, highlightColor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 摘要 → AnnotatedString：高亮区间加重 + 主色底。 */
@Composable
private fun annotatedSnippet(
    hit: SearchHit,
    accent: androidx.compose.ui.graphics.Color,
    onSurface: androidx.compose.ui.graphics.Color
): AnnotatedString {
    if (hit.highlights.isEmpty()) return AnnotatedString(hit.snippet)
    return buildAnnotatedString {
        var cursor = 0
        hit.highlights.sortedBy { it.first }.forEach { range ->
            val from = range.first.coerceIn(0, hit.snippet.length)
            val to = (range.last + 1).coerceIn(from, hit.snippet.length)
            if (from > cursor) append(hit.snippet.substring(cursor, from))
            withStyle(
                SpanStyle(
                    color = onSurface,
                    background = accent.copy(alpha = 0.22f),
                    fontWeight = FontWeight.SemiBold
                )
            ) {
                append(hit.snippet.substring(from, to))
            }
            cursor = to
        }
        if (cursor < hit.snippet.length) append(hit.snippet.substring(cursor))
    }
}

/** 会话标题里命中关键词的那一段也加重显示；大小写不敏感，但保留原文大小写。 */
@Composable
private fun highlightText(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val index = text.indexOf(query, ignoreCase = true)
    if (index < 0) return AnnotatedString(text)
    val accent = LocalAccentColor.current
    return buildAnnotatedString {
        append(text.substring(0, index))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
            append(text.substring(index, index + query.length))
        }
        append(text.substring(index + query.length))
    }
}
