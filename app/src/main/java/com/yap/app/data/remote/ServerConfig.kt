package com.yap.app.data.remote

import com.yap.app.BuildConfig
import com.yap.app.domain.CleartextHttpPolicy
import com.yap.app.domain.WebUrlPolicy
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 服务端权威模式（`docs/design/server-authoritative.md` §3）的连接配置。
 *
 * ## 来源与优先级
 *
 * 0.12（M3）起新增**用户在设置页保存的那一份**（[ServerCredentials]，加密落盘）：
 *
 * 1. **用户保存的配置**（[ServerCredentials.cleanUrl] 非空时）：这是用户显式选择，
 *    即使开关关着也不再回落到构建期值——否则「我在设置里关了服务端」会被 debug 构建的
 *    `-PYAP_SERVER_URL` 悄悄覆盖；
 * 2. **系统属性** `YAP_SERVER_URL` / `YAP_SERVER_TOKEN`（非空时覆盖下面的构建期值）；
 * 3. **BuildConfig** `YAP_SERVER_URL` / `YAP_SERVER_TOKEN`（由 Gradle property 透传，默认空）；
 * 4. 都没有 → [baseUrl] 为空、[enabled] = false（App 继续走本地 Room 路径）。
 *
 * 系统属性优先是为了让 JVM 单测 / 真机联调不必重新打包；生产实际用的是用户保存的那一份
 * （release 构建把 2、3 两处都强制成空，见 `app/build.gradle.kts`）。
 *
 * ## 安全边界（与现有发布包策略一致）
 *
 * - `https://` 永远允许；
 * - `http://` 只允许**调试构建**（[debuggable]）且主机是**本机 / 模拟器宿主机 / 局域网**字面量
 *   （`127.0.0.1`、`10.0.2.2`、`192.168.x.x`、`::1`…）：判定直接复用 [WebUrlPolicy.isLocalHost]，
 *   「哪些算内网」只有一份实现，不在这里再抄一遍；
 * - 「构建本身允不允许明文」复用 [CleartextHttpPolicy]：release 的 manifest 是
 *   `usesCleartextTraffic="false"`，所以这里必须**明确拒绝**并给出专门原因，
 *   而不是让 OkHttp 抛一个 `UnknownServiceException` 或者悄悄超时。
 *
 * `http://` + 公网域名在 debug 下也拒绝：服务端权威模式要传的是聊天正文与令牌，
 * 明文发到公网不是「调试便利」而是数据泄露。
 *
 * ## 令牌
 *
 * [token] **绝不进日志、不进 [toString]**：这个类型会被 DI / 日志 / 崩溃上报顺手打印，
 * 一旦 `data class` 的默认 `toString()` 把令牌写出去，之前所有「密钥不出设备」的努力就白费了。
 * 覆盖后的 [toString] 只写 `<redacted>`。
 */
data class ServerConfig(
    val baseUrl: String = "",
    val token: String? = null,
    val enabled: Boolean = false
) {

    /** 配置是否齐到「可以发请求」：[enabled] 且地址合法（[rejection] 为 null）。 */
    fun isUsable(debuggable: Boolean = BuildConfig.DEBUG): Boolean =
        enabled && rejection(debuggable) == null

    /**
     * 地址被拒绝的原因；放行返回 null。
     *
     * 纯函数（不建连接、不做 DNS），release 语义由 [debuggable] 显式传入，
     * 所以 JVM 单测能把 `debuggable = false` 的发布包行为钉死。
     */
    fun rejection(debuggable: Boolean = BuildConfig.DEBUG): ServerConfigRejection? {
        if (!enabled) return ServerConfigRejection.DISABLED
        val raw = baseUrl.trim()
        if (raw.isEmpty()) return ServerConfigRejection.EMPTY
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0) return ServerConfigRejection.UNSUPPORTED_SCHEME
        val scheme = raw.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return ServerConfigRejection.UNSUPPORTED_SCHEME

        val url = raw.toHttpUrlOrNull() ?: return ServerConfigRejection.INVALID_URL
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            // `https://user:pass@host/` 的口令会进 Authorization 之外的日志、也会被 HttpUrl 带进
            // 请求行；服务端权威模式的凭据只有 Bearer token 一种，地址里带 userinfo 直接拒。
            return ServerConfigRejection.INVALID_URL
        }
        if (url.scheme == "https") return null

        // 走到这里一定是 http：先看构建允不允许明文，再看主机是不是本机/局域网。
        val cleartext = CleartextHttpPolicy.rejection(allowInsecureHttp = true, debuggable = debuggable)
        if (cleartext != null) return ServerConfigRejection.CLEARTEXT_HTTP_IN_RELEASE
        if (!WebUrlPolicy.isLocalHost(url.host)) return ServerConfigRejection.CLEARTEXT_HTTP_NOT_LOCAL
        return null
    }

    /**
     * 校验后的地址；不合法时抛 [ServerApiException]（`code = "server_unavailable"`）。
     *
     * 用异常而不是 `null`：调用点（P1b 的仓储层）一旦拿到「能用」的 [ServerApi]，
     * 就不该每次请求都再判一遍空。
     */
    fun validatedUrl(debuggable: Boolean = BuildConfig.DEBUG): HttpUrl {
        val reason = rejection(debuggable)
        if (reason != null) {
            throw ServerApiException(
                "server transport is not usable: $reason",
                code = "server_unavailable"
            )
        }
        return baseUrl.trim().toHttpUrlOrNull()
            ?: throw ServerApiException("server base url is not a valid http url", code = "server_unavailable")
    }

    /** 供 OkHttp 请求用的 `Authorization` 头；没配令牌返回 null。 */
    fun authorizationHeader(): String? {
        val value = token?.trim().orEmpty()
        return if (value.isEmpty()) null else "Bearer $value"
    }

    /**
     * 令牌脱敏的 toString。
     *
     * 覆盖 `data class` 的默认实现是**故意的**：默认实现会把 [token] 原样写进任何一次
     * `"$config"` / 日志 / 测试失败信息里。这里只区分「配了 / 没配」。
     */
    override fun toString(): String =
        "ServerConfig(baseUrl=$baseUrl, enabled=$enabled, token=${if (authorizationHeader() == null) "null" else "<redacted>"})"

    companion object {

        /** 与 [ServerConfig] 的来源约定一一对应：App 里用同名系统属性覆盖构建期值。 */
        const val URL_PROPERTY = "YAP_SERVER_URL"
        const val TOKEN_PROPERTY = "YAP_SERVER_TOKEN"

        /** 未配置任何地址时的默认值：关闭。 */
        val DISABLED: ServerConfig = ServerConfig()

        /**
         * 按「用户保存的配置 → 系统属性 → BuildConfig → 默认关闭」解析配置。
         *
         * 三个来源都可由参数注入（默认读真实加密存储 / BuildConfig / `System.getProperty`），
         * 这样单测不需要改进程级状态就能覆盖优先级与空值语义。
         *
         * [credentials] 为 null 时与旧行为完全一致（P1a 的 54 条测试不需要改）。
         * 用户保存的地址非空时，**开关状态以用户为准**：关着就返回 `enabled = false`，
         * 不会再拿构建期地址把它打开。
         */
        fun resolve(
            credentials: ServerCredentials? = null,
            buildUrl: String? = BuildConfig.YAP_SERVER_URL,
            buildToken: String? = BuildConfig.YAP_SERVER_TOKEN,
            systemProperty: (String) -> String? = { name -> System.getProperty(name) }
        ): ServerConfig {
            if (credentials != null && credentials.cleanUrl.isNotEmpty()) {
                return ServerConfig(
                    baseUrl = credentials.cleanUrl,
                    token = credentials.cleanToken.takeIf { it.isNotEmpty() },
                    enabled = credentials.enabled
                )
            }
            val propertyUrl = systemProperty(URL_PROPERTY)?.trim().orEmpty()
            val url = propertyUrl.ifEmpty { buildUrl?.trim().orEmpty() }
            val propertyToken = systemProperty(TOKEN_PROPERTY)?.trim().orEmpty()
            val token = propertyToken.ifEmpty { buildToken?.trim().orEmpty() }
            return ServerConfig(
                baseUrl = url,
                token = token.ifEmpty { null },
                enabled = url.isNotEmpty()
            )
        }
    }
}

/** [ServerConfig.rejection] 的拒绝原因；顺序就是判定顺序。 */
enum class ServerConfigRejection {
    /** 没有（或显式关掉）配置：App 走本地路径，不发任何请求。 */
    DISABLED,

    /** 打开了但地址是空串。 */
    EMPTY,

    /** 不是 `http(s)://` 形式（`file:` / 相对路径 / `ws://`…）。 */
    UNSUPPORTED_SCHEME,

    /** `http(s)` 语法不合法、带 userinfo、或端口/转义写错了。 */
    INVALID_URL,

    /** 明文 `http://`，但这次构建是 release（manifest 本来也不允许）。 */
    CLEARTEXT_HTTP_IN_RELEASE,

    /** 明文 `http://` 指向的不是本机/模拟器宿主机/局域网地址。 */
    CLEARTEXT_HTTP_NOT_LOCAL
}
