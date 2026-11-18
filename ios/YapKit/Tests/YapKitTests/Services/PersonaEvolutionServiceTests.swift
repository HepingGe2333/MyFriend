import Foundation
import XCTest
@testable import YapKit

/// 人格进化的**调度与人审**（`docs/design/persona-evolution.md` §2）：
/// 20 条用户消息 / 24 小时触发、pending 落库、接受 → version+1、拒绝、7 天过期。
final class PersonaEvolutionServiceTests: XCTestCase {

    private var db: TempDatabase!
    private var completion: FakeCompletionClient!
    private var cursors: InMemoryEvolutionCursorStore!
    private var service: PersonaEvolutionService!
    private let personaId = "persona-1"
    private let sessionId = "session-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        db = try TempDatabase()
        completion = FakeCompletionClient()
        cursors = InMemoryEvolutionCursorStore()
        service = PersonaEvolutionService(
            stores: db.stores,
            completion: completion,
            cursors: cursors,
            now: { self.now }
        )
        var persona = TestData.persona(id: personaId)
        persona.traits = ["慢热", "嘴硬心软"]
        persona.likes = ["夜里散步"]
        persona.behaviors = ["先给事实"]
        try db.stores.personas.upsert(persona)
        try db.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
    }

    override func tearDownWithError() throws {
        service = nil
        cursors = nil
        completion = nil
        db.cleanUp()
        db = nil
    }

    private var config: CompletionConfig {
        CompletionConfig(id: "config-1", name: "测试", baseUrl: "https://api.example.com",
                         model: "deepseek-chat", apiKey: "sk-1")
    }

    /// 造 `count` 条用户消息；`offset` 让多次调用得到互不覆盖的 id
    /// （`messages` 是 upsert，重复 id 会把上一条盖掉，消息总数就不涨了）。
    private func insertUserMessages(_ count: Int, offset: Int = 0) throws {
        var messages: [MessageEntity] = []
        for index in 0..<count {
            let serial = offset + index
            messages.append(TestData.message(
                id: "user-\(serial)", role: .user, content: "第 \(serial) 句",
                createdAt: now - Int64(100_000 - serial) * 1000
            ))
        }
        try db.stores.messages.upsert(messages)
    }

    // MARK: - 触发节奏

    /// 第一次只记基线；之后每 20 条用户消息触发一次。
    func testTriggersEveryTwentyUserMessages() throws {
        try insertUserMessages(5)
        XCTAssertFalse(service.shouldEvaluate(personaId: personaId, now: now), "第一次只记基线")
        XCTAssertNotNil(cursors.cursor(personaId: personaId), "基线要写下游标")
        XCTAssertEqual(cursors.cursor(personaId: personaId)?.userMessageCount, 5)

        try insertUserMessages(10, offset: 5)
        XCTAssertFalse(service.shouldEvaluate(personaId: personaId, now: now), "基线是 5，新增 10 条还不够")

        try insertUserMessages(20, offset: 15)
        XCTAssertTrue(service.shouldEvaluate(personaId: personaId, now: now), "新增 30 条 ≥ 20")
    }

    /// 消息不多但过了 24 小时：也要评估一次（游标里存了上次评估的时刻）。
    func testTriggersAfterTwentyFourHours() throws {
        try insertUserMessages(3)
        XCTAssertFalse(service.shouldEvaluate(personaId: personaId, now: now))
        // 刚过 23 小时：不评估；过了 24 小时：评估。
        XCTAssertFalse(service.shouldEvaluate(personaId: personaId, now: now + 23 * 3_600_000))
        XCTAssertTrue(service.shouldEvaluate(personaId: personaId, now: now + 24 * 3_600_000))
    }

    // MARK: - 评估与落库

    /// 一次评估：调一次模型（低温度、限定输出）、把合法变化写进 pending，并推进游标。
    func testEvaluateWritesPendingPatchesAndAdvancesCursor() async throws {
        try insertUserMessages(20)
        XCTAssertFalse(service.shouldEvaluate(personaId: personaId, now: now), "先记基线")
        try insertUserMessages(20, offset: 20)
        XCTAssertTrue(service.shouldEvaluate(personaId: personaId, now: now))
        completion = FakeCompletionClient(text: """
        {"changes":[
          {"field":"traits","op":"add","value":"更耐心","reason":"最近都在耐心听","confidence":0.8},
          {"field":"likes","op":"add","value":"夜里散步","reason":"已经有了","confidence":0.9},
          {"field":"name","op":"add","value":"林深深","reason":"想改名","confidence":0.99}
        ]}
        """)
        service = PersonaEvolutionService(
            stores: db.stores, completion: completion, cursors: cursors, now: { self.now }
        )

        let result = await service.evaluate(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        let outcome = try XCTUnwrap(try? result.get())
        XCTAssertTrue(outcome.evaluated)
        XCTAssertEqual(outcome.proposed, 1, "already-there 与白名单外的字段都要丢掉")
        XCTAssertEqual(outcome.created, 1)
        XCTAssertEqual(completion.callCount, 1)
        XCTAssertEqual(completion.calls.first?.params.temperature, PersonaEvolutionService.temperature)
        XCTAssertEqual(completion.calls.first?.params.maxOutputTokens, PersonaEvolutionService.maxOutputTokens)

        let pending = service.pending(personaId: personaId)
        XCTAssertEqual(pending.count, 1)
        XCTAssertEqual(pending.first?.summary, "性格多了「更耐心」")
        XCTAssertEqual(pending.first?.reason, "最近都在耐心听")
        XCTAssertEqual(pending.first?.sessionId, sessionId)
        XCTAssertEqual(service.userMessageCount(personaId: personaId), 40)
        XCTAssertFalse(service.shouldEvaluate(personaId: personaId, now: now), "评估完游标要推进")
    }

    /// 模型输出坏 JSON：算评估过一次（推进游标），但不落任何 pending。
    func testMalformedEvaluationDoesNotWritePatches() async throws {
        try insertUserMessages(20)
        _ = service.shouldEvaluate(personaId: personaId, now: now)   // 记基线
        try insertUserMessages(20, offset: 20)
        completion = FakeCompletionClient(text: "我觉得他更耐心了")
        service = PersonaEvolutionService(
            stores: db.stores, completion: completion, cursors: cursors, now: { self.now }
        )
        let result = await service.evaluate(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        let outcome = try XCTUnwrap(try? result.get())
        XCTAssertTrue(outcome.evaluated)
        XCTAssertEqual(outcome.created, 0)
        XCTAssertTrue(service.pending(personaId: personaId).isEmpty)
        XCTAssertFalse(service.shouldEvaluate(personaId: personaId, now: now), "坏 JSON 也算过一次，别每条消息都重试")
    }

    // MARK: - 接受 / 拒绝

    /// 接受：写回人格四个字段、version+1、删掉待确认条目。
    func testApproveWritesBackAndBumpsVersion() async throws {
        let patch = PersonaPendingPatchEntity(
            id: "patch-1", personaId: personaId, sessionId: sessionId,
            patchJson: PersonaEvolution.encodePatch([
                PersonaEvolution.Change(field: .traits, operation: .add, value: "更耐心", confidence: 0.9),
                PersonaEvolution.Change(field: .likes, operation: .remove, value: "夜里散步", confidence: 0.8)
            ]),
            summary: "性格多了「更耐心」", reason: "最近很耐心", createdAt: now
        )
        try db.stores.personaPendingPatches.upsert(patch)
        let before = try XCTUnwrap(try db.stores.personas.byId(personaId))

        let result = try service.approve(patchId: "patch-1")
        XCTAssertEqual(result.applied, 2)
        XCTAssertEqual(result.persona.traits, before.traits + ["更耐心"])
        XCTAssertEqual(result.persona.version, before.version + 1)
        XCTAssertTrue(try service.pending(personaId: personaId).isEmpty, "接受后条目要删掉")
        let reloaded = try XCTUnwrap(try db.stores.personas.byId(personaId))
        XCTAssertEqual(reloaded.version, before.version + 1)
        XCTAssertEqual(reloaded.traits, before.traits + ["更耐心"])
        // 用户写的东西没被碰。
        XCTAssertEqual(reloaded.name, before.name)
        XCTAssertEqual(reloaded.prompt, before.prompt)
    }

    /// 拒绝：人格一个字都不动，条目删掉。
    func testRejectOnlyDropsTheProposal() throws {
        try db.stores.personaPendingPatches.upsert(PersonaPendingPatchEntity(
            id: "patch-2", personaId: personaId,
            patchJson: PersonaEvolution.encodePatch([
                PersonaEvolution.Change(field: .traits, operation: .remove, value: "慢热")
            ]),
            summary: "性格少了「慢热」", reason: "最近话多", createdAt: now
        ))
        let before = try XCTUnwrap(try db.stores.personas.byId(personaId))
        try service.reject(patchId: "patch-2")
        XCTAssertTrue(try service.pending(personaId: personaId).isEmpty)
        let reloaded = try XCTUnwrap(try db.stores.personas.byId(personaId))
        XCTAssertEqual(reloaded, before)
    }

    /// 待确认变化 7 天过期：读的时候顺手清掉，不会变成永远没人点的死按钮。
    func testPendingExpiresAfterSevenDays() throws {
        try db.stores.personaPendingPatches.upsert(PersonaPendingPatchEntity(
            id: "patch-old", personaId: personaId, patchJson: "[]",
            summary: "七天前的变化", reason: "", createdAt: now - PersonaEvolution.pendingTtlMillis - 1
        ))
        try db.stores.personaPendingPatches.upsert(PersonaPendingPatchEntity(
            id: "patch-new", personaId: personaId, patchJson: "[]",
            summary: "刚提出来的变化", reason: "", createdAt: now
        ))
        let pending = service.pending(personaId: personaId, at: now)
        XCTAssertEqual(pending.map(\.id), ["patch-new"])
        XCTAssertEqual(try db.stores.personaPendingPatches.count(personaId: personaId), 1)
    }

    /// 找不到条目时明确报错（界面据此提示「已经不在了」）。
    func testApproveUnknownPatchThrows() {
        XCTAssertThrowsError(try service.approve(patchId: "nope")) { error in
            XCTAssertEqual(error as? PersonaEvolutionError, .patchNotFound)
        }
    }
}
