import Foundation
import XCTest
@testable import MyFriend

/// **iOS 模拟器专用**：Kotlin 实现真的被接进 App，而且与共享契约一致。
///
/// 为什么要有这一组测试（`swift test` 覆盖不到）：
///
/// 0.12.0 起 iOS App 的 agent 决策走 Kotlin `AgentCoreKit.InProcessAgentCore`（与 Android、
/// `agentd` 同一份 `:agentcore`）。Swift 实现（`NativeAgentCoreClient`）从 0.12.0 起包在
/// `#if !os(iOS)` 里，**iOS 构建里根本不存在** —— 所以这组测试只验证两件事：
///
/// 1. 工程真的链上了 `AgentCoreKit`（工厂默认给出 `KotlinAgentCoreClient`，启动日志写
///    `agent core: kotlin`，Kotlin/Native 的 ObjC 类已在进程里注册）；
/// 2. Kotlin 实现跑 `spec/agent-core/cases/*.json` 的输出与契约**逐字段相同** —— 睡眠 /
///    defer 计划 / 主动上限 / 工具参数夹取四个决策全覆盖。
///
/// 「两条实现互相比对」不再在 iOS 上做：Swift 实现只存在于 macOS 的 `swift test`，
/// 那边用同一批 spec 用例对照（`AgentCoreSpecTests` / `AgentCoreClientTests`）。两端的等价性
/// 因此由「各自对同一份契约」保证，而不是由 iOS 构建偷偷带一份 Swift 实现来比对。
///
/// 用例文件不是抄来的：工程里对 `spec/agent-core/cases` 建了文件夹引用，测试 bundle 里
/// 带的就是仓库里那一份（Android 的 `AgentCoreSpecTest` 读同一批文件）。
///
/// 跑法：
/// ```bash
/// xcodebuild -project MyFriend.xcodeproj -scheme MyFriend \
///   -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
///   -derivedDataPath .build/DerivedData test
/// ```
final class AgentCoreKotlinParityTests: XCTestCase {

    /// App 装配真正会拿到的那个「本机实现」。iOS 上它必须是 Kotlin —— 见下面的接线测试。
    private let wired: any AgentCoreClient = AgentCoreClientFactory.makeNative()

    // MARK: - 0. 接线：iOS 上默认真的是 Kotlin

    func testFactoryNativeClientIsKotlinOnIOS() {
        XCTAssertTrue(
            wired is KotlinAgentCoreClient,
            "iOS App 构建下默认本机实现必须是 Kotlin，实际是 \(type(of: wired))"
        )
        XCTAssertEqual(wired.agentCoreMode, .native, "Kotlin 客户端就是「本机」实现，不走网络")
        // framework 真的被链进进程的旁证：Kotlin/Native 生成的 ObjC 类已在 runtime 里注册。
        // 只 import 不链接的话这里会是 nil。
        XCTAssertNotNil(
            NSClassFromString("ACKInProcessAgentCore"),
            "AgentCoreKit 的动态库/静态库没有进到这个进程"
        )
    }

    func testFactoryLogsThatKotlinIsInUse() {
        let log = LogBox()
        let client = AgentCoreClientFactory.make(
            mode: .native, environment: [:], info: nil, log: { log.append($0) }
        )
        XCTAssertTrue(client is KotlinAgentCoreClient)
        XCTAssertTrue(
            log.all.contains { $0.contains("agent core: kotlin") },
            "启动日志里没有 agent core: kotlin，实际：\(log.all)"
        )
    }

    /// 远程模式没有被动过：`MYFRIEND_AGENT_CORE` 仍然覆盖默认，回退用的「本机」也是 Kotlin。
    func testRemoteOverrideStillWinsAndFallsBackToKotlin() {
        let remote = AgentCoreClientFactory.make(
            mode: .remote(preferredFallback: true),
            environment: [:], info: nil,
            baseURL: URL(string: "http://127.0.0.1:1")!,
            log: { _ in }
        )
        let fallback = remote as? FallbackAgentCoreClient
        XCTAssertNotNil(fallback, "remote 模式仍然是 FallbackAgentCoreClient")
        XCTAssertEqual(fallback?.agentCoreMode, .remote(preferredFallback: true))

        let strict = AgentCoreClientFactory.make(
            mode: .remote(preferredFallback: false),
            environment: [:], info: nil,
            baseURL: URL(string: "http://127.0.0.1:1")!,
            log: { _ in }
        )
        XCTAssertTrue(strict is RemoteAgentCoreClient, "remote-strict 仍然是纯远程，不回退")
    }

    // MARK: - 1. 睡眠判定

    func testSleepDecisionParityOnSpecCases() async throws {
        for item in try loadCases("sleep-decision") {
            let input = try Self.sleepInput(from: item.input)
            let kotlin = try await wired.sleepDecision(input)

            // 把契约本身钉一下：Kotlin 实现不许偏离 spec。
            XCTAssertEqual(kotlin.wake, try item.expect.bool("shouldReply"), "[\(item.name)] wake")
            XCTAssertEqual(kotlin.moodRelaxed, try item.expect.bool("woken"), "[\(item.name)] moodRelaxed")
            XCTAssertEqual(kotlin.marksRead, try item.expect.bool("marksRead"), "[\(item.name)] marksRead")
            XCTAssertEqual(kotlin.asleep, try item.expect.bool("asleep"), "[\(item.name)] asleep")
            XCTAssertEqual(kotlin.dnd, try item.expect.bool("dnd"), "[\(item.name)] dnd")
        }
    }

    // MARK: - 2. defer 计划

    func testDeferPlanParityOnSpecCases() async throws {
        for item in try loadCases("defer-plan") {
            let minutes = item.input.optionalInt("minutes")
            let reason = item.input.optionalString("reason")
            let now = try item.input.int64("now")

            let kotlin = try await wired.deferPlan(minutes: minutes, reason: reason, now: now)

            XCTAssertEqual(kotlin.minutes, try item.expect.int("minutes"), "[\(item.name)] minutes")
            XCTAssertEqual(kotlin.reason, try item.expect.string("reason"), "[\(item.name)] reason")
            XCTAssertEqual(kotlin.earliestAt, try item.expect.int64("earliestAt"), "[\(item.name)] earliestAt")
            XCTAssertEqual(kotlin.latestAt, try item.expect.int64("latestAt"), "[\(item.name)] latestAt")
        }
    }

    /// `Int64` 的 64 位往返：契约里的 `now` 是毫秒时间戳，不能在半路被截成 32 位。
    func testDeferPlanParityKeeps64BitTimestamps() async throws {
        for now in [Int64(0), 1_760_000_000_000, 4_102_444_800_000, Int64.max / 2] {
            let kotlin = try await wired.deferPlan(minutes: 45, reason: "在忙", now: now)
            // Int64 不能被 Kotlin/Native 的桥截成 32 位：earliestAt/latestAt 必须原样往回走。
            XCTAssertEqual(kotlin.earliestAt, now + 45 * 60_000, "now=\(now) 时 earliestAt 不对")
            XCTAssertEqual(kotlin.latestAt, kotlin.earliestAt + 30 * 60_000, "now=\(now) 时窗口宽度不对")
            XCTAssertEqual(kotlin.minutes, 45)
        }
    }

    // MARK: - 3. 主动上限

    func testProactiveCapParityOnSpecCases() async throws {
        for item in try loadCases("proactive-cap") {
            let level = try item.input.int("level")
            let moodIntensity = (item.input["mood"] as? [String: Any])?.optionalInt("intensity")

            let kotlin = try await wired.proactiveCap(level: level, moodIntensity: moodIntensity)

            // 协议只回**基础值**（心情修正留在调用点的 MoodBehavior）：与 spec 的 baseDailyCap 对齐。
            XCTAssertEqual(kotlin, try item.expect.int("baseDailyCap"), "[\(item.name)] baseDailyCap")
        }
    }

    func testProactiveCapParityOnOutOfRangeLevels() async throws {
        for level in [-5, -1, 0, 1, 2, 3, 4, 5, 6, 99] {
            for intensity in [nil, 0, 3, 5] {
                let kotlin = try await wired.proactiveCap(level: level, moodIntensity: intensity)
                // 契约只有一条：夹到 1–5，只按主动程度换算（moodIntensity 不移动基础值）。
                XCTAssertEqual(
                    kotlin, min(max(level, 1), 5),
                    "level=\(level) intensity=\(String(describing: intensity)) 的夹取不对"
                )
            }
        }
    }

    // MARK: - 4. 工具参数夹取（`[String: JSONValue]` ⇄ `Map<String, Any?>`）

    func testClampToolArgsParityOnSpecCases() async throws {
        for item in try loadCases("tool-args") {
            let tool = try item.input.string("tool")
            let args = try item.input.object("args").mapValues(AgentCoreJSON.value(from:))

            let kotlin = try await wired.clampToolArgs(tool: tool, args: args)
            let expected = try item.expect.object("args").mapValues(AgentCoreJSON.value(from:))

            XCTAssertEqual(kotlin, expected, "[\(item.name)] 夹取结果与契约不一致")
        }
    }

    /// 数值/布尔/空值/嵌套结构跨 ObjC 桥的往返：Kotlin 只夹已知数值键，其余必须原样带回。
    ///
    /// 这一条专门盯两个已知的桥陷阱（期望值直接写死，不再和 Swift 实现比）：
    /// - Kotlin 的 `Boolean` 回来是 `KotlinBoolean`（不是 CFBoolean），按数值读会把 true 变成 1；
    /// - Swift `Int` 过去是 Kotlin `Long`，`intArgument` 必须认得出（否则静默用缺省值）。
    func testClampToolArgsSurvivesTheKotlinBridge() async throws {
        let edgeArgs: [String: JSONValue] = [
            "content": .string("她说怕冷"),
            "flag": .bool(true),
            "off": .bool(false),
            "nothing": .null,
            "nested": .object(["a": .int(1), "b": .array([.string("x"), .int(2)])]),
            "items": .array([.int(1), .string("two"), .bool(true), .null])
        ]
        let cases: [(tool: String, args: [String: JSONValue], expected: [String: JSONValue])] = [
            // 非数值的 importance（布尔/null/对象）按「没给」处理 → 缺省值 3。
            (AgentCoreTool.remember, edgeArgs, edgeArgs.merging(["importance": .int(3)]) { _, new in new }),
            (AgentCoreTool.remember, ["importance": .double(4.0)], ["importance": .int(4)]),
            (AgentCoreTool.remember, ["importance": .string("5")], ["importance": .int(5)]),
            (AgentCoreTool.remember, ["importance": .bool(true)], ["importance": .int(3)]),
            (AgentCoreTool.remember, ["importance": .null], ["importance": .int(3)]),
            (AgentCoreTool.webSearch, ["count": .string("99")], ["count": .int(12)]),
            (AgentCoreTool.webSearch, ["count": .int(-3)], ["count": .int(1)]),
            (AgentCoreTool.webSearch, [:], ["count": .int(6)]),
            // 只夹数字：reason 原样保留（trim 是 defer-plan 端点的事，不是工具参数夹取）。
            (AgentCoreTool.deferReply, ["minutes": .int(1000), "reason": .string(" 在忙 ")], ["minutes": .int(720), "reason": .string(" 在忙 ")]),
            // 不认识的工具原样返回（拒绝未知工具是服务端 HTTP 层的事）。
            ("unknown_tool", ["weight": .int(7), "name": .string("n"), "ratio": .double(0.5)], ["weight": .int(7), "name": .string("n"), "ratio": .double(0.5)])
        ]
        for (tool, args, expected) in cases {
            let kotlin = try await wired.clampToolArgs(tool: tool, args: args)
            XCTAssertEqual(kotlin, expected, "tool=\(tool) args=\(args) 的夹取结果不对")
        }
    }

    // MARK: - 用例读取

    private struct SpecCase {
        var name: String
        var input: [String: Any]
        var expect: [String: Any]
    }

    /// 从测试 bundle 里读 `cases/<name>.json`（工程里对 `spec/agent-core/cases` 的文件夹引用）。
    private func loadCases(_ fileName: String) throws -> [SpecCase] {
        let bundle = Bundle(for: AgentCoreKotlinParityTests.self)
        let url = try XCTUnwrap(
            bundle.url(forResource: fileName, withExtension: "json", subdirectory: "cases"),
            "测试 bundle 里没有 cases/\(fileName).json（文件夹引用失效？bundle=\(bundle.bundlePath)）"
        )
        let data = try Data(contentsOf: url)
        let root = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: data) as? [String: Any],
            "\(url.lastPathComponent) 不是 JSON 对象"
        )
        let rawCases = try XCTUnwrap(root["cases"] as? [[String: Any]], "\(url.lastPathComponent) 没有 cases 数组")
        XCTAssertFalse(rawCases.isEmpty, "\(fileName) 不能没有用例")
        return rawCases.map {
            SpecCase(
                name: $0["name"] as? String ?? "<unnamed>",
                input: $0["input"] as? [String: Any] ?? [:],
                expect: $0["expect"] as? [String: Any] ?? [:]
            )
        }
    }

    // MARK: - 入参整形（用例 JSON → SleepInput）

    private static func sleepInput(from raw: [String: Any]) throws -> SleepInput {
        SleepInput(
            sleepEnabled: try raw.bool("sleepEnabled"),
            dndOnSleep: try raw.bool("dndOnSleep"),
            sleepMinutes: raw.optionalInt("sleepMinutes"),
            wakeMinutes: raw.optionalInt("wakeMinutes"),
            nowMinutes: try raw.int("nowMinutes"),
            userMessagesInWindow: try raw.int("userMessagesInWindow"),
            wakeThreshold: try raw.int("wakeThreshold")
        )
    }

    /// 收集日志用（`make(log:)` 是 `@Sendable`，不能直接往测试属性里写）。
    private final class LogBox: @unchecked Sendable {
        private let lock = NSLock()
        private var items: [String] = []

        func append(_ item: String) {
            lock.lock()
            items.append(item)
            lock.unlock()
        }

        var all: [String] {
            lock.lock()
            defer { lock.unlock() }
            return items
        }
    }
}

// MARK: - JSON 取值辅助（与 AgentCoreSpecTests 同口径）

private extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) throws -> String {
        guard let value = self[key] as? String else { throw missing(key, "字符串") }
        return value
    }

    func optionalString(_ key: String) -> String? { self[key] as? String }

    func int(_ key: String) throws -> Int {
        guard let number = self[key] as? NSNumber else { throw missing(key, "整数") }
        return number.intValue
    }

    func optionalInt(_ key: String) -> Int? { (self[key] as? NSNumber)?.intValue }

    func int64(_ key: String) throws -> Int64 {
        guard let number = self[key] as? NSNumber else { throw missing(key, "长整数") }
        return number.int64Value
    }

    func bool(_ key: String) throws -> Bool {
        guard let number = self[key] as? NSNumber else { throw missing(key, "布尔") }
        return number.boolValue
    }

    func object(_ key: String) throws -> [String: Any] {
        guard let value = self[key] as? [String: Any] else { throw missing(key, "对象") }
        return value
    }

    private func missing(_ key: String, _ kind: String) -> NSError {
        NSError(
            domain: "AgentCoreKotlinParity",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "缺少\(kind)字段 \(key)"]
        )
    }
}
