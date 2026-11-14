import Foundation

// ---------------------------------------------------------------------------
// 全局 LLM 并发闸（1.9 §五）。
//
// 产品约束：所有模型调用（聊天 / 抽取 / 整理 / 思考 / 打分 / 回忆重构 / 压缩）共享
// **一个全局闸门，上限 3**；并且**聊天有保底**——后台任务最多同时占 2 个位，
// 聊天总能进剩下的那 1 个。
//
// 为什么是「后台名额 2 + 全局名额 3」两层，而不是给聊天单独的通道：
// 后台那 2 个位是**上限**（它们连开 5 个任务时最多 2 个在跑），而全局 3 是**总上限**。
// 于是任意时刻最多 2 个后台 + 1 个聊天在跑；聊天不会因为后台排队而进不来，
// 后台也不会把 3 个位吃满把聊天饿死。
//
// 为什么必须是纯逻辑类：这条规则一旦散到各个调用点（聊天一处、抽取一处、整理一处…），
// 就会出现「新建一个后台任务的人忘了加闸」。这里只有一个入口，
// `OpenAICompatibleClient` 是所有请求的必经之路，闸门挂在那里。
//
// 排队不是无限的：后台等 20 秒拿不到就放弃（记一条日志，不抛给用户）；
// 聊天等 60 秒（用户在等着回话，值得多等一会儿）。
//
// 实现：锁 + 计数 + 等待者队列（continuation）。**不阻塞线程**——
// 这是 async 代码里最关键的一条：用信号量阻塞会把 Swift 并发的协作线程池占死，
// 三个排队者就足以让整个进程卡住。
// ---------------------------------------------------------------------------

/// 一次调用属于哪条道。聊天 = 用户此刻在等回话；后台 = 他自己在忙的事。
public enum LlmCallLane: String, Sendable {
    case chat = "CHAT"
    case background = "BACKGROUND"

    /// 用途字符串（`UsageKind`）→ 道。只有聊天是聊天，其余都算后台。
    public init(kind: String?) {
        self = UsageKind.isChat(kind) ? .chat : .background
    }

    public var label: String {
        switch self {
        case .chat: return "聊天"
        case .background: return "后台"
        }
    }
}

/// 拿到名额的凭证。用完必须 `release`，否则名额会漏（闸门会一直变窄）。
public struct LlmGateTicket: Sendable, Equatable {
    public let lane: LlmCallLane
    public let id: UInt64
}

/// 一次排队的结果。
public enum LlmGateResult: Sendable, Equatable {
    case acquired(LlmGateTicket)
    /// 到点还没轮到。`waitedMillis` 是实际等了多久（就是超时值）。
    case timedOut(lane: LlmCallLane, waitedMillis: Int64)

    public var ticket: LlmGateTicket? {
        if case .acquired(let ticket) = self { return ticket }
        return nil
    }
}

/// 全局并发闸。进程内单例（`shared`），也可以给测试单独造一个。
public final class LlmConcurrencyGate: @unchecked Sendable {

    /// 全局上限：同时最多 3 个模型调用。
    public static let globalLimit = 3
    /// 后台上限：同时最多 2 个后台调用（剩下那 1 个位留给聊天）。
    public static let backgroundLimit = 2
    /// 后台等多久还拿不到就放弃（毫秒）。
    public static let backgroundTimeoutMillis: Int64 = 20_000
    /// 聊天等多久还拿不到就放弃（毫秒）。
    public static let chatTimeoutMillis: Int64 = 60_000

    public static let shared = LlmConcurrencyGate()

    /// 排队超时时叫一声（App 层拿去写日志；默认什么都不做）。
    ///
    /// 为什么用回调而不是直接抛错：后台任务拿不到名额时的正确行为是「这次不做」，
    /// 而不是把一个 Error 冒到界面上——用户不该看见「他排队超时了」。
    public var onTimeout: ((LlmCallLane, Int64) -> Void)?

    private final class Waiter {
        let id: UInt64
        let lane: LlmCallLane
        let timeoutMillis: Int64
        let continuation: CheckedContinuation<LlmGateResult, Never>
        /// 定时器与凭证都要在「拿到 / 过期」时读到，所以放在锁里改。
        var timeoutTask: Task<Void, Never>?
        var grantedTicket: LlmGateTicket?

        init(id: UInt64, lane: LlmCallLane, timeoutMillis: Int64,
             continuation: CheckedContinuation<LlmGateResult, Never>) {
            self.id = id
            self.lane = lane
            self.timeoutMillis = timeoutMillis
            self.continuation = continuation
        }
    }

    private let lock = NSLock()
    private var globalActive = 0
    private var backgroundActive = 0
    private var nextId: UInt64 = 0
    private var waiters: [Waiter] = []

    public init() {}

    // MARK: - 取 / 还

    /// 申请一个名额。返回到点没等到时是 `.timedOut`，调用方按「这次不做」处理。
    ///
    /// - Parameters:
    ///   - kind: `UsageKind` 那串用途名（CHAT = 聊天，其余都走后台道）。
    ///   - timeoutMillis: 自定义等待上限；不传就按道取默认值。
    public func acquire(kind: String?, timeoutMillis: Int64? = nil) async -> LlmGateResult {
        let lane = LlmCallLane(kind: kind)
        let timeout = max(timeoutMillis ?? Self.defaultTimeout(lane), 0)

        // 快速路径：名额够就直接给，连 continuation 都不用挂。
        //
        // 锁只出现在同步的 `mfWithLock` 里：加解锁不跨 await，等待名额靠挂起 continuation
        // 而不是阻塞线程（这些语义与原实现逐条一致，见 LlmConcurrencyGateTests）。
        let quick = lock.mfWithLock { tryAcquireLocked(lane) }
        if let quick { return .acquired(quick) }
        let waiterId = lock.mfWithLock { () -> UInt64 in
            nextId += 1
            return nextId
        }

        // 名额不够：登记成等待者（登记时再查一次，避免这中间有人还了名额）。
        return await withCheckedContinuation { continuation in
            // 登记与「再查一次名额」在同一段同步临界区里完成，避免这中间有人还了名额又被别人抢走。
            enum Registration {
                case acquired(LlmGateTicket)
                case registered
            }
            let registration: Registration = lock.mfWithLock {
                if let ticket = tryAcquireLocked(lane) { return .acquired(ticket) }
                waiters.append(
                    Waiter(id: waiterId, lane: lane, timeoutMillis: timeout,
                           continuation: continuation)
                )
                return .registered
            }
            if case .acquired(let ticket) = registration {
                continuation.resume(returning: .acquired(ticket))
                return
            }

            let timeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeout) * 1_000_000)
                self?.expireWaiter(id: waiterId)
            }
            let installed = lock.mfWithLock { () -> Bool in
                if let index = waiters.firstIndex(where: { $0.id == waiterId }) {
                    waiters[index].timeoutTask = timeoutTask
                    return true
                }
                return false
            }
            if !installed {
                // 已经拿到名额 / 已经过期：这个定时器没用了。
                timeoutTask.cancel()
            }
        }
    }

    /// 还名额。多还一次不会把计数搞成负数（只在 Debug 里报一句，别在生产里炸掉聊天）。
    public func release(_ ticket: LlmGateTicket) {
        lock.lock()
        if globalActive > 0 { globalActive -= 1 }
        if ticket.lane == .background && backgroundActive > 0 { backgroundActive -= 1 }
        let granted = takeGrantableWaiterLocked()
        lock.unlock()
        if let granted, let ticket = granted.grantedTicket {
            granted.continuation.resume(returning: .acquired(ticket))
        }
    }

    /// 当前占用情况（测试与「他正在忙」这类展示用）。
    public func snapshot() -> (global: Int, background: Int, waiting: Int) {
        lock.lock()
        defer { lock.unlock() }
        return (globalActive, backgroundActive, waiters.count)
    }

    // MARK: - 便利入口

    /// 「在这个名额里跑一段 async 活」——拿不到就返回 nil（调用方按「这次不做」处理）。
    public func withSlot<T>(kind: String?, timeoutMillis: Int64? = nil,
                            _ body: () async throws -> T) async rethrows -> T? {
        let result = await acquire(kind: kind, timeoutMillis: timeoutMillis)
        guard let ticket = result.ticket else { return nil }
        defer { release(ticket) }
        return try await body()
    }

    // MARK: - 内部

    static func defaultTimeout(_ lane: LlmCallLane) -> Int64 {
        switch lane {
        case .chat: return chatTimeoutMillis
        case .background: return backgroundTimeoutMillis
        }
    }

    private func tryAcquireLocked(_ lane: LlmCallLane) -> LlmGateTicket? {
        guard globalActive < Self.globalLimit else { return nil }
        if lane == .background && backgroundActive >= Self.backgroundLimit { return nil }
        globalActive += 1
        if lane == .background { backgroundActive += 1 }
        nextId += 1
        return LlmGateTicket(lane: lane, id: nextId)
    }

    /// 到点还没轮到：把自己从队列里摘掉，并告诉调用方「这次算了」。
    private func expireWaiter(id: UInt64) {
        lock.lock()
        guard let index = waiters.firstIndex(where: { $0.id == id }) else {
            lock.unlock()
            return
        }
        let waiter = waiters.remove(at: index)
        let timeout = waiter.timeoutMillis
        lock.unlock()
        waiter.timeoutTask?.cancel()
        onTimeout?(waiter.lane, timeout)
        waiter.continuation.resume(returning: .timedOut(lane: waiter.lane, waitedMillis: timeout))
    }

    /// 从队列里找一个能立刻满足的等待者（FIFO：先到的先拿，聊天优先由「后台名额上限」保证）。
    private func takeGrantableWaiterLocked() -> Waiter? {
        guard globalActive < Self.globalLimit else { return nil }
        guard let index = waiters.firstIndex(where: { waiter in
            waiter.lane == .chat || backgroundActive < Self.backgroundLimit
        }) else { return nil }
        let waiter = waiters.remove(at: index)
        // 名额在这里就记上账：等待者拿到凭证之前不能再被别人抢走。
        globalActive += 1
        if waiter.lane == .background { backgroundActive += 1 }
        nextId += 1
        waiter.grantedTicket = LlmGateTicket(lane: waiter.lane, id: nextId)
        waiter.timeoutTask?.cancel()
        return waiter
    }
}
