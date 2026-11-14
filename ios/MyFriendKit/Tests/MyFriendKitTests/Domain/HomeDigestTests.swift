import Foundation
import XCTest
@testable import MyFriendKit

/// 0.11.0 审计 P2-4：首页的未读与预览要跟着后台写入刷新。
///
/// 首页只读 `chat_sessions` 的「未读 / 最后一句预览 / 最后一条时间」三列；
/// 这一层负责判断「值不值得重画」（0.11.1 审计 P2-12：原来还有 `merge` / `onlyChanged`
/// 两个「增量合并」函数，但首页从未调用它们 —— 死代码已删，这里只保留生产路径）。
final class HomeDigestTests: XCTestCase {

    private let base: Int64 = 1_794_500_000_000

    private func session(
        _ id: String,
        persona: String,
        unread: Int = 0,
        preview: String? = nil,
        at: Int64? = nil
    ) -> ChatSessionEntity {
        ChatSessionEntity(
            id: id,
            personaId: persona,
            apiConfigId: "cfg-1",
            title: "对话 \(id)",
            lastMessagePreview: preview,
            lastMessageAt: at,
            unreadCount: unread,
            createdAt: base,
            updatedAt: at ?? base
        )
    }

    // MARK: - 聚合

    func testDigestsCarryOnlyTheThreeHomeFields() {
        let digests = HomeDigest.digests(from: [
            session("s-1", persona: "p-1", unread: 2, preview: "刚到家", at: base),
            session("s-2", persona: "p-2")
        ])
        XCTAssertEqual(digests.count, 2)
        XCTAssertEqual(digests[0].sessionId, "s-1")
        XCTAssertEqual(digests[0].personaId, "p-1")
        XCTAssertEqual(digests[0].unreadCount, 2)
        XCTAssertEqual(digests[0].lastMessagePreview, "刚到家")
        XCTAssertEqual(digests[0].lastMessageAt, base)
        XCTAssertEqual(digests[1].unreadCount, 0)
        XCTAssertNil(digests[1].lastMessageAt)
    }

    // MARK: - 该不该重画

    /// 三列都没变 → 不重画（后台任务写 `updatedAt` 的次数很多，不能一写就抖一遍列表）。
    /// 首页的增量刷新确实走 `changed`（`HomeView.refreshDigestsIfChanged`），不是「只测不用的死代码」。
    func testUnchangedDigestsDoNotTriggerRedraw() {
        let old = HomeDigest.digests(from: [
            session("s-1", persona: "p-1", unread: 1, preview: "晚安", at: base)
        ])
        let same = HomeDigest.digests(from: [
            session("s-1", persona: "p-1", unread: 1, preview: "晚安", at: base)
        ])
        XCTAssertFalse(HomeDigest.changed(old: old, new: same))
    }

    func testMissingOrExtraSessionCountsAsChange() {
        let old = HomeDigest.digests(from: [
            session("s-1", persona: "p-1", unread: 0, preview: "a", at: base),
            session("s-2", persona: "p-2", unread: 0, preview: "b", at: base)
        ])
        XCTAssertTrue(HomeDigest.changed(old: old, new: Array(old.prefix(1))), "删了一段会话")
        let added = old + HomeDigest.digests(from: [session("s-3", persona: "p-3")])
        XCTAssertTrue(HomeDigest.changed(old: old, new: added), "新建了一段会话")
    }

    /// 未读 / 预览 / 时间，任意一个变了都要重画。
    func testEachFieldAloneTriggersRedraw() {
        let old = HomeDigest.digests(from: [
            session("s-1", persona: "p-1", unread: 0, preview: "晚安", at: base)
        ])
        let unreadChanged = HomeDigest.digests(from: [
            session("s-1", persona: "p-1", unread: 3, preview: "晚安", at: base)
        ])
        let previewChanged = HomeDigest.digests(from: [
            session("s-1", persona: "p-1", unread: 0, preview: "睡了吗", at: base)
        ])
        let timeChanged = HomeDigest.digests(from: [
            session("s-1", persona: "p-1", unread: 0, preview: "晚安", at: base + 5000)
        ])
        for fresh in [unreadChanged, previewChanged, timeChanged] {
            XCTAssertTrue(HomeDigest.changed(old: old, new: fresh))
        }
    }
}
