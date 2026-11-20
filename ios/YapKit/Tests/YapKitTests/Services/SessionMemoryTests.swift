import Foundation
import XCTest
@testable import YapKit

/// 0.14 会话备忘录（`spec/experience/README.md` §6）：节流（5 / 30 条）、上限（200 / 500 字）、
/// 失败保留旧摘要、注入顺序（短期在前、长期在后）。
final class SessionMemoryTests: XCTestCase {

    private var temp: TempDatabase!
    private var stores: DatabaseStores { temp.stores }
    private let personaId = "persona-1"
    private let sessionId = "session-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        try stores.personas.upsert(TestData.persona(id: personaId, sleepEnabled: false))
        try stores.sessions.upsert(TestData.session(
            id: sessionId, personaId: personaId, lastMessageAt: now
        ))
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    /// 造 `count` 条用户消息（时间递增，全部未拉黑）。
    private func seedUserMessages(_ count: Int) throws {
        for index in 0..<count {
            try stores.messages.upsert(MessageEntity(
                id: "u-\(index)", sessionId: sessionId, personaId: personaId,
                role: .user, content: "第 \(index) 句",
                createdAt: now + Int64(index), updatedAt: now + Int64(index)
            ))
        }
    }

    // MARK: - 纯逻辑

    func testLimits() {
        XCTAssertEqual(SessionMemoryPolicy.shortLimit, 200)
        XCTAssertEqual(SessionMemoryPolicy.longLimit, 500)
        XCTAssertEqual(SessionMemoryPolicy.shortInterval, 5)
        XCTAssertEqual(SessionMemoryPolicy.longInterval, 30)
        XCTAssertEqual(SessionMemoryPolicy.shortRounds, 5)
    }

    func testThrottleUsesCountDifference() {
        XCTAssertFalse(SessionMemoryPolicy.shouldUpdate(currentUserCount: 4, upTo: 0, interval: 5))
        XCTAssertTrue(SessionMemoryPolicy.shouldUpdate(currentUserCount: 5, upTo: 0, interval: 5))
        XCTAssertFalse(SessionMemoryPolicy.shouldUpdate(currentUserCount: 9, upTo: 5, interval: 5))
        XCTAssertTrue(SessionMemoryPolicy.shouldUpdate(currentUserCount: 10, upTo: 5, interval: 5))
        // 失败没写 upTo：下一次回复会重试（不是「等到下一个 5 的倍数」）。
        XCTAssertTrue(SessionMemoryPolicy.shouldUpdate(currentUserCount: 6, upTo: 0, interval: 5))
    }

    func testCleanCapsAndFlattens() {
        let long = String(repeating: "字", count: 300)
        XCTAssertEqual(SessionMemoryPolicy.clean(long, limit: 200)?.count, 200)
        XCTAssertEqual(SessionMemoryPolicy.clean("a\nb", limit: 10), "a b")
        XCTAssertNil(SessionMemoryPolicy.clean("   ", limit: 10))
    }

    func testMemoBlockPutsShortBeforeLong() {
        let block = SessionMemoryPolicy.memoBlock(short: "最近在赶方案", long: "他换工作了")
        let shortRange = try! XCTUnwrap(block.range(of: "最近在赶方案"))
        let longRange = try! XCTUnwrap(block.range(of: "他换工作了"))
        XCTAssertLessThan(shortRange.lowerBound, longRange.lowerBound, "短期在前、长期在后")
        XCTAssertTrue(block.contains("## 你的备忘录"))
        XCTAssertEqual(SessionMemoryPolicy.memoBlock(short: nil, long: nil), "")
        XCTAssertEqual(SessionMemoryPolicy.memoBlock(short: "只有短期", long: nil).contains("更早以前"), false)
    }

    // MARK: - 仓储

    func testRepositoryWritesEachSummaryIndependentlyAndCaps() throws {
        let repository = SessionMemoryRepository(stores: stores, now: { self.now })
        repository.applyShort(
            sessionId: sessionId, personaId: personaId,
            summary: String(repeating: "短", count: 260), userCount: 5, at: now
        )
        var memory = try XCTUnwrap(try stores.sessionMemory.bySession(sessionId))
        XCTAssertEqual(memory.shortSummary?.count, 200)
        XCTAssertEqual(memory.shortUserCount, 5)
        XCTAssertNil(memory.longSummary, "只写短期时不该凭空造出长期")

        repository.applyLong(
            sessionId: sessionId, personaId: personaId,
            summary: String(repeating: "长", count: 700), userCount: 30, at: now + 1000
        )
        memory = try XCTUnwrap(try stores.sessionMemory.bySession(sessionId))
        XCTAssertEqual(memory.longSummary?.count, 500)
        XCTAssertEqual(memory.longUserCount, 30)
        XCTAssertEqual(memory.shortSummary?.count, 200, "写长期不该冲掉短期")
        XCTAssertEqual(memory.shortUserCount, 5)
    }

    // MARK: - 服务

    /// 假摘要器：记录调用，按队列返回（nil = 这次失败）。
    private final class FakeSummarizer: SessionSummarizing {
        var shortResults: [String?]
        var longResults: [String?]
        private(set) var shortCalls = 0
        private(set) var longCalls = 0
        private(set) var shortEarlierCounts: [Int] = []

        init(short: [String?] = [], long: [String?] = []) {
            self.shortResults = short
            self.longResults = long
        }

        func summarizeShort(
            persona: PersonaEntity,
            session: ChatSessionEntity,
            recent: [MessageEntity],
            previous: String?,
            now: Int64
        ) async -> String? {
            shortCalls += 1
            shortEarlierCounts.append(recent.count)
            return shortResults.isEmpty ? nil : shortResults.removeFirst()
        }

        func summarizeLong(
            persona: PersonaEntity,
            session: ChatSessionEntity,
            earlier: [MessageEntity],
            previous: String?,
            now: Int64
        ) async -> String? {
            longCalls += 1
            return longResults.isEmpty ? nil : longResults.removeFirst()
        }
    }

    func testServiceSkipsUntilFiveUserMessages() async throws {
        try seedUserMessages(4)
        let summarizer = FakeSummarizer(short: ["不该用"])
        let service = SessionMemoryService(
            stores: stores, summarizer: summarizer, now: { self.now }
        )
        await service.updateIfNeeded(sessionId: sessionId, personaId: personaId, now: now)
        XCTAssertEqual(summarizer.shortCalls, 0)
        XCTAssertNil(try stores.sessionMemory.bySession(sessionId))
    }

    func testServiceUpdatesShortAtFiveAndLongAtThirty() async throws {
        let summarizer = FakeSummarizer(
            short: ["最近他在赶方案", "又更新了一次短期"],
            long: ["更早以前他换了工作"]
        )
        let service = SessionMemoryService(
            stores: stores, summarizer: summarizer, now: { self.now }
        )

        try seedUserMessages(5)
        await service.updateIfNeeded(sessionId: sessionId, personaId: personaId, now: now)
        var memory = try XCTUnwrap(try stores.sessionMemory.bySession(sessionId))
        XCTAssertEqual(memory.shortSummary, "最近他在赶方案")
        XCTAssertEqual(summarizer.longCalls, 0, "30 条才更新长期")

        try seedUserMessages(30)
        await service.updateIfNeeded(sessionId: sessionId, personaId: personaId, now: now + 1000)
        memory = try XCTUnwrap(try stores.sessionMemory.bySession(sessionId))
        XCTAssertEqual(memory.shortSummary, "又更新了一次短期")
        XCTAssertEqual(memory.longSummary, "更早以前他换了工作")
        XCTAssertEqual(summarizer.shortCalls, 2)
        XCTAssertEqual(summarizer.longCalls, 1)
    }

    func testFailureKeepsOldSummaryAndRetriesNextTime() async throws {
        try seedUserMessages(5)
        let first = FakeSummarizer(short: ["旧摘要"])
        let service = SessionMemoryService(stores: stores, summarizer: first, now: { self.now })
        await service.updateIfNeeded(sessionId: sessionId, personaId: personaId, now: now)
        XCTAssertEqual(try stores.sessionMemory.bySession(sessionId)?.shortSummary, "旧摘要")

        // 再来 5 条，但这次模型失败：旧摘要必须留着，且 upTo 不前进（下次还会重试）。
        try seedUserMessages(10)
        let failing = FakeSummarizer(short: [nil])
        let retryService = SessionMemoryService(stores: stores, summarizer: failing, now: { self.now })
        await retryService.updateIfNeeded(sessionId: sessionId, personaId: personaId, now: now + 1000)
        let memory = try XCTUnwrap(try stores.sessionMemory.bySession(sessionId))
        XCTAssertEqual(memory.shortSummary, "旧摘要", "失败保留旧摘要（规范 §6）")
        XCTAssertEqual(memory.shortUserCount, 5, "失败不该把「已更新到第几条」往前推")
        XCTAssertEqual(failing.shortCalls, 1)
    }

    func testBlockedMessagesDoNotCountOrEnterSummaries() async throws {
        try seedUserMessages(4)
        // 第 5 条是在拉黑期发的：不该算进「每 5 条」，也不该被摘要看到。
        try stores.messages.upsert(MessageEntity(
            id: "u-blocked", sessionId: sessionId, personaId: personaId,
            role: .user, content: "被拉黑时说的话",
            createdAt: now + 100, updatedAt: now + 100, blocked: true
        ))
        let summarizer = FakeSummarizer(short: ["摘要"])
        let service = SessionMemoryService(stores: stores, summarizer: summarizer, now: { self.now })
        await service.updateIfNeeded(sessionId: sessionId, personaId: personaId, now: now)
        XCTAssertEqual(summarizer.shortCalls, 0, "拉黑期间的消息不进备忘录")
    }

    func testPromptBlockIsInjectedIntoSystemPrompt() throws {
        let repository = SessionMemoryRepository(stores: stores, now: { self.now })
        repository.applyShort(
            sessionId: sessionId, personaId: personaId, summary: "最近在赶方案", userCount: 5, at: now
        )
        repository.applyLong(
            sessionId: sessionId, personaId: personaId, summary: "他换了工作", userCount: 30, at: now
        )
        let block = repository.promptBlock(sessionId: sessionId)
        XCTAssertTrue(block.contains("你的备忘录"))
        XCTAssertLessThan(
            try XCTUnwrap(block.range(of: "最近在赶方案")).lowerBound,
            try XCTUnwrap(block.range(of: "他换了工作")).lowerBound
        )
        let system = PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: "你叫林岸。", personaName: "林岸", memoBlock: block
        ))
        XCTAssertTrue(system.contains("最近在赶方案"))
        XCTAssertTrue(system.contains("他换了工作"))
        // 备忘录是可变区域，不该混进人设（`# 你是谁` 那一段里）。
        let identityRange = try XCTUnwrap(system.range(of: "# 你是谁"))
        let firstSeparator = try XCTUnwrap(system.range(of: "---"))
        let identitySection = system[identityRange.lowerBound..<firstSeparator.lowerBound]
        XCTAssertFalse(identitySection.contains("最近在赶方案"))
    }
}
