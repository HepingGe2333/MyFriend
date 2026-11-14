import Foundation
import XCTest
@testable import MyFriendKit

/// `web_fetch` 的 URL 规则（0.11.1 安全审计 P2-5 / iOS 审计 P2-17）。
///
/// 审计实测 Android 的 `WebUrlPolicy` 会放行下面这批「解析差异」写法，而 iOS 以前连
/// 内网字面量都不判（只判 scheme + host 非空）。这里把同一批绕过用例逐条钉住：
/// 规则改了、或者谁把某条判断删了，这里立刻红。
final class WebUrlPolicyTests: XCTestCase {

    // MARK: - 放行

    func testOrdinaryHttpAndHTTPSAreAllowed() {
        let allowed = [
            "https://example.com/",
            "https://example.com/path?q=1#frag",
            "http://example.com:8080/admin",
            "https://api.deepseek.com/v1/chat/completions",
            // 域名以数字开头但不是 IPv4 字面量：不能误杀。
            "https://10.example.com/",
            "https://127.example.com/",
            "https://172.20.example.com/"
        ]
        for raw in allowed {
            XCTAssertNil(WebUrlPolicy.check(raw), "应当放行：\(raw)")
        }
    }

    // MARK: - 拒绝：scheme / host 形状

    func testRejectsNonHttpSchemesAndMalformedAuthorities() {
        let rejected = [
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "file:///etc/passwd",
            "data:text/html,hi",
            "tel:12345",
            "//example.com/path",
            "https:///path",
            "",
            "   "
        ]
        for raw in rejected {
            XCTAssertNotNil(WebUrlPolicy.check(raw), "必须拒绝：\(raw)")
        }
        XCTAssertEqual(WebUrlPolicy.check("javascript:1"), .unsupportedScheme)
        XCTAssertEqual(WebUrlPolicy.check("https:///path"), .missingHost)
    }

    func testRejectsWhitespaceAndControlCharactersInHost() {
        XCTAssertEqual(WebUrlPolicy.check("http://exa mple.com/"), .invalidHost)
        XCTAssertEqual(WebUrlPolicy.check("http://example.com\t/"), .invalidHost)
        XCTAssertEqual(WebUrlPolicy.check("http://example.com\n/"), .invalidHost)
    }

    // MARK: - 拒绝：内网 / 本机字面量

    func testRejectsLoopbackAndPrivateLiteralHosts() {
        let local = [
            "http://localhost/",
            "http://localhost:8080/admin",
            "http://LOCALHOST./admin",          // 尾点 FQDN
            "https://localhost./",
            "http://127.0.0.1/",
            "http://127.0.0.1:8080/admin",
            "http://127.1/",                    // 省略段
            "http://10.0.0.1/",
            "http://192.168.1.5/admin",
            "http://172.16.0.1/",
            "http://172.31.255.255/",
            "http://169.254.1.1/",              // 链路本地
            "http://0.0.0.0/",
            "http://[::1]/",
            "http://[::]/"
        ]
        for raw in local {
            guard let rejection = WebUrlPolicy.check(raw) else {
                XCTFail("必须拒绝内网/本机地址：\(raw)")
                continue
            }
            if case .localHost = rejection {
                // 期望
            } else {
                XCTFail("\(raw) 的拒绝原因应为 localHost，实际 \(rejection)")
            }
        }
        // 边界：172.32 不在 172.16/12 里，应当放行。
        XCTAssertNil(WebUrlPolicy.check("http://172.32.0.1/"))
        // 没加方括号的完整 IPv6 不是合法 authority（URL 解析就会失败）：同样必须拒绝。
        XCTAssertNotNil(WebUrlPolicy.check("http://0:0:0:0:0:0:0:1/"))
    }

    /// 十进制 / 十六进制 / 八进制 IPv4 都是同一个回环地址（浏览器与系统解析器都接受）。
    func testRejectsNumericFormsOfLoopback() {
        let numeric = [
            "http://2130706433/",       // 127.0.0.1 十进制
            "http://0x7f000001/",       // 127.0.0.1 十六进制
            "http://0177.0.0.1/",       // 127.0.0.1 八进制首段
            "http://0x7f.0.0.1/",
            "http://127.0.0.1./"        // 尾点
        ]
        for raw in numeric {
            XCTAssertNotNil(WebUrlPolicy.check(raw), "数字形式的回环也必须拒绝：\(raw)")
        }
        XCTAssertEqual(WebUrlPolicy.check("http://2130706433/"), .localHost("2130706433"))
    }

    /// IPv4-mapped IPv6：`::ffff:127.0.0.1` 与它的十六进制写法 `::ffff:7f00:1`。
    func testRejectsIPv4MappedIPv6Loopback() {
        for raw in [
            "http://[::ffff:127.0.0.1]/",
            "http://[::ffff:7f00:1]/",
            "http://[0:0:0:0:0:ffff:7f00:1]/"
        ] {
            XCTAssertNotNil(WebUrlPolicy.check(raw), "IPv4-mapped 回环也必须拒绝：\(raw)")
        }
    }

    // MARK: - 拒绝：解析差异与凭据

    /// `\` 在 WHATWG 里等价于 `/`：`http://127.0.0.1:8080\@example.com/` 的真实 host 是 127.0.0.1。
    func testRejectsBackslashAuthority() {
        XCTAssertEqual(
            WebUrlPolicy.check(#"http://127.0.0.1:8080\@example.com/"#),
            .ambiguousAuthority
        )
    }

    func testRejectsUserInfoCredentials() {
        XCTAssertEqual(
            WebUrlPolicy.check("http://user:pass@example.com/"),
            .credentialsInURL
        )
        XCTAssertEqual(WebUrlPolicy.check("https://user@example.com/"), .credentialsInURL)
    }

    // MARK: - 与 WebToolsClient 的接线

    /// `WebToolsClient` 的判断必须走 `WebUrlPolicy`（不再是「只判 scheme + host」的那份）。
    func testWebToolsValidatorUsesPolicy() {
        XCTAssertNoThrow(try WebToolsClient.validatePageUrl("https://example.com/"))
        XCTAssertThrowsError(try WebToolsClient.validatePageUrl("http://192.168.1.5/admin")) { error in
            guard let failure = error as? WebToolsFailure else {
                return XCTFail("期望 WebToolsFailure，实际 \(error)")
            }
            guard case .localUrl = failure else {
                return XCTFail("内网地址应报 localUrl，实际 \(failure)")
            }
            XCTAssertTrue(failure.message.contains("内网"))
        }
        XCTAssertThrowsError(try WebToolsClient.validatePageUrl("http://user:pass@example.com/")) { error in
            guard case .credentialsInURL = error as? WebToolsFailure else {
                return XCTFail("带凭据的 URL 应报 credentialsInURL，实际 \(error)")
            }
        }
    }

    // MARK: - 纯函数的强度（避免「规则看起来很严、实际只挡了一种」）

    func testIPv4NumberParsingMatchesInetAton() {
        XCTAssertEqual(WebUrlPolicy.ipv4Number("127.0.0.1"), 0x7F00_0001)
        XCTAssertEqual(WebUrlPolicy.ipv4Number("2130706433"), 0x7F00_0001)
        XCTAssertEqual(WebUrlPolicy.ipv4Number("0x7f000001"), 0x7F00_0001)
        XCTAssertEqual(WebUrlPolicy.ipv4Number("0177.0.0.1"), 0x7F00_0001)
        XCTAssertEqual(WebUrlPolicy.ipv4Number("127.1"), 0x7F00_0001)
        XCTAssertNil(WebUrlPolicy.ipv4Number("example.com"))
        XCTAssertNil(WebUrlPolicy.ipv4Number("10.example.com"))
        XCTAssertNil(WebUrlPolicy.ipv4Number("256.0.0.1"), "每段超过 255 时不是合法 IPv4")
    }
}
