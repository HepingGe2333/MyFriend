import Foundation
import XCTest
@testable import MyFriendKit

/// 0.11.0 审计 P2-4：首页的未读与预览要跟着后台写入刷新。
///
/// 首页只读 `chat_sessions` 的「未读 / 最后一句预览 / 最后一条时间」三列；
/// 这一层负责两件事：**合并新旧列表**、以及判断「值不值得重画」。
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

    // MARK: - 合并新旧列表

    /// 后台写了一句话：旧列表 + 新快照 → 未读、预览、时间都跟上，顺序按最近说话。
    func testMergeAppliesNewerSnapshotAndResorts() {
        let old = HomeDigest.digests(from: [
            session("s-1", persona: "p-1", unread: 0, preview: "晚安", at: base - 3000),
            session("s-2", persona: "p-2", unread: 1, preview: "在吗", at: base - 2000),
            session("s-3", persona: "p-3", unread: 0, preview: "好的", at: base - 1000)
        ])
        // 后台心跳往 s-1 写了一条，s-2 的未读被用户读掉了；s-4 是新建的会话。
        let fresh = HomeDigest.digests(from: [
            session("s-1", persona: "p-1", unread: 1, preview: "我刚到家", at: base),
            session("s-2", persona: "p-2", unread: 0, preview: "在吗", at: base - 2000),
            session("s-3", persona: "p-3", unread: 0, preview: "好的", at: base - 1000),
            session("s-4", persona: "p-4", unread: 0, preview: nil, at: nil)
        ])

        let merged = HomeDigest.merge(old: old, new: fresh)
        XCTAssertEqual(merged.map(\.sessionId), ["s-1", "s-3", "s-2", "s-4"])
        XCTAssertEqual(merged[0].unreadCount, 1, "未读角标跟着后台写入变")
        XCTAssertEqual(merged[0].lastMessagePreview, "我刚到家", "预览跟着变")
        XCTAssertEqual(merged[0].lastMessageAt, base)
        XCTAssertEqual(merged[2].unreadCount, 0, "读掉之后角标要消失")
        XCTAssertTrue(HomeDigest.changed(old: old, new: fresh))
    }

    /// 只有「新的一条没说完的消息」：旧的 3 行原样保留，不丢人、不重排错人。
    func testMergeKeepsOldRowsWhenSnapshotOnlyHasTheChangedOne() {
        let old = HomeDigest.digests(from: [
            session("s-1", persona: "p-1", unread: 0, preview: "晚安", at: base - 2000),
            session("s-2", persona: "p-2", unread: 0, preview: "好", at: base - 1000)
        ])
        // 增量刷新（只有变化的那一条）。
        let increment = HomeDigest.digests(from: [
            session("s-2", persona: "p-2", unread: 2, preview: "你在干嘛", at: base)
        ])
        XCTAssertTrue(HomeDigest.changed(old: old, new: increment))
        let merged = HomeDigest.merge(old: old, new: increment)
        XCTAssertEqual(merged.map(\.sessionId), ["s-2", "s-1"])
        XCTAssertEqual(merged[0].unreadCount, 2)
        XCTAssertEqual(merged[0].lastMessagePreview, "你在干嘛")
        XCTAssertEqual(merged[1], old[0], "没提到的行原样留着")
    }

    // MARK: - 该不该重画

    /// 三列都没变 → 不重画（后台任务写 `updatedAt` 的次数很多，不能一写就抖一遍列表）。
    func testUnchangedDigestsDoNotTriggerRedraw() {
        let old = HomeDigest.digests(from: [
            session("s-1", persona: "p-1", unread: 1, preview: "晚安", at: base)
        ])
        let same = HomeDigest.digests(from: [
            session("s-1", persona: "p-1", unread: 1, preview: "晚安", at: base)
        ])
        XCTAssertFalse(HomeDigest.changed(old: old, new: same))
        XCTAssertTrue(HomeDigest.onlyChanged(old: old, new: same).isEmpty)
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
            XCTAssertEqual(HomeDigest.onlyChanged(old: old, new: fresh).count, 1)
        }
    }
}
