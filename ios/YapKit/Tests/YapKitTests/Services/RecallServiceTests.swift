import Foundation
import XCTest
@testable import YapKit

/// 回忆三档：QUICK 只看还在用的、NORMAL 加上沉下去的、DEEP 连归档都翻。
final class RecallServiceTests: XCTestCase {

    private var db: TempDatabase!
    private var settings: InMemorySettings!
    private var repository: MemoryRepository!
    private var service: RecallService!

    override func setUpWithError() throws {
        db = try TempDatabase()
        settings = InMemorySettings(AppSettings())
        let index = MemoryVectorIndex(stores: db.stores, now: { TestData.now })
        repository = MemoryRepository(stores: db.stores, vectorIndex: index,
                                      now: { TestData.now })
        service = RecallService(
            stores: db.stores,
            memoryRepository: repository,
            browsingDigest: { _ in "他这几天在看雪具和哈尔滨的天气。" },
            now: { TestData.now }
        )
        try db.stores.personas.upsert(TestData.persona())
        try db.stores.sessions.upsert(TestData.session())
        try db.stores.memories.upsert([
            TestData.memory(id: "m-active", content: "你提过下个月要去哈尔滨，怕冷。", status: "ACTIVE"),
            TestData.memory(id: "m-dormant", content: "你们说好冬天一起去看哈尔滨的雪。",
                            status: "DORMANT", strength: 0.3),
            TestData.memory(id: "m-archived", content: "很久以前你在哈尔滨住过一年。",
                            status: "ARCHIVED", strength: 0.05)
        ])
        repository.ensureTermIndex()
    }

    override func tearDownWithError() throws {
        service = nil
        repository = nil
        settings = nil
        db.cleanUp()
        db = nil
    }

    func testQuickOnlySeesActiveMemoriesAndRendersChineseAnswer() {
        let result = service.recall(personaId: "persona-1", query: "哈尔滨", depth: .quick)
        XCTAssertFalse(result.isEmpty)
        XCTAssertFalse(result.rendered.isEmpty)
        XCTAssertTrue(result.rendered.contains("你翻了翻记忆（随口想想）"))
        XCTAssertFalse(result.hits.contains { $0.refId == "m-dormant" })
        XCTAssertFalse(result.hits.contains { $0.refId == "m-archived" })
        // QUICK 不翻聊天记录、也不带他浏览过的东西。
        XCTAssertFalse(result.hits.contains { $0.kind == .chat })
        XCTAssertFalse(result.hits.contains { $0.kind == .browsing })
    }

    func testNormalAddsDormantAndChatSearch() throws {
        try db.stores.messages.upsert([
            TestData.message(id: "msg-1", role: .user, content: "哈尔滨那边零下二十度吧。",
                             createdAt: TestData.now - 2 * 86_400_000),
            TestData.message(id: "msg-2", role: .assistant, content: "那我提醒你带厚外套。",
                             createdAt: TestData.now - 86_400_000)
        ])
        let result = service.recall(personaId: "persona-1", query: "哈尔滨", depth: .normal)
        XCTAssertTrue(result.hits.contains { $0.refId == "m-dormant" })
        XCTAssertFalse(result.hits.contains { $0.refId == "m-archived" })
        XCTAssertTrue(result.hits.contains { $0.kind == .chat }, "NORMAL 要能翻聊天记录")
        XCTAssertTrue(result.hits.contains { $0.kind == .browsing })
    }

    func testDeepAlsoSeesArchivedWithUncertainNote() {
        let result = service.recall(personaId: "persona-1", query: "哈尔滨", depth: .deep)
        XCTAssertTrue(result.hits.contains { $0.refId == "m-archived" })
        let archived = result.hits.first { $0.refId == "m-archived" }
        XCTAssertEqual(archived?.note, Recall.ARCHIVED_NOTE)
        XCTAssertTrue(result.rendered.contains("翻箱倒柜"))
    }

    func testEmptyQueryStillReturnsRecentThingsAndNoMatchSaysSo() {
        // 空 query：退化成「最近 / 重要优先」，不报错。
        let result = service.recall(personaId: "persona-1", query: "", depth: .normal)
        XCTAssertFalse(result.isEmpty)

        // 一个完全查不到的词 + 只有一条记忆时仍会兜底取出全部记忆；
        // 真正空答案的路径靠「人格不存在」触发（工具不能抛异常）。
        let missingPersona = service.recall(personaId: "persona-missing", query: "哈尔滨", depth: .deep)
        XCTAssertTrue(missingPersona.isEmpty)
        XCTAssertTrue(missingPersona.hits.isEmpty)
        XCTAssertTrue(missingPersona.rendered.contains("翻箱倒柜"), "没翻到也要给人话")
    }

    /// 1.9：记忆内置开启，没有开关能让回忆变空（§一）。
    func testRecallWorksWithoutAnySwitch() {
        let result = service.recall(personaId: "persona-1", query: "哈尔滨", depth: .deep)
        XCTAssertFalse(result.isEmpty)
    }
}
