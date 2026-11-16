import Foundation
import XCTest
@testable import YapKit

/// 后台补算与通知文案（`Services/BackgroundCatchUp.swift`）。
///
/// 这两件事在 iOS 上是「平台限制」的正面回应：系统不给常驻，
/// 就把「错过的时间」在回来时补一次，并且通知里**永远不放他说的话**。
final class BackgroundCatchUpTests: XCTestCase {

    private let now: Int64 = 1_794_500_000_000

    // MARK: - 该不该补一次「想一下」

    func testNeverTickedBeforeMeansCatchUp() {
        let plan = BackgroundCatchUp.plan(
            lastHeartbeatAt: nil, lastConsolidatedAt: nil, now: now, isForeground: true
        )
        XCTAssertTrue(plan.shouldTick)
        XCTAssertTrue(plan.note.contains("还没有想过事情"))
    }

    func testRecentHeartbeatDoesNotTick() {
        let plan = BackgroundCatchUp.plan(
            lastHeartbeatAt: now - 2 * 60_000, lastConsolidatedAt: now, now: now, isForeground: true
        )
        XCTAssertFalse(plan.shouldTick, "刚想过就不补，别一打开 App 就调模型")
        XCTAssertEqual(plan.missedBeats, 0)
    }

    func testForegroundIsMoreEagerThanBackground() {
        // 12 分钟前：前台算「被挂起过」，该补；后台还没到 30 分钟，不用。
        let last = now - 12 * 60_000
        let foreground = BackgroundCatchUp.plan(
            lastHeartbeatAt: last, lastConsolidatedAt: now, now: now, isForeground: true
        )
        let background = BackgroundCatchUp.plan(
            lastHeartbeatAt: last, lastConsolidatedAt: now, now: now, isForeground: false
        )
        XCTAssertTrue(foreground.shouldTick)
        XCTAssertFalse(background.shouldTick)
    }

    func testMissedBeatsIsCappedAndNeverNegative() {
        let longAgo = BackgroundCatchUp.plan(
            lastHeartbeatAt: now - 100 * 60 * 60_000, lastConsolidatedAt: now, now: now, isForeground: true
        )
        XCTAssertEqual(longAgo.missedBeats, 99, "错过多少拍只用于日志，要封顶")
        // 时钟回拨（上次比现在还晚）不能变成负数。
        let skewed = BackgroundCatchUp.plan(
            lastHeartbeatAt: now + 60_000, lastConsolidatedAt: now, now: now, isForeground: true
        )
        XCTAssertEqual(skewed.missedBeats, 0)
        // 上次记录比现在还晚（时钟回拨/换时区）：当作「刚想过」，不补，免得连着想两次。
        XCTAssertFalse(skewed.shouldTick)
    }

    // MARK: - 该不该补一次每日整理

    func testDailyConsolidationDueAfterFourWhenMissedYesterday() throws {
        let calendar = ServiceTime.calendar(TimeZone(identifier: "Asia/Shanghai")!)
        // 早上 09:00，昨天整理过 → 今天 4:00 那次错过了，要补。
        let morning = try date(2026, 11, 14, 9, 0, calendar: calendar)
        let yesterday = try date(2026, 11, 13, 4, 10, calendar: calendar)
        let plan = BackgroundCatchUp.plan(
            lastHeartbeatAt: morning - 60 * 60_000,
            lastConsolidatedAt: yesterday,
            now: morning,
            isForeground: true,
            calendar: calendar
        )
        XCTAssertTrue(plan.shouldConsolidate)
        XCTAssertTrue(plan.note.contains("整理"))

        // 今天 04:10 刚整理过 → 不用再整理。
        let done = try date(2026, 11, 14, 4, 10, calendar: calendar)
        let again = BackgroundCatchUp.plan(
            lastHeartbeatAt: morning - 60 * 60_000,
            lastConsolidatedAt: done,
            now: morning,
            isForeground: true,
            calendar: calendar
        )
        XCTAssertFalse(again.shouldConsolidate)
    }

    func testDailyConsolidationNotBeforeFour() throws {
        let calendar = ServiceTime.calendar(TimeZone(identifier: "Asia/Shanghai")!)
        let beforeFour = try date(2026, 11, 14, 3, 30, calendar: calendar)
        let plan = BackgroundCatchUp.plan(
            lastHeartbeatAt: beforeFour - 60_000,
            lastConsolidatedAt: try date(2026, 11, 13, 4, 0, calendar: calendar),
            now: beforeFour,
            isForeground: true,
            calendar: calendar
        )
        XCTAssertFalse(plan.shouldConsolidate, "今天 4:00 还没到，不整理")
    }

    // MARK: - 下一次

    func testNextDailyAtRollsToTomorrow() throws {
        let calendar = ServiceTime.calendar(TimeZone(identifier: "Asia/Shanghai")!)
        let morning = try date(2026, 11, 14, 9, 0, calendar: calendar)
        let next = BackgroundCatchUp.nextDailyAt(now: morning, calendar: calendar)
        XCTAssertEqual(next, try date(2026, 11, 15, 4, 0, calendar: calendar))

        let earlyMorning = try date(2026, 11, 14, 2, 0, calendar: calendar)
        XCTAssertEqual(
            BackgroundCatchUp.nextDailyAt(now: earlyMorning, calendar: calendar),
            try date(2026, 11, 14, 4, 0, calendar: calendar)
        )
    }

    // MARK: - 通知文案：锁屏不露正文

    func testNotificationBodyNeverContainsMessageText() {
        let content = LocalNotificationContent.make(
            personaName: "林深", personaId: "p-1", sessionId: "s-1"
        )
        XCTAssertEqual(content.title, "林深")
        XCTAssertEqual(content.body, "给你发来一条消息")
        XCTAssertEqual(content.threadId, "yap.p-1", "同一个人一组，通知中心不刷屏")
        XCTAssertEqual(content.userInfo[LocalNotificationContent.sessionKey], "s-1")
        XCTAssertEqual(content.userInfo[LocalNotificationContent.personaKey], "p-1")

        let image = LocalNotificationContent.make(
            personaName: "林深", personaId: "p-1", sessionId: "s-1", hasAttachments: true
        )
        XCTAssertEqual(image.body, "给你发来一张图片")
    }

    func testNotificationFallsBackToGenericName() {
        let content = LocalNotificationContent.make(personaName: "   ", personaId: "p-1", sessionId: "s-1")
        XCTAssertEqual(content.title, "他")
    }

    // MARK: - 夹具

    private func date(
        _ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int,
        calendar: Calendar
    ) throws -> Int64 {
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        components.hour = hour
        components.minute = minute
        let date = try XCTUnwrap(calendar.date(from: components))
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }
}
