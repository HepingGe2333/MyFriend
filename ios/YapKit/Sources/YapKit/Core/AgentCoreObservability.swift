import Foundation

// ---------------------------------------------------------------------------
// 决策层的可观测性（0.11.1 审计 P1-2 / P2-1）。
//
// 两条缺陷其实是同一件事的两面：
//
// 1. `remote-strict` 的语义是「远程失败要冒出来」（`AgentCoreClient` 的注释这么写、
//    `RemoteAgentCoreClient` 也确实抛了），但**每一个调用点**都 `try?` 掉并改用本机实现。
//    于是严格模式与普通回退模式在 App 行为上没有区别 —— 用它做「agentd 真的被用上了」
//    的验收会得到假阳性。
// 2. 远程不可达时没有熔断：一次回复里串行经过的几次决策各等满一个超时。
//
// `AgentCoreDiagnostics` 记录「决策层失败」（严格模式的失败一定可见：计数 + 一行
// debug 日志，App 启动时可以接上 `DebugSeeding.log`）；`AgentCoreCircuitBreaker`
// 让带回退的远程客户端在连续失败后短路一段时间；`decide(endpoint:nativeFallback:)`
// 是调用点共用的那一个入口 —— 严格模式的错误原样抛出，其它模式静默回退。
// ---------------------------------------------------------------------------

/// 决策层失败的可见记录（计数 + 最近若干条）。
///
/// 为什么不是直接 `print`：Release 包里没有 debug 日志文件，界面也没有「诊断」入口，
/// 所以至少要在进程内留一份可查的计数与最近失败列表（联调、崩溃报告、以后接诊断页都用得上）。
public final class AgentCoreDiagnostics: @unchecked Sendable {

    /// 一条失败记录。
    public struct Failure: Equatable, Sendable {
        public let endpoint: String
        public let mode: String
        public let message: String
        public let strict: Bool
        public let at: Int64

        public init(endpoint: String, mode: String, message: String, strict: Bool, at: Int64) {
            self.endpoint = endpoint
            self.mode = mode
            self.message = message
            self.strict = strict
            self.at = at
        }
    }

    public static let shared = AgentCoreDiagnostics()

    /// 最近失败保留多少条（再多就是日志了，不是诊断信息）。
    public static let recentLimit = 20

    private let lock = NSLock()
    private var failures: [Failure] = []
    private var totals: [String: Int] = [:]
    private var strictTotals: [String: Int] = [:]
    private var sink: (@Sendable (String) -> Void)?

    public init() {}

    /// App 启动后接上（Debug 构建指向 `debug.log`；测试里接自己的收集器）。
    public func setSink(_ sink: (@Sendable (String) -> Void)?) {
        lock.mfWithLock { self.sink = sink }
    }

    /// 记一条失败。
    ///
    /// - Parameters:
    ///   - endpoint: 端点名（`sleep-decision` 等，与日志同一口径）。
    ///   - mode: 当时的模式（严格/回退/本机）。
    ///   - error: 原始错误（只取 `String(describing:)`，不进用户界面）。
    /// - Returns: 这算不算「严格模式的失败」（调用点据此决定是否上抛）。
    @discardableResult
    public func record(
        endpoint: String,
        mode: AgentCoreMode,
        error: Error,
        now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) -> Bool {
        let strict = mode.isStrictRemote
        let message = AgentCoreClientError.describe(error)
        let failure = Failure(
            endpoint: endpoint, mode: mode.description, message: message, strict: strict, at: now
        )
        var sinkCopy: (@Sendable (String) -> Void)?
        lock.mfWithLock {
            totals[endpoint, default: 0] += 1
            if strict { strictTotals[endpoint, default: 0] += 1 }
            failures.append(failure)
            if failures.count > Self.recentLimit {
                failures.removeFirst(failures.count - Self.recentLimit)
            }
            sinkCopy = sink
        }
        // 严格模式一定写日志（这是「失败可见」的落点）；回退模式由 `FallbackAgentCoreClient`
        // 自己记一行，这里不重复刷屏。
        if strict {
            sinkCopy?("[agentcore] 严格模式远程失败 endpoint=\(endpoint) 原因=\(message)")
        }
        return strict
    }

    /// 某个端点一共失败了几次。
    public func count(endpoint: String) -> Int {
        lock.mfWithLock { totals[endpoint] ?? 0 }
    }

    /// 某个端点在严格模式下失败了几次。
    public func strictCount(endpoint: String) -> Int {
        lock.mfWithLock { strictTotals[endpoint] ?? 0 }
    }

    /// 所有端点的失败总数。
    public var totalCount: Int {
        lock.mfWithLock { totals.values.reduce(0, +) }
    }

    /// 所有端点在严格模式下的失败总数。
    public var strictTotalCount: Int {
        lock.mfWithLock { strictTotals.values.reduce(0, +) }
    }

    /// 最近的失败（新的在后）。
    public func recent() -> [Failure] {
        lock.mfWithLock { failures }
    }

    /// 清空（单测用；App 不会调）。
    public func reset() {
        lock.mfWithLock {
            failures.removeAll()
            totals.removeAll()
            strictTotals.removeAll()
        }
    }
}

/// 「远程连续失败后先停一会儿」的短路开关。
///
/// 默认阈值 1：只要远程失败一次，接下来 `cooldown` 秒内的决策直接走本机 ——
/// agentd 没起/端口写错是**持续**状态，第二次还去等同一个超时只会让聊天更慢。
/// 冷却期一过就重新试一次远程：服务恢复了不需要重启 App。
public final class AgentCoreCircuitBreaker: @unchecked Sendable {

    /// 连续失败几次后打开（1 = 第一次失败就打开）。
    public let failureThreshold: Int
    /// 打开后多久再试远程（秒）。
    public let cooldown: TimeInterval
    private let now: @Sendable () -> TimeInterval

    private let lock = NSLock()
    private var consecutiveFailures = 0
    private var openUntil: TimeInterval?

    public init(
        failureThreshold: Int = 1,
        cooldown: TimeInterval = 30,
        now: @escaping @Sendable () -> TimeInterval = { Date().timeIntervalSince1970 }
    ) {
        self.failureThreshold = max(1, failureThreshold)
        self.cooldown = max(0, cooldown)
        self.now = now
    }

    /// 现在是不是「短路中」（远程不该再试）。
    public var isOpen: Bool {
        lock.mfWithLock {
            guard let openUntil else { return false }
            if now() >= openUntil {
                self.openUntil = nil
                consecutiveFailures = 0
                return false
            }
            return true
        }
    }

    /// 记一次失败。
    ///
    /// - Returns: 这一次失败有没有把开关打开（调用点用它决定要不要写一行「熔断中」）。
    @discardableResult
    public func recordFailure() -> Bool {
        lock.mfWithLock {
            consecutiveFailures += 1
            guard consecutiveFailures >= failureThreshold else { return false }
            let wasOpen = openUntil.map { now() < $0 } ?? false
            openUntil = now() + cooldown
            return !wasOpen
        }
    }

    /// 记一次成功：连续失败清零、开关复位。
    public func recordSuccess() {
        lock.mfWithLock {
            consecutiveFailures = 0
            openUntil = nil
        }
    }

    /// 当前连续失败次数（测试/诊断用）。
    public var failureCount: Int {
        lock.mfWithLock { consecutiveFailures }
    }
}

// MARK: - 调用点共用的入口

public extension AgentCoreClient {

    /// 调一次决策层，失败时按模式处理。
    ///
    /// - 严格远程（`remote-strict`）：先记一条**可见**诊断（`AgentCoreDiagnostics`：计数 +
    ///   debug 日志），再把错误原样抛出。调用点要么让错误冒到用户面前，要么自己记成
    ///   一条可见的失败 —— 但无论哪种，都不会再假装「本机判定成功」。
    /// - 回退/本机模式：记一条静默诊断（不刷日志）后回退到 `nativeFallback`，
    ///   行为与 0.11.0 一致（每次失败由 `FallbackAgentCoreClient` 记一行）。
    ///
    /// **0.12.0：iOS App 构建里 `nativeFallback` 恒为 `nil`。** Swift 的
    /// `NativeAgentCoreClient` 被 `#if !os(iOS)` 排除，调用点传进来的是
    /// `AgentCoreNativeFallback` 的返回值（iOS = nil）；此时失败按「可见」处理、直接上抛，
    /// 不再回落到第二份实现。macOS `swift test` 里行为与以前完全一样（见
    /// `AgentCoreNativeFallback.resolve`）。
    ///
    /// 取消（用户点了停止）不算失败：保持 `CancellationError`，不记录、不回退。
    func decide<T>(
        endpoint: String,
        nativeFallback: (@Sendable () -> T)?,
        _ attempt: () async throws -> T
    ) async throws -> T {
        do {
            return try await attempt()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            let strict = AgentCoreDiagnostics.shared.record(
                endpoint: endpoint, mode: agentCoreMode, error: error
            )
            return try AgentCoreNativeFallback.resolve(error, strict: strict, fallback: nativeFallback)
        }
    }
}
