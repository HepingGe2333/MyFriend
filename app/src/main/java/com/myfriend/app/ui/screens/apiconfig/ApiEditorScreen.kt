@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.myfriend.app.ui.screens.apiconfig

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myfriend.app.data.local.ProviderType
import com.myfriend.app.ui.components.AppHeader
import com.myfriend.app.ui.components.BackNavigation
import com.myfriend.app.ui.components.BottomSheetHandle
import com.myfriend.app.ui.components.BottomSheetTitle
import com.myfriend.app.ui.components.PrimaryButton
import com.myfriend.app.ui.components.SectionLabel
import com.myfriend.app.ui.components.StyledOutlinedField
import com.myfriend.app.ui.components.TextPill
import com.myfriend.app.ui.theme.LocalAccentColor

@Composable
fun ApiEditorScreen(
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: ApiEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var keyVisible by remember { mutableStateOf(false) }
    var webToolsKeyVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is ApiEditorEvent.Saved -> onSaved(event.configId)
                is ApiEditorEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }
    BackHandler(enabled = true) { onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = viewModel::testConnection,
                        enabled = !state.testing && !state.saving
                    ) {
                        if (state.testing) {
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp)
                            Spacer(Modifier.width(7.dp))
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(if (state.testing) "测试中" else "测试连接")
                    }
                    PrimaryButton(
                        text = if (state.isNew) "保存配置" else "保存更改",
                        onClick = viewModel::save,
                        loading = state.saving,
                        modifier = Modifier.weight(1f),
                        enabled = !state.loading
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppHeader(
                title = if (state.isNew) "添加 API" else "编辑 API",
                subtitle = "密钥仅保存在本机 Keystore",
                navigation = { BackNavigation(onBack) }
            )
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 20.dp)
                ) {
                    SectionLabel("服务商")
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProviderOption(
                            label = "OpenAI 兼容",
                            selected = state.provider == ProviderType.OPENAI,
                            available = true,
                            onClick = { viewModel.setProvider(ProviderType.OPENAI) }
                        )
                        ProviderOption(
                            label = "Gemini",
                            selected = state.provider == ProviderType.GEMINI,
                            available = false,
                            onClick = {}
                        )
                        ProviderOption(
                            label = "Claude",
                            selected = state.provider == ProviderType.CLAUDE,
                            available = false,
                            onClick = {}
                        )
                    }
                    Text(
                        "Gemini 与 Claude 的原生适配接口已预留，当前版本请使用 OpenAI 兼容端点。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                    )

                    SectionLabel("连接")
                    StyledOutlinedField(
                        value = state.name,
                        onValueChange = { value -> viewModel.update { it.copy(name = value.take(60)) } },
                        label = "配置名称",
                        placeholder = "例如：DeepSeek 主力"
                    )
                    Spacer(Modifier.height(14.dp))
                    StyledOutlinedField(
                        value = state.baseUrl,
                        onValueChange = { value -> viewModel.update { it.copy(baseUrl = value.trim()) } },
                        label = "Base URL",
                        placeholder = "https://api.openai.com/v1",
                        supportingText = "兼容 /chat/completions 与 /models 接口；通常包含 /v1。"
                    )
                    Spacer(Modifier.height(14.dp))
                    StyledOutlinedField(
                        value = state.apiKey,
                        onValueChange = { value -> viewModel.update { it.copy(apiKey = value.trim()) } },
                        label = "API Key",
                        placeholder = if (state.hasExistingKey) "已加密保存 · 留空不修改" else "sk-…",
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = {
                            Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (keyVisible) "隐藏密钥" else "显示密钥",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                    StyledOutlinedField(
                        value = state.model,
                        onValueChange = { value -> viewModel.update { it.copy(model = value.take(160)) } },
                        label = "模型 ID",
                        placeholder = "gpt-4o-mini / deepseek-chat / …",
                        trailingIcon = {
                            IconButton(onClick = viewModel::fetchModels, enabled = !state.fetchingModels) {
                                if (state.fetchingModels) {
                                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 1.8.dp)
                                } else {
                                    Icon(Icons.Outlined.Refresh, contentDescription = "获取模型列表", modifier = Modifier.size(18.dp), tint = LocalAccentColor.current)
                                }
                            }
                        },
                        supportingText = "可以手动填写任意模型 ID，也可以从服务端获取列表。"
                    )

                    SectionLabel("推理参数")
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "留空表示使用模型默认值，请求时不会发送该参数。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (state.capabilities.supportsTemperature) {
                                    StyledOutlinedField(
                                        value = state.temperature,
                                        onValueChange = { value -> viewModel.update { it.copy(temperature = value.numeric()) } },
                                        label = "Temperature",
                                        placeholder = "0 – 2",
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (state.capabilities.supportsTopP) {
                                    StyledOutlinedField(
                                        value = state.topP,
                                        onValueChange = { value -> viewModel.update { it.copy(topP = value.numeric()) } },
                                        label = "Top P",
                                        placeholder = "0 – 1",
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (state.capabilities.supportsMaxTokens) {
                                    StyledOutlinedField(
                                        value = state.maxOutputTokens,
                                        onValueChange = { value -> viewModel.update { it.copy(maxOutputTokens = value.filter { ch -> ch.isDigit() }) } },
                                        label = "Max Output Tokens",
                                        placeholder = "模型默认",
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (state.capabilities.supportsFrequencyPenalty) {
                                    StyledOutlinedField(
                                        value = state.frequencyPenalty,
                                        onValueChange = { value -> viewModel.update { it.copy(frequencyPenalty = value.numeric()) } },
                                        label = "Frequency Penalty",
                                        placeholder = "-2 – 2",
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            if (state.capabilities.supportsPresencePenalty) {
                                Spacer(Modifier.height(10.dp))
                                StyledOutlinedField(
                                    value = state.presencePenalty,
                                    onValueChange = { value -> viewModel.update { it.copy(presencePenalty = value.numeric()) } },
                                    label = "Presence Penalty",
                                    placeholder = "-2 – 2（留空使用默认）",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = viewModel::resetParameters, contentPadding = PaddingValues(horizontal = 2.dp)) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("恢复模型默认参数")
                                }
                                Spacer(Modifier.weight(1f))
                                TextPill("服务商能力自动适配")
                            }
                        }
                    }

                    SectionLabel("人格注入")
                    Text(
                        "人格提示词与长期记忆每轮随 system 消息一起发送，不需要额外的同步接口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )

                    if (state.provider == ProviderType.OPENAI) {
                        SectionLabel("联网工具")
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("联网（DeepSeek 搜索 / 抓取）", style = MaterialTheme.typography.bodyMedium)
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            "开启后他可以在需要时自己查资料；按成功请求计费（\$0.75 / 千次）。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Switch(
                                        checked = state.webToolsEnabled,
                                        onCheckedChange = { value -> viewModel.update { it.copy(webToolsEnabled = value) } }
                                    )
                                }
                                if (state.webToolsEnabled) {
                                    Spacer(Modifier.height(14.dp))
                                    StyledOutlinedField(
                                        value = state.webToolsBaseUrl,
                                        onValueChange = { value -> viewModel.update { it.copy(webToolsBaseUrl = value.trim()) } },
                                        label = "联网地址",
                                        placeholder = "https://api.deepseek.com",
                                        supportingText = "留空使用 DeepSeek 官方地址"
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    StyledOutlinedField(
                                        value = state.webToolsApiKey,
                                        onValueChange = { value ->
                                            viewModel.update { it.copy(webToolsApiKey = value.trim(), webToolsKeyCleared = false) }
                                        },
                                        label = "联网专用 Key",
                                        placeholder = "留空则复用上面的 API Key",
                                        singleLine = true,
                                        visualTransformation = if (webToolsKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            IconButton(onClick = { webToolsKeyVisible = !webToolsKeyVisible }) {
                                                Icon(
                                                    if (webToolsKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                    contentDescription = if (webToolsKeyVisible) "隐藏联网密钥" else "显示联网密钥",
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (state.webToolsKeyStored) "已加密保存" else "将随配置一起加密保存",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (state.webToolsKeyStored) {
                                            Spacer(Modifier.width(4.dp))
                                            TextButton(
                                                onClick = viewModel::clearWebToolsKey,
                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                            ) {
                                                Text("清除", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SectionLabel("高级")
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
                                    .clickable { viewModel.update { it.copy(advancedExpanded = !it.advancedExpanded) } }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("自定义 Headers", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (state.customHeadersJson.isBlank()) "未设置" else "已设置 JSON",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    if (state.advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (state.advancedExpanded) {
                                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                    StyledOutlinedField(
                                        value = state.customHeadersJson,
                                        onValueChange = { value -> viewModel.update { it.copy(customHeadersJson = value) } },
                                        label = "Headers JSON",
                                        placeholder = "{\"HTTP-Referer\": \"https://example.com\"}",
                                        singleLine = false,
                                        minLines = 3,
                                        maxLines = 8,
                                        supportingText = "必须是字符串键值对。若自定义 Authorization，将优先于 API Key。"
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("允许不安全 HTTP", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "仅用于本地调试。开启后允许明文连接，存在密钥泄露风险。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (state.allowInsecureHttp) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = state.allowInsecureHttp,
                                    onCheckedChange = { value -> viewModel.update { it.copy(allowInsecureHttp = value) } }
                                )
                            }
                        }
                    }
                    Text(
                        "网络安全：默认拒绝 http://。API Key 不会出现在日志中；导出人格预设时也不会包含密钥。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp, 10.dp, 4.dp, 0.dp)
                    )
                }
            }
        }
    }

    if (state.showModelPicker) {
        ModelPickerSheet(
            models = state.models,
            onSelect = viewModel::selectModel,
            onDismiss = viewModel::closeModelPicker
        )
    }
}

@Composable
private fun ProviderOption(label: String, selected: Boolean, available: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = available,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) LocalAccentColor.current.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) LocalAccentColor.current.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    !available -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    selected -> LocalAccentColor.current
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            if (!available) {
                Spacer(Modifier.width(5.dp))
                Text("预留", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun ModelPickerSheet(
    models: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, models) {
        if (query.isBlank()) models else models.filter { it.contains(query, ignoreCase = true) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetHandle() }
    ) {
        Column(Modifier.padding(bottom = 20.dp)) {
            BottomSheetTitle("选择模型", "共 ${models.size} 个可用模型")
            Box(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
                StyledOutlinedField(
                    value = query,
                    onValueChange = { query = it },
                    label = "筛选模型",
                    placeholder = "输入名称过滤"
                )
            }
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(filtered, key = { it }) { model ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(model) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            model,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "没有匹配的模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun String.numeric(): String = filter { it.isDigit() || it == '.' || it == '-' }.take(12)
