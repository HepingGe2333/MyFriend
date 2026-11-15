import Foundation
import XCTest
@testable import MyFriendKit

/// 媒体删除 TOCTOU（0.12 遗留缺陷 2，iOS 侧）。
///
/// 附件按内容哈希命名，`saveImage` 与 `deleteIfUnreferenced` 会操作同一个文件名。
/// 修法是把「引用查询 + 删除」与「写文件 + 返回引用」放进同一把进程级锁
/// （`MediaStorage.mediaFileLock`）。这里用并发 Task + `XCTestExpectation` 钉住：
/// - 删除方持有锁时，并发的写方必须被挡住；
/// - 源码契约：`saveImage` 与 `deleteIfUnreferenced` 都必须走 `withMediaFileLock`。
final class MediaStorageRaceTests: XCTestCase {

    private var directory: URL!
    private var storage: MediaStorage!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("mf-media-race-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        storage = MediaStorage(directory: directory)
    }

    override func tearDownWithError() throws {
        if let directory { try? FileManager.default.removeItem(at: directory) }
        storage = nil
        directory = nil
        try super.tearDownWithError()
    }

    func testConcurrentWriterWaitsUntilTheGuardedDeleteReleasesTheLock() {
        let storage = self.storage!
        let holderEntered = expectation(description: "删除方持有媒体锁")
        let holderDone = expectation(description: "删除方释放媒体锁")
        let writerAttempted = expectation(description: "写方开始尝试")
        let writerEntered = expectation(description: "写方拿到媒体锁")
        let writerEnteredFlag = LockedFlag()
        let releaseHolder = DispatchSemaphore(value: 0)

        let holder = Task.detached {
            storage.withMediaFileLock {
                holderEntered.fulfill()
                // 模拟「引用查询 / 删除」还在临界区里。
                _ = releaseHolder.wait(timeout: .now() + 5)
            }
            holderDone.fulfill()
        }

        wait(for: [holderEntered], timeout: 2)

        let writer = Task.detached {
            writerAttempted.fulfill()
            storage.withMediaFileLock {
                writerEnteredFlag.set()
                writerEntered.fulfill()
            }
        }

        wait(for: [writerAttempted], timeout: 2)
        // 删除方没释放之前，写方不能进入临界区 —— 这正是旧实现里「查完无引用 → 写回 → 被删」的窗口。
        Thread.sleep(forTimeInterval: 0.25)
        XCTAssertFalse(writerEnteredFlag.value, "写方不该在删除方持锁时进入临界区")

        releaseHolder.signal()
        wait(for: [holderDone, writerEntered], timeout: 3)
        _ = holder
        _ = writer
    }

    func testSaveImageAndGuardedDeleteUseTheSameProcessLock() throws {
        let source = try Self.mediaStorageSource()
        XCTAssertTrue(
            source.contains("private static let mediaFileLock = NSRecursiveLock()"),
            "锁必须是 MediaStorage 的进程级静态锁"
        )
        XCTAssertTrue(source.contains("func withMediaFileLock<T>("))

        let saveBody = try XCTUnwrap(Self.slice(
            source,
            from: "private func saveImage(source:",
            to: "// MARK: 读"
        ))
        XCTAssertTrue(
            saveBody.contains("return try withMediaFileLock {"),
            "saveImage 的「复用/写文件 + 返回引用」必须在锁内"
        )

        let deleteBody = try XCTUnwrap(Self.slice(
            source,
            from: "public func deleteIfUnreferenced(_ uriString: String?, references: MediaReferences) -> Bool {",
            to: "/// 单条附件的便捷入口"
        ))
        XCTAssertTrue(
            deleteBody.contains("withMediaFileLock {"),
            "deleteIfUnreferenced 的「查引用 + 删文件」必须在锁内"
        )
    }

    // MARK: - 工具

    private static func mediaStorageSource() throws -> String {
        var current = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = current
                .appendingPathComponent("Sources/MyFriendKit/Support/MediaStorage.swift")
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try String(contentsOf: candidate, encoding: .utf8)
            }
            current.deleteLastPathComponent()
        }
        XCTFail("找不到 ios/MyFriendKit/Sources/MyFriendKit/Support/MediaStorage.swift")
        return ""
    }

    private static func slice(_ source: String, from start: String, to end: String) -> String? {
        guard let startRange = source.range(of: start),
              let endRange = source.range(of: end, range: startRange.upperBound..<source.endIndex) else {
            return nil
        }
        return String(source[startRange.lowerBound..<endRange.lowerBound])
    }

    /// 简单的线程安全布尔值（避免测试里的标志位数据竞争）。
    private final class LockedFlag {
        private let lock = NSLock()
        private var stored = false

        var value: Bool {
            lock.lock()
            defer { lock.unlock() }
            return stored
        }

        func set() {
            lock.lock()
            stored = true
            lock.unlock()
        }
    }
}
