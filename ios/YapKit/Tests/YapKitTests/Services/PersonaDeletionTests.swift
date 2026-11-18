import Foundation
import XCTest
@testable import YapKit

/// 删人格的级联清单（0.10.2 安全审计 P1-6）。
///
/// 0.10.1 只删了消息 / 会话 / 整理记录 / 主动节奏 / 念头 / 人格，漏了：
/// - `memory_echoes`（用户原话）、`agent_ticks`（他的思考留痕）、`memory_versions`（记忆正文的旧版本）；
/// - 删掉的若是默认人格，库里就一个默认人格都没有，`defaultPersona()` 返回 nil，
///   「正在聊的人」还指着一个已经不存在的 id。
final class PersonaDeletionTests: XCTestCase {

    private var temp: TempDatabase!
    private var memoryRepository: MemoryRepository!
    private let personaId = "persona-1"
    private let otherId = "persona-2"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        memoryRepository = MemoryRepository(stores: temp.stores)
        try temp.stores.personas.upsert([
            TestData.persona(id: personaId),
            TestData.persona(id: otherId, name: "陈屿", isDefault: false)
        ])
    }

    override func tearDownWithError() throws {
        memoryRepository = nil
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    private func seedEverything() throws {
        try temp.stores.sessions.upsert(TestData.session(id: "s-1", personaId: personaId))
        try temp.stores.sessions.upsert(TestData.session(id: "s-2", personaId: otherId))
        try temp.stores.messages.upsert([
            TestData.message(id: "m-1", sessionId: "s-1", personaId: personaId,
                             role: .user, content: "他会忘掉这句", createdAt: now),
            TestData.message(id: "m-2", sessionId: "s-2", personaId: otherId,
                             role: .user, content: "这句是别人的", createdAt: now)
        ])
        try temp.stores.memories.upsert([
            TestData.memory(id: "mem-1", personaId: personaId, content: "他会忘掉这件事"),
            TestData.memory(id: "mem-2", personaId: otherId, content: "别人的记忆")
        ])
        try temp.stores.memoryVersions.upsert([
            MemoryVersionEntity(id: "v-1", memoryId: "mem-1", at: now, content: "他会忘掉这件事",
                                beforeContent: "更早的正文", reason: "drift", moodLabel: nil),
            MemoryVersionEntity(id: "v-2", memoryId: "mem-2", at: now, content: "别人的记忆",
                                beforeContent: nil, reason: "drift", moodLabel: nil)
        ])
        MemoryEchoStore(database: temp.stores.database).insertAll([
            MemoryEcho(id: "e-1", personaId: personaId, drawer: "USER", content: "只提过一次的原话",
                       importance: 3, sourceQuote: "原话", categoryName: "FACT", confidence: 0.6,
                       createdAt: now, lastSeenAt: now),
            MemoryEcho(id: "e-2", personaId: otherId, drawer: "USER", content: "别人的回声",
                       importance: 3, sourceQuote: "", categoryName: "FACT", confidence: 0.6,
                       createdAt: now, lastSeenAt: now)
        ])
        AgentTickStore(database: temp.stores.database).insert(
            AgentTick(id: "t-1", personaId: personaId, at: now, thought: "他在想什么",
                      actionJson: "{}", outcome: "nothing")
        )
        AgentTickStore(database: temp.stores.database).insert(
            AgentTick(id: "t-2", personaId: otherId, at: now, thought: "别人在想什么",
                      actionJson: "{}", outcome: "nothing")
        )
        try temp.stores.memoryConsolidations.upsert([
            MemoryConsolidationEntity(id: "c-1", personaId: personaId, at: now, kept: 1, merged: 0,
                                      archived: 0, forgotten: 0, summary: "", modelName: "m"),
            MemoryConsolidationEntity(id: "c-2", personaId: otherId, at: now, kept: 1, merged: 0,
                                      archived: 0, forgotten: 0, summary: "", modelName: "m")
        ])
        try temp.stores.proactiveRules.upsert([
            ProactiveRuleEntity(id: "r-1", personaId: personaId, kind: .interval,
                                intervalMinutes: 60, enabled: true, source: .manual,
                                createdAt: now, updatedAt: now),
            ProactiveRuleEntity(id: "r-2", personaId: otherId, kind: .interval,
                                intervalMinutes: 60, enabled: true, source: .manual,
                                createdAt: now, updatedAt: now)
        ])
        try temp.stores.agentIntents.upsert([
            AgentIntentEntity(id: "i-1", personaId: personaId, kind: .reachOut, earliestAt: now,
                              latestAt: now, note: "", status: .pending, createdAt: now),
            AgentIntentEntity(id: "i-2", personaId: otherId, kind: .reachOut, earliestAt: now,
                              latestAt: now, note: "", status: .pending, createdAt: now)
        ])
        // 0.13：日记板与待确认的人格变化（同样没有 persona 外键）。
        try temp.stores.diaryNotes.upsert([
            DiaryNoteEntity(id: "d-1", personaId: personaId, content: "他写给自己的便条",
                            createdAt: now, updatedAt: now),
            DiaryNoteEntity(id: "d-2", personaId: otherId, content: "别人的便条",
                            createdAt: now, updatedAt: now)
        ])
        try temp.stores.personaPendingPatches.upsert([
            PersonaPendingPatchEntity(id: "p-1", personaId: personaId, patchJson: "[]",
                                      summary: "他的待确认变化", reason: "", createdAt: now),
            PersonaPendingPatchEntity(id: "p-2", personaId: otherId, patchJson: "[]",
                                      summary: "别人的待确认变化", reason: "", createdAt: now)
        ])
    }

    // MARK: - 全量级联

    func testDeletingDefaultPersonaRemovesEverythingHeLeftBehind() throws {
        try seedEverything()
        let outcome = PersonaDeletion.delete(
            personaId: personaId,
            stores: temp.stores,
            memoryRepository: memoryRepository,
            activePersonaId: personaId
        )

        XCTAssertTrue(outcome.wasDefault)
        XCTAssertTrue(outcome.personaDeleted)
        XCTAssertEqual(outcome.echoes, 1, "回声要跟着走（里面存着用户原话）")
        XCTAssertEqual(outcome.ticks, 1, "思考留痕要跟着走")
        XCTAssertEqual(outcome.memoryVersions, 1, "记忆版本里存着正文与 beforeContent")

        // 人没了，他的一切也没了。
        XCTAssertNil(try temp.stores.personas.byId(personaId))
        XCTAssertTrue(try temp.stores.messages.fetch(where: "personaId = ?", [.text(personaId)]).isEmpty)
        XCTAssertTrue(try temp.stores.sessions.byPersona(personaId).isEmpty)
        XCTAssertTrue(try temp.stores.memories.byPersona(personaId).isEmpty)
        XCTAssertTrue(try temp.stores.memoryVersions.fetch(where: "memoryId = ?", [.text("mem-1")]).isEmpty)
        XCTAssertTrue(MemoryEchoStore(database: temp.stores.database).byPersona(personaId).isEmpty)
        XCTAssertTrue(try temp.stores.memoryConsolidations.fetch(where: "personaId = ?", [.text(personaId)]).isEmpty)
        XCTAssertTrue(try temp.stores.proactiveRules.fetch(where: "personaId = ?", [.text(personaId)]).isEmpty)
        XCTAssertTrue(try temp.stores.agentIntents.fetch(where: "personaId = ?", [.text(personaId)]).isEmpty)
        XCTAssertTrue(try temp.stores.diaryNotes.fetch(where: "personaId = ?", [.text(personaId)]).isEmpty,
                      "日记板要跟着走（他不在了，便条不该留在库里）")
        XCTAssertTrue(try temp.stores.personaPendingPatches.fetch(where: "personaId = ?", [.text(personaId)]).isEmpty)

        // 默认人格转移给还在的那个人；「正在聊的人」也跟着换。
        let successor = try XCTUnwrap(try temp.stores.personas.defaultPersona())
        XCTAssertEqual(successor.id, otherId)
        XCTAssertEqual(outcome.nextDefaultPersonaId, otherId)
        XCTAssertTrue(outcome.activePersonaWasDeleted)

        // v11：他那些对话的块也要跟着清掉（message_blocks 不能留下孤儿行）。
        XCTAssertEqual(try temp.stores.messageBlocks.blockCount(sessionId: "s-1"), 0,
                       "他的会话块必须清掉")
        XCTAssertEqual(try temp.stores.messageBlocks.blockCount(sessionId: "s-2"), 1,
                       "别人的块一行都不该动")
        XCTAssertTrue(try temp.stores.messageBlocks.inconsistencies().isEmpty)

        // 别人的东西一样都没动。
        XCTAssertEqual(try temp.stores.messages.bySession("s-2").count, 1)
        XCTAssertEqual(try temp.stores.memories.byPersona(otherId).count, 1)
        XCTAssertEqual(try temp.stores.memoryVersions.count(where: "memoryId = ?", [.text("mem-2")]), 1)
        XCTAssertEqual(MemoryEchoStore(database: temp.stores.database).byPersona(otherId).count, 1)
        XCTAssertEqual(AgentTickStore(database: temp.stores.database).recent(personaId: otherId, limit: 10).count, 1)
        XCTAssertEqual(try temp.stores.diaryNotes.count(personaId: otherId), 1, "别人的便条一行都不该动")
        XCTAssertEqual(try temp.stores.personaPendingPatches.count(personaId: otherId), 1)
    }

    func testDeletingNonDefaultPersonaKeepsExistingDefault() throws {
        try seedEverything()
        let outcome = PersonaDeletion.delete(
            personaId: otherId,
            stores: temp.stores,
            memoryRepository: memoryRepository,
            activePersonaId: personaId
        )
        XCTAssertFalse(outcome.wasDefault)
        XCTAssertFalse(outcome.activePersonaWasDeleted)
        XCTAssertEqual(outcome.nextDefaultPersonaId, personaId)
        let defaultPersona = try XCTUnwrap(try temp.stores.personas.defaultPersona())
        XCTAssertEqual(defaultPersona.id, personaId)
    }

    func testDeletingLastPersonaLeavesNoDefaultAndAsksToClearActiveId() throws {
        try temp.stores.personas.delete(id: otherId)
        let outcome = PersonaDeletion.delete(
            personaId: personaId,
            stores: temp.stores,
            memoryRepository: memoryRepository,
            activePersonaId: personaId
        )
        XCTAssertTrue(outcome.wasDefault)
        XCTAssertNil(outcome.nextDefaultPersonaId, "一个都不剩时「正在聊的人」要清空")
        XCTAssertNil(try temp.stores.personas.defaultPersona())
    }

    /// 「忘掉一条记忆」也要把它的版本历史一起删掉（P2-11）：正文不能只从界面上消失。
    func testDeletingMemoryAlsoDropsItsVersions() throws {
        try temp.stores.memories.upsert([TestData.memory(id: "mem-1", content: "你会忘掉这件事")])
        try temp.stores.memoryVersions.upsert([
            MemoryVersionEntity(id: "v-1", memoryId: "mem-1", at: now, content: "你会忘掉这件事",
                                beforeContent: "更早的正文", reason: "drift", moodLabel: nil)
        ])
        let memory = try XCTUnwrap(try temp.stores.memories.byId("mem-1"))
        memoryRepository.delete(memory)
        XCTAssertEqual(try temp.stores.memoryVersions.count(where: "memoryId = ?", [.text("mem-1")]), 0)
    }

    /// 思考留痕按人格删（不能把别人的一起删）。
    func testAgentTickStoreDeletesOnePersonaOnly() throws {
        let ticks = AgentTickStore(database: temp.stores.database)
        ticks.insert(AgentTick(id: "t-a", personaId: personaId, at: now))
        ticks.insert(AgentTick(id: "t-b", personaId: otherId, at: now))
        ticks.deleteAll(personaId: personaId)
        XCTAssertTrue(ticks.recent(personaId: personaId, limit: 10).isEmpty)
        XCTAssertEqual(ticks.recent(personaId: otherId, limit: 10).map(\.id), ["t-b"])
    }

    /// 装配层必须真的走 `PersonaDeletion`，并且处理「正在聊的人」。
    func testAppEnvironmentUsesPersonaDeletionAndResetsActivePersona() throws {
        var current = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var path: URL?
        for _ in 0..<12 {
            let candidate = current.appendingPathComponent("YetAnotherPal/App/AppEnvironment.swift")
            if FileManager.default.fileExists(atPath: candidate.path) { path = candidate; break }
            current = current.deletingLastPathComponent()
        }
        let source = try String(contentsOf: try XCTUnwrap(path), encoding: .utf8)
        XCTAssertTrue(source.contains("PersonaDeletion.delete("), "装配层要调 Kit 的级联删除")
        XCTAssertTrue(source.contains("activePersonaWasDeleted"), "删的是「正在聊的人」时要重置设置")
    }
}
