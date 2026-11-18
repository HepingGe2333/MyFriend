import Foundation
import XCTest
@testable import YapKit

/// 云端情绪（0.13 第二阶段）：`POST /v1/personas/{id}/mood/evaluate` 的契约与编排。
///
/// 钉住四件事：
/// 1. **请求形状**：POST 到 `/v1/personas/{id}/mood/evaluate`、带鉴权、没有 body 字段；
/// 2. **写本地缓存**：三维 + label + intensity 一起写进人格的 `mood*` 列（界面/离线/心跳都读它）；
/// 3. **节流**：每 4 条用户消息 / 24 小时，第一次只记基线；
/// 4. **服务端没配模型**（200 + source=decay、mood 为空）与**网络失败**都不该把本地心情改坏。
final class ServerMoodEvaluateTests: XCTestCase {

    private var db: TempDatabase!
    private var mood: MoodRepository!
    private var cursors: InMemoryMoodJudgementCursorStore!
    private var cloud: CloudMoodJudgement!
    private let personaId = "p1"
    private let sessionId = "s1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerURLProtocolStub.reset()
        db = try TempDatabase()
        mood = MoodRepository(stores: db.stores, now: { self.now })
        cursors = InMemoryMoodJudgementCursorStore()
        cloud = makeCloud()
        try db.stores.personas.upsert([TestData.persona(id: personaId, name: "林深")])
        try db.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
    }

    override func tearDownWithError() throws {
        ServerURLProtocolStub.reset()
        cloud = nil
        cursors = nil
        mood = nil
        db.cleanUp()
        db = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func makeCloud(serverPersonaId: @escaping (String) -> String = { $0 }) -> CloudMoodJudgement {
        CloudMoodJudgement(
            client: ServerAPIClient(
                config: ServerConfig(baseURL: ServerTestSupport.defaultBaseURL, token: "secret-token"),
                session: ServerURLProtocolStub.makeSession()
            ),
            stores: db.stores,
            mood: mood,
            cursors: cursors,
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
                role: .user, content: "第 \(serial) 句", createdAt: now - Int64(10_000 - serial) * 1000
            ))
        }
        try db.stores.messages.upsert(messages)
    }

    /// 记一次基线，再灌够 4 条新的用户消息：`shouldEvaluate` 从此为 true。
    private func makeThrottleDue() throws {
        try insertUserMessages(4)
        XCTAssertFalse(cloud.shouldEvaluate(personaId: personaId, at: now), "第一次只记基线")
        try insertUserMessages(4, offset: 4)
        XCTAssertTrue(cloud.shouldEvaluate(personaId: personaId, at: now), "新增 4 条该判了")
    }

    private static let moodJSON = """
    {"personaId":"p1","changed":true,"source":"llm",
     "mood":{"personaId":"p1","label":"有点累","intensity":3,
       "valence":-1,"arousal":2,"dominance":3,
       "reason":"聊到加班","updatedAt":1794500000000}}
    """

    // MARK: - 客户端契约

    func testClientPostsToEvaluateEndpointAndDecodesDimensions() async throws {
        ServerURLProtocolStub.enqueue(.json(Self.moodJSON))
        let client = ServerAPIClient(
            config: ServerConfig(baseURL: ServerTestSupport.defaultBaseURL, token: "secret-token"),
            session: ServerURLProtocolStub.makeSession()
        )
        let evaluation = try await client.evaluateMood(personaId: "p1")
        XCTAssertTrue(evaluation.changed)
        XCTAssertEqual(evaluation.source, "llm")
        XCTAssertTrue(evaluation.usedModel)
        let mood = try XCTUnwrap(evaluation.mood)
        XCTAssertEqual(mood.label, "有点累")
        XCTAssertEqual(mood.intensity, 3)
        XCTAssertEqual(mood.reason, "聊到加班")
        XCTAssertEqual(mood.dimensions?.valence, -1)
        XCTAssertEqual(mood.dimensions?.arousal, 2)
        XCTAssertEqual(mood.dimensions?.dominance, 3)

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/personas/p1/mood/evaluate")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer secret-token")
    }

    /// 老服务端不下发三维：`dimensions` 为 nil（退回词表推断，而不是假装 0/2/3）。
    func testMissingDimensionsFallBackToNil() async throws {
        ServerURLProtocolStub.enqueue(.json("""
        {"personaId":"p1","changed":true,"source":"llm",
         "mood":{"personaId":"p1","label":"安静","intensity":2,"updatedAt":1}}
        """))
        let client = ServerAPIClient(
            config: ServerConfig(baseURL: ServerTestSupport.defaultBaseURL, token: "secret-token"),
            session: ServerURLProtocolStub.makeSession()
        )
        let evaluation = try await client.evaluateMood(personaId: "p1")
        XCTAssertNil(evaluation.mood?.dimensions)
    }

    // MARK: - 编排

    /// 到节奏 → 请求一次 → 三维 + label + intensity 写进本地缓存。
    func testEvaluateWritesLocalMoodCacheWithDimensions() async throws {
        try makeThrottleDue()
        ServerURLProtocolStub.enqueue(.json(Self.moodJSON))

        let outcome = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertEqual(outcome?.requested, true)
        XCTAssertEqual(outcome?.written, true)
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests.count, 1)

        let state = mood.current(personaId: personaId, now: now)
        XCTAssertEqual(state.label, "有点累")
        XCTAssertEqual(state.intensity, 3)
        XCTAssertEqual(state.reason, "聊到加班")
        XCTAssertEqual(state.dimensions?.valence, -1)
        XCTAssertEqual(state.dimensions?.arousal, 2)
        XCTAssertEqual(state.dimensions?.dominance, 3)
        XCTAssertEqual(state.updatedAt, now)

        // 人格行上的四列 + 三维也要一起落库（界面与提示词读的就是它）。
        let persona = try XCTUnwrap(try db.stores.personas.byId(personaId))
        XCTAssertEqual(persona.mood, "有点累")
        XCTAssertEqual(persona.moodIntensity, 3)
        XCTAssertEqual(persona.moodValence, -1)
        XCTAssertEqual(persona.moodArousal, 2)
        XCTAssertEqual(persona.moodDominance, 3)

        // 游标推进：同一窗口内再问一次不再发请求。
        let again = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertNil(again)
        XCTAssertEqual(ServerURLProtocolStub.recordedRequests.count, 1)
    }

    /// 没到节奏：一个请求都不发。
    func testThrottleSkipsUntilFourNewUserMessages() async throws {
        try insertUserMessages(3)
        let skipped = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertNil(skipped)
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty)
        // 再攒够 4 条（基线之后的 4 条）才判。
        try insertUserMessages(3, offset: 3)
        XCTAssertFalse(cloud.shouldEvaluate(personaId: personaId, at: now), "才 3 条新的，还没到")
        try insertUserMessages(1, offset: 6)
        XCTAssertTrue(cloud.shouldEvaluate(personaId: personaId, at: now))
    }

    /// 服务端没配模型（200 + source=decay + mood=null）：不写本地、不报错、游标推进。
    func testNoModelOnServerLeavesLocalMoodUntouched() async throws {
        try makeThrottleDue()
        let before = mood.current(personaId: personaId, now: now)
        ServerURLProtocolStub.enqueue(.json("""
        {"personaId":"p1","changed":false,"source":"decay","mood":null}
        """))
        let outcome = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertEqual(outcome?.requested, true)
        XCTAssertEqual(outcome?.written, false)
        XCTAssertEqual(mood.current(personaId: personaId, now: now), before, "本地心情不该被改")
        XCTAssertFalse(cloud.shouldEvaluate(personaId: personaId, at: now), "失败也推进游标")
    }

    /// 网络失败：不写本地、**也不按时间衰减**（云端模式下「连不上服务端」不等于「他变冷淡」）。
    func testNetworkFailureDoesNotDecayLocalMood() async throws {
        try makeThrottleDue()
        _ = mood.setMood(personaId: personaId, label: "雀跃", intensity: 4, now: now - 1000)
        let before = mood.current(personaId: personaId, now: now)
        ServerURLProtocolStub.enqueue(
            ServerURLProtocolStub.Response(chunks: [], failure: URLError(.notConnectedToInternet))
        )

        let outcome = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertEqual(outcome?.requested, true)
        XCTAssertEqual(outcome?.written, false)
        let after = mood.current(personaId: personaId, now: now)
        XCTAssertEqual(after.label, before.label, "本地心情不该被网络失败改掉")
        XCTAssertEqual(after.intensity, before.intensity)
    }

    /// 本机人格 id ≠ 服务端 id：请求打到服务端那个，本地缓存仍挂本机 id。
    func testUsesMappedServerPersonaId() async throws {
        cloud = makeCloud(serverPersonaId: { _ in "server-p1" })
        try makeThrottleDue()
        ServerURLProtocolStub.enqueue(.json(Self.moodJSON))
        _ = await cloud.evaluateIfNeeded(personaId: personaId)
        XCTAssertEqual(
            ServerURLProtocolStub.recordedRequests.first?.url?.path,
            "/v1/personas/server-p1/mood/evaluate"
        )
        XCTAssertEqual(mood.current(personaId: personaId, now: now).label, "有点累")
    }

    /// 三维缺一个就整组退回词表推断（不能拿默认值补齐，否则「平静」与「没给」混在一起）。
    func testPartialDimensionsFallBackToLexicon() async throws {
        try makeThrottleDue()
        ServerURLProtocolStub.enqueue(.json("""
        {"personaId":"p1","changed":true,"source":"llm",
         "mood":{"personaId":"p1","label":"有点累","intensity":3,"valence":-1,"updatedAt":1}}
        """))
        _ = await cloud.evaluateIfNeeded(personaId: personaId)
        let state = mood.current(personaId: personaId, now: now)
        XCTAssertEqual(state.label, "有点累")
        let inferred = MoodDimensions.fromLegacy(label: "有点累", intensity: 3)
        XCTAssertEqual(state.dimensions, inferred, "三维不齐时按词表推，而不是补 0/2/3")
    }
}
