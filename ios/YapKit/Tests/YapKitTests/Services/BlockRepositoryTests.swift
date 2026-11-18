import Foundation
import XCTest
@testable import YapKit

/// 0.14 拉黑仓储（`spec/experience/README.md` §2）：提议落库（跨进程还能确认）、
/// 生效写人格、到期自动解除。
final class BlockRepositoryTests: XCTestCase {

    private var temp: TempDatabase!
    private var stores: DatabaseStores { temp.stores }
    private let personaId = "persona-1"
    private let sessionId = "session-1"
    private let now: Int64 = 1_794_500_000_000

    override func setUpWithError() throws {
        try super.setUpWithError()
        temp = try TempDatabase()
        try stores.personas.upsert(TestData.persona(id: personaId, sleepEnabled: false))
        try stores.sessions.upsert(TestData.session(
            id: sessionId, personaId: personaId, lastMessageAt: now
        ))
    }

    override func tearDownWithError() throws {
        temp.cleanUp()
        temp = nil
        try super.tearDownWithError()
    }

    private func addUserMessage(id: String, at: Int64) throws {
        try stores.messages.upsert(MessageEntity(
            id: id, sessionId: sessionId, personaId: personaId,
            role: .user, content: "在吗", createdAt: at, updatedAt: at
        ))
    }

    /// 提议写在 `personas.blockProposalJson`（Android 18.json 的同一列）：
    /// 换个仓储实例（≈ 重启进程）也还能读到、还能确认。
    func testProposalIsPersistedOnPersonaRow() throws {
        let first = BlockRepository(stores: stores, now: { self.now })
        let proposal = first.propose(
            personaId: personaId, sessionId: sessionId, hours: 4, reason: "先冷静一下"
        )
        XCTAssertEqual(proposal.hours, 4)
        XCTAssertNotNil(try stores.personas.byId(personaId)?.blockProposalJson)

        let second = BlockRepository(stores: stores, now: { self.now + 60_000 })
        let pending = try XCTUnwrap(second.pendingProposal(personaId: personaId))
        XCTAssertEqual(pending.reason, "先冷静一下")

        // 对方又发了一条 → 下一轮确认生效。
        try addUserMessage(id: "u-1", at: now + 120_000)
        let confirmRepo = BlockRepository(stores: stores, now: { self.now + 180_000 })
        XCTAssertEqual(
            confirmRepo.confirm(
                personaId: personaId, sessionId: sessionId, hours: 4, reason: nil
            ),
            .accepted
        )
        let persona = try XCTUnwrap(try stores.personas.byId(personaId))
        XCTAssertEqual(persona.blockedUntil, now + 180_000 + 4 * 3_600_000)
        XCTAssertEqual(persona.blockReason, "先冷静一下", "没有传原因时沿用提议里的原因")
        XCTAssertNil(persona.blockProposalJson)
        XCTAssertEqual(confirmRepo.state(personaId: personaId)?.until, persona.blockedUntil)
    }

    /// 到期自动解除：读一次就把两列清掉（不通知、不补看）。
    func testExpiredBlockIsClearedOnRead() throws {
        var persona = try XCTUnwrap(try stores.personas.byId(personaId))
        persona.blockedUntil = now - 1
        persona.blockReason = "过期了"
        try stores.personas.upsert(persona)

        let repository = BlockRepository(stores: stores, now: { self.now })
        XCTAssertNil(repository.state(personaId: personaId), "到期即视为解除")
        XCTAssertNil(try stores.personas.byId(personaId)?.blockedUntil, "顺手把列清掉")
        XCTAssertNil(try stores.personas.byId(personaId)?.blockReason)
    }

    /// 过期的提议在读取时就被丢掉（不需要等下一次写入）。
    func testExpiredProposalIsDroppedOnRead() throws {
        let repository = BlockRepository(stores: stores, now: { self.now })
        repository.propose(personaId: personaId, sessionId: sessionId, hours: 8, reason: nil)
        let later = BlockRepository(
            stores: stores, now: { self.now + BlockPolicy.proposalTTLMillis }
        )
        XCTAssertNil(later.pendingProposal(personaId: personaId))
        XCTAssertNil(try stores.personas.byId(personaId)?.blockProposalJson)
    }
}
