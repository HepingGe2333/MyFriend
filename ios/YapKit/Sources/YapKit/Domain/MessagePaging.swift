import Foundation

// ---------------------------------------------------------------------------
// 聊天页窗口的**合并规则**（纯逻辑，便于单测）。
//
// 分页本身在 v11 改成了「一页 = 一块」（`MessageStore.block(_:sessionId:)` /
// `blocksAround`，规范 `spec/messages-blocks/README.md` §3），这一层只剩两件事：
//
// 1. **往上接一块**（`prepend`）：滚到顶部时把更早那一块并到窗口前面，按 id 去重；
// 2. **往后接新消息**（`appendingNewer`）：心跳 / 主动开口 / 流式结束写进库的新消息，
//    接在已加载区间后面，不重读「最后 N 条」把最早那一页挤掉（0.11.0 P2-5）。
//
// 为什么合并要单独一个函数：界面重读与上滑可能同时把同一页拿两次；
// 合并时按 id 去重是最后一道保险，避免 `ForEach` 出现重复 id。
//
// 「从搜索结果跳到某条消息」的块定位在 `MessageBlocks.focus`（Domain）。
// ---------------------------------------------------------------------------

public enum MessagePaging {

    /// 把「更早的一块」接在窗口前面：按 id 去重、保持时间升序。
    public static func prepend(_ older: [MessageEntity], to current: [MessageEntity]) -> [MessageEntity] {
        if older.isEmpty { return current }
        if current.isEmpty { return older }
        let existing = Set(current.map(\.id))
        let fresh = older.filter { !existing.contains($0.id) }
        return fresh.isEmpty ? current : fresh + current
    }

    // MARK: - 新消息到达：已加载区间不随窗口滑动（0.11.0 P2-5）

    /// 已加载窗口的条目上限。
    ///
    /// 长时间停留在同一个聊天页时，窗口会随着新消息一条条变长：给个上限，
    /// 免得「聊了一整天」的页面把几千条消息又攒回内存里（那就绕回 P2-1 了）。
    /// 600 条 = 10 屏，远比任何正常阅读深度长，同时仍能保住用户翻过的历史。
    public static let maxLoadedMessages = 600

    /// 把「最后一条之后的新消息」追加进窗口：按 id 去重、保持时间升序。
    ///
    /// 为什么不是「重读最后 N 条」：0.10.2 审计 P2-5 说的问题正是那个写法——
    /// 用户上滑加载到 180 条之后，只要来一条新消息，重读的就是「最后 181 条」，
    /// 最早那一页被悄悄挤掉，滚动位置还会跳；翻到最早时还会把「加载更早」重新变出来。
    ///
    /// 现在窗口以**已加载区间的最早一条**为锚，新消息只往后追加，一条历史都不会少。
    /// 只有用户自己的动作会改动历史（清空上下文 / 删除消息），
    /// 那几条路径走「重置窗口」，不走这里。
    ///
    /// - Parameters:
    ///   - newer: 游标（当前窗口最后一条）之后的消息，时间升序。
    ///   - loaded: 当前窗口，时间升序。
    ///   - cap: 窗口上限（默认 `maxLoadedMessages`）；超过时**从最旧的一端**丢，
    ///     因为用户此刻在底部，丢掉的正是离他最远的那几屏。区块分页「往下接一块」时
    ///     传 `.max`（那条路径不该丢历史：界面还靠块号区间判断上面还有没有更早的）。
    public static func appendingNewer(
        _ newer: [MessageEntity],
        to loaded: [MessageEntity],
        cap: Int = maxLoadedMessages
    ) -> [MessageEntity] {
        // `cap` 可以小于一屏（界面永远传默认值；单测用它验证「从最旧的一端丢」）。
        let limit = max(cap, 1)
        guard !newer.isEmpty else { return trim(loaded, to: limit) }
        guard !loaded.isEmpty else { return trim(newer, to: limit) }
        let existing = Set(loaded.map(\.id))
        let fresh = newer.filter { !existing.contains($0.id) }
        guard !fresh.isEmpty else { return loaded }
        // `newer` 是严格晚于窗口最后一条的，所以直接接在后面就是时间升序，不用再排一次。
        return trim(loaded + fresh, to: limit)
    }

    /// 只保留最后 `limit` 条（丢掉最旧的）。
    private static func trim(_ messages: [MessageEntity], to limit: Int) -> [MessageEntity] {
        guard messages.count > limit else { return messages }
        return Array(messages.suffix(limit))
    }
}
