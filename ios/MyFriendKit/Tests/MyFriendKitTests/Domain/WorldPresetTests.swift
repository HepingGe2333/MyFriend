import Foundation
import XCTest
@testable import MyFriendKit

/// 世界预设：宽容解析、日程匹配、提示词渲染、编辑器「一行一条」的往返。
///
/// 样本数据来自 `spec/persona-guidance/cases/world-preset-linshen.json`（实测输出的形状），
/// 文件不在时跳过——这里不复制一份样本，免得两端对「长什么样」各有各的看法。
final class WorldPresetTests: XCTestCase {

    private func specCase() throws -> [String: Any] {
        var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let fm = FileManager.default
        for _ in 0..<16 {
            let candidate = url.appendingPathComponent("spec/persona-guidance/cases/world-preset-linshen.json")
            if fm.fileExists(atPath: candidate.path) {
                guard let data = try? Data(contentsOf: candidate),
                      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    throw XCTSkip("world-preset-linshen.json 读不出来")
                }
                return object
            }
            let parent = url.deletingLastPathComponent()
            if parent.path == url.path { break }
            url = parent
        }
        throw XCTSkip("找不到 spec/persona-guidance/cases/world-preset-linshen.json")
    }

    // MARK: - 解析

    func testDecodesSpecSample() throws {
        let root = try specCase()
        let expect = try XCTUnwrap(root["expect"] as? [String: Any])
        let data = try JSONSerialization.data(withJSONObject: expect)
        let text = try XCTUnwrap(String(data: data, encoding: .utf8))
        let preset = try XCTUnwrap(WorldPreset.decode(text))
        XCTAssertFalse(preset.worldName.isEmpty)
        XCTAssertFalse(preset.era.isEmpty)
        XCTAssertFalse(preset.description.isEmpty)
        XCTAssertFalse(preset.schedule.isEmpty, "样本里有日程")
        XCTAssertFalse(preset.relationships.isEmpty)
        XCTAssertFalse(preset.obligations.isEmpty)
        // 日程的时间与活动都要原样读进来。
        XCTAssertTrue(preset.schedule.allSatisfy { !$0.activity.isEmpty })
        XCTAssertTrue(preset.schedule.contains { $0.day == "工作日" })
    }

    func testDecodeToleratesMarkdownFenceAndChatter() {
        let raw = """
        好的，下面是补全结果：
        ```json
        {"worldName":"临港新城","era":"当代","description":"沿海城市里的事务所。",
         "schedule":[{"day":"工作日","start":"09:00","end":"18:30","activity":"画图"}],
         "relationships":[],"obligations":["交图"]}
        ```
        需要我改得更具体吗？
        """
        let preset = WorldPreset.parse(raw)
        XCTAssertEqual(preset?.worldName, "临港新城")
        XCTAssertEqual(preset?.schedule.count, 1)
    }

    func testDecodeToleratesTrailingCommasAndMissingFields() {
        let raw = #"{"worldName":"小城","description":"安静。",}"#
        let preset = WorldPreset.decode(raw)
        XCTAssertEqual(preset?.worldName, "小城")
        XCTAssertEqual(preset?.schedule, [])
        XCTAssertEqual(preset?.obligations, [])
    }

    func testBadJsonIsNilNotACrash() {
        XCTAssertNil(WorldPreset.decode(nil))
        XCTAssertNil(WorldPreset.decode(""))
        XCTAssertNil(WorldPreset.decode("这不是 JSON"))
        XCTAssertNil(WorldPreset.decode("{}"), "全空 = 没有世界预设")
        XCTAssertNil(WorldPreset.decode(#"{"schedule":[]}"#))
        // 类型错（数组写成字符串、对象写成数字）不抛，按「没写」处理。
        let preset = WorldPreset.decode(#"{"worldName":123,"schedule":"工作日","obligations":"交图"}"#)
        XCTAssertEqual(preset?.worldName, "123")
    }

    // MARK: - 渲染

    func testRenderForPromptIncludesEverySection() {
        let preset = WorldPreset(
            worldName: "临港新城·云筑建筑事务所",
            era: "当代，中国沿海新一线城市，2026 年",
            description: "事务所竞争激烈，甲方频繁改图。",
            schedule: [
                WorldScheduleItem(day: "工作日", start: "09:00", end: "18:30", activity: "在事务所画图"),
                WorldScheduleItem(day: "周三", start: "20:00", end: "20:30", activity: "接母亲电话")
            ],
            relationships: [WorldRelationship(name: "母亲", relation: "家人", note: "每周三晚打电话")],
            obligations: ["按节点交图", "每周三接母亲电话"]
        )
        let text = preset.renderForPrompt()
        XCTAssertTrue(text.hasPrefix("# 你所在的世界"))
        XCTAssertTrue(text.contains("当代，中国沿海新一线城市"))
        XCTAssertTrue(text.contains("临港新城·云筑建筑事务所"))
        XCTAssertTrue(text.contains("工作日 09:00-18:30 在事务所画图"))
        XCTAssertTrue(text.contains("周三 20:00-20:30 接母亲电话"))
        XCTAssertTrue(text.contains("母亲（家人）：每周三晚打电话"))
        XCTAssertTrue(text.contains("按节点交图"))
        XCTAssertTrue(text.contains("不要主动汇报日程"), "要明确写「世界是背景，不是台词」")
        // 中文句子之间不插空格（「2026 年 你在…」是拼接 bug 的味道）。
        XCTAssertFalse(text.contains("年 你在"))
        XCTAssertFalse(text.contains("。 事务所"))
        // 空预设整节不出现。
        XCTAssertEqual(WorldPreset().renderForPrompt(), "")
    }

    // MARK: - 日程匹配

    private func millis(year: Int, month: Int, day: Int, hour: Int, minute: Int) -> Int64 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        let date = calendar.date(from: DateComponents(
            year: year, month: month, day: day, hour: hour, minute: minute
        ))!
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    func testScheduleMatchesWeekdayAndTime() {
        let preset = WorldPreset(
            worldName: "事务所",
            schedule: [
                WorldScheduleItem(day: "工作日", start: "09:00", end: "18:30", activity: "画图"),
                WorldScheduleItem(day: "周末", start: "10:00", end: "16:00", activity: "补觉")
            ]
        )
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        // 2026-11-16 是周一。
        let mondayMorning = millis(year: 2026, month: 11, day: 16, hour: 10, minute: 0)
        XCTAssertEqual(preset.scheduledActivity(now: mondayMorning, calendar: calendar)?.activity, "画图")
        let mondayNight = millis(year: 2026, month: 11, day: 16, hour: 21, minute: 0)
        XCTAssertNil(preset.scheduledActivity(now: mondayNight, calendar: calendar))
        // 2026-11-15 是周日。
        let sundayNoon = millis(year: 2026, month: 11, day: 15, hour: 12, minute: 0)
        XCTAssertEqual(preset.scheduledActivity(now: sundayNoon, calendar: calendar)?.activity, "补觉")
    }

    func testScheduleSupportsCrossMidnight() {
        let item = WorldScheduleItem(day: "每天", start: "22:00", end: "02:00", activity: "加夜班")
        XCTAssertTrue(item.contains(minuteOfDay: 23 * 60))
        XCTAssertTrue(item.contains(minuteOfDay: 60))
        XCTAssertFalse(item.contains(minuteOfDay: 12 * 60))
    }

    // MARK: - 编辑器往返

    func testScheduleLinesRoundTrip() {
        let items = [
            WorldScheduleItem(day: "工作日", start: "09:00", end: "18:30", activity: "在事务所画图、审图"),
            WorldScheduleItem(day: "周三", start: "20:00", end: "20:30", activity: "接母亲电话")
        ]
        let text = WorldPreset.scheduleLines(items)
        XCTAssertEqual(WorldPreset.parseScheduleLines(text), items)
        // 手写的形状也认：没有时间、只有日子与事。
        XCTAssertEqual(
            WorldPreset.parseScheduleLines("周末 睡到中午")[0],
            WorldScheduleItem(day: "周末", start: "", end: "", activity: "睡到中午")
        )
    }

    func testRelationshipLinesRoundTrip() {
        let items = [
            WorldRelationship(name: "母亲", relation: "家人", note: "每周三晚打电话"),
            WorldRelationship(name: "老周", relation: "合伙人", note: "")
        ]
        let text = WorldPreset.relationshipLines(items)
        XCTAssertEqual(WorldPreset.parseRelationshipLines(text), items)
        XCTAssertEqual(
            WorldPreset.parseRelationshipLines("小雨：妹妹，在外地")[0].name,
            "小雨"
        )
        // 关系与称呼同名时只写一遍（「母亲（母亲）」是没人会说的话）；读回来 relation 为空，
        // 渲染出来仍然一样——这是有意的信息压缩，不是丢字段。
        XCTAssertEqual(
            WorldRelationship(name: "母亲", relation: "母亲", note: "打电话").renderedLine,
            "母亲：打电话"
        )
    }

    func testJsonRoundTripKeepsFields() throws {
        let preset = WorldPreset(
            worldName: "临港新城", era: "当代", description: "沿海城市。",
            schedule: [WorldScheduleItem(day: "每天", start: "09:00", end: "10:00", activity: "通勤")],
            relationships: [WorldRelationship(name: "老周", relation: "合伙人", note: "催图直接")],
            obligations: ["交图"]
        )
        let json = try XCTUnwrap(preset.jsonString())
        XCTAssertEqual(WorldPreset.decode(json), preset)
        XCTAssertNil(WorldPreset().jsonString(), "空世界不落库")
    }
}
