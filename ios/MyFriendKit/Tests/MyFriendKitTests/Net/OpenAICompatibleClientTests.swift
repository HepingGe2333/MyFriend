import Foundation
import XCTest
@testable import MyFriendKit

/// 网络层：报文形状、header 优先级、SSE 流式、错误码文案、超时/DNS/断网映射。
///
/// 为什么这些要在这里钉死：错误文案是**用户看到的那句话**，Android 端已经定了；
/// 两边说法不同，用户搜同一个报错就搜不到同一批答案。报文形状则是网关兼容性，
/// 漏了 `stream_options.include_usage` 会让「聊天花了钱但账上记 0」。
final class OpenAICompatibleClientTests: XCTestCase {

    private var databaseURL: URL!
    private var stores: DatabaseStores!
    private var recorder: UsageRecorder!

    override func setUpWithError() throws {
        try super.setUpWithError()
        StubURLProtocol.reset()
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("myfriend-ios-net-tests", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        databaseURL = directory.appendingPathComponent("net-\(UUID().uuidString).sqlite")
        stores = try DatabaseStores.open(path: databaseURL.path)
        recorder = UsageRecorder(stores: stores)
    }

    override func tearDownWithError() throws {
        StubURLProtocol.reset()
        stores = nil
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(atPath: databaseURL.path + suffix)
        }
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func config(
        baseUrl: String = "https://api.example.com",
        model: String = "deepseek-chat",
        customHeaders: String = "{}",
        allowInsecureHttp: Bool = false,
        webTools: Bool = false
    ) -> ApiConfigEntity {
        ApiConfigEntity(
            id: "cfg-1",
            name: "测试配置",
            provider: .openai,
            baseUrl: baseUrl,
            model: model,
            customHeadersJson: customHeaders,
            allowInsecureHttp: allowInsecureHttp,
            temperature: 0.7,
            topP: 0.9,
            maxOutputTokens: 512,
            webToolsEnabled: webTools
        )
    }

    private func makeClient(session: URLSession? = nil,
                            gate: LlmConcurrencyGate = LlmConcurrencyGate()) -> OpenAICompatibleClient {
        OpenAICompatibleClient(
            session: session ?? StubURLProtocol.makeSession(),
            usage: recorder,
            gate: gate
        )
    }

    private func collect(_ stream: AsyncThrowingStream<String, Error>) async throws -> [String] {
        var pieces: [String] = []
        for try await piece in stream { pieces.append(piece) }
        return pieces
    }

    // MARK: - 报文形状

    func testStreamRequestShapeMatchesAndroid() throws {
        let data = try OpenAICompatibleClient.encodeRequest(
            model: "deepseek-chat",
            messages: [
                WireMessage(role: "system", content: "你是他"),
                WireMessage(role: "user", content: "在吗")
            ],
            params: InferenceParams(temperature: 0.7, topP: 0.9, maxOutputTokens: 512,
                                    frequencyPenalty: 0.1, presencePenalty: 0.2),
            stream: true
        )
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(root["model"] as? String, "deepseek-chat")
        XCTAssertEqual(root["stream"] as? Bool, true)
        // 这一条是「聊天必须有账」的前提：让服务端在最后一片里带 usage。
        let options = try XCTUnwrap(root["stream_options"] as? [String: Any])
        XCTAssertEqual(options["include_usage"] as? Bool, true)
        XCTAssertEqual(root["temperature"] as? Double, 0.7)
        XCTAssertEqual(root["top_p"] as? Double, 0.9)
        XCTAssertEqual(root["max_tokens"] as? Int, 512)
        XCTAssertEqual(root["frequency_penalty"] as? Double, 0.1)
        XCTAssertEqual(root["presence_penalty"] as? Double, 0.2)

        let messages = try XCTUnwrap(root["messages"] as? [[String: Any]])
        XCTAssertEqual(messages.count, 2)
        XCTAssertEqual(messages[0]["role"] as? String, "system")
        XCTAssertEqual(messages[0]["content"] as? String, "你是他")
        XCTAssertEqual(messages[1]["content"] as? String, "在吗")
    }

    /// 非流式请求不该带 `stream_options`（有的网关会因此 400）。
    func testNonStreamRequestHasNoStreamOptions() throws {
        let data = try OpenAICompatibleClient.encodeRequest(
            model: "m", messages: [WireMessage(role: "user", content: "hi")],
            params: InferenceParams(), stream: false
        )
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(root["stream"] as? Bool, false)
        XCTAssertNil(root["stream_options"])
        XCTAssertNil(root["temperature"])
    }

    /// 带图消息走多模态 content 数组（图片协议先留着，iOS 端还没接图片存储）。
    func testImageMessageUsesMultimodalContent() throws {
        let data = try OpenAICompatibleClient.encodeRequest(
            model: "m",
            messages: [WireMessage(role: "user", content: "看这个", images: ["data:image/jpeg;base64,AAA"])],
            params: InferenceParams(), stream: false
        )
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let messages = try XCTUnwrap(root["messages"] as? [[String: Any]])
        let parts = try XCTUnwrap(messages[0]["content"] as? [[String: Any]])
        XCTAssertEqual(parts.count, 2)
        XCTAssertEqual(parts[0]["type"] as? String, "text")
        XCTAssertEqual(parts[1]["type"] as? String, "image_url")
        let image = try XCTUnwrap(parts[1]["image_url"] as? [String: Any])
        XCTAssertEqual(image["url"] as? String, "data:image/jpeg;base64,AAA")
    }

    func testEndpointNormalization() throws {
        XCTAssertEqual(NetworkGuard.normalizeBaseUrl("https://a.com/v1/"), "https://a.com/v1")
        XCTAssertEqual(NetworkGuard.normalizeBaseUrl("https://a.com/v1/chat/completions"), "https://a.com/v1")
        XCTAssertEqual(NetworkGuard.normalizeBaseUrl("https://a.com/models"), "https://a.com")
        XCTAssertEqual(
            try NetworkGuard.endpoint(baseUrl: "https://a.com", path: "chat/completions").absoluteString,
            "https://a.com/v1/chat/completions"
        )
        XCTAssertEqual(
            try NetworkGuard.endpoint(baseUrl: "https://a.com/v1/", path: "/chat/completions").absoluteString,
            "https://a.com/v1/chat/completions"
        )
    }

    func testInsecureHttpRequiresExplicitOptIn() {
        XCTAssertThrowsError(try NetworkGuard.parseHttpURL("http://127.0.0.1:8080", allowInsecureHttp: false)) { error in
            let api = error as? ApiException
            XCTAssertEqual(api?.message, "出于安全要求，仅允许 HTTPS。若使用本地服务，请在 API 配置中显式开启“允许不安全 HTTP”。")
        }
        XCTAssertNoThrow(try NetworkGuard.parseHttpURL("http://127.0.0.1:8080", allowInsecureHttp: true))
        XCTAssertThrowsError(try NetworkGuard.parseHttpURL("", allowInsecureHttp: true)) { error in
            XCTAssertEqual((error as? ApiException)?.message, "地址不能为空")
        }
    }

    func testCustomHeadersAndAuthorizationPrecedence() {
        let headers = OpenAICompatibleClient.parseHeaders(#"{"X-Trace":"1","Authorization":"Bearer custom","Content-Type":"text/plain","Empty":"  ","Num":3}"#)
        // 解析层只做「扁平化成 (name, value)」，排掉空值、按名字排序（同样配置每次发出同一组 header）。
        XCTAssertEqual(headers.map(\.0), ["Authorization", "Content-Type", "Num", "X-Trace"])
        XCTAssertEqual(headers.first { $0.0 == "Num" }?.1, "3")
        // 真正发请求时 Content-Type 由客户端自己给，不让自定义 Header 覆盖（见下一条测试）。
    }

    /// 自定义 Authorization 存在时，主 Key 不再追加第二个 Authorization。
    func testRequestHeadersUseBearerAndRespectCustomAuthorization() async throws {
        StubURLProtocol.enqueue(.json(#"{"choices":[{"message":{"content":"好"}}]}"#))
        let client = makeClient()
        _ = try await client.complete(
            config: config(), apiKey: "sk-abc", model: "deepseek-chat",
            messages: [WireMessage(role: "user", content: "hi")],
            params: InferenceParams(), extraHeaders: [:], kind: UsageKind.chat
        )
        let request = try XCTUnwrap(StubURLProtocol.recordedRequests.first)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer sk-abc")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.absoluteString, "https://api.example.com/v1/chat/completions")

        StubURLProtocol.reset()
        StubURLProtocol.enqueue(.json(#"{"choices":[{"message":{"content":"好"}}]}"#))
        _ = try await client.complete(
            config: config(customHeaders: #"{"Authorization":"Bearer custom-key"}"#),
            apiKey: "sk-abc", model: "deepseek-chat",
            messages: [WireMessage(role: "user", content: "hi")],
            params: InferenceParams(), extraHeaders: [:], kind: UsageKind.chat
        )
        let second = try XCTUnwrap(StubURLProtocol.recordedRequests.first)
        XCTAssertEqual(second.value(forHTTPHeaderField: "Authorization"), "Bearer custom-key")
    }

    // MARK: - 流式

    /// 完整一轮流式：半包 + CRLF + usage 帧 + `[DONE]`，正文要按顺序拼回来。
    func testStreamChatDeliversDeltasAndRecordsUsage() async throws {
        let sse = """
        data: {"choices":[{"delta":{"content":"你"}}]}\r\n\r\n
        data: {"choices":[{"delta":{"content":"好"}}]}\r\n\r\n
        data: {"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":7}}\r\n\r\n
        data: [DONE]\r\n\r\n
        """
        StubURLProtocol.enqueue(.sse(sse, splitEvery: 7))
        let client = makeClient()
        let pieces = try await collect(client.streamChat(
            config: config(), apiKey: "sk-abc", model: "deepseek-chat",
            messages: [WireMessage(role: "user", content: "hi")],
            params: InferenceParams(), extraHeaders: [:], kind: UsageKind.chat
        ))
        XCTAssertEqual(pieces, ["你", "好"])

        let rows = try stores.apiUsage.all()
        XCTAssertEqual(rows.count, 1, "一次流式只记一笔")
        let row = try XCTUnwrap(rows.first)
        XCTAssertEqual(row.kind, UsageKind.chat)
        XCTAssertEqual(row.modelName, "deepseek-chat")
        XCTAssertEqual(row.promptTokens, 11)
        XCTAssertEqual(row.completionTokens, 7)
        XCTAssertEqual(
            row.costMicroUsd,
            ApiCost.estimateCostMicroUsd(modelName: "deepseek-chat", usage: TokenUsage(promptTokens: 11, completionTokens: 7))
        )
    }

    /// 服务端不报 usage：按字数估一个，不能记成 0（聊天的钱是最大头）。
    func testStreamWithoutUsageFallsBackToEstimate() async throws {
        let sse = """
        data: {"choices":[{"delta":{"content":"今天"}}]}

        data: {"choices":[{"delta":{"content":"有点冷"}}]}

        data: [DONE]

        """
        StubURLProtocol.enqueue(.sse(sse))
        let client = makeClient()
        let pieces = try await collect(client.streamChat(
            config: config(), apiKey: "sk-abc", model: "qwen-plus",
            messages: [WireMessage(role: "user", content: "外面冷吗")],
            params: InferenceParams(), extraHeaders: [:], kind: UsageKind.chat
        ))
        XCTAssertEqual(pieces.joined(), "今天有点冷")
        let row = try XCTUnwrap(try stores.apiUsage.all().first)
        XCTAssertEqual(row.completionTokens, ApiCost.estimateTokens("今天有点冷"))
        XCTAssertEqual(row.promptTokens, ApiCost.estimateTokens("外面冷吗"))
        XCTAssertGreaterThan(row.costMicroUsd, 0)
    }

    /// 网关忽略 `stream: true`、直接返回一整份 JSON：当成一次性回复处理，别报错。
    func testNonEventStreamResponseIsTreatedAsFullCompletion() async throws {
        StubURLProtocol.enqueue(.json(
            #"{"choices":[{"message":{"content":"完整回复"}}],"usage":{"prompt_tokens":3,"completion_tokens":5}}"#
        ))
        let client = makeClient()
        let pieces = try await collect(client.streamChat(
            config: config(), apiKey: "sk-abc", model: "m",
            messages: [WireMessage(role: "user", content: "hi")],
            params: InferenceParams(), extraHeaders: [:], kind: UsageKind.chat
        ))
        XCTAssertEqual(pieces, ["完整回复"])
        XCTAssertEqual(try stores.apiUsage.all().first?.promptTokens, 3)
    }

    /// 一片文字都没有：不能静默成功（否则界面上是一条永远空着的气泡）。
    func testEmptyStreamThrows() async {
        // 空流属于「可重试」错误（没有状态码），最多重试 2 次：三次都给同一份空流。
        StubURLProtocol.enqueue(
            .sse("data: [DONE]\n\n"), .sse("data: [DONE]\n\n"), .sse("data: [DONE]\n\n")
        )
        let client = makeClient()
        do {
            _ = try await collect(client.streamChat(
                config: config(), apiKey: "sk-abc", model: "m",
                messages: [WireMessage(role: "user", content: "hi")],
                params: InferenceParams(), extraHeaders: [:], kind: UsageKind.chat
            ))
            XCTFail("应当抛错")
        } catch {
            XCTAssertEqual((error as? ApiException)?.message, "模型没有返回任何文字，请检查模型名称或服务配置")
        }
    }

    /// 流里夹着 error 帧：按错误处理，并把服务端那句原文带上。
    func testStreamErrorFrameIsMapped() async {
        StubURLProtocol.enqueue(.sse(#"data: {"error":{"message":"quota exceeded"}}"# + "\n\n"))
        let client = makeClient()
        do {
            _ = try await collect(client.streamChat(
                config: config(), apiKey: "sk-abc", model: "m",
                messages: [WireMessage(role: "user", content: "hi")],
                params: InferenceParams(), extraHeaders: [:], kind: UsageKind.chat
            ))
            XCTFail("应当抛错")
        } catch {
            let api = error as? ApiException
            // 服务端给了原话就用原话（与 Android 的 extractErrorMessage 一致），没有才用兜底文案。
            XCTAssertEqual(api?.message, "quota exceeded")
        }
    }

    // MARK: - 非流式

    func testCompleteParsesTextAndRecordsUsage() async throws {
        StubURLProtocol.enqueue(.json(
            #"{"choices":[{"message":{"content":"记住啦"}}],"usage":{"prompt_tokens":20,"completion_tokens":9}}"#
        ))
        let client = makeClient()
        let text = try await client.complete(
            config: config(), apiKey: "sk-abc", model: "deepseek-chat",
            messages: [WireMessage(role: "user", content: "记一下")],
            params: InferenceParams(), extraHeaders: [:], kind: UsageKind.extraction
        )
        XCTAssertEqual(text, "记住啦")
        let row = try XCTUnwrap(try stores.apiUsage.all().first)
        XCTAssertEqual(row.kind, UsageKind.extraction)
        XCTAssertEqual(row.completionTokens, 9)
    }

    /// 1.9：没有预算闸了，但有一条**并发闸**——名额被占满时后台调用直接放弃（不发请求）。
    ///
    /// 用户不该看见「他排队超时了」：后台道超时给一句「这次先不做」，聊天道才有面向用户的话。
    func testBackgroundCallGivesUpWhenGateIsFull() async {
        let gate = LlmConcurrencyGate()
        // 先占满 3 个位（2 个后台 + 1 个聊天）。
        let tickets = [
            await gate.acquire(kind: UsageKind.consolidation),
            await gate.acquire(kind: UsageKind.agent),
            await gate.acquire(kind: UsageKind.chat)
        ].compactMap { $0.ticket }
        XCTAssertEqual(tickets.count, 3)

        let client = makeClient(gate: gate)
        do {
            _ = try await client.complete(
                config: config(), apiKey: "sk-abc", model: "deepseek-chat",
                messages: [WireMessage(role: "user", content: "hi")],
                params: InferenceParams(), extraHeaders: [:],
                kind: UsageKind.consolidation,
                usageContext: .none
            )
            XCTFail("名额已满时后台调用应当放弃")
        } catch {
            XCTAssertTrue((error as? ApiException)?.message.contains("先不做") == true)
        }
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "拿不到名额时不该发出请求")
        for ticket in tickets { gate.release(ticket) }
    }

    /// 后台道的排队超时**不能**用聊天文案（0.10.2 审计 P1-11）。
    ///
    /// 0.10.1 的 `streamChat` 在拿不到名额时硬编码 `busyError(lane: .chat)`：
    /// 抽记忆/整理/心跳这些后台任务超时后，日志与「他最近在想什么」里会出现一句
    /// 面向聊天的「他手上还有别的事没忙完，这次没顾上回你」。
    func testBackgroundLaneUsesItsOwnTimeoutMessageInBothPaths() async {
        let gate = LlmConcurrencyGate()
        let tickets = [
            await gate.acquire(kind: UsageKind.consolidation),
            await gate.acquire(kind: UsageKind.agent),
            await gate.acquire(kind: UsageKind.chat)
        ].compactMap { $0.ticket }
        XCTAssertEqual(tickets.count, 3)
        let client = makeClient(gate: gate)

        // 1) 非流式（后台任务都走它）
        do {
            _ = try await client.complete(
                config: config(), apiKey: "sk-abc", model: "deepseek-chat",
                messages: [WireMessage(role: "user", content: "hi")],
                params: InferenceParams(), extraHeaders: [:],
                kind: UsageKind.extraction, usageContext: .none
            )
            XCTFail("名额已满时后台调用应当放弃")
        } catch {
            let message = (error as? ApiException)?.message ?? ""
            XCTAssertTrue(message.contains("后台任务"), "后台道要用后台文案：\(message)")
            XCTAssertFalse(message.contains("没顾上回你"), "不该出现聊天文案：\(message)")
        }

        // 2) 流式（心跳/自主循环可能走它）
        let stream = client.streamChat(
            config: config(), apiKey: "sk-abc", model: "deepseek-chat",
            messages: [WireMessage(role: "user", content: "hi")],
            params: InferenceParams(), extraHeaders: [:],
            kind: UsageKind.agent, usageContext: .none
        )
        do {
            for try await _ in stream { XCTFail("名额已满时不该有内容") }
            XCTFail("应当抛错")
        } catch {
            let message = (error as? ApiException)?.message ?? ""
            XCTAssertTrue(message.contains("后台任务"), "流式后台道也要用后台文案：\(message)")
            XCTAssertFalse(message.contains("没顾上回你"), "不该出现聊天文案：\(message)")
        }
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "拿不到名额时不该发请求")
        for ticket in tickets { gate.release(ticket) }
    }

    /// 排队期间被取消：必须抛 `CancellationError`，不再发一次用户已经不想要的请求。
    func testCancelledWhileWaitingDoesNotSendRequest() async {
        let gate = LlmConcurrencyGate()
        let tickets = [
            await gate.acquire(kind: UsageKind.consolidation),
            await gate.acquire(kind: UsageKind.agent),
            await gate.acquire(kind: UsageKind.chat)
        ].compactMap { $0.ticket }
        let client = makeClient(gate: gate)

        let finished = expectation(description: "取消后返回")
        let box = ResultBox()
        let task = Task {
            do {
                _ = try await client.complete(
                    config: config(), apiKey: "sk-abc", model: "deepseek-chat",
                    messages: [WireMessage(role: "user", content: "hi")],
                    params: InferenceParams(), extraHeaders: [:],
                    kind: UsageKind.chat, usageContext: .none
                )
                box.set(error: nil)
            } catch {
                box.set(error: error)
            }
            finished.fulfill()
        }
        try? await Task.sleep(nanoseconds: 30_000_000)
        task.cancel()
        await fulfillment(of: [finished], timeout: 5)

        XCTAssertTrue(box.error is CancellationError, "取消要变成 CancellationError：\(String(describing: box.error))")
        XCTAssertTrue(StubURLProtocol.recordedRequests.isEmpty, "取消之后不能再发请求")
        for ticket in tickets { gate.release(ticket) }
    }

    // MARK: - 状态码与中文文案

    func testHTTPErrorMessagesMatchAndroid() {
        let client = makeClient()
        let cases: [(Int, String)] = [
            (400, "请求被服务拒绝（400），请检查模型名称与推理参数"),
            (401, "API Key 无效或未授权（401）"),
            (403, "没有访问该模型的权限（403）"),
            (404, "接口或模型不存在（404），请检查 Base URL 与模型 ID"),
            (408, "服务请求超时（408）"),
            (413, "请求内容过长（413），请缩短上下文或输出长度"),
            (429, "请求过于频繁或额度不足（429）"),
            (500, "服务端暂时不可用（500）"),
            (503, "服务端暂时不可用（503）"),
            (418, "请求失败（HTTP 418）")
        ]
        for (status, expected) in cases {
            let api = client.apiException(status: status, body: "")
            XCTAssertEqual(api.message, expected, "状态码 \(status) 的文案不一致")
            XCTAssertEqual(api.statusCode, status)
        }
    }

    /// 服务端给了具体原因：接在中文后面，用户和开发者都能看懂。
    func testHTTPErrorAppendsServerDetail() {
        let client = makeClient()
        let body = #"{"error":{"message":"model not found"}}"#
        let api = client.apiException(status: 404, body: body)
        XCTAssertEqual(api.message, "接口或模型不存在（404），请检查 Base URL 与模型 ID：model not found")
    }

    /// 401 要从真实请求链路里冒出来（不是只测映射函数）。
    func testUnauthorizedResponseSurfacesApiException() async {
        StubURLProtocol.enqueue(.json(#"{"error":{"message":"invalid key"}}"#, status: 401))
        let client = makeClient()
        do {
            _ = try await collect(client.streamChat(
                config: config(), apiKey: "bad", model: "m",
                messages: [WireMessage(role: "user", content: "hi")],
                params: InferenceParams(), extraHeaders: [:], kind: UsageKind.chat
            ))
            XCTFail("401 应当抛错")
        } catch {
            let api = error as? ApiException
            XCTAssertEqual(api?.statusCode, 401)
            XCTAssertEqual(api?.message, "API Key 无效或未授权（401）：invalid key")
        }
    }

    /// 超时 / DNS / 断网：三句不同的中文，分别对应 Android 的三条分支。
    func testNetworkErrorMapping() {
        let client = makeClient()
        let timeout = client.mapNetworkError(URLError(.timedOut), timedOutMessage: client.timeOutMessage) as? ApiException
        XCTAssertEqual(timeout?.message, "连接超时，请检查网络或服务地址")
        XCTAssertNil(timeout?.statusCode)

        let dns = client.mapNetworkError(URLError(.cannotFindHost), timedOutMessage: "") as? ApiException
        XCTAssertEqual(dns?.message, "无法解析服务器地址，请检查网络连接")

        let offline = client.mapNetworkError(URLError(.notConnectedToInternet), timedOutMessage: "") as? ApiException
        XCTAssertTrue(offline?.message.hasPrefix("网络连接中断：") == true, "断网文案要以「网络连接中断」开头")

        XCTAssertEqual(client.completeTimeOutMessage, "请求超时，请检查网络或服务响应时间")
        // 已经带状态码的错误不再被改写。
        let original = ApiException(statusCode: 401, message: "x")
        XCTAssertEqual((client.mapNetworkError(original, timedOutMessage: "") as? ApiException)?.message, "x")
    }

    func testRetryClassification() {
        let client = makeClient()
        XCTAssertTrue(client.isRetryable(ApiException(statusCode: nil, message: "x")))
        XCTAssertTrue(client.isRetryable(ApiException(statusCode: 429, message: "x")))
        XCTAssertTrue(client.isRetryable(ApiException(statusCode: 503, message: "x")))
        XCTAssertTrue(client.isRetryable(URLError(.networkConnectionLost)))
        XCTAssertFalse(client.isRetryable(ApiException(statusCode: 401, message: "x")))
        XCTAssertFalse(client.isRetryable(ApiException(statusCode: 404, message: "x")))
    }

    // MARK: - 分片解析细节

    func testStreamChunkParsingShapes() throws {
        let client = makeClient()
        // OpenAI delta
        let delta = try client.parseStreamChunk(
            payload: #"{"choices":[{"delta":{"content":"嗨"}}]}"#, status: 200
        )
        XCTAssertEqual(delta.text, "嗨")
        // 兼容 text / message.content 两种老形状
        XCTAssertEqual(try client.parseStreamChunk(payload: #"{"choices":[{"text":"a"}]}"#, status: 200).text, "a")
        XCTAssertEqual(
            try client.parseStreamChunk(payload: #"{"choices":[{"message":{"content":"b"}}]}"#, status: 200).text,
            "b"
        )
        // 只有 usage 的分片
        let usageOnly = try client.parseStreamChunk(
            payload: #"{"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}"#, status: 200
        )
        XCTAssertNil(usageOnly.text)
        XCTAssertEqual(usageOnly.usage, TokenUsage(promptTokens: 1, completionTokens: 2))
        // 坏 JSON 静默跳过（流不能因为一个坏分片整段失败）
        XCTAssertNil(try client.parseStreamChunk(payload: "not json", status: 200).text)
    }

    func testParseCompletionErrorShapes() {
        let client = makeClient()
        XCTAssertThrowsError(try client.parseCompletion(raw: #"{"choices":[]}"#, status: 200)) { error in
            XCTAssertEqual((error as? ApiException)?.message, "服务返回了空回复")
        }
        XCTAssertThrowsError(try client.parseCompletion(raw: "garbage", status: 200)) { error in
            XCTAssertEqual((error as? ApiException)?.message, "服务返回了无法识别的回复内容")
        }
        XCTAssertThrowsError(try client.parseCompletion(raw: #"{"error":{"message":"boom"}}"#, status: 200)) { error in
            XCTAssertEqual((error as? ApiException)?.message, "boom")
        }
    }
}

/// 跨线程收集一个 Error（取消测试用）。
private final class ResultBox: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Error?

    func set(error: Error?) {
        lock.lock()
        stored = error
        lock.unlock()
    }

    var error: Error? {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }
}
