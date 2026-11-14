import Foundation
import XCTest
@testable import MyFriendKit

/// 深度回忆不再整段读会话（0.11.1 审计 P2-7）。
///
/// 0.10.2 P2-1 把「长会话四处全量读历史」改成了窗口查询，但 `RecallService` 是审计点名的
/// 第五处：`messages.bySession(session.id)` 会把 `content` / `attachmentsJson` /
/// `toolTraceJson` 整段物化。从返回值上看不出差别（窗口里也能找到同样的两句话），
/// 所以这里用 `MessageStore.queryObserver` 数「从 SQLite 捞了多少行」。
final class RecallWindowTests: XCTestCase {

    private var db: TempDatabase!
    private var service: RecallService!
    private let sessionId = "session-1"

    override func setUpWithError() throws {
        try super.setUpWithError()
        db = try TempDatabase()
        let index = MemoryVectorIndex(stores: db.stores, now: { TestData.now })
        let repository = MemoryRepository(stores: db.stores, vectorIndex: index, now: { TestData.now })
        service = RecallService(
            stores: db.stores,
            memoryRepository: repository,
            now: { TestData.now }
        )
        try db.stores.personas.upsert(TestData.persona(id: "persona-1"))
        try db.stores.sessions.upsert(TestData.session(id: sessionId, personaId: "persona-1"))
    }

    override func tearDownWithError() throws {
        db.stores.messages.queryObserver = nil
        service = nil
        db.cleanUp()
        db = nil
        try super.tearDownWithError()
    }

    func testDeepRecallReadsABoundedWindowInsteadOfTheWholeSession() throws {
        // 300 条会话：关键词只出现在最早那批里（窗口之外找不到也无所谓，重点是别整段读）。
        let messages = (0..<300).map { index in
            TestData.message(
                id: String(format: "m-%03d", index),
                role: index % 2 == 0 ? .user : .assistant,
                content: "第 \(index) 句：那天雪很大。",
                createdAt: TestData.now - Int64(300 - index) * 1000
            )
        }
        try db.stores.messages.upsert(messages)

        var calls: [(String, Int)] = []
        db.stores.messages.queryObserver = { method, rows in
            calls.append((method, rows))
        }

        // 空 query 会走「翻箱倒柜再扫一遍最近的消息」那条分支 —— 正是原来 `bySession` 的位置。
        let result = service.recall(personaId: "persona-1", query: "", depth: .deep)
        XCTAssertFalse(result.isEmpty, "回忆本身要还能用")

        XCTAssertFalse(
            calls.contains { $0.0 == "bySession" },
            "回忆不该再整段读会话；实际调用：\(calls.map(\.0))"
        )
        let recentCalls = calls.filter { $0.0 == "recentDialogue" }
        XCTAssertFalse(recentCalls.isEmpty, "应当走窗口查询；实际调用：\(calls.map(\.0))")
        for call in recentCalls {
            XCTAssertLessThanOrEqual(
                call.1, RecallService.recallWindowMessages,
                "窗口查询返回的行数不能超过上限（300 条的会话不该整段物化）"
            )
        }
    }
}
