package com.myfriend.app.domain

import java.net.URI
import java.net.URISyntaxException

/**
 * `web_fetch` 要抓的 URL 该不该发出去（安全审计 0.10.2 P2-11，0.11.1 P2-5 加固）。
 *
 * Android 侧以前只判空：`file://`、`javascript:`、`data:`、`intent://`，甚至
 * `http://127.0.0.1:8080/admin` 都会被原样塞进 `{"url": …}` 转给第三方服务。
 * 抓取确实发生在服务端（不是设备侧 SSRF），但：
 *
 * 1. 把明显非法的 scheme 交给第三方服务是**输入校验契约不一致**（iOS 早在 `validatePageUrl` 里挡了）；
 * 2. 内网字面量（`127.0.0.1` / `localhost` / `10.` / `192.168.` / `::1`）对服务端也没有意义，
 *    却会把「本机/内网地址」写进日志与工具结果，属于不该发生的信息外泄面。
 *
 * 0.11.1 的加固（审计 P2-5 的实测绕过表逐条收掉）：
 * - 用 [URI] 做整体语法校验：`http://127.0.0.1:8080\@example.com/` 里的 `\` 被 WHATWG 当 `/`，
 *   手切 authority 会把它判成 `example.com`；而 `\` 在 URI 的 authority 里非法，直接拒；
 * - host 归一化：小写、去 FQDN 尾点（`localhost.` → `localhost`）、拒绝百分号编码的 host；
 * - 拒绝 userinfo（`https://user:pass@host/` 的口令会被原样发给第三方并进它的日志）；
 * - 数字 IP 的等价写法（`2130706433`、`0x7f000001`、`0177.0.0.1`、`127.1`）一律按内网处理：
 *   正常公网 IP 只会写成「四段、无前导零」的点分十进制；
 * - IPv4-mapped / IPv4-compatible / NAT64 / 6to4 的 IPv6 里内嵌的 v4 地址也按内网判定
 *   （`[::ffff:7f00:1]` = 127.0.0.1）；
 * - `127.0.0.1.nip.io` 这类把 IPv4 写进域名前缀的通配 DNS 也按内网处理。
 *
 * 只做纯字符串解析，**不建连接、不做 DNS 解析**：策略必须能在无网络的单测里给出确定结果。
 * 代价是 `localtest.me` / `lvh.me` 这种「域名本身解析到回环、但字面上看不出是 IP」的别名拦不住，
 * 已在安全审计的「剩余风险」里记下（要彻底挡只能做 DNS 解析，那会把策略变成网络调用）。
 *
 * 仍然只有 4 个 [Rejection] 值：两端的文案表（Android `ErrorText`、iOS `validatePageUrl`）对枚举
 * 是穷尽的，新增值会同时改两端 UI。userinfo 与非法 host 都归到 [Rejection.MISSING_HOST]——
 * 对调用方而言结果都是「不发这个请求」，而原因文案是近似的（见报告里的已知取舍）。
 */
object WebUrlPolicy {

    /** 拒绝原因；顺序就是判定顺序，UI 按它给不同文案。 */
    enum class Rejection {
        /** 空串。 */
        EMPTY,

        /** 不是 http / https（`file:` / `javascript:` / `data:` / `intent:` / 相对路径 / 没有 scheme）。 */
        UNSUPPORTED_SCHEME,

        /** 有 scheme 但没有 host / host 语法非法 / 带 userinfo（例如 `https:///path`、`https://user:pass@host/`）。 */
        MISSING_HOST,

        /** 内网/本机字面量，或「看起来像 IP」的变形写法（`localhost.`、`127.0.0.1`、`10.x`、`2130706433`、`::1` …）。 */
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

        // 整体语法校验：反斜杠、空格、制表符、非法转义、坏端口在这里被拒（失败方向是「不发」）。
        val uri = try {
            URI(text)
        } catch (_: URISyntaxException) {
            return Rejection.MISSING_HOST
        }

        val authority = uri.rawAuthority ?: return Rejection.MISSING_HOST
        val host = hostFromAuthority(authority) ?: return Rejection.MISSING_HOST
        if (host.isEmpty()) return Rejection.MISSING_HOST

        // 先判内网再判 userinfo：`https://user@10.0.0.1/x` 应报「本机/内网」而不是「缺少主机名」
        // （Android 既有测试就是按这个顺序断言的）。
        if (isLocalHost(host)) return Rejection.LOCAL_HOST
        if (uri.rawUserInfo != null) return Rejection.MISSING_HOST
        if (!isPlausibleHost(host)) return Rejection.MISSING_HOST
        return null
    }

    /**
     * 这个 host 是不是内网 / 本机 / 数字 IP 的变形写法。
     *
     * 判定顺序：localhost 名字 → IPv6 字面量 → IPv4（含各种数字变形）→ IPv4 前缀的通配 DNS。
     * 解析不了的 IPv6 / 数字 IP 一律按内网处理（fail closed）。
     */
    fun isLocalHost(host: String): Boolean {
        var value = host.trim()
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length - 1)
        }
        value = value.lowercase()
        // FQDN 尾点：`localhost.` / `127.0.0.1.` 解析到同一个主机，不能靠尾点绕过。
        while (value.endsWith(".")) value = value.dropLast(1)
        if (value.isEmpty()) return true
        if (value == "localhost" || value.endsWith(".localhost")) return true
        // 常见的回环别名（Debian 系 /etc/hosts 默认就有）：字面上没有 IP，但解析到本机。
        if (value == "localhost.localdomain" || value == "ip6-localhost" || value == "ip6-loopback") return true

        // IPv6 字面量：只放行公网地址；回环 / 未指定 / 链路本地 / ULA / 站点本地 /
        // 内嵌内网 v4 的都算内网。解析失败按内网处理。
        if (value.contains(':')) {
            val hextets = parseIpv6(value) ?: return true
            return isLocalIpv6(hextets)
        }

        // 「看起来像 IPv4」：只有「四段、每段 1–3 位、无前导零」是正常公网 IP 的写法。
        // 其余（`127.1` / `2130706433` / `0x7f000001` / `0177.0.0.1` / `1.2.3.4.5` 的前四段）都算内网。
        if (looksLikeIpv4(value)) {
            val canonical = parseStrictDottedIpv4(value) ?: return true
            return isLocalIpv4(canonical)
        }

        // `127.0.0.1.nip.io` / `2130706433.nip.io` 这类通配 DNS：前缀是内网 IPv4 就按内网处理。
        if (hasLocalIpv4Prefix(value)) return true
        return false
    }

    // ------------------------------------------------------------------ host 解析

    /**
     * 从 authority 里取 host：去 userinfo、去端口、去 IPv6 方括号，并做归一化。
     *
     * 返回 null 表示 authority 语法不对（host 为空、端口不是数字、带 `[`/`]` 的畸形写法、
     * host 里出现百分号编码等）——全部按「没有可用 host」处理。
     */
    private fun hostFromAuthority(authority: String): String? {
        if (authority.isEmpty()) return null
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isEmpty()) return null

        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close <= 1) return null
            val inside = hostPort.substring(1, close)
            if (inside.contains('%')) return null
            if (!isValidPortSuffix(hostPort.substring(close + 1))) return null
            return inside.lowercase()
        }
        if (hostPort.contains('[') || hostPort.contains(']')) return null

        val colon = hostPort.indexOf(':')
        val rawHost = if (colon >= 0) hostPort.substring(0, colon) else hostPort
        val port = if (colon >= 0) hostPort.substring(colon) else ""
        if (!isValidPortSuffix(port)) return null
        if (rawHost.contains('%') || rawHost.contains('@')) return null
        return normalizeHost(rawHost)
    }

    /** 端口后缀只能是空串或 `:<1–5 位数字>`。 */
    private fun isValidPortSuffix(suffix: String): Boolean {
        if (suffix.isEmpty()) return true
        if (!suffix.startsWith(":")) return false
        val port = suffix.substring(1)
        return port.isNotEmpty() && port.length <= 5 && port.all { it in '0'..'9' }
    }

    private fun normalizeHost(host: String): String {
        var value = host.lowercase()
        while (value.endsWith(".")) value = value.dropLast(1)
        return value
    }

    /** host 语法是否是一个可以发出去的域名 / IP 字面量（不含内网判定）。 */
    private fun isPlausibleHost(host: String): Boolean = when {
        host.contains(':') -> parseIpv6(host) != null
        looksLikeIpv4(host) -> parseStrictDottedIpv4(host) != null
        else -> isValidDomainName(host)
    }

    /**
     * 域名：ASCII 字母/数字/`-`/`_`，点分；每段 1–63，总长 ≤253，不以 `-` 开头或结尾。
     *
     * 不使用 IDN 的 Unicode 写法：真实请求里 host 必须是 punycode/ASCII，
     * Unicode 域名在没有 URL 编码器的情况下也发不出去，直接拒（失败方向是「不发」）。
     */
    private fun isValidDomainName(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        val labels = host.split('.')
        if (labels.isEmpty()) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.first() == '-' || label.last() == '-') return false
            for (ch in label) {
                val ok = ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_'
                if (!ok) return false
            }
        }
        return true
    }

    // ------------------------------------------------------------------ IPv4

    /** 看起来像 IPv4：1–4 段，每段是十进制数字，或 `0x` + 十六进制（WHATWG 接受的写法）。 */
    private fun looksLikeIpv4(value: String): Boolean {
        if (value.isEmpty()) return false
        val parts = value.split('.')
        if (parts.size > 4) return false
        return parts.all { part ->
            part.isNotEmpty() && (
                part.all { it in '0'..'9' } ||
                    (part.startsWith("0x") && part.length > 2 && part.substring(2).all { isHexDigit(it) })
                )
        }
    }

    /** 只接受「四段、每段 1–3 位、无前导零、0–255」的规范点分十进制；返回 32 位无符号值的 Long。 */
    private fun parseStrictDottedIpv4(value: String): Long? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        var packed = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return null
            if (part.length > 1 && part.first() == '0') return null
            if (!part.all { it in '0'..'9' }) return null
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            packed = (packed shl 8) or octet.toLong()
        }
        return packed
    }

    /** 是不是内网 / 回环 / 保留的 IPv4 段（与 JDK `InetAddress.isSiteLocal…` 的口径一致，外加 CGNAT）。 */
    private fun isLocalIpv4(packed: Long): Boolean {
        val first = ((packed ushr 24) and 0xFF).toInt()
        val second = ((packed ushr 16) and 0xFF).toInt()
        return when {
            first == 0 -> true // 0.0.0.0/8
            first == 10 -> true // 10/8
            first == 127 -> true // 回环
            first == 169 && second == 254 -> true // 链路本地
            first == 172 && second in 16..31 -> true // 172.16/12
            first == 192 && second == 168 -> true // 192.168/16
            first == 100 && second in 64..127 -> true // CGNAT 100.64/10
            first >= 224 -> true // 组播 224/4 + 保留 240/4
            else -> false
        }
    }

    /** 四段数字前缀（`127.0.0.1.nip.io`）或整数 IP 前缀（`2130706433.nip.io`）是内网就返回 true。 */
    private fun hasLocalIpv4Prefix(host: String): Boolean {
        val labels = host.split('.')
        if (labels.size < 2) return false

        if (labels.size >= 5) {
            val prefix = labels.take(4)
            val allNumeric = prefix.all { label -> label.isNotEmpty() && label.all { it in '0'..'9' } }
            if (allNumeric) {
                val packed = parseStrictDottedIpv4(prefix.joinToString("."))
                if (packed != null && isLocalIpv4(packed)) return true
            }
        }

        // 单个数字 label 只有在数值大到不可能是「普通数字开头的域名」时，才按整数 IP 解释：
        // `10.example.com` 是域名，`2130706433.nip.io` / `167772161.nip.io` 是 127.0.0.1 / 10.0.0.1。
        val firstLabel = labels.first()
        if (firstLabel.length >= 7) {
            val number = parseIpv4Number(firstLabel) ?: return false
            if (number > 0xFFFFFFL && isLocalIpv4(number)) return true
        }
        return false
    }

    /** 解析一段数字（十进制 / `0x` 十六进制 / 前导零八进制）为 32 位无符号值。 */
    private fun parseIpv4Number(text: String): Long? {
        if (text.isEmpty()) return null
        val value = when {
            text.startsWith("0x") && text.length > 2 -> text.substring(2).toLongOrNull(16)
            text.length > 1 && text.startsWith("0") -> text.toLongOrNull(8)
            else -> text.toLongOrNull()
        } ?: return null
        return value.takeIf { it in 0..0xFFFFFFFFL }
    }

    // ------------------------------------------------------------------ IPv6

    /** 十六进制冒号形式的 IPv6 → 8 个 16 位 hextet；支持 `::` 压缩与末段点分 v4。解析失败返回 null。 */
    private fun parseIpv6(input: String): IntArray? {
        if (input.isEmpty()) return null
        val doubleColon = input.indexOf("::")
        if (doubleColon < 0) {
            val groups = parseIpv6Groups(input) ?: return null
            if (groups.size != 8) return null
            return groups.toIntArray()
        }
        if (input.indexOf("::", doubleColon + 1) >= 0) return null
        val head = parseIpv6Groups(input.substring(0, doubleColon)) ?: return null
        val tail = parseIpv6Groups(input.substring(doubleColon + 2)) ?: return null
        // `::` 至少代表一个全零 hextet。
        if (head.size + tail.size > 7) return null
        val hextets = IntArray(8)
        for (index in head.indices) hextets[index] = head[index]
        for (index in tail.indices) hextets[8 - tail.size + index] = tail[index]
        return hextets
    }

    private fun parseIpv6Groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        val groups = part.split(':')
        val hextets = ArrayList<Int>(groups.size + 1)
        for ((index, group) in groups.withIndex()) {
            if (group.isEmpty()) return null
            if (group.contains('.')) {
                // 末尾的点分 v4（`::ffff:127.0.0.1`）展开成两个 hextet。
                if (index != groups.size - 1) return null
                val packed = parseStrictDottedIpv4(group) ?: return null
                hextets.add(((packed ushr 16) and 0xFFFF).toInt())
                hextets.add((packed and 0xFFFF).toInt())
                continue
            }
            if (group.length > 4) return null
            if (!group.all { isHexDigit(it) }) return null
            hextets.add(group.toInt(16))
        }
        return hextets
    }

    private fun isLocalIpv6(hextets: IntArray): Boolean {
        if (hextets.all { it == 0 }) return true // ::
        if ((0..6).all { hextets[it] == 0 } && hextets[7] == 1) return true // ::1
        val first = hextets[0]
        if (first and 0xFE00 == 0xFC00) return true // fc00::/7 唯一本地
        if (first and 0xFFC0 == 0xFE80) return true // fe80::/10 链路本地
        if (first and 0xFFC0 == 0xFEC0) return true // fec0::/10 站点本地（已废弃但仍按内网）

        // IPv4-mapped (`::ffff:a.b.c.d`) / IPv4-compatible (`::a.b.c.d`)
        if ((0..4).all { hextets[it] == 0 } && (hextets[5] == 0xFFFF || hextets[5] == 0)) {
            return isLocalIpv4(combineIpv4(hextets[6], hextets[7]))
        }
        // NAT64 64:ff9b::/96
        if (first == 0x0064 && hextets[1] == 0xFF9B && (2..5).all { hextets[it] == 0 }) {
            return isLocalIpv4(combineIpv4(hextets[6], hextets[7]))
        }
        // 6to4 2002:V4::/48
        if (first == 0x2002) {
            return isLocalIpv4(combineIpv4(hextets[1], hextets[2]))
        }
        return false
    }

    private fun combineIpv4(high: Int, low: Int): Long =
        ((high.toLong() and 0xFFFF) shl 16) or (low.toLong() and 0xFFFF)

    private fun isHexDigit(ch: Char): Boolean = ch in '0'..'9' || ch in 'a'..'f'
}
