import Foundation
import XCTest
@testable import YapKit

/// ATS 与「允许不安全 HTTP」的构建边界（0.10.2 审计 P1-6 / 安全审计 P1-2）。
///
/// 0.10.1 的 `Config/Info.plist` 里 `NSAllowsArbitraryLoads = true`，而且 Debug 与 Release
/// **共用同一份 plist**：发布包里 ATS 是关着的，唯一防线只剩 App 内的 `NetworkGuard`。
/// 修法分两层，这里各钉一条：
/// 1. 配置层：发布 plist 只留 `NSAllowsLocalNetworking`，Debug 用单独的 plist 放开；
///    两份文件的差异逐键比对（防止有人「顺手同步」把 Release 又改回去）。
/// 2. 代码层：非 DEBUG 构建里，即使用户打开了「允许不安全 HTTP」，也给出明确错误
///    （不能悄悄走明文，也不能让系统层静默拦掉说不清原因）。
final class ATSAndInsecureHTTPTests: XCTestCase {

    // MARK: - 找文件

    private static func iosRoot() throws -> URL {
        var current = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<12 {
            let candidate = current.appendingPathComponent("YetAnotherPal/Resources", isDirectory: true)
            var isDir: ObjCBool = false
            if FileManager.default.fileExists(atPath: candidate.path, isDirectory: &isDir), isDir.boolValue {
                return current
            }
            current = current.deletingLastPathComponent()
        }
        throw XCTSkip("找不到 ios/ 目录")
    }

    private func plist(_ name: String) throws -> [String: Any] {
        let url = try Self.iosRoot().appendingPathComponent("Config/\(name)")
        let data = try Data(contentsOf: url)
        let object = try PropertyListSerialization.propertyList(from: data, format: nil)
        return try XCTUnwrap(object as? [String: Any])
    }

    private func ats(_ name: String) throws -> [String: Any] {
        try XCTUnwrap(try plist(name)["NSAppTransportSecurity"] as? [String: Any])
    }

    // MARK: - 1. 两份 plist

    func testReleasePlistDoesNotAllowArbitraryLoads() throws {
        let release = try ats("Info.plist")
        XCTAssertNil(release["NSAllowsArbitraryLoads"], "发布配置不能全量放开明文 HTTP")
        XCTAssertEqual(release["NSAllowsLocalNetworking"] as? Bool, true, "局域网联调要留着")
        XCTAssertNil(release["NSAllowsArbitraryLoadsInWebContent"])
    }

    func testDebugPlistAllowsArbitraryLoadsForLocalDevelopment() throws {
        let debug = try ats("Info-Debug.plist")
        XCTAssertEqual(debug["NSAllowsArbitraryLoads"] as? Bool, true, "调试构建要能给本机参考服务发 http")
        XCTAssertEqual(debug["NSAllowsLocalNetworking"] as? Bool, true)
    }

    /// 两份 plist 除了 ATS 这一节之外必须**逐键一致**：否则某个后台模式/权限只在 Debug 里有，
    /// 发布包会静默少一项（反过来也一样）。
    func testDebugAndReleasePlistsDifferOnlyInATS() throws {
        var release = try plist("Info.plist")
        var debug = try plist("Info-Debug.plist")
        release["NSAppTransportSecurity"] = nil
        debug["NSAppTransportSecurity"] = nil
        XCTAssertEqual(
            Set(release.keys), Set(debug.keys),
            "两份 plist 的键集合要一致（差异只允许出现在 ATS）"
        )
        // 用 NSDictionary 的深比较（`String(describing:)` 对桥接的数组只会打印指针）。
        let releaseDictionary = release as NSDictionary
        let debugDictionary = debug as NSDictionary
        XCTAssertTrue(
            releaseDictionary.isEqual(to: debugDictionary as! [AnyHashable: Any]),
            "两份 plist 除 ATS 之外必须完全一致（否则发布会少一项后台模式/权限）"
        )
    }

    /// 工程配置必须真的把 Debug 指到 Info-Debug.plist（否则上面那条只是文件好看）。
    func testProjectWiresDebugPlistToDebugConfiguration() throws {
        let pbx = try String(
            contentsOf: try Self.iosRoot().appendingPathComponent("YetAnotherPal.xcodeproj/project.pbxproj"),
            encoding: .utf8
        )
        let debugBlocks = pbx.components(separatedBy: "/* Debug */ = {")
        // 只要求「至少 project + App 两个 Debug 配置块」：工程里加了新的 target
        //（比如 0.12 的 iOS 测试 target）不该让这条守卫变红 —— 它要守的是 plist 接线，不是块的数量。
        XCTAssertGreaterThanOrEqual(debugBlocks.count, 3, "工程里应当有 project/target 两个 Debug 配置块")
        XCTAssertTrue(
            pbx.components(separatedBy: "INFOPLIST_FILE = Config/Info-Debug.plist;").count - 1 == 1,
            "Debug 配置应当使用 Config/Info-Debug.plist"
        )
        XCTAssertTrue(
            pbx.components(separatedBy: "INFOPLIST_FILE = Config/Info.plist;").count - 1 >= 1,
            "Release 配置必须继续使用 Config/Info.plist"
        )
    }

    // MARK: - 2. 代码层：构建边界与错误文案

    func testHttpIsRejectedInReleaseBuildsEvenWhenUserAllowedIt() {
        XCTAssertThrowsError(
            try NetworkGuard.parseHttpURL(
                "http://192.168.1.20:8787/v1", allowInsecureHttp: true, buildAllowsInsecureHttp: false
            )
        ) { error in
            let message = (error as? ApiException)?.message ?? ""
            XCTAssertTrue(message.contains("https"), "要明确告诉用户改成 https：\(message)")
            XCTAssertTrue(message.contains("不允许明文"), "要明确说明这个构建不允许明文：\(message)")
        }
    }

    func testHttpStillWorksInDebugBuildsWhenUserAllowedIt() throws {
        let url = try NetworkGuard.parseHttpURL(
            "http://127.0.0.1:8787/v1", allowInsecureHttp: true, buildAllowsInsecureHttp: true
        )
        XCTAssertEqual(url.absoluteString, "http://127.0.0.1:8787/v1")
    }

    func testHttpWithoutUserConsentIsRejectedInBothBuilds() {
        for build in [true, false] {
            XCTAssertThrowsError(
                try NetworkGuard.parseHttpURL(
                    "http://example.com/v1", allowInsecureHttp: false, buildAllowsInsecureHttp: build
                )
            ) { error in
                let message = (error as? ApiException)?.message ?? ""
                XCTAssertTrue(message.contains("允许不安全 HTTP"), "\(message)")
            }
        }
    }

    func testHttpsAlwaysAllowed() throws {
        for build in [true, false] {
            let url = try NetworkGuard.parseHttpURL(
                "https://api.deepseek.com/v1", allowInsecureHttp: false, buildAllowsInsecureHttp: build
            )
            XCTAssertEqual(url.scheme, "https")
        }
    }

    /// 测试进程是 DEBUG，所以默认值（业务代码实际用的那条）应当允许明文 ——
    /// 与「调试构建能用本机参考服务」一致；Release 编译时这里会变成 false（见上面的注入用例）。
    func testDefaultFlagFollowsBuildConfiguration() {
        #if DEBUG
        XCTAssertTrue(NetworkGuard.buildAllowsInsecureHTTP)
        #else
        XCTAssertFalse(NetworkGuard.buildAllowsInsecureHTTP)
        #endif
    }
}
