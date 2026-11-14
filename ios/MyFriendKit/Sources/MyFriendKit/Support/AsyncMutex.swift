import Foundation

// ---------------------------------------------------------------------------
// 一把「不把系统锁带过 await」的异步互斥。
//
// 为什么需要它：Swift 6 对 `NSLock.lock()` / `unlock()` 标了 `@available(*, noasync)`，
// 因为**在 async 上下文里加锁、然后把锁带过 await** 是危险的：
// 持锁的任务被挂起时，另一个想拿锁的任务会把协作线程池里的线程占死（三个排队者就足以让进程卡住）。
// 之前 `AgentLoop.tick` / `MemoryConsolidator` / `MemoryExtraction` 都是
// `lock.lock(); defer { lock.unlock() }` 包住整段 async 逻辑，行为正确但正是这种写法。
//
// 替代方案不是「把锁换成信号量」（那一样会阻塞线程），而是显式排队：
// 拿不到就挂起一个 continuation，释放时唤醒下一个。临界区仍然「同一时间只有一个人」，
// 只是等待从「占住一个线程」变成「挂起一个任务」。
//
// 锁本身（`NSLock`）只出现在**同步**方法里，加解锁不会跨 await —— 这也是编译期警告消失的原因：
// 它不是被抑制掉的，而是被真正移到了同步的临界区里。
// ---------------------------------------------------------------------------

/// 异步互斥：`await acquire()` 之后临界区里同一时间只有一个任务，用 `release()` 交还。
///
/// 与 `NSLock` 的语义差异只有一条（而且是有意的）：等锁的任务**挂起**而不是阻塞线程。
/// 它不做重入：在这些调用点里没有一个会自己再进一次同一个临界区。
public final class AsyncMutex: @unchecked Sendable {

    private let lock = NSLock()
    private var busy = false
    private var waiters: [(id: UInt64, continuation: CheckedContinuation<Void, Error>)] = []
    private var nextWaiterId: UInt64 = 0

    public init() {}

    /// 申请进入临界区；已被占用就挂起，直到前一个调用者 `release()`。
    ///
    /// 0.11.1 审计 P2-18：等待**响应取消**。以前被取消的任务既不会从 `waiters` 里摘掉，
    /// 拿到锁之后也照样跑完整拍（包括一次 LLM 请求、写主动消息/通知）——
    /// 而此时 BGTask 的时间片已经用完。现在：取消时把自己摘掉并抛 `CancellationError`；
    /// 即使「刚被授予锁」与「取消」同时发生，也会在返回前再查一次并把名额交还。
    public func acquire() async throws {
        if tryAcquire() {
            // 拿到锁的瞬间已经被取消：不占着名额，直接抛。
            if Task.isCancelled {
                release()
                throw CancellationError()
            }
            return
        }
        let id: UInt64 = lock.mfWithLock {
            nextWaiterId += 1
            return nextWaiterId
        }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                // 登记之前可能刚好被释放：那时 `enqueue` 会返回 false，由这里直接放行。
                if !enqueue(id: id, continuation: continuation) {
                    continuation.resume()
                    return
                }
                // 登记之后、真正挂起之前也可能已经被取消：自查一次并摘除。
                if Task.isCancelled { cancelWaiter(id) }
            }
        } onCancel: {
            cancelWaiter(id)
        }
        // 「被授予锁」与「取消」同时发生时，`release()` 会先把它唤醒（成功 resume）；
        // 这里补一次检查，保证取消方不会带着名额去跑临界区。
        if Task.isCancelled {
            release()
            throw CancellationError()
        }
    }

    /// 交还临界区；有等待者就把所有权转给最早的那个（FIFO，与 NSLock 的排队直觉一致）。
    public func release() {
        let next: CheckedContinuation<Void, Error>? = lock.mfWithLock {
            if waiters.isEmpty {
                busy = false
                return nil
            }
            return waiters.removeFirst().continuation
        }
        next?.resume()
    }

    /// 「跑完这段 async 活再交还」。抛出（含取消）时也会交还，不会漏名额。
    public func withLock<T>(_ body: () async throws -> T) async throws -> T {
        try await acquire()
        defer { release() }
        return try await body()
    }

    // MARK: - 同步临界区（锁只在这里出现）

    private func tryAcquire() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if busy { return false }
        busy = true
        return true
    }

    /// - Returns: true = 已登记成等待者；false = 临界区刚好空着，调用方可以直接继续。
    private func enqueue(id: UInt64, continuation: CheckedContinuation<Void, Error>) -> Bool {
        lock.mfWithLock {
            if !busy {
                busy = true
                return false
            }
            waiters.append((id: id, continuation: continuation))
            return true
        }
    }

    /// 把某个等待者从队列里摘掉并以 `CancellationError` 唤醒。
    ///
    /// 找不到说明它已经被 `release()` 授予了名额（或已经取消过一次）：那时什么都不做，
    /// 由 `acquire()` 末尾的 `Task.isCancelled` 检查负责把名额交还。
    private func cancelWaiter(_ id: UInt64) {
        let removed: CheckedContinuation<Void, Error>? = lock.mfWithLock {
            guard let index = waiters.firstIndex(where: { $0.id == id }) else { return nil }
            return waiters.remove(at: index).continuation
        }
        removed?.resume(throwing: CancellationError())
    }
}

extension NSLock {
    /// 同步临界区小包装：让 `NSLock` 的加解锁只出现在同步方法里。
    ///
    /// 名字带前缀是为了不和 Foundation 自己可能提供的 `withLock` 撞（同名不同语义会很难查）。
    @inline(__always)
    func mfWithLock<T>(_ body: () -> T) -> T {
        lock()
        defer { unlock() }
        return body()
    }
}
