package com.myfriend.app.data.remote

import com.myfriend.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 设置页「测试连接」的结果（只带展示需要的字段，不带令牌）。 */
data class ServerConnectionTest(
    val serverName: String,
    val version: String,
    val latencyMillis: Long
) {
    /** 给用户看的一行；不含任何密钥。 */
    fun describe(): String = buildString {
        append(serverName.ifBlank { "agentd" })
        if (version.isNotBlank()) append(" · ").append(version)
        append(" · ").append(latencyMillis).append("ms")
    }
}

/** 服务端模式的当前连接状态：用户保存的配置 + 这次运行实际用的 [ServerConfig]。 */
data class ServerConnectionState(
    val credentials: ServerCredentials = ServerCredentials.EMPTY,
    val config: ServerConfig = ServerConfig.DISABLED
) {
    /** 能发请求：开关打开、地址合法（https / 调试期局域网 http）。 */
    val usable: Boolean get() = config.isUsable()

    /** 开关打开但地址被拒绝时的原因；可用或关闭时为 null。 */
    fun rejection(debuggable: Boolean = BuildConfig.DEBUG): ServerConfigRejection? =
        config.rejection(debuggable)
}

/**
 * 服务端模式的**唯一配置入口**：加密存储 + 运行时状态 + [ServerApi] 工厂。
 *
 * 为什么做成单例而不是每次从 SharedPreferences 现读：
 * 1. 同步协程、事件长轮询、聊天发送三条链路都要问「现在是不是服务端模式」，
 *    每次都解密一遍 Keystore 既慢又浪费；
 * 2. 设置页保存之后，正在跑的链路要能立刻看到新地址/开关（[state] 是热流）；
 * 3. `ServerApi` 的 OkHttp 连接池应当复用，不能每发一条消息建一个客户端。
 *
 * 地址/令牌的加密与回退在 [ServerCredentialStore]；这里只管「什么时候重建客户端」。
 */
@Singleton
class ServerModeRepository @Inject constructor(
    private val credentialStore: ServerCredentialStore
) : ServerApiProvider {
    /** 构建是否 debuggable：release 不允许明文 http，单测直接测 [ServerConfig.rejection]。 */
    private val debuggable: Boolean get() = BuildConfig.DEBUG

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<ServerConnectionState> = _state.asStateFlow()

    /** 缓存的客户端：地址/令牌/开关变了才重建（OkHttp 连接池复用）。 */
    @Volatile
    private var cachedApi: ServerApi? = null

    @Volatile
    private var cachedKey: String = ""

    /** 现在能不能走服务端。**同步**方法：数据层很多地方不是 suspend。 */
    override fun isEnabled(): Boolean = _state.value.usable

    /** 这次运行实际使用的配置（不可用时是 [ServerConfig.DISABLED]）。 */
    fun currentConfig(): ServerConfig = _state.value.config

    /** 设置页展示用的脱敏状态。 */
    fun currentCredentials(): ServerCredentials = _state.value.credentials

    /**
     * 拿一个可用的 [ServerApi]；没开/地址不合法时返回 null。
     *
     * 调用方**不要**把它存进字段：设置页一改地址，这里就会换一个新的。
     */
    @Synchronized
    override fun api(): ServerApi? {
        val config = currentConfig()
        if (!config.isUsable(debuggable)) return null
        val key = listOf(config.baseUrl, config.token.orEmpty(), config.enabled).joinToString("\u0000")
        if (key != cachedKey || cachedApi == null) {
            cachedApi = ServerApi(config, debuggable = debuggable)
            cachedKey = key
        }
        return cachedApi
    }

    /**
     * 保存设置。
     *
     * 写盘失败（Keystore 不可用）返回 failure，并且**不改内存状态**：界面上的开关要跟着回滚，
     * 不能出现「看起来开了、重启就没了」。
     */
    @Synchronized
    fun save(credentials: ServerCredentials): Result<ServerConnectionState> = runCatching {
        credentialStore.save(credentials)
        val next = ServerConnectionState(
            credentials = credentials.normalized(),
            config = resolveConfig(credentials.normalized())
        )
        _state.value = next
        resetApiCache()
        next
    }

    /** 只改开关，保留地址/令牌（设置页的 Switch 用它）。 */
    fun setEnabled(enabled: Boolean): Result<ServerConnectionState> =
        save(currentCredentials().copy(enabled = enabled))

    /**
     * 「测试连接」：用**未保存**的输入试一次，成功才返回结果。
     *
     * 测试失败不会写盘；[credentials] 里的开关按「要测就说明想用」处理（enabled=true），
     * 否则用户填完地址先点测试会永远看到 `DISABLED`。
     */
    suspend fun testConnection(
        credentials: ServerCredentials,
        apiFactory: (ServerConfig) -> ServerApi = { ServerApi(it, debuggable = debuggable) }
    ): Result<ServerConnectionTest> = runCatching {
        val config = credentials.copy(enabled = true).toConfig(debuggable)
        val rejection = config.rejection(debuggable)
        if (rejection != null) {
            throw ServerApiException(
                "server configuration rejected: $rejection",
                code = "server_unavailable"
            )
        }
        val api = apiFactory(config)
        val startedAt = System.nanoTime()
        val health = api.health()
        val elapsed = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
        if (!health.healthy) {
            throw ServerApiException("server reported unhealthy", code = "server_unhealthy")
        }
        ServerConnectionTest(
            serverName = health.name.ifBlank { health.status },
            version = health.version,
            latencyMillis = elapsed
        )
    }

    /**
     * 清空连接配置（删除全部数据 / 用户主动断开）。
     *
     * 与 [save] 一样先写盘再改内存；写盘失败返回 failure，界面据此提示而不是假装清掉了。
     */
    @Synchronized
    fun clear(): Result<Unit> = runCatching {
        credentialStore.clear()
        _state.value = ServerConnectionState()
        resetApiCache()
    }

    /** 外部清空数据后重新读盘（删除全部数据 / 登出服务端）。 */
    @Synchronized
    fun reload() {
        _state.value = loadState()
        resetApiCache()
    }

    private fun loadState(): ServerConnectionState {
        val credentials = runCatching { credentialStore.load() }.getOrDefault(ServerCredentials.EMPTY)
        return ServerConnectionState(
            credentials = credentials,
            config = resolveConfig(credentials)
        )
    }

    /**
     * 解析这次运行用的配置：用户保存了地址就以用户为准；没有用户配置时才回落到
     * 系统属性 / BuildConfig（真机联调用的兜底，release 构建里两者都是空）。
     *
     * `resolve` 的默认参数直接读真实 BuildConfig / `System.getProperty`，所以这里不要传 null，
     * 否则「有 BuildConfig 兜底」这条要求就只剩注释了。
     */
    private fun resolveConfig(credentials: ServerCredentials): ServerConfig =
        ServerConfig.resolve(credentials = credentials.takeIf { it.cleanUrl.isNotEmpty() })

    private fun resetApiCache() {
        cachedApi = null
        cachedKey = ""
    }

    private fun ServerCredentials.normalized(): ServerCredentials = copy(
        url = cleanUrl,
        token = cleanToken,
        // 没有地址时开关没有意义：存成关，避免「打开但地址丢了」的歧义。
        enabled = enabled && cleanUrl.isNotEmpty()
    )
}
