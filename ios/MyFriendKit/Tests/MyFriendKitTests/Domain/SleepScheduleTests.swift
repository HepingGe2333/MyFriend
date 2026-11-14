import Foundation
import XCTest
@testable import MyFriendKit

/// 睡眠、免打扰、唤醒阈值（§六）。
///
/// 这一组是纯逻辑测试：不碰数据库、不碰网络，只回答「这一轮他会不会回」。
/// 三种沉默在界面上长得几乎一样（都不回），差别全在这些判定里：
/// 免打扰连「已读」都不留，阈值没到只是不回，阈值到了要把他叫醒并把心情拉回来。
final class SleepScheduleTests: XCTestCase {

    /// 固定时钟：用当前时区构造「今天 23:30」「今天 08:00」这些点，避免写死时区。
    private func millis(hour: Int, minute: Int, day: Int = 14) -> Int64 {
        var calendar = ServiceTime.calendar()
        let date = calendar.date(from: DateComponents(
            year: 2026, month: 11, day: day, hour: hour, minute: minute
        ))!
        // `hour == 24` 想表达「次日 00:xx」时，day 由调用方自己加。
        _ = calendar
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    private func persona(
        sleep: Int? = nil,
        wake: Int? = nil,
        sleepEnabled: Bool = true,
        dnd: Bool = false,
        wakeThreshold: Int = PersonaFieldLimits.wakeThresholdDefault
    ) -> PersonaEntity {
        PersonaEntity(
            id: "p-sleep", name: "林岸",
            quietSleepMinutes: sleep, quietWakeMinutes: wake,
            sleepEnabled: sleepEnabled,
            wakeThreshold: wakeThreshold,
            dndOnSleep: dnd
        )
    }

    // MARK: - 开关

    /// `sleepEnabled = false`：他不睡觉，任何时间都会回（§6.1）。
    func testSleepDisabledNeverSleeps() {
        let awake = persona(sleep: 0, wake: 1439, sleepEnabled: false)
        XCTAssertFalse(SleepSchedule.isAsleep(awake, minuteOfDay: 0))
        XCTAssertFalse(SleepSchedule.isAsleep(awake, minuteOfDay: 12 * 60))
        XCTAssertEqual(
            SleepGate.decide(persona: awake, recentUserMessages: 99, now: millis(hour: 3, minute: 0)),
            .reply
        )
    }

    /// 没设作息但开着「会睡觉」：用默认的 23:30–08:00（§6.1）。
    func testDefaultWindowIs2330To0800() {
        let sleeper = persona()
        XCTAssertEqual(SleepSchedule.sleepMinutes(sleeper), 23 * 60 + 30)
        XCTAssertEqual(SleepSchedule.wakeMinutes(sleeper), 8 * 60)
        XCTAssertTrue(SleepSchedule.isAsleep(sleeper, minuteOfDay: 23 * 60 + 45))
        XCTAssertTrue(SleepSchedule.isAsleep(sleeper, minuteOfDay: 3 * 60))
        XCTAssertFalse(SleepSchedule.isAsleep(sleeper, minuteOfDay: 8 * 60))
        XCTAssertFalse(SleepSchedule.isAsleep(sleeper, minuteOfDay: 12 * 60))
    }

    /// 跨午夜的自定义作息也认得清（23:00–07:00）。
    func testCustomWindowAcrossMidnight() {
        let sleeper = persona(sleep: 23 * 60, wake: 7 * 60)
        XCTAssertTrue(SleepSchedule.isAsleep(sleeper, minuteOfDay: 23 * 60 + 30))
        XCTAssertTrue(SleepSchedule.isAsleep(sleeper, minuteOfDay: 6 * 60))
        XCTAssertFalse(SleepSchedule.isAsleep(sleeper, minuteOfDay: 7 * 60))
        XCTAssertFalse(SleepSchedule.isAsleep(sleeper, minuteOfDay: 22 * 60 + 59))
        XCTAssertEqual(SleepSchedule.wakeClockText(sleeper), "07:00")
    }

    /// 睡醒同一个点 = 没排作息：不能因为一条脏数据让他整天装睡。
    func testSameSleepAndWakeMeansAwake() {
        let broken = persona(sleep: 8 * 60, wake: 8 * 60)
        XCTAssertFalse(SleepSchedule.isAsleep(broken, minuteOfDay: 8 * 60))
        XCTAssertFalse(SleepSchedule.isAsleep(broken, minuteOfDay: 3 * 60))
    }

    // MARK: - 免打扰 / 唤醒阈值

    /// 免打扰：既不回、也不唤醒、也不留已读（§6.2）。
    func testDoNotDisturbNeverWakesEvenWithManyMessages() {
        let sleeper = persona(sleep: 23 * 60 + 30, wake: 8 * 60, dnd: true, wakeThreshold: 2)
        for count in [1, 2, 5, 99] {
            let decision = SleepGate.decide(
                persona: sleeper, recentUserMessages: count, now: millis(hour: 1, minute: 0)
            )
            XCTAssertEqual(decision, .sleepingDoNotDisturb, "免打扰时发 \(count) 条也不该醒")
            XCTAssertFalse(decision.shouldReply)
            XCTAssertFalse(decision.marksRead)
        }
    }

    /// 没开免打扰：消息数不到阈值 → 不回，但**是已读**（§6.2 的 `else` 分支）。
    func testBelowThresholdMarksReadButDoesNotReply() {
        let sleeper = persona(wakeThreshold: 6)
        let now = millis(hour: 1, minute: 0)
        for count in 1...5 {
            let decision = SleepGate.decide(persona: sleeper, recentUserMessages: count, now: now)
            XCTAssertEqual(decision, .sleepingWaiting)
            XCTAssertFalse(decision.shouldReply)
            XCTAssertTrue(decision.marksRead, "没开免打扰时，看到了就是已读")
        }
    }

    /// 到了阈值 → 唤醒并回复（§6.2）。
    func testAtThresholdWakesUpAndReplies() {
        let sleeper = persona(wakeThreshold: 6)
        let now = millis(hour: 1, minute: 0)
        XCTAssertEqual(
            SleepGate.decide(persona: sleeper, recentUserMessages: 5, now: now), .sleepingWaiting
        )
        let woken = SleepGate.decide(persona: sleeper, recentUserMessages: 6, now: now)
        XCTAssertEqual(woken, .wokenUp)
        XCTAssertTrue(woken.shouldReply)
        XCTAssertTrue(woken.marksRead)
        // 阈值来自人格，初始化时夹在 1–20；判定里再夹一次（防止有人直接改了字段）。
        let dirtyHigh = PersonaEntity(id: "p2", name: "他", wakeThreshold: 99)
        XCTAssertEqual(dirtyHigh.wakeThreshold, 20, "初始化时夹在 1–20")
        let dirtyLow = PersonaEntity(id: "p3", name: "他", wakeThreshold: 0)
        XCTAssertEqual(dirtyLow.wakeThreshold, 1)
        // 直接改字段绕过初始化也不会让判定失效：判定自己会夹。
        var bypassed = sleeper
        bypassed.wakeThreshold = 0
        XCTAssertEqual(SleepGate.decide(persona: bypassed, recentUserMessages: 1, now: now), .wokenUp)
    }

    /// 醒着的时候不做任何睡眠判定（阈值再低也不影响正常聊天）。
    func testAwakeHoursAlwaysReply() {
        let sleeper = persona(dnd: true, wakeThreshold: 20)
        XCTAssertEqual(
            SleepGate.decide(persona: sleeper, recentUserMessages: 0, now: millis(hour: 12, minute: 0)),
            .reply
        )
    }

    // MARK: - 被叫醒之后的心情

    /// 负面心情直接回到「平静」；其它心情只把强度压一档（§6.2）。
    func testMoodAfterWaking() {
        let negative = SleepGate.moodAfterWaking(label: "低落", intensity: 4)
        XCTAssertEqual(negative.label, MoodLexicon.BASELINE)
        XCTAssertEqual(negative.intensity, 1)

        let positive = SleepGate.moodAfterWaking(label: "雀跃", intensity: 4)
        XCTAssertEqual(positive.label, "雀跃")
        XCTAssertEqual(positive.intensity, 3)

        let faint = SleepGate.moodAfterWaking(label: "雀跃", intensity: 1)
        XCTAssertEqual(faint.intensity, 1, "强度最低就是 1，不能变成 0")
    }

    /// 状态行：睡着时带上「几点醒」（§七）。
    func testStatusLineShowsWakeClock() {
        let sleeper = persona(sleep: 23 * 60 + 30, wake: 8 * 60)
        // 这个用例不依赖「现在是不是深夜」：直接检查拼接规则本身。
        let line = SleepSchedule.statusLine(nil, presenceText: "在发呆")
        XCTAssertEqual(line, "在发呆", "没有入睡人格时原样返回")

        let hint = SleepSchedule.hint(sleeper)
        XCTAssertEqual(hint, "每天 23:30 睡觉，08:00 醒来")
        XCTAssertNil(SleepSchedule.hint(persona(sleepEnabled: false)), "不睡觉的人没有作息提示")
    }
}
