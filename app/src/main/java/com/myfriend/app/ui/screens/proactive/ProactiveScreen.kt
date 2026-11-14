package com.myfriend.app.ui.screens.proactive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myfriend.app.ui.common.ErrorText
import com.myfriend.app.R
import com.myfriend.app.data.local.PersonaEntity
import com.myfriend.app.data.local.ProactiveKind
import com.myfriend.app.data.local.ProactiveRuleEntity
import com.myfriend.app.data.local.ProactiveSource
import com.myfriend.app.data.repo.ProactiveProtocol
import com.myfriend.app.domain.MoodState
import com.myfriend.app.domain.Presence
import com.myfriend.app.ui.components.AppHeader
import com.myfriend.app.ui.components.BackNavigation
import com.myfriend.app.ui.components.ConfirmDialog
import com.myfriend.app.ui.components.EmptyState
import com.myfriend.app.ui.components.PersonaAvatar
import com.myfriend.app.ui.components.PresenceText
import com.myfriend.app.ui.components.SectionLabel
import com.myfriend.app.ui.components.StyledOutlinedField
import com.myfriend.app.ui.components.TextPill
import com.myfriend.app.ui.theme.LocalAccentColor
import com.myfriend.app.util.formatClock

@Composable
fun ProactiveScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    viewModel: ProactiveViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var ruleToDelete by remember { mutableStateOf<ProactiveRuleEntity?>(null) }

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    var permissionGranted by remember { mutableStateOf(hasNotificationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionGranted = granted }

    fun ensurePermission() {
        if (!hasNotificationPermission()) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is ProactiveEvent.OpenChat -> onOpenChat(event.sessionId)
            }
        }
    }
    // 历史数据的「待确认」规则直接生效，不再等人批准。
    LaunchedEffect(state.rules) { viewModel.activateLegacyPending(state.rules) }
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
                onClick = {
                    ensurePermission()
                    showAdd = true
                },
                containerColor = LocalAccentColor.current,
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.size(58.dp)
            ) {
                Icon(
                    Icons.Outlined.Alarm,
                    contentDescription = stringResource(R.string.proactive_fab_new),
                    modifier = Modifier.size(25.dp)
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppHeader(
                title = stringResource(R.string.proactive_title),
                subtitle = stringResource(R.string.proactive_subtitle),
                navigation = { BackNavigation(onBack) }
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
                                Icons.Outlined.Lightbulb,
                                contentDescription = null,
                                tint = LocalAccentColor.current,
                                modifier = Modifier.size(19.dp)
                            )
                            Text(
                                stringResource(R.string.proactive_intro_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (!permissionGranted) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .clickable { ensurePermission() }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.NotificationsActive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResource(R.string.proactive_permission_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                if (state.mood.updatedAt > 0L || state.presence.isMeaningful()) {
                    item { MoodCard(state.mood, state.presence) }
                }
                if (state.intents.isNotEmpty()) {
                    item { AgentIntentsCard(state) }
                }
                if (state.thoughts.isNotEmpty()) {
                    item { AgentThoughtsCard(state.thoughts) }
                }
                if (state.rules.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.Schedule,
                            title = stringResource(R.string.proactive_empty_title),
                            description = stringResource(R.string.proactive_empty_description),
                            modifier = Modifier.padding(top = 26.dp)
                        )
                    }
                } else {
                    items(state.rules, key = { it.id }) { rule ->
                        RuleCard(
                            rule = rule,
                            persona = state.persona(rule.personaId),
                            onToggle = { enabled ->
                                ensurePermission()
                                viewModel.setEnabled(rule, enabled)
                            },
                            onDelete = { ruleToDelete = rule },
                            onTriggerNow = { viewModel.triggerNow(rule.id) },
                            onOpenChat = { viewModel.openChat(rule) }
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddRuleDialog(
            personas = state.personas,
            defaultPersonaId = state.rules.firstOrNull()?.personaId ?: state.personas.firstOrNull()?.id,
            onDismiss = { showAdd = false },
            onConfirm = { personaId, kind, time, interval, note, hint ->
                viewModel.createManual(
                    personaId = personaId,
                    kind = kind,
                    timeOfDayMinutes = time,
                    intervalMinutes = interval,
                    hint = hint,
                    note = note,
                    notificationPermissionGranted = hasNotificationPermission()
                )
                showAdd = false
            }
        )
    }

    ruleToDelete?.let { rule ->
        ConfirmDialog(
            title = stringResource(R.string.proactive_delete_title),
            message = stringResource(R.string.proactive_delete_message, rule.note),
            confirmText = stringResource(R.string.proactive_delete_confirm),
            dismissText = stringResource(R.string.proactive_cancel),
            destructive = true,
            onConfirm = {
                viewModel.delete(rule.id)
                ruleToDelete = null
            },
            onDismiss = { ruleToDelete = null }
        )
    }
}

@Composable
private fun RuleCard(
    rule: ProactiveRuleEntity,
    persona: PersonaEntity?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onTriggerNow: () -> Unit,
    onOpenChat: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val accent = LocalAccentColor.current
    // 历史数据里 pending 的规则在新语义下也算「他已经安排好了」，页面不再等谁确认。
    val effectiveEnabled = rule.enabled || rule.pending
    val sourceText = stringResource(
        if (rule.source == ProactiveSource.AI) R.string.proactive_source_agent else R.string.proactive_source_user
    )
    val nextText = if (effectiveEnabled) {
        stringResource(R.string.proactive_next_trigger, formatClock(LocalContext.current, rule.nextTriggerAt))
    } else {
        stringResource(R.string.proactive_next_off)
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            0.8.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clickable(onClick = onOpenChat)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (persona != null) {
                    PersonaAvatar(
                        avatarUri = persona.avatarUri,
                        seed = persona.avatarSeed,
                        name = persona.name,
                        size = 36.dp
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        rule.note,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(
                            R.string.proactive_rule_meta,
                            persona?.name ?: stringResource(R.string.proactive_rule_persona_fallback),
                            sourceText,
                            nextText
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = effectiveEnabled,
                    onCheckedChange = onToggle,
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accent
                    )
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.proactive_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.proactive_try_now)) },
                            leadingIcon = { Icon(Icons.Outlined.NotificationsActive, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onTriggerNow()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.proactive_menu_delete), color = MaterialTheme.colorScheme.error) },
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
            if (rule.hint.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    rule.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (rule.source == ProactiveSource.AI) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.proactive_agent_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
            ErrorText.stored(LocalContext.current, rule.lastError)?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.proactive_last_error, error),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddRuleDialog(
    personas: List<PersonaEntity>,
    defaultPersonaId: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, ProactiveKind, Int?, Int?, String, String) -> Unit
) {
    var personaId by remember { mutableStateOf(defaultPersonaId) }
    var kind by remember { mutableStateOf(ProactiveKind.DAILY) }
    var timeText by remember { mutableStateOf("22:30") }
    var hoursText by remember { mutableStateOf("3") }
    var note by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    val minutes = ProactiveProtocol.parseTimeOfDay(timeText)
    val hours = hoursText.trim().toIntOrNull()?.takeIf { it in 1..24 }
    val valid = personaId != null && (if (kind == ProactiveKind.DAILY) minutes != null else hours != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.proactive_dialog_title_new)) },
        text = {
            Column {
                if (personas.isEmpty()) {
                    Text(
                        stringResource(R.string.proactive_no_persona),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    return@AlertDialog
                }
                SectionLabel(stringResource(R.string.proactive_section_persona))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(personas) { persona ->
                        TextPill(
                            text = persona.name,
                            selected = personaId == persona.id,
                            onClick = { personaId = persona.id }
                        )
                    }
                }
                SectionLabel(stringResource(R.string.proactive_section_trigger))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextPill(
                        text = stringResource(R.string.proactive_kind_daily),
                        selected = kind == ProactiveKind.DAILY
                    ) {
                        kind = ProactiveKind.DAILY
                    }
                    TextPill(
                        text = stringResource(R.string.proactive_kind_interval),
                        selected = kind == ProactiveKind.INTERVAL
                    ) {
                        kind = ProactiveKind.INTERVAL
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (kind == ProactiveKind.DAILY) {
                    StyledOutlinedField(
                        value = timeText,
                        onValueChange = { timeText = it.take(5) },
                        label = stringResource(R.string.proactive_field_time_label),
                        singleLine = true,
                        isError = minutes == null,
                        supportingText = if (minutes == null) {
                            stringResource(R.string.proactive_field_time_error)
                        } else null
                    )
                } else {
                    StyledOutlinedField(
                        value = hoursText,
                        onValueChange = { hoursText = it.filter { ch -> ch.isDigit() }.take(2) },
                        label = stringResource(R.string.proactive_field_interval_label),
                        singleLine = true,
                        isError = hours == null
                    )
                }
                Spacer(Modifier.height(10.dp))
                StyledOutlinedField(
                    value = note,
                    onValueChange = { note = it.take(60) },
                    label = stringResource(R.string.proactive_field_note_label),
                    singleLine = true,
                    placeholder = stringResource(R.string.proactive_field_note_placeholder)
                )
                Spacer(Modifier.height(10.dp))
                StyledOutlinedField(
                    value = hint,
                    onValueChange = { hint = it.take(300) },
                    label = stringResource(R.string.proactive_field_hint_label),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    placeholder = stringResource(R.string.proactive_field_hint_placeholder)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.proactive_dialog_footnote),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        personaId!!,
                        kind,
                        if (kind == ProactiveKind.DAILY) minutes else null,
                        if (kind == ProactiveKind.INTERVAL) hours?.times(60) else null,
                        note,
                        hint
                    )
                }
            ) { Text(stringResource(R.string.proactive_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.proactive_cancel)) } }
    )
}

/** 他给自己留的念头：一段窗口，不是准点。 */
/**
 * 「他此刻」——心情和正在做的事都是他自己的状态，不是给用户填的表，所以只展示、不提供调节。
 */
@Composable
private fun MoodCard(mood: MoodState, presence: Presence) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                stringResource(R.string.proactive_mood_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (mood.updatedAt > 0L) {
                Spacer(Modifier.height(8.dp))
                com.myfriend.app.ui.components.MoodLine(mood)
            }
            // 心情下面这一行是他正在做什么：同样是看见，不是开关。
            if (presence.isMeaningful()) {
                Spacer(Modifier.height(6.dp))
                PresenceText(
                    presence = presence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (mood.intensity >= 4 && mood.reason.isBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.proactive_mood_unsaid),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun AgentIntentsCard(state: ProactiveUiState) {
    val context = LocalContext.current
    val zone = java.time.ZoneId.systemDefault()
    // 日期部分按当前语言取系统格式，避免把「月/日」写死在代码里。
    val locale = LocalContext.current.resources.configuration.locales[0]
    val dayFmt = remember(locale) {
        java.time.format.DateTimeFormatter.ofPattern(
            android.text.format.DateFormat.getBestDateTimePattern(locale, "Mdjmm")
        )
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.8f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = LocalAccentColor.current,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.proactive_intents_title), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.proactive_intents_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.intents.take(4).forEach { intent ->
                Spacer(Modifier.height(10.dp))
                val start = java.time.Instant.ofEpochMilli(intent.earliestAt).atZone(zone)
                val end = java.time.Instant.ofEpochMilli(intent.latestAt).atZone(zone)
                val sameDay = start.toLocalDate() == java.time.LocalDate.now()
                val window = if (sameDay) {
                    // 审计 P2-11：时钟格式跟界面语言走（中文 HH:mm / 英文 h:mm a），不再写死 24 小时制。
                    "${formatClock(context, intent.earliestAt)}–${formatClock(context, intent.latestAt)}"
                } else {
                    stringResource(R.string.proactive_intent_window_around, start.format(dayFmt))
                }
                Text(
                    window,
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalAccentColor.current
                )
                Text(
                    intent.note.ifBlank { stringResource(R.string.proactive_intent_default_note) },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (intent.hint.isNotBlank()) {
                    Text(
                        intent.hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 他最近几次「想」：让他不是黑箱。 */
@Composable
private fun AgentThoughtsCard(thoughts: List<com.myfriend.app.data.local.AgentTickEntity>) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.proactive_thoughts_title), style = MaterialTheme.typography.titleSmall)
            }
            thoughts.take(5).forEachIndexed { index, tick ->
                if (index > 0) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 9.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                }
                // 同上：时钟格式走资源（审计 P2-11）。
                val at = formatClock(context, tick.at)
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        at,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            tick.thought.ifBlank { stringResource(R.string.proactive_thought_empty) },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            tick.outcome.ifBlank { stringResource(R.string.proactive_thought_outcome_empty) },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
