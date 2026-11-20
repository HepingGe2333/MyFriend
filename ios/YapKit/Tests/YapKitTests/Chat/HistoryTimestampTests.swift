import Foundation
import XCTest
@testable import YapKit

/// 历史时间戳（0.15 profile `history=timestamped`，`spec/context-profile/README.md`）。
///
/// 要钉的三件事：
/// 1. **格式**：同一天 `[HH:mm] 用户：` / `[HH:mm] 他：`；
/// 2. **跨天**：换一天就带日期 `[MM-dd HH:mm]`；
/// 3. **时区**：同一毫秒在不同设备时区下给出**本地**时刻（不是 UTC）。
/// 另外：工具 / 系统消息不加前缀；`createdAt` 缺失（老数据）时不编一个时间。
final class HistoryTimestampTests: XCTestCase {

    private func calendar(_ zone: String) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zone)!
        return calendar
    }

    private func millis(
        _ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int,
        zone: String = "Asia/Shanghai"
    ) -> Int64 {
        let date = calendar(zone).date(from: DateComponents(
            year: year, month: month, day: day, hour: hour, minute: minute
        ))!
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    // MARK: - 格式

    func testSameDayIsClockOnly() {
        let now = millis(2026, 11, 20, 22, 0)
        let messageAt = millis(2026, 11, 20, 21, 4)
        XCTAssertEqual(
            HistoryTimestamp.label(createdAt: messageAt, now: now, calendar: calendar("Asia/Shanghai")),
            "[21:04] "
        )
        // 零点过一分也还是同一格式（补零）。
        XCTAssertEqual(
            HistoryTimestamp.label(
                createdAt: millis(2026, 11, 20, 0, 5), now: now, calendar: calendar("Asia/Shanghai")
            ),
            "[00:05] "
        )
    }

    func testSpeakerPrefixes() {
        XCTAssertEqual(
            HistoryTimestamp.prefix(
                createdAt: millis(2026, 11, 20, 21, 4), now: millis(2026, 11, 20, 22, 0),
                role: .user, calendar: calendar("Asia/Shanghai")
            ),
            "[21:04] 用户："
        )
        XCTAssertEqual(
            HistoryTimestamp.prefix(
                createdAt: millis(2026, 11, 20, 21, 6), now: millis(2026, 11, 20, 22, 0),
                role: .assistant, calendar: calendar("Asia/Shanghai")
            ),
            "[21:06] 他："
        )
        // 英文人格读到英文标签，不读中文。
        XCTAssertEqual(
            HistoryTimestamp.speakerPrefix(role: .user, language: .en), "User: "
        )
        XCTAssertEqual(
            HistoryTimestamp.speakerPrefix(role: .assistant, language: .en), "He: "
        )
    }

    // MARK: - 跨天

    func testPreviousDayCarriesDate() {
        let now = millis(2026, 11, 20, 9, 0)
        let yesterday = millis(2026, 11, 19, 21, 4)
        XCTAssertEqual(
            HistoryTimestamp.label(createdAt: yesterday, now: now, calendar: calendar("Asia/Shanghai")),
            "[11-19 21:04] "
        )
    }

    func testOlderDayAlsoCarriesDate() {
        let now = millis(2026, 11, 20, 9, 0)
        let old = millis(2025, 12, 31, 23, 59)
        XCTAssertEqual(
            HistoryTimestamp.label(createdAt: old, now: now, calendar: calendar("Asia/Shanghai")),
            "[12-31 23:59] "
        )
    }

    /// 跨零点只差两分钟也算「跨天」：日期必须出现，否则模型会把昨晚当成刚才。
    func testJustAcrossMidnightCountsAsAnotherDay() {
        let now = millis(2026, 11, 20, 0, 1)
        let beforeMidnight = millis(2026, 11, 19, 23, 59)
        XCTAssertEqual(
            HistoryTimestamp.label(createdAt: beforeMidnight, now: now, calendar: calendar("Asia/Shanghai")),
            "[11-19 23:59] "
        )
    }

    // MARK: - 时区

    /// 同一毫秒：上海 21:04，纽约 08:04（同一个瞬间，各自的本地时刻）。
    func testSameInstantUsesEachDeviceLocalTime() {
        let now = millis(2026, 11, 20, 22, 0)
        let moment = millis(2026, 11, 20, 21, 4)
        XCTAssertEqual(
            HistoryTimestamp.label(createdAt: moment, now: now, calendar: calendar("Asia/Shanghai")),
            "[21:04] "
        )
        XCTAssertEqual(
            HistoryTimestamp.label(createdAt: moment, now: now, calendar: calendar("America/New_York")),
            "[08:04] "
        )
    }

    /// 时区不同，「今天」的边界也不同：同一条消息在上海算昨天、在纽约可能还算今天。
    func testDayBoundaryFollowsTimeZone() {
        // 2026-11-20 08:00 上海 = 2026-11-19 19:00 纽约；
        // 参照时刻取 2026-11-20 20:00 上海 = 2026-11-20 07:00 纽约。
        let moment = millis(2026, 11, 20, 8, 0)
        let nowShanghai = millis(2026, 11, 20, 20, 0)
        XCTAssertEqual(
            HistoryTimestamp.label(createdAt: moment, now: nowShanghai, calendar: calendar("Asia/Shanghai")),
            "[08:00] ", "在上海是同一天"
        )
        XCTAssertEqual(
            HistoryTimestamp.label(createdAt: moment, now: nowShanghai, calendar: calendar("America/New_York")),
            "[11-19 19:00] ", "同一瞬间在纽约是前一天 19:00"
        )
    }

    // MARK: - 老数据 / 系统消息

    func testMissingTimestampDoesNotInventOne() {
        XCTAssertEqual(HistoryTimestamp.label(createdAt: 0, now: millis(2026, 11, 20, 22, 0)), "")
        XCTAssertEqual(HistoryTimestamp.label(createdAt: -1), "")
        // 没有时间：至少还有说话人标签（模型要分清谁说的）。
        XCTAssertEqual(
            HistoryTimestamp.prefix(createdAt: 0, role: .user), "用户："
        )
    }

    func testSystemAndToolMessagesGetNoPrefix() {
        XCTAssertEqual(HistoryTimestamp.speakerPrefix(role: .system), "")
        XCTAssertEqual(
            HistoryTimestamp.prefix(
                createdAt: millis(2026, 11, 20, 21, 4), role: .system
            ),
            ""
        )
    }

    // MARK: - 接进 history()

    private func message(
        _ role: MessageRole, _ content: String, at: Int64
    ) -> MessageEntity {
        MessageEntity(
            id: UUID().uuidString, sessionId: "s-1", personaId: "p-1", role: role,
            content: content, createdAt: at, updatedAt: at
        )
    }

    func testHistoryPrefixesEveryConversationalMessage() throws {
        let now = millis(2026, 11, 20, 22, 0)
        let messages = [
            message(.user, "今天加班到十点", at: millis(2026, 11, 20, 21, 4)),
            message(.assistant, "嗯，累坏了吧", at: millis(2026, 11, 20, 21, 6)),
            message(.user, "早", at: millis(2026, 11, 19, 9, 12))
        ]
        let history = PromptBuilder.history(
            messages: messages, summarizedUpTo: nil, now: now, calendar: calendar("Asia/Shanghai")
        )
        XCTAssertEqual(history.map(\.content), [
            "[21:04] 用户：今天加班到十点",
            "[21:06] 他：嗯，累坏了吧",
            "[11-19 09:12] 用户：早"
        ])
    }

    func testHistoryExcludesSystemMessagesAndNeverPrefixesThem() {
        let now = millis(2026, 11, 20, 22, 0)
        let messages = [
            message(.system, "系统提示不该进历史", at: millis(2026, 11, 20, 20, 0)),
            message(.user, "在吗", at: millis(2026, 11, 20, 21, 4))
        ]
        let history = PromptBuilder.history(
            messages: messages, summarizedUpTo: nil, now: now, calendar: calendar("Asia/Shanghai")
        )
        XCTAssertEqual(history.count, 1)
        XCTAssertEqual(history[0].content, "[21:04] 用户：在吗")
        XCTAssertFalse(history.contains { $0.content.hasPrefix("[") && $0.role == "system" })
    }

    func testHistoryCanBeSwitchedBackOffForOldCallSites() {
        let now = millis(2026, 11, 20, 22, 0)
        let messages = [message(.user, "在吗", at: millis(2026, 11, 20, 21, 4))]
        let history = PromptBuilder.history(
            messages: messages, summarizedUpTo: nil, now: now, timestamped: false
        )
        XCTAssertEqual(history.map(\.content), ["在吗"])
    }

    /// 图片/语音那种「正文为空」的消息：时间戳加在补出来的那句话前面，不是后面。
    func testPlaceholderTextComesAfterTheTimestamp() {
        let now = millis(2026, 11, 20, 22, 0)
        let image = MessageEntity(
            id: "m-1", sessionId: "s-1", personaId: "p-1", role: .user,
            content: "", createdAt: millis(2026, 11, 20, 21, 4),
            updatedAt: millis(2026, 11, 20, 21, 4),
            attachmentsJson: #"[{"uri":"file:///a.jpg"}]"#
        )
        let history = PromptBuilder.history(
            messages: [image], summarizedUpTo: nil, now: now, calendar: calendar("Asia/Shanghai")
        )
        XCTAssertEqual(history.first?.content, "[21:04] 用户：（发来一张图片）")
    }
}
