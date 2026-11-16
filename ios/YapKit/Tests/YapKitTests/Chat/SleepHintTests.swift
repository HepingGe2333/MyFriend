import Foundation
import XCTest
@testable import YapKit

/// 聊天页那行「他睡着了（08:00 醒）；连着发几条可以叫醒他」的出现条件（0.12 续 7）。
///
/// 判定在 Kit 里（`ChatNoReplyReason.sleepHintWakeText`），整句由 App 层按语言拼。
/// 这行提示存在的唯一理由是「用户不知道他为什么只标已读不回」，所以它必须**精确**：
/// 醒着的人用 `defer_reply` 说了「等下再说」时显示「他睡着了」就是在撒谎；
/// 免打扰里叫不醒他时显示「发几条就能叫醒」也是撒谎。
final class SleepHintTests: XCTestCase {

    /// 固定时钟：今天 01:00（落在默认作息 23:30–08:00 里）。
    private func millis(hour: Int, minute: Int) -> Int64 {
        let calendar = ServiceTime.calendar()
        let date = calendar.date(from: DateComponents(
            year: 2026, month: 11, day: 14, hour: hour, minute: minute
        ))!
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    private func sleeper(sleepEnabled: Bool = true) -> PersonaEntity {
        PersonaEntity(
            id: "p-sleep",
            name: "林岸",
            quietSleepMinutes: nil,
            quietWakeMinutes: nil,
            sleepEnabled: sleepEnabled
        )
    }

    func testShowsWhenSilencedBySleep() {
        let reason = ChatNoReplyReason.sleepingWaiting(wakeAt: "08:00")
        XCTAssertEqual(
            reason.sleepHintWakeText(persona: sleeper(), now: millis(hour: 1, minute: 0)),
            "08:00"
        )
    }

    func testHiddenDoNotDisturb() {
        // 免打扰叫不醒他：不能提示「发几条就能叫醒」。
        XCTAssertNil(
            ChatNoReplyReason.sleepingDoNotDisturb
                .sleepHintWakeText(persona: sleeper(), now: millis(hour: 1, minute: 0))
        )
    }

    func testHiddenWhenDeferred() {
        // 他醒着，只是决定晚点回。
        XCTAssertNil(
            ChatNoReplyReason.deferred(minutes: 45, reason: "在忙")
                .sleepHintWakeText(persona: sleeper(), now: millis(hour: 1, minute: 0))
        )
    }

    func testHiddenAfterWakingUp() {
        // 时间走过起床点：他不再睡了，这一行该消失（界面每 30 秒重算 presence）。
        let reason = ChatNoReplyReason.sleepingWaiting(wakeAt: "08:00")
        XCTAssertNil(reason.sleepHintWakeText(persona: sleeper(), now: millis(hour: 8, minute: 0)))
        XCTAssertNil(reason.sleepHintWakeText(persona: sleeper(), now: millis(hour: 12, minute: 0)))
    }

    func testHiddenWithoutPersonaOrWithAwakePersona() {
        let reason = ChatNoReplyReason.sleepingWaiting(wakeAt: "08:00")
        XCTAssertNil(reason.sleepHintWakeText(persona: nil, now: millis(hour: 1, minute: 0)))
        XCTAssertNil(
            reason.sleepHintWakeText(persona: sleeper(sleepEnabled: false), now: millis(hour: 1, minute: 0))
        )
    }

    func testHiddenWithinCustomWindowOnly() {
        // 用户自己定的作息：02:00–06:00。01:00 醒着，03:00 睡着。
        let persona = PersonaEntity(
            id: "p-custom",
            name: "他",
            quietSleepMinutes: 2 * 60,
            quietWakeMinutes: 6 * 60,
            sleepEnabled: true
        )
        let reason = ChatNoReplyReason.sleepingWaiting(wakeAt: "06:00")
        XCTAssertNil(reason.sleepHintWakeText(persona: persona, now: millis(hour: 1, minute: 0)))
        XCTAssertEqual(reason.sleepHintWakeText(persona: persona, now: millis(hour: 3, minute: 0)), "06:00")
    }
}
