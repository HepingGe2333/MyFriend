package com.yap.app.network

import com.yap.app.data.local.ApiConfigEntity
import com.yap.app.domain.ApiDefaults
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

/**
 * 这个配置实际要发出去的推理参数。
 *
 * 配置里**显式填了**就用配置（用户能覆盖），没填就用应用默认值：
 * `temperature=0.75`、`max_tokens=16384`、`top_p=1.0`、频率/存在惩罚 0
 * （`spec/api-defaults/README.md`）。默认值写在 [ApiDefaults] 一处，聊天、主动消息、
 * 工具回路每一轮都走这里，不会出现「第一轮是 0.75、第二轮忘了带」。
 */
fun ApiConfigEntity.inferenceParams(): InferenceParams = InferenceParams(
    temperature = temperature ?: ApiDefaults.TEMPERATURE,
    topP = topP ?: ApiDefaults.TOP_P,
    maxOutputTokens = maxOutputTokens ?: ApiDefaults.MAX_OUTPUT_TOKENS,
    frequencyPenalty = frequencyPenalty ?: ApiDefaults.FREQUENCY_PENALTY,
    presencePenalty = presencePenalty ?: ApiDefaults.PRESENCE_PENALTY
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
