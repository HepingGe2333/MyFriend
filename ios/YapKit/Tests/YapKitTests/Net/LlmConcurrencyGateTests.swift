import Foundation
import XCTest
@testable import YapKit

/// 全局并发闸（§五）。
///
/// 这一版把「同时最多几个模型调用」从一个口头约定变成了一个**可测的纯逻辑**：
/// - 全局上限 3；
/// - 后台最多同时 2 个（聊天总能进剩下那 1 个位）；
/// - 排队有上限：后台 20 秒、聊天 60 秒，等不到就放弃（不是无限等）。
///
/// 为什么这些用例值得单独写：并发 bug 平时看不见，一出现就是「聊天发不出去」「后台任务
/// 排到天荒地老」这种最难复现的问题。这里用真实的并发任务跑，钉住的是行为而不是实现。
final class LlmConcurrencyGateTests: XCTestCase {

    /// 后台连开 5 个任务：最多 2 个在跑（不是 3，也不是 5）。
    func testBackgroundTasksNeverExceedTwoAtOnce() async {
        let gate = LlmConcurrencyGate()
        let counter = ConcurrencyCounter()
        let finished = expectation(description: "5 个后台任务全部结束")
        finished.expectedFulfillmentCount = 5

        for index in 0..<5 {
            Task {
                let result = await gate.acquire(kind: UsageKind.consolidation)
                guard let ticket = result.ticket else {
                    XCTFail("第 \(index) 个后台任务不该被拒绝")
                    finished.fulfill()
                    return
                }
                await counter.enter()
                // 让任务真的重叠一会儿：不 sleep 的话它们会一个接一个地跑完，测不出并发。
                try? await Task.sleep(nanoseconds: 40_000_000)
                await counter.leave()
                gate.release(ticket)
                finished.fulfill()
            }
        }

        await fulfillment(of: [finished], timeout: 10)
        let peak = await counter.peak
        XCTAssertLessThanOrEqual(peak, LlmConcurrencyGate.backgroundLimit,
                                 "后台任务最多同时 2 个（多的留给聊天）")
        XCTAssertGreaterThan(peak, 1, "这个用例要能测出「真的并发」才有意义")
    }

    /// 聊天有保底：2 个后台在跑时，聊天仍然能立刻拿到第 3 个位。
    func testChatStillGetsTheThirdSlotWhenBackgroundIsFull() async {
        let gate = LlmConcurrencyGate()
        let first = await gate.acquire(kind: UsageKind.consolidation)
        let second = await gate.acquire(kind: UsageKind.extraction)
        XCTAssertNotNil(first.ticket)
        XCTAssertNotNil(second.ticket)
        // 后台名额已经满了：再来一个后台（0 超时）应当拿不到。
        let blocked = await gate.acquire(kind: UsageKind.agent, timeoutMillis: 0)
        XCTAssertNil(blocked.ticket, "后台只有 2 个位")

        // 聊天不受后台名额限制，直接进第 3 个位。
        let chat = await gate.acquire(kind: UsageKind.chat, timeoutMillis: 0)
        XCTAssertNotNil(chat.ticket, "聊天必须能进剩下那 1 个位")
        let snapshot = gate.snapshot()
        XCTAssertEqual(snapshot.global, 3)
        XCTAssertEqual(snapshot.background, 2)

        if let ticket = first.ticket { gate.release(ticket) }
        if let ticket = second.ticket { gate.release(ticket) }
        if let ticket = chat.ticket { gate.release(ticket) }
    }

    /// 全局上限 3：2 个后台 + 2 个聊天时，第 4 个调用必须排队/超时。
    func testGlobalLimitIsThree() async {
        let gate = LlmConcurrencyGate()
        var tickets: [LlmGateTicket] = []
        for kind in [UsageKind.consolidation, UsageKind.agent, UsageKind.chat] {
            let result = await gate.acquire(kind: kind, timeoutMillis: 0)
            if let ticket = result.ticket { tickets.append(ticket) }
        }
        XCTAssertEqual(tickets.count, 3)
        let overflow = await gate.acquire(kind: UsageKind.chat, timeoutMillis: 0)
        XCTAssertNil(overflow.ticket, "全局上限是 3")
        for ticket in tickets { gate.release(ticket) }
        XCTAssertEqual(gate.snapshot().global, 0, "还回去以后要归零")
    }

    /// 排队的人会在名额空出来时被叫醒（FIFO），而不是等到超时。
    func testWaiterIsGrantedWhenSlotIsReleased() async {
        let gate = LlmConcurrencyGate()
        let first = await gate.acquire(kind: UsageKind.consolidation)
        let second = await gate.acquire(kind: UsageKind.agent)
        XCTAssertNotNil(first.ticket)
        XCTAssertNotNil(second.ticket)

        let waiting = expectation(description: "排队的后台任务拿到了名额")
        let granted = LockedBox<Bool>(false)
        Task {
            let result = await gate.acquire(kind: UsageKind.extraction, timeoutMillis: 5_000)
            if let ticket = result.ticket {
                granted.set(true)
                gate.release(ticket)
            }
            waiting.fulfill()
        }

        // 让排队者先登记上，再还一个名额。
        try? await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertFalse(granted.get(), "名额没空出来之前不该拿到")
        if let ticket = first.ticket { gate.release(ticket) }
        await fulfillment(of: [waiting], timeout: 5)
        XCTAssertTrue(granted.get(), "还回名额后，排队的人应当立刻拿到")
        if let ticket = second.ticket { gate.release(ticket) }
    }

    /// 超时是真的会超时：后台等 0 毫秒就放弃，并触发 `onTimeout`（App 拿它写日志）。
    func testTimeoutFiresCallbackAndDoesNotLeakSlots() async {
        let gate = LlmConcurrencyGate()
        let timeouts = LockedBox<[LlmCallLane]>([])
        gate.onTimeout = { lane, _ in timeouts.mutate { $0.append(lane) } }

        var tickets: [LlmGateTicket] = []
        for kind in [UsageKind.consolidation, UsageKind.agent, UsageKind.chat] {
            let result = await gate.acquire(kind: kind, timeoutMillis: 0)
            if let ticket = result.ticket { tickets.append(ticket) }
        }

        let result = await gate.acquire(kind: UsageKind.extraction, timeoutMillis: 30)
        guard case .timedOut(let lane, let waited) = result else {
            return XCTFail("后台名额满了应当超时")
        }
        XCTAssertEqual(lane, .background)
        XCTAssertEqual(waited, 30)
        XCTAssertEqual(timeouts.get(), [.background])
        // 超时不能把名额吃掉：还完 3 个之后必须还能再拿。
        for ticket in tickets { gate.release(ticket) }
        XCTAssertEqual(gate.snapshot().global, 0)
        let again = await gate.acquire(kind: UsageKind.chat, timeoutMillis: 0)
        XCTAssertNotNil(again.ticket)
        if let ticket = again.ticket { gate.release(ticket) }
    }

    /// 用途字符串 → 道：只有聊天走聊天道，其余（含不认识的值）都算后台。
    func testLaneMapping() {
        XCTAssertEqual(LlmCallLane(kind: UsageKind.chat), .chat)
        XCTAssertEqual(LlmCallLane(kind: " chat "), .chat)
        XCTAssertEqual(LlmCallLane(kind: UsageKind.extraction), .background)
        XCTAssertEqual(LlmCallLane(kind: UsageKind.agent), .background)
        XCTAssertEqual(LlmCallLane(kind: nil), .background)
        XCTAssertEqual(LlmCallLane(kind: "WHAT"), .background)
    }

    // MARK: - 取消（0.10.2 审计 P1-11）

    /// 等待者能被 `Task.cancel()` 唤醒，而且**名额不泄漏**。
    ///
    /// 0.10.1 的 `acquire` 用裸 `withCheckedContinuation`：用户点「停止」之后，
    /// 那次调用还要在队列里等到 20/60 秒超时才会被摘下（期间甚至可能拿到名额、
    /// 真的发出一次用户已经不想要的请求）。
    func testCancelledWaiterIsRemovedAndDoesNotLeakSlots() async throws {
        let gate = LlmConcurrencyGate()
        var tickets: [LlmGateTicket] = []
        for kind in [UsageKind.consolidation, UsageKind.agent, UsageKind.chat] {
            if let ticket = await gate.acquire(kind: kind, timeoutMillis: 0).ticket { tickets.append(ticket) }
        }
        XCTAssertEqual(tickets.count, 3)
        XCTAssertEqual(gate.snapshot().waiting, 0)

        let cancelled = expectation(description: "被取消的等待者立刻返回")
        let resultBox = LockedBox<LlmGateResult?>(nil)
        let task = Task {
            let result = await gate.acquire(kind: UsageKind.chat, timeoutMillis: 10_000)
            resultBox.set(result)
            cancelled.fulfill()
        }
        // 让它先登记成等待者。
        try? await Task.sleep(nanoseconds: 30_000_000)
        XCTAssertEqual(gate.snapshot().waiting, 1, "这时它应该还在排队")

        task.cancel()
        await fulfillment(of: [cancelled], timeout: 5)
        XCTAssertTrue(resultBox.get()?.isCancelled == true, "取消要返回 .cancelled，而不是等到超时")
        XCTAssertEqual(gate.snapshot().waiting, 0, "取消后要从队列里摘掉")
        XCTAssertEqual(gate.snapshot().global, 3, "取消不该动已占用的名额")

        // 名额一个都没泄漏：全部还回去之后还能再拿 3 个。
        for ticket in tickets { gate.release(ticket) }
        XCTAssertEqual(gate.snapshot().global, 0)
        var again: [LlmGateTicket] = []
        for kind in [UsageKind.consolidation, UsageKind.agent, UsageKind.chat] {
            if let ticket = await gate.acquire(kind: kind, timeoutMillis: 0).ticket { again.append(ticket) }
        }
        XCTAssertEqual(again.count, 3, "取消不能把名额吃掉")
        for ticket in again { gate.release(ticket) }
    }

    /// 在「还没开始等」之前就取消：也不能卡住（这条走的是登记时的 `Task.isCancelled` 兜底）。
    func testAcquireOnAlreadyCancelledTaskReturnsImmediately() async throws {
        let gate = LlmConcurrencyGate()
        let tickets = [
            await gate.acquire(kind: UsageKind.consolidation),
            await gate.acquire(kind: UsageKind.agent),
            await gate.acquire(kind: UsageKind.chat)
        ].compactMap { $0.ticket }

        let resultBox = LockedBox<LlmGateResult?>(nil)
        let task = Task {
            // 先取消自己，再进 acquire。
            withUnsafeCurrentTask { $0?.cancel() }
            resultBox.set(await gate.acquire(kind: UsageKind.chat, timeoutMillis: 10_000))
        }
        _ = await task.value
        XCTAssertEqual(resultBox.get()?.isCancelled, true)
        XCTAssertEqual(gate.snapshot().waiting, 0)
        XCTAssertEqual(gate.snapshot().global, 3)
        for ticket in tickets { gate.release(ticket) }
    }

    /// 取消**不能**触发 `onTimeout` 回调（那条回调的语义是「排队超时」）。
    func testCancellationDoesNotFireTimeoutCallback() async throws {
        let gate = LlmConcurrencyGate()
        let timeouts = LockedBox<[LlmCallLane]>([])
        gate.onTimeout = { lane, _ in timeouts.mutate { $0.append(lane) } }
        let tickets = [
            await gate.acquire(kind: UsageKind.consolidation),
            await gate.acquire(kind: UsageKind.agent),
            await gate.acquire(kind: UsageKind.chat)
        ].compactMap { $0.ticket }

        let done = expectation(description: "取消完成")
        let task = Task {
            _ = await gate.acquire(kind: UsageKind.extraction, timeoutMillis: 10_000)
            done.fulfill()
        }
        try? await Task.sleep(nanoseconds: 30_000_000)
        task.cancel()
        await fulfillment(of: [done], timeout: 5)
        XCTAssertTrue(timeouts.get().isEmpty, "取消不是超时")
        for ticket in tickets { gate.release(ticket) }
    }

    /// `withSlot` 拿不到名额时返回 nil，拿到时正常返回结果并还名额。
    func testWithSlotReturnsNilOnTimeoutAndReleasesOnSuccess() async throws {
        let gate = LlmConcurrencyGate()
        var tickets: [LlmGateTicket] = []
        for kind in [UsageKind.consolidation, UsageKind.agent, UsageKind.chat] {
            let result = await gate.acquire(kind: kind, timeoutMillis: 0)
            if let ticket = result.ticket { tickets.append(ticket) }
        }
        let blocked = await gate.withSlot(kind: UsageKind.consolidation, timeoutMillis: 0) { 1 }
        XCTAssertNil(blocked)
        for ticket in tickets { gate.release(ticket) }

        let ok = await gate.withSlot(kind: UsageKind.chat, timeoutMillis: 1_000) { 42 }
        XCTAssertEqual(ok, 42)
        XCTAssertEqual(gate.snapshot().global, 0, "withSlot 结束必须还名额")
    }
}

// MARK: - 小工具

/// 记录「同时有几个在跑」。
private actor ConcurrencyCounter {
    private(set) var current = 0
    private(set) var peak = 0

    func enter() async {
        current += 1
        peak = max(peak, current)
    }

    func leave() async {
        current = max(current - 1, 0)
    }
}

/// 简单的加锁盒子（并发测试里要跨线程读写一个值）。
private final class LockedBox<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) { self.value = value }

    func get() -> Value {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func set(_ newValue: Value) {
        lock.lock()
        value = newValue
        lock.unlock()
    }

    func mutate(_ body: (inout Value) -> Void) {
        lock.lock()
        body(&value)
        lock.unlock()
    }
}
