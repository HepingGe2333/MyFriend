import XCTest
@testable import YapKit

/// 自动情绪判定的服务层（`spec/mood/README.md` §2）。
///
/// 假模型 + 假时钟 + 真库：只断言「该不该判、判完库里是什么、失败了怎么衰减」。
final class MoodJudgementServiceTests: XCTestCase {

    private var db: TempDatabase!
    private var mood: MoodRepository!
    private var cursors: InMemoryMoodJudgementCursorStore!
    private var client: FakeCompletionClient!
    private var service: MoodJudgementService!
    private var clock: Int64 = TestData.now
    private var logs: [String] = []

    private let personaId = "persona-1"
    private let sessionId = "session-1"
    private let config = CompletionConfig(
        id: "config-1", name: "测试", baseUrl: "https://api.example.com", model: "deepseek-chat"
    )

    override func setUpWithError() throws {
        db = try TempDatabase()
        cursors = InMemoryMoodJudgementCursorStore()
        clock = TestData.now
        mood = MoodRepository(stores: db.stores, now: { [unowned self] in self.clock })
        client = FakeCompletionClient(texts: [
            #"{"label":"有点累","valence":-1,"arousal":1,"dominance":2,"intensity":3,"reason":"聊到加班"}"#
        ])
        logs = []
        service = MoodJudgementService(
            stores: db.stores,
            completion: client,
            mood: mood,
            cursors: cursors,
            now: { [unowned self] in self.clock },
            log: { [unowned self] in self.logs.append($0) }
        )
        try db.stores.personas.upsert(TestData.persona(id: personaId))
        try db.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
    }

    override func tearDownWithError() throws {
        service = nil
        client = nil
        cursors = nil
        mood = nil
        db.cleanUp()
        db = nil
    }

    // MARK: - 辅助

    private func addUserMessages(_ count: Int) throws {
        for index in 0..<count {
            try db.stores.messages.upsert(TestData.message(
                id: "user-\(UUID().uuidString)-\(index)",
                sessionId: sessionId,
                role: .user,
                content: "第 \(index) 句",
                createdAt: clock + Int64(index)
            ))
        }
    }

    private func persona() throws -> PersonaEntity {
        try XCTUnwrap(try db.stores.personas.byId(personaId))
    }

    // MARK: - 触发节奏

    func testFirstCallOnlyRecordsBaseline() throws {
        try addUserMessages(4)
        XCTAssertFalse(service.shouldJudge(personaId: personaId), "第一次只记基线，不判空对话")
        XCTAssertEqual(4, cursors.cursor(personaId: personaId)?.userMessageCount)
        // 再攒 4 条就到节奏了。
        try addUserMessages(4)
        XCTAssertTrue(service.shouldJudge(personaId: personaId))
    }

    func testEveryNewUserMessageTriggersJudgement() throws {
        // 第一次调用只记基线（不判定）；之后每一条新的用户消息都该触发。
        try addUserMessages(1)
        XCTAssertFalse(service.shouldJudge(personaId: personaId), "第一次只记基线")
        try addUserMessages(1)
        XCTAssertTrue(service.shouldJudge(personaId: personaId), "0.15：每条用户消息后都判一次")
        try addUserMessages(1)
        XCTAssertTrue(service.shouldJudge(personaId: personaId))
        try addUserMessages(1)
        XCTAssertTrue(service.shouldJudge(personaId: personaId))
    }

    func testTwentyFourHoursTriggersEvenWithoutNewMessages() throws {
        try addUserMessages(1)
        _ = service.shouldJudge(personaId: personaId)
        clock += MoodJudgementRules.triggerIntervalMillis - 60_000
        XCTAssertFalse(service.shouldJudge(personaId: personaId))
        clock += 120_000
        XCTAssertTrue(service.shouldJudge(personaId: personaId), "24 小时到了也要判一次")
    }

    func testNoMessagesNeverJudges() {
        XCTAssertFalse(service.shouldJudge(personaId: personaId))
    }

    // MARK: - 判定与写库

    func testJudgeWritesDimensionsAndUsesBackgroundKind() async throws {
        try addUserMessages(4)
        let result = await service.judge(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        let outcome = try XCTUnwrap(try? result.get())
        XCTAssertTrue(outcome.evaluated)
        XCTAssertTrue(outcome.written)
        XCTAssertEqual(1, client.callCount)
        XCTAssertEqual(.extraction, client.calls.first?.kind, "复用 EXTRACTION 类目，不新增用量类目")

        let stored = try persona()
        XCTAssertEqual("有点累", stored.mood)
        XCTAssertEqual(3, stored.moodIntensity)
        XCTAssertEqual(-1, stored.moodValence)
        XCTAssertEqual(1, stored.moodArousal)
        XCTAssertEqual(2, stored.moodDominance)
        XCTAssertEqual("聊到加班", stored.moodReason)
        XCTAssertEqual(clock, stored.moodUpdatedAt)
        XCTAssertFalse(logs.isEmpty)
    }

    func testJudgeLimitsHowFarMoodCanMoveInOneRound() async throws {
        // 先把心情做成「雀跃（2/5/5）」。
        var seed = try persona()
        seed.mood = "雀跃"
        seed.moodIntensity = 5
        seed.moodValence = 2
        seed.moodArousal = 5
        seed.moodDominance = 5
        seed.moodUpdatedAt = clock
        try db.stores.personas.upsert(seed)
        client = FakeCompletionClient(texts: [
            #"{"label":"崩溃","valence":-2,"arousal":0,"dominance":0,"intensity":5,"reason":"一下子"}"#
        ])
        service = MoodJudgementService(
            stores: db.stores, completion: client, mood: mood, cursors: cursors,
            now: { [unowned self] in self.clock }, log: { _ in }
        )
        try addUserMessages(4)
        _ = await service.judge(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        let stored = try persona()
        // 0.15：取消「每维最多动 2」，直觉是什么就是什么。
        XCTAssertEqual(-2, stored.moodValence)
        XCTAssertEqual(0, stored.moodArousal)
        XCTAssertEqual(0, stored.moodDominance)
    }

    func testNoVisibleChangeDoesNotTouchTheRow() async throws {
        var seed = try persona()
        seed.mood = "平静"
        seed.moodIntensity = 2
        seed.moodValence = 0
        seed.moodArousal = 2
        seed.moodDominance = 3
        seed.moodUpdatedAt = clock - 60_000
        try db.stores.personas.upsert(seed)
        client = FakeCompletionClient(texts: [
            #"{"label":"平静","valence":0,"arousal":2,"dominance":3,"intensity":2}"#
        ])
        service = MoodJudgementService(
            stores: db.stores, completion: client, mood: mood, cursors: cursors,
            now: { [unowned self] in self.clock }, log: { _ in }
        )
        try addUserMessages(4)
        let result = await service.judge(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        let outcome = try XCTUnwrap(try? result.get())
        XCTAssertFalse(outcome.written, "没有明显变化就不写")
        let stored = try persona()
        XCTAssertEqual(clock - 60_000, stored.moodUpdatedAt, "不写就不能把更新时间顶新")
    }

    func testBadJsonIsSkippedAndCursorStillAdvances() async throws {
        client = FakeCompletionClient(texts: ["今天有点累"])
        service = MoodJudgementService(
            stores: db.stores, completion: client, mood: mood, cursors: cursors,
            now: { [unowned self] in self.clock }, log: { [unowned self] in self.logs.append($0) }
        )
        try addUserMessages(4)
        let result = await service.judge(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        let outcome = try XCTUnwrap(try? result.get())
        XCTAssertTrue(outcome.evaluated)
        XCTAssertFalse(outcome.written)
        XCTAssertEqual(clock, cursors.cursor(personaId: personaId)?.lastJudgedAt,
                       "坏 JSON 也推进游标：下一条用户消息不该再烧一次调用")
        XCTAssertNil(try persona().moodValence)
    }

    // MARK: - 失败衰减（规范 §2）

    func testFailureDecaysMoodByHourAndDoesNotThrow() async throws {
        var seed = try persona()
        seed.mood = "烦"
        seed.moodIntensity = 4
        seed.moodValence = -2
        seed.moodArousal = 5
        seed.moodDominance = 0
        seed.moodUpdatedAt = clock - 3 * 3_600_000
        try db.stores.personas.upsert(seed)

        client = FakeCompletionClient([.failure(CompletionFailure("网络断了"))])
        service = MoodJudgementService(
            stores: db.stores, completion: client, mood: mood, cursors: cursors,
            now: { [unowned self] in self.clock }, log: { [unowned self] in self.logs.append($0) }
        )
        try addUserMessages(4)
        let result = await service.judge(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        let outcome = try XCTUnwrap(try? result.get(), "失败不抛给调用方")
        XCTAssertFalse(outcome.evaluated)
        let stored = try persona()
        // 3 小时：每维向 0/2/3 靠 3 步 → 回到基准。
        XCTAssertEqual(0, stored.moodValence)
        XCTAssertEqual(2, stored.moodArousal)
        XCTAssertEqual(3, stored.moodDominance)
        XCTAssertTrue(logs.contains { $0.contains("判定失败") })
    }

    func testDecayWithoutApiConfigStillMovesMood() throws {
        var seed = try persona()
        seed.mood = "烦"
        seed.moodIntensity = 4
        seed.moodValence = -2
        seed.moodArousal = 5
        seed.moodDominance = 0
        seed.moodUpdatedAt = clock - 2 * 3_600_000
        try db.stores.personas.upsert(seed)

        XCTAssertTrue(service.decay(personaId: personaId))
        let stored = try persona()
        // 2 小时：每维最多动 2 格 → (-2/5/0) → (0/3/2)。
        XCTAssertEqual(0, stored.moodValence)
        XCTAssertEqual(3, stored.moodArousal)
        XCTAssertEqual(2, stored.moodDominance)
    }
}
