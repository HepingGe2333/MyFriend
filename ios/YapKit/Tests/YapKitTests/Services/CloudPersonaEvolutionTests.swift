import Foundation
import XCTest
@testable import YapKit

/// 云端模式（服务端权威）下的人格进化（0.13 第二阶段：**服务端**评估 / 待审 / 接受拒绝）。
///
/// 钉住六件事：
/// 1. **触发**：服务端回复落库后按同一套节流问一次 `POST …/evolution/evaluate`，
///    提议进本地 `persona_pending_patches` 缓存；同一窗口内不重复问；
/// 2. **待审同步**：`GET …/evolution/pending` 覆盖本地缓存（换设备后「他最近的变化」还在）；
/// 3. **接受**：先 `POST …/{patchId}/accept`，**服务端成功之后**才写本地四字段 + version；
/// 4. **失败保留**：网络/上游失败 → 待审项保留、本地人格不动、错误抛给界面；
/// 5. **过期清理**：404/410（补丁不在了）→ 本地那条也删掉；
/// 6. **拒绝**：只调 `reject` + 删本地待审项，人格一个字不动。
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
        cloud = makeCloud()
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

    private func makeCloud(serverPersonaId: @escaping (String) -> String = { $0 }) -> CloudPersonaEvolution {
        let client = ServerAPIClient(
            config: ServerConfig(baseURL: ServerTestSupport.defaultBaseURL, token: "secret-token"),
            session: ServerURLProtocolStub.makeSession()
        )
        return CloudPersonaEvolution(
            client: client,
            evolution: evolution,
            stores: db.stores,
            serverPersonaId: serverPersonaId,
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

    private var version: Int { get throws {
        try currentPersona().version
    } }

    private func lastRequestBody() throws -> [String: Any] {
        let data = try XCTUnwrap(ServerURLProtocolStub.recordedBodies.last)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    /// 服务端评估的响应：提议一条 traits 变化。
    private static func evaluationJSON(alreadyPending: Bool = false) -> String {
        """
        {"personaId":"p1","proposed":1,"alreadyPending":\(alreadyPending),
         "summary":"性格多了「更耐心」",
         "patch":{"id":"patch-1","personaId":"p1","sessionId":"s1",
           "patchJson":"{\\"changes\\":[{\\"field\\":\\"traits\\",\\"op\\":\\"add\\",\\"value\\":\\"更耐心\\",\\"reason\\":\\"最近都在耐心听\\"}]}",
           "summary":"性格多了「更耐心」","reason":"最近都在耐心听",
           "createdAt":\(1_794_500_000_000),"expiresAt":\(1_794_500_000_000 + 604_800_000),
           "changes":[{"field":"traits","op":"add","value":"更耐心","reason":"最近都在耐心听"}]}}
        """
    }

    /// 服务端接受的响应：四字段已经合并、version=4，人格回执里带 traits。
    private static let acceptanceJSON = """
    {"personaId":"p1","patchId":"patch-1","changed":true,"version":4,
     "applied":[{"field":"traits","op":"add","value":"更耐心","reason":"最近都在耐心听"}],
     "persona":{"id":"p1","name":"林深","description":"慢热的人","isDefault":true,
       "createdAt":11,"updatedAt":\(1_794_500_000_000),"prompt":"你叫林深，说话慢。",
       "version":4,"traits":["慢热","嘴硬心软","更耐心"],"likes":["夜里散步"],
       "behaviors":["先给事实"],"quirks":"喜欢喝热牛奶",
       "worldJson":"{\\"worldName\\":\\"临港新城\\",\\"notes\\":\\"完整原文\\"}"}}
    """

    // MARK: - 触发与评估

    /// 到节奏 → 问服务端一次，提议进本地待审缓存；同一窗口内不再问。
    func testEvaluateCallsServerAndCachesPendingPatch() async throws {
        try makeThrottleDue()
        ServerURLProtocolStub.enqueue(.json(Self.evaluationJSON()))

        let evaluation = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertEqual(evaluation?.proposed, 1)
        XCTAssertEqual(evaluation?.patch?.id, "patch-1")
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests.count, 1)
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/personas/p1/evolution/evaluate")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer secret-token")

        // 提议进本地缓存（界面读这张表）。
        let pending = (try? db.stores.personaPendingPatches.byPersona(personaId)) ?? []
        XCTAssertEqual(pending.count, 1)
        XCTAssertEqual(pending.first?.id, "patch-1")
        XCTAssertEqual(pending.first?.summary, "性格多了「更耐心」")
        XCTAssertFalse(pending.first?.patchJson.isEmpty ?? true)

        // 游标已推进：同一个窗口内再问一次不会再发请求。
        let again = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertNil(again)
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests.count, 1)
        XCTAssertEqual(evolution.inFlightCount, 0, "名额要还回去")
    }

    /// 没到节奏：一个请求都不发（这条路径每轮回复都会走，必须便宜）。
    func testEvaluationSkippedWhenThrottleNotDue() async throws {
        try insertUserMessages(3)
        let evaluation = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertNil(evaluation)
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty)
        XCTAssertNotNil(cursors.cursor(personaId: personaId), "第一次只记基线")
    }

    /// 单飞：同一个人格正在评估时，云端触发直接放弃（不发请求、不排队）。
    func testSingleFlightSkipsWhileEvaluationInFlight() async throws {
        try makeThrottleDue()
        XCTAssertTrue(evolution.beginEvaluation(personaId: personaId), "模拟本地模式正在评估")

        let skipped = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertNil(skipped)
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty)

        evolution.endEvaluation(personaId: personaId)
        ServerURLProtocolStub.enqueue(.json(Self.evaluationJSON()))
        let after = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertNotNil(after, "名额还回去之后可以正常评估")
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests.count, 1)
    }

    /// 切模式不重复：本地模式与云端共用同一份游标。
    func testModeSwitchSharesCursorAndDoesNotReevaluate() async throws {
        try makeThrottleDue()
        ServerURLProtocolStub.enqueue(.json(Self.evaluationJSON()))
        _ = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests.count, 1)

        XCTAssertFalse(
            evolution.shouldEvaluate(personaId: personaId, now: now),
            "本地模式看到的是同一份游标：刚评估过，不能再评估"
        )
    }

    /// 服务端没配模型（503 `llm_not_configured`）：不写待审、不抛、只记日志；
    /// 游标一样推进（别让下一句话再白跑一次请求）。
    func testEvaluateFailureKeepsNothingAndAdvancesCursor() async throws {
        try makeThrottleDue()
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"code":"llm_not_configured","message":"服务端没有可用的模型配置"}}"#,
            status: 503
        ))
        let evaluation = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertNil(evaluation)
        XCTAssertTrue((try? db.stores.personaPendingPatches.byPersona(personaId))?.isEmpty ?? true)
        XCTAssertFalse(evolution.shouldEvaluate(personaId: personaId, now: now), "失败也推进游标")
    }

    /// 本机人格 id 与服务端 id 不同时，请求要打到服务端那个 id 上（首次同步另发 id 的真实情况）。
    func testUsesMappedServerPersonaId() async throws {
        cloud = makeCloud(serverPersonaId: { _ in "server-p1" })
        try makeThrottleDue()
        ServerURLProtocolStub.enqueue(.json(Self.evaluationJSON()))
        _ = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertEqual(
            ServerURLProtocolStub.recordedRequests.first?.url?.path,
            "/v1/personas/server-p1/evolution/evaluate"
        )
        // 本地缓存仍然挂在本机人格 id 上（界面按本机人格分组）。
        XCTAssertEqual(
            (try? db.stores.personaPendingPatches.byPersona(personaId))?.count,
            1
        )
    }

    // MARK: - 待审同步

    /// 服务端是权威：`/pending` 覆盖本地缓存（多的删、缺的补、内容变了覆盖）。
    func testRefreshPendingReplacesLocalCache() async throws {
        try db.stores.personaPendingPatches.upsert([
            pendingPatch(id: "local-only"),
            pendingPatch(id: "patch-1")
        ])
        ServerURLProtocolStub.enqueue(.json("""
        {"personaId":"p1","serverTime":\(now),"patches":[
          {"id":"patch-1","personaId":"p1","sessionId":"s1",
           "patchJson":"{\\"changes\\":[{\\"field\\":\\"likes\\",\\"op\\":\\"add\\",\\"value\\":\\"下雨天\\"}]}",
           "summary":"喜欢的事多了「下雨天」","reason":"你提过喜欢雨天",
           "createdAt":\(now - 1000),"expiresAt":\(now + 1000),
           "changes":[{"field":"likes","op":"add","value":"下雨天","reason":"你提过喜欢雨天"}]},
          {"id":"patch-2","personaId":"p1","patchJson":"{\\"changes\\":[{\\"field\\":\\"quirks\\",\\"op\\":\\"add\\",\\"value\\":\\"爱记天气\\"}]}",
           "summary":"个人设定多了「爱记天气」","reason":"最近常看天气","createdAt":\(now),
           "changes":[{"field":"quirks","op":"add","value":"爱记天气","reason":"最近常看天气"}]}
        ]}
        """))

        let count = await cloud.refreshPending(personaId: personaId)
        XCTAssertEqual(count, 2)
        let pending = (try? db.stores.personaPendingPatches.byPersona(personaId)) ?? []
        XCTAssertEqual(Set(pending.map(\.id)), ["patch-1", "patch-2"])
        XCTAssertEqual(
            pending.first { $0.id == "patch-1" }?.summary,
            "喜欢的事多了「下雨天」",
            "本地那条旧内容被服务端覆盖"
        )
        XCTAssertNil(pending.first { $0.id == "local-only" }, "服务端没有的本地待审项要清掉")
        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/v1/personas/p1/evolution/pending")
    }

    // MARK: - 接受

    /// 接受：先推服务端，成功之后写本地四字段 + version。
    func testApproveAppliesServerPersonaAndBumpsVersion() async throws {
        try db.stores.personaPendingPatches.upsert([pendingPatch()])
        ServerURLProtocolStub.enqueue(.json(Self.acceptanceJSON))

        let approval = try await cloud.approve(patchId: "patch-1")

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/personas/p1/evolution/patch-1/accept")

        XCTAssertEqual(approval.applied, 1)
        XCTAssertEqual(approval.serverVersion, 4)
        XCTAssertTrue(approval.persona.traits.contains("更耐心"))
        XCTAssertEqual(try version, 4, "版本跟服务端回执走（不是本地自己 +1）")

        let persona = try currentPersona()
        XCTAssertEqual(persona.traits, ["慢热", "嘴硬心软", "更耐心"])
        XCTAssertEqual(persona.likes, ["夜里散步"], "其它字段不动")
        XCTAssertEqual(persona.quirks, "喜欢喝热牛奶")
        XCTAssertEqual(persona.worldJson, #"{"worldName":"临港新城","notes":"完整原文"}"#, "世界设定不被回执抹掉")
        XCTAssertTrue(
            (try? db.stores.personaPendingPatches.byPersona(personaId))?.isEmpty ?? true,
            "接受之后待审项要删掉"
        )
    }

    /// 服务端说「去重后没有真正变化」（`changed=false`）：本地不升版本，但条目照样删掉。
    func testApproveWithNoRealChangeKeepsVersionButDropsPatch() async throws {
        try db.stores.personaPendingPatches.upsert([pendingPatch()])
        let versionBefore = try version
        ServerURLProtocolStub.enqueue(.json("""
        {"personaId":"p1","patchId":"patch-1","changed":false,"version":\(versionBefore),
         "applied":[],"persona":{"id":"p1","name":"林深","version":\(versionBefore),
         "traits":["慢热","嘴硬心软"],"likes":["夜里散步"],"behaviors":["先给事实"],
         "quirks":"喜欢喝热牛奶"}}
        """))

        let approval = try await cloud.approve(patchId: "patch-1")
        XCTAssertEqual(approval.applied, 0)
        XCTAssertEqual(try version, versionBefore, "没有真正变化就不升版本")
        XCTAssertTrue((try? db.stores.personaPendingPatches.byPersona(personaId))?.isEmpty ?? true)
    }

    /// 服务端失败（502）：**待审项保留、本地人格不动**，错误抛给界面。
    func testApproveFailureKeepsPendingAndLocalPersona() async throws {
        try db.stores.personaPendingPatches.upsert([pendingPatch()])
        let before = try currentPersona()
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"code":"llm_upstream_error","message":"上游模型返回 500"}}"#,
            status: 502
        ))

        do {
            _ = try await cloud.approve(patchId: "patch-1")
            XCTFail("失败应当抛给界面")
        } catch {
            XCTAssertEqual((error as? ServerAPIError)?.message, "上游模型返回 500")
        }
        XCTAssertEqual(
            (try? db.stores.personaPendingPatches.byPersona(personaId))?.count,
            1,
            "失败要保留待审项，用户还能再点一次"
        )
        XCTAssertEqual(try currentPersona(), before, "本地人格一个字都不能动")
    }

    /// 补丁已经过期（410 `patch_expired`）：本地那条删掉（它已经没意义了），错误照样抛。
    func testApproveExpiredPatchDropsLocalPending() async throws {
        try db.stores.personaPendingPatches.upsert([pendingPatch()])
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"code":"patch_expired","message":"这条待审补丁已经过期（7 天），已自动丢弃"}}"#,
            status: 410
        ))
        do {
            _ = try await cloud.approve(patchId: "patch-1")
            XCTFail("过期应当抛给界面")
        } catch {
            XCTAssertEqual(
                (error as? ServerAPIError)?.code,
                "patch_expired"
            )
        }
        XCTAssertTrue(
            (try? db.stores.personaPendingPatches.byPersona(personaId))?.isEmpty ?? true,
            "服务端说它不在了，本地也不该继续挂着"
        )
    }

    /// 本机没有这条待审项（比如界面上的旧行）：直接报「这条变化已经不在了」，不发请求。
    func testApproveUnknownLocalPatchDoesNotCallServer() async throws {
        do {
            _ = try await cloud.approve(patchId: "missing")
            XCTFail("应当抛 patchNotFound")
        } catch {
            XCTAssertEqual(error as? PersonaEvolutionError, .patchNotFound)
        }
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty)
    }

    // MARK: - 拒绝

    /// 拒绝：只调服务端的 reject，人格与 `version` 一个字都不动。
    func testRejectCallsServerAndDropsLocalPatchOnly() async throws {
        try db.stores.personaPendingPatches.upsert([pendingPatch()])
        let before = try currentPersona()
        ServerURLProtocolStub.enqueue(.json("""
        {"personaId":"p1","patchId":"patch-1","rejected":true}
        """))

        try await cloud.reject(patchId: "patch-1")

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/personas/p1/evolution/patch-1/reject")
        XCTAssertTrue((try? db.stores.personaPendingPatches.byPersona(personaId))?.isEmpty ?? true)
        XCTAssertEqual(try currentPersona(), before, "拒绝不动人格")
    }
}
