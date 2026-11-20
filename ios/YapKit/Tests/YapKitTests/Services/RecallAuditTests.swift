import Foundation
import XCTest
@testable import YapKit

/// 0.14「审计现有记忆检索并修复明显 bug」（`spec/experience/README.md` §6）。
///
/// 这里钉住四条审计结论：
/// 1. 空查询不炸、也不把「谁都不是」的查询当成 LIKE '%%' 去扫全表；
/// 2. 同一条内容从不同召回路径进来时只出现一次（重复会让提示词白占预算）；
/// 3. 人格之间不越权（索引命中也要再核一次 personaId）；
/// 4. 超长记忆在**提示词**里截断（库里的原文不截），不让一条记忆吃掉整段预算。
final class RecallAuditTests: XCTestCase {

    private var temp: TempDatabase!
    private var repository: MemoryRepository!
    private let personaId = "persona-1"
    private let otherPersonaId = "persona-2"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        repository = MemoryRepository(
            stores: temp.stores,
            vectorIndex: MemoryVectorIndex(stores: temp.stores, now: { self.now }),
            now: { self.now }
        )
        try temp.stores.personas.upsert(TestData.persona(id: personaId, sleepEnabled: false))
        try temp.stores.personas.upsert(TestData.persona(id: otherPersonaId, name: "别人", sleepEnabled: false))
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    private func add(_ content: String, personaId: String? = nil, pinned: Bool = false) throws -> String {
        let result = repository.addDetailed(
            personaId: personaId ?? self.personaId,
            content: content,
            category: .fact,
            importance: 4,
            pinned: pinned,
            drawer: "USER"
        )
        return try result.get()
    }

    func testEmptyQueryFallsBackToBaselineWithoutScanningWildcards() throws {
        _ = try add("对方怕冷", pinned: true)
        _ = try add("对方养了一只猫")
        let all = repository.getRelevant(personaId: personaId, query: "")
        let blank = repository.getRelevant(personaId: personaId, query: "   ")
        XCTAssertEqual(all.count, 2)
        XCTAssertEqual(blank.count, 2)
        XCTAssertEqual(all.first?.content, "对方怕冷", "pinned 排在前面")
    }

    func testDuplicateContentIsReturnedOnce() throws {
        _ = try add("对方怕冷，冬天手脚冰凉")
        _ = try add("对方怕冷，冬天手脚冰凉")
        let results = repository.getRelevant(personaId: personaId, query: "怕冷")
        XCTAssertEqual(results.count, 1, "同一条内容只该出现一次：\(results.map(\.content))")
        XCTAssertEqual(try temp.stores.memories.count(), 2, "库里的两条都还在（去重只发生在召回结果）")
    }

    func testRecallNeverCrossesPersonas() throws {
        _ = try add("对方怕冷", personaId: otherPersonaId)
        _ = try add("对方怕冷", personaId: personaId)
        let results = repository.getRelevant(personaId: personaId, query: "怕冷")
        XCTAssertEqual(results.count, 1)
        XCTAssertTrue(results.allSatisfy { $0.personaId == personaId })
    }

    func testLongMemoryIsTruncatedInPromptButNotInStorage() throws {
        let long = String(repeating: "很长的记忆。", count: 400)   // 2400 字
        _ = try add(String(long.prefix(MemoryRepository.maxContentChars)))
        let memories = repository.getRelevant(personaId: personaId, query: "")
        let block = repository.formatForPrompt(memories)
        let lines = block.components(separatedBy: "\n").filter { $0.hasPrefix("- ") }
        let line = try XCTUnwrap(lines.first)
        XCTAssertLessThanOrEqual(
            line.count, MemoryRepository.maxPromptLineChars + 10,
            "提示词里每条记忆最多 300 字（+「…」与「（对你很重要）」这类后缀）"
        )
        XCTAssertTrue(line.hasSuffix("…"), "截断要有省略号，说明还有下文")
        let stored = try XCTUnwrap(try temp.stores.memories.all().first)
        XCTAssertGreaterThan(stored.content.count, MemoryRepository.maxPromptLineChars,
                             "库里的原文不截断")
    }

    func testRecallRefreshTouchesUseCountButNotTooOften() throws {
        let id = try add("对方怕冷")
        _ = repository.getRelevant(personaId: personaId, query: "怕冷")
        let first = try XCTUnwrap(try temp.stores.memories.byId(id))
        XCTAssertEqual(first.useCount, 2, "写入时 1 次 + 被想起来 1 次")
        _ = repository.getRelevant(personaId: personaId, query: "怕冷")
        let second = try XCTUnwrap(try temp.stores.memories.byId(id))
        XCTAssertEqual(second.useCount, 2, "6 小时内不重复写「被想起来」")
    }
}
