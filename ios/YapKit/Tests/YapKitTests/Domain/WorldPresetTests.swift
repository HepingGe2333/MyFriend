import Foundation
import XCTest
@testable import YapKit

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
        // world_name 用规范模板渲染，并且是世界段第一行。
        XCTAssertTrue(text.hasPrefix(
            "# 你所在的世界\n" + PersonaPromptScaffold.worldNameLine("临港新城·云筑建筑事务所")
        ))
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

    // MARK: - notes（兜底自由文本）与亲密度

    /// notes：解析 → 渲染 → 落库往返；**存储不截断**，只有渲染提示词时取前 1200 字。
    func testNotesRoundTripKeepsFullTextAndRendersLimited() throws {
        let preset = WorldPreset(
            worldName: "临港新城",
            description: "沿海城市。",
            notes: "事务所里没人叫他全名，都叫「深哥」。"
        )
        let rendered = preset.renderForPrompt()
        XCTAssertTrue(rendered.contains("其他背景：事务所里没人叫他全名，都叫「深哥」。"))
        let json = try XCTUnwrap(preset.jsonString())
        XCTAssertEqual(WorldPreset.decode(json), preset)

        // 超长：内存/落库都保留全文（0.12 取消截断），只有渲染提示词时取前 1200 字。
        let long = String(repeating: "背", count: WorldPreset.maxNotesChars + 30)
        let untruncated = WorldPreset(notes: long)
        XCTAssertEqual(untruncated.notes.count, long.count, "存储不得截断")
        XCTAssertEqual(WorldPreset.decode(untruncated.jsonString())?.notes, long, "往返保留全文")
        let longRendered = untruncated.renderForPrompt()
        XCTAssertTrue(longRendered.contains("其他背景："))
        XCTAssertFalse(longRendered.contains(long), "渲染时按 1200 字截断")
        XCTAssertTrue(longRendered.contains(String(repeating: "背", count: WorldPreset.maxNotesChars)))
        XCTAssertFalse(longRendered.contains(String(repeating: "背", count: WorldPreset.maxNotesChars + 1)))

        // 只有 notes 也算"有世界"（不能因为结构化字段都空就把它当没有）。
        XCTAssertFalse(untruncated.isEmpty)
        // 坏类型当成没写。
        XCTAssertEqual(WorldPreset.from(object: ["notes": 42]).notes, "42")
        XCTAssertTrue(WorldPreset.from(object: ["notes": ["x": 1]]).notes.isEmpty)
    }

    /// 0.12：解析/初始化/落库都不按建议上限截断 —— 大文本原样往返。
    ///
    /// 以前 description 600、notes 1200、日程 12 条、关系 8 人、义务 6 条都会在
    /// `init` / `from(object:)` 里被砍；两端砍的长度不同，`sourceHash` 就会对同一份
    /// 人格算出不同的值。这条用「全部超出旧上限」的一份预设夹住整条链路。
    func testLargeFieldsAreNotTruncatedAnymore() throws {
        let longDescription = String(repeating: "城", count: WorldPreset.descriptionMax + 120)
        let longNotes = String(repeating: "事", count: WorldPreset.maxNotesChars + 200)
        let manySchedule = (0..<(WorldPreset.maxScheduleItems + 3)).map { index in
            WorldScheduleItem(day: "每天", start: "09:00", end: "10:00",
                              activity: "第\(index)件事")
        }
        let manyRelationships = (0..<(WorldPreset.maxRelationships + 2)).map { index in
            WorldRelationship(name: "人\(index)", relation: "朋友", note: "备注\(index)")
        }
        let manyObligations = (0..<(WorldPreset.maxObligations + 2)).map { "义务\($0)" }
        let preset = WorldPreset(
            worldName: "临港新城",
            era: "当代",
            description: longDescription,
            schedule: manySchedule,
            relationships: manyRelationships,
            obligations: manyObligations,
            notes: longNotes
        )
        XCTAssertEqual(preset.description, longDescription)
        XCTAssertEqual(preset.notes, longNotes)
        XCTAssertEqual(preset.schedule.count, manySchedule.count)
        XCTAssertEqual(preset.relationships.count, manyRelationships.count)
        XCTAssertEqual(preset.obligations.count, manyObligations.count)

        let json = try XCTUnwrap(preset.jsonString())
        let decoded = try XCTUnwrap(WorldPreset.decode(json))
        XCTAssertEqual(decoded, preset, "落库/解析必须原样往返")

        // `from(object:)` 走的是另一条解析路径（模型输出/手写 JSON），同样不能截断。
        let object: [String: Any] = [
            "worldName": "临港新城",
            "description": longDescription,
            "notes": longNotes,
            "schedule": manySchedule.map { item in
                ["day": item.day, "start": item.start, "end": item.end, "activity": item.activity]
            },
            "relationships": manyRelationships.map { person in
                ["name": person.name, "relation": person.relation, "note": person.note]
            },
            "obligations": manyObligations
        ]
        let parsed = WorldPreset.from(object: object)
        XCTAssertEqual(parsed.description, longDescription)
        XCTAssertEqual(parsed.notes, longNotes)
        XCTAssertEqual(parsed.schedule.count, manySchedule.count)
        XCTAssertEqual(parsed.relationships.count, manyRelationships.count)
        XCTAssertEqual(parsed.obligations, manyObligations)
    }

    /// §2.1：「没有世界预设」= **所有字段都为空**（`worldName` / `era` / `description` /
    /// `schedule` / `relationships` / `obligations` / **`notes`**）。
    ///
    /// 这条不是"再测一遍 isEmpty"，而是用**手写的 JSON** 和**逐字段构造**两侧夹住这个判定：
    /// 任何一处退回"只看前四个字段"（曾经在两端出现过的偏差形态），notes 那一组就会红。
    func testEmptyWorldMeansAllSevenFieldsEmpty() throws {
        // 1) 七字段全空 → 没有世界预设（decode 与落库都要走同一个判定）。
        XCTAssertTrue(WorldPreset().isEmpty)
        XCTAssertNil(WorldPreset.decode("{}"))
        XCTAssertNil(WorldPreset.decode(
            #"{"worldName":"","era":"","description":"","schedule":[],"relationships":[],"obligations":[],"notes":""}"#
        ))
        XCTAssertNil(WorldPreset().jsonString(), "没有世界不落库")

        // 2) 七个字段里**任何一个**单独非空 → 就算有世界。
        let variants: [WorldPreset] = [
            WorldPreset(worldName: "临港新城"),
            WorldPreset(era: "当代"),
            WorldPreset(description: "沿海城市里的事务所。"),
            WorldPreset(schedule: [
                WorldScheduleItem(day: "工作日", start: "09:00", end: "18:30", activity: "画图")
            ]),
            WorldPreset(relationships: [WorldRelationship(name: "母亲", relation: "家人")]),
            WorldPreset(obligations: ["按节点交图"]),
            WorldPreset(notes: "事务所里没人叫他全名。")
        ]
        XCTAssertEqual(variants.count, 7, "§2.1 一共七个字段")
        for (index, preset) in variants.enumerated() {
            XCTAssertFalse(preset.isEmpty, "只有第 \(index + 1) 个字段非空时不能当成「没有世界」")
        }

        // 3) 只有 notes 的 JSON：decode 必须返回非 nil，并且能渲染、能落库往返。
        let decoded = try XCTUnwrap(WorldPreset.decode(#"{"notes":"行业黑话很多。"}"#),
                                    "只有 notes 也算有世界（它是被翻译/被渲染的字段）")
        XCTAssertEqual(decoded.notes, "行业黑话很多。")
        XCTAssertEqual(WorldPreset.decode(try XCTUnwrap(decoded.jsonString())), decoded)
        XCTAssertTrue(decoded.renderForPrompt().contains("其他背景：行业黑话很多。"))
    }

    /// 亲密度是可选的：不填不写 key，1–5 之外当成没填。
    func testRelationshipIntimacyRoundTrip() throws {
        let person = WorldRelationship(name: "母亲", relation: "家人", note: "打电话", intimacy: 4)
        XCTAssertEqual(person.renderedLine, "母亲（家人）：打电话，亲密度 4/5")
        XCTAssertEqual(WorldPreset.parseRelationshipLines(person.renderedLine), [person])
        let plain = WorldRelationship(name: "老周", relation: "同事", note: "")
        XCTAssertEqual(plain.renderedLine, "老周（同事）")
        XCTAssertNil(WorldRelationship(name: "x", intimacy: 0).intimacy)

        let preset = WorldPreset(
            worldName: "事务所",
            relationships: [person, plain]
        )
        let json = try XCTUnwrap(preset.jsonString())
        XCTAssertTrue(json.contains("\"intimacy\":4"))
        XCTAssertEqual(WorldPreset.decode(json)?.relationships, [person, plain])
        let rendered = preset.renderForPrompt()
        XCTAssertTrue(rendered.contains("亲密度 4/5"))
    }

    /// 空状态示例必须与 `spec/.../cases/world-preset-linshen.json` 的 `expect` 一致。
    ///
    /// 抄一份样本进源码（App 里没有 spec/ 目录）就有漂的风险，这条测试是那份"抄本"的守卫。
    func testSampleMatchesSpecCase() throws {
        let root = try specCase()
        let expect = try XCTUnwrap(root["expect"] as? [String: Any])
        let data = try JSONSerialization.data(withJSONObject: expect)
        let text = try XCTUnwrap(String(data: data, encoding: .utf8))
        let fromSpec = try XCTUnwrap(WorldPreset.decode(text))
        let sample = WorldPresetSample.linshen
        XCTAssertEqual(sample.worldName, fromSpec.worldName)
        XCTAssertEqual(sample.era, fromSpec.era)
        XCTAssertEqual(sample.description, fromSpec.description)
        XCTAssertEqual(sample.schedule, fromSpec.schedule)
        XCTAssertEqual(sample.relationships, fromSpec.relationships)
        XCTAssertEqual(sample.obligations, fromSpec.obligations)
        XCTAssertEqual(sample.notes, fromSpec.notes)
    }

    /// worldName 为空时 world_name 整行省略（其余世界字段照常渲染）。
    func testWorldNameLineOnlyAppearsWhenSet() {
        let nameless = WorldPreset(era: "当代", description: "沿海城市。")
        XCTAssertFalse(nameless.renderForPrompt().contains("你所在的地方："))
        let named = WorldPreset(worldName: "临港新城")
        XCTAssertTrue(named.renderForPrompt().contains(
            PersonaPromptScaffold.worldNameLine("临港新城")
        ))
        XCTAssertTrue(named.renderForPrompt().hasSuffix(PersonaPromptScaffold.worldHint))
    }

    /// 世界段用规范脚手架渲染：开场白、背景提示、notes 的「其他背景：」都在。
    func testPromptUsesScaffoldWorldLines() {
        let preset = WorldPreset(
            worldName: "临港新城",
            era: "当代，沿海城市",
            description: "事务所竞争激烈。",
            obligations: ["按节点交图"],
            notes: "行业黑话很多。"
        )
        let rendered = preset.renderForPrompt()
        XCTAssertTrue(rendered.hasPrefix(PersonaPromptScaffold.worldSection))
        XCTAssertTrue(rendered.contains("当代，沿海城市。事务所竞争激烈。"))
        XCTAssertTrue(rendered.contains("- 必须：按节点交图"))
        XCTAssertTrue(rendered.contains("其他背景：行业黑话很多。"))
        XCTAssertTrue(rendered.contains(PersonaPromptScaffold.worldHint))
    }
}
