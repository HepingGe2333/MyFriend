import Foundation
import XCTest
@testable import MyFriendKit

/// 消息「算不算对话」的口径，以及会话预览的定向更新（0.11.1 审计 P2-11 / P2-15）。
///
/// 两件事的共同点是「同一份数据在 SQL 与 Swift 里语义不一致」：
/// 1. SQLite 的 `trim()` 默认只去空格，Swift 的 `trimmingCharacters(in: .whitespacesAndNewlines)`
///    还会去 `\t` / `\n` / `\r`；
/// 2. 会话预览原来是「读整行 → 改 → 整行 REPLACE」，会覆盖并发写入的未读数。
final class MessageVisibilityAndPreviewTests: XCTestCase {

    private var temp: TempDatabase!
    private let sessionId = "session-1"

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        try temp.stores.personas.upsert(TestData.persona(id: "persona-1"))
        try temp.stores.sessions.upsert(TestData.session(id: sessionId, personaId: "persona-1"))
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    private func seedWhitespaceOnlyMessages() throws {
        try temp.stores.messages.upsert([
            TestData.message(id: "msg-space", role: .user, content: "   ",
                             createdAt: TestData.now - 5000),
            TestData.message(id: "msg-newline", role: .user, content: "\n",
                             createdAt: TestData.now - 4000),
            TestData.message(id: "msg-tab", role: .assistant, content: "\t",
                             createdAt: TestData.now - 3000),
            TestData.message(id: "msg-mixed", role: .assistant, content: " \n\t\r ",
                             createdAt: TestData.now - 2000),
            TestData.message(id: "msg-real", role: .user, content: "在吗",
                             createdAt: TestData.now - 1000),
            TestData.message(id: "msg-real-reply", role: .assistant, content: "在",
                             createdAt: TestData.now)
        ])
    }

    // MARK: - 「只有空白字符」的消息不算对话

    func testRecentDialogueExcludesWhitespaceOnlyMessages() throws {
        try seedWhitespaceOnlyMessages()
        let dialogue = try temp.stores.messages.recentDialogue(sessionId: sessionId, limit: 20)
        XCTAssertEqual(dialogue.map(\.id), ["msg-real", "msg-real-reply"],
                       "只有换行/制表符的消息不该被拼进提示词变成空行")
    }

    func testCountDialogueExcludesWhitespaceOnlyMessages() throws {
        try seedWhitespaceOnlyMessages()
        XCTAssertEqual(try temp.stores.messages.countDialogue(sessionId: sessionId), 2)
    }

    func testLatestUserMessageSkipsWhitespaceOnlyTail() throws {
        try seedWhitespaceOnlyMessages()
        let latest = try XCTUnwrap(try temp.stores.messages.latestUserMessage(sessionId: sessionId))
        XCTAssertEqual(latest.id, "msg-real", "不能把一条只有空白的消息当成「对方上次说的话」")
    }

    func testLatestAssistantMessageSkipsWhitespaceOnlyTail() throws {
        try seedWhitespaceOnlyMessages()
        let latest = try XCTUnwrap(try temp.stores.messages.latestAssistantMessage(sessionId: sessionId))
        XCTAssertEqual(latest.id, "msg-real-reply")
    }

    // MARK: - 会话预览：定向 UPDATE，不覆盖并发写入

    func testPreviewUpdateKeepsConcurrentUnreadCount() throws {
        var session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        session.unreadCount = 3
        try temp.stores.sessions.upsert(session)

        let changed = try temp.stores.sessions.updatePreview(
            id: sessionId,
            preview: "刚刚那句",
            lastMessageAt: TestData.now,
            updatedAt: TestData.now + 1
        )
        XCTAssertTrue(changed)

        let stored = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertEqual(stored.lastMessagePreview, "刚刚那句")
        XCTAssertEqual(stored.lastMessageAt, TestData.now)
        XCTAssertEqual(stored.updatedAt, TestData.now + 1)
        XCTAssertEqual(stored.unreadCount, 3, "定向 UPDATE 不能把并发写入的未读数回滚成旧快照")
    }

    func testPreviewUpdateOnDeletedSessionReportsMissing() throws {
        try temp.stores.sessions.delete(id: sessionId)
        let changed = try temp.stores.sessions.updatePreview(
            id: sessionId, preview: nil, lastMessageAt: nil, updatedAt: TestData.now
        )
        XCTAssertFalse(changed, "会话没了要告诉调用点（它要清空页面）")
    }

    func testPreviewUpdateCanClearPreview() throws {
        var session = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        session.lastMessagePreview = "旧预览"
        session.lastMessageAt = TestData.now
        try temp.stores.sessions.upsert(session)

        try temp.stores.sessions.updatePreview(
            id: sessionId, preview: nil, lastMessageAt: nil, updatedAt: TestData.now + 2
        )
        let stored = try XCTUnwrap(try temp.stores.sessions.byId(sessionId))
        XCTAssertNil(stored.lastMessagePreview)
        XCTAssertNil(stored.lastMessageAt)
    }
}
