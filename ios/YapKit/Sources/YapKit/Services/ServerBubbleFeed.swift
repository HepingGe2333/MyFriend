import Foundation

// ---------------------------------------------------------------------------
// 服务端模式的「多气泡逐条上屏」去重器（纯逻辑，可单测）。
//
// 0.15 的问题：服务端模式下同一条助手消息会从**两条路**到达客户端——
// 1. 用户自己发消息时的 SSE 流（`ServerChatGateway` 把 `message.created` 转成
//    `ChatEvent.appended`）；
// 2. 前台事件长轮询（`ServerEventService` 的 `message.created`，写进本地缓存）。
// 如果是他主动开口 / 多端同时在线，同一条消息还可能先经事件流、再经 SSE。
//
// 界面原来每次收到 `.appended` 就 `reloadMessages` 整页：多气泡时变成「读库 → 整页
// 替换」好几次，滚动位置会抖、顺序也可能因为两次重读之间又来了新消息而错。这个
// 类型只回答一个问题：
//
//     「这一批新到的消息里，哪些应该**立刻**追加到当前视图？」
//
// 规则：
// - 同一条消息（按 id）只上屏一次：SSE 与事件流谁先到都行，后到的不闪；
// - 按 `(createdAt, id)` 稳定排序：同一条 SSE 批量下发多条时顺序不靠运气；
// - 只追加比「当前窗口最后一条」新的；更旧的（历史回填、离线补拉、重复投递）不打扰
//   当前视图——用户正在看的那一屏不该因为一条旧消息往里插而跳动；
// - 空 id 直接丢弃（坏事件），不让它污染 seen 集合。
//
// 它**不读库、不写库**：调用方拿到 `accepted` 之后自己去取实体、追加到 `messages`。
// 这样去重规则可以离线逐条钉住，不必起数据库或模拟服务端。
// ---------------------------------------------------------------------------

/// 一条「可能该上屏」的服务端消息。
public struct ServerBubbleCandidate: Equatable, Sendable {

    /// 这条消息从哪条路来。去重**不依赖**它（只按 id / 时间），保留它只为排查与单测：
    /// 「SSE 与事件流各来一次，最终只上屏一次」这类用例要能写清楚两边的身份。
    public enum Source: String, Equatable, Sendable {
        /// 用户自己发消息时那条 SSE 流（`ChatEvent.appended`）。
        case sse
        /// 前台事件长轮询（`ServerEventService` 的 `message.created`）。
        case eventStream
        /// 待发队列补发（离线消息恢复）。
        case outbox
        /// 来源不明（测试 / 以后新增的入口）。
        case unknown
    }

    public var id: String
    public var createdAt: Int64
    public var source: Source

    public init(id: String, createdAt: Int64, source: Source = .unknown) {
        self.id = id
        self.createdAt = createdAt
        self.source = source
    }
}

/// 服务端多气泡的「该不该立刻追加」判定。
///
/// 用 `struct` 而不是 actor：它只在 `@MainActor` 的 `ChatViewModel` 里被调用，
/// 一次 `accept` 就是一次同步的「过滤 + 游标推进」，没有并发问题；纯值类型也让单测
/// 可以直接复制一份状态去试「另一种到达顺序」。
public struct ServerBubbleFeed {

    /// 当前视图已经见过的消息 id（含更旧的、被游标挡掉的那些）。
    private var seenIds: Set<String>
    /// 当前视图最后一条的 `(createdAt, id)`；nil = 窗口还是空的。
    private var lastCreatedAt: Int64?
    private var lastId: String?

    /// - Parameters:
    ///   - seenIds: 当前窗口里已有的消息 id（进页面 / 重读整页之后调一次）。
    ///   - lastCreatedAt: 当前窗口最后一条的 createdAt。
    ///   - lastId: 当前窗口最后一条的 id。
    public init(
        seenIds: [String] = [],
        lastCreatedAt: Int64? = nil,
        lastId: String? = nil
    ) {
        self.seenIds = Set(seenIds.compactMap(Self.normalizedId))
        self.lastCreatedAt = lastCreatedAt
        self.lastId = lastId.flatMap(Self.normalizedId)
    }

    /// 当前视图最后一条的时间戳（没有就是 nil）。给调用方判断「窗口是否还是空的」。
    public var latestCreatedAt: Int64? { lastCreatedAt }
    /// 当前视图最后一条的 id。
    public var latestId: String? { lastId }
    /// 这个 id 是否已经被 feed 处理过（含被判定为「更旧、不上屏」的那些）。
    public func hasSeen(id: String) -> Bool {
        guard let normalized = Self.normalizedId(id) else { return false }
        return seenIds.contains(normalized)
    }

    /// 用当前窗口重置（进页面、整页重读之后调用）。
    public mutating func reset(
        seenIds: [String],
        lastCreatedAt: Int64?,
        lastId: String?
    ) {
        self = ServerBubbleFeed(
            seenIds: seenIds, lastCreatedAt: lastCreatedAt, lastId: lastId
        )
    }

    /// 处理一批「进来的消息」，返回**这次应该立刻追加到界面**的那几条（已排序）。
    ///
    /// 不修改输入；返回的是过滤后的副本，调用方可以按 id 去库里取实体。
    @discardableResult
    public mutating func accept(_ candidates: [ServerBubbleCandidate]) -> [ServerBubbleCandidate] {
        guard !candidates.isEmpty else { return [] }

        // 1. 丢掉坏数据（id 为空/全是空白）。坏事件不该占一个 seen 名额。
        let normalized: [ServerBubbleCandidate] = candidates.compactMap { candidate in
            guard let id = Self.normalizedId(candidate.id) else { return nil }
            var copy = candidate
            copy.id = id
            return copy
        }
        guard !normalized.isEmpty else { return [] }

        // 2. `(createdAt, id)` 排序：id 是**决胜键**，保证同一毫秒到达的多条顺序固定，
        //    不依赖两条通道谁先投递（Dictionary / Set 的遍历顺序不参与这里）。
        let sorted = normalized.sorted(by: Self.isOrderedBefore)

        var accepted: [ServerBubbleCandidate] = []
        for candidate in sorted {
            // 3. 同一条消息只上屏一次：SSE 与事件流各来一次也只追加一次。
            guard !seenIds.contains(candidate.id) else { continue }
            seenIds.insert(candidate.id)

            // 4. 只追加比当前窗口最后一条新的；更旧的不打扰当前视图。
            guard isNewerThanCursor(candidate) else { continue }

            accepted.append(candidate)
            lastCreatedAt = candidate.createdAt
            lastId = candidate.id
        }
        return accepted
    }

    /// 单条便利入口：等价于 `accept([candidate])`。
    @discardableResult
    public mutating func accept(
        id: String,
        createdAt: Int64,
        source: ServerBubbleCandidate.Source = .unknown
    ) -> [ServerBubbleCandidate] {
        accept([ServerBubbleCandidate(id: id, createdAt: createdAt, source: source)])
    }

    // MARK: - 内部

    /// 严格全序：先时间、再 id。与数据库 `pageAfter` 的 `ORDER BY createdAt, id`
    /// 以及 `MessagePaging.appendingNewer` 的前提保持一致。
    private static func isOrderedBefore(
        _ lhs: ServerBubbleCandidate,
        _ rhs: ServerBubbleCandidate
    ) -> Bool {
        if lhs.createdAt != rhs.createdAt { return lhs.createdAt < rhs.createdAt }
        return lhs.id < rhs.id
    }

    /// 候选是否严格晚于游标。窗口为空 / 只有时间没有 id 时按可用的那部分比较。
    private func isNewerThanCursor(_ candidate: ServerBubbleCandidate) -> Bool {
        if let lastCreatedAt, let lastId {
            if candidate.createdAt != lastCreatedAt {
                return candidate.createdAt > lastCreatedAt
            }
            return candidate.id > lastId
        }
        if let lastCreatedAt {
            return candidate.createdAt > lastCreatedAt
        }
        // 没有时间游标（窗口为空）时一律接受。
        return true
    }

    private static func normalizedId(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
