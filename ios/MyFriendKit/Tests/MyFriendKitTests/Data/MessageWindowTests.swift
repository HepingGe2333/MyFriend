import Foundation
import XCTest
@testable import MyFriendKit

/// 0.11.0 P2-1 的验收：长会话里「取上下文 / 拼处境 / 抽记忆」只读窗口，不读整段历史。
///
/// 这一层只测**存储语义**（到底从 SQLite 物化了多少行），调用点是否真的走窗口由
/// `ChatServiceTests.testLongSessionContextReadsWindowOnly` 钉住 —— 两边缺一不可：
/// 光有窗口查询、调用点继续 `bySession` 的话，测试全绿但内存问题照旧。
final class MessageWindowTests: XCTestCase {

    private var temp: TempDatabase!
    private let sessionId = "session-1"
    private let personaId = "persona-1"
    private let base: Int64 = 1_794_500_000_000

    /// 观察到的查询：方法名 + 行数（按发生顺序）。
    private var observed: [(method: String, rows: Int)] = []
    private let observedLock = NSLock()

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        try temp.stores.personas.upsert(TestData.persona(id: personaId))
        try temp.stores.sessions.upsert(TestData.session(id: sessionId, personaId: personaId))
        temp.stores.messages.queryObserver = { [weak self] method, rows in
            guard let self else { return }
            self.observedLock.lock()
            self.observed.append((method, rows))
            self.observedLock.unlock()
        }
    }

    override func tearDownWithError() throws {
        temp.stores.messages.queryObserver = nil
        temp.cleanUp()
        temp = nil
        observed = []
        try super.tearDownWithError()
    }

    private func snapshot() -> [(method: String, rows: Int)] {
        observedLock.lock()
        defer { observedLock.unlock() }
        return observed
    }

    @discardableResult
    private func seed(
        _ count: Int,
        role: (Int) -> MessageRole = { $0 % 2 == 0 ? .user : .assistant },
        content: (Int) -> String = { "第 \($0) 句" }
    ) throws -> [MessageEntity] {
        let messages = (0..<count).map { index in
            TestData.message(
                id: String(format: "m-%03d", index),
                role: role(index),
                content: content(index),
                createdAt: base + Int64(index) * 1000
            )
        }
        try temp.stores.messages.upsert(messages)
        return messages
    }

    // MARK: - 窗口查询

    /// 130 条的会话取 60 条窗口：只物化 60 行（不是 130 行再截尾巴）。
    func testRecentWindowOnlyMaterialisesRequestedRows() throws {
        try seed(130)
        observed = []

        let page = try temp.stores.messages.recent(sessionId: sessionId, limit: 60)

        XCTAssertEqual(page.count, 60)
        XCTAssertEqual(page.first?.id, "m-070")
        XCTAssertEqual(page.last?.id, "m-129")
        XCTAssertEqual(snapshot().map(\.rows), [60], "只该从 SQLite 捞 60 行")
        XCTAssertEqual(snapshot().map(\.method), ["recent"])
    }

    /// 对照：`bySession` 才是「整段读」，它会把 130 行都物化出来。
    ///
    /// 这条不是「必须这样」，而是把两者的差别钉住：以后谁把调用点写回 `bySession`，
    /// 上面的行数断言会立刻告诉你是哪一条路径退化了。
    func testBySessionReadsEverythingForContrast() throws {
        try seed(130)
        observed = []
        let all = try temp.stores.messages.bySession(sessionId)
        XCTAssertEqual(all.count, 130)
        XCTAssertEqual(snapshot().map(\.rows), [130])
    }

    /// 最后一次用户消息是**精确查询**：最后 60 条全是助手消息时，也不能被窗口挡住。
    func testLatestUserMessageIsExactQueryNotWindowSuffix() throws {
        // 前 10 条里有一句用户消息，之后 120 条全是助手消息。
        let messages = try seed(130) { index in
            index == 5 ? .user : .assistant
        }
        observed = []

        let latestUser = try XCTUnwrap(temp.stores.messages.latestUserMessage(sessionId: sessionId))
        XCTAssertEqual(latestUser.id, messages[5].id)
        XCTAssertEqual(snapshot().map(\.rows), [1], "精确查询只取 1 行")

        // 如果拿「最近 60 条窗口里最后一条用户消息」代替，就会读不到（窗口里全是助手）。
        let window = try temp.stores.messages.recent(sessionId: sessionId, limit: 60)
        XCTAssertFalse(window.contains { $0.role == .user }, "这 60 条里确实没有用户消息")
    }

    func testLatestAssistantMessageSkipsEmptyContent() throws {
        try temp.stores.messages.upsert([
            TestData.message(id: "a-1", role: .assistant, content: "在的", createdAt: base),
            TestData.message(id: "a-2", role: .assistant, content: "", createdAt: base + 1000),
            TestData.message(id: "u-1", role: .user, content: "嗯", createdAt: base + 2000)
        ])
        XCTAssertEqual(try temp.stores.messages.latestAssistantMessage(sessionId: sessionId)?.id, "a-1")
        XCTAssertEqual(try temp.stores.messages.latestUserMessage(sessionId: sessionId)?.id, "u-1")
    }

    /// 对话窗口：跳过 system 与空正文，只取最近 N 条，按时间升序。
    func testRecentDialogueSkipsSystemAndBlank() throws {
        try temp.stores.messages.upsert([
            TestData.message(id: "m-1", role: .user, content: "第一句", createdAt: base),
            TestData.message(id: "m-2", role: .system, content: "系统提示词", createdAt: base + 1000),
            TestData.message(id: "m-3", role: .assistant, content: "第二句", createdAt: base + 2000),
            TestData.message(id: "m-4", role: .user, content: "   ", createdAt: base + 3000),
            TestData.message(id: "m-5", role: .assistant, content: "第三句", createdAt: base + 4000)
        ])
        let recent = try temp.stores.messages.recentDialogue(sessionId: sessionId, limit: 2)
        XCTAssertEqual(recent.map(\.id), ["m-3", "m-5"], "跳过 system 与全空白正文，只要最近两条")
        XCTAssertEqual(try temp.stores.messages.countDialogue(sessionId: sessionId), 3)
    }

    // MARK: - 往后追加（P2-5 的存储基础）

    /// 游标之后的消息：只取更新的那几条，顺序与游标方向一致。
    func testPageAfterReturnsOnlyNewerMessages() throws {
        let messages = try seed(130)
        let cursor = messages[119]
        let newer = try temp.stores.messages.pageAfter(
            sessionId: sessionId, createdAt: cursor.createdAt, id: cursor.id, limit: 200
        )
        XCTAssertEqual(newer.map(\.id), ["m-120", "m-121", "m-122", "m-123", "m-124",
                                         "m-125", "m-126", "m-127", "m-128", "m-129"])
        XCTAssertEqual(snapshot().map(\.rows), [10])
    }

    /// 同一毫秒两条消息：游标用 `(createdAt, id)` 才不会漏掉或重复。
    func testPageAfterUsesIdAsTiebreakWithinSameMillisecond() throws {
        try temp.stores.messages.upsert([
            TestData.message(id: "m-a", role: .user, content: "同一毫秒 A", createdAt: base),
            TestData.message(id: "m-b", role: .assistant, content: "同一毫秒 B", createdAt: base),
            TestData.message(id: "m-c", role: .user, content: "同一毫秒 C", createdAt: base)
        ])
        let afterA = try temp.stores.messages.pageAfter(
            sessionId: sessionId, createdAt: base, id: "m-a", limit: 10
        )
        XCTAssertEqual(afterA.map(\.id), ["m-b", "m-c"])
        let afterB = try temp.stores.messages.pageAfter(
            sessionId: sessionId, createdAt: base, id: "m-b", limit: 10
        )
        XCTAssertEqual(afterB.map(\.id), ["m-c"])
    }
}
