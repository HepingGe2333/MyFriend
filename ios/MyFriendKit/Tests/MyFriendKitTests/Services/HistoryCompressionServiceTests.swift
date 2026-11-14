import Foundation
import XCTest
@testable import MyFriendKit

/// 历史压缩的落库编排（`Services/HistoryCompressionService.swift`）。
///
/// 对齐 Android 的 `data/repo/HistoryCompressor.kt`，重点钉住三件事：
/// 1. **最近 N 条逐字保留**、够一批就往前压一段（一次最多 40 条）；
/// 2. 写回 `historySummary` / `historySummarizedUpTo` / `historySummaryAt`，**原文一条不删**；
/// 3. **守卫**：模型把工具协议形状当成正文写回来时，整段拒收（1.7 事故的教训）。
final class HistoryCompressionServiceTests: XCTestCase {

    private var temp: TempDatabase!
    private var completion: FakeCompletionClient!
    private var compression: HistoryCompressionService!
    private let sessionId = "session-1"
    private let personaId = "persona-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        completion = FakeCompletionClient(text: "跟你聊过的那一段我一直记着。你常熬夜，夜里容易心里发慌。")
        try temp.stores.personas.upsert(TestData.persona(id: personaId))
        try temp.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
        compression = HistoryCompressionService(
            stores: temp.stores,
            completion: completion,
            resolveConfig: { _ in
                CompressionConfig(
                    config: CompletionConfig(id: "config-1", name: "测试", baseUrl: "https://api.example.com",
                                            model: "deepseek-chat", apiKey: "sk-test"),
                    model: "deepseek-chat"
                )
            },
            now: { self.now }
        )
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    // MARK: - 该不该压

    func testShortConversationIsSkipped() async throws {
        try seedMessages(count: 10)
        let result = await compression.maybeCompress(sessionId: sessionId, now: now)
        XCTAssertEqual(result.status, .skipped)
        XCTAssertEqual(completion.callCount, 0, "不够长就不该花一次模型调用")
        let session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertNil(session.historySummary)
    }

    // MARK: - 压成一段

    func testCompressesOlderChunkAndKeepsEveryMessage() async throws {
        try seedMessages(count: 40)
        let before = try temp.stores.messages.bySession(sessionId)
        let result = await compression.maybeCompress(sessionId: sessionId, now: now)

        XCTAssertEqual(result.status, .done, result.note)
        XCTAssertEqual(result.messageCount, HistoryCompression.DEFAULT_MAX_BATCH > 16 ? 16 : 16)
        XCTAssertEqual(completion.callCount, 1)
        let call = try XCTUnwrap(completion.calls.first)
        XCTAssertEqual(call.kind, .history, "用量要记成「整理记性」，不混进聊天")
        XCTAssertEqual(call.params.temperature, 0.2)
        XCTAssertEqual(call.params.maxOutputTokens, 500)
        XCTAssertTrue(call.messages.first(where: { $0.role == "system" })?
            .content.contains("收成你自己还留得住的一句话") == true)

        let session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertEqual(session.historySummary, result.summary)
        XCTAssertEqual(session.historySummaryAt, now)
        XCTAssertNotNil(session.historySummarizedUpTo)
        // 摘要覆盖到被压那批的最后一条，且严格早于「逐字保留段」。
        XCTAssertEqual(session.historySummarizedUpTo, result.compressedUpTo)

        // 原文一条都不删。
        let after = try temp.stores.messages.bySession(sessionId)
        XCTAssertEqual(before.count, after.count)
        XCTAssertEqual(before.map(\.id), after.map(\.id))
    }

    func testSecondRunMergesWithPreviousSummary() async throws {
        try seedMessages(count: 40)
        _ = await compression.maybeCompress(sessionId: sessionId, now: now)
        let firstSession = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        let firstUpTo = try XCTUnwrap(firstSession.historySummarizedUpTo)

        // 再聊 40 条：窗口里更早的部分都已经被压过，只剩新的一段可以压。
        try seedMessages(count: 40, startIndex: 40, baseTimestamp: firstUpTo + 10_000)
        completion = FakeCompletionClient(text: "那段聊到你手上的项目还没忙完，压力主要在排期。")
        compression = HistoryCompressionService(
            stores: temp.stores,
            completion: completion,
            resolveConfig: { _ in
                CompressionConfig(
                    config: CompletionConfig(id: "config-1", name: "测试",
                                            baseUrl: "https://api.example.com",
                                            model: "deepseek-chat", apiKey: "sk-test"),
                    model: "deepseek-chat"
                )
            },
            now: { self.now }
        )
        let result = await compression.maybeCompress(sessionId: sessionId, now: now + 60_000)
        XCTAssertEqual(result.status, .done, result.note)
        let merged = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertEqual(merged.historySummary, result.summary)
        XCTAssertGreaterThan(try XCTUnwrap(merged.historySummarizedUpTo), firstUpTo, "摘要要往前推，不能倒退")
    }

    // MARK: - 守卫：工具协议形状一律拒收

    func testRefusesSummaryThatLooksLikeAToolCall() async throws {
        try seedMessages(count: 40)
        let toolText = """
        嗯，这个我想记下来。

        ```myfriend-tool
        {"name":"remember","arguments":{"content":"对方怕冷"}}
        ```
        """
        completion = FakeCompletionClient(text: toolText)
        compression = HistoryCompressionService(
            stores: temp.stores,
            completion: completion,
            resolveConfig: { _ in
                CompressionConfig(
                    config: CompletionConfig(id: "config-1", name: "测试",
                                            baseUrl: "https://api.example.com",
                                            model: "deepseek-chat", apiKey: "sk-test"),
                    model: "deepseek-chat"
                )
            },
            now: { self.now }
        )
        let result = await compression.maybeCompress(sessionId: sessionId, now: now)
        XCTAssertEqual(result.status, .refused)
        XCTAssertEqual(result.summary, "")
        let session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertNil(session.historySummary, "带工具协议形状的回复绝不能被当成他的回忆")
        XCTAssertNil(session.historySummarizedUpTo)
    }

    func testRefusesRefusalAndPlaceholder() async throws {
        try seedMessages(count: 40)
        for text in ["抱歉，我无法完成这个整理。", "无"] {
            completion = FakeCompletionClient(text: text)
            compression = HistoryCompressionService(
                stores: temp.stores,
                completion: completion,
                resolveConfig: { _ in
                    CompressionConfig(
                        config: CompletionConfig(id: "config-1", name: "测试",
                                                baseUrl: "https://api.example.com",
                                                model: "deepseek-chat", apiKey: "sk-test"),
                        model: "deepseek-chat"
                    )
                },
                now: { self.now }
            )
            let result = await compression.maybeCompress(sessionId: sessionId, now: now)
            XCTAssertEqual(result.status, .refused, "「\(text)」不该被当真")
        }
    }

    func testMissingConfigIsReportedNotSilent() async throws {
        try seedMessages(count: 40)
        let bare = HistoryCompressionService(
            stores: temp.stores,
            completion: completion,
            resolveConfig: { _ in nil },
            now: { self.now }
        )
        let result = await bare.maybeCompress(sessionId: sessionId, now: now)
        XCTAssertEqual(result.status, .noConfig)
        XCTAssertEqual(completion.callCount, 0)
    }

    func testConcurrentRunForSameSessionIsSkipped() async throws {
        try seedMessages(count: 40)
        async let first = compression.maybeCompress(sessionId: sessionId, now: now)
        async let second = compression.maybeCompress(sessionId: sessionId, now: now)
        let results = await [first, second]
        XCTAssertEqual(results.filter { $0.status == .done }.count, 1, "同一段对话同一时间只压一次")
        XCTAssertEqual(completion.callCount, 1)
    }

    // MARK: - 启动自检

    func testRepairClearsStaleSummariesButKeepsHealthyOnes() throws {
        var broken = TestData.session(id: "s-broken", personaId: personaId)
        broken.historySummary = "```myfriend-tool\n{\"name\":\"remember\"}\n```"
        broken.historySummarizedUpTo = 123
        try temp.stores.sessions.upsert(broken)

        // 1.9 起 `chat_sessions.personaId` 有唯一索引：两条会话必须挂两个不同的人格，
        // 否则 `INSERT OR REPLACE` 会把前一条顶掉。
        try temp.stores.personas.upsert(TestData.persona(id: "persona-2", isDefault: false))
        var healthy = TestData.session(id: "s-healthy", personaId: "persona-2")
        healthy.historySummary = "你常熬夜，夜里容易心里发慌，想有人安静陪着。"
        healthy.historySummarizedUpTo = 456
        try temp.stores.sessions.upsert(healthy)

        let cleared = compression.repairStaleSummaries()
        XCTAssertEqual(cleared, 1)
        let fixed = try XCTUnwrap(try temp.stores.sessions.byId("s-broken"))
        XCTAssertNil(fixed.historySummary, "写坏的旧摘要要清掉，下次重新压")
        XCTAssertNil(fixed.historySummarizedUpTo)
        let kept = try XCTUnwrap(try temp.stores.sessions.byId("s-healthy"))
        XCTAssertNotNil(kept.historySummary)
        XCTAssertEqual(kept.historySummarizedUpTo, 456)
    }

    // MARK: - 夹具

    private func seedMessages(count: Int, startIndex: Int = 0, baseTimestamp: Int64 = 1_000_000) throws {
        var messages: [MessageEntity] = []
        for index in 0..<count {
            let number = startIndex + index
            messages.append(TestData.message(
                id: "m-\(number)",
                role: number % 2 == 0 ? .user : .assistant,
                content: "第 \(number) 条：聊了点日常，也约好了周末一起去看海。",
                createdAt: baseTimestamp + Int64(number) * 60_000
            ))
        }
        try temp.stores.messages.upsert(messages)
    }
}
