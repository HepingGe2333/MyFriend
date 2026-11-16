package com.yap.app.network

import com.yap.app.domain.NetworkErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkGuardTest {
    @Test
    fun rejectsHttpByDefault() {
        assertThrows(ApiException::class.java) {
            NetworkGuard.parseHttpUrl("http://example.com/v1", allowInsecureHttp = false)
        }
    }

    /**
     * 安全审计 P1-2：release 构建在系统层就禁止明文（主 manifest 是 false），
     * 用户把「允许不安全 HTTP」打开也必须被 App 闸门拒绝，并且给出专门原因。
     */
    @Test
    fun rejectsHttpInReleaseEvenWhenExplicitlyAllowed() {
        val error = assertThrows(ApiException::class.java) {
            NetworkGuard.parseHttpUrl(
                "http://192.168.1.8:8787/v1",
                allowInsecureHttp = true,
                debuggable = false
            )
        }
        assertEquals(
            "要区分「用户没同意」与「发布版不支持」，文案不同",
            NetworkErrorKind.CleartextDisabled,
            error.kind
        )
    }

    @Test
    fun debugBuildAllowsHttpWhenExplicitlyAllowed() {
        val url = NetworkGuard.parseHttpUrl(
            "http://192.168.1.8:8787/v1",
            allowInsecureHttp = true,
            debuggable = true
        )
        assertEquals("http", url.scheme)
    }

    @Test
    fun httpsWorksInRelease() {
        val url = NetworkGuard.parseHttpUrl("https://api.example.com/v1", allowInsecureHttp = false, debuggable = false)
        assertEquals("https", url.scheme)
    }

    @Test
    fun acceptsHttpWhenExplicitlyAllowed() {
        val url = NetworkGuard.parseHttpUrl("http://192.168.1.8:8787/v1", allowInsecureHttp = true)
        assertEquals("http", url.scheme)
    }

    @Test
    fun appendsV1WhenMissing() {
        val endpoint = NetworkGuard.endpoint("https://api.example.com", "chat/completions")
        assertEquals("https://api.example.com/v1/chat/completions", endpoint.toString())
    }

    @Test
    fun preservesExistingV1() {
        val endpoint = NetworkGuard.endpoint("https://api.example.com/v1/", "models")
        assertEquals("https://api.example.com/v1/models", endpoint.toString())
    }
}
