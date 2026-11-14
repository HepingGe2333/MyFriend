package com.myfriend.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myfriend.app.BuildConfig
import com.myfriend.app.R
import com.myfriend.app.data.prefs.BubbleStyle
import com.myfriend.app.data.prefs.ThemeMode
import com.myfriend.app.domain.ExportFile
import com.myfriend.app.ui.components.AppHeader
import com.myfriend.app.ui.components.BackNavigation
import com.myfriend.app.ui.components.ConfirmDialog
import com.myfriend.app.ui.components.SettingCard
import com.myfriend.app.ui.components.SettingRow
import com.myfriend.app.ui.components.SectionLabel
import com.myfriend.app.ui.components.StyledOutlinedField
import com.myfriend.app.ui.components.UserAvatar
import com.myfriend.app.ui.components.TextPill
import com.myfriend.app.ui.theme.LocalAccentColor
import com.myfriend.app.util.BackgroundKeeper
import com.myfriend.app.util.formatFullDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val accentChoices = listOf(
    0xFFC76F4E, 0xFFB6883E, 0xFF6F8B65, 0xFF4F7F92,
    0xFF6E79A8, 0xFF966A88, 0xFFB76076, 0xFF66645E
)

private val backgroundChoices = listOf(
    0xFFFAF8F4, 0xFFF6EFE6, 0xFFF1F3EE, 0xFFEEF2F5,
    0xFF191A18, 0xFF1D1B19, 0xFF171D1C, 0xFF1B1A22
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPersonas: () -> Unit,
    onOpenApiConfigs: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenProactive: () -> Unit,
    onOpenAbout: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backupState by viewModel.backupState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 「把他带走」这一节和下面清空前的快捷入口共用同一份文件选择器。
    val backupTransfer = rememberBackupTransfer(viewModel) { message ->
        scope.launch { snackbar.showSnackbar(message) }
    }
    var showClearChatsConfirm by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportFile?>(null) }
    var customHex by remember { mutableStateOf("") }
    var notificationsGranted by remember { mutableStateOf(BackgroundKeeper.hasNotificationPermission(context)) }
    var batteryUnrestricted by remember { mutableStateOf(BackgroundKeeper.isIgnoringBatteryOptimizations(context)) }

    // 从系统设置页回来时重新读一次：用户可能刚打开了通知或关掉了省电限制。
    LifecycleResumeEffect(Unit) {
        notificationsGranted = BackgroundKeeper.hasNotificationPermission(context)
        batteryUnrestricted = BackgroundKeeper.isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                com.myfriend.app.util.MediaStorage.persistImage(context, uri, "background")
                    .onSuccess { viewModel.setChatBackgroundImage(it) }
                    .onFailure { snackbar.showSnackbar(it.message ?: "背景图片保存失败") }
            }
        }
    }
    // 「我的头像」：和人格头像走同一条管线（采样 → 512px → JPEG → ≤200KB → files/avatars）。
    val userAvatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                com.myfriend.app.util.MediaStorage.persistAvatar(context, uri)
                    .onSuccess { viewModel.setUserAvatar(it) }
                    .onFailure { snackbar.showSnackbar(it.message ?: "头像保存失败") }
            }
        }
    }
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
                snackbar.showSnackbar(result.fold({ "聊天记录已导出" }, { "导出失败：${it.message}" }))
            }
        }
        pendingExport = null
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is SettingsEvent.ExportReady -> {
                    pendingExport = event.file
                    exportLauncher.launch(event.file.fileName)
                }
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
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppHeader(
                title = "设置",
                subtitle = "让 MyFriend 更像你的地方",
                navigation = { BackNavigation(onBack) }
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 40.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(LocalAccentColor.current.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.WavingHand, contentDescription = null, tint = LocalAccentColor.current, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text("MyFriend", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${state.personaCount} 个人格 · ${state.apiCount} 个 API 配置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                SectionLabel("外观")
                SettingCard {
                    // 「我的头像」是安静的：一行预览 + 点一下换图，没有单独的设置页，
                    // 也没有「确定要移除吗」这种弹窗——恢复默认只是一个普通按钮。
                    SettingRow(
                        title = "我的头像",
                        description = "聊天里显示在你这边；图片不跟着备份走",
                        icon = Icons.Outlined.PersonOutline,
                        trailing = {
                            UserAvatar(
                                avatarUri = state.settings.userAvatarUri,
                                size = 32.dp
                            )
                        },
                        onClick = {
                            userAvatarPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        showDivider = true
                    )
                    if (state.settings.userAvatarUri != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { viewModel.setUserAvatar(null) }) {
                                Text(
                                    "恢复默认",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    SettingRow(
                        title = "主题模式",
                        description = "浅色、深色或跟随系统",
                        icon = Icons.Outlined.SettingsBrightness,
                        trailing = { Text(themeLabel(state.settings.themeMode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                    ThemeModeRow(
                        selected = state.settings.themeMode,
                        onSelect = viewModel::setThemeMode
                    )
                    SettingRow(
                        title = "主题色",
                        description = "用于发送气泡、按钮与高亮",
                        icon = Icons.Outlined.Palette,
                        showDivider = false
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        accentChoices.forEach { colorValue ->
                            AccentSwatch(
                                color = Color(colorValue),
                                selected = state.settings.accentColor == colorValue,
                                onClick = { viewModel.setAccentColor(colorValue) }
                            )
                        }
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { customHex = "" },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Tune, contentDescription = "自定义颜色", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (customHex.isEmpty() && state.settings.accentColor !in accentChoices) {
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StyledOutlinedField(
                            value = customHex,
                            onValueChange = { customHex = it.take(9) },
                            label = "自定义主题色",
                            placeholder = "#C76F4E",
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                parseColor(customHex)?.let { viewModel.setAccentColor(it) }
                            },
                            enabled = parseColor(customHex) != null
                        ) { Text("应用") }
                    }
                    SettingRow(
                        title = "字体大小",
                        description = "只影响文字，不改变界面布局",
                        icon = Icons.Outlined.AutoAwesome,
                        trailing = {
                            Text("${(state.settings.fontScale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    )
                    Row(
                        Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("A", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = state.settings.fontScale,
                            onValueChange = { viewModel.setFontScale(it) },
                            valueRange = 0.85f..1.3f,
                            steps = 8,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = LocalAccentColor.current,
                                activeTrackColor = LocalAccentColor.current
                            )
                        )
                        Text("A", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SettingRow(
                        title = "聊天气泡样式",
                        description = "气泡圆角与留白密度",
                        icon = Icons.Outlined.ChatBubbleOutline,
                        showDivider = false
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BubbleStyle.values().forEach { style ->
                            BubbleStyleOption(
                                style = style,
                                selected = state.settings.bubbleStyle == style,
                                onClick = { viewModel.setBubbleStyle(style) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    SettingRow(
                        title = "界面动画",
                        description = "关闭后页面与气泡过渡更克制",
                        icon = Icons.Outlined.Tune,
                        trailing = {
                            Switch(
                                checked = state.settings.animationsEnabled,
                                onCheckedChange = viewModel::setAnimations,
                                colors = switchColors()
                            )
                        }
                    )
                    SettingRow(
                        title = "触感反馈",
                        description = "长按与操作时的轻微振动",
                        icon = Icons.Outlined.Vibration,
                        trailing = {
                            Switch(
                                checked = state.settings.hapticsEnabled,
                                onCheckedChange = viewModel::setHaptics,
                                colors = switchColors()
                            )
                        },
                        showDivider = false
                    )
                }

                SectionLabel("让他在后台活着")
                SettingCard {
                    SettingRow(
                        title = "常驻后台",
                        description = "关掉他就只在应用打开时存在；开着时他会在后台继续想事情，偶尔主动找你。",
                        icon = Icons.Outlined.NightsStay,
                        trailing = {
                            Switch(
                                checked = state.settings.agentLoopEnabled,
                                onCheckedChange = viewModel::setAgentLoopEnabled,
                                colors = switchColors()
                            )
                        },
                        showDivider = true
                    )
                    SettingRow(
                        title = "通知权限",
                        description = "没有通知权限，他找到你时你是看不到的。",
                        icon = Icons.Outlined.NotificationsNone,
                        trailing = {
                            Text(
                                if (notificationsGranted) "已开启" else "未开启",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (notificationsGranted) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = if (notificationsGranted) null else {
                            { BackgroundKeeper.openAppNotificationSettings(context) }
                        },
                        showDivider = true
                    )
                    SettingRow(
                        title = "后台运行限制",
                        description = "系统为了省电会冻结后台应用；允许后他才能一直待着。",
                        icon = Icons.Outlined.BatterySaver,
                        trailing = {
                            Text(
                                if (batteryUnrestricted) "已允许" else "受限",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (batteryUnrestricted) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = { BackgroundKeeper.requestIgnoreBatteryOptimizations(context) },
                        showDivider = true
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            "部分品牌手机还需要在系统设置里允许自启动 / 后台活动，否则他可能被系统冻结。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "去应用设置",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalAccentColor.current,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { BackgroundKeeper.openAppDetails(context) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                UsageSection(totals = state.usage)

                SectionLabel("聊天背景")
                SettingCard {
                    SettingRow(
                        title = "纯色背景",
                        description = if (state.settings.chatBackgroundImageUri != null) "当前使用自定义图片，颜色仅在移除图片后生效" else "选择聊天画布的颜色",
                        icon = Icons.Outlined.Image,
                        showDivider = false
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            onClick = { viewModel.setChatBackgroundColor(null) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("默认", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp)
                            }
                        }
                        backgroundChoices.forEach { colorValue ->
                            AccentSwatch(
                                color = Color(colorValue),
                                selected = state.settings.chatBackgroundColor == colorValue,
                                onClick = { viewModel.setChatBackgroundColor(colorValue) }
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { imagePicker.launch(arrayOf("image/*")) },
                            contentPadding = PaddingValues(horizontal = 0.dp)
                        ) {
                            Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("选择背景图片")
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.settings.chatBackgroundImageUri != null) {
                            TextButton(onClick = { viewModel.setChatBackgroundImage(null) }) {
                                Text("移除图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                SectionLabel("API 配置")
                SettingCard {
                    SettingRow(
                        title = "API 配置",
                        description = "Base URL、密钥、模型与推理参数",
                        icon = Icons.Outlined.Security,
                        trailing = { Text("${state.apiCount}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = onOpenApiConfigs,
                        showDivider = false
                    )
                }

                BackupSection(
                    state = backupState,
                    transfer = backupTransfer,
                    onConfirmImport = viewModel::importBackup,
                    onDismissPreview = viewModel::dismissBackupPreview
                )

                SectionLabel("数据与安全")
                SettingCard {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.clear_data_intro_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            stringResource(R.string.clear_data_intro_body),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = backupTransfer.takeHimAway,
                            contentPadding = PaddingValues(horizontal = 0.dp)
                        ) { Text(stringResource(R.string.clear_data_take_away_first)) }
                    }
                    SettingRow(
                        title = "导出全部聊天记录",
                        description = "JSON 文件，不包含 API Key",
                        icon = Icons.Outlined.FileDownload,
                        onClick = viewModel::exportAllChats,
                        showDivider = true
                    )
                    SettingRow(
                        title = "清空全部聊天与记忆",
                        description = "保留人格与 API 配置",
                        icon = Icons.Outlined.CleaningServices,
                        onClick = { showClearChatsConfirm = true },
                        showDivider = true
                    )
                    SettingRow(
                        title = "删除全部本地数据",
                        description = "人格、会话、记忆、预设、主动唤醒规则、API 配置与设置全部移除",
                        icon = Icons.Outlined.DeleteForever,
                        danger = true,
                        onClick = { showWipeConfirm = true },
                        showDivider = false
                    )
                }
                Text(
                    "API Key 由 Android Keystore 加密保存，不写入日志或崩溃报告。默认只允许 HTTPS；对话记录仅保存在本机。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp)
                )
                TextButton(onClick = onOpenAbout, modifier = Modifier.padding(horizontal = 2.dp)) {
                    Text("关于 MyFriend")
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    if (showClearChatsConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.clear_chats_confirm_title),
            message = stringResource(R.string.clear_chats_confirm_message),
            confirmText = stringResource(R.string.clear_chats_confirm_action),
            destructive = true,
            onConfirm = {
                showClearChatsConfirm = false
                viewModel.clearChats()
            },
            onDismiss = { showClearChatsConfirm = false }
        )
    }
    if (showWipeConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.wipe_confirm_title),
            message = stringResource(R.string.wipe_confirm_message),
            confirmText = stringResource(R.string.wipe_confirm_action),
            destructive = true,
            onConfirm = {
                showWipeConfirm = false
                viewModel.deleteAllData()
            },
            onDismiss = { showWipeConfirm = false }
        )
    }
}

@Composable
private fun ThemeModeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThemeOption(
            label = "跟随系统",
            icon = Icons.Outlined.SettingsBrightness,
            selected = selected == ThemeMode.SYSTEM,
            onClick = { onSelect(ThemeMode.SYSTEM) },
            modifier = Modifier.weight(1f)
        )
        ThemeOption(
            label = "浅色",
            icon = Icons.Outlined.LightMode,
            selected = selected == ThemeMode.LIGHT,
            onClick = { onSelect(ThemeMode.LIGHT) },
            modifier = Modifier.weight(1f)
        )
        ThemeOption(
            label = "深色",
            icon = Icons.Outlined.DarkMode,
            selected = selected == ThemeMode.DARK,
            onClick = { onSelect(ThemeMode.DARK) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ThemeOption(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = if (selected) LocalAccentColor.current.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) LocalAccentColor.current.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(
            Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AccentSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = "已选择", tint = Color.White, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun BubbleStyleOption(
    style: BubbleStyle,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = if (selected) LocalAccentColor.current.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) LocalAccentColor.current.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(Modifier.padding(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(
                    Modifier
                        .width(28.dp)
                        .height(11.dp)
                        .clip(
                            when (style) {
                                BubbleStyle.SOFT -> RoundedCornerShape(6.dp, 6.dp, 2.dp, 6.dp)
                                BubbleStyle.COMPACT -> RoundedCornerShape(3.dp)
                                BubbleStyle.OUTLINED -> RoundedCornerShape(5.dp)
                            }
                        )
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            when (style) {
                                BubbleStyle.SOFT -> RoundedCornerShape(6.dp, 6.dp, 2.dp, 6.dp)
                                BubbleStyle.COMPACT -> RoundedCornerShape(3.dp)
                                BubbleStyle.OUTLINED -> RoundedCornerShape(5.dp)
                            }
                        )
                )
                Box(
                    Modifier
                        .width(36.dp)
                        .height(12.dp)
                        .clip(
                            when (style) {
                                BubbleStyle.SOFT -> RoundedCornerShape(6.dp, 6.dp, 6.dp, 2.dp)
                                BubbleStyle.COMPACT -> RoundedCornerShape(3.dp)
                                BubbleStyle.OUTLINED -> RoundedCornerShape(5.dp)
                            }
                        )
                        .background(if (style == BubbleStyle.OUTLINED) LocalAccentColor.current.copy(alpha = 0.2f) else LocalAccentColor.current)
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                when (style) {
                    BubbleStyle.SOFT -> "柔和"
                    BubbleStyle.COMPACT -> "紧凑"
                    BubbleStyle.OUTLINED -> "描边"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StepperCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (enabled) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) else Color.Transparent)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 0.8f else 0.35f),
                CircleShape
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = LocalAccentColor.current,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
)

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

private fun parseColor(value: String): Long? {
    val cleaned = value.trim().removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    val parsed = cleaned.toLongOrNull(16) ?: return null
    return if (cleaned.length == 6) 0xFF000000L or parsed else parsed
}
