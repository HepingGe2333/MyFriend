package com.myfriend.app.network

import com.myfriend.app.data.local.ApiConfigEntity
import com.myfriend.app.data.local.ProviderType
import com.myfriend.app.domain.LlmLane
import com.myfriend.app.domain.UsageKind
import kotlinx.coroutines.flow.Flow

interface AiProviderAdapter {
    val provider: ProviderType
    val capabilities: ProviderCapabilities

    suspend fun fetchModels(config: ApiConfigEntity, apiKey: String?): List<String>

    /**
     * 流式聊天。
     *
     * [kind] 是这次调用算在哪件事上（[UsageKind]）；
     * [lane] 决定它走「聊天保底」还是「后台名额」——用户在看的那一轮必须传 [LlmLane.CHAT]；
     * [sessionId] / [personaId] 只用于用量页的「按人」统计。
     */
    fun streamChat(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: List<WireMessage>,
        params: InferenceParams,
        extraHeaders: Map<String, String> = emptyMap(),
        kind: String = UsageKind.UNKNOWN,
        lane: LlmLane = LlmLane.BACKGROUND,
        sessionId: String? = null,
        personaId: String? = null
    ): Flow<StreamEvent>

    /**
     * 一次性的完整回复（后台任务都用它）。
     *
     * 实现里先过全局并发闸，再发请求，拿到回复后按 usage 记一笔账。
     * [lane]、[sessionId]、[personaId] 的语义与 [streamChat] 一致。
     */
    suspend fun complete(
        config: ApiConfigEntity,
        apiKey: String?,
        model: String,
        messages: List<WireMessage>,
        params: InferenceParams,
        extraHeaders: Map<String, String> = emptyMap(),
        kind: String = UsageKind.UNKNOWN,
        lane: LlmLane = LlmLane.BACKGROUND,
        sessionId: String? = null,
        personaId: String? = null
    ): String
}
