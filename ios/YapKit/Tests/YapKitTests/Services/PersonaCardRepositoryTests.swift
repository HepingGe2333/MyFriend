import Foundation
import XCTest
@testable import YapKit

/// 人设卡仓储：读卡（有卡 / 回退旧提示词）、写卡（JSON + prompt 镜像 + 版本号）、
/// 模型补丁、作息与免打扰落库。
///
/// 都用真库（临时文件 SQLite），因为这里钉的是「写进去再读出来是否一致」，
/// 内存里的假仓储看不出 upsert 字段有没有漏绑。
final class PersonaCardRepositoryTests: XCTestCase {

    private var db: TempDatabase!
    private var repository: PersonaCardRepository!

    override func setUpWithError() throws {
        db = try TempDatabase()
        repository = PersonaCardRepository(stores: db.stores, now: { TestData.now })
    }

    override func tearDownWithError() throws {
        repository = nil
        db.cleanUp()
        db = nil
    }

    private func savedPersona(_ id: String = "persona-1") throws -> PersonaEntity {
        try XCTUnwrap(try db.stores.personas.byId(id))
    }

    // MARK: - 读

    func testReadCardPrefersStoredJson() throws {
        let card = PersonaCard(
            identity: PersonaIdentity(name: "阿岸", role: "开旧书店的人"),
            bond: "他记得你怕冷。"
        )
        var persona = TestData.persona()
        persona.personaCardJson = PersonaCardCodec.encode(card)
        XCTAssertEqual(repository.readCard(persona), card)
    }

    func testReadCardFallsBackToLegacyPrompt() throws {
        let persona = TestData.persona()   // prompt: 你叫林岸，住在海边，说话慢一点。
        let card = repository.readCard(persona)
        XCTAssertEqual(card.identity.name, "林岸")
        XCTAssertFalse(card.isEmpty)
        // 读的时候不落库：旧人格的 personaCardJson 仍然是空。
        XCTAssertNil(persona.personaCardJson)
    }

    func testReadCardEmptyWhenNoJsonAndNoPrompt() throws {
        var persona = TestData.persona()
        persona.prompt = ""
        persona.personaCardJson = nil
        XCTAssertTrue(repository.readCard(persona).isEmpty)
    }

    func testCardReturnsEmptyForMissingPersona() {
        XCTAssertTrue(repository.card(personaId: "不存在").isEmpty)
    }

    // MARK: - 保存整张卡

    func testSaveCardWritesJsonMirrorAndBumpsVersion() throws {
        try db.stores.personas.upsert(TestData.persona())   // version = 3，prompt 是旧提示词
        let card = PersonaCard(
            identity: PersonaIdentity(name: "林岸", role: "开旧书店的人"),
            voice: PersonaVoice(pace: "慢，偶尔停下来"),
            traits: [PersonaTrait(name: "嘴硬心软", strength: 0.8)],
            values: ["真诚"],
            life: PersonaLife(schedule: "凌晨两点睡，中午起"),
            bond: "他记得你怕冷。"
        )
        let result = repository.saveCard(personaId: "persona-1", card: card, reason: "编辑人设卡")
        XCTAssertNoThrow(try result.get())

        let saved = try savedPersona()
        XCTAssertEqual(PersonaCardCodec.decode(saved.personaCardJson), card)
        // prompt 是卡的镜像：短策略 + 按 RICH 预算渲染的正文。
        XCTAssertTrue(saved.prompt.hasPrefix("你是林岸"), saved.prompt)
        XCTAssertTrue(saved.prompt.contains("### 你是谁"), saved.prompt)
        XCTAssertTrue(saved.prompt.contains("### 说话方式"), saved.prompt)
        XCTAssertEqual(saved.version, 4)
        XCTAssertEqual(saved.updatedAt, TestData.now)
    }

    func testSaveCardDoesNotBumpVersionWhenRenderIsUnchanged() throws {
        try db.stores.personas.upsert(TestData.persona())
        let card = PersonaCard(identity: PersonaIdentity(name: "林岸"), bond: "他记得你怕冷。")

        XCTAssertNoThrow(try repository.saveCard(personaId: "persona-1", card: card, reason: "第一次").get())
        let firstVersion = try savedPersona().version

        // 同样的卡再存一次：镜像没变，版本号不应该动。
        XCTAssertNoThrow(try repository.saveCard(personaId: "persona-1", card: card, reason: "第二次").get())
        XCTAssertEqual(try savedPersona().version, firstVersion)
    }

    func testSaveCardEmptyCardProducesEmptyMirror() throws {
        try db.stores.personas.upsert(TestData.persona())
        XCTAssertNoThrow(try repository.saveCard(personaId: "persona-1", card: PersonaCard(), reason: "清空").get())
        let saved = try savedPersona()
        XCTAssertEqual(saved.prompt, "")
        XCTAssertEqual(saved.personaCardJson, "{}")
    }

    func testSaveCardForMissingPersonaFails() {
        let result = repository.saveCard(personaId: "不存在", card: PersonaCard(), reason: "")
        switch result {
        case .success:
            XCTFail("不存在的人格不应该保存成功")
        case .failure(let error):
            XCTAssertEqual(error.localizedDescription, "人格不存在")
        }
    }

    // MARK: - 模型补丁

    func testApplyPatchUpdatesCardAndReachesSystemText() throws {
        let base = PersonaCard(identity: PersonaIdentity(name: "林岸"), bond: "他记得你怕冷。")
        var persona = TestData.persona()
        persona.personaCardJson = PersonaCardCodec.encode(base)
        persona.prompt = "旧镜像"
        try db.stores.personas.upsert(persona)

        let result = repository.applyPatch(
            personaId: "persona-1",
            patchJson: #"{"voice":{"pace":"慢，偶尔停下来"},"values":["真诚"]}"#,
            reason: "他说话慢一点"
        )
        XCTAssertEqual(try result.get(), true)

        let saved = try savedPersona()
        let card = repository.readCard(saved)
        XCTAssertEqual(card.voice.pace, "慢，偶尔停下来")
        XCTAssertEqual(card.values, ["真诚"])
        XCTAssertEqual(card.identity.name, "林岸")
        XCTAssertEqual(saved.version, 4)
        XCTAssertFalse(saved.prompt.contains("旧镜像"))

        let text = repository.systemText(saved, budget: .normal)
        XCTAssertTrue(text.contains("语速：慢，偶尔停下来"), text)
        XCTAssertTrue(text.contains("- 真诚"), text)
    }

    func testApplyPatchReportsNoChangeWithoutBumpingVersion() throws {
        let base = PersonaCard(identity: PersonaIdentity(name: "林岸"), voice: PersonaVoice(pace: "慢"))
        var persona = TestData.persona()
        persona.personaCardJson = PersonaCardCodec.encode(base)
        try db.stores.personas.upsert(persona)
        let before = try savedPersona().version

        let result = repository.applyPatch(
            personaId: "persona-1",
            patchJson: #"{"voice":{"pace":"慢"}}"#,
            reason: ""
        )
        XCTAssertEqual(try result.get(), false)
        XCTAssertEqual(try savedPersona().version, before)
    }

    func testApplyBadPatchKeepsCard() throws {
        let base = PersonaCard(identity: PersonaIdentity(name: "林岸"), bond: "他记得你怕冷。")
        var persona = TestData.persona()
        persona.personaCardJson = PersonaCardCodec.encode(base)
        try db.stores.personas.upsert(persona)

        let result = repository.applyPatch(personaId: "persona-1", patchJson: "坏 JSON", reason: "")
        XCTAssertEqual(try result.get(), false)
        XCTAssertEqual(repository.readCard(try savedPersona()), base)
    }

    func testApplyPatchForMissingPersonaFails() {
        let result = repository.applyPatch(personaId: "不存在", patchJson: "{}", reason: "")
        switch result {
        case .success:
            XCTFail("不存在的人格不应该打补丁成功")
        case .failure(let error):
            XCTAssertEqual(error.localizedDescription, "人格不存在")
        }
    }

    // MARK: - systemText

    func testSystemTextFallsBackToPromptWhenCardIsEmpty() throws {
        var persona = TestData.persona()
        persona.personaCardJson = "{}"
        persona.prompt = "手写的提示词：不要假装记得没说过的事。"
        try db.stores.personas.upsert(persona)

        XCTAssertEqual(repository.systemText(persona), "手写的提示词：不要假装记得没说过的事。")
    }

    func testSystemTextAppendsProfileSectionsAndDoesNotRepeatPrompt() throws {
        var persona = TestData.persona()
        persona.personaCardJson = PersonaCardCodec.encode(PersonaCard(identity: PersonaIdentity(name: "林岸")))
        persona.prompt = "镜像文本-唯一标记"
        persona.traits = ["慢热"]
        persona.likes = ["夜里散步"]
        persona.backstory = "在海边长大"
        persona.quirks = "少用感叹号"
        try db.stores.personas.upsert(persona)

        let text = repository.systemText(persona, budget: .rich)
        // 卡的镜像 + 1.9 五个结构化小节都在。
        XCTAssertTrue(text.hasPrefix("你是林岸"), text)
        XCTAssertTrue(text.contains("### 性格"), text)
        XCTAssertTrue(text.contains("- 慢热"), text)
        XCTAssertTrue(text.contains("### 喜欢"), text)
        XCTAssertTrue(text.contains("### 过去"), text)
        XCTAssertTrue(text.contains("在海边长大"), text)
        // persona.prompt 已经包含在 base 里，追加小节前要剥掉重复的开头。
        XCTAssertFalse(text.contains("镜像文本-唯一标记"), text)
    }

    // MARK: - 作息 / 免打扰

    func testSetQuietHoursWritesScheduleIntoCardAndColumns() throws {
        try db.stores.personas.upsert(TestData.persona())
        let before = try savedPersona()

        let result = repository.setQuietHours(
            personaId: "persona-1",
            sleepMinutes: 23 * 60 + 30,
            wakeMinutes: 8 * 60,
            reason: "夜里想安静"
        )
        XCTAssertNoThrow(try result.get())

        let saved = try savedPersona()
        XCTAssertEqual(saved.quietSleepMinutes, 1410)
        XCTAssertEqual(saved.quietWakeMinutes, 480)
        XCTAssertEqual(saved.updatedAt, TestData.now)
        XCTAssertEqual(repository.readCard(saved).life.schedule, "每天 23:30 睡觉，08:00 醒来（夜里想安静）")
        // 与 Android 一致：只动作息，不重写 prompt、不产生新版本。
        XCTAssertEqual(saved.prompt, before.prompt)
        XCTAssertEqual(saved.version, before.version)
    }

    func testSetQuietHoursWithoutReason() throws {
        try db.stores.personas.upsert(TestData.persona())
        XCTAssertNoThrow(
            try repository.setQuietHours(personaId: "persona-1", sleepMinutes: 0, wakeMinutes: 1440, reason: " ")
                .get()
        )
        let saved = try savedPersona()
        XCTAssertEqual(saved.quietSleepMinutes, 0)
        XCTAssertEqual(saved.quietWakeMinutes, 1440)
        XCTAssertEqual(repository.readCard(saved).life.schedule, "每天 00:00 睡觉，00:00 醒来")
    }

    func testSetDndOnSleepWritesFlagOnly() throws {
        try db.stores.personas.upsert(TestData.persona())
        let before = try savedPersona()

        XCTAssertNoThrow(try repository.setDndOnSleep(personaId: "persona-1", enabled: true).get())

        let saved = try savedPersona()
        XCTAssertTrue(saved.dndOnSleep)
        XCTAssertNil(saved.quietSleepMinutes)
        XCTAssertEqual(saved.updatedAt, TestData.now)
        // 卡与版本都不该被这条「睡前免打扰」改动。
        XCTAssertEqual(saved.personaCardJson, before.personaCardJson)
        XCTAssertEqual(saved.version, before.version)
    }

    func testSetQuietHoursForMissingPersonaFails() {
        switch repository.setQuietHours(personaId: "不存在", sleepMinutes: 60, wakeMinutes: 120, reason: "") {
        case .success: XCTFail("不存在的人格不应该保存作息")
        case .failure(let error): XCTAssertEqual(error.localizedDescription, "人格不存在")
        }
        switch repository.setDndOnSleep(personaId: "不存在", enabled: true) {
        case .success: XCTFail("不存在的人格不应该保存免打扰")
        case .failure(let error): XCTAssertEqual(error.localizedDescription, "人格不存在")
        }
    }

    // MARK: - 编辑页的分字段保存

    func testSaveCardFieldsWritesCardAndMirror() throws {
        try db.stores.personas.upsert(TestData.persona())

        let result = repository.saveCardFields(
            personaId: "persona-1",
            identity: PersonaIdentity(name: "林岸", role: "开旧书店的人"),
            voice: PersonaVoice(pace: "慢，偶尔停下来"),
            traits: [PersonaTrait(name: "嘴硬心软", strength: 0.8)],
            values: ["真诚"],
            boundaries: ["不假装记得没说过的事"],
            life: PersonaLife(doing: "整理旧书"),
            bond: "他记得你怕冷。"
        )
        XCTAssertEqual(try result.get(), true)

        let saved = try savedPersona()
        let card = repository.readCard(saved)
        XCTAssertEqual(card.identity.role, "开旧书店的人")
        XCTAssertEqual(card.traits, [PersonaTrait(name: "嘴硬心软", strength: 0.8)])
        XCTAssertTrue(saved.prompt.hasPrefix("你是林岸"), saved.prompt)
        XCTAssertEqual(saved.version, 4)

        // 原样再点一次保存：卡没变，什么都不写。
        let again = repository.saveCardFields(
            personaId: "persona-1",
            identity: PersonaIdentity(name: "林岸", role: "开旧书店的人"),
            voice: PersonaVoice(pace: "慢，偶尔停下来"),
            traits: [PersonaTrait(name: "嘴硬心软", strength: 0.8)],
            values: ["真诚"],
            boundaries: ["不假装记得没说过的事"],
            life: PersonaLife(doing: "整理旧书"),
            bond: "他记得你怕冷。"
        )
        XCTAssertEqual(try again.get(), false)
        XCTAssertEqual(try savedPersona().version, 4)
    }

    func testSaveCardFieldsEmptyCardKeepsHandWrittenPrompt() throws {
        // 之前有卡，用户把所有结构化字段清空了：应把空卡写回去，但保留手写提示词。
        var persona = TestData.persona()
        persona.personaCardJson = PersonaCardCodec.encode(PersonaCard(identity: PersonaIdentity(name: "林岸")))
        persona.prompt = "手写的一大段提示词"
        try db.stores.personas.upsert(persona)

        let result = repository.saveCardFields(
            personaId: "persona-1",
            identity: PersonaIdentity(),
            voice: PersonaVoice(),
            traits: [],
            values: [],
            boundaries: [],
            life: PersonaLife(),
            bond: ""
        )
        XCTAssertEqual(try result.get(), true)

        let saved = try savedPersona()
        XCTAssertEqual(saved.prompt, "手写的一大段提示词")
        XCTAssertTrue(PersonaCardCodec.decode(saved.personaCardJson).isEmpty)
    }

    func testSaveCardFieldsSanitizesLimits() throws {
        try db.stores.personas.upsert(TestData.persona())
        XCTAssertNoThrow(
            try repository.saveCardFields(
                personaId: "persona-1",
                identity: PersonaIdentity(name: String(repeating: "长", count: 80)),
                voice: PersonaVoice(),
                traits: (0..<20).map { PersonaTrait(name: "标签\($0)", strength: 2) },
                values: (0..<20).map { "值\($0)" },
                boundaries: [],
                life: PersonaLife(),
                bond: ""
            ).get()
        )
        let card = repository.readCard(try savedPersona())
        XCTAssertEqual(card.identity.name.count, PersonaCardLimits.nameMax)
        XCTAssertEqual(card.traits.count, PersonaCardLimits.maxTraits)
        XCTAssertEqual(card.values.count, PersonaCardLimits.maxListItems)
        XCTAssertEqual(card.traits.first?.strength, 1)
    }
}
