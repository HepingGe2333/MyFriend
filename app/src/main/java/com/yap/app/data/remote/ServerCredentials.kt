package com.yap.app.data.remote

import com.yap.app.BuildConfig

/**
 * 用户在设置页里填的服务端连接信息（服务端权威模式 / M3–M4）。
 *
 * 与 [ServerConfig] 的分工：
 * - [ServerCredentials] 是**要存下来的东西**（地址、令牌、开关），由
 *   [ServerCredentialStore] 加密落盘；
 * - [ServerConfig] 是**这次运行实际用哪一份**（还要考虑系统属性 / BuildConfig 兜底），
 *   由 [toConfig] 产出。
 *
 * ## 令牌
 *
 * [token] 绝不进 `toString`（data class 会自动生成带字段值的 toString，这里显式覆盖）。
 * 界面上只显示 [maskedToken]：末 4 位 + 圆点，够认出「填的是哪一个」，又不足以拿去用。
 *
 * ## 地址
 *
 * 这里只做 trim；`https` / 调试期局域网 `http` 的判定统一在 [ServerConfig.rejection]，
 * 不要在第二个地方再写一套「什么算内网」。
 */
data class ServerCredentials(
    val url: String = "",
    val token: String = "",
    val enabled: Boolean = false
) {

    /** 去掉首尾空白后的地址。 */
    val cleanUrl: String get() = url.trim()

    /** 去掉首尾空白后的令牌；空串 = 没配。 */
    val cleanToken: String get() = token.trim()

    /** 这个开关有没有实际意义：打开了且填了地址。 */
    val isConfigured: Boolean get() = cleanUrl.isNotEmpty()

    /**
     * 界面展示用的脱敏令牌。
     *
     * - 没填 → 空串（界面显示「未设置」）；
     * - ≤ 4 位 → 全圆点（长度本身也不该泄露）；
     * - 否则 → `••••` + 末 4 位。
     */
    fun maskedToken(): String {
        val value = cleanToken
        return when {
            value.isEmpty() -> ""
            value.length <= MASK_VISIBLE_TAIL -> "•".repeat(8)
            else -> "••••" + value.takeLast(MASK_VISIBLE_TAIL)
        }
    }

    /**
     * 转成这次运行要用的 [ServerConfig]。
     *
     * [BuildConfig.DEBUG] 显式传进来（默认读构建值），单测才能把 release 语义钉死。
     */
    fun toConfig(debuggable: Boolean = BuildConfig.DEBUG): ServerConfig = ServerConfig(
        baseUrl = cleanUrl,
        token = cleanToken.takeIf { it.isNotEmpty() },
        enabled = enabled
    )

    /** 日志/异常里安全的表示：地址可以出现，令牌只写「有没有」。 */
    override fun toString(): String =
        "ServerCredentials(url=$cleanUrl, enabled=$enabled, hasToken=${cleanToken.isNotEmpty()})"

    companion object {
        /** 脱敏时保留的末位数量：4 位不足以在泄露后继续使用，又能和别的令牌区分开。 */
        const val MASK_VISIBLE_TAIL = 4

        val EMPTY = ServerCredentials()
    }
}
