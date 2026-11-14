@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.myfriend.app.ui.screens.persona

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Expand
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myfriend.app.data.local.ApiConfigEntity
import com.myfriend.app.domain.PersonaCard
import com.myfriend.app.domain.PersonaTrait
import com.myfriend.app.domain.ProactiveLevel
import com.myfriend.app.ui.components.AppHeader
import com.myfriend.app.ui.components.BackNavigation
import com.myfriend.app.ui.components.BottomSheetHandle
import com.myfriend.app.ui.components.BottomSheetTitle
import com.myfriend.app.ui.components.ConfirmDialog
import com.myfriend.app.ui.components.PersonaAvatar
import com.myfriend.app.ui.components.PrimaryButton
import com.myfriend.app.ui.components.SectionLabel
import com.myfriend.app.ui.components.StyledOutlinedField
import com.myfriend.app.ui.components.TextPill
import com.myfriend.app.ui.theme.LocalAccentColor
import com.myfriend.app.ui.theme.avatarPalette
import com.myfriend.app.util.MediaStorage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PersonaEditorScreen(
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onOpenApiConfigs: () -> Unit,
    viewModel: PersonaEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showPromptEditor by remember { mutableStateOf(false) }
    var showApiPicker by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // 系统照片选择器：只给「这一张」的读取权限，比 OpenDocument 更克制，也少一个权限面。
    // 选完立刻采样压缩进 files/avatars，库里只存 App 私有路径（avatarUri 列早就有了，不动 schema）。
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                MediaStorage.persistAvatar(context, uri)
                    .onSuccess { viewModel.setAvatarUri(it) }
                    .onFailure { snackbar.showSnackbar(it.message ?: "头像保存失败") }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is PersonaEditorEvent.Saved -> onSaved(event.personaId)
                is PersonaEditorEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    fun requestBack() {
        if (state.isDirty) showDiscardConfirm = true else onBack()
    }
    BackHandler(enabled = true) { requestBack() }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    PrimaryButton(
                        text = if (state.isNew) "创建人格" else "保存更改",
                        onClick = viewModel::save,
                        loading = state.saving,
                        enabled = !state.loading
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AppHeader(
                title = if (state.isNew) "新人格" else "编辑人格",
                subtitle = if (state.isDefault) "默认人格" else null,
                navigation = { BackNavigation(::requestBack) }
            )
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 20.dp)
                ) {
                    Spacer(Modifier.height(4.dp))
                    AvatarSection(
                        state = state,
                        onPick = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRemove = { viewModel.setAvatarUri(null) },
                        onSeed = viewModel::setAvatarSeed
                    )
                    Spacer(Modifier.height(22.dp))
                    StyledOutlinedField(
                        value = state.form.name,
                        onValueChange = { value -> viewModel.update { it.copy(name = value.take(80)) } },
                        label = "名字",
                        placeholder = "它会如何称呼自己"
                    )
                    Spacer(Modifier.height(14.dp))
                    StyledOutlinedField(
                        value = state.form.description,
                        onValueChange = { value -> viewModel.update { it.copy(description = value.take(600)) } },
                        label = "简介",
                        placeholder = "一句话描述这个人的气质",
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4
                    )
                    Spacer(Modifier.height(20.dp))
                    CategorySection(
                        category = state.form.category,
                        onCategory = { category -> viewModel.update { it.copy(category = category.take(30)) } }
                    )
                    ContactProfileSection(
                        form = state.form,
                        onForm = { transform -> viewModel.update(transform) }
                    )
                    Spacer(Modifier.height(20.dp))
                    SectionLabel("提示词全文（高级）", trailing = {
                        Text(
                            "${state.form.prompt.length} 字符",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    })
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            if (state.form.prompt.isBlank()) {
                                Text(
                                    "写下它的性格、说话方式、边界与相处原则。长文本会完整保存，不会被自动缩写或改写。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    state.form.prompt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 7,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        showPromptEditor = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 0.dp)
                                ) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("专注编辑")
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "完整原文保存",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    Text(
                        "修改提示词会产生新版本，并保留上一版。下一轮对话会自动使用新版本。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                    )

                    PersonaCardSection(
                        card = state.card,
                        onCard = viewModel::updateCard,
                        onAddTrait = viewModel::addTrait,
                        onTrait = viewModel::updateTrait,
                        onRemoveTrait = viewModel::removeTrait
                    )

                    SectionLabel("API 与模型")
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { showApiPicker = true }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("绑定的 API", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        state.boundConfig?.let { "${it.name} · ${it.model}" } ?: "未绑定（每个会话仍可单独选择）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(Icons.Outlined.ArrowOutward, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            )
                            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                StyledOutlinedField(
                                    value = state.form.modelOverride,
                                    onValueChange = { value -> viewModel.update { it.copy(modelOverride = value.take(160)) } },
                                    label = "模型覆盖（可选）",
                                    placeholder = state.boundConfig?.model ?: "留空使用 API 配置中的模型",
                                    supportingText = "人格可以独立使用某个模型 ID，不改变 API 配置的默认模型。"
                                )
                            }
                        }
                    }
                    if (state.configs.isEmpty()) {
                        TextButton(onClick = onOpenApiConfigs, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                            Text("还没有 API 配置，去添加一个")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    if (showPromptEditor) {
        PromptFullScreenEditor(
            initialValue = state.form.prompt,
            savedValue = state.initialPrompt,
            onDismiss = { showPromptEditor = false },
            onApply = {
                viewModel.setPrompt(it)
                showPromptEditor = false
            },
            modifier = Modifier.zIndex(5f)
        )
    }
    }

    if (showApiPicker) {
        ApiPickerSheet(
            configs = state.configs,
            selectedId = state.form.defaultApiConfigId,
            onSelect = {
                viewModel.update { form -> form.copy(defaultApiConfigId = it) }
                showApiPicker = false
            },
            onDismiss = { showApiPicker = false }
        )
    }

    if (showDiscardConfirm) {
        ConfirmDialog(
            title = "放弃未保存的修改？",
            message = "当前的人格信息与提示词改动尚未保存。",
            confirmText = "放弃",
            destructive = true,
            onConfirm = {
                showDiscardConfirm = false
                onBack()
            },
            onDismiss = { showDiscardConfirm = false }
        )
    }
}

/**
 * 联系人细分字段（规格 §三 / §七）。
 *
 * 分节：性格（多选 + 自定义）/ 喜欢的事 / 行为方式 / 成长经历 / 个人设定 /
 * 主动程度 / 记性 / 抽记忆频率 / 会不会主动睡觉 / 睡觉时间段 / 睡眠唤醒阈值。
 *
 * 列表字段用 TextPill 提供常见候选，同时保留一行自定义输入——候选只是降低输入成本，
 * 不是限制：用户写「半夜给人改简历」这种具体习惯也应该能存进去。
 */
@Composable
private fun ContactProfileSection(
    form: PersonaForm,
    onForm: ((PersonaForm) -> PersonaForm) -> Unit
) {
    SectionLabel("性格与设定")
    Text(
        "这些会拼进系统提示词的「你是谁」一节：性格 / 喜欢 / 习惯 / 过去 / 设定。没填的不会占位。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            ProfileListField(
                label = "性格（最多 6 个，用、分隔）",
                placeholder = "慢热、嘴硬心软",
                value = form.traitsText,
                onChange = { value -> onForm { it.copy(traitsText = value.take(120)) } },
                presets = TRAIT_PRESETS,
                selected = form.traits,
                max = 6,
                onTogglePreset = { trait ->
                    val current = form.traits.toMutableList()
                    if (current.contains(trait)) current.remove(trait) else if (current.size < 6) current.add(trait)
                    onForm { it.copy(traitsText = PersonaForm.joinList(current)) }
                }
            )
            Spacer(Modifier.height(12.dp))
            ProfileListField(
                label = "喜欢的事（最多 8 个，用、分隔）",
                placeholder = "夜里散步、喝冰美式",
                value = form.likesText,
                onChange = { value -> onForm { it.copy(likesText = value.take(160)) } },
                presets = LIKES_PRESETS,
                selected = form.likes,
                max = 8,
                onTogglePreset = { item ->
                    val current = form.likes.toMutableList()
                    if (current.contains(item)) current.remove(item) else if (current.size < 8) current.add(item)
                    onForm { it.copy(likesText = PersonaForm.joinList(current)) }
                }
            )
            Spacer(Modifier.height(12.dp))
            StyledOutlinedField(
                value = form.behaviorsText,
                onValueChange = { value -> onForm { it.copy(behaviorsText = value.take(160)) } },
                label = "行为方式 / 习惯（最多 6 个，用、分隔）",
                placeholder = "先听完再给建议、不追问",
                singleLine = false,
                minLines = 2,
                maxLines = 3
            )
            Spacer(Modifier.height(12.dp))
            StyledOutlinedField(
                value = form.backstory,
                onValueChange = { value -> onForm { it.copy(backstory = value.take(600)) } },
                label = "成长经历 / 过去（最多 600 字）",
                placeholder = "小时候在南方长大，后来一个人搬到北方……",
                singleLine = false,
                minLines = 3,
                maxLines = 8
            )
            Spacer(Modifier.height(12.dp))
            StyledOutlinedField(
                value = form.quirks,
                onValueChange = { value -> onForm { it.copy(quirks = value.take(300)) } },
                label = "个人设定：称呼、口头禅、忌讳（最多 300 字）",
                placeholder = "叫你「阿岚」；不说「加油」；不喜欢被安排",
                singleLine = false,
                minLines = 2,
                maxLines = 6
            )

            Spacer(Modifier.height(18.dp))
            ProfileSlider(
                title = "主动程度",
                valueText = "${form.proactiveLevel} · ${ProactiveLevel.description(form.proactiveLevel)}",
                value = form.proactiveLevel.toFloat(),
                valueRange = 1f..5f,
                steps = 3,
                onChange = { raw -> onForm { it.copy(proactiveLevel = raw.roundToInt().coerceIn(1, 5)) } }
            )
            ProfileSlider(
                title = "记性",
                valueText = memoryRetentionText(form.memoryRetention),
                value = form.memoryRetention.toFloat(),
                valueRange = 1f..5f,
                steps = 3,
                onChange = { raw -> onForm { it.copy(memoryRetention = raw.roundToInt().coerceIn(1, 5)) } }
            )
            ProfileSlider(
                title = "抽记忆频率",
                valueText = "每 ${form.extractionEvery} 条消息抽一次",
                value = form.extractionEvery.toFloat(),
                valueRange = 3f..12f,
                steps = 8,
                onChange = { raw -> onForm { it.copy(extractionEvery = raw.roundToInt().coerceIn(3, 12)) } }
            )

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("他会不会主动睡觉", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (form.sleepEnabled) "到点会睡，睡着时消息留着；叫得够多会醒。" else "他不睡觉，任何时间都会回。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = form.sleepEnabled,
                    onCheckedChange = { enabled -> onForm { it.copy(sleepEnabled = enabled) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = LocalAccentColor.current
                    )
                )
            }
            if (form.sleepEnabled) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StyledOutlinedField(
                        value = form.sleepAt,
                        onValueChange = { value -> onForm { it.copy(sleepAt = value.take(5)) } },
                        label = "几点睡",
                        placeholder = "23:30",
                        modifier = Modifier.weight(1f)
                    )
                    StyledOutlinedField(
                        value = form.wakeAt,
                        onValueChange = { value -> onForm { it.copy(wakeAt = value.take(5)) } },
                        label = "几点醒",
                        placeholder = "08:00",
                        modifier = Modifier.weight(1f)
                    )
                }
                ProfileSlider(
                    title = "睡眠唤醒阈值",
                    valueText = "连着 ${form.wakeThreshold} 条消息会把他叫醒",
                    value = form.wakeThreshold.toFloat(),
                    valueRange = 1f..20f,
                    steps = 18,
                    onChange = { raw -> onForm { it.copy(wakeThreshold = raw.roundToInt().coerceIn(1, 20)) } }
                )
            }
        }
    }
}

@Composable
private fun ProfileListField(
    label: String,
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
    presets: List<String>,
    selected: List<String>,
    max: Int,
    onTogglePreset: (String) -> Unit
) {
    StyledOutlinedField(
        value = value,
        onValueChange = onChange,
        label = label,
        placeholder = placeholder
    )
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        presets.forEach { preset ->
            TextPill(
                text = preset,
                selected = selected.contains(preset),
                onClick = {
                    if (selected.contains(preset) || selected.size < max) onTogglePreset(preset)
                }
            )
        }
    }
}

@Composable
private fun ProfileSlider(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = LocalAccentColor.current,
                activeTrackColor = LocalAccentColor.current
            )
        )
    }
}

private fun memoryRetentionText(level: Int): String = when (level.coerceIn(1, 5)) {
    1 -> "1 · 忘得快，只记得最近的事"
    2 -> "2 · 记得住大概"
    3 -> "3 · 普通的记性"
    4 -> "4 · 记得很细"
    else -> "5 · 什么都记得"
}

private val TRAIT_PRESETS = listOf("慢热", "嘴硬心软", "话少", "敏感", "爱开玩笑", "固执", "温柔", "直接", "黏人", "独立")
private val LIKES_PRESETS = listOf("夜里散步", "喝冰美式", "听老歌", "看电影", "做饭", "下雨天", "猫", "旅行")

@Composable
private fun AvatarSection(
    state: PersonaEditorUiState,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onSeed: (Int) -> Unit
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            PersonaAvatar(
                state.form.avatarUri,
                state.form.avatarSeed,
                state.form.name.ifBlank { "新" },
                size = 92.dp,
                ringColor = LocalAccentColor.current.copy(alpha = 0.45f)
            )
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(onClick = onPick),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = "更换头像", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onPick, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (state.form.avatarUri == null) "选一张" else "换一张")
            }
            if (state.form.avatarUri != null) {
                TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    // 「恢复默认」而不是「移除」：默认不是空白，是这个人的色块 + 首字。
                    Text("恢复默认", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.form.avatarUri == null) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (0..12).forEach { seed ->
                    val palette = avatarPalette(seed)
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(palette.first, palette.second)))
                            .then(
                                if (state.form.avatarSeed == seed) Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                                else Modifier
                            )
                            .clickable { onSeed(seed) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.form.avatarSeed == seed) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySection(category: String, onCategory: (String) -> Unit) {
    val suggestions = listOf("日常聊天", "角色扮演", "学习", "编程", "创意写作", "工作")
    SectionLabel("分类")
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        suggestions.forEach { item ->
            TextPill(item, selected = category == item, onClick = { onCategory(item) })
        }
    }
    Spacer(Modifier.height(10.dp))
    StyledOutlinedField(
        value = category,
        onValueChange = onCategory,
        label = "自定义分类",
        singleLine = true
    )
}

// ---------------------------------------------------------------------------
// 结构化人设卡编辑器
// ---------------------------------------------------------------------------

/** 逗号（中英文）、顿号、换行都可作为列表分隔符。 */
private fun parseCardList(raw: String): List<String> =
    raw.split(',', '，', '、', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun strengthText(strength: Float): String {
    val tenths = (strength.coerceIn(0f, 1f) * 10f).roundToInt()
    return if (tenths >= 10) "1.0" else "0.$tenths"
}

@Composable
private fun CardGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 2.dp)
    )
}

/**
 * 逗号分隔的字符串列表输入。
 *
 * 显示用本地原文，写入状态时才拆成 List，
 * 这样输入到「a，b，」这种半截状态时不会把刚敲的逗号吃掉。
 */
@Composable
private fun CommaListField(
    values: List<String>,
    onValues: (List<String>) -> Unit,
    label: String,
    placeholder: String
) {
    var text by rememberSaveable { mutableStateOf(values.joinToString("，")) }
    StyledOutlinedField(
        value = text,
        onValueChange = { raw ->
            text = raw
            onValues(parseCardList(raw))
        },
        label = label,
        placeholder = placeholder
    )
}

@Composable
private fun PersonaCardSection(
    card: PersonaCard,
    onCard: ((PersonaCard) -> PersonaCard) -> Unit,
    onAddTrait: () -> Unit,
    onTrait: (Int, (PersonaTrait) -> PersonaTrait) -> Unit,
    onRemoveTrait: (Int) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(!card.isEmpty()) }
    SectionLabel(
        "人格卡",
        trailing = {
            Text(
                if (card.isEmpty()) "可选" else "已填写",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
    Text(
        "人格卡会被拆成几段喂给他：稳定的说话策略放进系统提示，其余按字数预算进入当前对话。留空就用提示词原文。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("结构化设定", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "身份 · 声音 · 性格 · 在意的事 · 边界 · 生活 · 你们之间",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起人格卡" else "展开人格卡",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp)
                )
            }
            if (expanded) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                )
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp)) {
                    // ---------------------------------------------------- 身份
                    CardGroupLabel("身份")
                    StyledOutlinedField(
                        value = card.identity.name,
                        onValueChange = { value ->
                            onCard { it.copy(identity = it.identity.copy(name = value.take(40))) }
                        },
                        label = "名字",
                        placeholder = "卡片里它自己的名字"
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.identity.age,
                        onValueChange = { value ->
                            onCard { it.copy(identity = it.identity.copy(age = value.take(40))) }
                        },
                        label = "年龄感",
                        placeholder = "例如：二十多岁"
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.identity.role,
                        onValueChange = { value ->
                            onCard { it.copy(identity = it.identity.copy(role = value.take(80))) }
                        },
                        label = "身份 / 职业",
                        placeholder = "例如：开旧书店的人"
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.identity.relation,
                        onValueChange = { value ->
                            onCard { it.copy(identity = it.identity.copy(relation = value.take(40))) }
                        },
                        label = "和你的关系",
                        placeholder = "例如：认识很多年的朋友"
                    )

                    // ---------------------------------------------------- 声音
                    CardGroupLabel("声音")
                    StyledOutlinedField(
                        value = card.voice.pace,
                        onValueChange = { value ->
                            onCard { it.copy(voice = it.voice.copy(pace = value.take(240))) }
                        },
                        label = "语速",
                        placeholder = "例如：慢，偶尔停下来想想"
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.voice.sentence,
                        onValueChange = { value ->
                            onCard { it.copy(voice = it.voice.copy(sentence = value.take(240))) }
                        },
                        label = "句子长度",
                        placeholder = "例如：短句，一次说一两句"
                    )
                    Spacer(Modifier.height(10.dp))
                    CommaListField(
                        values = card.voice.catchphrases,
                        onValues = { list ->
                            onCard { it.copy(voice = it.voice.copy(catchphrases = list)) }
                        },
                        label = "口头禅（逗号分隔）",
                        placeholder = "例如：嗯，那就这样"
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.voice.address,
                        onValueChange = { value ->
                            onCard { it.copy(voice = it.voice.copy(address = value.take(40))) }
                        },
                        label = "怎么称呼你",
                        placeholder = "例如：喊你名字的后两个字"
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.voice.quirks,
                        onValueChange = { value ->
                            onCard { it.copy(voice = it.voice.copy(quirks = value.take(240))) }
                        },
                        label = "语气小习惯",
                        placeholder = "例如：少用感叹号，爱用省略号"
                    )

                    // ---------------------------------------------------- 性格
                    CardGroupLabel("性格特征")
                    if (card.traits.isEmpty()) {
                        Text(
                            "还没有性格标签。添加后可以单独调每个特质的强度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                        )
                    }
                    card.traits.forEachIndexed { index, trait ->
                        if (index > 0) Spacer(Modifier.height(6.dp))
                        TraitRow(
                            trait = trait,
                            onChange = { updated -> onTrait(index) { updated } },
                            onRemove = { onRemoveTrait(index) }
                        )
                    }
                    TextButton(
                        onClick = onAddTrait,
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        Text("添加一条")
                    }

                    // ---------------------------------------------------- 价值观 / 边界
                    CardGroupLabel("价值观")
                    CommaListField(
                        values = card.values,
                        onValues = { list -> onCard { it.copy(values = list) } },
                        label = "价值观（逗号分隔）",
                        placeholder = "例如：真诚，不敷衍"
                    )
                    CardGroupLabel("边界")
                    CommaListField(
                        values = card.boundaries,
                        onValues = { list -> onCard { it.copy(boundaries = list) } },
                        label = "边界（逗号分隔）",
                        placeholder = "例如：不评判你的选择，不假装记得没说过的事"
                    )

                    // ---------------------------------------------------- 生活
                    CardGroupLabel("生活")
                    StyledOutlinedField(
                        value = card.life.schedule,
                        onValueChange = { value ->
                            onCard { it.copy(life = it.life.copy(schedule = value.take(240))) }
                        },
                        label = "作息",
                        placeholder = "例如：凌晨两点睡，中午起"
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.life.doing,
                        onValueChange = { value ->
                            onCard { it.copy(life = it.life.copy(doing = value.take(240))) }
                        },
                        label = "最近在忙",
                        placeholder = "例如：把店里的旧书重新分类"
                    )
                    Spacer(Modifier.height(10.dp))
                    StyledOutlinedField(
                        value = card.life.caresAbout,
                        onValueChange = { value ->
                            onCard { it.copy(life = it.life.copy(caresAbout = value.take(240))) }
                        },
                        label = "在意的事",
                        placeholder = "例如：你最近睡得好不好"
                    )

                    // ---------------------------------------------------- 你们之间
                    CardGroupLabel("你们之间")
                    StyledOutlinedField(
                        value = card.bond,
                        onValueChange = { value -> onCard { it.copy(bond = value.take(2000)) } },
                        label = "对你的认知与变化",
                        placeholder = "它怎么理解你、这段关系里发生过什么、现在有什么不一样",
                        singleLine = false,
                        minLines = 3,
                        maxLines = 6,
                        supportingText = "他自己想通了什么会直接写在这里，改动都有版本可回滚。"
                    )
                }
            }
        }
    }
}

@Composable
private fun TraitRow(
    trait: PersonaTrait,
    onChange: (PersonaTrait) -> Unit,
    onRemove: () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StyledOutlinedField(
                value = trait.name,
                onValueChange = { value -> onChange(trait.copy(name = value.take(40))) },
                label = "特质",
                placeholder = "例如：嘴硬心软",
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "删除这条性格",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            Modifier.padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "强度",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = trait.strength,
                onValueChange = { value -> onChange(trait.copy(strength = value)) },
                valueRange = 0f..1f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Text(
                strengthText(trait.strength),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ApiPickerSheet(
    configs: List<ApiConfigEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetHandle() }
    ) {
        Column(Modifier.padding(bottom = 28.dp)) {
            BottomSheetTitle("绑定 API 配置", "绑定后，他的那一段会话会默认使用它")
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(null) }
                    .padding(horizontal = 24.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("不绑定", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                if (selectedId == null) Icon(Icons.Outlined.Check, contentDescription = null, tint = LocalAccentColor.current)
            }
            configs.forEach { config ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(config.id) }
                        .padding(horizontal = 24.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(config.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            config.model,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selectedId == config.id) Icon(Icons.Outlined.Check, contentDescription = null, tint = LocalAccentColor.current)
                }
            }
        }
    }
}

@Composable
private fun PromptFullScreenEditor(
    initialValue: String,
    savedValue: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialValue, TextRange(0)))
    }
    val clipboard = LocalClipboardManager.current
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(1600)
            notice = null
        }
    }
    BackHandler(enabled = true) { onDismiss() }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                        .padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "取消")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("人格提示词", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${value.text.length} 字符 · 原文完整保存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onApply(value.text) }) { Text("完成") }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(value.text))
                        notice = "已复制全文"
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("复制全文")
                    }
                    TextButton(onClick = {
                        val pasted = clipboard.getText()?.text.orEmpty()
                        if (pasted.isNotBlank()) {
                            val combined = if (value.text.isBlank()) pasted else value.text + "\n" + pasted
                            value = TextFieldValue(combined, TextRange(combined.length))
                            notice = "已粘贴到末尾"
                        }
                    }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("粘贴")
                    }
                    TextButton(onClick = {
                        if (savedValue.isNotBlank()) {
                            value = TextFieldValue(savedValue, TextRange(savedValue.length))
                            notice = "已恢复上次保存的版本"
                        }
                    }) {
                        Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("恢复上次版本")
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    if (value.text.isEmpty()) {
                        Text(
                            "在这里写下完整人格设定…\n\n可以包含身份、性格、说话方式、价值观、边界与相处原则。不会自动截断。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = {
                            value = it
                            notice = null
                        },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 25.sp
                        ),
                        cursorBrush = SolidColor(LocalAccentColor.current)
                    )
                }
                if (notice != null) {
                    Text(
                        notice!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAccentColor.current,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text("取消", style = MaterialTheme.typography.labelLarge) }
                    }
                    Surface(
                        onClick = { onApply(value.text) },
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("保存到草稿", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
    }
}
