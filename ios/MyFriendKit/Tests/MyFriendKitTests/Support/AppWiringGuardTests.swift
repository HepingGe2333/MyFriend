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

    /// Debug / Release 的版本号必须**精确等于发布值**（0.12.0 / 18），而且各 configuration 一致。
    ///
    /// 为什么从「彼此一致 + 不回退」改回精确断言：版本号是发布契约的一部分 ——
    /// `CURRENT_PROJECT_VERSION` 决定 App Store 能否覆盖升级，`MARKETING_VERSION` 是用户看到的
    /// 版本。「两个 configuration 一致」只证明它们长得一样，证明不了「这次发布真的升到了
    /// 0.12.0 / 18」：忘了升版本、或者只升了 Android 没升 iOS，旧断言都是绿的。
    /// 钉死具体数字之后，漏改任意一端/任意一处都会红。
    ///
    /// 维护方式：版本号的唯一来源是 `app/build.gradle.kts`（Android：versionCode/versionName）
    /// 与本工程文件 `MyFriend.xcodeproj/project.pbxproj`（iOS：`CURRENT_PROJECT_VERSION` /
    /// `MARKETING_VERSION`，Debug/Release 各一处，另外 0.12.0 的 iOS 测试 target 也有）。
    /// 升版本时改这些值，并同步改本测试与 Android 的
    /// `DataResilienceContractTest`（那边还会断言两端构建号/版本名相等）。
    func testVersionIsConsistentForBothConfigurations() throws {
        let text = try source("MyFriend.xcodeproj/project.pbxproj")
        let marketing = Self.values(of: "MARKETING_VERSION", in: text)
        let builds = Self.values(of: "CURRENT_PROJECT_VERSION", in: text)
        // 结构断言：「至少 Debug / Release 两处」+ 所有 configuration 一致。工程里新增 target
        // 时（0.12 加了 iOS 测试 target）数量会变，但每一处仍然必须是同一个值。
        XCTAssertGreaterThanOrEqual(marketing.count, 2, "Debug / Release 两处都要有 MARKETING_VERSION")
        XCTAssertGreaterThanOrEqual(builds.count, 2, "Debug / Release 两处都要有 CURRENT_PROJECT_VERSION")
        XCTAssertEqual(Set(marketing).count, 1, "两处版本号必须一致：\(marketing)")
        XCTAssertEqual(Set(builds).count, 1, "两处 build 号必须一致：\(builds)")

        // 精确断言：0.12.0 / 18。改版本时忘了改这里 → 红；改这里忘了改工程文件 → 也红。
        XCTAssertEqual(Set(marketing), ["0.12.0"], "MARKETING_VERSION 必须精确等于 0.12.0：\(marketing)")
        XCTAssertEqual(Set(builds), ["18"], "CURRENT_PROJECT_VERSION 必须精确等于 18：\(builds)")
    }

    // MARK: - Swift 第二实现只在 macOS 测试构建里（0.12.0）

    /// iOS App 的运行时只能有 Kotlin 决策实现（同一份 `:agentcore`）。
    ///
    /// 0.12.0 之前的失败方式很隐蔽：iOS App 已经默认走 `KotlinAgentCoreClient`，但
    /// `NativeAgentCoreClient`（Swift 实现）仍然被编译进 App，四个服务里的
    /// `decide(endpoint:nativeFallback:)` 回落闭包还直接构造它 —— 运行时不走，可它是
    /// 又一份可执行的判定实现，随时可能被「顺手回退」用上。
    ///
    /// 这条守卫是源码扫描（macOS 上可跑）：按真实构建条件算出「iOS 构建会编译的源码」，
    /// 其中不允许出现 `NativeAgentCoreClient`；Swift 实现必须整个在 `#if !os(iOS)` 里；
    /// 四个服务调用点也不许引用它（它们统一走 `AgentCoreNativeFallback`，iOS 上返回 nil）。
    func testSwiftDecisionImplementationIsExcludedFromIOSBuilds() throws {
        let client = try source("MyFriendKit/Sources/MyFriendKit/Core/AgentCoreClient.swift")

        // 1. Swift 实现类型必须整体在 `#if !os(iOS)` 条件块里。
        let nonIOSBlocks = Self.conditionalBlocks(in: client, opening: "#if !os(iOS)")
        XCTAssertFalse(nonIOSBlocks.isEmpty, "AgentCoreClient.swift 里找不到 #if !os(iOS)")
        XCTAssertTrue(
            nonIOSBlocks.contains { $0.contains("public struct NativeAgentCoreClient: AgentCoreClient") },
            "NativeAgentCoreClient 必须整体包在 #if !os(iOS) 里"
        )

        // 2. 更硬的一条：按 `#if os(iOS)` / `#else` 真实求值，iOS 构建的源码里一次都不许出现它。
        //    （只看 `#if os(iOS)` 块不够 —— `#else` 分支里的调用也会进 macOS，而不是 iOS。）
        for path in [
            "MyFriendKit/Sources/MyFriendKit/Core/AgentCoreClient.swift",
            "MyFriendKit/Sources/MyFriendKit/Core/AgentCoreClientKotlin.swift",
            "MyFriendKit/Sources/MyFriendKit/Core/AgentCoreObservability.swift"
        ] {
            let iosText = Self.iosBuildText(try source(path))
            // 只盯「代码」而不是注释：文档里提到类型名是允许的，`NativeAgentCoreClient(`
            // 和类型声明才是会进二进制的实现。
            XCTAssertFalse(
                iosText.contains("NativeAgentCoreClient(") || iosText.contains("struct NativeAgentCoreClient"),
                "\(path) 的 iOS 构建里出现了 Swift 决策实现（应被 #if !os(iOS) 排除）"
            )
        }

        // 3. 服务层调用点一律走 AgentCoreNativeFallback（iOS 上返回 nil），不许自己构造 Swift 实现。
        for path in [
            "Chat/ChatService.swift",
            "Services/HeartbeatController.swift",
            "Services/AgentLoop.swift",
            "Services/ToolExecutor.swift"
        ] {
            let text = try source("MyFriendKit/Sources/MyFriendKit/\(path)")
            XCTAssertFalse(
                text.contains("NativeAgentCoreClient"),
                "\(path) 不能引用 NativeAgentCoreClient：iOS 构建里这个类型不存在"
            )
            XCTAssertTrue(
                text.contains("AgentCoreNativeFallback"),
                "\(path) 的回落必须经由 AgentCoreNativeFallback（iOS 上返回 nil → 上抛）"
            )
        }

        // 4. iOS 的本机实现只能是 Kotlin，而且 framework 是硬依赖（不许静默退回 Swift）。
        let kotlin = try source("MyFriendKit/Sources/MyFriendKit/Core/AgentCoreClientKotlin.swift")
        XCTAssertTrue(kotlin.contains("#if os(iOS)"), "KotlinAgentCoreClient 只能在 iOS 构建里编译")
        XCTAssertTrue(kotlin.contains("import AgentCoreKit"), "iOS 构建必须硬链接 AgentCoreKit")
        XCTAssertFalse(kotlin.contains("NativeAgentCoreClient()"), "Kotlin 桥不能构造 Swift 实现")
    }

    /// 按真实构建条件算出「iOS 构建会参与编译的源码」。
    ///
    /// 只认 `os(iOS)` / `!os(iOS)`（这个仓库的条件编译就这么几种）；遇到别的条件直接
    /// `XCTFail`，逼作者把守卫一起更新，而不是让守卫悄悄漏掉一段代码。
    private static func iosBuildText(_ text: String) -> String {
        struct Frame {
            let parent: Bool
            var taken: Bool
            var active: Bool
        }
        var stack: [Frame] = []
        var included: [String] = []
        func currentActive() -> Bool { stack.last?.active ?? true }

        for line in text.components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("#if ") {
                let condition = String(trimmed.dropFirst(4)).trimmingCharacters(in: .whitespaces)
                let parent = currentActive()
                let value = evaluateIOSCondition(condition)
                stack.append(Frame(parent: parent, taken: value, active: parent && value))
                continue
            }
            if trimmed.hasPrefix("#elseif ") {
                let condition = String(trimmed.dropFirst(8)).trimmingCharacters(in: .whitespaces)
                guard var frame = stack.popLast() else {
                    XCTFail("#elseif 没有配对的 #if")
                    continue
                }
                let value = evaluateIOSCondition(condition)
                frame.active = frame.parent && !frame.taken && value
                frame.taken = frame.taken || value
                stack.append(frame)
                continue
            }
            if trimmed == "#else" {
                guard var frame = stack.popLast() else {
                    XCTFail("#else 没有配对的 #if")
                    continue
                }
                frame.active = frame.parent && !frame.taken
                stack.append(frame)
                continue
            }
            if trimmed.hasPrefix("#endif") {
                guard !stack.isEmpty else {
                    XCTFail("#endif 没有配对的 #if")
                    continue
                }
                stack.removeLast()
                continue
            }
            // 注释里提到类型名不算实现（文档需要解释这件事）；只扫描真正会编译的代码行。
            if currentActive(), !Self.isCommentLine(trimmed) { included.append(line) }
        }
        XCTAssertTrue(stack.isEmpty, "条件编译块没有配对的 #endif")
        return included.joined(separator: "\n")
    }

    private static func isCommentLine(_ trimmed: String) -> Bool {
        trimmed.hasPrefix("//") || trimmed.hasPrefix("/*") || trimmed.hasPrefix("*") || trimmed.hasPrefix("*/")
    }

    private static func evaluateIOSCondition(_ condition: String) -> Bool {
        switch condition {
        case "os(iOS)":
            return true
        case "!os(iOS)":
            return false
        default:
            XCTFail("未知的条件编译 #if \(condition)：请更新 AppWiringGuardTests 的求值")
            return true
        }
    }

    /// 取出所有以 `opening` 开头的条件编译块（支持嵌套 `#if`，`#else` 不改变层级）。
    private static func conditionalBlocks(in text: String, opening: String) -> [String] {
        var blocks: [String] = []
        var depth = 0
        var current: [String] = []
        for line in text.components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if depth == 0, trimmed.hasPrefix(opening) {
                depth = 1
                current = [line]
                continue
            }
            guard depth > 0 else { continue }
            current.append(line)
            if trimmed.hasPrefix("#if ") || trimmed.hasPrefix("#if(") {
                depth += 1
            } else if trimmed.hasPrefix("#endif") {
                depth -= 1
                if depth == 0 {
                    blocks.append(current.joined(separator: "\n"))
                    current = []
                }
            }
        }
        XCTAssertEqual(depth, 0, "条件编译块没有配对的 #endif（从 \(opening) 开始）")
        return blocks
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
