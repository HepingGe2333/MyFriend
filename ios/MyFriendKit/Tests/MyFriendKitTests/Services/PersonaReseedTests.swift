import Foundation
import XCTest
@testable import MyFriendKit

/// 0.11.0 审计 P2-13：「删除全部本地数据」之后要按当前语言重新播种三位示例人格。
///
/// 原来 `seedIfNeeded()` 只在 `init` 里跑：清空之后首页空着、重启之后种子人格又出现，
/// 同一台设备出现了两种「出厂状态」。现在清空动作自己负责播种（`DataReset.reseed`），
/// 这里用真库 + 固定时钟把「清空 → 播种 → 内容/语言/会话」整条链钉住。
final class PersonaReseedTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var reset: DataReset!

    override func setUpWithError() throws {
        try super.setUpWithError()
        db = try TempDatabase()
        settings = InMemorySettings(AppSettings(activePersonaId: "persona-1"))
        reset = DataReset(stores: db.stores, settings: settings)
        try db.stores.personas.upsert([TestData.persona(), TestData.persona(id: "persona-2", name: "陈屿")])
        try db.stores.sessions.upsert(TestData.session())
        try db.stores.messages.upsert([
            TestData.message(id: "m-1", role: .user, content: "在吗", createdAt: TestData.now - 1000),
            TestData.message(id: "m-2", role: .assistant, content: "在", createdAt: TestData.now)
        ])
    }

    override func tearDownWithError() throws {
        reset = nil
        settings = nil
        db.cleanUp()
        db = nil
        try super.tearDownWithError()
    }

    private func reseed(_ language: SeedLanguage) {
        reset.reseed = ReseedConfig(
            language: language,
            sessionTitle: language == .english ? "New chat" : "新的对话",
            now: TestData.now
        )
    }

    /// 清空之后：3 个人格、名字是**当前语言**的种子、每人一段会话。
    func testClearAllReseedsCurrentLanguageSeeds() throws {
        reseed(.english)
        let summary = reset.clearAll()

        let personas = try db.stores.personas.all(orderBy: "createdAt ASC")
        XCTAssertEqual(personas.count, 3, "清空之后不该是空 App")
        XCTAssertEqual(Set(personas.map(\.name)), ["Rowan", "Juno", "Ellis"])
        XCTAssertEqual(personas.filter(\.isDefault).map(\.id), [SeedPersonas.Identifiers.first])
        // 人格 id 是固定的：备份与会话引用认的就是它。
        XCTAssertEqual(Set(personas.map(\.id)), [
            SeedPersonas.Identifiers.first,
            SeedPersonas.Identifiers.second,
            SeedPersonas.Identifiers.third
        ])
        // 一人一段，标题是当前语言的「新的对话」。
        let sessions = try db.stores.sessions.all()
        XCTAssertEqual(sessions.count, 3)
        XCTAssertEqual(Set(sessions.map(\.title)), ["New chat"])
        XCTAssertEqual(Set(sessions.map(\.personaId)), Set(personas.map(\.id)))

        XCTAssertEqual(summary.seededPersonas, 3)
        XCTAssertEqual(summary.seededSessions, 3)
        XCTAssertTrue(summary.warnings.isEmpty)
        // 清空时数的是**删掉**的那些，不是播种之后的。
        XCTAssertEqual(summary.personas, 2)
    }

    func testClearAllReseedsChineseSeeds() throws {
        reseed(.chinese)
        _ = reset.clearAll()
        let names = try db.stores.personas.all().map(\.name)
        XCTAssertEqual(Set(names), ["林深", "阿岚", "墨"])
        XCTAssertEqual(Set(try db.stores.sessions.all().map(\.title)), ["新的对话"])
    }

    /// 没设 `reseed` 时不播种（0.10.2 的行为，只有单测会用到这个模式）。
    func testClearAllWithoutReseedLeavesDatabaseEmpty() throws {
        let summary = reset.clearAll()
        XCTAssertEqual(try db.stores.personas.count(), 0)
        XCTAssertEqual(try db.stores.sessions.count(), 0)
        XCTAssertEqual(summary.seededPersonas, 0)
    }

    /// 首次启动的入口：库里已经有人就什么都不做（不重写、不改名、不重建会话）。
    func testSeedIfNeededDoesNotTouchExistingData() throws {
        let outcome = try PersonaSeeding.seedIfNeeded(
            stores: db.stores, language: .english, sessionTitle: "New chat", now: TestData.now
        )
        XCTAssertTrue(outcome.skipped)
        XCTAssertEqual(try db.stores.personas.count(), 2)
        XCTAssertEqual(try db.stores.personas.byId("persona-1")?.name, "林岸")
    }

    func testSeedIfNeededSeedsEmptyDatabase() throws {
        try db.stores.personas.deleteAll()
        try db.stores.sessions.deleteAll()
        let outcome = try PersonaSeeding.seedIfNeeded(
            stores: db.stores, language: .chinese, sessionTitle: "新的对话", now: TestData.now
        )
        XCTAssertFalse(outcome.skipped)
        XCTAssertEqual(outcome.personaIds.count, 3)
        XCTAssertEqual(outcome.sessions, 3)
        XCTAssertEqual(try db.stores.personas.count(), 3)
    }

    /// 重复播种不会撞 `chat_sessions.personaId` 的唯一索引：已有会话的人格不重建会话。
    func testSeedIsIdempotentForSessions() throws {
        _ = try PersonaSeeding.seed(
            stores: db.stores, language: .chinese, sessionTitle: "新的对话", now: TestData.now
        )
        let firstSessions = try db.stores.sessions.count()
        _ = try PersonaSeeding.seed(
            stores: db.stores, language: .chinese, sessionTitle: "新的对话", now: TestData.now
        )
        XCTAssertEqual(try db.stores.sessions.count(), firstSessions)
        // 夹具里的 2 个 + 种子的 3 个；第二次播种不该再涨。
        XCTAssertEqual(try db.stores.personas.count(), 5)
    }
}
