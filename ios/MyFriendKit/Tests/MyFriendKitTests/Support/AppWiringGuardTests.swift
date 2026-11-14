import Foundation
import XCTest
@testable import MyFriendKit

/// App 层接线的源码守卫（0.11.1 审计 P2-16 的「App 层只有读源码的断言」那一类）。
///
/// 纯逻辑组件（`ChatWindowPolicy` / `BackupImportLoader` / `PendingDeepLink` /
/// `AgentCoreDiagnostics`）都有行为测试，但「线上真的调了它们」只能靠 App 层的接线。
/// 仓库没有 App 测试 target，所以这里与既有做法一致：读 `ios/MyFriend/**` 的源码断言
/// 关键调用存在、且旧的错误写法已经消失。
final class AppWiringGuardTests: XCTestCase {

    private static let iosRoot: URL = {
        var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let fileManager = FileManager.default
        while url.path != "/" {
            let candidate = url.appendingPathComponent("MyFriendKit/Sources/MyFriendKit")
            if fileManager.fileExists(atPath: candidate.path),
               fileManager.fileExists(atPath: url.appendingPathComponent("MyFriend").path) {
                return url
            }
            url = url.deletingLastPathComponent()
        }
        fatalError("找不到 ios 根目录（从 #filePath=\(#filePath) 向上）")
    }()

    private func source(_ relativePath: String) throws -> String {
        let url = Self.iosRoot.appendingPathComponent(relativePath)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw XCTSkip("文件不存在：\(url.path)")
        }
        return try String(contentsOf: url, encoding: .utf8)
    }

    // MARK: - 版本号（安全审计 P2-15 的 iOS 部分）

    func testVersionIs111Build16ForBothConfigurations() throws {
        let text = try source("MyFriend.xcodeproj/project.pbxproj")
        XCTAssertEqual(
            text.components(separatedBy: "MARKETING_VERSION = 0.11.1;").count - 1, 2,
            "Debug / Release 两处都要是 0.11.1"
        )
        XCTAssertEqual(
            text.components(separatedBy: "CURRENT_PROJECT_VERSION = 16;").count - 1, 2,
            "Debug / Release 两处都要是 16"
        )
        XCTAssertFalse(text.contains("MARKETING_VERSION = 0.11.0;"))
        XCTAssertFalse(text.contains("CURRENT_PROJECT_VERSION = 15;"))
    }

    // MARK: - P1-3：聊天页窗口感知外部删除

    func testChatViewModelReconcilesWindowAndStopsPolling() throws {
        let text = try source("MyFriend/Views/Chat/ChatViewModel.swift")
        XCTAssertTrue(text.contains("ChatWindowPolicy.plan("), "外部删除判定要走纯策略（有单测）")
        XCTAssertTrue(text.contains("case .sessionGone:"), "会话消失要单独分支")
        XCTAssertTrue(text.contains("private func handleSessionGone()"), "会话消失要有统一的清空/停轮询入口")
        XCTAssertTrue(text.contains("env.stores.sessions.byId(sessionId)"), "必须校验会话还在不在")
        XCTAssertTrue(text.contains("messagePulseTask?.cancel()"), "会话消失要停 20 秒轮询")
        // 旧写法（只看「条数/最新 id 变了没」）不能回来。
        let changed = try XCTUnwrap(text.range(of: "private func reloadMessagesIfChanged()"))
        let tail = text[changed.lowerBound...]
        let body = tail.prefix(600)
        XCTAssertTrue(body.contains("reloadMessages(preservingLoadedRange: true)"), "轮询统一交给 reloadMessages")
        XCTAssertFalse(body.contains("pagesAfter") || body.contains("appendNewerMessages"), "轮询不该自己再写一份比较逻辑")
    }

    // MARK: - P2-15：会话预览走定向 UPDATE

    func testChatViewModelUsesTargetedPreviewUpdate() throws {
        let text = try source("MyFriend/Views/Chat/ChatViewModel.swift")
        XCTAssertTrue(text.contains("env.stores.sessions.updatePreview("), "预览要用定向 UPDATE")
        let preview = try XCTUnwrap(text.range(of: "private func refreshSessionPreview"))
        let body = text[preview.lowerBound...].prefix(1_500)
        XCTAssertFalse(body.contains("sessions.upsert(current)"), "不能再整行 REPLACE（会覆盖并发写入的未读数）")
    }

    // MARK: - P2-9：导入的读 + 预解析在后台

    func testBackupScreenUsesBackgroundImportLoader() throws {
        let text = try source("MyFriend/Views/Backup/BackupScreen.swift")
        XCTAssertTrue(text.contains("BackupImportLoader.load(at:"), "读文件 + 预解析要走后台加载器")
        XCTAssertFalse(text.contains("readBackupText(at:"), "主线程不再自己读整份备份")
        XCTAssertFalse(text.contains("preview(fileText:"), "主线程不再自己 JSON 预解析")
    }

    // MARK: - P2-2：通知深链消费即清 + 会话校验

    func testRootViewConsumesDeepLinkAndValidatesSession() throws {
        let text = try source("MyFriend/App/RootView.swift")
        XCTAssertTrue(text.contains("pendingDeepLink.clear()"), "温启动消费后必须清持久化键")
        XCTAssertTrue(text.contains("consumeIfSessionExists"), "冷启动消费要校验会话存在")
        XCTAssertTrue(text.contains("env.sessionExists(id:"), "打开前要校验会话还在")
        XCTAssertTrue(text.contains("openPendingSession"), "温启动与冷启动共用一个消费入口")
    }

    // MARK: - P2-1：严格模式失败可见

    func testDiagnosticsSinkIsWiredAtLaunch() throws {
        let text = try source("MyFriend/App/MyFriendApp.swift")
        XCTAssertTrue(text.contains("AgentCoreDiagnostics.shared.setSink"), "启动时要把诊断接到 debug 日志")
        let chat = try source("MyFriendKit/Sources/MyFriendKit/Chat/ChatService.swift")
        XCTAssertTrue(chat.contains("agentCore.decide("), "聊天链路的决策要经过 decide（区分严格/回退）")
        let tools = try source("MyFriendKit/Sources/MyFriendKit/Services/ToolExecutor.swift")
        XCTAssertTrue(tools.contains("agentCore.decide("), "工具链路同理")
    }

    // MARK: - P2-7 / P2-12 / P2-13：窗口与死代码

    func testRecallNoLongerMaterializesWholeSession() throws {
        let text = try source("MyFriendKit/Sources/MyFriendKit/Services/RecallService.swift")
        XCTAssertTrue(text.contains("recentDialogue("), "深度回忆要读窗口")
        XCTAssertFalse(text.contains("messages.bySession("), "不能整段读会话")
    }

    func testHomeDigestDeadCodeIsGone() throws {
        let text = try source("MyFriendKit/Sources/MyFriendKit/Domain/HomeDigest.swift")
        XCTAssertFalse(text.contains("public static func merge("), "死代码已删")
        XCTAssertFalse(text.contains("public static func onlyChanged("), "死代码已删")
        XCTAssertTrue(text.contains("public static func changed("), "首页真正使用的判定要保留")
    }

    /// P2-12：首页 4 秒一拍不能再用「不会退订」的 `Timer.publish`。
    func testHomePulseStopsWhenPageIsNotVisible() throws {
        let text = try source("MyFriend/Views/Home/HomeView.swift")
        XCTAssertFalse(text.contains(".onReceive(Self.pulseTimer)"), "不再订阅全局 Timer")
        XCTAssertFalse(text.contains("static let pulseTimer"), "旧的静态 Timer 要删掉")
        XCTAssertTrue(text.contains("startDigestPulse()"), "可见时才起一拍")
        XCTAssertTrue(text.contains("stopDigestPulse()"), "不可见/进后台要取消")
        XCTAssertTrue(text.contains("pulseTask?.cancel()"), "取消要真的 cancel")
    }

    func testFocusWindowHasACapConstant() throws {
        let text = try source("MyFriendKit/Sources/MyFriendKit/Domain/MessagePaging.swift")
        XCTAssertTrue(text.contains("maxFocusWindowMessages"), "定位窗口要有条数上限")
        XCTAssertTrue(text.contains("cap: Int = maxFocusWindowMessages"), "window(reaching:) 默认带上限")
    }
}
