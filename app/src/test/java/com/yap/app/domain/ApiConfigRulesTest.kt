package com.yap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API 配置的数值参数与自定义 Header 规则（安全审计 P2-1）。
 *
 * 重点是保留头：OkHttp 只在 `Host` 缺失时才补，用户值会被原样发出——
 * 能把请求路由到同一 IP:端口上的另一个虚拟主机。保存时就拒绝，并说清是哪个头。
 */
class ApiConfigRulesTest {

    @Test
    fun `由 HTTP 框架管理的头一律拒绝`() {
        listOf("Host", "host", "HOST", "Content-Length", "Transfer-Encoding", "Connection", "Expect")
            .forEach { name ->
                assertTrue("$name 应当被拦", ApiConfigRules.isReservedHeader(name))
            }
    }

    @Test
    fun `Proxy 前缀的头也拒绝`() {
        listOf("Proxy-Authorization", "proxy-connection", "PROXY-FOO").forEach { name ->
            assertTrue("$name 应当被拦", ApiConfigRules.isReservedHeader(name))
        }
    }

    @Test
    fun `正常自定义头保留`() {
        listOf(
            "X-Custom",
            "HTTP-Referer",
            "X-Title",
            "OpenAI-Organization",
            "Authorization"
        ).forEach { name ->
            assertFalse("$name 不该被拦", ApiConfigRules.isReservedHeader(name))
        }
    }

    @Test
    fun `保留头会原样返回名字 方便文案里显示`() {
        assertEquals("Host", ApiConfigRules.reservedHeaderName("Host"))
        assertEquals("proxy-authorization", ApiConfigRules.reservedHeaderName("  proxy-authorization  "))
        assertNull(ApiConfigRules.reservedHeaderName("X-OK"))
        assertNull(ApiConfigRules.reservedHeaderName(""))
    }

    @Test
    fun `数值参数区间与保存时一致`() {
        assertNull(ApiConfigRules.paramError(null, null, null, null, null))
        assertNull(ApiConfigRules.paramError(2.0, 1.0, 1_000_000, -2.0, 2.0))
        assertEquals(DomainErrorCode.TEMPERATURE_RANGE, ApiConfigRules.paramError(1e9, null, null, null, null))
        assertEquals(DomainErrorCode.TEMPERATURE_RANGE, ApiConfigRules.paramError(-0.1, null, null, null, null))
        assertEquals(DomainErrorCode.TOP_P_RANGE, ApiConfigRules.paramError(null, 1.5, null, null, null))
        assertEquals(DomainErrorCode.MAX_OUTPUT_TOKENS_RANGE, ApiConfigRules.paramError(null, null, 0, null, null))
        assertEquals(
            DomainErrorCode.FREQUENCY_PENALTY_RANGE,
            ApiConfigRules.paramError(null, null, null, 3.0, null)
        )
        assertEquals(
            DomainErrorCode.PRESENCE_PENALTY_RANGE,
            ApiConfigRules.paramError(null, null, null, null, -3.0)
        )
    }
}
