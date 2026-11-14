import Foundation

// ---------------------------------------------------------------------------
// 「要抓取的网页地址该不该发出去」（0.11.1 安全审计 P2-5 / iOS 审计 P2-17）。
//
// 对照 Android 的 `agentcore/.../WebUrlPolicy.kt`：抓取发生在第三方服务端，不是设备侧
// SSRF，但这条闸的意义是「不把本机/内网地址交给别人去访问」，也不把明显的非法输入当成
// 正常链接发出去。iOS 以前只判 scheme + host 非空 —— 与 Android 声称的规则相比等于没拦。
//
// 这里额外处理 Android 旧实现被「URL 解析差异」绕过的几种写法（审计实测全部 ALLOW）：
//
// | 输入 | 为什么危险 |
// | --- | --- |
// | `http://127.0.0.1:8080\@example.com/` | WHATWG 把 `\` 当 `/`，真实 host 是 127.0.0.1 |
// | `https://localhost./` / `https://LOCALHOST./` | 尾点 FQDN 仍解析到回环 |
// | `http://2130706433/` / `0x7f000001` / `0177.0.0.1` | 十进制/十六进制/八进制 IPv4 |
// | `http://[::ffff:7f00:1]/` | IPv4-mapped 回环的十六进制写法 |
// | `http://user:pass@example.com/` | userinfo 里的口令会随 URL 发给第三方 |
//
// 与 Android 的差别（本端更严）：拒绝带 userinfo 的 URL、拒绝含 `\` 的 URL。
// 规则表在 `WebUrlPolicyTests` 里逐条列成「放行/拒绝」，改规则会立刻变红。
// ---------------------------------------------------------------------------

public enum WebUrlPolicy {

    /// 拒绝原因；顺序就是判定顺序。
    public enum Rejection: Equatable, Sendable {
        /// 空串。
        case empty
        /// 不是 http / https（`file:` / `javascript:` / `data:` / 相对路径 / 没有 scheme）。
        case unsupportedScheme
        /// 有 scheme 但没有 host（例如 `https:///path`）。
        case missingHost
        /// 内网 / 本机字面量（`localhost`、`127.0.0.1`、`10.x`、`::1` …）。
        case localHost(String)
        /// URL 里带 userinfo（`user:pass@host`）：凭据会被发给第三方。
        case credentialsInURL
        /// authority 里有 `\`（WHATWG 把它当 `/`，会让「取最后一个 @ 之后」这类解析错位）。
        case ambiguousAuthority
        /// host 里有空白 / 控制字符。
        case invalidHost
    }

    /// 放行返回 nil，否则返回拒绝原因。
    public static func check(_ raw: String?) -> Rejection? {
        let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !text.isEmpty else { return .empty }
        // `\` 在 URL 解析里等价于 `/`：手写 authority 解析会被它绕过。
        guard !text.contains("\\") else { return .ambiguousAuthority }
        // 空白与控制字符：`http://exa mple.com/` 这类不该「看起来合法地」发出去。
        guard !text.contains(" "), !text.contains("\t"), !text.contains("\n"), !text.contains("\r"),
              text.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) else {
            return .invalidHost
        }

        guard let url = URL(string: text),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            return .unsupportedScheme
        }
        if let user = url.user, !user.isEmpty { return .credentialsInURL }
        if let password = url.password, !password.isEmpty { return .credentialsInURL }
        guard let host = url.host, !host.isEmpty else { return .missingHost }
        if isLocalHost(host) { return .localHost(host) }
        return nil
    }

    /// 这个 host 是不是内网 / 本机字面量。
    ///
    /// 只判**字面量**（与 Android 的取舍一致）：`127.0.0.1.nip.io`、`localtest.me` 这类
    /// 「域名解析到回环」不拦（本端不解析 DNS，拦了也只是猜）。
    public static func isLocalHost(_ rawHost: String) -> Bool {
        var value = rawHost.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if value.hasPrefix("["), value.hasSuffix("]") {
            value = String(value.dropFirst().dropLast())
        }
        // 尾点 FQDN：`localhost.` / `127.0.0.1.` 都仍解析到同一个地址。
        while value.hasSuffix(".") { value.removeLast() }
        guard !value.isEmpty else { return true }
        if value == "localhost" || value.hasSuffix(".localhost") { return true }
        if value == "::" || value == "::1" || value == "0:0:0:0:0:0:0:1" { return true }

        // IPv4 字面量（含十进制整数、十六进制、八进制、以及省略段的写法）。
        if let address = ipv4Number(value) { return isLocalIPv4(address) }

        // IPv4-mapped IPv6：`::ffff:127.0.0.1`、`::ffff:7f00:1`、`0:0:0:0:0:ffff:7f00:1`。
        if value.contains("ffff") {
            let suffix = value.components(separatedBy: "ffff:").last ?? ""
            if let mapped = ipv4Number(suffix) { return isLocalIPv4(mapped) }
            let groups = suffix.split(separator: ":")
            if groups.count == 2,
               let high = UInt32(groups[0], radix: 16),
               let low = UInt32(groups[1], radix: 16) {
                return isLocalIPv4((high << 16) | low)
            }
        }
        return false
    }

    /// 内网 / 回环 / 链路本地的 IPv4 段。
    static func isLocalIPv4(_ value: UInt32) -> Bool {
        let first = UInt8((value >> 24) & 0xFF)
        let second = UInt8((value >> 16) & 0xFF)
        if value == 0 { return true }                       // 0.0.0.0
        if first == 127 { return true }                     // 回环 127.0.0.0/8
        if first == 10 { return true }                      // 10.0.0.0/8
        if first == 192, second == 168 { return true }      // 192.168.0.0/16
        if first == 172, (16...31).contains(second) { return true }  // 172.16.0.0/12
        if first == 169, second == 254 { return true }      // 169.254.0.0/16（链路本地）
        return false
    }

    /// IPv4 字面量 → 32 位整数。支持 inet_aton 的全部写法（浏览器/系统解析器都接受）：
    /// `127.0.0.1`、`127.1`、`2130706433`、`0x7f000001`、`0177.0.0.1`。
    ///
    /// 解析不了（含字母的域名、IPv6）返回 nil —— 调用方因此不会把 `10.example.com` 误杀。
    static func ipv4Number(_ text: String) -> UInt32? {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...4).contains(parts.count) else { return nil }
        var numbers: [UInt64] = []
        for part in parts {
            guard let number = componentNumber(part) else { return nil }
            numbers.append(number)
        }
        // 每段的范围：最后一段吃掉剩余字节（inet_aton 语义），其余段必须 <= 255。
        for (index, number) in numbers.enumerated() {
            if index == numbers.count - 1 {
                let remainingBytes = 5 - numbers.count
                let limit = (UInt64(1) << UInt64(8 * remainingBytes)) - 1
                guard number <= limit else { return nil }
            } else {
                guard number <= 255 else { return nil }
            }
        }
        var value: UInt64 = 0
        switch numbers.count {
        case 1: value = numbers[0]
        case 2: value = numbers[0] << 24 | numbers[1]
        case 3: value = numbers[0] << 24 | numbers[1] << 16 | numbers[2]
        default: value = numbers[0] << 24 | numbers[1] << 16 | numbers[2] << 8 | numbers[3]
        }
        guard value <= UInt64(UInt32.max) else { return nil }
        return UInt32(value)
    }

    /// 单个 IPv4 段：十进制 / `0x` 十六进制 / 前导 0 八进制。
    private static func componentNumber(_ part: Substring) -> UInt64? {
        let text = part.lowercased()
        guard !text.isEmpty else { return nil }
        if text.hasPrefix("0x") {
            let digits = text.dropFirst(2)
            guard !digits.isEmpty, digits.allSatisfy({ $0.isHexDigit }) else { return nil }
            return UInt64(digits, radix: 16)
        }
        if text.count > 1, text.hasPrefix("0") {
            let digits = text.dropFirst()
            guard digits.allSatisfy({ ("0"..."7").contains($0) }) else { return nil }
            return UInt64(digits, radix: 8) ?? 0
        }
        guard text.allSatisfy({ $0.isNumber }) else { return nil }
        return UInt64(text, radix: 10)
    }
}
