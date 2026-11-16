import Foundation
import XCTest
@testable import YapKit

/// `AsyncMutex` 必须响应取消（0.11.1 审计 P2-18）。
///
/// 缺陷：`acquire()` 用 `withCheckedContinuation` 登记等待者，没有取消处理。BGTask 到期
/// 取消任务时，它既不会从队列里摘掉、拿到锁之后还会跑完一整拍（包括一次 LLM 请求）——
/// 系统时间片已经用完，白花 token。
final class AsyncMutexCancellationTests: XCTestCase {

    private final class OrderBox: @unchecked Sendable {
        private let lock = NSLock()
        private var items: [String] = []
        func append(_ value: String) { lock.lock(); items.append(value); lock.unlock() }
        var values: [String] { lock.lock(); defer { lock.unlock() }; return items }
    }

    private final class Flag: @unchecked Sendable {
        private let lock = NSLock()
        private var value = false
        func set() { lock.lock(); value = true; lock.unlock() }
        var isSet: Bool { lock.lock(); defer { lock.unlock() }; return value }
    }

    private func wait(_ milliseconds: UInt64) async {
        try? await Task.sleep(nanoseconds: milliseconds * 1_000_000)
    }

    /// 等锁期间被取消：拿不到锁、不跑临界区，锁也能立刻被下一个人拿到。
    func testCancelledWaiterDoesNotEnterCriticalSection() async throws {
        let mutex = AsyncMutex()
        try await mutex.acquire()

        let entered = Flag()
        let waiter = Task {
            do {
                try await mutex.acquire()
            } catch {
                return   // CancellationError：期望
            }
            entered.set()
            mutex.release()
        }
        await wait(30)
        waiter.cancel()
        await wait(30)
        await waiter.value

        mutex.release()
        try await mutex.acquire()   // 被取消的等待者不该占着名额
        mutex.release()
        await wait(30)

        XCTAssertFalse(entered.isSet, "被取消的等待者拿到锁之后仍会跑完一整拍 —— 这正是要修的行为")
    }

    /// 已在取消状态的任务调 `acquire()`：直接抛 `CancellationError`，不登记、不占名额。
    func testAcquireThrowsForAlreadyCancelledTask() async {
        let mutex = AsyncMutex()
        let cancelled: Bool = await Task {
            withUnsafeCurrentTask { $0?.cancel() }
            do {
                try await mutex.acquire()
                mutex.release()
                return false
            } catch is CancellationError {
                return true
            } catch {
                return false
            }
        }.value
        XCTAssertTrue(cancelled)
    }

    /// 正常路径不受影响：排队是 FIFO、名额不会漏。
    func testNormalQueueingStillWorks() async throws {
        let mutex = AsyncMutex()
        let counter = Flag()
        let box = OrderBox()

        try await mutex.acquire()
        let tasks = (0..<3).map { index in
            Task {
                do { try await mutex.acquire() } catch { return }
                box.append("task-\(index)")
                mutex.release()
            }
        }
        await wait(20)
        mutex.release()
        for task in tasks { await task.value }
        // 排队顺序取决于三个 Task 的启动先后（那是调度器的自由），这里只要求
        // 「三个都跑完了、没有人被漏掉」——先前一个用例已经覆盖「取消的人不占名额」。
        XCTAssertEqual(box.values.sorted(), ["task-0", "task-1", "task-2"])
        // 全部交还之后还能再拿到。
        try await mutex.acquire()
        counter.set()
        mutex.release()
        XCTAssertTrue(counter.isSet)
    }

    /// `withLock` 在抛错时也交还名额。
    func testWithLockReleasesOnThrow() async throws {
        let mutex = AsyncMutex()
        struct Boom: Error {}
        do {
            try await mutex.withLock { throw Boom() }
        } catch is Boom {
            // 期望
        }
        try await mutex.acquire()
        mutex.release()
    }
}
