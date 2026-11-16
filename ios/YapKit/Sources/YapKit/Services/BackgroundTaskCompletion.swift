import Foundation

// ---------------------------------------------------------------------------
// BGTask 的「完成上报」状态机（0.10.2 审计 P1-4）。
//
// `BGTaskScheduler` 里 `setTaskCompleted(success:)` 的 `success` 语义是**任务成功与否**，
// 不是「这一拍有没有做事」。0.10.1 的写法是
// `task.setTaskCompleted(success: acted && !work.isCancelled)`：按产品设计，心跳绝大多数拍
// 什么都不做（`HeartbeatController` 的注释自己写明了），于是每一次正常跑完的刷新都被上报成
// **失败**。系统据此降低后续调度频率，最后把后台刷新饿死。
//
// 另一头：`expirationHandler` 只 `work.cancel()`。如果这次活卡在门控等待（20/60 秒）
// 或长请求（120/900 秒）里，`setTaskCompleted` 可能整个时间片都到不了 →
// 进程被系统按「未上报完成」杀掉，同样影响后续调度。
//
// 所以：无事可做也报 success=true；到期取消必须**自己**报 success=false，且只报一次。
// 这段状态机放在 Kit 里（对 `BGTask` 没有依赖），App 只把 `setTaskCompleted` 当闭包传进来。
// ---------------------------------------------------------------------------

/// 「这个 BGTask 报过了没有」的唯一权威。
public final class BackgroundTaskCompletionReporter {

    private let lock = NSLock()
    private var reported = false
    private let report: (Bool) -> Void

    /// - Parameter report: 真正的上报动作（App 传 `{ task.setTaskCompleted(success: $0) }`）。
    public init(report: @escaping (Bool) -> Void) {
        self.report = report
    }

    public var hasReported: Bool {
        lock.lock()
        defer { lock.unlock() }
        return reported
    }

    /// 正常跑完（**包括「无事可做」**）。
    ///
    /// - Returns: 这次调用有没有真的上报（false = 之前已经报过了）。
    @discardableResult
    public func complete() -> Bool {
        reportOnce(success: true)
    }

    /// 时间片到期 / 被系统取消：立刻报失败，别让进程被杀。
    ///
    /// - Returns: 这次调用有没有真的上报。
    @discardableResult
    public func expire() -> Bool {
        reportOnce(success: false)
    }

    private func reportOnce(success: Bool) -> Bool {
        lock.lock()
        if reported {
            lock.unlock()
            return false
        }
        reported = true
        lock.unlock()
        // 上报放在锁外：`setTaskCompleted` 是系统调用，别在临界区里做。
        report(success)
        return true
    }
}
