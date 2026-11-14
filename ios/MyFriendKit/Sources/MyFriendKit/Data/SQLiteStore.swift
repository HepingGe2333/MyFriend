import Foundation
import SQLite3

// ---------------------------------------------------------------------------
// 系统 SQLite3 的薄封装。
//
// 为什么不用 GRDB / SQLite.swift：`docs/IOS-PARITY.md` 的硬约束是「零第三方依赖」——
// 这套代码要长期能编译，而不是三个月后卡在依赖版本上。系统自带的 SQLite3 足够，
// 代价是要自己处理 C 指针与错误码，所以这个文件把那些细节收在一处：
// 上层（Schema/Stores）只写 SQL 和实体，不碰 `OpaquePointer`。
//
// 设计取舍：
// - **同步 API**：所有调用都是短查询，业务层自己决定放到哪个队列；这里不引入 async，
//   免得给后面的仓储层强加一个并发模型。
// - **语句用完即弃**：`SQLiteStatement` 在 deinit 里 `sqlite3_finalize`，不缓存预处理语句。
//   备份导入/消息列表都是低频操作，缓存带来的复杂度不值得。
// - **错误带 SQL**：出错时把 SQL 与 `sqlite3_errmsg` 一起抛出，问题现场不会丢。
// ---------------------------------------------------------------------------

public enum SQLiteError: Error, LocalizedError, CustomStringConvertible {
    case openFailed(path: String, message: String)
    case executeFailed(sql: String, code: Int32, message: String)
    case prepareFailed(sql: String, code: Int32, message: String)
    case bindFailed(index: Int32, code: Int32, message: String)
    case bindCountMismatch(expected: Int32, actual: Int)
    case stepFailed(sql: String, code: Int32, message: String)
    case columnNotFound(name: String)
    case closed

    public var errorDescription: String? { description }

    public var description: String {
        switch self {
        case .openFailed(let path, let message):
            return "打不开数据库（\(path)）：\(message)"
        case .executeFailed(let sql, let code, let message):
            return "SQL 执行失败（code \(code)）：\(message)\nSQL: \(sql)"
        case .prepareFailed(let sql, let code, let message):
            return "SQL 编译失败（code \(code)）：\(message)\nSQL: \(sql)"
        case .bindFailed(let index, let code, let message):
            return "绑定第 \(index) 个参数失败（code \(code)）：\(message)"
        case .bindCountMismatch(let expected, let actual):
            return "绑定的参数个数不对：SQL 需要 \(expected) 个，实际给了 \(actual) 个"
        case .stepFailed(let sql, let code, let message):
            return "SQL 执行失败（code \(code)）：\(message)\nSQL: \(sql)"
        case .columnNotFound(let name):
            return "结果里没有这一列：\(name)"
        case .closed:
            return "数据库已经关闭"
        }
    }
}

/// SQL 参数 / 列值的统一表示。
///
/// 单独造一个 enum 而不是用 `Any`，是为了让「实体 → 数据库」的绑定表在编译期就能查错：
/// 少写一个参数（个数不对）会在 `bind` 时立刻报错，而不是静默错位。
public enum SQLiteValue: Equatable, Sendable {
    case null
    case integer(Int64)
    case real(Double)
    case text(String)
    case blob(Data)

    public static func int<T: BinaryInteger>(_ value: T) -> SQLiteValue {
        .integer(Int64(value))
    }

    public static func optionalInt<T: BinaryInteger>(_ value: T?) -> SQLiteValue {
        guard let value else { return .null }
        return .integer(Int64(value))
    }

    public static func optionalReal(_ value: Double?) -> SQLiteValue {
        guard let value else { return .null }
        return .real(value)
    }

    /// SQLite 没有布尔类型，按 Room 的老规矩存 0/1。
    public static func bool(_ value: Bool) -> SQLiteValue { .integer(value ? 1 : 0) }

    public static func optionalText(_ value: String?) -> SQLiteValue {
        guard let value else { return .null }
        return .text(value)
    }

    public static func optionalBlob(_ value: Data?) -> SQLiteValue {
        guard let value else { return .null }
        return .blob(value)
    }
}

/// 与 C 的 `SQLITE_TRANSIENT` 等价：告诉 SQLite 立刻拷贝字符串/二进制，
/// 否则我们传进去的临时缓冲区会在 `step()` 前被释放（典型的悬空指针坑）。
private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

// ---------------------------------------------------------------------------
// 数据库
// ---------------------------------------------------------------------------

public final class SQLiteDatabase {
    public let path: String
    private var handle: OpaquePointer?
    /// 事务嵌套深度：见 `inTransaction`。
    ///
    /// **只在持有 `transactionLock` 时读写**。同一个进程里只有一个连接，而
    /// 聊天落库 / 心跳 / 抽记忆 / 压缩 / 备份导入会从不同线程同时进来，
    /// 裸 `Int` 会读到撕裂值（0.10.2 审计 P0-1：两个线程同时读到 0 → 都 BEGIN →
    /// 后者拿到 "cannot start a transaction within a transaction"；或者 T2 读到 >0
    /// 直接执行 body，把写并进 T1 的事务里，T1 一 ROLLBACK 就静默丢失）。
    private var transactionDepth = 0
    /// 连接级串行锁：`inTransaction` 整段持有它，于是「同一时刻只有一个事务」成立，
    /// 深度也就成了持锁线程的嵌套层数。
    ///
    /// 用递归锁而不是普通锁：嵌套事务（备份导入里仓储自己又开事务）必须能重入，
    /// 现有调用方依赖「内层并入外层」这条语义。
    private let transactionLock = NSRecursiveLock()

    /// - Parameter path: 文件路径，或 `":memory:"`（测试用）。
    public init(path: String) throws {
        self.path = path
        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        let status = sqlite3_open_v2(path, &handle, flags, nil)
        guard status == SQLITE_OK, let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "sqlite3_open_v2 返回 \(status)"
            if let handle { sqlite3_close(handle) }
            throw SQLiteError.openFailed(path: path, message: message)
        }
        self.handle = handle
        // 多线程（心跳后台写、界面读）撞锁时等一会儿，而不是立刻 SQLITE_BUSY。
        sqlite3_busy_timeout(handle, 5_000)
    }

    public static func inMemory() throws -> SQLiteDatabase {
        try SQLiteDatabase(path: ":memory:")
    }

    deinit {
        close()
    }

    public func close() {
        guard let handle else { return }
        sqlite3_close(handle)
        self.handle = nil
    }

    private func requireHandle() throws -> OpaquePointer {
        guard let handle else { throw SQLiteError.closed }
        return handle
    }

    /// 执行一段（可含多条语句的）SQL。DDL 与 PRAGMA 走这里。
    public func execute(_ sql: String) throws {
        transactionLock.lock()
        defer { transactionLock.unlock() }
        let handle = try requireHandle()
        var errorPointer: UnsafeMutablePointer<CChar>?
        let status = sqlite3_exec(handle, sql, nil, nil, &errorPointer)
        guard status == SQLITE_OK else {
            let message = errorPointer.map { String(cString: $0) } ?? String(cString: sqlite3_errmsg(handle))
            if let errorPointer { sqlite3_free(errorPointer) }
            throw SQLiteError.executeFailed(sql: sql, code: status, message: message)
        }
        if let errorPointer { sqlite3_free(errorPointer) }
    }

    /// 在连接锁里跑一段（同步）活。
    ///
    /// 为什么单条 autocommit 写也要过这把锁：SQLite 的事务状态是**连接级**的——
    /// 别的线程正在 `BEGIN IMMEDIATE … ROLLBACK` 时，这里一条裸 `INSERT` 会被并进
    /// 那个事务，一起回滚。锁住它，写就不会「搭别人的车」。
    ///
    /// 递归锁：已经在事务里（同一线程）再进来不会死锁。
    public func withConnectionLock<T>(_ body: () throws -> T) rethrows -> T {
        transactionLock.lock()
        defer { transactionLock.unlock() }
        return try body()
    }

    /// 执行一条写入语句并返回受影响行数（定向 UPDATE 的乐观检查靠它）。
    @discardableResult
    public func executeUpdate(_ sql: String, _ arguments: [SQLiteValue] = []) throws -> Int {
        transactionLock.lock()
        defer { transactionLock.unlock() }
        let handle = try requireHandle()
        let statement = try prepare(sql)
        try statement.bind(arguments)
        _ = try statement.step()
        return Int(sqlite3_changes(handle))
    }

    /// 预备语句。**不加锁**：语句的执行（`step`）发生在调用方，语句生命周期可能跨越
    /// 多个同步片段；调用方要保证「写不落进别人的事务」时用 `withConnectionLock` /
    /// `inTransaction` / `executeUpdate`。
    public func prepare(_ sql: String) throws -> SQLiteStatement {
        try SQLiteStatement(database: try requireHandle(), sql: sql)
    }

    /// 事务：`body` 抛错就 ROLLBACK，并把错误继续往上抛。
    ///
    /// 嵌套（同一个线程里再进一次）**退化为外层事务**：内层不 BEGIN/COMMIT，
    /// 由最外层统一提交或回滚——批量导入里「仓储自己开事务」和「仓储被包进大事务」
    /// 两种写法都能用。
    ///
    /// 并发（不同线程/任务）：`transactionLock` 把整段事务串起来，所以
    /// 1. 不会出现两个 `BEGIN`（"cannot start a transaction within a transaction"）；
    /// 2. 后来者不会把写并进别人的事务里（那会在对方回滚时静默丢失）。
    /// 异常路径（`BEGIN` 失败 / body 抛错 / COMMIT 失败）都会解锁并清深度。
    @discardableResult
    public func inTransaction<T>(_ body: () throws -> T) throws -> T {
        transactionLock.lock()
        defer { transactionLock.unlock() }
        // 能走到这里说明锁在我们手上：深度只可能是本线程的嵌套层数。
        if transactionDepth > 0 {
            transactionDepth += 1
            defer { transactionDepth -= 1 }
            return try body()
        }
        try execute("BEGIN IMMEDIATE")
        transactionDepth = 1
        // 无论正常/异常结束都回到「没有事务」：`BEGIN` 之后深度只能靠这里复位。
        defer { transactionDepth = 0 }
        do {
            let result = try body()
            try execute("COMMIT")
            return result
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    public func scalarInt64(_ sql: String, _ arguments: [SQLiteValue] = []) throws -> Int64 {
        transactionLock.lock()
        defer { transactionLock.unlock() }
        let statement = try prepare(sql)
        try statement.bind(arguments)
        guard try statement.step() else { return 0 }
        return statement.int64(at: 0)
    }

    public func userVersion() throws -> Int32 {
        Int32(try scalarInt64("PRAGMA user_version"))
    }

    public func setUserVersion(_ version: Int32) throws {
        try execute("PRAGMA user_version = \(version)")
    }

    /// 当前库里的表名（测试用来核对建表结果；业务代码不该依赖它）。
    public func tableNames() throws -> [String] {
        let statement = try prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
        var names: [String] = []
        while try statement.step() {
            names.append(statement.text(at: 0) ?? "")
        }
        return names
    }
}

// ---------------------------------------------------------------------------
// 语句
// ---------------------------------------------------------------------------

public final class SQLiteStatement {
    private let databaseHandle: OpaquePointer
    private let handle: OpaquePointer
    private let sql: String
    private var columnIndexCache: [String: Int32]?

    init(database: OpaquePointer, sql: String) throws {
        self.databaseHandle = database
        self.sql = sql
        var statement: OpaquePointer?
        let status = sqlite3_prepare_v2(database, sql, -1, &statement, nil)
        guard status == SQLITE_OK, let statement else {
            let message = String(cString: sqlite3_errmsg(database))
            throw SQLiteError.prepareFailed(sql: sql, code: status, message: message)
        }
        self.handle = statement
    }

    deinit {
        sqlite3_finalize(handle)
    }

    private func errorMessage() -> String {
        String(cString: sqlite3_errmsg(databaseHandle))
    }

    /// 按位置绑定参数（从 1 开始，与 SQLite 一致）。
    public func bind(_ values: [SQLiteValue]) throws {
        let expected = sqlite3_bind_parameter_count(handle)
        guard values.count == Int(expected) else {
            throw SQLiteError.bindCountMismatch(expected: expected, actual: values.count)
        }
        for (offset, value) in values.enumerated() {
            try bind(value, at: Int32(offset + 1))
        }
    }

    public func bind(_ value: SQLiteValue, at index: Int32) throws {
        let status: Int32
        switch value {
        case .null:
            status = sqlite3_bind_null(handle, index)
        case .integer(let number):
            status = sqlite3_bind_int64(handle, index, number)
        case .real(let number):
            status = sqlite3_bind_double(handle, index, number)
        case .text(let text):
            status = sqlite3_bind_text(handle, index, text, -1, sqliteTransient)
        case .blob(let data):
            if data.isEmpty {
                // 空 Data 的 baseAddress 是 nil，sqlite3_bind_blob 会当成 NULL；零长 blob 要单独走。
                status = sqlite3_bind_zeroblob(handle, index, 0)
            } else {
                status = data.withUnsafeBytes { buffer in
                    sqlite3_bind_blob(handle, index, buffer.baseAddress, Int32(buffer.count), sqliteTransient)
                }
            }
        }
        guard status == SQLITE_OK else {
            throw SQLiteError.bindFailed(index: index, code: status, message: errorMessage())
        }
    }

    /// 有下一行返回 true；语句执行完返回 false。
    public func step() throws -> Bool {
        let status = sqlite3_step(handle)
        switch status {
        case SQLITE_ROW:
            return true
        case SQLITE_DONE:
            return false
        default:
            throw SQLiteError.stepFailed(sql: sql, code: status, message: errorMessage())
        }
    }

    public func reset() {
        sqlite3_reset(handle)
        sqlite3_clear_bindings(handle)
    }

    // MARK: 列

    public var columnCount: Int32 { sqlite3_column_count(handle) }

    public func columnName(at index: Int32) -> String {
        sqlite3_column_name(handle, index).map { String(cString: $0) } ?? ""
    }

    public func columnIndex(named name: String) throws -> Int32 {
        if columnIndexCache == nil {
            var map: [String: Int32] = [:]
            for index in 0..<columnCount {
                map[columnName(at: index)] = index
            }
            columnIndexCache = map
        }
        guard let index = columnIndexCache?[name] else {
            throw SQLiteError.columnNotFound(name: name)
        }
        return index
    }

    public func isNull(at index: Int32) -> Bool {
        sqlite3_column_type(handle, index) == SQLITE_NULL
    }

    public func text(at index: Int32) -> String? {
        guard !isNull(at: index), let pointer = sqlite3_column_text(handle, index) else { return nil }
        return String(cString: UnsafeRawPointer(pointer).assumingMemoryBound(to: CChar.self))
    }

    public func int64(at index: Int32) -> Int64 {
        sqlite3_column_int64(handle, index)
    }

    public func double(at index: Int32) -> Double {
        sqlite3_column_double(handle, index)
    }

    public func blob(at index: Int32) -> Data? {
        guard !isNull(at: index) else { return nil }
        let count = Int(sqlite3_column_bytes(handle, index))
        guard count > 0 else { return Data() }
        guard let pointer = sqlite3_column_blob(handle, index) else { return Data() }
        return Data(bytes: pointer, count: count)
    }
}

// ---------------------------------------------------------------------------
// 一行结果：按列名取值，NULL → nil（或实体的默认值）
// ---------------------------------------------------------------------------

public struct SQLiteRow {
    let statement: SQLiteStatement

    public init(statement: SQLiteStatement) {
        self.statement = statement
    }

    private func index(_ name: String) throws -> Int32 {
        try statement.columnIndex(named: name)
    }

    public func optionalText(_ name: String) throws -> String? {
        statement.text(at: try index(name))
    }

    public func text(_ name: String, _ fallback: String = "") throws -> String {
        try optionalText(name) ?? fallback
    }

    public func optionalInt64(_ name: String) throws -> Int64? {
        let index = try index(name)
        return statement.isNull(at: index) ? nil : statement.int64(at: index)
    }

    public func int64(_ name: String, _ fallback: Int64 = 0) throws -> Int64 {
        try optionalInt64(name) ?? fallback
    }

    public func optionalInt(_ name: String) throws -> Int? {
        try optionalInt64(name).map { Int($0) }
    }

    public func int(_ name: String, _ fallback: Int = 0) throws -> Int {
        try optionalInt(name) ?? fallback
    }

    public func optionalDouble(_ name: String) throws -> Double? {
        let index = try index(name)
        return statement.isNull(at: index) ? nil : statement.double(at: index)
    }

    public func double(_ name: String, _ fallback: Double = 0) throws -> Double {
        try optionalDouble(name) ?? fallback
    }

    public func bool(_ name: String, _ fallback: Bool = false) throws -> Bool {
        guard let value = try optionalInt64(name) else { return fallback }
        return value != 0
    }

    public func optionalBlob(_ name: String) throws -> Data? {
        statement.blob(at: try index(name))
    }

    public func blob(_ name: String) throws -> Data {
        try optionalBlob(name) ?? Data()
    }
}
