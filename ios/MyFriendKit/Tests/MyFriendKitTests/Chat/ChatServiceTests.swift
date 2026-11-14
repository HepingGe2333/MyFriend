import Foundation
import XCTest
@testable import MyFriendKit

/// 聊天主链路：真 `URLSession`（自定义 `URLProtocol` 假装流式服务端）+ 真 SQLite。
///
/// 这一层是「所有零件装在一起」的地方，所以断言也偏整体：
/// 事件序列、落库内容、会话预览、用量记账、第二次请求里带了什么。
/// 任何一处接错，用户看到的就是「话说一半」「账没记」「记忆没进去」这类问题。
final class ChatServiceTests: XCTestCase {

    private var directoryURL: URL!
    private var databaseURL: URL!
    private var stores: DatabaseStores!
    private var keychain: InMemoryKeychain!
    private var apiKeys: ApiKeyStore!
    private var settings: InMemorySettings!
    private var recorder: UsageRecorder!
    private var service: ChatService!
    private var background: RecordingBackground!
    private var tools: RecordingTools!

    private let personaId = "p-1"
    private let configId = "cfg-1"
    private let sessionId = "s-1"

    // MARK: - 夹具

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
        directoryURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-chat-tests", isDirectory: true)
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        databaseURL = directoryURL.appendingPathComponent("chat-\(UUID().uuidString).sqlite")
        stores = try DatabaseStores.open(path: databaseURL.path)
        keychain = InMemoryKeychain()
        apiKeys = ApiKeyStore(backend: keychain)
        settings = InMemorySettings(AppSettings())
        recorder = UsageRecorder(stores: stores)
        background = RecordingBackground()
        tools = RecordingTools()
        service = makeService()
        AgentCoreDiagnostics.shared.reset()
    }

    override func tearDownWithError() throws {
        service = nil
        AgentCoreDiagnostics.shared.reset()
        StubURLProtocol.reset()
        stores = nil
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(atPath: databaseURL.path + suffix)
        }
        try super.tearDownWithError()
    }

    private func makeService(
        media: MediaStorage = .shared,
        agentCore: any AgentCoreClient = NativeAgentCoreClient()
    ) -> ChatService {
        ChatService(
            stores: stores,
            apiKeys: apiKeys,
            settings: settings,
            client: OpenAICompatibleClient(session: StubURLProtocol.makeSession(),
                                           usage: recorder, gate: LlmConcurrencyGate()),
            tools: tools,
            background: background,
            media: media,
            agentCore: agentCore
        )
    }

    @discardableResult
    private func seedPersona(prompt: String = "你是小满，说话很轻。") throws -> PersonaEntity {
        // `sleepEnabled: false`：这些用例测的是「生成链路」，不该被「测试机现在几点」影响。
        // 1.9 的默认人格是会睡觉的（23:30–08:00），凌晨跑测试时整条链路会正确走到
        // `ChatNoReplyReason.sleepingWaiting`，于是「没有请求、没有回复」——那是**睡眠判定**的用例
        // （`ChatRhythmTests` / `SleepScheduleTests`）该管的事，不是这里。
        let persona = PersonaEntity(
            id: personaId, name: "小满", prompt: prompt, version: 1, isDefault: true,
            updatedAt: 1, sleepEnabled: false
        )
        try stores.personas.upsert(persona)
        return persona
    }

    @discardableResult
    private func seedConfig(webTools: Bool = false) throws -> ApiConfigEntity {
        let config = ApiConfigEntity(
            id: configId, name: "测试", provider: .openai,
            baseUrl: "https://api.example.com", model: "deepseek-chat",
            updatedAt: 1, webToolsEnabled: webTools
        )
        try stores.apiConfigs.upsert(config)
        return config
    }

    @discardableResult
    private func seedSession(apiConfigId: String? = "cfg-1") throws -> ChatSessionEntity {
        let session = ChatSessionEntity(
            id: sessionId, personaId: personaId, apiConfigId: apiConfigId,
            model: "", title: "与 小满 的对话", createdAt: 1, updatedAt: 1,
            lastPersonaVersion: 1, lastMemoryExtractedCount: 0
        )
        try stores.sessions.upsert(session)
        return session
    }

    private func seedKey(_ key: String = "sk-test") throws {
        try apiKeys.setAPIKey(key, for: configId)
    }

    private func seedMemory(_ content: String, pinned: Bool = true) throws {
        try stores.memories.upsert(MemoryEntity(
            id: "m-\(UUID().uuidString)", personaId: personaId, content: content,
            category: .fact, importance: 5, pinned: pinned, createdAt: 1, updatedAt: 1,
            lastUsedAt: 0, useCount: 0, status: "ACTIVE", strength: 1
        ))
    }

    /// 一轮完整对话的准备：人格 + 配置 + 会话 + Key。
    private func seedReadySession(webTools: Bool = false) throws {
        try seedPersona()
        try seedConfig(webTools: webTools)
        try seedSession()
        try seedKey()
    }

    private func collect(_ stream: AsyncThrowingStream<ChatEvent, Error>,
                         onEvent: ((ChatEvent) -> Void)? = nil) async throws -> [ChatEvent] {
        var events: [ChatEvent] = []
        for try await event in stream {
            events.append(event)
            onEvent?(event)
        }
        return events
    }

    private func deltas(_ events: [ChatEvent]) -> [String] {
        events.compactMap { if case .delta(let text) = $0 { return text } else { return nil } }
    }

    private func doneEvent(_ events: [ChatEvent]) -> ChatCompletion? {
        for event in events {
            if case .done(let completion) = event { return completion }
        }
        return nil
    }

    /// 严格远程（`remote-strict`）下决策层失败必须变成**可见错误**（0.11.1 审计 P2-1）。
    ///
    /// 0.11.0 的问题：每个调用点都 `try?` 掉并改用本机判定，于是严格模式与普通回退模式
    /// 在 App 行为上没有区别 —— 用它验收「agentd 真的被用上了」会得到假阳性。
    func testStrictRemoteFailureSurfacesAsVisibleChatError() async throws {
        try seedReadySession()
        let strict = makeService(agentCore: StrictFailingAgentCoreClient())
        guard case .started(let stream) = strict.sendChecked(sessionId: sessionId, text: "在吗") else {
            return XCTFail("用户消息落库成功，应当返回 .started")
        }
        do {
            _ = try await collect(stream)
            XCTFail("严格模式失败必须让这条流报错，而不是把本机判定当成远程结果")
        } catch let error as AgentCoreClientError {
            let text = error.errorDescription ?? ""
            XCTAssertFalse(text.isEmpty, "用户要能看到一句人话（严格模式是联调模式，失败要显眼）")
        }
        XCTAssertEqual(
            AgentCoreDiagnostics.shared.strictCount(endpoint: "sleep-decision"), 1,
            "严格模式的失败要计数（可观测）"
        )
    }

    /// 永远失败、并对外声称自己是严格远程的假客户端。
    private struct StrictFailingAgentCoreClient: AgentCoreClient {
        var agentCoreMode: AgentCoreMode { .remote(preferredFallback: false) }
        func sleepDecision(_ input: SleepInput) async throws -> SleepDecision {
            throw AgentCoreClientError.transport("agentd 没起")
        }
        func deferPlan(minutes: Int?, reason: String?, now: Int64) async throws -> DeferPlan {
            throw AgentCoreClientError.transport("agentd 没起")
        }
        func proactiveCap(level: Int, moodIntensity: Int?) async throws -> Int {
            throw AgentCoreClientError.transport("agentd 没起")
        }
        func clampToolArgs(tool: String, args: [String: JSONValue]) async throws -> [String: JSONValue] {
            throw AgentCoreClientError.transport("agentd 没起")
        }
    }

    private func messages(sessionId: String = "s-1") throws -> [MessageEntity] {
        try stores.messages.bySession(sessionId)
    }

    /// 拿到这一次请求发出去的 JSON（系统提示词 + 消息数组）。
    private func requestBody(at index: Int = 0) throws -> [String: Any] {
        let bodies = StubURLProtocol.recordedBodies
        XCTAssertGreaterThan(bodies.count, index, "没有记录到第 \(index) 次请求")
        let body = bodies[index]
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: body) as? [String: Any])
    }

    private func systemPrompt(at index: Int = 0) throws -> String {
        let body = try requestBody(at: index)
        let messages = try XCTUnwrap(body["messages"] as? [[String: Any]])
        return try XCTUnwrap(messages.first?["content"] as? String)
    }

    // MARK: - 正常一轮

    func testSendStreamsDeltasPersistsMessagesAndRecordsUsage() async throws {
        try seedReadySession()
        let sse = """
        data: {"choices":[{"delta":{"content":"你"}}]}

        data: {"choices":[{"delta":{"content":"好呀"}}]}

        data: {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":9}}

        data: [DONE]

        """
        StubURLProtocol.enqueue(.sse(sse, splitEvery: 9))

        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        XCTAssertEqual(deltas(events), ["你", "你好呀"], "delta 是「当前可见全文」，不是增量")
        let done = try XCTUnwrap(doneEvent(events))
        XCTAssertEqual(done.message.content, "你好呀")
        XCTAssertEqual(done.message.role, .assistant)
        XCTAssertEqual(done.message.status, .complete)
        XCTAssertEqual(done.toolTraces.count, 0)
        XCTAssertFalse(done.pendingMemory, "才两条消息，还不到抽记忆的间隔")

        // 落库：用户消息在前，助手消息在后。
        let stored = try messages()
        XCTAssertEqual(stored.map(\.role), [.user, .assistant])
        XCTAssertEqual(stored.map(\.content), ["在吗", "你好呀"])
        XCTAssertEqual(stored[1].id, done.message.id)
        XCTAssertTrue(stored[0].createdAt < stored[1].createdAt, "时间戳必须严格递增")

        // 会话预览与未读：会话没打开过，所以未读 +1。
        let session = try XCTUnwrap(try stores.sessions.byId(sessionId))
        XCTAssertEqual(session.title, "在吗", "第一条用户消息自动当标题")
        XCTAssertEqual(session.lastMessagePreview, "你好呀")
        XCTAssertEqual(session.unreadCount, 1)

        // 记账：一笔 CHAT，带 usage。
        let usageRows = try stores.apiUsage.all()
        XCTAssertEqual(usageRows.count, 1)
        XCTAssertEqual(usageRows[0].kind, UsageKind.chat)
        XCTAssertEqual(usageRows[0].promptTokens, 120)
        XCTAssertEqual(usageRows[0].completionTokens, 9)

        // 报文：一条 system + 一句用户消息，且 stream/usage 选项都对。
        let body = try requestBody()
        XCTAssertEqual(body["stream"] as? Bool, true)
        XCTAssertEqual((body["stream_options"] as? [String: Any])?["include_usage"] as? Bool, true)
        let requestMessages = try XCTUnwrap(body["messages"] as? [[String: Any]])
        XCTAssertEqual(requestMessages.count, 2)
        XCTAssertEqual(requestMessages[0]["role"] as? String, "system")
        XCTAssertEqual(requestMessages[1]["content"] as? String, "在吗")
    }

    /// system 里该有的段落：人格、工具、主动唤醒、闲聊时也不该硬塞记忆块。
    func testSystemPromptContainsPersonaAndCapabilitiesInOrder() async throws {
        try seedReadySession()
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let system = try systemPrompt()
        XCTAssertTrue(system.contains("你是小满，说话很轻。"))
        XCTAssertTrue(system.contains("## 你可以主动联系对方"))
        XCTAssertTrue(system.contains("## 你可以调用这些工具"))
        let personaIndex = try XCTUnwrap(system.range(of: "你是小满"))
        let capabilityIndex = try XCTUnwrap(system.range(of: "## 你可以主动联系对方"))
        XCTAssertTrue(personaIndex.lowerBound < capabilityIndex.lowerBound, "人格必须在能力说明之前")
    }

    /// 心情：由 `MoodRepository` 读出来（顺手让时间冲淡），渲染成 `## 你现在的心情` 那一段。
    func testMoodLineComesFromMoodRepository() async throws {
        try seedReadySession()
        var persona = try XCTUnwrap(try stores.personas.byId(personaId))
        persona.mood = "雀跃"
        persona.moodIntensity = 4
        persona.moodReason = "他夸我了"
        persona.moodUpdatedAt = Int64(Date().timeIntervalSince1970 * 1000)
        try stores.personas.upsert(persona)
        StubURLProtocol.enqueue(.sse(textDeltas: ["嘿嘿"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "今天真好看"))

        let system = try systemPrompt()
        XCTAssertTrue(system.contains("## 你现在的心情"))
        XCTAssertTrue(system.contains("雀跃（强度 4/5，很浓）"))
        XCTAssertTrue(system.contains("原因：他夸我了"))
    }

    /// 记忆块：有记忆时进 system，标题是 `## 你记得的事`（与 Android 逐字一致）。
    func testMemoriesAreInjectedIntoSingleSystemPrompt() async throws {
        try seedReadySession()
        try seedMemory("他怕冷，冬天总穿两双袜子。")
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "今天好冷"))

        let system = try systemPrompt()
        XCTAssertTrue(system.contains("## 你记得的事"))
        XCTAssertTrue(system.contains("他怕冷，冬天总穿两双袜子。"))
        // 记忆块在人格之后、能力说明之前。
        let personaIndex = try XCTUnwrap(system.range(of: "你是小满"))
        let memoryIndex = try XCTUnwrap(system.range(of: "## 你记得的事"))
        let capabilityIndex = try XCTUnwrap(system.range(of: "## 你可以主动联系对方"))
        XCTAssertTrue(personaIndex.lowerBound < memoryIndex.lowerBound)
        XCTAssertTrue(memoryIndex.lowerBound < capabilityIndex.lowerBound)
    }

    /// 1.9：记忆**内置开启**，没有开关能把它关掉（§一）。
    /// 这条测试同时守着「以后别有人再把一个 memoryEnabled 加回来」。
    func testMemoryIsAlwaysOn() async throws {
        try seedReadySession()
        try seedMemory("他怕冷。")
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "今天好冷"))
        XCTAssertTrue(try systemPrompt().contains("## 你记得的事"))
    }

    /// 滚动摘要：会话压过的那一段以「【更早之前你记得的】」进 system。
    func testHistorySummaryBlockIsInjected() async throws {
        try seedReadySession()
        var session = try XCTUnwrap(try stores.sessions.byId(sessionId))
        session.historySummary = "上次聊到他搬家。"
        session.historySummarizedUpTo = 500
        try stores.sessions.upsert(session)
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "后来呢"))
        let system = try systemPrompt()
        XCTAssertTrue(system.contains(HistoryCompression.SUMMARY_HEADER))
        XCTAssertTrue(system.contains("上次聊到他搬家。"))
    }

    // MARK: - 工具轮

    func testToolRoundEmitsEventsAndFeedsResultBack() async throws {
        try seedReadySession()
        let first = """
        data: {"choices":[{"delta":{"content":"我查一"}}]}

        data: {"choices":[{"delta":{"content":"下。\\n```myfriend-tool\\n{\\"name\\":\\"web_search\\",\\"arguments\\":{\\"query\\":\\"今天天气\\"}}\\n```"}}]}

        data: [DONE]

        """
        let second = """
        data: {"choices":[{"delta":{"content":"查到了，"}}]}

        data: {"choices":[{"delta":{"content":"今天晴。"}}]}

        data: {"choices":[],"usage":{"prompt_tokens":80,"completion_tokens":6}}

        data: [DONE]

        """
        StubURLProtocol.enqueue(.sse(first, splitEvery: 11), .sse(second, splitEvery: 11))
        tools.resultFor = { invocation in
            ToolResult(
                invocation: invocation, ok: true, summary: "查了 3 个网页",
                contentForModel: "今天晴，12 度"
            )
        }

        let events = try await collect(service.send(sessionId: sessionId, text: "今天天气怎么样"))

        // 事件序列：先正文，再工具开始/结束，最后是第二轮正文。
        XCTAssertTrue(events.contains { if case .toolStarted(let trace) = $0 { return trace.title == "联网搜索" } else { return false } })
        XCTAssertTrue(events.contains { if case .toolFinished(let trace) = $0 { return trace.title == "联网搜索" && trace.subtitle == "查了 3 个网页" } else { return false } })
        // 协议块绝不能出现在推给界面的文字里。
        XCTAssertFalse(deltas(events).contains { $0.contains("myfriend-tool") })
        XCTAssertEqual(deltas(events).last, "查到了，今天晴。")

        let done = try XCTUnwrap(doneEvent(events))
        XCTAssertEqual(done.message.content, "查到了，今天晴。")
        XCTAssertEqual(done.toolTraces.count, 1)
        XCTAssertEqual(done.toolTraces[0].title, "联网搜索")
        XCTAssertNotNil(done.message.toolTraceJson, "痕迹要落进 toolTraceJson")
        XCTAssertEqual(ToolTraceCodec.decode(done.message.toolTraceJson), done.toolTraces)

        // 第二次请求：把「他自己刚做的事」回灌给模型。
        let secondBody = try requestBody(at: 1)
        let secondMessages = try XCTUnwrap(secondBody["messages"] as? [[String: Any]])
        let contents = secondMessages.compactMap { $0["content"] as? String }
        XCTAssertTrue(contents.contains { $0.contains("【工具结果】web_search") })
        XCTAssertTrue(contents.contains { $0.contains("今天晴，12 度") })
        XCTAssertTrue(contents.contains { $0.contains("现在接着把话说完") })
    }

    /// 模型同一轮里把同一个调用写了两次：只执行一次（防「查了又查」的死循环）。
    func testDuplicateToolCallsAreDeduplicated() async throws {
        try seedReadySession()
        let block = #"{"name":"think","arguments":{"question":"要不要问"}}"#
        let first = "容我想想。\n```myfriend-tool\n\(block)\n```\n```myfriend-tool\n\(block)\n```"
        StubURLProtocol.enqueue(.sse(textDeltas: [first]), .sse(textDeltas: ["想好了。"]))
        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        XCTAssertEqual(tools.callCount, 1, "同一个 name+arguments 只执行一次")
        // 与 Android 一致：被去重掉的那次不执行、也不留痕（留痕只记「真的跑过」的动作）。
        XCTAssertEqual(doneEvent(events)?.toolTraces.count, 1)
    }

    // MARK: - 失败与停止

    func testUnauthorizedMarksAssistantMessageFailed() async throws {
        try seedReadySession()
        StubURLProtocol.enqueue(.json(#"{"error":{"message":"invalid key"}}"#, status: 401))

        var events: [ChatEvent] = []
        var thrown: Error?
        do {
            for try await event in service.send(sessionId: sessionId, text: "在吗") {
                events.append(event)
            }
        } catch {
            thrown = error
        }
        let api = try XCTUnwrap(thrown as? ApiException)
        XCTAssertEqual(api.statusCode, 401)
        XCTAssertEqual(api.message, "API Key 无效或未授权（401）：invalid key")

        let stored = try messages()
        XCTAssertEqual(stored.map(\.role), [.user, .assistant])
        XCTAssertEqual(stored[1].status, .error)
        XCTAssertEqual(stored[1].errorMessage, "API Key 无效或未授权（401）：invalid key")
        XCTAssertEqual(stored[1].content, "", "没有部分正文时正文为空，界面只显示失败")
        // 失败也要给界面一个「落库后的消息」，否则气泡无 id 可点。
        XCTAssertEqual(doneEvent(events)?.message.id, stored[1].id)
    }

    /// 没填 Key：本地就说清楚，不发请求、不白花一次往返。
    func testMissingApiKeyFailsWithChineseMessageAndNoRequest() async throws {
        try seedPersona()
        try seedConfig()
        try seedSession()

        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        } catch {
            thrown = error
        }
        XCTAssertEqual(
            (thrown as? ApiException)?.message,
            "还没有填 API Key：请在设置里为这个 API 配置补上密钥"
        )
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "没 Key 不该发出请求")
        // 用户消息仍然留着（他说的话不能丢），失败原因落在助手消息上。
        XCTAssertEqual(try messages().map(\.role), [.user, .assistant])
        XCTAssertEqual(try messages().last?.status, .error)
    }

    func testMissingApiConfigFailsBeforePersistingAnything() async throws {
        try seedPersona()
        try seedConfig()
        try seedSession(apiConfigId: nil)

        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        } catch {
            thrown = error
        }
        XCTAssertEqual((thrown as? ApiException)?.message, "请先为该会话选择 API 配置")
        XCTAssertTrue(try messages().isEmpty, "配置不全时不该留下半截消息")
    }

    func testEmptyTextIsRejected() async throws {
        try seedReadySession()
        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: sessionId, text: "   "))
        } catch {
            thrown = error
        }
        XCTAssertEqual((thrown as? ApiException)?.message, "消息不能为空")
        XCTAssertTrue(try messages().isEmpty)
    }

    func testUnknownSessionFails() async throws {
        try seedPersona()
        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: "nope", text: "在吗"))
        } catch {
            thrown = error
        }
        XCTAssertEqual((thrown as? ApiException)?.message, "会话不存在")
    }

    /// 用户点停止：已经吐出来的字要落成 STOPPED，不能凭空消失。
    func testStopMidStreamPersistsStoppedMessage() async throws {
        try seedReadySession()
        let sse = """
        data: {"choices":[{"delta":{"content":"第一句。"}}]}

        data: {"choices":[{"delta":{"content":"第二句。"}}]}

        data: [DONE]

        """
        StubURLProtocol.enqueue(.sse(sse, splitEvery: 32, delay: 0.3))

        var events: [ChatEvent] = []
        for try await event in service.send(sessionId: sessionId, text: "讲个故事") {
            events.append(event)
            if case .delta = event {
                service.stop(sessionId: sessionId)
            }
        }
        XCTAssertFalse(deltas(events).isEmpty)
        let stored = try messages()
        let assistant = try XCTUnwrap(stored.last)
        XCTAssertEqual(assistant.role, .assistant)
        XCTAssertEqual(assistant.status, .stopped)
        XCTAssertTrue(assistant.content.hasPrefix("第一句"), "半截正文要保住：\(assistant.content)")
    }

    // MARK: - 重新生成

    func testRegenerateDeletesLastAssistantAndRunsAgain() async throws {
        try seedReadySession()
        StubURLProtocol.enqueue(.sse(textDeltas: ["第一次回复"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        XCTAssertEqual(try messages().count, 2)

        StubURLProtocol.enqueue(.sse(textDeltas: ["第二次回复"]))
        let events = try await collect(service.regenerate(sessionId: sessionId))
        XCTAssertEqual(doneEvent(events)?.message.content, "第二次回复")
        let stored = try messages()
        XCTAssertEqual(stored.count, 2, "旧回复被删掉，不该留下两条助手消息")
        XCTAssertEqual(stored.map(\.content), ["在吗", "第二次回复"])
    }

    func testRegenerateWithoutUserMessageFails() async throws {
        try seedReadySession()
        var thrown: Error?
        do {
            _ = try await collect(service.regenerate(sessionId: sessionId))
        } catch {
            thrown = error
        }
        XCTAssertEqual((thrown as? ApiException)?.message, "没有可以重新生成的消息")
    }

    // MARK: - 后台任务

    /// 回完话之后异步触发「抽记忆 / 压摘要」，但不阻塞回复。
    func testBackgroundTasksAreTriggeredAfterReply() async throws {
        try seedReadySession()
        // 先放 4 条旧消息，加上这一轮 2 条，正好到抽记忆的间隔（6）。
        for index in 0..<4 {
            try stores.messages.upsert(MessageEntity(
                id: "old-\(index)", sessionId: sessionId, personaId: personaId,
                role: index % 2 == 0 ? .user : .assistant,
                content: "旧消息 \(index)", createdAt: Int64(index + 1), updatedAt: Int64(index + 1)
            ))
        }
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯"]))
        let events = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        XCTAssertEqual(background.compressionCalls, [sessionId])
        XCTAssertEqual(background.memoryCalls.map(\.sessionId), [sessionId])
        XCTAssertTrue(doneEvent(events)?.pendingMemory == true)
        XCTAssertEqual(doneEvent(events)?.message.content, "嗯")
    }

    /// 会话开着时不计未读（界面正在看）。
    func testOpenSessionDoesNotIncrementUnread() async throws {
        try seedReadySession()
        service.setOpenSession(sessionId)
        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))
        XCTAssertEqual(try stores.sessions.byId(sessionId)?.unreadCount, 0)
        XCTAssertTrue(service.isSessionOpen(sessionId))
    }

    // MARK: - 主动唤醒代码块

    func testProactiveBlockIsStrippedAndReported() async throws {
        try seedReadySession()
        let text = "好呀，我记住啦。\n```myfriend-proactive\n{\"kind\":\"daily\",\"time\":\"22:30\",\"note\":\"每晚问候\"}\n```"
        StubURLProtocol.enqueue(.sse(textDeltas: [text]))
        let events = try await collect(service.send(sessionId: sessionId, text: "每天晚上十点半来问我一句"))
        let done = try XCTUnwrap(doneEvent(events))
        XCTAssertEqual(done.message.content, "好呀，我记住啦。")
        XCTAssertFalse(done.message.content.contains("myfriend-proactive"))
        XCTAssertEqual(done.proactiveProposalJson, #"{"kind":"daily","time":"22:30","note":"每晚问候"}"#)
    }

    // MARK: - 图片消息

    /// 只发一张图（正文为空）：落库 `attachmentsJson`、会话预览写「图片」、
    /// 这一次请求真的以多模态 content 数组把 data URL 发出去了。
    func testSendWithImagePersistsAttachmentsJsonAndSendsMultimodalPart() async throws {
        try seedReadySession()
        let mediaDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-chat-media-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: mediaDirectory) }
        let media = MediaStorage(directory: mediaDirectory)
        service = makeService(media: media)

        let attachment = try media.saveImage(data: makeJPEG(width: 1200, height: 800), kind: .chatImage)
        StubURLProtocol.enqueue(.sse(textDeltas: ["好看"]))

        _ = try await collect(service.send(sessionId: sessionId, text: "", attachments: [attachment]))

        let stored = try messages()
        XCTAssertEqual(stored.map(\.role), [.user, .assistant])
        XCTAssertEqual(stored[0].content, "", "只发图时正文可以是空的")
        let decoded = AttachmentsCodec.decode(stored[0].attachmentsJson)
        XCTAssertEqual(decoded.count, 1)
        XCTAssertEqual(decoded.first?.uri, attachment.uri)
        XCTAssertEqual(decoded.first?.width, 1200)
        XCTAssertEqual(decoded.first?.height, 800)

        // 首页那一行预览与标题：只有图的时候写「图片」（与 Android 的 ifBlank 一致）。
        let session = try XCTUnwrap(try stores.sessions.byId(sessionId))
        XCTAssertEqual(session.title, "图片")
        XCTAssertEqual(session.lastMessagePreview, "好看", "回复落库后预览是最后一句助手消息")

        // 报文：user 的 content 是数组，先一句「（发来一张图片）」，再一张 data URL。
        let requestMessages = try XCTUnwrap(try requestBody()["messages"] as? [[String: Any]])
        XCTAssertEqual(requestMessages.count, 2)
        let parts = try XCTUnwrap(requestMessages[1]["content"] as? [[String: Any]])
        XCTAssertEqual(parts.first?["type"] as? String, "text")
        XCTAssertEqual(parts.first?["text"] as? String, "（发来一张图片）")
        XCTAssertEqual(parts.last?["type"] as? String, "image_url")
        let url = try XCTUnwrap((parts.last?["image_url"] as? [String: Any])?["url"] as? String)
        XCTAssertTrue(url.hasPrefix("data:image/jpeg;base64,"))
    }

    /// 一条消息最多四张图（`MAX_IMAGES_PER_MESSAGE`）：多选的第五张不落库。
    func testSendCapsImagesPerMessage() async throws {
        try seedReadySession()
        let mediaDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-chat-media-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: mediaDirectory) }
        let media = MediaStorage(directory: mediaDirectory)
        service = makeService(media: media)

        var attachments: [MessageAttachment] = []
        for index in 1...5 {
            let data = try makeJPEG(width: 120 + index, height: 120)
            attachments.append(try media.saveImage(data: data, kind: .chatImage))
        }
        StubURLProtocol.enqueue(.sse(textDeltas: ["收到"]))

        _ = try await collect(service.send(sessionId: sessionId, text: "都看看", attachments: attachments))

        let stored = try messages()
        XCTAssertEqual(
            AttachmentsCodec.decode(stored[0].attachmentsJson).count,
            AttachmentsCodec.maxImagesPerMessage
        )
    }

    /// 既没有字也没有图：不该落一条空消息，而是明确报「消息不能为空」。
    func testSendRejectsEmptyTextWithoutImages() async throws {
        try seedReadySession()
        var thrown: Error?
        do {
            _ = try await collect(service.send(sessionId: sessionId, text: "   "))
        } catch {
            thrown = error
        }
        let api = try XCTUnwrap(thrown as? ApiException)
        XCTAssertEqual(api.message, "消息不能为空")
        XCTAssertTrue(try messages().isEmpty, "失败时不该留下半截消息")
    }

    // MARK: - 发送被拒（0.10.2 审计 P1-10）

    /// 消息过长 / 会话被删这类错误发生在**落库之前**：`sendChecked` 必须告诉调用方
    /// 「这次没落库」，界面才能把草稿与图片还给用户（原来只能拿到一条只会抛错的流，
    /// 而那时草稿已经被清空了）。
    func testSendCheckedRejectsOversizedMessageWithoutPersistingOrDeletingAttachments() async throws {
        try seedReadySession()
        let mediaDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-chat-media-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: mediaDirectory) }
        let media = MediaStorage(directory: mediaDirectory)
        service = makeService(media: media)
        let attachment = try media.saveImage(data: makeJPEG(width: 120, height: 120), kind: .chatImage)

        let tooLong = String(repeating: "字", count: ChatService.maxMessageLength + 1)
        let outcome = service.sendChecked(
            sessionId: sessionId, text: tooLong, attachments: [attachment]
        )
        guard case .rejected(let error) = outcome else {
            return XCTFail("超长消息应当被拒")
        }
        XCTAssertEqual((error as? ApiException)?.message, "消息过长")
        XCTAssertTrue(try messages().isEmpty, "没落库就不能留下半截消息")
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "更不该发请求")
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: try XCTUnwrap(media.fileURL(for: attachment.uri)).path),
            "附件文件不能被删：界面会把这张图还给用户重试，删了就成孤儿/丢图"
        )
    }

    /// 被拒的另一种现实来源：会话在点发送的一瞬间被别人删掉了。
    func testSendCheckedRejectsMissingSession() throws {
        try seedReadySession()
        try stores.sessions.delete(id: sessionId)
        guard case .rejected(let error) = service.sendChecked(sessionId: sessionId, text: "在吗") else {
            return XCTFail("会话不存在时应当被拒")
        }
        XCTAssertEqual((error as? ApiException)?.message, "会话不存在")
        XCTAssertTrue(try messages().isEmpty)
    }

    /// 真的开始生成时 `sendChecked` 返回 `.started`，并且用户那句已经落库。
    func testSendCheckedStartsAndPersistsUserMessage() async throws {
        try seedReadySession()
        StubURLProtocol.enqueue(.sse(textDeltas: ["在"]))
        guard case .started(let stream) = service.sendChecked(sessionId: sessionId, text: "在吗") else {
            return XCTFail("正常发送应当返回 .started")
        }
        _ = try await collect(stream)
        let stored = try messages()
        XCTAssertEqual(stored.map(\.role), [.user, .assistant])
        XCTAssertEqual(stored.first?.content, "在吗")
    }

    /// 界面守卫：`ChatViewModel` 只有在 `.started` 之后才清空草稿/图片；`.rejected` 时保留并给出重试入口。
    func testChatViewModelOnlyClearsComposerAfterPersisted() throws {
        var current = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var path: URL?
        for _ in 0..<12 {
            let candidate = current.appendingPathComponent("MyFriend/Views/Chat/ChatViewModel.swift")
            if FileManager.default.fileExists(atPath: candidate.path) { path = candidate; break }
            current = current.deletingLastPathComponent()
        }
        let source = try String(contentsOf: try XCTUnwrap(path), encoding: .utf8)
        XCTAssertTrue(source.contains("env.chat.sendChecked("), "ViewModel 要走能分辨「有没有落库」的入口")
        XCTAssertTrue(source.contains("case .rejected(let error)"), "被拒要走单独分支")
        XCTAssertTrue(source.contains("sendFailure = message"), "被拒时要留一个可重试状态")
        // 清空动作必须只出现在 `.started` 分支里（``.rejected` 分支只留提示，不碰输入状态）。
        let rejected = try XCTUnwrap(source.range(of: "case .rejected(let error)"))
        let started = try XCTUnwrap(source.range(of: "case .started(let stream)"))
        XCTAssertTrue(started.lowerBound < rejected.lowerBound)
        let startedBranch = source[started.upperBound..<rejected.lowerBound]
        XCTAssertTrue(startedBranch.contains("draft = \"\""), "成功落库后才清草稿")
        XCTAssertTrue(startedBranch.contains("pendingAttachments.removeAll()"), "成功落库后才清已选图片")
        let rejectedBranch = source[rejected.upperBound..<source.endIndex]
        let rejectedEnd = try XCTUnwrap(rejectedBranch.range(of: "func dismissSendFailure"))
        let rejectedBody = rejectedBranch[..<rejectedEnd.lowerBound]
        XCTAssertFalse(rejectedBody.contains("draft = "), "被拒时不能动草稿")
        XCTAssertFalse(rejectedBody.contains("pendingAttachments.removeAll()"), "被拒时不能丢已选图片")
    }

    /// 文件不在时（从 Android 备份接回、被系统清掉）仍然能发新消息：
    /// 历史里那条只剩文字，不崩、不阻塞这一轮。
    func testHistoryWithMissingAttachmentFileStillGenerates() async throws {
        try seedReadySession()
        try stores.messages.upsert(MessageEntity(
            id: "m-missing", sessionId: sessionId, personaId: personaId, role: .user,
            content: "", createdAt: 1, updatedAt: 1, status: .complete,
            attachmentsJson: #"[{"uri":"file:///var/mobile/gone.jpg"}]"#
        ))
        StubURLProtocol.enqueue(.sse(textDeltas: ["那张图我这边看不到了"]))

        _ = try await collect(service.send(sessionId: sessionId, text: "还在吗"))

        let requestMessages = try XCTUnwrap(try requestBody()["messages"] as? [[String: Any]])
        XCTAssertEqual(requestMessages[1]["content"] as? String, "（发来一张图片）")
        XCTAssertEqual(requestMessages.count, 3)
    }

    // MARK: - 长会话只读窗口（0.11.0 P2-1）

    /// 400 条的会话里发一句：上下文窗口只从 SQLite 捞 `contextRecentLimit` 行。
    ///
    /// 为什么这条测试必须在**调用点**上做：`MessageStore.recent` 自己只返回 N 条，
    /// 但「读全文再 suffix」的写法返回值一模一样 —— 只有数物化了多少行才分得清。
    /// 0.10.2 审计 P2-1 说的就是这条路径（`ChatService.swift` 的 `bySession` + `suffix`）。
    func testLongSessionContextReadsWindowOnly() async throws {
        try seedReadySession()
        // 400 条历史：远超上下文窗口（200 条），也远超一屏。
        let history = (0..<400).map { index in
            TestData.message(
                id: String(format: "h-%03d", index),
                sessionId: sessionId,
                personaId: personaId,
                role: index % 2 == 0 ? .user : .assistant,
                content: "历史第 \(index) 句",
                createdAt: 1_000 + Int64(index) * 1000
            )
        }
        try stores.messages.upsert(history)

        let reads = ReadLog()
        stores.messages.queryObserver = { method, rows in reads.record(method, rows) }
        defer { stores.messages.queryObserver = nil }

        StubURLProtocol.enqueue(.sse(textDeltas: ["嗯"]))
        _ = try await collect(service.send(sessionId: sessionId, text: "在吗"))

        let snapshot = reads.snapshot()
        XCTAssertFalse(
            snapshot.contains { $0.method == "bySession" },
            "长会话不该整段读历史（实际读到：\(snapshot)）"
        )
        let contextReads = snapshot.filter { $0.method == "recent" }
        XCTAssertEqual(contextReads.count, 1, "上下文窗口只查一次，实际：\(snapshot)")
        XCTAssertEqual(
            contextReads.first?.rows, PromptBuilder.contextRecentLimit,
            "窗口只该物化 \(PromptBuilder.contextRecentLimit) 行，而不是 400 行"
        )
        // 报文里也只带窗口内的消息：system + 窗口那 200 条（最后一条就是刚发的这句）。
        let requestMessages = try XCTUnwrap(try requestBody()["messages"] as? [[String: Any]])
        XCTAssertEqual(requestMessages.count, PromptBuilder.contextRecentLimit + 1)
        XCTAssertEqual(requestMessages.last?["content"] as? String, "在吗")
    }

    // MARK: - 造图

    private func makeJPEG(width: Int, height: Int) throws -> Data {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let context = try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: colorSpace, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.setFillColor(CGColor(red: 0.55, green: 0.35, blue: 0.25, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let image = try XCTUnwrap(context.makeImage())
        let data = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(
            data as CFMutableData, "public.jpeg" as CFString, 1, nil
        ))
        CGImageDestinationAddImage(
            destination, image,
            [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary
        )
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }
}

// MARK: - 读行数记录（P2-1 的验收）

/// `MessageStore.queryObserver` 的记录器：生成跑在别的 Task 上，所以要加锁。
final class ReadLog {
    struct Entry {
        var method: String
        var rows: Int
    }

    private var entries: [Entry] = []
    private let lock = NSLock()

    func record(_ method: String, _ rows: Int) {
        lock.lock()
        entries.append(Entry(method: method, rows: rows))
        lock.unlock()
    }

    func snapshot() -> [Entry] {
        lock.lock()
        defer { lock.unlock() }
        return entries
    }
}

// MARK: - 假工具执行器 / 假后台任务

private final class RecordingTools: ToolRunning {
    var resultFor: ((ToolInvocation) -> ToolResult)?
    private(set) var callCount = 0

    func run(_ invocation: ToolInvocation, persona: PersonaEntity, sessionId: String) async -> ToolResult {
        callCount += 1
        if let resultFor { return resultFor(invocation) }
        return ToolResult(invocation: invocation, ok: true, summary: "做完了", contentForModel: "结果")
    }
}

private final class RecordingBackground: ChatBackgroundTasks {
    private(set) var memoryCalls: [(sessionId: String, personaId: String)] = []
    private(set) var compressionCalls: [String] = []

    func memoryExtractionNeeded(sessionId: String, personaId: String) {
        memoryCalls.append((sessionId, personaId))
    }

    func historyCompressionNeeded(sessionId: String) {
        compressionCalls.append(sessionId)
    }
}

// MARK: - SSE 夹具

private extension StubURLProtocol.Stub {
    /// 「只有文字增量 + [DONE]」的最简流。
    static func sse(textDeltas: [String], usage: (Int, Int)? = nil) -> StubURLProtocol.Stub {
        var text = ""
        for delta in textDeltas {
            let escaped = delta
                .replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\\"")
                .replacingOccurrences(of: "\n", with: "\\n")
            text += "data: {\"choices\":[{\"delta\":{\"content\":\"\(escaped)\"}}]}\n\n"
        }
        if let usage {
            text += "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":\(usage.0),\"completion_tokens\":\(usage.1)}}\n\n"
        }
        text += "data: [DONE]\n\n"
        return .sse(text)
    }
}
