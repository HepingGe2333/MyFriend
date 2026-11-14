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

    /// 说明（0.11.1 审计 P2-12）：这里原本还有 `merge` / `onlyChanged` 两个「增量合并」的
    /// 纯函数，但首页从来没有调用它们 —— 真正执行的是「变了就全量 reload」。
    /// 测试覆盖的不是生产路径，等于死代码；本版删掉这两个函数与它们的用例，
    /// 只保留首页真正使用的 `digests` + `changed`（避免下一个人以为增量刷新已经接上了）。
}
