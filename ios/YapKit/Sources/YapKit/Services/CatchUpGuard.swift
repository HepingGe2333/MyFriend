import Foundation

// ---------------------------------------------------------------------------
// 「补算」的重入保护（0.10.2 审计 P1-2）。
//
// iOS 不允许常驻后台，所以「错过的时间」只能在两个地方补：冷启动的 `.task` 与
// `scenePhase == .active`。这两处会**同时**命中同一次冷启动（`inactive → active` 也算一次
// 变化），于是 `performCatchUp()` 被跑两遍：两遍读到同一个 `lastHeartbeatAt` /
// `lastConsolidatedAt`，都判「该补」→ 两次心跳、两次每日整理（双倍 token、两条主动消息）。
//
// `BackgroundCatchUp.plan` 只做纯判定（它不知道「另一次正在跑」），所以把「这一拍归谁」
// 收进这个类型：**in-flight 标记 + 最小间隔**。判据与副作用必须在同一层拿主意，
// 否则「先查后写」之间永远有缝。
// ---------------------------------------------------------------------------

/// 补算的闸：同一时刻只允许一次，且两次之间至少隔 `minimumIntervalMillis`。
public final class CatchUpGuard {

    /// 两次补算之间至少隔多久（毫秒）。30 秒：比一次心跳/整理慢不了多少，
    /// 又足够挡住「冷启动 + 回前台」这种同一时刻的重复触发。
    public static let defaultMinimumIntervalMillis: Int64 = 30_000

    private let lock = NSLock()
    private var inFlight = false
    private var lastStartedAt: Int64 = .min

    public init() {}

    /// 现在允许跑一次吗？允许时**立刻**记成「在跑」，调用方结束后必须 `finish()`。
    ///
    /// - Returns: false = 另一次还在跑，或距离上次开始不到最小间隔。
    public func begin(now: Int64, minimumIntervalMillis: Int64 = CatchUpGuard.defaultMinimumIntervalMillis) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if inFlight { return false }
        if lastStartedAt != Int64.min, now - lastStartedAt < minimumIntervalMillis { return false }
        inFlight = true
        lastStartedAt = now
        return true
    }

    /// 这一次跑完了（成功、失败、被取消都要调）。
    public func finish() {
        lock.lock()
        inFlight = false
        lock.unlock()
    }

    /// 有没有一次补算正在跑（界面/日志用）。
    public var isInFlight: Bool {
        lock.lock()
        defer { lock.unlock() }
        return inFlight
    }

    /// 上次被允许开始的时间（没有过是 nil）。
    public var lastStartAt: Int64? {
        lock.lock()
        defer { lock.unlock() }
        return lastStartedAt == Int64.min ? nil : lastStartedAt
    }
}
