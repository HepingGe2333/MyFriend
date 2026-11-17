import Foundation
import XCTest
@testable import YapKit

/// 云端模式（服务端权威）下的人格进化：
///
/// 1. **触发**：服务端回复落库后按同一套节流（20 条用户消息 / 24h）评估一次，
///    结果写本地 `persona_pending_patches`（与服务端模式下的「人格权威」不冲突：提议而已）；
/// 2. **接受**：四字段合并成一次 `POST /v1/personas`（保留其它字段），成功后才写本地缓存
///    并 `version+1`；失败**保留**待审项、本地不动；
/// 3. **拒绝**：只删本地待审项，一个请求都不发；
/// 4. **不重复**：与本地模式共用游标与单飞闸——切模式、两条触发路径同时命中，都只评估一次。
///
/// 用真临时库（`TempDatabase`）+ URLProtocol 桩（`ServerURLProtocolStub`），不联网。
final class CloudPersonaEvolutionTests: XCTestCase {

    private var db: TempDatabase!
    private var completion: FakeCompletionClient!
    private var cursors: InMemoryEvolutionCursorStore!
    private var evolution: PersonaEvolutionService!
    private var cloud: CloudPersonaEvolution!
    private let personaId = "p1"
    private let sessionId = "s1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerURLProtocolStub.reset()
        db = try TempDatabase()
        completion = FakeCompletionClient()
        cursors = InMemoryEvolutionCursorStore()
        evolution = PersonaEvolutionService(
            stores: db.stores,
            completion: completion,
            cursors: cursors,
            now: { self.now }
        )
        cloud = CloudPersonaEvolution(
            evolution: evolution,
            writes: makeWrites(),
            stores: db.stores,
            now: { self.now }
        )
        var persona = TestData.persona(id: personaId, name: "林深")
        persona.traits = ["慢热", "嘴硬心软"]
        persona.likes = ["夜里散步"]
        persona.behaviors = ["先给事实"]
        persona.quirks = "喜欢喝热牛奶"
        persona.version = 3
        persona.prompt = "你叫林深，说话慢。"
        persona.worldJson = #"{"worldName":"临港新城","notes":"完整原文"}"#
        try db.stores.personas.upsert(persona)
        try db.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
    }

    override func tearDownWithError() throws {
        ServerURLProtocolStub.reset()
        cloud = nil
        evolution = nil
        cursors = nil
        completion = nil
        db.cleanUp()
        db = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private var config: CompletionConfig {
        CompletionConfig(
            id: "config-1", name: "测试", baseUrl: "https://api.example.com",
            model: "deepseek-chat", apiKey: "sk-1"
        )
    }

    private func makeWrites() -> ServerWriteService {
        let client = ServerAPIClient(
            config: ServerConfig(baseURL: ServerTestSupport.defaultBaseURL, token: "secret-token"),
            session: ServerURLProtocolStub.makeSession()
        )
        return ServerWriteService(
            client: client,
            stores: db.stores,
            memoryRepository: MemoryRepository(stores: db.stores),
            now: { self.now }
        )
    }

    private func insertUserMessages(_ count: Int, offset: Int = 0) throws {
        var messages: [MessageEntity] = []
        for index in 0..<count {
            let serial = offset + index
            messages.append(TestData.message(
                id: "user-\(serial)", sessionId: sessionId, personaId: personaId,
                role: .user, content: "第 \(serial) 句", createdAt: now - Int64(100_000 - serial) * 1000
            ))
        }
        try db.stores.messages.upsert(messages)
    }

    /// 记一次基线，再灌够 20 条新的用户消息：`shouldEvaluate` 从此为 true。
    private func makeThrottleDue() throws {
        try insertUserMessages(20)
        XCTAssertFalse(evolution.shouldEvaluate(personaId: personaId, now: now), "第一次只记基线")
        try insertUserMessages(20, offset: 20)
        XCTAssertTrue(evolution.shouldEvaluate(personaId: personaId, now: now), "新增 20 条该评估了")
    }

    private func pendingPatch(
        id: String = "patch-1",
        changes: [PersonaEvolution.Change] = [
            PersonaEvolution.Change(
                field: .traits, operation: .add, value: "更耐心",
                reason: "最近都在耐心听", confidence: 0.9
            )
        ]
    ) -> PersonaPendingPatchEntity {
        PersonaPendingPatchEntity(
            id: id, personaId: personaId, sessionId: sessionId,
            patchJson: PersonaEvolution.encodePatch(changes),
            summary: "性格多了「更耐心」", reason: "最近都在耐心听", createdAt: now
        )
    }

    private func currentPersona() throws -> PersonaEntity {
        try XCTUnwrap((try? db.stores.personas.byId(personaId)) ?? nil)
    }

    private func lastRequestBody() throws -> [String: Any] {
        let data = try XCTUnwrap(ServerURLProtocolStub.recordedBodies.last)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    /// 服务端回执：刻意**不带** `version` 与四个字段（模拟「extras 不回显」的服务端），
    /// 用来验证本地缓存仍然会拿到刚接受的变化与 `version+1`。
    private static let personaEnvelope = #"""
    {"persona":{"id":"p1","name":"林深","description":"慢热的人","isDefault":true,
      "createdAt":11,"updatedAt":222,"prompt":"你叫林深，说话慢。",
      "worldJson":"{\"worldName\":\"临港新城\",\"notes\":\"完整原文\"}"}}
    """#

    // MARK: - 触发与评估

    /// 服务端模式下到节奏 → 评估一次、写本地待审项；紧接着再问一次不再评估（游标已推进）。
    func testServerReplyTriggersEvaluationOnceAndWritesPending() async throws {
        try makeThrottleDue()
        completion = FakeCompletionClient(text: """
        {"changes":[
          {"field":"traits","op":"add","value":"更耐心","reason":"最近都在耐心听","confidence":0.9},
          {"field":"quirks","op":"add","value":"开始记天气","reason":"最近总提天气","confidence":0.8}
        ]}
        """)
        evolution = PersonaEvolutionService(
            stores: db.stores, completion: completion, cursors: cursors, now: { self.now }
        )
        cloud = CloudPersonaEvolution(
            evolution: evolution, writes: makeWrites(), stores: db.stores, now: { self.now }
        )

        let outcome = await cloud.evaluateIfNeeded(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        XCTAssertEqual(outcome?.evaluated, true)
        XCTAssertEqual(outcome?.created, 2)
        XCTAssertEqual(completion.callCount, 1, "到节奏了就该调一次模型")
        XCTAssertEqual(evolution.pending(personaId: personaId).count, 2, "结果写本地待审项")
        XCTAssertEqual(evolution.inFlightCount, 0, "名额要还回去")

        // 同一轮里紧接着再问一次（比如另一条触发路径也命中）：游标已推进 → 不再调模型。
        let again = await cloud.evaluateIfNeeded(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        XCTAssertNil(again)
        XCTAssertEqual(completion.callCount, 1, "同一个节流窗口内不重复评估")
    }

    /// 没到节奏：一次模型都不调（这条路径每轮回复都会走，判定必须便宜且不烧调用）。
    func testEvaluationSkippedWhenThrottleNotDue() async throws {
        try insertUserMessages(3)
        let outcome = await cloud.evaluateIfNeeded(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        XCTAssertNil(outcome)
        XCTAssertEqual(completion.callCount, 0)
        XCTAssertNotNil(cursors.cursor(personaId: personaId), "第一次只记基线")
    }

    /// 单飞：同一个人格正在评估时，云端触发直接放弃（不再调模型、不排队）。
    func testSingleFlightSkipsWhileEvaluationInFlight() async throws {
        try makeThrottleDue()
        XCTAssertTrue(evolution.beginEvaluation(personaId: personaId), "模拟本地模式正在评估")

        let outcome = await cloud.evaluateIfNeeded(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        XCTAssertNil(outcome)
        XCTAssertEqual(completion.callCount, 0, "同一个人的评估还没结束，不该再烧一次调用")

        evolution.endEvaluation(personaId: personaId)
        let after = await cloud.evaluateIfNeeded(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        XCTAssertNotNil(after, "名额还回去之后可以正常评估")
        XCTAssertEqual(completion.callCount, 1)
    }

    /// 切模式不重复：本地模式记下的基线/游标与云端共用，切过去不会把同一段对话再评估一遍。
    func testModeSwitchSharesCursorAndDoesNotReevaluate() async throws {
        try makeThrottleDue()
        _ = await cloud.evaluateIfNeeded(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        XCTAssertEqual(completion.callCount, 1)

        // 「切回本地模式」：`ChatBackgroundService` 第一问就是同一个 `shouldEvaluate`。
        XCTAssertFalse(
            evolution.shouldEvaluate(personaId: personaId, now: now),
            "本地模式看到的是同一份游标：刚评估过，不能再评估"
        )
        // 再切回云端，同样不评估。
        let backToCloud = await cloud.evaluateIfNeeded(
            personaId: personaId, sessionId: sessionId, config: config, model: "deepseek-chat"
        )
        XCTAssertNil(backToCloud)
        XCTAssertEqual(completion.callCount, 1)
    }

    // MARK: - 接受

    /// 接受：`POST /v1/personas`（整份人格、其它字段原样保留）→ 本地缓存四个字段更新、`version+1`、
    /// 待审项删除。
    func testApprovePostsPersonaAndUpdatesLocalCache() async throws {
        let patch = pendingPatch()
        try db.stores.personaPendingPatches.upsert(patch)
        ServerURLProtocolStub.enqueue(.json(Self.personaEnvelope))
        let before = try currentPersona()

        let result = try await cloud.approve(patchId: patch.id)

        // 1) 打了正确的接口与 body
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.last)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/personas")
        let body = try lastRequestBody()
        XCTAssertEqual(body["id"] as? String, personaId)
        XCTAssertEqual(body["version"] as? Int, before.version + 1, "推上去的就是第 N+1 版")
        XCTAssertEqual(body["traits"] as? [String], before.traits + ["更耐心"], "四字段合并后推服务端")
        XCTAssertEqual(body["likes"] as? [String], before.likes, "没变的字段也要带上（整份人格）")
        XCTAssertEqual(body["quirks"] as? String, before.quirks)
        XCTAssertEqual(body["name"] as? String, "林深", "名字/简介/手写提示词原样保留")
        XCTAssertEqual(body["prompt"] as? String, before.prompt)
        XCTAssertEqual(body["worldJson"] as? String, before.worldJson, "世界设定的完整原文要保留")

        // 2) 本地缓存：四个字段写回、version+1、待审项删掉
        XCTAssertEqual(result.applied, 1)
        XCTAssertEqual(result.persona.traits, before.traits + ["更耐心"])
        XCTAssertEqual(result.persona.version, before.version + 1)
        let reloaded = try currentPersona()
        XCTAssertEqual(reloaded.traits, before.traits + ["更耐心"], "本地缓存跟着服务端一起更新")
        XCTAssertEqual(reloaded.version, before.version + 1)
        XCTAssertEqual(reloaded.name, before.name)
        XCTAssertEqual(reloaded.prompt, before.prompt)
        XCTAssertTrue(evolution.pending(personaId: personaId).isEmpty, "接受后条目要删掉")
    }

    /// 服务端失败：**保留**待审项、本地人格一个字都不动，错误抛给界面。
    func testApproveFailureKeepsPendingAndLocalCache() async throws {
        let patch = pendingPatch()
        try db.stores.personaPendingPatches.upsert(patch)
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"message":"名字不能为空","code":"missing_field"}}"#, status: 400
        ))
        let before = try currentPersona()

        do {
            _ = try await cloud.approve(patchId: patch.id)
            XCTFail("服务端拒绝时应当抛错")
        } catch let error as ServerAPIError {
            XCTAssertEqual(error.message, "名字不能为空")
        }
        XCTAssertEqual(try currentPersona(), before, "服务端失败时本地不能改")
        XCTAssertEqual(
            evolution.pending(personaId: personaId).map(\.id), [patch.id],
            "失败后待审项还在，用户可以再点一次"
        )
    }

    /// 这条已经不成立了（人格里本来就有）：不发请求，条目丢掉，并且不算失败。
    func testApproveWhenNothingAppliesDropsPatchWithoutRequest() async throws {
        var persona = try currentPersona()
        persona.traits.append("更耐心")
        try db.stores.personas.upsert(persona)
        let patch = pendingPatch()
        try db.stores.personaPendingPatches.upsert(patch)

        let result = try await cloud.approve(patchId: patch.id)
        XCTAssertEqual(result.applied, 0)
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty, "没有可写的就不该打扰服务端")
        XCTAssertTrue(evolution.pending(personaId: personaId).isEmpty)
        XCTAssertEqual(try currentPersona().version, persona.version, "没生效就不加版本号")
    }

    // MARK: - 拒绝

    /// 拒绝：只删待审项，一个请求都不发、人格不动。
    func testRejectOnlyDropsPendingPatch() throws {
        let patch = pendingPatch()
        try db.stores.personaPendingPatches.upsert(patch)
        let before = try currentPersona()

        try cloud.reject(patchId: patch.id)

        XCTAssertTrue(evolution.pending(personaId: personaId).isEmpty)
        XCTAssertEqual(try currentPersona(), before)
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty, "拒绝是纯本地动作")
    }
}
