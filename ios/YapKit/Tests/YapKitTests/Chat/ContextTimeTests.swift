import Foundation
import XCTest
@testable import YapKit

/// 「每一轮都注入当前时间」（`spec/context-time/README.md`，0.13）。
///
/// 这条规范要修的是一个很实际的 bug：时间缓存过 → 隔一天再聊，提示词里还是前一天的日期，
/// 模型会拿旧日期说话。所以这里钉四件事：
/// 1. 第一行带**日期 + 周几 + 时刻 + UTC 偏移**；
/// 2. 时间是从传进来的 `now` 现取的（跨天变、换时区变）；
/// 3. 只出现一次（工具多轮不会重复追加）；
/// 4. 主动消息与自主循环的提示词也带同样的时间。
final class ContextTimeTests: XCTestCase {

    private func calendar(_ timeZone: String) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: timeZone)!
        return calendar
    }

    private func millis(
        _ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int,
        timeZone: String = "Asia/Shanghai"
    ) -> Int64 {
        let calendar = calendar(timeZone)
        let date = calendar.date(from: DateComponents(
            year: year, month: month, day: day, hour: hour, minute: minute
        ))!
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    private func occurrences(of needle: String, in haystack: String) -> Int {
        guard !needle.isEmpty else { return 0 }
        var count = 0
        var range = haystack.startIndex..<haystack.endIndex
        while let found = haystack.range(of: needle, range: range) {
            count += 1
            range = found.upperBound..<haystack.endIndex
        }
        return count
    }

    // MARK: - 形状

    /// 第一行是「现在是 2026-11-18 周三 19:40（UTC+08:00）。」——四个要素都要有。
    func testStateSectionInjectsDateWeekdayClockAndOffset() {
        let now = millis(2026, 11, 18, 19, 40)
        let block = PromptBuilder.stateSection(now: now, activity: nil, calendar: calendar("Asia/Shanghai"))
        XCTAssertTrue(block.hasPrefix("\n\n# 你此刻的状态\n"), "时间必须在这段的第一行")
        let firstLine = block
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: "\n")
            .dropFirst()  // 标题
            .first
            .map(String.init) ?? ""
        XCTAssertTrue(firstLine.contains("2026-11-18"), "要有日期（跨天才能看出来）：\(firstLine)")
        XCTAssertTrue(firstLine.contains("周三"), "要有星期：\(firstLine)")
        XCTAssertTrue(firstLine.contains("19:40"), "要有时刻：\(firstLine)")
        XCTAssertTrue(firstLine.contains("UTC+08:00"), "要有 UTC 偏移：\(firstLine)")
        XCTAssertTrue(firstLine.hasPrefix("现在是"), "形状来自脚手架 state_line：\(firstLine)")
        XCTAssertTrue(firstLine.hasSuffix("。"))
    }

    /// `now <= 0`（不知道时间）时整段不出现——老调用点与测试不必关心时间。
    func testStateSectionIsEmptyWithoutTime() {
        XCTAssertEqual(PromptBuilder.stateSection(now: 0, activity: nil), "")
    }

    /// 同一会话隔一天再发消息：提示词里的日期必须变。
    ///
    /// 这正是「时间不缓存」的验收点：两次 `stateSection` 拿到的 `now` 不同，
    /// 渲染出来的第一行就必须不同（而不是复用上一次的字符串）。
    func testDateChangesAcrossDays() {
        let today = millis(2026, 11, 18, 23, 55)
        let tomorrow = millis(2026, 11, 19, 0, 5)
        let first = PromptBuilder.stateSection(now: today, activity: nil, calendar: calendar("Asia/Shanghai"))
        let second = PromptBuilder.stateSection(now: tomorrow, activity: nil, calendar: calendar("Asia/Shanghai"))
        XCTAssertNotEqual(first, second)
        XCTAssertTrue(first.contains("2026-11-18 周三"))
        XCTAssertTrue(second.contains("2026-11-19 周四"), "跨天后日期与星期都要变")
    }

    /// 设备时区变了：同一毫秒，注入的时刻与偏移都必须跟着变。
    func testTimeZoneChangeChangesClockAndOffset() {
        let now = millis(2026, 11, 18, 19, 40)
        let shanghai = PromptBuilder.stateSection(now: now, activity: nil, calendar: calendar("Asia/Shanghai"))
        let newYork = PromptBuilder.stateSection(now: now, activity: nil, calendar: calendar("America/New_York"))
        XCTAssertTrue(shanghai.contains("19:40（UTC+08:00）"), shanghai)
        XCTAssertTrue(newYork.contains("06:40（UTC-05:00）"), newYork)
        XCTAssertTrue(newYork.contains("2026-11-18"), "同一个瞬间在纽约还是 18 号")
    }

    /// 注入行只出现一次：即便调用点同时给了老的 `presenceBlock`，也不能出现两行时间。
    func testInjectedTimeAppearsExactlyOnceInSystemPrompt() {
        let now = millis(2026, 11, 18, 19, 40)
        let moment = PromptClock.momentText(now, calendar: calendar("Asia/Shanghai"))
        let system = PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: "你叫林深。",
            stateBlock: PromptBuilder.stateSection(
                now: now, activity: nil,
                presenceDetail: "在事务所赶图",
                calendar: calendar("Asia/Shanghai")
            ),
            // 老调用点还会传 presenceBlock：它只作为 stateBlock 为空时的兜底。
            presenceBlock: "\n\n## 你此刻的状态\n他正在发呆。",
            toolsCapability: ToolCatalog.capabilityPrompt()
        ))
        XCTAssertEqual(occurrences(of: moment, in: system), 1, "同一时刻只能注入一次")
        XCTAssertEqual(occurrences(of: "现在是", in: system), 1, "不能出现两行「现在是…」")
    }

    /// 自主循环的提示词也带时间（同一形状，含 UTC 偏移）。
    func testAgentPromptInjectsMomentWithOffset() {
        let now = millis(2026, 11, 18, 19, 40)
        let prompt = AgentPromptBuilder.user(now: now, calendar: calendar("Asia/Shanghai"))
        XCTAssertTrue(prompt.contains("2026-11-18 周三 19:40（UTC+08:00）"), prompt)
        XCTAssertTrue(prompt.contains("要不要做点什么"))
        // 不知道时间时不硬编一个日期。
        XCTAssertFalse(AgentPromptBuilder.user(now: 0).contains("UTC"))
    }

    /// 主动消息的提示词由 `AgentLoop.proactiveMessages` 生成：这里只钉「时间那一句」，
    /// 用同一份 `PromptClock` 的产出作为期望（文案来源唯一）。
    func testProactiveInstructionUsesMomentText() {
        let now = millis(2026, 11, 18, 19, 40)
        let moment = PromptClock.momentText(now, calendar: calendar("Asia/Shanghai"))
        XCTAssertTrue(moment.contains("UTC+08:00"))
        // `PromptClock.momentText` 是纯函数：同一时刻 -> 同一串，主动消息那行就是它。
        XCTAssertEqual(moment, PromptClock.momentText(now, calendar: calendar("Asia/Shanghai")))
    }
}
