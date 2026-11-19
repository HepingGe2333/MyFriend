import Foundation
import XCTest
@testable import YapKit

/// 0.14 拉黑的纯逻辑（`spec/experience/README.md` §2）。
final class BlockPolicyTests: XCTestCase {

    private let now: Int64 = 1_794_500_000_000

    func testHoursAreClampedToOneThroughTwentyFour() {
        XCTAssertEqual(BlockPolicy.clampHours(0), 1)
        XCTAssertEqual(BlockPolicy.clampHours(1), 1)
        XCTAssertEqual(BlockPolicy.clampHours(12), 12)
        XCTAssertEqual(BlockPolicy.clampHours(24), 24)
        XCTAssertEqual(BlockPolicy.clampHours(48), 24)
        XCTAssertEqual(BlockPolicy.clampHours(-5), 1)
    }

    func testHoursParsesModelShapesAndFallsBack() {
        XCTAssertEqual(BlockPolicy.hours(from: NSNumber(value: 3)), 3)
        XCTAssertEqual(BlockPolicy.hours(from: "18"), 18)
        XCTAssertEqual(BlockPolicy.hours(from: "abc"), BlockPolicy.defaultHours)
        XCTAssertEqual(BlockPolicy.hours(from: nil), BlockPolicy.defaultHours)
        // JSON 的 true/false 不是小时数。
        XCTAssertEqual(BlockPolicy.hours(from: NSNumber(value: true)), BlockPolicy.defaultHours)
        // 越界照样夹。
        XCTAssertEqual(BlockPolicy.hours(from: NSNumber(value: 99)), 24)
    }

    func testExpiryAndEffectiveState() {
        let until = BlockPolicy.expiry(hours: 2, now: now)
        XCTAssertEqual(until, now + 2 * 3_600_000)
        XCTAssertTrue(BlockPolicy.isBlocked(until: until, now: now))
        XCTAssertFalse(BlockPolicy.isBlocked(until: until, now: until))
        XCTAssertFalse(BlockPolicy.isBlocked(until: nil, now: now))
        XCTAssertFalse(BlockPolicy.isBlocked(until: 0, now: now))

        let state = BlockPolicy.effective(until: until, reason: "  累了  ", now: now)
        XCTAssertEqual(state?.until, until)
        XCTAssertEqual(state?.reason, "累了")
        XCTAssertNil(BlockPolicy.effective(until: until, reason: "累了", now: until))
    }

    // MARK: - 两轮确认

    func testConfirmationNeedsProposal() {
        XCTAssertEqual(
            BlockPolicy.confirmation(proposal: nil, userMessageId: "u-9", now: now),
            .needsProposal
        )
    }

    func testConfirmationWithinSameRoundOnlyWaits() {
        let proposal = BlockProposal(
            personaId: "p", hours: 6, reason: "太累了", userMessageId: "u-1", proposedAt: now
        )
        XCTAssertEqual(
            BlockPolicy.confirmation(proposal: proposal, userMessageId: "u-1", now: now),
            .waitingForNextRound,
            "同一轮里再调一次（用户消息 id 没变）不算确认"
        )
        XCTAssertEqual(
            BlockPolicy.confirmation(proposal: proposal, userMessageId: nil, now: now),
            .waitingForNextRound,
            "拿不到当前用户消息 id 时也只能等"
        )
    }

    func testConfirmationSucceedsAfterAUserMessage() {
        let proposal = BlockProposal(
            personaId: "p", hours: 6, reason: "太累了", userMessageId: "u-1", proposedAt: now
        )
        XCTAssertEqual(
            BlockPolicy.confirmation(
                proposal: proposal, userMessageId: "u-2", now: now + 60_000
            ),
            .accepted
        )
    }

    func testProposalExpiresAfterTenMinutes() {
        let proposal = BlockProposal(
            personaId: "p", hours: 6, reason: "", userMessageId: "u-1", proposedAt: now
        )
        XCTAssertTrue(BlockPolicy.isProposalFresh(proposal, now: now + BlockPolicy.proposalTTLMillis - 1))
        XCTAssertFalse(BlockPolicy.isProposalFresh(proposal, now: now + BlockPolicy.proposalTTLMillis))
        XCTAssertEqual(
            BlockPolicy.confirmation(
                proposal: proposal,
                userMessageId: "u-2",
                now: now + BlockPolicy.proposalTTLMillis
            ),
            .expiredProposal,
            "过期的提议不能再确认（要重新提议）"
        )
    }

    func testProposalReasonIsTrimmedAndCapped() {
        let proposal = BlockProposal(
            personaId: "p", hours: 6,
            reason: "  " + String(repeating: "烦", count: 200) + "  ",
            userMessageId: " u-1 ", proposedAt: now
        )
        XCTAssertEqual(proposal.reason.count, 120)
        XCTAssertFalse(proposal.reason.hasPrefix(" "))
        XCTAssertEqual(proposal.userMessageId, "u-1", "用户消息 id 去空白")
        XCTAssertEqual(proposal.expiresAt, now + BlockPolicy.proposalTTLMillis)
    }

    // MARK: - 与服务端 DTO 的 JSON 形状

    func testProposalJSONMatchesServerDTO() throws {
        let proposal = BlockProposal(
            proposalId: "proposal-1",
            personaId: "p",
            hours: 6,
            reason: "太累了",
            userMessageId: "u-1",
            proposedAt: now
        )
        let data = try JSONEncoder().encode(proposal)
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        XCTAssertEqual(
            Set(object.keys),
            ["proposalId", "personaId", "hours", "reason", "userMessageId", "proposedAt", "expiresAt"],
            "字段名必须与服务端 DTO 逐字一致（规范 §9）"
        )
        XCTAssertEqual(object["proposalId"] as? String, "proposal-1")
        XCTAssertEqual(object["personaId"] as? String, "p")
        XCTAssertEqual(object["hours"] as? Int, 6)
        XCTAssertEqual(object["userMessageId"] as? String, "u-1")
        XCTAssertEqual(object["proposedAt"] as? Int64, now)
        XCTAssertEqual(object["expiresAt"] as? Int64, now + BlockPolicy.proposalTTLMillis)

        // 服务端下发的 JSON 能原样解回来（含 expiresAt 权威窗口）。
        let decoded = try JSONDecoder().decode(BlockProposal.self, from: data)
        XCTAssertEqual(decoded, proposal)
    }

    // MARK: - 界面 / 提示词

    func testBlockedIndicatorOnlyForBlockedUserMessages() {
        XCTAssertTrue(BlockPolicy.showsBlockedIndicator(role: .user, blocked: true))
        XCTAssertFalse(BlockPolicy.showsBlockedIndicator(role: .user, blocked: false))
        XCTAssertFalse(BlockPolicy.showsBlockedIndicator(role: .assistant, blocked: true))
    }

    func testPromptNoteMentionsRemainingHoursAndNeverGuiltsTheUser() {
        let state = BlockState(until: now + 3 * 3_600_000, reason: "想静静")
        let note = BlockPolicy.promptNote(state, now: now)
        XCTAssertTrue(note.contains("还有 3 小时"))
        XCTAssertTrue(note.contains("想静静"))
        // 不允许写「他必须道歉」这类会推动模型惩罚对方的句子。
        XCTAssertFalse(note.contains("必须"))
        XCTAssertFalse(note.contains("道歉"))
    }
}
