import Foundation

// ---------------------------------------------------------------------------
// 聊天页的窗口化分页（纯逻辑，便于单测）。
//
// 语义与 Android 侧的窗口化一致：
// - 首屏只取最近 60 条；
// - 上滑到顶再取 60 条（`createdAt` 游标，`id` 做同一毫秒的稳定排序键），直到最早；
// - 加载中不重复触发（由界面状态位负责，这里只负责「合并」）。
//
// 为什么合并要单独一个函数：同一毫秒里可能落两条消息（主动开口与用户发送撞在一起），
// 游标按 `(createdAt, id)` 取值时不会重复，但界面重读与上滑可能同时把同一页拿两次；
// 合并时按 id 去重是最后一道保险，避免 `ForEach` 出现重复 id。
//
// 0.10.2 起还多了一件事：**从搜索结果跳进会话并定位到某条消息**。
// 那段「一页页往前取，直到目标进入窗口」的逻辑也放在这里（`window(reaching:)`）：
// 它不碰数据库，取页由调用方给（真实实现是 `MessageStore.pageBefore`），
// 所以可以在单测里用假数据把「老消息在很前面」「目标被删了」这些情况跑完。
// ---------------------------------------------------------------------------

public enum MessagePaging {
    /// 首屏与每一页的条数。
    public static let pageSize = 60

    /// 把「更早的一页」接在窗口前面：按 id 去重、保持时间升序。
    public static func prepend(_ older: [MessageEntity], to current: [MessageEntity]) -> [MessageEntity] {
        if older.isEmpty { return current }
        if current.isEmpty { return older }
        let existing = Set(current.map(\.id))
        let fresh = older.filter { !existing.contains($0.id) }
        return fresh.isEmpty ? current : fresh + current
    }

    // MARK: - 定位到某条消息

    /// 一次「跳到指定消息」最多往前翻多少页（60 × 200 = 12000 条，覆盖任何真实会话）。
    ///
    /// 为什么要上限：目标 id 如果已经被删掉，没有上限就是一次「读到最早」的全表扫描，
    /// 长会话下会让点一下结果卡住好几秒。宁可少翻一点，给个「没找到」的提示。
    public static let maxFocusPages = 200

    /// 定位的结果。
    public struct FocusWindow: Equatable {
        /// 最终的窗口（时间升序）。
        public var messages: [MessageEntity]
        /// 目标有没有进入窗口。
        public var found: Bool
        /// 往前取了几页（0 表示首屏就找到了）。
        public var pagesLoaded: Int
        /// 是不是因为没有更早的消息才停下（= 目标确实不在库里了）。
        public var reachedEarliest: Bool

        public init(messages: [MessageEntity], found: Bool, pagesLoaded: Int, reachedEarliest: Bool) {
            self.messages = messages
            self.found = found
            self.pagesLoaded = pagesLoaded
            self.reachedEarliest = reachedEarliest
        }
    }

    /// 从当前窗口出发，一页页往前取，直到目标消息进入窗口（或没有更早的了）。
    ///
    /// - Parameters:
    ///   - messageId: 要找的消息 id。
    ///   - start: 当前窗口（时间升序，通常是首屏那 60 条）。
    ///   - loadOlder: 取更早一页；返回空数组 / nil 表示「没有更早的了」。
    ///     参数是「当前窗口里最早的那条（可能为空）」与「这是第几页」——真实实现
    ///     拿它当 `pageBefore` 的游标，所以这层不需要知道数据库长什么样。
    ///   - maxPages: 最多翻几页（默认 `maxFocusPages`）。
    public static func window(
        reaching messageId: String,
        from start: [MessageEntity],
        maxPages: Int = maxFocusPages,
        loadOlder: (MessageEntity?, Int) -> [MessageEntity]?
    ) -> FocusWindow {
        var messages = start
        if messageId.isEmpty || messages.contains(where: { $0.id == messageId }) {
            return FocusWindow(
                messages: messages,
                found: messages.contains(where: { $0.id == messageId }),
                pagesLoaded: 0,
                reachedEarliest: false
            )
        }

        var pages = 0
        while pages < max(0, maxPages) {
            guard let older = loadOlder(messages.first, pages), !older.isEmpty else {
                return FocusWindow(messages: messages, found: false, pagesLoaded: pages, reachedEarliest: true)
            }
            let merged = prepend(older, to: messages)
            pages += 1
            // 一页都没合进来（全是重复 id）说明游标没有往前走，再翻也是同一页。
            guard merged.count > messages.count else {
                messages = merged
                return FocusWindow(messages: messages, found: false, pagesLoaded: pages, reachedEarliest: false)
            }
            messages = merged
            if messages.contains(where: { $0.id == messageId }) {
                return FocusWindow(messages: messages, found: true, pagesLoaded: pages, reachedEarliest: false)
            }
        }

        return FocusWindow(
            messages: messages,
            found: messages.contains(where: { $0.id == messageId }),
            pagesLoaded: pages,
            reachedEarliest: false
        )
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
    /// 只有用户自己的动作会改动历史（清空上下文 / 删除消息 / 重新生成），
    /// 那几条路径走「重置窗口」，不走这里。
    ///
    /// - Parameters:
    ///   - newer: 游标（当前窗口最后一条）之后的消息，时间升序。
    ///   - loaded: 当前窗口，时间升序。
    ///   - cap: 窗口上限（默认 `maxLoadedMessages`）；超过时**从最旧的一端**丢，
    ///     因为用户此刻在底部，丢掉的正是离他最远的那几屏。
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
