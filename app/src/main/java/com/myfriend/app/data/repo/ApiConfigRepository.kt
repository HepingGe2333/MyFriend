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
import com.myfriend.app.domain.ApiConfigRules
import com.myfriend.app.domain.ApiKeyPolicy
import com.myfriend.app.domain.ApiKeyState
import com.myfriend.app.domain.DomainErrorCode
import com.myfriend.app.domain.WebToolsProviders
import com.myfriend.app.domain.WebUrlPolicy
import com.myfriend.app.network.ConnectionTestResult
import com.myfriend.app.domain.ResolvedApiConfig
import com.myfriend.app.network.AiProviderRegistry
import com.myfriend.app.network.ApiException
import com.myfriend.app.domain.NetworkErrorKind
import com.myfriend.app.network.NetworkGuard
import com.myfriend.app.network.userError
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

    /**
     * 可选服务商只给「能不能用」的能力表，不再带展示名——
     * 展示名由 UI 按 [ProviderType] 映射资源（见 `ProviderLabels`），数据层不拼中文。
     */
    fun providerInfos(): List<com.myfriend.app.network.ProviderUiInfo> = ProviderType.entries.map {
        com.myfriend.app.network.ProviderUiInfo(
            type = it,
            capabilities = registry.capabilities(it)
        )
    }

    /**
     * 「这个配置现在能不能用 Key」的布尔形状。
     *
     * 安全 P2-6（0.11.1 审计 P2-5）：语义改成三态判定的转发——只有
     * [ApiKeyState.USABLE] 才算已配置，Keystore 失效时不再谎报「已保存」。
     * 界面直接用 [keyState] 区分「没配 / 可用 / 解不出来」。
     */
    fun hasApiKey(config: ApiConfigEntity): Boolean = keyState(config) == ApiKeyState.USABLE

    /** 是否已保存独立的联网专用 Key（为空表示复用主 Key）。 */
    fun hasWebToolsKey(config: ApiConfigEntity): Boolean = secretCipher.hasKey(config.webToolsApiKeyCipher)

    /** 这条配置的 Key 现在是「没配 / 可用 / 解不出来」哪一种（安全审计 P2-6）。 */
    fun keyState(config: ApiConfigEntity): ApiKeyState =
        ApiKeyPolicy.evaluate(config.apiKeyCipher, secretCipher.decrypt(config.apiKeyCipher))

    /**
     * 解析一条配置（含明文 Key）。
     *
     * 安全 P2-6：密文在、但**解不出来**时抛 [com.myfriend.app.domain.DomainErrorCode.API_KEY_UNRECOVERABLE]，
     * 而不是返回一个 `apiKey = null` 的配置——那会让请求不带 Authorization 发出去，
     * 用户最后看到的是一句「API Key 无效（401）」，而真正的解法是「重新填一次 Key」。
     */
    suspend fun resolve(configId: String): ResolvedApiConfig? {
        val config = apiConfigDao.getById(configId) ?: return null
        val decrypted = secretCipher.decrypt(config.apiKeyCipher)
        if (ApiKeyPolicy.evaluate(config.apiKeyCipher, decrypted) == ApiKeyState.UNRECOVERABLE) {
            userError(DomainErrorCode.API_KEY_UNRECOVERABLE)
        }
        return ResolvedApiConfig(config, decrypted)
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
        clearWebToolsApiKey: Boolean = false,
        webToolsProvider: String = WebToolsProviders.DEEPSEEK
    ): Result<String> = runCatching {
        val existing = draft.id?.let { apiConfigDao.getById(it) }
        if (existing != null && existing.id != draft.id) userError(DomainErrorCode.API_CONFIG_NOT_FOUND)
        val name = draft.name.trim()
        val baseUrl = NetworkGuard.normalizeBaseUrl(draft.baseUrl)
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
            // 空/脏值收敛到合法 provider；切换 provider 只改这一列，地址与 Key 原样保留。
            webToolsProvider = WebToolsProviders.normalize(webToolsProvider),
            webToolsBaseUrl = webToolsBaseUrl.trim(),
            webToolsApiKeyCipher = webToolsKeyCipher,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        // 与备份导入共用同一套校验：手填、导入、将来别的入口都必须过这一关。
        validateEntity(entity)
        apiConfigDao.insert(entity)
        id
    }

    /**
     * 一条配置实体的完整校验（保存与备份导入共用）。
     *
     * 之前只有 [save] 里有这些检查，备份导入直接写 DAO，于是 `temperature = 1e9`、
     * 超长 model、`http://` 明文地址都能从一份备份里塞进库；`allowInsecureHttp` 更是
     * 被合并逻辑静默打开。现在导入路径也必须调用这里，不合法就跳过那条配置并记 warning。
     *
     * 抛 [com.myfriend.app.domain.UserErrorException]（统一走 `userError`），
     * 调用方用 `runCatching` 决定是报错还是跳过。
     */
    suspend fun validateEntity(entity: com.myfriend.app.data.local.ApiConfigEntity) {
        val name = entity.name.trim()
        if (name.isBlank()) userError(DomainErrorCode.API_CONFIG_NAME_REQUIRED)
        if (name.length > 60) userError(DomainErrorCode.API_CONFIG_NAME_TOO_LONG)
        val baseUrl = NetworkGuard.normalizeBaseUrl(entity.baseUrl)
        NetworkGuard.parseHttpUrl(baseUrl, entity.allowInsecureHttp)
        if (entity.model.trim().isBlank()) userError(DomainErrorCode.MODEL_ID_REQUIRED)
        if (entity.model.trim().length > 160) userError(DomainErrorCode.MODEL_ID_TOO_LONG)
        if (!registry.capabilities(entity.provider).available) userError(DomainErrorCode.PROVIDER_RESERVED)
        validateHeaders(entity.customHeadersJson)
        validateWebTools(entity)
        ApiConfigRules.paramError(
            temperature = entity.temperature,
            topP = entity.topP,
            maxOutputTokens = entity.maxOutputTokens,
            frequencyPenalty = entity.frequencyPenalty,
            presencePenalty = entity.presencePenalty
        )?.let { userError(it) }
    }

    /**
     * 联网工具地址的校验（设置页保存与备份导入共用）。
     *
     * - 显式填了地址：必须过 [NetworkGuard]（http(s)、明文开关语义）；除自建 [WebToolsProviders.GENERIC]
     *   之外还要过 [WebUrlPolicy]，避免把「第三方搜索服务地址」指到内网/本机；
     * - 留空：除 GENERIC（自建服务没有默认地址）外都合法，运行时用 provider 的默认地址；
     * - 以上检查与 `webToolsEnabled` 无关地执行（留着一个坏地址，等打开开关时才报错更难排查），
     *   只有「GENERIC 必须填地址」这一条仅在联网开关打开时强制。
     */
    private suspend fun validateWebTools(entity: ApiConfigEntity) {
        val provider = WebToolsProviders.normalize(entity.webToolsProvider)
        val webBaseUrl = entity.webToolsBaseUrl.trim()
        if (webBaseUrl.isEmpty()) {
            if (entity.webToolsEnabled && provider == WebToolsProviders.GENERIC) {
                throw ApiException(NetworkErrorKind.WebBaseMissing)
            }
            return
        }
        NetworkGuard.parseHttpUrl(webBaseUrl, entity.allowInsecureHttp)
        if (!WebToolsProviders.allowsPrivateBaseUrl(provider)) {
            WebUrlPolicy.check(webBaseUrl)?.let { throw ApiException(NetworkErrorKind.WebUrlRejected(it)) }
        }
    }

    /**
     * 复制一份配置。
     *
     * 名字后缀由 UI 传进来（[copySuffix] 是当前语言的说法）：这一行会落库、之后原样显示，
     * 数据层不该替用户决定用哪种语言写他的名字。
     */
    suspend fun duplicate(configId: String, copySuffix: String): Result<String> = runCatching {
        val source = apiConfigDao.getById(configId) ?: userError(DomainErrorCode.API_CONFIG_NOT_FOUND)
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = (source.name + copySuffix).take(60),
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
        val resolved = resolve(configId) ?: userError(DomainErrorCode.API_CONFIG_NOT_FOUND)
        val models = registry.adapterFor(resolved.config.provider)
            .fetchModels(resolved.config, resolved.apiKey)
        ConnectionTestResult(modelCount = models.size, models = models)
    }

    suspend fun fetchModels(configId: String): Result<List<String>> = runCatching {
        val resolved = resolve(configId) ?: userError(DomainErrorCode.API_CONFIG_NOT_FOUND)
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
        ConnectionTestResult(modelCount = models.size, models = models)
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
            userError(DomainErrorCode.HEADERS_INVALID_JSON)
        }
        parsed.forEach { (key, value) ->
            if (key.isBlank()) userError(DomainErrorCode.HEADER_NAME_REQUIRED)
            // Host / Content-Length / Transfer-Encoding / Connection / Expect / Proxy-*
            // 由 HTTP 框架管理：Host 在 OkHttp 里只在缺失时才补，用户值会被原样发出，
            // 能把请求路由到同一 IP:端口上的另一个虚拟主机。保存时就拒绝并说清是哪个头。
            ApiConfigRules.reservedHeaderName(key)?.let { reserved ->
                userError(DomainErrorCode.HEADER_RESERVED, arg = reserved)
            }
            if (value !is JsonPrimitive || !value.isString) {
                userError(DomainErrorCode.HEADER_VALUE_NOT_STRING, arg = key)
            }
        }
    }

}
