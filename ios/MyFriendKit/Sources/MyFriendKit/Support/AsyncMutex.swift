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
    private var waiters: [CheckedContinuation<Void, Never>] = []

    public init() {}

    /// 申请进入临界区；已被占用就挂起，直到前一个调用者 `release()`。
    public func acquire() async {
        if tryAcquire() { return }
        await withCheckedContinuation { continuation in
            // 登记之前可能刚好被释放：那时 `enqueue` 会返回 false，由这里直接放行。
            if !enqueue(continuation) {
                continuation.resume()
            }
        }
    }

    /// 交还临界区；有等待者就把所有权转给最早的那个（FIFO，与 NSLock 的排队直觉一致）。
    public func release() {
        lock.lock()
        if waiters.isEmpty {
            busy = false
            lock.unlock()
            return
        }
        let next = waiters.removeFirst()
        lock.unlock()
        next.resume()
    }

    /// 「跑完这段 async 活再交还」。抛出时也会交还，不会漏名额。
    public func withLock<T>(_ body: () async throws -> T) async rethrows -> T {
        await acquire()
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
    private func enqueue(_ continuation: CheckedContinuation<Void, Never>) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if !busy {
            busy = true
            return false
        }
        waiters.append(continuation)
        return true
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
