import Foundation
import XCTest
@testable import MyFriendKit

/// 人设引导提示词的**逐字契约**：`spec/persona-guidance/scaffold.json` 是唯一出处。
///
/// 这条测试做两件事：
/// 1. `PersonaPromptScaffold` 里的每个 section / line 必须与 spec 文件**逐字相同**
///    （拆行、标点、「」、括号全算；差一个字就红）；
/// 2. 把一份"什么都有"的人格塞进 `PromptBuilder.systemPrompt`，断言规模型的提示词里
///    **真的出现了**这些行（防止"常量改了但某个 renderer 没接上"这种半成品）。
///
/// 为什么不去跑 Android：两端都要读同一份 spec。这条测试绿了，说明 iOS 这一端是规范的；
/// Android 那边有它自己的同名测试，任何一端漂了都会在自己那边红。
final class PersonaPromptScaffoldContractTests: XCTestCase {

    // MARK: - 找 spec

    private static func scaffoldURL(file: String = #filePath) -> URL? {
        var url = URL(fileURLWithPath: file).deletingLastPathComponent()
        let fm = FileManager.default
        for _ in 0..<16 {
            let candidate = url.appendingPathComponent("spec/persona-guidance/scaffold.json")
            if fm.fileExists(atPath: candidate.path) { return candidate }
            let parent = url.deletingLastPathComponent()
            if parent.path == url.path { break }
            url = parent
        }
        return nil
    }

    private func scaffold() throws -> [String: Any] {
        guard let url = Self.scaffoldURL() else {
            throw XCTSkip("找不到 spec/persona-guidance/scaffold.json")
        }
        let data = try Data(contentsOf: url)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw XCTSkip("scaffold.json 读不出来")
        }
        return object
    }

    private func specSections() throws -> [String: String] {
        try XCTUnwrap(try scaffold()["sections"] as? [String: String])
    }

    private func specLines() throws -> [String: Any] {
        try XCTUnwrap(try scaffold()["lines"] as? [String: Any])
    }

    // MARK: - 1. 常量必须与 spec 逐字一致

    func testSectionsMatchSpecVerbatim() throws {
        let sections = try specSections()
        XCTAssertEqual(sections["identity"], PersonaPromptScaffold.identitySection)
        XCTAssertEqual(sections["world"], PersonaPromptScaffold.worldSection)
        XCTAssertEqual(sections["state"], PersonaPromptScaffold.stateSection)
        XCTAssertEqual(sections["voice"], PersonaPromptScaffold.voiceSection)
        XCTAssertEqual(sections["relationship"], PersonaPromptScaffold.relationshipSection)
        XCTAssertEqual(sections["boundaries"], PersonaPromptScaffold.boundariesSection)
        // 段落顺序（规范 §1：越靠前越强，顺序不可乱）。
        XCTAssertEqual(PersonaPromptScaffold.sectionOrder, [
            sections["identity"], sections["world"], sections["state"],
            sections["voice"], sections["relationship"], sections["boundaries"]
        ])
    }

    func testScalarLinesMatchSpecVerbatim() throws {
        let lines = try specLines()
        func expect(_ key: String, _ actual: String) throws {
            let expected = try XCTUnwrap(lines[key] as? String, "spec 缺 line：\(key)")
            XCTAssertEqual(actual, expected, "脚手架漂了：\(key)")
        }
        try expect("identity_body", PersonaPromptScaffold.identityBody)
        try expect("identity_body_with_meta", PersonaPromptScaffold.identityBodyWithMeta)
        try expect("world_empty", PersonaPromptScaffold.worldEmpty)
        try expect("world_name", PersonaPromptScaffold.worldName)
        try expect("world_intro", PersonaPromptScaffold.worldIntro)
        try expect("world_notes", PersonaPromptScaffold.worldNotes)
        try expect("world_hint", PersonaPromptScaffold.worldHint)
        try expect("state_line", PersonaPromptScaffold.stateLine)
        try expect("state_idle", PersonaPromptScaffold.stateIdle)
        try expect("state_busy", PersonaPromptScaffold.stateBusy)
        try expect("state_limited", PersonaPromptScaffold.stateLimited)
        try expect("state_mood", PersonaPromptScaffold.stateMood)
        try expect("voice_tone", PersonaPromptScaffold.voiceTone)
        try expect("voice_phrases", PersonaPromptScaffold.voicePhrases)
        try expect("voice_no_narration", PersonaPromptScaffold.voiceNoNarration)
        try expect("relationship_default", PersonaPromptScaffold.relationshipDefault)
    }

    func testArrayLinesMatchSpecVerbatim() throws {
        let lines = try specLines()
        XCTAssertEqual(
            try XCTUnwrap(lines["voice_rules"] as? [String]),
            PersonaPromptScaffold.voiceRules
        )
        XCTAssertEqual(
            try XCTUnwrap(lines["boundary_rules"] as? [String]),
            PersonaPromptScaffold.boundaryRules
        )
    }

    // MARK: - 2. 渲染出来的提示词里真的包含它们

    /// 固定时区与时刻（周三 19:40，Asia/Shanghai）。
    private func fixedCalendar() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return calendar
    }

    private func fixtureMillis() -> Int64 {
        var calendar = fixedCalendar()
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        let date = calendar.date(from: DateComponents(
            year: 2026, month: 11, day: 18, hour: 19, minute: 40
        ))!
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    private func fixturePersona() -> PersonaEntity {
        PersonaEntity(
            id: "persona-scaffold",
            name: "林深",
            description: "一个嘴硬心软的建筑师。",
            prompt: "你叫林深，在事务所带一个小团队。",
            traits: ["嘴硬心软"],
            likes: ["夜里散步"],
            behaviors: ["先给事实"],
            backstory: "父亲早年去世。",
            quirks: "不太用感叹号。",
            worldJson: WorldPreset(
                worldName: "临港新城·云筑建筑事务所",
                era: "当代，中国沿海新一线城市，2026 年",
                description: "事务所竞争激烈，甲方频繁改图。",
                schedule: [WorldScheduleItem(day: "工作日", start: "09:00", end: "18:30",
                                             activity: "在事务所画图")],
                relationships: [WorldRelationship(name: "母亲", relation: "家人",
                                                  note: "每周三晚打电话", intimacy: 4)],
                obligations: ["按节点交图"],
                notes: "事务所里没人叫他全名，都叫「深哥」。"
            ).jsonString(),
            speechStyleJson: SpeechStyle(
                tone: "简短直接，偏冷淡务实",
                phrases: ["在忙，晚点说", "你先睡吧"],
                habits: ["句子很短"],
                avoid: ["长篇大论"]
            ).jsonString()
        )
    }

    private func fullPrompt() -> String {
        let persona = fixturePersona()
        let now = fixtureMillis()
        let activity = PersonaActivity(
            kind: .work, activity: "在事务所赶第三版方案", minutes: 90,
            availability: .busy, startedAt: now
        )
        return PromptBuilder.systemPrompt(SystemPromptInput(
            personaText: PromptBuilder.personaSection(persona),
            personaName: persona.name,
            worldBlock: PromptBuilder.worldSection(WorldPreset.decode(persona.worldJson)),
            stateBlock: PromptBuilder.stateSection(
                now: now, activity: activity, calendar: fixedCalendar()
            ),
            speechStyleBlock: PromptBuilder.speechStyleSection(
                SpeechStyle.decode(persona.speechStyleJson)
            ),
            relationshipBlock: PromptBuilder.relationshipSection(persona: persona),
            boundaryBlock: PromptBuilder.defaultBoundary,
            moodBlock: MoodPrompt.line(MoodState(label: "有点累", intensity: 3)),
            proactiveCapability: "P",
            toolsCapability: "T"
        ))
    }

    /// 模板里的 `{…}` 是变量；断言**所有静态片段**按顺序出现在提示词里（变量本身由别的测试管）。
    private func assertTemplateRendered(_ template: String, in text: String,
                                        file: StaticString = #filePath, line: UInt = #line) {
        var cursor = text.startIndex
        var rest = template[template.startIndex...]
        while let open = rest.firstIndex(of: "{") {
            let chunk = String(rest[rest.startIndex..<open])
            if !chunk.isEmpty {
                guard let found = text.range(of: chunk, range: cursor..<text.endIndex) else {
                    XCTFail("提示词里找不到脚手架片段：\(chunk)（来自 \(template)）", file: file, line: line)
                    return
                }
                cursor = found.upperBound
            }
            guard let close = rest[open...].firstIndex(of: "}") else { break }
            rest = rest[rest.index(after: close)...]
        }
        let tail = String(rest)
        if !tail.isEmpty, text.range(of: tail, range: cursor..<text.endIndex) == nil {
            XCTFail("提示词里找不到脚手架片段：\(tail)（来自 \(template)）", file: file, line: line)
        }
    }

    func testEverySectionAppearsInRenderedPrompt() throws {
        let text = fullPrompt()
        let sections = try specSections()
        for (key, title) in sections {
            XCTAssertTrue(text.contains(title), "提示词里没有段落 \(key)：\(title)")
        }
        // 「# 边界」在最后：边界是规则，压在尾巴上（§1 的顺序）。
        let boundary = try XCTUnwrap(sections["boundaries"])
        let boundaryRange = try XCTUnwrap(text.range(of: boundary))
        let identity = try XCTUnwrap(sections["identity"])
        let identityRange = try XCTUnwrap(text.range(of: identity))
        XCTAssertLessThan(identityRange.lowerBound, boundaryRange.lowerBound)
    }

    func testEveryLineAppearsInRenderedPrompt() throws {
        let lines = try specLines()
        let text = fullPrompt()
        let scalarKeys = [
            "world_empty", "world_name", "world_intro", "world_notes", "world_hint",
            "state_line", "state_idle", "state_busy", "state_limited", "state_mood",
            "voice_tone", "voice_phrases", "voice_no_narration"
        ]
        // world_empty / state_idle / state_limited 是「没有…」的分支：用各自的空输入单独断言。
        let branchChecks: [String: () -> String] = [
            "world_empty": { PersonaPromptScaffold.worldEmpty },
            "state_idle": { PersonaPromptScaffold.stateIdle },
            "state_limited": {
                PersonaActivity(
                    kind: .work, activity: "在开会", minutes: 30,
                    availability: .limited, startedAt: self.fixtureMillis()
                ).renderStateLine(now: self.fixtureMillis(), calendar: self.fixedCalendar())
            }
        ]
        for key in scalarKeys {
            let template = try XCTUnwrap(lines[key] as? String, "spec 缺 line：\(key)")
            if key == "world_empty" {
                let emptyWorld = PromptBuilder.worldSection(nil)
                XCTAssertTrue(emptyWorld.contains(template))
                continue
            }
            if key == "world_name" {
                // worldName 非空 → 世界段第一行是 world_name 模板；为空 → 不出现（整行省略）。
                let withName = WorldPreset(worldName: "临港新城", era: "当代")
                assertTemplateRendered(template, in: withName.renderForPrompt())
                XCTAssertTrue(withName.renderForPrompt()
                    .hasPrefix(PersonaPromptScaffold.worldSection + "\n你所在的地方：临港新城。"))
                let nameless = WorldPreset(era: "当代", description: "沿海城市")
                XCTAssertFalse(nameless.renderForPrompt().contains("你所在的地方："))
                continue
            }
            if key == "state_idle" {
                let idle = PromptBuilder.stateSection(
                    now: fixtureMillis(), activity: nil, calendar: fixedCalendar()
                )
                XCTAssertTrue(idle.contains(template), "空状态必须写「没在忙什么」")
                continue
            }
            if key == "state_limited" {
                XCTAssertTrue((branchChecks[key]?() ?? "").contains("可以回，但会慢一点"))
                assertTemplateRendered(template, in: branchChecks[key]?() ?? "")
                continue
            }
            assertTemplateRendered(template, in: text)
        }
        // 身份两句：有 meta / 没 meta 各一条。
        let identity = try XCTUnwrap(lines["identity_body"] as? String)
        assertTemplateRendered(identity, in: PromptBuilder.firstPersonRule(name: "林深"))
        let identityMeta = try XCTUnwrap(lines["identity_body_with_meta"] as? String)
        let withMeta = PromptBuilder.firstPersonRule(name: "林深", meta: "28 岁，建筑师")
        // meta 版本自带一个占位符 —— 只比对静态片段。
        assertTemplateRendered(identityMeta, in: withMeta)
        XCTAssertTrue(withMeta.contains("28 岁，建筑师"))
        // 数组行：每条都要在对应段落里。
        let voiceRules = try XCTUnwrap(lines["voice_rules"] as? [String])
        let voiceBlock = PromptBuilder.speechStyleSection(
            SpeechStyle.decode(fixturePersona().speechStyleJson)
        )
        for rule in voiceRules {
            XCTAssertTrue(voiceBlock.contains(rule), "话风段缺规则：\(rule)")
        }
        let boundaryRules = try XCTUnwrap(lines["boundary_rules"] as? [String])
        for rule in boundaryRules {
            XCTAssertTrue(PromptBuilder.defaultBoundary.contains(rule), "边界缺规则：\(rule)")
        }
        // 关系兜底：什么结构化字段都没有时用 relationship_default。
        let bare = PersonaEntity(id: "bare", name: "他")
        let relationship = PromptBuilder.relationshipSection(persona: bare)
        XCTAssertTrue(relationship.contains(PersonaPromptScaffold.relationshipDefault))
    }

    /// 占位符必须真的被填掉：提示词里出现 `{name}` / `{activity}` / `{notes}` 这种未替换的模板
    /// 就是拼接 bug（模型会把它当字面量学走）。
    func testNoUnreplacedPlaceholdersInRenderedPrompt() throws {
        let text = fullPrompt()
        let lines = try specLines()
        var placeholders = Set<String>()
        let regex = try NSRegularExpression(pattern: #"\{([a-zA-Z]+)\}"#)
        for value in lines.values {
            let templates: [String]
            if let single = value as? String { templates = [single] }
            else if let array = value as? [String] { templates = array }
            else { templates = [] }
            for template in templates {
                let range = NSRange(template.startIndex..<template.endIndex, in: template)
                for match in regex.matches(in: template, range: range) {
                    if let r = Range(match.range(at: 1), in: template) {
                        placeholders.insert(String(template[r]))
                    }
                }
            }
        }
        XCTAssertFalse(placeholders.isEmpty)
        for placeholder in placeholders {
            XCTAssertFalse(
                text.contains("{" + placeholder + "}"),
                "提示词里留了没替换的占位符：{\(placeholder)}"
            )
        }
    }
}
