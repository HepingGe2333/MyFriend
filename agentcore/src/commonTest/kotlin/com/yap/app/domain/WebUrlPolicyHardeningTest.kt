package com.yap.app.domain

import com.yap.app.domain.WebUrlPolicy.Rejection
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * 0.11.1 安全审计 P2-5 的加固回归测试。
 *
 * 表里的 URL 全部来自审计报告的实测绕过表（修复前是 ALLOW），这里逐条钉死成「拒绝」。
 * 这份表也是两端对齐的依据：iOS 的 `validatePageUrl` 要独立实现等价校验，核对时按同一批用例走。
 *
 * 除了「拒绝绕过」，还钉了两件事：
 * - 正常域名 / 公网 IP / 公网 IPv6 必须继续放行（不能为了安全把功能打死）；
 * - 拒绝原因（`Rejection`）保持 4 个值不变：两端 UI 的 `when` 是穷尽枚举，新增值会同时改两端。
 */
class WebUrlPolicyHardeningTest {

    // ------------------------------------------------------------------ 审计绕过表

    @Test
    fun `audit bypass table is rejected`() {
        val table: List<Pair<String, Rejection>> = listOf(
            // authority 解析差异：WHATWG 把 `\` 当 `/`，真实 host 是 127.0.0.1:8080
            "http://127.0.0.1:8080\\@example.com/" to Rejection.MISSING_HOST,
            // FQDN 尾点
            "https://localhost./" to Rejection.LOCAL_HOST,
            "https://LOCALHOST./admin" to Rejection.LOCAL_HOST,
            "http://127.0.0.1./" to Rejection.LOCAL_HOST,
            // 数字 IP 的等价写法
            "http://2130706433/" to Rejection.LOCAL_HOST,
            "http://0x7f000001/" to Rejection.LOCAL_HOST,
            "http://0177.0.0.1/" to Rejection.LOCAL_HOST,
            "http://127.1/" to Rejection.LOCAL_HOST,
            "http://0x7f.0.0.1/" to Rejection.LOCAL_HOST,
            "http://192.168.257/" to Rejection.LOCAL_HOST,
            "http://0/" to Rejection.LOCAL_HOST,
            // IPv4-mapped IPv6
            "http://[::ffff:7f00:1]/" to Rejection.LOCAL_HOST,
            "http://[::ffff:127.0.0.1]/" to Rejection.LOCAL_HOST,
            "http://[::1]/" to Rejection.LOCAL_HOST,
            "http://[::]/" to Rejection.LOCAL_HOST,
            "http://[fe80::1]/" to Rejection.LOCAL_HOST,
            "http://[fc00::1]/" to Rejection.LOCAL_HOST,
            "http://[64:ff9b::7f00:1]/" to Rejection.LOCAL_HOST,
            // userinfo（口令会被原样发给第三方）
            "http://user:pass@example.com/" to Rejection.MISSING_HOST,
            "https://user@example.com/x" to Rejection.MISSING_HOST,
            // 通配 DNS 的 IP 前缀
            "http://127.0.0.1.nip.io/" to Rejection.LOCAL_HOST,
            "http://2130706433.nip.io/" to Rejection.LOCAL_HOST,
            // 非法 host 字符 / 百分号编码 / 尾随控制字符
            "http://exa mple.com/" to Rejection.MISSING_HOST,
            "http://example.com\t/" to Rejection.MISSING_HOST,
            "http://%31%32%37.0.0.1/" to Rejection.MISSING_HOST,
            "http://example.com\\@127.0.0.1/" to Rejection.MISSING_HOST,
            "http://exa%20mple.com/" to Rejection.MISSING_HOST
        )
        for ((url, expected) in table) {
            assertEquals(expected, WebUrlPolicy.check(url), "$url 应该被拒绝（$expected）")
        }
    }

    @Test
    fun `userinfo is rejected even when the host is public`() {
        assertEquals(Rejection.MISSING_HOST, WebUrlPolicy.check("https://user:pass@example.com/x"))
        assertEquals(Rejection.MISSING_HOST, WebUrlPolicy.check("https://user@api.deepseek.com/x"))
        // 内网 host 优先报 LOCAL_HOST（Android 既有文案/测试按这个顺序）。
        assertEquals(Rejection.LOCAL_HOST, WebUrlPolicy.check("https://user@10.0.0.1/x"))
    }

    // ------------------------------------------------------------------ 正常用例

    @Test
    fun `normal public urls still pass`() {
        for (url in listOf(
            "https://example.com",
            "https://example.com/a/b?c=1#d",
            "http://example.com:8080/path",
            "  https://example.com/x  ",
            "https://example.com./",
            "https://api.deepseek.com/v1/chat/completions",
            "https://8.8.8.8/dns",
            "http://1.1.1.1/",
            "http://[2001:4860:4860::8888]/",
            "http://[::ffff:8.8.8.8]/",
            "https://sub-domain.example.co.uk/"
        )) {
            assertNull(WebUrlPolicy.check(url), "$url 应该放行")
        }
    }

    @Test
    fun `non http schemes stay rejected`() {
        for (url in listOf(
            "file:///data/data/com.yap.app/databases/yap.db",
            "javascript:alert(1)",
            "data:text/html;base64,AAAA",
            "intent://scan/#Intent;scheme=zxing;end",
            "ftp://example.com/x",
            "//example.com/x",
            "example.com/x",
            "mailto:someone@example.com"
        )) {
            assertEquals(Rejection.UNSUPPORTED_SCHEME, WebUrlPolicy.check(url), "$url 应该被拒绝")
        }
    }

    @Test
    fun `empty and missing host stay classified`() {
        assertEquals(Rejection.EMPTY, WebUrlPolicy.check(null))
        assertEquals(Rejection.EMPTY, WebUrlPolicy.check(""))
        assertEquals(Rejection.EMPTY, WebUrlPolicy.check("   "))
        assertEquals(Rejection.MISSING_HOST, WebUrlPolicy.check("https:///path"))
        assertEquals(Rejection.MISSING_HOST, WebUrlPolicy.check("https://"))
        assertEquals(Rejection.MISSING_HOST, WebUrlPolicy.check("http://example.com:abc/"))
    }

    // ------------------------------------------------------------------ isLocalHost

    @Test
    fun `private ipv4 ranges are local`() {
        for (host in listOf(
            "127.0.0.1", "127.1.2.3", "10.0.0.1", "10.1.2.3", "192.168.1.1",
            "172.16.0.1", "172.20.0.1", "172.31.255.255", "169.254.169.254",
            "0.0.0.0", "100.64.0.1", "224.0.0.1", "255.255.255.255"
        )) {
            assertTrue(WebUrlPolicy.isLocalHost(host), "$host 应该被判定为内网")
        }
    }

    @Test
    fun `172 range only rejects 16 to 31`() {
        assertFalse(WebUrlPolicy.isLocalHost("172.15.0.1"))
        assertFalse(WebUrlPolicy.isLocalHost("172.32.0.1"))
    }

    @Test
    fun `public hosts are not misclassified`() {
        for (host in listOf(
            "example.com", "api.deepseek.com", "10.example.com", "my192.168.example.org",
            "8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "dead.beef"
        )) {
            assertFalse(WebUrlPolicy.isLocalHost(host), "$host 不该被判定为内网")
        }
    }

    @Test
    fun `localhost aliases and trailing dots are local`() {
        for (host in listOf(
            "localhost", "LOCALHOST", "localhost.", "localhost..", "app.localhost",
            "localhost.localdomain", "ip6-localhost", "ip6-loopback"
        )) {
            assertTrue(WebUrlPolicy.isLocalHost(host), "$host 应该被判定为本机")
        }
    }

    @Test
    fun `numeric ip variants are local`() {
        for (host in listOf(
            "2130706433", "0x7f000001", "0X7F000001", "0177.0.0.1", "127.1",
            "0x7f.0.0.1", "192.168.257", "999.1.1.1"
        )) {
            assertTrue(WebUrlPolicy.isLocalHost(host), "$host 应该被判定为内网")
        }
    }

    @Test
    fun `ipv6 forms are classified by embedded ipv4`() {
        assertTrue(WebUrlPolicy.isLocalHost("::1"))
        assertTrue(WebUrlPolicy.isLocalHost("[::1]"))
        assertTrue(WebUrlPolicy.isLocalHost("::ffff:7f00:1"))
        assertTrue(WebUrlPolicy.isLocalHost("::ffff:127.0.0.1"))
        assertTrue(WebUrlPolicy.isLocalHost("fe80::1"))
        assertTrue(WebUrlPolicy.isLocalHost("fc00::1"))
        assertFalse(WebUrlPolicy.isLocalHost("2001:4860:4860::8888"))
        assertFalse(WebUrlPolicy.isLocalHost("::ffff:8.8.8.8"))
    }

    @Test
    fun `empty host is local`() {
        assertTrue(WebUrlPolicy.isLocalHost(""))
        assertTrue(WebUrlPolicy.isLocalHost("   "))
    }
}
