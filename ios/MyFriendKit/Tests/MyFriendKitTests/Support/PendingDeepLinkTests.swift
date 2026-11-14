import Foundation
import XCTest
@testable import MyFriendKit

/// 0.11.0 审计 P2-7：从通知冷启动时，「要打开哪个会话」不能丢。
///
/// 这里只测状态机：写入 → 消费 → 清除，以及重复消费的幂等性。
/// delegate 装配时机与根视图的消费顺序由 `RootView` / `MyFriendApp` 负责。
final class PendingDeepLinkTests: XCTestCase {

    /// 内存存储：与 `UserDefaults` 同语义（nil = 删除键）。
    private final class MemoryStorage: PendingDeepLink.Storage {
        var values: [String: String] = [:]
        func string(forKey key: String) -> String? { values[key] }
        func set(_ value: String?, forKey key: String) {
            if let value { values[key] = value } else { values.removeValue(forKey: key) }
        }
    }

    private var storage: MemoryStorage!
    private var pending: PendingDeepLink!

    override func setUpWithError() throws {
        try super.setUpWithError()
        storage = MemoryStorage()
        pending = PendingDeepLink(storage: storage)
    }

    override func tearDownWithError() throws {
        pending = nil
        storage = nil
        try super.tearDownWithError()
    }

    /// 写入 → 消费 → 清除：消费之后键真的没了，不会在下次启动时又跳一次。
    func testStageConsumeAndClear() {
        XCTAssertNil(pending.consume())
        XCTAssertTrue(pending.stage("session-42"))
        XCTAssertEqual(pending.consume(), "session-42")
        XCTAssertNil(pending.consume(), "消费过就没了")
        XCTAssertNil(pending.peek())
        XCTAssertNil(storage.values[PendingDeepLink.defaultKey], "键必须真的删掉，不能只留空串")
    }

    /// 重复消费幂等：视图重建、状态恢复、`.task` 重新跑都不会重复推一次页面。
    func testRepeatedConsumeIsIdempotent() {
        pending.stage("session-42")
        XCTAssertEqual(pending.consume(), "session-42")
        for _ in 0..<3 {
            XCTAssertNil(pending.consume())
        }
    }

    /// `peek` 只看不拿：启动自检可以看一眼，但真正跳页面还是只能消费一次。
    func testPeekDoesNotConsume() {
        pending.stage("session-7")
        XCTAssertEqual(pending.peek(), "session-7")
        XCTAssertEqual(pending.peek(), "session-7")
        XCTAssertEqual(pending.consume(), "session-7")
    }

    /// 后到的通知覆盖先到的（用户点了最新那条）。
    func testLatestStageWins() {
        pending.stage("session-a")
        pending.stage("session-b")
        XCTAssertEqual(pending.consume(), "session-b")
    }

    /// 空 id / 纯空白当成「没有」：通知里只有 personaId 时不该打开一段空会话。
    func testBlankIdentifiersAreIgnored() {
        XCTAssertFalse(pending.stage(nil))
        XCTAssertFalse(pending.stage(""))
        XCTAssertFalse(pending.stage("   \n"))
        XCTAssertNil(pending.consume())
    }

    /// 去掉首尾空白（通知里的 userInfo 是别人写进去的，不能假设干净）。
    func testStageTrimsWhitespace() {
        XCTAssertTrue(pending.stage("  session-9  "))
        XCTAssertEqual(pending.consume(), "session-9")
    }

    /// 显式清除：用户在别处已经处理掉这条待办。
    func testClearDropsPendingLink() {
        pending.stage("session-1")
        pending.clear()
        XCTAssertNil(pending.consume())
    }

    /// 换一个键也不会和别的库串（默认键带 App 前缀）。
    func testCustomKeyIsUsed() {
        let custom = PendingDeepLink(storage: storage, key: "other.key")
        custom.stage("session-x")
        XCTAssertNil(storage.values[PendingDeepLink.defaultKey])
        XCTAssertEqual(storage.values["other.key"], "session-x")
        XCTAssertNil(pending.consume(), "默认键那边没有东西")
    }
}
