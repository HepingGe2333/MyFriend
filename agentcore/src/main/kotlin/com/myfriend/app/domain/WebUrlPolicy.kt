package com.myfriend.app.domain

/**
 * `web_fetch` 要抓的 URL 该不该发出去（安全审计 P2-11）。
 *
 * Android 侧以前只判空：`file://`、`javascript:`、`data:`、`intent://`，甚至
 * `http://127.0.0.1:8080/admin` 都会被原样塞进 `{"url": …}` 转给第三方服务。
 * 抓取确实发生在服务端（不是设备侧 SSRF），但：
 *
 * 1. 把明显非法的 scheme 交给第三方服务是**输入校验契约不一致**（iOS 早在 `validatePageUrl` 里挡了）；
 * 2. 内网字面量（`127.0.0.1` / `localhost` / `10.` / `192.168.` / `::1`）对服务端也没有意义，
 *    却会把「本机/内网地址」写进日志与工具结果，属于不该发生的信息外泄面。
 *
 * 这里只放 http / https、必须有 host、且不接受内网字面量。纯字符串解析，不建连接，
 * 单测直接把「放行/拒绝」列成表。
 */
object WebUrlPolicy {

    /** 拒绝原因；顺序就是判定顺序，UI 按它给不同文案。 */
    enum class Rejection {
        /** 空串。 */
        EMPTY,

        /** 不是 http / https（`file:` / `javascript:` / `data:` / `intent:` / 相对路径 / 没有 scheme）。 */
        UNSUPPORTED_SCHEME,

        /** 有 scheme 但没有 host（例如 `https:///path`）。 */
        MISSING_HOST,

        /** 内网/本机字面量（`localhost`、`127.0.0.1`、`10.x`、`192.168.`、`::1` …）。 */
        LOCAL_HOST
    }

    /** 放行返回 null，否则返回拒绝原因。 */
    fun check(raw: String?): Rejection? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Rejection.EMPTY

        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0) return Rejection.UNSUPPORTED_SCHEME
        val scheme = text.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return Rejection.UNSUPPORTED_SCHEME

        val host = hostOf(text.substring(schemeEnd + 3))
        if (host.isBlank()) return Rejection.MISSING_HOST
        if (isLocalHost(host)) return Rejection.LOCAL_HOST
        return null
    }

    /** 这个 host 是不是内网 / 本机字面量。 */
    fun isLocalHost(host: String): Boolean {
        val value = host.trim().trim('[', ']').lowercase()
        if (value.isEmpty()) return true
        if (value == "localhost" || value.endsWith(".localhost")) return true
        if (value == "::" || value == "::1" || value == "0:0:0:0:0:0:0:1") return true
        if (value == "0.0.0.0") return true
        // 只有「长得像 IPv4 字面量」的才按前缀判，免得把 10.example.com 这类域名误杀。
        if (looksLikeIpv4(value)) {
            if (value.startsWith("127.")) return true
            if (value.startsWith("10.")) return true
            if (value.startsWith("192.168.")) return true
            if (value.startsWith("169.254.")) return true
            // 172.16.0.0/12
            if (value.startsWith("172.")) {
                val second = value.removePrefix("172.").substringBefore('.').toIntOrNull()
                if (second != null && second in 16..31) return true
            }
        }
        // IPv6 映射的 v4 本机地址：::ffff:127.0.0.1
        if (value.contains(":") && (value.contains("::ffff:127.") || value.endsWith("::1"))) return true
        return false
    }

    /** 只由数字与点组成、且有点：IPv4 字面量（不做严格校验，够用来避免误伤域名）。 */
    private fun looksLikeIpv4(value: String): Boolean =
        value.contains('.') && value.all { it.isDigit() || it == '.' }

    /**
     * 从 authority 里取 host：去掉 userinfo 与端口。
     *
     * `https://user:pass@example.com:8443/path` → `example.com`；
     * `http://[::1]:8080/` → `::1`。
     */
    private fun hostOf(authorityAndRest: String): String {
        val authorityEnd = authorityAndRest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) authorityAndRest else authorityAndRest.substring(0, authorityEnd)
        val afterUserInfo = authority.substringAfterLast('@')
        if (afterUserInfo.startsWith("[")) {
            // IPv6 字面量：[::1]:8080
            val close = afterUserInfo.indexOf(']')
            if (close < 0) return ""
            return afterUserInfo.substring(1, close)
        }
        return afterUserInfo.substringBefore(':')
    }
}
