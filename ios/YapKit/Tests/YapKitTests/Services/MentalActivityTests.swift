import Foundation
import XCTest
@testable import YapKit

/// 0.14 心理活动（`spec/experience/README.md` §3）：触发条件、念头截断、心情微调、
/// 只留 50 条、下一轮提示词注入。
final class MentalActivityTests: XCTestCase {

    private var temp: TempDatabase!
    private var stores: DatabaseStores { temp.stores }
    private let personaId = "persona-1"
    private let sessionId = "session-1"
    /// 2026-11-14 前后，固定时钟；所有断言都不跟真实时间赛跑。
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        try stores.personas.upsert(TestData.persona(
            id: personaId, sleepEnabled: false
        ))
        try stores.sessions.upsert(TestData.session(
            id: sessionId, personaId: personaId, lastMessageAt: now - 60_000
        ))
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    private func mood(_ arousal: Int = 2, _ valence: Int = 0) -> MoodState {
        MoodState(
            label: "平静", intensity: 2,
            dimensions: MoodDimensions(valence: valence, arousal: arousal, dominance: 3)
        )
    }

    // MARK: - 活跃窗口

    func testActiveWindowIsTenMinutes() {
        XCTAssertFalse(ActiveWindow.isActive(lastMessageAt: nil, now: now))
        XCTAssertTrue(ActiveWindow.isActive(lastMessageAt: now, now: now))
        XCTAssertTrue(ActiveWindow.isActive(lastMessageAt: now - ActiveWindow.windowMillis, now: now))
        XCTAssertFalse(ActiveWindow.isActive(lastMessageAt: now - ActiveWindow.windowMillis - 1, now: now))
    }

    // MARK: - 触发判定

    func testNoActivityOutsideActiveWindow() {
        XCTAssertFalse(MentalActivityPolicy.shouldGenerate(
            lastMessageAt: now - 11 * 60_000,
            now: now,
            lastActivityAt: nil,
            userMessagesSinceLastActivity: 0,
            mood: mood(),
            lastMood: nil
        ))
    }

    func testFirstActivityHappensImmediatelyInActiveWindow() {
        XCTAssertTrue(MentalActivityPolicy.shouldGenerate(
            lastMessageAt: now, now: now,
            lastActivityAt: nil, userMessagesSinceLastActivity: 0,
            mood: mood(), lastMood: nil
        ))
    }

    func testThrottleByThreeMinutes() {
        let last = now - 2 * 60_000
        XCTAssertFalse(MentalActivityPolicy.shouldGenerate(
            lastMessageAt: now, now: now,
            lastActivityAt: last, userMessagesSinceLastActivity: 1,
            mood: mood(), lastMood: mood()
        ), "2 分钟、只过了 1 轮：不该再做")
        XCTAssertTrue(MentalActivityPolicy.shouldGenerate(
            lastMessageAt: now, now: now,
            lastActivityAt: now - 3 * 60_000, userMessagesSinceLastActivity: 1,
            mood: mood(), lastMood: mood()
        ), "满 3 分钟：该做")
    }

    func testThrottleByThreeRounds() {
        XCTAssertTrue(MentalActivityPolicy.shouldGenerate(
            lastMessageAt: now, now: now - 30_000,
            lastActivityAt: now - 60_000, userMessagesSinceLastActivity: 3,
            mood: mood(), lastMood: mood()
        ), "3 轮到了：即使不到 3 分钟也该做")
        XCTAssertFalse(MentalActivityPolicy.shouldGenerate(
            lastMessageAt: now, now: now - 30_000,
            lastActivityAt: now - 60_000, userMessagesSinceLastActivity: 2,
            mood: mood(), lastMood: mood()
        ))
    }

    func testHighArousalAlwaysTriggers() {
        XCTAssertTrue(MentalActivityPolicy.shouldGenerate(
            lastMessageAt: now, now: now - 10_000,
            lastActivityAt: now - 20_000, userMessagesSinceLastActivity: 0,
            mood: mood(5), lastMood: mood(5)
        ), "唤醒度 ≥4：必做（规范 §3）")
    }

    func testBigMoodSwingAlwaysTriggers() {
        // 三维曼哈顿距离：valence 2 + arousal 0 + dominance 0 = 2 → 必做。
        XCTAssertTrue(MentalActivityPolicy.shouldGenerate(
            lastMessageAt: now, now: now - 10_000,
            lastActivityAt: now - 20_000, userMessagesSinceLastActivity: 0,
            mood: mood(2, 2), lastMood: mood(2, 0)
        ))
        XCTAssertFalse(MentalActivityPolicy.shouldGenerate(
            lastMessageAt: now, now: now - 10_000,
            lastActivityAt: now - 20_000, userMessagesSinceLastActivity: 0,
            mood: mood(2, 1), lastMood: mood(2, 0)
        ), "只差 1：不算「变化 ≥2」")
    }

    // MARK: - 念头与心情的清洗

    func testThoughtIsFlattenedToOneLineAndCappedAt60() {
        let long = String(repeating: "想", count: 80) + "\n第二行"
        let draft = MentalActivityDraft(thought: long, valenceDelta: 9, arousalDelta: -9, dominanceDelta: 2)
        XCTAssertEqual(draft.thought.count, 60)
        XCTAssertFalse(draft.thought.contains("\n"))
        XCTAssertEqual(draft.valenceDelta, 1, "每维 ≤1")
        XCTAssertEqual(draft.arousalDelta, -1)
        XCTAssertEqual(draft.dominanceDelta, 1)
        XCTAssertNil(MentalActivityPolicy.sanitizeThought("   \n  "))
    }

    func testAdjustedDimensionsNeverMoveMoreThanOneAndStayInRange() {
        let base = MoodDimensions(valence: 2, arousal: 0, dominance: 5)
        let draft = MentalActivityDraft(thought: "想", valenceDelta: 5, arousalDelta: -5, dominanceDelta: 5)
        let adjusted = MentalActivityPolicy.adjusted(base, by: draft)
        XCTAssertEqual(adjusted.valence, 2, "已经在上界：+1 也只能停在上界")
        XCTAssertEqual(adjusted.arousal, 0)
        XCTAssertEqual(adjusted.dominance, 5)
    }

    // MARK: - 仓储

    func testRepositoryWritesMoodSnapshotAndKeepsFifty() throws {
        let repository = MentalActivityRepository(stores: stores, now: { self.now })
        let state = mood(3)
        for index in 0..<55 {
            repository.record(
                personaId: personaId, sessionId: sessionId,
                thought: "第 \(index) 句", mood: state, at: now + Int64(index)
            )
        }
        XCTAssertEqual(
            try stores.mentalActivities.count(where: "personaId = ?", [.text(personaId)]),
            MentalActivityPolicy.retention,
            "只保留最近 50 条（规范 §3）"
        )
        XCTAssertEqual(repository.latest(personaId: personaId)?.thought, "第 54 句")
        XCTAssertEqual(repository.latest(personaId: personaId)?.moodArousal, 3, "心情快照要落库")
    }

    func testPromptLineUsesTheRequiredWordingAndOnlyWhenFresh() {
        let repository = MentalActivityRepository(stores: stores, now: { self.now })
        repository.record(
            personaId: personaId, sessionId: sessionId, thought: "他今天说话好轻", mood: mood(), at: now
        )
        let line = repository.promptLine(personaId: personaId, now: now + 60_000)
        XCTAssertTrue(line.contains("你刚才心里想："))
        XCTAssertTrue(line.contains("他今天说话好轻"))
        XCTAssertEqual(
            repository.promptLine(personaId: personaId, now: now + MentalActivityPolicy.promptFreshnessMillis + 1),
            "",
            "陈旧念头不该塞进当前这一轮"
        )
    }

    // MARK: - 服务

    /// 假生成器：记录调用次数，返回固定念头 + 微调。
    private final class FakeGenerator: MentalActivityGenerating {
        var drafts: [MentalActivityDraft?]
        private(set) var calls: [(personaId: String, recentCount: Int, mood: MoodState)] = []
        init(_ drafts: [MentalActivityDraft?]) { self.drafts = drafts }

        func generate(
            persona: PersonaEntity,
            session: ChatSessionEntity?,
            recent: [MessageEntity],
            mood: MoodState,
            now: Int64
        ) async -> MentalActivityDraft? {
            calls.append((persona.id, recent.count, mood))
            guard !drafts.isEmpty else { return nil }
            return drafts.removeFirst()
        }
    }

    private func seedDialogue() throws {
        try stores.messages.upsert(MessageEntity(
            id: "u-1", sessionId: sessionId, personaId: personaId,
            role: .user, content: "今天有点累", createdAt: now - 60_000, updatedAt: now - 60_000
        ))
        try stores.messages.upsert(MessageEntity(
            id: "a-1", sessionId: sessionId, personaId: personaId,
            role: .assistant, content: "那早点休息", createdAt: now - 30_000, updatedAt: now - 30_000
        ))
        var session = try XCTUnwrap(try stores.sessions.byId(sessionId))
        session.lastMessageAt = now - 30_000
        try stores.sessions.upsert(session)
    }

    func testServiceWritesActivityAndAdjustsMoodWithinOnePerDimension() async throws {
        try seedDialogue()
        let moodRepository = MoodRepository(stores: stores, now: { self.now })
        try stores.personas.upsert(TestData.persona(
            id: personaId, sleepEnabled: false,
            mood: "平静", moodIntensity: 2
        ))
        // 先给三维定一个已知起点（2/3/3）：微调后应落在 (1..3, 2..4, 2..4) 之间。
        _ = moodRepository.setMood(personaId: personaId, label: "平静", intensity: 3, now: now - 1000)

        let generator = FakeGenerator([
            MentalActivityDraft(thought: "其实我也挺累的", valenceDelta: -1, arousalDelta: 1, dominanceDelta: -1)
        ])
        let service = MentalActivityService(
            stores: stores,
            mood: moodRepository,
            generator: generator,
            now: { self.now }
        )
        let outcome = await service.runIfNeeded(personaId: personaId, sessionId: sessionId, now: now)
        let saved = try XCTUnwrap(outcome)
        XCTAssertEqual(saved.thought, "其实我也挺累的")
        XCTAssertEqual(generator.calls.count, 1)
        XCTAssertGreaterThan(generator.calls[0].recentCount, 0, "生成时应当看得到最近几句")
        XCTAssertEqual(
            try stores.mentalActivities.count(where: "personaId = ?", [.text(personaId)]), 1
        )
        // 三维每维最多动 1，且落库在 persona 上。
        let persona = try XCTUnwrap(try stores.personas.byId(personaId))
        XCTAssertEqual(persona.moodValence, -1)
        XCTAssertEqual(persona.moodArousal, 4)
        XCTAssertEqual(persona.moodDominance, 2)
    }

    func testServiceRespectsThrottle() async throws {
        try seedDialogue()
        let moodRepository = MoodRepository(stores: stores, now: { self.now })
        let generator = FakeGenerator([
            MentalActivityDraft(thought: "第一句"),
            MentalActivityDraft(thought: "第二句")
        ])
        let service = MentalActivityService(
            stores: stores, mood: moodRepository, generator: generator, now: { self.now }
        )
        _ = await service.runIfNeeded(personaId: personaId, sessionId: sessionId, now: now)
        // 一分钟后再来：既不满 3 分钟、也没有新的一轮 → 不该再调模型。
        _ = await service.runIfNeeded(personaId: personaId, sessionId: sessionId, now: now + 60_000)
        XCTAssertEqual(generator.calls.count, 1)
    }

    func testServiceSkipsWhileBlockedAndWhenGeneratorFails() async throws {
        try seedDialogue()
        let moodRepository = MoodRepository(stores: stores, now: { self.now })
        var persona = try XCTUnwrap(try stores.personas.byId(personaId))
        persona.blockedUntil = now + 3_600_000
        try stores.personas.upsert(persona)

        let generator = FakeGenerator([MentalActivityDraft(thought: "不该出现")])
        let service = MentalActivityService(
            stores: stores, mood: moodRepository, generator: generator, now: { self.now }
        )
        let blocked = await service.runIfNeeded(personaId: personaId, sessionId: sessionId, now: now)
        XCTAssertNil(blocked)
        XCTAssertEqual(generator.calls.count, 0, "拉黑期不做心理活动")

        // 解除后再试：生成器返回 nil（模型失败）→ 不写库、不报错。
        persona.blockedUntil = nil
        try stores.personas.upsert(persona)
        let failing = FakeGenerator([nil])
        let failingService = MentalActivityService(
            stores: stores, mood: moodRepository, generator: failing, now: { self.now }
        )
        let none = await failingService.runIfNeeded(
            personaId: personaId, sessionId: sessionId, now: now
        )
        XCTAssertNil(none)
        XCTAssertEqual(try stores.mentalActivities.count(where: nil, []), 0)
    }

    // MARK: - 提示词注入

    func testPromptBuilderPlacesMentalActivityAfterMood() {
        let system = PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: "你叫林岸。",
            personaName: "林岸",
            memoBlock: "\n\n## 你的备忘录\n- 最近几轮：他在忙",
            mentalActivityBlock: "\n\n你刚才心里想：今天他好像不太开心",
            moodBlock: "\n\n# 你现在的心情\n平静"
        ))
        let moodRange = try! XCTUnwrap(system.range(of: "# 你现在的心情"))
        let mindRange = try! XCTUnwrap(system.range(of: "你刚才心里想："))
        let memoRange = try! XCTUnwrap(system.range(of: "## 你的备忘录"))
        XCTAssertLessThan(moodRange.lowerBound, mindRange.lowerBound, "念头在心情之后")
        XCTAssertLessThan(mindRange.lowerBound, memoRange.lowerBound, "备忘录在念头之后")
    }
}
