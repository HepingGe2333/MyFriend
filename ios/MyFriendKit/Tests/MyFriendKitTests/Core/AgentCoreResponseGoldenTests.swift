import Foundation
import XCTest
@testable import MyFriendKit

/// 决策层响应体的字段名 golden（0.11.1 审计 P2-16）。
///
/// 审计指出：`AgentCoreClientTests` 里只有 **sleep** 响应是走真实 `RemoteAgentCoreClient`
/// 解码的；defer / proactive-cap / tool-args 的响应字段靠「Swift 客户端 + 它自己的桩」自证，
/// Kotlin 改了 `latestAt` / `dailyCap` / `args` 也抓不到。
///
/// 这里把四个端点的响应字段名**直接取自** `agentcore/.../AgentCoreProtocol.kt` 的
/// `FIELD_*` 常量，再用真实客户端解码 —— 两端谁改名都会红。
final class AgentCoreResponseGoldenTests: XCTestCase {

    private static let baseURL = URL(string: "http://127.0.0.1:8788")!

    override func setUp() {
        super.setUp()
        StubURLProtocol.reset()
        AgentCoreDiagnostics.shared.reset()
    }

    override func tearDown() {
        StubURLProtocol.reset()
        AgentCoreDiagnostics.shared.reset()
        super.tearDown()
    }

    // MARK: - Kotlin 常量

    /// 仓库根：只要求 `settings.gradle.kts` + `agentcore/` 目录在（**不写死源集布局**）。
    ///
    /// 2026-11 的 `:agentcore` KMP 化把 Kotlin 源集从 `src/main/kotlin` 改成了
    /// `src/commonMain/kotlin`；这里以前会 `fatalError` —— 一个路径漂移就让**整个 `swift test`
    /// 进程中止**，等于把一条用例的定位问题升级成「全部测试跑不完」。现在找不到就返回 nil，
    /// 由用例 `XCTSkip` 并写清找过哪些路径。
    private static func findRepoRoot(file: String = #filePath) -> URL? {
        var url = URL(fileURLWithPath: file).deletingLastPathComponent()
        let fileManager = FileManager.default
        while url.path != "/" {
            if fileManager.fileExists(atPath: url.appendingPathComponent("settings.gradle.kts").path),
               fileManager.fileExists(atPath: url.appendingPathComponent("agentcore").path) {
                return url
            }
            url = url.deletingLastPathComponent()
        }
        return nil
    }

    /// `AgentCoreProtocol.kt` 的候选路径：常见 Kotlin 源集逐个试，不写死一层。
    private static func protocolCandidates(repoRoot: URL) -> [URL] {
        let relative = "com/myfriend/app/domain/AgentCoreProtocol.kt"
        let sourceSets = [
            "commonMain", "jvmMain", "iosMain", "main", "androidMain", "nativeMain", "concurrentMain"
        ]
        return sourceSets.map { repoRoot.appendingPathComponent("agentcore/src/\($0)/kotlin/\(relative)") }
    }

    /// 读 Kotlin 协议文件；找不到就 skip（把试过的路径写进 skip 信息）。
    private func protocolText() throws -> String {
        guard let repoRoot = Self.findRepoRoot() else {
            throw XCTSkip(
                "找不到仓库根（向上没有 settings.gradle.kts + agentcore/）；#filePath=\(#filePath)"
            )
        }
        let candidates = Self.protocolCandidates(repoRoot: repoRoot)
        guard let file = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }),
              let text = try? String(contentsOf: file, encoding: .utf8) else {
            throw XCTSkip(
                "找不到 AgentCoreProtocol.kt。找过：\n\(candidates.map(\.path).joined(separator: "\n"))"
            )
        }
        return text
    }

    private func constant(_ name: String) throws -> String {
        // 找不到仓库/文件就 skip（不再 fatalError：一条定位问题不该中止整轮 swift test）。
        let text = try protocolText()
        let pattern = "const val \(name) = \"([^\"]+)\""
        let expression = try NSRegularExpression(pattern: pattern)
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = expression.firstMatch(in: text, range: range),
              let valueRange = Range(match.range(at: 1), in: text) else {
            XCTFail("Kotlin 协议里找不到常量 \(name)（改名了？）")
            return name
        }
        return String(text[valueRange])
    }

    private func makeClient() -> RemoteAgentCoreClient {
        RemoteAgentCoreClient(baseURL: Self.baseURL, session: StubURLProtocol.makeSession())
    }

    private static func sleepInput() -> SleepInput {
        SleepInput(
            sleepEnabled: true, dndOnSleep: false, sleepMinutes: 1380, wakeMinutes: 420,
            nowMinutes: 600, userMessagesInWindow: 0, wakeThreshold: 6
        )
    }

    // MARK: - 四个响应

    func testSleepResponseFieldsMatchAndroidConstants() async throws {
        let wake = try constant("FIELD_WAKE")
        let relaxed = try constant("FIELD_MOOD_RELAXED")
        let reason = try constant("FIELD_REASON")
        let marksRead = try constant("FIELD_MARKS_READ")
        StubURLProtocol.enqueue(.json(
            #"{"\#(wake)":true,"\#(relaxed)":true,"\#(reason)":"被叫醒了","\#(marksRead)":true}"#
        ))
        let decision = try await makeClient().sleepDecision(Self.sleepInput())
        XCTAssertTrue(decision.wake)
        XCTAssertTrue(decision.moodRelaxed)
        XCTAssertEqual(decision.reason, "被叫醒了")
        XCTAssertTrue(decision.marksRead)
    }

    func testDeferPlanResponseFieldsMatchAndroidConstants() async throws {
        let minutes = try constant("FIELD_MINUTES")
        let reason = try constant("FIELD_REASON")
        let earliest = try constant("FIELD_EARLIEST_AT")
        let latest = try constant("FIELD_LATEST_AT")
        StubURLProtocol.enqueue(.json(
            #"{"\#(minutes)":47,"\#(reason)":"过会儿再说","\#(earliest)":111,"\#(latest)":222}"#
        ))
        let plan = try await makeClient().deferPlan(minutes: 30, reason: nil, now: 0)
        XCTAssertEqual(plan.minutes, 47)
        XCTAssertEqual(plan.reason, "过会儿再说")
        XCTAssertEqual(plan.earliestAt, 111)
        XCTAssertEqual(plan.latestAt, 222)
    }

    func testProactiveCapResponseFieldsMatchAndroidConstants() async throws {
        let dailyCap = try constant("FIELD_DAILY_CAP")
        StubURLProtocol.enqueue(.json(#"{"\#(dailyCap)":4}"#))
        let cap = try await makeClient().proactiveCap(level: 3, moodIntensity: 2)
        XCTAssertEqual(cap, 4)
    }

    func testToolArgsResponseFieldsMatchAndroidConstants() async throws {
        let args = try constant("FIELD_ARGS")
        StubURLProtocol.enqueue(.json(
            #"{"\#(args)":{"importance":9,"drawer":"USER","extra":true}}"#
        ))
        let clamped = try await makeClient().clampToolArgs(
            tool: AgentCoreTool.remember,
            args: ["importance": .int(99), "drawer": .string("USER"), "extra": .bool(true)]
        )
        guard case .int(let importance)? = clamped["importance"] else {
            return XCTFail("importance 应当是整数：\(String(describing: clamped["importance"]))")
        }
        XCTAssertEqual(importance, 9)
        guard case .string(let drawer)? = clamped["drawer"] else {
            return XCTFail("drawer 应当原样保留")
        }
        XCTAssertEqual(drawer, "USER")
        guard case .bool(let extra)? = clamped["extra"] else {
            return XCTFail("不认识的键必须原样保留")
        }
        XCTAssertTrue(extra)
    }

    /// 请求路径也来自同一份 Kotlin 常量（改路径两条端都会漂）。
    func testRequestPathsMatchAndroidConstants() async throws {
        StubURLProtocol.enqueue(.json(
            #"{"wake":true,"moodRelaxed":false,"reason":"x","marksRead":true}"#
        ))
        _ = try await makeClient().sleepDecision(Self.sleepInput())
        let request = try XCTUnwrap(StubURLProtocol.recordedRequests.last)
        let expected = try constant("SLEEP_DECISION_PATH")
        XCTAssertEqual(request.url?.path, expected)
    }
}
