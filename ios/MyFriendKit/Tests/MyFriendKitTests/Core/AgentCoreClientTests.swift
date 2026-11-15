import Foundation
import XCTest
@testable import MyFriendKit

/// agent 决策层（`AgentCoreClient`）的测试。
///
/// 三件事必须钉死：
///
/// 1. **本机实现与契约用例一致**：`NativeAgentCoreClient` 只是把 `SleepGate` / `DeferReply` /
///    `ProactiveLevel` / `ToolArgClamp` 包了一层，跑同一批 `spec/agent-core/cases/*.json`，
///    结果必须与 `AgentCoreSpecTests` 里的 Domain 断言一模一样；
/// 2. **远程实现的报文形状**：请求方法 / 路径 / JSON 体 / 响应解码 / 非 200 / 坏 JSON / 超时，
///    用自定义 `URLProtocol` 桩逐条验证；
/// 3. **回退与零请求**：远程失败必须回退本机并记日志；远程成功不许偷偷再调本机；
///    `.native` 模式下一条网络请求都不许发。
///
/// 另外还有一条 golden 断言：请求/响应字段名与 `spec/agent-core` 和 Android 的
/// `AgentCoreProtocol.kt` 对齐 —— 两端谁改了字段名，这里先红。
final class AgentCoreClientTests: XCTestCase {

    private static let baseURL = URL(string: "http://127.0.0.1:8788")!

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
    }

    override func tearDownWithError() throws {
        StubURLProtocol.reset()
        try super.tearDownWithError()
    }

    // MARK: - 仓库与契约用例

    private static let repoRoot: URL = {
        var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let fm = FileManager.default
        while url.path != "/" {
            if fm.fileExists(atPath: url.appendingPathComponent("spec/agent-core/cases").path),
               fm.fileExists(atPath: url.appendingPathComponent("settings.gradle.kts").path) {
                return url
            }
            url = url.deletingLastPathComponent()
        }
        fatalError("找不到仓库根目录（从 #filePath=\(#filePath) 向上没有 spec/agent-core/cases）")
    }()

    /// `AgentCoreProtocol.kt` 可能出现的相对路径。
    ///
    /// 2026-11 的 `:agentcore` KMP 化把源集从 `src/main/kotlin` 改成了 `src/commonMain/kotlin`
    /// （将来也可能出现 `jvmMain` / `iosMain` 之类的细分源集），所以这里**不写死一层**：
    /// 常见源集逐个试，谁先存在用谁。找不到时把试过的路径全部写进 skip 信息。
    static func agentCoreProtocolCandidates() -> [URL] {
        let relative = "com/myfriend/app/domain/AgentCoreProtocol.kt"
        let sourceSets = [
            "commonMain", "jvmMain", "iosMain", "main", "androidMain", "nativeMain", "concurrentMain"
        ]
        return sourceSets.map {
            repoRoot.appendingPathComponent("agentcore/src/\($0)/kotlin/\(relative)")
        }
    }

    private struct SpecCase {
        var name: String
        var input: [String: Any]
        var expect: [String: Any]
    }

    private func loadCases(_ fileName: String) throws -> [SpecCase] {
        let file = Self.repoRoot.appendingPathComponent("spec/agent-core/cases/\(fileName).json")
        let data = try Data(contentsOf: file)
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawCases = root["cases"] as? [[String: Any]] else {
            throw SpecFailure.message("用例文件格式不对：\(file.path)")
        }
        XCTAssertFalse(rawCases.isEmpty, "\(fileName) 不能没有用例")
        return rawCases.map { entry in
            SpecCase(
                name: entry["name"] as? String ?? "<unnamed>",
                input: entry["input"] as? [String: Any] ?? [:],
                expect: entry["expect"] as? [String: Any] ?? [:]
            )
        }
    }

    private func loadSpec(_ fileName: String) throws -> [String: Any] {
        let file = Self.repoRoot.appendingPathComponent("spec/agent-core/cases/\(fileName).json")
        let data = try Data(contentsOf: file)
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw SpecFailure.message("契约文件格式不对：\(file.path)")
        }
        return root
    }

    private enum SpecFailure: Error, CustomStringConvertible {
        case message(String)
        case mismatch(String)
        var description: String {
            switch self {
            case .message(let text): return text
            case .mismatch(let text): return text
            }
        }
    }

    // MARK: - 夹具

    private static func sleepInput(
        sleepEnabled: Bool = true,
        dndOnSleep: Bool = false,
        sleepMinutes: Int? = nil,
        wakeMinutes: Int? = nil,
        nowMinutes: Int = 1260,
        userMessagesInWindow: Int = 1,
        wakeThreshold: Int = 3
    ) -> SleepInput {
        SleepInput(
            sleepEnabled: sleepEnabled,
            dndOnSleep: dndOnSleep,
            sleepMinutes: sleepMinutes,
            wakeMinutes: wakeMinutes,
            nowMinutes: nowMinutes,
            userMessagesInWindow: userMessagesInWindow,
            wakeThreshold: wakeThreshold
        )
    }

    private func makeRemoteClient(
        timeouts: AgentCoreTimeouts = .default,
        session: URLSession? = nil
    ) -> RemoteAgentCoreClient {
        RemoteAgentCoreClient(
            baseURL: Self.baseURL,
            timeouts: timeouts,
            session: session ?? StubURLProtocol.makeSession()
        )
    }

    private func recordedRequest(index: Int = 0) throws -> URLRequest {
        let requests = StubURLProtocol.recordedRequests
        guard requests.indices.contains(index) else {
            throw SpecFailure.message("应该发出过第 \(index + 1) 次请求，实际 \(requests.count) 次")
        }
        return requests[index]
    }

    private func recordedBody(index: Int = 0) throws -> [String: Any] {
        let bodies = StubURLProtocol.recordedBodies
        guard bodies.indices.contains(index) else {
            throw SpecFailure.message("第 \(index + 1) 次请求没有 body")
        }
        let object = try JSONSerialization.jsonObject(with: bodies[index])
        guard let dictionary = object as? [String: Any] else {
            throw SpecFailure.message("请求体不是 JSON 对象")
        }
        return dictionary
    }

    /// 把契约用例里的 `[String: Any]` 参数表换成 `[String: JSONValue]`。
    private static func jsonArgs(_ raw: [String: Any]) -> [String: JSONValue] {
        raw.mapValues(AgentCoreJSON.value(from:))
    }

    // MARK: - 1. 本机实现跑契约用例

    func testNativeSleepDecisionMatchesSpecCases() async throws {
        let client = NativeAgentCoreClient()
        for item in try loadCases("sleep-decision") {
            let input = item.input
            let sleepInput = SleepInput(
                sleepEnabled: try input.bool("sleepEnabled"),
                dndOnSleep: try input.bool("dndOnSleep"),
                sleepMinutes: input.optionalInt("sleepMinutes"),
                wakeMinutes: input.optionalInt("wakeMinutes"),
                nowMinutes: try input.int("nowMinutes"),
                userMessagesInWindow: try input.int("userMessagesInWindow"),
                wakeThreshold: try input.int("wakeThreshold")
            )
            let decision = try await client.sleepDecision(sleepInput)
            let expected = item.expect
            XCTAssertEqual(decision.wake, expected.bool("shouldReply"), "[\(item.name)] wake")
            XCTAssertEqual(decision.moodRelaxed, expected.bool("woken"), "[\(item.name)] moodRelaxed")
            XCTAssertEqual(decision.marksRead, expected.bool("marksRead"), "[\(item.name)] marksRead")
            XCTAssertEqual(decision.replyDecision, Self.replyDecision(expected), "[\(item.name)] replyDecision")
            XCTAssertFalse(decision.reason.isEmpty, "[\(item.name)] reason 不能为空")

            // reason 的四句话与 Android `InProcessAgentCore` 逐字一致（"睡着了 · HH:MM 醒" 里的
            // 时刻由人格作息决定，这里只钉前缀）。
            if expected.bool("asleep") == false {
                XCTAssertEqual(decision.reason, "醒着，会正常回复", "[\(item.name)] reason")
            } else if expected.bool("dnd") {
                XCTAssertEqual(decision.reason, "免打扰中，不会醒", "[\(item.name)] reason")
            } else if expected.bool("woken") {
                XCTAssertEqual(decision.reason, "被你叫醒了，缓了缓", "[\(item.name)] reason")
            } else {
                XCTAssertTrue(
                    decision.reason == "睡着了" || decision.reason.hasPrefix("睡着了 · "),
                    "[\(item.name)] reason=\(decision.reason)"
                )
            }
        }
    }

    func testNativeDeferPlanMatchesSpecCases() async throws {
        let client = NativeAgentCoreClient()
        for item in try loadCases("defer-plan") {
            let plan = try await client.deferPlan(
                minutes: item.input.optionalInt("minutes"),
                reason: item.input.optionalString("reason"),
                now: try item.input.int64("now")
            )
            XCTAssertEqual(plan.minutes, try item.expect.int("minutes"), "[\(item.name)] minutes")
            XCTAssertEqual(plan.reason, try item.expect.string("reason"), "[\(item.name)] reason")
            XCTAssertEqual(plan.earliestAt, try item.expect.int64("earliestAt"), "[\(item.name)] earliestAt")
            XCTAssertEqual(plan.latestAt, try item.expect.int64("latestAt"), "[\(item.name)] latestAt")
        }
    }

    func testNativeProactiveCapMatchesSpecCases() async throws {
        let client = NativeAgentCoreClient()
        for item in try loadCases("proactive-cap") {
            let level = try item.input.int("level")
            let intensity = (item.input["mood"] as? [String: Any])?.optionalInt("intensity")
            let cap = try await client.proactiveCap(level: level, moodIntensity: intensity)
            XCTAssertEqual(cap, try item.expect.int("baseDailyCap"), "[\(item.name)] baseDailyCap")
        }
    }

    func testNativeToolArgsMatchesSpecCases() async throws {
        let client = NativeAgentCoreClient()
        for item in try loadCases("tool-args") {
            let tool = try item.input.string("tool")
            let args = try item.input.object("args")
            let actual = try await client.clampToolArgs(tool: tool, args: Self.jsonArgs(args))
            let expected = Self.jsonArgs(try item.expect.object("args"))
            XCTAssertEqual(actual, expected, "[\(item.name)] 夹取后的参数（tool=\(tool)）")
        }
    }

    func testNativeToolArgsKeepsUnknownToolUntouched() async throws {
        let client = NativeAgentCoreClient()
        let args: [String: JSONValue] = ["path": .string("/tmp/x"), "depth": .int(9)]
        let actual = try await client.clampToolArgs(tool: "rm_rf", args: args)
        XCTAssertEqual(actual, args, "不认识的工具应该原样返回（拒绝是服务端 HTTP 层的事）")
    }

    // MARK: - 2. 远程实现：报文形状与解码

    func testRemoteSleepDecisionRequestAndResponse() async throws {
        StubURLProtocol.enqueue(.json(
            #"{"wake":false,"moodRelaxed":false,"reason":"睡着了 · 08:00 醒","marksRead":false}"#
        ))
        let client = makeRemoteClient()
        let input = Self.sleepInput(dndOnSleep: true, nowMinutes: 1410, userMessagesInWindow: 2, wakeThreshold: 3)
        let decision = try await client.sleepDecision(input)

        XCTAssertEqual(decision, SleepDecision(
            wake: false, moodRelaxed: false, reason: "睡着了 · 08:00 醒", marksRead: false
        ))
        XCTAssertEqual(decision.replyDecision, .sleepingDoNotDisturb)

        let request = try recordedRequest()
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/core/sleep-decision")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json; charset=utf-8")
        let body = try recordedBody()
        XCTAssertEqual(Set(body.keys), [
            "sleepEnabled", "dndOnSleep", "sleepMinutes", "wakeMinutes",
            "nowMinutes", "userMessagesInWindow", "wakeThreshold"
        ])
        XCTAssertEqual(body["sleepEnabled"] as? Bool, true)
        XCTAssertEqual(body["dndOnSleep"] as? Bool, true)
        XCTAssertTrue(body["sleepMinutes"] is NSNull, "可空字段要显式写 null（与 Android 报文一致）")
        XCTAssertTrue(body["wakeMinutes"] is NSNull)
        XCTAssertEqual(body["nowMinutes"] as? Int, 1410)
        XCTAssertEqual(body["userMessagesInWindow"] as? Int, 2)
        XCTAssertEqual(body["wakeThreshold"] as? Int, 3)
    }

    func testRemoteSleepDecisionToleratesLegacyServerWithoutMarksRead() async throws {
        // 0.10.x 的 agentd 还没有 marksRead：缺字段要按「会留已读」处理，不能解码失败。
        StubURLProtocol.enqueue(.json(#"{"wake":false,"moodRelaxed":false,"reason":"睡着了"}"#))
        let decision = try await makeRemoteClient().sleepDecision(Self.sleepInput())
        XCTAssertTrue(decision.marksRead, "老服务端缺 marksRead 时默认留已读")
        XCTAssertEqual(decision.replyDecision, .sleepingWaiting)
    }

    func testRemoteDeferPlanRequestAndResponse() async throws {
        StubURLProtocol.enqueue(.json(
            #"{"earliestAt":1700002700000,"latestAt":1700004500000,"minutes":45,"reason":"缓一缓"}"#
        ))
        let client = makeRemoteClient()
        let plan = try await client.deferPlan(minutes: nil, reason: nil, now: 1_700_000_000_000)

        XCTAssertEqual(plan, DeferPlan(
            minutes: 45, reason: "缓一缓", earliestAt: 1_700_002_700_000, latestAt: 1_700_004_500_000
        ))

        let request = try recordedRequest()
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/core/defer-plan")
        let body = try recordedBody()
        XCTAssertEqual(Set(body.keys), ["minutes", "reason", "now"])
        XCTAssertTrue(body["minutes"] is NSNull)
        XCTAssertTrue(body["reason"] is NSNull)
        XCTAssertEqual(body["now"] as? Int64, 1_700_000_000_000)
    }

    func testRemoteDeferPlanToleratesStringEncodedNumbers() async throws {
        // agentd 的 `JsonValue.Num` 内部按文本存数字；万一哪天序列化成字符串也要读得出来。
        StubURLProtocol.enqueue(.json(
            #"{"earliestAt":"1700002700000","latestAt":"1700004500000","minutes":"45","reason":""}"#
        ))
        let plan = try await makeRemoteClient().deferPlan(minutes: 45, reason: "", now: 1_700_000_000_000)
        XCTAssertEqual(plan.minutes, 45)
        XCTAssertEqual(plan.earliestAt, 1_700_002_700_000)
        XCTAssertEqual(plan.latestAt, 1_700_004_500_000)
    }

    func testRemoteProactiveCapRequestAndResponse() async throws {
        StubURLProtocol.enqueue(.json(#"{"dailyCap":4}"#))
        let cap = try await makeRemoteClient().proactiveCap(level: 5, moodIntensity: 4)
        XCTAssertEqual(cap, 4)

        let request = try recordedRequest()
        XCTAssertEqual(request.url?.path, "/v1/core/proactive-cap")
        let body = try recordedBody()
        XCTAssertEqual(Set(body.keys), ["level", "moodIntensity"])
        XCTAssertEqual(body["level"] as? Int, 5)
        XCTAssertEqual(body["moodIntensity"] as? Int, 4)
    }

    func testRemoteToolArgsRequestAndResponse() async throws {
        StubURLProtocol.enqueue(.json(
            #"{"args":{"content":"她妹妹叫小雨","drawer":"USER","importance":5}}"#
        ))
        let args: [String: JSONValue] = [
            "content": .string("她妹妹叫小雨"),
            "drawer": .string("USER"),
            "importance": .int(9)
        ]
        let clamped = try await makeRemoteClient().clampToolArgs(tool: AgentCoreTool.remember, args: args)
        XCTAssertEqual(clamped, [
            "content": .string("她妹妹叫小雨"),
            "drawer": .string("USER"),
            "importance": .int(5)
        ])

        let request = try recordedRequest()
        XCTAssertEqual(request.url?.path, "/v1/core/tool-args")
        let body = try recordedBody()
        XCTAssertEqual(Set(body.keys), ["tool", "args"])
        XCTAssertEqual(body["tool"] as? String, "remember")
        let sent = try XCTUnwrap(body["args"] as? [String: Any])
        XCTAssertEqual(sent["importance"] as? Int, 9, "原样把模型给的参数发过去，由内核夹")
    }

    // MARK: - 3. 远程实现的失败路径

    func testRemoteNon200SurfacesServerMessage() async throws {
        StubURLProtocol.enqueue(.json(
            #"{"error":{"message":"字段 now 必须是整数","code":"invalid_argument"}}"#,
            status: 400
        ))
        do {
            _ = try await makeRemoteClient().deferPlan(minutes: 30, reason: nil, now: 0)
            XCTFail("非 200 应该抛错")
        } catch let error as AgentCoreClientError {
            XCTAssertEqual(error, .http(status: 400, message: "字段 now 必须是整数（invalid_argument）"))
        }
    }

    func testRemoteBrokenJSONSurfacesBadJSON() async throws {
        StubURLProtocol.enqueue(.json("这不是 JSON", status: 200))
        do {
            _ = try await makeRemoteClient().sleepDecision(Self.sleepInput())
            XCTFail("坏 JSON 应该抛错")
        } catch let error as AgentCoreClientError {
            guard case .badJSON = error else {
                return XCTFail("期望 badJSON，实际 \(error)")
            }
        }
    }

    func testRemoteMissingRequiredFieldSurfaces() async throws {
        StubURLProtocol.enqueue(.json(#"{"moodRelaxed":true,"reason":"x","marksRead":true}"#))
        do {
            _ = try await makeRemoteClient().sleepDecision(Self.sleepInput())
            XCTFail("缺 wake 应该抛错")
        } catch let error as AgentCoreClientError {
            XCTAssertEqual(error, .missingField("wake"))
        }
    }

    func testRemoteTimeoutSurfacesAsTimedOut() async throws {
        let session = Self.neverRespondingSession(connect: 0.15, overall: 0.3)
        let client = RemoteAgentCoreClient(
            baseURL: Self.baseURL,
            timeouts: AgentCoreTimeouts(connect: 0.15, overall: 0.3),
            session: session
        )
        let started = Date()
        do {
            _ = try await client.sleepDecision(Self.sleepInput())
            XCTFail("超时应该抛错")
        } catch let error as AgentCoreClientError {
            guard case .timedOut = error else {
                return XCTFail("期望 timedOut，实际 \(error)")
            }
        } catch {
            XCTFail("期望 AgentCoreClientError.timedOut，实际 \(error)")
        }
        XCTAssertLessThan(Date().timeIntervalSince(started), 2.0, "超时必须真的在 8 秒以内结束")
    }

    // MARK: - 4. 回退

    func testFallbackUsesNativeWhenRemoteFails() async throws {
        StubURLProtocol.enqueue(.json(
            #"{"error":{"message":"服务内部错误","code":"internal_error"}}"#,
            status: 500
        ))
        let log = LogBox()
        let native = CountingAgentCoreClient()
        let client = FallbackAgentCoreClient(
            mode: .remote(preferredFallback: true),
            remote: makeRemoteClient(),
            native: native,
            log: { log.append($0) }
        )
        let input = Self.sleepInput(dndOnSleep: true, nowMinutes: 1410)
        let decision = try await client.sleepDecision(input)

        XCTAssertEqual(decision, NativeAgentCoreClient().sleepDecisionNow(input))
        XCTAssertEqual(native.count("sleepDecision"), 1, "远程失败后应该回退本机一次")
        XCTAssertTrue(
            log.all.contains { $0.contains("sleep-decision") && $0.contains("回退本机") },
            "回退必须留下日志：\(log.all)"
        )
    }

    func testFallbackRemoteSuccessDoesNotCallNative() async throws {
        StubURLProtocol.enqueue(.json(
            #"{"earliestAt":1,"latestAt":2,"minutes":7,"reason":""}"#
        ))
        let log = LogBox()
        let native = CountingAgentCoreClient()
        let client = FallbackAgentCoreClient(
            mode: .remote(preferredFallback: true),
            remote: makeRemoteClient(),
            native: native,
            log: { log.append($0) }
        )
        let plan = try await client.deferPlan(minutes: 7, reason: nil, now: 0)

        XCTAssertEqual(plan, DeferPlan(minutes: 7, reason: "", earliestAt: 1, latestAt: 2))
        XCTAssertEqual(native.count("deferPlan"), 0, "远程成功时不许再调本机")
        XCTAssertTrue(log.all.isEmpty, "成功路径不该写回退日志：\(log.all)")
    }

    func testStrictRemoteDoesNotFallBack() async throws {
        StubURLProtocol.enqueue(.json(
            #"{"error":{"message":"boom","code":"internal_error"}}"#,
            status: 500
        ))
        let native = CountingAgentCoreClient()
        let client = FallbackAgentCoreClient(
            mode: .remote(preferredFallback: false),
            remote: makeRemoteClient(),
            native: native
        )
        do {
            _ = try await client.sleepDecision(Self.sleepInput())
            XCTFail("严格远程模式应该把错误抛出来")
        } catch let error as AgentCoreClientError {
            XCTAssertEqual(error, .http(status: 500, message: "boom（internal_error）"))
        }
        XCTAssertEqual(native.count("sleepDecision"), 0, "严格远程模式不回退")
    }

    func testFallbackInNativeModeNeverUsesRemote() async throws {
        let log = LogBox()
        let native = CountingAgentCoreClient()
        let client = FallbackAgentCoreClient(
            mode: .native,
            remote: makeRemoteClient(),
            native: native,
            log: { log.append($0) }
        )
        _ = try await client.sleepDecision(Self.sleepInput())
        _ = try await client.deferPlan(minutes: nil, reason: nil, now: 0)
        _ = try await client.proactiveCap(level: 3, moodIntensity: nil)
        _ = try await client.clampToolArgs(tool: AgentCoreTool.remember, args: [:])
        XCTAssertEqual(native.totalCalls, 4)
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, ".native 模式不许发请求")
        XCTAssertTrue(log.all.isEmpty)
    }

    // MARK: - 5. 选择机制

    func testNativeModeMakesZeroNetworkRequests() async throws {
        let session = StubURLProtocol.makeSession()
        let client = AgentCoreClientFactory.make(
            mode: .native,
            baseURL: Self.baseURL,
            session: session
        )
        XCTAssertTrue(client is NativeAgentCoreClient)

        _ = try await client.sleepDecision(Self.sleepInput())
        _ = try await client.deferPlan(minutes: nil, reason: nil, now: 0)
        _ = try await client.proactiveCap(level: 3, moodIntensity: nil)
        _ = try await client.clampToolArgs(tool: AgentCoreTool.remember, args: [:])

        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, ".native 模式零请求")
    }

    func testFactoryResolvesModeFromEnvironmentThenInfoPlistThenNative() {
        XCTAssertEqual(
            AgentCoreClientFactory.resolveMode(environment: [:], info: nil),
            .native, "都没有时默认本机"
        )
        XCTAssertEqual(
            AgentCoreClientFactory.resolveMode(
                environment: [:], info: ["MFAgenCoreMode": "remote"]
            ),
            .remote(preferredFallback: true)
        )
        XCTAssertEqual(
            AgentCoreClientFactory.resolveMode(
                environment: ["MYFRIEND_AGENT_CORE": "native"],
                info: ["MFAgenCoreMode": "remote"]
            ),
            .native, "环境变量优先于 Info.plist"
        )
        XCTAssertEqual(
            AgentCoreClientFactory.resolveMode(
                environment: ["MYFRIEND_AGENT_CORE": "remote-strict"], info: nil
            ),
            .remote(preferredFallback: false)
        )
        XCTAssertEqual(
            AgentCoreClientFactory.resolveMode(
                environment: ["MYFRIEND_AGENT_CORE": "  REMOTE  "], info: nil
            ),
            .remote(preferredFallback: true), "大小写/空白要宽容"
        )
        XCTAssertEqual(
            AgentCoreClientFactory.resolveMode(
                environment: ["MYFRIEND_AGENT_CORE": "不认识"], info: nil
            ),
            .native, "解析不了就退回默认，不猜"
        )
    }

    func testFactoryResolvesBaseURLFromEnvironmentThenInfoPlistThenDefault() {
        XCTAssertEqual(
            AgentCoreClientFactory.resolveBaseURL(environment: [:], info: nil),
            Self.baseURL
        )
        XCTAssertEqual(
            AgentCoreClientFactory.resolveBaseURL(
                environment: ["MYFRIEND_AGENT_CORE_URL": "http://192.168.1.9:9000/"],
                info: ["MFAgenCoreBaseURL": "http://127.0.0.1:1"]
            ).absoluteString,
            "http://192.168.1.9:9000/", "环境变量优先"
        )
        XCTAssertEqual(
            AgentCoreClientFactory.resolveBaseURL(
                environment: [:], info: ["MFAgenCoreBaseURL": "http://127.0.0.1:8789"]
            ).absoluteString,
            "http://127.0.0.1:8789"
        )
        XCTAssertEqual(
            AgentCoreClientFactory.resolveBaseURL(
                environment: ["MYFRIEND_AGENT_CORE_URL": "不是地址"], info: nil
            ),
            Self.baseURL, "坏地址退回默认"
        )
    }

    func testFactoryBuildsFallbackRemoteClientWithConfiguredBaseURL() async throws {
        StubURLProtocol.enqueue(.json(#"{"wake":true,"moodRelaxed":false,"reason":"醒着，会正常回复","marksRead":true}"#))
        let client = AgentCoreClientFactory.make(
            mode: .remote(preferredFallback: true),
            baseURL: URL(string: "http://192.168.1.9:9000")!,
            session: StubURLProtocol.makeSession(),
            log: { _ in }
        )
        let fallback = try XCTUnwrap(client as? FallbackAgentCoreClient)
        XCTAssertEqual(fallback.mode, .remote(preferredFallback: true))
        let decision = try await client.sleepDecision(Self.sleepInput())
        XCTAssertTrue(decision.wake)

        let request = try recordedRequest()
        XCTAssertEqual(request.url?.host, "192.168.1.9")
        XCTAssertEqual(request.url?.port, 9000)
        XCTAssertEqual(request.url?.path, "/v1/core/sleep-decision")
    }

    func testFactoryStrictRemoteReturnsRemoteClientDirectly() {
        let client = AgentCoreClientFactory.make(
            mode: .remote(preferredFallback: false),
            baseURL: Self.baseURL,
            session: StubURLProtocol.makeSession()
        )
        XCTAssertTrue(client is RemoteAgentCoreClient)
    }

    // MARK: - 6. 字段名 golden（防两端改名漂移）

    func testRequestFieldNamesMatchSpecInputSpec() throws {
        // 睡眠：客户端发出去的 7 个字段 = 契约 inputSpec 去掉 `mood`（`mood` 是「被叫醒后的心情」
        // 用例参数，只给 Swift/Kotlin 测试补 moodAfter 用，不在 HTTP 请求体里）。
        let sleepSpec = try loadSpec("sleep-decision")
        let sleepInputSpec = try XCTUnwrap(sleepSpec["inputSpec"] as? [String: Any])
        let sleepKeys = Set(sleepInputSpec.keys).subtracting(["mood"])
        XCTAssertEqual(
            sleepKeys,
            ["sleepEnabled", "dndOnSleep", "sleepMinutes", "wakeMinutes",
             "nowMinutes", "userMessagesInWindow", "wakeThreshold"]
        )
        let encodedSleep = try JSONEncoder().encode(Self.sleepInput())
        XCTAssertEqual(try Self.jsonKeys(encodedSleep), sleepKeys, "SleepInput 的编码字段")

        // defer_reply：inputSpec 三个字段与请求体一字不差。
        let deferSpec = try loadSpec("defer-plan")
        let deferInputSpec = try XCTUnwrap(deferSpec["inputSpec"] as? [String: Any])
        let deferKeys = Set(deferInputSpec.keys)
        XCTAssertEqual(deferKeys, ["minutes", "reason", "now"])
        let encodedDefer = try JSONEncoder().encode(
            DeferPlanRequest(minutes: nil, reason: nil, now: 0)
        )
        XCTAssertEqual(try Self.jsonKeys(encodedDefer), deferKeys)

        // proactive-cap：inputSpec 是 `level` + 嵌套 `mood`；HTTP 契约把它拆成
        // `moodLabel` / `moodIntensity`（见 agentd README）。客户端的协议只有强度，
        // 所以发出去的 key 只能是 `level` + `moodIntensity`，且都必须被服务端接受。
        let capSpec = try loadSpec("proactive-cap")
        let capInputSpec = try XCTUnwrap(capSpec["inputSpec"] as? [String: Any])
        XCTAssertTrue(Set(capInputSpec.keys).isSuperset(of: ["level", "mood"]))
        let encodedCap = try JSONEncoder().encode(ProactiveCapRequest(level: 3, moodIntensity: 2))
        XCTAssertEqual(try Self.jsonKeys(encodedCap), ["level", "moodIntensity"])
        XCTAssertTrue(
            Set(["level", "moodLabel", "moodIntensity"]).isSuperset(of: try Self.jsonKeys(encodedCap)),
            "moodIntensity 必须在服务端接受的字段里"
        )

        // tool-args：inputSpec 两个字段与请求体一字不差。
        let toolSpec = try loadSpec("tool-args")
        let toolInputSpec = try XCTUnwrap(toolSpec["inputSpec"] as? [String: Any])
        XCTAssertEqual(Set(toolInputSpec.keys), ["tool", "args"])
        let encodedTool = try JSONEncoder().encode(
            ToolArgsRequest(tool: AgentCoreTool.remember, args: ["importance": .int(9)])
        )
        XCTAssertEqual(try Self.jsonKeys(encodedTool), ["tool", "args"])
    }

    /// 与 Android 的 `AgentCoreProtocol.kt` 对齐：那份常量是两端字段名的唯一出处。
    ///
    /// 文件找不到就跳过（iOS 单独 checkout 的情况），找到就必须完全一致。
    func testWireFieldNamesMatchAndroidAgentCoreProtocol() throws {
        let candidates = Self.agentCoreProtocolCandidates()
        guard let file = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }),
              let text = try? String(contentsOf: file, encoding: .utf8) else {
            throw XCTSkip(
                """
                没有找到 AgentCoreProtocol.kt（iOS 单独 checkout 时正常）。找过：
                \(candidates.map(\.path).joined(separator: "\n"))
                """
            )
        }
        let fields = Self.protocolFieldConstants(in: text)
        func field(_ name: String) throws -> String {
            guard let value = fields[name] else {
                throw SpecFailure.mismatch("AgentCoreProtocol.kt 里没有 \(name)")
            }
            return value
        }

        XCTAssertEqual(try Self.jsonKeys(JSONEncoder().encode(Self.sleepInput())), [
            try field("FIELD_SLEEP_ENABLED"),
            try field("FIELD_DND_ON_SLEEP"),
            try field("FIELD_SLEEP_MINUTES"),
            try field("FIELD_WAKE_MINUTES"),
            try field("FIELD_NOW_MINUTES"),
            try field("FIELD_USER_MESSAGES_IN_WINDOW"),
            try field("FIELD_WAKE_THRESHOLD")
        ])
        XCTAssertEqual(try Self.jsonKeys(JSONEncoder().encode(
            DeferPlanRequest(minutes: nil, reason: nil, now: 0)
        )), [
            try field("FIELD_MINUTES"), try field("FIELD_REASON"), try field("FIELD_NOW")
        ])
        XCTAssertEqual(try Self.jsonKeys(JSONEncoder().encode(
            ProactiveCapRequest(level: 3, moodIntensity: 1)
        )), [try field("FIELD_LEVEL"), try field("FIELD_MOOD_INTENSITY")])
        XCTAssertEqual(try Self.jsonKeys(JSONEncoder().encode(
            ToolArgsRequest(tool: AgentCoreTool.webSearch, args: [:])
        )), [try field("FIELD_TOOL"), try field("FIELD_ARGS")])

        // 响应字段：用一份「只含契约字段」的报文体解码，缺一个就会失败。
        let sleepJSON = """
        {"\(try field("FIELD_WAKE"))":true,"\(try field("FIELD_MOOD_RELAXED"))":true,
         "\(try field("FIELD_REASON"))":"x","\(try field("FIELD_MARKS_READ"))":true}
        """
        let sleepDecision = try XCTUnwrap(
            try JSONDecoder().decode([String: JSONValue].self, from: Data(sleepJSON.utf8))
        )
        XCTAssertTrue(try XCTUnwrap(SleepDecision(json: sleepDecision) as SleepDecision?).wake)

        let deferJSON = """
        {"\(try field("FIELD_EARLIEST_AT"))":1,"\(try field("FIELD_LATEST_AT"))":2,
         "\(try field("FIELD_MINUTES"))":3,"\(try field("FIELD_REASON"))":""}
        """
        let deferObject = try JSONDecoder().decode([String: JSONValue].self, from: Data(deferJSON.utf8))
        XCTAssertEqual(try AgentCoreJSON.requiredInt64(deferObject, try field("FIELD_EARLIEST_AT")), 1)

        let capJSON = #"{"\#(try field("FIELD_DAILY_CAP"))":4}"#
        let capObject = try JSONDecoder().decode([String: JSONValue].self, from: Data(capJSON.utf8))
        XCTAssertEqual(try AgentCoreJSON.requiredInt(capObject, try field("FIELD_DAILY_CAP")), 4)
    }

    private static func jsonKeys(_ data: Data) throws -> Set<String> {
        let object = try JSONSerialization.jsonObject(with: data)
        guard let dictionary = object as? [String: Any] else {
            throw SpecFailure.message("不是 JSON 对象")
        }
        return Set(dictionary.keys)
    }

    /// 从 `AgentCoreProtocol.kt` 里抽 `const val FIELD_X = "value"`。
    private static func protocolFieldConstants(in text: String) -> [String: String] {
        let regex = try! NSRegularExpression(
            pattern: #"const\s+val\s+(FIELD_[A-Z0-9_]+)\s*=\s*"([^"]+)""#
        )
        var fields: [String: String] = [:]
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        for match in regex.matches(in: text, range: range) {
            guard let nameRange = Range(match.range(at: 1), in: text),
                  let valueRange = Range(match.range(at: 2), in: text) else { continue }
            fields[String(text[nameRange])] = String(text[valueRange])
        }
        return fields
    }

    // MARK: - 7. sleep 判定到聊天语义的映射

    func testSleepDecisionReplyDecisionMapping() {
        XCTAssertEqual(
            SleepDecision(wake: true, moodRelaxed: false, reason: "", marksRead: true).replyDecision,
            .reply
        )
        XCTAssertEqual(
            SleepDecision(wake: true, moodRelaxed: true, reason: "", marksRead: true).replyDecision,
            .wokenUp
        )
        XCTAssertEqual(
            SleepDecision(wake: false, moodRelaxed: false, reason: "", marksRead: true).replyDecision,
            .sleepingWaiting
        )
        XCTAssertEqual(
            SleepDecision(wake: false, moodRelaxed: false, reason: "", marksRead: false).replyDecision,
            .sleepingDoNotDisturb
        )
    }

    // MARK: - 桩

    /// 记录回退日志（`@Sendable` 闭包不能捕获可变 var，收进一把锁里）。
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

    /// 数「本机实现被调了几次」：证明远程成功时不许偷偷再调本机。
    private final class CounterBox: @unchecked Sendable {
        private let lock = NSLock()
        private var values: [String: Int] = [:]

        func hit(_ name: String) {
            lock.lock()
            values[name, default: 0] += 1
            lock.unlock()
        }

        func count(_ name: String) -> Int {
            lock.lock()
            defer { lock.unlock() }
            return values[name] ?? 0
        }

        var total: Int {
            lock.lock()
            defer { lock.unlock() }
            return values.values.reduce(0, +)
        }
    }

    /// 数「本机实现被调了几次」：证明远程成功时不许偷偷再调本机。
    ///
    /// 结果**真的**委托给 `NativeAgentCoreClient`（不是撒一个假值），
    /// 这样「远程失败 → 回退本机」的用例能同时断言「调了本机」与「结果正确」。
    private struct CountingAgentCoreClient: AgentCoreClient {
        let box = CounterBox()
        private let native = NativeAgentCoreClient()

        var totalCalls: Int { box.total }
        func count(_ name: String) -> Int { box.count(name) }

        func sleepDecision(_ input: SleepInput) async throws -> SleepDecision {
            box.hit("sleepDecision")
            return try await native.sleepDecision(input)
        }

        func deferPlan(minutes: Int?, reason: String?, now: Int64) async throws -> DeferPlan {
            box.hit("deferPlan")
            return try await native.deferPlan(minutes: minutes, reason: reason, now: now)
        }

        func proactiveCap(level: Int, moodIntensity: Int?) async throws -> Int {
            box.hit("proactiveCap")
            return try await native.proactiveCap(level: level, moodIntensity: moodIntensity)
        }

        func clampToolArgs(tool: String, args: [String: JSONValue]) async throws -> [String: JSONValue] {
            box.hit("clampToolArgs")
            return try await native.clampToolArgs(tool: tool, args: args)
        }
    }

    /// 永不回包的 `URLProtocol`：用来验证超时。
    private final class NeverRespondingURLProtocol: URLProtocol {
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            // 故意什么都不做：让 URLSession / 客户端的整体超时先到。
        }
        override func stopLoading() {}
    }

    private static func neverRespondingSession(connect: TimeInterval, overall: TimeInterval) -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [NeverRespondingURLProtocol.self]
        configuration.timeoutIntervalForRequest = connect
        configuration.timeoutIntervalForResource = overall
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }

    private static func replyDecision(_ expect: [String: Any]) -> SleepReplyDecision {
        if !expect.bool("asleep") { return .reply }
        if expect.bool("dnd") { return .sleepingDoNotDisturb }
        return expect.bool("woken") ? .wokenUp : .sleepingWaiting
    }
}

// MARK: - 契约用例的 JSON 取值（与 AgentCoreSpecTests 同一套宽容规则）

private extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) throws -> String {
        guard let value = self[key] as? String else {
            throw NSError(domain: "AgentCoreClientTests", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "缺少字符串字段 \(key)"])
        }
        return value
    }

    func optionalString(_ key: String) -> String? { self[key] as? String }

    func int(_ key: String) throws -> Int {
        guard let number = self[key] as? NSNumber else {
            throw NSError(domain: "AgentCoreClientTests", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "缺少整数字段 \(key)"])
        }
        return number.intValue
    }

    func optionalInt(_ key: String) -> Int? { (self[key] as? NSNumber)?.intValue }

    func int64(_ key: String) throws -> Int64 {
        guard let number = self[key] as? NSNumber else {
            throw NSError(domain: "AgentCoreClientTests", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "缺少长整数字段 \(key)"])
        }
        return number.int64Value
    }

    func bool(_ key: String) -> Bool { (self[key] as? NSNumber)?.boolValue ?? false }

    func object(_ key: String) throws -> [String: Any] {
        guard let value = self[key] as? [String: Any] else {
            throw NSError(domain: "AgentCoreClientTests", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "缺少对象字段 \(key)"])
        }
        return value
    }
}
