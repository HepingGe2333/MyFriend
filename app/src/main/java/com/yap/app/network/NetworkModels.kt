package com.yap.app.network

import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.domain.NetworkErrorKind
import com.yap.app.domain.UserError
import com.yap.app.domain.debugText
import com.yap.app.data.local.ProviderType

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

/**
 * 连接测试的结果。
 *
 * 0.10.1 起不再带一句现成的中文：成功时只给「发现几个模型」，失败由调用方从
 * [ApiException.kind] 映射资源；界面上不会再读到数据层拼的句子。
 */
data class ConnectionTestResult(
    val success: Boolean = true,
    val modelCount: Int = 0,
    val models: List<String> = emptyList()
)

/**
 * 网络层错误。
 *
 * 只装结构化原因 [kind]（见 `domain/UserError.kt`）与服务端原始 [detail]；
 * [message] 是给日志/调试看的中文，**界面不得直接显示**（UI 走 `ErrorText` 映射资源）。
 */
class ApiException(
    val kind: NetworkErrorKind,
    val detail: String? = null
) : Exception(UserError.Network(kind).debugText())

/** 服务商适配还没做（Gemini / Claude 预留）：等价于一个网络层错误。 */
class ProviderUnavailableException(
    val provider: String? = null
) : Exception(UserError.Network(NetworkErrorKind.ProviderUnavailable(provider)).debugText())

data class ProviderUiInfo(
    val type: ProviderType,
    val capabilities: ProviderCapabilities
)
