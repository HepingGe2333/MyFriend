import XCTest
@testable import MyFriendKit

/// 「他此刻在做什么」的纯领域测试（移植自 Android 的 PresenceTest）。
///
/// 覆盖：每种状态的边界（刚醒 44/46 分钟、翻东西 14/16 分钟、消息 2/4 分钟、
/// 三小时的整点）、优先级顺序、坏输入（nil、负数、超大值），以及「多久以前」的中文说法。
final class PresenceTests: XCTestCase {

    /// 一个固定的「现在」：2023-11-14 22:13:20 UTC。具体哪天不重要，重要的是前后一致。
    let now: Int64 = 1_700_000_000_000

    func derive(
        asleepNow: Bool = false,
        minutesSinceWake: Int? = nil,
        minutesSinceLastTick: Int? = nil,
        lastTickOutcome: String? = nil,
        minutesSinceLastBrowse: Int? = nil,
        minutesSinceLastMessage: Int? = nil,
        minutesSinceLastAssistantMessage: Int? = nil,
        hasPendingIntentSoon: Bool = false,
        hourOfDay: Int = 12
    ) -> Presence {
        PresenceDeriver.derive(
            PresenceInput(
                asleepNow: asleepNow,
                minutesSinceWake: minutesSinceWake,
                minutesSinceLastTick: minutesSinceLastTick,
                lastTickOutcome: lastTickOutcome,
                minutesSinceLastBrowse: minutesSinceLastBrowse,
                minutesSinceLastMessage: minutesSinceLastMessage,
                minutesSinceLastAssistantMessage: minutesSinceLastAssistantMessage,
                hasPendingIntentSoon: hasPendingIntentSoon,
                hourOfDay: hourOfDay
            ),
            now: now
        )
    }

    // MARK: 睡着

    func testAsleep() {
        let presence = derive(asleepNow: true)
        XCTAssertEqual(PresenceKind.asleep, presence.kind)
        XCTAssertEqual("睡着了", presence.text)
        XCTAssertFalse(presence.detail.isEmpty)
        // 不知道他什么时候睡下的，就别硬编一个「…前」。
        XCTAssertEqual("睡着了", presence.display(now: now))
    }

    // MARK: 刚醒

    func testFortyFourMinutesAfterWakingStillJustWoke() {
        let presence = derive(minutesSinceWake: 44)
        XCTAssertEqual(PresenceKind.justWoke, presence.kind)
        XCTAssertEqual("刚醒", presence.text)
    }

    func testFortySixMinutesAfterWakingIsNotJustWoke() {
        XCTAssertEqual(PresenceKind.idle, derive(minutesSinceWake: 46).kind)
    }

    func testMorningAndEveningWordingDiffers() {
        let morning = derive(minutesSinceWake: 5, hourOfDay: 7)
        let evening = derive(minutesSinceWake: 5, hourOfDay: 21)
        XCTAssertEqual(PresenceKind.justWoke, morning.kind)
        XCTAssertTrue(morning.detail.contains("一早"))
        XCTAssertTrue(evening.detail.contains("刚睡醒"))
    }

    // MARK: 在写东西

    func testMessageTwoMinutesAgoMeansWriting() {
        let presence = derive(minutesSinceLastMessage: 2)
        XCTAssertEqual(PresenceKind.writing, presence.kind)
        XCTAssertEqual("在写东西", presence.text)
    }

    func testMessageFourMinutesAgoIsNotWriting() {
        XCTAssertEqual(PresenceKind.idle, derive(minutesSinceLastMessage: 4).kind)
    }

    func testOwnMessageAlsoMeansWriting() {
        let presence = derive(minutesSinceLastAssistantMessage: 1)
        XCTAssertEqual(PresenceKind.writing, presence.kind)
        // 是他自己说的，措辞该是「刚说完一句」而不是「对方刚说了话」。
        XCTAssertTrue(presence.detail.contains("刚说完"))
    }

    // MARK: 在翻东西

    func testBrowsingFourteenMinutesAgoStillBrowsing() {
        let presence = derive(minutesSinceLastBrowse: 14)
        XCTAssertEqual(PresenceKind.browsing, presence.kind)
        XCTAssertEqual("在翻东西", presence.text)
    }

    func testBrowsingSixteenMinutesAgoIsOver() {
        XCTAssertEqual(PresenceKind.idle, derive(minutesSinceLastBrowse: 16).kind)
    }

    func testBrowsingInferredFromOutcomeText() {
        let presence = derive(minutesSinceLastTick: 5, lastTickOutcome: "自己上网看了看")
        XCTAssertEqual(PresenceKind.browsing, presence.kind)
    }

    func testBrowsingInferredFromEnglishOutcome() {
        XCTAssertEqual(PresenceKind.browsing,
                       derive(minutesSinceLastTick: 5, lastTickOutcome: "browse: example.com").kind)
    }

    // MARK: 在想事情

    func testThinkingFourteenMinutesAgoStillThinking() {
        let presence = derive(minutesSinceLastTick: 14, lastTickOutcome: "什么都没做")
        XCTAssertEqual(PresenceKind.thinking, presence.kind)
        XCTAssertEqual("在想事情", presence.text)
    }

    func testThinkingSixteenMinutesAgoIsOver() {
        XCTAssertEqual(PresenceKind.idle,
                       derive(minutesSinceLastTick: 16, lastTickOutcome: "什么都没做").kind)
    }

    // MARK: 等时机

    func testPendingIntentMeansWaiting() {
        let presence = derive(hasPendingIntentSoon: true)
        XCTAssertEqual(PresenceKind.waiting, presence.kind)
        XCTAssertEqual("过一会儿想找你", presence.text)
        // 界面上不能出现「窗口」这种内部说法。
        XCTAssertFalse(presence.text.contains("窗口"))
        XCTAssertFalse(presence.detail.contains("窗口"))
    }

    // MARK: 在忙别的

    func testExactlyThreeHoursMeansAway() {
        XCTAssertEqual(PresenceKind.away, derive(minutesSinceLastTick: 180).kind)
    }

    func testOneMinuteShortOfThreeHoursStillIdle() {
        XCTAssertEqual(PresenceKind.idle, derive(minutesSinceLastTick: 179).kind)
    }

    func testVeryLongSilenceMeansAway() {
        let presence = derive(minutesSinceLastTick: 600_000)
        XCTAssertEqual(PresenceKind.away, presence.kind)
        XCTAssertEqual("在忙别的", presence.text)
    }

    // MARK: 优先级

    func testPriorityAsleepWinsEverything() {
        let presence = derive(asleepNow: true, minutesSinceWake: 10, minutesSinceLastTick: 1,
                              minutesSinceLastBrowse: 1, minutesSinceLastMessage: 1,
                              minutesSinceLastAssistantMessage: 1, hasPendingIntentSoon: true)
        XCTAssertEqual(PresenceKind.asleep, presence.kind)
    }

    func testPriorityJustWokeBeatsWriting() {
        XCTAssertEqual(PresenceKind.justWoke,
                       derive(minutesSinceWake: 30, minutesSinceLastMessage: 1,
                              minutesSinceLastAssistantMessage: 1).kind)
    }

    func testPriorityWritingBeatsBrowsingAndThinking() {
        XCTAssertEqual(PresenceKind.writing,
                       derive(minutesSinceWake: 600, minutesSinceLastTick: 3, minutesSinceLastBrowse: 3,
                              minutesSinceLastMessage: 2, hasPendingIntentSoon: true).kind)
    }

    func testPriorityBrowsingBeatsThinking() {
        XCTAssertEqual(PresenceKind.browsing,
                       derive(minutesSinceLastTick: 2, minutesSinceLastBrowse: 5,
                              minutesSinceLastMessage: 10, hasPendingIntentSoon: true).kind)
    }

    func testPriorityThinkingBeatsWaiting() {
        XCTAssertEqual(PresenceKind.thinking,
                       derive(minutesSinceLastTick: 4, minutesSinceLastMessage: 20,
                              hasPendingIntentSoon: true).kind)
    }

    func testPriorityWaitingBeatsAway() {
        XCTAssertEqual(PresenceKind.waiting,
                       derive(minutesSinceLastTick: 200, hasPendingIntentSoon: true).kind)
    }

    func testPriorityAwayBeatsIdle() {
        XCTAssertEqual(PresenceKind.away, derive(minutesSinceLastTick: 400).kind)
    }

    // MARK: 坏输入

    func testNothingOnRecordMeansIdle() {
        let presence = derive()
        XCTAssertEqual(PresenceKind.idle, presence.kind)
        XCTAssertEqual("在发呆", presence.text)
        XCTAssertTrue(presence.isMeaningful())
        // 什么都没发生过：不该编一个「…前」出来。
        XCTAssertEqual(0, presence.sinceAt)
    }

    func testNegativeMinutesTreatedAsJustNow() {
        let presence = derive(minutesSinceLastTick: -5)
        XCTAssertEqual(PresenceKind.thinking, presence.kind)
        XCTAssertEqual(now, presence.sinceAt)
    }

    func testLateNightIdleWordingDiffers() {
        let presence = derive(hourOfDay: 2)
        XCTAssertEqual(PresenceKind.idle, presence.kind)
        XCTAssertTrue(presence.detail.contains("没睡"))
    }

    func testFutureTimestampsAreClampedToNow() {
        let presence = derive(minutesSinceLastTick: 12)
        XCTAssertLessThanOrEqual(presence.sinceAt, now)
    }

    // MARK: 界面那一行

    func testDisplayLineIncludesHowLongAgo() {
        let presence = derive(minutesSinceLastTick: 12)
        XCTAssertEqual("在想事情 · 12 分钟前", presence.display(now: now))
    }

    func testWithoutStartTimeOnlyTheLine() {
        XCTAssertEqual("睡着了", derive(asleepNow: true).display(now: now))
    }

    func testWordingHasNoEnglishOrEngineeringTerms() {
        let samples = [
            derive(asleepNow: true),
            derive(minutesSinceWake: 5),
            derive(minutesSinceLastMessage: 1),
            derive(minutesSinceLastBrowse: 3),
            derive(minutesSinceLastTick: 3, lastTickOutcome: "什么都没做"),
            derive(hasPendingIntentSoon: true),
            derive(minutesSinceLastTick: 300),
            derive()
        ]
        for presence in samples {
            let text = presence.text + presence.detail
            XCTAssertNil(text.range(of: "[A-Za-z]", options: .regularExpression), "不该出现英文枚举名：\(text)")
            for word in ["状态", "心跳", "轮询", "窗口"] {
                XCTAssertFalse(text.contains(word), "不该出现工程词「\(word)」：\(text)")
            }
        }
    }

    // MARK: 多久以前

    func testJustNowAndMinutes() {
        XCTAssertEqual("刚刚", PresenceRules.relativeTime(ms: now - 30_000, now: now))
        XCTAssertEqual("3 分钟前", PresenceRules.relativeTime(ms: now - 3 * 60_000, now: now))
        XCTAssertEqual("59 分钟前", PresenceRules.relativeTime(ms: now - 59 * 60_000, now: now))
    }

    func testHoursAndYesterday() {
        XCTAssertEqual("1 小时前", PresenceRules.relativeTime(ms: now - 61 * 60_000, now: now))
        XCTAssertEqual("23 小时前", PresenceRules.relativeTime(ms: now - 23 * 3_600_000, now: now))
        XCTAssertEqual("昨天", PresenceRules.relativeTime(ms: now - 25 * 3_600_000, now: now))
        XCTAssertEqual("3 天前", PresenceRules.relativeTime(ms: now - 3 * 86_400_000, now: now))
    }

    func testLastWeekAndLonger() {
        XCTAssertEqual("昨天", PresenceRules.relativeTime(ms: now - 86_400_000, now: now))
        XCTAssertEqual("上周", PresenceRules.relativeTime(ms: now - 8 * 86_400_000, now: now))
        XCTAssertEqual("2 周前", PresenceRules.relativeTime(ms: now - 20 * 86_400_000, now: now))
        XCTAssertEqual("2 个月前", PresenceRules.relativeTime(ms: now - 60 * 86_400_000, now: now))
        XCTAssertEqual("很久以前", PresenceRules.relativeTime(ms: now - 400 * 86_400_000, now: now))
    }

    func testRelativeTimeBadInput() {
        // 0 / 负数表示「不知道什么时候」，留给界面自己决定不显示。
        XCTAssertEqual("", PresenceRules.relativeTime(ms: 0, now: now))
        XCTAssertEqual("", PresenceRules.relativeTime(ms: -1, now: now))
        // 时钟回拨、时间戳在将来：当作刚刚，不抛异常。
        XCTAssertEqual("刚刚", PresenceRules.relativeTime(ms: now + 10_000, now: now))
    }
}
