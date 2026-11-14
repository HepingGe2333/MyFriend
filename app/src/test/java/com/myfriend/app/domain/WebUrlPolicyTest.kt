package com.myfriend.app.domain

import com.myfriend.app.domain.WebUrlPolicy.Rejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `web_fetch` 的 URL 校验（安全审计 P2-11）。
 *
 * iOS 早就有 `validatePageUrl`，Android 只有判空；这里把两端的语义对齐成
 * 「只放行 http/https + 必须有 host + 不接受内网字面量」。
 */
class WebUrlPolicyTest {

    @Test
    fun `正常网页放行`() {
        assertNull(WebUrlPolicy.check("https://example.com"))
        assertNull(WebUrlPolicy.check("https://example.com/a/b?c=1#d"))
        assertNull(WebUrlPolicy.check("http://example.com:8080/path"))
        assertNull(WebUrlPolicy.check("  https://example.com/x  "))
        assertNull(WebUrlPolicy.check("https://user:pass@example.com/x"))
        assertNull("公网 IPv4 放行", WebUrlPolicy.check("https://8.8.8.8/dns"))
    }

    @Test
    fun `非法 scheme 一律拒绝`() {
        listOf(
            "file:///data/data/com.myfriend.app/databases/myfriend.db",
            "javascript:alert(1)",
            "data:text/html;base64,AAAA",
            "intent://scan/#Intent;scheme=zxing;end",
            "ftp://example.com/x",
            "//example.com/x",
            "example.com/x",
            "mailto:someone@example.com"
        ).forEach { url ->
            assertEquals("$url 应该被拒绝", Rejection.UNSUPPORTED_SCHEME, WebUrlPolicy.check(url))
        }
    }

    @Test
    fun `空串走 EMPTY`() {
        assertEquals(Rejection.EMPTY, WebUrlPolicy.check(null))
        assertEquals(Rejection.EMPTY, WebUrlPolicy.check(""))
        assertEquals(Rejection.EMPTY, WebUrlPolicy.check("   "))
    }

    @Test
    fun `没有 host 拒绝`() {
        assertEquals(Rejection.MISSING_HOST, WebUrlPolicy.check("https:///path"))
        assertEquals(Rejection.MISSING_HOST, WebUrlPolicy.check("https://"))
    }

    @Test
    fun `内网与本机字面量拒绝`() {
        listOf(
            "http://127.0.0.1:8080/admin",
            "http://127.1.2.3/",
            "http://localhost/",
            "http://LOCALHOST:3000/",
            "https://app.localhost/",
            "http://10.0.0.1/",
            "http://10.1.2.3:8080/",
            "http://192.168.1.1/",
            "http://172.16.0.1/",
            "http://172.31.255.255/",
            "http://169.254.169.254/latest/meta-data",
            "http://0.0.0.0/",
            "http://[::1]:8080/",
            "http://[::1]/",
            "https://user@10.0.0.1/x"
        ).forEach { url ->
            assertEquals("$url 是内网地址", Rejection.LOCAL_HOST, WebUrlPolicy.check(url))
        }
    }

    @Test
    fun `172 段只拒绝 16 到 31`() {
        assertFalse(WebUrlPolicy.isLocalHost("172.15.0.1"))
        assertFalse(WebUrlPolicy.isLocalHost("172.32.0.1"))
        assertTrue(WebUrlPolicy.isLocalHost("172.20.0.1"))
    }

    @Test
    fun `公网域名不被误判`() {
        assertFalse(WebUrlPolicy.isLocalHost("example.com"))
        assertFalse(WebUrlPolicy.isLocalHost("10.example.com"))
        assertFalse(WebUrlPolicy.isLocalHost("my192.168.example.org"))
    }
}
