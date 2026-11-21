package com.yap.app.data.ai

import com.yap.app.data.repo.ApiConfigRepository
import com.yap.app.domain.CrisisJudgement
import com.yap.app.domain.CrisisModel
import com.yap.app.domain.LlmLane
import com.yap.app.domain.UsageKind
import com.yap.app.network.AiProviderRegistry
import com.yap.app.network.InferenceParams
import com.yap.app.network.WireMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 危机第二段判定的生产实现：用**当前 API 配置**发一次独立 completion。
 *
 * 与聊天完全分开：不写 messages、不碰会话、不进上下文、不共享「这一轮」的临时状态。
 * 一次用完即弃（与 `DefaultJsonLlmClient` 同一种做法），失败返回 null，
 * 由 [com.yap.app.domain.CrisisClassifier] 按危机兜底。
 *
 * 隐私：只发用户的这一句原话（[CrisisJudgement.userPrompt] 已截短），不发聊天历史、
 * 不发记忆、不发 API Key 以外的任何配置；请求内容既不打日志也不落库。
 *
 * 为什么走 BACKGROUND 车道：它是聊天之外的附加请求，不该和聊天抢「保底的那 1 个位」，
 * 也不该让用户等它——调用方（`ChatRepository`）会把它挂在后台跑，需要结论时再取。
 */
@Singleton
class DefaultCrisisModel @Inject constructor(
    private val apiConfigRepository: ApiConfigRepository,
    private val registry: AiProviderRegistry
) : CrisisModel {

    override suspend fun complete(system: String, user: String): String? {
        // 「当前 API 配置」= 用户在设置里最后动过、且 Key 可用的那条（与 JSON 总结同一口径）。
        val resolved = runCatching {
            apiConfigRepository.getAll()
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
                // 判定不要创造力：温度 0、输出很短。
                params = InferenceParams(
                    temperature = CrisisJudgement.TEMPERATURE,
                    maxOutputTokens = CrisisJudgement.MAX_OUTPUT_TOKENS
                ),
                kind = UsageKind.UNKNOWN,
                lane = LlmLane.BACKGROUND
            )
        }.getOrNull()
    }
}
