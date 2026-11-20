package com.yap.app.data.repo

import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.PersonaEntity
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.domain.GenerationProbe
import com.yap.app.domain.LlmLane
import com.yap.app.domain.PromptBuilder
import com.yap.app.domain.PromptLanguage
import com.yap.app.domain.PromptTemplates
import com.yap.app.domain.ResolvedApiConfig
import com.yap.app.domain.UsageKind
import com.yap.app.network.AiProviderRegistry
import com.yap.app.network.InferenceParams
import com.yap.app.network.WireMessage
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置页「测试生成」的执行者（0.15）。
 *
 * 用**当前人格 + 当前配置**真的发一条消息，只为了回答三个问题：
 * 他会不会说话、这一轮多慢、有没有调用工具。
 *
 * 三条刻意的约束：
 * 1. **不写任何会话**：不落库、不进历史、不触发记忆抽取、不产生通知；
 * 2. **不带记忆/工具回灌**：只发「人设 + 一句测试消息」，这样测的是配置与人格本身，
 *    而不是「今天他刚好想起了什么」；
 * 3. **失败安静**：返回带稳定令牌的 [GenerationProbe.Result]，界面映射成一句人话。
 */
@Singleton
class GenerationTestRepository @Inject constructor(
    private val personaDao: PersonaDao,
    private val settingsRepository: SettingsRepository,
    private val apiConfigRepository: ApiConfigRepository,
    private val registry: AiProviderRegistry
) {

    /**
     * 跑一次测试生成。
     *
     * @param personaId 用哪个人格；null 时用设置里的当前人格
     */
    suspend fun run(
        personaId: String? = null,
        request: GenerationProbe.Request = GenerationProbe.Request()
    ): GenerationProbe.Result {
        val persona = resolvePersona(personaId)
            ?: return failed(GenerationProbe.TOKEN_FAILED)
        val resolved = resolveConfig(persona)
            ?: return failed(GenerationProbe.TOKEN_FAILED)
        val message = request.message.trim().ifBlank { GenerationProbe.DEFAULT_PROBE_MESSAGE }
        val language = PromptLanguage.resolve(persona.languageTag)
        val system = PromptBuilder.system(
            PromptBuilder.Inputs(
                name = persona.name,
                legacyPrompt = persona.prompt,
                now = System.currentTimeMillis(),
                // 没有心情/世界/话风：测试的是「这个人设本身」会不会说话。
                capabilitiesSection = "",
                languageSection = "\n\n" + PromptTemplates.directive(language),
                language = language
            )
        )
        val startedAt = System.currentTimeMillis()
        val raw = runCatching {
            registry.adapterFor(resolved.config.provider).complete(
                config = resolved.config,
                apiKey = resolved.apiKey,
                model = persona.modelOverride?.takeIf { it.isNotBlank() } ?: resolved.config.model,
                messages = listOf(WireMessage("system", system), WireMessage("user", message)),
                params = InferenceParams(
                    temperature = request.temperature.coerceIn(0.0, 1.5),
                    maxOutputTokens = request.maxOutputTokens.coerceIn(64, 1_024)
                ),
                // 算聊天用量：用户主动点的测试不该被「本月额度」挡掉。
                kind = UsageKind.CHAT,
                lane = LlmLane.CHAT
            )
        }.getOrNull()
        val elapsed = System.currentTimeMillis() - startedAt
        if (raw.isNullOrBlank()) return failed(GenerationProbe.TOKEN_FAILED, elapsed)
        return GenerationProbe.measure(raw, elapsed)
    }

    /** 当前人格：显式传入 > 设置里的当前人格 > 第一个人格。 */
    suspend fun resolvePersona(personaId: String? = null): PersonaEntity? {
        personaId?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { personaDao.getById(id) }.getOrNull()?.let { return it }
        }
        val active = runCatching { settingsRepository.settings.first().activePersonaId }.getOrNull()
        active?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { personaDao.getById(id) }.getOrNull()?.let { return it }
        }
        return runCatching { personaDao.getAll() }.getOrDefault(emptyList()).firstOrNull()
    }

    /** 配置：人格默认配置优先；没绑定就看有没有唯一可用配置。 */
    private suspend fun resolveConfig(persona: PersonaEntity): ResolvedApiConfig? {
        val id = persona.defaultApiConfigId ?: return null
        return runCatching { apiConfigRepository.resolve(id) }.getOrNull()
    }

    private fun failed(token: String, elapsedMs: Long = 0L): GenerationProbe.Result =
        GenerationProbe.Result(elapsedMs = elapsedMs, charCount = 0, toolCalls = 0, text = "", errorToken = token)
}
