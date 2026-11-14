import Foundation
import XCTest
@testable import MyFriendKit

/// 事务边界在**并发**下还成不成立（0.10.2 审计 P0-1）。
///
/// 为什么值得单独一个文件：App 里同一个 `SQLiteDatabase`（一个进程一个连接）被
/// 主线程读、`ChatService` 的生成任务写、心跳/自主循环写、回复后并发的抽记忆与压缩写、
/// 备份导入的 `Task.detached` 写。而 SQLite 的事务状态是**连接级**的：
/// 只要有两个线程同时进 `inTransaction`，就会出现
/// 1. `cannot start a transaction within a transaction`（两个 BEGIN）；
/// 2. 后到者的写被并进先到者的事务里，先到者一 ROLLBACK 就把「已经返回成功」的写静默吞掉。
///
/// 修法是把 `inTransaction` 整段放到一把连接级递归锁里（嵌套退化为外层事务这语义保留）。
/// 这里钉住的就是那四条语义：不抛错、不丢写、只回滚自己那段、同一时刻只有一个事务在跑。
final class SQLiteTransactionConcurrencyTests: XCTestCase {

    private enum Boom: Error { case boom }

    private var directory: URL!
    private var database: SQLiteDatabase!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("mf-txn-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        database = try SQLiteDatabase(path: directory.appendingPathComponent("db.sqlite").path)
        try database.execute("PRAGMA journal_mode = WAL")
        try database.execute("CREATE TABLE IF NOT EXISTS scratch (id TEXT PRIMARY KEY, owner TEXT NOT NULL)")
    }

    override func tearDownWithError() throws {
        database.close()
        database = nil
        try? FileManager.default.removeItem(at: directory)
        try super.tearDownWithError()
    }

    // MARK: - 小工具

    private func insert(_ id: String, owner: String) throws {
        try database.executeUpdate(
            "INSERT OR REPLACE INTO scratch (id, owner) VALUES (?, ?)",
            [.text(id), .text(owner)]
        )
    }

    private func rowIds() throws -> Set<String> {
        let statement = try database.prepare("SELECT id FROM scratch")
        var ids = Set<String>()
        while try statement.step() {
            ids.insert(statement.text(at: 0) ?? "")
        }
        return ids
    }

    private func count() throws -> Int {
        Int(try database.scalarInt64("SELECT COUNT(*) FROM scratch"))
    }

    /// 一个能跨线程收集错误的盒子。
    private final class FailureBox: @unchecked Sendable {
        private let lock = NSLock()
        private var messages: [String] = []

        func record(_ message: String) {
            lock.lock()
            messages.append(message)
            lock.unlock()
        }

        var all: [String] {
            lock.lock()
            defer { lock.unlock() }
            return messages
        }
    }

    // MARK: - 并发 + 嵌套：不抛错、不丢写、互斥

    func testConcurrentNestedTransactionsNeverThrowAndNeverLoseWrites() throws {
        let db = try XCTUnwrap(database)
        let workers = 8
        let rounds = 12
        let failures = FailureBox()
        // 同时有几个事务体在跑：串行化之后峰值必须是 1。
        let active = NSLock()
        var activeNow = 0
        var peak = 0

        DispatchQueue.concurrentPerform(iterations: workers) { worker in
            for round in 0..<rounds {
                do {
                    try db.inTransaction {
                        active.lock()
                        activeNow += 1
                        peak = max(peak, activeNow)
                        active.unlock()
                        defer {
                            active.lock()
                            activeNow -= 1
                            active.unlock()
                        }
                        try self.insert("w\(worker)-r\(round)-outer", owner: "\(worker)")
                        // 嵌套事务：必须并入外层（不 BEGIN / 不 COMMIT），也不能抛
                        // "cannot start a transaction within a transaction"。
                        try db.inTransaction {
                            try self.insert("w\(worker)-r\(round)-inner", owner: "\(worker)")
                        }
                    }
                } catch {
                    failures.record("w\(worker) r\(round): \(error)")
                }
            }
        }

        XCTAssertTrue(failures.all.isEmpty, "并发事务不该抛错：\n" + failures.all.joined(separator: "\n"))
        XCTAssertEqual(try count(), workers * rounds * 2, "并发事务不能丢写")
        XCTAssertEqual(peak, 1, "串行化实现下，同一时刻只该有一个事务体在跑")
    }

    // MARK: - 回滚只回滚自己那段

    func testRollbackDoesNotSwallowAnotherThreadsWrite() throws {
        let db = try XCTUnwrap(database)
        let insideTransaction = DispatchSemaphore(value: 0)
        let abort = DispatchSemaphore(value: 0)
        let workerReady = DispatchSemaphore(value: 0)
        let group = DispatchGroup()
        let failures = FailureBox()

        // A：开一个事务、写一条，然后等 B 撞上来再回滚。
        DispatchQueue.global().async(group: group) {
            do {
                try db.inTransaction {
                    try self.insert("victim", owner: "A")
                    insideTransaction.signal()
                    abort.wait()
                    throw Boom.boom
                }
            } catch is Boom {
                // 预期：A 自己回滚。
            } catch {
                failures.record("A: \(error)")
            }
        }

        insideTransaction.wait()
        // B：在 A 的事务还开着的时候写一条（走普通 autocommit 写）。
        DispatchQueue.global().async(group: group) {
            workerReady.signal()
            do {
                try self.insert("survivor", owner: "B")
            } catch {
                failures.record("B: \(error)")
            }
        }
        workerReady.wait()
        // 给 B 一点时间真的去撞锁（修好之前它是「直接执行 body」）。
        Thread.sleep(forTimeInterval: 0.08)
        abort.signal()
        group.wait()

        XCTAssertTrue(failures.all.isEmpty, failures.all.joined(separator: "\n"))
        let ids = try rowIds()
        XCTAssertFalse(ids.contains("victim"), "A 回滚了，它自己那条不该在")
        XCTAssertTrue(ids.contains("survivor"), "B 的写已经返回成功，不能被 A 的回滚吞掉")
    }

    func testCommittedTransactionPersistsWhileAnotherRollsBack() throws {
        let db = try XCTUnwrap(database)
        let insideTransaction = DispatchSemaphore(value: 0)
        let abort = DispatchSemaphore(value: 0)
        let workerReady = DispatchSemaphore(value: 0)
        let group = DispatchGroup()
        let failures = FailureBox()

        DispatchQueue.global().async(group: group) {
            do {
                try db.inTransaction {
                    try self.insert("outer", owner: "A")
                    insideTransaction.signal()
                    abort.wait()
                    throw Boom.boom
                }
            } catch is Boom {
                // 预期
            } catch {
                failures.record("A: \(error)")
            }
        }

        insideTransaction.wait()
        DispatchQueue.global().async(group: group) {
            workerReady.signal()
            do {
                try db.inTransaction { try self.insert("committed", owner: "B") }
            } catch {
                failures.record("B: \(error)")
            }
        }
        workerReady.wait()
        Thread.sleep(forTimeInterval: 0.08)
        abort.signal()
        group.wait()

        XCTAssertTrue(failures.all.isEmpty, failures.all.joined(separator: "\n"))
        let ids = try rowIds()
        XCTAssertEqual(ids, ["committed"], "只有 B 的那段该留下")
    }

    // MARK: - 嵌套退化为外层事务（现有调用方依赖）

    func testNestedTransactionCommitsWithOuter() throws {
        try database.inTransaction {
            try insert("a", owner: "outer")
            let innerValue = try database.inTransaction { () -> Int in
                try insert("b", owner: "inner")
                return 7
            }
            XCTAssertEqual(innerValue, 7, "内层的返回值要能带出来")
            try insert("c", owner: "outer")
        }
        XCTAssertEqual(try rowIds(), ["a", "b", "c"])
    }

    func testNestedTransactionRollsBackWithOuter() throws {
        XCTAssertThrowsError(
            try database.inTransaction {
                try insert("a", owner: "outer")
                try database.inTransaction { try insert("b", owner: "inner") }
                throw Boom.boom
            }
        )
        XCTAssertEqual(try count(), 0, "内层的写并入外层，外层回滚时一起没")
    }

    func testDepthResetsAfterFailureSoNextTransactionStillBegins() throws {
        XCTAssertThrowsError(try database.inTransaction {
            try insert("failed", owner: "A")
            throw Boom.boom
        })
        // 深度没被卡在 1：下一次事务必须真的 BEGIN + COMMIT（而不是退化成一个裸 body）。
        try database.inTransaction { try insert("ok", owner: "B") }
        XCTAssertEqual(try rowIds(), ["ok"])
    }

    /// 一个事务体抛错之后，另一个线程仍然能正常开事务（异常路径解锁）。
    func testLockIsReleasedAfterThrowAcrossThreads() throws {
        let db = try XCTUnwrap(database)
        XCTAssertThrowsError(try db.inTransaction { throw Boom.boom })
        let group = DispatchGroup()
        let failures = FailureBox()
        DispatchQueue.global().async(group: group) {
            do {
                try db.inTransaction { try self.insert("after-throw", owner: "B") }
            } catch {
                failures.record("\(error)")
            }
        }
        group.wait()
        XCTAssertTrue(failures.all.isEmpty, failures.all.joined(separator: "\n"))
        XCTAssertEqual(try rowIds(), ["after-throw"])
    }
}
