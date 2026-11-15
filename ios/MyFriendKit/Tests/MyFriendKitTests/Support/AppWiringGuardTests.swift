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

    /// Debug / Release 的版本号必须**两处一致**，而且不能退回到已经发过的旧版本。
    ///
    /// 这里原来硬编码 `0.11.1` / `16`：版本号一升，这条守卫就红，而它守的其实不是「某个具体数字」，
    /// 而是「两个 configuration 一致 + 没退回旧版本」。改成读出来比较，版本归版本负责人，
    /// 守卫只盯它真正关心的事（`MARKETING_VERSION` 由工程文件负责人维护，本测试不改工程）。
    func testVersionIsConsistentForBothConfigurations() throws {
        let text = try source("MyFriend.xcodeproj/project.pbxproj")
        let marketing = Self.values(of: "MARKETING_VERSION", in: text)
        let builds = Self.values(of: "CURRENT_PROJECT_VERSION", in: text)
        // 「至少 Debug / Release 两处」：工程里新增 target 时（0.12 加了 iOS 测试 target）
        // 这条守卫不该红，它守的是「两两一致 + 不回退」。
        XCTAssertGreaterThanOrEqual(marketing.count, 2, "Debug / Release 两处都要有 MARKETING_VERSION")
        XCTAssertGreaterThanOrEqual(builds.count, 2, "Debug / Release 两处都要有 CURRENT_PROJECT_VERSION")
        XCTAssertEqual(Set(marketing).count, 1, "两处版本号必须一致：\(marketing)")
        XCTAssertEqual(Set(builds).count, 1, "两处 build 号必须一致：\(builds)")

        // 0.11.0 / 15 是已经发出去的版本：不允许回退（新版本号由发布流程决定）。
        XCTAssertFalse(marketing.contains("0.11.0"), "不能退回 0.11.0")
        XCTAssertFalse(builds.contains("15"), "不能退回 build 15")
        let version = try XCTUnwrap(marketing.first)
        XCTAssertTrue(
            version.compare("0.11.1", options: .numeric) != .orderedAscending,
            "版本号不该低于 0.11.1（当前 \(version)）"
        )
    }

    /// 抓出工程文件里某个设置的所有取值。
    private static func values(of key: String, in text: String) -> [String] {
        text.components(separatedBy: "\n").compactMap { line in
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard trimmed.hasPrefix("\(key) = ") else { return nil }
            return trimmed
                .dropFirst("\(key) = ".count)
                .trimmingCharacters(in: CharacterSet(charactersIn: ";"))
        }
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

    /// v11：定位改成「目标块 + 往前一块上下文」，上限从「条数」变成「块数」——
    /// 但「不能一路读到最早」这条保护必须还在（纯逻辑在 `MessageBlocks`，有单测）。
    func testBlockFocusHasACapConstant() throws {
        let text = try source("MyFriendKit/Sources/MyFriendKit/Domain/MessageBlocks.swift")
        XCTAssertTrue(text.contains("focusContextBlocks"), "块定位要有上下文块数上限")
        XCTAssertTrue(text.contains("contextBlocks: Int = focusContextBlocks"), "focus 默认带上限")
        XCTAssertTrue(text.contains("maxGapScan"), "块号空洞的扫描要有上限（块表被写坏时不能死循环）")
    }

    /// v11 的区块分页接线：首屏读最新一块、向上/向下各读一块，且界面真的用了它们。
    func testChatViewModelUsesBlockPaging() throws {
        let viewModel = try source("MyFriend/Views/Chat/ChatViewModel.swift")
        XCTAssertTrue(viewModel.contains("blocksAround(sessionId: sessionId)"), "首屏要读最新一块")
        XCTAssertTrue(viewModel.contains("olderBlockIndex("), "向上加载要按块号找上一块")
        XCTAssertTrue(viewModel.contains("newerBlockIndex("), "向下加载要按块号找下一块")
        XCTAssertTrue(viewModel.contains("func loadNewerMessages()"), "v11 新增向下加载")
        XCTAssertFalse(viewModel.contains("countBefore("), "旧的游标分页判到底要删掉")

        let screen = try source("MyFriend/Views/Chat/ChatScreen.swift")
        XCTAssertTrue(screen.contains("loadNewerRow"), "界面要有「下方还有更新的消息」提示行")
        XCTAssertTrue(screen.contains("model.loadNewerMessages()"), "提示行要能触发向下加载")
    }

    /// 检索接线：逐块走完、没有候选上限。
    func testSearchIsBlockWiseAndUncapped() throws {
        let stores = try source("MyFriendKit/Sources/MyFriendKit/Data/Stores.swift")
        XCTAssertTrue(stores.contains("enumerateBlocks(personaId:"), "检索要逐块枚举")
        XCTAssertFalse(stores.contains("maxSearchCandidates"), "500 条候选上限要删掉")
        XCTAssertFalse(stores.contains("candidateLimit"), "候选上限参数要删掉")
    }
}
