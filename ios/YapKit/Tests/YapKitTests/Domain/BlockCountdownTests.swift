import Foundation
import XCTest
@testable import YapKit

/// 0.15 拉黑倒计时（`spec/experience/README.md` §2）。
///
/// 全是纯函数用例：不建库、不依赖系统时区，只钉「多少毫秒 → 显示什么」这一件事。
final class BlockCountdownTests: XCTestCase {

    private let now: Int64 = 1_800_000_000_000
    private let hour: Int64 = 3_600_000
    private let minute: Int64 = 60_000

    // MARK: - 整小时 / 小时 + 分

    func testExactHoursOmitMinutes() {
        XCTAssertEqual(
            BlockCountdown.text(until: now + 2 * hour, now: now),
            "你已被拉黑，还剩 2 小时"
        )
        XCTAssertEqual(
            BlockCountdown.text(until: now + 24 * hour, now: now),
            "你已被拉黑，还剩 24 小时",
            "24 小时是上限时也照实显示，不夹取"
        )
    }

    func testHoursAndMinutes() {
        XCTAssertEqual(
            BlockCountdown.text(until: now + hour + 23 * minute, now: now),
            "你已被拉黑，还剩 1 小时 23 分"
        )
        // 分钟也向上取整：1 秒也至少显示 1 分。
        XCTAssertEqual(
            BlockCountdown.text(until: now + hour + minute + 1, now: now),
            "你已被拉黑，还剩 1 小时 2 分"
        )
    }

    func testLessThanOneMinuteShowsOneMinute() {
        XCTAssertEqual(
            BlockCountdown.text(until: now + 1_000, now: now),
            "你已被拉黑，还剩 1 分"
        )
        XCTAssertEqual(
            BlockCountdown.text(until: now + 59_999, now: now),
            "你已被拉黑，还剩 1 分"
        )
    }

    // MARK: - 向上取整边界

    func testCeilingBoundaryBelowAnHour() {
        // 差 1 毫秒到 1 小时：向上取整成 60 分 ⇒ 显示「1 小时」，不显示 0 分。
        XCTAssertEqual(
            BlockCountdown.text(until: now + hour - 1, now: now),
            "你已被拉黑，还剩 1 小时"
        )
        // 差 2 毫秒到 1 小时：60 分 - 极小的余量仍然向上取整成 60 分。
        XCTAssertEqual(
            BlockCountdown.text(until: now + hour - 2, now: now),
            "你已被拉黑，还剩 1 小时"
        )
        // 刚过 1 小时 1 毫秒：61 分 ⇒ 1 小时 1 分。
        XCTAssertEqual(
            BlockCountdown.text(until: now + hour + 1, now: now),
            "你已被拉黑，还剩 1 小时 1 分"
        )
    }

    func testCeilingBoundaryAtMinuteEdges() {
        XCTAssertEqual(
            BlockCountdown.text(until: now + 2 * minute, now: now),
            "你已被拉黑，还剩 2 分"
        )
        XCTAssertEqual(
            BlockCountdown.text(until: now + 2 * minute + 1, now: now),
            "你已被拉黑，还剩 3 分"
        )
        XCTAssertEqual(
            BlockCountdown.remaining(until: now + 2 * minute + 1, now: now)?.minutes,
            3
        )
    }

    func testRemainingSplitsHoursAndMinutes() {
        let value = BlockCountdown.remaining(until: now + hour + 23 * minute + 1, now: now)
        XCTAssertEqual(value?.hours, 1)
        XCTAssertEqual(value?.minutes, 24, "多出的 1 毫秒把分钟向上取整到 24")
    }

    // MARK: - 不在拉黑期

    func testExactlyAtExpiryReturnsNil() {
        XCTAssertNil(BlockCountdown.text(until: now, now: now))
        XCTAssertNil(BlockCountdown.remaining(until: now, now: now))
    }

    func testExpiredReturnsNil() {
        XCTAssertNil(BlockCountdown.text(until: now - 1, now: now))
        XCTAssertNil(BlockCountdown.text(until: now - hour, now: now))
        XCTAssertNil(BlockCountdown.remaining(until: now - hour, now: now))
    }

    func testZeroAndNegativeUntilReturnNil() {
        XCTAssertNil(BlockCountdown.text(until: 0, now: now))
        XCTAssertNil(BlockCountdown.text(until: -1, now: now))
        // `until > 0` 这条与 `until > now` 是两道独立的门槛：now 为负时 0 也不该显示。
        XCTAssertNil(BlockCountdown.text(until: 0, now: -1))
        XCTAssertNil(BlockCountdown.remaining(until: -hour, now: now))
    }

    func testMoreThanADayIsNotClamped() {
        XCTAssertEqual(
            BlockCountdown.text(until: now + 25 * hour + 30 * minute, now: now),
            "你已被拉黑，还剩 25 小时 30 分"
        )
        let value = BlockCountdown.remaining(until: now + 49 * hour, now: now)
        XCTAssertEqual(value?.hours, 49, "≥24 小时照实显示，不夹到 24")
        XCTAssertEqual(value?.minutes, 0)
    }

    // MARK: - 文案形状

    func testTextAlwaysStartsWithTheSameSentence() {
        for delta in [1_000, minute, hour, hour + minute] as [Int64] {
            let text = BlockCountdown.text(until: now + delta, now: now) ?? ""
            XCTAssertTrue(
                text.hasPrefix("你已被拉黑，还剩 "),
                "所有倒计时都应是同一句开头，实际：\(text)"
            )
        }
    }

    func testZeroMinuteSegmentIsOmittedNotPrinted() {
        let text = BlockCountdown.text(until: now + 3 * hour, now: now) ?? ""
        XCTAssertFalse(text.contains("0 分"), "整点时不显示「0 分」：\(text)")
        XCTAssertFalse(text.contains("0 小时"), "不足 1 小时时不显示「0 小时」：\(text)")
    }
}
