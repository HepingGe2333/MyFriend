import Foundation
import XCTest
@testable import YapKit

/// `ServerConfig` 的来源优先级与 http/https 边界。
///
/// 这些用例不读真实 `ProcessInfo` / `Bundle.main`，全部注入字典，所以：
/// - 不依赖运行测试的机器上有没有设置 `YAP_SERVER_URL`；
/// - 能在 DEBUG 测试进程里模拟「Release 构建」（`buildAllowsInsecureHTTP: false`）。
final class ServerConfigTests: XCTestCase {

    private func load(
        environment: [String: String] = [:],
        plist: [String: Any]? = nil,
        buildAllowsInsecureHTTP: Bool = true
    ) throws -> ServerConfig {
        try ServerConfig.load(
            environment: environment,
            infoDictionary: plist,
            buildAllowsInsecureHTTP: buildAllowsInsecureHTTP
        )
    }

    // MARK: - 来源

    func testEnvironmentProvidesURLAndToken() throws {
        let config = try load(environment: [
            "YAP_SERVER_URL": "https://myfriend.example.com",
            "YAP_SERVER_TOKEN": "  secret-token  "
        ])
        XCTAssertTrue(config.enabled)
        XCTAssertTrue(config.isUsable)
        XCTAssertEqual(config.baseURL?.absoluteString, "https://myfriend.example.com")
        XCTAssertEqual(config.token, "secret-token", "Token 两侧空白要去掉")
        XCTAssertTrue(config.hasToken)
    }

    func testEnvironmentOverridesInfoPlist() throws {
        let config = try load(
            environment: [
                "YAP_SERVER_URL": "https://env.example.com",
                "YAP_SERVER_TOKEN": "env-token"
            ],
            plist: [
                "YetAnotherPalServerURL": "https://plist.example.com",
                "YetAnotherPalServerToken": "plist-token"
            ]
        )
        XCTAssertEqual(config.baseURL?.host, "env.example.com")
        XCTAssertEqual(config.token, "env-token")
    }

    func testInfoPlistIsFallbackWhenEnvironmentMissingOrBlank() throws {
        let config = try load(
            environment: [
                "YAP_SERVER_URL": "   ",
                "YAP_SERVER_TOKEN": ""
            ],
            plist: [
                "YetAnotherPalServerURL": "https://plist.example.com/",
                "YetAnotherPalServerToken": "plist-token"
            ]
        )
        XCTAssertEqual(config.baseURL?.absoluteString, "https://plist.example.com")
        XCTAssertEqual(config.token, "plist-token")
    }

    func testInfoPlistAcceptsUppercaseAliases() throws {
        let config = try load(plist: [
            "YAP_SERVER_URL": "https://alias.example.com",
            "YAP_SERVER_TOKEN": "alias-token"
        ])
        XCTAssertEqual(config.baseURL?.host, "alias.example.com")
        XCTAssertEqual(config.token, "alias-token")
    }

    func testMissingURLMeansDisabledButDoesNotThrow() throws {
        let config = try load(environment: ["YAP_SERVER_TOKEN": "orphan-token"])
        XCTAssertFalse(config.enabled)
        XCTAssertFalse(config.isUsable)
        XCTAssertNil(config.baseURL)
        XCTAssertEqual(config.token, "orphan-token", "Token 保留，用户下次补上地址即可")
    }

    func testExplicitEnabledFlagCanDisableAConfiguredServer() throws {
        let config = try load(environment: [
            "YAP_SERVER_URL": "https://myfriend.example.com",
            "YAP_SERVER_ENABLED": "false"
        ])
        XCTAssertFalse(config.enabled)
        XCTAssertFalse(config.isUsable)
        XCTAssertNotNil(config.baseURL, "地址仍保留，便于设置页回显")

        let plistBool = try load(plist: [
            "YetAnotherPalServerURL": "https://myfriend.example.com",
            "YetAnotherPalServerEnabled": false
        ])
        XCTAssertFalse(plistBool.enabled)
    }

    func testInvalidEnabledFlagIsReported() {
        XCTAssertThrowsError(try load(environment: [
            "YAP_SERVER_URL": "https://myfriend.example.com",
            "YAP_SERVER_ENABLED": "maybe"
        ])) { error in
            XCTAssertEqual(
                error as? ServerConfigError,
                .invalidEnabledFlag("maybe")
            )
        }
    }

    // MARK: - https / http 规则

    func testHTTPSIsAllowedForPublicHosts() throws {
        for raw in [
            "https://myfriend.example.com",
            "https://myfriend.example.com/base/",
            "https://192.168.1.9:8443"
        ] {
            let config = try load(
                environment: ["YAP_SERVER_URL": raw],
                buildAllowsInsecureHTTP: false
            )
            XCTAssertEqual(config.baseURL?.scheme, "https")
        }
    }

    func testHTTPLoopbackIsAllowedInDebugBuilds() throws {
        for raw in [
            "http://127.0.0.1:8787",
            "http://127.1.2.3:8787",
            "http://localhost:8787",
            "http://[::1]:8787"
        ] {
            let config = try load(environment: ["YAP_SERVER_URL": raw])
            XCTAssertEqual(config.baseURL?.scheme, "http", raw)
        }
    }

    func testHTTPPrivateLANIsAllowedInDebugBuilds() throws {
        for raw in [
            "http://10.0.0.5:8787",
            "http://172.16.0.1:8787",
            "http://172.31.255.254:8787",
            "http://192.168.1.20:8787",
            "http://169.254.1.7:8787",
            "http://[fe80::1]:8787",
            "http://[fd00::1234]:8787",
            "http://myfriend.local:8787"
        ] {
            let config = try load(environment: ["YAP_SERVER_URL": raw])
            XCTAssertEqual(config.baseURL?.scheme, "http", raw)
        }
    }

    func testHTTPPublicHostsAreRejectedInDebugBuilds() {
        for raw in [
            "http://example.com",
            "http://8.8.8.8",
            "http://172.32.0.1:8787",
            "http://192.169.1.1:8787"
        ] {
            XCTAssertThrowsError(try load(environment: ["YAP_SERVER_URL": raw]), raw) { error in
                guard case .insecureHTTPRequiresLocalNetwork = (error as? ServerConfigError) else {
                    return XCTFail("\(raw) 应报「只允许局域网」，实际 \(error)")
                }
            }
        }
    }

    func testHTTPIsRejectedInReleaseBuildsEvenForLoopback() {
        XCTAssertThrowsError(try load(
            environment: ["YAP_SERVER_URL": "http://127.0.0.1:8787"],
            buildAllowsInsecureHTTP: false
        )) { error in
            XCTAssertEqual(error as? ServerConfigError, .insecureHTTPNotAllowedInRelease)
        }
    }

    func testUnsupportedSchemesAreRejected() {
        for raw in ["ftp://example.com", "file:///tmp/server", "ws://example.com"] {
            XCTAssertThrowsError(try load(environment: ["YAP_SERVER_URL": raw]), raw) { error in
                guard case .unsupportedScheme = (error as? ServerConfigError) else {
                    return XCTFail("\(raw) 应报不支持的协议，实际 \(error)")
                }
            }
        }
    }

    func testCredentialsInURLAreRejected() {
        XCTAssertThrowsError(try load(environment: [
            "YAP_SERVER_URL": "https://user:pass@example.com"
        ])) { error in
            XCTAssertEqual(error as? ServerConfigError, .credentialsInURL)
        }
    }

    func testMalformedURLIsRejected() {
        for raw in ["not a url", "https://", "://example.com"] {
            XCTAssertThrowsError(try load(environment: ["YAP_SERVER_URL": raw]), raw)
        }
    }

    func testTrailingSlashesAreNormalized() throws {
        let config = try load(environment: [
            "YAP_SERVER_URL": "https://myfriend.example.com/base///"
        ])
        XCTAssertEqual(config.baseURL?.absoluteString, "https://myfriend.example.com/base")
    }

    func testLocalHostMatcher() {
        for host in ["127.0.0.1", "localhost", "::1", "10.1.2.3", "192.168.0.1",
                     "172.16.5.5", "172.31.9.9", "169.254.3.4", "fe80::1", "fd12::3",
                     "myfriend.local"] {
            XCTAssertTrue(ServerConfig.isLocalNetworkHost(host), host)
        }
        for host in ["example.com", "8.8.8.8", "172.15.0.1", "172.32.0.1",
                     "192.169.0.1", "100.64.0.1", "myfriend.local.example.com"] {
            XCTAssertFalse(ServerConfig.isLocalNetworkHost(host), host)
        }
    }

    // MARK: - 安全

    func testDescriptionRedactsToken() {
        let config = ServerConfig(
            baseURL: URL(string: "https://myfriend.example.com")!,
            token: "sk-must-never-leak"
        )
        XCTAssertFalse(String(describing: config).contains("sk-must-never-leak"))
        XCTAssertFalse(config.description.contains("sk-must-never-leak"))
        XCTAssertFalse(config.debugDescription.contains("sk-must-never-leak"))
        XCTAssertTrue(config.description.contains("<redacted>"))
    }

    func testConfigErrorsDoNotContainToken() {
        let token = "sk-must-never-leak"
        let error = ServerConfigError.insecureHTTPRequiresLocalNetwork("example.com")
        XCTAssertFalse(error.message.contains(token))
        let invalid = ServerConfigError.invalidURL("https://example.com/?token=\(token)")
        XCTAssertFalse(invalid.message.contains(token), "错误文案不能把原始地址/Token 打出来")
    }

    func testDefaultBuildFlagFollowsCompilation() {
        #if DEBUG
        XCTAssertTrue(ServerConfig.buildAllowsInsecureHTTP)
        #else
        XCTAssertFalse(ServerConfig.buildAllowsInsecureHTTP)
        #endif
    }
}
