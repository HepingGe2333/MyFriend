@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yap.app.ui.screens.apiconfig

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yap.app.R
import com.yap.app.data.local.ProviderType
import com.yap.app.domain.ApiKeyState
import com.yap.app.domain.WebToolsProviders
import com.yap.app.ui.components.AppHeader
import com.yap.app.ui.components.BackNavigation
import com.yap.app.ui.components.BottomSheetHandle
import com.yap.app.ui.components.BottomSheetTitle
import com.yap.app.ui.components.PrimaryButton
import com.yap.app.ui.components.SectionLabel
import com.yap.app.ui.components.StyledOutlinedField
import com.yap.app.ui.components.TextPill
import com.yap.app.ui.theme.LocalAccentColor

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
                        Text(stringResource(if (state.testing) R.string.api_testing else R.string.api_test_connection))
                    }
                    PrimaryButton(
                        text = stringResource(if (state.isNew) R.string.api_save_new else R.string.api_save_changes),
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
                title = stringResource(if (state.isNew) R.string.api_title_add else R.string.api_title_edit),
                subtitle = stringResource(R.string.api_subtitle_key_local),
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
                    SectionLabel(stringResource(R.string.api_section_provider))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProviderOption(
                            label = stringResource(R.string.api_provider_openai_compatible),
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
                        stringResource(R.string.api_provider_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                    )

                    SectionLabel(stringResource(R.string.api_section_connection))
                    StyledOutlinedField(
                        value = state.name,
                        onValueChange = { value -> viewModel.update { it.copy(name = value.take(60)) } },
                        label = stringResource(R.string.api_field_name),
                        placeholder = stringResource(R.string.api_field_name_placeholder)
                    )
                    Spacer(Modifier.height(14.dp))
                    StyledOutlinedField(
                        value = state.baseUrl,
                        onValueChange = { value -> viewModel.update { it.copy(baseUrl = value.trim()) } },
                        label = "Base URL",
                        placeholder = "https://api.openai.com/v1",
                        supportingText = stringResource(R.string.api_field_base_url_hint)
                    )
                    Spacer(Modifier.height(14.dp))
                    StyledOutlinedField(
                        value = state.apiKey,
                        onValueChange = { value -> viewModel.update { it.copy(apiKey = value.trim()) } },
                        label = "API Key",
                        // 三态（安全 P2-6 / 审计 P2-5）：只有「解得出非空 Key」才显示已保存；
                        // 「有密文但解不出来」要明说，而不是显示「已保存」让用户去猜 401。
                        placeholder = when (state.existingKeyState) {
                            ApiKeyState.USABLE -> stringResource(R.string.api_field_key_stored_placeholder)
                            ApiKeyState.UNRECOVERABLE -> stringResource(R.string.api_key_unrecoverable_placeholder)
                            ApiKeyState.MISSING -> "sk-…"
                        },
                        supportingText = when (state.existingKeyState) {
                            ApiKeyState.UNRECOVERABLE -> stringResource(R.string.api_key_unrecoverable_hint)
                            ApiKeyState.USABLE -> stringResource(R.string.api_key_stored)
                            ApiKeyState.MISSING -> null
                        },
                        isError = state.existingKeyState == ApiKeyState.UNRECOVERABLE,
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        leadingIcon = {
                            Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (keyVisible) {
                                        stringResource(R.string.api_key_hide)
                                    } else {
                                        stringResource(R.string.api_key_show)
                                    },
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
                        label = stringResource(R.string.api_field_model_id),
                        placeholder = "gpt-4o-mini / deepseek-chat / …",
                        trailingIcon = {
                            IconButton(onClick = viewModel::fetchModels, enabled = !state.fetchingModels) {
                                if (state.fetchingModels) {
                                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 1.8.dp)
                                } else {
                                    Icon(
                                        Icons.Outlined.Refresh,
                                        contentDescription = stringResource(R.string.api_fetch_models),
                                        modifier = Modifier.size(18.dp),
                                        tint = LocalAccentColor.current
                                    )
                                }
                            }
                        },
                        supportingText = stringResource(R.string.api_field_model_hint)
                    )

                    SectionLabel(stringResource(R.string.api_section_parameters))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.api_parameters_hint),
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
                                        placeholder = stringResource(R.string.api_placeholder_model_default),
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
                                    placeholder = stringResource(R.string.api_placeholder_penalty),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = viewModel::resetParameters, contentPadding = PaddingValues(horizontal = 2.dp)) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.api_reset_parameters))
                                }
                                Spacer(Modifier.weight(1f))
                                TextPill(stringResource(R.string.api_capabilities_auto))
                            }
                        }
                    }

                    SectionLabel(stringResource(R.string.api_section_persona))
                    Text(
                        stringResource(R.string.api_persona_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )

                    if (state.provider == ProviderType.OPENAI) {
                        SectionLabel(stringResource(R.string.api_section_web_tools))
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.api_web_tools_title), style = MaterialTheme.typography.bodyMedium)
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            stringResource(R.string.api_web_tools_hint),
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
                                    Text(
                                        stringResource(R.string.api_field_web_provider),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        WebToolsProviders.all.forEach { provider ->
                                            WebProviderChip(
                                                label = stringResource(webProviderLabelRes(provider)),
                                                selected = state.webToolsProvider == provider,
                                                onClick = { viewModel.setWebToolsProvider(provider) }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        stringResource(webProviderHintRes(state.webToolsProvider)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    val provider = WebToolsProviders.normalize(state.webToolsProvider)
                                    StyledOutlinedField(
                                        value = state.webToolsBaseUrl,
                                        onValueChange = { value -> viewModel.update { it.copy(webToolsBaseUrl = value.trim()) } },
                                        label = stringResource(R.string.api_field_web_base_url),
                                        placeholder = webProviderPlaceholder(provider),
                                        supportingText = if (provider == WebToolsProviders.GENERIC) {
                                            stringResource(R.string.api_field_web_base_url_hint_generic)
                                        } else {
                                            stringResource(R.string.api_field_web_base_url_hint)
                                        }
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    StyledOutlinedField(
                                        value = state.webToolsApiKey,
                                        onValueChange = { value ->
                                            viewModel.update { it.copy(webToolsApiKey = value.trim(), webToolsKeyCleared = false) }
                                        },
                                        label = stringResource(R.string.api_field_web_key),
                                        placeholder = if (provider == WebToolsProviders.GENERIC) {
                                            stringResource(R.string.api_field_web_key_placeholder_optional)
                                        } else {
                                            stringResource(R.string.api_field_web_key_placeholder)
                                        },
                                        singleLine = true,
                                        visualTransformation = if (webToolsKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            IconButton(onClick = { webToolsKeyVisible = !webToolsKeyVisible }) {
                                                Icon(
                                                    if (webToolsKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                    contentDescription = if (webToolsKeyVisible) {
                                                        stringResource(R.string.api_web_key_hide)
                                                    } else {
                                                        stringResource(R.string.api_web_key_show)
                                                    },
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (state.webToolsKeyStored) {
                                                stringResource(R.string.api_web_key_stored)
                                            } else {
                                                stringResource(R.string.api_web_key_will_store)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (state.webToolsKeyStored) {
                                            Spacer(Modifier.width(4.dp))
                                            TextButton(
                                                onClick = viewModel::clearWebToolsKey,
                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                            ) {
                                                Text(stringResource(R.string.api_clear), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SectionLabel(stringResource(R.string.api_section_advanced))
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
                                    Text(stringResource(R.string.api_custom_headers_title), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (state.customHeadersJson.isBlank()) {
                                            stringResource(R.string.api_custom_headers_not_set)
                                        } else {
                                            stringResource(R.string.api_custom_headers_set)
                                        },
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
                                        supportingText = stringResource(R.string.api_custom_headers_hint)
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
                                    Text(stringResource(R.string.api_allow_insecure_title), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        stringResource(R.string.api_allow_insecure_hint),
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
                        stringResource(R.string.api_security_note),
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
                Text(stringResource(R.string.api_provider_reserved), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun WebProviderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) LocalAccentColor.current.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) LocalAccentColor.current.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) LocalAccentColor.current else MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun webProviderLabelRes(provider: String): Int = when (WebToolsProviders.normalize(provider)) {
    WebToolsProviders.TAVILY -> R.string.api_web_provider_tavily
    WebToolsProviders.BRAVE -> R.string.api_web_provider_brave
    WebToolsProviders.SERPER -> R.string.api_web_provider_serper
    WebToolsProviders.GENERIC -> R.string.api_web_provider_generic
    else -> R.string.api_web_provider_messageapi
}

private fun webProviderHintRes(provider: String): Int = when (WebToolsProviders.normalize(provider)) {
    WebToolsProviders.TAVILY -> R.string.api_web_provider_tavily_hint
    WebToolsProviders.BRAVE -> R.string.api_web_provider_brave_hint
    WebToolsProviders.SERPER -> R.string.api_web_provider_serper_hint
    WebToolsProviders.GENERIC -> R.string.api_web_provider_generic_hint
    else -> R.string.api_web_provider_messageapi_hint
}

/** 占位符只是提示，不会写进库；留空语义由 provider 的默认地址决定。 */
private fun webProviderPlaceholder(provider: String): String =
    WebToolsProviders.defaultBaseUrl(provider)
        ?: if (WebToolsProviders.normalize(provider) == WebToolsProviders.GENERIC) {
            "https://search.example.com/search"
        } else {
            WebToolsProviders.MESSAGEAPI_FALLBACK_BASE
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
            BottomSheetTitle(
                stringResource(R.string.api_model_picker_title),
                pluralStringResource(R.plurals.api_model_picker_subtitle, models.size, models.size)
            )
            Box(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
                StyledOutlinedField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.api_model_filter_label),
                    placeholder = stringResource(R.string.api_model_filter_placeholder)
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
                            stringResource(R.string.api_model_filter_empty),
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
