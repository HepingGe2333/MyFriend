import Foundation
import XCTest
@testable import MyFriendKit

/// BGTask 的完成上报语义（0.10.2 审计 P1-4）。
///
/// 修的是两件在真机上才看得见的错：
/// 1. 「无事可做」原来报 `success: false`（心跳绝大多数拍本来就什么都不做），
///    系统把这个当任务失败，几次之后就把后台刷新饿死；
/// 2. 时间片到期时 `expirationHandler` 只 cancel 不上报，如果这次活卡在门控等待里，
///    整个时间片都等不到 `setTaskCompleted` → 进程被系统杀掉，同样影响后续调度。
///
/// `BGTask` 本身不能在单元测试里造，所以「只上报一次、谁先谁赢」这段状态机抽到了 Kit。
final class BackgroundTaskCompletionTests: XCTestCase {

    private final class Recorder: @unchecked Sendable {
        private let lock = NSLock()
        private var values: [Bool] = []

        func record(_ value: Bool) {
            lock.lock()
            values.append(value)
            lock.unlock()
        }

        var all: [Bool] {
            lock.lock()
            defer { lock.unlock() }
            return values
        }
    }

    func testNoWorkStillReportsSuccess() {
        let recorder = Recorder()
        let reporter = BackgroundTaskCompletionReporter { recorder.record($0) }
        // 「跑完了但什么都没做」：这才是心跳的常态。
        XCTAssertTrue(reporter.complete())
        XCTAssertEqual(recorder.all, [true], "无事可做也要按成功上报，否则系统会削弱后续调度")
        XCTAssertTrue(reporter.hasReported)
    }

    func testExpirationReportsFailureExactlyOnce() {
        let recorder = Recorder()
        let reporter = BackgroundTaskCompletionReporter { recorder.record($0) }
        XCTAssertTrue(reporter.expire())
        XCTAssertEqual(recorder.all, [false], "到期取消要自己上报失败（不然进程会被杀）")
        XCTAssertFalse(reporter.complete(), "已经上报过就不能再报一次")
        XCTAssertEqual(recorder.all, [false])
    }

    func testCompletionWinsWhenWorkFinishesBeforeExpiration() {
        let recorder = Recorder()
        let reporter = BackgroundTaskCompletionReporter { recorder.record($0) }
        XCTAssertTrue(reporter.complete())
        XCTAssertFalse(reporter.expire())
        XCTAssertEqual(recorder.all, [true])
    }

    /// 到期与收尾同时发生（真实情况：`expirationHandler` 在后台线程跑，`await work.value` 刚返回）。
    func testConcurrentReportsResolveToOne() {
        for _ in 0..<20 {
            let recorder = Recorder()
            let reporter = BackgroundTaskCompletionReporter { recorder.record($0) }
            DispatchQueue.concurrentPerform(iterations: 8) { index in
                if index % 2 == 0 {
                    _ = reporter.complete()
                } else {
                    _ = reporter.expire()
                }
            }
            XCTAssertEqual(recorder.all.count, 1, "`setTaskCompleted` 在真机上多调一次会崩/被记错")
        }
    }

    /// 装配层守卫：两个 handle 方法都必须经过 reporter，且不再用 `acted && !isCancelled` 当 success。
    func testBackgroundSchedulerUsesReporter() throws {
        var current = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var path: URL?
        for _ in 0..<12 {
            let candidate = current.appendingPathComponent("MyFriend/App/BackgroundScheduler.swift")
            if FileManager.default.fileExists(atPath: candidate.path) { path = candidate; break }
            current = current.deletingLastPathComponent()
        }
        let source = try String(contentsOf: try XCTUnwrap(path), encoding: .utf8)
        XCTAssertEqual(
            source.components(separatedBy: "BackgroundTaskCompletionReporter").count - 1, 2,
            "refresh 与 processing 都要用 reporter"
        )
        XCTAssertTrue(source.contains("reporter.expire()"), "expirationHandler 里要立刻上报失败")
        XCTAssertFalse(
            source.contains("success: acted && !work.isCancelled"),
            "「无事可做」不能再被当成任务失败"
        )
        XCTAssertFalse(
            source.contains("success: consolidated && !work.isCancelled"),
            "「没整理」也不能再被当成任务失败"
        )
    }
}
