import Foundation
import XCTest
@testable import YapKit

/// 配置推送（0.13 第二阶段）：连上**本机**服务端之后，把手机上的模型配置推过去。
///
/// 钉住四条：
/// 1. 只有回环地址才推（远程一律不发，连一个请求都没有）；
/// 2. 请求形状与 agentd 的 `LlmConfigRoutes` 逐字一致（`baseUrl/model/apiKey/provider`）；
/// 3. 失败只记日志（`Outcome.status == .failed`），绝不抛给界面；
/// 4. Key 不出现在任何日志/结果描述里。
final class ServerLlmConfigPushTests: XCTestCase {

    override func setUpWithError() throws {
        try super.setUpWithError()
        ServerURLProtocolStub.reset()
    }

    override func tearDownWithError() throws {
        ServerURLProtocolStub.reset()
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    private func client(host: String) -> ServerAPIClient {
        ServerAPIClient(
            config: ServerConfig(baseURL: URL(string: host)!, token: "secret-token"),
            session: ServerURLProtocolStub.makeSession()
        )
    }

    private var apiConfig: ApiConfigEntity {
        ApiConfigEntity(
            id: "config-1", name: "DeepSeek 官方", provider: .openai,
            baseUrl: "https://api.deepseek.com", model: "deepseek-chat",
            createdAt: 1, updatedAt: 2
        )
    }

    private static let okJSON = """
    {"ok":true,"id":"client-pushed","provider":"DEEPSEEK",
     "baseUrl":"https://api.deepseek.com","model":"deepseek-chat"}
    """

    /// 注意 `config` **不给默认值**：`nil` 表示「本机还没有配置」，不能悄悄回落到样本配置
    /// （否则「没有配置就不推」这条测不到）。
    private func pusher(
        host: String,
        config: ApiConfigEntity?,
        key: String? = "sk-secret",
        logs: ((String) -> Void)? = nil
    ) -> ServerLlmConfigPusher {
        ServerLlmConfigPusher(
            client: client(host: host),
            apiConfig: config,
            apiKey: key,
            log: logs ?? { _ in }
        )
    }

    // MARK: - 回环判定

    func testLoopbackDetectionAcceptsOnlyRealLoopbackHosts() {
        func loopback(_ host: String) -> Bool {
            ServerConfig(baseURL: URL(string: "http://\(host):8787")!).isLoopback
        }
        XCTAssertTrue(loopback("127.0.0.1"))
        XCTAssertTrue(loopback("127.5.6.7"), "整个 127/8 都是回环")
        XCTAssertTrue(loopback("localhost"))
        XCTAssertTrue(loopback("[::1]"))
        // 局域网与远程都不算本机：它们的 Key 是部署者的责任。
        XCTAssertFalse(loopback("192.168.1.10"))
        XCTAssertFalse(loopback("10.0.0.2"))
        XCTAssertFalse(loopback("172.16.0.1"))
        XCTAssertFalse(loopback("server.example.com"))
        XCTAssertFalse(loopback("my-mac.local"), "名字解析到哪台机器客户端无法验证，不当本机")
        XCTAssertFalse(
            ServerConfig(baseURL: URL(string: "https://api.example.com")!).isLoopback
        )
    }

    func testShouldPushRequiresUsableLoopbackConfig() {
        XCTAssertTrue(ServerLlmConfigPusher.shouldPush(
            config: ServerConfig(baseURL: URL(string: "http://127.0.0.1:8787")!)
        ))
        XCTAssertFalse(ServerLlmConfigPusher.shouldPush(
            config: ServerConfig(baseURL: URL(string: "http://127.0.0.1:8787")!, enabled: false)
        ))
        XCTAssertFalse(ServerLlmConfigPusher.shouldPush(
            config: ServerConfig(baseURL: URL(string: "https://remote.example.com")!)
        ))
        XCTAssertFalse(ServerLlmConfigPusher.shouldPush(config: ServerConfig()))
    }

    // MARK: - 推送

    func testPushSendsContractBodyToLoopbackServer() async throws {
        ServerURLProtocolStub.enqueue(.json(Self.okJSON))
        let outcome = await pusher(host: "http://127.0.0.1:8787", config: apiConfig).pushIfNeeded()
        XCTAssertEqual(outcome.status, .pushed)
        XCTAssertTrue(outcome.didPush)
        XCTAssertFalse(outcome.message.contains("sk-secret"), "结果里不能出现 Key：\(outcome.message)")

        let request = try XCTUnwrap(ServerURLProtocolStub.recordedRequests.first)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/llm-config")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer secret-token")
        let body = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try XCTUnwrap(ServerURLProtocolStub.recordedBodies.first))
                as? [String: Any]
        )
        XCTAssertEqual(body["baseUrl"] as? String, "https://api.deepseek.com")
        XCTAssertEqual(body["model"] as? String, "deepseek-chat")
        XCTAssertEqual(body["apiKey"] as? String, "sk-secret")
        // iOS 的 ProviderType 只有三种；DeepSeek 走 OpenAI 兼容，但服务端认 DEEPSEEK。
        XCTAssertEqual(body["provider"] as? String, "DEEPSEEK")
    }

    func testProviderMappingUsesGeminiAndClaudeVerbatim() async throws {
        var gemini = ApiConfigEntity(
            id: "c2", name: "Gemini", provider: .gemini,
            baseUrl: "https://generativelanguage.googleapis.com", model: "gemini-2.0-flash"
        )
        gemini.provider = .gemini
        ServerURLProtocolStub.enqueue(.json(Self.okJSON))
        _ = await pusher(host: "http://127.0.0.1:8787", config: gemini).pushIfNeeded()
        var body = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try XCTUnwrap(ServerURLProtocolStub.recordedBodies.last))
                as? [String: Any]
        )
        XCTAssertEqual(body["provider"] as? String, "GEMINI")

        var claude = gemini
        claude.provider = .claude
        claude.baseUrl = "https://api.anthropic.com"
        ServerURLProtocolStub.enqueue(.json(Self.okJSON))
        _ = await pusher(host: "http://127.0.0.1:8787", config: claude).pushIfNeeded()
        body = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try XCTUnwrap(ServerURLProtocolStub.recordedBodies.last))
                as? [String: Any]
        )
        XCTAssertEqual(body["provider"] as? String, "CLAUDE")
    }

    /// 远程服务器：一个请求都不发（Key 绝不能出现在公网服务的配置里）。
    func testRemoteServerIsNeverPushed() async throws {
        var logs: [String] = []
        let outcome = await pusher(host: "https://remote.example.com", config: apiConfig, logs: { logs.append($0) })
            .pushIfNeeded()
        XCTAssertEqual(outcome.status, .skippedRemote)
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty, "远程不该收到任何请求")
        XCTAssertTrue(logs.contains { $0.contains("远程") }, "跳过也要留一行日志：\(logs)")
    }

    /// 本机还没配好（没 Key / 没模型）：不发请求、不报错。
    func testMissingLocalConfigSkipsWithoutRequest() async throws {
        let noKey = await pusher(host: "http://127.0.0.1:8787", config: apiConfig, key: nil)
            .pushIfNeeded()
        XCTAssertEqual(noKey.status, .skippedNoConfig)
        let blankKey = await pusher(host: "http://127.0.0.1:8787", config: apiConfig, key: "   ")
            .pushIfNeeded()
        XCTAssertEqual(blankKey.status, .skippedNoConfig)
        let noConfig = await pusher(host: "http://127.0.0.1:8787", config: nil, key: "sk-1")
            .pushIfNeeded()
        XCTAssertEqual(noConfig.status, .skippedNoConfig)
        var emptyModel = apiConfig
        emptyModel.model = ""
        let missingModel = await pusher(host: "http://127.0.0.1:8787", config: emptyModel)
            .pushIfNeeded()
        XCTAssertEqual(missingModel.status, .skippedNoConfig)
        XCTAssertTrue(ServerURLProtocolStub.recordedRequests.isEmpty)
    }

    /// 服务端拒绝（远程部署的 403 / 老服务端 404）：只记失败，不抛。
    func testServerRejectionIsReportedAsFailureWithoutThrowing() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"code":"remote_config_disallowed","message":"远程部署不接受客户端推送的模型配置"}}"#,
            status: 403
        ))
        var logs: [String] = []
        let outcome = await pusher(host: "http://127.0.0.1:8787", config: apiConfig, logs: { logs.append($0) })
            .pushIfNeeded()
        XCTAssertEqual(outcome.status, .failed)
        XCTAssertTrue(outcome.message.contains("没推上去"))
        XCTAssertTrue(logs.contains { $0.contains("[llm-config]") })
        XCTAssertFalse(logs.joined().contains("sk-secret"), "日志里不能出现 Key")
    }

    /// 老服务端没有这个端点（404）：同样只是失败，不影响连接。
    func testMissingEndpointOnOlderServerIsFailureOnly() async throws {
        ServerURLProtocolStub.enqueue(.json(
            #"{"error":{"code":"not_found","message":"没有这个接口"}}"#, status: 404
        ))
        let outcome = await pusher(host: "http://127.0.0.1:8787", config: apiConfig).pushIfNeeded()
        XCTAssertEqual(outcome.status, .failed)
    }
}
