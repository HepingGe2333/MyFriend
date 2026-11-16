import Foundation
import XCTest
@testable import YapKit

/// 0.11.0 审计 P2-8：内置浏览器的导航白名单与数据仓策略。
///
/// 消息里的链接已经过了 `LinkDetector`（只放行 http/https），但**页面内的跳转/重定向**
/// 不受那是控制：`javascript:` 能执行脚本、`data:` 能伪造整页、`file:` 能读沙盒。
/// 这一层是浏览器内部的第二道闸。
final class BrowserPolicyTests: XCTestCase {

    private func decide(_ raw: String) -> BrowserNavigationDecision {
        BrowserPolicy.decide(URL(string: raw))
    }

    // MARK: - 放行

    func testHttpAndHttpsAreAllowed() {
        XCTAssertEqual(decide("https://example.com/a?b=1#c"), .allow)
        XCTAssertEqual(decide("http://example.com"), .allow)
        XCTAssertEqual(decide("https://例子.中国/路径"), .allow, "国际化域名也要能开")
    }

    func testSchemeIsCaseInsensitive() {
        XCTAssertEqual(decide("HTTPS://Example.com"), .allow)
        XCTAssertEqual(decide("JavaScript:alert(1)"), .cancel)
    }

    // MARK: - 取消

    func testDangerousSchemesAreCancelled() {
        XCTAssertEqual(decide("javascript:alert(document.cookie)"), .cancel)
        XCTAssertEqual(decide("data:text/html,<h1>fake</h1>"), .cancel)
        XCTAssertEqual(decide("file:///etc/passwd"), .cancel)
        XCTAssertEqual(decide("file:///var/mobile/Containers/Data/Application/x/Documents/db.sqlite"), .cancel)
        XCTAssertEqual(decide("about:blank"), .cancel)
        XCTAssertEqual(decide("blob:https://example.com/abc"), .cancel)
        XCTAssertEqual(decide("ftp://example.com/file"), .cancel)
    }

    func testMalformedOrEmptyTargetsAreCancelled() {
        XCTAssertEqual(BrowserPolicy.decide(nil), .cancel)
        XCTAssertEqual(BrowserPolicy.decide(URL(string: "")), .cancel)
        XCTAssertEqual(decide("https://"), .cancel, "没有 host 的 https 同样不放行")
        XCTAssertEqual(decide("https:///path"), .cancel)
        XCTAssertEqual(decide("example.com/no-scheme"), .cancel)
        XCTAssertEqual(BrowserPolicy.decide(rawString: "   "), .cancel)
    }

    // MARK: - 交给系统

    func testNonWebSchemesGoToTheSystem() {
        XCTAssertEqual(decide("mailto:someone@example.com"), .external)
        XCTAssertEqual(decide("tel:+8613800000000"), .external)
        XCTAssertEqual(decide("itms-apps://apps.apple.com/app/id123"), .external)
        XCTAssertEqual(decide("myfriend://chat/session-1"), .external)
    }

    func testRawStringEntryMatchesURLVariant() {
        XCTAssertEqual(BrowserPolicy.decide(rawString: "https://example.com"), .allow)
        XCTAssertEqual(BrowserPolicy.decide(rawString: "javascript:1"), .cancel)
        XCTAssertEqual(BrowserPolicy.decide(rawString: nil), .cancel)
    }

    // MARK: - 数据仓与脚本（装配层的取值）

    /// Cookie / LocalStorage 不进磁盘：退出这次浏览就没了，站点之间也认不出彼此。
    func testDataStoreIsNonPersistent() {
        XCTAssertEqual(BrowserPolicy.dataStore, .nonPersistent)
        XCTAssertNotEqual(BrowserPolicy.dataStore, .persistent)
    }

    /// 默认关闭 JavaScript：分享/阅读场景够用，少一整类攻击面。
    func testJavaScriptIsOffByDefault() {
        XCTAssertFalse(BrowserPolicy.allowsJavaScript)
    }

    /// 白名单与黑名单不能有交集（否则同一个 scheme 两种说法）。
    func testAllowedAndBlockedSchemesAreDisjoint() {
        XCTAssertTrue(BrowserPolicy.allowedSchemes.isDisjoint(with: BrowserPolicy.blockedSchemes))
        XCTAssertTrue(BrowserPolicy.allowedSchemes.contains("http"))
        XCTAssertTrue(BrowserPolicy.allowedSchemes.contains("https"))
        for scheme in BrowserPolicy.blockedSchemes {
            XCTAssertFalse(BrowserPolicy.allowedSchemes.contains(scheme))
        }
    }
}
