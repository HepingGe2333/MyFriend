import Foundation

// ---------------------------------------------------------------------------
// 首页联系人列表的「观察聚合」（纯逻辑，0.11.0 审计 P2-4）。
//
// 首页原来只在 `onAppear` / 切人格 / 下拉刷新时读一次库：心跳或主动唤醒在用户停留
// 首页时往会话里写了消息，未读角标与「最后一句」就不会变 —— 通知都弹了，列表却还是旧的。
//
// 为什么抽成这一层：首页真正需要的只是 `chat_sessions` 的三列（未读 / 预览 / 最后一条时间），
// 不需要任何消息正文。把「读出来的会话行 → 轻量摘要」和「摘要变了没 / 怎么合并」
// 这两件事写成纯函数，就能在 macOS 上直接断言，也避免界面里再手写一份比较逻辑。
//
// 只比这三列、不比 `updatedAt`：后台任务写 `updatedAt` 的机会很多（抽记忆计数、摘要进度），
// 那些变化跟首页上画的东西无关，比进去只会让列表白重画、滚动位置发抖。
// ---------------------------------------------------------------------------

/// 首页一行需要的会话摘要。
public struct SessionDigest: Equatable, Sendable {
    public var sessionId: String
    public var personaId: String
    public var unreadCount: Int
    public var lastMessagePreview: String?
    public var lastMessageAt: Int64?

    public init(
        sessionId: String,
        personaId: String,
        unreadCount: Int,
        lastMessagePreview: String?,
        lastMessageAt: Int64?
    ) {
        self.sessionId = sessionId
        self.personaId = personaId
        self.unreadCount = unreadCount
        self.lastMessagePreview = lastMessagePreview
        self.lastMessageAt = lastMessageAt
    }

    /// 排序用：说过话的按最后一句时间（与首页 `Contact.sortAt` 同一口径）。
    public var sortAt: Int64 { lastMessageAt ?? 0 }
}

public enum HomeDigest {

    /// 会话行 → 轻量摘要（一切入参都来自 `chat_sessions`，不读消息表）。
    public static func digests(from sessions: [ChatSessionEntity]) -> [SessionDigest] {
        sessions.map { session in
            SessionDigest(
                sessionId: session.id,
                personaId: session.personaId,
                unreadCount: session.unreadCount,
                lastMessagePreview: session.lastMessagePreview,
                lastMessageAt: session.lastMessageAt
            )
        }
    }

    /// 首页要不要重画：未读 / 预览 / 最后一条时间有任何变化，或者会话多/少了。
    ///
    /// 比较用 `sessionId` 索引而不是数组顺序：SQLite 的 `ORDER BY` 在时间相同的行上
    /// 顺序不保证，数组一比就会「每次都变」。
    public static func changed(old: [SessionDigest], new: [SessionDigest]) -> Bool {
        if old.count != new.count { return true }
        var index: [String: SessionDigest] = [:]
        for digest in old { index[digest.sessionId] = digest }
        for digest in new {
            guard let previous = index.removeValue(forKey: digest.sessionId) else { return true }
            if previous != digest { return true }
        }
        return !index.isEmpty
    }

    /// 合并新旧摘要：新的覆盖旧的，按「最近说话」降序。
    ///
    /// 为什么是合并而不是直接替换：首屏用一次全量快照，之后每一拍只带回来变化的那几行
    /// （`onlyChanged`）也成立；顺序与「没有变化时不重画」的判定都放在这里，
    /// 界面只负责把合并结果画出来。
    public static func merge(old: [SessionDigest], new: [SessionDigest]) -> [SessionDigest] {
        var merged: [String: SessionDigest] = [:]
        var order: [String] = []
        for digest in old where merged[digest.sessionId] == nil {
            merged[digest.sessionId] = digest
            order.append(digest.sessionId)
        }
        for digest in new {
            if merged[digest.sessionId] == nil { order.append(digest.sessionId) }
            merged[digest.sessionId] = digest
        }
        return order.compactMap { merged[$0] }.sorted { lhs, rhs in
            if lhs.sortAt != rhs.sortAt { return lhs.sortAt > rhs.sortAt }
            return lhs.sessionId < rhs.sessionId
        }
    }

    /// 只挑出与旧快照不同的那几条（增量刷新用；全量快照会返回全部）。
    public static func onlyChanged(old: [SessionDigest], new: [SessionDigest]) -> [SessionDigest] {
        var index: [String: SessionDigest] = [:]
        for digest in old { index[digest.sessionId] = digest }
        return new.filter { index[$0.sessionId] != $0 }
    }
}
