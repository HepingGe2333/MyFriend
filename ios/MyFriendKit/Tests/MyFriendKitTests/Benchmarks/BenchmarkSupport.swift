import Darwin
import Foundation
import XCTest
@testable import MyFriendKit

// ---------------------------------------------------------------------------
// 基准测试（benchmark）的共用底座。
//
// 这一层只做四件事，**不含任何业务断言**：
// 1. 开关：默认跳过，只有 `RUN_BENCHMARKS=1` 才真的跑（10 万条消息 / 500 张图的
//    数据构造对常规 `swift test` 来说太贵，但它必须留在同一套测试里才有人跑）；
// 2. 计时：单调时钟（`DispatchTime.uptimeNanoseconds`），不受系统时间调整影响；
// 3. 内存：`task_info(MACH_TASK_BASIC_INFO).resident_size` +
//    `task_info(TASK_VM_INFO).phys_footprint` 的前后采样与运行中峰值采样；
// 4. 结果落盘：JSON 写 `/tmp/ios-bench-*.json`，并把关键数字 `print` 出来
//    （测试报告里能直接看到，不必翻日志文件）。
//
// 为什么内存要两种口径：
// - `resident_size` 是常驻物理页，最接近「任务管理器里看到的数字」，但会被
//   free-list / shared cache 影响；
// - `phys_footprint` 是 Jetsam（iOS 低内存杀进程）真正看的那个数，含压缩内存与
//   IOKit 之外的脏页，比 resident 更接近「会不会被杀」。
// iOS 端决策该看 `phys_footprint`（Android 侧对应 PSS/Java heap，口径只能近似对齐）。
// ---------------------------------------------------------------------------

// MARK: - 开关

enum BenchmarkGate {

    /// 只有设了这个环境变量才会真的跑基准。
    static let enableVariable = "RUN_BENCHMARKS"

    static var isEnabled: Bool {
        ProcessInfo.processInfo.environment[enableVariable] == "1"
    }

    static let skipMessage = """
        基准测试默认跳过（数据构造很贵）。要跑请设 RUN_BENCHMARKS=1，例如：
        RUN_BENCHMARKS=1 swift test --disable-sandbox --filter LargeHistoryBenchmarkTests
        """

    /// 在 `setUpWithError` 里调用：没开开关就整条用例 skip（常规套件不受影响）。
    static func require(_ file: StaticString = #filePath, line: UInt = #line) throws {
        try XCTSkipUnless(isEnabled, skipMessage, file: file, line: line)
    }
}

// MARK: - 计时

enum BenchmarkClock {

    static func nowNanos() -> UInt64 {
        DispatchTime.now().uptimeNanoseconds
    }

    static func milliseconds(since startNanos: UInt64) -> Double {
        Double(nowNanos() - startNanos) / 1_000_000.0
    }

    /// 跑一次并返回结果 + 毫秒耗时。
    static func measure<T>(_ body: () throws -> T) rethrows -> (result: T, milliseconds: Double) {
        let start = nowNanos()
        let result = try body()
        return (result, milliseconds(since: start))
    }
}

/// 一组重复测量的统计量（中位数优先，避免被一次调度抖动带偏）。
struct BenchmarkStats {

    var samplesMilliseconds: [Double]

    init(_ samples: [Double]) {
        samplesMilliseconds = samples
    }

    var count: Int { samplesMilliseconds.count }

    var minMilliseconds: Double { samplesMilliseconds.min() ?? 0 }

    var maxMilliseconds: Double { samplesMilliseconds.max() ?? 0 }

    var meanMilliseconds: Double {
        guard !samplesMilliseconds.isEmpty else { return 0 }
        return samplesMilliseconds.reduce(0, +) / Double(samplesMilliseconds.count)
    }

    /// 中位数：偶数个样本取中间两个的平均。
    var medianMilliseconds: Double {
        let sorted = samplesMilliseconds.sorted()
        guard !sorted.isEmpty else { return 0 }
        let middle = sorted.count / 2
        if sorted.count % 2 == 0 {
            return (sorted[middle - 1] + sorted[middle]) / 2
        }
        return sorted[middle]
    }

    var dictionary: [String: Any] {
        [
            "samplesMs": samplesMilliseconds.map { Self.round($0) },
            "medianMs": Self.round(medianMilliseconds),
            "minMs": Self.round(minMilliseconds),
            "maxMs": Self.round(maxMilliseconds),
            "meanMs": Self.round(meanMilliseconds),
            "count": count
        ]
    }

    static func round(_ value: Double) -> Double {
        (value * 100).rounded() / 100
    }
}

// MARK: - 进程内存

enum ProcessMemory {

    struct Snapshot: Equatable {
        /// `MACH_TASK_BASIC_INFO.resident_size`：常驻物理页。
        var residentBytes: UInt64
        /// `TASK_VM_INFO.phys_footprint`：Jetsam 口径的物理占用。
        var footprintBytes: UInt64

        var residentMegabytes: Double { Self.megabytes(residentBytes) }
        var footprintMegabytes: Double { Self.megabytes(footprintBytes) }

        var dictionary: [String: Any] {
            [
                "residentMB": Self.round(residentMegabytes),
                "footprintMB": Self.round(footprintMegabytes),
                "residentBytes": Int(residentBytes),
                "footprintBytes": Int(footprintBytes)
            ]
        }

        static func megabytes(_ bytes: UInt64) -> Double {
            Double(bytes) / 1_048_576.0
        }

        static func round(_ value: Double) -> Double {
            (value * 100).rounded() / 100
        }
    }

    /// 当前进程的两个内存数字（取失败记 0，绝不让基准本身把测试搞挂）。
    static func snapshot() -> Snapshot {
        Snapshot(residentBytes: residentBytes(), footprintBytes: physFootprintBytes())
    }

    static func residentBytes() -> UInt64 {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(
            MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size
        )
        let status = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { rebound in
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), rebound, &count)
            }
        }
        guard status == KERN_SUCCESS else { return 0 }
        return info.resident_size
    }

    static func physFootprintBytes() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(
            MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size
        )
        let status = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { rebound in
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), rebound, &count)
            }
        }
        guard status == KERN_SUCCESS else { return 0 }
        return info.phys_footprint
    }

    /// 让分配器把空闲页还给系统（等价于「喘口气再看」）。
    ///
    /// 不做这一步，前一次大对象释放后 resident 仍然挂着，基线会被高估、
    /// 增量看起来偏小 —— 这是内存基准最常见的假象。
    @discardableResult
    static func relievePressure() -> Int {
        malloc_zone_pressure_relief(nil, 0)
    }

    /// 稳定的「前后」基线：先还页，再取多次里的最小值。
    static func settled(samples: Int = 3, pauseMicroseconds: UInt32 = 25_000) -> Snapshot {
        relievePressure()
        var best: Snapshot?
        for _ in 0..<max(samples, 1) {
            usleep(pauseMicroseconds)
            let current = snapshot()
            if let previous = best {
                best = Snapshot(
                    residentBytes: min(previous.residentBytes, current.residentBytes),
                    footprintBytes: min(previous.footprintBytes, current.footprintBytes)
                )
            } else {
                best = current
            }
        }
        return best ?? snapshot()
    }

    /// 一次操作的「峰值 − 基线」。
    struct Delta: Equatable {
        var baseline: Snapshot
        var peak: Snapshot
        var after: Snapshot

        var peakResidentDeltaBytes: UInt64 {
            peak.residentBytes > baseline.residentBytes ? peak.residentBytes - baseline.residentBytes : 0
        }

        var peakFootprintDeltaBytes: UInt64 {
            peak.footprintBytes > baseline.footprintBytes ? peak.footprintBytes - baseline.footprintBytes : 0
        }

        var afterResidentDeltaBytes: Int64 {
            Int64(after.residentBytes) - Int64(baseline.residentBytes)
        }

        var afterFootprintDeltaBytes: Int64 {
            Int64(after.footprintBytes) - Int64(baseline.footprintBytes)
        }

        var dictionary: [String: Any] {
            [
                "baseline": baseline.dictionary,
                "peak": peak.dictionary,
                "after": after.dictionary,
                "peakResidentDeltaMB": Snapshot.round(Snapshot.megabytes(peakResidentDeltaBytes)),
                "peakFootprintDeltaMB": Snapshot.round(Snapshot.megabytes(peakFootprintDeltaBytes)),
                "afterResidentDeltaMB": Snapshot.round(Double(afterResidentDeltaBytes) / 1_048_576.0),
                "afterFootprintDeltaMB": Snapshot.round(Double(afterFootprintDeltaBytes) / 1_048_576.0)
            ]
        }
    }
}

/// 后台每 5ms 采一次内存，记录运行期间的峰值。
///
/// 采样线程本身会带来一点噪声（栈 + 每次几十微秒），所以 `settled` 的基线
/// 不包含它，但「峰值」包含 —— 报告里要把这 1 个线程算进去（可忽略）。
final class PeakMemorySampler {

    /// 采样间隔。5ms 对「秒级」的导出/导入足够密；对毫秒级操作只能算下界。
    static let intervalMicroseconds: UInt32 = 5_000

    private let lock = NSLock()
    private let baseline: ProcessMemory.Snapshot
    private var peakResidentBytes: UInt64
    private var peakFootprintBytes: UInt64
    private var finished = false
    private var thread: Thread?

    init(baseline: ProcessMemory.Snapshot) {
        self.baseline = baseline
        self.peakResidentBytes = baseline.residentBytes
        self.peakFootprintBytes = baseline.footprintBytes
    }

    func start() {
        let sampler = Thread { [weak self] in
            guard let self else { return }
            while self.isRunning {
                self.record(ProcessMemory.snapshot())
                usleep(Self.intervalMicroseconds)
            }
        }
        sampler.name = "myfriend-bench-memory-sampler"
        sampler.stackSize = 256 * 1024
        thread = sampler
        sampler.start()
    }

    private var isRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !finished
    }

    private func record(_ snapshot: ProcessMemory.Snapshot) {
        lock.lock()
        peakResidentBytes = max(peakResidentBytes, snapshot.residentBytes)
        peakFootprintBytes = max(peakFootprintBytes, snapshot.footprintBytes)
        lock.unlock()
    }

    /// 停采样并给出峰值（`after` 由调用方在 `autoreleasepool` 之后再取）。
    func stop() -> (peak: ProcessMemory.Snapshot, baseline: ProcessMemory.Snapshot) {
        lock.lock()
        finished = true
        let resident = peakResidentBytes
        let footprint = peakFootprintBytes
        lock.unlock()
        thread = nil
        return (ProcessMemory.Snapshot(residentBytes: resident, footprintBytes: footprint), baseline)
    }

    /// 包一次操作的完整内存测量：基线 → 采样 → 执行 → 峰值 → 稳定后的「之后」。
    ///
    /// `body` 的返回值原样返回；耗时另外用 `BenchmarkClock.measure` 包一层由调用方决定。
    static func measure<T>(
        _ body: () throws -> T
    ) rethrows -> (result: T, memory: ProcessMemory.Delta) {
        let baseline = ProcessMemory.settled()
        let sampler = PeakMemorySampler(baseline: baseline)
        sampler.start()
        do {
            let result = try body()
            let peak = sampler.stop().peak
            let after = ProcessMemory.settled(samples: 2, pauseMicroseconds: 15_000)
            return (result, ProcessMemory.Delta(baseline: baseline, peak: peak, after: after))
        } catch {
            _ = sampler.stop()
            throw error
        }
    }
}

// MARK: - 环境信息

enum BenchmarkEnvironment {

    /// 机器 / OS / 进程 / schema 的快照（报告要能说明「这些数字是在哪儿测的」）。
    static func capture(extra: [String: Any] = [:]) -> [String: Any] {
        var info: [String: Any] = [
            "osVersion": ProcessInfo.processInfo.operatingSystemVersionString,
            "machineModel": sysctlString("hw.model") ?? "unknown",
            "cpuBrand": sysctlString("machdep.cpu.brand_string") ?? "unknown",
            "physicalMemoryMB": Int(ProcessInfo.processInfo.physicalMemory / 1_048_576),
            "processorCount": ProcessInfo.processInfo.processorCount,
            "isSimulator": isSimulator,
            "processKind": isSimulator ? "iOS Simulator" : "macOS host process (swift test)",
            "schemaVersion": Int(Schema.version),
            "blockSize": MessageBlocks.blockSize,
            "swiftVersion": swiftVersion,
            "buildConfiguration": buildConfiguration,
            "hostName": ProcessInfo.processInfo.hostName
        ]
        for (key, value) in extra { info[key] = value }
        return info
    }

    static var isSimulator: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != nil
        #endif
    }

    /// 编译器版本（`#if swift(...)` 判的是语言模式，这里要的是编译器本身）。
    static var swiftVersion: String {
        #if compiler(>=6.4)
        let compiler = "6.4+"
        #elseif compiler(>=6.0)
        let compiler = "6.0-6.3"
        #else
        let compiler = "<6.0"
        #endif
        return "compiler \(compiler), language mode 5（swift-tools-version 5.9）"
    }

    /// `swift test` 的构建配置：Debug 下 Swift 代码是 `-Onone`，检索类数字会比 Release 慢几倍；
    /// SQL 迁移的耗时基本与构建配置无关（跑在 SQLite 的 C 代码里）。
    static var buildConfiguration: String {
        #if DEBUG
        return "debug (-Onone)"
        #else
        return "release (-O)"
        #endif
    }

    private static func sysctlString(_ name: String) -> String? {
        var size = 0
        guard sysctlbyname(name, nil, &size, nil, 0) == 0, size > 0 else { return nil }
        var buffer = [CChar](repeating: 0, count: size)
        guard sysctlbyname(name, &buffer, &size, nil, 0) == 0 else { return nil }
        return String(cString: buffer)
    }
}

// MARK: - JSON 落盘

enum BenchmarkOutput {

    /// 把结果写成 `/tmp/ios-bench-<时间戳>-<name>.json`（`/tmp` 写不了就退到临时目录）。
    ///
    /// - Returns: 实际写入的路径（同时 `print` 出来，测试报告里可见）。
    @discardableResult
    static func write(_ payload: [String: Any], name: String) -> URL? {
        var root = URL(fileURLWithPath: "/tmp", isDirectory: true)
        if !FileManager.default.isWritableFile(atPath: root.path) {
            root = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        }
        let stamp = Int64(Date().timeIntervalSince1970 * 1000)
        let url = root.appendingPathComponent("ios-bench-\(stamp)-\(name).json")
        do {
            var data = try JSONSerialization.data(
                withJSONObject: payload,
                options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
            )
            data.append(0x0A) // 末尾换行，cat 的时候好看
            try data.write(to: url, options: .atomic)
            print("IOS-BENCH 结果已写入：\(url.path)")
            return url
        } catch {
            print("IOS-BENCH 写 JSON 失败（\(url.path)）：\(error)")
            return nil
        }
    }
}

// MARK: - 格式化（print 用）

enum BenchmarkFormat {

    static func ms(_ value: Double) -> String {
        String(format: "%.1f ms", value)
    }

    static func mb(_ value: Double) -> String {
        String(format: "%.1f MB", value)
    }

    static func bytes(_ value: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: value)
    }

    static func table(_ rows: [(String, String)]) -> String {
        let width = rows.map(\.0.count).max() ?? 0
        return rows.map { key, value in
            let padding = String(repeating: " ", count: max(width - key.count, 0))
            return "IOS-BENCH   \(key)\(padding)  \(value)"
        }.joined(separator: "\n")
    }
}
