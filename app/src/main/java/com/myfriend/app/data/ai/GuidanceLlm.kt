package com.myfriend.app.data.ai

import com.myfriend.app.data.repo.ApiConfigRepository
import com.myfriend.app.domain.UsageKind
import com.myfriend.app.network.AiProviderRegistry
import com.myfriend.app.network.InferenceParams
import com.myfriend.app.network.WireMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「请模型只回一段 JSON」的一次性调用。
 *
 * 抽成接口只为一件事：单测可以注入一个假 LLM，把「正常 JSON / 带 markdown 代码块 /
 * 缺字段 / 坏 JSON / 没配置」逐条跑一遍，而不是靠连真 API 碰运气。
 *
 * 实现不许把输入原文写进日志或数据库：输入是用户的聊天记录（`spec/persona-guidance`
 * §3.3 的隐私要求），只用于这一次总结。
 */
interface JsonLlmClient {
    /**
     * @param system 系统提示词（§6.1 / §6.2 的原文）
     * @param user 本次要分析的内容（聊天记录原文 / 角色简介）
     * @param configId 指定 API 配置；null 表示用当前可用的那条
     * @return 模型原文；没有配置、调用失败、超时都返回 null（调用方走本地兜底）
     */
    suspend fun completeJson(system: String, user: String, configId: String? = null): String?
}

/**
 * 生产实现：复用现有的 API 配置、Provider 适配器、并发闸与记账。
 *
 * 不复用聊天会话：这是一次用完即弃的分叉调用（与 `HistoryCompressor` 同一种做法），
 * 不写 messages、不碰会话摘要。
 *
 * 关于 `response_format`：现有 [com.myfriend.app.network.AiProviderAdapter] 的 `complete()`
 * 没有该字段（且 DeepSeek / 部分 OpenAI 兼容端点不支持），所以这里走「提示词约束 + 容错解析」
 * （规范允许的第二种做法）：提示词里明确「只输出 JSON」，解析时剥代码块、找第一个完整对象，
 * 认不出来就让调用方走本地兜底。
 */
@Singleton
class DefaultJsonLlmClient @Inject constructor(
    private val apiConfigRepository: ApiConfigRepository,
    private val registry: AiProviderRegistry
) : JsonLlmClient {

    override suspend fun completeJson(system: String, user: String, configId: String?): String? {
        val resolved = runCatching {
            val explicit = configId?.trim()?.takeIf { it.isNotBlank() }
                ?.let { apiConfigRepository.resolve(it) }
            explicit ?: apiConfigRepository.getAll()
                .filter { apiConfigRepository.hasApiKey(it) }
                .maxByOrNull { it.updatedAt }
                ?.let { apiConfigRepository.resolve(it.id) }
        }.getOrNull() ?: return null
        val model = resolved.config.model.trim()
        if (model.isBlank()) return null
        return runCatching {
            registry.adapterFor(resolved.config.provider).complete(
                config = resolved.config,
                apiKey = resolved.apiKey,
                model = model,
                messages = listOf(
                    WireMessage("system", system),
                    WireMessage("user", user)
                ),
                // 总结不需要创造力；输出上限够一份 JSON（含 12 条常用语）。
                params = InferenceParams(temperature = TEMPERATURE, maxOutputTokens = MAX_OUTPUT_TOKENS),
                kind = UsageKind.UNKNOWN
            )
        }.getOrNull()
    }

    private companion object {
        const val TEMPERATURE = 0.2
        const val MAX_OUTPUT_TOKENS = 1200
    }
}
