package com.yap.app.data.remote

import com.yap.app.data.local.PersonaDao
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.repo.ApiConfigRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「把本机已经配好的模型配置推给服务端」的策略（0.13，`agentd/LlmConfigRoutes.kt`）。
 *
 * ## 为什么必须先判回环
 *
 * 服务端只在**监听回环地址**时接受 `POST /v1/llm-config`（远程部署一律 403）。
 * 但客户端不能等 403 才知道：远程地址发一次，就等于把用户的 API Key 明文发到了公网服务
 * （即便是 https，也把密钥交给了另一台机器）。所以判定放在**构造请求之前**：
 *
 * - `127.0.0.1`（整个 127/8）、`localhost`、`::1`、`10.0.2.2`（Android 模拟器的宿主机回环）→ 推送；
 * - 其它任何地址（含 `192.168.x.x` 局域网）→ 不推。局域网也是「另一台机器」，不是本机。
 *
 * 这条规则是纯函数、单独放一个 object：它同时被生产代码与 JVM 单测使用，
 * 「什么算本机」只有一份实现。
 */
object ServerLlmConfigPushPolicy {

    /** 明确按回环处理的主机名/地址（小写、去掉 IPv6 方括号后比较）。 */
    private val LOOPBACK_HOSTS = setOf(
        "127.0.0.1",
        "localhost",
        "::1",
        "0:0:0:0:0:0:0:1",
        "10.0.2.2"
    )

    fun isLoopbackHost(host: String?): Boolean {
        val cleaned = host?.trim()?.trim('[', ']')?.lowercase().orEmpty()
        if (cleaned.isEmpty()) return false
        if (cleaned in LOOPBACK_HOSTS) return true
        // 127.0.0.0/8 整段都是回环：127.0.0.2 也是本机，没必要只认 .0.1。
        return cleaned.startsWith("127.")
    }

    /**
     * 这次该不该推：开关打开 + 地址可解析 + 主机是回环。
     *
     * 不做 DNS：`http://localhost` 的 `localhost` 无论解析到哪个 IP，语义都是「本机」；
     * 反过来，一个普通域名即使解析到 127.0.0.1，也不能凭一次 DNS 结果就把 Key 发出去。
     */
    fun shouldPush(baseUrl: String?, enabled: Boolean): Boolean {
        if (!enabled) return false
        val url = baseUrl?.trim()?.toHttpUrlOrNull() ?: return false
        return isLoopbackHost(url.host)
    }
}

/** 本机解析到的一组模型配置（推送请求体的领域视图；不含展示信息）。 */
data class LocalLlmConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val provider: String
)

/**
 * 推送的编排（纯 JVM：解析配置与发请求都注入，单测不需要 Android/Room）。
 *
 * 语义：
 * - 不是回环 / 没开关 / 本机没有可用配置 → [Outcome.pushed] = false，什么都不做；
 * - 同一份配置（地址/模型/provider/Key 都没变）只推一次（[lastFingerprint]）；
 *   失败**不记指纹**，下一次连接成功会再试；
 * - 失败只记日志，绝不向上抛：配置推送是「让本机服务端省一次手工配置」的便利，
 *   不是聊天链路的一部分。
 */
class ServerLlmConfigPush(
    private val resolve: suspend () -> LocalLlmConfig?,
    private val send: suspend (LocalLlmConfig) -> Unit,
    private val log: (String) -> Unit = {}
) {

    data class Outcome(val pushed: Boolean, val reason: String)

    @Volatile
    private var lastFingerprint: String? = null

    /** 运行时/开关状态机/设置页可能同时触发：串行化，避免同一份配置并发推两次。 */
    private val lock = Mutex()

    suspend fun pushIfLocal(baseUrl: String?, enabled: Boolean): Outcome = lock.withLock {
        pushLocked(baseUrl, enabled)
    }

    private suspend fun pushLocked(baseUrl: String?, enabled: Boolean): Outcome {
        if (!ServerLlmConfigPushPolicy.shouldPush(baseUrl, enabled)) {
            return Outcome(pushed = false, reason = "not_local")
        }
        val config = runCatching { resolve() }.getOrNull()
            ?: return Outcome(pushed = false, reason = "no_local_config")
        val fingerprint = fingerprintOf(baseUrl, config)
        if (fingerprint == lastFingerprint) {
            return Outcome(pushed = false, reason = "unchanged")
        }
        val result = runCatching { send(config) }
        if (result.isSuccess) {
            lastFingerprint = fingerprint
            log("llm config pushed (provider=${config.provider})")
            return Outcome(pushed = true, reason = "pushed")
        }
        val error = result.exceptionOrNull()
        // 只记类型与 code：异常文案里不该出现 Key（ServerApiException 本身也不带请求体）。
        val code = (error as? ServerApiException)?.code ?: error?.javaClass?.simpleName ?: "unknown"
        log("llm config push failed: $code")
        return Outcome(pushed = false, reason = "failed:$code")
    }

    /** 指纹只含「配置是否变了」所需的字段；Key 用 hashCode，不在内存里留第二份明文。 */
    private fun fingerprintOf(baseUrl: String?, config: LocalLlmConfig): String =
        listOf(
            baseUrl.orEmpty().trimEnd('/'),
            config.baseUrl,
            config.model,
            config.provider,
            config.apiKey.hashCode().toString()
        ).joinToString("\u0000")
}

/**
 * 生产实现：解析本机「聊天实际会用的」配置，连接成功后推给本机服务端。
 *
 * 选配置的顺序与聊天一致：正在聊的人格绑定配置 → 其它人格绑定的配置 → 最新的可用配置。
 * 没有任何可用配置（没填 Key / 地址为空）时安静跳过——服务端模式本来就不需要本机 Key。
 */
@Singleton
class ServerLlmConfigPusher @Inject constructor(
    private val mode: ServerModeRepository,
    private val apiConfigRepository: ApiConfigRepository,
    private val personaDao: PersonaDao,
    private val settingsRepository: SettingsRepository
) {

    private val engine = ServerLlmConfigPush(
        resolve = { resolveLocal() },
        send = { config ->
            val api = mode.api() ?: throw ServerApiException(
                "server mode is not available",
                code = "server_unavailable"
            )
            api.pushLlmConfig(
                ServerLlmConfigWrite(
                    baseUrl = config.baseUrl,
                    model = config.model,
                    apiKey = config.apiKey,
                    provider = config.provider
                )
            )
        },
        log = { message -> warn(message) }
    )

    /**
     * 开启服务端模式 / 连接成功 / App 每次进入前台时调一次（幂等，按配置指纹去重）。
     *
     * 调用方**不需要** await 它的结果、也不需要处理失败：返回值只给测试与日志用。
     */
    suspend fun pushIfLocal(): ServerLlmConfigPush.Outcome =
        engine.pushIfLocal(mode.currentConfig().baseUrl, mode.isEnabled())

    /**
     * 解析本机可推送的配置。
     *
     * 只在 `resolve` 被调用时解密 Key（回环判定在它之前），而且失败一律返回 null：
     * Keystore 解不出来时不该把一次「配置推送」变成设置页的错误弹窗。
     */
    private suspend fun resolveLocal(): LocalLlmConfig? {
        val personas = runCatching { personaDao.getAll() }.getOrDefault(emptyList())
        val activePersonaId = runCatching { settingsRepository.settings.first().activePersonaId }.getOrNull()
        val configs = runCatching { apiConfigRepository.getAll() }.getOrDefault(emptyList())
        if (configs.isEmpty()) return null
        val byId = configs.associateBy { it.id }
        val preferred = buildList {
            personas.firstOrNull { it.id == activePersonaId }?.defaultApiConfigId?.let(::add)
            addAll(personas.mapNotNull { it.defaultApiConfigId })
        }.distinct().mapNotNull { byId[it] }
        val ordered = preferred + configs.filter { candidate -> preferred.none { it.id == candidate.id } }
        val chosen = ordered.firstOrNull { config ->
            config.baseUrl.isNotBlank() && config.model.isNotBlank() && apiConfigRepository.hasApiKey(config)
        } ?: return null
        val resolved = runCatching { apiConfigRepository.resolve(chosen.id) }.getOrNull() ?: return null
        val apiKey = resolved.apiKey?.trim().orEmpty()
        if (apiKey.isEmpty()) return null
        return LocalLlmConfig(
            baseUrl = chosen.baseUrl.trim(),
            model = chosen.model.trim(),
            apiKey = apiKey,
            provider = chosen.provider.name.uppercase()
        )
    }

    private fun warn(message: String) {
        // 纯 JVM 单测里 android.util.Log 是 stub（调用会抛「not mocked」）：这里必须 runCatching。
        runCatching { android.util.Log.d("ServerLlmConfig", message) }
    }
}
