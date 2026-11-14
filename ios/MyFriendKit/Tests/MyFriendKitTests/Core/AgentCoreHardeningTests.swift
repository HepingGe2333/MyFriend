import Darwin
import Foundation
import XCTest
@testable import MyFriendKit

/// 决策层的加固项（0.11.1 审计 P1-2 / P2-1 / P2-9 / P2-10 / P2-11 + 安全审计 P2-8）。
///
/// 覆盖四类：
/// 1. `connect` 超时真的生效（请求级超时不能再覆盖会话级，且要能在 connect 量级返回）；
/// 2. 失败熔断（远程连续失败后先短路），以及严格模式的失败可见（计数 + 日志 + 上抛）；
/// 3. 老 agentd 缺 `marksRead` 时按输入推断、`asleep` 与 Kotlin 契约对齐；
/// 4. 远程响应体上限（1 MB）。
final class AgentCoreHardeningTests: XCTestCase {

    private static let baseURL = URL(string: "http://127.0.0.1:8788")!

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
        AgentCoreDiagnostics.shared.reset()
        AgentCoreDiagnostics.shared.setSink(nil)
    }

    override func tearDownWithError() throws {
        StubURLProtocol.reset()
        AgentCoreDiagnostics.shared.setSink(nil)
        AgentCoreDiagnostics.shared.reset()
        try super.tearDownWithError()
    }

    private func makeRemoteClient(
        timeouts: AgentCoreTimeouts = .default,
        session: URLSession? = nil,
        log: @escaping @Sendable (String) -> Void = { _ in }
    ) -> RemoteAgentCoreClient {
        RemoteAgentCoreClient(
            baseURL: Self.baseURL,
            timeouts: timeouts,
            session: session ?? StubURLProtocol.makeSession(),
            log: log
        )
    }

    private static func sleepInput(dndOnSleep: Bool = false) -> SleepInput {
        SleepInput(
            sleepEnabled: true,
            dndOnSleep: dndOnSleep,
            sleepMinutes: 1380,
            wakeMinutes: 420,
            nowMinutes: 1410,
            userMessagesInWindow: 0,
            wakeThreshold: 6
        )
    }

    // MARK: - 1. connect 超时（P1-2）

    /// 会话配置：请求级 = connect（等首个字节），资源级 = overall。
    func testSessionUsesConnectForRequestTimeoutAndOverallForResource() {
        let session = RemoteAgentCoreClient.makeSession(
            timeouts: AgentCoreTimeouts(connect: 0.4, overall: 3.0)
        )
        XCTAssertEqual(session.configuration.timeoutIntervalForRequest, 0.4, accuracy: 0.0001)
        XCTAssertEqual(session.configuration.timeoutIntervalForResource, 3.0, accuracy: 0.0001)
        XCTAssertFalse(session.configuration.waitsForConnectivity)
    }

    /// 不可达主机（accept 之后不回任何字节）：必须在 **connect** 量级返回，而不是等满 overall。
    ///
    /// 这条同时钉住「`URLRequest.timeoutInterval` 覆盖会话级超时」那个回归：
    /// 修之前请求级被设成 overall(3.0)，这里会 3 秒才回、且错误说 3 秒。
    func testUnreachableHostTimesOutNearConnectNotOverall() async throws {
        let server = try XCTUnwrap(SilentTCPServer(), "本机连一个静默 TCP 监听都建不起来")
        let timeouts = AgentCoreTimeouts(connect: 0.4, overall: 3.0)
        let client = RemoteAgentCoreClient(
            baseURL: URL(string: "http://127.0.0.1:\(server.port)")!,
            timeouts: timeouts,
            session: RemoteAgentCoreClient.makeSession(timeouts: timeouts)
        )
        let started = Date()
        do {
            _ = try await client.sleepDecision(Self.sleepInput())
            XCTFail("服务端不回包时必须超时")
        } catch let error as AgentCoreClientError {
            guard case .timedOut(let seconds) = error else {
                return XCTFail("期望 timedOut，实际 \(error)")
            }
            XCTAssertEqual(seconds, 0.4, accuracy: 0.001, "报告的必须是 connect 那一档")
        }
        let elapsed = Date().timeIntervalSince(started)
        XCTAssertGreaterThan(elapsed, 0.15, "不该早于 connect 就放弃")
        XCTAssertLessThan(elapsed, 1.5, "3 秒回退若失效，这里会等到 overall(3s) 才返回")
    }

    // MARK: - 2. 熔断（P1-2）

    /// 远程连续失败后，冷却期内**直接走本机**（不再等同一个超时）；冷却结束再试远程。
    func testRetryBreakerShortCircuitsAfterRemoteFailure() async throws {
        let clock = TestClock(start: 1_000)
        let breaker = AgentCoreCircuitBreaker(failureThreshold: 1, cooldown: 30) { clock.now }
        let remote = CountingFailingClient()
        let log = MessageBox()
        let client = FallbackAgentCoreClient(
            mode: .remote(preferredFallback: true),
            remote: remote,
            log: { log.append($0) },
            breaker: breaker
        )
        let input = Self.sleepInput(dndOnSleep: false)

        // 第一次：真的试了远程（失败）→ 回退本机。
        let first = try await client.sleepDecision(input)
        XCTAssertEqual(first, NativeAgentCoreClient().sleepDecisionNow(input))
        XCTAssertEqual(remote.count("sleepDecision"), 1)
        XCTAssertTrue(breaker.isOpen, "一次失败就该打开熔断（agentd 没起是持续状态）")

        // 冷却期内：不再碰远程，直接本机。
        clock.advance(5)
        _ = try await client.sleepDecision(input)
        XCTAssertEqual(remote.count("sleepDecision"), 1, "熔断期间不该再等远程超时")
        XCTAssertTrue(log.joined.contains("熔断中"))

        // 冷却结束：重新试一次远程（服务恢复了不需要重启 App）。
        clock.advance(40)
        _ = try await client.sleepDecision(input)
        XCTAssertEqual(remote.count("sleepDecision"), 2, "冷却结束后应该重新尝试")
    }

    /// 成功会把熔断复位：一次抖动之后不该一直短路。
    func testRetryBreakerResetsAfterSuccess() async throws {
        let clock = TestClock(start: 1_000)
        let breaker = AgentCoreCircuitBreaker(failureThreshold: 1, cooldown: 30) { clock.now }
        let flaky = FlakyClient(failuresRemaining: 1)
        let client = FallbackAgentCoreClient(
            mode: .remote(preferredFallback: true),
            remote: flaky,
            log: { _ in },
            breaker: breaker
        )
        let input = Self.sleepInput()
        _ = try await client.sleepDecision(input)   // 失败一次 → 打开
        XCTAssertTrue(breaker.isOpen)
        clock.advance(31)                            // 冷却结束
        _ = try await client.sleepDecision(input)   // 成功
        XCTAssertFalse(breaker.isOpen, "成功之后必须复位")
        XCTAssertFalse(breaker.isOpen)
        _ = try await client.sleepDecision(input)
        XCTAssertEqual(flaky.calls, 3, "复位之后每次都会真的走远程")
    }

    // MARK: - 3. 严格模式的失败可见（P2-1）

    /// 严格模式：失败写诊断（计数 + 日志）并且**原样上抛**，不再假装本机判定成功。
    func testStrictModeFailureIsRecordedAndRethrown() async throws {
        let log = MessageBox()
        AgentCoreDiagnostics.shared.setSink { message in log.append(message) }
        StubURLProtocol.enqueue(.json(
            #"{"error":{"message":"服务内部错误","code":"internal_error"}}"#,
            status: 500
        ))
        let client = makeRemoteClient()   // `RemoteAgentCoreClient` = remote-strict
        do {
            _ = try await client.decide(
                endpoint: "sleep-decision",
                nativeFallback: { NativeAgentCoreClient().sleepDecisionNow(Self.sleepInput()) }
            ) {
                try await client.sleepDecision(Self.sleepInput())
            }
            XCTFail("严格模式失败必须抛给调用点")
        } catch let error as AgentCoreClientError {
            guard case .http(let status, _) = error else {
                return XCTFail("期望 http 500，实际 \(error)")
            }
            XCTAssertEqual(status, 500)
        }
        XCTAssertEqual(AgentCoreDiagnostics.shared.strictCount(endpoint: "sleep-decision"), 1)
        XCTAssertTrue(
            log.joined.contains("严格模式远程失败") && log.joined.contains("sleep-decision"),
            "严格模式的失败必须写进可见诊断日志：\(log.joined)"
        )
    }

    /// 普通 `remote`（带回退）：静默回退本机，不刷「严格模式」日志，但失败计数仍然可查。
    func testFallbackModeFailureIsSilentButCounted() async throws {
        let log = MessageBox()
        AgentCoreDiagnostics.shared.setSink { message in log.append(message) }
        let remote = CountingFailingClient(mode: .remote(preferredFallback: true))
        let fallback = NativeAgentCoreClient().sleepDecisionNow(Self.sleepInput())
        let value = try await remote.decide(
            endpoint: "sleep-decision", nativeFallback: { fallback }
        ) {
            try await remote.sleepDecision(Self.sleepInput())
        }
        XCTAssertEqual(value, fallback)
        XCTAssertEqual(AgentCoreDiagnostics.shared.count(endpoint: "sleep-decision"), 1)
        XCTAssertEqual(AgentCoreDiagnostics.shared.strictCount(endpoint: "sleep-decision"), 0)
        XCTAssertTrue(log.joined.isEmpty, "普通回退模式不该刷「严格模式」日志")
    }

    /// 取消不算失败：不记诊断、不回退。
    func testCancellationIsNotRecordedAsFailure() async throws {
        struct CancellingClient: AgentCoreClient {
            func sleepDecision(_ input: SleepInput) async throws -> SleepDecision {
                throw CancellationError()
            }
            func deferPlan(minutes: Int?, reason: String?, now: Int64) async throws -> DeferPlan {
                throw CancellationError()
            }
            func proactiveCap(level: Int, moodIntensity: Int?) async throws -> Int {
                throw CancellationError()
            }
            func clampToolArgs(tool: String, args: [String: JSONValue]) async throws -> [String: JSONValue] {
                throw CancellationError()
            }
        }
        let client = CancellingClient()
        do {
            _ = try await client.decide(
                endpoint: "sleep-decision",
                nativeFallback: { NativeAgentCoreClient().sleepDecisionNow(Self.sleepInput()) }
            ) {
                try await client.sleepDecision(Self.sleepInput())
            }
            XCTFail("取消必须原样抛出")
        } catch is CancellationError {
            // 期望
        }
        XCTAssertEqual(AgentCoreDiagnostics.shared.totalCount, 0)
    }

    // MARK: - 4. 老 agentd 的 marksRead / asleep（P2-9 / P2-10）

    /// 老服务端（没有 marksRead）+ 免打扰 + 睡着：不要再补已读。
    func testLegacyServerWithoutMarksReadDoesNotMarkReadDuringDND() async throws {
        StubURLProtocol.enqueue(.json(#"{"wake":false,"moodRelaxed":false,"reason":"睡着了"}"#))
        let decision = try await makeRemoteClient().sleepDecision(Self.sleepInput(dndOnSleep: true))
        XCTAssertFalse(decision.wake)
        XCTAssertFalse(decision.marksRead, "免打扰里补已读会让已读状态与本机判定相反")
        XCTAssertEqual(decision.replyDecision, .sleepingDoNotDisturb)
    }

    /// 老服务端 + 没开免打扰：缺省仍然按「会留已读」处理（不把睡着的人显示成冷处理）。
    func testLegacyServerWithoutMarksReadKeepsReadWhenDNDIsOff() async throws {
        StubURLProtocol.enqueue(.json(#"{"wake":false,"moodRelaxed":false,"reason":"睡着了"}"#))
        let decision = try await makeRemoteClient().sleepDecision(Self.sleepInput(dndOnSleep: false))
        XCTAssertTrue(decision.marksRead)
        XCTAssertEqual(decision.replyDecision, .sleepingWaiting)
    }

    /// 新服务端显式给了 marksRead：以它为准。
    func testExplicitMarksReadWins() async throws {
        StubURLProtocol.enqueue(.json(#"{"wake":false,"moodRelaxed":false,"reason":"x","marksRead":false}"#))
        let decision = try await makeRemoteClient().sleepDecision(Self.sleepInput(dndOnSleep: false))
        XCTAssertFalse(decision.marksRead)
    }

    /// `asleep` 与 Kotlin 契约一致：被叫醒时人仍在睡眠窗口里（`!wake || moodRelaxed`）。
    func testAsleepMatchesKotlinContract() {
        XCTAssertTrue(SleepDecision(wake: false, moodRelaxed: false, reason: "").asleep)
        XCTAssertTrue(SleepDecision(wake: false, moodRelaxed: true, reason: "").asleep)
        XCTAssertTrue(
            SleepDecision(wake: true, moodRelaxed: true, reason: "被叫醒").asleep,
            "被叫醒时人还在睡眠窗口里，Kotlin 的 asleep 也是 true"
        )
        XCTAssertFalse(SleepDecision(wake: true, moodRelaxed: false, reason: "醒着").asleep)
    }

    // MARK: - 5. 远程响应体上限（P2-11）

    func testOversizedResponseIsRejectedWithoutDecoding() async throws {
        let oversized = String(repeating: "a", count: RemoteAgentCoreClient.maxResponseBytes + 64)
        StubURLProtocol.enqueue(.json(oversized))
        do {
            _ = try await makeRemoteClient().sleepDecision(Self.sleepInput())
            XCTFail("超过 1 MB 的响应必须被拒绝")
        } catch let error as AgentCoreClientError {
            guard case .responseTooLarge(let limit) = error else {
                return XCTFail("期望 responseTooLarge，实际 \(error)")
            }
            XCTAssertEqual(limit, RemoteAgentCoreClient.maxResponseBytes)
        }
    }

    func testNormalSizedResponseStillDecodes() async throws {
        StubURLProtocol.enqueue(.json(#"{"wake":true,"moodRelaxed":false,"reason":"醒着","marksRead":true}"#))
        let decision = try await makeRemoteClient().sleepDecision(Self.sleepInput())
        XCTAssertTrue(decision.wake)
    }

    // MARK: - 夹具

    private final class CounterBox: @unchecked Sendable {
        private let lock = NSLock()
        private var values: [String: Int] = [:]
        func hit(_ name: String) {
            lock.lock(); values[name, default: 0] += 1; lock.unlock()
        }
        func count(_ name: String) -> Int {
            lock.lock(); defer { lock.unlock() }
            return values[name] ?? 0
        }
    }

    private final class TestClock: @unchecked Sendable {
        private var value: TimeInterval
        init(start: TimeInterval) { value = start }
        var now: TimeInterval { value }
        func advance(_ seconds: TimeInterval) { value += seconds }
    }

    private final class MessageBox: @unchecked Sendable {
        private let lock = NSLock()
        private var messages: [String] = []
        func append(_ message: String) {
            lock.lock(); messages.append(message); lock.unlock()
        }
        var joined: String {
            lock.lock(); defer { lock.unlock() }
            return messages.joined(separator: "\n")
        }
    }

    /// 永远失败的假远程（可指定「对外声称」的模式）。
    private struct CountingFailingClient: AgentCoreClient {
        var mode: AgentCoreMode = .remote(preferredFallback: false)
        private let box = CounterBox()
        var agentCoreMode: AgentCoreMode { mode }
        func count(_ name: String) -> Int { box.count(name) }

        func sleepDecision(_ input: SleepInput) async throws -> SleepDecision {
            box.hit("sleepDecision")
            throw AgentCoreClientError.transport("连不上 agentd")
        }
        func deferPlan(minutes: Int?, reason: String?, now: Int64) async throws -> DeferPlan {
            box.hit("deferPlan")
            throw AgentCoreClientError.transport("连不上 agentd")
        }
        func proactiveCap(level: Int, moodIntensity: Int?) async throws -> Int {
            box.hit("proactiveCap")
            throw AgentCoreClientError.transport("连不上 agentd")
        }
        func clampToolArgs(tool: String, args: [String: JSONValue]) async throws -> [String: JSONValue] {
            box.hit("clampToolArgs")
            throw AgentCoreClientError.transport("连不上 agentd")
        }
    }

    /// 前 N 次失败、之后成功（测熔断复位）。
    private final class FlakyClient: AgentCoreClient, @unchecked Sendable {
        private var failuresRemaining: Int
        private let lock = NSLock()
        private var callCount = 0
        private let native = NativeAgentCoreClient()

        init(failuresRemaining: Int) { self.failuresRemaining = failuresRemaining }

        var calls: Int { lock.lock(); defer { lock.unlock() }; return callCount }

        func sleepDecision(_ input: SleepInput) async throws -> SleepDecision {
            lock.lock()
            callCount += 1
            let shouldFail = failuresRemaining > 0
            if shouldFail { failuresRemaining -= 1 }
            lock.unlock()
            if shouldFail { throw AgentCoreClientError.transport("抖动") }
            return try await native.sleepDecision(input)
        }
        func deferPlan(minutes: Int?, reason: String?, now: Int64) async throws -> DeferPlan {
            try await native.deferPlan(minutes: minutes, reason: reason, now: now)
        }
        func proactiveCap(level: Int, moodIntensity: Int?) async throws -> Int {
            try await native.proactiveCap(level: level, moodIntensity: moodIntensity)
        }
        func clampToolArgs(tool: String, args: [String: JSONValue]) async throws -> [String: JSONValue] {
            try await native.clampToolArgs(tool: tool, args: args)
        }
    }

    /// 只 accept、永远不回包的本地 TCP 监听（复刻审计里那个 /tmp 探针）。
    ///
    /// 为什么不用 `URLProtocol` 桩：`URLProtocol` 层的「永不回调」不会跑到 URLSession 的
    /// 空闲超时逻辑上，测不出「请求级超时覆盖会话级」这一类问题。真的开一个 socket 才准。
    private final class SilentTCPServer {
        private var socketFD: Int32 = -1
        private(set) var port: UInt16 = 0

        init?() {
            let fd = socket(AF_INET, SOCK_STREAM, 0)
            guard fd >= 0 else { return nil }
            var reuse: Int32 = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))
            var address = sockaddr_in()
            address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            address.sin_family = sa_family_t(AF_INET)
            address.sin_port = 0
            address.sin_addr = in_addr(s_addr: in_addr_t(UInt32(INADDR_LOOPBACK).bigEndian))
            let bound = withUnsafePointer(to: &address) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
            guard bound == 0, Darwin.listen(fd, 1) == 0 else {
                close(fd)
                return nil
            }
            var actual = sockaddr_in()
            var length = socklen_t(MemoryLayout<sockaddr_in>.size)
            let named = withUnsafeMutablePointer(to: &actual) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    getsockname(fd, $0, &length)
                }
            }
            guard named == 0 else {
                close(fd)
                return nil
            }
            port = UInt16(bigEndian: actual.sin_port)
            socketFD = fd
        }

        deinit {
            if socketFD >= 0 { close(socketFD) }
        }
    }
}
