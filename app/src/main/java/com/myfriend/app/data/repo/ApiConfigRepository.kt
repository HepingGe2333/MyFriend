package com.myfriend.app.data.repo

import androidx.room.withTransaction
import com.myfriend.app.data.local.ApiConfigDao
import com.myfriend.app.data.local.ApiConfigEntity
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.ProviderType
import com.myfriend.app.data.secure.SecretCipher
import com.myfriend.app.domain.ApiConfigDraft
import com.myfriend.app.network.ConnectionTestResult
import com.myfriend.app.domain.ResolvedApiConfig
import com.myfriend.app.network.AiProviderRegistry
import com.myfriend.app.network.NetworkGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiConfigRepository @Inject constructor(
    private val apiConfigDao: ApiConfigDao,
    private val personaDao: PersonaDao,
    private val sessionDao: ChatSessionDao,
    private val database: MyFriendDatabase,
    private val secretCipher: SecretCipher,
    private val registry: AiProviderRegistry,
    private val json: Json
) {
    fun observeAll(): Flow<List<ApiConfigEntity>> = apiConfigDao.observeAll()
    fun observeById(id: String): Flow<ApiConfigEntity?> = apiConfigDao.observeById(id)
    suspend fun getById(id: String?): ApiConfigEntity? = id?.let { apiConfigDao.getById(it) }
    suspend fun getAll(): List<ApiConfigEntity> = apiConfigDao.getAll()

    fun providerInfos(): List<com.myfriend.app.network.ProviderUiInfo> = listOf(
        com.myfriend.app.network.ProviderUiInfo(
            type = ProviderType.OPENAI,
            label = "OpenAI 兼容",
            capabilities = registry.capabilities(ProviderType.OPENAI)
        ),
        com.myfriend.app.network.ProviderUiInfo(
            type = ProviderType.GEMINI,
            label = "Gemini（预留）",
            capabilities = registry.capabilities(ProviderType.GEMINI)
        ),
        com.myfriend.app.network.ProviderUiInfo(
            type = ProviderType.CLAUDE,
            label = "Claude（预留）",
            capabilities = registry.capabilities(ProviderType.CLAUDE)
        )
    )

    fun providerLabel(type: ProviderType): String = when (type) {
        ProviderType.OPENAI -> "OpenAI 兼容"
        ProviderType.GEMINI -> "Gemini（预留）"
        ProviderType.CLAUDE -> "Claude（预留）"
    }

    fun hasApiKey(config: ApiConfigEntity): Boolean = secretCipher.hasKey(config.apiKeyCipher)

    /** 是否已保存独立的联网专用 Key（为空表示复用主 Key）。 */
    fun hasWebToolsKey(config: ApiConfigEntity): Boolean = secretCipher.hasKey(config.webToolsApiKeyCipher)

    suspend fun resolve(configId: String): ResolvedApiConfig? {
        val config = apiConfigDao.getById(configId) ?: return null
        return ResolvedApiConfig(config, secretCipher.decrypt(config.apiKeyCipher))
    }

    /**
     * 保存配置。联网工具字段默认关闭/留空，仅编辑器会显式传入；
     * 新参数都有默认值，旧调用点不受影响。
     */
    suspend fun save(
        draft: ApiConfigDraft,
        webToolsEnabled: Boolean = false,
        webToolsBaseUrl: String = "",
        webToolsApiKey: String = "",
        clearWebToolsApiKey: Boolean = false
    ): Result<String> = runCatching {
        val existing = draft.id?.let { apiConfigDao.getById(it) }
        if (existing != null && existing.id != draft.id) error("配置不存在")
        val name = draft.name.trim()
        require(name.isNotBlank()) { "请填写配置名称" }
        require(name.length <= 60) { "配置名称过长" }
        val baseUrl = NetworkGuard.normalizeBaseUrl(draft.baseUrl)
        NetworkGuard.parseHttpUrl(baseUrl, draft.allowInsecureHttp)
        require(draft.model.trim().isNotBlank()) { "请填写模型 ID" }
        require(draft.model.trim().length <= 160) { "模型 ID 过长" }
        val capabilities = registry.capabilities(draft.provider)
        require(capabilities.available) { "该服务商适配仍在预留中，请选择 OpenAI 兼容" }
        validateHeaders(draft.customHeadersJson)
        validateParams(draft)
        val now = System.currentTimeMillis()
        val id = existing?.id ?: UUID.randomUUID().toString()
        val keyCipher = when {
            draft.apiKey.isNotBlank() -> secretCipher.encrypt(draft.apiKey)
            existing != null -> existing.apiKeyCipher
            else -> null
        }
        // 联网专用 Key：新输入加密 > 显式清除 > 保留原值；空且原值为空则继续复用主 Key。
        val webToolsKeyCipher = when {
            webToolsApiKey.isNotBlank() -> secretCipher.encrypt(webToolsApiKey.trim())
            clearWebToolsApiKey -> null
            else -> existing?.webToolsApiKeyCipher
        }
        val entity = ApiConfigEntity(
            id = id,
            name = name,
            provider = draft.provider,
            baseUrl = baseUrl,
            apiKeyCipher = keyCipher,
            model = draft.model.trim(),
            customHeadersJson = draft.customHeadersJson.trim(),
            allowInsecureHttp = draft.allowInsecureHttp,
            temperature = draft.temperature,
            topP = draft.topP,
            maxOutputTokens = draft.maxOutputTokens,
            frequencyPenalty = draft.frequencyPenalty,
            presencePenalty = draft.presencePenalty,
            webToolsEnabled = webToolsEnabled,
            webToolsBaseUrl = webToolsBaseUrl.trim(),
            webToolsApiKeyCipher = webToolsKeyCipher,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        apiConfigDao.insert(entity)
        id
    }

    suspend fun duplicate(configId: String): Result<String> = runCatching {
        val source = apiConfigDao.getById(configId) ?: error("配置不存在")
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} 副本".take(60),
            createdAt = now,
            updatedAt = now
        )
        apiConfigDao.insert(copy)
        copy.id
    }

    suspend fun delete(configId: String) {
        database.withTransaction {
            personaDao.unbindApiConfig(configId)
            sessionDao.detachConfig(configId, System.currentTimeMillis())
            apiConfigDao.deleteById(configId)
        }
    }

    suspend fun testConnection(configId: String): Result<ConnectionTestResult> = runCatching {
        val resolved = resolve(configId) ?: error("配置不存在")
        val models = registry.adapterFor(resolved.config.provider)
            .fetchModels(resolved.config, resolved.apiKey)
        ConnectionTestResult(
            success = true,
            message = if (models.isEmpty()) "连接成功" else "连接成功，发现 ${models.size} 个模型",
            models = models
        )
    }

    suspend fun fetchModels(configId: String): Result<List<String>> = runCatching {
        val resolved = resolve(configId) ?: error("配置不存在")
        registry.adapterFor(resolved.config.provider).fetchModels(resolved.config, resolved.apiKey)
    }

    suspend fun testDraft(draft: ApiConfigDraft): Result<ConnectionTestResult> = runCatching {
        val existingKey = draft.id?.let { apiConfigDao.getById(it) }?.let { secretCipher.decrypt(it.apiKeyCipher) }
        val key = draft.apiKey.ifBlank { existingKey }
        NetworkGuard.parseHttpUrl(draft.baseUrl, draft.allowInsecureHttp)
        val config = ApiConfigEntity(
            id = draft.id ?: "draft",
            name = draft.name,
            provider = draft.provider,
            baseUrl = NetworkGuard.normalizeBaseUrl(draft.baseUrl),
            apiKeyCipher = null,
            model = draft.model,
            customHeadersJson = draft.customHeadersJson,
            allowInsecureHttp = draft.allowInsecureHttp,
            temperature = draft.temperature,
            topP = draft.topP,
            maxOutputTokens = draft.maxOutputTokens,
            frequencyPenalty = draft.frequencyPenalty,
            presencePenalty = draft.presencePenalty,
            createdAt = 0,
            updatedAt = 0
        )
        val models = registry.adapterFor(config.provider).fetchModels(config, key)
        ConnectionTestResult(
            success = true,
            message = if (models.isEmpty()) "连接成功" else "连接成功，发现 ${models.size} 个模型",
            models = models
        )
    }

    suspend fun fetchModelsForDraft(draft: ApiConfigDraft): Result<List<String>> = runCatching {
        val existingKey = draft.id?.let { apiConfigDao.getById(it) }?.let { secretCipher.decrypt(it.apiKeyCipher) }
        val key = draft.apiKey.ifBlank { existingKey }
        val config = ApiConfigEntity(
            id = "draft",
            name = draft.name,
            provider = draft.provider,
            baseUrl = NetworkGuard.normalizeBaseUrl(draft.baseUrl),
            apiKeyCipher = null,
            model = draft.model,
            customHeadersJson = draft.customHeadersJson,
            allowInsecureHttp = draft.allowInsecureHttp,
            temperature = draft.temperature,
            topP = draft.topP,
            maxOutputTokens = draft.maxOutputTokens,
            frequencyPenalty = draft.frequencyPenalty,
            presencePenalty = draft.presencePenalty,
            createdAt = 0,
            updatedAt = 0
        )
        registry.adapterFor(config.provider).fetchModels(config, key)
    }

    private fun validateHeaders(raw: String) {
        if (raw.isBlank()) return
        val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            throw IllegalArgumentException("自定义 Headers 必须是合法的 JSON 对象")
        }
        parsed.forEach { (key, value) ->
            require(key.isNotBlank()) { "Header 名称不能为空" }
            require(value is JsonPrimitive && value.isString) { "Header $key 的值必须是字符串" }
        }
    }

    private fun validateParams(draft: ApiConfigDraft) {
        draft.temperature?.let { require(it in 0.0..2.0) { "Temperature 应在 0 到 2 之间" } }
        draft.topP?.let { require(it in 0.0..1.0) { "Top P 应在 0 到 1 之间" } }
        draft.maxOutputTokens?.let { require(it in 1..1_000_000) { "Max Output Tokens 必须大于 0" } }
        draft.frequencyPenalty?.let { require(it in -2.0..2.0) { "Frequency Penalty 应在 -2 到 2 之间" } }
        draft.presencePenalty?.let { require(it in -2.0..2.0) { "Presence Penalty 应在 -2 到 2 之间" } }
    }
}
