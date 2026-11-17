import Foundation
import XCTest
@testable import YapKit

/// 0.13 第二阶段：云端情绪 / 进化 / 配置推送 / 媒体落地的**接线契约**。
///
/// 仓库没有 App 测试 target，App 层只能 `swiftc -parse`（语法级）。所以这里做两件事：
/// 1. 用与 `AppEnvironment` **完全相同的参数标签**把四个新类型造一遍 ——
///    签名一变，这个测试就先红（比等到 `xcodebuild` 才发现便宜得多）；
/// 2. 读 `ios/YetAnotherPal/**` 的源码，断言关键调用真的存在（与
///    `AppWiringGuardTests` 同一套做法）。
final class ServerCloudWiringTests: XCTestCase {

    private static let iosRoot: URL = {
        var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let fileManager = FileManager.default
        while url.path != "/" {
            let candidate = url.appendingPathComponent("YapKit/Sources/YapKit")
            if fileManager.fileExists(atPath: candidate.path),
               fileManager.fileExists(atPath: url.appendingPathComponent("YetAnotherPal").path) {
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

    // MARK: - 装配契约（签名对了才编译得过）

    /// 造一遍 AppEnvironment 里那几行装配，参数标签必须与生产代码一致。
    func testEnvironmentWiringCompilesAgainstKitAPI() throws {
        let db = try TempDatabase()
        defer { db.cleanUp() }
        let client = ServerAPIClient(
            config: ServerConfig(baseURL: URL(string: "http://127.0.0.1:8787")!, token: "t"),
            session: ServerURLProtocolStub.makeSession()
        )
        let moodRepo = MoodRepository(stores: db.stores)
        let evolution = PersonaEvolutionService(
            stores: db.stores, completion: FakeCompletionClient(), log: { _ in }
        )
        let writes = ServerWriteService(
            client: client,
            stores: db.stores,
            memoryRepository: MemoryRepository(stores: db.stores)
        )
        let config = ApiConfigEntity(
            id: "c1", name: "本机", baseUrl: "https://api.deepseek.com", model: "deepseek-chat"
        )

        // 1) 配置推送（AppEnvironment.pushLocalModelConfigIfNeeded）
        let pusher = ServerLlmConfigPusher(
            client: client,
            apiConfig: config,
            apiKey: "sk-1",
            log: { _ in }
        )
        XCTAssertTrue(ServerLlmConfigPusher.shouldPush(config: client.config))

        // 2) 云端情绪（AppEnvironment.cloudMood）
        let cloudMood = CloudMoodJudgement(
            client: client,
            stores: db.stores,
            mood: moodRepo,
            serverPersonaId: { localId in writes.resolvedServerPersonaId(localId) },
            log: { _ in }
        )
        XCTAssertEqual(cloudMood.shouldEvaluate(personaId: "p1"), false, "没有消息就不判")

        // 3) 云端进化（AppEnvironment.cloudPersonaEvolution）
        let cloudEvolution = CloudPersonaEvolution(
            client: client,
            evolution: evolution,
            stores: db.stores,
            serverPersonaId: { localId in writes.resolvedServerPersonaId(localId) },
            log: { _ in }
        )
        XCTAssertEqual(writes.resolvedServerPersonaId("p1"), "p1", "没有映射时就是它自己")

        // 4) 媒体落地（AppEnvironment.serverMediaSync）
        let mediaSync = ServerMediaSyncService(
            downloader: client,
            stores: db.stores,
            log: { _ in }
        )
        let report = awaitResult { await mediaSync.syncMessageAttachments(limit: 1) }
        XCTAssertTrue(report.isEmpty)

        // 5) 本机媒体落盘目录（ServerMediaLocalStore.shared 用的就是这几个）
        let store = ServerMediaLocalStore.shared
        XCTAssertEqual(store.attachmentsDirectory.lastPathComponent, "attachments")
        XCTAssertEqual(store.stickersDirectory.lastPathComponent, "stickers")
        XCTAssertEqual(store.audioDirectory.lastPathComponent, "audio")
        XCTAssertEqual(store.videoDirectory.lastPathComponent, "video")

        // 占位：这几个对象造出来就够（真正的行为在各自的测试文件里）。
        withExtendedLifetime((pusher, cloudMood, cloudEvolution)) {}
    }

    /// 把一个 async 调用在同步测试里跑完（测试不许为「造对象」引入并发脚手架）。
    private func awaitResult<T>(_ body: @escaping () async -> T) -> T {
        var value: T?
        let expectation = expectation(description: "await")
        Task {
            value = await body()
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 10)
        // swiftlint:disable:next force_unwrapping
        return value!
    }

    // MARK: - 源码守卫：线上的接线真的存在

    func testServerModeActivationPushesLocalModelConfigAndSyncsMedia() throws {
        let text = try source("YetAnotherPal/App/AppEnvironment.swift")
        // 连接成功后先推本机配置（云端情绪/进化要靠服务端那份配置调模型）。
        XCTAssertTrue(text.contains("await pushLocalModelConfigIfNeeded()"),
                      "activateServerMode 里要先推本机模型配置")
        XCTAssertTrue(text.contains("ServerLlmConfigPusher("), "要用 Kit 里的推送器（含回环判断）")
        XCTAssertTrue(text.contains("ServerLlmConfigPusher.shouldPush(config:"),
                      "推之前要按 isLoopback 判断（远程不推）")
        // 媒体落地：构造服务 + 在进聊天页/同步后跑一拍。
        XCTAssertTrue(text.contains("serverMediaSync = ServerMediaSyncService("),
                      "要装配 ServerMediaSyncService")
        XCTAssertTrue(text.contains("scheduleServerMediaSync()"),
                      "连上服务端之后要跑一轮媒体落地")
        XCTAssertTrue(text.contains("scheduleServerMediaSync(sessionId: sessionId)"),
                      "进聊天页拉到新块之后要落这一页的附件")
        XCTAssertTrue(text.contains("func runServerMediaSyncNow()"),
                      "要提供「一次把消息/头像/表情都搬一遍」的入口")
    }

    func testCloudReplyTriggersBothEvolutionAndMood() throws {
        let chat = try source("YetAnotherPal/Views/Chat/ChatViewModel.swift")
        XCTAssertTrue(chat.contains("env.triggerPersonaEvolutionForServerReply("),
                      "回复落库后要触发云端进化")
        XCTAssertTrue(chat.contains("env.triggerCloudMoodForServerReply("),
                      "回复落库后要触发云端情绪（每 4 条节流）")
        let env = try source("YetAnotherPal/App/AppEnvironment.swift")
        XCTAssertTrue(env.contains("func triggerCloudMoodForServerReply("))
        XCTAssertTrue(env.contains("CloudMoodJudgement("), "要用 Kit 里的云端情绪编排")
        XCTAssertTrue(env.contains("await cloud.evaluateIfNeeded(personaId: personaId)"),
                      "进化改成服务端评估：不再需要本机模型配置")
    }

    func testPersonaEditorUsesServerPendingAndAsyncReject() throws {
        let text = try source("YetAnotherPal/Views/Persona/PersonaEditorScreen.swift")
        XCTAssertTrue(text.contains("await cloud.refreshPending(personaId: personaId)"),
                      "打开人格页要先拉服务端的待审列表（服务端是权威）")
        XCTAssertTrue(text.contains("try await cloud.reject(patchId: patch.id)"),
                      "拒绝走服务端端点（异步）")
        XCTAssertTrue(text.contains("try await cloud.approve(patchId: patch.id)"),
                      "接受走服务端端点（异步）")
    }

    /// 旧的第一阶段写法必须已经消失：客户端自己算完再整份推人格。
    func testLegacyClientSideEvaluationIsGone() throws {
        let env = try source("YetAnotherPal/App/AppEnvironment.swift")
        XCTAssertFalse(
            env.contains("cloud.evaluateIfNeeded(\n                personaId: personaId,\n                sessionId: trimmed"),
            "不该再走第一阶段「本机评估」的调用形状"
        )
        XCTAssertFalse(env.contains("writes: writes,"), "CloudPersonaEvolution 不再需要 ServerWriteService")
    }
}
