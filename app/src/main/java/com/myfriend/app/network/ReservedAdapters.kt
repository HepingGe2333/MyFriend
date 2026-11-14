package com.myfriend.app.network

import com.myfriend.app.data.local.ApiConfigEntity
import com.myfriend.app.data.local.ProviderType
import com.myfriend.app.domain.LlmLane
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * Adapter seam for future native Gemini support.
 * The UI intentionally exposes it as "预留", and the registry never pretends it works.
 */
class GeminiAdapter @Inject constructor() : AiProviderAdapter {
    override val provider = ProviderType.GEMINI
    override val capabilities = ProviderCapabilities(
        available = false,
        supportsTemperature = true,
        supportsTopP = true,
        supportsMaxTokens = true,
        supportsFrequencyPenalty = false,
        supportsPresencePenalty = false
    )
    private val error = "Gemini 原生适配接口已预留，当前版本请使用 OpenAI 兼容端点"
    override suspend fun fetchModels(config: ApiConfigEntity, apiKey: String?): List<String> = throw ProviderUnavailableException(error)
    override fun streamChat(config: ApiConfigEntity, apiKey: String?, model: String, messages: List<WireMessage>, params: InferenceParams, extraHeaders: Map<String, String>, kind: String, lane: LlmLane, sessionId: String?, personaId: String?): Flow<StreamEvent> = flow { throw ProviderUnavailableException(error) }
    override suspend fun complete(config: ApiConfigEntity, apiKey: String?, model: String, messages: List<WireMessage>, params: InferenceParams, extraHeaders: Map<String, String>, kind: String, lane: LlmLane, sessionId: String?, personaId: String?): String = throw ProviderUnavailableException(error)
}

class ClaudeAdapter @Inject constructor() : AiProviderAdapter {
    override val provider = ProviderType.CLAUDE
    override val capabilities = ProviderCapabilities(
        available = false,
        supportsTemperature = true,
        supportsTopP = true,
        supportsMaxTokens = true,
        supportsFrequencyPenalty = false,
        supportsPresencePenalty = false
    )
    private val error = "Claude 原生适配接口已预留，当前版本请使用 OpenAI 兼容端点"
    override suspend fun fetchModels(config: ApiConfigEntity, apiKey: String?): List<String> = throw ProviderUnavailableException(error)
    override fun streamChat(config: ApiConfigEntity, apiKey: String?, model: String, messages: List<WireMessage>, params: InferenceParams, extraHeaders: Map<String, String>, kind: String, lane: LlmLane, sessionId: String?, personaId: String?): Flow<StreamEvent> = flow { throw ProviderUnavailableException(error) }
    override suspend fun complete(config: ApiConfigEntity, apiKey: String?, model: String, messages: List<WireMessage>, params: InferenceParams, extraHeaders: Map<String, String>, kind: String, lane: LlmLane, sessionId: String?, personaId: String?): String = throw ProviderUnavailableException(error)
}

class AiProviderRegistry @Inject constructor(
    private val openAiAdapter: OpenAiAdapter,
    geminiAdapter: GeminiAdapter,
    claudeAdapter: ClaudeAdapter
) {
    private val adapters = listOf(openAiAdapter, geminiAdapter, claudeAdapter).associateBy { it.provider }
    fun adapterFor(provider: ProviderType): AiProviderAdapter =
        adapters[provider] ?: throw ProviderUnavailableException("暂不支持该服务商")
    fun capabilities(provider: ProviderType): ProviderCapabilities =
        adapterFor(provider).capabilities
}
