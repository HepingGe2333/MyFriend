package com.myfriend.app.ui.screens.apiconfig

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myfriend.app.ui.common.ErrorText
import com.myfriend.app.R
import com.myfriend.app.data.local.ProviderType
import com.myfriend.app.data.repo.ApiConfigRepository
import com.myfriend.app.domain.ApiConfigDraft
import com.myfriend.app.domain.ApiKeyState
import com.myfriend.app.network.ProviderCapabilities
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ApiEditorUiState(
    val apiConfigId: String? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val fetchingModels: Boolean = false,
    val name: String = "",
    val provider: ProviderType = ProviderType.OPENAI,
    val baseUrl: String = "",
    val apiKey: String = "",
    /**
     * 已保存 Key 的三态（安全 P2-6 / 审计 P2-5）。
     *
     * 以前界面只拿到 `hasExistingKey`（密文非空）：Keystore 失效、密文还在时显示
     * 「密钥已加密保存」，用户发消息才收到 401/无法恢复。现在「解不出来」是独立状态，
     * 界面上直接说「密钥无法解密，请重新填写」。
     */
    val existingKeyState: ApiKeyState = ApiKeyState.MISSING,
    val model: String = "",
    val customHeadersJson: String = "",
    val allowInsecureHttp: Boolean = false,
    val temperature: String = "",
    val topP: String = "",
    val maxOutputTokens: String = "",
    val frequencyPenalty: String = "",
    val presencePenalty: String = "",
    val webToolsEnabled: Boolean = false,
    val webToolsBaseUrl: String = "",
    /** 仅存在于内存；为空表示复用主 Key。 */
    val webToolsApiKey: String = "",
    /** 已有加密 Key（界面上只显示「已加密保存」，不回填明文）。 */
    val webToolsKeyStored: Boolean = false,
    /** 用户点击「清除」后置为 true，保存时把 webToolsApiKeyCipher 置 null。 */
    val webToolsKeyCleared: Boolean = false,
    val capabilities: ProviderCapabilities = ProviderCapabilities(true, true, true, true, true, true),
    val models: List<String> = emptyList(),
    val showModelPicker: Boolean = false,
    val advancedExpanded: Boolean = false,
    val isNew: Boolean = true
)

sealed interface ApiEditorEvent {
    data class Saved(val configId: String) : ApiEditorEvent
    data class Message(val text: String) : ApiEditorEvent
}

@HiltViewModel
class ApiEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ApiConfigRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val configId: String? = savedStateHandle.get<String>("configId")?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(ApiEditorUiState(apiConfigId = configId, isNew = configId == null))
    val state: StateFlow<ApiEditorUiState> = _state.asStateFlow()
    private val events = Channel<ApiEditorEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    init {
        _state.update { it.copy(capabilities = repository.providerInfos().first().capabilities) }
        viewModelScope.launch {
            if (configId == null) {
                _state.update { it.copy(loading = false) }
            } else {
                val config = repository.getById(configId)
                if (config == null) {
                    events.send(ApiEditorEvent.Message(context.getString(R.string.api_error_config_not_found)))
                    _state.update { it.copy(loading = false) }
                } else {
                    _state.update {
                        it.copy(
                            loading = false,
                            name = config.name,
                            provider = config.provider,
                            baseUrl = config.baseUrl,
                            existingKeyState = repository.keyState(config),
                            model = config.model,
                            customHeadersJson = config.customHeadersJson,
                            allowInsecureHttp = config.allowInsecureHttp,
                            temperature = config.temperature?.formatNumber().orEmpty(),
                            topP = config.topP?.formatNumber().orEmpty(),
                            maxOutputTokens = config.maxOutputTokens?.toString().orEmpty(),
                            frequencyPenalty = config.frequencyPenalty?.formatNumber().orEmpty(),
                            presencePenalty = config.presencePenalty?.formatNumber().orEmpty(),
                            webToolsEnabled = config.webToolsEnabled,
                            webToolsBaseUrl = config.webToolsBaseUrl,
                            webToolsApiKey = "",
                            webToolsKeyStored = repository.hasWebToolsKey(config),
                            webToolsKeyCleared = false,
                            capabilities = repository.providerInfos().firstOrNull { it.type == config.provider }?.capabilities
                                ?: it.capabilities
                        )
                    }
                }
            }
        }
    }

    fun update(transform: (ApiEditorUiState) -> ApiEditorUiState) {
        _state.update(transform)
    }

    fun setProvider(provider: ProviderType) {
        val info = repository.providerInfos().firstOrNull { it.type == provider } ?: return
        if (!info.capabilities.available) return
        _state.update {
            it.copy(
                provider = provider,
                capabilities = info.capabilities,
                frequencyPenalty = if (info.capabilities.supportsFrequencyPenalty) it.frequencyPenalty else "",
                presencePenalty = if (info.capabilities.supportsPresencePenalty) it.presencePenalty else ""
            )
        }
    }

    fun resetParameters() {
        _state.update {
            it.copy(
                temperature = "",
                topP = "",
                maxOutputTokens = "",
                frequencyPenalty = "",
                presencePenalty = ""
            )
        }
        viewModelScope.launch { events.send(ApiEditorEvent.Message(context.getString(R.string.api_reset_parameters_done))) }
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val result = repository.save(
                draft = current.toDraft(),
                webToolsEnabled = current.webToolsEnabled,
                webToolsBaseUrl = current.webToolsBaseUrl,
                webToolsApiKey = current.webToolsApiKey,
                clearWebToolsApiKey = current.webToolsKeyCleared
            )
            _state.update { it.copy(saving = false) }
            result.onSuccess { events.send(ApiEditorEvent.Saved(it)) }
                .onFailure {
                    events.send(ApiEditorEvent.Message(ErrorText.of(context, it, R.string.api_error_save_failed)))
                }
        }
    }

    /** 清除已保存的联网专用 Key（保存后改为复用主 Key）。 */
    fun clearWebToolsKey() {
        _state.update { it.copy(webToolsApiKey = "", webToolsKeyStored = false, webToolsKeyCleared = true) }
    }

    fun testConnection() {
        val current = _state.value
        if (current.testing) return
        viewModelScope.launch {
            _state.update { it.copy(testing = true) }
            repository.testDraft(current.toDraft())
                .onSuccess { events.send(ApiEditorEvent.Message(ErrorText.connectionSuccess(context, it.modelCount))) }
                .onFailure {
                    val reason = ErrorText.of(context, it, R.string.api_error_unknown)
                    events.send(ApiEditorEvent.Message(context.getString(R.string.api_error_connect_failed, reason)))
                }
            _state.update { it.copy(testing = false) }
        }
    }

    fun fetchModels() {
        val current = _state.value
        if (current.fetchingModels) return
        viewModelScope.launch {
            _state.update { it.copy(fetchingModels = true) }
            repository.fetchModelsForDraft(current.toDraft())
                .onSuccess { models ->
                    if (models.isEmpty()) {
                        events.send(ApiEditorEvent.Message(context.getString(R.string.api_models_empty_hint)))
                    } else {
                        _state.update { it.copy(models = models, showModelPicker = true) }
                    }
                }
                .onFailure {
                    val reason = ErrorText.of(context, it, R.string.api_error_unknown)
                    events.send(ApiEditorEvent.Message(context.getString(R.string.api_error_fetch_models_failed, reason)))
                }
            _state.update { it.copy(fetchingModels = false) }
        }
    }

    fun selectModel(model: String) {
        _state.update { it.copy(model = model, showModelPicker = false) }
    }

    fun closeModelPicker() {
        _state.update { it.copy(showModelPicker = false) }
    }

    private fun ApiEditorUiState.toDraft(): ApiConfigDraft = ApiConfigDraft(
        id = apiConfigId,
        name = name,
        provider = provider,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        customHeadersJson = customHeadersJson,
        allowInsecureHttp = allowInsecureHttp,
        temperature = temperature.toDoubleOrNull(),
        topP = topP.toDoubleOrNull(),
        maxOutputTokens = maxOutputTokens.toIntOrNull(),
        frequencyPenalty = frequencyPenalty.toDoubleOrNull(),
        presencePenalty = presencePenalty.toDoubleOrNull()
    )

    private fun Double.formatNumber(): String =
        if (this == toLong().toDouble()) toLong().toString() else toString()
}
