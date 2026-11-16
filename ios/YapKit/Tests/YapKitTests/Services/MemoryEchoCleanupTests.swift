import Foundation
import XCTest
@testable import YapKit

/// 记忆回声的清理：`remember` 升级成正式记忆后，Android 会 `deleteByPersona` 清空旧回声；
/// iOS 在 0.10.1 之前不清，表会一直变大。这里钉住三件事：
/// 1. promote 之后该人格的回声清零，**别人格的不动**；
/// 2. `.reinforce`（又说到同一条已有记忆）不清，那条还在「确认中」；
/// 3. `MemoryEchoStoring` 的按条 / 按人格删除在内存实现与 SQLite 实现上语义一致。
final class MemoryEchoCleanupTests: XCTestCase {

    private var temp: TempDatabase!
    private var memory: MemoryRepository!
    private var echoes: InMemoryMemoryEchoStore!
    private var executor: ToolExecutor!
    private let personaId = "persona-1"
    private let otherPersonaId = "persona-2"
    private let sessionId = "session-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        memory = MemoryRepository(stores: temp.stores, vectorIndex: MemoryVectorIndex(stores: temp.stores))
        echoes = InMemoryMemoryEchoStore()
        try temp.stores.personas.upsert([
            TestData.persona(id: personaId),
            TestData.persona(id: otherPersonaId, name: "陈屿", isDefault: false)
        ])
        try temp.stores.sessions.upsert([
            TestData.session(id: sessionId, personaId: personaId),
            TestData.session(id: "session-2", personaId: otherPersonaId)
        ])
        try temp.stores.apiConfigs.upsert(ApiConfigEntity(
            id: "config-1", name: "测试配置", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat"
        ))
        executor = ToolExecutor(
            stores: temp.stores,
            apiKeys: ApiKeyStore(backend: InMemoryKeychain()),
            memory: memory,
            recallService: RecallService(stores: temp.stores, memoryRepository: memory),
            mood: MoodRepository(stores: temp.stores, now: { self.now }),
            personaCards: PersonaCardRepository(stores: temp.stores),
            intents: AgentIntentSupport(stores: temp.stores, ticks: AgentTickStore(database: temp.stores.database)),
            echoes: echoes,
            webTools: WebToolsClient(session: StubURLProtocol.makeSession()),
            now: { self.now }
        )
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    private func echo(_ id: String, personaId: String, content: String) -> MemoryEcho {
        MemoryEcho(
            id: id, personaId: personaId, drawer: "EVENT", content: content,
            importance: 4, categoryName: "EVENT", createdAt: now - 1000, lastSeenAt: now - 1000
        )
    }

    private func run(_ arguments: [String: Any]) async -> ToolResult {
        let data = try! JSONSerialization.data(withJSONObject: arguments, options: [.sortedKeys])
        let invocation = ToolInvocation(
            id: "call-1", name: "remember",
            argumentsJson: String(data: data, encoding: .utf8) ?? "{}"
        )
        let persona = (try? temp.stores.personas.byId(personaId)) ?? TestData.persona(id: personaId)
        return await executor.run(invocation, persona: persona, sessionId: sessionId)
    }

    func testPromoteClearsEchoesForThatPersonaOnly() async throws {
        let arguments: [String: Any] = [
            "content": "对方在准备一个很重要的项目，压力来自排期",
            "drawer": "EVENT",
            "importance": 4
        ]
        let first = await run(arguments)         // 第一次：只留回声
        XCTAssertEqual(first.traceTitle, "记下了（还在确认）", first.traceDetail ?? "")
        XCTAssertEqual(echoes.byPersona(personaId).count, 1)
        // 模拟「以前留下的旧回声」：同样一条事、另一个人格各一条。
        echoes.insertAll([
            echo("p1-old", personaId: personaId, content: "对方在准备一个很重要的项目，压力来自排期"),
            echo("p2-keep", personaId: otherPersonaId, content: "陈屿喜欢在夜里散步")
        ])
        XCTAssertEqual(echoes.byPersona(personaId).count, 2)

        let promoted = await run(arguments)      // 第二次：升级成正式记忆
        XCTAssertEqual(promoted.traceTitle, "记住了", promoted.traceDetail ?? "")
        XCTAssertEqual(try temp.stores.memories.byPersona(personaId).count, 1)
        XCTAssertTrue(echoes.byPersona(personaId).isEmpty, "升级成功后该人格的旧回声要清空")
        XCTAssertEqual(echoes.byPersona(otherPersonaId).count, 1, "别人格的回声一律不动")
    }

    func testReinforceKeepsEchoes() async throws {
        echoes.insertAll([echo("keep-me", personaId: personaId, content: "对方怕黑，睡觉要留一盏灯")])
        _ = memory.addDetailed(
            personaId: personaId,
            content: "对方在准备一个很重要的项目，压力来自排期",
            category: .event, importance: 4, drawer: "EVENT"
        )
        let result = await run([
            "content": "对方在准备一个很重要的项目，压力来自排期",
            "drawer": "EVENT",
            "importance": 4
        ])
        XCTAssertEqual(result.traceTitle, "又确认了一次", result.traceDetail ?? "")
        XCTAssertEqual(echoes.byPersona(personaId).count, 1, ".reinforce 不清回声（既有行为不变）")
    }

    func testInMemoryDeleteByIDAndPersona() {
        echoes.insertAll([
            echo("a", personaId: personaId, content: "一"),
            echo("b", personaId: personaId, content: "二"),
            echo("c", personaId: otherPersonaId, content: "三")
        ])
        echoes.delete(id: "a")
        XCTAssertEqual(echoes.byPersona(personaId).map(\.id), ["b"])
        echoes.deleteAll(personaId: personaId)
        XCTAssertTrue(echoes.byPersona(personaId).isEmpty)
        XCTAssertEqual(echoes.byPersona(otherPersonaId).map(\.id), ["c"])
        echoes.deleteAll()
        XCTAssertTrue(echoes.byPersona(otherPersonaId).isEmpty)
    }

    func testSQLiteStoreDeletesByIDAndPersona() {
        let store = MemoryEchoStore(database: temp.stores.database)
        XCTAssertFalse(store.isUsingFallback, "0.9.1 起 memory_echoes 表总会存在")
        store.insertAll([
            echo("sql-a", personaId: personaId, content: "一"),
            echo("sql-b", personaId: personaId, content: "二"),
            echo("sql-c", personaId: otherPersonaId, content: "三")
        ])
        XCTAssertEqual(store.byPersona(personaId).count, 2)
        store.delete(id: "sql-a")
        XCTAssertEqual(store.byPersona(personaId).count, 1)
        store.deleteAll(personaId: personaId)
        XCTAssertTrue(store.byPersona(personaId).isEmpty)
        XCTAssertEqual(store.byPersona(otherPersonaId).map(\.id), ["sql-c"])
        store.deleteAll()
        XCTAssertTrue(store.byPersona(otherPersonaId).isEmpty)
    }
}
