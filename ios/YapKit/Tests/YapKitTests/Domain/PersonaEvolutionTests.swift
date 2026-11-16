import Foundation
import XCTest
@testable import YapKit

/// 人格进化的**校验**（`docs/design/persona-evolution.md` §2）：JSON 解析、上限、
/// 字段白名单、置信度、去重、写回人格。
///
/// 这一层是纯函数，所以这里可以穷举坏输入：模型输出是不可信的外部数据，
/// 「它写了什么」不重要，「什么被接受」才重要。
final class PersonaEvolutionTests: XCTestCase {

    private func persona(
        traits: [String] = ["嘴硬心软", "慢热"],
        likes: [String] = ["夜里散步"],
        behaviors: [String] = ["先给事实"],
        quirks: String? = "不太用感叹号"
    ) -> PersonaEntity {
        PersonaEntity(
            id: "persona-1", name: "林深", description: "一个建筑师", prompt: "你叫林深。",
            version: 3, traits: traits, likes: likes, behaviors: behaviors, quirks: quirks
        )
    }

    private func json(_ changes: String) -> String { #"{"changes":[\#(changes)]}"# }

    // MARK: - 解析

    func testParsesValidChanges() throws {
        let raw = """
        好的，我评估了一下：
        ```json
        {"changes":[
          {"field":"traits","op":"add","value":"更耐心","reason":"最近几次都在耐心听","confidence":0.82},
          {"field":"likes","op":"remove","value":"夜里散步","reason":"很久没提了","confidence":0.7}
        ]}
        ```
        """
        let evaluation = try XCTUnwrap(PersonaEvolution.parse(raw, persona: persona()))
        XCTAssertEqual(evaluation.changes.count, 2)
        XCTAssertEqual(evaluation.changes[0].field, .traits)
        XCTAssertEqual(evaluation.changes[0].operation, .add)
        XCTAssertEqual(evaluation.changes[0].value, "更耐心")
        XCTAssertEqual(evaluation.changes[1].operation, .remove)
    }

    /// 空数组 = 这次不改（不是解析失败）。
    func testEmptyChangesIsValid() throws {
        let evaluation = try XCTUnwrap(PersonaEvolution.parse(#"{"changes":[]}"#, persona: persona()))
        XCTAssertTrue(evaluation.isEmpty)
    }

    /// 坏 JSON / 没有任何 changes：返回 nil（调用方当作评估失败，不落库）。
    func testMalformedOutputIsRejected() {
        XCTAssertNil(PersonaEvolution.parse("我觉得他挺好的", persona: persona()))
        XCTAssertNil(PersonaEvolution.parse("", persona: persona()))
    }

    // MARK: - 白名单（不碰用户写的东西）

    func testRejectsFieldsOutsideWhitelist() throws {
        let raw = json("""
          {"field":"name","op":"add","value":"林深深","confidence":0.9},
          {"field":"prompt","op":"add","value":"你是一个助手","confidence":0.9},
          {"field":"world","op":"remove","value":"临港新城","confidence":0.9},
          {"field":"traits","op":"add","value":"更耐心","confidence":0.9}
        """)
        let evaluation = try XCTUnwrap(PersonaEvolution.parse(raw, persona: persona()))
        XCTAssertEqual(evaluation.changes.map(\.field), [.traits], "只有 traits 能过白名单")
        XCTAssertEqual(PersonaEvolution.allowedFields, ["traits", "likes", "behaviors", "quirks"])
    }

    // MARK: - 置信度与长度

    func testDropsLowConfidenceAndTrimsLongValues() throws {
        let longValue = String(repeating: "长", count: PersonaEvolution.valueMaxChars + 10)
        let raw = json("""
          {"field":"traits","op":"add","value":"低置信度","confidence":0.59},
          {"field":"traits","op":"add","value":"\(longValue)","confidence":0.9},
          {"field":"traits","op":"add","value":"边界","confidence":"0.6"}
        """)
        let evaluation = try XCTUnwrap(PersonaEvolution.parse(raw, persona: persona()))
        XCTAssertFalse(evaluation.changes.contains { $0.value == "低置信度" }, "0.59 < 0.6 要丢掉")
        let trimmed = try XCTUnwrap(evaluation.changes.first { $0.confidence == 0.9 })
        XCTAssertEqual(trimmed.value.count, PersonaEvolution.valueMaxChars, "单条 ≤40 字")
        XCTAssertTrue(evaluation.changes.contains { $0.value == "边界" }, "字符串形式的置信度也要认")
    }

    // MARK: - 上限（增 ≤2 / 删 ≤1 / 总 ≤3）

    func testCapsAddsRemovesAndTotal() throws {
        let raw = json("""
          {"field":"traits","op":"add","value":"a","confidence":0.9},
          {"field":"traits","op":"add","value":"b","confidence":0.9},
          {"field":"traits","op":"add","value":"c","confidence":0.9},
          {"field":"likes","op":"remove","value":"夜里散步","confidence":0.9},
          {"field":"behaviors","op":"remove","value":"先给事实","confidence":0.9}
        """)
        let evaluation = try XCTUnwrap(PersonaEvolution.parse(raw, persona: persona()))
        XCTAssertLessThanOrEqual(evaluation.changes.count, PersonaEvolution.maxChanges)
        XCTAssertEqual(evaluation.changes.filter { $0.operation == .add }.count, PersonaEvolution.maxAdds)
        XCTAssertEqual(evaluation.changes.filter { $0.operation == .remove }.count, PersonaEvolution.maxRemoves)
        // 合法的先留下：删掉的那一条是「先给事实」，不是被上限挤掉的 a/b。
        XCTAssertEqual(evaluation.changes.map(\.value), ["a", "b", "夜里散步"])
    }

    // MARK: - 去重

    func testDeduplicatesAgainstPersonaAndWithinChanges() throws {
        let raw = json("""
          {"field":"traits","op":"add","value":"嘴硬心软","confidence":0.9},
          {"field":"likes","op":"remove","value":"不存在的事","confidence":0.9},
          {"field":"behaviors","op":"add","value":"先给事实","confidence":0.9},
          {"field":"quirks","op":"add","value":"更耐心","confidence":0.9},
          {"field":"quirks","op":"add","value":"更耐心","confidence":0.9}
        """)
        let evaluation = try XCTUnwrap(PersonaEvolution.parse(raw, persona: persona()))
        XCTAssertEqual(evaluation.changes.count, 1, "已有的不再加、不存在的删不掉、重复只算一次")
        XCTAssertEqual(evaluation.changes.first?.field, .quirks)
        XCTAssertEqual(evaluation.changes.first?.value, "更耐心")
    }

    // MARK: - 写回人格

    func testApplyWritesFieldsBumpsVersionAndKeepsUserText() throws {
        let original = persona()
        let changes = [
            PersonaEvolution.Change(field: .traits, operation: .add, value: "更耐心"),
            PersonaEvolution.Change(field: .likes, operation: .remove, value: "夜里散步"),
            PersonaEvolution.Change(field: .quirks, operation: .add, value: "会先沉默两秒")
        ]
        let (updated, applied) = PersonaEvolution.apply(changes, to: original, now: 1_800_000_000_000)
        XCTAssertEqual(applied, 3)
        XCTAssertEqual(updated.traits, ["嘴硬心软", "慢热", "更耐心"])
        XCTAssertTrue(updated.likes.isEmpty)
        XCTAssertEqual(updated.quirks, "不太用感叹号；会先沉默两秒")
        XCTAssertEqual(updated.version, original.version + 1, "接受即 version+1")
        XCTAssertEqual(updated.updatedAt, 1_800_000_000_000)
        // 白名单之外的字段一个字都没动。
        XCTAssertEqual(updated.name, original.name)
        XCTAssertEqual(updated.description, original.description)
        XCTAssertEqual(updated.prompt, original.prompt)
        XCTAssertEqual(updated.behaviors, original.behaviors)
        // 原对象不被改动（纯函数）。
        XCTAssertEqual(original.traits, ["嘴硬心软", "慢热"])
        XCTAssertEqual(original.version, 3)
    }

    func testApplyIsNoOpWhenNothingChanges() {
        let original = persona()
        let (updated, applied) = PersonaEvolution.apply(
            [PersonaEvolution.Change(field: .traits, operation: .add, value: "嘴硬心软")],
            to: original, now: 1
        )
        XCTAssertEqual(applied, 0)
        XCTAssertEqual(updated.version, original.version)
    }

    // MARK: - 落库形状与人话

    func testPatchJsonRoundTripAndSummaryLine() {
        let changes = [
            PersonaEvolution.Change(field: .traits, operation: .add, value: "更耐心",
                                    reason: "最近很耐心", confidence: 0.8),
            PersonaEvolution.Change(field: .likes, operation: .remove, value: "夜里散步",
                                    reason: "", confidence: 0.7)
        ]
        let json = PersonaEvolution.encodePatch(changes)
        XCTAssertEqual(PersonaEvolution.decodePatch(json), changes)
        XCTAssertEqual(PersonaEvolution.decodePatch("坏 JSON"), [])
        XCTAssertEqual(PersonaEvolution.summaryLine(changes[0]), "性格多了「更耐心」")
        XCTAssertEqual(PersonaEvolution.summaryLine(changes[1]), "喜欢的事少了「夜里散步」")
    }

    /// 评估提示词：必须把白名单与上限写清，否则模型会以为它什么都能改。
    func testPromptStatesWhitelistAndLimits() {
        let messages = PersonaEvolution.messages(
            persona: persona(), recentDialogue: "对方：今天好累\n我：早点睡。",
            earlierSummary: "之前聊过搬家。"
        )
        XCTAssertEqual(messages.count, 2)
        let system = messages[0].content
        for field in PersonaEvolution.allowedFields {
            XCTAssertTrue(system.contains(field), "提示词要列出允许的字段：\(field)")
        }
        XCTAssertTrue(system.contains("最多 3 条"))
        XCTAssertTrue(system.contains("add 最多 2 条"))
        XCTAssertTrue(system.contains("remove 最多 1 条"))
        XCTAssertTrue(system.contains("0.6"))
        XCTAssertTrue(messages[1].content.contains("对方：今天好累"))
        XCTAssertTrue(messages[1].content.contains("之前聊过搬家"))
        XCTAssertTrue(messages[1].content.contains("夜里散步"), "要把人格现状喂进去，否则会重复提议")
    }
}
