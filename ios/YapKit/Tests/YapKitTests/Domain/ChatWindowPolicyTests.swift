import Foundation
import XCTest
@testable import YapKit

/// 聊天页窗口与库的一致性判定（0.11.1 审计 P1-3）。
///
/// 缺陷现场：从聊天页进设置 →「清空全部聊天与记忆」/ 备份「替换导入」→ 返回，
/// 页面继续显示已经不存在的消息；`sessions.byId == nil` 也不清 `session`，
/// 20 秒轮询的比较条件永远成立 → 永远无效重试。
///
/// `ChatWindowPolicy.plan` 是那段判定的唯一出处；`ChatViewModel` 只负责按结果
/// `keep / append / reset / sessionGone`（App 层接线由 `AppWiringGuardTests` 钉住）。
final class ChatWindowPolicyTests: XCTestCase {

    func testNothingChangedKeepsWindow() {
        let plan = ChatWindowPolicy.plan(
            sessionExists: true,
            storedTotal: 60,
            loadedCount: 60,
            loadedTotalAtLastCheck: 60,
            latestIdMatched: true
        )
        XCTAssertEqual(plan, .keep)
    }

    func testNewMessageAppends() {
        let plan = ChatWindowPolicy.plan(
            sessionExists: true,
            storedTotal: 61,
            loadedCount: 60,
            loadedTotalAtLastCheck: 60,
            latestIdMatched: false
        )
        XCTAssertEqual(plan, .append)
    }

    /// 外部删除：库里的总数比上次看到的少 → 只追加永远追不回来，必须重置窗口。
    func testExternalDeletionResetsWindow() {
        let plan = ChatWindowPolicy.plan(
            sessionExists: true,
            storedTotal: 40,
            loadedCount: 60,
            loadedTotalAtLastCheck: 60,
            latestIdMatched: false
        )
        XCTAssertEqual(plan, .reset, "删了 20 条之后窗口里 60 条有 20 条已经不存在了")
    }

    /// 「清空上下文」：会话还在、消息为 0。
    func testClearedContextResetsNonEmptyWindow() {
        let plan = ChatWindowPolicy.plan(
            sessionExists: true,
            storedTotal: 0,
            loadedCount: 60,
            loadedTotalAtLastCheck: 60,
            latestIdMatched: false
        )
        XCTAssertEqual(plan, .reset)
    }

    func testEmptyWindowWithEmptySessionIsQuiet() {
        let plan = ChatWindowPolicy.plan(
            sessionExists: true,
            storedTotal: 0,
            loadedCount: 0,
            loadedTotalAtLastCheck: 0,
            latestIdMatched: true
        )
        XCTAssertEqual(plan, .keep)
    }

    /// 会话被删（清空全部聊天 / 删人格 / 替换导入）：清空会话与消息、停轮询。
    func testMissingSessionMeansSessionGone() {
        let plan = ChatWindowPolicy.plan(
            sessionExists: false,
            storedTotal: nil,
            loadedCount: 60,
            loadedTotalAtLastCheck: 60,
            latestIdMatched: false
        )
        XCTAssertEqual(plan, .sessionGone)
    }

    /// 查库失败（会话读到了、count 读失败）：这一拍什么都不做，下一拍再试。
    func testCountFailureKeepsWindow() {
        let plan = ChatWindowPolicy.plan(
            sessionExists: true,
            storedTotal: nil,
            loadedCount: 60,
            loadedTotalAtLastCheck: 60,
            latestIdMatched: false
        )
        XCTAssertEqual(plan, .keep)
    }

    /// 窗口里比库里还多（消息被删且没有新增）：重置。
    func testWindowLargerThanStoreResets() {
        let plan = ChatWindowPolicy.plan(
            sessionExists: true,
            storedTotal: 10,
            loadedCount: 60,
            loadedTotalAtLastCheck: 10,
            latestIdMatched: true
        )
        XCTAssertEqual(plan, .reset)
    }
}
