import Foundation
import XCTest
@testable import MyFriendKit

/// 当前活动（`set_activity`）的夹取、枚举归一与「忙」的语义。
///
/// 契约在 `spec/agent-core/cases/tool-args.json` 的 `activity` 段（两端共用同一份用例）：
/// 这里**逐条跑那份 JSON**，而不是把期望值再抄一遍——抄一遍就等于允许两端慢慢漂。
final class PersonaActivityTests: XCTestCase {

    private let now: Int64 = 1_794_500_000_000

    // MARK: - 契约用例（tool-args.json 的 activity）

    private func specObject() throws -> [String: Any] {
        var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let fm = FileManager.default
        for _ in 0..<16 {
            let candidate = url.appendingPathComponent("spec/agent-core/cases/tool-args.json")
            if fm.fileExists(atPath: candidate.path) {
                guard let data = try? Data(contentsOf: candidate),
                      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    throw XCTSkip("tool-args.json 读不出来")
                }
                return object
            }
            let parent = url.deletingLastPathComponent()
            if parent.path == url.path { break }
            url = parent
        }
        throw XCTSkip("找不到 spec/agent-core/cases/tool-args.json（iOS 单独 checkout 时正常）")
    }

    func testActivityContractCasesFromSpec() throws {
        let root = try specObject()
        guard let activity = root["activity"] as? [String: Any],
              let cases = activity["cases"] as? [[String: Any]] else {
            throw XCTSkip("tool-args.json 里还没有 activity 段（Android 同批产出）")
        }
        XCTAssertFalse(cases.isEmpty)
        for item in cases {
            let input = (item["input"] as? [String: Any]) ?? [:]
            let expect = (item["expect"] as? [String: Any]) ?? [:]
            let label = "activity 用例：\(input)"
            if let minutes = expect["minutes"] as? NSNumber {
                XCTAssertEqual(
                    ToolArgClamp.activityMinutes(input["minutes"]),
                    minutes.intValue,
                    label
                )
            }
            if let kind = expect["kind"] as? String {
                XCTAssertEqual(ActivityKind(stored: input["kind"] as? String).stored, kind, label)
            }
            if let availability = expect["availability"] as? String {
                XCTAssertEqual(
                    ActivityAvailability(stored: input["availability"] as? String).stored,
                    availability,
                    label
                )
            }
        }
    }

    /// 契约里声明的边界与服务端规则一致（15–480、默认 60）。
    func testActivityClampBounds() throws {
        let root = try specObject()
        guard let activity = root["activity"] as? [String: Any],
              let rules = activity["rules"] as? [String: Any],
              let minutesRule = rules["minutes"] as? [String: Any] else {
            throw XCTSkip("tool-args.json 里还没有 activity.rules")
        }
        XCTAssertEqual(ToolArgClamp.activityMinutesMin, (minutesRule["min"] as? NSNumber)?.intValue)
        XCTAssertEqual(ToolArgClamp.activityMinutesMax, (minutesRule["max"] as? NSNumber)?.intValue)
        XCTAssertEqual(ToolArgClamp.activityMinutesDefault, (minutesRule["default"] as? NSNumber)?.intValue)
    }

    // MARK: - 夹取

    func testClampMinutesHandlesDirtyShapes() {
        XCTAssertEqual(ToolArgClamp.activityMinutes(nil), 60)
        XCTAssertEqual(ToolArgClamp.activityMinutes(15), 15)
        XCTAssertEqual(ToolArgClamp.activityMinutes(14), 15)
        XCTAssertEqual(ToolArgClamp.activityMinutes(480), 480)
        XCTAssertEqual(ToolArgClamp.activityMinutes(481), 480)
        XCTAssertEqual(ToolArgClamp.activityMinutes(-3), 15)
        XCTAssertEqual(ToolArgClamp.activityMinutes("120"), 120)
        XCTAssertEqual(ToolArgClamp.activityMinutes(" 90 "), 90)
        XCTAssertEqual(ToolArgClamp.activityMinutes("abc"), 60)
        XCTAssertEqual(ToolArgClamp.activityMinutes("1e3"), 60)
        // 布尔是 NSNumber 的一种，不能被当成 1 → 15。
        XCTAssertEqual(ToolArgClamp.activityMinutes(true), 60)
        XCTAssertEqual(ToolArgClamp.activityMinutes([1, 2]), 60)
    }

    // MARK: - 结构与过期

    func testActivityRoundTripsThroughJson() throws {
        let activity = PersonaActivity(
            kind: .commute, activity: "在地铁上", minutes: 40, availability: .limited,
            startedAt: now
        )
        let json = try XCTUnwrap(activity.jsonString())
        XCTAssertEqual(PersonaActivity.decode(json, now: now), activity)
        XCTAssertTrue(activity.isActive(now: now + 39 * 60_000))
        XCTAssertFalse(activity.isActive(now: now + 40 * 60_000), "到 until 就失效")
        XCTAssertEqual(activity.remainingMinutes(now: now), 40)
        XCTAssertEqual(activity.remainingMinutes(now: now + 40 * 60_000), 0)
    }

    func testActivityToleratesBadJsonAndMissingFields() {
        XCTAssertNil(PersonaActivity.decode(nil, now: now))
        XCTAssertNil(PersonaActivity.decode("不是 JSON", now: now))
        XCTAssertNil(PersonaActivity.decode("{}", now: now), "没有活动内容等于没在忙")
        // 只有 activity：按「从现在开始 60 分钟、BUSY、OTHER」补齐。
        let activity = PersonaActivity.decode(#"{"activity":"在开会"}"#, now: now)
        XCTAssertEqual(activity?.minutes, 60)
        XCTAssertEqual(activity?.availability, .busy)
        XCTAssertEqual(activity?.kind, .other)
        XCTAssertEqual(activity?.startedAt, now)
    }

    /// 跨端形状：只带 `until`（不带 `startedAt` / `minutes`）时，必须按 `until` 失效，
    /// 不能拿「现在 + 默认 60 分钟」把它续命。
    func testActivityHonorsExplicitUntilWithoutStartedAt() throws {
        let until = now + 5 * 60_000
        let raw = #"{"kind":"WORK","activity":"在开会","until":\#(until)}"#
        let activity = try XCTUnwrap(PersonaActivity.decode(raw, now: now))
        XCTAssertEqual(activity.until, until)
        XCTAssertTrue(activity.isActive(now: now + 4 * 60_000))
        XCTAssertFalse(activity.isActive(now: until), "到显式的 until 就该失效")
    }

    // MARK: - 主动开口（§4）

    func testBusySkipsProactiveAndLimitedSlowsDown() {
        let busy = PersonaActivity(
            kind: .work, activity: "在开会", minutes: 60, availability: .busy, startedAt: now
        )
        let limited = PersonaActivity(
            kind: .work, activity: "在赶图", minutes: 60, availability: .limited, startedAt: now
        )
        let free = PersonaActivity(
            kind: .rest, activity: "在家躺着", minutes: 60, availability: .free, startedAt: now
        )
        XCTAssertEqual(PersonaActivity.proactiveGate(busy, now: now), .skip)
        XCTAssertEqual(
            PersonaActivity.proactiveGate(limited, now: now),
            .slowDown(factor: PersonaActivity.limitedProbabilityFactor)
        )
        XCTAssertEqual(PersonaActivity.proactiveGate(free, now: now), .allow)
        XCTAssertEqual(PersonaActivity.proactiveGate(nil, now: now), .allow)
        // 过期 = 照常。
        XCTAssertEqual(PersonaActivity.proactiveGate(busy, now: now + 61 * 60_000), .allow)
    }

    // MARK: - 渲染

    func testRenderStateLineExplainsBusyAndEndTime() {
        let busy = PersonaActivity(
            kind: .work, activity: "在事务所赶第三版方案", minutes: 90,
            availability: .busy, startedAt: now
        )
        // 固定时区，避免断言跟着测试机时区漂。
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        let line = busy.renderStateLine(now: now, calendar: calendar)
        XCTAssertTrue(line.contains("在事务所赶第三版方案"))
        XCTAssertFalse(line.contains("你在在"), "活动本身就带「在……」时不要再加一个「你在」")
        XCTAssertTrue(line.contains("结束"))
        XCTAssertTrue(line.contains("不要谎称有空"))
        XCTAssertFalse(line.contains("WORK"), "不展示 kind 原值")
        XCTAssertFalse(line.contains("BUSY"), "不展示 availability 原值")
    }

    func testDisplayTextNeverShowsRawEnum() {
        let activity = PersonaActivity(
            kind: .study, activity: "在看书", minutes: 30, availability: .free, startedAt: now
        )
        XCTAssertEqual(activity.displayText(now: now), "在看书")
        XCTAssertEqual(activity.displayText(now: now + 31 * 60_000), "", "过期就不显示")
    }
}
