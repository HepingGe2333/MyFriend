package com.myfriend.app.domain

/**
 * API 配置里「数值参数」与「自定义 Headers」的校验规则。
 *
 * 抽成纯函数的原因有两个：
 * 1. `ApiConfigRepository.save`（用户手填）和备份导入（外部 JSON）必须走**同一套**规则，
 *    否则「导入一份备份」就成了绕过校验的后门（审计 P1-0 的另一半）；
 * 2. 单测不需要构造 Room / Hilt / Context。
 */
object ApiConfigRules {

    /**
     * 不允许用户自定义的请求头。
     *
     * - `Host`：OkHttp 只在缺失时才补，用户值会被**原样发出**——能把请求路由到同一
     *   IP:端口上的另一个虚拟主机（反代/网关场景下就是越权）。而且它不在 TLS
     *   证书校验的覆盖范围里，属于「静默改变请求语义」的那类头；
     * - `Content-Length` / `Transfer-Encoding`：由框架算，用户写只会与真实 body 冲突；
     * - `Connection` / `Expect`：逐跳头，由 OkHttp 自己管；
     * - `Proxy-*`：代理语义，属于「让请求经过谁的机器」的决定，不该藏在配置里。
     *
     * 其余头（`X-*`、`HTTP-Referer`、`OpenAI-Organization` 之类）照常放行。
     */
    private val RESERVED_HEADERS = setOf(
        "host",
        "content-length",
        "transfer-encoding",
        "connection",
        "expect"
    )

    /** 命中的保留头名字（原样返回，方便错误文案里显示用户写的那一份）；没命中返回 null。 */
    fun reservedHeaderName(name: String): String? {
        val normalized = name.trim().lowercase()
        if (normalized.isEmpty()) return null
        val reserved = normalized in RESERVED_HEADERS || normalized.startsWith("proxy-")
        return if (reserved) name.trim() else null
    }

    fun isReservedHeader(name: String): Boolean = reservedHeaderName(name) != null

    /**
     * 数值参数越界时返回对应的错误码；全部合法返回 null。
     *
     * 与 `ApiConfigRepository.save` 原来的区间一致（备份导入曾经绕过这些区间，
     * 于是 `temperature = 1e9` 也能写进库）。
     */
    fun paramError(
        temperature: Double?,
        topP: Double?,
        maxOutputTokens: Int?,
        frequencyPenalty: Double?,
        presencePenalty: Double?
    ): DomainErrorCode? = when {
        temperature != null && temperature !in 0.0..2.0 -> DomainErrorCode.TEMPERATURE_RANGE
        topP != null && topP !in 0.0..1.0 -> DomainErrorCode.TOP_P_RANGE
        maxOutputTokens != null && maxOutputTokens !in 1..1_000_000 ->
            DomainErrorCode.MAX_OUTPUT_TOKENS_RANGE
        frequencyPenalty != null && frequencyPenalty !in -2.0..2.0 ->
            DomainErrorCode.FREQUENCY_PENALTY_RANGE
        presencePenalty != null && presencePenalty !in -2.0..2.0 ->
            DomainErrorCode.PRESENCE_PENALTY_RANGE
        else -> null
    }
}
