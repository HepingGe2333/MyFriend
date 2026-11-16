import Foundation
import XCTest
@testable import MyFriendKit

/// 世界可视化编辑器的状态机（`spec/persona-guidance/README.md` §2.3）：
/// 增删/排序、跨午夜、亲密度、notes 截断、保存前校验。
///
/// 界面（`Views/Persona/WorldPresetEditorView.swift`）只负责画这一份状态；
/// 行为都在这里被逐条钉住，所以"改一个下拉就写坏 JSON"这类问题能在这里抓到。
final class WorldPresetEditorStateTests: XCTestCase {

    func testScheduleAddUpdateRemoveAndMove() {
        var state = WorldEditorState()
        state = state.addSchedule()
        XCTAssertEqual(state.schedule.count, 1)
        XCTAssertEqual(state.schedule[0].start, "09:00")
        state = state.addSchedule(WorldScheduleItem(day: "周末", start: "10:00", end: "16:00", activity: "补觉"))
        XCTAssertEqual(state.schedule.map(\.day), ["工作日", "周末"])
        // 上移/下移：越界原样返回，不崩。
        state = state.moveSchedule(1, delta: -1)
        XCTAssertEqual(state.schedule.map(\.day), ["周末", "工作日"])
        XCTAssertEqual(state.moveSchedule(0, delta: -1).schedule.map(\.day), ["周末", "工作日"])
        XCTAssertEqual(state.moveSchedule(1, delta: +1).schedule.map(\.day), ["周末", "工作日"])
        state = state.updateSchedule(1) { item in
            var next = item
            next.activity = "画图"
            return next
        }
        XCTAssertEqual(state.schedule[1].activity, "画图")
        state = state.removeSchedule(0)
        XCTAssertEqual(state.schedule.count, 1)
        XCTAssertEqual(state.schedule[0].activity, "画图")
        XCTAssertEqual(state.removeSchedule(9).schedule.count, 1)
    }

    func testRelationshipDefaultsAndIntimacy() {
        var state = WorldEditorState()
        state = state.addRelationship(WorldRelationship(name: "母亲", relation: "家人", note: "打电话"))
        state = state.addRelationship(WorldRelationship(name: "老周", relation: "同事", intimacy: 4))
        XCTAssertEqual(state.relationships.count, 2)
        XCTAssertEqual(state.relationships[1].intimacy, 4)
        // 越界亲密度被视为"没填"（不把 9 写进提示词）。
        XCTAssertNil(WorldRelationship(name: "x", intimacy: 9).intimacy)
        state = state.moveRelationship(1, delta: -1)
        XCTAssertEqual(state.relationships.map(\.name), ["老周", "母亲"])
        XCTAssertTrue(state.removeRelationship(0).relationships.map(\.name) == ["母亲"])
    }

    func testObligationsArePerLineAndCapped() {
        var state = WorldEditorState()
        state = state.addObligation("按节点交图")
        state = state.addObligation("协调各专业")
        XCTAssertEqual(state.obligations, ["按节点交图", "协调各专业"])
        state = state.updateObligation(0, "按甲方节点交图")
        XCTAssertEqual(state.obligations[0], "按甲方节点交图")
        state = state.removeObligation(1)
        XCTAssertEqual(state.obligations, ["按甲方节点交图"])
        // 超过上限就不再加（界面上的「添加」按钮会同时置灰）。
        var full = WorldEditorState(obligations: Array(repeating: "x", count: WorldEditorState.maxObligations))
        full = full.addObligation("多出来的一条")
        XCTAssertEqual(full.obligations.count, WorldEditorState.maxObligations)
    }

    func testCrossMidnightDetection() {
        XCTAssertTrue(WorldEditorState.isCrossMidnight(start: "22:00", end: "02:00"))
        XCTAssertFalse(WorldEditorState.isCrossMidnight(start: "09:00", end: "18:30"))
        XCTAssertFalse(WorldEditorState.isCrossMidnight(start: "09:00", end: "九点"), "读不懂的时间不猜")
        XCTAssertEqual(WorldEditorState.nextDayEndText(end: "02:00"), "次日 02:00")
        // 跨午夜的日程匹配：23:00 与 01:00 都在里面（真正的规则由 `WorldScheduleItem` 管）。
        let item = WorldScheduleItem(day: "每天", start: "22:00", end: "02:00", activity: "夜班")
        XCTAssertTrue(item.contains(minuteOfDay: 23 * 60))
        XCTAssertTrue(item.contains(minuteOfDay: 60))
    }

    func testNotesTruncationAndHint() throws {
        var state = WorldEditorState()
        let long = String(repeating: "背", count: WorldPreset.maxNotesChars + 10)
        state = state.withNotes(long)
        XCTAssertEqual(state.notes.count, WorldPreset.maxNotesChars, "输入时就截断到 1200 字")
        XCTAssertFalse(state.notesTooLong)
        XCTAssertEqual(state.notesTruncatedBy, 0)
        let preset = state.preset
        XCTAssertEqual(preset.notes.count, WorldPreset.maxNotesChars)
        // 落库 JSON 里也带着 notes（备份 v5 的 worldJson 原样带走，不需要升版本）。
        let json = try XCTUnwrap(preset.jsonString())
        XCTAssertTrue(json.contains("\"notes\""))
        XCTAssertEqual(WorldPreset.decode(json)?.notes, preset.notes)
    }

    func testValidationPointsAtTheRightRow() {
        var state = WorldEditorState()
        // 空世界可以保存（= 不要世界）。
        XCTAssertTrue(state.canSave)

        state = state.addSchedule(WorldScheduleItem(day: "工作日", start: "九点", end: "18:00", activity: ""))
        let issues = state.issues()
        XCTAssertTrue(issues.contains(WorldEditorIssue(field: .scheduleStart, index: 0)))
        XCTAssertTrue(issues.contains(WorldEditorIssue(field: .scheduleActivity, index: 0)))

        state = state.addRelationship(WorldRelationship(name: "母亲", relation: "家人"))
        XCTAssertFalse(state.canSave == false && state.issues().isEmpty, "有关系缺字段就必须有校验项")
        state = state.removeRelationship(0)
        state = state.updateSchedule(0) { item in
            var next = item
            next.start = "09:00"
            next.activity = "画图"
            return next
        }
        XCTAssertTrue(state.canSave, "改合法之后应当能保存：\(state.issues())")

        state = state.addRelationship(WorldRelationship(name: "", relation: ""))
        let relationIssues = state.issues()
        XCTAssertTrue(relationIssues.contains(WorldEditorIssue(field: .relationshipName, index: 0)))
        XCTAssertTrue(relationIssues.contains(WorldEditorIssue(field: .relationshipRelation, index: 0)))
        // 认不出的 day 也拒绝保存（只在用户真写了的内容上报错，空串不算）。
        state = state.removeRelationship(0)
        state = state.updateSchedule(0) { item in
            var next = item
            next.day = "每个工作日"
            return next
        }
        XCTAssertTrue(state.issues().contains(WorldEditorIssue(field: .scheduleDay, index: 0)))
    }

    func testPresetRoundTripAndSample() throws {
        let state = WorldEditorState(preset: WorldPresetSample.linshen)
        XCTAssertFalse(state.isEmpty)
        XCTAssertEqual(state.preset.relationships.count, WorldPresetSample.linshen.relationships.count)
        XCTAssertTrue(state.canSave)
        XCTAssertEqual(state.preset, WorldPresetSample.linshen, "编辑器读写不能改坏示例")
        // 清空后是"没有世界"。
        let cleared = WorldEditorState(preset: nil)
        XCTAssertTrue(cleared.isEmpty)
        XCTAssertEqual(cleared.preset, WorldPreset())
    }

    /// AI 补全只填空白项：用户写过的名字/日程不会被覆盖（§2.3）。
    func testMergingBlanksOnlyFillsEmptyFields() {
        var state = WorldEditorState()
        state.worldName = "我自己写的城市"
        state.schedule = [WorldScheduleItem(day: "每天", start: "08:00", end: "09:00", activity: "通勤")]
        let incoming = WorldPreset(
            worldName: "AI 补的世界", era: "当代", description: "一段描述。",
            schedule: [WorldScheduleItem(day: "周末", start: "10:00", end: "12:00", activity: "补觉")],
            relationships: [WorldRelationship(name: "母亲", relation: "家人")],
            obligations: ["交图"],
            notes: "背景"
        )
        let merged = state.mergingBlanks(from: incoming)
        XCTAssertEqual(merged.worldName, "我自己写的城市", "用户写的名字不能被覆盖")
        XCTAssertEqual(merged.era, "当代", "空字段要补上")
        XCTAssertEqual(merged.description, "一段描述。")
        XCTAssertEqual(merged.schedule.map(\.activity), ["通勤"], "已有日程保持原样")
        XCTAssertEqual(merged.relationships.count, 1, "空列表要补上")
        XCTAssertEqual(merged.obligations, ["交图"])
        XCTAssertEqual(merged.notes, "背景")
        // 全空时等同于整份接受。
        XCTAssertEqual(WorldEditorState().mergingBlanks(from: incoming).preset, incoming)
    }
}
