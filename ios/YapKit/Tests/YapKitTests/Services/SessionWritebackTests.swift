import Foundation
import XCTest
@testable import YapKit

/// 后台任务写回会话时**只动自己那几列**（0.10.2 审计 P1-1）。
///
/// 抽记忆与压缩摘要在 `ChatService` 里是**同时**触发的：两者都是「读整行会话 → 调模型
/// （几百毫秒到几十秒）→ 写回」。0.10.1 之前写回用整行 `INSERT OR REPLACE`，于是这段时间里
/// - 用户新发的消息带来的 `lastMessagePreview` / `unreadCount` / `title` 被旧快照盖回去；
/// - 两条后台任务互相把对方的字段冲掉（摘要白压一次 / 同一段被反复抽）；
/// - 会话被删掉时还会被 REPLACE 插回一行幽灵会话。
///
/// 这里用一个「在模型调用期间顺手改库」的假 completion 来精确复现那段窗口。
final class SessionWritebackTests: XCTestCase {

    private var temp: TempDatabase!
    private let sessionId = "session-1"
    private let personaId = "persona-1"
    private let configId = "config-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        try temp.stores.personas.upsert(TestData.persona(id: personaId))
        try temp.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
        try temp.stores.apiConfigs.upsert(ApiConfigEntity(
            id: configId, name: "测试配置", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat"
        ))
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - 假模型：调用期间执行一段「并发写」

    private final class InterleavingCompletionClient: CompletionClient {
        private let text: String
        private let during: () -> Void
        private(set) var calls = 0

        init(text: String, during: @escaping () -> Void) {
            self.text = text
            self.during = during
        }

        func complete(
            config: CompletionConfig,
            model: String,
            messages: [CompletionMessage],
            params: CompletionParams,
            kind: CompletionKind,
            context: UsageContext
        ) async throws -> CompletionResult {
            calls += 1
            // 这就是「模型在想的这几秒」：期间界面/心跳可以往同一个会话里写。
            during()
            return CompletionResult(text: text)
        }
    }

    /// 模拟用户在这几秒里又发了一条消息（`ChatService.appendUserMessage` 写的那几列）。
    private func simulateNewUserMessage(writeCount: Bool = false) throws {
        guard var session = try temp.stores.sessions.byId(sessionId) else { return }
        session.lastMessagePreview = "那我明天早点到"
        session.lastMessageAt = now + 1_000
        session.unreadCount = 0
        session.title = "明天早点到"
        session.updatedAt = now + 1_000
        if writeCount { session.lastMemoryExtractedCount = 999 }
        try temp.stores.sessions.upsert(session)
    }

    private func seedMessages(count: Int, startIndex: Int = 0) throws {
        var messages: [MessageEntity] = []
        for index in startIndex..<(startIndex + count) {
            let role: MessageRole = index % 2 == 0 ? .user : .assistant
            messages.append(
                TestData.message(
                    id: "m-\(index)",
                    role: role,
                    content: role == .user ? "第 \(index) 句：今天有点累" : "第 \(index) 句：那就早点睡",
                    createdAt: now - Int64((startIndex + count - index) * 60_000)
                )
            )
        }
        try temp.stores.messages.upsert(messages)
    }

    private func extraction(_ client: CompletionClient) -> MemoryExtraction {
        MemoryExtraction(
            stores: temp.stores,
            memoryRepository: MemoryRepository(stores: temp.stores),
            completion: client,
            now: { self.now }
        )
    }

    private func compression(_ client: CompletionClient) -> HistoryCompressionService {
        HistoryCompressionService(
            stores: temp.stores,
            completion: client,
            resolveConfig: { _ in
                CompressionConfig(
                    config: CompletionConfig(
                        id: self.configId, name: "测试", baseUrl: "https://api.example.com",
                        model: "deepseek-chat", apiKey: "sk-test"
                    ),
                    model: "deepseek-chat"
                )
            },
            now: { self.now }
        )
    }

    // MARK: - 抽记忆

    func testMemoryExtractionKeepsPreviewAndUnreadWrittenMeanwhile() async throws {
        try seedMessages(count: 8)
        let payload = #"[{"content":"对方最近总是熬夜","category":"FACT","drawer":"USER","importance":3,"confidence":0.8,"sourceQuote":"今天有点累"}]"#
        let client = InterleavingCompletionClient(text: payload) { try? self.simulateNewUserMessage() }

        let result = await extraction(client).extract(
            sessionId: sessionId,
            config: CompletionConfig(id: configId, name: "测试", baseUrl: "https://api.example.com",
                                     model: "deepseek-chat", apiKey: "sk-test"),
            model: "deepseek-chat",
            force: true
        )
        XCTAssertTrue(result.isSuccess, "\(result)")
        XCTAssertEqual(client.calls, 1)

        let session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertEqual(session.lastMessagePreview, "那我明天早点到", "写回计数时不能把新预览盖回旧值")
        XCTAssertEqual(session.unreadCount, 0)
        XCTAssertEqual(session.title, "明天早点到")
        XCTAssertEqual(session.lastMessageAt, now + 1_000)
        XCTAssertEqual(session.lastMemoryExtractedCount, 8, "自己那一列照常写")
    }

    func testMemoryExtractionSkipsWriteWhenCountWasAdvancedConcurrently() async throws {
        try seedMessages(count: 8)
        let payload = #"[{"content":"对方最近总是熬夜","category":"FACT","drawer":"USER","importance":3,"confidence":0.8,"sourceQuote":"今天有点累"}]"#
        let client = InterleavingCompletionClient(text: payload) {
            // 期间另一轮抽取/导入把计数推进到 999：这次的乐观检查必须失败。
            try? self.simulateNewUserMessage(writeCount: true)
        }

        let result = await extraction(client).extract(
            sessionId: sessionId,
            config: CompletionConfig(id: configId, name: "测试", baseUrl: "https://api.example.com",
                                     model: "deepseek-chat", apiKey: "sk-test"),
            model: "deepseek-chat",
            force: true
        )
        XCTAssertTrue(result.isSuccess, "放弃写回不是失败：这次结果只进日志")

        let session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertEqual(session.lastMemoryExtractedCount, 999, "乐观检查没过就不该写")
    }

    func testMemoryExtractionDoesNotResurrectDeletedSession() async throws {
        try seedMessages(count: 8)
        let payload = #"[{"content":"对方最近总是熬夜","category":"FACT","drawer":"USER","importance":3,"confidence":0.8,"sourceQuote":"今天有点累"}]"#
        let client = InterleavingCompletionClient(text: payload) {
            // 等待模型期间用户删了这个人（会话跟着删）。
            try? self.temp.stores.sessions.delete(id: self.sessionId)
        }

        _ = await extraction(client).extract(
            sessionId: sessionId,
            config: CompletionConfig(id: configId, name: "测试", baseUrl: "https://api.example.com",
                                     model: "deepseek-chat", apiKey: "sk-test"),
            model: "deepseek-chat",
            force: true
        )
        XCTAssertNil(try temp.stores.sessions.byId(sessionId), "会话被删了就不能被 REPLACE 插回来")
    }

    // MARK: - 压缩摘要

    func testCompressionKeepsPreviewAndUnreadWrittenMeanwhile() async throws {
        try seedMessages(count: 40)
        let client = InterleavingCompletionClient(
            text: "跟你聊过的那一段我一直记着。你最近总说累，睡得晚。"
        ) { try? self.simulateNewUserMessage() }

        let result = await compression(client).maybeCompress(sessionId: sessionId, now: now)
        XCTAssertEqual(result.status, .done, result.note)

        let session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertEqual(session.historySummary, result.summary)
        XCTAssertEqual(session.lastMessagePreview, "那我明天早点到", "写摘要不能把新预览盖回旧值")
        XCTAssertEqual(session.unreadCount, 0)
        XCTAssertEqual(session.title, "明天早点到")
    }

    func testCompressionDoesNotResurrectDeletedSession() async throws {
        try seedMessages(count: 40)
        let client = InterleavingCompletionClient(
            text: "跟你聊过的那一段我一直记着。你最近总说累，睡得晚。"
        ) {
            try? self.temp.stores.sessions.delete(id: self.sessionId)
        }

        let result = await compression(client).maybeCompress(sessionId: sessionId, now: now)
        XCTAssertEqual(result.status, .skipped, "写不回去就当没收过")
        XCTAssertNil(try temp.stores.sessions.byId(sessionId))
    }

    /// 两次压缩：只有压得更远的那一次生效（不是「先到先得」）。
    func testOnlyTheFurthestCompressionWins() throws {
        XCTAssertTrue(
            try temp.stores.sessions.updateHistorySummary(
                id: sessionId, summary: "第一次：聊到项目排期", summarizedUpTo: now - 10_000,
                summaryAt: now, updatedAt: now
            )
        )
        XCTAssertFalse(
            try temp.stores.sessions.updateHistorySummary(
                id: sessionId, summary: "第二次：只压到更早的一段", summarizedUpTo: now - 20_000,
                summaryAt: now + 5_000, updatedAt: now + 5_000
            ),
            "压得更近的一次不该盖掉已经压得更远的摘要"
        )
        var session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertEqual(session.historySummary, "第一次：聊到项目排期")
        XCTAssertEqual(session.historySummarizedUpTo, now - 10_000)

        XCTAssertTrue(
            try temp.stores.sessions.updateHistorySummary(
                id: sessionId, summary: "第三次：又往前压了一段", summarizedUpTo: now - 5_000,
                summaryAt: now + 10_000, updatedAt: now + 10_000
            ),
            "压得更远的一次应该生效"
        )
        session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertEqual(session.historySummary, "第三次：又往前压了一段")
        XCTAssertEqual(session.historySummarizedUpTo, now - 5_000)
    }

    /// 启动自检清掉坏摘要时，也只清那三列（预览/未读不动）。
    func testRepairStaleSummariesOnlyClearsSummaryColumns() throws {
        var session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        session.historySummary = "```yap-tool\n{\"name\":\"web_search\"}\n```"
        session.historySummarizedUpTo = now - 30_000
        session.historySummaryAt = now
        try temp.stores.sessions.upsert(session)
        try simulateNewUserMessage()

        let cleared = compression(FakeCompletionClient()).repairStaleSummaries()
        XCTAssertEqual(cleared, 1)

        let repaired = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertNil(repaired.historySummary)
        XCTAssertNil(repaired.historySummarizedUpTo)
        XCTAssertNil(repaired.historySummaryAt)
        XCTAssertEqual(repaired.lastMessagePreview, "那我明天早点到")
        XCTAssertEqual(repaired.unreadCount, 0)
    }
}

// MARK: - 小工具

private extension Result {
    var isSuccess: Bool {
        if case .success = self { return true }
        return false
    }
}
