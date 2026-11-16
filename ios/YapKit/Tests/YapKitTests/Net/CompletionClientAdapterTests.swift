import Foundation
import XCTest
@testable import YapKit

/// 服务层接缝：把真实的网络客户端接到 `CompletionClient` 上。
///
/// 这条路径是「后台任务」唯一会走的路：记忆抽取、每日整理、打分、主动思考都靠它。
/// 一旦参数没搬对（比如丢了 `allowInsecureHttp`、或把温度覆盖错了），
/// 表现是「后台任务静默失败」——所以这里连「搬参数」这件事也要测。
final class CompletionClientAdapterTests: XCTestCase {

    /// 记录下最后一次调用收到了什么。
    private final class FakeClient: ChatCompleting {
        var lastConfig: ApiConfigEntity?
        var lastAPIKey: String?
        var lastModel: String?
        var lastMessages: [WireMessage] = []
        var lastParams = InferenceParams()
        var lastKind: String = ""
        var lastContext = UsageContext.none
        var result: Result<String, Error> = .success("好的")

        func fetchModels(config: ApiConfigEntity, apiKey: String?) async throws -> [String] { [] }

        func streamChat(
            config: ApiConfigEntity, apiKey: String?, model: String, messages: [WireMessage],
            params: InferenceParams, extraHeaders: [String: String], kind: String,
            usageContext: UsageContext
        ) -> AsyncThrowingStream<String, Error> {
            AsyncThrowingStream { $0.finish() }
        }

        func complete(
            config: ApiConfigEntity, apiKey: String?, model: String, messages: [WireMessage],
            params: InferenceParams, extraHeaders: [String: String], kind: String,
            usageContext: UsageContext
        ) async throws -> String {
            lastConfig = config
            lastAPIKey = apiKey
            lastModel = model
            lastMessages = messages
            lastParams = params
            lastKind = kind
            lastContext = usageContext
            return try result.get()
        }
    }

    private func config(apiKey: String? = "sk-1") -> CompletionConfig {
        CompletionConfig(
            id: "cfg-1",
            name: "测试",
            baseUrl: "https://api.example.com",
            model: "deepseek-chat",
            apiKey: apiKey,
            temperature: 0.3,
            topP: 0.8,
            maxOutputTokens: 256,
            frequencyPenalty: 0.5,
            presencePenalty: 0.6,
            allowInsecureHttp: true,
            customHeadersJson: #"{"X-A":"1"}"#
        )
    }

    func testCompletePassesConfigMessagesAndKind() async throws {
        let fake = FakeClient()
        let adapter = CompletionClientAdapter(client: fake)
        let result = try await adapter.complete(
            config: config(),
            model: "deepseek-reasoner",
            messages: [.system("你是整理器"), .user("这是对话")],
            params: CompletionParams(temperature: 0.9, maxOutputTokens: 64, topP: 0.1),
            kind: .extraction,
            context: UsageContext(personaId: "p-1", sessionId: "s-1")
        )
        XCTAssertEqual(result.text, "好的")
        XCTAssertEqual(result.model, "deepseek-reasoner")

        let entity = try XCTUnwrap(fake.lastConfig)
        XCTAssertEqual(entity.baseUrl, "https://api.example.com")
        XCTAssertTrue(entity.allowInsecureHttp, "允许 http 的开关必须传下去，否则本地服务会被拒")
        XCTAssertEqual(entity.customHeadersJson, #"{"X-A":"1"}"#)
        XCTAssertEqual(fake.lastAPIKey, "sk-1")
        XCTAssertEqual(fake.lastKind, UsageKind.extraction, "kind 必须与 UsageKind 用同一套字符串")
        XCTAssertEqual(fake.lastMessages.map(\.role), ["system", "user"])
        XCTAssertEqual(fake.lastMessages.map(\.content), ["你是整理器", "这是对话"])

        // 显式参数覆盖配置默认值；没覆盖的沿用配置。
        XCTAssertEqual(fake.lastParams.temperature, 0.9)
        XCTAssertEqual(fake.lastParams.maxOutputTokens, 64)
        XCTAssertEqual(fake.lastParams.topP, 0.1)
        XCTAssertEqual(fake.lastParams.frequencyPenalty, 0.5)
    }

    func testEmptyModelFallsBackToConfigModel() async throws {
        let fake = FakeClient()
        let adapter = CompletionClientAdapter(client: fake)
        _ = try await adapter.complete(
            config: config(), model: "  ",
            messages: [.user("x")], params: CompletionParams(), kind: .scoring
        )
        XCTAssertEqual(fake.lastModel, "deepseek-chat")
        // 没给显式参数时用配置里的值。
        XCTAssertEqual(fake.lastParams.temperature, 0.3)
    }

    /// 网络层的中文错误要变成服务层的 `CompletionFailure`（后台任务据此「跳过这一轮」），
    /// 而不是把 HTTP 状态码一路冒到界面。
    func testApiExceptionBecomesCompletionFailureWithChineseMessage() async {
        let fake = FakeClient()
        fake.result = .failure(ApiException(statusCode: 429, message: "请求过于频繁或额度不足（429）"))
        let adapter = CompletionClientAdapter(client: fake)
        do {
            _ = try await adapter.complete(
                config: config(), model: "m", messages: [.user("x")],
                params: CompletionParams(), kind: .consolidation
            )
            XCTFail("应当抛错")
        } catch let failure as CompletionFailure {
            XCTAssertEqual(failure.message, "请求过于频繁或额度不足（429）")
        } catch {
            XCTFail("错误类型不对：\(error)")
        }
    }

    func testEntityMappingKeepsSamplingParams() throws {
        let entity = CompletionClientAdapter.entity(from: config())
        XCTAssertEqual(entity.id, "cfg-1")
        XCTAssertEqual(entity.provider, .openai)
        XCTAssertEqual(entity.temperature, 0.3)
        XCTAssertEqual(entity.topP, 0.8)
        XCTAssertEqual(entity.maxOutputTokens, 256)
        XCTAssertNil(entity.apiKeyCipher, "iOS 的 Key 只在 Keychain/明文参数里，不该进密文字段")
    }
}
