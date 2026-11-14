package com.myfriend.app.network

import com.myfriend.app.data.local.ApiConfigEntity
import com.myfriend.app.data.local.ProviderType

data class WireMessage(
    val role: String,
    val content: String,
    /** 已经编码好的 data URL（base64），非空时以多模态 content 数组发送。 */
    val images: List<String> = emptyList()
)

data class InferenceParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null
)

fun ApiConfigEntity.inferenceParams(): InferenceParams = InferenceParams(
    temperature = temperature,
    topP = topP,
    maxOutputTokens = maxOutputTokens,
    frequencyPenalty = frequencyPenalty,
    presencePenalty = presencePenalty
)

data class ProviderCapabilities(
    val available: Boolean,
    val supportsTemperature: Boolean,
    val supportsTopP: Boolean,
    val supportsMaxTokens: Boolean,
    val supportsFrequencyPenalty: Boolean,
    val supportsPresencePenalty: Boolean
)

sealed class StreamEvent {
    data class Text(val value: String) : StreamEvent()
    data object Done : StreamEvent()
}

data class ConnectionTestResult(
    val success: Boolean,
    val message: String,
    val models: List<String> = emptyList()
)

class ApiException(
    val statusCode: Int?,
    override val message: String,
    val detail: String? = null
) : Exception(message)

class ProviderUnavailableException(
    override val message: String
) : Exception(message)

data class ProviderUiInfo(
    val type: ProviderType,
    val label: String,
    val capabilities: ProviderCapabilities
)
