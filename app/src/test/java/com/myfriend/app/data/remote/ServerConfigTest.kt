package com.myfriend.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ServerConfig] 的纯 JVM 单测：来源优先级 + 明文边界 + 令牌脱敏。
 *
 * `debuggable` 全部显式传参：单测跑在 debug 变体下（`BuildConfig.DEBUG == true`），
 * 想验 release 语义只能靠这个参数，而不是改进程状态。
 */
class ServerConfigTest {

    // ------------------------------------------------------------------ 来源与优先级

    @Test
    fun `没配置任何来源时默认关闭`() {
        val config = ServerConfig.resolve(buildUrl = null, buildToken = null, systemProperty = { null })

        assertEquals("", config.baseUrl)
        assertNull(config.token)
        assertFalse(config.enabled)
        assertFalse("关闭时不该被认为可用", config.isUsable(debuggable = true))
        assertEquals(ServerConfigRejection.DISABLED, config.rejection(debuggable = true))
        assertTrue("未配置令牌时明确写 null：${config}", config.toString().endsWith("token=null)"))
    }

    @Test
    fun `系统属性优先于构建期值`() {
        val config = ServerConfig.resolve(
            buildUrl = "https://build.example:8788",
            buildToken = "build-token",
            systemProperty = { name ->
                when (name) {
                    ServerConfig.URL_PROPERTY -> "https://runtime.example:8788"
                    ServerConfig.TOKEN_PROPERTY -> "runtime-token"
                    else -> null
                }
            }
        )

        assertEquals("https://runtime.example:8788", config.baseUrl)
        assertEquals("Bearer runtime-token", config.authorizationHeader())
        assertTrue(config.enabled)
    }

    @Test
    fun `系统属性为空串时回落到构建期值`() {
        val config = ServerConfig.resolve(
            buildUrl = "  https://build.example:8788  ",
            buildToken = "  ",
            systemProperty = { name -> if (name == ServerConfig.URL_PROPERTY) "   " else null }
        )

        assertEquals("地址与令牌都要 trim", "https://build.example:8788", config.baseUrl)
        assertNull("空白令牌等于没配", config.token)
        assertTrue(config.enabled)
    }

    @Test
    fun `只有令牌时地址仍来自构建期且不把空地址当可用`() {
        val config = ServerConfig.resolve(
            buildUrl = null,
            buildToken = "build-token",
            systemProperty = { name -> if (name == ServerConfig.TOKEN_PROPERTY) "runtime-token" else null }
        )

        assertEquals("", config.baseUrl)
        assertFalse("没有地址就是关闭", config.enabled)
        assertEquals("Bearer runtime-token", config.authorizationHeader())
    }

    // ------------------------------------------------------------------ https / http 边界

    @Test
    fun `release 构建也放行 https`() {
        val config = ServerConfig(baseUrl = "https://server.example:8788", enabled = true)

        assertNull(config.rejection(debuggable = false))
        assertTrue(config.isUsable(debuggable = false))
        assertEquals("https", config.validatedUrl(debuggable = false).scheme)
    }

    @Test
    fun `调试构建允许本机与局域网的明文`() {
        val allowed = listOf(
            "http://127.0.0.1:8788",
            "http://10.0.2.2:8788",
            "http://192.168.1.8:8788",
            "http://10.1.2.3:8788",
            "http://172.20.0.5:8788",
            "http://[::1]:8788",
            "http://localhost:8788",
            "http://127.0.0.1:8788/v1"
        )
        allowed.forEach { baseUrl ->
            val config = ServerConfig(baseUrl = baseUrl, enabled = true)
            assertNull("$baseUrl 在调试构建应放行", config.rejection(debuggable = true))
        }
    }

    @Test
    fun `release 构建连本机明文也拒绝`() {
        val config = ServerConfig(baseUrl = "http://10.0.2.2:8788", enabled = true)

        assertEquals(
            "发布包的 manifest 是 usesCleartextTraffic=false，必须给专门原因",
            ServerConfigRejection.CLEARTEXT_HTTP_IN_RELEASE,
            config.rejection(debuggable = false)
        )
        assertThrows(ServerApiException::class.java) { config.validatedUrl(debuggable = false) }
    }

    @Test
    fun `调试构建也拒绝公网地址的明文`() {
        listOf("http://example.com:8788", "http://8.8.8.8:8788", "http://server.example").forEach { baseUrl ->
            val config = ServerConfig(baseUrl = baseUrl, enabled = true)
            assertEquals(
                "$baseUrl 是明文公网，聊天正文与令牌不该这么发",
                ServerConfigRejection.CLEARTEXT_HTTP_NOT_LOCAL,
                config.rejection(debuggable = true)
            )
        }
    }

    @Test
    fun `地址形状不对按原因分类`() {
        assertEquals(
            ServerConfigRejection.EMPTY,
            ServerConfig(baseUrl = "   ", enabled = true).rejection(debuggable = true)
        )
        assertEquals(
            "没有 scheme 的相对路径不是服务端地址",
            ServerConfigRejection.UNSUPPORTED_SCHEME,
            ServerConfig(baseUrl = "10.0.2.2:8788", enabled = true).rejection(debuggable = true)
        )
        assertEquals(
            ServerConfigRejection.UNSUPPORTED_SCHEME,
            ServerConfig(baseUrl = "ftp://127.0.0.1:8788", enabled = true).rejection(debuggable = true)
        )
        assertEquals(
            ServerConfigRejection.INVALID_URL,
            ServerConfig(baseUrl = "http://", enabled = true).rejection(debuggable = true)
        )
        assertEquals(
            "地址里带 userinfo 会把口令带进请求行/日志，直接拒",
            ServerConfigRejection.INVALID_URL,
            ServerConfig(baseUrl = "https://user:pass@server.example:8788", enabled = true)
                .rejection(debuggable = true)
        )
    }

    // ------------------------------------------------------------------ 令牌 / 地址归一化

    @Test
    fun `令牌只出现在 Bearer 头且 toString 里被脱敏`() {
        val config = ServerConfig(
            baseUrl = "https://server.example:8788",
            token = "  s3cret-token-value  ",
            enabled = true
        )

        assertEquals("Bearer s3cret-token-value", config.authorizationHeader())
        val rendered = config.toString()
        assertFalse("toString 绝不能带令牌：$rendered", rendered.contains("s3cret-token-value"))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun `没有令牌时 Authorization 为空且 toString 明确写 null`() {
        val config = ServerConfig(baseUrl = "https://server.example:8788", token = " ", enabled = true)

        assertNull(config.authorizationHeader())
        assertTrue(config.toString().contains("token=null"))
    }

    @Test
    fun `validatedUrl 对不可用配置抛出带 code 的异常`() {
        val error = assertThrows(ServerApiException::class.java) {
            ServerConfig().validatedUrl(debuggable = true)
        }
        assertEquals("server_unavailable", error.code)
        assertNull(error.status)
    }

    @Test
    fun `基础地址归一化去掉尾部 v1 并保留反向代理前缀`() {
        assertEquals(
            "https://server.example:8788",
            ServerApi.normalizedBaseUrl("https://server.example:8788/v1")
        )
        assertEquals(
            "https://server.example/mf",
            ServerApi.normalizedBaseUrl("https://server.example/mf/v1/")
        )
        assertEquals(
            "https://server.example",
            ServerApi.normalizedBaseUrl("https://server.example")
        )
        assertNull("不是 http url 时不猜", ServerApi.normalizedBaseUrl("not a url"))
    }

    @Test
    fun `isBaseUrlUsable 与 rejection 口径一致`() {
        assertTrue(ServerApi.isBaseUrlUsable("https://server.example:8788", debuggable = false))
        assertTrue(ServerApi.isBaseUrlUsable("http://10.0.2.2:8788", debuggable = true))
        assertFalse(ServerApi.isBaseUrlUsable("http://10.0.2.2:8788", debuggable = false))
        assertFalse(ServerApi.isBaseUrlUsable("http://example.com", debuggable = true))
        assertFalse(ServerApi.isBaseUrlUsable("", debuggable = true))
    }
}
